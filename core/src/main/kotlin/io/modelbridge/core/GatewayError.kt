package io.modelbridge.core

/**
 * 网关对外的错误分类。
 *
 * 与 [io.modelbridge.spi.ProviderException] 的区别：那个描述「上游怎么错了」，
 * 这个描述「客户端应该收到什么」，负责映射成 OpenAI 风格的错误响应。
 */
sealed class GatewayError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** 请求格式错误。 */
    class BadRequest(message: String, cause: Throwable? = null) : GatewayError(message, cause)

    /** 未提供或提供了无效的认证信息。 */
    class Unauthorized(message: String = "缺少有效的 API Key") : GatewayError(message)

    /** 请求的资源不存在，例如模型未配置。 */
    class NotFound(message: String) : GatewayError(message)

    /** 请求体过大。 */
    class TooLarge(message: String) : GatewayError(message)

    /** 上游全部失败或熔断中。 */
    class UpstreamUnavailable(
        message: String,
        val retryAfterSeconds: Int? = null,
        cause: Throwable? = null,
    ) : GatewayError(message, cause)

    /** 服务端内部错误。 */
    class Internal(message: String, cause: Throwable? = null) : GatewayError(message, cause)

    /** 映射成 HTTP 状态码。 */
    val statusCode: Int
        get() = when (this) {
            is BadRequest -> 400
            is Unauthorized -> 401
            is NotFound -> 404
            is TooLarge -> 413
            is UpstreamUnavailable -> 503
            is Internal -> 500
        }

    /** OpenAI 风格的错误类型标识。 */
    val errorType: String
        get() = when (this) {
            is BadRequest -> "invalid_request_error"
            is Unauthorized -> "authentication_error"
            is NotFound -> "not_found_error"
            is TooLarge -> "invalid_request_error"
            is UpstreamUnavailable -> "upstream_error"
            is Internal -> "server_error"
        }
}
