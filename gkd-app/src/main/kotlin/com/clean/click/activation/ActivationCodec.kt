package com.clean.click.activation

import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * CLEAN 激活码纯算法实现（结构版本 v3）。
 *
 * 与 `keygen/keygen.py`、`keygen/keygen.html`、`keygen/selftest.mjs` 逐位一致，
 * 规范见 `keygen/spec.md`。本文件**不依赖任何 Android API**，可直接在 JVM 单元测试中运行，
 * 这使得多端一致性可以被自动化测试守住。
 *
 * ## 结构版本演进
 *
 * | 版本 | 载荷 | MAC | 总长 | 特点 |
 * | --- | --- | --- | --- | --- |
 * | v1 | 6 字节（含 dev24 设备哈希） | 9 | 15 字节 / 24 字符 | 绑定设备 |
 * | v2 | 6 字节（dev24 → issueMinutes） | 9 | 15 字节 / 24 字符 | 取消绑定，限时激活窗口 |
 * | **v3** | **7 字节（+ 1 字节序号）** | **8** | **15 字节 / 24 字符** | **码唯一，不再同分钟重复** |
 *
 * v3 修掉了一个致命缺陷：v2 的载荷只有「版本|档位 / 有效期天数 / 签发分钟数」三个字段，
 * **没有任何唯一性字段**。于是同一分钟内、同参数生成的码**完全相同**——
 * 批量出 50 条会得到 50 份同一个码，无法分发给不同客户。v3 增加 1 字节 `serial`，
 * 同一分钟内可产出 256 个互不相同的码。
 *
 * MAC 由 9 字节缩到 8 字节（72 → 64 位）。盲猜成功率 2⁻⁶⁴ ≈ 5.4×10⁻²⁰，
 * 仍是不可行的；真正的攻击面是「从 APK 提取密钥」，那与 MAC 长度无关（见 spec.md §1.2）。
 *
 * 窗口语义与 v2 相同：签发后 [WINDOW_MINUTES] 分钟内必须激活，激活后终身有效；
 * 复检已存码时必须传 `enforceWindow = false`，否则已激活设备会自我停用。
 *
 * 修改本文件前请先读 spec.md，改完必须跑 ActivationCodecTest。
 */
object ActivationCodec {

    /** Crockford Base32：无 I / L / O / U，避免与 1 / 0 混淆。 */
    const val ALPHABET: String = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    private val ACT_DOMAIN: ByteArray = "CLEAN-ACT-V3".toByteArray(Charsets.US_ASCII)

    /** 激活码结构版本。v1 / v2 的码会被判为 [ActivationError.UnsupportedVersion]。 */
    const val VERSION: Int = 3

    /** 时间基准日：`expiryDays` 相对它的天数，`issueMinutes` 相对它当天 00:00 UTC 的分钟数。 */
    val EPOCH: LocalDate = LocalDate.of(2020, 1, 1)

    /** 时间基准时刻：EPOCH 当天 00:00:00 UTC。 */
    val EPOCH_INSTANT: Instant = EPOCH.atStartOfDay(ZoneOffset.UTC).toInstant()

    const val CODE_LENGTH: Int = 24
    const val MAC_LENGTH: Int = 8
    const val CODE_BYTES: Int = 15
    const val PAYLOAD_BYTES: Int = 7

    /** 签发后必须在这么多分钟内完成激活。 */
    const val WINDOW_MINUTES: Int = 10

    /**
     * 时钟偏斜容忍：允许设备时钟比签发时刻**慢**这么多分钟。
     * 纯粹是为了吸收 NTP 误差与人工操作延迟，不构成安全边界。
     */
    const val CLOCK_SKEW_MINUTES: Int = 5

    /** `issueMinutes` 是 24 位，最大可表示 16,777,215 分钟 ≈ 31.9 年（覆盖到 2051 年）。 */
    const val MAX_ISSUE_MINUTES: Int = 0xFF_FFFF

    /** 序号字段取值范围：同一分钟内最多 256 个互不相同的码。 */
    const val MAX_SERIAL: Int = 0xFF

    private const val MAX_EXPIRY_DAYS: Int = 0xFFFF

    private val INDEX: IntArray = IntArray(128) { -1 }.also { table ->
        ALPHABET.forEachIndexed { index, ch -> table[ch.code] = index }
    }

    private val formatter: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    private val dateTimeFormatter: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    private val macThreadLocal = ThreadLocal.withInitial {
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(ByteArray(32), "HmacSHA256")) }
    }

    // ----------------------------------------------------------------------
    // Base32（MSB-first，无填充）—— 自 v1 起未改动，不得改动
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

    /** 展示用分组，例如 24 字符 -> 6 组 4 字符。 */
    fun group(text: String, size: Int): String =
        text.chunked(size).joinToString("-")

    // ----------------------------------------------------------------------
    // 签发时刻
    // ----------------------------------------------------------------------

    /** 某一时刻对应的签发分钟数（自 [EPOCH_INSTANT] 起的整分钟，向下取整）。 */
    fun issueMinutesOf(instant: Instant): Int {
        val minutes = ChronoUnit.MINUTES.between(EPOCH_INSTANT, instant)
        require(minutes in 0..MAX_ISSUE_MINUTES.toLong()) {
            "签发时刻超出可编码范围（0..$MAX_ISSUE_MINUTES 分钟，即 2020-01-01 至 2051 年）"
        }
        return minutes.toInt()
    }

    /** 当前时刻的签发分钟数。 */
    fun currentIssueMinutes(now: Instant = Instant.now()): Int = issueMinutesOf(now)

    /** 签发分钟数还原为 UTC 时刻。 */
    fun issuedAt(issueMinutes: Int): Instant =
        EPOCH_INSTANT.plusSeconds(issueMinutes.toLong() * 60L)

    fun describeIssuedAt(issueMinutes: Int): String =
        dateTimeFormatter.format(
            java.time.LocalDateTime.ofInstant(issuedAt(issueMinutes), ZoneOffset.UTC),
        ) + " UTC"

    /** 距离激活窗口关闭还剩多少秒；已过期返回 0。 */
    fun windowSecondsLeft(issueMinutes: Int, now: Instant = Instant.now()): Long {
        val elapsed = ChronoUnit.SECONDS.between(issuedAt(issueMinutes), now)
        return (WINDOW_MINUTES * 60L - elapsed).coerceAtLeast(0L)
    }

    // ----------------------------------------------------------------------
    // 有效期（到期语义自 v1 起未变）
    // ----------------------------------------------------------------------

    /** 自今天起 [days] 天的字段值；0 表示永久。 */
    fun expiryDaysFromToday(days: Int, today: LocalDate = LocalDate.now(ZoneOffset.UTC)): Int {
        require(days >= 0) { "有效期不能为负数（0 表示永久）" }
        if (days == 0) return 0
        val elapsed = ChronoUnit.DAYS.between(EPOCH, today).toInt()
        val limit = MAX_EXPIRY_DAYS - elapsed
        require(days <= limit) { "有效期过长：自今天起最多可签发 $limit 天" }
        return elapsed + days
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

    /**
     * 组装 7 字节 payload（大端）：
     * `version|tier`、`expiryDays`(16)、`issueMinutes`(24)、`serial`(8)。
     */
    fun buildPayload(
        tier: Int,
        expiryDays: Int,
        issueMinutes: Int,
        serial: Int,
    ): ByteArray {
        require(tier in 0..0x0F) { "tier 必须在 0..15" }
        require(expiryDays in 0..MAX_EXPIRY_DAYS) { "expiryDays 必须在 0..65535" }
        require(issueMinutes in 0..MAX_ISSUE_MINUTES) {
            "issueMinutes 必须在 0..$MAX_ISSUE_MINUTES"
        }
        require(serial in 0..MAX_SERIAL) { "serial 必须在 0..$MAX_SERIAL" }
        return byteArrayOf(
            ((VERSION shl 4) or tier).toByte(),
            ((expiryDays shr 8) and 0xFF).toByte(),
            (expiryDays and 0xFF).toByte(),
            ((issueMinutes shr 16) and 0xFF).toByte(),
            ((issueMinutes shr 8) and 0xFF).toByte(),
            (issueMinutes and 0xFF).toByte(),
            (serial and 0xFF).toByte(),
        )
    }

    /**
     * 生成激活码（返回 24 字符规范形式）。
     *
     * @param issueMinutes 签发时刻，通常传 [currentIssueMinutes]。
     * @param expiryDays 0 表示永久（当前产品策略恒为 0）。
     * @param serial 唯一性序号 0..255。**调用方负责保证同一分钟内不重复**，
     *   否则会得到完全相同的码。批量生成时按 0,1,2… 递增即可。
     */
    fun generateCode(
        secret: ByteArray,
        issueMinutes: Int,
        expiryDays: Int,
        tier: Int = 0,
        serial: Int = 0,
    ): String {
        val payload = buildPayload(tier, expiryDays, issueMinutes, serial)
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
        val issueMinutes = ((raw[3].toInt() and 0xFF) shl 16) or
            ((raw[4].toInt() and 0xFF) shl 8) or
            (raw[5].toInt() and 0xFF)
        return ParsedCode(
            normalized = normalized,
            display = group(normalized, 4),
            version = (raw[0].toInt() and 0xFF) shr 4,
            tier = raw[0].toInt() and 0x0F,
            expiryDays = expiryDays,
            expiryDate = expiryDate(expiryDays),
            issueMinutes = issueMinutes,
            issuedAt = issuedAt(issueMinutes),
            serial = raw[6].toInt() and 0xFF,
            mac = raw.copyOfRange(PAYLOAD_BYTES, CODE_BYTES),
        )
    }

    /**
     * 完整校验。校验顺序见 spec.md §8.1：
     * 先长度/版本，再验签，最后才做窗口与到期判断
     * —— 避免把业务判断变成可试错的预言机。
     *
     * @param nowMinutes 设备当前时刻对应的签发分钟数，通常传 [currentIssueMinutes]。
     * @param today 用于 `expiryDays` 的到期判定，通常传 UTC 当天。
     * @param enforceWindow 是否校验激活窗口。
     *   **激活码输入时必须是 true**；**复检已存储的激活码时必须是 false**
     *   —— 否则已激活的设备会在签发 10 分钟后把自己判为失效。
     */
    fun verify(
        secret: ByteArray,
        code: String,
        nowMinutes: Int,
        today: LocalDate = LocalDate.now(ZoneOffset.UTC),
        enforceWindow: Boolean = true,
    ): ActivationResult {
        val normalized = normalize(code)
        if (normalized.length != CODE_LENGTH || !isValidAlphabet(normalized)) {
            return ActivationResult.Failure(ActivationError.Malformed)
        }
        val raw = base32Decode(normalized)
        if (raw.size != CODE_BYTES) return ActivationResult.Failure(ActivationError.Malformed)

        val payload = raw.copyOfRange(0, PAYLOAD_BYTES)
        val macFromCode = raw.copyOfRange(PAYLOAD_BYTES, CODE_BYTES)

        if (((payload[0].toInt() and 0xFF) shr 4) != VERSION) {
            return ActivationResult.Failure(ActivationError.UnsupportedVersion)
        }
        // MessageDigest.isEqual 是常量时间比较
        if (!MessageDigest.isEqual(mac(secret, payload), macFromCode)) {
            return ActivationResult.Failure(ActivationError.BadSignature)
        }

        val expiryDays = ((payload[1].toInt() and 0xFF) shl 8) or (payload[2].toInt() and 0xFF)
        val issueMinutes = ((payload[3].toInt() and 0xFF) shl 16) or
            ((payload[4].toInt() and 0xFF) shl 8) or
            (payload[5].toInt() and 0xFF)
        val serial = payload[6].toInt() and 0xFF

        // 激活窗口：签发后 WINDOW_MINUTES 分钟内必须激活
        if (enforceWindow) {
            val delta = nowMinutes - issueMinutes
            if (delta < -CLOCK_SKEW_MINUTES) {
                // 设备时钟明显落后于签发时刻 —— 属时钟异常，而非"码还没生效"
                return ActivationResult.Failure(
                    ActivationError.ClockAnomaly,
                    issuedAt = issuedAt(issueMinutes),
                )
            }
            if (delta > WINDOW_MINUTES) {
                return ActivationResult.Failure(
                    ActivationError.WindowExpired,
                    issuedAt = issuedAt(issueMinutes),
                )
            }
        }

        if (expiryDays != 0) {
            val elapsed = ChronoUnit.DAYS.between(EPOCH, today).toInt()
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
            issueMinutes = issueMinutes,
            issuedAt = issuedAt(issueMinutes),
            serial = serial,
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
    val issueMinutes: Int,
    val issuedAt: Instant,
    /** 唯一性序号 0..255；同一分钟内不同序号产出不同的码。 */
    val serial: Int,
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

    /** 超过激活窗口（签发后 [ActivationCodec.WINDOW_MINUTES] 分钟内未激活）。 */
    WindowExpired,
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
        val issueMinutes: Int,
        val issuedAt: Instant,
        val serial: Int,
    ) : ActivationResult()

    data class Failure(
        val error: ActivationError,
        val expiredAt: LocalDate? = null,
        val issuedAt: Instant? = null,
    ) : ActivationResult()
}
