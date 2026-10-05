package com.clean.click.activation

import li.gkd.app.BuildConfig
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * 激活生命周期管理：激活、每次启动复检、时间回拨检测。
 *
 * v2 变更（取消设备绑定）：
 *  - 删除设备指纹相关的一切（`deviceSecret`、设备码、`ANDROID_ID`、回退 UUID 文件）。
 *    激活页不再需要用户上报设备码，卖家也不需要绑定设备。
 *  - 校验用 [ActivationCodec.verify]，窗口只在**输入激活码那一刻**校验；
 *    复检已存储的码时传 `enforceWindow = false`，否则已激活设备会在签发 90 秒后自我停用。
 *
 * 对外只暴露不可变状态与明确的业务方法（遵循仓库的 Compose 状态边界约定）。
 * 调用方：`App.onCreate()` 里 `ActivationManager.initialize(app)`，
 * UI 层通过 [snapshotFlow] / [isActivatedFlow] 渲染。
 */
object ActivationManager {

    /** 时间回拨容忍窗口：1 天。 */
    private const val CLOCK_TOLERANCE_SECONDS = 86_400L

    /** 到期前多少天开始提示续费。当前产品策略恒为永久码，因此实际不会触发。 */
    const val RENEW_NOTICE_DAYS = 7

    data class Snapshot(
        val activated: Boolean = false,
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

    /**
     * 无激活码版本（-PCLEAN_FREE=true）直接以「已永久激活、最高档」起步，
     * 这样在 initialize() 执行之前界面就已经放行，不会闪一下激活页。
     */
    /** 无激活码版本使用的档位（tier 为 4 位，15 即解锁全部功能）。 */
    private const val FREE_BUILD_TIER = 15

    private val freeBuild: Boolean get() = !BuildConfig.ACTIVATION_REQUIRED

    private fun freeSnapshot() = Snapshot(
        activated = true,
        permanent = true,
        tier = FREE_BUILD_TIER,
    )

    val snapshotFlow: StateFlow<Snapshot> field =
        MutableStateFlow(if (freeBuild) freeSnapshot() else Snapshot())

    val isActivatedFlow: StateFlow<Boolean> field = MutableStateFlow(freeBuild)

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
        val record = ActivationStore.read(appContext, secret)
        val now = Instant.now().epochSecond

        // 时间回拨检测：只看记录里的 lastSeen，与当前时间比较
        val clockAnomaly = record.lastSeen > 0 && now < record.lastSeen - CLOCK_TOLERANCE_SECONDS

        if (record.isEmpty) {
            publish(ActivationResult.Failure(ActivationError.BadSignature), clockAnomaly)
            return
        }

        val result = ActivationCodec.verify(
            secret = secret,
            code = record.code,
            nowSeconds = ActivationCodec.currentIssueSeconds(),
            today = LocalDate.now(ZoneOffset.UTC),
            // 关键：复检已存储的码时不再校验激活窗口，
            // 窗口只约束"何时能激活"，不约束"激活后能用多久"。
            enforceWindow = false,
        )

        when (result) {
            is ActivationResult.Success -> {
                // 限时码在时间回拨时拒绝，永久码只告警（避免误伤）
                if (clockAnomaly && !result.permanent) {
                    publish(ActivationResult.Failure(ActivationError.ClockAnomaly), true)
                    return
                }
                if (allowWrite) {
                    ActivationStore.touch(appContext, secret, record, now)
                }
                publish(result, clockAnomaly)
            }

            is ActivationResult.Failure -> {
                publish(result, clockAnomaly)
            }
        }
    }

    /** 用户提交激活码。这是唯一会校验激活窗口的入口。 */
    fun activate(input: String): Outcome {
        val normalized = ActivationCodec.normalize(input)
        if (normalized.isEmpty()) return Outcome.InvalidInput("请输入激活码")
        if (normalized.length != ActivationCodec.CODE_LENGTH) {
            return Outcome.InvalidInput(
                "激活码应为 ${ActivationCodec.CODE_LENGTH} 位，当前 ${normalized.length} 位",
            )
        }
        if (!ActivationCodec.isValidAlphabet(normalized)) {
            // U 不在字母表内，I/L/O 已在前一步纠正
            return Outcome.InvalidInput("激活码含无效字符，请核对后重试")
        }

        val result = ActivationCodec.verify(
            secret = secret,
            code = normalized,
            nowSeconds = ActivationCodec.currentIssueSeconds(),
            today = LocalDate.now(ZoneOffset.UTC),
            enforceWindow = true,
        )
        return when (result) {
            is ActivationResult.Success -> {
                val now = Instant.now().epochSecond
                ActivationStore.write(
                    appContext,
                    secret,
                    ActivationStore.Record(
                        code = normalized,
                        activatedAt = now,
                        lastSeen = now,
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
        result: ActivationResult,
        clockAnomaly: Boolean,
    ) {
        val snapshot = when (result) {
            is ActivationResult.Success -> {
                val remaining = result.expiryDays.takeIf { it != 0 }?.let { days ->
                    days - java.time.temporal.ChronoUnit.DAYS
                        .between(ActivationCodec.EPOCH, LocalDate.now(ZoneOffset.UTC))
                }
                Snapshot(
                    activated = true,
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
                clockAnomaly = clockAnomaly,
            )
        }
        // 无激活码版本：无论激活记录校验结果如何，一律放行且永久有效。
        // 只在这一处兜底即可 —— isActivatedFlow 是全部门禁的唯一事实源
        // （A11yRuleEngine 的执行判断与界面的激活页判断都读它）。
        val effective = if (freeBuild) freeSnapshot() else snapshot
        snapshotFlow.value = effective
        isActivatedFlow.value = effective.activated
    }
}
