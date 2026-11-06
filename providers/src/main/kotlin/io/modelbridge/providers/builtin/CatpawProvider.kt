package io.modelbridge.providers.builtin

import io.modelbridge.providers.OpenAiCompatibleProvider
import io.modelbridge.spi.AuthKind

/**
 * CatPaw 美团（`catpaw`）。
 *
 * 美团 CatPaw，需登录会话。
 */
class CatpawProvider : OpenAiCompatibleProvider() {

    override val id: String = "catpaw"

    override val displayName: String = "CatPaw 美团"

    override val authKind: AuthKind = AuthKind.OAuth

    /** 网页登录地址；界面内嵌 WebView 登录后抓取 Cookie。 */
    override val loginUrl: String = "https://ai.catpaw.meituan.com"

    /** 抓 Cookie 时尝试的域名。 */
    override val cookieDomains: List<String> = listOf("ai.catpaw.meituan.com", "meituan.com")

    override val defaultBaseUrl: String = "https://ai.catpaw.meituan.com"
}
