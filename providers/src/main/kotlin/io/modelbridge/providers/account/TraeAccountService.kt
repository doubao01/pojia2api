package io.modelbridge.providers.account

import io.modelbridge.core.Account
import io.modelbridge.spi.Credential
import io.modelbridge.spi.HttpClient
import io.modelbridge.spi.HttpRequest
import kotlinx.serialization.json.JsonObject

/**
 * Trae 签到、签到状态和额度包。
 *
 * 静态契约来自 3.9.3 `p021x/y.java`、`p021x/J.java`、`C/P.java`。
 * 版本、设备品牌、系统版本、设备 ID 由 [TraeRequestMaterial] 注入；
 * 不从原 APK 复制客户端 ID 或固定指纹。
 */
class TraeAccountService(
    http: HttpClient,
    private val material: TraeRequestMaterial = TraeRequestMaterial(),
    clockMillis: () -> Long = System::currentTimeMillis,
) : HttpProviderAccountService(
    providerId = "trae",
    http = http,
    capabilities = setOf(
        AccountCapability.CreditRefresh,
        AccountCapability.CheckIn,
    ),
    clockMillis = clockMillis,
) {
    override suspend fun credit(account: Account): AccountQuota {
        val paths = if (normalizedRegion(account) == "global") {
            listOf(
                "/trae/api/v1/pay/user_current_entitlement_list" to """{"require_usage":true}""",
                "/trae/api/v1/pay/ide_user_ent_usage" to "{}",
            )
        } else {
            listOf(
                "/trae/api/v2/pay/web_user_ent_usage" to """{"require_usage":true}""",
                "/trae/api/v2/pay/ide_user_ent_usage" to "{}",
                "/trae/api/v2/pay/user_current_entitlement_list" to """{"require_usage":true}""",
            )
        }
        for ((path, body) in paths) {
            val response = requestOrNull(
                HttpRequest(
                    method = "POST",
                    url = baseUrl(account) + path,
                    headers = headers(account),
                    body = body.toByteArray(Charsets.UTF_8),
                    connectTimeoutMillis = 15_000,
                    readTimeoutMillis = 30_000,
                ),
            ) ?: continue
            if (response.status !in 200..299) continue
            val root = response.json ?: continue
            val packs = findPackArray(root) ?: continue
            val entries = mutableListOf<QuotaEntry>()
            for (item in packs) {
                val pack = item as? JsonObject ?: continue
                val quota = pack.obj("entitlement_base_info")?.obj("quota")
                val total = quota?.long("credits_limit") ?: continue
                if (total <= 0) continue
                val used = pack.obj("usage")?.double("credits_amount")?.toLong() ?: 0
                val baseInfo = pack.obj("entitlement_base_info")
                val label = pack.text("display_desc", "group_name").ifBlank {
                    baseInfo?.obj("product_extra")?.obj("package_extra")?.text("package_name").orEmpty()
                }.ifBlank { "额度包" }
                val expiry = pack.long("expire_time") ?: baseInfo?.long("end_time") ?: 0
                entries += QuotaEntry(
                    kind = QuotaKind.Plan,
                    label = label,
                    total = total,
                    used = used,
                    remaining = (total - used).coerceAtLeast(0),
                    detail = if (expiry > 0) "到期 $expiry" else "",
                )
            }
            if (entries.isEmpty()) continue
            return AccountQuota(
                entries = entries,
                detail = "权益包剩余 ${entries.sumOf { it.remaining }} / ${entries.sumOf { it.total }}",
                known = true,
                refreshedAtMillis = clockMillis(),
            )
        }
        return failedQuota("上游未返回可解析额度")
    }

    override suspend fun checkIn(account: Account): AccountActionResult {
        if (normalizedRegion(account) == "global") {
            return AccountActionResult(false, "该供应商不支持「签到」", unsupported = true)
        }
        val status = requestOrNull(
            HttpRequest(
                method = "POST",
                url = baseUrl(account) + "/trae/api/v2/ug/checkin_credits/status",
                headers = headers(account),
                body = "{}".toByteArray(Charsets.UTF_8),
                connectTimeoutMillis = 15_000,
                readTimeoutMillis = 30_000,
            ),
        ) ?: return AccountActionResult(false, "签到状态查询失败")
        if (status.status !in 200..299) return AccountActionResult(false, httpError("签到状态查询失败", status))
        val node = status.json?.let { unwrap(it) }
            ?: return AccountActionResult(false, "签到状态响应无法解析")
        if (node.boolean("checked_in", "checkedIn") == true) {
            return AccountActionResult(true, "今日已签到")
        }
        val claim = requestOrNull(
            HttpRequest(
                method = "POST",
                url = baseUrl(account) + "/trae/api/v2/ug/checkin_credits/claim",
                headers = headers(account),
                body = "{}".toByteArray(Charsets.UTF_8),
                connectTimeoutMillis = 15_000,
                readTimeoutMillis = 30_000,
            ),
        ) ?: return AccountActionResult(false, "签到失败")
        if (claim.status !in 200..299) return AccountActionResult(false, httpError("签到失败", claim))
        val claimRoot = claim.json
        val code = claimRoot?.long("code") ?: 0
        val success = code == 0L || code == 200L ||
            claimRoot?.boolean("success") == true ||
            claimRoot?.text("status").equals("success", ignoreCase = true)
        if (success) return AccountActionResult(true, "签到成功")
        val already = code == 1001L || claimRoot?.text("message", "msg", "error_msg", "error")
            ?.let { text -> listOf("已签到", "已经签到", "明日再来", "今日已完成", "已领取", "already", "checked")
                .any { text.contains(it, ignoreCase = true) } } == true
        if (already) return AccountActionResult(true, "今日已签到")
        return AccountActionResult(
            false,
            claimRoot?.text("message", "msg", "error_msg", "error").ifNullOrBlank { "签到失败（code=$code）" },
        )
    }

    private fun findPackArray(root: JsonObject): List<kotlinx.serialization.json.JsonElement>? {
        val candidates = listOfNotNull(
            root,
            root.obj("data"),
            root.obj("result"),
            root.obj("Result"),
        )
        for (candidate in candidates) {
            candidate.array("user_entitlement_pack_list")?.let { return it }
        }
        return null
    }

    private fun unwrap(root: JsonObject): JsonObject =
        root.obj("data") ?: root.obj("result") ?: root.obj("Result") ?: root

    private fun baseUrl(account: Account): String = if (normalizedRegion(account) == "global") {
        material.globalBaseUrl
    } else {
        material.cnBaseUrl
    }

    private fun headers(account: Account): Map<String, String> = buildMap {
        put("Content-Type", "application/json")
        put("Accept", "application/json")
        if (material.appVersion.isNotBlank()) put("User-Agent", "Trae/${material.appVersion}")
        val token = (account.credential as? Credential.OAuth)?.accessToken.orEmpty()
        if (token.isBlank()) throw IllegalStateException("账号凭证无法解析")
        put("Authorization", "Cloud-IDE-JWT $token")
        put("X-User-Region", if (normalizedRegion(account) == "global") "US" else "CN")
        account.authUid.takeIf { it.isNotBlank() }?.let { put("X-Uid", it) }
        material.deviceBrand.takeIf { it.isNotBlank() }?.let { put("x-device-brand", it) }
        put("x-device-type", "windows")
        material.osVersion.takeIf { it.isNotBlank() }?.let { put("x-os-version", it) }
        material.appVersion.takeIf { it.isNotBlank() }?.let { put("x-app-version", it) }
        material.deviceId.takeIf { it.isNotBlank() }?.let { put("x-device-id", it) }
    }

    private suspend fun requestOrNull(request: HttpRequest): JsonResponse? = try {
        request(request)
    } catch (_: Exception) {
        null
    }

    private fun String?.ifNullOrBlank(block: () -> String): String =
        if (isNullOrBlank()) block() else this
}

/** Trae 版本/设备上下文材料，由宿主按实际环境注入；不从原 APK 复制固定值。 */
data class TraeRequestMaterial(
    val cnBaseUrl: String = "https://api.trae.cn",
    val globalBaseUrl: String = "https://ug-normal.trae.ai",
    val appVersion: String = "",
    val deviceBrand: String = "",
    val osVersion: String = "",
    val deviceId: String = "",
)
