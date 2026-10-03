package li.gkd.app.a11y

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `RuleSkipLog.merge` 的行为测试。
 *
 * ## 要防止的具体回归
 * 这个日志的用途是回答「刚才那个应用为什么没跳过广告」。它有两个容易写错、
 * 且**不会报错**的失效方式：
 * 1. **不去重** → 同一条「超出匹配时间」在界面多次变化中反复写入，50 条上限瞬间刷满，
 *    把其它应用的信息挤掉，用户翻不到自己关心的那条；
 * 2. **截断方向错** → 保留旧的、丢掉新的，看到的永远是过时信息。
 */
class RuleSkipLogTest {

    private fun entry(
        appId: String = "tv.danmaku.bili",
        groupKey: Int = 100,
        ruleIndex: Int = 0,
        statusName: String = "超出匹配时间",
        time: Long = 1_000L,
    ) = RuleSkipLog.Entry(
        appId = appId,
        activityId = null,
        subsId = -2L,
        groupKey = groupKey,
        groupName = "开屏广告（CLEAN 兜底）",
        ruleIndex = ruleIndex,
        ruleKey = ruleIndex,
        statusName = statusName,
        time = time,
    )

    @Test
    fun emptyAddedReturnsSameListInstance() {
        val existing = listOf(entry())
        // 原样返回同一个实例，调用方据此避免无谓的状态更新
        assertSame(existing, RuleSkipLog.merge(existing, emptyList(), now = 2_000L))
    }

    @Test
    fun newEntriesArePrependedSoRecentComeFirst() {
        val old = entry(statusName = "处于匹配延迟", time = 1_000L)
        val fresh = entry(statusName = "超出匹配时间", time = 2_000L)
        val merged = RuleSkipLog.merge(listOf(old), listOf(fresh), now = 2_000L)
        assertEquals(listOf(fresh, old), merged)
    }

    @Test
    fun sameRuleSameStatusWithinWindowIsDeduplicated() {
        val first = entry(statusName = "超出匹配时间", time = 1_000L)
        val existing = listOf(first)
        // 窗口内重复出现：不应新增
        val repeated = entry(statusName = "超出匹配时间", time = 1_500L)
        assertSame(existing, RuleSkipLog.merge(existing, listOf(repeated), now = 1_500L))
    }

    @Test
    fun sameRuleSameStatusAfterWindowIsKeptAgain() {
        val existing = listOf(entry(statusName = "超出匹配时间", time = 1_000L))
        val later = entry(statusName = "超出匹配时间", time = 30_000L)
        val merged = RuleSkipLog.merge(existing, listOf(later), now = 30_000L)
        assertEquals(2, merged.size)
        assertEquals(later, merged.first())
    }

    @Test
    fun differentStatusOfSameRuleIsKept() {
        val existing = listOf(entry(statusName = "超出匹配时间", time = 1_000L))
        val other = entry(statusName = "达到最大执行次数", time = 1_200L)
        val merged = RuleSkipLog.merge(existing, listOf(other), now = 1_200L)
        assertEquals(2, merged.size)
    }

    @Test
    fun differentAppIsNeverTreatedAsDuplicate() {
        val existing = listOf(entry(appId = "tv.danmaku.bili", time = 1_000L))
        val other = entry(appId = "com.zhihu.android", time = 1_100L)
        val merged = RuleSkipLog.merge(existing, listOf(other), now = 1_100L)
        assertEquals(2, merged.size)
    }

    @Test
    fun duplicateInsideTheSameBatchIsCollapsed() {
        // 同一批里出现两条完全相同的候选（理论上不应发生，但不能因此刷屏）
        val a = entry(statusName = "超出匹配时间", time = 5_000L)
        val b = entry(statusName = "超出匹配时间", time = 5_000L)
        val merged = RuleSkipLog.merge(emptyList(), listOf(a, b), now = 5_000L)
        assertEquals(1, merged.size)
    }

    @Test
    fun entriesBeyondLimitAreDroppedKeepingNewest() {
        val existing = (1..RuleSkipLog.MAX_ENTRIES).map { i ->
            entry(ruleIndex = i, statusName = "旧-$i", time = 1_000L)
        }
        val fresh = entry(ruleIndex = 999, statusName = "最新", time = 9_999L)
        val merged = RuleSkipLog.merge(existing, listOf(fresh), now = 9_999L)
        assertEquals(RuleSkipLog.MAX_ENTRIES, merged.size)
        assertEquals("最新", merged.first().statusName)
        // 最旧的一条必须被挤掉
        assertTrue(
            "超出上限时应丢弃最旧的条目",
            merged.none { it.statusName == "旧-${RuleSkipLog.MAX_ENTRIES}" },
        )
    }
}
