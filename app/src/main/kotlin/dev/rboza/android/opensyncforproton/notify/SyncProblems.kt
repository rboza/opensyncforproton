package dev.rboza.android.opensyncforproton.notify

import dev.rboza.android.opensyncforproton.sync.db.AccountStateDao
import dev.rboza.android.opensyncforproton.sync.db.Blocker
import dev.rboza.android.opensyncforproton.sync.db.ConflictHoldDao
import dev.rboza.android.opensyncforproton.sync.db.LastReportDao
import dev.rboza.android.opensyncforproton.sync.db.LastReportEntity
import dev.rboza.android.opensyncforproton.sync.db.LoginState
import dev.rboza.android.opensyncforproton.sync.db.ReportTrigger
import dev.rboza.android.opensyncforproton.sync.db.SyncMode
import dev.rboza.android.opensyncforproton.sync.engine.RunReport
import dev.rboza.android.opensyncforproton.sync.engine.SyncEngine
import dev.rboza.android.opensyncforproton.sync.provider.PhoneAccount
import kotlin.coroutines.cancellation.CancellationException

/** A problem the "Sync problems" channel tells the user about. */
sealed interface SyncProblem {
    data object SessionExpired : SyncProblem
    data object Captcha : SyncProblem
    data object PermissionRevoked : SyncProblem
    data class DuplicateRows(val count: Int) : SyncProblem

    /** [count] contacts held for the user to answer. */
    data class Held(val count: Int) : SyncProblem
}

/** A kind of [SyncProblem]: one notification each. */
enum class ProblemKind { SESSION_EXPIRED, CAPTCHA, PERMISSION_REVOKED, DUPLICATE_ROWS, HELD }

val SyncProblem.kind: ProblemKind
    get() = when (this) {
        SyncProblem.SessionExpired -> ProblemKind.SESSION_EXPIRED
        SyncProblem.Captcha -> ProblemKind.CAPTCHA
        SyncProblem.PermissionRevoked -> ProblemKind.PERMISSION_REVOKED
        is SyncProblem.DuplicateRows -> ProblemKind.DUPLICATE_ROWS
        is SyncProblem.Held -> ProblemKind.HELD
    }

/** Where problems go: SyncProblemNotifier; an interface so NotifyingEngine's tests can fake it. */
interface ProblemNotices {
    fun show(problems: Set<SyncProblem>)
    fun clear(kinds: Set<ProblemKind>)
}

/**
 * The kinds of problem [report] shows gone, whose notifications are cleared: a ready run shows the permission fine, and
 * the session and the captcha too once Proton answered it (an upload-only sync after phone edits may send nothing); a
 * run with a plan and no duplicate rows shows those gone. A run that stopped early shows nothing about what it didn't
 * reach.
 */
fun resolvedProblems(report: RunReport): Set<ProblemKind> = buildSet {
    if (report.ready) add(ProblemKind.PERMISSION_REVOKED)
    if (report.ready && report.reachedProton) addAll(listOf(ProblemKind.SESSION_EXPIRED, ProblemKind.CAPTCHA))
    if (report.plan != null && report.counts().duplicateRows == 0) add(ProblemKind.DUPLICATE_ROWS)
}

/**
 * The problems [report] brings that the report stored before it ([previous]) didn't: each is notified once per
 * occurrence, with no state of its own. Only with sync on, where runs happen without the user watching, and only for a
 * signed-in account (signed out was the user's choice). Duplicate rows count as new when their number rises; a run that
 * couldn't count them (any run that ended without a plan) counts none.
 */
fun newProblems(previous: LastReportEntity?, report: RunReport, signedIn: Boolean, syncOn: Boolean): Set<SyncProblem> {
    if (!signedIn || !syncOn) return emptySet()
    val before = previous?.blocker
    val now = report.blocker
    // A login is needed for each: keys that don't unlock pause sync as a session that ended does.
    val ended = setOf(Blocker.SIGNED_OUT, Blocker.SESSION_EXPIRED, Blocker.KEYS_DONT_UNLOCK)
    return buildSet {
        if (now in ended && before !in ended) add(SyncProblem.SessionExpired)
        if (now == Blocker.CAPTCHA && before != Blocker.CAPTCHA) add(SyncProblem.Captcha)
        if (now == Blocker.PERMISSION_MISSING && before != Blocker.PERMISSION_MISSING) add(SyncProblem.PermissionRevoked)
        val duplicates = report.counts().duplicateRows
        if (duplicates > (previous?.duplicateRows ?: 0)) add(SyncProblem.DuplicateRows(duplicates))
    }
}

/**
 * The engine the coordinator runs, told what's new after each run: it reads the account's stored report before the run
 * (the engine updates it) and its record and mode after, then hands any new problems to [notices], and clears the
 * notifications of the problems the run shows gone; an upload-only sync and a failed check too, each by what it
 * checked. A sync, upload-only too, also counts the held contacts before and after. Those reads, the notifications, and
 * clearing are only for notifying: when any of them fails, that part is skipped and the run goes on as if unwrapped. An
 * engine failure notifies and clears nothing.
 */
class NotifyingEngine(
    private val engine: SyncEngine,
    private val state: AccountStateDao,
    private val reports: LastReportDao,
    private val holds: ConflictHoldDao,
    private val notices: ProblemNotices,
) : SyncEngine {
    override suspend fun dryRun(account: PhoneAccount): RunReport = around(account) { engine.dryRun(account) }

    override suspend fun sync(account: PhoneAccount, confirmedPhoneDeletions: Int?, confirmedRemoteDeletions: Int?, trigger: ReportTrigger): RunReport =
        held(account) { around(account) { engine.sync(account, confirmedPhoneDeletions, confirmedRemoteDeletions, trigger) } }

    /** It adds holds and removes none, so it may post the held one, and in practice never clears it. */
    override suspend fun upload(account: PhoneAccount, trigger: ReportTrigger): RunReport =
        held(account) { around(account) { engine.upload(account, trigger) } }

    /** A check changes no holds. */
    override suspend fun failedCheck(account: PhoneAccount, blocker: Blocker): RunReport = around(account) { engine.failedCheck(account, blocker) }

    /**
     * The held list's notification: how many are held, posted when the run left more than it found (only with sync on
     * and signed in, as the others), and cleared once a run that found some, or couldn't count them, leaves none.
     */
    private suspend fun held(account: PhoneAccount, run: suspend () -> RunReport): RunReport {
        val id = account.id.value
        val before = quietly { holds.holds(id).size }
        val report = run()
        quietly {
            val after = holds.holds(id).size
            if (after == 0 && before != 0) {
                notices.clear(setOf(ProblemKind.HELD))
            } else if (before != null && after > before && notifies(id)) {
                notices.show(setOf(SyncProblem.Held(after)))
            }
        }
        return report
    }

    private suspend fun notifies(accountId: String): Boolean =
        state.record(accountId)?.loginState == LoginState.ACTIVE && state.settings(accountId)?.syncMode == SyncMode.ON

    private suspend fun around(account: PhoneAccount, run: suspend () -> RunReport): RunReport {
        val id = account.id.value
        val previous = quietly { Base(reports.report(id)) }
        val report = run()
        // In any mode: what's fixed is fixed.
        quietly {
            val resolved = resolvedProblems(report)
            if (resolved.isNotEmpty()) notices.clear(resolved)
        }
        if (previous != null) {
            quietly {
                val signedIn = state.record(id)?.loginState == LoginState.ACTIVE
                val syncOn = state.settings(id)?.syncMode == SyncMode.ON
                val problems = newProblems(previous.report, report, signedIn, syncOn)
                if (problems.isNotEmpty()) notices.show(problems)
            }
        }
        return report
    }

    /** The report stored before a run (null when there was none), read successfully. */
    private class Base(val report: LastReportEntity?)

    /**
     * [block], or null if it failed: everything a notification says is on the main screen too, and nothing of the
     * failure is logged (D23). A cancellation still propagates.
     */
    private suspend fun <T> quietly(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}
