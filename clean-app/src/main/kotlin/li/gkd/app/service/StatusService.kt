package li.gkd.app.service

import android.content.Intent
import android.view.WindowManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import li.gkd.app.text.UiStrings
import li.gkd.app.META
import li.gkd.app.a11y.useA11yServiceEnabledFlow
import li.gkd.app.app
import li.gkd.app.notif.NotificationCatalog
import li.gkd.app.permission.PermissionStates
import li.gkd.app.platform.overlay.KeepAliveOverlayCoordinator
import li.gkd.app.priv.PrivilegeServiceStatus
import li.gkd.app.priv.privilegeServiceStatusFlow
import li.gkd.app.priv.uiAutomationFlow
import li.gkd.app.store.AppStore.actionCountFlow
import li.gkd.app.store.AppStore.storeFlow
import li.gkd.app.notif.replaceNotificationTemplate
import li.gkd.app.data.appinfo.AppInfoRepository
import li.gkd.app.data.subscription.SubscriptionState
import li.gkd.app.ui.share.statusText
import li.gkd.app.util.IntentUtils
import kotlin.time.Duration.Companion.milliseconds

class StatusService : LifecycleHookService() {

    private val a11yServiceEnabledFlow by lazy { useA11yServiceEnabledFlow(lifecycleScope) }
    private fun statusTriple(): Triple<String, String, String?> {
        val abRunning = A11yService.isRunning.value
        val automationRunning = uiAutomationFlow.value != null
        val store = storeFlow.value
        val ruleSummary = SubscriptionState.ruleSummaryFlow.value
        val count = actionCountFlow.value
        // CLEAN：privilegeServiceStatus 已在特权封装后恒为 Disconnected，其通知分支一并移除
        val title = if (store.useCustomNotifText) {
            store.customNotifTitle.replaceNotificationTemplate(ruleSummary, count)
        } else {
            META.appName
        }
        return if (PermissionStates.appOpsRestrictedFlow.value) {
            // CLEAN：原指向 gkd://page/3（特权服务页），该页已下线；改为不带深链的普通提示
            Triple(title, UiStrings.permission_restricted_reauthorize, defaultStatusNotification.uri)
        } else if (!automationRunning && !abRunning) {
            if (currentAppUseA11y) {
                val text = if (a11yServiceEnabledFlow.value) {
                    UiStrings.a11y_fault
                } else if (PermissionStates.writeSecureSettings.updateAndGet()) {
                    if (store.enableAutomator && store.enableBlockA11yAppList && a11yPartDisabledFlow.value) {
                        val name =
                            AppInfoRepository.appInfoMapFlow.value[topAppIdFlow.value]?.name ?: topAppIdFlow.value
                        UiStrings.service_partially_disabled_detail(name)
                    } else {
                        UiStrings.a11y_stopped
                    }
                } else {
                    UiStrings.a11y_unauthorized
                }
                Triple(title, text, defaultStatusNotification.uri)
            } else {
                val text =
                    if (store.enableAutomator && store.enableBlockA11yAppList && a11yPartDisabledFlow.value) {
                        val name =
                            AppInfoRepository.appInfoMapFlow.value[topAppIdFlow.value]?.name ?: topAppIdFlow.value
                        UiStrings.service_partially_disabled_detail(name)
                    } else {
                        UiStrings.automation_stopped
                    }
                Triple(title, text, defaultStatusNotification.uri)
            }
        } else if (!store.enableMatch) {
            // CLEAN：规则匹配总开关已迁到设置页（tab=3），深链随之更新；scheme 改为 clean://
            Triple(title, UiStrings.rule_matching_pause, "clean://page?tab=3")
        } else if (store.useCustomNotifText) {
            Triple(
                title,
                store.customNotifText.replaceNotificationTemplate(ruleSummary, count),
                defaultStatusNotification.uri
            )
        } else {
            Triple(title, ruleSummary.statusText(count), defaultStatusNotification.uri)
        }
    }

    init {
        useServicePresence(
            stateFlow = isRunning,
            name = UiStrings.persistent_notification,
            startToastDelayMillis = if (app.justStarted) 1000 else 0,
        )
        onCreated {
            // CLEAN：把无障碍守护排上（持久化周期任务 + 闹钟），并立刻做一次自检。
            A11yWatchdog.ensureScheduled(this)
            A11yWatchdog.runCheck(this, "status-start")
            // 进程还活着时的兜底轮询：无障碍服务被系统悄悄关掉时，最迟 10 分钟内被发现。
            lifecycleScope.launch {
                while (true) {
                    delay(10 * 60 * 1000L)
                    A11yWatchdog.runCheck(this@StatusService, "status-loop")
                }
            }
            if (!defaultStatusNotification.startForeground()) return@onCreated
            lifecycleScope.launch {
                combine(
                    A11yService.isRunning,
                    KeepAliveOverlayCoordinator.accessibilityAttached,
                ) { a11yRunning, a11yOverlayAttached ->
                    a11yRunning to a11yOverlayAttached
                }.distinctUntilChanged().collectLatest {
                    val (a11yRunning, a11yOverlayAttached) = it
                    if (a11yRunning && a11yOverlayAttached) {
                        KeepAliveOverlayCoordinator.releaseAfterHandoff(
                            source = KeepAliveOverlayCoordinator.Source.Status,
                            owner = this@StatusService,
                        )
                    } else {
                        KeepAliveOverlayCoordinator.acquire(
                            source = KeepAliveOverlayCoordinator.Source.Status,
                            owner = this@StatusService,
                            context = this@StatusService,
                            windowType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        )
                    }
                }
            }
            lifecycleScope.launch {
                combine(
                    A11yService.isRunning,
                    uiAutomationFlow,
                    storeFlow,
                    SubscriptionState.ruleSummaryFlow,
                    privilegeServiceStatusFlow,
                    a11yServiceEnabledFlow,
                    PermissionStates.writeSecureSettings.stateFlow,
                    PermissionStates.appOpsRestrictedFlow,
                    topAppIdFlow,
                    actionCountFlow.debounce(1000L.milliseconds),
                ) {
                    statusTriple()
                }.collect {
                    NotificationCatalog.status(
                        title = it.first,
                        text = it.second,
                        uri = it.third,
                    ).startForeground()
                }
            }
        }
        onDestroyed {
            KeepAliveOverlayCoordinator.release(
                source = KeepAliveOverlayCoordinator.Source.Status,
                owner = this,
            )
        }
    }

    /**
     * CLEAN：显式声明 `START_STICKY`。
     *
     * 系统因内存压力杀掉本服务后会尝试重建它，重建会带回 `App.onCreate`、状态通知与守护轮询。
     * 这不是"保证不死"，只是把"被杀之后没人管"的概率压低。
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    companion object {
        val isRunning: StateFlow<Boolean>
            field = MutableStateFlow(false)

        val needRestart
            get() = storeFlow.value.enableStatusService
                    && !isRunning.value
                    && PermissionStates.notification.updateAndGet()
                    && PermissionStates.foregroundServiceSpecialUse.updateAndGet()

        fun start() = IntentUtils.startForegroundServiceByClass(StatusService::class)
        fun stop() = IntentUtils.stopServiceByClass(StatusService::class)
        private var lastAutoStart = 0L
        fun autoStart() {
            if (System.currentTimeMillis() - lastAutoStart < 1000) return
            // 重启自动打开通知栏状态服务
            // 需要已有服务或前台才能自主启动，否则报错 startForegroundService() not allowed due to mAllowStartForeground false
            if (needRestart) {
                start()
                lastAutoStart = System.currentTimeMillis()
            }
        }
    }
}

private val defaultStatusNotification by lazy { NotificationCatalog.status() }
