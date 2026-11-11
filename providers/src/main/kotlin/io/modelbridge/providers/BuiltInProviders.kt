package io.modelbridge.providers

import io.modelbridge.core.ProviderRegistry
import io.modelbridge.spi.Provider
import io.modelbridge.providers.builtin.AStudioProvider
import io.modelbridge.providers.builtin.AgentmoreProvider
import io.modelbridge.providers.builtin.CatpawProvider
import io.modelbridge.providers.builtin.CodeBuddyProvider
import io.modelbridge.providers.builtin.DangbeiProvider
import io.modelbridge.providers.builtin.DeepSeekProvider
import io.modelbridge.providers.builtin.LoomyProvider
import io.modelbridge.providers.builtin.MeooProvider
import io.modelbridge.providers.builtin.QoderProvider
import io.modelbridge.providers.builtin.RaccoonProvider
import io.modelbridge.providers.builtin.TabbitProvider
import io.modelbridge.providers.builtin.TraeProvider
import io.modelbridge.providers.builtin.WenxinProvider
import io.modelbridge.providers.builtin.ZhipuProvider
import io.modelbridge.providers.workbuddy.WorkBuddyProvider
import io.modelbridge.providers.kilo.KiloProvider
import io.modelbridge.providers.opencode.OpenCodeProvider

/**
 * 内置供应商清单。
 *
 * 清单与 3.10.0 对齐（共 14 家）：
 * - 已移除 `opencode`、`runable`（新版不再内置）
 * - `xiaohuanxiong` 更名为 `raccoon`
 *
 * 注册顺序决定未配置优先级时的默认顺序。
 * 如需增删，只改这一个文件，[ProviderRegistry] 与路由逻辑不需要动。
 */
object BuiltInProviders {

    /** 全部内置供应商。 */
    val all: List<Provider> = listOf(
        ZhipuProvider(),
        DeepSeekProvider(),
        AStudioProvider(),
        AgentmoreProvider(),
        TabbitProvider(),
        WenxinProvider(),
        MeooProvider(),
        DangbeiProvider(),
        CatpawProvider(),
        CodeBuddyProvider(),
        WorkBuddyProvider(),
        OpenCodeProvider(),
        KiloProvider(),
        RaccoonProvider(),
        TraeProvider(),
        QoderProvider(),
        LoomyProvider(),
    )

    /** 注册到现有注册表。 */
    fun registerInto(registry: ProviderRegistry) {
        registry.registerAll(all)
    }
}
