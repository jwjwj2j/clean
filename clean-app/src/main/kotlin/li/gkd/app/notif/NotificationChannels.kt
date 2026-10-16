package li.gkd.app.notif

import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationManagerCompat
import li.gkd.app.META
import li.gkd.app.app

// CLEAN: 原有两个渠道（Service 运行状态 / Snapshot 快照已保存），快照下线后只剩运行状态。
enum class AppNotificationChannel(
    val id: String,
    private val label: String? = null,
    val description: String? = null,
    val importance: Int = NotificationManager.IMPORTANCE_LOW,
) {
    Service(id = "0"),

    /**
     * CLEAN：无障碍服务被系统关闭时的恢复提醒。
     *
     * 必须用 `IMPORTANCE_HIGH`：这条通知的全部价值就是"尽快把用户拉回开关页面"，
     * 低优先级渠道在息屏/后台会被折叠，等于没有。
     */
    Guard(id = "1", importance = NotificationManager.IMPORTANCE_HIGH);


    val displayName: String
        get() = label ?: META.appName
}

object NotificationChannels {
    fun initialize() {
        val manager = NotificationManagerCompat.from(app)
        val channelIds = AppNotificationChannel.entries.mapTo(mutableSetOf()) { it.id }

        manager.notificationChannels
            .filter { it.id !in channelIds }
            .forEach { manager.deleteNotificationChannel(it.id) }

        manager.createNotificationChannels(
            AppNotificationChannel.entries.map { spec ->
                NotificationChannel(
                    spec.id,
                    spec.displayName,
                    spec.importance,
                ).apply {
                    description = spec.description
                }
            }
        )
    }
}
