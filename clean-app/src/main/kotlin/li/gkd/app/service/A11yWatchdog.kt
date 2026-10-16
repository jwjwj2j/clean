package li.gkd.app.service

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import li.gkd.app.R
import li.gkd.app.a11y.A11yGuard
import li.gkd.app.entry.A11yRecoveryActivity
import li.gkd.app.notif.AppNotificationChannel
import li.gkd.app.text.UiStrings
import li.gkd.app.util.AndroidTarget
import li.gkd.app.util.LogUtils

/**
 * CLEAN：无障碍服务存活守护（调度 + 恢复引导层）。
 *
 * ## 它到底能做什么
 *
 * 没有 ADB / Shizuku / root 时，**没有任何办法让系统把无障碍开关重新打开**（见 [A11yGuard] 的说明）。
 * 所以这里的全部价值是：**把"服务已经被系统关掉"这件事，尽快变成"用户看得见、点一下就能开回来"**。
 * 越早发现，拦截中断的窗口越短。
 *
 * ## 四条互相兜底的路径
 *
 * | 路径 | 触发时机 | 特点 |
 * | --- | --- | --- |
 * | `A11yService.onDestroy`（同进程） | 服务被关的那一瞬间 | 最快，秒级；进程被杀时失效 |
 * | `StatusService` 前台服务内的循环 | 进程还活着时每 10 分钟 | 兜住"服务被关但进程还在" |
 * | `JobScheduler` 周期任务（15 分钟、持久化） | 进程被杀之后 | **进程死了也能把应用拉起来自检**；免权限 |
 * | `AlarmManager` 闹钟 + 开机广播 | Doze / 重启之后 | 补上 Job 可能被推迟的窗口 |
 *
 * ## 明确做不到的
 *
 * - 不能自动重新开启无障碍（无 `WRITE_SECURE_SETTINGS`）；
 * - 不能保证进程不被 ColorOS 清理，只能提高存活概率并缩短"失联→提醒"的时延。
 */
object A11yWatchdog {
    const val NOTIFICATION_ID = 101
    const val ACTION_TICK = "li.gkd.app.action.A11Y_WATCHDOG_TICK"
    const val ACTION_CHECK_NOW = "li.gkd.app.action.A11Y_CHECK_NOW"

    private const val JOB_ID = 1001
    private const val ALARM_REQUEST = 1002
    private const val INTERVAL_MILLIS = 15 * 60 * 1000L

    private val pendingFlags
        get() = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    /** 安排周期任务与闹钟（幂等，可反复调用）。 */
    fun ensureScheduled(context: Context) {
        scheduleJob(context)
        scheduleAlarm(context)
    }

    fun cancel(context: Context) {
        runCatching {
            (context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler).cancel(JOB_ID)
        }.onFailure { LogUtils.d("A11yWatchdog: 取消周期任务失败", it.message) }
        runCatching {
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(alarmPendingIntent(context))
        }.onFailure { LogUtils.d("A11yWatchdog: 取消闹钟失败", it.message) }
        clearNotification(context)
    }

    private fun scheduleJob(context: Context) {
        runCatching {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            val info = JobInfo.Builder(JOB_ID, ComponentName(context, A11yWatchdogJobService::class.java))
                .setPeriodic(INTERVAL_MILLIS)
                // 持久化：系统重启后仍然有效（配合 BootReceiver 的双保险）
                .setPersisted(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                .build()
            scheduler.schedule(info)
        }.onFailure { LogUtils.d("A11yWatchdog: 周期任务安排失败", it.message) }
    }

    private fun scheduleAlarm(context: Context) {
        runCatching {
            val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val triggerAt = System.currentTimeMillis() + INTERVAL_MILLIS
            if (AndroidTarget.S && !manager.canScheduleExactAlarms()) {
                // 没有精确闹钟权限（Android 12+ 的特殊权限，侧载默认不给）：退化为不精确闹钟，
                // 仍能在 Doze 下唤醒进程，只是时间有偏差。不为此再申请一个敏感权限。
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, alarmPendingIntent(context))
            } else {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, alarmPendingIntent(context))
            }
        }.onFailure { LogUtils.d("A11yWatchdog: 闹钟安排失败", it.message) }
    }

    private fun alarmPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, A11yWatchdogReceiver::class.java).setAction(ACTION_TICK)
        return PendingIntent.getBroadcast(context, ALARM_REQUEST, intent, pendingFlags)
    }

    /**
     * 核心自检。所有入口（开机、周期任务、闹钟、前台服务循环、无障碍服务销毁）最终都走这里。
     * 只在"确实被系统关掉、且用户本来就想要它开着"时才发提醒。
     */
    fun runCheck(context: Context, source: String) {
        ensureScheduled(context)
        if (A11yGuard.isServiceChecked()) {
            if (A11yGuard.isRunning) {
                A11yGuard.markAlive()
                A11yGuard.clearRecovery()
                clearNotification(context)
            }
            StatusService.autoStart()
            return
        }
        if (A11yGuard.decideNeedRecovery(source)) {
            notifyLost(context)
        }
        StatusService.autoStart()
    }

    /** 高优先级恢复提醒；息屏时尽量全屏弹出（无权限时自动降级为普通高优先级通知）。 */
    fun notifyLost(context: Context) {
        val openIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            Intent(context, A11yRecoveryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            pendingFlags,
        )
        val builder = NotificationCompat.Builder(context, AppNotificationChannel.Guard.id)
            .setSmallIcon(R.drawable.ic_status)
            .setContentTitle(UiStrings.a11y_guard_notif_title)
            .setContentText(UiStrings.a11y_guard_notif_text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(UiStrings.a11y_guard_notif_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .addAction(0, UiStrings.a11y_guard_action_open, openIntent)
        if (canUseFullScreenIntent(context)) {
            builder.setFullScreenIntent(openIntent, true)
        }
        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
        }.onFailure { LogUtils.d("A11yWatchdog: 恢复通知发送失败", it.message) }
    }

    fun clearNotification(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
            .onFailure { LogUtils.d("A11yWatchdog: 清除恢复通知失败", it.message) }
    }

    /** Android 14 起全屏 Intent 需要用户单独授权；未授权时静默降级。 */
    fun canUseFullScreenIntent(context: Context): Boolean {
        if (!AndroidTarget.UPSIDE_DOWN_CAKE) return true
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return false
        return manager.canUseFullScreenIntent()
    }
}
