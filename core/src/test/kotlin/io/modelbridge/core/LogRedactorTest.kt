package io.modelbridge.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LogRedactorTest {
    @Test
    fun `bearer tokens and cookie headers are redacted`() {
        val raw = "上游失败 Authorization: Bearer abcdefgh12345678; Cookie: sid=secret; other=ok"

        val value = LogRedactor.redact(raw)

        assertFalse(value!!.contains("abcdefgh12345678"))
        assertFalse(value.contains("sid=secret"))
        assertTrue(value.lowercase().contains("authorization=<redacted>"))
        assertTrue(value.contains("Cookie: <redacted>"))
        assertTrue(value.contains("other=ok"))
    }

    @Test
    fun `json credential and prompt fields are redacted without losing status`() {
        val raw = """HTTP 401 {"accessToken":"eyJhbGciOi","content":"策略正文","messages":[{"role":"user"}]}"""

        val value = LogRedactor.redact(raw)

        assertTrue(value!!.startsWith("HTTP 401"))
        assertFalse(value.contains("eyJhbGciOi"))
        assertFalse(value.contains("策略正文"))
        assertTrue(value.contains("accessToken=<redacted>"))
        assertTrue(value.contains("content=<redacted>"))
        assertTrue(value.contains("messages=<redacted>"))
    }

    @Test
    fun `protocol injection fields and escaped json strings are redacted`() {
        val raw = """上游失败 {"instructions":"你是内部助手","system":"secret system","input":"say \"keep this secret\"","token":"abc.def.ghi"} sid=abcd1234"""

        val value = LogRedactor.redact(raw)

        assertFalse(value!!.contains("你是内部助手"))
        assertFalse(value.contains("secret system"))
        assertFalse(value.contains("keep this secret"))
        assertFalse(value.contains("abc.def.ghi"))
        assertFalse(value.contains("sid=abcd1234"))
        assertTrue(value.contains("instructions=<redacted>"))
        assertTrue(value.contains("system=<redacted>"))
        assertTrue(value.contains("input=<redacted>"))
        assertTrue(value.contains("token=<redacted>"))
        assertTrue(value.contains("sid=<redacted>"))
        assertTrue(value.startsWith("上游失败"))
    }

    @Test
    fun `structured protocol fields are redacted as whole values`() {
        val raw = """HTTP 400 {"messages":[{"role":"user","content":[{"type":"input_text","text":"内部策略正文"}]}],"instructions":"隐藏规则","system":"隐藏系统","authorization":"Bearer abcdefgh12345678"}"""

        val value = LogRedactor.redact(raw)

        assertTrue(value!!.startsWith("HTTP 400"))
        assertFalse(value.contains("内部策略正文"))
        assertFalse(value.contains("隐藏规则"))
        assertFalse(value.contains("隐藏系统"))
        assertFalse(value.contains("abcdefgh12345678"))
        assertFalse(value.contains("input_text"))
        assertTrue(value.contains("messages=<redacted>"))
        assertTrue(value.contains("instructions=<redacted>"))
        assertTrue(value.contains("system=<redacted>"))
        assertTrue(value.contains("authorization=<redacted>"))
    }

    @Test
    fun `ordinary diagnostics are kept and long bodies are truncated`() {
        assertEquals("请求体不是合法 JSON", LogRedactor.redact("请求体不是合法 JSON"))
        assertNull(LogRedactor.redact(null))
        assertNull(LogRedactor.redact("   "))

        val value = LogRedactor.redact("x".repeat(500))!!
        assertTrue(value.length <= 241)
        assertTrue(value.endsWith("…"))
    }

    @Test
    fun `secrets beyond truncation boundary are not exposed`() {
        val raw = "前缀 " + "x".repeat(300) + " Bearer supersecrettoken123456"

        val value = LogRedactor.redact(raw)

        assertFalse(value!!.contains("supersecrettoken123456"))
        assertTrue(value.endsWith("…"))
    }
}
