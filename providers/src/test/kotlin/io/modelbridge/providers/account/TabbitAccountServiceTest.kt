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

class TabbitAccountServiceTest {
    @Test
    fun `quota converts used percentage into remaining plan quota`() = runTest {
        val http = ScriptedHttp()
            .reply(200, """{"usage_percentage":"37.5%","current_cycle_end":"2026-11-30",
                "member_level":"Pro","membership_end_time":"2027-01-01"}""")
        val registry = registry(http)

        val quota = registry.credit(tabbit())

        assertTrue(quota.known)
        assertEquals(62, quota.balance)
        assertEquals(38, quota.entries.single().used)
        assertTrue(quota.detail.contains("Pro"))
        assertTrue(quota.detail.contains("周期至 2026-11-30"))
        val request = http.requests.single()
        assertTrue(request.url.startsWith("https://web.tabbit.com/api/commerce/quota/v1/usage?"))
        assertTrue(request.url.contains("user_id=fixture-user"))
        assertEquals("fixture-cookie", request.headers["Cookie"])
        assertEquals(null, request.headers["Authorization"])
        assertEquals("fixture-ua", request.headers["User-Agent"])
        assertEquals("fixture-ctx", request.headers["X-Req-Ctx"])
    }

    @Test
    fun `checkin skips POST when status says already signed`() = runTest {
        val http = ScriptedHttp().reply(200, """{"results":[{
            "signed_today":true,"signed_days":3,"activity_open":true}]}""")
        val result = registry(http).checkIn(tabbit())

        assertTrue(result.ok)
        assertEquals("今天已签到", result.message)
        assertEquals(1, http.requests.size)
        assertTrue(http.requests.single().url.contains("/sign-in/status"))
    }

    @Test
    fun `checkin posts daily scene and carries upstream usage percentage`() = runTest {
        val http = ScriptedHttp()
            .reply(200, """{"results":[{"signed_today":false,"activity_open":true}]}""")
            .reply(200, """{"usage_percentage":"20%"}""")
            .reply(200, """{"results":[{"sign_in_result":"success"}]}""")
        val registry = registry(http)

        val result = registry.checkIn(tabbit())

        assertTrue(result.ok)
        assertEquals("签到成功", result.message)
        assertEquals(3, http.requests.size)
        val post = http.requests.last()
        assertEquals("POST", post.method)
        assertTrue(post.url.endsWith("/api/commerce/activity/v1/sign-in"))
        val body = post.body!!.toString(StandardCharsets.UTF_8)
        assertTrue(body.contains("\"scene_codes\":[\"daily_sign_in\"]"))
        assertTrue(body.contains("\"usage_percentage\":\"20%\""))
        assertTrue(body.contains("\"request_no\":\"signin_"))
    }

    @Test
    fun `quota failure and unsupported task remain explicit`() = runTest {
        val http = ScriptedHttp().reply(401, """{"message":"expired"}""")
        val registry = registry(http)

        val quota = registry.credit(tabbit())
        val tasks = registry.claimTasks(tabbit())

        assertFalse(quota.known)
        assertEquals("登录态已失效，请重新登录", quota.error)
        assertTrue(tasks.unsupported)
    }

    private fun registry(http: ScriptedHttp) = AccountServiceRegistry(
        services = listOf(
            TabbitAccountService(
                http = http,
                material = TabbitRequestMaterial(
                    userAgent = "fixture-ua",
                    requestContext = "fixture-ctx",
                    deviceId = "fixture-device",
                ),
                randomId = { "fixture-id" },
                clockMillis = { NOW },
            ),
        ),
        clockMillis = { NOW },
    )

    private fun tabbit() = Account(
        id = "tabbit-1",
        providerId = "tabbit",
        label = "Tabbit",
        credential = Credential.Cookies("fixture-cookie"),
        authUid = "fixture-user",
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
