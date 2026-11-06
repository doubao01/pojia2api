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

    override val defaultBaseUrl: String = "https://ai.catpaw.meituan.com"
}
