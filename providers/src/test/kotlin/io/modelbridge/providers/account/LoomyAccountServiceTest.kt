package io.modelbridge.providers.account

import io.modelbridge.core.Account
import io.modelbridge.spi.Credential
import io.modelbridge.spi.HttpClient
import io.modelbridge.spi.HttpRequest
import io.modelbridge.spi.HttpResponse
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque

class LoomyAccountServiceTest {
    @Test
    fun `credit combines personal daily and team balances`() = runTest {
        val http = ScriptedHttp()
            .reply(200, """{"data":{"balance":300,"dailyRemainingPoints":80,
                "dailyLimitPoints":100,"dailyConsumedPoints":20}}""")
            .reply(200, """{"data":{"currentBalance":420}}""")
        val registry = registry(http)

        val quota = registry.credit(loomy())

        assertTrue(quota.known)
        assertEquals(500, quota.balance)
        assertEquals(QuotaKind.Points, quota.entries[0].kind)
        assertEquals("团队积分", quota.entries[0].label)
        assertEquals(420, quota.entries[0].remaining)
        assertEquals(80, quota.entries[1].remaining)
        assertEquals(2, http.requests.size)
        assertEquals("fixture-session", http.requests[0].headers["token"])
        assertTrue(http.requests[0].url.endsWith("/api/v1/points/records?pageNo=1&pageSize=20&recordType=all"))
        assertTrue(http.requests[1].url.endsWith("/api/v1/team-points/balance"))
    }

    @Test
    fun `tasks map fixed onboarding rewards without inventing completion`() = runTest {
        val http = ScriptedHttp().reply(200, """{"data":{"tasks":{
            "first_message":true,"generate_ppt":false
        }}}""")
        val registry = registry(http)

        val result = registry.tasks(loomy())

        assertEquals(8, result.tasks.size)
        assertEquals("已领取", result.tasks[0].stateLabel)
        assertEquals(500, result.tasks[0].rewardCredit)
        assertEquals("0/1", result.tasks[2].let { "${it.current}/${it.target}" })
        assertEquals("未完成", result.tasks[2].status)
        assertTrue(http.requests.single().url.endsWith("/api/v1/onboarding/tasks"))
    }

    @Test
    fun `claim tasks posts only incomplete keys and reports upstream state`() = runTest {
        val http = ScriptedHttp()
            .reply(200, """{"data":{"tasks":{"first_message":true}}}""")
            .reply(200, """{"data":{"balance":600,"alreadyCompleted":false}}""")
            .reply(200, """{"data":{"balance":2100,"alreadyCompleted":true}}""")
        val registry = registry(http)

        val result = registry.claimTasks(loomy())

        assertTrue(result.ok)
        assertTrue(result.message.contains("选择技能：已完成"))
        assertTrue(result.message.contains("生成 PPT：此前已完成"))
        val completeRequests = http.requests.drop(1).take(2)
        assertTrue(completeRequests.all { it.method == "POST" && it.url.endsWith("/api/v1/onboarding/tasks/complete") })
        assertEquals(
            """{"key":"pick_skill"}""",
            completeRequests[0].body!!.toString(StandardCharsets.UTF_8),
        )
    }

    @Test
    fun `unsupported checkin and failed quota remain explicit`() = runTest {
        val http = ScriptedHttp().reply(500, """{"msg":"fixture failure"}""")
        val registry = registry(http)

        val checkIn = registry.checkIn(loomy())
        val quota = registry.credit(loomy())

        assertTrue(checkIn.unsupported)
        assertFalse(quota.known)
        assertEquals("积分查询失败（HTTP 500）", quota.error)
    }

    private fun registry(http: ScriptedHttp) = AccountServiceRegistry(
        services = listOf(LoomyAccountService(http, loomyVersion = "fixture-version", randomHex = { length -> "a".repeat(length * 2) }, clockMillis = { NOW })),
        clockMillis = { NOW },
    )

    private fun loomy() = Account(
        id = "loomy-1",
        providerId = "loomy",
        label = "Loomy",
        credential = Credential.Session("fixture-phone", "fixture-session"),
        authUid = "fixture-user",
        authExpiresAtMillis = NOW + 1_209_600_000,
    )

    private class ScriptedHttp : HttpClient {
        val requests = mutableListOf<HttpRequest>()
        private val scripts = ArrayDeque<Pair<Int, String>>()

        fun reply(status: Int, body: String): ScriptedHttp = apply { scripts.addLast(status to body) }

        override suspend fun execute(request: HttpRequest): HttpResponse {
            requests += request
            val (status, body) = if (scripts.isNotEmpty()) scripts.removeFirst() else 200 to "{}"
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
