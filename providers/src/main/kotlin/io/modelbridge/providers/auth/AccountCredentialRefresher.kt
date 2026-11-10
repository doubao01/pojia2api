package io.modelbridge.providers.auth

import io.modelbridge.core.Account
import io.modelbridge.core.AccountStore
import io.modelbridge.spi.Credential
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 一次到期扫描的汇总；不记录令牌、账号名或上游响应。 */
data class CredentialRefreshSummary(
    val scanned: Int = 0,
    val attempted: Int = 0,
    val updated: Int = 0,
    val unchanged: Int = 0,
    val failed: Int = 0,
) {
    val skipped: Int get() = (scanned - attempted).coerceAtLeast(0)
}

/**
 * 把已保存的 Qoder / WorkBuddy 账号映射到设备授权刷新协议。
 *
 * 只刷新带 refresh token 且已知到期时间的 OAuth 账号；服务端返回的
 * access token、refresh token、有效期和身份元数据会写回账号仓储。
 * 失败按账号身份退避，避免过期 refresh token 在后台造成请求风暴。
 */
class AccountCredentialRefresher(
    private val accounts: AccountStore,
    private val clients: Map<String, DeviceAuthClient>,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val thresholdSeconds: Long = DEFAULT_THRESHOLD_SECONDS,
    private val minimumRetryMillis: Long = DEFAULT_MINIMUM_RETRY_MILLIS,
) {
    private val mutex = Mutex()
    private val retryAfter = HashMap<String, Long>()

    private enum class Outcome { Updated, Unchanged, Failed, Skipped }

    private data class RefreshAttempt(
        val outcome: Outcome,
        val calledClient: Boolean = false,
    )

    init {
        require(thresholdSeconds >= 0) { "刷新提前量不能为负数" }
        require(minimumRetryMillis > 0) { "刷新失败退避必须为正数" }
    }

    /** 扫描全部已启用账号，刷新已到期或即将到期的设备授权凭据。 */
    suspend fun refreshDue(): CredentialRefreshSummary = mutex.withLock {
        val now = clockMillis()
        prune(now)
        val snapshot = accounts.all()
        var attempted = 0
        var updated = 0
        var unchanged = 0
        var failed = 0

        for (account in snapshot) {
            currentCoroutineContext().ensureActive()
            val attempt = refreshOne(
                account = account,
                now = now,
                ignoreBackoff = false,
                allowDisabled = false,
            )
            if (attempt.calledClient) attempted++
            when (attempt.outcome) {
                Outcome.Updated -> updated++
                Outcome.Unchanged -> unchanged++
                Outcome.Failed -> failed++
                Outcome.Skipped -> Unit
            }
        }
        prune(now)
        CredentialRefreshSummary(
            scanned = snapshot.size,
            attempted = attempted,
            updated = updated,
            unchanged = unchanged,
            failed = failed,
        )
    }

    /**
     * 刷新单个账号，供用户主动检测连通性前调用。
     *
     * [ignoreBackoff] 只跳过本类的本地退避；供应商客户端自身的退避仍生效，
     * 且距离到期仍很远的账号不会被无谓刷新。
     */
    suspend fun refreshAccount(accountId: String, ignoreBackoff: Boolean = false): Boolean =
        mutex.withLock {
            val account = accounts.find(accountId) ?: return@withLock false
            val now = clockMillis()
            prune(now)
            val result = refreshOne(
                account = account,
                now = now,
                ignoreBackoff = ignoreBackoff,
                allowDisabled = true,
            )
            prune(now)
            result.outcome == Outcome.Updated
        }

    private suspend fun refreshOne(
        account: Account,
        now: Long,
        ignoreBackoff: Boolean,
        allowDisabled: Boolean,
    ): RefreshAttempt {
        val credential = account.credential as? Credential.OAuth
            ?: return RefreshAttempt(Outcome.Skipped)
        val refreshToken = credential.refreshToken?.takeIf { it.isNotBlank() }
            ?: return RefreshAttempt(Outcome.Skipped)
        val client = clients[account.providerId]
            ?: return RefreshAttempt(Outcome.Skipped)
        if (!allowDisabled && !account.enabled) return RefreshAttempt(Outcome.Skipped)

        val expiresAtMillis = effectiveExpiry(account, credential)
        if (expiresAtMillis <= 0) return RefreshAttempt(Outcome.Skipped)
        val threshold = secondsToMillis(thresholdSeconds)
        if (expiresAtMillis > safeAdd(now, threshold)) return RefreshAttempt(Outcome.Skipped)

        val key = refreshKey(account, credential)
        if (!ignoreBackoff && (retryAfter[key] ?: 0) > now) {
            return RefreshAttempt(Outcome.Skipped)
        }

        val request = toDeviceAuthCredentials(
            account = account,
            credential = credential.copy(
                refreshToken = refreshToken,
                expiresAtMillis = expiresAtMillis,
            ),
            expiresAtMillis = expiresAtMillis,
        )
        val result = try {
            client.refresh(request, thresholdSeconds)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            DeviceAuthRefreshResult.Failed(
                DeviceAuthFailure(DeviceAuthError.NetworkError),
            )
        }

        val outcome = when (result) {
            is DeviceAuthRefreshResult.Updated -> {
                val persisted = persist(account.id, credential, result.credentials, now)
                retryAfter.remove(key)
                if (persisted) Outcome.Updated else Outcome.Unchanged
            }
            is DeviceAuthRefreshResult.Unchanged -> {
                if (result.reason == DeviceAuthRefreshSkip.Backoff) {
                    noteBackoff(key, now, result.retryAfterMillis)
                }
                Outcome.Unchanged
            }
            is DeviceAuthRefreshResult.Failed -> {
                noteBackoff(key, now, result.retryAfterMillis)
                Outcome.Failed
            }
        }
        return RefreshAttempt(outcome, calledClient = true)
    }

    private fun effectiveExpiry(account: Account, credential: Credential.OAuth): Long =
        listOf(credential.expiresAtMillis, account.authExpiresAtMillis)
            .filter { it > 0 }
            .minOrNull()
            ?: 0L

    private fun toDeviceAuthCredentials(
        account: Account,
        credential: Credential.OAuth,
        expiresAtMillis: Long,
    ): DeviceAuthCredentials = DeviceAuthCredentials(
        providerId = account.providerId,
        credential = credential.copy(expiresAtMillis = expiresAtMillis),
        region = account.authRegion.trim().ifBlank { "cn" },
        uid = account.authUid.ifBlank { account.id },
        tenantId = account.authTenantId,
        domain = account.authDomain,
        nickname = account.authNickname.ifBlank { account.label },
        tokenKind = tokenKindFor(account, credential),
    )

    private fun tokenKindFor(account: Account, credential: Credential.OAuth): String {
        val stored = account.authTokenKind.trim()
        if (stored.isNotBlank() && !stored.equals("Bearer", ignoreCase = true)) return stored
        if (account.providerId == "qoder") {
            val jobToken = credential.refreshToken.orEmpty().startsWith("jrt-") ||
                credential.accessToken.startsWith("jt-")
            return if (jobToken) "jrt" else "drt"
        }
        return stored.ifBlank { credential.tokenType.ifBlank { "Bearer" } }
    }

    private fun persist(
        accountId: String,
        original: Credential.OAuth,
        updated: DeviceAuthCredentials,
        now: Long,
    ): Boolean {
        val current = accounts.find(accountId) ?: return false
        val currentCredential = current.credential as? Credential.OAuth ?: return false
        if (currentCredential.accessToken != original.accessToken ||
            currentCredential.refreshToken != original.refreshToken
        ) {
            // 刷新期间用户改过账号或导入了新凭据，旧结果不能覆盖新状态。
            return false
        }
        if (updated.credential.accessToken.isBlank()) return false

        // 上游可能沿用旧有效期或省略 TTL；已知的到期时间不能因此丢成「永不过期」。
        val replacementExpiry = listOf(
            updated.credential.expiresAtMillis,
            currentCredential.expiresAtMillis,
            current.authExpiresAtMillis,
        ).firstOrNull { it > 0 } ?: 0L
        val replacementCredential = currentCredential.copy(
            accessToken = updated.credential.accessToken,
            refreshToken = updated.credential.refreshToken
                ?.takeIf { it.isNotBlank() }
                ?: currentCredential.refreshToken,
            expiresAtMillis = replacementExpiry,
            tokenType = updated.credential.tokenType.ifBlank { currentCredential.tokenType },
        )
        val replacement = current.copy(
            credential = replacementCredential,
            authExpiresAtMillis = replacementExpiry,
            authRegion = updated.region.ifBlank { current.authRegion },
            authUid = updated.uid.ifBlank { current.authUid },
            authTenantId = updated.tenantId.ifBlank { current.authTenantId },
            authDomain = updated.domain.ifBlank { current.authDomain },
            authNickname = updated.nickname.ifBlank { current.authNickname },
            authTokenKind = mergeTokenKind(current.authTokenKind, updated.tokenKind),
        )
        accounts.upsert(replacement)
        if (replacementExpiry > 0) {
            val refreshAgainAt = replacementExpiry - secondsToMillis(thresholdSeconds)
            if (now < refreshAgainAt) return true
            // 刷新后依然临近到期时，保留一个短退避，避免轮换令牌绕过
            // 供应商客户端的单飞缓存后在每次维护周期重复换令牌；退避不越过到期时间。
            val untilExpiry = (replacementExpiry - now).coerceAtLeast(0)
            val wait = minOf(minimumRetryMillis, maxOf(1L, untilExpiry / 2))
            retryAfter[refreshKey(replacement, replacementCredential)] = safeAdd(now, wait)
        }
        return true
    }

    private fun mergeTokenKind(current: String, replacement: String): String {
        val value = replacement.trim()
        if (value.isBlank()) return current
        if (value.equals("Bearer", ignoreCase = true) &&
            current.isNotBlank() &&
            !current.equals("Bearer", ignoreCase = true)
        ) {
            return current
        }
        return value
    }

    private fun refreshKey(account: Account, credential: Credential.OAuth): String =
        listOf(
            account.providerId,
            account.id,
            account.authRegion,
            account.authUid,
            account.authTenantId,
            authTokenIdentity(credential.refreshToken.orEmpty()),
        ).joinToString("|")

    private fun noteBackoff(key: String, now: Long, requestedMillis: Long) {
        val wait = maxOf(minimumRetryMillis, requestedMillis.coerceAtLeast(0))
        retryAfter[key] = safeAdd(now, wait)
    }

    private fun prune(now: Long) {
        val expired = retryAfter.filterValues { it <= now }.keys.toList()
        expired.forEach(retryAfter::remove)
    }

    private fun secondsToMillis(seconds: Long): Long =
        if (seconds > Long.MAX_VALUE / 1_000) Long.MAX_VALUE else seconds * 1_000

    private fun safeAdd(value: Long, amount: Long): Long =
        if (value > Long.MAX_VALUE - amount) Long.MAX_VALUE else value + amount

    private companion object {
        const val DEFAULT_THRESHOLD_SECONDS = 300L
        const val DEFAULT_MINIMUM_RETRY_MILLIS = 60_000L
    }
}
