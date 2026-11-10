package io.modelbridge.providers.account

import io.modelbridge.core.Account
import io.modelbridge.spi.Credential
import io.modelbridge.spi.HttpClient
import io.modelbridge.spi.HttpRequest

/**
 * 讯飞星辰 AStudio 积分与套餐。
 *
 * 静态契约来自 3.9.3 `p011l/b.java:602-680`、`G/d.java:257-275`：
 * POST `/points/balance` 和 `/membership/me`。版本、clientType 与 uid
 * 由 [AStudioRequestMaterial] 注入，不从原 APK 复制固定值。
 */
class AStudioAccountService(
    http: HttpClient,
    private val material: AStudioRequestMaterial = AStudioRequestMaterial(),
    clockMillis: () -> Long = System::currentTimeMillis,
) : HttpProviderAccountService(
    providerId = "astudio",
    http = http,
    capabilities = setOf(AccountCapability.CreditRefresh),
    clockMillis = clockMillis,
) {
    override suspend fun credit(account: Account): AccountQuota {
        val balance = requestOrNull(
            HttpRequest(
                method = "POST",
                url = "${material.baseUrl}/points/balance",
                headers = headers(account),
                body = jsonBody(emptyMap()),
                connectTimeoutMillis = 15_000,
                readTimeoutMillis = 60_000,
            ),
        ) ?: return failedQuota("积分查询失败")
        if (balance.status !in 200..299) return failedQuota(httpError("积分查询失败", balance))
        val root = balance.json ?: return failedQuota("响应不是合法 JSON")
        val data = root.obj("data") ?: root.obj("result") ?: root
        val points = data.long("totalBalance") ?: return failedQuota("上游未返回积分余额")
        val spark = data.long("sparkTotalBalance") ?: 0
        val membership = requestOrNull(
            HttpRequest(
                method = "POST",
                url = "${material.baseUrl}/membership/me",
                headers = headers(account),
                body = jsonBody(emptyMap()),
                connectTimeoutMillis = 15_000,
                readTimeoutMillis = 60_000,
            ),
        )
        val memberData = membership?.takeIf { it.status in 200..299 }?.json
            ?.let { it.obj("data") ?: it.obj("result") ?: it }
        val planName = memberData?.text("planName", "plan_name").orEmpty()
        val planCode = memberData?.text("planCode", "plan_code").orEmpty()
        val detail = listOfNotNull(
            planName.takeIf { it.isNotBlank() } ?: planCode.takeIf { it.isNotBlank() },
            "积分 $points",
            spark.takeIf { it > 0 }?.let { "星火积分 $it" },
        ).joinToString(" · ")
        return AccountQuota(
            entries = buildList {
                add(QuotaEntry(QuotaKind.Points, "积分", remaining = points, total = points))
                if (spark > 0) {
                    add(QuotaEntry(QuotaKind.Points, "星火积分", remaining = spark, total = spark))
                }
            },
            detail = detail,
            known = true,
            refreshedAtMillis = clockMillis(),
        )
    }

    private fun headers(account: Account): Map<String, String> = buildMap {
        val credential = account.credential as? Credential.ApiKey
            ?: throw IllegalStateException("账号凭证无法解析")
        if (credential.key.isBlank()) throw IllegalStateException("账号凭证无法解析")
        put("Content-Type", "application/json")
        put("Authorization", "Bearer ${credential.key}")
        put("Accept", "application/json, text/event-stream")
        material.clientType.takeIf { it.isNotBlank() }?.let { put("clientType", it) }
        material.studioVersion.takeIf { it.isNotBlank() }?.let { put("studioVersion", it) }
        material.userAgent.takeIf { it.isNotBlank() }?.let { put("User-Agent", it) }
        account.authUid.takeIf { it.isNotBlank() && it != "0" }?.let { put("uid", it) }
    }

    private suspend fun requestOrNull(request: HttpRequest): JsonResponse? = try {
        request(request)
    } catch (_: Exception) {
        null
    }
}

/** AStudio 版本与客户端上下文材料，由宿主按实际环境注入；不从原 APK 复制固定值。 */
data class AStudioRequestMaterial(
    val baseUrl: String = "https://astronstudio-api-volces-prod.xf-yun.com/api/v1/model-manager",
    val clientType: String = "",
    val studioVersion: String = "",
    val userAgent: String = "",
)
