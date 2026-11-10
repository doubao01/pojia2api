package io.modelbridge.providers.account

import io.modelbridge.core.Account
import io.modelbridge.spi.Credential
import io.modelbridge.spi.HttpClient
import io.modelbridge.spi.HttpRequest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Tabbit 签到与额度。
 *
 * 静态契约来自 3.9.3 `p020w/f.java` 与 `p020w/h.java`：签到前查状态，
 * 额度来自 quota/usage 与 member/level；浏览器识别头、固定上下文头和随机
 * 请求头由 [TabbitRequestMaterial] 注入，不从原 APK 复制。
 */
class TabbitAccountService(
    http: HttpClient,
    private val material: TabbitRequestMaterial = TabbitRequestMaterial(),
    private val randomId: () -> String = { UUID.randomUUID().toString() },
    clockMillis: () -> Long = System::currentTimeMillis,
) : HttpProviderAccountService(
    providerId = "tabbit",
    http = http,
    capabilities = setOf(
        AccountCapability.CreditRefresh,
        AccountCapability.CheckIn,
    ),
    clockMillis = clockMillis,
) {
    override suspend fun credit(account: Account): AccountQuota {
        val userId = account.authUid.ifBlank { account.id }
        val encoded = URLEncoder.encode(userId, StandardCharsets.UTF_8.name())
        val timezone = URLEncoder.encode("Asia/Shanghai", StandardCharsets.UTF_8.name())
        val response = try {
            request(
                HttpRequest(
                    method = "GET",
                    url = "${material.baseUrl}/api/commerce/quota/v1/usage?user_id=$encoded&timezone=$timezone",
                    headers = headers(account, material.modelsPath),
                    connectTimeoutMillis = 15_000,
                    readTimeoutMillis = 30_000,
                ),
            )
        } catch (error: Exception) {
            return failedQuota(error.message ?: "额度查询失败")
        }
        if (response.status == 401) return failedQuota("登录态已失效，请重新登录")
        if (response.status !in 200..299) return failedQuota(httpError("额度查询失败", response))
        val root = response.json ?: return failedQuota("响应不是合法 JSON")
        val usage = root.text("usage_percentage")
        val percent = usage.trimEnd('%').toDoubleOrNull()
            ?: return failedQuota("上游未返回可解析额度")
        val remaining = (100.0 - percent).coerceAtLeast(0.0).toLong()
        val memberLevel = root.text("member_level").ifBlank {
            // 原版会再查一次会员等级，失败时保留 usage 返回的等级。
            memberLevel(account) ?: ""
        }
        val cycleEnd = root.text("current_cycle_end")
        val membershipEnd = root.text("membership_end_time")
        val detail = buildList {
            memberLevel.takeIf { it.isNotBlank() }?.let { add(it) }
            add("剩余 $remaining%")
            cycleEnd.takeIf { it.isNotBlank() }?.let { add("周期至 $it") }
            membershipEnd.takeIf { it.isNotBlank() }?.let { add("会员至 $it") }
        }.joinToString(" · ")
        return AccountQuota(
            entries = listOf(
                QuotaEntry(
                    kind = QuotaKind.Plan,
                    label = "套餐额度",
                    remaining = remaining,
                    total = 100,
                    used = 100 - remaining,
                    detail = "已用 $usage",
                ),
            ),
            detail = detail,
            known = true,
            refreshedAtMillis = clockMillis(),
        )
    }

    override suspend fun checkIn(account: Account): AccountActionResult {
        val status = requestOrNull(
            HttpRequest(
                method = "GET",
                url = "${material.baseUrl}/api/commerce/activity/v1/sign-in/status?scene_codes=daily_sign_in",
                headers = headers(account, material.modelsPath),
                connectTimeoutMillis = 15_000,
                readTimeoutMillis = 30_000,
            ),
        ) ?: return AccountActionResult(false, "签到状态查询失败")
        if (status.status !in 200..299) return AccountActionResult(false, httpError("签到状态查询失败", status))
        val result = status.json?.array("results")?.firstOrNull() as? JsonObject
        if (result?.boolean("signed_today") == true) {
            return AccountActionResult(true, "今天已签到")
        }
        if (result?.boolean("activity_open") == false) {
            return AccountActionResult(false, "签到活动未开放")
        }
        val usage = quotaUsage(account).ifBlank { "0%" }
        val body = jsonBody(mapOf(
            "request_no" to string("signin_${clockMillis()}_${randomId().replace("-", "")}"),
            "scene_codes" to JsonArray(listOf(string("daily_sign_in"))),
            "usage_percentage" to string(usage),
        ))
        val response = try {
            request(
                HttpRequest(
                    method = "POST",
                    url = "${material.baseUrl}/api/commerce/activity/v1/sign-in",
                    headers = headers(account, material.modelsPath) + ("Content-Type" to "application/json"),
                    body = body,
                    connectTimeoutMillis = 15_000,
                    readTimeoutMillis = 30_000,
                ),
            )
        } catch (error: Exception) {
            return AccountActionResult(false, error.message ?: "签到失败")
        }
        val signInResult = (response.json?.array("results")?.firstOrNull() as? JsonObject)
            ?.text("sign_in_result").orEmpty()
        return when {
            signInResult == "success" -> AccountActionResult(true, "签到成功")
            signInResult == "already_signed" -> AccountActionResult(true, "今天已签到")
            response.status in 200..299 -> AccountActionResult(true, signInResult.ifBlank { "签到完成" })
            response.status == 401 -> AccountActionResult(false, "登录态已失效，请重新登录")
            else -> AccountActionResult(false, httpError("签到失败", response))
        }
    }

    private suspend fun quotaUsage(account: Account): String {
        val encoded = URLEncoder.encode(account.authUid.ifBlank { account.id }, StandardCharsets.UTF_8.name())
        val timezone = URLEncoder.encode("Asia/Shanghai", StandardCharsets.UTF_8.name())
        val response = requestOrNull(
            HttpRequest(
                method = "GET",
                url = "${material.baseUrl}/api/commerce/quota/v1/usage?user_id=$encoded&timezone=$timezone",
                headers = headers(account, material.modelsPath),
                connectTimeoutMillis = 15_000,
                readTimeoutMillis = 30_000,
            ),
        ) ?: return ""
        return response.takeIf { it.status in 200..299 }?.json?.text("usage_percentage").orEmpty()
    }

    private suspend fun memberLevel(account: Account): String? {
        val encoded = URLEncoder.encode(account.authUid.ifBlank { account.id }, StandardCharsets.UTF_8.name())
        val response = requestOrNull(
            HttpRequest(
                method = "GET",
                url = "${material.baseUrl}/api/commerce/member/v1/level?user_id=$encoded",
                headers = headers(account, material.modelsPath),
                connectTimeoutMillis = 15_000,
                readTimeoutMillis = 30_000,
            ),
        ) ?: return null
        return response.takeIf { it.status in 200..299 }?.json?.text("member_level")?.takeIf { it.isNotBlank() }
    }

    private fun headers(account: Account, refererPath: String): Map<String, String> = buildMap {
        material.userAgent.takeIf { it.isNotBlank() }?.let { put("User-Agent", it) }
        material.requestContext.takeIf { it.isNotBlank() }?.let { put("X-Req-Ctx", it) }
        material.deviceId.takeIf { it.isNotBlank() }?.let { put("x-device-id", it) }
        put("Accept", "application/json")
        put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        put("Origin", material.baseUrl)
        put("Referer", material.baseUrl + refererPath)
        put("X-Nonce", randomId().replace("-", ""))
        put("Trace-Id", randomId())
        put("X-Timestamp", clockMillis().toString())
        put("Unique-Uuid", randomId())
        put("X-Signature", randomId())
        val cookie = (account.credential as? Credential.Cookies)?.raw.orEmpty()
        if (cookie.isNotBlank()) put("Cookie", cookie)
        val bearer = (account.credential as? Credential.OAuth)?.accessToken.orEmpty()
        if (bearer.isNotBlank()) put("Authorization", "Bearer $bearer")
    }

    private suspend fun requestOrNull(request: HttpRequest): JsonResponse? = try {
        request(request)
    } catch (_: Exception) {
        null
    }
}

/** 浏览器识别与设备上下文材料，由宿主按实际环境注入；不从原 APK 复制固定值。 */
data class TabbitRequestMaterial(
    val baseUrl: String = "https://web.tabbit.com",
    val modelsPath: String = "/",
    val userAgent: String = "",
    val requestContext: String = "",
    val deviceId: String = "",
)
