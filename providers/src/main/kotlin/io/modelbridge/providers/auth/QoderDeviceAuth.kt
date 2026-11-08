package io.modelbridge.providers.auth

import io.modelbridge.spi.Credential
import io.modelbridge.spi.HttpClient
import io.modelbridge.spi.HttpRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Public client identifiers are supplied by the application, never copied from embedded secret material. */
data class QoderClientIds(val cn: String, val global: String)

/**
 * Qoder's PKCE device-selection flow is not RFC 8628: begin creates a browser URL locally,
 * and poll sends nonce + verifier instead of a server-issued device_code.
 * Contract evidence: original p017t/s.java:623-664 and p017t/e.java:454-524,546-578.
 */
class QoderDeviceAuth(
    private val http: HttpClient,
    private val clientIds: QoderClientIds,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val monotonicNanos: () -> Long = System::nanoTime,
    private val randomBytes: (Int) -> ByteArray = { count -> ByteArray(count).also(SecureRandom()::nextBytes) },
    private val uuid: () -> String = { UUID.randomUUID().toString() },
    private val completeUserInfo: Boolean = true,
) : DeviceAuthClient {
    private data class Region(val name: String, val openApi: String, val origin: String, val userAgent: String)
    private class Pending(val verifier: String, val nonce: String, val region: Region)
    private val owner = Any()
    private val sequence = AtomicLong()
    private val pending = ConcurrentHashMap<DeviceAuthSession, Pending>()
    private val refreshes = DeviceRefreshCoordinator()

    override suspend fun begin(region: String): DeviceAuthSession {
        currentCoroutineContext().ensureActive()
        val config = regionConfig(region)
        val clientId = if (config.name == "global") clientIds.global else clientIds.cn
        if (clientId.isBlank()) throw DeviceAuthException(DeviceAuthFailure(DeviceAuthError.MissingClientId))
        val entropy = randomBytes(32)
        require(entropy.size == 32) { "PKCE entropy must contain exactly 32 bytes" }
        val verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy)
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII)),
        )
        val nonce = uuid().let { if (config.name == "global") it.replace("-", "") else it }
        val parameters = linkedMapOf("challenge" to challenge, "challenge_method" to "S256", "nonce" to nonce)
        if (config.name == "cn") parameters["redirect_uri"] = "qoder-work-cn://"
        parameters["client_id"] = clientId
        parameters["machine_id"] = uuid()
        val query = parameters.entries.joinToString("&") { (key, value) -> "$key=${encodeAuthQuery(value)}" }
        val now = clockMillis()
        val session = DeviceAuthSession(
            providerId = "qoder",
            region = config.name,
            state = "qd-${monotonicNanos()}-${sequence.incrementAndGet()}",
            loginUrl = "${config.origin}/device/selectAccounts?$query",
            createdAtMillis = now,
            expiresAtMillis = authAddMillis(now, 600_000),
            owner = owner,
        )
        currentCoroutineContext().ensureActive()
        pending[session] = Pending(verifier, nonce, config)
        return session
    }

    override suspend fun poll(session: DeviceAuthSession): DeviceAuthPollResult {
        val result = controlledDevicePoll(session, owner, clockMillis) {
            val ticket = pending[session]
                ?: return@controlledDevicePoll DeviceAuthPollResult.Failed(DeviceAuthFailure(DeviceAuthError.InvalidSession))
            val url = "${ticket.region.openApi}/api/v1/deviceToken/poll" +
                "?nonce=${encodeAuthQuery(ticket.nonce)}&verifier=${encodeAuthQuery(ticket.verifier)}&challenge_method=S256"
            val response = deviceRequest(http, HttpRequest(
                method = "GET", url = url,
                headers = mapOf("Accept" to "application/json", "User-Agent" to ticket.region.userAgent),
                connectTimeoutMillis = 15_000, readTimeoutMillis = 20_000,
            ))
            if (response.status == 202 || response.status == 404) return@controlledDevicePoll DeviceAuthPollResult.Pending
            if (response.status !in 200..299) {
                return@controlledDevicePoll DeviceAuthPollResult.Failed(DeviceAuthFailure(DeviceAuthError.HttpError, response.status))
            }
            val json = response.json ?: return@controlledDevicePoll DeviceAuthPollResult.Pending
            val accessToken = json.authText("token", "device_token")
            if (accessToken.isBlank()) return@controlledDevicePoll DeviceAuthPollResult.Pending
            val refreshToken = json.authText("refresh_token", "refreshToken")
            val base = DeviceAuthCredentials(
                providerId = "qoder",
                credential = Credential.OAuth(
                    accessToken = accessToken,
                    refreshToken = refreshToken.takeIf { it.isNotBlank() },
                    expiresAtMillis = authExpiryMillis(
                        json, clockMillis(), arrayOf("expires_at"), arrayOf("expires_in"),
                        normalizeLargeRelative = true,
                    ),
                ),
                region = ticket.region.name,
                uid = json.authText("uid", "userId", "user_id"),
                nickname = json.authText("nickname", "name"),
                tokenKind = if (refreshToken.startsWith("jrt-") || accessToken.startsWith("jt-")) "jrt" else "drt",
            )
            val completed = if (completeUserInfo) enrichIdentity(base, ticket.region) else base
            val withIdentity = if (completed.uid.isBlank()) {
                completed.copy(uid = authTokenIdentity(accessToken, "MD5"))
            } else completed
            DeviceAuthPollResult.Success(withIdentity)
        }
        if (session.owner === owner && result != DeviceAuthPollResult.Pending) pending.remove(session)
        return result
    }

    override fun cancel(session: DeviceAuthSession) {
        if (session.owner !== owner) return
        session.control.cancel()
        pending.remove(session)
    }

    override suspend fun refresh(
        credentials: DeviceAuthCredentials,
        thresholdSeconds: Long,
    ): DeviceAuthRefreshResult {
        if (credentials.providerId != "qoder") {
            return DeviceAuthRefreshResult.Failed(DeviceAuthFailure(DeviceAuthError.ProviderMismatch))
        }
        return refreshes.refresh(credentials, thresholdSeconds, clockMillis) {
            try {
                exchange(credentials)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: DeviceAuthException) {
                DeviceAuthRefreshResult.Failed(failure.failure)
            } catch (_: Exception) {
                DeviceAuthRefreshResult.Failed(DeviceAuthFailure(DeviceAuthError.NetworkError))
            }
        }
    }

    private suspend fun exchange(old: DeviceAuthCredentials): DeviceAuthRefreshResult {
        val config = regionConfig(old.region)
        val path = if (old.tokenKind == "jrt") "/api/v1/jobToken/exchange" else "/api/v1/deviceToken/refresh"
        val refreshToken = old.credential.refreshToken.orEmpty()
        val body = JsonObject(mapOf(
            "refresh_token" to JsonPrimitive(refreshToken), "refreshToken" to JsonPrimitive(refreshToken),
        )).toString().toByteArray(StandardCharsets.UTF_8)
        val response = deviceRequest(http, HttpRequest(
            method = "POST", url = config.openApi + path,
            headers = authHeaders(config, old.credential.accessToken), body = body,
            connectTimeoutMillis = 15_000, readTimeoutMillis = 20_000,
        ))
        if (response.status !in 200..299) {
            return DeviceAuthRefreshResult.Failed(DeviceAuthFailure(DeviceAuthError.HttpError, response.status))
        }
        val json = response.json
            ?: return DeviceAuthRefreshResult.Failed(DeviceAuthFailure(DeviceAuthError.InvalidResponse))
        val accessToken = json.authText("token", "access_token")
        if (accessToken.isBlank()) {
            return DeviceAuthRefreshResult.Failed(DeviceAuthFailure(DeviceAuthError.MissingAccessToken))
        }
        val replacementRefresh = json.authText("refresh_token", "refreshToken").ifBlank { refreshToken }
        val expiry = authExpiryMillis(json, clockMillis(), arrayOf("expires_at"), arrayOf("expires_in"), normalizeLargeRelative = true)
        return DeviceAuthRefreshResult.Updated(old.copy(credential = old.credential.copy(
            accessToken = accessToken, refreshToken = replacementRefresh, expiresAtMillis = expiry,
        )))
    }

    private suspend fun enrichIdentity(value: DeviceAuthCredentials, region: Region): DeviceAuthCredentials {
        return try {
            val response = deviceRequest(http, HttpRequest(
                method = "GET", url = "${region.openApi}/api/v1/userinfo",
                headers = authHeaders(region, value.credential.accessToken),
                connectTimeoutMillis = 15_000, readTimeoutMillis = 20_000,
            ))
            if (response.status !in 200..299) return value
            val json = response.json ?: return value
            val user = json["data"] as? JsonObject ?: json
            value.copy(
                uid = user.authText("id", "uid", "userId").ifBlank { value.uid },
                nickname = user.authText("name", "nickname", "username").ifBlank { value.nickname },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Optional profile information cannot undo successful token exchange.
            value
        }
    }

    private fun authHeaders(region: Region, accessToken: String): Map<String, String> = mapOf(
        "Authorization" to "Bearer $accessToken", "Accept" to "application/json, text/plain, */*",
        "User-Agent" to region.userAgent, "Content-Type" to "application/json",
        "Origin" to region.origin, "Referer" to "${region.origin}/",
    )

    private fun regionConfig(region: String): Region = if (normalizeAuthRegion(region) == "global") {
        Region("global", "https://openapi.qoder.sh", "https://qoder.com", "Qoder/1.1.64")
    } else {
        Region("cn", "https://openapi.qoder.com.cn", "https://qoder.com.cn", "QoderWork/1.1.64")
    }
}
