package li.gkd.app.a11y

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import li.gkd.app.META
import li.gkd.app.data.ResolvedRule
import li.gkd.app.data.RuleStatus

/**
 * 「规则为什么没触发」的内存记录（CLEAN 新增）。
 *
 * ## 解决什么问题
 * 触发记录页（`ActionLog`）只记录**已经成功执行**的动作 —— 它回答"什么被触发了"，
 * 回答不了"为什么没触发"。而 `ResolvedRule.status` 虽然实时算出 7 种状态
 * （超出匹配时间 / 达到最大执行次数 / 处于匹配延迟 …），却从未被任何界面展示。
 *
 * 更麻烦的是：用户打开 CLEAN 查看时，`MainActivityLifecycle.onStart` 会把
 * `topActivity` 改成 CLEAN 自己，因此**实时状态指向的是 CLEAN，不是刚才出问题的应用**。
 *
 * ## 因此采用「切走时快照」
 * [A11yState.updateTopActivity] 在应用切换的那一刻仍持有**上一个应用**的规则集，
 * 此时把其中非正常状态（`StatusOk` 之外）的规则记下来。于是：
 * 用户在 App B 遇到广告没跳过 → 切到 CLEAN → 触发记录页就能看到
 * 「App B：开屏广告 · 超出匹配时间」——这正是"为什么没拦住"的答案。
 *
 * ## 边界（刻意收窄）
 * - **只在内存**：不写数据库（避免 Room 迁移风险），进程结束即失。诊断用途足够。
 * - **不挂热路径**：只在应用切换时记录一次，不在每个无障碍事件里记录。
 * - **有上限**：最多保留 [MAX_ENTRIES] 条，超出丢最旧的。
 * - 同一「应用 + 组 + 规则 + 状态」在短时间内重复出现时只记一次，避免刷屏。
 */
object RuleSkipLog {
    /** 最多保留的条数。 */
    const val MAX_ENTRIES = 50

    /** 同一规则同一状态的去重窗口（毫秒）。 */
    private const val DEDUP_WINDOW_MS = 10_000L

    data class Entry(
        val appId: String,
        val activityId: String?,
        val subsId: Long,
        val groupKey: Int,
        val groupName: String,
        val ruleIndex: Int,
        val ruleKey: Int?,
        /** 规则状态文案，取自 `UiStrings.rule_status_*`（见 [RuleStatus]）。 */
        val statusName: String,
        val time: Long,
    )

    val flow: StateFlow<List<Entry>>
        field = MutableStateFlow<List<Entry>>(emptyList())

    /**
     * 记录一批「未正常生效」的规则。
     *
     * 由 [A11yState.updateTopActivity] 在应用切换时调用；**不在此处做线程切换**，
     * 调用点已持有状态锁，只做一次 O(n) 过滤 + 有界插入。
     *
     * @param rules 上一个应用的规则集（`ActivityRule.currentRules`）
     */
    fun recordFrom(
        appId: String,
        activityId: String?,
        rules: List<ResolvedRule>,
        now: Long = System.currentTimeMillis(),
    ) {
        if (appId.isEmpty() || appId == META.appId) return
        val pending = rules.filter { it.status !== RuleStatus.StatusOk }
        if (pending.isEmpty()) return
        val candidates = pending.map { rule ->
            Entry(
                appId = appId,
                activityId = activityId,
                subsId = rule.subsItem.id,
                groupKey = rule.g.group.key,
                groupName = rule.g.group.name,
                ruleIndex = rule.index,
                ruleKey = rule.key,
                statusName = rule.status.name,
                time = now,
            )
        }
        val next = merge(flow.value, candidates, now)
        if (next !== flow.value) {
            flow.value = next
        }
    }

    /**
     * 去重 + 截断（纯逻辑，单独拆出以便测试，不依赖 `ResolvedRule`）。
     *
     * 去重键：应用 + 组 + 规则 + 状态，且在 [DEDUP_WINDOW_MS] 内才算重复。
     * 之所以要这个窗口：同一个「超出匹配时间」会在多次界面变化中反复出现，
     * 不去重会把 50 条上限瞬间刷满，把其它应用的信息挤掉。
     *
     * @return 新列表；若无新增内容则**原样返回** [existing]（调用方据此避免无谓的状态更新）
     */
    fun merge(
        existing: List<Entry>,
        added: List<Entry>,
        now: Long,
        dedupWindowMs: Long = DEDUP_WINDOW_MS,
        maxEntries: Int = MAX_ENTRIES,
    ): List<Entry> {
        if (added.isEmpty()) return existing
        val kept = ArrayList<Entry>(added.size)
        for (entry in added) {
            val duplicated = (existing.asSequence() + kept.asSequence()).any { old ->
                old.appId == entry.appId &&
                        old.groupKey == entry.groupKey &&
                        old.ruleIndex == entry.ruleIndex &&
                        old.statusName == entry.statusName &&
                        now - old.time < dedupWindowMs
            }
            if (!duplicated) {
                kept.add(entry)
            }
        }
        if (kept.isEmpty()) return existing
        // 新的在前，便于查看「最近为什么没触发」
        return (kept.asReversed() + existing).take(maxEntries)
    }

    /** 清空记录（诊断页提供入口）。 */
    fun clear() {
        flow.value = emptyList()
    }
}
