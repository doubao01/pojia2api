package io.modelbridge.providers.builtin

import io.modelbridge.providers.OpenAiCompatibleProvider
import io.modelbridge.spi.AuthKind

/**
 * Loomy（`loomy`）。
 *
 * Loomy，手机号 + 短信验证码登录；baseUrl 由账号配置传入。
 */
class LoomyProvider : OpenAiCompatibleProvider() {

    override val id: String = "loomy"

    override val displayName: String = "Loomy"

    override val authKind: AuthKind = AuthKind.PhoneCode

    override val defaultBaseUrl: String = ""

    /** 由账号配置传入上游地址。 */
}
