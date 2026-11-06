package io.modelbridge.spi

/**
 * 一个供应商（Provider）代表一个上游 AI 服务的接入方式。
 *
 * 实现类必须是无状态的、线程安全的：网关会在多个线程中并发调用同一个实例。
 * 所有会话状态（凭据、Cookie、Token）都通过 [ProviderContext] 传入，不保存在实现类里。
 */
interface Provider {
    /** 全局唯一的供应商标识，小写、短横线分隔，例如 `openai`、`zhipu`。 */
    val id: String

    /** 展示名称，用于 UI。 */
    val displayName: String

    /** 该供应商支持的认证方式。 */
    val authKind: AuthKind

    /** 该供应商是否需要用户先登录/配置凭据。 */
    val requiresCredential: Boolean get() = authKind != AuthKind.None

    /**
     * 拉取该供应商可用的模型列表。
     *
     * @param context 已认证的上下文，包含凭据与 HTTP 客户端
     * @return 模型条目；失败时抛出 [ProviderException]
     */
    suspend fun listModels(context: ProviderContext): List<ModelInfo>

    /**
     * 发起一次对话补全请求。
     *
     * 实现类负责把统一的 [ChatRequest] 翻译成上游协议，并把上游响应翻译回
     * [ChatResponse]（非流式）或 [ChatChunk] 序列（流式）。
     *
     * @param context 已认证的上下文
     * @param request 统一格式的请求
     * @return 上游结果；流式场景返回 [ChatResult.Stream]
     */
    suspend fun chat(context: ProviderContext, request: ChatRequest): ChatResult

    /**
     * 校验凭据是否仍然有效。用于「测试连接」功能。
     *
     * 默认实现是拉一次模型列表，只要不抛异常就视为有效。
     */
    suspend fun validate(context: ProviderContext): ValidationResult =
        runCatching { listModels(context) }
            .fold(
                onSuccess = { ValidationResult.Valid },
                onFailure = { ValidationResult.Invalid(it.message ?: "校验失败") },
            )
}

/** 供应商认证方式。 */
enum class AuthKind {
    /** 无需认证，匿名可用。 */
    None,

    /** 单一 API Key，放在请求头。 */
    ApiKey,

    /** 需要浏览器 Cookie。 */
    Cookie,

    /** OAuth 授权码流程。 */
    OAuth,

    /** 手机号 + 短信验证码。 */
    PhoneCode,
}

/** 一个模型的元信息。 */
data class ModelInfo(
    /** 模型 ID，客户端调用时使用。 */
    val id: String,
    /** 归属该供应商。 */
    val providerId: String,
    /** 人类可读名称，缺省时用 [id]。 */
    val displayName: String = id,
    /** 上下文窗口 token 数，0 表示未知。 */
    val contextLength: Long = 0,
    /** 是否支持工具调用。 */
    val supportsTools: Boolean = false,
    /** 是否支持推理/思维链。 */
    val supportsReasoning: Boolean = false,
    /** 额外能力标记，透传给客户端。 */
    val extras: Map<String, String> = emptyMap(),
)

/** 凭据校验结果。 */
sealed interface ValidationResult {
    data object Valid : ValidationResult
    data class Invalid(val reason: String) : ValidationResult
}

/** 供应商调用失败。 */
class ProviderException(
    message: String,
    /** 建议返回给客户端的 HTTP 状态码。 */
    val statusCode: Int = 502,
    /** 是否属于可重试的瞬时故障（网络抖动、上游 5xx）。 */
    val retryable: Boolean = false,
    cause: Throwable? = null,
) : Exception(message, cause)
