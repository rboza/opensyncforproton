package dev.rboza.android.opensyncforproton

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.ContactsContract
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import dev.rboza.android.opensyncforproton.account.AccountId
import dev.rboza.android.opensyncforproton.account.AccountManagerDirectory
import dev.rboza.android.opensyncforproton.account.FileCredentialStore
import dev.rboza.android.opensyncforproton.account.LoginInteractor
import dev.rboza.android.opensyncforproton.account.NewSession
import dev.rboza.android.opensyncforproton.account.ProtonLoginInteractor
import dev.rboza.android.opensyncforproton.crypto.GoItemCrypto
import dev.rboza.android.opensyncforproton.crypto.GoKeyHierarchy
import dev.rboza.android.opensyncforproton.crypto.GoSrpClient
import dev.rboza.android.opensyncforproton.crypto.KeystoreSecretBox
import dev.rboza.android.opensyncforproton.diagnostics.DiagnosticsLog
import dev.rboza.android.opensyncforproton.diagnostics.LogEntry
import dev.rboza.android.opensyncforproton.diagnostics.LogRecord
import dev.rboza.android.opensyncforproton.diagnostics.Pseudonyms
import dev.rboza.android.opensyncforproton.diagnostics.RecordingEngine
import dev.rboza.android.opensyncforproton.diagnostics.installKey
import dev.rboza.android.opensyncforproton.mapping.EzvcardContactMapper
import dev.rboza.android.opensyncforproton.mapping.MappingResult
import dev.rboza.android.opensyncforproton.notify.NotifyingEngine
import dev.rboza.android.opensyncforproton.notify.SyncProblemNotifier
import dev.rboza.android.opensyncforproton.proton.ProtonApi
import dev.rboza.android.opensyncforproton.proton.ProtonApiFactory
import dev.rboza.android.opensyncforproton.proton.ProtonConfig
import dev.rboza.android.opensyncforproton.proton.TokenStore
import dev.rboza.android.opensyncforproton.settings.appVersion
import dev.rboza.android.opensyncforproton.sync.db.AccountRecordEntity
import dev.rboza.android.opensyncforproton.sync.db.ConflictHoldEntity
import dev.rboza.android.opensyncforproton.sync.db.HoldChoice
import dev.rboza.android.opensyncforproton.sync.db.LastReportEntity
import dev.rboza.android.opensyncforproton.sync.db.ReportTrigger
import dev.rboza.android.opensyncforproton.sync.db.SettingsEntity
import dev.rboza.android.opensyncforproton.sync.db.SyncMode
import dev.rboza.android.opensyncforproton.sync.db.openSyncDatabase
import dev.rboza.android.opensyncforproton.sync.engine.ContactSyncEngine
import dev.rboza.android.opensyncforproton.sync.engine.EventLoop
import dev.rboza.android.opensyncforproton.sync.engine.RunReport
import dev.rboza.android.opensyncforproton.sync.engine.SyncCoordinator
import dev.rboza.android.opensyncforproton.sync.engine.SyncRequest
import dev.rboza.android.opensyncforproton.sync.lifecycle.AccountLifecycle
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
import dev.rboza.android.opensyncforproton.sync.provider.PhoneAccount
import dev.rboza.android.opensyncforproton.sync.provider.PhotoScaler
import dev.rboza.android.opensyncforproton.sync.provider.ProviderDeviceContacts
import dev.rboza.android.opensyncforproton.sync.provider.applyMerges
import dev.rboza.android.opensyncforproton.sync.provider.importContacts
import dev.rboza.android.opensyncforproton.sync.provider.importList
import dev.rboza.android.opensyncforproton.sync.provider.keepCopy
import dev.rboza.android.opensyncforproton.sync.provider.removeOriginals
import dev.rboza.android.opensyncforproton.sync.provider.scalePhoto
import dev.rboza.android.opensyncforproton.sync.schedule.ContactsSyncAdapter
import dev.rboza.android.opensyncforproton.sync.schedule.SyncScheduler
import dev.rboza.android.opensyncforproton.sync.schedule.SyncWorker
import java.io.File
import java.io.OutputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttp

/**
 * The composition root: builds every module's pieces once per process and hands them to the screens (as their AppGraph)
 * and to WorkManager (its configuration and the worker factory). Everything is built lazily, most of it by the start-up
 * reconciliation; no constructor loads the crypto library (native), which loads only for a run with credentials or a
 * login. Its own work (the coordinator's runs, diagnostics, the start-up reconciliation) runs in one app-wide scope on
 * the IO dispatcher, where one failure doesn't cancel the rest.
 */
open class OspApp : Application(), AppGraph, Configuration.Provider {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** One writer for the diagnostics files, so records keep their order and an export follows what came before it. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val logWriter = Dispatchers.IO.limitedParallelism(1)

    /** D4: the account type is the app's ID (`.debug` in debug and test builds). */
    private val accountType: String get() = packageName

    private val db by lazy { openSyncDatabase(this) }
    private val credentials by lazy { FileCredentialStore(File(noBackupFilesDir, "credentials"), packageName, KeystoreSecretBox()) }
    private val directory by lazy { AccountManagerDirectory(AccountManager.get(this), accountType) }
    private val deviceContacts by lazy {
        // Keep a copy and import use the local account Android names, never the default account for new contacts.
        ProviderDeviceContacts(
            contentResolver, accountType,
            localAccountName = ContactsContract.RawContacts.getLocalAccountName(this),
            localAccountType = ContactsContract.RawContacts.getLocalAccountType(this),
        )
    }
    private val scheduler by lazy { SyncScheduler(WorkManager.getInstance(this), accountType) }
    /** Proton's server; the end-to-end test's Application overrides it. */
    internal open val protonConfig: ProtonConfig get() = PROTON

    private val newClient: (TokenStore) -> ProtonApi = { ProtonApiFactory.create(protonConfig, it) }

    private val keyHierarchy by lazy { GoKeyHierarchy() }

    private val engine by lazy {
        val contacts = ContactSyncEngine(
            credentials, newClient, keyHierarchy, GoItemCrypto(), EzvcardContactMapper(), deviceContacts,
            db.contactMapDao(), db.lastReportDao(), db.pendingCreateDao(), db.conflictHoldDao(), db.eventCursorDao(), PhotoScaler(::scalePhoto), ::isOnline, System::currentTimeMillis, appVersion(this),
        )
        val notifying = NotifyingEngine(contacts, db.accountStateDao(), db.lastReportDao(), db.conflictHoldDao(), SyncProblemNotifier(this))
        RecordingEngine(notifying, ::log, System::nanoTime)
    }
    private val eventLoop by lazy {
        EventLoop(engine, db.eventCursorDao(), db.accountStateDao(), credentials, keyHierarchy, newClient, ::isOnline, System::currentTimeMillis)
    }
    private val coordinator by lazy {
        SyncCoordinator(engine, { engine.incremental(it, eventLoop::run) }, db.accountStateDao(), db.lastReportDao(), scope, System::currentTimeMillis)
    }
    private val lifecycle by lazy {
        AccountLifecycle(credentials, directory, db.accountDao(), db.accountStateDao(), deviceContacts, scheduler, coordinator, newClient, Dispatchers.IO)
    }

    /** The one contacts sync adapter of the process (Android binds `ContactsSyncService` for each sync). */
    internal val contactsSyncAdapter by lazy {
        ContactsSyncAdapter(this, coordinator, db.accountStateDao(), accountType, lifecycle::reconcile)
    }

    private val diagnostics by lazy { DiagnosticsLog(File(noBackupFilesDir, "diagnostics")) }

    /**
     * Null when the install key can't be read or written (a full disk, say): no records for this process. Any failure
     * counts, since it would otherwise escape a record's coroutine and end the process.
     */
    private val pseudonyms by lazy {
        try {
            Pseudonyms(installKey(File(noBackupFilesDir, "diagnostics.key")))
        } catch (_: Exception) {
            null
        }
    }

    override fun onCreate() {
        super.onCreate()
        // AndroidX Startup's provider is removed from the manifest; OkHttp learns the context here instead.
        OkHttp.initialize(this)
        reconcileInBackground()
    }

    /** The latest reconciliation [reconcileInBackground] started, so a test can wait for it. */
    internal var reconciling: Job? = null
        private set

    /**
     * Reconciliation (docs/sync.md § Account states), at app start and on `ACCOUNT_REMOVED`; a failure here is seen at
     * the next run or login, which reconcile too.
     */
    internal fun reconcileInBackground(): Job =
        scope.launch {
            try {
                lifecycle.reconcile()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Nothing of it is logged (D23).
            }
        }.also { reconciling = it }

    /**
     * For `ACCOUNT_REMOVED`: reconciles in the background and calls [finish] when that ends, or at [BROADCAST_HOLD].
     */
    internal fun reconcileForBroadcast(finish: () -> Unit) = finishWhenDone(scope, reconcileInBackground(), BROADCAST_HOLD, finish)

    override val workManagerConfiguration: Configuration by lazy {
        Configuration.Builder()
            // WorkManager logs each run's result at INFO, with its own details; nothing of it goes to logcat (D23).
            .setMinimumLoggingLevel(LOG_ASSERT)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
                        if (workerClassName == SyncWorker::class.java.name) {
                            SyncWorker(appContext, workerParameters, coordinator, db.accountStateDao(), accountType, lifecycle::reconcile)
                        } else {
                            null
                        }
                },
            )
            .build()
    }

    /**
     * The active network can reach the internet: the engine asks before its first request to Proton, and tells offline
     * from Proton unavailable with it after a network error.
     */
    internal fun isOnline(): Boolean {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val network = connectivity.activeNetwork ?: return false
        return connectivity.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    override fun newLoginInteractor(): LoginInteractor = ProtonLoginInteractor(newClient, GoSrpClient(), GoKeyHierarchy(), scope)

    override suspend fun completeLogin(session: NewSession): LoginCommit = lifecycle.completeLogin(session)

    override suspend fun accounts(): List<AccountRecordEntity> = db.accountDao().records()

    override suspend fun settings(account: AccountId): SettingsEntity? = db.accountStateDao().settings(account.value)

    override suspend fun lastReport(account: AccountId): LastReportEntity? = db.lastReportDao().report(account.value)

    override fun reportChanges(): Flow<Unit> = db.invalidationTracker.createFlow("last_report", emitInitialState = false).map {}

    override suspend fun hasCredentials(account: AccountId): Boolean = withContext(Dispatchers.IO) {
        credentials.load(account)?.use { true } ?: false
    }

    override suspend fun androidAutoSync(androidAccountName: String): Boolean = withContext(Dispatchers.IO) {
        ContentResolver.getMasterSyncAutomatically() &&
            ContentResolver.getSyncAutomatically(Account(androidAccountName, accountType), ContactsContract.AUTHORITY)
    }

    override fun hasContactsPermission(): Boolean = granted(Manifest.permission.READ_CONTACTS) && granted(Manifest.permission.WRITE_CONTACTS)

    override fun hasNotificationPermission(): Boolean = granted(Manifest.permission.POST_NOTIFICATIONS)

    private fun granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    override suspend fun setSyncMode(account: AccountId, mode: SyncMode) = lifecycle.setSyncMode(account, mode)

    override suspend fun dismissUpgradeNotice(account: AccountId) = db.accountDao().dismissUpgradeNotice(account.value)

    override suspend fun setInterval(account: AccountId, minutes: Int) = lifecycle.setInterval(account, minutes)
    override suspend fun setCheckInterval(account: AccountId, minutes: Int?) = lifecycle.setCheckInterval(account, minutes)
    override suspend fun setManual(account: AccountId, manual: Boolean) = lifecycle.setManual(account, manual)

    override suspend fun logout(account: AccountId): LogoutResult = lifecycle.logout(account)

    override suspend fun removeFromPhone(account: AccountId): RemovalResult = lifecycle.removeFromPhone(account)

    override suspend fun deviceContacts(account: AccountId, androidAccountName: String): List<DeviceContact> = withContext(Dispatchers.IO) {
        deviceContacts.query(PhoneAccount(account, androidAccountName))
    }

    /** Keep a copy: blocking provider calls on the IO dispatcher, so once started it runs to its end. */
    override suspend fun keepCopy(account: AccountId, androidAccountName: String): CopyResult = withContext(Dispatchers.IO) {
        keepCopy(deviceContacts, PhoneAccount(account, androidAccountName))
    }

    /**
     * The import list (docs/sync.md § Import from this phone): the local account's live contacts and the account's
     * rows, each with its data rows (importList keeps the live ones holding a Proton ID); a contact's card issue is
     * `create`'s with a placeholder `UID`.
     */
    override suspend fun importList(account: AccountId, androidAccountName: String): ImportList = withContext(Dispatchers.IO) {
        val local = deviceContacts.queryLocal().map { ReadContact(it, deviceContacts.readRows(it.rawContactId)) }
        val synced = deviceContacts.query(PhoneAccount(account, androidAccountName)).map { ReadContact(it, deviceContacts.readRows(it.rawContactId)) }
        val records = db.importRecordDao().records(account.value)
        val held = db.conflictHoldDao().holds(account.value).map { it.protonId }.toSet()
        val mapper = EzvcardContactMapper()
        val version = appVersion(this@OspApp)
        importList(local, synced, records, held) { graph -> (mapper.create(graph, CARD_CHECK_UID, version) as? MappingResult.Invalid)?.issues?.firstOrNull()?.code }
    }

    /**
     * Import, off the main thread; the screen calls it in `NonCancellable`, so it runs to its end. A copy made asks for
     * a full sync named after the import, which runs on in the app's scope: the result doesn't wait for it.
     */
    override suspend fun importContacts(account: AccountId, androidAccountName: String, chosen: List<ReadContact>): ActionResult {
        val result = withContext(Dispatchers.IO) { importContacts(deviceContacts, db.importRecordDao(), PhoneAccount(account, androidAccountName), chosen) }
        if (result.done > 0) syncImported(account)
        return result
    }

    /**
     * Merge's Apply, as [importContacts]: a write to a contact (not Keep Proton's, nor a choice that leaves Proton's
     * row as it is) asks for the same sync.
     */
    override suspend fun applyMerges(account: AccountId, androidAccountName: String, chosen: List<Pair<MergePair, MergeChoice>>): MergeResult {
        val result = withContext(Dispatchers.IO) { applyMerges(deviceContacts, db.importRecordDao(), PhoneAccount(account, androidAccountName), chosen) }
        if (result.wrote) syncImported(account)
        return result
    }

    /** Remove originals, off the main thread: the copies and Proton are untouched. */
    override suspend fun removeOriginals(account: AccountId, androidAccountName: String, originals: List<ImportedOriginal>): ActionResult =
        withContext(Dispatchers.IO) { removeOriginals(deviceContacts, db.importRecordDao(), PhoneAccount(account, androidAccountName), originals) }

    /**
     * A full sync named after the import, which runs on in the app's scope: the screen's result doesn't wait for it.
     */
    private fun syncImported(account: AccountId) {
        scope.launch {
            try {
                requestSync(account, SyncRequest.Full(trigger = ReportTrigger.IMPORT))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The run's report and record show it.
            }
        }
    }

    override suspend fun holds(account: AccountId): List<ConflictHoldEntity> = db.conflictHoldDao().holds(account.value)

    override suspend fun choose(hold: ConflictHoldEntity, choice: HoldChoice?): Boolean =
        db.conflictHoldDao().choose(hold.accountId, hold.protonId, hold.kind, hold.rawContactId, hold.version, hold.fingerprint, choice) == 1

    override fun log(entry: LogEntry) {
        val at = System.currentTimeMillis()
        scope.launch(logWriter) {
            val pseudonyms = pseudonyms ?: return@launch
            try {
                diagnostics.record(LogRecord(at, entry.operation, entry.account?.let(pseudonyms::account), entry.outcome, entry.counts, entry.durationMs))
            } catch (_: Exception) {
                // A record never fails what it describes; DiagnosticsLog already drops what it can't write.
            }
        }
    }

    override suspend fun exportLogs(out: OutputStream) = withContext(logWriter) { diagnostics.export(out) }

    override suspend fun reconcile() = lifecycle.reconcile()

    override suspend fun requestSync(account: AccountId, request: SyncRequest): RunReport? = coordinator.request(account, request)

    internal companion object {
        /** The base URL, and `x-pm-appversion` `Other` (D4; docs/protocol.md § Unknown). */
        val PROTON = ProtonConfig("https://mail-api.proton.me/".toHttpUrl(), "Other")

        /** The placeholder `UID` import's card check gives `create`: the card is never sent. */
        const val CARD_CHECK_UID = "urn:uuid:00000000-0000-0000-0000-000000000000"

        /** The platform log's assert level (7), above every level WorkManager logs at. */
        const val LOG_ASSERT = 7
    }
}
