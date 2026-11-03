package dev.rboza.android.opensyncforproton.notify

import dev.rboza.android.opensyncforproton.account.AccountId
import dev.rboza.android.opensyncforproton.sync.db.AccountRecordEntity
import dev.rboza.android.opensyncforproton.sync.db.AccountStateDao
import dev.rboza.android.opensyncforproton.sync.db.Blocker
import dev.rboza.android.opensyncforproton.sync.db.ConflictHoldDao
import dev.rboza.android.opensyncforproton.sync.db.ConflictHoldEntity
import dev.rboza.android.opensyncforproton.sync.db.HoldChoice
import dev.rboza.android.opensyncforproton.sync.db.HoldKind
import dev.rboza.android.opensyncforproton.sync.db.LastReportDao
import dev.rboza.android.opensyncforproton.sync.db.LastReportEntity
import dev.rboza.android.opensyncforproton.sync.db.LoginState
import dev.rboza.android.opensyncforproton.sync.db.ReportKind
import dev.rboza.android.opensyncforproton.sync.db.ReportTrigger
import dev.rboza.android.opensyncforproton.sync.db.SettingsEntity
import dev.rboza.android.opensyncforproton.sync.db.SyncMode
import dev.rboza.android.opensyncforproton.sync.engine.RunReport
import dev.rboza.android.opensyncforproton.sync.engine.SyncEngine
import dev.rboza.android.opensyncforproton.sync.provider.PhoneAccount
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

// The engine wrapper compares each run's report with the one stored before it to post and clear notifications
// (docs/sync.md § Screens and notifications). Names and IDs are made up.
class NotifyingEngineTest {
    private val account = PhoneAccount(AccountId("a1"), "someone.made.up@example.test")
    private val calls = mutableListOf<String>()
    private var stored: LastReportEntity? = LastReportEntity("a1", ReportKind.SYNC, 1_000, true, null, 0, 0, 0, 0, 0, 0, 0, false, false, 0, 0, 0)
    private var record: AccountRecordEntity? = AccountRecordEntity("a1", account.name, LoginState.ACTIVE)
    private var settings: SettingsEntity? = SettingsEntity("a1", SyncMode.ON, 60)
    private var result = RunReport(ReportKind.SYNC, 2_000, Blocker.CAPTCHA, plan = null)
    private var engineFailure: Exception? = null
    private val notified = mutableListOf<Set<SyncProblem>>()
    private var reportFailure: Exception? = null
    private var stateFailure: Exception? = null
    private var notifyFailure: Exception? = null
    private val cleared = mutableListOf<Set<ProblemKind>>()
    private var clearFailure: Exception? = null

    /** Each sync's confirmations: the phone direction's, then the other's. */
    private val confirmations = mutableListOf<Pair<Int?, Int?>>()

    /** How many contacts are held, and how many after the next run (null: as many). */
    private var held = 0
    private var heldAfterRun: Int? = null
    private var holdsFailure: Exception? = null

    /** Only the next holds read throws this. */
    private var nextHoldsFailure: Exception? = null
    private val holdReads = mutableListOf<Int>()

    /** What each sync, upload-only sync, and failed check was given: what started it, or the check's blocker. */
    private val given = mutableListOf<Any>()

    private val engine = object : SyncEngine {
        override suspend fun dryRun(account: PhoneAccount) = answer("dryRun")
        override suspend fun sync(account: PhoneAccount, confirmedPhoneDeletions: Int?, confirmedRemoteDeletions: Int?, trigger: ReportTrigger) =
            answer("sync").also { confirmations += confirmedPhoneDeletions to confirmedRemoteDeletions; given += trigger }
        override suspend fun upload(account: PhoneAccount, trigger: ReportTrigger) = answer("upload").also { given += trigger }
        override suspend fun failedCheck(account: PhoneAccount, blocker: Blocker) = answer("failedCheck").also { given += blocker }

        private fun answer(call: String): RunReport {
            calls += call
            engineFailure?.let { throw it }
            stored = null // the engine stores its own report, replacing the one read before
            heldAfterRun?.let { held = it }
            return result
        }
    }
    private val holds = object : ConflictHoldDao {
        override suspend fun holds(accountId: String): List<ConflictHoldEntity> {
            holdsFailure?.let { throw it }
            nextHoldsFailure?.let {
                nextHoldsFailure = null
                throw it
            }
            holdReads += held
            return List(held) { ConflictHoldEntity(accountId, "proton-id-$it", it.toLong(), HoldKind.CONFLICT, null, 1, null) }
        }

        override suspend fun found(accountId: String, protonId: String, rawContactId: Long, kind: HoldKind, version: Long?, fingerprint: String?, remoteSeen: Boolean) = error("not used")
        override suspend fun choose(accountId: String, protonId: String, kind: HoldKind, rawContactId: Long, version: Long?, fingerprint: String?, choice: HoldChoice?) = error("not used")
        override suspend fun delete(accountId: String, protonId: String) = error("not used")
    }
    private val reports = object : LastReportDao {
        override suspend fun save(report: LastReportEntity) = error("not used")
        override suspend fun report(accountId: String): LastReportEntity? {
            calls += "report $accountId"
            reportFailure?.let { throw it }
            return stored
        }
    }
    private val state = object : AccountStateDao {
        override suspend fun record(accountId: String): AccountRecordEntity? {
            calls += "record"
            stateFailure?.let { throw it }
            return record
        }

        override suspend fun settings(accountId: String): SettingsEntity? {
            calls += "settings"
            return settings
        }
        override suspend fun recordByAndroidName(name: String) = error("not used")
    }

    private val wrapped = NotifyingEngine(
        engine, state, reports, holds,
        object : ProblemNotices {
            override fun show(problems: Set<SyncProblem>) {
                notifyFailure?.let { throw it }
                notified += problems
            }

            override fun clear(kinds: Set<ProblemKind>) {
                clearFailure?.let { throw it }
                cleared += kinds
            }
        },
    )

    @Test
    fun `a sync passes its confirmation on`() = runTest {
        wrapped.sync(account, confirmedPhoneDeletions = 7)
        wrapped.sync(account, confirmedRemoteDeletions = 4)
        wrapped.sync(account)

        assertEquals(listOf(7 to null, null to 4, null to null), confirmations)
    }

    @Test
    fun `what started a run, and a failed check's blocker, reach the engine`() = runTest {
        wrapped.sync(account, trigger = ReportTrigger.ANDROID_SETTINGS)
        wrapped.upload(account, ReportTrigger.CHECK)
        wrapped.failedCheck(account, Blocker.OFFLINE)

        assertEquals(listOf(ReportTrigger.ANDROID_SETTINGS, ReportTrigger.CHECK, Blocker.OFFLINE), given)
    }

    @Test
    fun `a real sync's new problems are notified, the report read before the run`() = runTest {
        assertSame(result, wrapped.sync(account))
        assertEquals(listOf("report a1", "sync", "record", "settings"), calls)
        assertEquals(listOf(setOf<SyncProblem>(SyncProblem.Captcha)), notified)
    }

    @Test
    fun `an upload-only sync notifies and clears by its own report, the stored one read before it`() = runTest {
        // It updates the report's phone side, so it tells what it checked.
        assertSame(result, wrapped.upload(account))
        assertEquals(listOf("report a1", "upload", "record", "settings"), calls.take(4))
        assertEquals(listOf(setOf<SyncProblem>(SyncProblem.Captcha)), notified)

        // Ready, but Proton never answered it: only what it read on the phone shows gone.
        result = RunReport(ReportKind.SYNC, 2_000, null, plan = null, reachedProton = false)
        wrapped.upload(account)
        assertEquals(listOf(setOf(ProblemKind.PERMISSION_REVOKED)), cleared)
    }

    @Test
    fun `a failed check notifies and clears as any run, and reads no holds`() = runTest {
        result = RunReport(ReportKind.SYNC, 2_000, Blocker.SESSION_EXPIRED, plan = null)

        assertSame(result, wrapped.failedCheck(account, Blocker.SESSION_EXPIRED))

        assertEquals(listOf("report a1", "failedCheck", "record", "settings"), calls)
        assertEquals(listOf(setOf<SyncProblem>(SyncProblem.SessionExpired)), notified)
        assertEquals(emptyList<Int>(), holdReads, "a check changes no holds")
    }

    @Test
    fun `a sync that leaves more contacts held posts how many, and one that leaves none clears it`() = runTest {
        // The held list's count, before and after the run.
        result = RunReport(ReportKind.SYNC, 2_000, null, plan = null)
        held = 1
        heldAfterRun = 3
        wrapped.sync(account)
        assertEquals(listOf(setOf<SyncProblem>(SyncProblem.Held(3))), notified)
        assertEquals(listOf(1, 3), holdReads)

        heldAfterRun = 0
        wrapped.sync(account)
        assertEquals(1, notified.size)
        assertEquals(listOf(resolvedProblems(result), resolvedProblems(result), setOf(ProblemKind.HELD)), cleared)
    }

    @Test
    fun `as many or fewer held posts nothing, and none held before clears nothing`() = runTest {
        result = RunReport(ReportKind.SYNC, 2_000, Blocker.PERMISSION_MISSING, plan = null)
        held = 2
        wrapped.sync(account)
        heldAfterRun = 1
        wrapped.sync(account)
        held = 0
        heldAfterRun = null
        wrapped.sync(account)
        assertEquals(emptyList<SyncProblem>(), notified.flatten().filterIsInstance<SyncProblem.Held>())
        assertEquals(emptyList<Any>(), cleared)
    }

    @Test
    fun `an upload-only sync that leaves more held posts how many too, and never clears`() = runTest {
        // It adds the holds its re-fetches find and removes none.
        result = RunReport(ReportKind.SYNC, 2_000, null, plan = null)
        heldAfterRun = 2
        wrapped.upload(account)
        assertEquals(listOf(setOf<SyncProblem>(SyncProblem.Held(2))), notified)
        assertEquals(listOf(0, 2), holdReads)
        assertEquals(emptyList<ProblemKind>(), cleared.flatten().filter { it == ProblemKind.HELD })
    }

    @Test
    fun `more held posts nothing in dry run mode or signed out, and a dry run never reads the holds`() = runTest {
        result = RunReport(ReportKind.SYNC, 2_000, null, plan = null)
        settings = SettingsEntity("a1", SyncMode.DRY_RUN, 60)
        heldAfterRun = 1
        wrapped.upload(account)
        settings = SettingsEntity("a1", SyncMode.ON, 60)
        record = AccountRecordEntity("a1", account.name, LoginState.SIGNED_OUT)
        heldAfterRun = 2
        wrapped.sync(account)
        assertEquals(emptyList<Any>(), notified)

        holdReads.clear()
        wrapped.dryRun(account)
        assertEquals(emptyList<Int>(), holdReads)
    }

    @Test
    fun `holds that can't be read post and clear nothing, and the run goes on`() = runTest {
        result = RunReport(ReportKind.SYNC, 2_000, null, plan = null)
        held = 1
        holdsFailure = IllegalStateException("made-up")
        assertSame(result, wrapped.sync(account))
        assertSame(result, wrapped.upload(account))
        assertEquals(emptyList<Any>(), notified)
        assertEquals(listOf(resolvedProblems(result), resolvedProblems(result)), cleared, "each run's report problems, never the held one")
    }

    @Test
    fun `held that couldn't be counted before the run posts nothing, and still clears once none are held`() = runTest {
        result = RunReport(ReportKind.SYNC, 2_000, Blocker.PERMISSION_MISSING, plan = null)
        nextHoldsFailure = IllegalStateException("made-up")
        held = 2
        wrapped.sync(account)
        assertEquals(emptyList<SyncProblem>(), notified.flatten().filterIsInstance<SyncProblem.Held>(), "it may not have risen")

        nextHoldsFailure = IllegalStateException("made-up")
        heldAfterRun = 0
        wrapped.sync(account)
        assertEquals(listOf(setOf(ProblemKind.HELD)), cleared)
    }

    @Test
    fun `a dry run's too`() = runTest {
        assertSame(result, wrapped.dryRun(account))
        assertEquals(listOf("report a1", "dryRun", "record", "settings"), calls)
        assertEquals(1, notified.size)
    }

    @Test
    fun `nothing new, nothing notified`() = runTest {
        stored = LastReportEntity("a1", ReportKind.SYNC, 1_000, false, Blocker.CAPTCHA, 0, 0, 0, 0, 0, 0, 0, false, false, 0, 0, 0)
        wrapped.sync(account)
        assertEquals(emptyList<Any>(), notified)
    }

    @Test
    fun `nothing in dry run mode, or signed out, or for an account Room no longer has`() = runTest {
        settings = SettingsEntity("a1", SyncMode.DRY_RUN, 60)
        wrapped.sync(account)
        settings = null
        wrapped.sync(account)
        settings = SettingsEntity("a1", SyncMode.ON, 60)
        record = AccountRecordEntity("a1", account.name, LoginState.SIGNED_OUT)
        wrapped.sync(account)
        record = null
        wrapped.sync(account)
        assertEquals(emptyList<Any>(), notified)
    }

    @Test
    fun `after each run, the problems it shows gone are cleared, in any mode`() = runTest {
        result = RunReport(ReportKind.DRY_RUN, 2_000, null, plan = null)
        settings = SettingsEntity("a1", SyncMode.DRY_RUN, 60)
        wrapped.dryRun(account)
        assertEquals(listOf(resolvedProblems(result)), cleared)
    }

    @Test
    fun `nothing is cleared when nothing is shown gone, or when the run fails`() = runTest {
        result = RunReport(ReportKind.SYNC, 2_000, Blocker.PERMISSION_MISSING, plan = null)
        wrapped.sync(account)
        engineFailure = IOException("made-up")
        try {
            wrapped.sync(account)
        } catch (_: IOException) {
        }
        assertEquals(emptyList<Any>(), cleared)
    }

    @Test
    fun `clearing that fails doesn't fail the run`() = runTest {
        result = RunReport(ReportKind.SYNC, 2_000, null, plan = null)
        clearFailure = SecurityException("made-up")
        assertSame(result, wrapped.sync(account))
    }

    @Test
    fun `a notification that fails doesn't fail the run`() = runTest {
        notifyFailure = SecurityException("made-up")
        assertSame(result, wrapped.sync(account))
    }

    @Test
    fun `a stored report that can't be read doesn't stop the run, and nothing is notified`() = runTest {
        reportFailure = IllegalStateException("made-up")
        assertSame(result, wrapped.sync(account))
        assertEquals(emptyList<Any>(), notified)
    }

    @Test
    fun `a record that can't be read after the run doesn't fail it, and nothing is notified`() = runTest {
        stateFailure = IllegalStateException("made-up")
        assertSame(result, wrapped.sync(account))
        assertEquals(emptyList<Any>(), notified)
    }

    @Test
    fun `an engine failure reaches the caller and notifies nothing`() = runTest {
        engineFailure = IOException("made-up")
        val failure = try {
            wrapped.sync(account)
            null
        } catch (e: IOException) {
            e
        }
        assertSame(engineFailure, failure)
        assertEquals(emptyList<Any>(), notified)
    }
}
