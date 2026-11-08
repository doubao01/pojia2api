package io.modelbridge.providers.auth

import io.modelbridge.spi.Credential
import io.modelbridge.spi.HttpClient
import io.modelbridge.spi.HttpRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** UI opens [DeviceAuthSession.loginUrl]; polling never substitutes browser cookies for tokens. */
interface DeviceAuthClient {
    suspend fun begin(region: String = "cn"): DeviceAuthSession
    suspend fun poll(session: DeviceAuthSession): DeviceAuthPollResult
    fun cancel(session: DeviceAuthSession)
    suspend fun refresh(
        credentials: DeviceAuthCredentials,
        thresholdSeconds: Long = 120,
    ): DeviceAuthRefreshResult
}

enum class DeviceAuthSessionStatus { Waiting, Succeeded, Cancelled, Expired, Failed }

/** State/URL may be login capabilities, so neither is included in diagnostic output. */
class DeviceAuthSession internal constructor(
    val providerId: String,
    val region: String,
    val state: String,
    val loginUrl: String,
    val createdAtMillis: Long,
    val expiresAtMillis: Long,
    internal val owner: Any,
) {
    val pollIntervalMillis: Long = 3_000
    internal val control = DeviceSessionControl(expiresAtMillis)
    val status: DeviceAuthSessionStatus get() = control.status

    override fun toString(): String =
        "DeviceAuthSession(providerId=$providerId, region=$region, status=$status, state=<redacted>, loginUrl=<redacted>)"
}

/** Expiry is normalized to milliseconds for the existing [Credential.OAuth] contract. */
data class DeviceAuthCredentials(
    val providerId: String,
    val credential: Credential.OAuth,
    val region: String,
    val uid: String = "",
    val tenantId: String = "",
    val domain: String = "",
    val nickname: String = "",
    val tokenKind: String = "",
) {
    val expiresAtMillis: Long get() = credential.expiresAtMillis

    override fun toString(): String =
        "DeviceAuthCredentials(providerId=$providerId, region=$region, expiresAtMillis=$expiresAtMillis, credential=<redacted>, identity=<redacted>)"
}

enum class DeviceAuthError {
    MissingClientId,
    InvalidSession,
    SessionConsumed,
    HttpError,
    NetworkError,
    InvalidResponse,
    MissingState,
    MissingAuthUrl,
    InvalidAuthUrl,
    MissingAccessToken,
    BusinessError,
    ProviderMismatch,
}

/** No upstream message, request body, URL, cookie or token is retained in errors. */
data class DeviceAuthFailure(
    val error: DeviceAuthError,
    val httpStatus: Int? = null,
    val businessCode: Long? = null,
) {
    val message: String get() = buildString {
        append("设备授权失败：").append(error.name)
        httpStatus?.let { append(" (HTTP ").append(it).append(')') }
        businessCode?.let { append(" (code ").append(it).append(')') }
    }
}

class DeviceAuthException(val failure: DeviceAuthFailure) : IllegalStateException(failure.message)

sealed interface DeviceAuthPollResult {
    data object Pending : DeviceAuthPollResult
    data object Cancelled : DeviceAuthPollResult
    data object Expired : DeviceAuthPollResult
    data class Success(val credentials: DeviceAuthCredentials) : DeviceAuthPollResult
    data class Failed(val failure: DeviceAuthFailure) : DeviceAuthPollResult
}

enum class DeviceAuthRefreshSkip { StillValid, NoRefreshToken, Backoff }

sealed interface DeviceAuthRefreshResult {
    data class Updated(
        val credentials: DeviceAuthCredentials,
        val fromCache: Boolean = false,
    ) : DeviceAuthRefreshResult

    data class Unchanged(
        val reason: DeviceAuthRefreshSkip,
        val retryAfterMillis: Long = 0,
    ) : DeviceAuthRefreshResult

    data class Failed(
        val failure: DeviceAuthFailure,
        val retryAfterMillis: Long = 0,
    ) : DeviceAuthRefreshResult
}

internal class DeviceSessionControl(private val expiresAtMillis: Long) {
    private val lock = Any()
    private var state = DeviceAuthSessionStatus.Waiting
    private var operation: Job? = null
    val status: DeviceAuthSessionStatus get() = synchronized(lock) { state }

    /** Register the child poll job atomically, so cancel cannot race past a successful response. */
    fun enter(job: Job, nowMillis: Long): DeviceAuthPollResult? = synchronized(lock) {
        terminal(nowMillis)?.let { return@synchronized it }
        if (operation != null) return@synchronized DeviceAuthPollResult.Pending
        operation = job
        null
    }

    fun check(nowMillis: Long): DeviceAuthPollResult? = synchronized(lock) { terminal(nowMillis) }

    fun succeed(nowMillis: Long): Boolean = synchronized(lock) {
        if (terminal(nowMillis) != null) return@synchronized false
        state = DeviceAuthSessionStatus.Succeeded
        true
    }

    fun fail(nowMillis: Long): DeviceAuthPollResult? = synchronized(lock) {
        terminal(nowMillis)?.let { return@synchronized it }
        state = DeviceAuthSessionStatus.Failed
        null
    }

    fun leave(job: Job) = synchronized(lock) {
        if (operation === job) operation = null
    }

    fun cancel() {
        val job = synchronized(lock) {
            if (state != DeviceAuthSessionStatus.Waiting) return
            state = DeviceAuthSessionStatus.Cancelled
            operation
        }
        job?.cancel(CancellationException("Device authorization cancelled"))
    }

    private fun terminal(nowMillis: Long): DeviceAuthPollResult? {
        if (state == DeviceAuthSessionStatus.Waiting && nowMillis > expiresAtMillis) {
            state = DeviceAuthSessionStatus.Expired
        }
        return when (state) {
            DeviceAuthSessionStatus.Waiting -> null
            DeviceAuthSessionStatus.Cancelled -> DeviceAuthPollResult.Cancelled
            DeviceAuthSessionStatus.Expired -> DeviceAuthPollResult.Expired
            DeviceAuthSessionStatus.Succeeded, DeviceAuthSessionStatus.Failed ->
                DeviceAuthPollResult.Failed(DeviceAuthFailure(DeviceAuthError.SessionConsumed))
        }
    }
}

internal suspend fun controlledDevicePoll(
    session: DeviceAuthSession,
    owner: Any,
    clockMillis: () -> Long,
    request: suspend () -> DeviceAuthPollResult,
): DeviceAuthPollResult {
    if (session.owner !== owner) {
        return DeviceAuthPollResult.Failed(DeviceAuthFailure(DeviceAuthError.InvalidSession))
    }
    return coroutineScope {
        val operation = async(start = CoroutineStart.LAZY) {
            val job = currentCoroutineContext()[Job]!!
            session.control.enter(job, clockMillis())?.let { return@async it }
            try {
                val result = request()
                currentCoroutineContext().ensureActive()
                session.control.check(clockMillis())?.let { return@async it }
                when (result) {
                    is DeviceAuthPollResult.Success -> {
                        if (session.control.succeed(clockMillis())) result
                        else session.control.check(clockMillis())
                            ?: DeviceAuthPollResult.Failed(DeviceAuthFailure(DeviceAuthError.InvalidSession))
                    }
                    is DeviceAuthPollResult.Failed -> session.control.fail(clockMillis()) ?: result
                    else -> result
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: DeviceAuthException) {
                session.control.fail(clockMillis()) ?: DeviceAuthPollResult.Failed(failure.failure)
            } catch (_: Exception) {
                val failure = DeviceAuthFailure(DeviceAuthError.NetworkError)
                session.control.fail(clockMillis()) ?: DeviceAuthPollResult.Failed(failure)
            } finally {
                session.control.leave(job)
            }
        }
        try {
            operation.await()
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            if (session.status == DeviceAuthSessionStatus.Cancelled) DeviceAuthPollResult.Cancelled
            else throw cancelled
        }
    }
}

internal data class DeviceJsonResponse(val status: Int, val json: JsonObject?)

internal suspend fun deviceRequest(http: HttpClient, request: HttpRequest): DeviceJsonResponse {
    try {
        currentCoroutineContext().ensureActive()
        val response = http.execute(request)
        currentCoroutineContext().ensureActive()
        if (response.statusCode !in 200..299) return DeviceJsonResponse(response.statusCode, null)
        val bytes = response.bodyBytes()
        currentCoroutineContext().ensureActive()
        val parsed = if (bytes.size <= 1_048_576) {
            runCatching { Json.parseToJsonElement(bytes.toString(StandardCharsets.UTF_8)) as? JsonObject }
                .getOrNull()
        } else null
        return DeviceJsonResponse(response.statusCode, parsed)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        throw DeviceAuthException(DeviceAuthFailure(DeviceAuthError.NetworkError))
    }
}

internal fun JsonObject.authText(vararg names: String): String =
    names.asSequence().mapNotNull { (this[it] as? JsonPrimitive)?.contentOrNull }
        .firstOrNull { it.isNotBlank() }.orEmpty()

internal fun JsonObject.authLong(vararg names: String): Long? =
    names.asSequence().mapNotNull { (this[it] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() }
        .firstOrNull()

internal fun encodeAuthQuery(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
internal fun normalizeAuthRegion(region: String): String = if (region == "global") "global" else "cn"

internal fun authExpiryMillis(
    data: JsonObject,
    nowMillis: Long,
    absoluteKeys: Array<out String>,
    relativeKeys: Array<out String>,
    fallbackMillis: Long = 0,
    normalizeLargeRelative: Boolean = false,
): Long {
    for (key in absoluteKeys) {
        val raw = (data[key] as? JsonPrimitive)?.contentOrNull ?: continue
        val iso = runCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }.getOrNull()
        if (iso != null && iso > 0) return iso
        val absolute = raw.toLongOrNull()
        if (absolute != null && absolute > 0) {
            return if (absolute > 100_000_000_000L) absolute else authSecondsToMillis(absolute)
        }
    }
    val relative = data.authLong(*relativeKeys)?.takeIf { it > 0 } ?: return fallbackMillis
    val seconds = if (normalizeLargeRelative && relative > 100_000_000_000L) relative / 1_000 else relative
    return authAddMillis(nowMillis, authSecondsToMillis(seconds))
}

internal fun authSecondsToMillis(seconds: Long): Long =
    if (seconds > Long.MAX_VALUE / 1_000) Long.MAX_VALUE else seconds * 1_000

internal fun authAddMillis(nowMillis: Long, amount: Long): Long =
    if (amount > 0 && nowMillis > Long.MAX_VALUE - amount) Long.MAX_VALUE else nowMillis + amount

/** JWT claims are identity hints only: parsing them does not verify authentication or grant privileges. */
internal fun authJwtClaims(token: String): JsonObject? = runCatching {
    val payload = token.split('.').getOrNull(1) ?: return@runCatching null
    val bytes = Base64.getUrlDecoder().decode(payload)
    if (bytes.size > 65_536) return@runCatching null
    Json.parseToJsonElement(bytes.toString(StandardCharsets.UTF_8)) as? JsonObject
}.getOrNull()

internal fun authTokenIdentity(token: String, algorithm: String = "SHA-256"): String =
    MessageDigest.getInstance(algorithm).digest(token.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

internal fun validAuthUrl(url: String): Boolean = runCatching {
    val uri = URI(url)
    uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() && uri.userInfo == null
}.getOrDefault(false)

/** Serializes refreshes per account and reuses results only for the same identity and login. */
internal class DeviceRefreshCoordinator(private val backoffMillis: Long = 0) {
    private class Slot {
        val mutex = Mutex()
        @Volatile var generation = 0L
        var result: DeviceAuthRefreshResult? = null
        var cached: DeviceAuthCredentials? = null
        var failedAt: Long? = null
    }
    private val slots = ConcurrentHashMap<String, Slot>()

    suspend fun refresh(
        credentials: DeviceAuthCredentials,
        thresholdSeconds: Long,
        clockMillis: () -> Long,
        exchange: suspend () -> DeviceAuthRefreshResult,
    ): DeviceAuthRefreshResult {
        require(thresholdSeconds >= 0) { "Refresh threshold must be non-negative" }
        if (credentials.credential.refreshToken.isNullOrBlank()) {
            return DeviceAuthRefreshResult.Unchanged(DeviceAuthRefreshSkip.NoRefreshToken)
        }
        val key = listOf(
            credentials.providerId, credentials.region, credentials.uid, credentials.tenantId,
            // Separate distinct logins even when they belong to the same user.
            authTokenIdentity(credentials.credential.refreshToken.orEmpty()),
        ).joinToString("|")
        val slot = slots.computeIfAbsent(key) { Slot() }
        val observedGeneration = slot.generation
        return slot.mutex.withLock {
            val now = clockMillis()
            currentCoroutineContext().ensureActive()
            if (slot.generation != observedGeneration) {
                slot.result?.let { return@withLock cachedResult(it) }
            }
            if (validPastThreshold(credentials, now, thresholdSeconds)) {
                return@withLock DeviceAuthRefreshResult.Unchanged(DeviceAuthRefreshSkip.StillValid)
            }
            slot.cached?.takeIf { validPastThreshold(it, now, thresholdSeconds) }?.let {
                return@withLock DeviceAuthRefreshResult.Updated(it, fromCache = true)
            }
            slot.failedAt?.let { last ->
                val age = (now - last).coerceAtLeast(0)
                if (backoffMillis > 0 && age < backoffMillis) {
                    return@withLock DeviceAuthRefreshResult.Unchanged(
                        DeviceAuthRefreshSkip.Backoff, backoffMillis - age,
                    )
                }
            }
            val result = exchange()
            currentCoroutineContext().ensureActive()
            when (result) {
                is DeviceAuthRefreshResult.Updated -> {
                    slot.cached = result.credentials
                    slot.failedAt = null
                }
                is DeviceAuthRefreshResult.Failed -> slot.failedAt = clockMillis()
                is DeviceAuthRefreshResult.Unchanged -> Unit
            }
            slot.result = result
            slot.generation++
            result
        }
    }

    private fun validPastThreshold(value: DeviceAuthCredentials, now: Long, seconds: Long): Boolean =
        value.expiresAtMillis > authAddMillis(now, authSecondsToMillis(seconds))

    private fun cachedResult(value: DeviceAuthRefreshResult): DeviceAuthRefreshResult = when (value) {
        is DeviceAuthRefreshResult.Updated -> value.copy(fromCache = true)
        else -> value
    }
}
