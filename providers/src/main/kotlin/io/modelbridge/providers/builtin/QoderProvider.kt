package io.modelbridge.providers.builtin

import io.modelbridge.providers.OpenAiCompatibleProvider
import io.modelbridge.spi.AuthKind

/**
 * Qoder（`qoder`）。
 *
 * Qoder，需登录会话；baseUrl 由账号配置传入。
 */
class QoderProvider : OpenAiCompatibleProvider() {

    override val id: String = "qoder"

    override val displayName: String = "Qoder"

    override val authKind: AuthKind = AuthKind.OAuth

    /** 网页登录地址；界面内嵌 WebView 登录后抓取 Cookie。 */
    override val loginUrl: String = "https://qoder.com/login"

    /** 抓 Cookie 时尝试的域名。 */
    override val cookieDomains: List<String> = listOf("qoder.com")

    override val defaultBaseUrl: String = ""

    /** 由账号配置传入上游地址。 */
}
