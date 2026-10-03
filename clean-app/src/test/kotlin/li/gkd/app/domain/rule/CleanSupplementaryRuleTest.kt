package li.gkd.app.domain.rule

import li.gkd.app.data.RawSubscription
import li.gkd.app.data.subscription.SubscriptionState
import li.gkd.selector.Selector
import li.gkd.selector.SelectorCompileResult
import li.gkd.db.SubsItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * CLEAN 补充规则「开屏兜底」的有效性证据。
 *
 * ## 要防止的具体回归
 * 上游订阅把开屏广告的**通用兜底**对 107 个应用显式关闭（`apps:[{id:'x',enable:false}]`），
 * 前提是这些应用各有专属规则。一旦专属选择器因为 App 改版失效，通用兜底**不会接管**，
 * 而订阅已停止维护、没人会修 —— 那条广告就彻底无人处理。
 *
 * CLEAN 在自有规则源里补一条**更严格**的兜底（要求 clickable + 叶子 + 短文本，
 * 且 actionMaximum=1）。本测试锁住三件事：
 * 1. 上游兜底对目标应用确实是**关闭**的（这就是"原本拦不住"）；
 * 2. CLEAN 补充规则对同一应用**生效**（没有 disable 列表挡着）；
 * 3. 补充规则的选择器**能编译** —— 选择器写错会静默永不匹配，是最隐蔽的失效。
 */
class CleanSupplementaryRuleTest {

    private companion object {
        /** 优先按模块工作目录解析，回退到仓库根目录。 */
        fun readAsset(name: String): String {
            val candidates = listOf(
                File("src/main/assets/$name"),
                File("clean-app/src/main/assets/$name"),
            )
            val hit = candidates.firstOrNull { it.isFile }
            assertNotNull(
                "找不到随包资源 $name，尝试过: " + candidates.joinToString { it.absolutePath },
                hit,
            )
            return hit!!.readText()
        }

        /** 上游兜底关闭的应用里取一个典型样本；哔哩哔哩在这份列表中。 */
        const val TARGET_APP = "tv.danmaku.bili"
    }

    private fun upstreamSplashGroup(): RawSubscription.RawGlobalGroup {
        val subs = RawSubscription.parse(readAsset("gkd-fallback.json5"))
        return subs.globalGroups.first { it.name.contains("开屏") }
    }

    private fun cleanSplashGroup(): RawSubscription.RawGlobalGroup {
        val subs = RawSubscription.parse(readAsset("clean-rules.json5"))
        assertEquals(-2L, subs.id)
        return subs.globalGroups.first { it.name.contains("开屏") }
    }

    /**
     * 「原本拦不住」的证据：上游为该应用写了 `enable:false`，
     * 因此 `globalAppEnabled` 返回 false —— 通用兜底对该应用不生效。
     */
    @Test
    fun upstreamSplashFallbackIsDisabledForTargetApp() {
        val group = upstreamSplashGroup()
        val app = RuleScopePolicy.globalApp(group, null, TARGET_APP)
        assertNotNull(
            "上游应为 $TARGET_APP 写了一条 apps 条目（本测试的前提）",
            app,
        )
        assertEquals(false, app!!.enable)
        assertFalse(
            "上游开屏兜底对 $TARGET_APP 必须是关闭的，否则本补充规则没有存在意义",
            RuleScopePolicy.globalAppEnabled(app, null),
        )
    }

    /**
     * 「现在覆盖到了」的证据：CLEAN 补充规则没有 disable 列表，
     * 且 `globalDefault` 对普通应用返回 true —— 规则会参与匹配。
     */
    @Test
    fun cleanSplashFallbackAppliesToSameApp() {
        val group = cleanSplashGroup()
        assertNull(
            "CLEAN 补充规则不应把该应用排除在外",
            RuleScopePolicy.globalApp(group, null, TARGET_APP),
        )
        assertTrue(
            "CLEAN 补充规则应对 $TARGET_APP 生效",
            RuleScopePolicy.globalDefault(
                group = group,
                rule = null,
                appId = TARGET_APP,
                launcherAppId = "com.example.launcher",
                systemAppIds = setOf("android"),
            ),
        )
    }

    /**
     * 「规则真的会被装载」的证据：`buildUsedSubsEntries` 是规则解析的入口，
     * 它要求 `item.enable && subscription.hasRule`。本地订阅只有同时满足这两条
     * 才会进入后续的规则构建 —— 任一条不满足，规则写了也等于没写，且没有任何报错。
     *
     * 这条同时锁住 `SubsItem.enable` 默认 false 的坑：上游源当初就因此「装上却零规则」。
     */
    @Test
    fun enabledLocalSubscriptionWithRulesIsPickedUp() {
        val subs = RawSubscription.parse(readAsset("clean-rules.json5"))
        assertEquals(-2L, subs.id)
        assertTrue(
            "只有全局组也算有规则（hasRule 覆盖 globalGroups）",
            subs.hasRule,
        )

        val enabled = SubsItem(id = -2L, order = 0, enable = true)
        val picked = SubscriptionState.buildUsedSubsEntries(listOf(enabled), mapOf(-2L to subs))
        assertEquals("启用且有规则的本地订阅必须被装载", 1, picked.size)

        val disabled = SubsItem(id = -2L, order = 0, enable = false)
        val notPicked = SubscriptionState.buildUsedSubsEntries(listOf(disabled), mapOf(-2L to subs))
        assertEquals("未启用则必须被排除（默认值就是 false）", 0, notPicked.size)
    }

    /**
     * 选择器必须能编译：写错的选择器不会报错，只会永不匹配 —— 这是最隐蔽的失效。
     * 同时锁住「比上游更严格」的设计意图（要求 clickable）。
     */
    @Test
    fun cleanSplashSelectorsCompileAndRequireClickable() {
        val group = cleanSplashGroup()
        assertTrue("补充规则至少要有一条", group.rules.isNotEmpty())
        val allSources = mutableListOf<String>()
        group.rules.forEach { rule ->
            // matches 是 List<String>：一条规则可以给出多个候选选择器
            val sources = rule.matches
            assertNotNull("每条规则都必须有 matches", sources)
            assertTrue("matches 不能为空", sources!!.isNotEmpty())
            allSources += sources
        }
        assertTrue("补充规则至少要有一个选择器", allSources.isNotEmpty())
        allSources.forEach { source ->
            val result = Selector.compile(source)
            assertTrue(
                "选择器编译失败: $source -> $result",
                result is SelectorCompileResult.Success,
            )
            assertTrue(
                "补充规则必须要求 clickable=true（降低误触）：$source",
                source.contains("clickable"),
            )
        }
    }
}
