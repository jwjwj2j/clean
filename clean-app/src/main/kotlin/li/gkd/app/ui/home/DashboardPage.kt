package li.gkd.app.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import li.gkd.app.MainViewModel
import li.gkd.app.R
import li.gkd.app.data.appinfo.AppInfoRepository
import li.gkd.app.data.subscription.SubscriptionState
import li.gkd.app.domain.rule.RuleSummary
import li.gkd.app.permission.PermissionStates
import li.gkd.app.service.A11yService
import li.gkd.app.service.a11yPartDisabledFlow
import li.gkd.app.service.switchAutomatorService
import li.gkd.app.service.topAppIdFlow
import li.gkd.app.store.AppStore.actionCountFlow
import li.gkd.app.feature.log.ActionLogRoute
import li.gkd.app.store.AppStore.actualA11yScopeAppList
import li.gkd.app.store.AppStore.storeFlow
import li.gkd.app.text.UiStrings
import li.gkd.app.ui.AppConfigRoute
import li.gkd.app.ui.app.showAccessRestrictedSettingsDialog
import li.gkd.app.ui.component.GkIcon
import li.gkd.app.ui.component.GkIcons
import li.gkd.app.ui.component.GkPageBottomSpace
import li.gkd.app.ui.component.GkSwitch
import li.gkd.app.ui.component.GkTopAppBar
import li.gkd.app.ui.component.rememberColumnScrollState
import li.gkd.app.ui.style.cardGap
import li.gkd.app.ui.style.cardHorizontalPadding
import li.gkd.app.ui.style.cardPadding
import li.gkd.app.ui.style.heroSwitchHeight
import li.gkd.app.ui.style.heroSwitchWidth
import li.gkd.app.ui.style.iconSize
import li.gkd.app.ui.style.iconTextGap
import li.gkd.app.ui.style.itemVerticalPadding
import li.gkd.app.ui.style.lineGap
import li.gkd.app.ui.style.pagePadding
import li.gkd.app.ui.style.pillCorner
import li.gkd.app.ui.style.pressDurationMs
import li.gkd.app.ui.style.pressScale
import li.gkd.app.ui.style.recordCardHeight
import li.gkd.app.ui.style.statusPillHeight
import li.gkd.app.ui.style.surfaceCardColors
import li.gkd.app.util.TimeUtils.throttle

// ------------------------------------------------------------------ 文案
// CLEAN：设计稿 §1 新增的文案在本文件内以私有常量给出。
// UiStrings 由 res/values/strings.xml 生成，而本次改造限定只允许修改本文件，
// 不能新增字符串资源键；能命中既有键的文案仍然复用 UiStrings。
private const val labelActionCount = UiStrings.dashboard_action_count_label
private const val labelServiceRunning = UiStrings.dashboard_status_running
private const val labelServiceStopped = UiStrings.dashboard_status_stopped
private const val labelGlobalCount = UiStrings.dashboard_stat_global
private const val labelAppCount = UiStrings.dashboard_stat_app
private const val labelRuleCount = UiStrings.dashboard_stat_rule
private const val labelRecentTrigger = UiStrings.dashboard_recent

@Composable
fun useDashboardPage(): ScaffoldExt {
    val mainVm = MainViewModel.requireCurrent()
    val ruleSummary by SubscriptionState.ruleSummaryFlow.collectAsStateWithLifecycle()
    val actionCount by actionCountFlow.collectAsStateWithLifecycle()
    val store by storeFlow.collectAsStateWithLifecycle()
    val automatorMode by mainVm.automatorModeFlow.collectAsStateWithLifecycle()
    val a11yRunning by A11yService.isRunning.collectAsStateWithLifecycle()
    val writeSecureSettings by PermissionStates.writeSecureSettings.stateFlow.collectAsStateWithLifecycle()
    val a11yServiceEnabled by mainVm.a11yServiceEnabledFlow.collectAsStateWithLifecycle()
    val a11yPartDisabled by a11yPartDisabledFlow.collectAsStateWithLifecycle()
    val topAppId by topAppIdFlow.collectAsStateWithLifecycle()
    val appInfoMap by AppInfoRepository.appInfoMapFlow.collectAsStateWithLifecycle()
    val latestRecord by SubscriptionState.latestRecordFlow.collectAsStateWithLifecycle()
    // CLEAN：「最近触发」的文案（应用名/规则名），来自最近一条触发记录
    val recentTriggerDesc by SubscriptionState.latestRecordDescFlow.collectAsStateWithLifecycle()
    val pageScrollState = rememberColumnScrollState()
    val scrollBehavior = pageScrollState.scrollBehavior
    val scrollState = pageScrollState.scrollState
    ResetPageScrollOnRequest(BottomNavItem.Dashboard, pageScrollState::resetScrollAndAwait)
    // 运行状态判定沿用原「服务状态」卡片的既有逻辑：胶囊与开关都由无障碍服务的运行状态决定，
    // 副标题（故障 / 局部关闭 / 未授权 / 已关闭）保留为胶囊的无障碍描述。
    val serviceDetail = if (a11yRunning) {
        UiStrings.a11y_running
    } else if (a11yServiceEnabled) {
        UiStrings.a11y_fault
    } else if (writeSecureSettings) {
        if (store.enableAutomator && a11yPartDisabled) {
            UiStrings.a11y_partially_disabled
        } else {
            UiStrings.a11y_stopped
        }
    } else {
        UiStrings.a11y_unauthorized
    }
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
        Column(
            modifier = Modifier
                .verticalScroll(scrollState)
                .padding(contentPadding),
        ) {
            if (PermissionStates.appOpsRestrictedFlow.collectAsStateWithLifecycle().value) {
                // CLEAN：原卡片点击会跳转 PrivilegeServicePage 去借特权解除受限；
                // 特权入口已封装移除，这里改为纯提示，用户需在系统设置中自行处理。
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = pagePadding),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(itemVerticalPadding),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(iconTextGap),
                    ) {
                        GkIcon(imageVector = GkIcons.WarningAmber)
                        Text(
                            modifier = Modifier.weight(1f),
                            text = UiStrings.permission_restricted_privilege_notice,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
                Spacer(Modifier.height(cardGap))
            }

            DashboardHeroSection(
                actionCount = actionCount,
                running = a11yRunning,
                serviceDetail = serviceDetail,
                modeLabel = automatorMode.label,
                // 与服务开关一致：仅在无障碍工作模式（或前台应用处于无障碍范围内）下提供开关。
                showSwitch = store.useA11y || actualA11yScopeAppList.contains(topAppId),
                onCheckedChange = { newEnabled ->
                    if (newEnabled && !PermissionStates.writeSecureSettings.value) {
                        // CLEAN：原跳转「工作模式」页说明受限设置，该页已下线；
                        // 改为复用应用级「受限设置」提示对话框。
                        showAccessRestrictedSettingsDialog()
                    } else {
                        switchAutomatorService()
                    }
                },
            )
            Spacer(Modifier.height(cardGap))

            DashboardStatRow(ruleSummary = ruleSummary)
            // 设计稿 §1.2：副数据区卡片下方留 16dp 间距（令牌 cardGap 12dp + lineGap 4dp）。
            Spacer(Modifier.height(cardGap + lineGap))

            val record = latestRecord
            TriggerRecordCard(
                // CLEAN：触发记录页已恢复上线（feature/log/ActionLogPage.kt），
                // 因此整卡始终可点，不再受「有没有最近一条记录」限制。
                // 该页会列出每条记录对应的规则状态原因（处于匹配延迟 / 超出匹配时间 /
                // 达到最大执行次数 …），这正是「规则为什么没生效」的答案所在。
                onOpen = {
                    mainVm.navigatePage(ActionLogRoute())
                },
            )
            Spacer(Modifier.height(cardGap))

            RecentTriggerCard(
                // CLEAN 修复：原先接的是 topAppIdFlow，而它只在
                // enableBlockA11yAppList（默认 false）或无障碍范围白名单非空时才更新，
                // 是「局部禁用无障碍」功能的遗留数据源 —— 默认配置下恒为初始值 ""，
                // 因此这张卡片永远显示「暂无数据」。
                // 改用 latestRecordDescFlow：由最近一条触发记录（ActionLog）解析出
                // 「应用名/规则名」，正是设计稿 §1.4 要的内容。
                appName = recentTriggerDesc.orEmpty(),
                onOpen = latestRecord?.let { record ->
                    { mainVm.navigatePage(AppConfigRoute(appId = record.appId)) }
                },
            )

            GkPageBottomSpace()
        }
    }
}

// ------------------------------------------------------------------ 主视觉（设计稿 §1.1）

@Composable
private fun DashboardHeroSection(
    actionCount: Long,
    running: Boolean,
    serviceDetail: String,
    modeLabel: String,
    showSwitch: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    // 设计稿 §1.1：主视觉占屏幕上半部分，数字与说明整体垂直居中，左右留白至少 24dp
    //（令牌组合 pagePadding 16dp + iconTextGap 8dp）。
    val heroHeight = (LocalConfiguration.current.screenHeightDp / 2).dp
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = heroHeight)
            .padding(horizontal = pagePadding + iconTextGap),
    ) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = actionCount.toString(),
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            // 口径修正：actionCountFlow 是持久化累计计数（SettingsRepository.incrementActionCount
            // 只做 it + 1，从不按天重置），因此这里必须写「累计触发」，不能标成「今日触发」。
            Text(
                text = labelActionCount,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            modifier = Modifier.align(Alignment.TopEnd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(iconTextGap),
        ) {
            ServiceStatusPill(
                running = running,
                serviceDetail = serviceDetail,
                modeLabel = modeLabel,
            )
            if (showSwitch) {
                GkSwitch(
                    checked = running,
                    onCheckedChange = onCheckedChange,
                    modifier = Modifier.size(heroSwitchWidth, heroSwitchHeight),
                )
            }
        }
    }
}

@Composable
private fun ServiceStatusPill(
    running: Boolean,
    serviceDetail: String,
    modeLabel: String,
) {
    Box(
        modifier = Modifier
            .height(statusPillHeight)
            .clip(RoundedCornerShape(pillCorner))
            .background(
                if (running) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                }
            )
            .padding(horizontal = cardHorizontalPadding)
            .semantics(mergeDescendants = true) {
                // 可见文案只有运行中 / 已停止；原来的详细状态与工作模式信息保留给无障碍播报。
                contentDescription = serviceDetail
                stateDescription = modeLabel
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (running) labelServiceRunning else labelServiceStopped,
            style = MaterialTheme.typography.labelMedium,
            color = if (running) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

// ------------------------------------------------------------------ 副数据区（设计稿 §1.2）

@Composable
private fun DashboardStatRow(ruleSummary: RuleSummary) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = pagePadding),
        horizontalArrangement = Arrangement.spacedBy(cardGap),
    ) {
        DashboardStatCard(
            modifier = Modifier.weight(1f),
            value = ruleSummary.globalRules.size.toString(),
            label = labelGlobalCount,
        )
        DashboardStatCard(
            modifier = Modifier.weight(1f),
            value = ruleSummary.appSize.toString(),
            label = labelAppCount,
        )
        DashboardStatCard(
            modifier = Modifier.weight(1f),
            value = ruleSummary.appIdToRules.values.sumOf { it.size }.toString(),
            label = labelRuleCount,
        )
    }
}

@Composable
private fun DashboardStatCard(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        colors = surfaceCardColors,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = cardHorizontalPadding, vertical = itemVerticalPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ------------------------------------------------------------------ 触发记录（设计稿 §1.3）

@Composable
private fun TriggerRecordCard(onOpen: (() -> Unit)?) {
    val interactionSource = remember { MutableInteractionSource() }
    if (onOpen == null) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = pagePadding)
                .height(recordCardHeight),
            shape = MaterialTheme.shapes.medium,
            colors = surfaceCardColors,
        ) {
            TriggerRecordCardContent()
        }
    } else {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = pagePadding)
                .height(recordCardHeight)
                .pressScaleEffect(interactionSource)
                .semantics {
                    onClick(label = UiStrings.action_log_open, action = null)
                },
            shape = MaterialTheme.shapes.medium,
            colors = surfaceCardColors,
            onClick = throttle(onOpen),
            interactionSource = interactionSource,
        ) {
            TriggerRecordCardContent()
        }
    }
}

@Composable
private fun TriggerRecordCardContent() {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = cardPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GkIcon(
            imageVector = GkIcons.History,
            modifier = Modifier.size(iconSize),
            tint = MaterialTheme.colorScheme.primary,
            contentDescription = null,
        )
        Spacer(Modifier.width(iconTextGap))
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = UiStrings.action_log_title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = UiStrings.action_log_description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(iconTextGap))
        GkIcon(
            imageVector = GkIcons.KeyboardArrowRight,
            modifier = Modifier.size(iconSize),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            contentDescription = null,
        )
    }
}

// ------------------------------------------------------------------ 最近触发（设计稿 §1.4）

@Composable
private fun RecentTriggerCard(
    appName: String,
    onOpen: (() -> Unit)?,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = pagePadding),
        shape = MaterialTheme.shapes.medium,
        colors = surfaceCardColors,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(cardPadding),
        ) {
            Text(
                text = labelRecentTrigger,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(lineGap))
            val rowModifier = if (onOpen == null) {
                Modifier
            } else {
                Modifier
                    .pressScaleEffect(interactionSource)
                    .clip(MaterialTheme.shapes.small)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = ripple(),
                        onClickLabel = UiStrings.app_rule_summary_open,
                        onClick = throttle(onOpen),
                    )
            }
            Row(
                modifier = rowModifier
                    .fillMaxWidth()
                    .padding(vertical = lineGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = appName.ifEmpty { UiStrings.data_empty },
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (appName.isEmpty()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                if (onOpen != null) {
                    GkIcon(
                        imageVector = GkIcons.KeyboardArrowRight,
                        modifier = Modifier.size(iconSize),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        contentDescription = null,
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 交互

/**
 * 设计稿 §4.4：点击缩放到 `pressScale`(0.98)，时长 `pressDurationMs`(120)。
 *
 * 只做视觉过渡：不改变可点性、不加点击等待，也不在动画期间禁用控件（AGENTS.md「UI 交互与过渡动画」）。
 */
@Composable
private fun Modifier.pressScaleEffect(interactionSource: MutableInteractionSource): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressScale else 1f,
        animationSpec = tween(durationMillis = pressDurationMs),
        label = "pressScale",
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}
