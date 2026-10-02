package com.clean.click.activation

import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * CLEAN 激活码纯算法实现。
 *
 * 与 `keygen/keygen.py`、`keygen/keygen.html` 逐位一致，规范见 `keygen/spec.md`。
 * 本文件**不依赖任何 Android API**，可以直接在 JVM 单元测试中运行，
 * 这使得三端一致性可以被自动化测试守住。
 *
 * 修改本文件前请先读 spec.md，改完必须跑 ActivationCodecTest。
 */
object ActivationCodec {

    /** Crockford Base32：无 I / L / O / U，避免与 1 / 0 混淆。 */
    const val ALPHABET: String = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    private const val DEVICE_DOMAIN = "CLEAN-DEV-V1|"
    private val ACT_DOMAIN: ByteArray = "CLEAN-ACT-V1".toByteArray(Charsets.US_ASCII)

    /** 激活码结构版本，v1 固定为 1。 */
    const val VERSION: Int = 1

    /** 时间基准日；expiryDays 是相对它的天数，0 表示永久。 */
    val EPOCH: LocalDate = LocalDate.of(2020, 1, 1)

    const val CODE_LENGTH: Int = 24
    const val DEVICE_CODE_LENGTH: Int = 10
    const val DEV24_LENGTH: Int = 3
    const val MAC_LENGTH: Int = 9
    const val CODE_BYTES: Int = 15
    private const val DEVICE_HASH_PREFIX_BYTES: Int = 8

    private const val MAX_EXPIRY_DAYS: Int = 0xFFFF

    private val INDEX: IntArray = IntArray(128) { -1 }.also { table ->
        ALPHABET.forEachIndexed { index, ch -> table[ch.code] = index }
    }

    private val formatter: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    private val macThreadLocal = ThreadLocal.withInitial {
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(ByteArray(32), "HmacSHA256")) }
    }

    // ----------------------------------------------------------------------
    // Base32（MSB-first，无填充）
    // ----------------------------------------------------------------------

    /**
     * 去除非字母数字 -> 转大写 -> I/L 视为 1、O 视为 0。
     * 不校验字符是否在字母表内（U 等非法字符留给 [isValidAlphabet] 判失败）。
     */
    fun normalize(text: String): String {
        val out = StringBuilder(text.length)
        for (raw in text) {
            if (!raw.isLetterOrDigit()) continue
            when (val ch = raw.uppercaseChar()) {
                'I', 'L' -> out.append('1')
                'O' -> out.append('0')
                else -> out.append(ch)
            }
        }
        return out.toString()
    }

    fun isValidAlphabet(text: String): Boolean =
        text.all { it.code < INDEX.size && INDEX[it.code] >= 0 }

    /** 把字节流按 5 位一组从高位编码；末组不足 5 位时左移补零。 */
    fun base32Encode(data: ByteArray): String {
        val out = StringBuilder((data.size * 8 + 4) / 5)
        var buffer = 0L
        var bits = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toLong() and 0xFF)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                out.append(ALPHABET[((buffer shr bits) and 0x1F).toInt()])
            }
        }
        if (bits > 0) {
            out.append(ALPHABET[((buffer shl (5 - bits)) and 0x1F).toInt()])
        }
        return out.toString()
    }

    /** 解码；末尾不足 8 位的残余位被丢弃（本规范所有长度都不依赖残余位）。 */
    fun base32Decode(text: String): ByteArray {
        val out = java.io.ByteArrayOutputStream(text.length * 5 / 8 + 1)
        var buffer = 0L
        var bits = 0
        for (ch in text) {
            val value = if (ch.code < INDEX.size) INDEX[ch.code] else -1
            require(value >= 0) { "非法的 Base32 字符: $ch" }
            buffer = (buffer shl 5) or value.toLong()
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.write(((buffer shr bits) and 0xFF).toInt())
            }
        }
        return out.toByteArray()
    }

    /** 展示用分组，例如 24 字符 -> 6 组 4 字符，10 字符 -> 2 组 5 字符。 */
    fun group(text: String, size: Int): String =
        text.chunked(size).joinToString("-")

    // ----------------------------------------------------------------------
    // 设备指纹
    // ----------------------------------------------------------------------

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    /** H = SHA-256("CLEAN-DEV-V1|" + deviceSecret)，32 字节。 */
    fun deviceHash(deviceSecret: String): ByteArray =
        sha256((DEVICE_DOMAIN + deviceSecret).toByteArray(Charsets.UTF_8))

    /** 展示给用户的设备码：H 前 8 字节编码后的前 10 个字符。 */
    fun deviceCodeOf(deviceSecret: String): String =
        base32Encode(deviceHash(deviceSecret).copyOf(DEVICE_HASH_PREFIX_BYTES))
            .take(DEVICE_CODE_LENGTH)

    /** 激活码中嵌入的 24 位绑定值。 */
    fun dev24Of(deviceSecret: String): ByteArray =
        deviceHash(deviceSecret).copyOf(DEV24_LENGTH)

    /**
     * 卖家侧：从用户报来的设备码还原 dev24。
     *
     * 设备码 10 字符 = 50 位，完整包含 H 的前 24 位（见 spec.md §4.2），
     * 因此解码后取前 3 字节即可，与服务端无关。
     */
    fun dev24FromDeviceCode(deviceCode: String): ByteArray {
        val normalized = normalize(deviceCode)
        require(normalized.length == DEVICE_CODE_LENGTH) {
            "设备码必须是 $DEVICE_CODE_LENGTH 个字符，当前 ${normalized.length} 个"
        }
        require(isValidAlphabet(normalized)) { "设备码含非法字符（字母表不含 I/L/O/U）" }
        val raw = base32Decode(normalized)
        require(raw.size >= DEV24_LENGTH) { "设备码解码结果过短" }
        return raw.copyOf(DEV24_LENGTH)
    }

    // ----------------------------------------------------------------------
    // 有效期
    // ----------------------------------------------------------------------

    /** 自今天起 [days] 天的字段值；0 表示永久。 */
    fun expiryDaysFromToday(days: Int, today: LocalDate = LocalDate.now()): Int {
        require(days >= 0) { "有效期不能为负数（0 表示永久）" }
        if (days == 0) return 0
        val limit = MAX_EXPIRY_DAYS - java.time.temporal.ChronoUnit.DAYS.between(EPOCH, today).toInt()
        require(days <= limit) { "有效期过长：自今天起最多可签发 $limit 天" }
        return java.time.temporal.ChronoUnit.DAYS.between(EPOCH, today).toInt() + days
    }

    fun expiryDate(days: Int): LocalDate? =
        if (days == 0) null else EPOCH.plusDays(days.toLong())

    fun describeExpiry(days: Int): String =
        expiryDate(days)?.let { "有效至 ${it.format(formatter)}" } ?: "永久有效"

    // ----------------------------------------------------------------------
    // 生成 / 解析 / 校验
    // ----------------------------------------------------------------------

    private fun mac(secret: ByteArray, payload: ByteArray): ByteArray {
        val mac = macThreadLocal.get()!!
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        return mac.doFinal(ACT_DOMAIN + payload).copyOf(MAC_LENGTH)
    }

    /** 组装 6 字节 payload：version|tier、expiryDays(大端)、dev24。 */
    fun buildPayload(tier: Int, expiryDays: Int, dev24: ByteArray): ByteArray {
        require(tier in 0..0x0F) { "tier 必须在 0..15" }
        require(expiryDays in 0..MAX_EXPIRY_DAYS) { "expiryDays 必须在 0..65535" }
        require(dev24.size == DEV24_LENGTH) { "dev24 必须是 $DEV24_LENGTH 字节" }
        return byteArrayOf(
            ((VERSION shl 4) or tier).toByte(),
            ((expiryDays shr 8) and 0xFF).toByte(),
            (expiryDays and 0xFF).toByte(),
            dev24[0], dev24[1], dev24[2],
        )
    }

    /** 生成激活码（返回 24 字符规范形式）。 */
    fun generateCode(secret: ByteArray, deviceCode: String, expiryDays: Int, tier: Int = 0): String {
        val payload = buildPayload(tier, expiryDays, dev24FromDeviceCode(deviceCode))
        return base32Encode(payload + mac(secret, payload))
    }

    /** 仅做结构解析，不验签。 */
    fun parseCode(code: String): ParsedCode {
        val normalized = normalize(code)
        require(normalized.length == CODE_LENGTH) {
            "激活码必须是 $CODE_LENGTH 个字符，当前 ${normalized.length} 个"
        }
        require(isValidAlphabet(normalized)) { "激活码含非法字符" }
        val raw = base32Decode(normalized)
        require(raw.size == CODE_BYTES) { "激活码解码长度异常: ${raw.size}" }
        val expiryDays = ((raw[1].toInt() and 0xFF) shl 8) or (raw[2].toInt() and 0xFF)
        return ParsedCode(
            normalized = normalized,
            display = group(normalized, 4),
            version = (raw[0].toInt() and 0xFF) shr 4,
            tier = raw[0].toInt() and 0x0F,
            expiryDays = expiryDays,
            expiryDate = expiryDate(expiryDays),
            dev24 = raw.copyOfRange(3, 6),
            mac = raw.copyOfRange(6, 15),
        )
    }

    /**
     * 完整校验。校验顺序见 spec.md §8.1：
     * 先长度/版本，再验签，最后才比对设备——避免把设备比对变成试错预言机。
     */
    fun verify(
        secret: ByteArray,
        code: String,
        deviceSecret: String,
        today: LocalDate = LocalDate.now(),
    ): ActivationResult {
        val normalized = normalize(code)
        if (normalized.length != CODE_LENGTH || !isValidAlphabet(normalized)) {
            return ActivationResult.Failure(ActivationError.Malformed)
        }
        val raw = base32Decode(normalized)
        if (raw.size != CODE_BYTES) return ActivationResult.Failure(ActivationError.Malformed)

        val payload = raw.copyOfRange(0, 6)
        val macFromCode = raw.copyOfRange(6, 15)

        if (((payload[0].toInt() and 0xFF) shr 4) != VERSION) {
            return ActivationResult.Failure(ActivationError.UnsupportedVersion)
        }
        // MessageDigest.isEqual 是常量时间比较
        if (!MessageDigest.isEqual(mac(secret, payload), macFromCode)) {
            return ActivationResult.Failure(ActivationError.BadSignature)
        }
        if (!MessageDigest.isEqual(dev24Of(deviceSecret), payload.copyOfRange(3, 6))) {
            return ActivationResult.Failure(ActivationError.DeviceMismatch)
        }

        val expiryDays = ((payload[1].toInt() and 0xFF) shl 8) or (payload[2].toInt() and 0xFF)
        if (expiryDays != 0) {
            val elapsed = java.time.temporal.ChronoUnit.DAYS.between(EPOCH, today).toInt()
            if (elapsed > expiryDays) {
                return ActivationResult.Failure(
                    ActivationError.Expired,
                    expiredAt = expiryDate(expiryDays),
                )
            }
        }
        return ActivationResult.Success(
            version = VERSION,
            tier = payload[0].toInt() and 0x0F,
            expiryDays = expiryDays,
            expiryDate = expiryDate(expiryDays),
            permanent = expiryDays == 0,
        )
    }
}

/** [ActivationCodec.parseCode] 的结果。 */
data class ParsedCode(
    val normalized: String,
    val display: String,
    val version: Int,
    val tier: Int,
    val expiryDays: Int,
    val expiryDate: LocalDate?,
    val dev24: ByteArray,
    val mac: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is ParsedCode && other.normalized == normalized

    override fun hashCode(): Int = normalized.hashCode()

    val permanent: Boolean get() = expiryDays == 0
}

/** 校验失败原因。文案见 spec.md §8.2。 */
enum class ActivationError {
    Malformed,
    UnsupportedVersion,
    BadSignature,
    DeviceMismatch,
    Expired,
    ClockAnomaly,
}

sealed class ActivationResult {
    data class Success(
        val version: Int,
        val tier: Int,
        val expiryDays: Int,
        val expiryDate: LocalDate?,
        val permanent: Boolean,
    ) : ActivationResult()

    data class Failure(
        val error: ActivationError,
        val expiredAt: LocalDate? = null,
    ) : ActivationResult()
}
