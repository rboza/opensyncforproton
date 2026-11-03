package dev.rboza.android.opensyncforproton

import dev.rboza.android.opensyncforproton.account.AccountId
import dev.rboza.android.opensyncforproton.diagnostics.LogEntry
import java.io.OutputStream
import dev.rboza.android.opensyncforproton.account.LoginInteractor
import dev.rboza.android.opensyncforproton.account.NewSession
import dev.rboza.android.opensyncforproton.login.FakeLoginInteractor
import dev.rboza.android.opensyncforproton.sync.db.AccountRecordEntity
import dev.rboza.android.opensyncforproton.sync.db.ConflictHoldEntity
import dev.rboza.android.opensyncforproton.sync.db.HoldChoice
import dev.rboza.android.opensyncforproton.sync.db.LastReportEntity
import dev.rboza.android.opensyncforproton.sync.db.LoginState
import dev.rboza.android.opensyncforproton.sync.db.ReportTrigger
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * The composition root's stand-in for the screens' tests: Room's rows, credentials, and Android's switches as plain
 * values, and a log of the calls that change something.
 */
class FakeAppGraph : AppGraph {
    val interactors = mutableListOf<FakeLoginInteractor>()
    var next: () -> FakeLoginInteractor = { FakeLoginInteractor() }
    var commit: suspend () -> LoginCommit = { LoginCommit.Committed(resumed = false) }

    var records = listOf<AccountRecordEntity>()
    val settings = mutableMapOf<String, SettingsEntity>()
    val reports = mutableMapOf<String, LastReportEntity>()
    val readable = mutableSetOf<String>()
    var autoSync = true

    /** Any read throws this, when set. */
    var readFailure: Exception? = null

    /** Only the settings read throws this, when set. */
    var settingsFailure: Exception? = null

    /** When set, the accounts read takes its rows, then waits for this before answering with them. */
    var accountsGate: (suspend () -> Unit)? = null

    /** Both contacts permissions are granted. */
    var permission = true
    var notificationPermission = true
    var setSyncModeFailure: Exception? = null

    /** When set, setSyncMode waits for this before changing the mode. */
    var setSyncModeGate: (suspend () -> Unit)? = null

    /** The account's rows on the phone, or the exception reading them throws. */
    var rows: List<DeviceContact> = emptyList()
    var rowsFailure: Exception? = null

    /** When set, reading the rows waits for this first. */
    var rowsGate: (suspend () -> Unit)? = null

    /** Room's `conflict_hold` rows; only the holds read throws [holdsFailure], and only choose [chooseFailure]. */
    val holds = mutableListOf<ConflictHoldEntity>()
    var holdsFailure: Exception? = null
    var chooseFailure: Exception? = null
    var logoutResult = LogoutResult(sessionEnded = true)
    var removalResult = RemovalResult(accountRemoved = true, sessionEnded = true)
    var lifecycleFailure: Exception? = null

    /** Logout writes SIGNED_OUT, then throws this (as a credential delete that fails does). */
    var logoutFailureAfterSignOut: Exception? = null

    /** Removal signs out, then throws this (as AccountManager or Room failing afterwards does). */
    var removalFailureAfterSignOut: Exception? = null

    /** When set, the timing, logout, and removeFromPhone wait for this first. */
    var lifecycleGate: (suspend () -> Unit)? = null

    val calls = mutableListOf<String>()

    /** What the screens recorded (AppGraph.log). */
    val logged = mutableListOf<LogEntry>()
    var exportFailure: Exception? = null
    var reconcile: suspend () -> Unit = {}
    var request: suspend (AccountId, SyncRequest) -> RunReport? = { _, _ -> null }

    override fun newLoginInteractor(): LoginInteractor = next().also { interactors += it }

    override suspend fun completeLogin(session: NewSession): LoginCommit = commit()

    override suspend fun accounts(): List<AccountRecordEntity> {
        val rows = read { records }
        accountsGate?.invoke()
        return rows
    }

    override suspend fun settings(account: AccountId): SettingsEntity? = read {
        settingsFailure?.let { throw it }
        settings[account.value]
    }

    /** When set, the report read takes its row, then waits for this before answering with it. */
    var reportGate: (suspend () -> Unit)? = null

    override suspend fun lastReport(account: AccountId): LastReportEntity? {
        val row = read { reports[account.value] }
        reportGate?.invoke()
        return row
    }

    override suspend fun hasCredentials(account: AccountId): Boolean = read { account.value in readable }

    override suspend fun androidAutoSync(androidAccountName: String): Boolean = read { autoSync }

    override fun hasContactsPermission(): Boolean = permission

    override fun hasNotificationPermission(): Boolean = notificationPermission

    override suspend fun setSyncMode(account: AccountId, mode: SyncMode) {
        calls += "setSyncMode ${account.value} $mode"
        setSyncModeGate?.invoke()
        setSyncModeFailure?.let { throw it }
        settings[account.value] = (settings[account.value] ?: SettingsEntity(account.value)).copy(syncMode = mode)
    }

    var dismissFailure: Exception? = null

    override suspend fun dismissUpgradeNotice(account: AccountId) {
        calls += "dismissUpgradeNotice ${account.value}"
        dismissFailure?.let { throw it }
        settings[account.value]?.let { settings[account.value] = it.copy(upgradeNotice = false) }
    }

    override suspend fun setInterval(account: AccountId, minutes: Int) {
        calls += "setInterval ${account.value} $minutes"
        lifecycle()
        val now = settings[account.value] ?: SettingsEntity(account.value)
        settings[account.value] = now.copy(intervalMinutes = minutes, checkMinutes = now.checkMinutes?.coerceAtMost(minutes))
    }

    override suspend fun setCheckInterval(account: AccountId, minutes: Int?) {
        calls += "setCheckInterval ${account.value} $minutes"
        lifecycle()
        settings[account.value] = (settings[account.value] ?: SettingsEntity(account.value)).copy(checkMinutes = minutes)
    }

    override suspend fun setManual(account: AccountId, manual: Boolean) {
        calls += "setManual ${account.value} $manual"
        lifecycle()
        settings[account.value] = (settings[account.value] ?: SettingsEntity(account.value)).copy(manual = manual)
    }

    override suspend fun logout(account: AccountId): LogoutResult {
        calls += "logout ${account.value}"
        lifecycle()
        records = records.map { if (it.accountId == account.value) it.copy(loginState = LoginState.SIGNED_OUT) else it }
        logoutFailureAfterSignOut?.let { throw it }
        return logoutResult
    }

    override suspend fun removeFromPhone(account: AccountId): RemovalResult {
        calls += "removeFromPhone ${account.value}"
        lifecycle()
        removalFailureAfterSignOut?.let { failure ->
            records = records.map { if (it.accountId == account.value) it.copy(loginState = LoginState.SIGNED_OUT) else it }
            throw failure
        }
        if (removalResult.accountRemoved) {
            records = records.filterNot { it.accountId == account.value }
        } else {
            records = records.map { if (it.accountId == account.value) it.copy(loginState = LoginState.SIGNED_OUT) else it }
        }
        return removalResult
    }

    override suspend fun deviceContacts(account: AccountId, androidAccountName: String): List<DeviceContact> {
        calls += "deviceContacts ${account.value} $androidAccountName"
        rowsGate?.invoke()
        rowsFailure?.let { throw it }
        return rows
    }

    /** What Keep a copy gives, or the exception it throws, after waiting for [copyGate] when set. */
    var copyResult = CopyResult(0, 0)
    var copyFailure: Exception? = null
    var copyGate: (suspend () -> Unit)? = null

    override suspend fun keepCopy(account: AccountId, androidAccountName: String): CopyResult {
        calls += "keepCopy ${account.value} $androidAccountName"
        copyGate?.invoke()
        copyFailure?.let { throw it }
        return copyResult
    }

    /**
     * What the import list read gives (taken before waiting for [importListGate], when set), or the exception it
     * throws.
     */
    var importList = ImportList(emptyList(), emptyList(), emptyList())
    var importListFailure: Exception? = null
    var importListGate: (suspend () -> Unit)? = null

    override suspend fun importList(account: AccountId, androidAccountName: String): ImportList {
        calls += "importList ${account.value} $androidAccountName"
        val list = importList
        importListGate?.invoke()
        importListFailure?.let { throw it }
        return list
    }

    /** What an import gives, or the exception it throws, after waiting for [importGate] when set. */
    var importResult = ActionResult(0, 0)
    var importFailure: Exception? = null
    var importGate: (suspend () -> Unit)? = null

    override suspend fun importContacts(account: AccountId, androidAccountName: String, chosen: List<ReadContact>): ActionResult {
        calls += "importContacts ${account.value} $androidAccountName ${chosen.map { it.row.rawContactId }}"
        importGate?.invoke()
        importFailure?.let { throw it }
        return importResult
    }

    /** What applying merge choices gives, or the exception it throws, after waiting for [mergeGate] when set. */
    var mergeResult = MergeResult()
    var mergeFailure: Exception? = null
    var mergeGate: (suspend () -> Unit)? = null

    override suspend fun applyMerges(account: AccountId, androidAccountName: String, chosen: List<Pair<MergePair, MergeChoice>>): MergeResult {
        calls += "applyMerges ${account.value} $androidAccountName ${chosen.map { (pair, choice) -> "${pair.original.row.rawContactId}:$choice" }}"
        mergeGate?.invoke()
        mergeFailure?.let { throw it }
        return mergeResult
    }

    /** What removing originals gives, or the exception it throws, after waiting for [removeGate] when set. */
    var removeResult = ActionResult(0, 0)
    var removeFailure: Exception? = null
    var removeGate: (suspend () -> Unit)? = null

    /** The originals each removal was handed, in order. */
    val removedOriginals = mutableListOf<List<ImportedOriginal>>()

    override suspend fun removeOriginals(account: AccountId, androidAccountName: String, originals: List<ImportedOriginal>): ActionResult {
        calls += "removeOriginals ${account.value} $androidAccountName ${originals.map { it.original.rawContactId }}"
        removedOriginals += originals
        removeGate?.invoke()
        removeFailure?.let { throw it }
        return removeResult
    }

    override suspend fun holds(account: AccountId): List<ConflictHoldEntity> = read {
        holdsFailure?.let { throw it }
        holds.filter { it.accountId == account.value }
    }

    /** As ConflictHoldDao.choose: saved only while the hold still records the state [hold] does. */
    override suspend fun choose(hold: ConflictHoldEntity, choice: HoldChoice?): Boolean {
        calls += "choose ${hold.protonId} $choice"
        chooseFailure?.let { throw it }
        val index = holds.indexOfFirst { it.copy(choice = null) == hold.copy(choice = null) }
        if (index < 0) return false
        holds[index] = holds[index].copy(choice = choice)
        return true
    }

    private suspend fun lifecycle() {
        lifecycleGate?.invoke()
        lifecycleFailure?.let { throw it }
    }

    /** When set, AppGraph.log throws it (the real one never does; the screens must cope anyway). */
    var logFailure: Exception? = null

    override fun log(entry: LogEntry) {
        logFailure?.let { throw it }
        logged += entry
    }

    override suspend fun exportLogs(out: OutputStream) {
        calls += "exportLogs"
        exportFailure?.let { throw it }
        out.write("made-up log line\n".toByteArray())
    }

    override suspend fun reconcile() {
        calls += "reconcile"
        reconcile.invoke()
    }

    override suspend fun requestSync(account: AccountId, request: SyncRequest): RunReport? {
        val name = when (request) {
            SyncRequest.DryRun -> "DRY_RUN"
            SyncRequest.Upload -> "UPLOAD"
            SyncRequest.Incremental -> "INCREMENTAL"
            is SyncRequest.Full -> listOfNotNull(
                "FULL",
                request.confirmedPhoneDeletions?.let { "confirming $it" },
                request.confirmedRemoteDeletions?.let { "confirming $it remote" },
                request.trigger.takeIf { it != ReportTrigger.SYNC_NOW }?.let { "started by $it" },
            ).joinToString(" ")
        }
        calls += "request ${account.value} $name"
        return this.request(account, request)
    }

    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** When set, watching the reports throws this. */
    var reportChangesFailure: Exception? = null

    /** Room's notice that a stored report changed, to whoever watches. */
    fun reportSaved() = check(changes.tryEmit(Unit))

    override fun reportChanges(): Flow<Unit> = flow {
        reportChangesFailure?.let { throw it }
        emitAll(changes)
    }

    /** How many reads of the stores started. */
    var reads = 0
        private set

    private fun <T> read(value: () -> T): T {
        reads++
        readFailure?.let { throw it }
        return value()
    }
}
