package io.modelbridge.providers.auth

import io.modelbridge.spi.Credential
import io.modelbridge.spi.HttpClient
import io.modelbridge.spi.HttpRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * WorkBuddy/codebuddy uses a server-issued authorization state. Business code 11217 means pending;
 * refresh is GET with X-Refresh-Token, not POST or a browser Cookie exchange.
 * Source contract: n/d.java:1221-1247,1346-1393,1049-1124,814-824.
 */
class WorkBuddyDeviceAuth(
    private val http: HttpClient,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val sessionTimeoutMillis: Long = 300_000,
) : DeviceAuthClient {
    private data class Region(val name: String, val authBase: String, val origin: String)
    private val owner = Any()
    private val refreshes = DeviceRefreshCoordinator(backoffMillis = 60_000)

    init {
        require(sessionTimeoutMillis > 0) { "Authorization timeout must be positive" }
    }

    override suspend fun begin(region: String): DeviceAuthSession {
        val config = regionConfig(region)
        val response = deviceRequest(http, HttpRequest(
            method = "POST", url = "${config.authBase}/v2/plugin/auth/state?platform=CLI",
            headers = mapOf("Content-Type" to "application/json"),
            body = "{}".toByteArray(StandardCharsets.UTF_8),
            connectTimeoutMillis = 15_000, readTimeoutMillis = 20_000,
        ))
        val data = successfulData(response)
        val state = data.authText("state")
        val loginUrl = data.authText("authUrl")
        if (state.isBlank()) throw DeviceAuthException(DeviceAuthFailure(DeviceAuthError.MissingState))
        if (loginUrl.isBlank()) throw DeviceAuthException(DeviceAuthFailure(DeviceAuthError.MissingAuthUrl))
        if (!validAuthUrl(loginUrl)) throw DeviceAuthException(DeviceAuthFailure(DeviceAuthError.InvalidAuthUrl))
        currentCoroutineContext().ensureActive()
        val now = clockMillis()
        return DeviceAuthSession(
            providerId = "codebuddy", region = config.name, state = state, loginUrl = loginUrl,
            createdAtMillis = now, expiresAtMillis = authAddMillis(now, sessionTimeoutMillis), owner = owner,
        )
    }

    override suspend fun poll(session: DeviceAuthSession): DeviceAuthPollResult =
        controlledDevicePoll(session, owner, clockMillis) {
            val config = regionConfig(session.region)
            val response = deviceRequest(http, HttpRequest(
                method = "GET",
                url = "${config.authBase}/v2/plugin/auth/token?state=${encodeAuthQuery(session.state)}",
                headers = emptyMap(), connectTimeoutMillis = 15_000, readTimeoutMillis = 20_000,
            ))
            if (response.status !in 200..299) {
                return@controlledDevicePoll DeviceAuthPollResult.Failed(DeviceAuthFailure(DeviceAuthError.HttpError, response.status))
            }
            val json = response.json
                ?: return@controlledDevicePoll DeviceAuthPollResult.Failed(DeviceAuthFailure(DeviceAuthError.InvalidResponse))
            val code = json.authLong("code")
            if (code == 11_217L) return@controlledDevicePoll DeviceAuthPollResult.Pending
            if (code != 0L) {
                return@controlledDevicePoll DeviceAuthPollResult.Failed(DeviceAuthFailure(DeviceAuthError.BusinessError, businessCode = code))
            }
            val data = json["data"] as? JsonObject
                ?: return@controlledDevicePoll DeviceAuthPollResult.Failed(DeviceAuthFailure(DeviceAuthError.InvalidResponse))
            val accessToken = data.authText("accessToken", "access_token")
            if (accessToken.isBlank()) {
                return@controlledDevicePoll DeviceAuthPollResult.Failed(DeviceAuthFailure(DeviceAuthError.MissingAccessToken))
            }
            val claims = authJwtClaims(accessToken)
            val domain = data.authText("domain")
            DeviceAuthPollResult.Success(DeviceAuthCredentials(
                providerId = "codebuddy",
                credential = Credential.OAuth(
                    accessToken = accessToken,
                    refreshToken = data.authText("refreshToken", "refresh_token").takeIf { it.isNotBlank() },
                    expiresAtMillis = authExpiryMillis(
                        data, clockMillis(), arrayOf("expiresAt", "expires_at"), arrayOf("expiresIn", "expires_in"),
                    ),
                ),
                region = inferRegion(accessToken, domain, config.name),
                uid = data.authText("uid", "userId", "user_id")
                    .ifBlank { claims?.authText("user_id", "userId", "uid", "sub").orEmpty() },
                tenantId = data.authText("enterpriseId", "enterprise_id", "entId", "tenantId", "tenant_id")
                    .ifBlank { claims?.authText("tenant_id", "tenantId", "enterprise_id", "enterpriseId").orEmpty() },
                domain = domain,
                nickname = data.authText("nickname", "nickName", "name"),
            ))
        }

    override fun cancel(session: DeviceAuthSession) {
        if (session.owner === owner) session.control.cancel()
    }

    override suspend fun refresh(
        credentials: DeviceAuthCredentials,
        thresholdSeconds: Long,
    ): DeviceAuthRefreshResult {
        if (credentials.providerId != "codebuddy") {
            return DeviceAuthRefreshResult.Failed(DeviceAuthFailure(DeviceAuthError.ProviderMismatch))
        }
        return refreshes.refresh(credentials, thresholdSeconds, clockMillis) {
            try {
                exchange(credentials)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: DeviceAuthException) {
                DeviceAuthRefreshResult.Failed(failure.failure, retryAfterMillis = 60_000)
            } catch (_: Exception) {
                DeviceAuthRefreshResult.Failed(DeviceAuthFailure(DeviceAuthError.NetworkError), retryAfterMillis = 60_000)
            }
        }
    }

    private suspend fun exchange(old: DeviceAuthCredentials): DeviceAuthRefreshResult {
        val config = regionConfig(inferRegion(old.credential.accessToken, old.domain, old.region))
        val headers = linkedMapOf(
            "Content-Type" to "application/json", "Accept" to "application/json, text/plain, */*",
            "X-Requested-With" to "XMLHttpRequest", "Origin" to config.origin,
            "Referer" to "${config.origin}/", "User-Agent" to "CLI/2.63.2 CodeBuddy/2.63.2",
            "X-Refresh-Token" to old.credential.refreshToken.orEmpty(), "X-Auth-Refresh-Source" to "workbuddy",
        )
        if (old.tenantId.isNotBlank()) {
            headers["X-Enterprise-Id"] = old.tenantId
            headers["X-Tenant-Id"] = old.tenantId
        }
        // Source I()/K() select GET when body is null; do not add Authorization here.
        val data = successfulData(deviceRequest(http, HttpRequest(
            method = "GET", url = "${config.authBase}/v2/plugin/auth/token/refresh",
            headers = headers, body = null, connectTimeoutMillis = 15_000, readTimeoutMillis = 20_000,
        )))
        val accessToken = data.authText("accessToken", "access_token")
        if (accessToken.isBlank()) {
            return DeviceAuthRefreshResult.Failed(DeviceAuthFailure(DeviceAuthError.MissingAccessToken), retryAfterMillis = 60_000)
        }
        val replacementRefresh = data.authText("refreshToken", "refresh_token")
            .ifBlank { old.credential.refreshToken.orEmpty() }
        val domain = data.authText("domain").ifBlank { old.domain }
        // Refresh has only relative TTL in the original: absent/invalid TTL preserves old expiry.
        val expiry = authExpiryMillis(
            data, clockMillis(), emptyArray(), arrayOf("expiresIn", "expires_in"),
            fallbackMillis = old.expiresAtMillis,
        )
        return DeviceAuthRefreshResult.Updated(old.copy(
            credential = old.credential.copy(
                accessToken = accessToken, refreshToken = replacementRefresh, expiresAtMillis = expiry,
            ),
            region = inferRegion(accessToken, domain, config.name),
            domain = domain,
        ))
    }

    private fun successfulData(response: DeviceJsonResponse): JsonObject {
        if (response.status !in 200..299) throw DeviceAuthException(DeviceAuthFailure(DeviceAuthError.HttpError, response.status))
        val json = response.json ?: throw DeviceAuthException(DeviceAuthFailure(DeviceAuthError.InvalidResponse))
        val code = json.authLong("code")
        if (code != 0L) throw DeviceAuthException(DeviceAuthFailure(DeviceAuthError.BusinessError, businessCode = code))
        return json["data"] as? JsonObject ?: throw DeviceAuthException(DeviceAuthFailure(DeviceAuthError.InvalidResponse))
    }

    private fun inferRegion(accessToken: String, domain: String, fallback: String): String {
        val issuer = authJwtClaims(accessToken)?.authText("iss", "issuer").orEmpty().lowercase(Locale.ROOT)
        val value = domain.trim().lowercase(Locale.ROOT)
        val globalDomain = value == "global" || value == "workbuddy.ai" || value.endsWith(".workbuddy.ai") ||
            value == "codebuddy.ai" || value.endsWith(".codebuddy.ai")
        return when {
            issuer.contains("workbuddy.ai") || issuer.contains("codebuddy.ai") || globalDomain -> "global"
            value.isNotBlank() -> "cn"
            else -> normalizeAuthRegion(fallback)
        }
    }

    private fun regionConfig(region: String): Region = if (normalizeAuthRegion(region) == "global") {
        Region("global", "https://www.workbuddy.ai", "https://www.workbuddy.ai")
    } else {
        Region("cn", "https://copilot.tencent.com", "https://www.codebuddy.cn")
    }
}
