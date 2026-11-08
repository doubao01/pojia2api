package io.modelbridge.app.prefs

import android.content.Context
import io.modelbridge.core.policy.InMemoryPolicyStore
import io.modelbridge.core.policy.Policy
import io.modelbridge.core.policy.PolicyStore
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** 保存成功后才发布新快照，避免界面显示已保存而磁盘写入失败。 */
class PrefsPolicyStore(
    context: Context,
    tag: String = "modelbridge_policies",
) : PolicyStore {
    private val preferences = context.getSharedPreferences(tag, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(Policy.serializer())
    private val lock = Any()
    private val memory = InMemoryPolicyStore(load())

    override val policiesFlow: StateFlow<List<Policy>> = memory.policiesFlow

    override fun all(): List<Policy> = memory.all()

    override fun find(id: String): Policy? = memory.find(id)

    override fun upsert(policy: Policy) = synchronized(lock) {
        val next = all().toMutableList()
        val index = next.indexOfFirst { it.id == policy.id }
        if (index < 0) next.add(policy) else next[index] = policy
        persist(next)
        memory.upsert(policy)
    }

    override fun delete(id: String) = synchronized(lock) {
        if (find(id)?.builtin == true) return@synchronized
        persist(all().filterNot { it.id == id })
        memory.delete(id)
    }

    override fun replaceAll(policies: List<Policy>) = synchronized(lock) {
        val next = policies.associateByTo(linkedMapOf()) { it.id }
        all().filter { it.builtin }.forEach { next[it.id] = it }
        val restored = next.values.toList()
        persist(restored)
        memory.replaceAll(restored)
    }

    private fun persist(policies: List<Policy>) {
        val raw = json.encodeToString(serializer, policies)
        check(preferences.edit().putString(KEY, raw).commit()) { "策略无法保存" }
    }

    private fun load(): List<Policy> {
        val raw = preferences.getString(KEY, null) ?: return emptyList()
        return try {
            json.decodeFromString(serializer, raw)
        } catch (error: Exception) {
            // 不清空损坏的存档，也不把策略正文写入日志。
            throw IllegalStateException("策略存档无法读取，请先备份后恢复", error)
        }
    }

    private companion object {
        const val KEY = "policies_json"
    }
}
