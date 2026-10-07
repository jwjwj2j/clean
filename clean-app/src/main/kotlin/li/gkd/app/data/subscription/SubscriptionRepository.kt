package li.gkd.app.data.subscription

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import li.gkd.app.app
import li.gkd.app.text.UiStrings
import li.gkd.app.core.state.Loadable
import li.gkd.app.data.RawSubscription
import li.gkd.app.data.SubsVersion
import li.gkd.app.data.edit
import li.gkd.app.domain.rule.CategoryPolicy
import li.gkd.app.util.LogUtils
import li.gkd.app.util.MutexState
import li.gkd.app.util.NetworkUtils
import li.gkd.app.util.client
import li.gkd.app.util.distinctByIfAny
import li.gkd.app.util.filterIfNotAll
import li.gkd.app.util.json
import li.gkd.app.util.rawClient
import li.gkd.db.Db
import li.gkd.db.LOCAL_SUBS_ID
import li.gkd.db.SubsItem
import li.songe.json5.decodeFromJson5String

/**
 * CLEAN 随包兜底的订阅规则资源（assets/[BUNDLED_RULES_ASSET]）。
 *
 * **为什么需要它**：内置订阅源是上游的 `@gkd-kit/subscription`，该订阅自称
 * 「默认订阅-已停止维护」，且规则**只能联网下载**——APK 里原本没有任何规则。
 * 后果有两个，都很严重：
 * 1. 全新安装且当时无网络 → 零条规则，什么都拦不住，用户会认为 App 是坏的；
 * 2. 上游 CDN 一旦失效 → 所有用户同时失效，且无法自愈。
 *
 * **因此把某个版本的规则随包打进去作为兜底**，规则如下：
 * - 联网正常时**仍以网络为准**（网络版本可能比随包新），不改变既有更新链路；
 * - 三个地址全部失败且本地无订阅时，用随包规则安装，保证首次离线也能拦；
 * - 随包版本**高于**已安装版本时用随包规则升级，使 App 更新即可带来规则更新；
 * - 随包版本低于已安装版本时**不回退**，避免用旧规则覆盖用户已经拿到的新规则。
 *
 * 更新方法：用 `tools/update_bundled_rules.py` 重新抓取覆盖该资源即可。
 * 资源缺失或解析失败一律静默降级为「没有兜底」，绝不阻断启动。
 */
private const val BUNDLED_RULES_ASSET = "gkd-fallback.json5"

/**
 * CLEAN **自有规则源**资源（assets/[CLEAN_RULES_ASSET]）。
 *
 * **为什么需要它**：上游订阅自称「已停止维护」并冻结在 version 186，CLEAN 无法增加、
 * 也无法调整任何规则（`matchTime` / `actionMaximum` 等参数写在规则正文里，而本地配置层
 * `RuleSetting` 只能覆盖「启用/禁用」这一个布尔量）。要让拦截能力真正变强，就必须拥有
 * 自己的规则源。
 *
 * **机制**：项目本就支持「本地订阅」——[LOCAL_SUBS_ID] 对应的订阅
 * - 不会被单源强制清除（`purgeForeignSources` 显式保留 `isLocal`）；
 * - 内容存在 `subsFolder/<id>.json`，可读写；
 * - 与上游订阅同时启用，规则会合并生效。
 *
 * 因此把 CLEAN 自己写的规则随包发在这里，启动时物化到本地订阅即可 —— 与网络、与上游
 * CDN 都无关，且发新版本 App 就能更新规则。
 *
 * 注意坑：`SubsItem.enable` 默认 false，物化后必须显式启用，否则「装上却零规则」。
 */
private const val CLEAN_RULES_ASSET = "clean-rules.json5"

/** 读取随包兜底规则正文；资源缺失或读取失败返回 null（不抛异常）。 */
private fun readBundledRulesText(): String? = runCatching {
    app.assets.open(BUNDLED_RULES_ASSET).use { it.readBytes().decodeToString() }
}.onFailure {
    LogUtils.d("随包兜底规则读取失败", it.message)
}.getOrNull()

/** 解析随包兜底规则；任何失败都返回 null。 */
private fun parseBundledRules(): RawSubscription? = runCatching {
    readBundledRulesText()?.let { RawSubscription.parse(it) }
}.onFailure {
    LogUtils.d("随包兜底规则解析失败", it.message)
}.getOrNull()

/** 读取 CLEAN 自有规则；资源缺失或读取失败返回 null。 */
private fun readCleanRulesText(): String? = runCatching {
    app.assets.open(CLEAN_RULES_ASSET).use { it.readBytes().decodeToString() }
}.onFailure {
    LogUtils.d("CLEAN 自有规则读取失败", it.message)
}.getOrNull()

/** 解析 CLEAN 自有规则；任何失败都返回 null。 */
private fun parseCleanRules(): RawSubscription? = runCatching {
    readCleanRulesText()?.let { RawSubscription.parse(it) }
}.onFailure {
    LogUtils.d("CLEAN 自有规则解析失败", it.message)
}.getOrNull()

object SubscriptionRepository {
    private val updateMutex = MutexState()

    val snapshotFlow: StateFlow<Loadable<SubscriptionSnapshot>>
        field = MutableStateFlow<Loadable<SubscriptionSnapshot>>(Loadable.Loading)
    val updating = updateMutex.state
    val isBusy: Boolean
        get() = updating.value

    suspend fun existingUpdateUrls(): Set<String> =
        Db.subsItemDao.queryAll().mapNotNullTo(mutableSetOf()) { it.updateUrl }

    suspend fun initialize() = withContext(Dispatchers.IO) {
        updateMutex.withStateLock {
            snapshotFlow.value = Loadable.Loading
            try {
                refreshRawSubscriptions(
                    items = Db.subsItemDao.queryAll(),
                    previous = SubscriptionSnapshot(),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                snapshotFlow.value = Loadable.Failure(e)
                throw e
            }
        }
        ensureLocalSubscription()
        // CLEAN：确保内置订阅源已安装并启用，同时清除其它订阅源（单源强制）。
        // 注意不能写在上面那次 withStateLock 里 —— MutexState 不可重入，会死锁。
        // 离线或下载失败时静默跳过，不阻断启动，下次启动会重试。
        runCatching { ensureBuiltin() }
            .onFailure { LogUtils.d("内置订阅源安装失败", it.message) }
    }

    /**
     * CLEAN 内置订阅源地址（主 + 备用），按顺序重试。
     *
     * 该源直接返回原始 JSON5 订阅正文（响应头为 application/json5），
     * 因此可以原样交给 [RawSubscription.parse]。
     */
    val builtinUrls = listOf(
        // 只用「直接返回 JSON5 正文」的地址。
        //
        // 曾把 npmmirror 的 tarball（/-/xxx.tgz）放在首位，但它有两个问题：
        //  1) 需要 tar 解包，真机上实测解包失败（本地同算法却通过，差异未定位）；
        //  2) 即使解包成功，它也会被记进 updateUrl，而「检查更新」要按 JSON 解析
        //     SubsVersion —— 拿压缩包去解析必然报
        //     「Fields [id, version] are required ... but they were missing」。
        // 因此只保留纯净的 JSON5 端点。
        // npmmirror 是**唯一在国内可达**的源（实测：jsdelivr 连接被重置、
        // cdn.jsdelivr.net 与 unpkg.com 均 DNS 解析失败）。它的 /latest/files 端点
        // 对带 @ 的包名返回 403，因此只能用 tarball 端点，必须解包。
        "https://registry.npmmirror.com/@fkybnjd/gkd-subscription-cn/-/gkd-subscription-cn-1.0.6.tgz",
        "https://fastly.jsdelivr.net/npm/@fkybnjd/gkd-subscription-cn/dist/AIsouler_gkd_cn.json5",
        "https://cdn.jsdelivr.net/npm/@fkybnjd/gkd-subscription-cn/dist/AIsouler_gkd_cn.json5",
        "https://unpkg.com/@fkybnjd/gkd-subscription-cn/dist/AIsouler_gkd_cn.json5",
    )

    /**
     * 旧内置源（官方 @gkd-kit/subscription）。
     *
     * 该订阅自 2024-02-03 起停更，冻结在 version 186（来源：npmmirror registry
     * 的发布记录），因此已换成上面覆盖更广、仍在维护的第三方源。
     *
     * **这几个 URL 只在迁移时使用：** [ensureBuiltin] 判断"内置槽位"的依据是
     * `updateUrl in builtinUrls`。老用户库里存的是旧 URL，若不显式识别并删除，
     * 该判断会落空 → 走"全新安装"分支 → **新旧两份订阅并存** →
     * 同一个按钮上两套规则叠加、连点两次 → 界面已变时第二下点到别处。
     * 这正是「中国移动关怀模式被误关」事故的成因，不能重演。
     */
    /**
     * 用随包规则兜底安装时，写入 [SubsItem.updateUrl] 的占位值。
     *
     * **为什么不能写成 [builtinUrls] 里的地址：** [ensureBuiltin] 判断
     * 「内置槽位已存在」的依据是 `updateUrl in builtinUrls`。如果兜底安装也记成
     * 真实 URL，那么只要**一次**网络失败（限流、超时、断网），下次启动就会命中
     * 「已存在」分支并 `return` —— **再也不会重试下载**，用户被永久钉在随包的
     * 停更规则上，即使网络恢复也没救。
     *
     * 用占位值让 `in builtinUrls` 恒为 false，从而**每次启动都重试**，
     * 直到真正下载成功、写入真实 URL 为止。
     */
    const val BUNDLED_PLACEHOLDER_URL = "bundled://gkd-fallback.json5"

    val legacyBuiltinUrls = listOf(
        "https://registry.npmmirror.com/@gkd-kit/subscription/latest/files",
        "https://registry.npmmirror.com/@gkd-kit/subscription/latest/files/dist/gkd.json5",
        "https://fastly.jsdelivr.net/npm/@gkd-kit/subscription",
    )

    /**
     * 确保内置订阅源已安装并启用，并清除其它订阅源。
     *
     * 设计要点（改动前请先读 docs/03）：
     * - **必须在锁内完成下载与写入**，由本函数自行取锁；调用方不要再包一层 withStateLock。
     * - 安装时必须显式 `enable = true`：`SubsItem.enable` 默认 false，而「应用」页只展示
     *   enable=true 的订阅，漏掉会出现"装上却零规则"。
     * - 不能复用 [addOrModifyRemote]：它把 enable 硬编码为 false，且并发时直接返回 Busy。
     * - 失败只返回结果，不抛异常，避免阻断 App 启动。
     */
    suspend fun ensureBuiltin(): SubscriptionResult = withContext(Dispatchers.IO) {
        var result: SubscriptionResult = SubscriptionResult.Busy
        val acquired = updateMutex.tryWithStateLock {
            val items = Db.subsItemDao.queryAll()

            // 迁移：清掉旧内置源，避免它与新源并存。
            // 必须在查询 existing **之前**执行，否则下面会以为"还没有内置源"。
            val legacyIds = items
                .filter { it.updateUrl != null && it.updateUrl in legacyBuiltinUrls }
                .map { it.id }
                .toLongArray()
            if (legacyIds.isNotEmpty()) {
                runCatching { SubscriptionPersistence.delete(legacyIds) }
                    .onSuccess { LogUtils.d("已移除旧内置订阅源（迁移到新源）", it.ids) }
                    .onFailure { LogUtils.d("移除旧内置订阅源失败", it.message) }
            }
            val liveItems = Db.subsItemDao.queryAll()
            val existing =
                liveItems.firstOrNull { it.updateUrl != null && it.updateUrl in builtinUrls }

            // 自愈：如果已安装的订阅**就是随包兜底那份**（id 与随包规则相同），
            // 说明它从来不是从网络装上的 —— 典型来源是 Android 自动备份在重装时的恢复。
            // 这种情况绝不能 early return，否则会永远停在停更的兜底规则上：
            // 现象就是「第一次安装成功、卸载重装后一直显示默认订阅」。
            val bundledId = parseBundledRules()?.id
            val existingIsBundled =
                existing != null && bundledId != null && existing.id == bundledId

            if (existing != null && !existingIsBundled) {
                if (!existing.enable) {
                    Db.subsItemDao.updateEnable(existing.id, true)
                }
                purgeForeignSources(keepId = existing.id)
                // CLEAN：随包规则比已安装的更新时，用随包规则升级。
                // 这样发新版本 App 就能顺带更新规则，不必等网络同步。
                // 只在「严格更新」时替换，随包更旧则保持不动。
                val installedVersion =
                    snapshotFlow.value.value?.subscriptions?.get(existing.id)?.version
                val bundled = parseBundledRules()
                if (
                    bundled != null && installedVersion != null &&
                    bundled.id == existing.id && bundled.version > installedVersion
                ) {
                    LogUtils.d(
                        "随包规则更新",
                        "已安装 version=$installedVersion, 随包 version=${bundled.version}",
                    )
                    saveLocked(
                        subscription = bundled,
                        insertItem = false,
                    )
                }
                refreshRawSubscriptions(
                    items = Db.subsItemDao.queryAll(),
                    previous = SubscriptionSnapshot(),
                )
                result = SubscriptionResult.Success(kind = SubscriptionResult.SuccessKind.Refreshed)
                return@tryWithStateLock
            }

            var text: String? = null
            var lastError: Exception? = null
            // 记录内容究竟来自网络还是随包兜底 —— 决定写入哪个 updateUrl，
            // 进而决定下次启动是否重试（见 BUNDLED_PLACEHOLDER_URL 的说明）。
            var fromNetwork = false
            // 记录**实际成功**的那个地址，而不是 builtinUrls.first()。
            // 否则一旦首选项失败、后面某个成功，updateUrl 会被写成失败地址，
            // 导致之后每次「检查更新」都对着一个无效 URL 重试。
            var successUrl: String? = null
            for (url in builtinUrls) {
                try {
                    text = extractRulesPayload(rawClient.get(url).body<ByteArray>())
                    fromNetwork = true
                    successUrl = url
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e
                    LogUtils.d("内置订阅源下载失败", url, e.message)
                }
            }
            // CLEAN：三个地址全部失败时回退到随包兜底规则。
            // 这是「全新安装 + 无网络」以及「上游 CDN 失效」两条路径的唯一保障。
            if (text == null) {
                LogUtils.d("内置订阅源全部下载失败，改用随包兜底规则", lastError?.message)
                text = readBundledRulesText()
            }
            if (text == null) {
                result = SubscriptionResult.Failure(
                    reason = SubscriptionResult.FailureReason.Download,
                    detail = lastError?.message,
                    cause = lastError,
                )
                return@tryWithStateLock
            }

            // 下载/解包可能成功但正文无效（例如 npm 包里 15 字节的版本元数据）。
            // 若把这种内容当订阅装进去，原有好规则已被 purge，用户就会看到
            // 「规则几乎为 0」。所以先体检，不合格就退回随包兜底。
            if (!looksLikeSubscription(text)) {
                LogUtils.d("下载内容不像订阅正文，改用随包兜底规则", "长度=${text.length}")
                text = readBundledRulesText()
            }
            if (text == null) {
                result = SubscriptionResult.Failure(
                    reason = SubscriptionResult.FailureReason.Download,
                    detail = lastError?.message,
                    cause = lastError,
                )
                return@tryWithStateLock
            }

            val subscription = try {
                RawSubscription.parse(text).branded()
            } catch (e: Exception) {
                result = SubscriptionResult.Failure(
                    reason = SubscriptionResult.FailureReason.Parse,
                    detail = e.message,
                    cause = e,
                )
                return@tryWithStateLock
            }
            if (subscription.id < 0) {
                result = SubscriptionResult.Failure(
                    reason = SubscriptionResult.FailureReason.InvalidId,
                    detail = subscription.id.toString(),
                )
                return@tryWithStateLock
            }

            purgeForeignSources(keepId = null)
            try {
                saveLocked(
                    subscription = subscription,
                    newItem = SubsItem(
                        id = subscription.id,
                        // 网络下载成功才记真实 URL；兜底则记占位值，
                        // 保证下次启动仍会重试下载。
                        updateUrl = if (fromNetwork) (successUrl ?: builtinUrls.first())
                        else BUNDLED_PLACEHOLDER_URL,
                        order = 0,
                        enable = true,
                    ),
                    insertItem = true,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                result = SubscriptionResult.Failure(
                    reason = SubscriptionResult.FailureReason.Save,
                    detail = e.message,
                    cause = e,
                )
                return@tryWithStateLock
            }
            result = SubscriptionResult.Success(kind = SubscriptionResult.SuccessKind.Added)
        }
        if (!acquired) return@withContext SubscriptionResult.Busy
        result
    }

    /** CLEAN 单源强制：删除除 [keepId] 与本地订阅之外的所有订阅源。必须在锁内调用。 */
    /**
     * 把订阅响应体转成规则正文，**同时兼容两种格式**：
     *
     * - 裸 JSON5（jsdelivr 等直接返回文件内容）-> 原样解码
     * - gzip 的 npm tarball（npmmirror 的 /-/xxx.tgz）-> 解包后取包内规则文件
     *
     * 为什么要支持 tarball：npmmirror 的 /latest/files 端点对带 @ 的包名返回 403，
     * 唯一可用的国内端点是 tarball；而 jsdelivr 在国内时通时不通，不能作为主源。
     */
    private fun extractRulesPayload(bytes: ByteArray): String {
        // gzip magic: 1F 8B；非 gzip 一律按文本处理
        if (bytes.size < 2 || bytes[0] != 0x1F.toByte() || bytes[1] != 0x8B.toByte()) {
            return bytes.decodeToString()
        }
        // 一次性把 tar 解压进内存再解析。
        //
        // 为什么不流式读：早先的实现用「逐个 512 字节块 + 手动跳过对齐」的方式，
        // 在真机上失败（报「tarball 内未找到规则文件」），而同样的逻辑在本地用 Python
        // 跑同一个文件是通的 —— 说明差异出在流的读取/跳过行为上，而不是数据本身。
        // 规则包解压后仅约 1.4 MB，直接整体读入、按显式偏移量解析是确定性的：
        // 没有流状态、没有 skip、没有对齐，出错的余地小得多。
        val tar = java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(bytes))
            .use { it.readBytes() }
        var offset = 0
        var best: ByteArray? = null
        while (offset + TAR_BLOCK <= tar.size) {
            // 全零块表示归档结束
            var allZero = true
            for (p in offset until offset + TAR_BLOCK) {
                if (tar[p] != 0.toByte()) {
                    allZero = false
                    break
                }
            }
            if (allZero) break
            // 用 code 比较填充字符，避免在字符串里写引号
            val name = String(tar, offset, 100, Charsets.UTF_8)
                .trimEnd { it.code == 0 || it.code == 32 }
            // 只保留八进制数字（0..7 = 字符码 48..55），其余字符一律丢弃。
            //
            // tar 的 size 字段是 12 字节，按惯例为「八进制数字 + NUL/空格填充」，
            // 但填充形态并不统一：真机实测在 trim()/trimEnd{} 之后**仍残留一个字符**，
            // 使 toLongOrNull(8) 返回 null、size 变成 0 —— 于是 `size > 0` 恒为假，
            // 永远不检查成员名，最后报「tarball 内未找到规则文件」。
            // 直接过滤出数字字符不受填充形态影响，是最稳的写法。
            val sizeText = String(tar, offset + 124, 12, Charsets.US_ASCII)
                .filter { it.code in 48..55 }
            val size = sizeText.toLongOrNull(8) ?: 0L
            val dataStart = offset + TAR_BLOCK
            val dataEnd = dataStart + size
            LogUtils.d(
                "tar成员",
                "offset=$offset", "name=[$name]", "sizeRaw=[$sizeText]", "size=$size",
                "tarSize=${tar.size}",
            )
            if (size > 0 && size <= MAX_TAR_MEMBER_SIZE && dataEnd <= tar.size) {
                if (name.endsWith(".json5") && !name.endsWith(".version.json5")) {
                    val data = tar.copyOfRange(dataStart, dataEnd.toInt())
                    // 取最大的，而不是第一个：包内可能同时有规则正文与版本元数据
                    val current = best
                    if (current == null || data.size > current.size) best = data
                }
            }
            // 数据区按 512 对齐
            offset = dataStart + (((size + TAR_BLOCK - 1) / TAR_BLOCK) * TAR_BLOCK).toInt()
        }
        return best?.decodeToString()
            ?: error("tarball 内未找到规则文件 size=${tar.size}")
    }

    /** tar 每块 512 字节。 */
    /** 单个成员大小上限，防御 tar 头被误读成垃圾值时产生超大数组分配。 */
    private const val MAX_TAR_MEMBER_SIZE = 16L * 1024 * 1024

    /** 精确跳过 n 字节（不补齐）。 */
    private fun java.io.InputStream.skipExact(n: Long): Boolean {
        var remaining = n
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
                continue
            }
            if (read() < 0) return false
            remaining -= 1
        }
        return true
    }

    private const val TAR_BLOCK = 512

    /** 读满 target，EOF 返回 false。 */
    private fun java.io.InputStream.readFully(target: ByteArray): Boolean {
        var offset = 0
        while (offset < target.size) {
            val read = read(target, offset, target.size - offset)
            if (read <= 0) return false
            offset += read
        }
        return true
    }

    /** 跳过 n 字节并按 512 对齐。 */
    private fun java.io.InputStream.skipFully(n: Long): Boolean {
        var remaining = (n + TAR_BLOCK - 1) / TAR_BLOCK * TAR_BLOCK
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
                continue
            }
            if (read() < 0) return false
            remaining -= 1
        }
        return true
    }

    /**
     * 轻量体检：下载到的正文是否**像**一份订阅规则。
     *
     * 拦住「解析能通过、但几乎没有规则」的情况。一旦这类内容被当成订阅装进去，
     * 原有的好规则又已被 purge，用户看到的就是「规则几乎为 0」。
     * 宁可退回随包兜底，也不要装一份空规则。
     */
    private fun looksLikeSubscription(text: String): Boolean {
        if (text.length < MIN_SUBSCRIPTION_LENGTH) return false
        return text.contains("globalGroups") || text.contains("apps")
    }

    /** 规则正文长度下限。
     *
     * 真实订阅都是数百 KB；15 字节的版本元数据这类内容会被直接排除。
     */
    private const val MIN_SUBSCRIPTION_LENGTH = 10_000

    private suspend fun purgeForeignSources(keepId: Long?) {
        val stale = Db.subsItemDao.queryAll()
            .filter { it.id != keepId && !it.isLocal }
            .map { it.id }
            .toLongArray()
        if (stale.isEmpty()) return
        runCatching { SubscriptionPersistence.delete(stale) }
            .onSuccess { LogUtils.d("已清除非内置订阅源", it.ids) }
            .onFailure { LogUtils.d("清除订阅源失败", it.message) }
    }

    private suspend fun ensureLocalSubscription() = withContext(Dispatchers.IO) {
        updateMutex.withStateLock {
            try {
                val items = Db.subsItemDao.queryAll()
                if (snapshotFlow.value !is Loadable.Ready) {
                    refreshRawSubscriptions(
                        items = items,
                        previous = SubscriptionSnapshot(),
                    )
                }
                // CLEAN：本地订阅承载「CLEAN 自有规则源」。先判断是否需要写入随包规则：
                // 无文件、或随包版本更高时都要写；版本不高于现有则保持不动，
                // 与内置源的升级口径一致，避免用旧规则覆盖用户已拿到的新规则。
                val bundled = parseCleanRules()
                val existingItem = items.firstOrNull { it.id == LOCAL_SUBS_ID }
                val installedVersion = SubscriptionFileStore.readBytes(LOCAL_SUBS_ID)
                    ?.let { bytes ->
                        runCatching { RawSubscription.parse(bytes.decodeToString()).version }.getOrNull()
                    }
                val needsMaterialize = bundled != null &&
                        (installedVersion == null || bundled.version > installedVersion)

                if (existingItem == null) {
                    val item = SubsItem(
                        id = LOCAL_SUBS_ID,
                        order = items.minByOrNull { it.order }?.order ?: 0,
                        // CLEAN：必须显式启用 —— SubsItem.enable 默认 false，
                        // 否则自有规则「装上了却零规则生效」。
                        enable = bundled != null,
                    )
                    if (bundled != null && needsMaterialize) {
                        SubscriptionFileStore.write(bundled)
                    }
                    if (SubscriptionFileStore.readBytes(LOCAL_SUBS_ID) != null) {
                        Db.subsItemDao.upsert(item)
                        refreshRawSubscriptions(listOf(item))
                    } else {
                        saveLocked(
                            subscription = RawSubscription(
                                id = LOCAL_SUBS_ID,
                                name = UiStrings.subscription_local,
                                version = 0,
                            ),
                            newItem = item,
                            insertItem = true,
                        )
                    }
                } else {
                    // 条目已存在（含从旧版本升级上来的「本地订阅为空且禁用」状态）：
                    // 补写随包规则，并确保启用。
                    if (bundled != null && needsMaterialize) {
                        LogUtils.d("写入 CLEAN 自有规则", "version=${bundled.version}")
                        SubscriptionFileStore.write(bundled)
                        refreshRawSubscriptions(
                            items = Db.subsItemDao.queryAll(),
                            previous = SubscriptionSnapshot(),
                        )
                    }
                    if (bundled != null && !existingItem.enable) {
                        Db.subsItemDao.updateEnable(LOCAL_SUBS_ID, true)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (snapshotFlow.value !is Loadable.Ready) {
                    snapshotFlow.value = Loadable.Failure(e)
                }
                throw e
            }
        }
    }

    suspend fun awaitSnapshot(): SubscriptionSnapshot {
        return when (val state = snapshotFlow.first { it !is Loadable.Loading }) {
            Loadable.Loading -> error(UiStrings.subscription_not_loaded)
            is Loadable.Failure -> throw state.cause
            is Loadable.Ready -> state.value
        }
    }

    /**
     * Keeps backup file changes and compensation exclusive with subscription updates.
     * The block owns the database transaction and uses SubscriptionPersistence directly.
     */
    suspend fun <T> withBackupTransaction(
        subscriptions: List<RawSubscription>,
        block: suspend (List<RawSubscription>) -> T,
    ): T = withContext(Dispatchers.IO) {
        updateMutex.withStateLock {
            val previous = snapshotFlow.value.value ?: refreshRawSubscriptions(Db.subsItemDao.queryAll())
            val prepared = subscriptions.map { prepareSubscription(it, previous) }
            // Waiting for an in-flight refresh remains cancellable; an accepted restore completes.
            withContext(NonCancellable) {
                try {
                    val result = block(prepared)
                    refreshRawSubscriptions(
                        items = Db.subsItemDao.queryAll(),
                        previous = SubscriptionSnapshot(),
                    )
                    result
                } catch (error: Throwable) {
                    // Compensation can itself fail: publish what is actually readable from disk.
                    runCatching {
                        refreshRawSubscriptions(
                            items = Db.subsItemDao.queryAll(),
                            previous = SubscriptionSnapshot(),
                        )
                    }.exceptionOrNull()?.let(error::addSuppressed)
                    throw error
                }
            }
        }
    }

    suspend fun saveWithItem(
        subscription: RawSubscription,
        defaultItem: SubsItem,
    ) = withContext(Dispatchers.IO) {
        require(subscription.id == defaultItem.id) {
            UiStrings.subscription_item_id_mismatch(subscription.id, defaultItem.id)
        }
        updateMutex.withStateLock {
            val currentItem = Db.subsItemDao.queryAll().find { it.id == subscription.id }
            try {
                saveLocked(
                    subscription = subscription,
                    newItem = currentItem ?: defaultItem,
                    insertItem = currentItem == null,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setUpdateError(subscription.id, e)
                throw e
            }
        }
    }

    // Configuration commands share the subscription lock so an update cannot change
    // category membership between validating the displayed snapshot and writing.
    suspend fun <T> withSubscriptionSnapshot(
        expected: RawSubscription,
        action: suspend (RawSubscription) -> T,
    ): T = withSubscriptionSnapshots(listOf(expected)) { action(expected) }

    suspend fun <T> withSubscriptionSnapshots(
        expected: Collection<RawSubscription>,
        action: suspend () -> T,
    ): T = updateMutex.withStateLock {
        expected.forEach { subscription ->
            val current = requireSnapshot(subscription.id).subscriptions[subscription.id]
                ?: error(UiStrings.subscription_missing)
            check(current == subscription) { UiStrings.subscription_content_conflict }
        }
        action()
    }

    suspend fun saveCategory(
        expected: RawSubscription,
        categoryKey: Int?,
        name: String,
        description: String,
    ): Boolean = update(expected.id) { current ->
        check(current == expected) { UiStrings.subscription_preview_conflict }
        CategoryPolicy.previewEdit(current, categoryKey, name, description)
    }

    suspend fun deleteCategory(expected: RawSubscription, categoryKey: Int): Boolean =
        deleteCategories(expected, setOf(categoryKey))

    suspend fun deleteCategories(expected: RawSubscription, categoryKeys: Set<Int>): Boolean =
        update(expected.id) { current ->
            require(current.isLocal) { UiStrings.remote_category_delete_unsupported }
            check(current == expected) { UiStrings.subscription_content_conflict }
            current.edit {
                categoryKeys.forEach { categoryKey ->
                    check(removeCategory(categoryKey) != null) { UiStrings.category_missing }
                }
            }
        }

    suspend fun update(
        id: Long,
        transform: (RawSubscription) -> RawSubscription,
    ): Boolean = withContext(Dispatchers.IO) {
        var changed = false
        updateMutex.withStateLock {
            val snapshot = requireSnapshot(id)
            val current = snapshot.subscriptions[id]
                ?: throw (snapshot.loadErrors[id] ?: IllegalStateException(UiStrings.subscription_missing_id(id)))
            val next = transform(current)
            require(next.id == id) { UiStrings.subscription_id_immutable(id, next.id) }
            if (next == current) return@withStateLock
            try {
                saveLocked(next)
                changed = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setUpdateError(id, e)
                throw e
            }
        }
        changed
    }

    suspend fun delete(vararg subscriptionIds: Long): SubscriptionResult =
        withContext(Dispatchers.IO) {
            if (subscriptionIds.isEmpty()) return@withContext SubscriptionResult.Success()
            var result: SubscriptionResult = SubscriptionResult.Busy
            updateMutex.withStateLock {
                val deletion = try {
                    SubscriptionPersistence.delete(subscriptionIds)
                } catch (e: SubscriptionPersistence.DeleteException) {
                    result = SubscriptionResult.Failure(
                        reason = when (e.stage) {
                            SubscriptionPersistence.DeleteStage.File ->
                                SubscriptionResult.FailureReason.DeleteFile

                            SubscriptionPersistence.DeleteStage.Database ->
                                SubscriptionResult.FailureReason.DeleteData
                        },
                        detail = e.message,
                        cause = e,
                    )
                    return@withStateLock
                }
                if (deletion.count == 0) {
                    result = SubscriptionResult.Success()
                    return@withStateLock
                }
                val snapshot = snapshotFlow.value.value
                if (snapshot != null) {
                    snapshotFlow.value = Loadable.Ready(snapshot.copy(
                        subscriptions = snapshot.subscriptions - deletion.ids,
                        loadErrors = snapshot.loadErrors - deletion.ids,
                        updateErrors = snapshot.updateErrors - deletion.ids,
                    ))
                }
                LogUtils.d("deleteSubscription", deletion.ids)
                result = SubscriptionResult.Success(
                    kind = SubscriptionResult.SuccessKind.Deleted,
                    count = deletion.count,
                )
            }
            result
        }

    suspend fun addOrModifyRemote(
        url: String,
        oldItem: SubsItem? = null,
    ): SubscriptionResult = withContext(Dispatchers.IO) {
        fun failure(
            reason: SubscriptionResult.FailureReason,
            detail: String? = null,
            cause: Exception = IllegalArgumentException(reason.name),
        ): SubscriptionResult.Failure {
            oldItem?.id?.let { setUpdateError(it, cause) }
            return SubscriptionResult.Failure(reason, detail, cause)
        }

        var result: SubscriptionResult = SubscriptionResult.Busy
        val acquired = updateMutex.tryWithStateLock {
            val items = Db.subsItemDao.queryAll()
            if (items.any { it.updateUrl == url && it.id != oldItem?.id }) {
                result = failure(SubscriptionResult.FailureReason.DuplicateUrl)
                return@tryWithStateLock
            }
            val text = try {
                client.get(url).bodyAsText()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                LogUtils.d(e)
                result = failure(
                    reason = SubscriptionResult.FailureReason.Download,
                    detail = e.message,
                    cause = e,
                )
                return@tryWithStateLock
            }
            val subscription = try {
                RawSubscription.parse(text).branded()
            } catch (e: Exception) {
                e.printStackTrace()
                LogUtils.d(e)
                result = failure(
                    reason = SubscriptionResult.FailureReason.Parse,
                    detail = e.message,
                    cause = e,
                )
                return@tryWithStateLock
            }
            if (oldItem == null && items.any { it.id == subscription.id }) {
                result = failure(SubscriptionResult.FailureReason.AlreadyExists)
                return@tryWithStateLock
            }
            if (oldItem != null && oldItem.id != subscription.id) {
                result = failure(SubscriptionResult.FailureReason.IdMismatch)
                return@tryWithStateLock
            }
            if (subscription.id < 0) {
                result = failure(
                    reason = SubscriptionResult.FailureReason.InvalidId,
                    detail = subscription.id.toString(),
                )
                return@tryWithStateLock
            }
            val newItem = oldItem?.copy(updateUrl = url) ?: SubsItem(
                id = subscription.id,
                updateUrl = url,
                order = if (items.isEmpty()) 1 else items.maxOf { it.order } + 1,
            )
            try {
                saveLocked(
                    subscription = subscription,
                    newItem = newItem,
                    insertItem = oldItem == null,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setUpdateError(oldItem?.id ?: subscription.id, e)
                result = SubscriptionResult.Failure(
                    reason = SubscriptionResult.FailureReason.Save,
                    detail = e.message,
                    cause = e,
                )
                return@tryWithStateLock
            }
            result = SubscriptionResult.Success(
                if (oldItem == null) {
                    SubscriptionResult.SuccessKind.Added
                } else {
                    SubscriptionResult.SuccessKind.Modified
                },
            )
        }
        if (!acquired) return@withContext SubscriptionResult.Busy
        result
    }

    suspend fun refresh(): SubscriptionResult = withContext(Dispatchers.IO) {
        if (snapshotFlow.value is Loadable.Loading) {
            return@withContext SubscriptionResult.Busy
        }
        var result: SubscriptionResult = SubscriptionResult.Busy
        val acquired = updateMutex.tryWithStateLock {
            val items = try {
                Db.subsItemDao.queryAll()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (snapshotFlow.value !is Loadable.Ready) {
                    snapshotFlow.value = Loadable.Failure(e)
                }
                throw e
            }
            val currentSnapshot = snapshotFlow.value.value
            val missingItems = if (currentSnapshot == null) {
                items
            } else {
                items.filter { item -> item.id !in currentSnapshot.subscriptions }
            }
            val snapshot = refreshRawSubscriptions(
                items = missingItems,
                previous = currentSnapshot ?: SubscriptionSnapshot(),
            )
            val entries = items.map { item ->
                SubsEntry(item, snapshot.subscriptions[item.id])
            }
            if (entries.any { !it.subsItem.isLocal } && !NetworkUtils.isAvailable()) {
                result = SubscriptionResult.Failure(
                    SubscriptionResult.FailureReason.NetworkUnavailable
                )
                return@tryWithStateLock
            }
            LogUtils.d("开始检测更新")
            var successCount = 0
            entries.filter { !it.subsItem.isLocal }.forEach { entry ->
                try {
                    val subscription = fetchUpdate(entry)
                    if (subscription != null) {
                        saveLocked(subscription)
                        successCount++
                    } else {
                        clearUpdateError(entry.subsItem.id)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    setUpdateError(entry.subsItem.id, e)
                    LogUtils.d("检测更新失败", e.message)
                }
            }
            result = SubscriptionResult.Success(
                kind = SubscriptionResult.SuccessKind.Refreshed,
                count = successCount,
            )
            LogUtils.d("结束检测更新")
        }
        if (!acquired) return@withContext SubscriptionResult.Busy
        result
    }

    private suspend fun saveLocked(
        subscription: RawSubscription,
        newItem: SubsItem? = null,
        insertItem: Boolean = false,
    ) {
        val id = subscription.id
        val snapshot = snapshotFlow.value.value
            ?: refreshRawSubscriptions(
                items = Db.subsItemDao.queryAll(),
                previous = SubscriptionSnapshot(),
            )
        val nextSubscription = prepareSubscription(subscription, snapshot)
        SubscriptionPersistence.save(nextSubscription, newItem, insertItem)
        snapshotFlow.value = Loadable.Ready(snapshot.copy(
            subscriptions = snapshot.subscriptions.toMutableMap().apply {
                set(id, nextSubscription)
            },
            loadErrors = snapshot.loadErrors.toMutableMap().apply { remove(id) },
            updateErrors = snapshot.updateErrors.toMutableMap().apply { remove(id) },
        ))
        LogUtils.d("更新订阅文件:id=$id,name=${nextSubscription.name}")
    }

    private fun prepareSubscription(
        subscription: RawSubscription,
        snapshot: SubscriptionSnapshot,
    ): RawSubscription = if (
        subscription.id < 0 && snapshot.subscriptions[subscription.id]?.version == subscription.version
    ) {
        subscription.copy(
            version = subscription.version + 1,
            apps = subscription.apps.filterIfNotAll { it.groups.isNotEmpty() }
                .distinctByIfAny { it.id },
        )
    } else {
        subscription
    }

    private fun load(id: Long): RawSubscription {
        return SubscriptionFileStore.load(id)
    }

    private fun refreshRawSubscriptions(
        items: List<SubsItem>,
        previous: SubscriptionSnapshot = snapshotFlow.value.value ?: SubscriptionSnapshot(),
    ): SubscriptionSnapshot {
        val subscriptions = previous.subscriptions.toMutableMap()
        val errors = previous.loadErrors.toMutableMap()
        items.forEach { item ->
            try {
                subscriptions[item.id] = load(item.id)
                errors.remove(item.id)
            } catch (e: Exception) {
                errors[item.id] = e
            }
        }
        val nextSnapshot = previous.copy(
            subscriptions = subscriptions,
            loadErrors = errors,
        )
        snapshotFlow.value = Loadable.Ready(nextSnapshot)
        return nextSnapshot
    }

    private fun clearUpdateError(id: Long) {
        val snapshot = snapshotFlow.value.value ?: return
        if (id !in snapshot.updateErrors) return
        snapshotFlow.value = Loadable.Ready(snapshot.copy(
            updateErrors = snapshot.updateErrors.toMutableMap().apply { remove(id) },
        ))
    }

    private fun setUpdateError(id: Long, error: Exception) {
        val snapshot = snapshotFlow.value.value ?: return
        snapshotFlow.value = Loadable.Ready(snapshot.copy(
            updateErrors = snapshot.updateErrors.toMutableMap().apply { set(id, error) },
        ))
    }

    private fun requireSnapshot(id: Long): SubscriptionSnapshot {
        return when (val state = snapshotFlow.value) {
            Loadable.Loading -> error(UiStrings.subscription_not_loaded_id(id))
            is Loadable.Failure -> throw state.cause
            is Loadable.Ready -> state.value
        }
    }

    private suspend fun fetchUpdate(entry: SubsEntry): RawSubscription? {
        val item = entry.subsItem
        val current = entry.subscription
        val itemUpdateUrl = item.updateUrl ?: return null
        if (item.id < 0) return null
        val checkUrl = entry.checkUpdateUrl
        if (checkUrl != null && current != null) {
            try {
                val version = json.decodeFromJson5String<SubsVersion>(
                    client.get(checkUrl).bodyAsText(),
                )
                if (version.id == current.id && version.version <= current.version) return null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogUtils.d("快速检测更新失败", item, e.message)
            }
        }
        val updateUrl = current?.updateUrl ?: itemUpdateUrl
        val text = try {
            client.get(updateUrl).bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw Exception(UiStrings.subscription_update_url_request_failed, e)
        }
        val subscription = try {
            RawSubscription.parse(text).branded()
        } catch (e: Exception) {
            throw Exception(UiStrings.text_parse_failed, e)
        }
        if (subscription.id != item.id) {
            error(UiStrings.subscription_updated_id_mismatch(subscription.id, item.id))
        }
        if (current != null && subscription.version <= current.version) {
            LogUtils.d(
                UiStrings.subscription_version_mismatch(item.id),
                "${current.version} -> ${subscription.version}",
            )
            return null
        }
        return subscription
    }
}
