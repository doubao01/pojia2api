package io.modelbridge.providers.builtin

import io.modelbridge.providers.OpenAiCompatibleProvider
import io.modelbridge.spi.AuthKind

/**
 * Meoo（`meoo`）。
 *
 * Meoo，需登录会话。
 */
class MeooProvider : OpenAiCompatibleProvider() {

    override val id: String = "meoo"

    override val displayName: String = "Meoo"

    override val authKind: AuthKind = AuthKind.OAuth

    override val defaultBaseUrl: String = "https://meoo.com"
}
