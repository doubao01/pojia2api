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

    /** OpenSSL 风格的套件串，可直接用于 `SSLContext.setCipherSuite` 或 `openssl s_client -cipher`。 */
    val CIPHER_SUITE_STRING: String = CIPHER_SUITES.joinToString(":")

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
