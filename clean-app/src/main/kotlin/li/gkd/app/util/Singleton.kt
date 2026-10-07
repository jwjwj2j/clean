package li.gkd.app.util

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.text.Collator
import java.util.Locale


val json by lazy {
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }
}

val keepNullJson by lazy {
    Json(from = json) {
        explicitNulls = true
    }
}

/**
 * 订阅规则源（jsdelivr 等 CDN）**会拒绝或限流非浏览器 UA**。
 *
 * OkHttp 默认发 `okhttp/4.x`，实测表现为：**浏览器能打开该地址，App 却拉取失败**，
 * 于是静默退回随包兜底规则 —— 用户看到的是「订阅没更新」，很难定位。
 * 因此这里显式带上浏览器 UA。
 */
private const val USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/124.0.0.0 Mobile Safari/537.36"

val client by lazy {
    HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(json, ContentType.Any)
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 30_000
            // 规则文件约 1.2 MB，默认读超时在移动网络上偏紧
            socketTimeoutMillis = 60_000
            requestTimeoutMillis = 120_000
        }
        defaultRequest {
            headers[HttpHeaders.UserAgent] = USER_AGENT
        }
        engine {
            clientCacheSize = 0
        }
    }
}

val collator by lazy { Collator.getInstance(Locale.CHINESE)!! }

/**
 * 下载订阅规则专用：**不能挂 [io.ktor.client.plugins.contentnegotiation.ContentNegotiation]**。
 *
 * 原因：client 上注册的是 `json(json, ContentType.Any)`，而 kotlinx.serialization 支持
 * ByteArray，因此 `body<ByteArray>()` 会走转换器链、尝试把响应体当 JSON 反序列化。
 * 订阅正文是 **JSON5**（非标准 JSON），npm tarball 更是 gzip 二进制 —— 反序列化必然抛错，
 * 于是**所有规则源都失败**、静默退回随包的停更规则，现象是「规则几乎为 0」。
 *
 * 这里用一个不挂 ContentNegotiation 的裸客户端取原始字节，彻底绕开这条路径。
 */
val rawClient by lazy {
    HttpClient(OkHttp) {
        install(HttpTimeout) {
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 60_000
            requestTimeoutMillis = 120_000
        }
        defaultRequest {
            headers[HttpHeaders.UserAgent] = USER_AGENT
        }
        engine {
            clientCacheSize = 0
        }
    }
}
