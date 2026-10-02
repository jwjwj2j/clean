package com.clean.click.activation

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 多端一致性守门测试（结构版本 v4）。
 *
 * 向量来自 `keygen/vectors.json`（由生成器用公开测试密钥生成后冻结），
 * 并与 `tools/gen_v4_vectors.py` 的独立实现交叉核对过。
 * 本文件刻意把这些常量**硬编码**，而不是在测试运行时读取 JSON：
 * 激活码算法一旦对外发售就不可变更，测试要能在最小的依赖下长期存在。
 *
 * 若本测试失败，说明 Kotlin 实现与生成器已经不一致 —— **绝对不要为了让测试通过而改向量**，
 * 那会让已售出的激活码全部失效。应先确认是哪一端偏离了 `keygen/spec.md`。
 *
 * v4 修掉的问题：v3 的签发时刻只精确到**分钟**，窗口判定也按分钟比较，所以窗口只能是整分钟，
 * 想做 90 秒就会出现「实际可用 31–90 秒」的飘动。v4 把签发时刻改为**秒级**，
 * 窗口因此精确等于 [ActivationCodec.WINDOW_SECONDS] 秒。
 */
class ActivationCodecTest {

    private val secret: ByteArray = hex(
        "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
    )

    private fun hex(text: String): ByteArray =
        ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** 2026-01-01T00:00:00Z 对应的 issueSeconds。 */
    private val s2026: Long = 189388800L

    private val today: LocalDate = LocalDate.of(2026, 1, 1)

    // ------------------------------------------------------------------
    // 结构常量
    // ------------------------------------------------------------------

    @Test
    fun `结构版本与字段宽度符合 v4 规范`() {
        assertEquals(4, ActivationCodec.VERSION)
        assertEquals(8, ActivationCodec.PAYLOAD_BYTES)
        assertEquals(7, ActivationCodec.MAC_LENGTH)
        assertEquals(15, ActivationCodec.CODE_BYTES)
        assertEquals(24, ActivationCodec.CODE_LENGTH)
        assertEquals(1023, ActivationCodec.MAX_SERIAL)
        assertEquals(90L, ActivationCodec.WINDOW_SECONDS)
        assertEquals(30L, ActivationCodec.CLOCK_SKEW_SECONDS)
        assertEquals(1073741823L, ActivationCodec.MAX_ISSUE_SECONDS)
        // 8 字节 payload + 7 字节 MAC = 120 位，恰好 24 个 Base32 字符 —— 码长自 v1 起未变
        assertEquals(
            ActivationCodec.CODE_LENGTH,
            ActivationCodec.base32Encode(ByteArray(ActivationCodec.CODE_BYTES)).length,
        )
    }

    // ------------------------------------------------------------------
    // Base32 与规范化（自 v1 起未改动，属回归保护）
    // ------------------------------------------------------------------

    @Test
    fun `字母表为 32 个互异字符且不含易混字符`() {
        assertEquals(32, ActivationCodec.ALPHABET.length)
        assertEquals(32, ActivationCodec.ALPHABET.toSet().size)
        listOf('I', 'L', 'O', 'U').forEach { ch ->
            assertFalse("字母表不应包含 $ch", ch in ActivationCodec.ALPHABET)
        }
    }

    @Test
    fun `base32 往返保持字节不变`() {
        val samples = listOf(
            ByteArray(0),
            byteArrayOf(0),
            byteArrayOf(-1),
            ByteArray(15) { it.toByte() },
            ByteArray(15) { 0xFF.toByte() },
            byteArrayOf(1, 2, 3),
        )
        samples.forEach { raw ->
            assertArrayEquals(raw, ActivationCodec.base32Decode(ActivationCodec.base32Encode(raw)))
        }
    }

    /** 与 v1 向量逐条比对：Base32 必须完全没变，这是最重要的回归证据。 */
    @Test
    fun `base32 向量与 v1 完全一致`() {
        val vectors = listOf(
            "" to "",
            "00" to "00",
            "ff" to "ZW",
            "0102030405060708" to "041061050R3GG",
            "000102030405060708090a0b0c0d0e0f" to "000G40R40M30E209185GR38E1W",
            "ffffffffffffffffffffffffffffff" to "ZZZZZZZZZZZZZZZZZZZZZZZZ",
            "0102030405060708090a0b0c0d0e" to "041061050R3GG28A1C60T3G",
            "deadbeefcafebabe" to "VTPVXVYAZTXBW",
            "00000000000000000000000000000000" to "00000000000000000000000000",
            "ffffffffffffffffffffffffffffffff" to "ZZZZZZZZZZZZZZZZZZZZZZZZZW",
        )
        vectors.forEach { (inHex, expected) ->
            val raw = if (inHex.isEmpty()) ByteArray(0) else hex(inHex)
            assertEquals("input=$inHex", expected, ActivationCodec.base32Encode(raw))
        }
    }

    @Test
    fun `normalize 纠正大小写 连字符与易混字符`() {
        assertEquals("A1B2C3D4E5", ActivationCodec.normalize("a1b2c-3d4e5"))
        assertEquals("11100", ActivationCodec.normalize("I1L0O"))
        assertEquals("ABCD", ActivationCodec.normalize(" AB CD "))
        assertEquals("8000000000BXDKXMFMAAJAZ3",
            ActivationCodec.normalize("8000-0000-00BX-DKXM-FMAA-JAZ3"))
    }

    // ------------------------------------------------------------------
    // 激活码向量
    // ------------------------------------------------------------------

    private data class CodeVector(
        val name: String,
        val tier: Int,
        val expiryDays: Int,
        val issueSeconds: Long,
        val serial: Int,
        val payloadHex: String,
        val code: String,
    )

    private val codeVectors = listOf(
        CodeVector("永久/标准档/serial0/整秒", 0, 0, 189388800L, 0, "4000002d27600000", "80000B97C0001PF55B4EDQYP"),
        CodeVector("永久/标准档/serial1/整秒", 0, 0, 189388800L, 1, "4000002d27600001", "80000B97C0003Z3DEJKBB0AG"),
        CodeVector("永久/标准档/serial1023", 0, 0, 189388800L, 1023, "4000002d276003ff", "80000B97C01ZZGEKXB4F869G"),
        CodeVector("永久/标准档/秒偏移+1", 0, 0, 189388801L, 0, "4000002d27600400", "80000B97C02018HMGSS0KE8P"),
        CodeVector("永久/标准档/秒偏移+37", 0, 0, 189388837L, 0, "4000002d27609400", "80000B97C2A01P5XV9W784V0"),
        CodeVector("365天/标准档", 0, 365, 189388845L, 0, "40016d2d2760b400", "800PTB97C2T01KGZETHX3HZJ"),
        CodeVector("30天/标准档", 0, 30, 189388859L, 42, "40001e2d2760ec2a", "8001WB97C3P2NX2YGY39RNME"),
        CodeVector("65535天/标准档", 0, 65535, 189388800L, 7, "40ffff2d27600007", "83ZZYB97C000EZS0ETJRC81B"),
        CodeVector("1天/高级档", 1, 1, 189388812L, 0, "4100012d27603000", "84002B97C0R017DEMHFVQKAD"),
        CodeVector("永久/最高档", 15, 0, 189388830L, 200, "4f00002d276078c8", "9W000B97C1WCHV5G59B9NT55"),
        CodeVector("1095天/高级档", 1, 1095, 189388800L, 99, "4104472d27600063", "8424EB97C0067Q8JE2HZSJC7"),
        CodeVector("永久/最早签发", 0, 0, 0L, 0, "4000000000000000", "8000000000001209E9WA99G4"),
        CodeVector("永久/最晚签发", 0, 0, 1073741823L, 1023, "400000ffffffffff", "80001ZZZZZZZYZHQCWVC34ZC"),
    )

    @Test
    fun `payload 组装与向量一致`() {
        codeVectors.forEach { v ->
            val payload = ActivationCodec.buildPayload(
                v.tier, v.expiryDays, v.issueSeconds, v.serial,
            )
            assertEquals(v.name, v.payloadHex, payload.toHex())
        }
    }

    @Test
    fun `生成的激活码与向量一致`() {
        codeVectors.forEach { v ->
            assertEquals(v.name, v.code, ActivationCodec.generateCode(
                secret, v.issueSeconds, v.expiryDays, v.tier, v.serial,
            ))
        }
    }

    @Test
    fun `解析往返与向量一致`() {
        codeVectors.forEach { v ->
            val parsed = ActivationCodec.parseCode(v.code)
            assertEquals(v.name, ActivationCodec.VERSION, parsed.version)
            assertEquals(v.name, v.tier, parsed.tier)
            assertEquals(v.name, v.expiryDays, parsed.expiryDays)
            assertEquals(v.name, v.issueSeconds, parsed.issueSeconds)
            assertEquals(v.name, v.serial, parsed.serial)
            assertEquals(v.name, ActivationCodec.issuedAt(v.issueSeconds), parsed.issuedAt)
            assertEquals(v.name, v.expiryDays == 0, parsed.permanent)
        }
    }

    @Test
    fun `带分组与大小写的输入解析结果相同`() {
        val v = codeVectors[4]
        val messy = ActivationCodec.group(v.code, 4).lowercase()
        assertEquals(v.code, ActivationCodec.parseCode(messy).normalized)
    }

    // ------------------------------------------------------------------
    // 序号与唯一性
    // ------------------------------------------------------------------

    /**
     * v3 的回归保护：同一时刻下 256 个序号必须产出 256 个不同的码。
     * v4 的序号是 10 位，因此这里检查 1024 个。
     */
    @Test
    fun `同一秒内不同序号产出互不相同的码`() {
        val codes = (0..ActivationCodec.MAX_SERIAL).map { serial ->
            ActivationCodec.generateCode(secret, s2026, 0, 0, serial)
        }
        assertEquals(
            "同一秒内 1024 个 serial 必须产出 1024 个不同的码",
            1024,
            codes.toSet().size,
        )
        codes.forEachIndexed { serial, code ->
            assertEquals(serial, ActivationCodec.parseCode(code).serial)
        }
    }

    /**
     * **v4 的核心收益**：签发时刻精确到秒后，即使序号相同，
     * **相邻秒生成的码也互不相同**。v3 在同一分钟内做不到这一点
     * （当时必须靠 serial 区分，否则同分钟同参数的码会完全相同）。
     */
    @Test
    fun `相邻秒生成的码互不相同`() {
        val codes = (0..4).map { offset ->
            ActivationCodec.generateCode(secret, s2026 + offset, 0, 0, 0)
        }
        assertEquals("连续 5 秒、序号都为 0，也必须得到 5 个不同的码", 5, codes.toSet().size)
        codes.forEachIndexed { offset, code ->
            assertEquals(s2026 + offset, ActivationCodec.parseCode(code).issueSeconds)
        }
    }

    /** 相邻序号的码必须完全不同（不能只差最后一位之类的弱变化）。 */
    @Test
    fun `相邻序号的码差异足够大`() {
        val a = ActivationCodec.generateCode(secret, s2026, 0, 0, 0)
        val b = ActivationCodec.generateCode(secret, s2026, 0, 0, 1)
        assertFalse(a == b)
        val diff = a.indices.count { a[it] != b[it] }
        assertTrue("相邻序号的码应有明显差异，实际只差 $diff 个字符", diff >= 8)
    }

    /** 同样输入必须产出同样结果 —— 这是可复现性要求，不是缺陷。 */
    @Test
    fun `相同输入产出相同码`() {
        assertEquals(
            ActivationCodec.generateCode(secret, s2026, 0, 0, 7),
            ActivationCodec.generateCode(secret, s2026, 0, 0, 7),
        )
    }

    @Test
    fun `序号与签发时刻超出范围被拒绝`() {
        listOf(-1, 1024, 5000).forEach { bad ->
            try {
                ActivationCodec.buildPayload(0, 0, s2026, bad)
                fail("serial=$bad 应被拒绝")
            } catch (_: IllegalArgumentException) {
                // 预期
            }
        }
        listOf(-1L, ActivationCodec.MAX_ISSUE_SECONDS + 1).forEach { bad ->
            try {
                ActivationCodec.buildPayload(0, 0, bad, 0)
                fail("issueSeconds=$bad 应被拒绝")
            } catch (_: IllegalArgumentException) {
                // 预期
            }
        }
    }

    // ------------------------------------------------------------------
    // 激活窗口（v4：精确 90 秒）
    // ------------------------------------------------------------------

    private data class WindowVector(
        val name: String,
        val issueSeconds: Long,
        val nowSeconds: Long,
        val expect: ActivationError?,
    )

    private val windowVectors = listOf(
        WindowVector("窗口内 第0秒", s2026, s2026, null),
        WindowVector("窗口内 第45秒", s2026, s2026 + 45, null),
        WindowVector("窗口内 第90秒（边界内）", s2026, s2026 + 90, null),
        WindowVector("窗口外 第91秒", s2026, s2026 + 91, ActivationError.WindowExpired),
        WindowVector("窗口外 第600秒", s2026, s2026 + 600, ActivationError.WindowExpired),
        WindowVector("时钟落后 30 秒（容忍内）", s2026, s2026 - 30, null),
        WindowVector("时钟落后 31 秒（时钟异常）", s2026, s2026 - 31, ActivationError.ClockAnomaly),
    )

    @Test
    fun `窗口向量的结果与期望一致`() {
        windowVectors.forEach { v ->
            val code = ActivationCodec.generateCode(secret, v.issueSeconds, 0)
            val result = ActivationCodec.verify(secret, code, v.nowSeconds, today)
            if (v.expect == null) {
                assertTrue("${v.name}: 应通过，实际 $result", result is ActivationResult.Success)
            } else {
                assertEquals(v.name, v.expect, (result as ActivationResult.Failure).error)
            }
        }
    }

    /**
     * 防止「已激活设备自我停用」：复检已存储的激活码时必须跳过窗口校验，
     * 否则设备会在签发 90 秒后变回未激活。
     */
    @Test
    fun `复检已存储的码不校验窗口`() {
        val code = ActivationCodec.generateCode(secret, s2026, 0)
        val later = s2026 + 600

        val withWindow = ActivationCodec.verify(secret, code, later, today, enforceWindow = true)
        assertEquals(ActivationError.WindowExpired, (withWindow as ActivationResult.Failure).error)

        val without = ActivationCodec.verify(secret, code, later, today, enforceWindow = false)
        assertTrue("复检必须通过，实际 $without", without is ActivationResult.Success)
        assertTrue((without as ActivationResult.Success).permanent)
    }

    @Test
    fun `窗口内激活后给出的信息为永久且带序号`() {
        val code = ActivationCodec.generateCode(secret, s2026, 0, 0, 33)
        val success = ActivationCodec.verify(secret, code, s2026 + 1, today) as ActivationResult.Success
        assertTrue(success.permanent)
        assertNull(success.expiryDate)
        assertEquals(s2026, success.issueSeconds)
        assertEquals(33, success.serial)
        assertEquals(ActivationCodec.issuedAt(s2026), success.issuedAt)
    }

    /**
     * v4 的关键可见行为：剩余时间从 **90** 精确递减。
     * v3 会落在 601..660 的区间里（因为签发只精确到分钟、还要补一分钟）。
     */
    @Test
    fun `剩余窗口秒数从 90 精确递减`() {
        val issued = ActivationCodec.issuedAt(s2026)
        assertEquals(90L, ActivationCodec.windowSecondsLeft(s2026, issued))
        assertEquals(89L, ActivationCodec.windowSecondsLeft(s2026, issued.plusSeconds(1)))
        assertEquals(2L, ActivationCodec.windowSecondsLeft(s2026, issued.plusSeconds(88)))
        assertEquals(0L, ActivationCodec.windowSecondsLeft(s2026, issued.plusSeconds(90)))
        assertEquals(0L, ActivationCodec.windowSecondsLeft(s2026, issued.plusSeconds(9000)))
    }

    // ------------------------------------------------------------------
    // 签发时刻换算
    // ------------------------------------------------------------------

    @Test
    fun `issueSeconds 与 issuedAt 往返一致`() {
        listOf(0L, 1L, 86400L, s2026, ActivationCodec.MAX_ISSUE_SECONDS).forEach { seconds ->
            assertEquals(seconds, ActivationCodec.issueSecondsOf(ActivationCodec.issuedAt(seconds)))
        }
        assertEquals(Instant.parse("2020-01-01T00:00:00Z"), ActivationCodec.issuedAt(0))
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), ActivationCodec.issuedAt(s2026))
    }

    /**
     * 秒级精度必须真的到秒：同一秒内的不同时刻得到相同的 issueSeconds，
     * 下一秒则不同。这是 v4 相对 v3（分钟级）的核心差别。
     */
    @Test
    fun `同一秒内得到相同 issueSeconds 下一秒则不同`() {
        val base = Instant.parse("2026-01-01T00:00:00Z")
        val expected = ActivationCodec.issueSecondsOf(base)
        assertEquals(expected, ActivationCodec.issueSecondsOf(base.plusMillis(500)))
        assertEquals(expected + 1, ActivationCodec.issueSecondsOf(base.plusSeconds(1)))
        assertEquals(expected, ActivationCodec.issueSecondsOf(base))
    }

    @Test
    fun `超出可编码范围时报错`() {
        try {
            ActivationCodec.issueSecondsOf(Instant.parse("2080-01-01T00:00:00Z"))
            fail("2054 年之后的时刻应被拒绝")
        } catch (_: IllegalArgumentException) {
            // 预期
        }
    }

    // ------------------------------------------------------------------
    // 拒绝路径
    // ------------------------------------------------------------------

    /** v1 / v2 / v3 的旧码都必须被明确拒绝，便于客服区分「拿的是旧版码」与「码打错了」。 */
    @Test
    fun `旧版本激活码被明确拒绝`() {
        val legacy = listOf(
            "v1" to "2000000000BXDKXMFMAAJAZ3",
            "v2" to "40000C1A014MR5NHG8XM7YKS",
            "v3" to "60000C1A0008RC4QHFF390PC",
        )
        legacy.forEach { (label, code) ->
            assertEquals(24, code.length)
            val result = ActivationCodec.verify(secret, code, s2026, today)
            assertEquals(
                "$label 码应判为版本不支持",
                ActivationError.UnsupportedVersion,
                (result as ActivationResult.Failure).error,
            )
        }
    }

    @Test
    fun `篡改任意一位都会失败`() {
        val code = ActivationCodec.generateCode(secret, s2026, 0)
        code.indices.forEach { index ->
            val original = code[index]
            val replacement = ActivationCodec.ALPHABET.first { it != original }
            val mutant = code.substring(0, index) + replacement + code.substring(index + 1)
            val result = ActivationCodec.verify(secret, mutant, s2026, today)
            assertFalse("位置 $index 被篡改后不应通过", result is ActivationResult.Success)
        }
    }

    @Test
    fun `换密钥后签名校验失败`() {
        val code = ActivationCodec.generateCode(secret, s2026, 0)
        val wrong = secret.copyOf().also { it[0] = (it[0].toInt() xor 0xFF).toByte() }
        val result = ActivationCodec.verify(wrong, code, s2026, today)
        assertEquals(ActivationError.BadSignature, (result as ActivationResult.Failure).error)
    }

    @Test
    fun `畸形输入返回 Malformed 而不是抛异常`() {
        listOf("", "ABC", "U".repeat(24), "!".repeat(24)).forEach { bad ->
            val result = ActivationCodec.verify(secret, bad, s2026, today)
            assertEquals(bad, ActivationError.Malformed,
                (result as ActivationResult.Failure).error)
        }
    }

    @Test
    fun `未知版本号被拒绝`() {
        // 手工构造 version=9 的 payload，MAC 用同一密钥重算，确保失败原因是版本而非签名
        val payload = byteArrayOf(0x90.toByte(), 0x00, 0x00, 0x2d, 0x27, 0x60, 0x00, 0x00)
        val code = ActivationCodec.base32Encode(payload + mac(payload))
        val result = ActivationCodec.verify(secret, code, s2026, today)
        assertEquals(ActivationError.UnsupportedVersion,
            (result as ActivationResult.Failure).error)
    }

    // ------------------------------------------------------------------
    // 到期语义
    // ------------------------------------------------------------------

    private fun expiryFor365DaysFromToday(): Int =
        ActivationCodec.expiryDaysFromToday(365, today)

    @Test
    fun `限时码在窗口内激活后按时到期`() {
        val days = expiryFor365DaysFromToday()
        val code = ActivationCodec.generateCode(secret, s2026, days)
        val result = ActivationCodec.verify(secret, code, s2026 + 1, today)
        assertTrue(result is ActivationResult.Success)
        val success = result as ActivationResult.Success
        assertFalse(success.permanent)
        assertEquals(ActivationCodec.expiryDate(days), success.expiryDate)
    }

    @Test
    fun `限时码到期后校验失败并给出到期日`() {
        val days = expiryFor365DaysFromToday()
        val code = ActivationCodec.generateCode(secret, s2026, days)
        val result = ActivationCodec.verify(
            secret, code, s2026 + 1, today.plusDays(400), enforceWindow = false,
        )
        val failure = result as ActivationResult.Failure
        assertEquals(ActivationError.Expired, failure.error)
        assertEquals(ActivationCodec.expiryDate(days), failure.expiredAt)
    }

    @Test
    fun `永久码在很久以后仍有效`() {
        val code = ActivationCodec.generateCode(secret, s2026, 0)
        val result = ActivationCodec.verify(
            secret, code, s2026, LocalDate.of(2099, 1, 1), enforceWindow = false,
        )
        assertTrue(result is ActivationResult.Success)
        assertTrue((result as ActivationResult.Success).permanent)
    }

    @Test
    fun `expiryDaysFromToday 语义为自今天起的天数`() {
        assertEquals(0, ActivationCodec.expiryDaysFromToday(0, today))
        assertEquals(2192 + 365, ActivationCodec.expiryDaysFromToday(365, today))
        assertEquals(LocalDate.of(2027, 1, 1), ActivationCodec.expiryDate(2192 + 365))
    }

    @Test
    fun `超出 16 位字段上限时拒绝`() {
        try {
            ActivationCodec.expiryDaysFromToday(70000, today)
            fail("应拒绝超过 65535 天的有效期")
        } catch (_: IllegalArgumentException) {
            // 预期
        }
    }

    // ------------------------------------------------------------------
    // 批量往返
    // ------------------------------------------------------------------

    @Test
    fun `一批签发时刻与序号生成的码都能被自身校验`() {
        val maxFromToday = 0xFFFF - ChronoUnit.DAYS.between(ActivationCodec.EPOCH, today).toInt()
        val issues = listOf(0L, 1L, 1000L, s2026, ActivationCodec.MAX_ISSUE_SECONDS)
        repeat(60) { index ->
            val issue = issues[index % issues.size]
            listOf(0, 1, 30, 365, maxFromToday).forEach { days ->
                val code = ActivationCodec.generateCode(
                    secret, issue, ActivationCodec.expiryDaysFromToday(days, today), 0, index % 1024,
                )
                val result = ActivationCodec.verify(
                    secret, code, s2026, today, enforceWindow = false,
                )
                assertTrue(
                    "issue=$issue days=$days 应通过，实际 $result",
                    result is ActivationResult.Success,
                )
            }
        }
    }

    private fun mac(payload: ByteArray): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(secret, "HmacSHA256"))
        return mac.doFinal("CLEAN-ACT-V4".toByteArray(Charsets.US_ASCII) + payload)
            .copyOf(ActivationCodec.MAC_LENGTH)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
