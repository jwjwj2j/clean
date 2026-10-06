package li.gkd.app.ui.home

import li.gkd.app.ui.style.lineGap
import li.gkd.app.ui.component.GkSettingItem
import com.clean.click.activation.activationErrorText
import com.clean.click.activation.ActivationManager
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import li.gkd.app.ui.component.GkPageBottomSpace
import li.gkd.app.MainViewModel

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import li.gkd.app.text.UiStrings
import li.gkd.app.store.AppStore
import li.gkd.app.store.AppStore.storeFlow
import li.gkd.app.platform.service.ServiceController
import li.gkd.app.ui.style.titleItemPadding
import li.gkd.app.util.DarkThemeOption
import li.gkd.app.util.findOption
import li.gkd.app.ui.share.launchUi
import li.gkd.app.ui.component.GkTextMenu
import li.gkd.app.ui.component.GkTextSwitch
import li.gkd.app.ui.component.GkTopAppBar
import li.gkd.app.ui.component.rememberColumnScrollState

@Composable
fun useSettingsPage(): ScaffoldExt {
    val mainVm = MainViewModel.requireCurrent()
    val vm = viewModel<SettingsVm>()
    val store by storeFlow.collectAsStateWithLifecycle()
    val actionScope = vm.scope
    // CLEAN：原「备份与恢复」（导入 zip / 导出 zip / 存到下载）与整个备份对话框已移除。
    // 备份格式（BackupFormat / BackupManager / BackupArchiveReader）与 OpenFileActivity 导入入口
    // 一并删除。SettingsRepository.withBackupRestore 因其并发与原子性测试仍有价值而保留，
    // 分享 APK 用到的 SystemDownloads / ExportFileNames 也保留。

    val pageScrollState = rememberColumnScrollState()
    val scrollBehavior = pageScrollState.scrollBehavior
    val scrollState = pageScrollState.scrollState
    ResetPageScrollOnRequest(BottomNavItem.Settings, pageScrollState::resetScrollAndAwait)
    return ScaffoldExt(
        navItem = BottomNavItem.Settings,
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            GkTopAppBar(
                scrollBehavior = scrollBehavior,
                title = {
                    Text(
                        text = BottomNavItem.Settings.label,
                    )
                },
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .verticalScroll(scrollState)
                .padding(contentPadding)
        ) {

            Text(
                text = UiStrings.settings_general,
                modifier = Modifier.titleItemPadding(showTop = false),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            // CLEAN：原「点击提示文案」与「通知文案」设置项指向的定制页面已随技术面收口下线，
            // 两项一并移除以保持设置页只保留消费者真正会调的开关。

            // CLEAN 补充：规则匹配总开关（enableMatch）在上游只存在于已删除的订阅板块里，
            // 消费者将失去唯一的应用内控制入口，因此在这里补回。这也是 MatchTileService
            // 与状态通知「规则匹配已暂停」对应的那个开关。
            GkTextSwitch(
                title = UiStrings.rule_matching_label,
                subtitle = UiStrings.rule_matching_description,
                checked = store.enableMatch,
                onCheckedChange = { enabled ->
                    AppStore.updateSettings { it.copy(enableMatch = enabled) }
                },
            )

            GkTextSwitch(
                title = UiStrings.hide_from_recents,
                subtitle = UiStrings.hide_from_recents_description,
                checked = store.excludeFromRecents,
                onCheckedChange = { enabled ->
                    actionScope.launchUi {
                        if (enabled) {
                            if (!mainVm.dialogRequests.confirm(
                                title = UiStrings.hide_from_recents,
                                text = UiStrings.hide_from_recents_warning,
                                confirmText = UiStrings.action_continue,
                            )) return@launchUi
                        }
                        vm.setExcludeFromRecents(enabled)
                    }
                })

            // CLEAN：原「局部禁用无障碍」（enableBlockA11yAppList）、其设置向导与白名单页面
            // 属于技术面，已随相关页面下线。

            // CLEAN：常驻通知开关从首页移到此处。首页改为数据仪表盘后不再有它的位置，
            // 但它是 ServiceController.setStatusEnabled 的**唯一用户可见入口**，
            // 直接删掉等于丢功能，因此保留在设置页的「通用」分组里。
            GkTextSwitch(
                title = UiStrings.persistent_notification,
                subtitle = UiStrings.persistent_notification_description,
                checked = store.enableStatusService,
                onCheckedChange = { enabled ->
                    actionScope.launchUi {
                        if (enabled) {
                            // 需要「特殊用途前台服务」与「通知」两项权限；
                            // MainViewModel.enableStatusService() 内部负责申请。
                            mainVm.enableStatusService()
                        } else {
                            ServiceController.setStatusEnabled(false)
                        }
                    }
                },
            )

            // CLEAN：激活码入口。试用期内用户可在这里提前输入激活码取消 6 小时限制；
            // 试用结束后门禁本身会拦到激活页，但设置里保留入口便于随时补激活。
            val activation by ActivationManager.snapshotFlow.collectAsStateWithLifecycle()
            var showActivationDialog by remember { mutableStateOf(false) }
            GkSettingItem(
                title = UiStrings.activation_entry_title,
                subtitle = when {
                    activation.activated && !activation.trialActive ->
                        UiStrings.activation_entry_subtitle_activated
                    activation.trialActive -> UiStrings.activation_entry_subtitle_active
                    else -> UiStrings.activation_entry_subtitle_expired
                },
                onClick = { showActivationDialog = true },
            )
            if (showActivationDialog) {
                ActivationEntryDialog(onDismiss = { showActivationDialog = false })
            }

            Text(
                text = UiStrings.settings_appearance,
                modifier = Modifier.titleItemPadding(),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )

            GkTextMenu(
                title = UiStrings.theme_mode,
                option = DarkThemeOption.objects.findOption(store.enableDarkTheme),
                onOptionChange = {
                    vm.setDarkTheme(it.value)
                }
            )

            // CLEAN：原「动态配色」（enableDynamicColor）设置项已移除，
            // 应用固定使用 CLEAN 自己的配色方案，不再随壁纸取色。

            // CLEAN：原「其他」分组与其中的「关于」入口已一并移除
            // （AboutRoute / AboutPage / AboutDialogs 已随之下线）。

            GkPageBottomSpace()
        }
    }
}

/**
 * 设置页的激活码输入框（试用期内也可用）。
 *
 * 与全屏的 [ActivationPage] 共用同一套提交与错误文案，避免两处逻辑漂移。
 * 激活成功后 [ActivationManager.isActivatedFlow] 变为 true，门禁自动放行，无需额外通知。
 */
@Composable
private fun ActivationEntryDialog(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(UiStrings.activation_title) },
        text = {
            Column {
                Text(
                    text = UiStrings.activation_window_hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(lineGap))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text(UiStrings.activation_input_label) },
                    singleLine = true,
                    enabled = !busy,
                )
                message?.let { text ->
                    Spacer(Modifier.height(lineGap))
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && input.isNotBlank(),
                onClick = {
                    busy = true
                    message = null
                    scope.launch {
                        when (val outcome = ActivationManager.activate(input)) {
                            is ActivationManager.Outcome.Activated -> onDismiss()
                            is ActivationManager.Outcome.Rejected ->
                                message = activationErrorText(outcome)
                            is ActivationManager.Outcome.InvalidInput ->
                                message = outcome.message
                        }
                        busy = false
                    }
                },
            ) { Text(UiStrings.activation_submit) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(UiStrings.activation_got_it)
            }
        },
    )
}
