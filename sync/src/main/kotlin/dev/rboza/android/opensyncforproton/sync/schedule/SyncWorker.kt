package dev.rboza.android.opensyncforproton.sync.schedule

import android.accounts.Account
import android.content.ContentResolver
import android.content.Context
import android.provider.ContactsContract
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlin.coroutines.cancellation.CancellationException
import dev.rboza.android.opensyncforproton.account.AccountId
import dev.rboza.android.opensyncforproton.sync.db.AccountStateDao
import dev.rboza.android.opensyncforproton.sync.engine.SyncCoordinator
import dev.rboza.android.opensyncforproton.sync.engine.SyncRequest

/**
 * The periodic job (D20): an incremental run through the coordinator (its event loop adds a full sync when one is due),
 * skipped while Android's global or per-account sync switch is off. A run that ends with a failure that may pass by
 * itself is retried with the job's backoff; anything else waits for the next period. An engine failure is a failed run,
 * so WorkManager never logs it (docs/architecture.md § Diagnostics). Stopping the job cancels its request.
 * [accountType] is the app's account type (D4). [reconcile] (the account lifecycle's) runs right before the request,
 * and its failure is a failed run too.
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
    private val coordinator: SyncCoordinator,
    private val accounts: AccountStateDao,
    private val accountType: String,
    private val reconcile: suspend () -> Unit,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val account = inputData.getString(ACCOUNT_ID) ?: return Result.failure()
        // An account that's gone runs nothing.
        val record = accounts.record(account) ?: return Result.success()
        val android = Account(record.androidAccountName, accountType)
        val switchedOn = ContentResolver.getMasterSyncAutomatically() &&
            ContentResolver.getSyncAutomatically(android, ContactsContract.AUTHORITY)
        if (!switchedOn) return Result.success()
        val report = try {
            reconcile()
            coordinator.request(AccountId(account), SyncRequest.Incremental)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return Result.failure()
        } ?: return Result.success()
        return if (report.retryable) Result.retry() else Result.success()
    }

    companion object {
        /** The job's input: the Proton user ID of the account it syncs. */
        const val ACCOUNT_ID = "account_id"
    }
}
