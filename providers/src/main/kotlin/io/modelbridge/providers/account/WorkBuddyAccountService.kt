package io.modelbridge.providers.account

import io.modelbridge.core.Account
import io.modelbridge.spi.HttpClient
import io.modelbridge.spi.HttpRequest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * WorkBuddy / CodeBuddy 账号业务。
 *
 * 静态契约来自 3.9.3 的 `n/d.java`：国内版 copilot.tencent.com / www.codebuddy.cn，
 * 国际版 www.workbuddy.ai；签到和额度走 billingBase，成长任务走 chatBase。
 * 这里不复制客户端标识或签名材料，只使用已保存账号的 OAuth access token。
 */
class WorkBuddyAccountService(
    http: HttpClient,
    clockMillis: () -> Long = System::currentTimeMillis,
    /** 复用同一套签到/额度协议的供应商 id；桌面端接入用 `workbuddy`。 */
    providerId: String = "codebuddy",
) : HttpProviderAccountService(
    providerId = providerId,
    http = http,
    capabilities = setOf(
        AccountCapability.CreditRefresh,
        AccountCapability.CheckIn,
        AccountCapability.Tasks,
    ),
    clockMillis = clockMillis,
) {
    private data class Region(val chatBase: String, val billingBase: String, val origin: String)

    override suspend fun credit(account: Account): AccountQuota {
        val region = region(account)
        val response = try {
            request(
                HttpRequest(
                    method = "POST",
                    url = region.billingBase + "/v2/billing/meter/get-user-resource",
                    headers = headers(account, region),
                    body = resourceRequestBody(clockMillis()),
                    connectTimeoutMillis = 15_000,
                    readTimeoutMillis = 30_000,
                ),
            )
        } catch (error: Exception) {
            return failedQuota(error.message ?: "查询额度失败")
        }
        if (response.status !in 200..299) {
            return failedQuota(httpError("额度查询失败", response).withMessage(response))
        }
        val root = response.json ?: return failedQuota("响应不是合法 JSON")
        if (root.long("code") != 0L) return failedQuota(errorMessage(response).ifBlank { "额度接口返回异常" })
        val data = root.obj("data") ?: return failedQuota("响应缺少 data")
        val responseNode = data.obj("Response") ?: data
        val accounts = responseNode.obj("Data")?.array("Accounts")
            ?: return failedQuota("响应缺少 Response.Data.Accounts")

        val entries = mutableListOf<QuotaEntry>()
        for (item in accounts) {
            val node = item as? JsonObject ?: continue
            val total = node.long("CycleCapacitySize")
            val remaining = node.long("CycleCapacityRemain", "CapacityRemain")
            if (total == null && remaining == null) continue
            entries += QuotaEntry(
                kind = QuotaKind.Plan,
                label = node.text("PackageName", "PlanName", "name").ifBlank { "套餐额度" },
                total = total ?: 0,
                remaining = remaining ?: 0,
                used = ((total ?: 0) - (remaining ?: 0)).coerceAtLeast(0),
            )
        }
        if (entries.isEmpty()) return failedQuota("响应里没有可识别的额度字段")
        return AccountQuota(
            entries = entries,
            detail = "周期剩余 ${entries.sumOf { it.remaining }}",
            known = true,
            refreshedAtMillis = clockMillis(),
        )
    }

    override suspend fun checkIn(account: Account): AccountActionResult {
        if (normalizedRegion(account) == "global") {
            return AccountActionResult(false, "该供应商不支持「签到」", unsupported = true)
        }
        if (account.authTenantId.isNotBlank()) {
            return AccountActionResult(false, "该供应商不支持「签到」", unsupported = true)
        }
        val region = region(account)
        val response = try {
            request(
                HttpRequest(
                    method = "POST",
                    url = region.billingBase + "/v2/billing/meter/daily-checkin",
                    headers = headers(account, region),
                    body = jsonBody(emptyMap()),
                    connectTimeoutMillis = 15_000,
                    readTimeoutMillis = 30_000,
                ),
            )
        } catch (error: Exception) {
            return AccountActionResult(false, error.message ?: "签到失败")
        }
        val message = errorMessage(response)
        if (message.contains("已签到", ignoreCase = true) || message.contains("already", ignoreCase = true)) {
            return AccountActionResult(true, "今日已签到")
        }
        if (response.status !in 200..299) {
            return AccountActionResult(false, httpError("签到失败", response).withMessage(response))
        }
        val code = response.json?.long("code") ?: -1L
        return if (code == 0L) {
            AccountActionResult(true, "签到成功")
        } else {
            AccountActionResult(false, message.ifBlank { "签到失败（code=$code）" })
        }
    }

    override suspend fun claimTasks(account: Account): AccountActionResult {
        if (normalizedRegion(account) == "global") {
            return AccountActionResult(false, "国际版没有成长任务", unsupported = true)
        }
        if (account.authTenantId.isNotBlank()) {
            return AccountActionResult(false, "企业账号无个人成长任务", unsupported = true)
        }
        val region = region(account)
        val paths = listOf(
            "/v2/billing/meter/claim-gift" to "新手礼包",
            "/v2/billing/meter/claim-compensation" to "补偿",
        )
        val messages = mutableListOf<String>()
        var success = false
        for ((path, label) in paths) {
            val response = try {
                request(
                    HttpRequest(
                        method = "POST",
                        url = region.chatBase + path,
                        headers = headers(account, region),
                        body = jsonBody(emptyMap()),
                        connectTimeoutMillis = 15_000,
                        readTimeoutMillis = 30_000,
                    ),
                )
            } catch (_: Exception) {
                continue
            }
            if (response.status !in 200..299) continue
            val root = response.json ?: continue
            val reward = root.double("credit", "reward_credit", "amount")
                ?: root.obj("data")?.double("credit", "reward_credit", "amount")
            if (reward != null) {
                success = true
                messages += "$label +${reward.toLong()}"
            }
        }
        messages += claimGrowthTasks(account, region)
        success = success || messages.size > paths.size
        return if (success) {
            AccountActionResult(true, "已处理：${messages.joinToString("、")}")
        } else {
            AccountActionResult(false, "没有可领取的任务")
        }
    }

    override suspend fun tasks(account: Account): ProviderTaskList {
        if (normalizedRegion(account) == "global") {
            return ProviderTaskList(error = "国际版没有成长任务")
        }
        if (account.authTenantId.isNotBlank()) {
            return ProviderTaskList(error = "企业账号无个人成长任务")
        }
        val region = region(account)
        val response = try {
            request(
                HttpRequest(
                    method = "GET",
                    url = region.chatBase + "/v2/activity/growth/tasks",
                    headers = headers(account, region),
                    connectTimeoutMillis = 15_000,
                    readTimeoutMillis = 30_000,
                ),
            )
        } catch (error: Exception) {
            return ProviderTaskList(error = error.message ?: "拉取任务列表失败")
        }
        if (response.status !in 200..299) {
            return ProviderTaskList(error = httpError("任务列表拉取失败", response))
        }
        val root = response.json ?: return ProviderTaskList(error = "响应不是合法 JSON")
        val items = root.array("tasks") ?: root.obj("data")?.array("tasks")
            ?: return ProviderTaskList(inPeriod = root.long("in_period") == 1L)
        val tasks = items.mapNotNull { element ->
            val node = element as? JsonObject ?: return@mapNotNull null
            val code = node.text("task_code", "code", "id")
            if (code.isBlank()) return@mapNotNull null
            val progress = node.obj("progress")
            val status = node.text("accept_status")
            ProviderTask(
                code = code,
                title = node.text("title").ifBlank { code },
                description = node.text("description"),
                current = progress?.long("current") ?: node.long("current") ?: 0,
                target = progress?.long("target") ?: node.long("target") ?: 0,
                status = status,
                locked = node.long("locked") == 1L,
                claimed = status.equals("claimed", ignoreCase = true),
                rewardCredit = node.long("reward_credit", "rewardCredit") ?: 0,
            )
        }
        return ProviderTaskList(
            tasks = tasks,
            inPeriod = root.long("in_period") == 1L,
            error = if (tasks.isEmpty()) "没有可显示的任务" else "",
        )
    }

    private suspend fun claimGrowthTasks(
        account: Account,
        region: Region,
    ): List<String> {
        val response = try {
            request(
                HttpRequest(
                    method = "GET",
                    url = region.chatBase + "/v2/activity/growth/tasks",
                    headers = headers(account, region),
                    connectTimeoutMillis = 15_000,
                    readTimeoutMillis = 30_000,
                ),
            )
        } catch (_: Exception) {
            return emptyList()
        }
        if (response.status !in 200..299) return emptyList()
        val root = response.json ?: return emptyList()
        val items = root.array("tasks") ?: root.obj("data")?.array("tasks") ?: return emptyList()
        val messages = mutableListOf<String>()
        for (item in items) {
            val node = item as? kotlinx.serialization.json.JsonObject ?: continue
            val code = node.text("task_code", "code")
            if (code.isBlank()) continue
            val status = node.text("accept_status")
            if (status.equals("claimed", ignoreCase = true) || node.long("locked") == 1L) continue
            val progress = node.obj("progress")
            val target = progress?.long("target") ?: node.long("target") ?: 1L
            val current = progress?.long("current") ?: node.long("current") ?: 0L
            val eligible = (target > 0 && current >= target) || status.isBlank()
            if (!eligible) continue
            val title = node.text("title").ifBlank { code }
            val claim = if (target > 0 && current >= target && status.isNotBlank()) {
                requestOrNull(
                    HttpRequest(
                        method = "GET",
                        url = region.origin + "/activity/growth/tasks/$code/claim",
                        headers = growthHeaders(account, region),
                        connectTimeoutMillis = 15_000,
                        readTimeoutMillis = 30_000,
                    ),
                )
            } else {
                requestOrNull(
                    HttpRequest(
                        method = "POST",
                        url = region.chatBase + "/v2/activity/growth/tasks/accept",
                        headers = headers(account, region),
                        body = jsonBody(mapOf("task_code" to string(code))),
                        connectTimeoutMillis = 15_000,
                        readTimeoutMillis = 30_000,
                    ),
                )
            }
            if (claim == null || claim.status !in 200..299) continue
            val reward = claim.json?.double("credit", "reward_credit")
                ?: claim.json?.obj("data")?.double("credit", "reward_credit")
            messages += if (reward != null && reward > 0) {
                "领任务奖「$title」+${reward.toLong()}"
            } else {
                "领取任务「$title」"
            }
        }
        return messages
    }

    private suspend fun requestOrNull(request: HttpRequest): JsonResponse? = try {
        request(request)
    } catch (_: Exception) {
        null
    }

    private fun growthHeaders(account: Account, region: Region): Map<String, String> =
        headers(account, region) + mapOf(
            "Referer" to "${region.origin}/profile/growth-center",
            "x-client-platform" to "web",
            "Accept" to "application/json, text/plain, */*",
        )

    private fun headers(account: Account, region: Region): Map<String, String> = buildMap {
        put("Authorization", "Bearer ${bearer(account)}")
        put("Accept", "application/json")
        put("Content-Type", "application/json")
        put("X-Requested-With", "XMLHttpRequest")
        put("User-Agent", "CLI/2.63.2 CodeBuddy/2.63.2")
        put("Origin", region.origin)
        put("Referer", "${region.origin}/")
        put("X-Product", "SaaS")
        account.authUid.takeIf { it.isNotBlank() }?.let { put("X-User-Id", it) }
        account.authTenantId.takeIf { it.isNotBlank() }?.let {
            put("X-Enterprise-Id", it)
            put("X-Tenant-Id", it)
        }
        account.authDomain.takeIf { it.isNotBlank() }?.let { put("X-Domain", it) }
    }

    private fun region(account: Account): Region = if (normalizedRegion(account) == "global") {
        Region("https://www.workbuddy.ai", "https://www.workbuddy.ai", "https://www.workbuddy.ai")
    } else {
        Region("https://copilot.tencent.com", "https://www.codebuddy.cn", "https://www.codebuddy.cn")
    }

    private fun String.withMessage(response: JsonResponse): String {
        val message = errorMessage(response)
        return if (message.isBlank()) this else "$this：$message"
    }
}
