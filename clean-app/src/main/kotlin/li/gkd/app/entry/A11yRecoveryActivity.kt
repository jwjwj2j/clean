package li.gkd.app.entry

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import li.gkd.app.a11y.A11yGuard
import li.gkd.app.service.A11yService
import li.gkd.app.service.A11yWatchdog
import li.gkd.app.text.UiStrings
import li.gkd.app.util.LogUtils
import li.gkd.app.util.ToastUtils.toast

/**
 * CLEAN：无障碍被系统关闭后的「一键恢复」入口。
 *
 * 由 [A11yWatchdog] 的高优先级通知（息屏时可能是全屏 Intent）拉起。它本身没有界面
 * （使用透明主题），打开后直接跳到本应用无障碍服务的系统设置页；用户开好开关返回时，
 * 这里会自动复核并给出"已恢复"的提示。
 *
 * 说明：这是无特权设备上能做到的最短路径 —— 应用**不能**替用户打开那个开关，只能把
 * 用户送到开关面前。
 */
class A11yRecoveryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (A11yGuard.isServiceChecked() && A11yGuard.isRunning) {
            finishRecovered()
            return
        }
        toast(UiStrings.a11y_guard_guide, forced = true)
        if (!openServiceDetail()) {
            LogUtils.d("A11yRecoveryActivity: 打不开无障碍详情页")
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        if (A11yGuard.isServiceChecked()) {
            A11yWatchdog.clearNotification(this)
            finishRecovered()
        }
    }

    private fun finishRecovered() {
        A11yWatchdog.clearNotification(this)
        toast(UiStrings.a11y_guard_recovered, forced = true)
        finish()
    }

    /**
     * 跳到本应用无障碍服务的系统详情页。
     *
     * `ACTION_ACCESSIBILITY_DETAILS_SETTINGS` 从 API 26 起可用，但各家 ROM 对
     * `EXTRA_COMPONENT_NAME` 的读取方式不一致（有的读扁平化字符串，有的读 ComponentName
     * 对象），所以这里按「字符串 → 对象 → 无障碍列表页」三级降级，保证用户总能到达开关附近。
     */
    private fun openServiceDetail(): Boolean {
        val component = A11yService.a11yCn
        return tryStart(
            Intent(ACTION_A11Y_DETAILS_SETTINGS)
                .putExtra(Intent.EXTRA_COMPONENT_NAME, component.flattenToString())
        ) || tryStart(
            Intent(ACTION_A11Y_DETAILS_SETTINGS)
                .putExtra(Intent.EXTRA_COMPONENT_NAME, component)
        ) || tryStart(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun tryStart(intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            startActivity(intent)
            true
        } catch (e: Exception) {
            LogUtils.d("A11yRecoveryActivity: 跳转失败", e.message)
            false
        }
    }

    companion object {
        /**
         * `Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS`（API 26 起可用）在当前编译平台的
         * android.jar 里没有这个常量，所以这里用动作串字面量。运行时系统若无人处理，
         * [tryStart] 会捕获异常并逐级降级到无障碍列表页。
         */
        private const val ACTION_A11Y_DETAILS_SETTINGS = "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"
    }
}
