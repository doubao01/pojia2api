package io.modelbridge.providers.builtin

import io.modelbridge.providers.OpenAiCompatibleProvider
import io.modelbridge.spi.AuthKind

/**
 * DeepSeek（`deepseek`）。
 *
 * DeepSeek。原应用走 chat.deepseek.com 的会话/Cookie 模式；
 * 这里采用官方 API（Bearer Key），更稳定且适合长期维护。
 */
class DeepSeekProvider : OpenAiCompatibleProvider() {

    override val id: String = "deepseek"

    override val displayName: String = "DeepSeek"

    override val authKind: AuthKind = AuthKind.Cookie

    /** 网页登录地址；界面内嵌 WebView 登录后抓取 Cookie。 */
    override val loginUrl: String = "https://chat.deepseek.com/sign_in"

    /** 抓 Cookie 时尝试的域名。 */
    override val cookieDomains: List<String> = listOf("chat.deepseek.com", "deepseek.com")

    override val defaultBaseUrl: String = "https://api.deepseek.com/v1"
}
