package io.modelbridge.providers.account

import io.modelbridge.core.Account
import io.modelbridge.spi.Credential
import io.modelbridge.spi.HttpClient
import io.modelbridge.spi.HttpRequest
import kotlinx.serialization.json.JsonObject

/** 小浣熊积分余额，静态契约来自 3.9.3 `p018u/c.java:725-805`。 */
class RaccoonAccountService(
    http: HttpClient,
    private val userAgent: String = "",
    private val orgCode: String = "",
    clockMillis: () -> Long = System::currentTimeMillis,
) : HttpProviderAccountService(
    providerId = "raccoon",
    http = http,
    capabilities = setOf(
        AccountCapability.CreditRefresh,
        AccountCapability.CheckIn,
        AccountCapability.PointsBills,
    ),
    clockMillis = clockMillis,
) {
    override suspend fun credit(account: Account): AccountQuota {
        val token = oauthToken(account)
        val response = try {
            request(
                HttpRequest(
                    method = "GET",
                    url = "https://xiaohuanxiong.com/api/web/points/v1/balance",
                    headers = headers(account, token),
                    connectTimeoutMillis = 15_000,
                    readTimeoutMillis = 30_000,
                ),
            )
        } catch (error: Exception) {
            return failedQuota(error.message ?: "积分查询失败")
        }
        if (response.status !in 200..299) return failedQuota(httpError("积分查询失败", response))
        val root = response.json ?: return failedQuota("响应不是合法 JSON")
        val data = root.obj("data") ?: return failedQuota("响应缺少 data")
        val available = data.long(
            "available_points", "availablePoints", "points", "balance", "credits",
        ) ?: return failedQuota("响应里没有积分字段")
        return AccountQuota(
            entries = listOf(
                QuotaEntry(
                    kind = QuotaKind.Points,
                    label = "可用积分",
                    remaining = available,
                    total = available,
                ),
            ),
            detail = "可用积分 $available",
            known = true,
            refreshedAtMillis = clockMillis(),
        )
    }

    private fun oauthToken(account: Account): String {
        val credential = account.credential as? Credential.OAuth
            ?: throw IllegalStateException("凭证无法解析")
        if (credential.accessToken.isBlank()) throw IllegalStateException("凭证无法解析")
        return credential.accessToken
    }

    private fun headers(account: Account, token: String): Map<String, String> = buildMap {
        put("Authorization", "Bearer $token")
        put("Accept", "application/json")
        if (userAgent.isNotBlank()) put("User-Agent", userAgent)
        put("Accept-Language", "zh-Hans")
        val org = orgCode.ifBlank { account.authTenantId }
        if (org.isNotBlank()) put("X-Org-Code", org)
    }
}

/** CatPaw / catx 积分余额，静态契约来自 3.9.3 `p008i/a.java:4957-5134`。 */
class CatpawAccountService(
    http: HttpClient,
    clockMillis: () -> Long = System::currentTimeMillis,
) : HttpProviderAccountService(
    providerId = "catpaw",
    http = http,
    capabilities = setOf(AccountCapability.CreditRefresh),
    clockMillis = clockMillis,
) {
    override suspend fun credit(account: Account): AccountQuota {
        val token = oauthToken(account)
        val response = try {
            request(
                HttpRequest(
                    method = "GET",
                    url = "https://catx.nocode.cn/api/gateway/credit/balance",
                    headers = mapOf(
                        "Accept" to "application/json",
                        "X-Auth-Token" to token,
                        "gray-set" to "new-agent-sdk",
                    ),
                    connectTimeoutMillis = 15_000,
                    readTimeoutMillis = 15_000,
                ),
            )
        } catch (error: Exception) {
            return failedQuota(error.message ?: "积分查询失败")
        }
        if (response.status == 401 || response.json?.long("code") in setOf(4010L, 4011L)) {
            return failedQuota("凭证已失效，请重新登录")
        }
        if (response.status !in 200..299) return failedQuota(httpError("积分查询失败", response))
        val root = response.json ?: return failedQuota("响应不是合法 JSON")
        val code = root.long("code")
        val data = root.obj("data") ?: return failedQuota("响应缺少 data")
        if (code != null && code != 0L) return failedQuota(errorMessage(response).ifBlank { "积分查询返回异常 code=$code" })
        val available = data.double("availableCredits", "available", "available_credits")
            ?: return failedQuota("积分查询未返回可用额度")
        val plan = data.obj("userPlan") ?: JsonObject(emptyMap())
        val planName = plan.text("planName", "name", "title")
        return AccountQuota(
            entries = listOf(
                QuotaEntry(
                    kind = QuotaKind.Points,
                    label = "可用积分",
                    remaining = available.toLong(),
                    total = available.toLong(),
                    detail = planName,
                ),
            ),
            detail = listOfNotNull(
                planName.takeIf { it.isNotBlank() },
                "可用积分 ${available.toLong()}",
            ).joinToString(" · "),
            known = true,
            refreshedAtMillis = clockMillis(),
        )
    }

    private fun oauthToken(account: Account): String {
        val credential = account.credential as? Credential.OAuth
            ?: throw IllegalStateException("账号凭证无法解析")
        if (credential.accessToken.isBlank()) throw IllegalStateException("账号凭证无法解析")
        return credential.accessToken
    }
}
