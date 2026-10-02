package li.gkd.app.ui.home

import li.gkd.app.ui.component.GkPageBottomSpace
import li.gkd.app.MainViewModel

import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import li.gkd.app.text.UiStrings
import li.gkd.app.MainActivity
import li.gkd.app.META
import li.gkd.app.data.subscription.SubscriptionState
import li.gkd.app.notif.replaceNotificationTemplate
import li.gkd.app.store.AppStore
import li.gkd.app.store.AppStore.actionCountFlow
import li.gkd.app.ui.share.statusText
import li.gkd.app.store.AppStore.storeFlow
import li.gkd.app.feature.settings.AboutRoute
import li.gkd.app.ui.style.titleItemPadding
import li.gkd.app.util.AndroidTarget
import li.gkd.app.util.DarkThemeOption
import li.gkd.app.util.findOption
import li.gkd.app.util.FolderUtils
import li.gkd.app.ui.share.launchUi
import li.gkd.app.util.ToastUtils.toast
import li.gkd.app.ui.component.GkSettingItem
import li.gkd.app.ui.component.GkTextListDialog
import li.gkd.app.ui.component.GkTextMenu
import li.gkd.app.ui.component.GkTextSwitch
import li.gkd.app.ui.component.GkTopAppBar
import li.gkd.app.ui.component.rememberColumnScrollState

private const val ZIP_MIME_TYPE = "application/zip"

@Composable
fun useSettingsPage(): ScaffoldExt {
    val mainVm = MainViewModel.requireCurrent()
    val context = LocalActivity.current as MainActivity
    val vm = viewModel<SettingsVm>()
    val store by storeFlow.collectAsStateWithLifecycle()
    val actionScope = vm.scope
    var showBackupDialog by rememberSaveable { mutableStateOf(false) }
    var showExportBackupDialog by rememberSaveable { mutableStateOf(false) }

    if (showBackupDialog) {
        GkTextListDialog(
            onDismiss = { showBackupDialog = false },
            textList = listOf(
                UiStrings.backup_import_label to {
                    actionScope.launchUi {
                        val uri = mainVm.activityResults.openDocument(ZIP_MIME_TYPE)
                        if (uri == null) {
                            toast(UiStrings.file_not_selected)
                            return@launchUi
                        }
                        vm.importBackup(uri)
                    }
                },
                UiStrings.backup_export to {
                    showExportBackupDialog = true
                },
            )
        )
    }
    if (showExportBackupDialog) {
        GkTextListDialog(
            onDismiss = { showExportBackupDialog = false },
            textList = listOf(
                UiStrings.action_share to {
                    actionScope.launchUi {
                        val file = vm.exportBackup()
                        context.shareFile(file, UiStrings.backup_share)
                    }
                },
                UiStrings.action_save_to_downloads to {
                    actionScope.launchUi {
                        FolderUtils.withTemporaryZip(
                            create = vm::exportBackup,
                            delete = FolderUtils::deleteSharedFile,
                        ) { file ->
                            context.saveFileToDownloads(file)
                        }
                    }
                },
            )
        )
    }

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

            if (AndroidTarget.S) {
                GkTextSwitch(
                    title = UiStrings.dynamic_colors,
                    checked = store.enableDynamicColor,
                    onCheckedChange = {
                        vm.setDynamicColor(it)
                    }
                )
            }

            Text(
                text = UiStrings.settings_other,
                modifier = Modifier.titleItemPadding(),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )

            // CLEAN：原「高级设置」入口已移除（HTTP、悬浮窗、GitHub cookie 等技术开关随页面一并下线）
            GkSettingItem(title = UiStrings.backup_restore, onClick = {
                showBackupDialog = true
            })

            GkSettingItem(title = UiStrings.about_title, onClick = {
                mainVm.navigatePage(AboutRoute)
            })

            GkPageBottomSpace()
        }
    }
}
