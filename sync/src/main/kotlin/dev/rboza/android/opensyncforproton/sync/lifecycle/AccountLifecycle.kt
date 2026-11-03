package dev.rboza.android.opensyncforproton.sync.lifecycle

import dev.rboza.android.opensyncforproton.account.AccountDirectory
import dev.rboza.android.opensyncforproton.account.AccountId
import dev.rboza.android.opensyncforproton.account.CredentialStore
import dev.rboza.android.opensyncforproton.account.Credentials
import dev.rboza.android.opensyncforproton.account.NewSession
import dev.rboza.android.opensyncforproton.proton.ProtonApi
import dev.rboza.android.opensyncforproton.proton.ProtonError
import dev.rboza.android.opensyncforproton.proton.TokenStore
import dev.rboza.android.opensyncforproton.proton.Tokens
import dev.rboza.android.opensyncforproton.sync.db.AccountDao
import dev.rboza.android.opensyncforproton.sync.db.AccountRecordEntity
import dev.rboza.android.opensyncforproton.sync.db.AccountStateDao
import dev.rboza.android.opensyncforproton.sync.db.LoginState
import dev.rboza.android.opensyncforproton.sync.db.SettingsEntity
import dev.rboza.android.opensyncforproton.sync.db.SyncMode
import dev.rboza.android.opensyncforproton.sync.engine.SyncCoordinator
import dev.rboza.android.opensyncforproton.sync.provider.DeviceContacts
import dev.rboza.android.opensyncforproton.sync.provider.ProviderUnavailableException
import dev.rboza.android.opensyncforproton.sync.schedule.SyncScheduler
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** What [AccountLifecycle.completeLogin] did with a verified login. */
sealed interface LoginCommit {
    /** The account is signed in; [resumed] when it already existed (its rows, maps, and mode kept). */
    data class Committed(val resumed: Boolean) : LoginCommit

    /** Nothing was kept, and the new session was ended. */
    data class Refused(val reason: RefusalReason) : LoginCommit
}

enum class RefusalReason {
    /** Another account is signed in, or its session expired: log out of it first. */
    OTHER_ACCOUNT_ACTIVE,

    /** AccountManager didn't add the account: another account of the app's type has the name, or it refused. */
    NAME_IN_USE,
}

/**
 * [sessionEnded] is false when Proton didn't confirm an A5, or none was sent (the account was already signed out, or
 * its credentials couldn't be read): the session may need ending from Proton's web settings.
 */
data class LogoutResult(val sessionEnded: Boolean)

/** [accountRemoved] is false when AccountManager kept the account; the app then keeps its rows too, signed out. */
data class RemovalResult(val accountRemoved: Boolean, val sessionEnded: Boolean)

/**
 * Everything that spans several stores: the login commit, the sync mode and interval, logout, removal, and
 * reconciliation. They share one mutex, so they never interleave; there is one instance in the app. The credential
 * store, AccountManager, and the provider block, so they run on [io]. Every operation, once called, runs to its end
 * even if its caller is cancelled (after waiting its turn, and for a running sync to stop; its own A5 takes at most
 * 10 s), so it never stops halfway.
 */
class AccountLifecycle(
    private val credentials: CredentialStore,
    private val directory: AccountDirectory,
    private val accounts: AccountDao,
    private val state: AccountStateDao,
    private val deviceContacts: DeviceContacts,
    private val scheduler: SyncScheduler,
    private val coordinator: SyncCoordinator,
    private val newClient: (TokenStore) -> ProtonApi,
    private val io: CoroutineDispatcher,
) {
    private val mutex = Mutex()

    /**
     * Commits a verified login. The same `User.ID` gets its credentials replaced and is signed in again, keeping its
     * Android account, rows, mode, and interval, and its job and flags come back with sync on. A different one while
     * another account is `ACTIVE` is refused. A new one is saved in order: the Room row (dry run), then the
     * credentials, then the Android account, so every crash point leaves a Room row without an Android account, which
     * reconciliation deletes with any credentials; if AccountManager doesn't add it, the first two are undone. A
     * re-login is signed in once `ACTIVE` is written: a failed save undoes nothing (the store keeps the old envelope,
     * or the new one whose session the A5 then ends), a failed state write deletes the new credentials, and a failure
     * setting the job and flags after it still reports the login committed, leaving them for reconciliation. A refused
     * login, or one that fails before it's signed in, ends its session (one A5, at most 10 s); a failure is then passed
     * on. The session's credentials are closed either way.
     */
    suspend fun completeLogin(session: NewSession): LoginCommit = withContext(NonCancellable) {
        mutex.withLock { session.credentials.use { commit(session, it) } }
    }

    /**
     * Turns sync on or off. On (after the screen's fresh dry run and confirmation): the job and Android's flags, then
     * the mode. Off: the running sync is cancelled and no other starts until the mode and flags are back to dry run. An
     * unknown account changes nothing.
     */
    suspend fun setSyncMode(account: AccountId, mode: SyncMode): Unit = withContext(NonCancellable) {
        mutex.withLock {
            val record = state.record(account.value) ?: return@withLock
            val settings = state.settings(account.value) ?: return@withLock
            when (mode) {
                SyncMode.ON -> {
                    // The schedule first: a failure or crash before the write leaves Room on dry run, which
                    // reconciliation brings the flags back to, never sync on with automatic sync off.
                    withContext(io) { scheduler.choose(record, settings.copy(syncMode = mode)) }
                    accounts.setSyncMode(account.value, mode)
                }
                SyncMode.DRY_RUN -> coordinator.whileClosed(account) {
                    accounts.setSyncMode(account.value, mode)
                    withContext(io) { scheduler.reconcile(record, settings.copy(syncMode = mode)) }
                }
            }
        }
    }

    /**
     * The full sync's interval, one of the listed ones; a longer check drops to it. Another throws
     * IllegalArgumentException.
     */
    suspend fun setInterval(account: AccountId, minutes: Int) {
        require(minutes in SyncScheduler.INTERVALS) { "not an interval choice" }
        chooseTiming(account, { it.copy(intervalMinutes = minutes, checkMinutes = it.checkMinutes?.coerceAtMost(minutes)) }) {
            accounts.setInterval(account.value, minutes)
        }
    }

    /**
     * The check's: a listed interval no longer than the full sync's, or `null` for Same as full sync. An unlisted one
     * throws IllegalArgumentException, and so does one longer than a known account's full sync.
     */
    suspend fun setCheckInterval(account: AccountId, minutes: Int?) {
        require(minutes == null || minutes in SyncScheduler.INTERVALS) { "not an interval choice" }
        chooseTiming(
            account,
            {
                require(minutes == null || minutes <= it.intervalMinutes) { "longer than the full sync" }
                it.copy(checkMinutes = minutes)
            },
        ) { accounts.setCheckInterval(account.value, minutes) }
    }

    /** Manual on: no job, and Android's automatic sync off; off: both back at the kept intervals. */
    suspend fun setManual(account: AccountId, manual: Boolean) {
        chooseTiming(account, { it.copy(manual = manual) }) { accounts.setManual(account.value, manual) }
    }

    /** The schedule first, then the setting, as for [setSyncMode]. An unknown account changes nothing. */
    private suspend fun chooseTiming(account: AccountId, change: (SettingsEntity) -> SettingsEntity, save: suspend () -> Unit) {
        withContext(NonCancellable) {
            mutex.withLock {
                val record = state.record(account.value) ?: return@withLock
                val settings = state.settings(account.value) ?: return@withLock
                val chosen = change(settings)
                withContext(io) { scheduler.choose(record, chosen) }
                save()
            }
        }
    }

    /** Logs out, with no run in progress or starting until it's done; an unknown account changes nothing. */
    suspend fun logout(account: AccountId): LogoutResult = withContext(NonCancellable) {
        mutex.withLock {
            val record = state.record(account.value) ?: return@withLock LogoutResult(sessionEnded = false)
            coordinator.whileClosed(account) { signOut(record) }
        }
    }

    /**
     * Removes the account from this phone: log out, remove the Android account, then delete its rows (as the sync
     * adapter, since Android can leave them) and everything Room keeps for it. If AccountManager keeps the account, so
     * does the app, signed out. Rows the provider can't delete now (permission revoked, provider not answering) are
     * left for the next reconciliation, which deletes any rows of an account that's gone; rows under a name another
     * Android account of the app's type now holds are never deleted. No run is in progress or starts until it's done.
     */
    suspend fun removeFromPhone(account: AccountId): RemovalResult = withContext(NonCancellable) {
        mutex.withLock {
            val record = state.record(account.value) ?: return@withLock RemovalResult(accountRemoved = false, sessionEnded = false)
            coordinator.whileClosed(account) { remove(record) }
        }
    }

    /**
     * Reconciliation: compares the Room rows with the app's Android accounts, which alone decide whether an account
     * exists. A signed-in account gets its job and flags back (never Android's automatic sync), and a signed-out one
     * loses any leftover credentials; an Android account without a Room row gets one, signed in and in dry run; a Room
     * row without an Android account loses its credentials, job, and Room rows, unless an account of the app's type
     * whose `proton_user_id` can't be read holds its name (ambiguous, so kept). Then the provider rows under every name
     * no Android account of the app's type holds are deleted, as the sync adapter; a provider that can't list or delete
     * them now (permission revoked, not answering, missing) leaves them for next time. Called before every run, never
     * from inside one: it waits its turn (cancellably), then runs to its end.
     */
    suspend fun reconcile() = mutex.withLock { withContext(NonCancellable) { reconcileLocked() } }

    private suspend fun reconcileLocked() {
        val live = withContext(io) { directory.live() }
        // Names held by accounts of the app's type whose proton_user_id can't be read: ambiguous, so kept.
        val unreadable = withContext(io) { directory.names() } - live.values.toSet()
        val records = accounts.records()
        for (record in records) {
            when {
                AccountId(record.accountId) in live -> keep(record)
                record.androidAccountName !in unreadable -> forget(record)
            }
        }
        val known = records.mapTo(mutableSetOf()) { it.accountId }
        for ((id, name) in live) {
            if (id.value !in known) recreate(id, name)
        }
        sweep()
    }

    private suspend fun keep(record: AccountRecordEntity) {
        if (record.loginState == LoginState.SIGNED_OUT) withContext(io) { credentials.delete(AccountId(record.accountId)) }
        val settings = state.settings(record.accountId)
        withContext(io) { scheduler.reconcile(record, settings) }
    }

    /**
     * Room went ("Clear storage" keeps the Android account and its rows). Signed in, so the credentials decide: a run
     * without them reports signed out until the user logs in again.
     */
    private suspend fun recreate(id: AccountId, name: String) {
        val record = AccountRecordEntity(id.value, name, LoginState.ACTIVE)
        val settings = SettingsEntity(id.value)
        accounts.insertAccount(record, settings)
        withContext(io) { scheduler.reconcile(record, settings) }
    }

    /**
     * Its run is stopped first, as for a logout or removal, so it can't save maps after the Room rows go. The Room row
     * goes last, so a crash before it leaves the account for the next reconciliation to finish.
     */
    private suspend fun forget(record: AccountRecordEntity) {
        val account = AccountId(record.accountId)
        coordinator.whileClosed(account) {
            withContext(io) { credentials.delete(account) }
            withContext(io) { scheduler.cancel(account) }
            accounts.deleteAccount(record.accountId)
        }
    }

    private suspend fun sweep() {
        try {
            withContext(io) {
                val held = directory.names()
                for (name in deviceContacts.accountNamesWithRows() - held) deviceContacts.deleteAccountRows(name)
            }
        } catch (_: SecurityException) {
        } catch (_: ProviderUnavailableException) {
        } catch (_: IllegalArgumentException) {
            // No Contacts provider at all ("Unknown URL"): the rows wait, and a cleanup step never blocks a login.
        }
    }

    private suspend fun commit(session: NewSession, new: Credentials): LoginCommit {
        val id = session.accountId
        val records = try {
            // Reconcile first, so a crashed login's Room row neither blocks this one nor stands in for its account.
            reconcileLocked()
            accounts.records()
        } catch (failure: Exception) {
            endSession(new.tokens)
            throw failure
        }
        if (records.any { it.loginState == LoginState.ACTIVE && it.accountId != id.value }) {
            endSession(new.tokens)
            return LoginCommit.Refused(RefusalReason.OTHER_ACCOUNT_ACTIVE)
        }
        val own = records.firstOrNull { it.accountId == id.value }
        return if (own != null) resume(own, new) else add(session, new)
    }

    private suspend fun resume(own: AccountRecordEntity, new: Credentials): LoginCommit {
        val id = AccountId(own.accountId)
        try {
            withContext(io) { credentials.save(id, new) }
        } catch (failure: Exception) {
            endSession(new.tokens)
            throw failure
        }
        try {
            accounts.setLoginState(id.value, LoginState.ACTIVE)
        } catch (failure: Exception) {
            try {
                withContext(io) { credentials.delete(id) }
            } catch (undoFailure: Exception) {
                failure.addSuppressed(undoFailure)
            }
            endSession(new.tokens)
            throw failure
        }
        // Signed in from here on, and reported so: a failure setting the job and flags leaves them for
        // reconciliation, and a "failed" login would only be retried, replacing this session without ending it.
        try {
            val settings = state.settings(id.value)
            withContext(io) { scheduler.reconcile(own.copy(loginState = LoginState.ACTIVE), settings) }
        } catch (_: Exception) {
        }
        return LoginCommit.Committed(resumed = true)
    }

    private suspend fun add(session: NewSession, new: Credentials): LoginCommit {
        val id = session.accountId
        val added = try {
            accounts.insertAccount(AccountRecordEntity(id.value, session.accountName, LoginState.ACTIVE), SettingsEntity(id.value))
            withContext(io) { credentials.save(id, new) }
            withContext(io) { directory.add(id, session.accountName) }
        } catch (failure: Exception) {
            try {
                undo(id)
            } catch (undoFailure: Exception) {
                failure.addSuppressed(undoFailure)
            }
            endSession(new.tokens)
            throw failure
        }
        if (added) return LoginCommit.Committed(resumed = false)
        try {
            undo(id)
        } finally {
            endSession(new.tokens)
        }
        return LoginCommit.Refused(RefusalReason.NAME_IN_USE)
    }

    /**
     * Undoes a new account's writes: the credentials first, then its Room row. If the credentials can't be deleted
     * this throws and the Room row stays, so reconciliation finds both (the row has no Android account).
     */
    private suspend fun undo(id: AccountId) {
        withContext(io) { credentials.delete(id) }
        accounts.deleteAccount(id.value)
    }

    private suspend fun remove(record: AccountRecordEntity): RemovalResult {
        val account = AccountId(record.accountId)
        val ended = signOut(record).sessionEnded
        if (!withContext(io) { directory.remove(account) }) return RemovalResult(accountRemoved = false, sessionEnded = ended)
        // Every account with this ID is gone now, so one still holding the name is another account's.
        if (record.androidAccountName !in withContext(io) { directory.names() }) {
            try {
                withContext(io) { deviceContacts.deleteAccountRows(record.androidAccountName) }
            } catch (_: SecurityException) {
            } catch (_: ProviderUnavailableException) {
            } catch (_: IllegalArgumentException) {
            }
        }
        accounts.deleteAccount(record.accountId)
        return RemovalResult(accountRemoved = true, sessionEnded = ended)
    }

    /**
     * Logout's steps, called under the mutex inside `whileClosed`: the job and flags go, then `SIGNED_OUT` is written,
     * then one A5 if the account was signed in with readable credentials, then the credentials are deleted. A failed
     * delete throws, the account already signed out, so reconciliation finishes it.
     */
    private suspend fun signOut(record: AccountRecordEntity): LogoutResult {
        val account = AccountId(record.accountId)
        val settings = state.settings(record.accountId)
        withContext(io) { scheduler.reconcile(record.copy(loginState = LoginState.SIGNED_OUT), settings) }
        accounts.setLoginState(record.accountId, LoginState.SIGNED_OUT)
        val tokens = if (record.loginState != LoginState.ACTIVE) null else withContext(io) { credentials.load(account)?.use { it.tokens } }
        val ended = tokens != null && endSession(tokens)
        withContext(io) { credentials.delete(account) }
        return LogoutResult(sessionEnded = ended)
    }

    /** One A5 with [tokens] (a 401 from A5 never refreshes), at most 10 s; true when Proton confirmed it. */
    private suspend fun endSession(tokens: Tokens): Boolean = withTimeoutOrNull(A5_LIMIT) {
        try {
            newClient(FixedTokenStore(tokens)).logout()
            true
        } catch (_: ProtonError) {
            false
        }
    } ?: false

    /** The one session an A5 ends; it is never refreshed, so nothing is ever replaced. */
    private class FixedTokenStore(private val tokens: Tokens) : TokenStore {
        override fun current() = tokens

        override fun replace(tokens: Tokens) = error("an A5 never refreshes")

        override fun onSessionRejected(uid: String) {}
    }

    private companion object {
        val A5_LIMIT = 10.seconds
    }
}
