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

class TraeAccountServiceTest {
    @Test
    fun `credit parses entitlement packs from cn response`() = runTest {
        val http = ScriptedHttp().reply(200, """{"data":{"user_entitlement_pack_list":[
            {"group_name":"套餐","display_desc":"月度包",
             "entitlement_base_info":{"quota":{"credits_limit":1000},"end_time":1790000000},
             "usage":{"credits_amount":125.9}},
            {"entitlement_base_info":{"quota":{"credits_limit":200},
             "product_extra":{"package_extra":{"package_name":"加购包"}}},
             "usage":{"credits_amount":50}}
        ]}}""")
        val registry = registry(http)

        val quota = registry.credit(trae())

        assertTrue(quota.known)
        assertEquals(2, quota.entries.size)
        assertEquals(875, quota.entries[0].remaining)
        assertEquals("月度包", quota.entries[0].label)
        assertEquals("加购包", quota.entries[1].label)
        assertEquals("权益包剩余 1025 / 1200", quota.detail)
        val request = http.requests.single()
        assertEquals("https://api.trae.cn/trae/api/v2/pay/web_user_ent_usage", request.url)
        assertEquals("Cloud-IDE-JWT fixture-access", request.headers["Authorization"])
        assertEquals("CN", request.headers["X-User-Region"])
        assertEquals("fixture-user", request.headers["X-Uid"])
        assertEquals("fixture-device", request.headers["x-device-id"])
        assertEquals("""{"require_usage":true}""", request.body!!.toString(StandardCharsets.UTF_8))
    }

    @Test
    fun `global credit uses v1 endpoints and US region header`() = runTest {
        val http = ScriptedHttp().reply(200, """{"result":{"user_entitlement_pack_list":[
            {"entitlement_base_info":{"quota":{"credits_limit":500}},
             "usage":{"credits_amount":100}}
        ]}}""")
        val registry = registry(http)

        val quota = registry.credit(trae().copy(authRegion = "global"))

        assertTrue(quota.known)
        assertEquals(400, quota.balance)
        assertEquals("https://ug-normal.trae.ai/trae/api/v1/pay/user_current_entitlement_list", http.requests.single().url)
        assertEquals("US", http.requests.single().headers["X-User-Region"])
    }

    @Test
    fun `checkin skips claim when status already checked and handles fresh success`() = runTest {
        val http = ScriptedHttp()
            .reply(200, """{"data":{"checked_in":true,"credits":20,"enable":true}}""")
            .reply(200, """{"data":{"checked_in":false,"credits":30,"enabled":true}}""")
            .reply(200, """{"code":0,"success":true,"status":"success"}""")
        val registry = registry(http)

        val already = registry.checkIn(trae())
        val fresh = registry.checkIn(trae())

        assertEquals("今日已签到", already.message)
        assertEquals("签到成功", fresh.message)
        assertEquals(3, http.requests.size)
        assertTrue(http.requests[0].url.endsWith("/checkin_credits/status"))
        assertTrue(http.requests[2].url.endsWith("/checkin_credits/claim"))
    }

    @Test
    fun `global checkin and unsupported tasks stay explicit`() = runTest {
        val http = ScriptedHttp()
        val registry = registry(http)
        val global = trae().copy(authRegion = "global")

        val checkIn = registry.checkIn(global)
        val tasks = registry.claimTasks(trae())

        assertTrue(checkIn.unsupported)
        assertTrue(tasks.unsupported)
        assertTrue(http.requests.isEmpty())
    }

    private fun registry(http: ScriptedHttp) = AccountServiceRegistry(
        services = listOf(
            TraeAccountService(
                http = http,
                material = TraeRequestMaterial(
                    appVersion = "fixture-version",
                    deviceBrand = "fixture-brand",
                    osVersion = "fixture-os",
                    deviceId = "fixture-device",
                ),
                clockMillis = { NOW },
            ),
        ),
        clockMillis = { NOW },
    )

    private fun trae() = Account(
        id = "trae-1",
        providerId = "trae",
        label = "Trae",
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
