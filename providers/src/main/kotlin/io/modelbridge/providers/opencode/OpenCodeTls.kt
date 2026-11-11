package io.modelbridge.providers.opencode

/**
 * OpenCode 免费层的 TLS 指纹。
 *
 * 门禁会比对 ClientHello 的加密套件列表：官方 CLI 用 Bun/BoringSSL，提供下面
 * 17 个套件；Node 默认的 52 个会被拒。列表顺序也要一致，且**不能删掉其中的
 * CBC-SHA 老套件**，删了同样被拒。
 *
 * JVM 侧的限制：`SSLSocketFactory` 只能按名称设置套件，且 JDK 默认禁用
 * `*_SHA`（无 PFS）系列。启用它们需要额外的 JVM 参数或自定义 `SSLContext`，
 * 因此在 JVM 内精确复现这个列表并不稳妥。
 *
 * 结论：本模块只提供常量。实际使用时把 [CIPHER_SUITE_STRING] 交给一个能精确
 * 控制 TLS 的本地转发层（OpenSSL/BoringSSL 系），由它转发出站请求。
 */
object OpenCodeTls {

    /** 官方 CLI（Bun/BoringSSL）提供的加密套件，按官方顺序。 */
    val CIPHER_SUITES: List<String> = listOf(
        "TLS_AES_128_GCM_SHA256",
        "TLS_AES_256_GCM_SHA384",
        "TLS_CHACHA20_POLY1305_SHA256",
        "ECDHE-ECDSA-AES128-GCM-SHA256",
        "ECDHE-RSA-AES128-GCM-SHA256",
        "ECDHE-ECDSA-AES256-GCM-SHA384",
        "ECDHE-RSA-AES256-GCM-SHA384",
        "ECDHE-ECDSA-CHACHA20-POLY1305",
        "ECDHE-RSA-CHACHA20-POLY1305",
        "ECDHE-ECDSA-AES128-SHA",
        "ECDHE-RSA-AES128-SHA",
        "ECDHE-ECDSA-AES256-SHA",
        "ECDHE-RSA-AES256-SHA",
        "AES128-GCM-SHA256",
        "AES256-GCM-SHA384",
        "AES128-SHA",
        "AES256-SHA",
    )

    /** OpenSSL 风格的套件串，可直接用于 `openssl s_client -cipher`。 */
    val CIPHER_SUITE_STRING: String = CIPHER_SUITES.map(::toOpenSslName).joinToString(":")

    /**
     * 同一份套件列表的 IANA 名称，供 JVM 的
     * `SSLSocket.setEnabledCipherSuites` 使用。
     *
     * JVM 只认 IANA 名称（如 `TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256`）；
     * 直接传 OpenSSL 写法会被整份忽略。
     */
    val CIPHER_SUITES_IANA: List<String> = CIPHER_SUITES.map(::toIanaName)

    /**
     * OpenSSL 名称转 IANA。
     *
     * TLS 1.3 套件（`TLS_` 开头）两种写法一致；TLS 1.2 需要补 `TLS_` 前缀、
     * 把密钥交换与算法用 `_WITH_` 分开，并给 AES 位数加下划线。
     */
    fun toIanaName(suite: String): String {
        if (suite.startsWith("TLS_")) return suite
        val parts = suite.split("-")
        val (keyExchange, rest) = when {
            // ECDHE-RSA / ECDHE-ECDSA / DHE-RSA 这类是两段密钥交换
            parts.size > 1 && parts[0] in setOf("ECDHE", "DHE") &&
                parts[1] in setOf("RSA", "ECDSA") -> "${parts[0]}_${parts[1]}" to parts.drop(2)
            parts.size > 1 && parts[0] == "RSA" -> "RSA" to parts.drop(1)
            // AES128-SHA 这类没有密钥交换前缀，OpenSSL 隐含 RSA
            parts[0].startsWith("AES") || parts[0].startsWith("CHACHA20") -> "RSA" to parts
            else -> parts[0] to parts.drop(1)
        }
        val cipher = rest.joinToString("_")
            // 只有 AES 需要拆位数（AES128 → AES_128），CHACHA20 保持原样
            .replace(Regex("^(AES)(\\d+)"), "$1_$2")
            .let { name ->
                // AES_128_SHA 这类 CBC 套件的 IANA 名带 CBC
                if (name.endsWith("_SHA") && name.contains("AES_") && !name.contains("GCM")) {
                    name.removeSuffix("_SHA") + "_CBC_SHA"
                } else {
                    name
                }
            }
        return "TLS_${keyExchange}_WITH_$cipher"
    }

    /** IANA 名称转 OpenSSL，用于 `openssl s_client` 校对。 */
    fun toOpenSslName(suite: String): String {
        // TLS 1.3 套件在两种写法下同名。
        if (!suite.contains("_WITH_")) return suite
        val body = suite.removePrefix("TLS_")
        val keyExchange = body.substringBefore("_WITH_")
        val cipher = body.substringAfter("_WITH_")
        val opensslKeyExchange = keyExchange
            .replace("RSA_WITH", "")
            .replace('_', '-')
        val opensslCipher = cipher
            .replace("_CBC_", "_")
            .replace('_', '-')
            .replace(Regex("^(AES)-(\\d+)"), "$1$2")
        // TLS_RSA_WITH_AES_128_CBC_SHA 在 OpenSSL 写作 AES128-SHA（无密钥交换前缀）
        return if (keyExchange == "RSA") opensslCipher else "$opensslKeyExchange-$opensslCipher"
    }

    /**
     * 其中被 JDK 默认禁用的套件。
     *
     * 这几个是 CBC 模式且无前向保密，JDK 从 8u291 起默认禁用；要启用需要
     * `jdk.tls.disabledAlgorithms` 调整，属全局安全策略，不建议在网关内改动。
     */
    val JDK_DISABLED_BY_DEFAULT: List<String> = listOf(
        "ECDHE-ECDSA-AES128-SHA",
        "ECDHE-RSA-AES128-SHA",
        "ECDHE-ECDSA-AES256-SHA",
        "ECDHE-RSA-AES256-SHA",
        "AES128-SHA",
        "AES256-SHA",
    )
}
