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

    override val defaultBaseUrl: String = "https://ai.dangbei.com"
}
