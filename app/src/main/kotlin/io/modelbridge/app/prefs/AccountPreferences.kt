package io.modelbridge.app.prefs

import android.content.Context
import android.content.SharedPreferences
import io.modelbridge.core.Account
import io.modelbridge.core.AccountStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 基于 SharedPreferences 的 Android 账号仓储，重启后不丢。
 *
 * 因为 Account 已经是 @Serializable ，直接使用 Json。
 */
class AccountPreferences(
    context: Context,
    private val tag: String = "modelbridge_accounts",
) : AccountStore {

    private val preferences: SharedPreferences = context.getSharedPreferences(tag, Context.MODE_PRIVATE)
    private val json = Json { encodeDefaults = false }

    init {
        // 任何时候字段变化都重新读一遍。
        preferences.registerOnSharedPreferenceChangeListener { _, _ -> emit() }
    }

    override fun all(): List<Account> = readAccounts()

    override fun byProvider(providerId: String): List<Account> = readAccounts().filter { it.providerId == providerId }

    override fun find(id: String): Account? = readAccounts().firstOrNull { it.id == id }

    override fun upsert(account: Account) {
        val list = readAccounts().toMutableList()
        val existing = list.indexOfFirst { it.id == account.id }
        if (existing >= 0) {
            list[existing] = account
        } else {
            list.add(account)
        }
        preferences.edit().putString(KEY, json.encodeToString(list)).apply()
        emit()
    }

    override fun delete(id: String) {
        val list = readAccounts().filter { it.id != id }
        preferences.edit().putString(KEY, json.encodeToString(list)).apply()
        emit()
    }

    private val _accountsFlow = MutableStateFlow<List<Account>>(readAccounts())
    override val accountsFlow: StateFlow<List<Account>> = _accountsFlow

    private fun emit() {
        _accountsFlow.value = readAccounts()
    }

    private fun readAccounts(): List<Account> {
        val raw = preferences.getString(KEY, "[]") ?: "[]"
        return runCatching { json.decodeFromString<List<Account>>(raw) }.getOrNull() ?: emptyList()
    }

    companion object {
        private const val KEY = "accounts_json"
    }
}
