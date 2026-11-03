package dev.rboza.android.opensyncforproton.sync.engine

import dev.rboza.android.opensyncforproton.account.AccountId
import dev.rboza.android.opensyncforproton.sync.db.AccountRecordEntity
import dev.rboza.android.opensyncforproton.sync.db.AccountStateDao
import dev.rboza.android.opensyncforproton.sync.db.Blocker
import dev.rboza.android.opensyncforproton.sync.db.LastReportEntity
import dev.rboza.android.opensyncforproton.sync.db.LoginState
import dev.rboza.android.opensyncforproton.sync.db.ReportKind
import dev.rboza.android.opensyncforproton.sync.db.ReportTrigger
import dev.rboza.android.opensyncforproton.sync.db.SettingsEntity
import dev.rboza.android.opensyncforproton.sync.db.SyncMode
import dev.rboza.android.opensyncforproton.sync.provider.PhoneAccount
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// The coordinator: one run at a time per account (docs/sync.md § Scheduling). Every value is made up for the test.
class SyncCoordinatorTest {
    private val id = AccountId("user-id-1")
    private val engine = FakeEngine()
    private val accounts = FakeAccounts()
    private val reports = EngineHarness.FakeReports()

    private fun TestScope.coordinator() = SyncCoordinator(engine, engine::incremental, accounts, reports, backgroundScope, now = { 1_767_225_600_000 })

    private fun signedIn(mode: SyncMode?, state: LoginState = LoginState.ACTIVE) {
        accounts.records["user-id-1"] = AccountRecordEntity("user-id-1", "test-only@example.test", state)
        if (mode == null) accounts.settings.remove("user-id-1") else accounts.settings["user-id-1"] = SettingsEntity("user-id-1", mode)
    }

    /** The report is run [index]'s (computed before the expected value is read); [only] says there was just one. */
    private fun assertRun(report: RunReport?, index: Int, message: String = "", only: Boolean = false) {
        assertSame(engine.returned[index], report, message)
        if (only) assertEquals(1, engine.returned.size, message)
    }

    @Test
    fun `a dry run runs the engine's dry run under the account's Android name`() = runTest {
        signedIn(SyncMode.DRY_RUN)

        val report = coordinator().request(id, SyncRequest.DryRun)

        assertEquals(listOf("dryRun user-id-1 test-only@example.test"), engine.calls)
        assertRun(report, 0, only = true)
    }

    @Test
    fun `a full sync runs only with sync on`() = runTest {
        signedIn(SyncMode.ON)

        val report = coordinator().request(id, SyncRequest.Full())

        assertEquals(listOf("sync user-id-1 test-only@example.test"), engine.calls)
        assertRun(report, 0, only = true)
        for (mode in listOf(SyncMode.DRY_RUN, null)) {
            signedIn(mode)
            assertNull(coordinator().request(id, SyncRequest.Full()), "$mode")
        }
        assertEquals(1, engine.calls.size, "missing settings mean dry run")
    }

    @Test
    fun `an upload-only sync runs only with sync on`() = runTest {
        signedIn(SyncMode.ON)

        val report = coordinator().request(id, SyncRequest.Upload)

        assertEquals(listOf("upload user-id-1 test-only@example.test"), engine.calls)
        assertRun(report, 0, only = true)
        for (mode in listOf(SyncMode.DRY_RUN, null)) {
            signedIn(mode)
            assertNull(coordinator().request(id, SyncRequest.Upload), "$mode")
        }
        signedIn(SyncMode.ON, LoginState.SIGNED_OUT)
        assertNull(coordinator().request(id, SyncRequest.Upload), "signed out")
        assertEquals(1, engine.calls.size)
    }

    @Test
    fun `an incremental run runs the event loop only signed in with sync on`() = runTest {
        signedIn(SyncMode.ON)

        val report = coordinator().request(id, SyncRequest.Incremental)

        assertEquals(listOf("incremental user-id-1 test-only@example.test"), engine.calls)
        assertRun(report, 0, only = true)
        for (mode in listOf(SyncMode.DRY_RUN, null)) {
            signedIn(mode)
            assertNull(coordinator().request(id, SyncRequest.Incremental), "$mode: a dry run reads no feed")
        }
        signedIn(SyncMode.ON, LoginState.SIGNED_OUT)
        assertNull(coordinator().request(id, SyncRequest.Incremental), "signed out")
        assertEquals(1, engine.calls.size)
    }

    @Test
    fun `a merged follow-up runs full before a dry run, a dry run before incremental, and incremental before upload-only`() = runTest {
        for ((requests, expected) in listOf(
            listOf(SyncRequest.Upload, SyncRequest.Full()) to "sync",
            listOf(SyncRequest.Upload, SyncRequest.Upload) to "upload",
            listOf(SyncRequest.Upload, SyncRequest.DryRun) to "dryRun",
            listOf(SyncRequest.Incremental, SyncRequest.Full()) to "sync",
            listOf(SyncRequest.DryRun, SyncRequest.Incremental) to "dryRun",
            listOf(SyncRequest.Upload, SyncRequest.Incremental) to "incremental",
            listOf(SyncRequest.Incremental, SyncRequest.Upload) to "incremental",
            listOf(SyncRequest.Incremental, SyncRequest.Incremental) to "incremental",
        )) {
            signedIn(SyncMode.ON)
            engine.calls.clear()
            engine.returned.clear()
            val (first) = engine.gates(1)
            val coordinator = coordinator()

            val a = async { coordinator.request(id, SyncRequest.Full()) }
            runCurrent()
            val waiting = requests.map { async { coordinator.request(id, it) } }
            runCurrent()
            first.complete(Unit)
            a.await()
            waiting.forEach { it.await() }

            assertEquals("$expected user-id-1 test-only@example.test", engine.calls[1], "$requests")
            assertEquals(2, engine.calls.size, "$requests")
        }
    }

    @Test
    fun `a confirmed full sync passes its count to the engine`() = runTest {
        signedIn(SyncMode.ON)

        coordinator().request(id, SyncRequest.Full(confirmedPhoneDeletions = 7))
        coordinator().request(id, SyncRequest.Full())

        assertEquals(listOf(7, null), engine.confirmations)
        assertEquals(listOf(null, null), engine.remoteConfirmations)

        coordinator().request(id, SyncRequest.Full(confirmedRemoteDeletions = 6))

        assertEquals(listOf(7, null, null), engine.confirmations, "each direction apart")
        assertEquals(listOf(null, null, 6), engine.remoteConfirmations)
    }

    @Test
    fun `each sync tells the engine what started it, Sync now when the request doesn't say`() = runTest {
        signedIn(SyncMode.ON)

        for (trigger in listOf(ReportTrigger.SYNC_NOW, ReportTrigger.START, ReportTrigger.ANDROID_SETTINGS, ReportTrigger.IMPORT)) {
            coordinator().request(id, SyncRequest.Full(trigger = trigger))
        }
        coordinator().request(id, SyncRequest.Full())
        coordinator().request(id, SyncRequest.Upload)

        assertEquals(
            listOf(ReportTrigger.SYNC_NOW, ReportTrigger.START, ReportTrigger.ANDROID_SETTINGS, ReportTrigger.IMPORT, ReportTrigger.SYNC_NOW, ReportTrigger.PHONE_CHANGES),
            engine.triggers,
        )
    }

    @Test
    fun `full syncs merged into one follow-up name Sync now, else Start, else Android Settings, else Import`() = runTest {
        for ((requests, expected) in listOf(
            listOf(SyncRequest.Full(trigger = ReportTrigger.ANDROID_SETTINGS), SyncRequest.Full(trigger = ReportTrigger.START)) to ReportTrigger.START,
            listOf(SyncRequest.Full(trigger = ReportTrigger.START), SyncRequest.Full(trigger = ReportTrigger.SYNC_NOW)) to ReportTrigger.SYNC_NOW,
            listOf(SyncRequest.Upload, SyncRequest.Full(trigger = ReportTrigger.ANDROID_SETTINGS), SyncRequest.Incremental) to ReportTrigger.ANDROID_SETTINGS,
            // An import's full sync is named after the other three.
            listOf(SyncRequest.Full(trigger = ReportTrigger.IMPORT), SyncRequest.Full(trigger = ReportTrigger.ANDROID_SETTINGS)) to ReportTrigger.ANDROID_SETTINGS,
            listOf(SyncRequest.Full(trigger = ReportTrigger.START), SyncRequest.Full(trigger = ReportTrigger.IMPORT)) to ReportTrigger.START,
            listOf(SyncRequest.Full(trigger = ReportTrigger.IMPORT), SyncRequest.Full(trigger = ReportTrigger.SYNC_NOW)) to ReportTrigger.SYNC_NOW,
            listOf(SyncRequest.Upload, SyncRequest.Full(trigger = ReportTrigger.IMPORT), SyncRequest.Incremental) to ReportTrigger.IMPORT,
        )) {
            signedIn(SyncMode.ON)
            engine.triggers.clear()
            val (first) = engine.gates(1)
            val coordinator = coordinator()

            val a = async { coordinator.request(id, SyncRequest.Full()) }
            runCurrent()
            val waiting = requests.map { async { coordinator.request(id, it) } }
            runCurrent()
            first.complete(Unit)
            a.await()
            waiting.forEach { it.await() }

            assertEquals(listOf(ReportTrigger.SYNC_NOW, expected), engine.triggers, "$requests")
        }
    }

    @Test
    fun `requests merged into one follow-up keep the smallest confirmation of each direction`() = runTest {
        signedIn(SyncMode.ON)
        val (first) = engine.gates(1)
        val coordinator = coordinator()

        val a = async { coordinator.request(id, SyncRequest.Full()) }
        runCurrent()
        val requests = listOf(
            SyncRequest.Full(confirmedPhoneDeletions = 9), SyncRequest.Full(confirmedRemoteDeletions = 3),
            SyncRequest.Full(confirmedPhoneDeletions = 5, confirmedRemoteDeletions = 8), SyncRequest.Full(),
        )
        val waiting = requests.map { async { coordinator.request(id, it) } }
        runCurrent()
        first.complete(Unit)
        a.await()
        waiting.forEach { it.await() }

        assertEquals(listOf(null, 5), engine.confirmations)
        assertEquals(listOf(null, 3), engine.remoteConfirmations)
    }

    @Test
    fun `requests merged into one follow-up give it the smallest confirmation any carried`() = runTest {
        for ((requests, expected) in listOf(
            listOf(SyncRequest.Full(9), SyncRequest.Full(), SyncRequest.Full(5)) to 5,
            listOf(SyncRequest.Upload, SyncRequest.Full(4)) to 4,
            listOf(SyncRequest.Full(), SyncRequest.Upload) to null,
        )) {
            signedIn(SyncMode.ON)
            engine.calls.clear()
            engine.returned.clear()
            engine.confirmations.clear()
            val (first) = engine.gates(1)
            val coordinator = coordinator()

            val a = async { coordinator.request(id, SyncRequest.Full()) }
            runCurrent()
            val waiting = requests.map { async { coordinator.request(id, it) } }
            runCurrent()
            first.complete(Unit)
            a.await()
            waiting.forEach { it.await() }

            assertEquals(listOf(null, expected), engine.confirmations, "$requests")
        }
    }

    @Test
    fun `an account without a record runs nothing and stores nothing`() = runTest {
        for (request in listOf(SyncRequest.DryRun, SyncRequest.Full(), SyncRequest.Upload, SyncRequest.Incremental)) assertNull(coordinator().request(id, request), "$request")

        assertEquals(emptyList<String>(), engine.calls)
        assertEquals(emptyList<LastReportEntity>(), reports.saved)
    }

    @Test
    fun `a signed-out account reports signed out to a dry run, and a sync runs nothing`() = runTest {
        signedIn(SyncMode.ON, LoginState.SIGNED_OUT)

        assertNull(coordinator().request(id, SyncRequest.Full()))
        assertEquals(emptyList<LastReportEntity>(), reports.saved)
        val report = coordinator().request(id, SyncRequest.DryRun)

        assertEquals(emptyList<String>(), engine.calls)
        assertEquals(listOf(ReportKind.DRY_RUN, Blocker.SIGNED_OUT, null), listOf(report?.kind, report?.blocker, report?.plan))
        val zero = LastReportEntity(
            "user-id-1", ReportKind.DRY_RUN, 1_767_225_600_000, ready = false, blocker = Blocker.SIGNED_OUT, newFromProton = 0,
            changedOnProton = 0, deletedOnProton = 0, newOnPhone = 0, changedOnPhone = 0, deletedOnPhone = 0, conflicts = 0,
            bulkGuardRemote = false, bulkGuardPhone = false, unreadable = 0, duplicateRows = 0, missingRows = 0,
        )
        assertEquals(listOf(zero), reports.saved)
    }

    @Test
    fun `requests during a run merge into one follow-up, and runs never overlap`() = runTest {
        signedIn(SyncMode.ON)
        val (first, second) = engine.gates(2)
        val coordinator = coordinator()

        val a = async { coordinator.request(id, SyncRequest.Full()) }
        runCurrent()
        val b = async { coordinator.request(id, SyncRequest.Full()) }
        val c = async { coordinator.request(id, SyncRequest.Full()) }
        runCurrent()
        assertEquals(1, engine.calls.size, "the follow-up waits for the run")
        first.complete(Unit)
        runCurrent()

        assertRun(a.await(), 0)
        assertEquals(2, engine.calls.size, "one follow-up for both")
        assertFalse(b.isCompleted)
        val d = async { coordinator.request(id, SyncRequest.Full()) }
        runCurrent()
        assertEquals(2, engine.calls.size, "a request during the follow-up waits for it too")
        second.complete(Unit)
        assertRun(b.await(), 1)
        assertRun(c.await(), 1)
        assertRun(d.await(), 2)
        assertEquals(3, engine.calls.size)
        assertEquals(1, engine.mostAtOnce)
    }

    @Test
    fun `a follow-up is a full sync when one was asked for and sync is on, otherwise the dry run`() = runTest {
        for ((mode, expected) in listOf(SyncMode.ON to "sync", SyncMode.DRY_RUN to "dryRun")) {
            signedIn(mode)
            engine.calls.clear()
            engine.returned.clear()
            val (first) = engine.gates(1)
            val coordinator = coordinator()

            val a = async { coordinator.request(id, SyncRequest.DryRun) }
            runCurrent()
            val b = async { coordinator.request(id, SyncRequest.DryRun) }
            val c = async { coordinator.request(id, SyncRequest.Full()) }
            runCurrent()
            first.complete(Unit)
            a.await()

            assertEquals("$expected user-id-1 test-only@example.test", engine.calls[1], "$mode")
            assertRun(b.await(), 1, "$mode")
            assertRun(c.await(), 1, "$mode: every waiter gets the follow-up's report")
        }
    }

    @Test
    fun `a follow-up of full syncs alone runs nothing without sync on`() = runTest {
        signedIn(SyncMode.DRY_RUN)
        val (first) = engine.gates(1)
        val coordinator = coordinator()

        val a = async { coordinator.request(id, SyncRequest.DryRun) }
        runCurrent()
        val b = async { coordinator.request(id, SyncRequest.Full()) }
        runCurrent()
        first.complete(Unit)
        a.await()

        assertNull(b.await())
        assertEquals(1, engine.calls.size)
    }

    @Test
    fun `the mode is read when the follow-up starts`() = runTest {
        signedIn(SyncMode.DRY_RUN)
        val (first) = engine.gates(1)
        val coordinator = coordinator()

        val a = async { coordinator.request(id, SyncRequest.DryRun) }
        runCurrent()
        val b = async { coordinator.request(id, SyncRequest.Full()) }
        runCurrent()
        signedIn(SyncMode.ON)
        first.complete(Unit)
        a.await()

        assertRun(b.await(), 1)
        assertEquals("sync user-id-1 test-only@example.test", engine.calls[1])
    }

    @Test
    fun `the last waiter to leave cancels the run`() = runTest {
        signedIn(SyncMode.ON)
        engine.gates(1)
        val coordinator = coordinator()

        val a = async { coordinator.request(id, SyncRequest.Full()) }
        runCurrent()
        a.cancel()
        runCurrent()

        assertEquals(1, engine.cancelled)
        assertEquals(0, engine.running)
        assertRun(coordinator.request(id, SyncRequest.Full()), 0, "the next request runs", only = true)
    }

    @Test
    fun `a waiter leaving doesn't stop a run others still wait for`() = runTest {
        signedIn(SyncMode.ON)
        val (first, second) = engine.gates(2)
        val coordinator = coordinator()

        val a = async { coordinator.request(id, SyncRequest.Full()) }
        runCurrent()
        val b = async { coordinator.request(id, SyncRequest.Full()) }
        val c = async { coordinator.request(id, SyncRequest.Full()) }
        runCurrent()
        first.complete(Unit)
        a.await()
        runCurrent()
        b.cancel()
        runCurrent()
        second.complete(Unit)

        assertRun(c.await(), 1)
        assertEquals(0, engine.cancelled)
    }

    @Test
    fun `a follow-up whose only waiter left is dropped`() = runTest {
        signedIn(SyncMode.ON)
        val (first) = engine.gates(1)
        val coordinator = coordinator()

        val a = async { coordinator.request(id, SyncRequest.Full()) }
        runCurrent()
        val b = async { coordinator.request(id, SyncRequest.Full()) }
        runCurrent()
        b.cancel()
        runCurrent()
        first.complete(Unit)
        a.await()
        runCurrent()

        assertEquals(1, engine.calls.size)
    }

    @Test
    fun `whileClosed stops the run and its follow-up before the block, and refuses requests until it ends`() = runTest {
        signedIn(SyncMode.ON)
        engine.gates(1)
        val coordinator = coordinator()
        val a = async { coordinator.request(id, SyncRequest.Full()) }
        runCurrent()
        val b = async { coordinator.request(id, SyncRequest.Full()) }
        runCurrent()

        val seen = coordinator.whileClosed(id) {
            assertEquals(0, engine.running, "the run ended before the block")
            assertEquals(1, engine.cancelled)
            assertNull(coordinator.request(id, SyncRequest.Full()), "refused while closed")
            assertNull(coordinator.request(id, SyncRequest.DryRun), "refused while closed")
            "the block's value"
        }

        assertEquals("the block's value", seen)
        assertNull(a.await(), "its run was stopped")
        assertNull(b.await(), "its follow-up was dropped")
        assertEquals(1, engine.calls.size)
        assertRun(coordinator.request(id, SyncRequest.Full()), 0, "reopened", only = true)
    }

    @Test
    fun `a run that fails as whileClosed stops it still gives its callers null`() = runTest {
        signedIn(SyncMode.ON)
        engine.gates(1)
        engine.failureOnCancel = IOException()
        val coordinator = coordinator()
        val a = async { coordinator.request(id, SyncRequest.Full()) }
        runCurrent()

        coordinator.whileClosed(id) {}

        assertEquals(1, engine.cancelled)
        assertNull(a.await())
    }

    @Test
    fun `whileClosed reopens when its block fails, and can't be nested`() = runTest {
        signedIn(SyncMode.ON)
        val coordinator = coordinator()

        val failed = runCatching { coordinator.whileClosed(id) { error("the lifecycle step failed") } }
        val nested = runCatching { coordinator.whileClosed(id) { coordinator.whileClosed(id) {} } }

        assertTrue(failed.exceptionOrNull() is IllegalStateException)
        assertTrue(nested.exceptionOrNull() is IllegalStateException)
        assertRun(coordinator.request(id, SyncRequest.Full()), 0, "reopened", only = true)
    }

    @Test
    fun `an engine failure reaches every caller of its run, and the next request still runs`() = runTest {
        signedIn(SyncMode.ON)
        val (first, second) = engine.gates(2)
        val coordinator = coordinator()
        val a = async { coordinator.request(id, SyncRequest.Full()) }
        runCurrent()
        val b = async { runCatching { coordinator.request(id, SyncRequest.Full()) } }
        val c = async { runCatching { coordinator.request(id, SyncRequest.Full()) } }
        runCurrent()
        first.complete(Unit)
        assertRun(a.await(), 0)

        engine.failure = IllegalStateException()
        second.complete(Unit)

        assertTrue(b.await().exceptionOrNull() is IllegalStateException)
        assertTrue(c.await().exceptionOrNull() is IllegalStateException)
        engine.failure = null
        assertRun(coordinator.request(id, SyncRequest.Full()), 1)
    }

    /**
     * Records each call; each call waits for the next gate a test set, if any. Each report is a new object, so tests
     * tell runs apart.
     */
    class FakeEngine : SyncEngine {
        val calls = mutableListOf<String>()
        val returned = mutableListOf<RunReport>()
        private val gates = mutableListOf<CompletableDeferred<Unit>>()
        var failure: Exception? = null

        /** The blocker each report carries. */
        var blocker: Blocker? = null

        /** Called as each run starts, on the engine's thread. */
        var onRun: () -> Unit = {}

        /** Thrown instead of the cancellation, as an engine might after a blocking call is interrupted. */
        var failureOnCancel: Exception? = null
        var running = 0
        var mostAtOnce = 0
        var cancelled = 0

        fun gates(count: Int): List<CompletableDeferred<Unit>> = List(count) { CompletableDeferred<Unit>() }.also { gates += it }

        override suspend fun dryRun(account: PhoneAccount) = run("dryRun", account, ReportKind.DRY_RUN)

        /** Each sync's confirmation of the phone direction's bulk guard, and of the other direction's, in turn. */
        val confirmations = mutableListOf<Int?>()
        val remoteConfirmations = mutableListOf<Int?>()

        /** What each sync and upload-only sync was told started it, in turn. */
        val triggers = mutableListOf<ReportTrigger>()

        override suspend fun sync(account: PhoneAccount, confirmedPhoneDeletions: Int?, confirmedRemoteDeletions: Int?, trigger: ReportTrigger): RunReport {
            confirmations += confirmedPhoneDeletions
            remoteConfirmations += confirmedRemoteDeletions
            triggers += trigger
            return run("sync", account, ReportKind.SYNC)
        }

        override suspend fun upload(account: PhoneAccount, trigger: ReportTrigger): RunReport {
            triggers += trigger
            return run("upload", account, ReportKind.SYNC)
        }

        override suspend fun failedCheck(account: PhoneAccount, blocker: Blocker) = error("only the event loop reports a failed check")

        /** The event loop's run, which the coordinator is given beside the engine. */
        suspend fun incremental(account: PhoneAccount) = run("incremental", account, ReportKind.SYNC)

        private suspend fun run(name: String, account: PhoneAccount, kind: ReportKind): RunReport {
            calls += "$name ${account.id.value} ${account.name}"
            running++
            mostAtOnce = maxOf(mostAtOnce, running)
            onRun()
            try {
                gates.removeFirstOrNull()?.await()
                failure?.let { throw it }
                return RunReport(kind, finishedAt = calls.size.toLong(), blocker = blocker, plan = null).also { returned += it }
            } catch (e: CancellationException) {
                cancelled++
                throw failureOnCancel ?: e
            } finally {
                running--
            }
        }
    }

    class FakeAccounts : AccountStateDao {
        val records = mutableMapOf<String, AccountRecordEntity>()
        val settings = mutableMapOf<String, SettingsEntity>()

        override suspend fun record(accountId: String) = records[accountId]

        override suspend fun settings(accountId: String) = settings[accountId]

        /** Thrown by the lookup by name, as a database error would be. */
        var failure: Exception? = null

        override suspend fun recordByAndroidName(name: String): AccountRecordEntity? {
            failure?.let { throw it }
            return records.values.singleOrNull { it.androidAccountName == name }
        }
    }
}
