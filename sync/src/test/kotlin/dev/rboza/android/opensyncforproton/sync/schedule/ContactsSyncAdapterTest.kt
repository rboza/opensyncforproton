package dev.rboza.android.opensyncforproton.sync.schedule

import android.accounts.Account
import android.content.ContentResolver
import android.content.Context
import android.content.SyncResult
import android.os.Bundle
import android.provider.ContactsContract
import dev.rboza.android.opensyncforproton.account.AccountId
import dev.rboza.android.opensyncforproton.sync.db.AccountRecordEntity
import dev.rboza.android.opensyncforproton.sync.db.Blocker
import dev.rboza.android.opensyncforproton.sync.db.LoginState
import dev.rboza.android.opensyncforproton.sync.db.ReportTrigger
import dev.rboza.android.opensyncforproton.sync.db.SettingsEntity
import dev.rboza.android.opensyncforproton.sync.db.SyncMode
import dev.rboza.android.opensyncforproton.sync.engine.EngineHarness
import dev.rboza.android.opensyncforproton.sync.engine.SyncCoordinator
import dev.rboza.android.opensyncforproton.sync.engine.SyncCoordinatorTest
import dev.rboza.android.opensyncforproton.sync.engine.SyncRequest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The SyncAdapter (docs/sync.md § Scheduling). The adapter runs on Android's sync thread, so it blocks; an
 * initialization request only sets the syncable state. Every value is made up for the test.
 */
@RunWith(RobolectricTestRunner::class)
class ContactsSyncAdapterTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val accounts = SyncCoordinatorTest.FakeAccounts()
    private val engine = SyncCoordinatorTest.FakeEngine()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val coordinator = SyncCoordinator(engine, engine::incremental, accounts, EngineHarness.FakeReports(), scope, now = { 0 })
    /** What ran, in order: the reconciliation and the engine's run. */
    private val order = java.util.Collections.synchronizedList(mutableListOf<String>())
    private var reconcileFailure: Exception? = null
    private val adapter = ContactsSyncAdapter(context, coordinator, accounts, TYPE) {
        order += "reconcile"
        reconcileFailure?.let { throw it }
    }
    private val android = Account("test-only@example.test", TYPE)

    @After
    fun stop() = scope.cancel()

    private fun account(state: LoginState = LoginState.ACTIVE, mode: SyncMode = SyncMode.ON, manual: Boolean = false) {
        accounts.records["user-id-1"] = AccountRecordEntity("user-id-1", "test-only@example.test", state)
        accounts.settings["user-id-1"] = SettingsEntity("user-id-1", mode, manual = manual)
    }

    private fun sync(account: Account = android, extras: Bundle = Bundle()): SyncResult =
        SyncResult().also { adapter.onPerformSync(account, extras, ContactsContract.AUTHORITY, null, it) }

    private fun initialize(): SyncResult = sync(extras = Bundle().apply { putBoolean(ContentResolver.SYNC_EXTRAS_INITIALIZE, true) })

    private fun syncable() = ContentResolver.getIsSyncable(android, ContactsContract.AUTHORITY)

    @Test
    fun anInitializationRequestOnlySetsTheSyncableStateFromTheMode() {
        account()
        initialize()
        assertEquals(1, syncable())

        for (setUp in listOf({ account(mode = SyncMode.DRY_RUN) }, { account(state = LoginState.SIGNED_OUT) }, { accounts.records.clear() })) {
            ContentResolver.setIsSyncable(android, ContactsContract.AUTHORITY, -1)
            setUp()

            initialize()

            assertEquals(0, syncable())
        }
        assertEquals(emptyList<String>(), engine.calls)
    }

    @Test
    fun aSyncStartedFromAndroidIsAFullSyncManualIncluded() {
        account(manual = true)

        val result = sync()

        assertEquals(listOf("sync user-id-1 test-only@example.test"), engine.calls)
        assertEquals("its report says Android Settings started it", listOf(ReportTrigger.ANDROID_SETTINGS), engine.triggers)
        assertFalse(result.hasError())
    }

    @Test
    fun anUploadRequestAfterAPhoneEditRunsAnUploadOnlySyncAndNothingUnderManual() {
        // An upload-only sync after phone edits.
        val upload = Bundle().apply { putBoolean(ContentResolver.SYNC_EXTRAS_UPLOAD, true) }
        account()

        val result = sync(extras = upload)

        assertEquals(listOf("upload user-id-1 test-only@example.test"), engine.calls)
        assertEquals(listOf(ReportTrigger.PHONE_CHANGES), engine.triggers)
        assertEquals(listOf("reconcile"), order)
        assertFalse(result.hasError())

        engine.calls.clear(); order.clear()
        account(manual = true)
        sync(extras = upload)
        assertEquals(emptyList<String>(), engine.calls)
        assertEquals("nothing runs, so nothing is reconciled", emptyList<String>(), order)
    }

    @Test
    fun nothingRunsInDryRunSignedOutForAnUnknownAccountOrAnotherType() {
        account(mode = SyncMode.DRY_RUN)
        sync()
        account(state = LoginState.SIGNED_OUT)
        sync()
        account()
        sync(Account("other@example.test", TYPE))
        sync(Account("test-only@example.test", "other.type"))
        initialize()

        assertEquals(emptyList<String>(), engine.calls)
        assertEquals("only a run is preceded by a reconciliation", emptyList<String>(), order)
    }

    @Test
    fun theAccountsAreReconciledRightBeforeTheRun() {
        // Reconciliation runs before every run.
        account()
        engine.onRun = { order += "run" }

        sync()

        assertEquals(listOf("reconcile", "run"), order)
    }

    @Test
    fun aReconciliationThatFailsIsAHardError() {
        account()
        reconcileFailure = IllegalStateException("test-only failure")

        val result = sync()

        assertTrue(result.databaseError)
        assertEquals(emptyList<String>(), engine.calls)
    }

    @Test
    fun inDryRunARequestReturnsAtOnceInsteadOfWaitingForASyncNow() {
        account(mode = SyncMode.DRY_RUN)
        val gate = engine.gates(1).single()
        val started = CountDownLatch(1)
        engine.onRun = { started.countDown() }
        val syncNow = scope.async { coordinator.request(AccountId("user-id-1"), SyncRequest.DryRun) }
        assertTrue(started.await(10, TimeUnit.SECONDS))

        val syncThread = thread { sync() }
        syncThread.join(5_000)
        val waited = syncThread.isAlive
        gate.complete(Unit)
        runBlocking { syncNow.await() }
        syncThread.join(10_000)

        assertFalse("Android's sync thread didn't wait for the dry run", waited)
        assertEquals(1, engine.calls.size)
    }

    @Test
    fun retryableRunsAreIoErrorsAndAnEndedSessionIsAnAuthError() {
        account()
        val retried = setOf(Blocker.OFFLINE, Blocker.PROTON_UNAVAILABLE, Blocker.CONTACTS_UNAVAILABLE)
        val auth = setOf(Blocker.SIGNED_OUT, Blocker.SESSION_EXPIRED)

        for (each in Blocker.entries) {
            engine.blocker = each

            val result = sync()

            assertEquals("$each", if (each in retried) 1L else 0L, result.stats.numIoExceptions)
            assertEquals("$each", if (each in auth) 1L else 0L, result.stats.numAuthExceptions)
            assertEquals("$each: only an ended session isn't retried", each in auth, result.hasHardError())
        }
    }

    @Test
    fun anEngineFailureIsAHardErrorNotRetried() {
        account()
        engine.failure = IllegalStateException()

        val result = sync()

        assertTrue(result.databaseError)
        assertTrue(result.hasHardError())
        assertEquals(listOf(0L, 0L), listOf(result.stats.numIoExceptions, result.stats.numAuthExceptions))
    }

    @Test
    fun aDatabaseErrorIsAHardErrorAndNeverReachesAndroidsSyncThread() {
        account()
        accounts.failure = IllegalStateException()

        for (result in listOf(sync(), initialize())) {
            assertTrue(result.databaseError)
        }
        assertEquals(emptyList<String>(), engine.calls)
    }

    @Test
    fun cancellingTheSyncCancelsItsRun() {
        account()
        engine.gates(1)
        val started = CountDownLatch(1)
        engine.onRun = { started.countDown() }
        var result: SyncResult? = null

        val syncThread = thread { result = sync() }
        assertTrue(started.await(10, TimeUnit.SECONDS))
        adapter.onSyncCanceled()
        syncThread.join(10_000)

        assertFalse("the sync thread returned", syncThread.isAlive)
        // Waits for the run to end, so its count is seen.
        runBlocking { coordinator.whileClosed(AccountId("user-id-1")) {} }
        assertEquals(1, engine.cancelled)
        assertFalse(checkNotNull(result).hasError())
    }

    private companion object {
        const val TYPE = "dev.rboza.android.opensyncforproton.debug"
    }
}
