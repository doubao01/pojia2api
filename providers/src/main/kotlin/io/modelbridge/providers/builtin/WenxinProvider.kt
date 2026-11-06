package io.modelbridge.providers.builtin

import io.modelbridge.providers.OpenAiCompatibleProvider
import io.modelbridge.spi.AuthKind

/**
 * 文心一言（`wenxin`）。
 *
 * 百度文心。原应用通过 wappass.baidu.com 的登录会话实现；
 * 这里使用千帆 v2 的 OpenAI 兼容入口。
 */
class WenxinProvider : OpenAiCompatibleProvider() {

    override val id: String = "wenxin"

    override val displayName: String = "文心一言"

    override val authKind: AuthKind = AuthKind.OAuth

    override val defaultBaseUrl: String = "https://qianfan.baidubce.com/v2"
}
