package io.modelbridge.providers.account

import io.modelbridge.core.Account
import io.modelbridge.spi.Credential
import io.modelbridge.spi.HttpClient
import io.modelbridge.spi.HttpRequest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.security.SecureRandom

/**
 * Loomy 账号业务。
 *
 * 静态契约来自 3.9.3 `p015q/m.java`、`p015q/h.java`、`p015q/j.java`：
 * 已保存短信验证码 session 放在 `token` 头；任务、个人积分和团队积分分别查询。
 * 不复制原版固定版本号，[loomyVersion] 由宿主注入。
 */
class LoomyAccountService(
    http: HttpClient,
    private val loomyVersion: String = "",
    private val randomHex: (Int) -> String = ::secureHex,
    clockMillis: () -> Long = System::currentTimeMillis,
) : HttpProviderAccountService(
    providerId = "loomy",
    http = http,
    capabilities = setOf(
        AccountCapability.CreditRefresh,
        AccountCapability.Tasks,
        AccountCapability.PointsBills,
    ),
    clockMillis = clockMillis,
) {
    private companion object {
        const val BASE = "https://loomyad.xunfei.cn"
        val TASK_REWARDS = linkedMapOf(
            "first_message" to 500L,
            "pick_skill" to 1000L,
            "generate_ppt" to 1500L,
            "set_schedule" to 1000L,
            "install_skill" to 1500L,
            "configure_remote" to 1000L,
            "create_soul" to 1500L,
            "share_soul" to 2000L,
        )
    }

    override suspend fun credit(account: Account): AccountQuota {
        val token = session(account)
        val personal = requestOrNull(
            HttpRequest(
                method = "GET",
                url = "$BASE/api/v1/points/records?pageNo=1&pageSize=20&recordType=all",
                headers = headers(token),
                connectTimeoutMillis = 15_000,
                readTimeoutMillis = 30_000,
            ),
        ) ?: return failedQuota("积分查询失败")
        if (personal.status !in 200..299) return failedQuota(httpError("积分查询失败", personal))
        val personalData = personal.json?.obj("data")
        val team = requestOrNull(
            HttpRequest(
                method = "GET",
                url = "$BASE/api/v1/team-points/balance",
                headers = headers(token),
                connectTimeoutMillis = 15_000,
                readTimeoutMillis = 30_000,
            ),
        )
        val teamData = team?.takeIf { it.status in 200..299 }?.json?.obj("data")

        val personalBalance = personalData.long("balance") ?: -1L
        val teamBalance = teamData?.long("currentBalance", "balance") ?: -1L
        val dailyRemaining = personalData.long("dailyRemainingPoints", "dailyBalance") ?: -1L
        val mainBalance = maxOf(personalBalance, teamBalance).coerceAtLeast(0)
        val entries = mutableListOf<QuotaEntry>()
        if (mainBalance >= 0 && (personalBalance >= 0 || teamBalance >= 0)) {
            entries += QuotaEntry(
                kind = QuotaKind.Points,
                label = if (teamBalance > personalBalance) "团队积分" else "个人积分",
                remaining = mainBalance,
                total = mainBalance,
                detail = "可用积分",
            )
        }
        if (dailyRemaining >= 0) {
            entries += QuotaEntry(
                kind = QuotaKind.Plan,
                label = "当日剩余",
                remaining = dailyRemaining,
                total = personalData.long("dailyLimitPoints") ?: dailyRemaining,
                used = personalData.long("dailyConsumedPoints") ?: 0,
            )
        }
        if (entries.isEmpty()) return failedQuota("上游未返回可解析额度")
        val source = if (teamBalance > personalBalance) "团队" else "个人"
        return AccountQuota(
            entries = entries,
            detail = "$source=$mainBalance，当日剩余=$dailyRemaining",
            known = true,
            refreshedAtMillis = clockMillis(),
        )
    }

    override suspend fun tasks(account: Account): ProviderTaskList {
        val token = session(account)
        val response = try {
            request(
                HttpRequest(
                    method = "GET",
                    url = "$BASE/api/v1/onboarding/tasks",
                    headers = headers(token),
                    connectTimeoutMillis = 15_000,
                    readTimeoutMillis = 30_000,
                ),
            )
        } catch (error: Exception) {
            return ProviderTaskList(error = error.message ?: "任务列表拉取失败")
        }
        if (response.status !in 200..299) return ProviderTaskList(error = httpError("任务列表拉取失败", response))
        val flags = response.json?.obj("data")?.obj("tasks") ?: JsonObject(emptyMap())
        val tasks = TASK_REWARDS.map { (code, reward) ->
            val completed = flags.boolean(code) ?: false
            ProviderTask(
                code = code,
                title = taskTitle(code),
                current = if (completed) 1 else 0,
                target = 1,
                status = if (completed) "已完成" else "未完成",
                claimed = completed,
                rewardCredit = reward,
            )
        }
        return ProviderTaskList(tasks = tasks, inPeriod = true)
    }

    override suspend fun claimTasks(account: Account): AccountActionResult {
        val token = session(account)
        val current = tasks(account)
        if (current.error.isNotBlank()) return AccountActionResult(false, current.error)
        val pending = current.tasks.filterNot { it.claimed }
        if (pending.isEmpty()) return AccountActionResult(true, "所有任务都已完成")
        val messages = mutableListOf<String>()
        for (task in pending) {
            val response = requestOrNull(
                HttpRequest(
                    method = "POST",
                    url = "$BASE/api/v1/onboarding/tasks/complete",
                    headers = headers(token) + ("Content-Type" to "application/json"),
                    body = jsonBody(mapOf("key" to string(task.code))),
                    connectTimeoutMillis = 15_000,
                    readTimeoutMillis = 30_000,
                ),
            ) ?: continue
            if (response.status !in 200..299) continue
            val data = response.json?.obj("data") ?: JsonObject(emptyMap())
            val already = data.boolean("alreadyCompleted") ?: false
            messages += "${task.title}：${if (already) "此前已完成" else "已完成"}"
        }
        return if (messages.isEmpty()) {
            AccountActionResult(false, "任务领取失败")
        } else {
            AccountActionResult(true, messages.joinToString("；"))
        }
    }

    private fun session(account: Account): String {
        val credential = account.credential as? Credential.Session
            ?: throw IllegalStateException("账号凭证无法解析")
        if (credential.token.isBlank()) throw IllegalStateException("账号凭证无法解析")
        return credential.token
    }

    private fun headers(token: String): Map<String, String> = buildMap {
        put("token", token)
        put("traceparent", "00-${randomHex(16)}-${randomHex(8)}-01")
        if (loomyVersion.isNotBlank()) put("loomy-version", loomyVersion)
        put("Accept", "application/json")
    }

    private fun JsonObject?.boolean(name: String): Boolean? =
        (this?.get(name) as? JsonPrimitive)?.contentOrNull
            ?.let { it.equals("true", ignoreCase = true) || it == "1" }

    private suspend fun requestOrNull(request: HttpRequest): JsonResponse? = try {
        request(request)
    } catch (_: Exception) {
        null
    }

    private fun taskTitle(code: String): String = when (code) {
        "first_message" -> "发送第一条消息"
        "pick_skill" -> "选择技能"
        "generate_ppt" -> "生成 PPT"
        "set_schedule" -> "设置日程"
        "install_skill" -> "安装技能"
        "configure_remote" -> "配置远程"
        "create_soul" -> "创建 Soul"
        "share_soul" -> "分享 Soul"
        else -> code
    }
}

private fun secureHex(bytes: Int): String {
    val value = ByteArray(bytes).also(SecureRandom()::nextBytes)
    return value.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
