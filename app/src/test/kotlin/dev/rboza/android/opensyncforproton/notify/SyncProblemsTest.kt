package dev.rboza.android.opensyncforproton.notify

import dev.rboza.android.opensyncforproton.sync.db.Blocker
import dev.rboza.android.opensyncforproton.sync.db.LastReportEntity
import dev.rboza.android.opensyncforproton.sync.db.ReportKind
import dev.rboza.android.opensyncforproton.sync.engine.RunReport
import dev.rboza.android.opensyncforproton.sync.plan.PlanCategory
import dev.rboza.android.opensyncforproton.sync.plan.PlanItem
import dev.rboza.android.opensyncforproton.sync.plan.SyncPlan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

// Which problems a run brings that the report before it didn't (docs/sync.md § Screens and notifications).
class SyncProblemsTest {
    private fun stored(blocker: Blocker?, duplicates: Int = 0) =
        LastReportEntity("a1", ReportKind.SYNC, 1_000, blocker == null, blocker, 0, 0, 0, 0, 0, 0, 0, false, false, 0, duplicates, 0)

    private fun run(blocker: Blocker?, duplicates: Int = 0) = RunReport(
        ReportKind.SYNC, 2_000, blocker,
        SyncPlan(List(duplicates) { PlanItem(PlanCategory.DUPLICATE_ROWS, null, null, null, "made-up", false) }, emptyList(), false, false),
    )

    private fun new(previous: LastReportEntity?, report: RunReport, signedIn: Boolean = true, syncOn: Boolean = true) =
        newProblems(previous, report, signedIn, syncOn)

    @Test
    fun `a session that ended is new once, whether rejected or with credentials gone`() {
        assertEquals(setOf(SyncProblem.SessionExpired), new(stored(null), run(Blocker.SESSION_EXPIRED)))
        assertEquals(setOf(SyncProblem.SessionExpired), new(stored(Blocker.OFFLINE), run(Blocker.SIGNED_OUT)))
        assertEquals(setOf(SyncProblem.SessionExpired), new(null, run(Blocker.SIGNED_OUT)))
        assertEquals(emptySet<SyncProblem>(), new(stored(Blocker.SESSION_EXPIRED), run(Blocker.SIGNED_OUT)))
        assertEquals(emptySet<SyncProblem>(), new(stored(Blocker.SIGNED_OUT), run(Blocker.SESSION_EXPIRED)))
    }

    @Test
    fun `keys that don't unlock need a login as a session that ended does, new once`() {
        // Sync pauses until the user logs in, so they hear of it once.
        assertEquals(setOf(SyncProblem.SessionExpired), new(stored(null), run(Blocker.KEYS_DONT_UNLOCK)))
        assertEquals(emptySet<SyncProblem>(), new(stored(Blocker.KEYS_DONT_UNLOCK), run(Blocker.KEYS_DONT_UNLOCK)))
        assertEquals(emptySet<SyncProblem>(), new(stored(Blocker.SESSION_EXPIRED), run(Blocker.KEYS_DONT_UNLOCK)))
        assertEquals(emptySet<SyncProblem>(), new(stored(Blocker.KEYS_DONT_UNLOCK), run(Blocker.SIGNED_OUT)))
    }

    @Test
    fun `a captcha is new once`() {
        assertEquals(setOf(SyncProblem.Captcha), new(stored(null), run(Blocker.CAPTCHA)))
        assertEquals(emptySet<SyncProblem>(), new(stored(Blocker.CAPTCHA), run(Blocker.CAPTCHA)))
    }

    @Test
    fun `a missing permission is new once`() {
        assertEquals(setOf(SyncProblem.PermissionRevoked), new(stored(Blocker.OFFLINE), run(Blocker.PERMISSION_MISSING)))
        assertEquals(emptySet<SyncProblem>(), new(stored(Blocker.PERMISSION_MISSING), run(Blocker.PERMISSION_MISSING)))
    }

    @Test
    fun `duplicate rows are new when their count rises, with the count`() {
        assertEquals(setOf(SyncProblem.DuplicateRows(2)), new(stored(null), run(null, duplicates = 2)))
        assertEquals(setOf(SyncProblem.DuplicateRows(3)), new(stored(null, duplicates = 2), run(null, duplicates = 3)))
        assertEquals(emptySet<SyncProblem>(), new(stored(null, duplicates = 3), run(null, duplicates = 3)))
        assertEquals(emptySet<SyncProblem>(), new(stored(null, duplicates = 3), run(null, duplicates = 1)))
        assertEquals(setOf(SyncProblem.DuplicateRows(1)), new(null, run(null, duplicates = 1)))
    }

    @Test
    fun `other blockers and a clean run bring nothing`() {
        Blocker.entries.filterNot { it in setOf(Blocker.SESSION_EXPIRED, Blocker.SIGNED_OUT, Blocker.KEYS_DONT_UNLOCK, Blocker.CAPTCHA, Blocker.PERMISSION_MISSING) }
            .forEach { assertEquals(emptySet<SyncProblem>(), new(stored(null), run(it)), it.name) }
        assertEquals(emptySet<SyncProblem>(), new(stored(Blocker.CAPTCHA), run(null)))
    }

    @Test
    fun `nothing in dry run, or for an account the user logged out of`() {
        assertEquals(emptySet<SyncProblem>(), new(stored(null), run(Blocker.CAPTCHA, duplicates = 2), syncOn = false))
        assertEquals(emptySet<SyncProblem>(), new(stored(null), run(Blocker.SIGNED_OUT), signedIn = false))
    }

    @Test
    fun `a ready run that counted no duplicates shows every problem gone`() {
        // The held list's is judged by its count, not the report (NotifyingEngine).
        assertEquals(ProblemKind.entries.toSet() - ProblemKind.HELD, resolvedProblems(run(null)))
    }

    @Test
    fun `a ready run with duplicates shows the others gone`() {
        assertEquals(
            setOf(ProblemKind.SESSION_EXPIRED, ProblemKind.CAPTCHA, ProblemKind.PERMISSION_REVOKED),
            resolvedProblems(run(null, duplicates = 1)),
        )
    }

    @Test
    fun `a ready run Proton never answered shows the session and captcha problems as they were`() {
        // An upload-only sync after phone edits that sent nothing Proton answered.
        val unanswered = run(null).let { RunReport(it.kind, it.finishedAt, null, it.plan, reachedProton = false) }

        assertEquals(setOf(ProblemKind.PERMISSION_REVOKED, ProblemKind.DUPLICATE_ROWS), resolvedProblems(unanswered))
    }

    @Test
    fun `a run that stopped shows nothing gone but duplicates it counted as none`() {
        assertEquals(setOf(ProblemKind.DUPLICATE_ROWS), resolvedProblems(run(Blocker.OFFLINE)))
        assertEquals(emptySet<ProblemKind>(), resolvedProblems(RunReport(ReportKind.SYNC, 2_000, Blocker.PERMISSION_MISSING, plan = null)))
        assertEquals(emptySet<ProblemKind>(), resolvedProblems(run(Blocker.CAPTCHA, duplicates = 2)))
    }

    @Test
    fun `each problem has its kind`() {
        assertEquals(ProblemKind.SESSION_EXPIRED, SyncProblem.SessionExpired.kind)
        assertEquals(ProblemKind.CAPTCHA, SyncProblem.Captcha.kind)
        assertEquals(ProblemKind.PERMISSION_REVOKED, SyncProblem.PermissionRevoked.kind)
        assertEquals(ProblemKind.DUPLICATE_ROWS, SyncProblem.DuplicateRows(2).kind)
    }

    @Test
    fun `a run can bring several at once`() {
        assertEquals(
            setOf(SyncProblem.Captcha, SyncProblem.DuplicateRows(1)),
            new(stored(null), run(Blocker.CAPTCHA, duplicates = 1)),
        )
    }
}
