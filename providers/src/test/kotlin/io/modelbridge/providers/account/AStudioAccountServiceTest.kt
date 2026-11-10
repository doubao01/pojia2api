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

class AStudioAccountServiceTest {
    @Test
    fun `credit combines points spark balance and membership plan`() = runTest {
        val http = ScriptedHttp()
            .reply(200, """{"data":{"totalBalance":600,"sparkTotalBalance":40}}""")
            .reply(200, """{"data":{"planCode":"pro","planName":"专业版","active":true}}""")
        val registry = registry(http)

        val quota = registry.credit(astudio())

        assertTrue(quota.known)
        assertEquals(640, quota.balance)
        assertEquals(2, quota.entries.size)
        assertTrue(quota.detail.contains("专业版"))
        assertTrue(quota.detail.contains("星火积分 40"))
        val request = http.requests.first()
        assertEquals("POST", request.method)
        assertTrue(request.url.endsWith("/points/balance"))
        assertEquals("Bearer fixture-key", request.headers["Authorization"])
        assertEquals("fixture-client", request.headers["clientType"])
        assertEquals("fixture-user", request.headers["uid"])
        assertTrue(http.requests.last().url.endsWith("/membership/me"))
    }

    @Test
    fun `missing balance and unsupported checkin stay explicit`() = runTest {
        val http = ScriptedHttp().reply(200, """{"data":{"other":1}}""")
        val registry = registry(http)
        val account = astudio()

        val quota = registry.credit(account)
        val checkIn = registry.checkIn(account)

        assertFalse(quota.known)
        assertEquals("上游未返回积分余额", quota.error)
        assertTrue(checkIn.unsupported)
    }

    private fun registry(http: ScriptedHttp) = AccountServiceRegistry(
        services = listOf(
            AStudioAccountService(
                http = http,
                material = AStudioRequestMaterial(
                    clientType = "fixture-client",
                    studioVersion = "fixture-version",
                    userAgent = "fixture-ua",
                ),
                clockMillis = { NOW },
            ),
        ),
        clockMillis = { NOW },
    )

    private fun astudio() = Account(
        id = "astudio-1",
        providerId = "astudio",
        label = "AStudio",
        credential = Credential.ApiKey("fixture-key"),
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
