package io.modelbridge.core.policy

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 策略仓储。
 *
 * 与原版一致：策略正文只存在本机，不上传。
 * 这里定义抽象，Android 侧用 SharedPreferences 实现，测试用内存实现。
 */
interface PolicyStore {
    fun all(): List<Policy>
    fun find(id: String): Policy?
    fun upsert(policy: Policy)
    fun delete(id: String)

    val policiesFlow: StateFlow<List<Policy>>
}

/** 内存实现，供测试与无持久化场景使用。 */
class InMemoryPolicyStore(initial: List<Policy> = emptyList()) : PolicyStore {

    private val map = LinkedHashMap<String, Policy>()
    private val lock = Any()
    private val _flow = MutableStateFlow<List<Policy>>(emptyList())

    override val policiesFlow: StateFlow<List<Policy>> = _flow.asStateFlow()

    init {
        initial.forEach { map[it.id] = it }
        emit()
    }

    override fun all(): List<Policy> = synchronized(lock) { map.values.toList() }

    override fun find(id: String): Policy? = synchronized(lock) { map[id] }

    override fun upsert(policy: Policy) {
        synchronized(lock) { map[policy.id] = policy }
        emit()
    }

    override fun delete(id: String) {
        synchronized(lock) {
            // 内置策略不可删除
            if (map[id]?.builtin == true) return
            map.remove(id)
        }
        emit()
    }

    private fun emit() {
        _flow.value = all()
    }
}
