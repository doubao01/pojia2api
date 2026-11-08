package io.modelbridge.app.prefs

import android.content.Context
import io.modelbridge.core.Account
import io.modelbridge.core.AccountStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** 先确认磁盘写入成功，再发布新账号快照；损坏存档不会静默变为空列表。 */
class AccountPreferences(
    context: Context,
    tag: String = "modelbridge_accounts",
) : AccountStore {
    private val preferences = context.getSharedPreferences(tag, Context.MODE_PRIVATE)
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Account.serializer())
    private val lock = Any()
    private val _accounts = MutableStateFlow(readAccounts())
    override val accountsFlow: StateFlow<List<Account>> = _accounts.asStateFlow()

    override fun all(): List<Account> = _accounts.value
    override fun byProvider(providerId: String): List<Account> = all().filter { it.providerId == providerId }
    override fun find(id: String): Account? = all().firstOrNull { it.id == id }

    override fun upsert(account: Account) = synchronized(lock) {
        val next = all().toMutableList()
        val index = next.indexOfFirst { it.id == account.id }
        if (index < 0) next.add(account) else next[index] = account
        persist(next)
    }

    override fun delete(id: String) = synchronized(lock) {
        persist(all().filterNot { it.id == id })
    }

    fun importAll(accounts: List<Account>, replace: Boolean = false) = synchronized(lock) {
        val next = if (replace) linkedMapOf<String, Account>() else all().associateByTo(linkedMapOf()) { it.id }
        accounts.forEach { next[it.id] = it }
        persist(next.values.toList())
    }

    private fun persist(accounts: List<Account>) {
        val raw = json.encodeToString(serializer, accounts)
        check(preferences.edit().putString(KEY, raw).commit()) { "账号无法保存" }
        _accounts.value = accounts
    }

    private fun readAccounts(): List<Account> {
        val raw = preferences.getString(KEY, "[]") ?: "[]"
        return try {
            json.decodeFromString(serializer, raw)
        } catch (error: Exception) {
            throw IllegalStateException("账号存档无法读取，原文件未被清空", error)
        }
    }

    private companion object {
        const val KEY = "accounts_json"
    }
}
