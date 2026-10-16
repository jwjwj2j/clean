package li.gkd.app.a11y

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.view.accessibility.AccessibilityManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import li.gkd.app.app
import li.gkd.app.service.A11yService
import li.gkd.app.util.LogUtils

/**
 * CLEAN：无障碍服务存活守护（检测层）。
 *
 * ## 先说清楚平台硬约束
 *
 * 系统里那个无障碍开关存在 `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`，写入需要
 * `WRITE_SECURE_SETTINGS`（signature|privileged）。没有 ADB / Shizuku / root 的侧载应用
 * **拿不到这个权限，因此无法用任何代码手段把开关重新打开**。这是平台约束，不是实现问题；
 * 任何声称能"自动重开"的方案在无特权设备上都站不住。
 *
 * 所以这一层只做三件真正能生效的事：
 *  1. **尽快发现**开关被系统关掉了（本对象）；
 *  2. 立刻用高优先级通知 + 一键恢复页把用户送回开关页面（[li.gkd.app.service.A11yWatchdog]）；
 *  3. 记录被关的时间与累计次数，用于判断系统的清理节奏与存活时长。
 *
 * ## 检测走两条互相兜底的路
 *
 * - `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`（同步读；项目已有
 *   [useEnabledA11yServicesFlow] 的内容观察者做实时推送）；
 * - `AccessibilityManager.getEnabledAccessibilityServiceList`：公开 API，个别 ROM 上
 *   Settings 读不到时兜底。
 */
object A11yGuard {
    private const val PREFS_NAME = "a11y_guard"
    private const val KEY_LOST_AT = "lost_at"
    private const val KEY_LOST_COUNT = "lost_count"
    private const val KEY_LOST_REASON = "lost_reason"
    private const val KEY_LAST_ALIVE_AT = "last_alive_at"
    private const val KEY_USER_INTENT = "user_intent"
    private const val KEY_INTENTIONAL_STOP_AT = "intentional_stop_at"
    private const val KEY_NOTIFIED_INCIDENT = "notified_incident"

    private fun prefs() = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 最近一次「发现服务被关」的时间戳，0 表示从未发生。 */
    val lostAtFlow: StateFlow<Long> field = MutableStateFlow(prefs().getLong(KEY_LOST_AT, 0L))

    /** 累计被关次数（跨进程重启保留）。 */
    val lostCountFlow: StateFlow<Int> field = MutableStateFlow(prefs().getInt(KEY_LOST_COUNT, 0))

    /** 最近一次「确认服务活着」的时间戳。 */
    val lastAliveAtFlow: StateFlow<Long> field = MutableStateFlow(prefs().getLong(KEY_LAST_ALIVE_AT, 0L))

    /** 系统里那个开关当前是否为「已勾选」。 */
    fun isServiceChecked(): Boolean {
        val bySecure = runCatching { app.getSecureA11yServices().contains(A11yService.a11yCn) }
            .getOrDefault(false)
        if (bySecure) return true
        return runCatching { isCheckedByManager() }.getOrDefault(false)
    }

    private fun isCheckedByManager(): Boolean {
        val manager =
            app.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { info ->
                val serviceInfo = info.resolveInfo?.serviceInfo ?: return@any false
                ComponentName(serviceInfo.packageName, serviceInfo.name) == A11yService.a11yCn
            }
    }

    /** 服务进程是否活着（只在同一进程内可信；跨进程要看 [isServiceChecked]）。 */
    val isRunning: Boolean get() = A11yService.isRunning.value

    /** 用户是否表达过「我要用它」的意图（服务连上过，且之后没有被我们自己主动关掉）。 */
    fun isUserIntentActive(): Boolean = prefs().getBoolean(KEY_USER_INTENT, false)

    /** 服务连上：用户意图成立，并刷新存活时间。 */
    fun onServiceConnected() {
        val now = System.currentTimeMillis()
        prefs().edit()
            .putBoolean(KEY_USER_INTENT, true)
            .putLong(KEY_LAST_ALIVE_AT, now)
            .apply()
        lastAliveAtFlow.value = now
    }

    /**
     * 服务销毁。
     * [intentional] 为 true 表示是**我们自己**关的（用户在应用里关开关 / 切换自动化模式），
     * 这种情况不该提醒用户去开回来。
     */
    fun onServiceDestroyed(intentional: Boolean) {
        if (intentional) {
            prefs().edit()
                .putBoolean(KEY_USER_INTENT, false)
                .putLong(KEY_INTENTIONAL_STOP_AT, System.currentTimeMillis())
                .apply()
        }
    }

    /**
     * 在**主动**关闭无障碍之前打一个标记。
     *
     * 存在这条路径是因为界面开关与磁贴走的是 `AccessibilityService.disableSelf()`，并不经过
     * `A11yService.shutdown()`；没有这个标记，主动关闭会被守护误判成"被系统关掉"而弹提醒。
     */
    fun markIntentionalStop() {
        prefs().edit()
            .putBoolean(KEY_USER_INTENT, false)
            .putLong(KEY_INTENTIONAL_STOP_AT, System.currentTimeMillis())
            .apply()
    }

    /** 最近一次主动关闭是否就发生在刚刚（用于服务销毁时的归因）。 */
    fun recentlyMarkedIntentionalStop(windowMillis: Long = 5000L): Boolean {
        val at = prefs().getLong(KEY_INTENTIONAL_STOP_AT, 0L)
        return at > 0L && System.currentTimeMillis() - at <= windowMillis
    }

    /** 确认一次存活。 */
    fun markAlive() {
        val now = System.currentTimeMillis()
        prefs().edit().putLong(KEY_LAST_ALIVE_AT, now).apply()
        lastAliveAtFlow.value = now
    }

    /**
     * 判定「是否需要提醒用户去重新开启」，并在需要时把这次事故登记下来。
     *
     * 同一次死亡只提醒一次：用「上一次确认活着的时刻」作为事故编号，提醒过就记进
     * [KEY_NOTIFIED_INCIDENT]，直到服务重新连上（会刷新编号）才会再次提醒。
     */
    fun decideNeedRecovery(reason: String): Boolean {
        if (isServiceChecked()) return false
        if (!isUserIntentActive()) return false
        val incident = prefs().getLong(KEY_LAST_ALIVE_AT, 0L)
        if (incident <= 0L) return false
        if (prefs().getLong(KEY_NOTIFIED_INCIDENT, -1L) == incident) return false

        val now = System.currentTimeMillis()
        val count = prefs().getInt(KEY_LOST_COUNT, 0) + 1
        prefs().edit()
            .putLong(KEY_LOST_AT, now)
            .putInt(KEY_LOST_COUNT, count)
            .putString(KEY_LOST_REASON, reason)
            .putLong(KEY_NOTIFIED_INCIDENT, incident)
            .apply()
        lostAtFlow.value = now
        lostCountFlow.value = count
        LogUtils.d("A11yGuard：无障碍服务被系统关闭", "reason=$reason", "累计=$count", "存活=${now - incident}ms")
        return true
    }

    /** 服务已恢复：清掉待提醒状态。 */
    fun clearRecovery() {
        prefs().edit().remove(KEY_NOTIFIED_INCIDENT).apply()
    }

    fun lostReason(): String? = prefs().getString(KEY_LOST_REASON, null)

    /** 上次被关到现在过了多久（毫秒）；从未被关返回 0。 */
    fun sinceLostMillis(): Long {
        val at = prefs().getLong(KEY_LOST_AT, 0L)
        return if (at <= 0L) 0L else System.currentTimeMillis() - at
    }

    /** 上一次确认活着距现在多久（毫秒）；从未活着返回 -1。 */
    fun sinceAliveMillis(): Long {
        val at = prefs().getLong(KEY_LAST_ALIVE_AT, 0L)
        return if (at <= 0L) -1L else System.currentTimeMillis() - at
    }
}
