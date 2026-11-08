package io.modelbridge.providers.auth

import io.modelbridge.spi.Credential
import io.modelbridge.spi.HttpClient
import io.modelbridge.spi.HttpRequest
import io.modelbridge.spi.HttpResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.Base64

/** Scripted, in-memory HTTP only. An unscripted call fails without printing request headers/body. */
internal class DeviceHttpFake : HttpClient {
    val requests = mutableListOf<HttpRequest>()
    private val scripts = ArrayDeque<suspend (HttpRequest) -> HttpResponse>()

    fun enqueue(status: Int = 200, body: String): DeviceHttpFake = apply {
        scripts.addLast { DeviceHttpResponse(status, body) }
    }

    fun enqueue(block: suspend (HttpRequest) -> HttpResponse): DeviceHttpFake = apply {
        scripts.addLast(block)
    }

    override suspend fun execute(request: HttpRequest): HttpResponse {
        requests += request
        check(scripts.isNotEmpty()) { "Unexpected device-auth HTTP call" }
        return scripts.removeFirst().invoke(request)
    }
}

internal class DeviceHttpResponse(
    override val statusCode: Int,
    private val payload: String,
) : HttpResponse {
    override val headers: Map<String, String> = emptyMap()
    override suspend fun bodyBytes(): ByteArray = payload.toByteArray(StandardCharsets.UTF_8)
    override fun bodyLines(): Sequence<String> = payload.lineSequence()
}

internal fun authTestQuery(url: String): Map<String, String> = URI(url).rawQuery.orEmpty()
    .split('&').filter { it.isNotBlank() }.associate { pair ->
        val parts = pair.split('=', limit = 2)
        URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
    }

internal fun authTestBody(request: HttpRequest): JsonObject =
    Json.parseToJsonElement(request.body!!.toString(StandardCharsets.UTF_8)) as JsonObject

/** Dummy unsigned JWT: decoding its claims must never be treated as validating a signature. */
internal fun authTestJwt(claims: String): String {
    val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(claims.toByteArray(StandardCharsets.UTF_8))
    return "fixture.$payload.signature"
}

internal fun authTestCredentials(
    providerId: String,
    region: String = "cn",
    accessToken: String = "fixture-access",
    refreshToken: String? = "fixture-refresh",
    expiresAtMillis: Long = 0,
    uid: String = "fixture-user",
    tenantId: String = "",
    domain: String = "",
    tokenKind: String = "drt",
): DeviceAuthCredentials = DeviceAuthCredentials(
    providerId = providerId,
    credential = Credential.OAuth(accessToken, refreshToken, expiresAtMillis),
    region = region,
    uid = uid,
    tenantId = tenantId,
    domain = domain,
    nickname = "fixture-name",
    tokenKind = tokenKind,
)
