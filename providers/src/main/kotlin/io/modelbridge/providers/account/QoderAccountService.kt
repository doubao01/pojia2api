package io.modelbridge.providers.account

import io.modelbridge.core.Account
import io.modelbridge.spi.HttpClient
import io.modelbridge.spi.HttpRequest
import kotlinx.serialization.json.JsonObject

/**
 * Qoder 账号额度。
 *
 * 静态契约来自 3.9.3 `p017t/e.java:536` 与 `p017t/s.java:2968-2978`：
 * GET `/api/v2/quota/usage`，响应 `data` 中解析 userQuota / addOnQuota /
 * 共享额度字段的 cap、remaining、used。Qoder 签到涉及额外设备派生头与
 * 多个活动接口，静态反编译条件存在歧义，本轮不冒充已实现。
 */
class QoderAccountService(
    http: HttpClient,
    clockMillis: () -> Long = System::currentTimeMillis,
) : HttpProviderAccountService(
    providerId = "qoder",
    http = http,
    capabilities = setOf(AccountCapability.CreditRefresh),
    clockMillis = clockMillis,
) {
    override suspend fun credit(account: Account): AccountQuota {
        val region = region(account)
        val response = try {
            request(
                HttpRequest(
                    method = "GET",
                    url = region.openApi + "/api/v2/quota/usage",
                    headers = headers(account, region),
                    connectTimeoutMillis = 15_000,
                    readTimeoutMillis = 30_000,
                ),
            )
        } catch (error: Exception) {
            return failedQuota(error.message ?: "查询额度失败")
        }
        if (response.status !in 200..299) return failedQuota(httpError("额度查询失败", response))
        val root = response.json ?: return failedQuota("响应不是合法 JSON")
        val data = root.obj("data") ?: root
        val entries = mutableListOf<QuotaEntry>()
        addQuota(data, "userQuota", QuotaKind.Plan, "套餐额度", entries)
        addQuota(data, "addOnQuota", QuotaKind.AddOn, "加购额度", entries)
        for (key in listOf("orgResourcePackage", "sharedQuota", "teamQuota", "poolQuota")) {
            addQuota(data, key, QuotaKind.Shared, "共享额度", entries)
        }
        for (key in listOf("sharedCredits", "sharedCredit")) {
            addScalar(data, key, QuotaKind.Shared, "共享额度", entries)
        }
        if (entries.isEmpty()) return failedQuota("上游未返回可解析额度")

        val plan = data.obj("plan") ?: data.obj("subscription")
        val detail = buildList {
            plan?.text("plan_tier_name", "planTierName")?.takeIf { it.isNotBlank() }?.let { add(it) }
            entries.sumOf { it.remaining }.takeIf { it > 0 }?.let { add("剩余 $it") }
            val usage = data.double("totalUsagePercentage")?.let {
                if (it in 0.0..1.0) it * 100 else it
            }
            usage?.takeIf { it > 0 }?.let { add("已用 ${it.toLong()}%") }
        }.joinToString(" · ")
        return AccountQuota(
            entries = entries,
            detail = detail,
            known = true,
            refreshedAtMillis = clockMillis(),
        )
    }

    private fun addQuota(
        data: JsonObject,
        key: String,
        kind: QuotaKind,
        label: String,
        sink: MutableList<QuotaEntry>,
    ) {
        val node = data.obj(key) ?: return
        val (total, remaining, used) = quotaNumbers(node)
        val available = when {
            remaining != null -> remaining
            total != null && used != null -> maxOf(0, total - used)
            else -> return
        }
        if (node.long("available") == 0L) return
        sink += QuotaEntry(
            kind = kind,
            label = label,
            total = total ?: (available + (used ?: 0)),
            remaining = available,
            used = used ?: ((total ?: available) - available).coerceAtLeast(0),
        )
    }

    private fun addScalar(
        data: JsonObject,
        key: String,
        kind: QuotaKind,
        label: String,
        sink: MutableList<QuotaEntry>,
    ) {
        val value = data.long(key) ?: return
        sink += QuotaEntry(kind = kind, label = label, remaining = value, total = value)
    }

    private fun headers(account: Account, region: Region): Map<String, String> = mapOf(
        "Authorization" to "Bearer ${bearer(account)}",
        "Accept" to "application/json, text/plain, */*",
        "User-Agent" to region.userAgent,
        "Content-Type" to "application/json",
        "Origin" to region.origin,
        "Referer" to "${region.origin}/",
    )

    private fun region(account: Account): Region = if (normalizedRegion(account) == "global") {
        Region("https://openapi.qoder.sh", "https://qoder.com", "Qoder/1.1.64")
    } else {
        Region("https://openapi.qoder.com.cn", "https://qoder.com.cn", "QoderWork/1.1.64")
    }

    private data class Region(val openApi: String, val origin: String, val userAgent: String)
}
