package li.gkd.app.ui.home

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
