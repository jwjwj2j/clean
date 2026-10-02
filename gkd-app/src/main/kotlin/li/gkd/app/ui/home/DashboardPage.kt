package li.gkd.app.ui.home

import li.gkd.app.ui.component.GkPageBottomSpace
import li.gkd.app.MainViewModel

import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import li.gkd.app.text.UiStrings
import li.gkd.app.ui.component.GkTooltipIconButtonBox
import li.gkd.app.ui.icon.GkAnimatedRocketIcon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import li.gkd.app.MainActivity
import li.gkd.app.R
import li.gkd.app.data.subscription.SubscriptionState
import li.gkd.app.permission.PermissionStates
import li.gkd.app.priv.PrivilegeServiceStatus
import li.gkd.app.priv.privilegeContextFlow
import li.gkd.app.priv.privilegeServiceStatusFlow
import li.gkd.app.priv.uiAutomationFlow
import li.gkd.app.service.A11yService
import li.gkd.app.service.StatusService
import li.gkd.app.service.a11yPartDisabledFlow
import li.gkd.app.service.switchAutomatorService
import li.gkd.app.service.topAppIdFlow
import li.gkd.app.store.AppStore.actualA11yScopeAppList
import li.gkd.app.store.AppStore.actionCountFlow
import li.gkd.app.store.AppStore.storeFlow
import li.gkd.app.ui.AppConfigRoute
import li.gkd.app.ui.WebViewRoute
import li.gkd.app.ui.app.showAccessRestrictedSettingsDialog
import li.gkd.app.ui.style.itemHorizontalPadding
import li.gkd.app.ui.style.itemVerticalPadding
import li.gkd.app.ui.style.surfaceCardColors
import li.gkd.app.util.HOME_PAGE_URL
import li.gkd.app.ui.share.launchUi
import li.gkd.app.ui.share.statusText
import li.gkd.app.util.TimeUtils.throttle
import li.gkd.db.RuleGroupType
import li.gkd.app.ui.component.GkGroupNameText
import li.gkd.app.ui.component.GkIcon
import li.gkd.app.ui.component.GkIcons
import li.gkd.app.ui.component.GkSwitch
import li.gkd.app.ui.component.GkTopAppBar
import li.gkd.app.ui.component.rememberColumnScrollState
import li.gkd.app.ui.component.textSize

@Composable
fun useDashboardPage(): ScaffoldExt {
    val context = LocalActivity.current as MainActivity
    val mainVm = MainViewModel.requireCurrent()
    val vm = viewModel<DashboardVm>()
    val ruleSummary by SubscriptionState.ruleSummaryFlow.collectAsStateWithLifecycle()
    val actionCount by actionCountFlow.collectAsStateWithLifecycle()
    val subsStatus = ruleSummary.statusText(actionCount)
    val store by storeFlow.collectAsStateWithLifecycle()
    val privilegeContext by privilegeContextFlow.collectAsStateWithLifecycle()
    val privilegeServiceStatus by privilegeServiceStatusFlow.collectAsStateWithLifecycle()
    val automatorMode by mainVm.automatorModeFlow.collectAsStateWithLifecycle()
    val pageScrollState = rememberColumnScrollState()
    val scrollBehavior = pageScrollState.scrollBehavior
    val scrollState = pageScrollState.scrollState
    ResetPageScrollOnRequest(BottomNavItem.Dashboard, pageScrollState::resetScrollAndAwait)
    return ScaffoldExt(
        navItem = BottomNavItem.Dashboard,
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            GkTopAppBar(scrollBehavior = scrollBehavior, title = {
                Text(
                    text = stringResource(R.string.app_name)
                )
            }, actions = {
                // CLEAN：原右上角的「特权服务」状态图标（跳转 PrivilegeServicePage）已移除。
                // 特权能力按 docs/09 §6.3 封装：实现保留，但消费者看不到任何入口。
            })
        }) { contentPadding ->
        val a11yRunning by A11yService.isRunning.collectAsStateWithLifecycle()
        val manageRunning by StatusService.isRunning.collectAsStateWithLifecycle()
        val writeSecureSettings by PermissionStates.writeSecureSettings.stateFlow.collectAsStateWithLifecycle()

        Column(
            modifier = Modifier
                .verticalScroll(scrollState)
                .padding(contentPadding)
                .padding(horizontal = itemHorizontalPadding),
            verticalArrangement = Arrangement.spacedBy(itemHorizontalPadding / 2)
        ) {
            if (PermissionStates.appOpsRestrictedFlow.collectAsStateWithLifecycle().value) {
                // CLEAN：原卡片点击会跳转 PrivilegeServicePage 去借特权解除受限；
                // 特权入口已封装移除，这里改为纯提示，用户需在系统设置中自行处理。
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(itemVerticalPadding),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        GkIcon(imageVector = GkIcons.WarningAmber)
                        Text(
                            modifier = Modifier.weight(1f),
                            text = UiStrings.permission_restricted_privilege_notice,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
            if (store.useA11y || actualA11yScopeAppList.contains(topAppIdFlow.collectAsStateWithLifecycle().value)) {
                ServiceStatusCard(
                    subtitle = if (a11yRunning) {
                        UiStrings.a11y_running
                    } else if (mainVm.a11yServiceEnabledFlow.collectAsStateWithLifecycle().value) {
                        UiStrings.a11y_fault
                    } else if (writeSecureSettings) {
                        if (store.enableAutomator && a11yPartDisabledFlow.collectAsStateWithLifecycle().value) {
                            UiStrings.a11y_partially_disabled
                        } else {
                            UiStrings.a11y_stopped
                        }
                    } else {
                        UiStrings.a11y_unauthorized
                    },
                    checked = a11yRunning,
                    onCheckedChange = { newEnabled ->
                        if (newEnabled && !PermissionStates.writeSecureSettings.value) {
                            // CLEAN：原跳转「工作模式」页说明受限设置，该页已下线；
                            // 改为复用应用级「受限设置」提示对话框。
                            showAccessRestrictedSettingsDialog()
                        } else {
                            switchAutomatorService()
                        }
                    },
                    mode = automatorMode.label,
                )
            }
            // CLEAN：原 else 分支展示「自动化模式」（特权服务）状态卡片，并会在未连接时跳转
            // PrivilegeServicePage。特权能力已按 docs/09 §6.3 封装 —— 保留实现但不给用户入口，
            // 因此自动化模式在消费级产品中不可达，这里只保留无障碍服务状态卡片。

            PageSwitchItemCard(
                imageVector = GkIcons.Notifications,
                title = UiStrings.persistent_notification,
                subtitle = UiStrings.status_statistics_description,
                checked = manageRunning && store.enableStatusService,
                onCheckedChange = {
                    if (it) {
                        vm.scope.launchUi {
                            mainVm.enableStatusService()
                        }
                    } else {
                        vm.stopStatusService()
                    }
                },
            )

            val latestRecord by SubscriptionState.latestRecordFlow.collectAsStateWithLifecycle()
            val latestRecordDesc by SubscriptionState.latestRecordDescFlow.collectAsStateWithLifecycle()
            TriggerOverviewCard(
                subsStatus = subsStatus,
                latestRecordDesc = latestRecordDesc,
                latestRecordIsGlobal = latestRecord?.groupType == RuleGroupType.Global,
                onOpenLatestRecord = {
                    latestRecord?.let {
                        mainVm.navigatePage(AppConfigRoute(appId = it.appId, focusLog = it))
                    }
                },
            )

            // CLEAN：原「活动记录」卡片依赖 ActivityService（悬浮调试窗），已随其下线；
            // 原「了解 GKD / 打开文档」卡片指向 gkd.li 的 WebView 入口，也已移除。
            GkPageBottomSpace()
        }
    }
}


@Composable
private fun PageItemCard(
    imageVector: ImageVector,
    title: String,
    subtitle: String,
    onClickLabel: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                this.onClick(label = onClickLabel, action = null)
            },
        shape = MaterialTheme.shapes.large,
        colors = surfaceCardColors,
        onClick = throttle(fn = onClick)
    ) {
        IconTextCard(
            imageVector = imageVector,
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PageSwitchItemCard(
    imageVector: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val onClick = throttle { onCheckedChange(!checked) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                this.onClick(label = UiStrings.item_toggle_description(title), action = null)
            },
        shape = MaterialTheme.shapes.large,
        colors = surfaceCardColors,
        onClick = onClick,
    ) {
        IconTextCard(
            imageVector = imageVector,
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            GkSwitch(
                checked = checked,
                onCheckedChange = null,
            )
        }
    }
}

@Composable
private fun ServiceStatusCard(
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    mode: String,
    onModeClick: (() -> Unit)? = null,
) {
    val onStatusClick = throttle { onCheckedChange(!checked) }
    // CLEAN：工作模式页面已随技术面收口下线，模式行改为只读展示（不再可点）
    val modeRowClickModifier = if (onModeClick == null) {
        Modifier
    } else {
        Modifier.clickable(
            onClickLabel = UiStrings.work_mode_open,
            onClick = throttle(onModeClick),
        )
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = surfaceCardColors,
    ) {
        IconTextCard(
            imageVector = GkIcons.Memory,
            modifier = Modifier
                .semantics(mergeDescendants = true) {}
                .clickable(
                    onClickLabel = UiStrings.service_state_toggle,
                    onClick = onStatusClick,
                ),
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = UiStrings.service_state,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            GkSwitch(
                checked = checked,
                onCheckedChange = null,
            )
        }
        HorizontalDivider(
            modifier = Modifier.padding(
                start = itemVerticalPadding + 40.dp + itemHorizontalPadding,
                end = itemVerticalPadding,
            ),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {}
                .then(modeRowClickModifier)
                .padding(
                    start = itemVerticalPadding,
                    end = itemVerticalPadding,
                    top = 10.dp,
                    bottom = 10.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GkIcon(
                imageVector = GkIcons.AutoMode,
                modifier = Modifier
                    .padding(horizontal = 10.dp)
                    .size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                contentDescription = null,
            )
            Spacer(modifier = Modifier.width(itemHorizontalPadding))
            Text(
                text = UiStrings.work_mode_title,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = mode,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            GkIcon(
                imageVector = GkIcons.KeyboardArrowRight,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                contentDescription = null,
            )
        }
    }
}

@Composable
private fun IconTextCard(
    imageVector: ImageVector,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(itemVerticalPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GkIcon(
            imageVector = imageVector,
            modifier = Modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(8.dp)
                .size(24.dp),
            tint = MaterialTheme.colorScheme.primary,
            contentDescription = null,
        )
        Spacer(modifier = Modifier.width(itemHorizontalPadding))
        content()
    }
}

@Composable
private fun TriggerOverviewCard(
    subsStatus: String,
    latestRecordDesc: String?,
    latestRecordIsGlobal: Boolean,
    onOpenActionLog: (() -> Unit)? = null,
    onOpenLatestRecord: () -> Unit,
) {
    // CLEAN：操作日志页面已下线，此处卡片改为只读展示
    val logClickModifier = if (onOpenActionLog == null) {
        Modifier
    } else {
        Modifier.clickable(
            onClickLabel = UiStrings.action_log_open,
            onClick = throttle(onOpenActionLog),
        )
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = surfaceCardColors,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {}
                .then(logClickModifier)
                .padding(
                    start = itemVerticalPadding,
                    end = itemVerticalPadding,
                    top = itemVerticalPadding,
                    bottom = itemVerticalPadding / 2
                ), verticalAlignment = Alignment.CenterVertically
        ) {
            GkIcon(
                imageVector = GkIcons.History,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(8.dp)
                    .size(24.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(itemHorizontalPadding))
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = UiStrings.action_log_title,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = UiStrings.action_log_description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            GkIcon(
                imageVector = GkIcons.KeyboardArrowRight,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                contentDescription = null,
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = itemVerticalPadding)
        ) {
            AnimatedVisibility(subsStatus.isNotEmpty()) {
                Text(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    text = subsStatus,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (latestRecordDesc != null) {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .clip(MaterialTheme.shapes.extraSmall)
                        .clickable(
                            onClickLabel = UiStrings.app_rule_summary_open,
                            onClick = throttle(onOpenLatestRecord),
                        )
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp)
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                    ) {
                        GkGroupNameText(
                            modifier = Modifier.fillMaxWidth(),
                            preText = UiStrings.action_log_recent_prefix,
                            isGlobal = latestRecordIsGlobal,
                            text = latestRecordDesc,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    GkIcon(
                        imageVector = GkIcons.KeyboardArrowRight,
                        modifier = Modifier.textSize(style = MaterialTheme.typography.bodyMedium),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(modifier = Modifier.height(itemVerticalPadding))
        }
    }
}
