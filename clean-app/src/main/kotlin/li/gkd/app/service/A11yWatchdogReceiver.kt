package li.gkd.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import li.gkd.app.util.LogUtils

/**
 * CLEAN：无障碍守护的内部广播入口（仅接收本应用显式发出的广播，不对外暴露）。
 *
 * - [A11yWatchdog.ACTION_TICK]：`AlarmManager` 闹钟到点后的自检；
 * - [A11yWatchdog.ACTION_CHECK_NOW]：应用内部请求的立即自检。
 */
class A11yWatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (val action = intent?.action) {
            A11yWatchdog.ACTION_TICK,
            A11yWatchdog.ACTION_CHECK_NOW,
            -> A11yWatchdog.runCheck(context, action ?: "internal")

            else -> LogUtils.d("A11yWatchdogReceiver: 忽略的广播", action)
        }
    }
}
