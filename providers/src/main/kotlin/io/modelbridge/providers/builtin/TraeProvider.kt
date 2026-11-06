package io.modelbridge.providers.builtin

import io.modelbridge.providers.OpenAiCompatibleProvider
import io.modelbridge.spi.AuthKind

/**
 * Trae（`trae`）。
 *
 * 字节 Trae。原应用在本地 127.0.0.1:8790 起 authorize 回调做 OAuth，
 * 登录后注入会话凭据；baseUrl 由账号配置传入。
 */
class TraeProvider : OpenAiCompatibleProvider() {

    override val id: String = "trae"

    override val displayName: String = "Trae"

    override val authKind: AuthKind = AuthKind.OAuth

    override val defaultBaseUrl: String = ""

    /** 由账号配置传入上游地址。 */
}
