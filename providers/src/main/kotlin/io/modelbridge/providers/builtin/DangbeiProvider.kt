package io.modelbridge.providers.builtin

import io.modelbridge.providers.OpenAiCompatibleProvider
import io.modelbridge.spi.AuthKind

/**
 * 当贝 AI（`dangbei`）。
 *
 * 当贝 AI 助手，需登录会话。
 */
class DangbeiProvider : OpenAiCompatibleProvider() {

    override val id: String = "dangbei"

    override val displayName: String = "当贝 AI"

    override val authKind: AuthKind = AuthKind.OAuth

    /** 网页登录地址；界面内嵌 WebView 登录后抓取 Cookie。 */
    override val loginUrl: String = "https://ai.dangbei.com"

    /** 抓 Cookie 时尝试的域名。 */
    override val cookieDomains: List<String> = listOf("ai.dangbei.com", "dangbei.com")

    override val defaultBaseUrl: String = "https://ai.dangbei.com"
}
