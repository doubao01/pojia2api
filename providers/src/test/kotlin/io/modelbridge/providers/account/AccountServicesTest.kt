package io.modelbridge.providers.account

import io.modelbridge.core.Account
import io.modelbridge.core.InMemoryAccountStore
import io.modelbridge.spi.Credential
import io.modelbridge.spi.HttpClient
import io.modelbridge.spi.HttpRequest
import io.modelbridge.spi.HttpResponse
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque

class AccountServicesTest {
    @Test
    fun `workbuddy credit parses real quota fields and sends authenticated POST`() = runTest {
        val http = ScriptedHttp().reply(200, """{"code":0,"data":{"Response":{"Data":{"Accounts":[
            {"CycleCapacitySize":1000,"CycleCapacityRemain":640},
            {"CycleCapacitySize":200,"CapacityRemain":60}
        ]}}}}""")
        val account = workbuddy().copy(authTenantId = "fixture-tenant")
        val registry = registry(http)

        val quota = registry.credit(account)

        assertTrue(quota.known)
        assertEquals(2, quota.entries.size)
        assertEquals(700, quota.balance)
        assertEquals("周期剩余 700", quota.detail)
        val request = http.requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://www.codebuddy.cn/v2/billing/meter/get-user-resource", request.url)
        assertEquals("Bearer fixture-access", request.headers["Authorization"])
        assertEquals("fixture-user", request.headers["X-User-Id"])
        assertEquals("fixture-tenant", request.headers["X-Enterprise-Id"])
        assertEquals("www.codebuddy.cn", request.headers["X-Domain"])
        assertFalse(request.body!!.toString(StandardCharsets.UTF_8).contains("fixture-access"))
    }

    @Test
    fun `workbuddy tasks map progress state and point rewards`() = runTest {
        val http = ScriptedHttp().reply(200, """{"in_period":1,"tasks":[
            {"task_code":"daily_signin","title":"每日签到","current":3,"target":3,
             "accept_status":"claimable","reward_credit":20},
            {"task_code":"invite","title":"邀请好友","progress":{"current":1,"target":5},
             "accept_status":"claimed","reward_credit":50}
        ]}""")
        val registry = registry(http)

        val result = registry.tasks(workbuddy())

        assertTrue(result.inPeriod)
        assertEquals(2, result.tasks.size)
        assertEquals("每日签到", result.tasks[0].displayTitle)
        assertEquals("可领取", result.tasks[0].stateLabel)
        assertEquals(20, result.tasks[0].rewardCredit)
        assertEquals("已领取", result.tasks[1].stateLabel)
        assertEquals("1/5", result.tasks[1].current.let { "1/5" })
        assertEquals("https://copilot.tencent.com/v2/activity/growth/tasks", http.requests.single().url)
    }

    @Test
    fun `workbuddy checkin handles fresh success and already-signed responses`() = runTest {
        val http = ScriptedHttp()
            .reply(200, """{"code":0,"msg":"ok"}""")
            .reply(200, """{"code":1,"msg":"今日已签到"}""")
        val registry = registry(http)
        val account = workbuddy()

        val first = registry.checkIn(account)
        val second = registry.checkIn(account)

        assertTrue(first.ok)
        assertEquals("签到成功", first.message)
        assertTrue(second.ok)
        assertEquals("今日已签到", second.message)
        assertEquals(2, http.requests.size)
        assertTrue(http.requests.all { it.url.endsWith("/v2/billing/meter/daily-checkin") })
    }

    @Test
    fun `workbuddy global and enterprise accounts expose explicit unsupported tasks`() = runTest {
        val http = ScriptedHttp()
        val registry = registry(http)
        val global = workbuddy().copy(authRegion = "global")
        val enterprise = workbuddy()

        assertTrue(registry.checkIn(global).unsupported)
        assertEquals("国际版没有成长任务", registry.tasks(global).error)
        assertTrue(registry.claimTasks(enterprise.copy(authTenantId = "tenant")).unsupported)
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun `workbuddy claim tasks only succeeds on a real reward response`() = runTest {
        val http = ScriptedHttp()
            .reply(200, """{"code":0,"credit":25}""")
            .reply(200, """{"code":1,"msg":"已领取"}""")
            .reply(200, """{"tasks":[]}""")
        val registry = registry(http)

        val result = registry.claimTasks(workbuddy())

        assertTrue(result.ok)
        assertEquals("已处理：新手礼包 +25", result.message)
        assertTrue(http.requests.take(2).all { it.method == "POST" && it.url.contains("claim-") })
        assertEquals(
            "https://copilot.tencent.com/v2/activity/growth/tasks",
            http.requests.last().url,
        )
    }

    @Test
    fun `qoder quota maps plan addon and shared fields`() = runTest {
        val http = ScriptedHttp().reply(200, """{"data":{
            "userQuota":{"cap":1000,"remaining":600,"used":400},
            "addOnQuota":{"total":200,"used":50},
            "sharedQuota":{"cap":300,"remain":180,"used":120},
            "totalUsagePercentage":0.42,
            "plan":{"plan_tier_name":"Pro"}
        }}""")
        val registry = registry(http)

        val quota = registry.credit(qoder())

        assertTrue(quota.known)
        assertEquals(3, quota.entries.size)
        assertEquals(930, quota.balance)
        assertEquals(QuotaKind.Plan, quota.entries[0].kind)
        assertEquals(QuotaKind.AddOn, quota.entries[1].kind)
        assertEquals(QuotaKind.Shared, quota.entries[2].kind)
        assertTrue(quota.detail.contains("Pro"))
        assertTrue(quota.detail.contains("已用 42%"))
        assertEquals("https://openapi.qoder.com.cn/api/v2/quota/usage", http.requests.single().url)
    }

    @Test
    fun `qoder unsupported quota response and unregistered action stay honest`() = runTest {
        val http = ScriptedHttp().reply(200, """{"data":{}}""")
        val registry = registry(http)
        val account = qoder()

        val quota = registry.credit(account)
        val checkIn = registry.checkIn(account)
        val tasks = registry.tasks(account)

        assertFalse(quota.known)
        assertEquals("上游未返回可解析额度", quota.error)
        assertTrue(checkIn.unsupported)
        assertEquals("该供应商不支持任务列表", tasks.error)
    }

    @Test
    fun `registry isolates upstream failures and records a refresh timestamp`() = runTest {
        val http = ScriptedHttp().reply(500, """{"msg":"upstream fixture"}""")
        val registry = AccountServiceRegistry(
            services = listOf(WorkBuddyAccountService(http, { NOW }), QoderAccountService(http, { NOW })),
            clockMillis = { NOW },
        )

        val quota = registry.credit(workbuddy())

        assertFalse(quota.known)
        assertEquals("额度查询失败（HTTP 500）：upstream fixture", quota.error)
        assertEquals(NOW, quota.refreshedAtMillis)
    }

    private fun registry(http: ScriptedHttp) = AccountServiceRegistry(
        services = listOf(WorkBuddyAccountService(http, { NOW }), QoderAccountService(http, { NOW })),
        clockMillis = { NOW },
    )

    private fun workbuddy() = Account(
        id = "codebuddy-1",
        providerId = "codebuddy",
        label = "fixture-name",
        credential = Credential.OAuth("fixture-access", "fixture-refresh", NOW + 3_600_000),
        authRegion = "cn",
        authUid = "fixture-user",
        authDomain = "www.codebuddy.cn",
        authExpiresAtMillis = NOW + 3_600_000,
    )

    private fun qoder() = Account(
        id = "qoder-1",
        providerId = "qoder",
        label = "fixture-name",
        credential = Credential.OAuth("fixture-access", "fixture-refresh", NOW + 3_600_000),
        authRegion = "cn",
        authUid = "fixture-user",
        authExpiresAtMillis = NOW + 3_600_000,
    )

    private class ScriptedHttp : HttpClient {
        val requests = mutableListOf<HttpRequest>()
        private val scripts = ArrayDeque<Pair<Int, String>>()

        fun reply(status: Int, body: String): ScriptedHttp = apply { scripts.addLast(status to body) }

        override suspend fun execute(request: HttpRequest): HttpResponse {
            requests += request
            check(scripts.isNotEmpty()) { "Unexpected account-service HTTP call" }
            val (status, body) = scripts.removeFirst()
            return object : HttpResponse {
                override val statusCode = status
                override val headers: Map<String, String> = emptyMap()
                override suspend fun bodyBytes() = body.toByteArray(StandardCharsets.UTF_8)
                override fun bodyLines() = body.lineSequence()
            }
        }
    }

    private companion object {
        const val NOW = 2_000_000_000_000L
    }
}
