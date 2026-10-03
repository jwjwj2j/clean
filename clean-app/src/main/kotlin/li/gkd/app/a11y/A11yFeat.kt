package li.gkd.app.a11y

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import li.gkd.app.app
import li.gkd.app.appScope
import li.gkd.app.store.AppStore.storeFlow
import li.gkd.app.util.LogUtils
import li.gkd.app.util.ScreenUtils
import li.gkd.app.data.subscription.SubscriptionResult
import li.gkd.app.data.subscription.SubscriptionRepository
import li.gkd.app.util.UpdateTimeOption
import li.gkd.app.util.launchLogged
import li.gkd.app.util.mapState
import li.gkd.selector.MatchOptions
import li.gkd.selector.Selector
import li.gkd.selector.SelectorCompileResult
import li.gkd.selector.NodeAdapter


fun onA11yFeatEvent(event: AccessibilityEvent) = event.run {
    // CLEAN：原 watchCaptureScreenshot()（按无障碍事件自动抓快照）已随快照功能下线
    if (event.packageName == launcherAppId) {
        watchAutoUpdateSubs()
    }
}
private fun AccessibilityEvent.getEventAttr(name: String): Any? = when (name) {
    "name" -> className
    "desc" -> contentDescription
    "text" -> text
    else -> null
}

private object A11yEventNodeAdapter : NodeAdapter<AccessibilityEvent>() {
    override fun getAttr(target: Any, name: String): Any? = when (target) {
        is AccessibilityEvent -> target.getEventAttr(name)
        is List<*> -> when (name) {
            "size" -> target.size
            else -> null
        }

        else -> null
    }

    override fun getInvoke(target: Any, name: String, args: List<Any>): Any? = when (target) {
        is List<*> -> when (name) {
            "get" -> (args.singleOrNull() as? Int)?.let(target::getOrNull)
            else -> null
        }

        else -> null
    }

    override fun getName(node: AccessibilityEvent): String? = node.className?.toString()

    override fun getChildCount(node: AccessibilityEvent): Int = 0

    override fun getChild(node: AccessibilityEvent, index: Int): AccessibilityEvent? = null

    override fun getParent(node: AccessibilityEvent): AccessibilityEvent? = null

    override fun getNodeKey(node: AccessibilityEvent): Any = node
}

private val a11yEventAdapter = A11yEventNodeAdapter

// CLEAN：watchCaptureScreenshot() 已删除。它曾用 SnapshotCapture.isCapturing 作为无障碍事件的
// 处理守卫，并调用 SnapshotCapture.capture()；快照功能下线后该守卫不再需要。
// 注意：A11yState.hasFeatureAction 与快照无关，必须保留（规则引擎依赖它维持存活）。

private var lastUpdateSubsTime = 0L
private var autoRefreshPending = false
private fun watchAutoUpdateSubs() {
    val interval = storeFlow.value.updateSubsInterval
    if (interval <= 0 || autoRefreshPending) return
    val currentTime = System.currentTimeMillis()
    if (
        currentTime - lastUpdateSubsTime <=
        interval.coerceAtLeast(UpdateTimeOption.Everyday.value)
    ) return
    autoRefreshPending = true
    appScope.launchLogged {
        try {
            val result = SubscriptionRepository.refresh()
            if (result !is SubscriptionResult.Busy) {
                lastUpdateSubsTime = currentTime
            }
        } finally {
            autoRefreshPending = false
        }
    }
}

private fun initRuleChangedLog() {
    appScope.launch(Dispatchers.Default) {
        activityRuleFlow.debounce(300).drop(1).collect {
            if (storeFlow.value.enableMatch && it.currentRules.isNotEmpty()) {
                LogUtils.d(it.topActivity, *it.currentRules.map { r ->
                    r.statusText()
                }.toTypedArray())
            }
        }
    }
}

// CLEAN：音量键触发抓快照（createVolumeReceiver / initCaptureVolume）已随快照功能整体删除。

var isInteractive = true
    private set
private val screenStateReceiver = object : BroadcastReceiver() {
    override fun onReceive(
        context: Context?,
        intent: Intent?
    ) {
        val action = intent?.action ?: return
        LogUtils.d("screenStateReceiver->${action}")
        isInteractive = when (action) {
            Intent.ACTION_SCREEN_ON -> true
            Intent.ACTION_SCREEN_OFF -> false
            Intent.ACTION_USER_PRESENT -> true
            else -> isInteractive
        }
        if (isInteractive) {
            val t = System.currentTimeMillis()
            if (t - appChangeTime > 500) { // 37.872(a11y) -> 38.228(onReceive)
                A11yRuntime.onScreenForcedActive()
            }
        }
    }
}

private fun initScreenStateReceiver() {
    isInteractive = app.powerManager.isInteractive
    ContextCompat.registerReceiver(
        app,
        screenStateReceiver,
        IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        },
        ContextCompat.RECEIVER_EXPORTED
    )
}

fun initA11yFeat() {
    initRuleChangedLog()
    initScreenStateReceiver()
}
