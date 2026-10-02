package com.clean.click.activation

import android.content.Context
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * 激活生命周期管理：设备指纹、激活、每次启动复检、时间回拨检测。
 *
 * 对外只暴露不可变状态与明确的业务方法（遵循仓库的 Compose 状态边界约定）。
 * 调用方：`App.onCreate()` 里 `ActivationManager.initialize(app)`，
 * UI 层通过 [snapshotFlow] / [isActivatedFlow] 渲染。
 */
object ActivationManager {

    /** 时间回拨容忍窗口：1 天。 */
    private const val CLOCK_TOLERANCE_SECONDS = 86_400L

    /** 到期前多少天开始提示续费。 */
    const val RENEW_NOTICE_DAYS = 7

    private const val DEVICE_ID_FILE = "clean/device.id"

    /** 已知的历史坏值：部分 ROM 会返回它，无法用于绑定。 */
    private const val BAD_ANDROID_ID = "9774d56d682e549c"

    data class Snapshot(
        val activated: Boolean = false,
        val deviceCode: String = "",
        val expiryDays: Int = 0,
        val expiryDate: LocalDate? = null,
        val permanent: Boolean = false,
        val daysRemaining: Long? = null,
        val clockAnomaly: Boolean = false,
        val tier: Int = 0,
    ) {
        val needsRenewNotice: Boolean
            get() = activated && !permanent &&
                (daysRemaining ?: Long.MAX_VALUE) <= RENEW_NOTICE_DAYS
    }

    sealed class Outcome {
        data object Activated : Outcome()
        data class Rejected(val error: ActivationError, val expiredAt: LocalDate? = null) : Outcome()
        data class InvalidInput(val message: String) : Outcome()
    }

    val snapshotFlow: StateFlow<Snapshot> field = MutableStateFlow(Snapshot())

    val isActivatedFlow: StateFlow<Boolean> field = MutableStateFlow(false)

    /** 设备码，激活页需要展示给用户。初始化后才有值。 */
    val deviceCode: String get() = snapshotFlow.value.deviceCode

    private lateinit var appContext: Context
    private val secret: ByteArray by lazy { ActivationSecret.key() }

    fun initialize(context: Context) {
        appContext = context.applicationContext
        refresh(allowWrite = true)
    }

    /**
     * 重新解析并校验已存储的激活码。
     * 每次冷启动、每次回到前台都应调用（[allowWrite] 为 false 时只读，不更新 lastSeen）。
     */
    fun refresh(allowWrite: Boolean = true) {
        val deviceSecret = resolveDeviceSecret()
        val deviceCode = ActivationCodec.deviceCodeOf(deviceSecret)
        val record = ActivationStore.read(appContext, secret)
        val now = Instant.now().epochSecond

        // 时间回拨检测：只看记录里的 lastSeen，与当前时间比较
        val clockAnomaly = record.lastSeen > 0 && now < record.lastSeen - CLOCK_TOLERANCE_SECONDS

        if (record.isEmpty) {
            publish(deviceCode, ActivationResult.Failure(ActivationError.BadSignature), clockAnomaly, null)
            return
        }

        val result = ActivationCodec.verify(
            secret = secret,
            code = record.code,
            deviceSecret = deviceSecret,
            today = LocalDate.now(ZoneOffset.UTC),
        )

        when (result) {
            is ActivationResult.Success -> {
                // 限时码在时间回拨时拒绝，永久码只告警（避免误伤）
                if (clockAnomaly && !result.permanent) {
                    publish(deviceCode, ActivationResult.Failure(ActivationError.ClockAnomaly), true, null)
                    return
                }
                if (allowWrite) {
                    ActivationStore.touch(appContext, secret, record, now)
                }
                publish(deviceCode, result, clockAnomaly, record)
            }

            is ActivationResult.Failure -> {
                publish(deviceCode, result, clockAnomaly, null)
            }
        }
    }

    /** 用户提交激活码。 */
    fun activate(input: String): Outcome {
        val normalized = ActivationCodec.normalize(input)
        if (normalized.isEmpty()) return Outcome.InvalidInput("请输入激活码")
        if (normalized.length != ActivationCodec.CODE_LENGTH) {
            return Outcome.InvalidInput(
                "激活码应为 ${ActivationCodec.CODE_LENGTH} 位，当前 ${normalized.length} 位",
            )
        }
        if (!ActivationCodec.isValidAlphabet(normalized)) {
            // U 不在字母表内，I/L/O 已在 normalize 阶段纠正
            return Outcome.InvalidInput("激活码含无效字符，请核对后重试")
        }

        val deviceSecret = resolveDeviceSecret()
        val today = LocalDate.now(ZoneOffset.UTC)
        return when (val result = ActivationCodec.verify(secret, normalized, deviceSecret, today)) {
            is ActivationResult.Success -> {
                val now = Instant.now().epochSecond
                ActivationStore.write(
                    appContext,
                    secret,
                    ActivationStore.Record(
                        code = normalized,
                        activatedAt = now,
                        lastSeen = now,
                        deviceCode = ActivationCodec.deviceCodeOf(deviceSecret),
                    ),
                )
                refresh()
                Outcome.Activated
            }

            is ActivationResult.Failure -> Outcome.Rejected(result.error, result.expiredAt)
        }
    }

    /** 客服/工程模式使用：强制清除激活状态。 */
    fun deactivate() {
        ActivationStore.clear(appContext)
        refresh()
    }

    // ----------------------------------------------------------------------

    private fun publish(
        deviceCode: String,
        result: ActivationResult,
        clockAnomaly: Boolean,
        record: ActivationStore.Record?,
    ) {
        val snapshot = when (result) {
            is ActivationResult.Success -> {
                val remaining = result.expiryDays.takeIf { it != 0 }?.let { days ->
                    days - java.time.temporal.ChronoUnit.DAYS
                        .between(ActivationCodec.EPOCH, LocalDate.now(ZoneOffset.UTC))
                }
                Snapshot(
                    activated = true,
                    deviceCode = deviceCode,
                    expiryDays = result.expiryDays,
                    expiryDate = result.expiryDate,
                    permanent = result.permanent,
                    daysRemaining = remaining,
                    clockAnomaly = clockAnomaly,
                    tier = result.tier,
                )
            }

            is ActivationResult.Failure -> Snapshot(
                activated = false,
                deviceCode = deviceCode,
                clockAnomaly = clockAnomaly,
            )
        }
        snapshotFlow.value = snapshot
        isActivatedFlow.value = snapshot.activated
        // record 仅用于未来扩展（例如审计），当前不额外持久化
        @Suppress("UNUSED_EXPRESSION") record
    }

    /**
     * deviceSecret 取值：`Settings.Secure.ANDROID_ID`，异常时回退到私有目录中的随机 UUID。
     * 见 spec.md §4.1。注意清除 App 数据会删除回退文件，届时设备码会变化。
     */
    private fun resolveDeviceSecret(): String {
        val androidId = runCatching {
            Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()
        if (!androidId.isNullOrBlank() && androidId != BAD_ANDROID_ID) return androidId

        val file = File(appContext.filesDir, DEVICE_ID_FILE)
        file.takeIf { it.isFile }?.let {
            runCatching { it.readText(Charsets.UTF_8).trim() }
                .getOrNull()
                ?.takeIf { value -> value.isNotBlank() }
                ?.let { return it }
        }
        val generated = UUID.randomUUID().toString()
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(generated, Charsets.UTF_8)
        }
        return generated
    }
}
