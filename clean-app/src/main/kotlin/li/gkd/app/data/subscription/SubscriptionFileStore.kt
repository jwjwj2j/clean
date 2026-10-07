package li.gkd.app.data.subscription

import android.util.AtomicFile
import li.gkd.app.text.UiStrings
import li.gkd.app.data.RawSubscription
import li.gkd.app.util.FolderUtils
import li.gkd.app.util.json
import li.gkd.db.LOCAL_HTTP_SUBS_ID
import li.gkd.db.LOCAL_SUBS_ID
import java.io.File
import java.io.FileOutputStream

object SubscriptionFileStore {
    fun load(id: Long): RawSubscription {
        val file = file(id)
        if (!file.exists()) {
            return when (id) {
                LOCAL_SUBS_ID -> RawSubscription(id = id, name = UiStrings.subscription_local, version = 0)
                LOCAL_HTTP_SUBS_ID -> RawSubscription(id = id, name = UiStrings.subscription_memory, version = 0)
                else -> error(UiStrings.subscription_file_missing)
            }
        }
        val subscription = try {
            RawSubscription.parse(file.readText(), json5 = false).branded()
        } catch (e: Exception) {
            throw Exception(UiStrings.subscription_file_parse_failed, e)
        }
        if (subscription.id != id) error(UiStrings.subscription_file_id_mismatch)
        return subscription
    }

    fun readBytes(id: Long): ByteArray? = file(id).takeIf { it.exists() }?.readBytes()

    fun write(subscription: RawSubscription) {
        writeBytes(subscription.id, json.encodeToString(subscription).encodeToByteArray())
    }

    fun restore(id: Long, bytes: ByteArray?) {
        if (bytes == null) {
            delete(id)
        } else {
            writeBytes(id, bytes)
        }
    }

    fun delete(id: Long) {
        val file = file(id)
        AtomicFile(file).delete()
        if (file.exists()) error(UiStrings.file_delete_failed(file.name))
    }

    private fun file(id: Long): File = FolderUtils.subsFolder.resolve("$id.json")

    private fun writeBytes(id: Long, bytes: ByteArray) {
        val atomicFile = AtomicFile(file(id))
        var output: FileOutputStream? = null
        try {
            output = atomicFile.startWrite()
            output.write(bytes)
            atomicFile.finishWrite(output)
        } catch (e: Exception) {
            atomicFile.failWrite(output)
            throw e
        }
    }
}

/**
 * 订阅在 App 内的统一显示名。
 *
 * 规则来自第三方公开订阅，但 CLEAN 是**运行时拉取**、并不再分发，因此没有必须在界面上
 * 标注来源的许可义务；这里统一成自有名称，避免界面上出现与本产品无关的第三方标识。
 *
 * 注意：这只是**显示名**。规则来源在其他地方仍需如实记录（开源仓库说明、以及一旦
 * 将来要把规则打包进 APK 时 —— 那时的署名就是强制义务了）。
 */
const val DISPLAY_SUBSCRIPTION_NAME = "CLEAN 规则"

/**
 * 把订阅的来源名称替换为 [DISPLAY_SUBSCRIPTION_NAME]。
 *
 * 在**解析与读取**两处都调用：
 * - 解析处：新下载/更新的订阅一进来就改名，落盘即为自有名称；
 * - 读取处（[SubscriptionFileStore.load]）：兜住升级前已经落盘的旧数据，
 *   否则已装过旧版本的用户界面上仍会显示旧名称。
 */
fun RawSubscription.branded(): RawSubscription =
    if (name == DISPLAY_SUBSCRIPTION_NAME) this
    else copy(name = DISPLAY_SUBSCRIPTION_NAME)
