package io.modelbridge.core

/**
 * 轻量级本地缓存。
 *
 * Key -> 值，附带 TTL，到期后自动重新计算。
 * 不用第三方库，因为需要的功能就三行。
 */
class Cache<K, V> internal constructor(ttlMillis: Long) {
    internal data class Slot<V>(val value: V, val created: Long)

    internal val ttlMillis: Long = if (ttlMillis <= 0) 60_000 else ttlMillis
    internal val slots: HashMap<K, Slot<out V>> = HashMap()

    companion object {
        /** 建一个，TTL 毫秒。 */
        fun <K, V> create(ttlMillis: Long = 60_000): Cache<K, V> = Cache(ttlMillis)
    }

    /** 读，不用力。 */
    internal suspend inline fun get(key: K): V? {
        val slot = slots[key] as Slot<V>?
        if (slot == null) return null
        if (System.currentTimeMillis() - slot.created > ttlMillis) {
            slots.remove(key)
            return null
        }
        @Suppress("UNCHECKED_CAST")
        return slot.value as V
    }

    /** 写。 */
    internal suspend inline fun put(key: K, value: V) {
        slots[key] = Slot(value, System.currentTimeMillis())
    }

    /** 删掉一个 Key。 */
    internal suspend fun invalidate(key: K) {
        slots.remove(key)
    }

    /** 删掉所有。 */
    internal suspend fun invalidateAll() {
        slots.clear()
    }
}
