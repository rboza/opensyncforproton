package dev.rboza.android.opensyncforproton.diagnostics

import dev.rboza.android.opensyncforproton.AppGraph
import dev.rboza.android.opensyncforproton.account.AccountId
import dev.rboza.android.opensyncforproton.sync.db.Blocker
import dev.rboza.android.opensyncforproton.sync.db.ReportTrigger
import dev.rboza.android.opensyncforproton.sync.engine.RunReport
import dev.rboza.android.opensyncforproton.sync.engine.SyncEngine
import dev.rboza.android.opensyncforproton.sync.provider.PhoneAccount
import kotlin.coroutines.cancellation.CancellationException

/** What a diagnostics record is about. */
enum class Operation { RUN_DRY, RUN_SYNC, RUN_UPLOAD, RUN_INCREMENTAL, LOGIN_STEP, LOGIN_COMMIT, SYNC_NOW, SYNC_ON, SYNC_OFF, INTERVAL, LOGOUT, REMOVE, SESSION_END, KEEP_COPY, IMPORT, MERGE, REMOVE_ORIGINALS }

/**
 * How it ended, where no enum of its own says so (a run's Blocker, a login's LoginFailure or RefusalReason are
 * recorded as they are).
 */
enum class Outcome {
    READY, FAILED, CANCELLED,
    NEED_TOTP, NEED_MAILBOX_PASSWORD, NEED_CAPTCHA, SUCCESS, STOPPED,
    COMMITTED, RESUMED, NOT_SAVED,
    RAN, DIDNT_RUN, DONE, REMOVED, KEPT, NOT_ENDED,
}

/** A run's counts, one per report category (RunReport.counts). */
enum class RunCount {
    NEW_FROM_PROTON, CHANGED_ON_PROTON, DELETED_ON_PROTON, NEW_ON_PHONE, CHANGED_ON_PHONE, DELETED_ON_PHONE,
    CONFLICTS, BULK_GUARD_REMOTE, BULK_GUARD_PHONE, CANT_BE_SYNCED, DUPLICATE_ROWS, MISSING_ROWS,
}

/** Keep a copy's counts, and an import's: the contacts copied, and those that couldn't be. */
enum class CopyCount { COPIED, NOT_COPIED }

/** A merge's counts: the pairs applied of each choice, and those that couldn't be. */
enum class MergeCount { COMBINED, KEPT_PROTON, KEPT_PHONE, KEPT_BOTH, NOT_APPLIED }

/** Remove originals' counts: the originals removed of those asked about, and those that couldn't be. */
enum class RemoveCount { REMOVED, NOT_REMOVED }

/**
 * What the screens and the engine wrapper hand to AppGraph.log; the composition root adds the time and turns [account]
 * into its pseudonym (a LogRecord). Typed values only (D23).
 */
data class LogEntry(
    val operation: Operation,
    val account: AccountId?,
    val outcome: Enum<*>?,
    val counts: Map<out Enum<*>, Int> = emptyMap(),
    val durationMs: Long? = null,
)

/**
 * The engine the coordinator runs, recording every run, whatever started it: its kind, account, outcome (ready, its
 * blocker, failed, or cancelled; never an exception's text), counts, and duration. A record that fails never fails the
 * run.
 */
class RecordingEngine(
    private val engine: SyncEngine,
    private val log: (LogEntry) -> Unit,
    private val nanoTime: () -> Long,
) : SyncEngine {
    override suspend fun dryRun(account: PhoneAccount): RunReport = around(Operation.RUN_DRY, account) { engine.dryRun(account) }

    override suspend fun sync(account: PhoneAccount, confirmedPhoneDeletions: Int?, confirmedRemoteDeletions: Int?, trigger: ReportTrigger): RunReport =
        around(Operation.RUN_SYNC, account) { engine.sync(account, confirmedPhoneDeletions, confirmedRemoteDeletions, trigger) }

    override suspend fun upload(account: PhoneAccount, trigger: ReportTrigger): RunReport =
        around(Operation.RUN_UPLOAD, account) { engine.upload(account, trigger) }

    /** A failed check is the event loop's run, which [incremental] records. */
    override suspend fun failedCheck(account: PhoneAccount, blocker: Blocker): RunReport = engine.failedCheck(account, blocker)

    /**
     * The event loop's run, recorded apart from the sync or upload it runs through this engine, which records its own
     * counts: so only its outcome and duration, and a feed that fails is recorded here alone.
     */
    suspend fun incremental(account: PhoneAccount, run: suspend (PhoneAccount) -> RunReport): RunReport =
        around(Operation.RUN_INCREMENTAL, account, counted = false) { run(account) }

    private suspend fun around(operation: Operation, account: PhoneAccount, counted: Boolean = true, run: suspend () -> RunReport): RunReport {
        val start = nanoTime()
        fun record(outcome: Enum<*>, counts: Map<RunCount, Int> = emptyMap()) {
            val ms = (nanoTime() - start) / 1_000_000
            try {
                log(LogEntry(operation, account.id, outcome, counts, ms))
            } catch (_: Exception) {
                // A log never fails what it describes.
            }
        }
        val report = try {
            run()
        } catch (e: CancellationException) {
            record(Outcome.CANCELLED)
            throw e
        } catch (e: Exception) {
            record(Outcome.FAILED)
            throw e
        }
        record(report.blocker ?: Outcome.READY, if (counted) counts(report) else emptyMap())
        return report
    }

    private fun counts(report: RunReport): Map<RunCount, Int> {
        val counts = report.counts()
        return linkedMapOf(
            RunCount.NEW_FROM_PROTON to counts.newFromProton,
            RunCount.CHANGED_ON_PROTON to counts.changedOnProton,
            RunCount.DELETED_ON_PROTON to counts.deletedOnProton,
            RunCount.NEW_ON_PHONE to counts.newOnPhone,
            RunCount.CHANGED_ON_PHONE to counts.changedOnPhone,
            RunCount.DELETED_ON_PHONE to counts.deletedOnPhone,
            RunCount.CONFLICTS to counts.conflicts,
            RunCount.BULK_GUARD_REMOTE to if (counts.bulkGuardRemote) 1 else 0,
            RunCount.BULK_GUARD_PHONE to if (counts.bulkGuardPhone) 1 else 0,
            RunCount.CANT_BE_SYNCED to counts.unreadable,
            RunCount.DUPLICATE_ROWS to counts.duplicateRows,
            RunCount.MISSING_ROWS to counts.missingRows,
        )
    }
}

/** AppGraph.log for the screens: a record never fails what it describes (docs/architecture.md § Diagnostics). */
internal fun AppGraph.record(entry: LogEntry) {
    try {
        log(entry)
    } catch (_: Exception) {
    }
}
