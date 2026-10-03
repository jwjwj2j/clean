package li.gkd.app.ui.share

import li.gkd.app.feature.subscription.RuleExcludeEditorRoute
import li.gkd.app.feature.subscription.SubsAppGroupListRoute
import li.gkd.app.feature.subscription.SubsCategoryGroupRoute
import li.gkd.app.feature.subscription.SubsGlobalGroupExcludeRoute
import li.gkd.app.feature.subscription.SubsGlobalGroupListRoute
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// CLEAN：订阅管理相关路由（SubsAppListRoute / SubsCategoryRoute / CategoryEditorRoute /
// UpsertRuleGroupRoute）已随订阅板块与本地规则编辑下线，测试相应收敛到仍存在的控制面路由。
class DeletionTargetTest {
    @Test
    fun categoryTargetOwnsItsDetailRouteOnly() {
        val target = DeletionTarget.Category(1, 2)
        assertTrue(target.owns(SubsCategoryGroupRoute(1, 2)))
        assertFalse(target.owns(SubsCategoryGroupRoute(1, 3)))
        assertFalse(target.owns(SubsCategoryGroupRoute(4, 2)))
        assertFalse(target.owns(SubsAppGroupListRoute(1, "app")))
    }

    @Test
    fun groupTargetOwnsMatchingEditorsOnly() {
        val target = DeletionTarget.Group(1, "app", 2)
        assertTrue(target.owns(RuleExcludeEditorRoute(subsId = 1, groupKey = 2, appId = "app")))
        assertFalse(target.owns(SubsAppGroupListRoute(1, "app", focusGroupKey = 2)))
        assertFalse(target.owns(SubsGlobalGroupExcludeRoute(1, 2)))
        assertFalse(target.owns(RuleExcludeEditorRoute(subsId = 1, groupKey = 2, appId = "other")))
        assertFalse(target.owns(RuleExcludeEditorRoute(subsId = 3, groupKey = 2, appId = "app")))
        val global = DeletionTarget.Group(1, null, 2)
        assertTrue(global.owns(SubsGlobalGroupExcludeRoute(1, 2)))
        assertFalse(global.owns(SubsGlobalGroupListRoute(1, focusGroupKey = 2)))
    }

    @Test
    fun appTargetOwnsMatchingAppRoutesOnly() {
        val target = DeletionTarget.App(1, "app")
        assertTrue(target.owns(SubsAppGroupListRoute(1, "app")))
        assertTrue(target.owns(RuleExcludeEditorRoute(subsId = 1, groupKey = 2, appId = "app")))
        assertFalse(target.owns(SubsAppGroupListRoute(1, "other")))
        assertFalse(target.owns(RuleExcludeEditorRoute(subsId = 1, groupKey = 2, appId = "other")))
    }

    @Test
    fun subscriptionTargetOwnsItsDescendantRoutesOnly() {
        val target = DeletionTarget.Subscription(1)
        assertTrue(target.owns(SubsAppGroupListRoute(1, "app")))
        assertTrue(target.owns(SubsCategoryGroupRoute(1, 2)))
        assertTrue(target.owns(SubsGlobalGroupExcludeRoute(1, 3)))
        assertFalse(target.owns(SubsAppGroupListRoute(2, "app")))
        assertFalse(target.owns(SubsGlobalGroupExcludeRoute(2, 3)))
    }
}
