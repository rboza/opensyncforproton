package dev.rboza.android.opensyncforproton.sync.schedule

import android.accounts.Account
import android.content.ContentResolver
import android.provider.ContactsContract
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.rboza.android.opensyncforproton.account.AccountId
import dev.rboza.android.opensyncforproton.sync.db.AccountRecordEntity
import dev.rboza.android.opensyncforproton.sync.db.LoginState
import dev.rboza.android.opensyncforproton.sync.db.SettingsEntity
import dev.rboza.android.opensyncforproton.sync.db.SyncMode
import java.util.concurrent.TimeUnit

/**
 * Keeps an account's periodic job and Android's sync flags matching its state. Signed in with sync on, the contacts
 * authority is syncable, and with Manual off there is one job per account at the check's period (the full sync's under
 * Same as full sync), network required, backing off exponentially from 5 minutes; otherwise the authority isn't
 * syncable and there's no job. Each run is incremental, and the full sync's interval is when its event loop adds a full
 * sync. [accountType] is the app's account type (D4).
 */
class SyncScheduler(private val workManager: WorkManager, private val accountType: String) {
    /**
     * The user chose a mode, an interval, or Manual: Android's automatic sync turned on with Manual off or off with it
     * on, then what [reconcile] does. In that order, since reconciliation never turns automatic sync back on: a crash
     * in between can't leave sync on with it never turned on.
     */
    fun choose(record: AccountRecordEntity, settings: SettingsEntity) {
        ContentResolver.setSyncAutomatically(android(record), ContactsContract.AUTHORITY, !settings.manual)
        reconcile(record, settings)
    }

    /**
     * Makes the job and the syncable flag match; never changes Android's automatic sync, which the user may have
     * turned off in Android Settings. A job's intervals that aren't [INTERVALS], or a check longer than the full sync,
     * throw IllegalArgumentException.
     */
    fun reconcile(record: AccountRecordEntity, settings: SettingsEntity?) {
        val on = syncOn(record, settings)
        ContentResolver.setIsSyncable(android(record), ContactsContract.AUTHORITY, if (on) 1 else 0)
        val account = AccountId(record.accountId)
        if (settings == null || !on || settings.manual) {
            workManager.cancelUniqueWork(workName(account))
        } else {
            val check = settings.checkMinutes
            require(settings.intervalMinutes in INTERVALS && (check == null || check in INTERVALS)) { "not an interval choice" }
            require(check == null || check <= settings.intervalMinutes) { "a check longer than the full sync" }
            // UPDATE keeps the job and its period's start: a job that has run is next run at that start plus the
            // (new) period, at once when that has passed; one that hasn't run yet runs as soon as the network is up,
            // and one backing off keeps its backoff.
            val period = check ?: settings.intervalMinutes
            workManager.enqueueUniquePeriodicWork(workName(account), ExistingPeriodicWorkPolicy.UPDATE, request(account, period))
        }
    }

    /** An account that's gone: only its job is cancelled, since Android dropped its flags with it. */
    fun cancel(account: AccountId) {
        workManager.cancelUniqueWork(workName(account))
    }

    private fun android(record: AccountRecordEntity) = Account(record.androidAccountName, accountType)

    companion object {
        /**
         * The interval choices, in minutes: 15 or 30 minutes, or 1, 2, 6, 12, or 24 hours (also the settings screen's).
         */
        val INTERVALS = setOf(15, 30, 60, 120, 360, 720, 1440)

        /** Periodic jobs only, so no other kind of job can take this name. */
        internal fun workName(account: AccountId) = "sync.periodic.${account.value}"

        internal fun request(account: AccountId, periodMinutes: Int): PeriodicWorkRequest =
            PeriodicWorkRequestBuilder<SyncWorker>(periodMinutes.toLong(), TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .setInputData(workDataOf(SyncWorker.ACCOUNT_ID to account.value))
                .build()
    }
}

/** Signed in with sync on: the authority is syncable and Android's requests run. */
internal fun syncOn(record: AccountRecordEntity?, settings: SettingsEntity?) =
    record?.loginState == LoginState.ACTIVE && settings?.syncMode == SyncMode.ON
