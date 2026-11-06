package io.modelbridge.core

import io.modelbridge.spi.Provider

/**
 * 供应商注册表。
 *
 * 线程安全，支持运行时注册（供插件动态加载）。
 * 同一 id 重复注册会抛异常，避免静默覆盖。
 */
class ProviderRegistry {
    private val providers = LinkedHashMap<String, Provider>()
    private val lock = Any()

    /** 注册一个供应商。 */
    fun register(provider: Provider) {
        synchronized(lock) {
            require(provider.id.isNotBlank()) { "provider id 不能为空" }
            require(provider.id == provider.id.lowercase()) { "provider id 必须小写：${provider.id}" }
            check(providers.put(provider.id, provider) == null) {
                "供应商标识重复：${provider.id}"
            }
        }
    }

    /** 批量注册。 */
    fun registerAll(list: Iterable<Provider>) = list.forEach(::register)

    /** 按 id 查找，不存在返回 null。 */
    fun find(id: String): Provider? = synchronized(lock) { providers[id] }

    /** 按 id 查找，不存在抛 [GatewayError.NotFound]。 */
    fun require(id: String): Provider =
        find(id) ?: throw GatewayError.NotFound("未知的供应商：$id")

    /** 全部已注册供应商，按注册顺序。 */
    fun all(): List<Provider> = synchronized(lock) { providers.values.toList() }

    /** 已注册数量。 */
    val size: Int get() = synchronized(lock) { providers.size }
}
