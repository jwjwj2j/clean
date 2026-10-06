package com.clean.click.activation

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * 激活状态的持久化。
 *
 * 设计要点：
 * 1. **双写**：`SharedPreferences` 与 `filesDir/clean/activation.dat` 各存一份，读取时取两者中
 *    更新时间较晚且校验通过的那份。用户只清理其中一处无法重置状态。
 * 2. **存的是激活码本身，不是布尔值**。每次启动都用 [ActivationCodec.verify] 重新校验，
 *    因此"把 is_activated 改成 true"这类朴素 patch 无效。
 * 3. **HMAC 自校验**：文件内容带 HMAC，防止用文本编辑器手改 `lastSeen` 来绕过时间回拨检测。
 *    注意密钥在 APK 内，这不是强完整性保护，只是提高门槛（见 spec.md §1.2）。
 *
 * v2 变更：取消设备绑定，记录里不再保存 `deviceCode`。
 * 读取时仍接受 v1 的 4 段旧格式（忽略第 4 段），但旧记录里的 v1 激活码会在
 * [ActivationCodec.verify] 阶段被判为 UNSUPPORTED_VERSION，因此兼容只是为了避免解析报错。
 */
object ActivationStore {

    private const val PREFS_NAME = "clean_activation"
    private const val KEY_CODE = "code"
    private const val KEY_ACTIVATED_AT = "activated_at"
    private const val KEY_LAST_SEEN = "last_seen"
    private const val KEY_TRIAL_START = "trial_start_at"

    private const val FILE_DIR = "clean"
    private const val FILE_NAME = "activation.dat"

    /** 一条激活记录。`code` 为空表示未激活。 */
    data class Record(
        val code: String,
        val activatedAt: Long,
        val lastSeen: Long,
    ) {
        val isEmpty: Boolean get() = code.isBlank()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun file(context: Context): File =
        File(context.filesDir, "$FILE_DIR/$FILE_NAME")

    private fun hmacKey(secret: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256")
            .digest(secret + "CLEAN-STORE-V1".toByteArray(Charsets.US_ASCII))

    private fun sign(secret: ByteArray, payload: String): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(hmacKey(secret), "HmacSHA256"))
        return mac.doFinal(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun encode(record: Record, secret: ByteArray): String {
        val payload = listOf(
            record.code,
            record.activatedAt.toString(),
            record.lastSeen.toString(),
        ).joinToString("\u0000")
        val body = android.util.Base64.encodeToString(
            payload.toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP,
        )
        return "$body.${sign(secret, body)}"
    }

    private fun decode(text: String, secret: ByteArray): Record? = runCatching {
        val index = text.lastIndexOf('.')
        if (index <= 0) return null
        val body = text.substring(0, index)
        val signature = text.substring(index + 1)
        if (!MessageDigest.isEqual(
                signature.toByteArray(Charsets.US_ASCII),
                sign(secret, body).toByteArray(Charsets.US_ASCII),
            )
        ) {
            return null
        }
        val payload = String(
            android.util.Base64.decode(body, android.util.Base64.NO_WRAP),
            Charsets.UTF_8,
        )
        val parts = payload.split('\u0000')
        // v2 为 3 段；v1 为 4 段（第 4 段是已废弃的 deviceCode），读旧记录时忽略它
        if (parts.size != 3 && parts.size != 4) return null
        Record(
            code = parts[0],
            activatedAt = parts[1].toLongOrNull() ?: return null,
            lastSeen = parts[2].toLongOrNull() ?: return null,
        )
    }.getOrNull()

    /**
     * 读取记录：两处都读，取 `lastSeen` 较大且校验通过的一份。
     * 两处都无效时返回空记录（未激活）。
     */
    fun read(context: Context, secret: ByteArray): Record {
        val local = prefs(context)
        val fromPrefs = local.getString(KEY_CODE, null)
            ?.takeIf { it.isNotBlank() }
            ?.let {
                Record(
                    code = it,
                    activatedAt = local.getLong(KEY_ACTIVATED_AT, 0L),
                    lastSeen = local.getLong(KEY_LAST_SEEN, 0L),
                )
            }
        val fromFile = file(context).takeIf { it.isFile }
            ?.let { runCatching { decode(it.readText(Charsets.UTF_8), secret) }.getOrNull() }

        val candidates = listOfNotNull(fromPrefs, fromFile).filter { !it.isEmpty }
        if (candidates.isEmpty()) return Record("", 0L, 0L)
        // 取 lastSeen 较大者；相同则取 prefs（写入更早完成）
        return candidates.maxByOrNull { it.lastSeen } ?: candidates.first()
    }

    /** 双写一条记录。任一处失败都会抛出，由调用方决定是否提示用户。 */
    fun write(context: Context, secret: ByteArray, record: Record) {
        prefs(context).edit()
            .putString(KEY_CODE, record.code)
            .putLong(KEY_ACTIVATED_AT, record.activatedAt)
            .putLong(KEY_LAST_SEEN, record.lastSeen)
            .commit()

        val target = file(context)
        target.parentFile?.mkdirs()
        // 先写临时文件再原子改名，避免写入中断留下半截内容
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.writeText(encode(record, secret), Charsets.UTF_8)
        if (!temp.renameTo(target)) {
            target.writeText(temp.readText(Charsets.UTF_8), Charsets.UTF_8)
            temp.delete()
        }
    }

    /**
     * 免费试用的起始时间（秒）。未开始过返回 0。
     *
     * 与激活记录共用同一个 SharedPreferences，但不参与签名文件格式，
     * 因此不影响既有的激活数据兼容性。
     *
     * 注意：清除应用数据会一并清掉它，试用会重新开始 —— 这是免费试用，不是安全边界，
     * 刻意不做额外的防重置加固（那会误伤正常重装的用户）。
     */
    fun readTrialStart(context: Context): Long =
        prefs(context).getLong(KEY_TRIAL_START, 0L)

    /** 首次调用写入试用起始时间；已存在则原样返回，不覆盖。 */
    fun ensureTrialStart(context: Context, now: Long): Long {
        val local = prefs(context)
        val existing = local.getLong(KEY_TRIAL_START, 0L)
        if (existing > 0L) return existing
        local.edit().putLong(KEY_TRIAL_START, now).apply()
        return now
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().commit()
        file(context).delete()
    }

    /** 更新 lastSeen：两处都要写，且只允许增大。 */
    fun touch(context: Context, secret: ByteArray, record: Record, now: Long): Record {
        if (now <= record.lastSeen) return record
        val next = record.copy(lastSeen = now)
        write(context, secret, next)
        return next
    }
}
