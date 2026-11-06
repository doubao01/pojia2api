package io.modelbridge.providers.builtin

import io.modelbridge.providers.OpenAiCompatibleProvider
import io.modelbridge.spi.AuthKind

/**
 * 讯飞星火 AStudio（`astudio`）。
 *
 * 讯飞星火 MaaS。地址从原应用抽取，OpenAI 兼容形态。
 */
class AStudioProvider : OpenAiCompatibleProvider() {

    override val id: String = "astudio"

    override val displayName: String = "讯飞星火 AStudio"

    override val authKind: AuthKind = AuthKind.ApiKey

    override val defaultBaseUrl: String = "https://maas-api.cn-huabei-1.xf-yun.com/v1"
}
