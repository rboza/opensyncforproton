package dev.rboza.android.opensyncforproton

import dev.rboza.android.opensyncforproton.account.AccountId
import dev.rboza.android.opensyncforproton.account.LoginInteractor
import dev.rboza.android.opensyncforproton.account.NewSession
import dev.rboza.android.opensyncforproton.diagnostics.LogEntry
import java.io.OutputStream
import dev.rboza.android.opensyncforproton.sync.db.AccountRecordEntity
import dev.rboza.android.opensyncforproton.sync.db.ConflictHoldEntity
import dev.rboza.android.opensyncforproton.sync.db.HoldChoice
import dev.rboza.android.opensyncforproton.sync.db.LastReportEntity
import dev.rboza.android.opensyncforproton.sync.db.SettingsEntity
import dev.rboza.android.opensyncforproton.sync.db.SyncMode
import dev.rboza.android.opensyncforproton.sync.engine.RunReport
import dev.rboza.android.opensyncforproton.sync.engine.SyncRequest
import dev.rboza.android.opensyncforproton.sync.lifecycle.LoginCommit
import dev.rboza.android.opensyncforproton.sync.lifecycle.LogoutResult
import dev.rboza.android.opensyncforproton.sync.lifecycle.RemovalResult
import dev.rboza.android.opensyncforproton.sync.provider.ActionResult
import dev.rboza.android.opensyncforproton.sync.provider.CopyResult
import dev.rboza.android.opensyncforproton.sync.provider.DeviceContact
import dev.rboza.android.opensyncforproton.sync.provider.ImportList
import dev.rboza.android.opensyncforproton.sync.provider.ImportedOriginal
import dev.rboza.android.opensyncforproton.sync.provider.MergeChoice
import dev.rboza.android.opensyncforproton.sync.provider.MergePair
import dev.rboza.android.opensyncforproton.sync.provider.MergeResult
import dev.rboza.android.opensyncforproton.sync.provider.ReadContact
import kotlinx.coroutines.flow.Flow

/**
 * What the screens need from the composition root, which the Application implements. An interface so the activity's
 * tests can give fakes.
 */
interface AppGraph {
    /**
     * A fresh interactor for one login attempt. Its A5 scope must outlive the screen: the screen's `cancel()` runs
     * from `ViewModel.onCleared()`, after `viewModelScope` has ended.
     */
    fun newLoginInteractor(): LoginInteractor

    /** AccountLifecycle.completeLogin. */
    suspend fun completeLogin(session: NewSession): LoginCommit

    // The rest is for the main screen and the screens it opens. Each call that blocks (Room, the credential store,
    // Android's sync settings) does so off the main thread.

    /** AccountDao.records: every account Room keeps, signed-out ones included. */
    suspend fun accounts(): List<AccountRecordEntity>

    /** AccountStateDao.settings; null means the defaults (dry run). */
    suspend fun settings(account: AccountId): SettingsEntity?

    /** LastReportDao.report: counts only. */
    suspend fun lastReport(account: AccountId): LastReportEntity?

    /** Emits each time Room's `last_report` changes, whatever run saved it; nothing at once. */
    fun reportChanges(): Flow<Unit>

    /**
     * Whether the account's credentials can be read (CredentialStore.load, closed at once); a signed-in account whose
     * can't is a session expired (docs/sync.md § Account states).
     */
    suspend fun hasCredentials(account: AccountId): Boolean

    /** Android's global sync switch and the account's own for contacts are both on. */
    suspend fun androidAutoSync(androidAccountName: String): Boolean

    /** Both READ_CONTACTS and WRITE_CONTACTS are granted. */
    fun hasContactsPermission(): Boolean

    /** POST_NOTIFICATIONS is granted. */
    fun hasNotificationPermission(): Boolean

    /** AccountLifecycle.setSyncMode. */
    suspend fun setSyncMode(account: AccountId, mode: SyncMode)

    /** AccountDao.dismissUpgradeNotice: the user dismissed the upgrade notice (docs/sync.md § Sync mode). */
    suspend fun dismissUpgradeNotice(account: AccountId)

    /** AccountLifecycle.setInterval: the full sync's, a listed interval. */
    suspend fun setInterval(account: AccountId, minutes: Int)

    /**
     * AccountLifecycle.setCheckInterval: a listed interval no longer than the full sync's, or null for Same as full
     * sync.
     */
    suspend fun setCheckInterval(account: AccountId, minutes: Int?)

    /** AccountLifecycle.setManual. */
    suspend fun setManual(account: AccountId, manual: Boolean)

    /** AccountLifecycle.logout. */
    suspend fun logout(account: AccountId): LogoutResult

    /** AccountLifecycle.removeFromPhone. */
    suspend fun removeFromPhone(account: AccountId): RemovalResult

    /**
     * DeviceContacts.query for the account (its rows, deleted ones too), for Remove from this phone's and Keep a copy's
     * counts; throws what the provider throws (a revoked permission, say).
     */
    suspend fun deviceContacts(account: AccountId, androidAccountName: String): List<DeviceContact>

    /**
     * keepCopy for the account, off the main thread: copies its live rows into the phone's local account
     * (docs/sync.md § Keep a copy). Once started it runs to its end; throws what the provider throws.
     */
    suspend fun keepCopy(account: AccountId, androidAccountName: String): CopyResult

    /**
     * importList for the account, off the main thread (docs/sync.md § Import from this phone): the phone's local
     * account's live contacts and the account's own live rows, each with its data rows, its import records, and its
     * held Proton IDs. Throws what the provider throws.
     */
    suspend fun importList(account: AccountId, androidAccountName: String): ImportList

    /**
     * importContacts for the account, off the main thread: copies [chosen] into the account, then, after at least one
     * copy, asks for a full sync started by the import without waiting for it. Room writes suspend, so a caller that
     * must see it to its end calls it in `NonCancellable`; throws what the provider throws.
     */
    suspend fun importContacts(account: AccountId, androidAccountName: String, chosen: List<ReadContact>): ActionResult

    /**
     * applyMerges for the account, off the main thread: settles each of [chosen] as chosen, then, after a write to a
     * contact, asks for a full sync started by the import without waiting for it. Room writes suspend, so a caller that
     * must see it to its end calls it in `NonCancellable`; throws what the provider throws.
     */
    suspend fun applyMerges(account: AccountId, androidAccountName: String, chosen: List<Pair<MergePair, MergeChoice>>): MergeResult

    /**
     * removeOriginals for the account, off the main thread: deletes the removable ones of [originals] from the phone's
     * local account, and asks for no sync, since the copies and Proton stay as they are. Room writes suspend, so a
     * caller that must see it to its end calls it in `NonCancellable`; throws what the provider throws.
     */
    suspend fun removeOriginals(account: AccountId, androidAccountName: String, originals: List<ImportedOriginal>): ActionResult

    /** ConflictHoldDao.holds: what real syncs left for the user to answer (docs/sync.md § Conflicts). */
    suspend fun holds(account: AccountId): List<ConflictHoldEntity>

    /**
     * ConflictHoldDao.choose with the kind, row, `VERSION`, and fingerprint [hold] records: whether the hold still
     * recorded them, so [choice] (null: Skip) was saved.
     */
    suspend fun choose(hold: ConflictHoldEntity, choice: HoldChoice?): Boolean

    /**
     * Records [entry] in the diagnostics log (docs/architecture.md § Diagnostics): the composition root adds the time
     * and the account's pseudonym, and writes off the main thread; it never throws.
     */
    fun log(entry: LogEntry)

    /** DiagnosticsLog.export, off the main thread: Export logs. */
    suspend fun exportLogs(out: OutputStream)

    /** AccountLifecycle.reconcile, which Sync now calls before asking for a run. */
    suspend fun reconcile()

    /** SyncCoordinator.request. */
    suspend fun requestSync(account: AccountId, request: SyncRequest): RunReport?
}
