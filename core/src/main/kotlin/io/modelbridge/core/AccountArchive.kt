package io.modelbridge.core

import io.modelbridge.spi.Credential
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/**
 * 兼容原版「导出账号」文件的解析与生成。
 *
 * 原版账号文件顶层是 JSON 数组，每项包含 provider、uid、nickname、secret。
 * 3.10.0 还会遇到 WorkBuddy 导出的单对象 `{account, auth}`；这里也一并兼容，
 * 并保留 ModelBridge 自身的 ConfigBundle / Account 结构。
 */
object AccountArchive {
    private val JSON = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun parseAccounts(raw: String): List<Account> {
        val root = JSON.parseToJsonElement(raw)
        val items: List<JsonElement> = when (root) {
            is JsonArray -> root.toList()
            is JsonObject -> {
                val configAccounts = root["accounts"] as? JsonArray
                configAccounts?.toList() ?: listOf(root)
            }
            else -> throw IllegalArgumentException("账号文件不是 JSON 对象或数组")
        }

        val errors = mutableListOf<String>()
        val accounts = items.mapNotNull { item ->
            val obj = item as? JsonObject
                ?: run {
                    errors += "账号项不是 JSON 对象"
                    return@mapNotNull null
                }
            runCatching { parseAccount(obj) }
                .onFailure { errors += it.message ?: "账号项无法解析" }
                .getOrNull()
        }
        if (accounts.isEmpty()) {
            throw IllegalArgumentException(errors.firstOrNull() ?: "没有导入任何账号")
        }
        return accounts
    }

    /** 3.10.0 整包配置的 upstreams 项转成自定义中转站账号，保留首个可用 API Key。 */
    fun parseLegacyUpstream(element: JsonElement): Account? {
        val obj = element as? JsonObject ?: return null
        val baseUrl = obj.string("baseUrl", "base_url")
        if (baseUrl.isBlank()) return null
        val id = obj.string("id").ifBlank { "upstream-${UUID.randomUUID()}" }
        val name = obj.string("name").ifBlank { id }
        val apiKey = obj.string("apiKey", "api_key").ifBlank {
            (obj["apiKeys"] as? JsonArray)
                ?.firstNotNullOfOrNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
                .orEmpty()
        }
        return Account(
            id = id,
            providerId = "custom",
            label = name,
            enabled = obj.boolean("enabled", default = true),
            credential = if (apiKey.isBlank()) Credential.None else Credential.ApiKey(apiKey),
            baseUrl = baseUrl,
            priority = obj.long("priority").toInt(),
            fixedPolicyId = obj.string("profileId", "profile_id").takeIf { it.isNotBlank() },
            extraApiKeys = (obj["apiKeys"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
                ?.filter { it != apiKey }
                .orEmpty(),
        )
    }

    /** 整包配置中未加密的 profiles 直接转成策略；加密正文保持原样但不注入。 */
    fun parseLegacyProfile(element: JsonElement): io.modelbridge.core.policy.Policy? {
        val obj = element as? JsonObject ?: return null
        val id = obj.string("id")
        val name = obj.string("name").ifBlank { id }
        val instruction = obj.string("instruction")
        if (id.isBlank() || instruction.startsWith("p1:")) return null
        return io.modelbridge.core.policy.Policy(
            id = id,
            name = name,
            models = obj.stringSet("models").toList(),
            instruction = instruction,
            builtin = obj.boolean("builtin", default = false),
            enabled = obj.boolean("enabled", default = true),
        )
    }

    fun export(accounts: List<Account>): String =
        JsonArray(accounts.map(::exportAccount)).toString()

    private fun parseAccount(obj: JsonObject): Account {
        // ModelBridge 自己导出的项带完整 Account 字段，直接还原可保留策略绑定等元数据。
        if (obj["credential"] != null) {
            return JSON.decodeFromJsonElement(Account.serializer(), obj)
        }

        val provider = normalizeProvider(obj.string("provider", "providerId"))
        val secretElement = obj["secret"]
        val secretText = (secretElement as? JsonPrimitive)?.content
        val secretObject = when (secretElement) {
            is JsonObject -> secretElement
            is JsonPrimitive -> parseObjectOrNull(secretElement.content)
            else -> null
        }
        val merged = buildMap<String, JsonElement> {
            for (group in listOf("auth", "account")) {
                val value = obj[group] as? JsonObject ?: continue
                putAll(value)
            }
            secretObject?.let { putAll(it) }
            for ((key, value) in obj) {
                if (key == "auth" || key == "account" || key == "secret") continue
                if (value is JsonObject || value is JsonArray) continue
                put(key, value)
            }
        }.let(::JsonObject)

        val providerId = if (provider.isBlank()) inferProvider(merged) else provider
        val credential = credentialFor(providerId, merged, secretText)
        val uid = merged.string("uid", "userId", "user_id")
        val nickname = merged.string("nickname", "nickName", "name")
            .ifBlank { obj.string("nickname", "name") }
        val domain = merged.string("domain")
        val realm = merged.string("realm", "region", "authRegion")
            .ifBlank { inferRegion(domain) }
        val tenant = merged.string("enterpriseId", "enterprise_id", "entId", "tenantId", "tenant_id")
        val tokenKind = merged.string("tokenKind", "token_type", "tokenType")
            .ifBlank { credential.tokenTypeOrEmpty() }
        val expiresAtMillis = expiryMillis(merged)
            .takeIf { it > 0 }
            ?: merged.long("authExpiresAtMillis").takeIf { it > 0 }
            ?: (credential as? Credential.OAuth)?.expiresAtMillis
            ?: 0L

        return Account(
            id = uid.ifBlank { "$providerId-${UUID.randomUUID().toString().take(8)}" },
            providerId = providerId,
            label = nickname.ifBlank { "$providerId 账号" },
            enabled = obj.boolean("enabled", default = true),
            credential = credential,
            baseUrl = obj.string("baseUrl"),
            priority = obj.long("priority").toInt(),
            disabledModels = obj.stringSet("disabledModels"),
            authRegion = realm,
            authUid = uid,
            authTenantId = tenant,
            authDomain = domain,
            authNickname = nickname,
            authTokenKind = tokenKind,
            authExpiresAtMillis = expiresAtMillis,
            fixedPolicyId = obj.string("fixedPolicyId").takeIf { it.isNotBlank() },
            extraApiKeys = obj.stringSet("extraApiKeys").toList(),
        )
    }

    private fun exportAccount(account: Account): JsonObject {
        val base = JSON.encodeToJsonElement(Account.serializer(), account).jsonObject.toMutableMap()
        base["provider"] = JsonPrimitive(account.providerId)
        base["uid"] = JsonPrimitive(account.authUid.ifBlank { account.id })
        base["nickname"] = JsonPrimitive(account.label)
        base["enabled"] = JsonPrimitive(account.enabled)
        base["secret"] = secretFor(account)
        return JsonObject(base)
    }

    private fun secretFor(account: Account): JsonObject {
        val fields = linkedMapOf<String, JsonElement>()
        when (val credential = account.credential) {
            is Credential.OAuth -> {
                fields["accessToken"] = JsonPrimitive(credential.accessToken)
                credential.refreshToken?.let { fields["refreshToken"] = JsonPrimitive(it) }
                fields["expiresAt"] = JsonPrimitive(credential.expiresAtMillis / 1000L)
                fields["expiresAtMillis"] = JsonPrimitive(credential.expiresAtMillis)
                fields["tokenType"] = JsonPrimitive(credential.tokenType)
            }
            is Credential.ApiKey -> {
                fields["key"] = JsonPrimitive(credential.key)
                fields["apiKey"] = JsonPrimitive(credential.key)
            }
            is Credential.Cookies -> {
                fields["cookie"] = JsonPrimitive(credential.raw)
                fields["raw"] = JsonPrimitive(credential.raw)
            }
            is Credential.Session -> {
                fields["phone"] = JsonPrimitive(credential.phone)
                fields["token"] = JsonPrimitive(credential.token)
                fields["session"] = JsonPrimitive(credential.token)
            }
            Credential.None -> Unit
        }
        fields["domain"] = JsonPrimitive(account.authDomain)
        fields["realm"] = JsonPrimitive(account.authRegion)
        fields["uid"] = JsonPrimitive(account.authUid.ifBlank { account.id })
        fields["enterpriseId"] = JsonPrimitive(account.authTenantId)
        fields["nickname"] = JsonPrimitive(account.authNickname.ifBlank { account.label })
        fields["tokenKind"] = JsonPrimitive(account.authTokenKind)
        if (account.authExpiresAtMillis > 0) {
            fields["expiresAtMillis"] = JsonPrimitive(account.authExpiresAtMillis)
            fields["expiresAt"] = JsonPrimitive(account.authExpiresAtMillis / 1000L)
        }
        return JsonObject(fields)
    }

    private fun credentialFor(
        providerId: String,
        obj: JsonObject,
        secretText: String?,
    ): Credential {
        val access = obj.string("accessToken", "access_token")
        if (providerId == "codebuddy" || providerId == "qoder" || access.isNotBlank()) {
            if (access.isBlank()) throw IllegalArgumentException("凭证缺少 accessToken")
            return Credential.OAuth(
                accessToken = access,
                refreshToken = obj.string("refreshToken", "refresh_token").takeIf { it.isNotBlank() },
                expiresAtMillis = expiryMillis(obj),
                tokenType = obj.string("tokenType", "token_type").ifBlank { "Bearer" },
            )
        }
        if (providerId == "loomy" || obj.string("session", "token").isNotBlank()) {
            val session = obj.string("session", "token")
            if (session.isBlank()) throw IllegalArgumentException("凭证缺少 session")
            return Credential.Session(
                phone = obj.string("phone"),
                token = session,
            )
        }
        val cookie = obj.string("cookie", "raw")
        if (cookie.isNotBlank()) return Credential.Cookies(cookie)
        val apiKey = obj.string("apiKey", "key", "token")
        if (apiKey.isNotBlank()) return Credential.ApiKey(apiKey)
        if (!secretText.isNullOrBlank()) return Credential.Cookies(secretText)
        throw IllegalArgumentException("凭证内容无法解析")
    }

    private fun expiryMillis(obj: JsonObject): Long {
        val direct = obj.long("expiresAtMillis")
        if (direct > 0L) return direct
        val value = obj.long("expiresAt", "expires_at")
        if (value <= 0L) return 0L
        return if (value > 100_000_000_000L) value else value * 1000L
    }

    private fun inferProvider(obj: JsonObject): String {
        val domain = obj.string("domain").lowercase()
        return when {
            obj.string("accessToken", "access_token").isBlank() &&
                obj.string("session", "phone").isNotBlank() -> "loomy"
            domain.contains("qoder") -> "qoder"
            domain.contains("codebuddy") || domain.contains("workbuddy") ||
                obj.string("refreshToken", "refresh_token").isNotBlank() -> "codebuddy"
            else -> "imported"
        }
    }

    private fun inferRegion(domain: String): String {
        val value = domain.lowercase()
        return when {
            value.contains("workbuddy.ai") || value.contains("codebuddy.ai") -> "global"
            value.contains("codebuddy.cn") || value.contains("qoder.com.cn") -> "cn"
            else -> ""
        }
    }

    private fun normalizeProvider(value: String): String = when (value.trim().lowercase()) {
        "", "workbuddy", "codebuddy" -> if (value.isBlank()) "" else "codebuddy"
        "qoder" -> "qoder"
        "loomy" -> "loomy"
        else -> value.trim().lowercase()
    }

    private fun parseObjectOrNull(text: String): JsonObject? = runCatching {
        JSON.parseToJsonElement(text).jsonObject
    }.getOrNull()

    private fun Credential.tokenTypeOrEmpty(): String = when (this) {
        is Credential.OAuth -> tokenType
        else -> ""
    }

    private fun JsonObject.string(vararg keys: String): String = keys.asSequence()
        .mapNotNull { key ->
            val primitive = this[key] as? JsonPrimitive ?: return@mapNotNull null
            primitive.contentOrNull?.takeIf { it.isNotBlank() }
        }
        .firstOrNull()
        .orEmpty()

    private fun JsonObject.long(vararg keys: String): Long = keys.asSequence()
        .mapNotNull { key ->
            val primitive = this[key] as? JsonPrimitive ?: return@mapNotNull null
            primitive.contentOrNull?.toLongOrNull()
        }
        .firstOrNull()
        ?: 0L

    private fun JsonObject.boolean(key: String, default: Boolean): Boolean {
        val primitive = this[key] as? JsonPrimitive ?: return default
        return primitive.contentOrNull?.toBooleanStrictOrNull() ?: default
    }

    private fun JsonObject.stringSet(key: String): Set<String> {
        val array = this[key] as? JsonArray ?: return emptySet()
        return array.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
    }
}
