package io.modelbridge.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** 本机端口扫描结果与自定义中转站账号字段的纯逻辑。 */
data class ScannedGateway(val port: Int, val modelCount: Int) {
    val baseUrl: String get() = "http://127.0.0.1:$port/v1"
    val label: String get() = "本机 :$port"
    val display: String get() = "127.0.0.1:$port（$modelCount 个模型）"
}

object ScannedGatewayParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 解析 `/v1/models` 响应；不是合法 OpenAI 列表时返回 null。 */
    fun modelCount(body: String): Int? {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return null
        val array = when (root) {
            is JsonArray -> root
            is JsonObject -> root["data"] as? JsonArray ?: root["models"] as? JsonArray
            else -> null
        } ?: return null
        return array.size
    }

    fun scanned(port: Int, body: String): ScannedGateway? =
        modelCount(body)?.let { ScannedGateway(port, it) }

    /** 从原始 `HTTP/1.1` 文本里分离响应体；扫描器只读取短响应。 */
    fun responseBody(rawHttp: String): String {
        val separator = listOf("\r\n\r\n", "\n\n")
            .mapNotNull { rawHttp.indexOf(it).takeIf { index -> index >= 0 }?.let { index -> index to it } }
            .minByOrNull { it.first }
        return separator?.let { (index, token) -> rawHttp.substring(index + token.length) } ?: rawHttp
    }

    /** 把用户/扫描选中的地址规范化成 OpenAI 兼容 baseUrl。 */
    fun normalizeBaseUrl(raw: String): String {
        var value = raw.trim().trimEnd('/')
        if (value.isEmpty()) return ""
        if (!value.startsWith("http://", ignoreCase = true) &&
            !value.startsWith("https://", ignoreCase = true)
        ) {
            value = "http://$value"
        }
        return if (value.endsWith("/v1", ignoreCase = true)) value else "$value/v1"
    }
}
