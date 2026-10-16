package li.gkd.app.service

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.google.android.accessibility.selecttospeak.SelectToSpeakService
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import li.gkd.app.text.UiStrings
import li.gkd.app.a11y.A11yCommonImpl
import li.gkd.app.a11y.A11yGuard
import li.gkd.app.a11y.A11yRuleEngine
import li.gkd.app.a11y.A11yRuntime
import li.gkd.app.a11y.A11yState
import li.gkd.app.a11y.currentTopActivity
import li.gkd.app.a11y.updateTopActivity
import li.gkd.app.appScope
import li.gkd.app.platform.lifecycle.LifecycleHooks
import li.gkd.app.platform.overlay.KeepAliveOverlayCoordinator
import li.gkd.app.priv.privilegeContextFlow
import li.gkd.app.priv.uiAutomationFlow
import li.gkd.app.store.AppStore.updateEnableAutomator
import li.gkd.app.util.AndroidTarget
import li.gkd.app.util.AutomatorModeOption
import li.gkd.app.util.LogUtils
import li.gkd.app.util.componentName
import li.gkd.app.util.ToastUtils.toast
import kotlin.coroutines.resume

@SuppressLint("AccessibilityPolicy")
abstract class A11yService : AccessibilityService(), A11yCommonImpl {
    private val lifecycleHooks = LifecycleHooks()
    override val scope = MainScope()
    override val mode get() = AutomatorModeOption.A11yMode
    override val windowNodeInfo: AccessibilityNodeInfo? get() = rootInActiveWindow
    override val windowInfos: List<AccessibilityWindowInfo> get() = windows
    override suspend fun screenshot(): Bitmap? = suspendCancellableCoroutine { cont ->
        if (AndroidTarget.R) {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                application.mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onFailure(errorCode: Int) {
                        if (cont.isActive) {
                            cont.resume(null)
                        }
                    }

                    override fun onSuccess(screenshot: ScreenshotResult) {
                        try {
                            if (cont.isActive) {
                                cont.resume(
                                    Bitmap.wrapHardwareBuffer(
                                        screenshot.hardwareBuffer, screenshot.colorSpace
                                    )
                                )
                            }
                        } finally {
                            screenshot.hardwareBuffer.close()
                        }
                    }
                }
            )
        } else {
            cont.resume(null)
        }
    }

    override val ruleEngine by lazy { A11yRuleEngine(this) }

    override fun onInterrupt() {}
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = ruleEngine.onA11yEvent(event)

    private val startTime = System.currentTimeMillis()
    override var justStarted: Boolean = true
        get() {
            if (field) {
                field = System.currentTimeMillis() - startTime < 3_000
            }
            return field
        }

    private var tempShutdownFlag = false

    /** CLEAN：是否由我们主动关闭（用户关开关 / 切换自动化模式）。主动关闭不该提醒用户重开。 */
    private var intentionalShutdown = false

    override fun shutdown(temp: Boolean) {
        intentionalShutdown = true
        A11yGuard.markIntentionalStop()
        if (temp) {
            tempShutdownFlag = true
        }
        disableSelf()
    }

    private var destroyed = false
    private var connected = false

    val wm by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }

    init {
        lifecycleHooks.useLogLifecycle(this)
        lifecycleHooks.onCreated {
            isRunning.value = true
            if (currentAppUseA11y) {
                updateEnableAutomator(true)
            } else {
                toast(UiStrings.a11y_automation_mode_notice, forced = true)
                scope.launch {
                    delay(1)
                    shutdown(true)
                }
            }
            StatusService.autoStart()
            scope.launch {
                delay(3000)
                if (!(destroyed || connected)) {
                    toast(UiStrings.a11y_start_timeout, forced = true)
                }
            }
        }
        lifecycleHooks.onDestroyed {
            scope.cancel()
            if (instance === this) {
                instance = null
            }
            isRunning.value = false
            releaseKeepAliveOverlayAfterHandoff()
            // CLEAN：把「服务没了」这件事告诉守护层 —— 这是最快的检测点（同进程、秒级）。
            // 主动关闭（shutdown / 界面开关的 disableSelf / 已切到自动化模式）不算被系统关掉。
            val intentional = intentionalShutdown ||
                    uiAutomationFlow.value != null ||
                    A11yGuard.recentlyMarkedIntentionalStop()
            A11yGuard.onServiceDestroyed(intentional = intentional)
            if (!intentional) {
                // 系统把开关关掉后，Settings 的写入未必立刻可见，延迟两秒再复核一次。
                appScope.launch {
                    delay(2000)
                    A11yWatchdog.runCheck(applicationContext, "a11y-onDestroy")
                }
            }
            if (tempShutdownFlag) {
                toast(UiStrings.a11y_partially_disabled)
            } else {
                toast(UiStrings.a11y_stopped)
                updateEnableAutomator(false)
            }
            A11yState.withTopActivityLock {
                privilegeContextFlow.value?.run {
                    topCpn()?.let { cpn ->
                        // com.android.systemui
                        if (!currentTopActivity.sameAs(cpn.packageName, cpn.className)) {
                            updateTopActivity(cpn.packageName, cpn.className)
                        }
                    }
                }
            }
            destroyed = true
        }
    }

    private fun attachKeepAliveOverlay() {
        if (!KeepAliveOverlayCoordinator.acquire(
                source = KeepAliveOverlayCoordinator.Source.Accessibility,
                owner = this,
                context = this,
                windowType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            )
        ) {
            toast(UiStrings.a11y_keep_alive_failed)
        }
    }

    private fun releaseKeepAliveOverlayAfterHandoff() {
        appScope.launch {
            KeepAliveOverlayCoordinator.releaseAfterHandoff(
                source = KeepAliveOverlayCoordinator.Source.Accessibility,
                owner = this@A11yService,
                replacement = KeepAliveOverlayCoordinator.Source.Status
                    .takeIf { StatusService.isRunning.value },
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        lifecycleHooks.dispatchCreated()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        LogUtils.d("onA11yConnected -> ${this::class.simpleName}")
        instance = this
        attachKeepAliveOverlay()
        connected = true
        toast(UiStrings.a11y_started)
        // CLEAN：告诉守护层"用户确实在用它"，并清掉之前那条恢复提醒。
        A11yGuard.onServiceConnected()
        A11yWatchdog.clearNotification(this)
        A11yWatchdog.ensureScheduled(this)
        if (currentAppUseA11y) {
            A11yRuntime.onA11yConnected(this)
        }
    }

    /**
     * CLEAN：服务被系统停用时，`onUnbind` 往往先于 `onDestroy` 到达，是另一个早期信号点。
     * 这里只做标记，真正的判定交给 `onDestroy` 里的守护自检（避免把主动关闭误判成被系统关掉）。
     */
    override fun onUnbind(intent: android.content.Intent?): Boolean {
        LogUtils.d("onA11yUnbind -> ${this::class.simpleName}")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        lifecycleHooks.dispatchDestroyed()
        super.onDestroy()
    }

    companion object {
        val a11yCn by lazy { SelectToSpeakService::class.componentName }
        val isRunning: StateFlow<Boolean>
            field = MutableStateFlow(false)

        @Volatile
        var instance: A11yService? = null
            private set
    }
}
