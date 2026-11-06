package io.modelbridge.providers.builtin

import io.modelbridge.providers.OpenAiCompatibleProvider
import io.modelbridge.spi.AuthKind

/**
 * Qoder（`qoder`）。
 *
 * Qoder，需登录会话；baseUrl 由账号配置传入。
 */
class QoderProvider : OpenAiCompatibleProvider() {

    override val id: String = "qoder"

    override val displayName: String = "Qoder"

    override val authKind: AuthKind = AuthKind.OAuth

    override val defaultBaseUrl: String = ""

    /** 由账号配置传入上游地址。 */
}
