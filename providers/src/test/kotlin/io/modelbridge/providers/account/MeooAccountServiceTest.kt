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

class MeooAccountServiceTest {
    @Test
    fun `credit parses account balance plan and earliest grant expiry`() = runTest {
        val http = ScriptedHttp().reply(200, """{"data":{
            "planCode":"Pro","userPointInfo":{"account":{"availableBalance":456},
            "activeGrants":[{"grantDetails":[{"expiresDate":"2026-12-01"},
                {"expiresDate":"2026-11-20"},{"expiresDate":"9999-12-31"}]}]}}}""")
        val registry = registry(http)

        val quota = registry.credit(meoo())

        assertTrue(quota.known)
        assertEquals(456, quota.balance)
        assertTrue(quota.detail.contains("Pro"))
        assertTrue(quota.detail.contains("2026-11-20 到期"))
        val request = http.requests.single()
        assertEquals("https://meoo.com/api/mc/entitlement/get", request.url)
        assertEquals("fixture-cookie", request.headers["Cookie"])
        assertEquals("fixture-ua", request.headers["User-Agent"])
    }

    @Test
    fun `checkin handles fresh grant already-granted and failure reasons`() = runTest {
        val http = ScriptedHttp()
            .reply(200, """{"data":{"dailyLoginGranted":true,"dailyLoginGrantAmount":30}}""")
            .reply(200, """{"data":{"dailyLoginGranted":false,
                "dailyLoginSkipReason":"ALREADY_GRANTED_TODAY"}}""")
            .reply(200, """{"data":{"dailyLoginGranted":false,
                "dailyLoginSkipReason":"RISK_BLOCKED"}}""")
        val registry = registry(http)

        val first = registry.checkIn(meoo())
        val second = registry.checkIn(meoo())
        val third = registry.checkIn(meoo())

        assertEquals("领取成功，获得 30 积分", first.message)
        assertTrue(first.ok)
        assertEquals("今天已经领取过啦", second.message)
        assertTrue(second.ok)
        assertFalse(third.ok)
        assertEquals("RISK_BLOCKED", third.message)
        assertTrue(http.requests.all { it.url.endsWith("/api/mc/entitlement/init") })
    }

    @Test
    fun `expired cookie and unsupported tasks remain explicit`() = runTest {
        val http = ScriptedHttp().reply(401, """{"message":"expired"}""")
        val registry = registry(http)
        val account = meoo()

        val quota = registry.credit(account)
        val tasks = registry.claimTasks(account)

        assertFalse(quota.known)
        assertEquals("登录态已失效，请重新登录", quota.error)
        assertTrue(tasks.unsupported)
    }

    private fun registry(http: ScriptedHttp) = AccountServiceRegistry(
        services = listOf(MeooAccountService(http, userAgent = "fixture-ua", clockMillis = { NOW })),
        clockMillis = { NOW },
    )

    private fun meoo() = Account(
        id = "meoo-1",
        providerId = "meoo",
        label = "Meoo",
        credential = Credential.Cookies("fixture-cookie"),
    )

    private class ScriptedHttp : HttpClient {
        val requests = mutableListOf<HttpRequest>()
        private val scripts = ArrayDeque<Pair<Int, String>>()

        fun reply(status: Int, body: String): ScriptedHttp = apply { scripts.addLast(status to body) }

        override suspend fun execute(request: HttpRequest): HttpResponse {
            requests += request
            check(scripts.isNotEmpty()) { "Unexpected HTTP call: ${request.url}" }
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
