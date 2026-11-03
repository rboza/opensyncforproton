package dev.rboza.android.opensyncforproton.sync.engine

import dev.rboza.android.opensyncforproton.account.AccountId
import dev.rboza.android.opensyncforproton.sync.db.AccountStateDao
import dev.rboza.android.opensyncforproton.sync.db.Blocker
import dev.rboza.android.opensyncforproton.sync.db.LastReportDao
import dev.rboza.android.opensyncforproton.sync.db.LoginState
import dev.rboza.android.opensyncforproton.sync.db.ReportKind
import dev.rboza.android.opensyncforproton.sync.db.ReportTrigger
import dev.rboza.android.opensyncforproton.sync.db.SyncMode
import dev.rboza.android.opensyncforproton.sync.provider.PhoneAccount
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** What a caller asks for; [Upload] is Android's after a phone edit, [Incremental] the periodic job's. */
sealed interface SyncRequest {
    data object DryRun : SyncRequest

    /**
     * [confirmedPhoneDeletions] and [confirmedRemoteDeletions] are the user's confirmations of each direction's bulk
     * guard: the count the screen asked with. [trigger] is what started it: Sync now, Start, Android Settings, or an
     * import.
     */
    data class Full(
        val confirmedPhoneDeletions: Int? = null, val confirmedRemoteDeletions: Int? = null, val trigger: ReportTrigger = ReportTrigger.SYNC_NOW,
    ) : SyncRequest

    data object Upload : SyncRequest

    /** The event loop's run: it polls the feed, then a full or upload-only sync. */
    data object Incremental : SyncRequest
}

/** [ContactSyncEngine] behind an interface, so the coordinator's tests can stand in for it. */
interface SyncEngine {
    suspend fun dryRun(account: PhoneAccount): RunReport

    /**
     * [confirmedPhoneDeletions], [confirmedRemoteDeletions]: the run's confirmations of each direction's bulk guard, if
     * any; [trigger]: what started it, which its report names.
     */
    suspend fun sync(
        account: PhoneAccount, confirmedPhoneDeletions: Int? = null, confirmedRemoteDeletions: Int? = null, trigger: ReportTrigger = ReportTrigger.SYNC_NOW,
    ): RunReport

    /** [trigger]: phone edits, or the event loop's check that found nothing on Proton. */
    suspend fun upload(account: PhoneAccount, trigger: ReportTrigger = ReportTrigger.PHONE_CHANGES): RunReport

    /** The event loop's check failed with [blocker] and no full sync was due: its report, stored. */
    suspend fun failedCheck(account: PhoneAccount, blocker: Blocker): RunReport
}

/**
 * Every run goes through here (D20): one run at a time per account, and requests that arrive during a run merge into a
 * single follow-up that every one of them waits for. A run starts as a full sync if one was asked for and the account
 * is signed in with sync on, otherwise as a dry run if one was asked for, otherwise as an incremental run, then an
 * upload-only sync, if one was asked for with sync on, otherwise not at all (`null`); the account's state and mode are
 * read as it starts. A full sync carries, for each direction's bulk guard, the smallest confirmation its requests
 * carried, if any. A dry run of a signed-out account stores a report saying so, without the engine. A run is cancelled
 * once every caller waiting for it has been cancelled. Runs execute in [scope] (`Dispatchers.IO` in the app), so a
 * caller never runs one itself.
 */
class SyncCoordinator(
    private val engine: SyncEngine,
    /** The event loop's run ([EventLoop.run] in the app). */
    private val incremental: suspend (PhoneAccount) -> RunReport,
    private val accounts: AccountStateDao,
    private val reports: LastReportDao,
    private val scope: CoroutineScope,
    private val now: () -> Long,
) {
    /** One run, started or waiting to follow the current one. */
    private class Run(request: SyncRequest) {
        val requests = mutableSetOf(request)
        val result = CompletableDeferred<RunReport?>()
        var waiters = 0
        var job: Job? = null
    }

    private class AccountRuns {
        var current: Run? = null
        var next: Run? = null
        var closed = false
    }

    private val lock = Any()
    private val runs = HashMap<AccountId, AccountRuns>()

    /**
     * The report of the run this request joined, or `null` when nothing ran (see the class). An engine failure is
     * rethrown.
     */
    suspend fun request(account: AccountId, request: SyncRequest): RunReport? {
        val run = synchronized(lock) {
            val state = runs.getOrPut(account) { AccountRuns() }
            if (state.closed) return null
            val current = state.current
            val run = if (current == null) {
                Run(request).also { state.current = it; start(account, it) }
            } else {
                (state.next ?: Run(request).also { state.next = it }).also { it.requests += request }
            }
            run.waiters++
            run
        }
        try {
            return run.result.await()
        } catch (cancelled: CancellationException) {
            synchronized(lock) { leave(account, run) }
            throw cancelled
        }
    }

    /**
     * Runs [block] while the account takes no runs: the current run is cancelled and has ended before [block] starts, a
     * waiting follow-up is dropped, their callers get `null`, and so does every request made until [block] returns.
     * Logout, turning sync off, removal, and reconciliation forgetting a gone account change the account inside it, so
     * no run can start in the old state and write after them. Calls for one account never nest (the lifecycle's mutex).
     */
    suspend fun <T> whileClosed(account: AccountId, block: suspend () -> T): T {
        val stopping = synchronized(lock) {
            val state = runs.getOrPut(account) { AccountRuns() }
            check(!state.closed) { "already closed" }
            state.closed = true
            state.next?.result?.complete(null)
            state.next = null
            state.current?.also { it.job?.cancel() }
        }
        try {
            // The run's job completes its result last, after it has left `current`.
            stopping?.result?.join()
            return block()
        } finally {
            synchronized(lock) { checkNotNull(runs[account]).closed = false }
        }
    }

    /** A cancelled caller leaves its run; the last one to leave cancels it, or drops it if it hadn't started. */
    private fun leave(account: AccountId, run: Run) {
        if (run.result.isCompleted || --run.waiters > 0) return
        val state = checkNotNull(runs[account])
        if (state.next === run) {
            // Nobody waits for its result any more.
            state.next = null
        } else {
            run.job?.cancel()
        }
    }

    /** Called under [lock]; [scope] must dispatch (never `Unconfined`), so no engine code runs under the lock. */
    private fun start(account: AccountId, run: Run) {
        var report: RunReport? = null
        var failure: Throwable? = null
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                report = execute(account, run.requests)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Throwable) {
                // Kept for the callers, so the scope never sees it.
                failure = e
            }
        }
        run.job = job
        job.invokeOnCompletion { cause ->
            synchronized(lock) {
                val state = checkNotNull(runs[account])
                val next = state.next
                state.current = next
                state.next = null
                if (next != null) start(account, next)
            }
            when {
                // A stopped run ends with null for its callers, whatever the engine threw on the way out.
                cause != null -> run.result.complete(null)
                failure != null -> run.result.completeExceptionally(failure!!)
                else -> run.result.complete(report)
            }
        }
        job.start()
    }

    private suspend fun execute(account: AccountId, requests: Set<SyncRequest>): RunReport? {
        val record = accounts.record(account.value) ?: return null
        val phone = PhoneAccount(account, record.androidAccountName)
        if (record.loginState != LoginState.ACTIVE) {
            if (SyncRequest.DryRun !in requests) return null
            val report = RunReport(ReportKind.DRY_RUN, now(), Blocker.SIGNED_OUT, plan = null)
            reports.save(lastReport(phone, report))
            return report
        }
        // Missing settings are the default mode, dry run.
        val mode = accounts.settings(account.value)?.syncMode ?: SyncMode.DRY_RUN
        // A full sync uploads too, and a dry run writes nothing, so either beats an upload-only sync.
        val full = requests.filterIsInstance<SyncRequest.Full>()
        return when {
            // Merged confirmations: the smallest count any carried, for each direction. Of what started them, the
            // user's own on screen comes first: Sync now, then Start, then Android Settings, then an import.
            full.isNotEmpty() && mode == SyncMode.ON -> engine.sync(
                phone, full.mapNotNull { it.confirmedPhoneDeletions }.minOrNull(), full.mapNotNull { it.confirmedRemoteDeletions }.minOrNull(),
                full.minOf { it.trigger },
            )
            SyncRequest.DryRun in requests -> engine.dryRun(phone)
            // The event loop uploads too, whatever its feed shows.
            SyncRequest.Incremental in requests && mode == SyncMode.ON -> incremental(phone)
            SyncRequest.Upload in requests && mode == SyncMode.ON -> engine.upload(phone, ReportTrigger.PHONE_CHANGES)
            else -> null
        }
    }
}
