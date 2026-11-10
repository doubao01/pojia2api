package io.modelbridge.core

import io.modelbridge.core.http.ServerRequest
import io.modelbridge.core.http.HttpResult
import io.modelbridge.core.openai.ChatCompletionChunk
import io.modelbridge.core.openai.ChatCompletionRequest
import io.modelbridge.core.openai.ChatCompletionResponse
import io.modelbridge.core.openai.Mapper
import io.modelbridge.core.openai.ModelListResponse
import io.modelbridge.core.openai.ModelObject
import io.modelbridge.core.openai.WireProtocolAdapter
import io.modelbridge.core.openai.WireProtocol
import io.modelbridge.core.openai.WireUsage
import io.modelbridge.spi.ChatChunk
import io.modelbridge.spi.ChatRequest
import io.modelbridge.spi.ChatResult
import io.modelbridge.spi.ModelInfo
import io.modelbridge.spi.Provider
import io.modelbridge.spi.Usage
import io.modelbridge.spi.ProviderContext
import io.modelbridge.spi.ProviderException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.util.UUID
import io.modelbridge.core.policy.Policy
import io.modelbridge.core.policy.PolicyInjector
import io.modelbridge.core.policy.PolicyMatcher
import io.modelbridge.core.policy.InjectionProtocol
import io.modelbridge.core.policy.InjectionResult

/**
 * 网关配置。
 *
 * @property apiKeys 客户端访问网关所需的密钥集合。为空表示不鉴权（仅本机使用）
 * @property publicModelPrefix 模型 ID 前缀，用于避免与上游重名，空表示不加
 */
data class GatewayConfig(
    val apiKeys: Set<String> = emptySet(),
    val publicModelPrefix: String = "",
    val enableCors: Boolean = true,
    /**
     * 同一次请求在账号之间最多重试几次（不含第一次）。
     *
     * 原版「我的」页里的「重试次数」。0 表示每个账号只试一次，
     * 仍然是逐个账号故障转移，只是不再回头重试已失败的账号。
     */
    val maxRetries: Int = 2,
    /** 单次上游调用超时（秒）。0 或负数表示不设超时。 */
    val requestTimeoutSeconds: Int = 120,
    /**
     * 转发时不带客户端识别头。
     *
     * 原版「身份与指纹」里的开关：上游只该看到网关自己，
     * 而不是终端客户端的 UA / 来源信息。
     */
    val stripClientFingerprint: Boolean = false,
) {
    /** 是否启用鉴权。 */
    val authEnabled: Boolean get() = apiKeys.isNotEmpty()

    /** 实际生效的重试次数，负数按 0 处理。 */
    val effectiveMaxRetries: Int get() = maxRetries.coerceAtLeast(0)

    /** 超时毫秒；null 表示不限。 */
    val requestTimeoutMillis: Long?
        get() = requestTimeoutSeconds.takeIf { it > 0 }?.toLong()?.times(1000)
}

/**
 * 网关核心：把 HTTP 请求翻译成对上游的调用。
 *
 * 只做编排，不含协议细节——协议差异全部在 [io.modelbridge.spi.Provider] 实现里。
 */
class Gateway(
    private val router: Router,
    private val catalog: ModelCatalog,
    private val registry: ProviderRegistry,
    private val accounts: AccountStore,
    private val health: HealthTracker,
    private val contextFactory: (io.modelbridge.spi.Provider, Account) -> ProviderContext,
    private val config: GatewayConfig = GatewayConfig(),
    private val clock: () -> Long = System::currentTimeMillis,
    /** 目录超过该时长未刷新则视为过期，按需自动刷新。 */
    private val catalogStaleMillis: Long = 60_000,
    /**
     * 每次拿到上游用量时回调一次，用于界面统计。
     *
     * 放在这里而不是从 HttpResult 里取：响应体要原样透传给客户端，
     * 不该为了统计去解析它。
     */
    private val onUsage: ((Usage) -> Unit)? = null,
    /** 策略仓储；为 null 表示不启用策略注入。 */
    private val policyStore: io.modelbridge.core.policy.PolicyStore? = null,
) {
    /** 最近一次请求命中的策略名，供日志展示。 */
    @Volatile
    var policyName: String? = null
        private set

    /** 最近一次策略注入失败的原因。 */
    @Volatile
    var lastPolicyError: String? = null
        private set

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        // OpenAI 协议要求带上 "object" 字段，它在数据类里是默认值，需要输出默认值
        encodeDefaults = true
    }

    /** 上次目录刷新时间，0 表示从未刷新。 */
    @Volatile
    private var lastRefreshAt: Long = 0L

    /** 处理一个 HTTP 请求，返回响应。 */
    suspend fun handle(request: ServerRequest): HttpResult {
        // 预检请求直接放行
        if (request.method == "OPTIONS") {
            return HttpResult.Buffered(status = 204, headers = corsHeaders())
        }

        return try {
            authenticate(request)
            when {
                request.path == "/health" -> health()
                request.path == "/v1/models" || request.path == "/models" -> models()
                request.path == "/v1/chat/completions" ||
                    request.path == "/chat/completions" ||
                    request.path == "/v2/chat/completions" -> chat(request, WireProtocol.Chat)
                request.path == "/v1/responses" || request.path == "/responses" ->
                    chat(request, WireProtocol.Responses)
                request.path == "/v1/messages" || request.path == "/messages" ->
                    chat(request, WireProtocol.Messages)
                else -> error(
                    GatewayError.NotFound("未知路径：${request.path}"),
                )
            }
        } catch (e: GatewayError) {
            error(e)
        } catch (e: Exception) {
            error(GatewayError.Internal(e.message ?: "内部错误", e))
        }
    }

    /** 定时刷新模型目录。调用方负责按需触发。 */
    suspend fun refreshCatalog(): ModelCatalogResult {
        val result = catalog.build(contextFactory)
        router.updateCatalog(result.models)
        lastRefreshAt = clock()
        return result
    }

    /** 目录为空或过期时按需刷新。 */
    private suspend fun refreshIfStale() {
        val stale = clock() - lastRefreshAt > catalogStaleMillis
        if (router.knownModels().isEmpty() || stale) {
            runCatching { refreshCatalog() }
        }
    }

    private fun authenticate(request: ServerRequest) {
        if (!config.authEnabled) return

        val header = request.header("authorization")
        val token = when {
            header?.startsWith("Bearer ", ignoreCase = true) == true ->
                header.substring(7).trim()
            else -> request.header("x-api-key")?.trim()
        }.orEmpty()

        if (token.isEmpty() || token !in config.apiKeys) {
            throw GatewayError.Unauthorized()
        }
    }

    private suspend fun health(): HttpResult {
        val models = router.knownModels()
        val body = """{"status":"ok","service":"modelbridge","providers":${registry.size},"models":${models.size}}"""
        return HttpResult.Buffered(
            status = 200,
            headers = corsHeaders(),
            body = body.toByteArray(StandardCharsets.UTF_8),
        )
    }

    private suspend fun models(): HttpResult {
        refreshIfStale()
        val snapshot = router.knownModels()
        val now = clock() / 1000

        val response = ModelListResponse(
            data = snapshot.map(::toModelObject).map { obj ->
                if (config.publicModelPrefix.isEmpty()) obj
                else obj.copy(id = config.publicModelPrefix + obj.id)
            },
        )

        return HttpResult.Buffered(
            status = 200,
            headers = corsHeaders(),
            body = json.encodeToString(ModelListResponse.serializer(), response)
                .toByteArray(StandardCharsets.UTF_8),
        )
    }

    private fun toModelObject(model: ModelInfo): ModelObject = ModelObject(
        id = model.id,
        ownedBy = model.providerId,
        created = clock() / 1000,
        contextLength = model.contextLength.takeIf { it > 0 },
    )

    /**
     * 按模型匹配并应用策略。
     *
     * 与原版一致：一次请求最多一条策略，注入失败只记录、不影响转发。
     *
     * @return 注入后的请求；没有策略或注入失败返回 null
     */
    private fun applyPolicy(
        modelId: String,
        rawBody: String,
        fixedPolicyId: String? = null,
        protocol: WireProtocol = WireProtocol.Chat,
    ): Pair<ChatRequest, Policy>? {
        val policies = policyStore?.all() ?: return null
        if (policies.isEmpty()) return null

        val matched = PolicyMatcher.match(
            policies = policies,
            candidateModelIds = listOf(modelId),
            fixedPolicyId = fixedPolicyId,
        ) ?: return null

        if (matched.instruction.isBlank()) return null

        val injectionProtocol = when (protocol) {
            WireProtocol.Chat -> InjectionProtocol.Chat
            WireProtocol.Responses -> InjectionProtocol.Responses
            WireProtocol.Messages -> InjectionProtocol.Messages
        }
        val result = PolicyInjector.inject(
            body = rawBody,
            instruction = matched.instruction,
            protocol = injectionProtocol,
        )

        return when (result) {
            is InjectionResult.Applied -> {
                // 重新解析注入后的请求体，转成内核模型
                val wire = runCatching {
                    WireProtocolAdapter.decodeChatRequest(result.body, protocol)
                }.getOrNull() ?: return null
                wire.copy(model = modelId) to matched
            }
            is InjectionResult.Failed -> {
                // 注入失败不阻断请求，按原文转发
                lastPolicyError = result.reason
                null
            }
            InjectionResult.Skipped -> null
        }
    }

    private suspend fun chat(
        request: ServerRequest,
        protocol: WireProtocol = WireProtocol.Chat,
    ): HttpResult {
        if (request.method != "POST") {
            throw GatewayError.BadRequest("该路径只接受 POST")
        }
        if (request.body.isEmpty()) {
            throw GatewayError.BadRequest("请求体为空")
        }

        val rawBody = request.body.toString(StandardCharsets.UTF_8)
        // 与原版一致：开关关闭时原样透传；开启时只清理对话内容里的客户端痕迹。
        val forwardedBody = if (config.stripClientFingerprint) {
            ClientFingerprintStripper.strip(rawBody)
        } else {
            rawBody
        }

        val wire = try {
            WireProtocolAdapter.decodeChatRequest(forwardedBody, protocol)
        } catch (e: Exception) {
            throw GatewayError.BadRequest("请求体不是合法的 ${protocol.name} JSON：${e.message}")
        }

        val publicModel = wire.model.removePrefix(config.publicModelPrefix)
        val suffixIndex = publicModel.lastIndexOf('@')
        val upstreamHint = if (suffixIndex > 0) publicModel.substring(suffixIndex + 1).trim() else ""
        val modelId = if (suffixIndex > 0) publicModel.substring(0, suffixIndex) else publicModel
        val original = wire.copy(model = modelId)
        val allTargets = router.resolve(modelId)
        val targets = if (upstreamHint.isEmpty()) {
            allTargets
        } else {
            val hinted = allTargets.filter { target ->
                val provider = target.provider
                val account = target.account
                upstreamHint.equals(provider.id, ignoreCase = true) ||
                    upstreamHint.equals(provider.displayName, ignoreCase = true) ||
                    upstreamHint.equals(account.label, ignoreCase = true) ||
                    account.label.contains(upstreamHint, ignoreCase = true) ||
                    provider.displayName.contains(upstreamHint, ignoreCase = true)
            }
            if (hinted.isEmpty()) {
                throw GatewayError.NotFound("找不到上游昵称对应的账号：$upstreamHint")
            }
            hinted
        }
        val failures = mutableListOf<String>()

        policyName = null
        lastPolicyError = null
        // 每次从原始请求体注入，故障转移不叠加前一个账号的策略。
        // 重试按「整轮」计：先把账号按优先级走一遍做故障转移，
        // 整轮都失败后再从头重试一轮。这样单账号时是原地重试，
        // 多账号时不会把请求钉死在第一个坏账号上。
        val rounds = config.effectiveMaxRetries + 1
        var round = 0
        while (round < rounds) {
            round++
            var progressed = false
            for (target in targets) {
                // 已进冷却的账号本轮跳过，不浪费超时预算
                if (!health.isAvailable(target.account.id)) continue
                progressed = true
                try {
                    val applied = applyPolicy(target.model.id, forwardedBody, target.account.fixedPolicyId, protocol)
                    val internal = applied?.first ?: original
                    policyName = applied?.second?.name
                    val context = contextFactory(target.provider, target.account)
                    val result = callWithTimeout { target.provider.chat(context, internal) }
                    health.recordSuccess(target.account.id)
                    return when (result) {
                        is ChatResult.Complete -> completeResponse(
                            result.response.id, modelId, result, protocol,
                        )
                        is ChatResult.Stream -> streamResponse(modelId, result.chunks, protocol)
                    }
                } catch (e: ProviderException) {
                    health.recordFailure(target.account.id)
                    failures += "${target.provider.id}/${target.account.id}: ${e.message}"
                    if (!e.retryable) {
                        // 明确不可重试的错误直接返回，不必再试其它账号
                        throw GatewayError.UpstreamUnavailable(e.message ?: "上游失败", cause = e)
                    }
                } catch (e: Exception) {
                    health.recordFailure(target.account.id)
                    failures += "${target.provider.id}/${target.account.id}: ${e.message}"
                }
            }
            // 所有账号都在冷却，再重试也没有可用上游
            if (!progressed) break
        }

        throw GatewayError.UpstreamUnavailable(
            message = "所有上游均失败：" + failures.joinToString("; "),
            retryAfterSeconds = 30,
        )
    }

    private fun completeResponse(
        id: String,
        modelId: String,
        result: ChatResult.Complete,
        protocol: WireProtocol = WireProtocol.Chat,
    ): HttpResult {
        result.response.usage?.let { usage -> onUsage?.invoke(usage) }
        val body = WireProtocolAdapter.encodeComplete(
            protocol = protocol,
            id = id,
            model = modelId,
            created = clock() / 1000,
            result = result,
        )

        return HttpResult.Buffered(
            status = 200,
            headers = corsHeaders(),
            body = body.toByteArray(StandardCharsets.UTF_8),
        )
    }

    private fun streamResponse(
        modelId: String,
        chunks: Sequence<ChatChunk>,
        protocol: WireProtocol = WireProtocol.Chat,
    ): HttpResult {
        val created = clock() / 1000
        val seq = chunks.map { chunk ->
            toSsePayload(chunk, modelId, created, protocol)
        }
        return HttpResult.Streamed(
            status = 200,
            headers = corsHeaders(),
            chunks = seq,
        )
    }

    /** 把一个内部 chunk 编码成对应协议的 SSE 帧。 */
    private fun toSsePayload(
        chunk: ChatChunk,
        modelId: String,
        created: Long,
        protocol: WireProtocol = WireProtocol.Chat,
    ): ByteArray {
        val id = chunk.id.ifBlank { "chatcmpl-${UUID.randomUUID()}" }
        chunk.usage?.let { usage -> onUsage?.invoke(usage) }
        return WireProtocolAdapter.encodeStreamChunk(
            protocol = protocol,
            id = id,
            model = modelId,
            created = created,
            index = chunk.index,
            text = chunk.deltaText,
            finishReason = chunk.finishReason,
            usage = chunk.usage,
        )
    }

    private fun roleName(role: io.modelbridge.spi.ChatMessage.Role): String = when (role) {
        io.modelbridge.spi.ChatMessage.Role.System -> "system"
        io.modelbridge.spi.ChatMessage.Role.User -> "user"
        io.modelbridge.spi.ChatMessage.Role.Assistant -> "assistant"
        io.modelbridge.spi.ChatMessage.Role.Tool -> "tool"
    }

    /**
     * 按设置里的「请求超时」包住一次上游调用。
     *
     * 超时按整个调用的总时长算，包含流式响应建立连接的时间；
     * 设置里填 0 或负数表示不限，直接透传。
     */
    private suspend fun <T> callWithTimeout(block: suspend () -> T): T {
        val limit = config.requestTimeoutMillis ?: return block()
        return withTimeout(limit) { block() }
    }

    private fun error(e: GatewayError): HttpResult {
        val body = Mapper.errorBody(
            message = e.message ?: "错误",
            type = e.errorType,
        )
        val headers = corsHeaders().toMutableMap()
        if (e is GatewayError.UpstreamUnavailable && e.retryAfterSeconds != null) {
            headers["Retry-After"] = e.retryAfterSeconds.toString()
        }

        return HttpResult.Buffered(
            status = e.statusCode,
            headers = headers,
            body = json.encodeToString(
                io.modelbridge.core.openai.ErrorResponse.serializer(),
                body,
            ).toByteArray(StandardCharsets.UTF_8),
        )
    }

    private fun corsHeaders(): Map<String, String> = if (!config.enableCors) {
        emptyMap()
    } else {
        mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Access-Control-Allow-Headers" to "Authorization, Content-Type, X-Api-Key",
            "Access-Control-Allow-Methods" to "GET, POST, OPTIONS",
        )
    }
}
