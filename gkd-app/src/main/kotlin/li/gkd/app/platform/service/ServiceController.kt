package li.gkd.app.platform.service

import li.gkd.app.service.StatusService
import li.gkd.app.store.AppStore

// CLEAN: 原 ServiceController 还负责 HTTP 服务、悬浮截图按钮、活动监视与事件监视四个技术型
// Service 的启停。这些 Service 已随快照 / HTTP / 悬浮调试窗一并下线，故这里只保留运行状态通知。
object ServiceController {
    fun setStatusEnabled(enabled: Boolean) {
        if (enabled) StatusService.start() else StatusService.stop()
        AppStore.updateSettings { it.copy(enableStatusService = enabled) }
    }
}
