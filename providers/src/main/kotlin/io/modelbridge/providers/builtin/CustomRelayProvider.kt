package io.modelbridge.providers.builtin

import io.modelbridge.providers.OpenAiCompatibleProvider
import io.modelbridge.spi.AuthKind

/**
 * 原版「自定义中转站」对应的隐藏 OpenAI 兼容 Provider。
 *
 * 它不显示在 14 家内置供应商分组里；每个账号通过 `baseUrl` 指向自己的
 * 本机/局域网网关。`authKind = None` 让本机中转站无需凭据即可路由，
 * 若用户手动配置了 API Key，`OpenAiCompatibleProvider` 仍会带上 Authorization。
 */
class CustomRelayProvider : OpenAiCompatibleProvider() {
    override val id: String = "custom"
    override val displayName: String = "自定义中转站"
    override val authKind: AuthKind = AuthKind.None
    override val defaultBaseUrl: String = ""
}
