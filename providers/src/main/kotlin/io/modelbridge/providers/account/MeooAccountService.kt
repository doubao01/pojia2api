package io.modelbridge.providers.account

import io.modelbridge.core.Account
import io.modelbridge.spi.Credential
import io.modelbridge.spi.HttpClient
import io.modelbridge.spi.HttpRequest

/** Meoo 每日登录赠送与积分余额，静态契约来自 3.9.3 `r/d.java`、`r/f.java`。 */
class MeooAccountService(
    http: HttpClient,
    private val userAgent: String = "",
    private val baseUrl: String = "https://meoo.com",
    clockMillis: () -> Long = System::currentTimeMillis,
) : HttpProviderAccountService(
    providerId = "meoo",
    http = http,
    capabilities = setOf(AccountCapability.CreditRefresh, AccountCapability.CheckIn),
    clockMillis = clockMillis,
) {
    override suspend fun credit(account: Account): AccountQuota {
        val response = try {
            request(
                HttpRequest(
                    method = "GET",
                    url = "$baseUrl/api/mc/entitlement/get",
                    headers = headers(account),
                    connectTimeoutMillis = 15_000,
                    readTimeoutMillis = 60_000,
                ),
            )
        } catch (error: Exception) {
            return failedQuota(error.message ?: "积分查询失败")
        }
        if (response.status == 401) return failedQuota("登录态已失效，请重新登录")
        if (response.status !in 200..299) return failedQuota(httpError("积分查询失败", response))
        val data = response.json?.obj("data") ?: return failedQuota("上游未返回积分")
        val balance = data.obj("userPointInfo")?.obj("account")?.long("availableBalance")
            ?: return failedQuota("上游未返回积分")
        val plan = data.text("planCode")
        val grants = data.obj("userPointInfo")?.array("activeGrants") ?: emptyList()
        val earliestExpiry = grants.mapNotNull { element ->
            val grant = element as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            val details = grant.array("grantDetails") ?: emptyList()
            details.mapNotNull { detail ->
                val node = detail as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                node.text("expiresDate").takeIf { it.isNotBlank() && !it.startsWith("9999") }
            }.minOrNull()
        }.minOrNull().orEmpty()
        val detail = buildList {
            plan.takeIf { it.isNotBlank() }?.let { add(it) }
            earliestExpiry.takeIf { it.isNotBlank() }?.let { add("$it 到期") }
        }.joinToString(" · ")
        return AccountQuota(
            entries = listOf(
                QuotaEntry(
                    kind = QuotaKind.Points,
                    label = "可用积分",
                    remaining = balance,
                    total = balance,
                    detail = detail,
                ),
            ),
            detail = detail,
            known = true,
            refreshedAtMillis = clockMillis(),
        )
    }

    override suspend fun checkIn(account: Account): AccountActionResult {
        val response = try {
            request(
                HttpRequest(
                    method = "GET",
                    url = "$baseUrl/api/mc/entitlement/init",
                    headers = headers(account),
                    connectTimeoutMillis = 15_000,
                    readTimeoutMillis = 60_000,
                ),
            )
        } catch (error: Exception) {
            return AccountActionResult(false, error.message ?: "签到失败")
        }
        if (response.status == 401) return AccountActionResult(false, "登录态已失效，请重新登录")
        if (response.status !in 200..299) return AccountActionResult(false, httpError("签到失败", response))
        val data = response.json?.obj("data")
            ?: return AccountActionResult(false, "上游未返回领取结果")
        if (data.boolean("dailyLoginGranted") != true) {
            val reason = data.text("dailyLoginSkipReason")
            return when {
                reason == "ALREADY_GRANTED_TODAY" || reason.isBlank() ->
                    AccountActionResult(true, "今天已经领取过啦")
                else -> AccountActionResult(false, reason)
            }
        }
        val amount = data.long("dailyLoginGrantAmount") ?: 0
        return AccountActionResult(
            true,
            if (amount > 0) "领取成功，获得 $amount 积分" else "领取成功",
        )
    }

    private fun headers(account: Account): Map<String, String> = buildMap {
        put("Accept", "application/json")
        put("Content-Type", "application/json")
        if (userAgent.isNotBlank()) put("User-Agent", userAgent)
        put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        put("Origin", baseUrl)
        put("Referer", "$baseUrl/")
        val cookie = (account.credential as? Credential.Cookies)?.raw.orEmpty()
        if (cookie.isNotBlank()) put("Cookie", cookie)
    }
}
