package io.modelbridge.providers.builtin

import io.modelbridge.providers.OpenAiCompatibleProvider
import io.modelbridge.spi.AuthKind

/**
 * Meoo（`meoo`）。
 *
 * Meoo，需登录会话。
 */
class MeooProvider : OpenAiCompatibleProvider() {

    override val id: String = "meoo"

    override val displayName: String = "Meoo"

    override val authKind: AuthKind = AuthKind.OAuth

    /** 网页登录地址；界面内嵌 WebView 登录后抓取 Cookie。 */
    override val loginUrl: String = "https://meoo.com/login"

    /** 抓 Cookie 时尝试的域名。 */
    override val cookieDomains: List<String> = listOf("meoo.com")

    override val defaultBaseUrl: String = "https://meoo.com"
}
