package io.modelbridge.providers.builtin

import io.modelbridge.providers.OpenAiCompatibleProvider
import io.modelbridge.spi.AuthKind

/**
 * AgentMore（`agentmore`）。
 *
 * ChatGLM 出品的 AgentMore 平台，OpenAI 兼容接口。
 */
class AgentmoreProvider : OpenAiCompatibleProvider() {

    override val id: String = "agentmore"

    override val displayName: String = "AgentMore"

    override val authKind: AuthKind = AuthKind.ApiKey

    override val defaultBaseUrl: String = "https://agentmore.chatglm.cn"
}
