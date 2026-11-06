package io.modelbridge.providers.builtin

import io.modelbridge.providers.OpenAiCompatibleProvider
import io.modelbridge.spi.AuthKind

/**
 * Tabbit（`tabbit`）。
 *
 * Tabbit，OpenAI 兼容。
 */
class TabbitProvider : OpenAiCompatibleProvider() {

    override val id: String = "tabbit"

    override val displayName: String = "Tabbit"

    override val authKind: AuthKind = AuthKind.ApiKey

    override val defaultBaseUrl: String = "https://api.tabbit.cn/v1"
}
