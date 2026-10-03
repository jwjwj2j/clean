package li.gkd.selector

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * CLEAN 补充规则所用「开屏兜底」选择器的行为测试。
 *
 * ## 为什么单独测
 * 上游把开屏广告的通用兜底对 107 个应用显式关闭（这些应用各有专属规则）。CLEAN 在
 * 自有规则源里补一条**更严格**的兜底，理由是降低误触风险。严格是双刃剑：
 * 若严过头（例如实际「跳过」节点并不可点击），规则将**永不触发**，而这类失效
 * 不会报错、只在真机上表现为"还是没跳过"。因此这里正反两面都锁：
 * - 命中：正常的可点击短文本「跳过」叶子
 * - 不命中：不可点击、文本过长、不可见、以及非目标文案（防止误关正常弹窗）
 */
class CleanSplashSelectorTest {

    /** 与 assets/clean-rules.json5 保持一致；改规则时必须同步改这里。 */
    private val primary = "[text*=\"跳过\"][text.length<8][visibleToUser=true][clickable=true]"
    private val secondary =
        "[childCount=0][visibleToUser=true][clickable=true][(text.length<8&&(text*=\"跳过\"||text*=\"跳過\"||text*=\"skip\"||text*=\"Skip\"))]"

    /** 上游全局兜底的第一条，用于对照「CLEAN 更严格」。 */
    private val upstreamPrimary = "[text*=\"跳过\"][text.length<10][visibleToUser=true]"

    private fun node(
        key: String,
        text: String? = null,
        desc: String? = null,
        clickable: Boolean = true,
        visibleToUser: Boolean = true,
        children: List<TestNode> = emptyList(),
    ): TestNode = TestNode(
        key = key,
        name = "android.widget.TextView",
        attributes = buildMap {
            text?.let { put("text", it) }
            desc?.let { put("desc", it) }
            put("clickable", clickable)
            put("visibleToUser", visibleToUser)
        },
        children = children,
    )

    private fun match(selector: String, node: TestNode): TestNode? =
        Selector.compile(selector).value.match(node, TestNodeAdapter)

    // ---------------------------------------------------------------- 正例

    @Test
    fun clickableShortSkipLeafMatches() {
        val skip = node("skip", text = "跳过")
        assertSame(skip, match(primary, skip), "可点击的短文本「跳过」必须命中")
        assertSame(skip, match(secondary, skip), "第二条兜底同样必须命中")
    }

    @Test
    fun chineseTraditionalAndEnglishSkipMatch() {
        // primary 只覆盖简体「跳过」（与上游第一条一致）；繁体与英文由 secondary 覆盖。
        assertNotNull(match(primary, node("s", text = "跳过")), "「跳过」必须被 primary 命中")
        listOf("跳過", "Skip", "skip").forEach { label ->
            val n = node("skip-$label", text = label)
            assertNotNull(match(secondary, n), "「$label」必须被 secondary 命中")
        }
    }

    @Test
    fun skipWithCountdownSuffixMatches() {
        // 例如「跳过5」「跳过 3」这类带倒计时的短文本
        listOf("跳过5", "跳过 3", "跳过3s").forEach { label ->
            assertNotNull(match(primary, node("c-$label", text = label)), "「$label」必须命中")
        }
    }

    // ---------------------------------------------------------------- 反例（严格性）

    @Test
    fun nonClickableSkipDoesNotMatch() {
        // 这是 CLEAN 相对上游收紧的地方：不可点击的节点点不动，不应被选中
        val n = node("skip-not-clickable", text = "跳过", clickable = false)
        assertNull(match(primary, n), "不可点击的「跳过」不应命中")
        assertNull(match(secondary, n), "不可点击的「跳过」不应命中（第二条）")
        // 对照：上游那条没有 clickable 约束，会命中它
        assertNotNull(match(upstreamPrimary, n), "上游选择器本会命中，说明 CLEAN 确实更严格")
    }

    @Test
    fun invisibleSkipDoesNotMatch() {
        val n = node("skip-invisible", text = "跳过", visibleToUser = false)
        assertNull(match(primary, n), "不可见的「跳过」不应命中")
    }

    @Test
    fun longTextContainingSkipDoesNotMatch() {
        // 正文里出现「跳过」二字的长文本（例如说明文案）不得被当成按钮
        val n = node("long", text = "跳过广告即可继续观看视频")
        assertNull(match(primary, n), "长文本不应命中")
        assertNull(match(secondary, n), "长文本不应命中（第二条）")
    }

    // ---------------------------------------------------------------- 反例（防误触）

    @Test
    fun closeButtonIsNotTargeted() {
        // 关键防误触：正常弹窗的「关闭」「取消」「确定」绝不能被这条兜底点到
        listOf("关闭", "关闭广告", "取消", "确定", "以后再说").forEach { label ->
            val n = node("btn-$label", text = label)
            assertNull(match(primary, n), "「$label」不应命中（否则会误关正常弹窗）")
            assertNull(match(secondary, n), "「$label」不应命中（第二条）")
        }
    }

    @Test
    fun parentWithChildrenIsNotTreatedAsLeafBySecondary() {
        val child = node("child", text = "跳过")
        val parent = node("parent", text = "跳过", children = listOf(child))
        // 第二条要求 childCount=0，父节点不应命中
        assertNull(match(secondary, parent), "有子节点的「跳过」不应被第二条命中")
    }
}
