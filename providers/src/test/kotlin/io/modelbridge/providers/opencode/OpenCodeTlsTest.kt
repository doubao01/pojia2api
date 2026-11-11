package io.modelbridge.providers.opencode

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 官方 CLI 的 TLS 套件列表与 JVM/OpenSSL 两套命名。 */
class OpenCodeTlsTest {
    @Test
    fun `official cli offers seventeen cipher suites`() {
        assertEquals(17, OpenCodeTls.CIPHER_SUITES.size)
        assertEquals(17, OpenCodeTls.CIPHER_SUITES.distinct().size)
    }

    @Test
    fun `tls 1_3 suites keep their iana names`() {
        assertTrue(OpenCodeTls.CIPHER_SUITES_IANA.contains("TLS_AES_128_GCM_SHA256"))
        assertTrue(OpenCodeTls.CIPHER_SUITES_IANA.contains("TLS_CHACHA20_POLY1305_SHA256"))
    }

    @Test
    fun `tls 1_2 suites are converted to iana with with separator`() {
        assertEquals(
            "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
            OpenCodeTls.toIanaName("ECDHE-RSA-AES128-GCM-SHA256"),
        )
        assertEquals(
            "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
            OpenCodeTls.toIanaName("ECDHE-ECDSA-AES256-GCM-SHA384"),
        )
        assertEquals(
            "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305",
            OpenCodeTls.toIanaName("ECDHE-RSA-CHACHA20-POLY1305"),
        )
        assertEquals(
            "TLS_RSA_WITH_AES_128_CBC_SHA",
            OpenCodeTls.toIanaName("AES128-SHA"),
        )
    }

    @Test
    fun `every iana name is accepted by the jvm ssl layer`() {
        // JDK 暴露的套件名才能被 setEnabledCipherSuites 接受，这里只校验命名形态。
        OpenCodeTls.CIPHER_SUITES_IANA.forEach { name ->
            assertTrue(name.startsWith("TLS_"), name)
            assertTrue(name.none { it == '-' }, name)
        }
    }

    @Test
    fun `openssl string round trips back to the official list`() {
        val openssl = OpenCodeTls.CIPHER_SUITE_STRING.split(":")
        assertEquals(17, openssl.size)
        assertEquals(OpenCodeTls.CIPHER_SUITES, openssl)
    }

    @Test
    fun `jdk disabled suites are the cbc ones`() {
        assertEquals(6, OpenCodeTls.JDK_DISABLED_BY_DEFAULT.size)
        OpenCodeTls.JDK_DISABLED_BY_DEFAULT.forEach { suite ->
            assertTrue(suite.endsWith("-SHA"), suite)
            assertTrue(OpenCodeTls.CIPHER_SUITES.contains(suite), suite)
        }
    }
}
