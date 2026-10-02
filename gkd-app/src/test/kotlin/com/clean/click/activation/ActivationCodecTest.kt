package com.clean.click.activation

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate

/**
 * 三端一致性守门测试。
 *
 * 向量来自 `keygen/vectors.json`（由 `keygen/keygen.py vectors` 用公开测试密钥生成后冻结）。
 * 本文件刻意把这些常量**硬编码**，而不是在测试运行时读取 JSON：
 * 激活码算法一旦对外发售就不可变更，测试要能在最小的依赖下长期存在。
 *
 * 若本测试失败，说明 Kotlin 实现与生成器已经不一致 —— **绝对不要为了让测试通过而改向量**，
 * 那会让已售出的激活码全部失效。应先确认是哪一端偏离了 `keygen/spec.md`。
 */
class ActivationCodecTest {

    private val secret: ByteArray = hex(
        "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
    )

    private fun hex(text: String): ByteArray =
        ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    // ------------------------------------------------------------------
    // Base32 与规范化
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

    @Test
    fun `编码长度符合规范`() {
        assertEquals(24, ActivationCodec.base32Encode(ByteArray(15)).length)
        assertEquals(13, ActivationCodec.base32Encode(ByteArray(8)).length)
        assertEquals(10, ActivationCodec.deviceCodeOf("a1b2c3d4e5f60718").length)
    }

    @Test
    fun `normalize 纠正大小写 连字符与易混字符`() {
        assertEquals("A1B2C3D4E5", ActivationCodec.normalize("a1b2c-3d4e5"))
        assertEquals("11100", ActivationCodec.normalize("I1L0O"))
        assertEquals("ABCD", ActivationCodec.normalize(" AB CD "))
        assertEquals("2000000000BXDKXMFMAAJAZ3",
            ActivationCodec.normalize("2000-0000-00BX-DKXM-FMAA-JAZ3"))
    }

    // ------------------------------------------------------------------
    // 设备向量（对应 vectors.json device_vectors）
    // ------------------------------------------------------------------

    private data class DeviceVector(
        val deviceSecret: String,
        val deviceCode: String,
        val dev24Hex: String,
    )

    private val deviceVectors = listOf(
        DeviceVector("a1b2c3d4e5f60718", "TP8BAWARYC", "d590b5"),
        DeviceVector("9774d56d682e549d", "65FCJYHBVF", "315ec9"),
        DeviceVector("0123456789abcdef0123456789abcdef", "A7QZY97NQZ", "51efff"),
        DeviceVector("设备指纹-测试-001", "3EG71KWSQ3", "1ba070"),
        DeviceVector("abc", "CZGE25T84B", "67e0e1"),
    )

    @Test
    fun `设备向量全部匹配`() {
        deviceVectors.forEach { v ->
            assertEquals("deviceCode ${v.deviceSecret}", v.deviceCode,
                ActivationCodec.deviceCodeOf(v.deviceSecret))
            assertEquals("dev24 ${v.deviceSecret}", v.dev24Hex,
                ActivationCodec.dev24Of(v.deviceSecret).toHex())
            // 卖家侧从设备码还原出的 dev24 必须与 App 侧一致 —— 这是设备绑定的全部基础
            assertEquals("dev24FromDeviceCode ${v.deviceCode}", v.dev24Hex,
                ActivationCodec.dev24FromDeviceCode(v.deviceCode).toHex())
        }
    }

    @Test
    fun `设备码容错 展示形式 小写与易混字符`() {
        val v = deviceVectors.first()
        val messy = ActivationCodec.group(v.deviceCode, 5)
            .lowercase()
            .replace('1', 'I')
            .replace('0', 'O')
        assertEquals(v.dev24Hex, ActivationCodec.dev24FromDeviceCode(messy).toHex())
    }

    @Test
    fun `设备码长度或字符非法时抛异常`() {
        listOf("SHORT", "TOOLONGVALUE", "TP8BAWARY").forEach { bad ->
            try {
                ActivationCodec.dev24FromDeviceCode(bad)
                fail("应拒绝非法设备码: $bad")
            } catch (_: IllegalArgumentException) {
                // 预期
            }
        }
        try {
            ActivationCodec.dev24FromDeviceCode("UUUUUUUUUU")
            fail("U 不在字母表内，应拒绝")
        } catch (_: IllegalArgumentException) {
            // 预期
        }
    }

    // ------------------------------------------------------------------
    // 激活码向量（对应 vectors.json code_vectors）
    // ------------------------------------------------------------------

    private data class CodeVector(
        val name: String,
        val tier: Int,
        val expiryDays: Int,
        val dev24Hex: String,
        val payloadHex: String,
        val code: String,
    )

    private val codeVectors = listOf(
        CodeVector("永久/标准档", 0, 0, "000000", "100000000000", "2000000000BXDKXMFMAAJAZ3"),
        CodeVector("365天/标准档", 0, 365, "ffffff", "10016dffffff", "200PVZZZZZQJRMWCJFRF8FRC"),
        CodeVector("30天/标准档", 0, 30, "123456", "10001e123456", "2001W4HMAV4KWWQZ7Z57Q5TJ"),
        CodeVector("65535天/标准档", 0, 65535, "a5b6c7", "10ffffa5b6c7", "23ZZZ9DPRWE1E5ZV0XWVNPX7"),
        CodeVector("1天/高级档", 1, 1, "000001", "110001000001", "2400200006KEA6ESD2PRY91Z"),
        CodeVector("永久/最高档", 15, 0, "f0e1d2", "1f0000f0e1d2", "3W001W71TAZE9FCX4Y67NT72"),
        CodeVector("1095天/高级档", 1, 1095, "89abcd", "11044789abcd", "2424F2DBSPH8FF06MH5TFA1P"),
    )

    @Test
    fun `payload 组装与向量一致`() {
        codeVectors.forEach { v ->
            val payload = ActivationCodec.buildPayload(v.tier, v.expiryDays, hex(v.dev24Hex))
            assertEquals(v.name, v.payloadHex, payload.toHex())
        }
    }

    @Test
    fun `按 dev24 生成的激活码与向量一致`() {
        codeVectors.forEach { v ->
            val payload = ActivationCodec.buildPayload(v.tier, v.expiryDays, hex(v.dev24Hex))
            assertEquals(v.name, v.code, ActivationCodec.base32Encode(payload + mac(payload)))
        }
    }

    @Test
    fun `按设备码生成与按 dev24 生成等价`() {
        // 这是卖家侧可行性所依赖的关键不变式：
        // 生成器只有设备码（10 字符），而激活码里嵌的是 dev24；
        // deviceCode -> dev24 这条路必须与 dev24Of(deviceSecret) 得到的结果完全一致。
        val maxFromToday = 0xFFFF -
            java.time.temporal.ChronoUnit.DAYS.between(ActivationCodec.EPOCH, today).toInt()
        deviceVectors.forEach { d ->
            listOf(0, 30, 365, maxFromToday).forEach { days ->
                val payload = ActivationCodec.buildPayload(0, days, hex(d.dev24Hex))
                val fromDev24 = ActivationCodec.base32Encode(payload + mac(payload))
                val fromDeviceCode = ActivationCodec.generateCode(secret, d.deviceCode, days, 0)
                assertEquals(
                    "deviceSecret=${d.deviceSecret} days=$days",
                    fromDev24,
                    fromDeviceCode,
                )
                // 且生成的码里嵌的 dev24 必须与设备自身一致
                assertEquals(d.dev24Hex, ActivationCodec.parseCode(fromDeviceCode).dev24.toHex())
            }
        }
    }

    @Test
    fun `解析往返与向量一致`() {
        codeVectors.forEach { v ->
            val parsed = ActivationCodec.parseCode(v.code)
            assertEquals(v.name, 1, parsed.version)
            assertEquals(v.name, v.tier, parsed.tier)
            assertEquals(v.name, v.expiryDays, parsed.expiryDays)
            assertEquals(v.name, v.dev24Hex, parsed.dev24.toHex())
            assertEquals(v.name, v.expiryDays == 0, parsed.permanent)
        }
    }

    @Test
    fun `带分组与大小写的输入解析结果相同`() {
        val v = codeVectors[2]
        val messy = ActivationCodec.group(v.code, 4).lowercase()
        assertEquals(v.code, ActivationCodec.parseCode(messy).normalized)
    }

    // ------------------------------------------------------------------
    // 端到端
    // ------------------------------------------------------------------

    private val today: LocalDate = LocalDate.of(2026, 1, 1)

    private fun expiryFor365DaysFromToday(): Int = ActivationCodec.expiryDaysFromToday(365, today)

    @Test
    fun `有效期内 本机激活码校验通过`() {
        val device = deviceVectors.first()
        val code = ActivationCodec.generateCode(
            secret, device.deviceCode, expiryFor365DaysFromToday(),
        )
        val result = ActivationCodec.verify(secret, code, device.deviceSecret, today)
        assertTrue("应通过，实际 $result", result is ActivationResult.Success)
        val success = result as ActivationResult.Success
        assertEquals(0, success.tier)
        assertFalse(success.permanent)
        assertEquals(
            ActivationCodec.expiryDate(expiryFor365DaysFromToday()),
            success.expiryDate,
        )
    }

    @Test
    fun `换设备校验失败`() {
        val code = ActivationCodec.generateCode(
            secret, deviceVectors[0].deviceCode, expiryFor365DaysFromToday(),
        )
        val result = ActivationCodec.verify(secret, code, deviceVectors[1].deviceSecret, today)
        assertEquals(
            ActivationError.DeviceMismatch,
            (result as ActivationResult.Failure).error,
        )
    }

    @Test
    fun `到期后校验失败并给出到期日`() {
        val device = deviceVectors.first()
        val code = ActivationCodec.generateCode(
            secret, device.deviceCode, expiryFor365DaysFromToday(),
        )
        val result = ActivationCodec.verify(
            secret, code, device.deviceSecret, today.plusDays(400),
        )
        val failure = result as ActivationResult.Failure
        assertEquals(ActivationError.Expired, failure.error)
        assertEquals(ActivationCodec.expiryDate(expiryFor365DaysFromToday()), failure.expiredAt)
    }

    @Test
    fun `永久码在很久以后仍有效`() {
        val device = deviceVectors.first()
        val code = ActivationCodec.generateCode(secret, device.deviceCode, 0)
        val result = ActivationCodec.verify(
            secret, code, device.deviceSecret, LocalDate.of(2099, 1, 1),
        )
        assertTrue(result is ActivationResult.Success)
        assertTrue((result as ActivationResult.Success).permanent)
    }

    @Test
    fun `篡改任意一位都会失败`() {
        val device = deviceVectors.first()
        val code = ActivationCodec.generateCode(secret, device.deviceCode, 0)
        // 逐个位置替换成另一个合法字符，全部必须失败
        var tampered = 0
        code.indices.forEach { index ->
            val original = code[index]
            val replacement = ActivationCodec.ALPHABET.first { it != original }
            val mutant = code.substring(0, index) + replacement + code.substring(index + 1)
            val result = ActivationCodec.verify(secret, mutant, device.deviceSecret, today)
            assertFalse("位置 $index 被篡改后不应通过", result is ActivationResult.Success)
            tampered++
        }
        assertEquals(ActivationCodec.CODE_LENGTH, tampered)
    }

    @Test
    fun `换密钥后签名校验失败`() {
        val device = deviceVectors.first()
        val code = ActivationCodec.generateCode(secret, device.deviceCode, 0)
        val wrong = secret.copyOf().also { it[0] = (it[0].toInt() xor 0xFF).toByte() }
        val result = ActivationCodec.verify(wrong, code, device.deviceSecret, today)
        assertEquals(ActivationError.BadSignature, (result as ActivationResult.Failure).error)
    }

    @Test
    fun `畸形输入返回 Malformed 而不是抛异常`() {
        val device = deviceVectors.first()
        listOf("", "ABC", "U".repeat(24), "!".repeat(24)).forEach { bad ->
            val result = ActivationCodec.verify(secret, bad, device.deviceSecret, today)
            assertEquals(bad, ActivationError.Malformed,
                (result as ActivationResult.Failure).error)
        }
    }

    @Test
    fun `未知版本号被拒绝`() {
        // 手工构造 version=2 的 payload，MAC 用同一密钥重算，确保失败原因是版本而非签名
        val dev24 = hex("000000")
        val payload = byteArrayOf(0x20, 0x00, 0x00, dev24[0], dev24[1], dev24[2])
        val code = ActivationCodec.base32Encode(payload + mac(payload))
        val result = ActivationCodec.verify(secret, code, deviceVectors.first().deviceSecret, today)
        assertEquals(ActivationError.UnsupportedVersion,
            (result as ActivationResult.Failure).error)
    }

    // ------------------------------------------------------------------
    // 有效期换算
    // ------------------------------------------------------------------

    @Test
    fun `expiryDaysFromToday 语义为自今天起的天数`() {
        assertEquals(0, ActivationCodec.expiryDaysFromToday(0, today))
        // 2020-01-01 与 2026-01-01 相差 2192 天
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
    // 与 Python 生成器产出的说明性一致性
    // ------------------------------------------------------------------

    @Test
    fun `随机设备码生成的码可被自身校验`() {
        // 覆盖一批随机 deviceSecret，确保 normalize/编码/解码在边界上没有系统性偏差。
        // 注意：65535 是字段上限，但「自今天起的天数」上限要小得多，必须按 today 折算。
        val maxFromToday = 0xFFFF -
            java.time.temporal.ChronoUnit.DAYS.between(ActivationCodec.EPOCH, today).toInt()
        repeat(200) { index ->
            val deviceSecret = "clean-selftest-$index"
            val deviceCode = ActivationCodec.deviceCodeOf(deviceSecret)
            listOf(0, 1, 30, 365, maxFromToday).forEach { days ->
                val code = ActivationCodec.generateCode(
                    secret, deviceCode, ActivationCodec.expiryDaysFromToday(days, today),
                )
                val result = ActivationCodec.verify(secret, code, deviceSecret, today)
                assertTrue(
                    "deviceSecret=$deviceSecret days=$days 应通过，实际 $result",
                    result is ActivationResult.Success,
                )
            }
        }
    }

    private fun mac(payload: ByteArray): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(secret, "HmacSHA256"))
        return mac.doFinal("CLEAN-ACT-V1".toByteArray(Charsets.US_ASCII) + payload)
            .copyOf(ActivationCodec.MAC_LENGTH)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
