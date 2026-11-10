package io.modelbridge.providers.account

import io.modelbridge.core.Account
import io.modelbridge.spi.Credential
import io.modelbridge.spi.HttpClient
import io.modelbridge.spi.HttpRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 供应商账号业务能力，按原版 CREDIT_REFRESH / CHECKIN / TASKS / POINTS_BILLS 建模。 */
enum class AccountCapability { CreditRefresh, CheckIn, Tasks, PointsBills }

/** 动作结果；[unsupported] 表示供应商明确不支持，而非查询失败。 */
data class AccountActionResult(
    val ok: Boolean,
    val message: String,
    val unsupported: Boolean = false,
)

/** 原版 ProviderTask 的等价数据；奖励与进度为 0 时 UI 不伪造数字。 */
data class ProviderTask(
    val code: String,
    val title: String,
    val description: String = "",
    val current: Long = 0,
    val target: Long = 0,
    val status: String = "",
    val locked: Boolean = false,
    val claimed: Boolean = false,
    val rewardCredit: Long = 0,
) {
    val displayTitle: String get() = title.ifBlank { code }
    val stateLabel: String
        get() = when {
            claimed -> "已领取"
            !locked && target > 0 && current >= target -> "可领取"
            locked -> "未解锁"
            target > 0 -> "$current/$target"
            status.isNotBlank() -> status
            else -> "未完成"
        }
}

data class ProviderTaskList(
    val tasks: List<ProviderTask> = emptyList(),
    val inPeriod: Boolean = false,
    val error: String = "",
)

/** 额度分类，对应 3.10.0 的套餐 / 共享 / 加购额度和可用积分。 */
enum class QuotaKind(val label: String) { Plan("套餐额度"), Shared("共享额度"), AddOn("加购额度"), Points("可用积分"), Other("其它额度") }

data class QuotaEntry(
    val kind: QuotaKind,
    val label: String,
    val used: Long = 0,
    val remaining: Long = 0,
    val total: Long = 0,
    val detail: String = "",
)

data class AccountQuota(
    val entries: List<QuotaEntry> = emptyList(),
    val detail: String = "",
    val known: Boolean = false,
    val error: String = "",
    val refreshedAtMillis: Long = 0,
) {
    val balance: Long get() = entries.filterNot { it.kind == QuotaKind.Points }.sumOf { it.remaining } +
        entries.filter { it.kind == QuotaKind.Points }.sumOf { it.remaining }
}

/** 单个供应商账号的业务接口；所有方法只使用调用时传入的账号，不缓存凭据。 */
interface ProviderAccountService {
    val providerId: String
    val capabilities: Set<AccountCapability>

    suspend fun credit(account: Account): AccountQuota
    suspend fun checkIn(account: Account): AccountActionResult
    suspend fun claimTasks(account: Account): AccountActionResult
    suspend fun tasks(account: Account): ProviderTaskList

    suspend fun unsupported(action: String): AccountActionResult =
        AccountActionResult(false, "该供应商不支持「$action」", unsupported = true)
}

/** 账号业务总入口：注册哪些供应商，就给哪些供应商展示真实查询能力。 */
class AccountServiceRegistry(
    services: List<ProviderAccountService>,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    private val byProvider = services.associateBy { it.providerId }

    fun capabilities(providerId: String): Set<AccountCapability> =
        byProvider[providerId]?.capabilities ?: emptySet()

    fun supports(providerId: String, capability: AccountCapability): Boolean =
        capability in capabilities(providerId)

    suspend fun credit(account: Account): AccountQuota {
        val service = byProvider[account.providerId] ?: return AccountQuota(
            error = "该供应商未接入额度查询",
            refreshedAtMillis = clockMillis(),
        )
        return runCatching { service.credit(account) }
            .getOrElse { AccountQuota(error = it.message ?: "额度查询失败", refreshedAtMillis = clockMillis()) }
            .let { if (it.refreshedAtMillis > 0) it else it.copy(refreshedAtMillis = clockMillis()) }
    }

    suspend fun checkIn(account: Account): AccountActionResult =
        runAction(account, AccountCapability.CheckIn) { service, item -> service.checkIn(item) }

    suspend fun claimTasks(account: Account): AccountActionResult =
        runAction(account, AccountCapability.Tasks) { service, item -> service.claimTasks(item) }

    suspend fun tasks(account: Account): ProviderTaskList {
        val service = byProvider[account.providerId]
            ?: return ProviderTaskList(error = "该供应商未接入任务列表")
        if (AccountCapability.Tasks !in service.capabilities) {
            return ProviderTaskList(error = "该供应商不支持任务列表")
        }
        return runCatching { service.tasks(account) }
            .getOrElse { ProviderTaskList(error = it.message ?: "任务列表拉取失败") }
    }

    private suspend fun runAction(
        account: Account,
        capability: AccountCapability,
        block: suspend (ProviderAccountService, Account) -> AccountActionResult,
    ): AccountActionResult {
        val service = byProvider[account.providerId]
            ?: return AccountActionResult(false, "该供应商未接入${actionLabel(capability)}")
        if (capability !in service.capabilities) {
            return AccountActionResult(false, "该供应商不支持「${actionLabel(capability)}」", unsupported = true)
        }
        return runCatching { block(service, account) }
            .getOrElse { AccountActionResult(false, it.message ?: "${actionLabel(capability)}失败") }
    }

    private fun actionLabel(capability: AccountCapability): String = when (capability) {
        AccountCapability.CheckIn -> "签到"
        AccountCapability.Tasks -> "任务"
        else -> "该操作"
    }
}

/** 供应商任务/额度 HTTP 适配器的共同请求与 JSON 工具。 */
abstract class HttpProviderAccountService(
    override val providerId: String,
    protected val http: HttpClient,
    override val capabilities: Set<AccountCapability>,
    protected val clockMillis: () -> Long = System::currentTimeMillis,
) : ProviderAccountService {
    protected val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    override suspend fun checkIn(account: Account): AccountActionResult = unsupported("签到")
    override suspend fun claimTasks(account: Account): AccountActionResult = unsupported("任务")
    override suspend fun tasks(account: Account): ProviderTaskList =
        ProviderTaskList(error = "该供应商未提供任务列表")
    override suspend fun credit(account: Account): AccountQuota = AccountQuota(
        error = "该供应商未提供额度查询",
        refreshedAtMillis = clockMillis(),
    )

    protected suspend fun request(request: HttpRequest): JsonResponse {
        val response = http.execute(request)
        val bytes = response.bodyBytes()
        val text = if (bytes.size <= 1_048_576) bytes.toString(StandardCharsets.UTF_8) else ""
        val parsed = runCatching {
            json.parseToJsonElement(text).takeIf { it is JsonObject } as? JsonObject
        }.getOrNull()
        return JsonResponse(response.statusCode, text, parsed)
    }

    protected fun bearer(account: Account): String {
        val credential = account.credential as? Credential.OAuth
            ?: throw IllegalStateException("凭证无法解析")
        if (credential.accessToken.isBlank()) throw IllegalStateException("凭证无法解析")
        return credential.accessToken
    }

    protected fun failedQuota(message: String): AccountQuota = AccountQuota(
        error = message,
        refreshedAtMillis = clockMillis(),
    )

    protected fun httpError(prefix: String, response: JsonResponse): String =
        "$prefix（HTTP ${response.status}）"

    protected fun JsonObject?.text(vararg names: String): String =
        names.asSequence().mapNotNull { name ->
            (this?.get(name) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        }.firstOrNull().orEmpty()

    protected fun JsonObject?.long(vararg names: String): Long? =
        names.asSequence().mapNotNull { name ->
            (this?.get(name) as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toLong()
        }.firstOrNull()

    protected fun JsonObject?.double(vararg names: String): Double? =
        names.asSequence().mapNotNull { name ->
            (this?.get(name) as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
        }.firstOrNull()

    protected fun JsonObject?.array(name: String): JsonArray? = this?.get(name) as? JsonArray
    protected fun JsonObject?.obj(name: String): JsonObject? = this?.get(name) as? JsonObject
    protected fun JsonObject?.boolean(vararg names: String): Boolean? =
        names.asSequence().mapNotNull { name ->
            (this?.get(name) as? JsonPrimitive)?.contentOrNull
                ?.let { it.equals("true", ignoreCase = true) || it == "1" }
        }.firstOrNull()

    protected fun jsonBody(fields: Map<String, JsonElement>): ByteArray =
        JsonObject(fields).toString().toByteArray(StandardCharsets.UTF_8)

    protected fun string(value: String): JsonPrimitive = JsonPrimitive(value)
    protected fun number(value: Long): JsonPrimitive = JsonPrimitive(value)
    protected fun numbers(values: List<Long>): JsonArray =
        JsonArray(values.map { JsonPrimitive(it) })

    /** 原版 `n/d.java P()`：查询有效期覆盖当前时刻起的资源包。 */
    protected fun resourceRequestBody(nowMillis: Long): ByteArray {
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val begin = format.format(Date(nowMillis))
        val end = format.format(Date(nowMillis + 3_185_136_000_000L))
        return jsonBody(mapOf(
            "PageNumber" to number(1),
            "PageSize" to number(100),
            "ProductCode" to string("p_tcaca"),
            "Status" to numbers(listOf(0, 3)),
            "PackageEndTimeRangeBegin" to string(begin),
            "PackageEndTimeRangeEnd" to string(end),
        ))
    }

    protected fun quotaNumbers(obj: JsonObject?): Triple<Long?, Long?, Long?> = Triple(
        obj.long("cap", "total", "limit", "CycleCapacitySize"),
        obj.long("remaining", "remain", "available_credits", "available", "CycleCapacityRemain", "CapacityRemain"),
        obj.long("used", "consumed"),
    )

    protected fun normalizedRegion(account: Account): String {
        val region = account.authRegion.trim().lowercase(Locale.ROOT)
        if (region == "global") return "global"
        val domain = account.authDomain.trim().lowercase(Locale.ROOT)
        if (domain.contains("workbuddy.ai") || domain.contains("codebuddy.ai")) return "global"
        return "cn"
    }

    protected fun errorMessage(response: JsonResponse): String {
        val root = response.json ?: return ""
        val data = root.obj("data")
        for (key in listOf("msg", "message", "error")) {
            val value = (root[key] as? JsonPrimitive)?.contentOrNull
                ?: (data?.get(key) as? JsonPrimitive)?.contentOrNull
            if (!value.isNullOrBlank()) return value
        }
        return ""
    }

    protected data class JsonResponse(val status: Int, val raw: String, val json: JsonObject?)
}
