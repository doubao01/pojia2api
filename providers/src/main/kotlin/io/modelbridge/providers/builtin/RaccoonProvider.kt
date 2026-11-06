package io.modelbridge.providers.builtin

import io.modelbridge.providers.OpenAiCompatibleProvider
import io.modelbridge.spi.AuthKind

/**
 * 小浣熊（`raccoon`）。
 *
 * 3.10.0 起由 `xiaohuanxiong` 更名为 `raccoon`，登录会话保持兼容。
 * 上游为自建 OpenAI 兼容接口，需登录后注入凭据。
 */
class RaccoonProvider : OpenAiCompatibleProvider() {

    override val id: String = "raccoon"

    override val displayName: String = "小浣熊"

    override val authKind: AuthKind = AuthKind.OAuth

    /** 网页登录地址；界面内嵌 WebView 登录后抓取 Cookie。 */
    override val loginUrl: String = "https://xiaohuanxiong.com/login"

    /** 抓 Cookie 时尝试的域名。 */
    override val cookieDomains: List<String> = listOf("xiaohuanxiong.com")

    override val defaultBaseUrl: String = "https://xiaohuanxiong.com"
}
