package dev.rboza.android.opensyncforproton.sync.schedule

import android.accounts.Account
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.ContentResolver
import android.content.Context
import android.content.SyncResult
import android.os.Bundle
import android.provider.ContactsContract
import dev.rboza.android.opensyncforproton.account.AccountId
import dev.rboza.android.opensyncforproton.sync.db.AccountStateDao
import dev.rboza.android.opensyncforproton.sync.db.Blocker
import dev.rboza.android.opensyncforproton.sync.db.ReportTrigger
import dev.rboza.android.opensyncforproton.sync.engine.SyncCoordinator
import dev.rboza.android.opensyncforproton.sync.engine.SyncRequest
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking

/**
 * The contacts SyncAdapter (D20), on Android's own sync thread, so it blocks. An initialization request only sets the
 * authority's syncable state: syncable when the account is signed in with sync on. An upload request (after a phone
 * edit) runs an upload-only sync, and nothing under Manual; any other request runs a full sync through the coordinator
 * in that state (Manual included); and nothing runs otherwise. A retryable run is an I/O error and an ended session an
 * auth error, so Android retries or asks; any other failure (the engine's, or reading Room) is a hard error, never
 * retried and never logged, so nothing reaches Android's sync thread, where an exception would end the app's process.
 * The account is found by its Android name. [reconcile] (the account lifecycle's) runs right before a run, and its
 * failure is a hard error too. The service that hands out this adapter, and its XML, are the app's.
 */
class ContactsSyncAdapter(
    context: Context,
    private val coordinator: SyncCoordinator,
    private val accounts: AccountStateDao,
    private val accountType: String,
    private val reconcile: suspend () -> Unit,
) : AbstractThreadedSyncAdapter(context, /* autoInitialize = */ false, /* allowParallelSyncs = */ false) {
    private val current = AtomicReference<Job?>()

    override fun onPerformSync(account: Account, extras: Bundle, authority: String, provider: ContentProviderClient?, syncResult: SyncResult) {
        val job = Job()
        current.set(job)
        try {
            runBlocking(job) { perform(account, extras, syncResult) }
        } catch (_: CancellationException) {
            // Cancelled by onSyncCanceled; Android expects nothing more.
        } finally {
            current.compareAndSet(job, null)
        }
    }

    /**
     * Cancels the request instead of interrupting the thread (Android's default, which would make `runBlocking` throw
     * on the sync thread); the run stops once no caller waits for it. A cancel that arrives before [onPerformSync] has
     * registered its job isn't seen, so that sync runs to its end.
     */
    override fun onSyncCanceled() {
        current.get()?.cancel()
    }

    private suspend fun perform(account: Account, extras: Bundle, syncResult: SyncResult) {
        if (account.type != accountType) return
        try {
            syncOrInitialize(account, extras, syncResult)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A hard error: Android shows it and doesn't retry.
            syncResult.databaseError = true
        }
    }

    private suspend fun syncOrInitialize(account: Account, extras: Bundle, syncResult: SyncResult) {
        val record = accounts.recordByAndroidName(account.name)
        val settings = record?.let { accounts.settings(it.accountId) }
        val on = syncOn(record, settings)
        if (extras.getBoolean(ContentResolver.SYNC_EXTRAS_INITIALIZE)) {
            ContentResolver.setIsSyncable(account, ContactsContract.AUTHORITY, if (on) 1 else 0)
            return
        }
        if (!on || record == null) return
        // Android asks for an upload after a phone edit: an upload-only sync, except under Manual, which runs only what
        // the user starts.
        val upload = extras.getBoolean(ContentResolver.SYNC_EXTRAS_UPLOAD)
        if (upload && settings?.manual != false) return
        // Before each run; only once sync is on, so a dry run still returns at once.
        reconcile()
        val request = if (upload) SyncRequest.Upload else SyncRequest.Full(trigger = ReportTrigger.ANDROID_SETTINGS)
        val report = coordinator.request(AccountId(record.accountId), request) ?: return
        when {
            report.retryable -> syncResult.stats.numIoExceptions++
            report.blocker == Blocker.SIGNED_OUT || report.blocker == Blocker.SESSION_EXPIRED -> syncResult.stats.numAuthExceptions++
        }
    }
}
