package io.modelbridge.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ScannedGatewayTest {
    @Test
    fun `OpenAI models arrays produce a scanned gateway`() {
        val body = """{"object":"list","data":[{"id":"a"},{"id":"b"},{"id":"c"}]}"""

        val gateway = ScannedGatewayParser.scanned(8080, body)

        assertEquals(ScannedGateway(8080, 3), gateway)
        assertEquals("http://127.0.0.1:8080/v1", gateway!!.baseUrl)
        assertEquals("本机 :8080", gateway.label)
        assertEquals("127.0.0.1:8080（3 个模型）", gateway.display)
    }

    @Test
    fun `bare models array is accepted and non list is rejected`() {
        assertEquals(2, ScannedGatewayParser.modelCount("""{"models":[{"id":"a"},{"id":"b"}]}"""))
        assertEquals(1, ScannedGatewayParser.modelCount("""[{"id":"a"}]"""))
        assertNull(ScannedGatewayParser.modelCount("""{"error":"no"}"""))
        assertNull(ScannedGatewayParser.modelCount("not-json"))
    }

    @Test
    fun `raw HTTP body is separated from headers`() {
        val raw = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n" +
            """{"data":[{"id":"a"}]}"""

        assertEquals(1, ScannedGatewayParser.modelCount(ScannedGatewayParser.responseBody(raw)))
        assertEquals(ScannedGateway(8080, 1),
            ScannedGatewayParser.scanned(8080, ScannedGatewayParser.responseBody(raw)))
    }

    @Test
    fun `base url is normalized to an OpenAI compatible v1 root`() {
        assertEquals("http://127.0.0.1:9000/v1", ScannedGatewayParser.normalizeBaseUrl("127.0.0.1:9000"))
        assertEquals("http://127.0.0.1:9000/v1", ScannedGatewayParser.normalizeBaseUrl("http://127.0.0.1:9000/"))
        assertEquals("http://127.0.0.1:9000/v1", ScannedGatewayParser.normalizeBaseUrl("http://127.0.0.1:9000/v1/"))
        assertEquals("https://example.com/v1", ScannedGatewayParser.normalizeBaseUrl("https://example.com"))
        assertEquals("", ScannedGatewayParser.normalizeBaseUrl("  "))
    }
}
