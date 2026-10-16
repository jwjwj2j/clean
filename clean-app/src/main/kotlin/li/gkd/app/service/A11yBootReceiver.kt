package li.gkd.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * CLEAN：开机 / 应用更新后的自愈入口。
 *
 * 这是**唯一需要对外暴露**的组件，且只处理三个系统广播：开机完成、应用被覆盖安装、
 * 用户解锁。收到后重建守护（周期任务 + 闹钟）并立刻检查一次无障碍是否还开着。
 */
class A11yBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_USER_UNLOCKED,
            -> A11yWatchdog.runCheck(context, intent.action ?: "boot")

            else -> {}
        }
    }
}
