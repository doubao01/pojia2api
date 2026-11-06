package io.modelbridge.providers.builtin

import io.modelbridge.providers.OpenAiCompatibleProvider
import io.modelbridge.spi.AuthKind

/**
 * 智谱 GLM（`zhipu`）。
 *
 * 智谱开放平台。标准 OpenAI 兼容接口，API Key 鉴权。
 * 原应用另有 WebGLM 会话模式，此处采用标准 API 路径。
 */
class ZhipuProvider : OpenAiCompatibleProvider() {

    override val id: String = "zhipu"

    override val displayName: String = "智谱 GLM"

    override val authKind: AuthKind = AuthKind.ApiKey

    override val defaultBaseUrl: String = "https://open.bigmodel.cn/api/paas/v4"
}
