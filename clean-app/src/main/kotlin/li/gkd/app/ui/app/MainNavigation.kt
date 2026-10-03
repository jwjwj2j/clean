package li.gkd.app.ui.app

import li.gkd.app.MainViewModel

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import li.gkd.app.feature.inspect.NodeInspectPage
import li.gkd.app.feature.inspect.NodeInspectRoute
import li.gkd.app.feature.log.ActionLogPage
import li.gkd.app.feature.log.ActionLogRoute
import li.gkd.app.feature.subscription.RuleExcludeEditorPage
import li.gkd.app.feature.subscription.RuleExcludeEditorRoute
import li.gkd.app.feature.subscription.SubsAppGroupListPage
import li.gkd.app.feature.subscription.SubsAppGroupListRoute
import li.gkd.app.feature.subscription.SubsCategoryGroupPage
import li.gkd.app.feature.subscription.SubsCategoryGroupRoute
import li.gkd.app.feature.subscription.SubsGlobalGroupExcludePage
import li.gkd.app.feature.subscription.SubsGlobalGroupExcludeRoute
import li.gkd.app.feature.subscription.SubsGlobalGroupListPage
import li.gkd.app.feature.subscription.SubsGlobalGroupListRoute
import li.gkd.app.ui.AppConfigPage
import li.gkd.app.ui.style.pageTransitionDurationMs
import li.gkd.app.ui.AppConfigRoute
import li.gkd.app.ui.ImagePreviewPage
import li.gkd.app.ui.ImagePreviewRoute
import li.gkd.app.ui.WebViewPage
import li.gkd.app.ui.WebViewRoute
import li.gkd.app.ui.home.HomePage
import li.gkd.app.ui.home.HomeRoute

// CLEAN：本文件只保留面向消费者必需的页面路由。
//
// 已随技术面收口移除的路由（对应页面文件已删除）：
//   AdvancedPageRoute、SnapshotPageRoute / SnapshotPreviewRoute / SnapshotSettingsRoute、
//   WorkModeRoute、ActionToastRoute、NotificationTextRoute、BlockA11ySetupRoute、
//   BlockA11yAppListRoute、EditBlockAppListRoute、A11YScopeAppListRoute、
//   ActivityLogRoute、A11yEventLogRoute、ActionLogRoute、CrashReportRoute、
//   SubsAppListRoute、SubsCategoryRoute、UpsertRuleGroupRoute、CategoryEditorRoute。
//
// 保留 WebViewRoute 是刻意的：条款与隐私政策需要页内展示，不属于技术细节。

private val editorTransitions = NavDisplay.transitionSpec {
    (slideInVertically(tween(250)) { it / 8 } + fadeIn(tween(250))) togetherWith fadeOut(tween(150))
} + NavDisplay.popTransitionSpec {
    fadeIn(tween(150)) togetherWith (slideOutVertically(tween(250)) { it / 8 } + fadeOut(tween(250)))
} + NavDisplay.predictivePopTransitionSpec {
    fadeIn(tween(150)) togetherWith (slideOutVertically(tween(250)) { it / 8 } + fadeOut(tween(250)))
}

private val mainRouteEntryProvider = entryProvider {
    entry<HomeRoute> { HomePage() }
    // CLEAN：AboutRoute / AboutPage 已随设置页「其他」分组的移除而下线

    // 应用规则控制面（消费级核心）
    entry<AppConfigRoute> { AppConfigPage(it) }
    // CLEAN：触发记录页恢复上线。它是「规则为什么没生效」的唯一可见入口，
    // 数据层（A11yState.addActionLog -> Db.actionLogDao）一直在写，此前只是没有页面读它。
    // 全局记录：ActionLogRoute()；单应用记录：ActionLogRoute(appId = ...)。
    entry<ActionLogRoute> { ActionLogPage(it) }
    // CLEAN：节点树审查页（第②段恢复）。只读当前屏幕节点树，不截图/不落盘/不上传。
    entry<NodeInspectRoute> { NodeInspectPage() }
    entry<SubsAppGroupListRoute> { SubsAppGroupListPage(it) }
    entry<SubsCategoryGroupRoute> { SubsCategoryGroupPage(it) }
    entry<SubsGlobalGroupListRoute> { SubsGlobalGroupListPage(it) }
    entry<SubsGlobalGroupExcludeRoute> { SubsGlobalGroupExcludePage(it) }
    entry<RuleExcludeEditorRoute>(metadata = editorTransitions) { RuleExcludeEditorPage(it) }

    // 通用
    entry<ImagePreviewRoute> { ImagePreviewPage(it) }
    entry<WebViewRoute> { WebViewPage(it) }

}

@Composable
fun MainNavigation() {
    val mainVm = MainViewModel.requireCurrent()
    NavDisplay(
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        backStack = mainVm.backStack,
        onBack = mainVm::popPage,
        entryProvider = mainRouteEntryProvider,
        // CLEAN：页面切换统一为 fade + slide，200ms（设计稿 §4.4）。
        // 工具类 App 以稳为主，不做弹跳/旋转。
        transitionSpec = {
            (slideInHorizontally(tween(pageTransitionDurationMs)) { it } +
                    fadeIn(tween(pageTransitionDurationMs))) togetherWith
                    (slideOutHorizontally(tween(pageTransitionDurationMs)) { -it / 8 } +
                            fadeOut(tween(pageTransitionDurationMs)))
        },
        popTransitionSpec = {
            (slideInHorizontally(tween(pageTransitionDurationMs)) { -it / 8 } +
                    fadeIn(tween(pageTransitionDurationMs))) togetherWith
                    (slideOutHorizontally(tween(pageTransitionDurationMs)) { it } +
                            fadeOut(tween(pageTransitionDurationMs)))
        },
        predictivePopTransitionSpec = {
            (slideInHorizontally(tween(pageTransitionDurationMs)) { -it / 8 } +
                    fadeIn(tween(pageTransitionDurationMs))) togetherWith
                    (slideOutHorizontally(tween(pageTransitionDurationMs)) { it } +
                            fadeOut(tween(pageTransitionDurationMs)))
        },
    )
}
