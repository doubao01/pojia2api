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

class BearerCreditServicesTest {
    @Test
    fun `raccoon parses available points aliases and sends bearer headers`() = runTest {
        val http = ScriptedHttp().reply(200, """{"data":{"availablePoints":1234,"ignored":7}}""")
        val registry = registry(http)

        val quota = registry.credit(account("raccoon"))

        assertTrue(quota.known)
        assertEquals(1234, quota.balance)
        assertEquals(QuotaKind.Points, quota.entries.single().kind)
        val request = http.requests.single()
        assertEquals("https://xiaohuanxiong.com/api/web/points/v1/balance", request.url)
        assertEquals("Bearer fixture-access", request.headers["Authorization"])
        assertEquals("fixture-org", request.headers["X-Org-Code"])
        assertEquals("fixture-ua", request.headers["User-Agent"])
    }

    @Test
    fun `raccoon reports missing fields instead of inventing zero`() = runTest {
        val http = ScriptedHttp().reply(200, """{"data":{"other":1}}""")
        val quota = registry(http).credit(account("raccoon"))

        assertFalse(quota.known)
        assertEquals("响应里没有积分字段", quota.error)
    }

    @Test
    fun `catpaw uses X-Auth-Token and parses availableCredits`() = runTest {
        val http = ScriptedHttp().reply(200, """{"code":0,"data":{
            "availableCredits":88.9,"userPlan":{"planName":"Pro"}}}""")
        val registry = registry(http)

        val quota = registry.credit(account("catpaw"))

        assertTrue(quota.known)
        assertEquals(88, quota.balance)
        assertTrue(quota.detail.contains("Pro"))
        val request = http.requests.single()
        assertEquals("https://catx.nocode.cn/api/gateway/credit/balance", request.url)
        assertEquals("fixture-access", request.headers["X-Auth-Token"])
        assertEquals("new-agent-sdk", request.headers["gray-set"])
        assertEquals(null, request.headers["Authorization"])
    }

    @Test
    fun `catpaw expired session and unsupported action remain explicit`() = runTest {
        val http = ScriptedHttp().reply(200, """{"code":4010,"message":"expired"}""")
        val registry = registry(http)
        val account = account("catpaw")

        val quota = registry.credit(account)
        val checkIn = registry.checkIn(account)

        assertFalse(quota.known)
        assertEquals("凭证已失效，请重新登录", quota.error)
        assertTrue(checkIn.unsupported)
    }

    private fun registry(http: ScriptedHttp) = AccountServiceRegistry(
        services = listOf(
            RaccoonAccountService(http, userAgent = "fixture-ua", orgCode = "fixture-org", clockMillis = { NOW }),
            CatpawAccountService(http, clockMillis = { NOW }),
        ),
        clockMillis = { NOW },
    )

    private fun account(providerId: String) = Account(
        id = "$providerId-1",
        providerId = providerId,
        label = "fixture-account",
        credential = Credential.OAuth("fixture-access", "fixture-refresh", NOW + 3_600_000),
        authExpiresAtMillis = NOW + 3_600_000,
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
