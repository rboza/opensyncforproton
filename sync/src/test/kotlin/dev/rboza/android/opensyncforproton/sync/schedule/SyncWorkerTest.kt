package dev.rboza.android.opensyncforproton.sync.schedule

import android.accounts.Account
import android.content.ContentResolver
import android.content.Context
import android.provider.ContactsContract
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import dev.rboza.android.opensyncforproton.sync.db.AccountRecordEntity
import dev.rboza.android.opensyncforproton.sync.db.Blocker
import dev.rboza.android.opensyncforproton.sync.db.LoginState
import dev.rboza.android.opensyncforproton.sync.db.SettingsEntity
import dev.rboza.android.opensyncforproton.sync.db.SyncMode
import dev.rboza.android.opensyncforproton.sync.engine.EngineHarness
import dev.rboza.android.opensyncforproton.sync.engine.SyncCoordinator
import dev.rboza.android.opensyncforproton.sync.engine.SyncCoordinatorTest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The periodic job's worker (docs/sync.md § Scheduling). The worker asks the coordinator for a full sync unless
 * Android's global or per-account sync switch is off, retries only what may pass by itself, and turns an engine failure
 * into a failed run. Every value is made up for the test.
 */
@RunWith(RobolectricTestRunner::class)
class SyncWorkerTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val accounts = SyncCoordinatorTest.FakeAccounts()
    private val androidAccount = Account("test-only@example.test", TYPE)
    private val engine = SyncCoordinatorTest.FakeEngine()

    /** What ran, in order: the reconciliation and the engine's run. */
    private val order = java.util.Collections.synchronizedList(mutableListOf<String>())
    private var reconcileFailure: Exception? = null

    private fun signedIn(mode: SyncMode = SyncMode.ON, switchOn: Boolean = true, globalSwitchOn: Boolean = true) {
        accounts.records["user-id-1"] = AccountRecordEntity("user-id-1", "test-only@example.test", LoginState.ACTIVE)
        accounts.settings["user-id-1"] = SettingsEntity("user-id-1", mode)
        ContentResolver.setSyncAutomatically(androidAccount, ContactsContract.AUTHORITY, switchOn)
        ContentResolver.setMasterSyncAutomatically(globalSwitchOn)
    }

    private fun TestScope.worker(accountId: String? = "user-id-1"): SyncWorker {
        val coordinator = SyncCoordinator(engine, engine::incremental, accounts, EngineHarness.FakeReports(), backgroundScope, now = { 0 })
        return TestListenableWorkerBuilder<SyncWorker>(context)
            .setInputData(if (accountId == null) workDataOf() else workDataOf(SyncWorker.ACCOUNT_ID to accountId))
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                        SyncWorker(appContext, workerParameters, coordinator, accounts, TYPE) {
                            order += "reconcile"
                            reconcileFailure?.let { throw it }
                        }
                },
            )
            .build()
    }

    @Test
    fun aScheduledRunIsIncremental() = runTest {
        // Each run polls the event loop, which adds a full sync when one is due.
        signedIn()

        val result = worker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(listOf("incremental user-id-1 test-only@example.test"), engine.calls)
    }

    @Test
    fun theJobSkipsWhileAndroidsSyncSwitchIsOff() = runTest {
        signedIn(switchOn = false)

        val result = worker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(emptyList<String>(), engine.calls)
        assertEquals(emptyList<String>(), order)
    }

    @Test
    fun theAccountsAreReconciledRightBeforeTheRun() = runTest {
        // Reconciliation runs before every run.
        signedIn()
        engine.onRun = { order += "run" }

        worker().doWork()

        assertEquals(listOf("reconcile", "run"), order)
    }

    @Test
    fun aReconciliationThatFailsIsAFailedRun() = runTest {
        signedIn()
        reconcileFailure = IllegalStateException("test-only failure")

        assertEquals(ListenableWorker.Result.failure(), worker().doWork())
        assertEquals(emptyList<String>(), engine.calls)
    }

    @Test
    fun theJobSkipsWhileAndroidsGlobalSyncSwitchIsOff() = runTest {
        signedIn(globalSwitchOn = false)

        val result = worker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(emptyList<String>(), engine.calls)
    }

    @Test
    fun anEngineFailureIsAFailedRunNotAnException() = runTest {
        signedIn()
        engine.failure = IllegalStateException()

        assertEquals(ListenableWorker.Result.failure(), worker().doWork())
    }

    @Test
    fun stoppingTheJobCancelsItsRun() = runTest {
        signedIn()
        engine.gates(1)
        val worker = worker()

        var outcome: kotlin.Result<ListenableWorker.Result>? = null
        val work = async { outcome = runCatching { worker.doWork() } }
        runCurrent()
        work.cancel()
        runCurrent()

        assertTrue(work.isCancelled)
        assertEquals(1, engine.cancelled)
        assertTrue("the cancellation is never swallowed", outcome?.exceptionOrNull() is CancellationException)
    }

    @Test
    fun onlyFailuresThatPassByThemselvesAreRetried() = runTest {
        signedIn()
        val retried = setOf(Blocker.OFFLINE, Blocker.PROTON_UNAVAILABLE, Blocker.CONTACTS_UNAVAILABLE)

        for (each in Blocker.entries) {
            engine.blocker = each
            val expected = if (each in retried) ListenableWorker.Result.retry() else ListenableWorker.Result.success()
            assertEquals("$each", expected, worker().doWork())
        }
    }

    @Test
    fun nothingRunsWithoutSyncOnOrWithoutTheAccount() = runTest {
        signedIn(mode = SyncMode.DRY_RUN)
        assertEquals(ListenableWorker.Result.success(), worker().doWork())

        accounts.records.clear()
        assertEquals(ListenableWorker.Result.success(), worker().doWork())

        assertEquals(emptyList<String>(), engine.calls)
    }

    @Test
    fun aJobWithoutItsAccountFails() = runTest {
        signedIn()

        assertEquals(ListenableWorker.Result.failure(), worker(accountId = null).doWork())
        assertEquals(emptyList<String>(), engine.calls)
    }

    private companion object {
        const val TYPE = "dev.rboza.android.opensyncforproton.debug"
    }
}
