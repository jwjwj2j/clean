package li.gkd.app

import android.content.Intent
import android.net.Uri
import androidx.annotation.MainThread
import li.gkd.app.platform.service.ServiceController
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import li.gkd.app.text.UiStrings
import li.gkd.app.a11y.useA11yServiceEnabledFlow
import li.gkd.app.a11y.useEnabledA11yServicesFlow
import li.gkd.app.data.CrashData
import li.gkd.app.data.RawSubscription
import li.gkd.app.data.appinfo.AppInfoRepository
import li.gkd.app.data.trimCrashDataFiles
import li.gkd.app.entry.EntryActivity
import li.gkd.app.priv.AutomationService
import li.gkd.app.priv.privilegeContextFlow
import li.gkd.app.priv.uiAutomationFlow
import li.gkd.app.permission.PermissionRequests
import li.gkd.app.permission.PermissionStates
import li.gkd.app.service.A11yService
import li.gkd.app.store.AppStore
import li.gkd.app.store.FileStateStore
import li.gkd.app.store.AppStore.storeFlow
import li.gkd.app.ui.WebViewRoute
import li.gkd.app.ui.component.DialogRequests
import li.gkd.app.feature.subscription.RuleGroupState
import li.gkd.app.domain.rule.RuleGroupTarget
import li.gkd.app.ui.component.TextDialogState
import li.gkd.app.ui.home.BottomNavItem
import li.gkd.app.ui.home.HomeRoute
import li.gkd.app.ui.share.BaseViewModel
import li.gkd.app.ui.share.ActivityResultRequests
import li.gkd.app.ui.share.launchUi
import li.gkd.app.ui.share.DeletionTarget
import li.gkd.app.util.AutomatorModeOption
import li.gkd.app.util.LogUtils
import li.gkd.app.util.ShortUrlSet
import li.gkd.app.util.ThrottleTimer
import li.gkd.app.util.FolderUtils
import li.gkd.app.util.findOption
import li.gkd.app.util.json
import li.gkd.app.util.launchLogged
import li.gkd.app.util.IntentUtils
import li.gkd.app.util.runMainPost
import li.gkd.app.util.ToastUtils.toast
import li.gkd.db.Db
import li.songe.codeorigin.CallSite
import java.nio.file.Files
import kotlin.reflect.jvm.jvmName
import kotlin.time.Duration.Companion.days

data class PageScrollResetRequest(
    val id: Long,
    val navItem: BottomNavItem,
)

class MainViewModel : BaseViewModel() {
    companion object {
        private var tempTermsAccepted = false
        private var currentInstance: MainViewModel? = null

        /** Only available to the main UI after its Activity has bound the request hosts. */
        @MainThread
        fun requireCurrent(): MainViewModel = checkNotNull(currentInstance) {
            "MainViewModel is not registered; a bound MainActivity is required"
        }
    }

    init {
        LogUtils.d("MainViewModel:init")
        addCloseable {
            if (currentInstance === this) {
                currentInstance = null
            }
            LogUtils.d("MainViewModel:close")
        }
    }

    /** Called by MainActivity after binding permission and Activity Result hosts. */
    @MainThread
    fun registerCurrent() {
        currentInstance = this
    }

    /** Returns whether the start request was issued; observe StatusService for running state. */
    suspend fun enableStatusService(): Boolean {
        if (!permissionRequests.ensurePermissions(
                PermissionStates.foregroundServiceSpecialUse,
                PermissionStates.notification,
            )
        ) return false
        ServiceController.setStatusEnabled(true)
        return true
    }

    val termsStepFlow: StateFlow<Int>
        field = MutableStateFlow(0)

    fun acceptTermsStep(lastStep: Int) {
        if (termsStepFlow.value < lastStep) {
            termsStepFlow.value++
        } else {
            termsAcceptedFlow.value = true
        }
    }

    val activityResults = ActivityResultRequests()
    // CLEAN：原实现在此跳转 PrivilegeServicePage 去借特权授予权限；特权入口已封装移除，
    // 改为直接打开系统的无障碍设置页，让用户手动授权。
    val permissionRequests = PermissionRequests {
        IntentUtils.openA11ySettings()
    }

    val backStack: NavBackStack<NavKey> = NavBackStack(HomeRoute)
    val topRoute get() = backStack.last()

    private val backThrottleTimer = ThrottleTimer()

    fun popPage(@CallSite loc: String = "") = runMainPost {
        if (backThrottleTimer.expired() && backStack.size > 1) {
            val old = backStack.last()
            backStack.removeAt(backStack.lastIndex)
            LogUtils.d("popPage", "$old -> ${backStack.last()}", loc = loc)
        }
    }

    fun navigatePage(
        navKey: NavKey,
        replaced: Boolean = false,
        @CallSite loc: String = "",
    ) = runMainPost {
        if (navKey != backStack.last()) {
            val old = backStack.last()
            if (replaced) {
                backStack[backStack.lastIndex] = navKey
            } else {
                backStack.add(navKey)
            }
            LogUtils.d("navigatePage", "$old -> ${backStack.last()}", loc = loc)
        }
    }

    fun navigateWebPage(url: String) = navigatePage(WebViewRoute(url))

    val dialogRequests = DialogRequests()

    fun confirmDelete(
        title: String,
        text: String,
        targets: () -> Set<DeletionTarget> = { emptySet() },
        dismiss: () -> Unit = {},
        delete: suspend () -> Unit,
    ) = scope.launchUi {
        if (!dialogRequests.confirm(title = title, text = text, error = true)) return@launchUi
        val deletedTargets = targets()
        dismiss()
        // CLEAN：subsSheet 已随订阅板块下线
        ruleGroupState.dismissForDeletion(deletedTargets)
        ruleControlDialog.dismissForDeletion(deletedTargets)
        // Remove the owning page and its descendants synchronously, without the back-button throttle.
        val firstOwned = backStack.indexOfFirst { route -> deletedTargets.any { it.owns(route) } }
        if (firstOwned > 0) {
            while (backStack.size > firstOwned) backStack.removeAt(backStack.lastIndex)
        }
        delete()
    }

    // CLEAN：updateStatus（应用内自更新）、githubUpload 与 shareLog（把日志上传到 GKD 的
    // GitHub 仓库）全部移除。自更新会从 GKD 的发布渠道下载并安装 APK，对 CLEAN 属于
    // 装错包的行为；日志上传则会把用户数据发给第三方仓库。
    // CLEAN：subsLinkDialog（添加/修改订阅链接）与 subsSheet（订阅管理面板）已随订阅板块下线。
    // 规则来源固定为内置订阅源，用户不再需要任何订阅管理入口。

    val appOrderListState = Db.actionLogDao.queryLatestUniqueAppIds().stateLoadable()
    val appVisitOrderMapState = Db.appLastVisitDao.query().map {
        it.mapIndexed { i, appId -> appId to i }.toMap()
    }.debounce(500).stateLoadable()

    val ruleGroupState = RuleGroupState(this)
    val ruleControlDialog = li.gkd.app.feature.subscription.RuleControlDialogState()

    fun showRuleGroup(
        subscriptionId: Long,
        appId: String?,
        group: RawSubscription.RawGroupProps,
        pageAppId: String? = appId,
    ) {
        scope.launch(Dispatchers.Default) {
            group.cacheStr
            runMainPost {
                ruleGroupState.showGroup(
                    when (group) {
                        is RawSubscription.RawAppGroup -> RuleGroupTarget.App(
                            subsId = subscriptionId,
                            appId = appId ?: error("require appId"),
                            groupKey = group.key,
                        )

                        is RawSubscription.RawGlobalGroup -> RuleGroupTarget.Global(
                            subsId = subscriptionId,
                            groupKey = group.key,
                            pageAppId = pageAppId,
                        )
                    },
                )
            }
        }
    }

    val textDialog = TextDialogState()

    fun openUrl(url: String) {
        textDialog.showUrl(url)
    }

    val tabFlow: StateFlow<Int>
        field = MutableStateFlow(BottomNavItem.Dashboard.key)
    val pageScrollResetRequestFlow: StateFlow<PageScrollResetRequest?>
        field = MutableStateFlow(null)
    private var nextPageScrollResetRequestId = 0L
    private var lastClickTabTime = 0L
    fun handleClickTab(navItem: BottomNavItem) {
        val t = System.currentTimeMillis()
        if (navItem.key != tabFlow.value) {
            pageScrollResetRequestFlow.value = null
        }
        // double click
        if (navItem.key == tabFlow.value && t - lastClickTabTime < 500) {
            pageScrollResetRequestFlow.value = PageScrollResetRequest(
                id = ++nextPageScrollResetRequestId,
                navItem = navItem,
            )
        }
        tabFlow.value = navItem.key
        lastClickTabTime = t
    }

    fun consumePageScrollResetRequest(request: PageScrollResetRequest) {
        pageScrollResetRequestFlow.compareAndSet(request, null)
    }

    fun handleGkdUri(uri: Uri) {
        val notFoundToast = { toast(UiStrings.uri_unknown(uri)) }
        when (uri.host) {
            "page" -> when (uri.path) {
                "" -> runMainPost {
                    val tab = uri.getQueryParameter("tab")?.toIntOrNull()
                    if (tab != null && BottomNavItem.allSubObjects.any { it.key == tab }) {
                        tabFlow.value = tab
                    }
                    // MainActivity 被复用时，也需要返回首页。
                    backStack.subList(1, backStack.size).clear()
                }

                // CLEAN：gkd://page/1（高级设置）与 /2（快照）已下线，交由 else 提示
                // CLEAN：gkd://page/1（高级设置）、/2（快照）、/3 与 /4（特权服务）对应的
                // 页面均已下线，统一交由 else 提示。
                else -> notFoundToast()
            }

            "invoke" -> when (uri.path) {
                "/1" -> IntentUtils.openWeChatScaner()
                else -> notFoundToast()
            }

            else -> notFoundToast()
        }
    }

    fun handleIntent(intent: Intent) = scope.launchUi {
        LogUtils.d(intent)
        val uri = intent.data?.normalizeScheme()
        val source = intent.getStringExtra(EntryActivity.activityNavSourceName)
        // CLEAN：URL scheme 由 gkd:// 改为 clean://（与 AndroidManifest 的 intent-filter 一致）
        if (uri?.scheme == "clean") {
            handleGkdUri(uri)
        }
        // CLEAN：原 OpenFileActivity 分支（zip 备份导入）已随备份功能移除
    }

    val termsAcceptedFlow: StateFlow<Boolean>
        field: MutableStateFlow<Boolean> = if (tempTermsAccepted) {
            MutableStateFlow(true)
        } else {
            FileStateStore.createTextFlow(
                key = "terms_accepted",
                decode = { it == "true" },
                encode = {
                    tempTermsAccepted = it
                    it.toString()
                },
                scope = scope,
            ).apply {
                tempTermsAccepted = value
            }
        }

    private val a11yServicesFlow = useEnabledA11yServicesFlow(scope)
    val a11yServiceEnabledFlow = useA11yServiceEnabledFlow(scope, a11yServicesFlow)

    val automatorModeFlow = storeFlow.mapNew {
        AutomatorModeOption.objects.findOption(it.automatorMode)
    }

    // CLEAN：原 updateAutomatorMode / applyAutomatorMode 用于在「工作模式」页切换
    // 无障碍模式与自动化模式（特权）。该页面已删除，且特权入口整体封装下线，
    // 因此这两个函数不再有任何调用者，一并移除（automatorModeFlow 仍用于仪表盘展示）。

    private var tempCrashDataList = emptyList<CrashData>()

    fun takeCrashDataList(): List<CrashData> = tempCrashDataList.also {
        tempCrashDataList = emptyList()
    }

    init {
        // preload
        AppInfoRepository.appIconMapFlow.value
        scope.launchLogged(Dispatchers.IO) {
            // 每次进入删除缓存
            FolderUtils.clearCache()
        }

        // CLEAN：原有的启动自更新检查已移除（改由分发渠道更新）
        scope.launchLogged(Dispatchers.IO) {
            trimCrashDataFiles()
            val list = (FolderUtils.crashTempFolder.listFiles() ?: emptyArray()).mapNotNull {
                try {
                    json.decodeFromString<CrashData>(it.readText())
                } catch (e: Exception) {
                    LogUtils.d("解析崩溃日志失败: ${it.name}", e)
                    null
                }
            }.sortedBy { -it.mtime }
            FolderUtils.crashTempFolder.deleteRecursively()
            val t = System.currentTimeMillis()
            FolderUtils.crashFolder.listFiles()?.filter {
                val name = it.name
                !list.any { f -> name == f.filename }
            }?.forEach {
                val mtime = Files.getLastModifiedTime(it.toPath()).toMillis()
                if (t - mtime > 30.days.inWholeMilliseconds) {
                    it.delete()
                }
            }
            tempCrashDataList = list
            if (list.isNotEmpty()) {
                // CLEAN：崩溃报告页面已随技术面收口下线。
                // 崩溃数据仍会写入 crash 目录（供客服索取），但不再自动跳转页面。
                LogUtils.d("检测到崩溃记录", list.size)
            }
        }

    }
}
