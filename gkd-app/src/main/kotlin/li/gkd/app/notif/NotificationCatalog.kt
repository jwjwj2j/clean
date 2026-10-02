package li.gkd.app.notif

import android.app.Service
import li.gkd.app.text.UiStrings
import li.gkd.app.META
import li.gkd.app.R
import kotlin.reflect.KClass

// CLEAN: 原目录包含 9 个通知条目（状态 / 截图 / 截图按钮 / HTTP / 外部调用 / 快照已保存 /
// 活动信息 / 无障碍事件 / 操作轨迹）。截图、HTTP 与悬浮调试窗下线后，只剩运行状态通知。
enum class ForegroundNotificationKey(
    val id: Int,
    val channel: AppNotificationChannel = AppNotificationChannel.Service,
) {
    Status(id = 100),
}

sealed interface AppNotificationSpec {
    val id: Int
    val channel: AppNotificationChannel
    val smallIcon: Int
    val title: String
    val text: String?
    val uri: String?
    val ongoing: Boolean
    val autoCancel: Boolean
    val stopService: KClass<out Service>?
}

data class ForegroundNotification(
    val key: ForegroundNotificationKey,
    override val title: String,
    override val text: String? = null,
    override val uri: String? = null,
    override val smallIcon: Int = R.drawable.ic_status,
    override val stopService: KClass<out Service>? = null,
) : AppNotificationSpec {
    override val id: Int
        get() = key.id
    override val channel: AppNotificationChannel
        get() = key.channel
    override val ongoing = true
    override val autoCancel = false

    context(service: Service)
    fun startForeground() = NotificationDispatcher.startForeground(service, this)
}

object NotificationCatalog {
    fun status(
        title: String = META.appName,
        text: String? = UiStrings.a11y_running,
        uri: String? = null,
    ) = ForegroundNotification(
        key = ForegroundNotificationKey.Status,
        title = title,
        text = text,
        uri = uri,
    )
}
