package li.gkd.app.util

import android.text.format.DateUtils
import androidx.annotation.WorkerThread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import li.gkd.app.META
import li.gkd.app.app
import li.gkd.app.data.appinfo.AppInfoRepository
import li.gkd.app.data.AppInfo
import li.gkd.app.data.UserInfo
import li.gkd.app.permission.PermissionStates
import li.gkd.app.priv.currentUserId
import java.io.File

object FolderUtils {
    private val filesDir: File by lazy {
        val markFile = app.filesDir.resolve(".gkd")
        if (markFile.isFile) {
            app.filesDir
        } else {
            // fix #1333
            app.getExternalFilesDir(null) ?: app.filesDir.also {
                markFile.createNewFile()
            }
        }
    }

    val dbFolder: File
        get() = filesDir.resolve("db").autoMk()
    val shFolder: File
        get() = filesDir.resolve("sh").autoMk()
    val storeFolder: File
        get() = filesDir.resolve("store").autoMk()
    val subsFolder: File
        get() = filesDir.resolve("subscription").autoMk()
    val snapshotFolder: File
        get() = filesDir.resolve("snapshot").autoMk()
    val logFolder: File
        get() = filesDir.resolve("log").autoMk()
    val crashFolder: File
        get() = filesDir.resolve("crash").autoMk()
    val crashTempFolder: File
        get() = filesDir.resolve("crash/temp").autoMk()

    val privateStoreFolder: File
        get() = app.filesDir.resolve("private-store").autoMk()

    private val cacheDir by lazy { app.externalCacheDir ?: app.cacheDir }
    val coilCacheDir: File
        get() = cacheDir.resolve("coil").autoMk()
    val sharedDir: File
        get() = cacheDir.resolve("shared").autoMk()
    private val tempDir: File
        get() = cacheDir.resolve("temp").autoMk()

    fun createGkdTempDir(): File {
        return tempDir
            .resolve(System.currentTimeMillis().toString())
            .apply { mkdirs() }
    }

    private fun removeExpired(dir: File) {
        dir.listFiles()?.forEach { file ->
            if (System.currentTimeMillis() - file.lastModified() > DateUtils.HOUR_IN_MILLIS) {
                if (file.isDirectory) {
                    file.deleteRecursively()
                } else if (file.isFile) {
                    file.delete()
                }
            }
        }
    }

    fun clearCache() {
        removeExpired(sharedDir)
        removeExpired(tempDir)
    }

    // CLEAN：原 deleteSharedFile() 与 withTemporaryZip() 是备份导出的配套缓存清理工具，
    // 只被设置页的「备份与恢复」流程使用；备份功能移除后已无调用者，一并删除。

    @Serializable
    private data class AppJsonData(
        val userId: Int = currentUserId,
        val apps: List<AppInfo> = AppInfoRepository.userAppInfoMapFlow.value.values.toList(),
        val otherUsers: List<UserInfo> = AppInfoRepository.otherUserMapFlow.value.values.toList(),
        val othersApps: List<AppInfo> = AppInfoRepository.otherUserAppInfoMapFlow.value.values.toList(),
    )

    // CLEAN：原 buildLogFile() 会打包 db/store/subs/log/crash 目录、应用列表、权限清单与
    // META 信息，供「分享日志」上传到 GKD 的 GitHub 仓库。日志上传已移除，故一并删除。
    //
    // 注意：该函数还依赖 assets/source-paths.txt，而生成它的 GenerateSourcePathsTask
    // 已随 api.gkd.li 构建产物上传一并删除 —— 保留此函数会在运行期直接抛 asset 缺失异常。
}
