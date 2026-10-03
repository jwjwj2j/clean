package li.gkd.app.ui.share

import androidx.navigation3.runtime.NavKey
import li.gkd.app.feature.subscription.RuleExcludeEditorRoute
import li.gkd.app.feature.subscription.SubsAppGroupListRoute
import li.gkd.app.feature.subscription.SubsCategoryGroupRoute
import li.gkd.app.feature.subscription.SubsGlobalGroupExcludeRoute
import li.gkd.app.feature.subscription.SubsGlobalGroupListRoute

// CLEAN：订阅管理相关路由（SubsAppListRoute / SubsCategoryRoute / CategoryEditorRoute /
// UpsertRuleGroupRoute）已随订阅板块与本地规则编辑一并下线，故此处只保留控制面路由。
sealed interface DeletionTarget {
    data class Subscription(val subsId: Long) : DeletionTarget
    data class Category(val subsId: Long, val categoryKey: Int) : DeletionTarget
    data class App(val subsId: Long, val appId: String) : DeletionTarget
    data class Group(val subsId: Long, val appId: String?, val groupKey: Int) : DeletionTarget

    fun owns(route: NavKey): Boolean {
        val routeSubsId = when (route) {
            is SubsAppGroupListRoute -> route.subsItemId
            is SubsCategoryGroupRoute -> route.subsId
            is SubsGlobalGroupListRoute -> route.subsItemId
            is SubsGlobalGroupExcludeRoute -> route.subsItemId
            is RuleExcludeEditorRoute -> route.subsId
            else -> return false
        }
        return when (this) {
            is Subscription -> subsId == routeSubsId
            is Category -> subsId == routeSubsId && when (route) {
                is SubsCategoryGroupRoute -> categoryKey == route.categoryKey
                else -> false
            }

            is App -> subsId == routeSubsId && when (route) {
                is SubsAppGroupListRoute -> appId == route.appId
                is RuleExcludeEditorRoute -> appId == route.appId
                else -> false
            }

            is Group -> subsId == routeSubsId && when (route) {
                is SubsGlobalGroupExcludeRoute -> appId == null && groupKey == route.groupKey
                is RuleExcludeEditorRoute -> appId == route.appId && groupKey == route.groupKey
                else -> false
            }
        }
    }
}
