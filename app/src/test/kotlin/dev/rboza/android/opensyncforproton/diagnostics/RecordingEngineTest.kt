package dev.rboza.android.opensyncforproton.diagnostics

import dev.rboza.android.opensyncforproton.account.AccountId
import dev.rboza.android.opensyncforproton.sync.db.Blocker
import dev.rboza.android.opensyncforproton.sync.db.ReportKind
import dev.rboza.android.opensyncforproton.sync.db.ReportTrigger
import dev.rboza.android.opensyncforproton.sync.engine.RunReport
import dev.rboza.android.opensyncforproton.sync.engine.SyncEngine
import dev.rboza.android.opensyncforproton.sync.plan.PlanCategory
import dev.rboza.android.opensyncforproton.sync.plan.PlanItem
import dev.rboza.android.opensyncforproton.sync.plan.SyncPlan
import dev.rboza.android.opensyncforproton.sync.provider.PhoneAccount
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// The diagnostics records of every run, whatever started it (docs/architecture.md § Diagnostics). Names and IDs are
// made up.
class RecordingEngineTest {
    private val account = PhoneAccount(AccountId("a1"), "someone.made.up@example.test")
    private val logged = mutableListOf<LogEntry>()
    private var now = 1_000_000_000L
    private var answer: () -> RunReport = { error("no answer") }

    /** Each sync's confirmations: the phone direction's, then the other's. */
    private val confirmations = mutableListOf<Pair<Int?, Int?>>()

    /** What each sync and upload-only sync was told started it, and each failed check's blocker. */
    private val given = mutableListOf<Any>()

    private val engine = object : SyncEngine {
        override suspend fun dryRun(account: PhoneAccount) = run()
        override suspend fun sync(account: PhoneAccount, confirmedPhoneDeletions: Int?, confirmedRemoteDeletions: Int?, trigger: ReportTrigger) =
            run().also { confirmations += confirmedPhoneDeletions to confirmedRemoteDeletions; given += trigger }
        override suspend fun upload(account: PhoneAccount, trigger: ReportTrigger) = run().also { given += trigger }
        override suspend fun failedCheck(account: PhoneAccount, blocker: Blocker) = run().also { given += blocker }

        private fun run(): RunReport {
            now += 250_000_000L // 250 ms
            return answer()
        }
    }

    private val wrapped = RecordingEngine(engine, log = { logged += it }, nanoTime = { now })

    private fun plan(vararg categories: PlanCategory, remote: Boolean = true, phone: Boolean = false) =
        SyncPlan(categories.map { PlanItem(it, null, null, null, "Made-Up Name", false) }, emptyList(), bulkGuardRemote = remote, bulkGuardPhone = phone)

    @Test
    fun `what started a run reaches the engine, and a failed check, which the event loop's run records, isn't recorded again`() = runTest {
        answer = { RunReport(ReportKind.SYNC, 5, Blocker.OFFLINE, plan = null) }

        wrapped.sync(account, trigger = ReportTrigger.SCHEDULED)
        wrapped.upload(account, ReportTrigger.CHECK)
        logged.clear()
        wrapped.failedCheck(account, Blocker.OFFLINE)

        assertEquals(listOf(ReportTrigger.SCHEDULED, ReportTrigger.CHECK, Blocker.OFFLINE), given)
        assertEquals(emptyList<LogEntry>(), logged)
    }

    @Test
    fun `a dry run is recorded with its outcome, counts, and duration`() = runTest {
        val report = RunReport(ReportKind.DRY_RUN, 5, null, plan(PlanCategory.NEW_FROM_PROTON, PlanCategory.NEW_FROM_PROTON, PlanCategory.CONFLICT))
        answer = { report }

        assertSame(report, wrapped.dryRun(account))

        assertEquals(
            listOf(
                LogEntry(
                    Operation.RUN_DRY, AccountId("a1"), Outcome.READY,
                    linkedMapOf(
                        RunCount.NEW_FROM_PROTON to 2, RunCount.CHANGED_ON_PROTON to 0, RunCount.DELETED_ON_PROTON to 0,
                        RunCount.NEW_ON_PHONE to 0, RunCount.CHANGED_ON_PHONE to 0, RunCount.DELETED_ON_PHONE to 0,
                        RunCount.CONFLICTS to 1, RunCount.BULK_GUARD_REMOTE to 1, RunCount.BULK_GUARD_PHONE to 0, RunCount.CANT_BE_SYNCED to 0,
                        RunCount.DUPLICATE_ROWS to 0, RunCount.MISSING_ROWS to 0,
                    ),
                    250,
                ),
            ),
            logged,
        )
    }

    /** Each direction of the bulk guard is counted apart. */
    @Test
    fun `the bulk guard is counted per direction`() = runTest {
        for ((remote, phone) in listOf(false to true, true to false, true to true, false to false)) {
            answer = { RunReport(ReportKind.DRY_RUN, 5, null, plan(remote = remote, phone = phone)) }
            wrapped.dryRun(account)
            val counts = logged.last().counts
            assertEquals((if (remote) 1 else 0) to (if (phone) 1 else 0), counts[RunCount.BULK_GUARD_REMOTE] to counts[RunCount.BULK_GUARD_PHONE], "$remote, $phone")
        }
    }

    @Test
    fun `a sync passes its confirmation on`() = runTest {
        answer = { RunReport(ReportKind.SYNC, 5, null, plan()) }

        wrapped.sync(account, confirmedPhoneDeletions = 7)
        wrapped.sync(account, confirmedRemoteDeletions = 4)
        wrapped.sync(account)

        assertEquals(listOf(7 to null, null to 4, null to null), confirmations)
    }

    @Test
    fun `an upload-only sync is recorded as its own operation`() = runTest {
        // RUN_UPLOAD, with a run's outcome, counts, and duration.
        val report = RunReport(ReportKind.SYNC, 5, Blocker.OFFLINE, plan = null)
        answer = { report }

        assertSame(report, wrapped.upload(account))

        assertEquals(Operation.RUN_UPLOAD, logged.single().operation)
        assertEquals(Blocker.OFFLINE, logged.single().outcome)
        assertEquals(250L, logged.single().durationMs)
    }

    @Test
    fun `an incremental run is recorded as its own operation, without counts`() = runTest {
        // RUN_INCREMENTAL, the event loop's run; the sync or upload it runs records its own counts, and a feed that
        // fails is recorded by this one alone.
        for (blocker in listOf(Blocker.PROTON_UNAVAILABLE, null)) {
            logged.clear()
            val report = RunReport(ReportKind.SYNC, 5, blocker, plan(PlanCategory.NEW_FROM_PROTON))
            val seen = mutableListOf<PhoneAccount>()

            assertSame(report, wrapped.incremental(account) { seen += it; now += 250_000_000L; report })

            assertEquals(listOf(account), seen)
            assertEquals(listOf(LogEntry(Operation.RUN_INCREMENTAL, AccountId("a1"), blocker ?: Outcome.READY, emptyMap(), 250)), logged)
        }
        logged.clear()
        val failure = runCatching { wrapped.incremental(account) { throw IOException("someone.made.up@example.test") } }
        assertTrue(failure.exceptionOrNull() is IOException)
        assertEquals(LogEntry(Operation.RUN_INCREMENTAL, AccountId("a1"), Outcome.FAILED, emptyMap(), 0), logged.single())
    }

    @Test
    fun `a sync with a blocker records the blocker`() = runTest {
        answer = { RunReport(ReportKind.SYNC, 5, Blocker.CAPTCHA, plan = null) }
        wrapped.sync(account)
        assertEquals(Operation.RUN_SYNC, logged.single().operation)
        assertEquals(Blocker.CAPTCHA, logged.single().outcome)
        assertEquals(0 to 0, logged.single().counts.let { it[RunCount.BULK_GUARD_REMOTE] to it[RunCount.BULK_GUARD_PHONE] })
    }

    @Test
    fun `a run that fails is recorded as failed, without the exception, and still fails`() = runTest {
        answer = { throw IOException("someone.made.up@example.test") }
        val failure = try {
            wrapped.sync(account)
            null
        } catch (e: IOException) {
            e
        }
        assertEquals("someone.made.up@example.test", failure?.message)
        assertEquals(LogEntry(Operation.RUN_SYNC, AccountId("a1"), Outcome.FAILED, emptyMap(), 250), logged.single())
    }

    @Test
    fun `a cancelled run is recorded as cancelled and stays cancelled`() = runTest {
        answer = { throw CancellationException("stopped") }
        val cancelled = try {
            wrapped.dryRun(account)
            false
        } catch (_: CancellationException) {
            true
        }
        assertEquals(true, cancelled)
        assertEquals(Outcome.CANCELLED, logged.single().outcome)
    }

    @Test
    fun `a record that fails doesn't fail the run`() = runTest {
        val report = RunReport(ReportKind.SYNC, 5, null, plan = null)
        answer = { report }
        val failing = RecordingEngine(engine, log = { throw IllegalStateException("made-up") }, nanoTime = { now })
        assertSame(report, failing.sync(account))
    }
}
