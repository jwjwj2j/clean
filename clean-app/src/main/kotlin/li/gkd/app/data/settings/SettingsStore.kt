package li.gkd.app.data.settings

import kotlinx.serialization.Serializable
import li.gkd.app.text.UiStrings
import li.gkd.app.META
import li.gkd.app.util.AppGroupOption
import li.gkd.app.util.AppSortOption
import li.gkd.app.util.AutomatorModeOption
import li.gkd.app.util.RuleSortOption
import li.gkd.app.util.UpdateTimeOption

@Serializable
data class SettingsStore(
    val enableAutomator: Boolean = false,
    val automatorMode: Int = AutomatorModeOption.A11yMode.value,
    val enableMatch: Boolean = true,
    val enableStatusService: Boolean = false,
    val excludeFromRecents: Boolean = false,
    // CLEAN：captureScreenshot / screenshotTargetAppId / screenshotEventSelector / httpServerPort /
    // captureVolumeChange / hideSnapshotStatusBar / autoSaveSnapshotToDownloads / snapshotDisplayMode
    // 随快照与 HTTP 服务一并删除。老配置文件中的这些字段由 json 的 ignoreUnknownKeys 忽略。
    val updateSubsInterval: Long = UpdateTimeOption.Everyday.value,
    val toastWhenClick: Boolean = true,
    val actionToast: String = META.appName,
    val autoClearMemorySubs: Boolean = false,
    val enableDarkTheme: Boolean? = null,
    // CLEAN：enableDynamicColor（动态配色）已随该设置项移除，应用固定使用 CLEAN 配色方案。
    // 老配置中的该字段由 json 的 ignoreUnknownKeys 忽略。
    val useSystemToast: Boolean = false,
    val useCustomNotifText: Boolean = false,
    val customNotifTitle: String = META.appName,
    val customNotifText: String = UiStrings.notification_summary_template,
    // CLEAN：updateChannel（更新渠道）已随应用内自更新移除；老配置中的该字段由 ignoreUnknownKeys 忽略。
    val appSort: Int = AppSortOption.ByUsedTime.value,
    val showBlockApp: Boolean = true,
    val appRuleSort: Int = RuleSortOption.ByDefault.value,
    val subsAppSort: Int = AppSortOption.ByUsedTime.value,
    val subsCategorySort: Int = AppSortOption.ByUsedTime.value,
    val subsAppShowUninstall: Boolean = false,
    val subsAppGroupType: Int = AppGroupOption.UserGroup.value or AppGroupOption.SystemGroup.value,
    val subsCategoryGroupType: Int = AppGroupOption.UserGroup.value or AppGroupOption.SystemGroup.value,
    val subsAppShowBlock: Boolean = false,
    val subsCategoryShowBlock: Boolean = false,
    val subsExcludeSort: Int = AppSortOption.ByUsedTime.value,
    val subsExcludeShowBlockApp: Boolean = true,
    val subsExcludeShowInnerDisabledApp: Boolean = true,
    val subsPowerWarn: Boolean = true,
    val enableBlockA11yAppList: Boolean = false,
    val blockA11yAppListFollowMatch: Boolean = true,
    val a11yAppSort: Int = AppSortOption.ByUsedTime.value,
    val a11yScopeAppSort: Int = AppSortOption.ByUsedTime.value,
    val appGroupType: Int = (1 shl AppGroupOption.normalObjects.size) - 1,
    val a11yAppGroupType: Int = appGroupType,
    val a11yScopeAppGroupType: Int = appGroupType,
    val subsExcludeAppGroupType: Int = appGroupType,
    val showDisabledRule: Boolean = true,
) {
    val useA11y get() = automatorMode == AutomatorModeOption.A11yMode.value
    val useAutomation get() = automatorMode == AutomatorModeOption.AutomationMode.value
}
