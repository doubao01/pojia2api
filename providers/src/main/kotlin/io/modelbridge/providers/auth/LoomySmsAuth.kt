package io.modelbridge.providers.auth

import io.modelbridge.spi.HttpClient
import io.modelbridge.spi.HttpRequest
import io.modelbridge.spi.HttpResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Signing material is injected by the host; no credential is embedded in this module. */
data class LoomySigningMaterial(
    val appId: String,
    val accessKey: String,
    val secret: String,
)

data class LoomySmsChallenge(
    val phone: String,
    val messageId: String,
    val expiresAtMillis: Long,
)

data class LoomySession(
    val phone: String,
    val session: String,
    val userId: String,
    val expiresAtMillis: Long,
)

sealed interface LoomySmsResult {
    data class Sent(val challenge: LoomySmsChallenge) : LoomySmsResult
    data class LoggedIn(val session: LoomySession) : LoomySmsResult
}

/**
 * Loomy phone login contract from the audited source:
 * sendMsgCode -> msgid, then checkCode -> session (14 day validity).
 * SMS is never sent implicitly by this class.
 */
class LoomySmsAuth(
    private val http: HttpClient,
    private val material: LoomySigningMaterial,
    private val baseUrl: String = "https://account.xfinfr.com",
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val nonce: () -> String = { UUID.randomUUID().toString() },
    private val signer: LoomyRequestSigner = HmacSha1LoomySigner,
) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    suspend fun sendCode(phone: String): LoomySmsChallenge {
        val normalized = normalizePhone(phone)
        val body = envelope(
            param = buildJsonObject {
                put("ccode", "86")
                put("phone", normalized)
                put("expire", 300)
            },
        )
        val root = execute("/login/phone/sendMsgCode", body)
        val data = root.data()
        val messageId = data.text("msgid").ifBlank { data.text("msgId") }
        require(messageId.isNotBlank()) { "Loomy response missing msgid" }
        return LoomySmsChallenge(normalized, messageId, clockMillis() + 300_000)
    }

    suspend fun checkCode(challenge: LoomySmsChallenge, code: String): LoomySession {
        require(challenge.expiresAtMillis >= clockMillis()) { "SMS challenge expired" }
        require(code.trim().isNotEmpty()) { "SMS code is empty" }
        val body = envelope(
            param = buildJsonObject {
                put("ccode", "86")
                put("phone", challenge.phone)
                put("mcode", code.trim())
                put("msgid", challenge.messageId)
                put("expire", 1_209_600)
            },
        )
        val data = execute("/login/phone/checkCode", body).data()
        val session = data.text("session")
        require(session.isNotBlank()) { "Loomy response missing session" }
        return LoomySession(
            phone = challenge.phone,
            session = session,
            userId = data.text("userid").ifBlank { data.text("userId") },
            expiresAtMillis = clockMillis() + 1_209_600_000L,
        )
    }

    private suspend fun execute(path: String, body: JsonObject): JsonObject {
        val bytes = body.toString().toByteArray(StandardCharsets.UTF_8)
        val date = utcDate(clockMillis())
        val requestNonce = nonce()
        val headers = signer.headers(
            material = material,
            method = "POST",
            path = path,
            body = bytes,
            date = date,
            nonce = requestNonce,
        )
        val response = http.execute(
            HttpRequest(
                method = "POST",
                url = baseUrl.trimEnd('/') + path,
                headers = headers + ("Content-Type" to "application/json"),
                body = bytes,
                connectTimeoutMillis = 15_000,
                readTimeoutMillis = 20_000,
            ),
        )
        val raw = response.bodyBytes().decodeToString()
        require(response.statusCode in 200..299) { "Loomy HTTP ${response.statusCode}" }
        return json.parseToJsonElement(raw).jsonObject
    }

    private fun envelope(param: JsonObject): JsonObject = buildJsonObject {
        put("base", buildJsonObject {
            put("appid", material.appId)
            put("modelid", "Web")
            put("version", "1.0.0")
            put("devid", "web")
            put("ua", "Loomy|Desktop|Electron|macOS")
            put("traceid", nonce().replace("-", ""))
        })
        put("param", param)
    }

    private fun normalizePhone(phone: String): String {
        val value = phone.trim().removePrefix("+86").removePrefix("86")
        require(value.matches(Regex("1[3-9]\\d{9}"))) { "手机号格式不正确" }
        return value
    }

    private fun JsonObject.data(): JsonObject = this["data"]?.jsonObject ?: this
    private fun JsonObject.text(key: String): String = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

    companion object {
        private fun utcDate(now: Long): String = SimpleDateFormat(
            "EEE, dd MMM yyyy HH:mm:ss z", Locale.US,
        ).apply { timeZone = TimeZone.getTimeZone("GMT") }.format(Date(now))
    }
}

fun interface LoomyRequestSigner {
    fun headers(
        material: LoomySigningMaterial,
        method: String,
        path: String,
        body: ByteArray,
        date: String,
        nonce: String,
    ): Map<String, String>
}

/** HMAC-SHA1 signer matching the audited account service envelope. */
object HmacSha1LoomySigner : LoomyRequestSigner {
    override fun headers(
        material: LoomySigningMaterial,
        method: String,
        path: String,
        body: ByteArray,
        date: String,
        nonce: String,
    ): Map<String, String> {
        val md5 = Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(body))
        val canonical = listOf(
            method.uppercase(Locale.US),
            path,
            md5,
            "application/json",
            date,
            nonce,
        ).joinToString("\n")
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(material.secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA1"))
        val signature = Base64.getEncoder().encodeToString(mac.doFinal(canonical.toByteArray(StandardCharsets.UTF_8)))
        return mapOf(
            "Authorization" to "account ${material.accessKey}:$signature",
            "Date" to date,
            "Nonce" to nonce,
            "Content-MD5" to md5,
        )
    }
}
