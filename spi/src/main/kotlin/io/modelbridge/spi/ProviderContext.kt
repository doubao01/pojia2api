package io.modelbridge.spi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 一次已认证的调用上下文。
 *
 * 由网关在每次请求时构造，生命周期仅限于该次请求。
 * 实现类不得把 [credential] 缓存到自身字段中。
 */
interface ProviderContext {
    /** 该供应商的凭据，形态由 [Provider.authKind] 决定。 */
    val credential: Credential

    /** 发起 HTTP 请求的客户端，实现类应统一使用它以便网关统计与超时控制。 */
    val http: HttpClient

    /** 上游基础地址，允许用户自建中转时覆盖。 */
    val baseUrl: String

    /** 附加 API Key；3.10.0 自定义上游支持配置多个密钥，首个在 [credential] 里。 */
    val extraApiKeys: List<String> get() = emptyList()

    /**
     * 账号在供应商侧的用户 ID。
     *
     * 官方客户端把它放在 `X-User-Id`，部分上游还要求它参与设备标识派生。
     * 为空表示该供应商不需要，或上下文来自不携带账号元数据的调用点。
     */
    val accountUid: String get() = ""

    /** 企业/租户 ID。官方客户端区分个人号与企业号的请求形态，例如 `X-Private-Data`。 */
    val tenantId: String get() = ""

    /** 账号所属区域，例如 `cn`、`global`。为空时由供应商按 [baseUrl] 自行判断。 */
    val region: String get() = ""

    /** 账号绑定的域名，例如 `www.codebuddy.cn`。 */
    val accountDomain: String get() = ""
}

/** 供应商凭据。保存格式使用稳定类型名，避免类重构破坏存档。 */
@Serializable
sealed interface Credential {
    @Serializable
    @SerialName("none")
    data object None : Credential

    @Serializable
    @SerialName("api_key")
    data class ApiKey(val key: String) : Credential

    @Serializable
    @SerialName("cookies")
    data class Cookies(val raw: String) : Credential

    @Serializable
    @SerialName("oauth")
    data class OAuth(
        val accessToken: String,
        val refreshToken: String? = null,
        val expiresAtMillis: Long = 0,
        val tokenType: String = "Bearer",
    ) : Credential

    @Serializable
    @SerialName("session")
    data class Session(val phone: String, val token: String) : Credential
}

/**
 * 抽象 HTTP 客户端。
 *
 * 网关不直接依赖任何具体 HTTP 库，这样内核可以在 JVM 测试里注入假实现。
 */
interface HttpClient {
    suspend fun execute(request: HttpRequest): HttpResponse
}

/** 一次 HTTP 请求。 */
data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val connectTimeoutMillis: Int = 10_000,
    val readTimeoutMillis: Int = 60_000,
    /** 是否要求流式读取响应体。 */
    val streaming: Boolean = false,
) {
    // ByteArray 的 data class 语义需要手写，避免数组引用比较
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HttpRequest) return false
        return method == other.method &&
            url == other.url &&
            headers == other.headers &&
            body.contentEquals(other.body) &&
            connectTimeoutMillis == other.connectTimeoutMillis &&
            readTimeoutMillis == other.readTimeoutMillis &&
            streaming == other.streaming
    }

    override fun hashCode(): Int {
        var result = method.hashCode()
        result = 31 * result + url.hashCode()
        result = 31 * result + headers.hashCode()
        result = 31 * result + (body?.contentHashCode() ?: 0)
        result = 31 * result + connectTimeoutMillis
        result = 31 * result + readTimeoutMillis
        result = 31 * result + streaming.hashCode()
        return result
    }
}

/** 一次 HTTP 响应。 */
interface HttpResponse {
    val statusCode: Int
    val headers: Map<String, String>

    /** 完整读取响应体。仅用于非流式响应。 */
    suspend fun bodyBytes(): ByteArray

    /** 按行读取响应体，用于 SSE。 */
    fun bodyLines(): Sequence<String>
}
