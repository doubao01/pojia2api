package io.modelbridge.providers.builtin

import io.modelbridge.providers.OpenAiCompatibleProvider
import io.modelbridge.spi.AuthKind

/**
 * CodeBuddy 腾讯（`codebuddy`）。
 *
 * 腾讯 CodeBuddy（含 workbuddy / copilot 域名），需登录会话。
 */
class CodeBuddyProvider : OpenAiCompatibleProvider() {

    override val id: String = "codebuddy"

    override val displayName: String = "CodeBuddy 腾讯"

    override val authKind: AuthKind = AuthKind.OAuth

    override val defaultBaseUrl: String = "https://www.codebuddy.cn"
}
