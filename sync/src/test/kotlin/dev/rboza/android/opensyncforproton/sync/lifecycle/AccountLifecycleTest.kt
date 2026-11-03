package dev.rboza.android.opensyncforproton.sync.lifecycle

import android.accounts.Account
import android.content.ContentResolver
import android.provider.ContactsContract
import androidx.room.Room
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import dev.rboza.android.opensyncforproton.account.AccountDirectory
import dev.rboza.android.opensyncforproton.account.AccountId
import dev.rboza.android.opensyncforproton.account.CredentialStore
import dev.rboza.android.opensyncforproton.account.CredentialStoreException
import dev.rboza.android.opensyncforproton.account.Credentials
import dev.rboza.android.opensyncforproton.account.NewSession
import dev.rboza.android.opensyncforproton.account.PasswordMode
import dev.rboza.android.opensyncforproton.crypto.LockedKeyBundle
import dev.rboza.android.opensyncforproton.proton.AuthDto
import dev.rboza.android.opensyncforproton.proton.AuthInfoDto
import dev.rboza.android.opensyncforproton.proton.CaptchaHeaders
import dev.rboza.android.opensyncforproton.proton.CardDto
import dev.rboza.android.opensyncforproton.proton.ContactDto
import dev.rboza.android.opensyncforproton.proton.ContactExportPageDto
import dev.rboza.android.opensyncforproton.proton.ContactListPageDto
import dev.rboza.android.opensyncforproton.proton.CreateReply
import dev.rboza.android.opensyncforproton.proton.EventId
import dev.rboza.android.opensyncforproton.proton.EventsReply
import dev.rboza.android.opensyncforproton.proton.KeySaltDto
import dev.rboza.android.opensyncforproton.proton.ProtonApi
import dev.rboza.android.opensyncforproton.proton.ProtonContactId
import dev.rboza.android.opensyncforproton.proton.ProtonError
import dev.rboza.android.opensyncforproton.proton.SrpAuthDto
import dev.rboza.android.opensyncforproton.proton.TokenStore
import dev.rboza.android.opensyncforproton.proton.Tokens
import dev.rboza.android.opensyncforproton.proton.UserDto
import dev.rboza.android.opensyncforproton.sync.db.AccountRecordEntity
import dev.rboza.android.opensyncforproton.sync.db.LoginState
import dev.rboza.android.opensyncforproton.sync.db.SettingsEntity
import dev.rboza.android.opensyncforproton.sync.db.SyncDatabase
import dev.rboza.android.opensyncforproton.sync.db.SyncMode
import dev.rboza.android.opensyncforproton.sync.engine.EngineHarness
import dev.rboza.android.opensyncforproton.sync.engine.SyncCoordinator
import dev.rboza.android.opensyncforproton.sync.engine.SyncCoordinatorTest
import dev.rboza.android.opensyncforproton.sync.engine.SyncRequest
import dev.rboza.android.opensyncforproton.sync.provider.ContactWrite
import dev.rboza.android.opensyncforproton.sync.provider.ContactWriteResult
import dev.rboza.android.opensyncforproton.sync.provider.DataRow
import dev.rboza.android.opensyncforproton.sync.provider.DeviceContact
import dev.rboza.android.opensyncforproton.sync.provider.DeviceContacts
import dev.rboza.android.opensyncforproton.sync.provider.ExistingRow
import dev.rboza.android.opensyncforproton.sync.provider.PhoneAccount
import dev.rboza.android.opensyncforproton.sync.provider.ProviderUnavailableException
import dev.rboza.android.opensyncforproton.sync.schedule.SyncScheduler
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The account lifecycle: docs/sync.md § Account states, § Login, § Sync mode, and § Logout and removal. Real Room (in
 * memory), WorkManager (test helper), scheduler, and coordinator; the stores, AccountManager, the provider, and Proton
 * are fakes. Every value is made up for the test.
 */
@RunWith(RobolectricTestRunner::class)
class AccountLifecycleTest {
    private val context = RuntimeEnvironment.getApplication()
    private lateinit var db: SyncDatabase
    private lateinit var workManager: WorkManager

    private val engine = SyncCoordinatorTest.FakeEngine()
    private val store = FakeStore()
    private val directory = FakeDirectory()
    private val phone = FakePhone()
    private val api = FakeApi()

    /** What happened, in order, across the fakes. */
    private val events = mutableListOf<String>()

    private lateinit var coordinator: SyncCoordinator
    private lateinit var lifecycle: AccountLifecycle

    @Before
    fun setUp() {
        val direct = Executor { it.run() }
        db = Room.inMemoryDatabaseBuilder(context, SyncDatabase::class.java).allowMainThreadQueries()
            .setQueryExecutor(direct).setTransactionExecutor(direct).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        workManager = WorkManager.getInstance(context)
    }

    @After
    fun close() = db.close()

    private fun TestScope.start() {
        coordinator = SyncCoordinator(engine, engine::incremental, db.accountStateDao(), EngineHarness.FakeReports(), backgroundScope, now = { 1_767_225_600_000 })
        lifecycle = AccountLifecycle(
            credentials = store,
            directory = directory,
            accounts = db.accountDao(),
            state = db.accountStateDao(),
            deviceContacts = phone,
            scheduler = SyncScheduler(workManager, TYPE),
            coordinator = coordinator,
            newClient = { tokens -> api.stores += tokens; api },
            io = StandardTestDispatcher(testScheduler),
        )
    }

    private fun credentials(uid: String, passphrase: ByteArray = "passphrase-$uid".toByteArray()) = Credentials(
        Tokens(uid, "access-$uid", "refresh-$uid"), PasswordMode.ONE, mapOf("key-1" to "salt-1"), LockedKeyBundle(emptyList()),
        mapOf("key-1" to passphrase),
    )

    private fun session(id: String = "user-id-1", name: String = "$id@example.test", uid: String = "uid-$id-new", passphrase: ByteArray = "p".toByteArray()) =
        NewSession(AccountId(id), name, credentials(uid, passphrase))

    /** An account existing everywhere: Room, AccountManager, the provider, and (unless [uid] is null) credentials. */
    private fun existing(
        id: String,
        state: LoginState,
        mode: SyncMode = SyncMode.DRY_RUN,
        interval: Int = 60,
        uid: String? = "uid-$id",
        check: Int? = 15,
    ) {
        db.openHelper.writableDatabase.execSQL("INSERT INTO account_record VALUES ('$id', '$id@example.test', '$state')")
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO settings (account_id, sync_mode, interval_minutes, check_minutes) VALUES ('$id', '$mode', $interval, ${check ?: "NULL"})",
        )
        directory.accounts[AccountId(id)] = "$id@example.test"
        phone.rows["$id@example.test"] = 3
        if (uid != null) store.saved[AccountId(id)] = Tokens(uid, "access-$uid", "refresh-$uid")
        if (state == LoginState.ACTIVE && mode == SyncMode.ON) {
            SyncScheduler(workManager, TYPE).choose(record(id)!!, settings(id)!!)
        }
    }

    private fun record(id: String): AccountRecordEntity? =
        db.openHelper.readableDatabase.query("SELECT account_id, android_account_name, login_state FROM account_record WHERE account_id = '$id'").use {
            if (it.moveToFirst()) AccountRecordEntity(it.getString(0), it.getString(1), LoginState.valueOf(it.getString(2))) else null
        }

    private fun settings(id: String): SettingsEntity? =
        db.openHelper.readableDatabase.query("SELECT sync_mode, interval_minutes, check_minutes, manual FROM settings WHERE account_id = '$id'").use {
            if (it.moveToFirst()) {
                SettingsEntity(id, SyncMode.valueOf(it.getString(0)), it.getInt(1), if (it.isNull(2)) null else it.getInt(2), it.getInt(3) == 1)
            } else {
                null
            }
        }

    /** An import record of [id]'s. */
    private fun importRecord(id: String) =
        db.openHelper.writableDatabase.execSQL("INSERT INTO import_record VALUES ('$id', 7, 3, 10, 1)")

    private fun importRecords(id: String): List<String> =
        db.openHelper.readableDatabase.query("SELECT original_raw_contact_id, copy_raw_contact_id FROM import_record WHERE account_id = '$id'").use {
            buildList { while (it.moveToNext()) add("${it.getLong(0)}->${it.getLong(1)}") }
        }

    private fun android(id: String) = Account("$id@example.test", TYPE)

    /** Every later UPDATE of [table] fails, as a database error would. */
    private fun failUpdates(table: String) =
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_$table BEFORE UPDATE ON $table BEGIN SELECT RAISE(ABORT, 'test-only'); END")

    private fun syncable(id: String) = ContentResolver.getIsSyncable(android(id), ContactsContract.AUTHORITY)

    private fun automatic(id: String) = ContentResolver.getSyncAutomatically(android(id), ContactsContract.AUTHORITY)

    private fun jobs(id: String): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork("sync.periodic.$id").get().filterNot { it.state.isFinished }

    // ---- Login ----

    @Test
    fun `a new login saves the Room row in dry run, then the credentials, then the Android account`() = runTest {
        start()

        val result = lifecycle.completeLogin(session())

        assertEquals(LoginCommit.Committed(resumed = false), result)
        // Every crash point leaves a Room row without an Android account, which reconciliation deletes with any
        // credentials.
        assertEquals(listOf("save user-id-1 (Room: ACTIVE)", "add user-id-1 user-id-1@example.test (Room: ACTIVE, credentials: saved)"), events)
        assertEquals(AccountRecordEntity("user-id-1", "user-id-1@example.test", LoginState.ACTIVE), record("user-id-1"))
        assertEquals(SettingsEntity("user-id-1", SyncMode.DRY_RUN, 60), settings("user-id-1"))
        assertEquals(mapOf(AccountId("user-id-1") to "user-id-1@example.test"), directory.accounts)
    }

    @Test
    fun `a fresh account schedules nothing and makes no requests or provider writes`() = runTest {
        start()

        lifecycle.completeLogin(session())

        assertEquals(emptyList<WorkInfo>(), jobs("user-id-1"))
        assertEquals(emptyList<String>(), api.calls)
        assertEquals(emptyList<String>(), engine.calls)
        assertEquals(emptyList<String>(), phone.calls)
    }

    @Test
    fun `the session's credentials are closed after the commit, refused or not`() = runTest {
        start()
        existing("user-id-1", LoginState.ACTIVE)
        val committed = "passphrase-a".toByteArray()
        val refused = "passphrase-b".toByteArray()

        lifecycle.completeLogin(session("user-id-1", passphrase = committed))
        lifecycle.completeLogin(session("user-id-2", passphrase = refused))

        assertTrue(committed.all { it == 0.toByte() })
        assertTrue(refused.all { it == 0.toByte() })
    }

    @Test
    fun `logging in again as the same user resumes with everything kept`() = runTest {
        start()
        existing("user-id-1", LoginState.SIGNED_OUT, SyncMode.ON, interval = 120, uid = null)

        val result = lifecycle.completeLogin(session("user-id-1", uid = "uid-2"))

        assertEquals(LoginCommit.Committed(resumed = true), result)
        assertEquals("uid-2", store.saved.getValue(AccountId("user-id-1")).uid)
        assertEquals(LoginState.ACTIVE, record("user-id-1")?.loginState)
        assertEquals(SettingsEntity("user-id-1", SyncMode.ON, 120), settings("user-id-1"))
        assertEquals("sync on comes back with the login", TimeUnit.MINUTES.toMillis(15), jobs("user-id-1").single().periodicityInfo?.repeatIntervalMillis)
        assertEquals(1, syncable("user-id-1"))
        // Reconciling first deletes any leftover credentials of the signed-out account; then the new ones are saved.
        assertEquals("no second Android account", listOf("save user-id-1 (Room: SIGNED_OUT)"), events.filterNot { it.startsWith("delete") })
        assertEquals(mapOf("user-id-1@example.test" to 3), phone.rows)
        assertEquals(emptyList<String>(), api.calls)
    }

    @Test
    fun `logging in again after the session expired resumes too`() = runTest {
        start()
        existing("user-id-1", LoginState.ACTIVE, uid = null)

        assertEquals(LoginCommit.Committed(resumed = true), lifecycle.completeLogin(session("user-id-1")))
        assertEquals(SettingsEntity("user-id-1", SyncMode.DRY_RUN, 60), settings("user-id-1"))
    }

    @Test
    fun `another user while one is signed in or expired is refused, its session ended, and nothing changes`() = runTest {
        start()
        for (uid in listOf("uid-user-id-1", null)) {
            db.clearAllTables()
            store.saved.clear()
            api.calls.clear()
            existing("user-id-1", LoginState.ACTIVE, uid = uid)

            val result = lifecycle.completeLogin(session("user-id-2", uid = "uid-other"))

            assertEquals("$uid", LoginCommit.Refused(RefusalReason.OTHER_ACCOUNT_ACTIVE), result)
            assertNull(record("user-id-2"))
            assertFalse(store.saved.containsKey(AccountId("user-id-2")))
            assertEquals(mapOf(AccountId("user-id-1") to "user-id-1@example.test"), directory.accounts)
            assertEquals("one A5, with the refused session's own tokens", listOf("logout uid-other"), api.calls)
        }
    }

    @Test
    fun `a signed-out user logging back in while another is signed in is refused too`() = runTest {
        start()
        existing("user-id-1", LoginState.SIGNED_OUT, uid = null)
        existing("user-id-2", LoginState.ACTIVE)

        val result = lifecycle.completeLogin(session("user-id-1", uid = "uid-back"))

        assertEquals(LoginCommit.Refused(RefusalReason.OTHER_ACCOUNT_ACTIVE), result)
        assertEquals(LoginState.SIGNED_OUT, record("user-id-1")?.loginState)
        assertFalse(store.saved.containsKey(AccountId("user-id-1")))
        assertEquals(listOf("logout uid-back"), api.calls)
    }

    @Test
    fun `another user next to a signed-out one is added`() = runTest {
        start()
        existing("user-id-1", LoginState.SIGNED_OUT, uid = null)

        assertEquals(LoginCommit.Committed(resumed = false), lifecycle.completeLogin(session("user-id-2")))
        assertEquals(LoginState.SIGNED_OUT, record("user-id-1")?.loginState)
        assertEquals(LoginState.ACTIVE, record("user-id-2")?.loginState)
    }

    @Test
    fun `a taken Android name rolls the new account back and ends its session`() = runTest {
        start()
        directory.taken += "user-id-1@example.test"

        val result = lifecycle.completeLogin(session("user-id-1", uid = "uid-new"))

        assertEquals(LoginCommit.Refused(RefusalReason.NAME_IN_USE), result)
        assertNull(record("user-id-1"))
        assertNull(settings("user-id-1"))
        assertFalse(store.saved.containsKey(AccountId("user-id-1")))
        assertEquals(listOf("logout uid-new"), api.calls)
        assertEquals("the credentials go before the Room row", "delete user-id-1 (Room: ACTIVE, A5: not sent)", events.last())
    }

    @Test
    fun `a rollback whose credentials can't be deleted keeps the Room row for reconciliation and still ends the session`() = runTest {
        start()
        directory.taken += "user-id-1@example.test"
        store.deleteFailure = CredentialStoreException()

        val error = runCatching { lifecycle.completeLogin(session("user-id-1", uid = "uid-new")) }.exceptionOrNull()

        assertTrue("$error", error is CredentialStoreException)
        assertEquals(LoginState.ACTIVE, record("user-id-1")?.loginState)
        assertEquals(listOf("logout uid-new"), api.calls)
    }

    @Test
    fun `a commit that fails is undone, its session ended, and the failure passed on`() = runTest {
        start()
        val failures = listOf<(Exception) -> Unit>({ store.saveFailure = it }, { directory.addFailure = it })
        for ((index, fail) in failures.withIndex()) {
            store.saveFailure = null
            directory.addFailure = null
            api.calls.clear()
            val failure = IllegalStateException("test-only failure $index")
            fail(failure)

            val error = runCatching { lifecycle.completeLogin(session("user-id-1", uid = "uid-new")) }.exceptionOrNull()

            // Coroutines may pass on a copy (stack-trace recovery), so compare what it says.
            assertEquals("$index", failure::class to failure.message, error?.let { it::class to it.message })
            assertNull("$index", record("user-id-1"))
            assertNull("$index", settings("user-id-1"))
            assertFalse("$index", store.saved.containsKey(AccountId("user-id-1")))
            assertEquals("$index", emptyMap<AccountId, String>(), directory.accounts)
            assertEquals("$index", listOf("logout uid-new"), api.calls)
        }
    }

    @Test
    fun `a database failure before the commit still ends the session`() = runTest {
        start()
        val passphrase = "passphrase-a".toByteArray()
        db.close()

        val error = runCatching { lifecycle.completeLogin(session("user-id-1", uid = "uid-new", passphrase = passphrase)) }.exceptionOrNull()

        assertTrue("$error", error is IllegalStateException)
        assertEquals(listOf("logout uid-new"), api.calls)
        assertTrue("closed on failure too", passphrase.all { it == 0.toByte() })
    }

    @Test
    fun `a re-login whose credentials can't be saved keeps the old ones`() = runTest {
        // The store keeps the old envelope when a save fails (CredentialStore.save), so nothing is undone.
        start()
        existing("user-id-1", LoginState.ACTIVE, uid = "uid-old")
        store.saveFailure = IllegalStateException("test-only failure")

        val error = runCatching { lifecycle.completeLogin(session("user-id-1", uid = "uid-new")) }.exceptionOrNull()

        assertTrue("$error", error is IllegalStateException)
        assertEquals("uid-old", store.saved[AccountId("user-id-1")]?.uid)
        assertEquals(LoginState.ACTIVE, record("user-id-1")?.loginState)
        assertEquals(listOf("logout uid-new"), api.calls)
    }

    @Test
    fun `a re-login whose state can't be written deletes only its new credentials`() = runTest {
        start()
        existing("user-id-1", LoginState.SIGNED_OUT, uid = null)
        failUpdates("account_record")

        val error = runCatching { lifecycle.completeLogin(session("user-id-1", uid = "uid-new")) }.exceptionOrNull()

        assertTrue("$error", error != null)
        assertEquals("still signed out, so no other login is blocked", LoginState.SIGNED_OUT, record("user-id-1")?.loginState)
        assertFalse(store.saved.containsKey(AccountId("user-id-1")))
        assertEquals(listOf("logout uid-new"), api.calls)
    }

    @Test
    fun `a re-login stands once it's signed in, even if its schedule fails`() = runTest {
        // An interval that isn't a choice makes the scheduler fail after the commit; reconciliation repairs the flags.
        // Reporting it as signed in keeps a retry from replacing (and never ending) this session.
        start()
        existing("user-id-1", LoginState.SIGNED_OUT, SyncMode.ON, interval = 45, uid = null)

        val result = lifecycle.completeLogin(session("user-id-1", uid = "uid-new"))

        assertEquals(LoginCommit.Committed(resumed = true), result)
        assertEquals(LoginState.ACTIVE, record("user-id-1")?.loginState)
        assertEquals("uid-new", store.saved[AccountId("user-id-1")]?.uid)
        assertEquals(emptyList<String>(), api.calls)
    }

    @Test
    fun `a failed re-login keeps the account's Room rows`() = runTest {
        start()
        existing("user-id-1", LoginState.SIGNED_OUT, SyncMode.ON, uid = null)
        store.saveFailure = IllegalStateException("test-only failure")

        val error = runCatching { lifecycle.completeLogin(session("user-id-1", uid = "uid-new")) }.exceptionOrNull()

        assertTrue("$error", error is IllegalStateException)
        assertEquals(LoginState.SIGNED_OUT, record("user-id-1")?.loginState)
        assertEquals(SettingsEntity("user-id-1", SyncMode.ON, 60), settings("user-id-1"))
        assertEquals(listOf("logout uid-new"), api.calls)
    }

    @Test
    fun `a failed commit whose undo fails too passes on the first failure and leaves the Room row for reconciliation`() = runTest {
        start()
        directory.addFailure = IllegalStateException("test-only failure")
        store.deleteFailure = CredentialStoreException()

        val error = runCatching { lifecycle.completeLogin(session("user-id-1", uid = "uid-new")) }.exceptionOrNull()

        // The undo's failure is attached as suppressed, which stack-trace recovery's copy doesn't carry.
        assertEquals("test-only failure", error?.message)
        assertEquals(LoginState.ACTIVE, record("user-id-1")?.loginState)
        assertEquals(listOf("logout uid-new"), api.calls)
    }

    @Test
    fun `a login whose caller leaves still finishes`() = runTest {
        start()
        existing("user-id-2", LoginState.ACTIVE)
        val gate = CompletableDeferred<Unit>()
        api.logout = { gate.await() }
        // The login waits behind a logout of the other account, and its caller leaves meanwhile.
        val logout = async { lifecycle.logout(AccountId("user-id-2")) }
        runCurrent()
        val login = async { lifecycle.completeLogin(session()) }
        runCurrent()
        login.cancel()
        gate.complete(Unit)
        logout.await()
        advanceUntilIdle()

        assertEquals(LoginState.ACTIVE, record("user-id-1")?.loginState)
        assertTrue(store.saved.containsKey(AccountId("user-id-1")))
        assertEquals("user-id-1@example.test", directory.accounts[AccountId("user-id-1")])
    }

    @Test
    fun `a refused login's A5 gives up after 10 seconds`() = runTest {
        start()
        existing("user-id-1", LoginState.ACTIVE)
        api.logout = { awaitCancellation() }

        val result = lifecycle.completeLogin(session("user-id-2"))

        assertEquals(LoginCommit.Refused(RefusalReason.OTHER_ACCOUNT_ACTIVE), result)
        assertEquals(10_000L, currentTime)
    }

    // ---- Sync mode and interval ----

    @Test
    fun `turning sync on makes the authority syncable and starts the job and Android's automatic sync`() = runTest {
        start()
        existing("user-id-1", LoginState.ACTIVE, interval = 30)

        lifecycle.setSyncMode(AccountId("user-id-1"), SyncMode.ON)

        assertEquals(SettingsEntity("user-id-1", SyncMode.ON, 30), settings("user-id-1"))
        assertEquals(1, syncable("user-id-1"))
        assertTrue(automatic("user-id-1"))
        // The job runs at the check's period, 15 minutes by default, whatever the full sync's interval.
        assertEquals(TimeUnit.MINUTES.toMillis(15), jobs("user-id-1").single().periodicityInfo?.repeatIntervalMillis)
    }

    @Test
    fun `turning sync off stops a running sync first, then the job and the flag, and leaves the rows`() = runTest {
        start()
        existing("user-id-1", LoginState.ACTIVE, SyncMode.ON)
        engine.gates(1)
        val running = async { coordinator.request(AccountId("user-id-1"), SyncRequest.Full()) }
        runCurrent()

        lifecycle.setSyncMode(AccountId("user-id-1"), SyncMode.DRY_RUN)

        assertEquals(1, engine.cancelled)
        assertNull(running.await())
        assertEquals(SyncMode.DRY_RUN, settings("user-id-1")?.syncMode)
        assertEquals(emptyList<WorkInfo>(), jobs("user-id-1"))
        assertEquals(0, syncable("user-id-1"))
        assertEquals(mapOf("user-id-1@example.test" to 3), phone.rows)
        assertEquals(emptyList<String>(), phone.calls)
    }

    private fun period(id: String) = jobs(id).single().periodicityInfo?.repeatIntervalMillis

    @Test
    fun `Manual turns Android's automatic sync off and stops the job, and turning it off brings both back at the kept timing`() = runTest {
        // Manual is a switch of its own; both intervals are kept.
        start()
        existing("user-id-1", LoginState.ACTIVE, SyncMode.ON, interval = 360, check = 30)

        lifecycle.setManual(AccountId("user-id-1"), true)

        assertEquals(SettingsEntity("user-id-1", SyncMode.ON, 360, checkMinutes = 30, manual = true), settings("user-id-1"))
        assertEquals(emptyList<WorkInfo>(), jobs("user-id-1"))
        assertFalse(automatic("user-id-1"))
        assertEquals("Manual still syncs from Sync now and Android Settings", 1, syncable("user-id-1"))

        lifecycle.setInterval(AccountId("user-id-1"), 720)
        lifecycle.setCheckInterval(AccountId("user-id-1"), 60)

        assertEquals("a choice under Manual is kept, and starts nothing", emptyList<WorkInfo>(), jobs("user-id-1"))
        assertFalse(automatic("user-id-1"))

        lifecycle.setManual(AccountId("user-id-1"), false)

        assertEquals(SettingsEntity("user-id-1", SyncMode.ON, 720, checkMinutes = 60, manual = false), settings("user-id-1"))
        assertEquals(TimeUnit.MINUTES.toMillis(60), period("user-id-1"))
        assertTrue(automatic("user-id-1"))
    }

    @Test
    fun `the job runs at the check's period, or the full sync's under Same as full sync`() = runTest {
        start()
        existing("user-id-1", LoginState.ACTIVE, SyncMode.ON, interval = 360, check = 15)
        assertEquals(TimeUnit.MINUTES.toMillis(15), period("user-id-1"))

        lifecycle.setCheckInterval(AccountId("user-id-1"), 120)
        assertEquals(SettingsEntity("user-id-1", SyncMode.ON, 360, checkMinutes = 120), settings("user-id-1"))
        assertEquals(TimeUnit.MINUTES.toMillis(120), period("user-id-1"))

        lifecycle.setCheckInterval(AccountId("user-id-1"), null)
        assertEquals(TimeUnit.MINUTES.toMillis(360), period("user-id-1"))

        lifecycle.setInterval(AccountId("user-id-1"), 60)
        assertEquals("Same as full sync follows it", SettingsEntity("user-id-1", SyncMode.ON, 60, checkMinutes = null), settings("user-id-1"))
        assertEquals(TimeUnit.MINUTES.toMillis(60), period("user-id-1"))
        assertTrue(automatic("user-id-1"))
    }

    @Test
    fun `a full sync shorter than the check lowers the check, and the job with it`() = runTest {
        start()
        existing("user-id-1", LoginState.ACTIVE, SyncMode.ON, interval = 360, check = 120)

        lifecycle.setInterval(AccountId("user-id-1"), 30)

        assertEquals(SettingsEntity("user-id-1", SyncMode.ON, 30, checkMinutes = 30), settings("user-id-1"))
        assertEquals(TimeUnit.MINUTES.toMillis(30), period("user-id-1"))
    }

    @Test
    fun `a check longer than the full sync, or unlisted, is refused`() = runTest {
        start()
        existing("user-id-1", LoginState.ACTIVE, SyncMode.ON, interval = 60, check = 15)
        // In dry run there's no job to refuse it, so the choice itself must be (it applies once sync is on).
        existing("user-id-2", LoginState.ACTIVE, SyncMode.DRY_RUN, interval = 60, check = 15)

        for (id in listOf("user-id-1", "user-id-2")) {
            for (minutes in listOf(120, 45)) {
                val error = runCatching { lifecycle.setCheckInterval(AccountId(id), minutes) }.exceptionOrNull()
                assertTrue("$id $minutes: $error", error is IllegalArgumentException)
            }
        }

        assertEquals(SettingsEntity("user-id-1", SyncMode.ON, 60, checkMinutes = 15), settings("user-id-1"))
        assertEquals(TimeUnit.MINUTES.toMillis(15), period("user-id-1"))
        assertEquals(SettingsEntity("user-id-2", SyncMode.DRY_RUN, 60, checkMinutes = 15), settings("user-id-2"))
    }

    @Test
    fun `a mode or timing whose Room write fails has already turned Android's automatic sync on`() = runTest {
        // The schedule goes first: a failure (or crash) after it leaves Room on the old choice, which reconciliation
        // brings the flags back to, never sync on with automatic sync off.
        start()
        existing("user-id-1", LoginState.ACTIVE, interval = 60)
        failUpdates("settings")

        assertTrue(runCatching { lifecycle.setSyncMode(AccountId("user-id-1"), SyncMode.ON) }.isFailure)
        assertTrue(automatic("user-id-1"))
        ContentResolver.setSyncAutomatically(android("user-id-1"), ContactsContract.AUTHORITY, false)

        assertTrue(runCatching { lifecycle.setInterval(AccountId("user-id-1"), 30) }.isFailure)
        assertTrue(automatic("user-id-1"))
        ContentResolver.setSyncAutomatically(android("user-id-1"), ContactsContract.AUTHORITY, false)

        assertTrue(runCatching { lifecycle.setCheckInterval(AccountId("user-id-1"), 30) }.isFailure)
        assertTrue(automatic("user-id-1"))
        ContentResolver.setSyncAutomatically(android("user-id-1"), ContactsContract.AUTHORITY, false)

        assertTrue(runCatching { lifecycle.setManual(AccountId("user-id-1"), false) }.isFailure)
        assertTrue(automatic("user-id-1"))
        assertEquals(SettingsEntity("user-id-1", SyncMode.DRY_RUN, 60), settings("user-id-1"))
    }

    @Test
    fun `a mode or timing whose caller leaves still finishes`() = runTest {
        start()
        existing("user-id-1", LoginState.ACTIVE)
        existing("user-id-2", LoginState.ACTIVE)
        existing("user-id-3", LoginState.ACTIVE, SyncMode.ON)
        val gate = CompletableDeferred<Unit>()
        api.logout = { gate.await() }
        // Both wait behind a logout of the other account, and their callers leave meanwhile.
        val logout = async { lifecycle.logout(AccountId("user-id-2")) }
        runCurrent()
        val mode = async { lifecycle.setSyncMode(AccountId("user-id-1"), SyncMode.ON) }
        val interval = async { lifecycle.setInterval(AccountId("user-id-1"), 120) }
        val check = async { lifecycle.setCheckInterval(AccountId("user-id-1"), 60) }
        val manual = async { lifecycle.setManual(AccountId("user-id-3"), true) }
        runCurrent()
        mode.cancel()
        interval.cancel()
        check.cancel()
        manual.cancel()
        gate.complete(Unit)
        logout.await()
        advanceUntilIdle()

        assertEquals(SettingsEntity("user-id-1", SyncMode.ON, 120, checkMinutes = 60), settings("user-id-1"))
        assertEquals(TimeUnit.MINUTES.toMillis(60), period("user-id-1"))
        assertEquals(SettingsEntity("user-id-3", SyncMode.ON, manual = true), settings("user-id-3"))
        assertEquals(emptyList<WorkInfo>(), jobs("user-id-3"))
    }

    @Test
    fun `only the listed intervals are accepted`() = runTest {
        start()
        existing("user-id-1", LoginState.ACTIVE)

        val error = runCatching { lifecycle.setInterval(AccountId("user-id-1"), 45) }.exceptionOrNull()

        assertTrue("$error", error is IllegalArgumentException)

        assertEquals(60, settings("user-id-1")?.intervalMinutes)
    }

    @Test
    fun `an unknown account's mode and timing change nothing`() = runTest {
        start()

        lifecycle.setSyncMode(AccountId("user-id-9"), SyncMode.ON)
        lifecycle.setInterval(AccountId("user-id-9"), 30)
        lifecycle.setCheckInterval(AccountId("user-id-9"), 15)
        lifecycle.setManual(AccountId("user-id-9"), true)

        assertNull(settings("user-id-9"))
        assertEquals(emptyList<WorkInfo>(), jobs("user-id-9"))
    }

    // ---- Logout ----

    @Test
    fun `logout stops syncing, signs out, ends the session once, then deletes the credentials, keeping everything else`() = runTest {
        start()
        existing("user-id-1", LoginState.ACTIVE, SyncMode.ON)
        importRecord("user-id-1")

        val result = lifecycle.logout(AccountId("user-id-1"))

        assertEquals(LogoutResult(sessionEnded = true), result)
        assertEquals(
            listOf("logout uid-user-id-1 (Room: SIGNED_OUT, job: none, syncable: 0)", "delete user-id-1 (Room: SIGNED_OUT, A5: sent)"),
            events,
        )
        assertFalse(store.saved.containsKey(AccountId("user-id-1")))
        assertEquals(LoginState.SIGNED_OUT, record("user-id-1")?.loginState)
        assertEquals("the mode stays for the next login", SettingsEntity("user-id-1", SyncMode.ON, 60), settings("user-id-1"))
        assertEquals(mapOf(AccountId("user-id-1") to "user-id-1@example.test"), directory.accounts)
        assertEquals(mapOf("user-id-1@example.test" to 3), phone.rows)
        assertEquals(emptyList<String>(), phone.calls)
        assertEquals("import records stay", listOf("7->10"), importRecords("user-id-1"))

        assertNull("later runs do nothing", coordinator.request(AccountId("user-id-1"), SyncRequest.Full()))
        assertEquals(listOf("logout uid-user-id-1"), api.calls)
        assertEquals(emptyList<String>(), engine.calls)
    }

    @Test
    fun `logout turns the job and flag off before it writes SIGNED_OUT`() = runTest {
        // If the write then fails, the account is still signed in with its credentials and session, safe to retry;
        // reconciliation turns the flags back on.
        start()
        existing("user-id-1", LoginState.ACTIVE, SyncMode.ON)
        failUpdates("account_record")

        assertTrue(runCatching { lifecycle.logout(AccountId("user-id-1")) }.isFailure)

        assertEquals(0, syncable("user-id-1"))
        assertEquals(emptyList<WorkInfo>(), jobs("user-id-1"))
        assertTrue(store.saved.containsKey(AccountId("user-id-1")))
        assertEquals(emptyList<String>(), api.calls)
    }

    @Test
    fun `logout stops a running sync before signing out`() = runTest {
        start()
        existing("user-id-1", LoginState.ACTIVE, SyncMode.ON)
        engine.gates(1)
        val running = async { coordinator.request(AccountId("user-id-1"), SyncRequest.Full()) }
        runCurrent()

        lifecycle.logout(AccountId("user-id-1"))

        assertEquals(1, engine.cancelled)
        assertNull(running.await())
    }

    @Test
    fun `a logout whose caller leaves during the A5 still sends it and deletes the credentials`() = runTest {
        start()
        existing("user-id-1", LoginState.ACTIVE)
        val gate = CompletableDeferred<Unit>()
        api.logout = { gate.await() }

        val logout = async { lifecycle.logout(AccountId("user-id-1")) }
        runCurrent()
        logout.cancel()
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("logout uid-user-id-1"), api.calls)
        assertFalse(store.saved.containsKey(AccountId("user-id-1")))
        assertEquals(LoginState.SIGNED_OUT, record("user-id-1")?.loginState)
    }

    @Test
    fun `logging out offline still signs out, saying the session may still be open`() = runTest {
        start()
        existing("user-id-1", LoginState.ACTIVE)
        api.logout = { throw ProtonError.Network() }

        assertEquals(LogoutResult(sessionEnded = false), lifecycle.logout(AccountId("user-id-1")))

        assertFalse(store.saved.containsKey(AccountId("user-id-1")))
        assertEquals(LoginState.SIGNED_OUT, record("user-id-1")?.loginState)
    }

    @Test
    fun `logout's A5 gives up after 10 seconds`() = runTest {
        start()
        existing("user-id-1", LoginState.ACTIVE)
        api.logout = { awaitCancellation() }

        assertEquals(LogoutResult(sessionEnded = false), lifecycle.logout(AccountId("user-id-1")))

        assertEquals(10_000L, currentTime)
        assertFalse(store.saved.containsKey(AccountId("user-id-1")))
    }

    @Test
    fun `without readable credentials logout sends nothing`() = runTest {
        start()
        existing("user-id-1", LoginState.ACTIVE, uid = null)

        assertEquals(LogoutResult(sessionEnded = false), lifecycle.logout(AccountId("user-id-1")))

        assertEquals(emptyList<String>(), api.calls)
        assertEquals(LoginState.SIGNED_OUT, record("user-id-1")?.loginState)
    }

    @Test
    fun `a credential delete that fails leaves the account signed out`() = runTest {
        start()
        existing("user-id-1", LoginState.ACTIVE)
        store.deleteFailure = CredentialStoreException()

        val error = runCatching { lifecycle.logout(AccountId("user-id-1")) }.exceptionOrNull()

        assertTrue("$error", error is CredentialStoreException)

        assertEquals(LoginState.SIGNED_OUT, record("user-id-1")?.loginState)
    }

    @Test
    fun `logging out an unknown account changes nothing`() = runTest {
        start()

        assertEquals(LogoutResult(sessionEnded = false), lifecycle.logout(AccountId("user-id-9")))

        assertEquals(emptyList<String>(), api.calls)
        assertEquals(emptyList<String>(), events)
    }

    // ---- Remove from this phone ----

    @Test
    fun `removal logs out, removes the Android account, then its rows and every Room row`() = runTest {
        start()
        existing("user-id-1", LoginState.ACTIVE, SyncMode.ON)
        existing("user-id-2", LoginState.SIGNED_OUT, uid = null)
        importRecord("user-id-1")
        importRecord("user-id-2")

        val result = lifecycle.removeFromPhone(AccountId("user-id-1"))

        assertEquals(RemovalResult(accountRemoved = true, sessionEnded = true), result)
        assertEquals(
            listOf(
                "logout uid-user-id-1 (Room: SIGNED_OUT, job: none, syncable: 0)", "delete user-id-1 (Room: SIGNED_OUT, A5: sent)",
                "remove user-id-1", "deleteRows user-id-1@example.test (Room: SIGNED_OUT)",
            ),
            events,
        )
        assertNull(record("user-id-1"))
        assertNull(settings("user-id-1"))
        assertEquals(emptyList<WorkInfo>(), jobs("user-id-1"))
        assertEquals(mapOf(AccountId("user-id-2") to "user-id-2@example.test"), directory.accounts)
        assertEquals("another account's rows stay", mapOf("user-id-2@example.test" to 3), phone.rows)
        assertEquals(LoginState.SIGNED_OUT, record("user-id-2")?.loginState)
        assertEquals(emptyList<String>(), importRecords("user-id-1"))
        assertEquals("another account's import records stay", listOf("7->10"), importRecords("user-id-2"))
    }

    @Test
    fun `a removal whose caller leaves during the A5 still finishes`() = runTest {
        start()
        existing("user-id-1", LoginState.ACTIVE)
        val gate = CompletableDeferred<Unit>()
        api.logout = { gate.await() }

        val removal = async { lifecycle.removeFromPhone(AccountId("user-id-1")) }
        runCurrent()
        removal.cancel()
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(emptyMap<AccountId, String>(), directory.accounts)
        assertEquals(emptyMap<String, Int>(), phone.rows)
        assertNull(record("user-id-1"))
    }

    @Test
    fun `no run starts until a removal is done`() = runTest {
        start()
        existing("user-id-1", LoginState.ACTIVE)
        val gate = CompletableDeferred<Unit>()
        api.logout = { gate.await() }

        val removal = async { lifecycle.removeFromPhone(AccountId("user-id-1")) }
        runCurrent()

        assertNull("Sync now during the A5", coordinator.request(AccountId("user-id-1"), SyncRequest.DryRun))
        gate.complete(Unit)
        removal.await()
        assertEquals(emptyList<String>(), engine.calls)
    }

    @Test
    fun `rows under a name another account now holds are never deleted`() = runTest {
        // The account was removed in Android Settings; another Proton account then logged in with the same address.
        start()
        existing("user-id-1", LoginState.SIGNED_OUT, uid = null)
        directory.accounts.clear()
        directory.accounts[AccountId("user-id-2")] = "user-id-1@example.test"

        assertEquals(RemovalResult(accountRemoved = true, sessionEnded = false), lifecycle.removeFromPhone(AccountId("user-id-1")))

        assertEquals(mapOf("user-id-1@example.test" to 3), phone.rows)
        assertEquals(emptyList<String>(), phone.calls)
        assertNull(record("user-id-1"))
    }

    @Test
    fun `rows under a name an account without a proton_user_id holds are never deleted by a removal`() = runTest {
        start()
        existing("user-id-1", LoginState.SIGNED_OUT, uid = null)
        directory.accounts.clear()
        directory.unlinked += "user-id-1@example.test"

        lifecycle.removeFromPhone(AccountId("user-id-1"))

        assertEquals(mapOf("user-id-1@example.test" to 3), phone.rows)
    }

    @Test
    fun `removing a signed-out account sends nothing`() = runTest {
        start()
        existing("user-id-1", LoginState.SIGNED_OUT, uid = null)

        assertEquals(RemovalResult(accountRemoved = true, sessionEnded = false), lifecycle.removeFromPhone(AccountId("user-id-1")))

        assertEquals(emptyList<String>(), api.calls)
        assertNull(record("user-id-1"))
        assertEquals(emptyMap<String, Int>(), phone.rows)
    }

    @Test
    fun `a signed-out account's leftover credentials get no A5 but are deleted`() = runTest {
        // An interrupted logout: SIGNED_OUT was written, the credentials not yet deleted.
        start()
        existing("user-id-1", LoginState.SIGNED_OUT, uid = "uid-left")

        assertEquals(RemovalResult(accountRemoved = true, sessionEnded = false), lifecycle.removeFromPhone(AccountId("user-id-1")))

        assertEquals(emptyList<String>(), api.calls)
        assertFalse(store.saved.containsKey(AccountId("user-id-1")))
    }

    @Test
    fun `a removal Android refuses keeps the account's Room rows and contacts`() = runTest {
        start()
        existing("user-id-1", LoginState.ACTIVE)
        directory.refuseRemoval = true

        assertEquals(RemovalResult(accountRemoved = false, sessionEnded = true), lifecycle.removeFromPhone(AccountId("user-id-1")))

        assertEquals(LoginState.SIGNED_OUT, record("user-id-1")?.loginState)
        assertEquals(SettingsEntity("user-id-1", SyncMode.DRY_RUN, 60), settings("user-id-1"))
        assertEquals(mapOf("user-id-1@example.test" to 3), phone.rows)
    }

    @Test
    fun `rows that can't be deleted now are left for the next reconciliation`() = runTest {
        for (failure in listOf(SecurityException(), ProviderUnavailableException(), IllegalArgumentException("Unknown URL"))) {
            start()
            db.clearAllTables()
            existing("user-id-1", LoginState.ACTIVE)
            phone.deleteFailure = failure

            assertEquals("$failure", RemovalResult(accountRemoved = true, sessionEnded = true), lifecycle.removeFromPhone(AccountId("user-id-1")))

            assertNull("$failure", record("user-id-1"))
            assertEquals("$failure", mapOf("user-id-1@example.test" to 3), phone.rows)
        }
    }

    @Test
    fun `removing an unknown account changes nothing`() = runTest {
        start()

        assertEquals(RemovalResult(accountRemoved = false, sessionEnded = false), lifecycle.removeFromPhone(AccountId("user-id-9")))

        assertEquals(emptyList<String>(), events)
    }

    // ---- Reconciliation ----

    @Test
    fun `reconciliation puts back a signed-in account's job and flag, but never Android's automatic sync`() = runTest {
        start()
        existing("user-id-1", LoginState.ACTIVE, SyncMode.ON)
        workManager.cancelUniqueWork("sync.periodic.user-id-1")
        ContentResolver.setIsSyncable(android("user-id-1"), ContactsContract.AUTHORITY, 0)
        ContentResolver.setSyncAutomatically(android("user-id-1"), ContactsContract.AUTHORITY, false)

        lifecycle.reconcile()

        assertEquals(1, jobs("user-id-1").size)
        assertEquals(1, syncable("user-id-1"))
        assertFalse("the user turned it off in Android Settings", automatic("user-id-1"))
    }

    @Test
    fun `after the upgrade's migration, reconciliation stops the job and the flag, and a sync asked for runs nothing`() = runTest {
        // After an upgrade that adds uploads, the v1→v2 migration put the account back in dry run with the notice
        // (SyncMigrationTest); its job and flag are still v0.1.0's.
        start()
        existing("user-id-1", LoginState.ACTIVE, SyncMode.ON)
        assertEquals(1, jobs("user-id-1").size)
        db.openHelper.writableDatabase.execSQL("UPDATE settings SET sync_mode = 'DRY_RUN', upgrade_notice = 1 WHERE account_id = 'user-id-1'")

        lifecycle.reconcile()

        assertEquals(emptyList<WorkInfo>(), jobs("user-id-1"))
        assertEquals(0, syncable("user-id-1"))
        assertTrue("reconciliation never changes Android's automatic sync", automatic("user-id-1"))
        assertEquals(SettingsEntity("user-id-1", SyncMode.DRY_RUN, 60, upgradeNotice = true), db.accountStateDao().settings("user-id-1"))
        assertNull("no sync before Start", coordinator.request(AccountId("user-id-1"), SyncRequest.Full()))
        assertEquals(emptyList<String>(), engine.calls)
        assertEquals(emptyList<String>(), api.calls)
    }

    @Test
    fun `an account that still exists loses nothing, whatever asked for the reconciliation`() = runTest {
        // Only the Android account's absence ever deletes anything.
        start()
        existing("user-id-1", LoginState.ACTIVE)

        lifecycle.reconcile()

        assertEquals(LoginState.ACTIVE, record("user-id-1")?.loginState)
        assertTrue(store.saved.containsKey(AccountId("user-id-1")))
        assertEquals(mapOf("user-id-1@example.test" to 3), phone.rows)
        assertEquals(mapOf(AccountId("user-id-1") to "user-id-1@example.test"), directory.accounts)
        assertEquals(emptyList<String>(), api.calls)
    }

    @Test
    fun `an interrupted logout's credentials are deleted, sending nothing`() = runTest {
        // Killed after SIGNED_OUT, before the credentials went.
        start()
        existing("user-id-1", LoginState.SIGNED_OUT, uid = "uid-left")

        lifecycle.reconcile()

        assertFalse(store.saved.containsKey(AccountId("user-id-1")))
        assertEquals(emptyList<String>(), api.calls)
        assertEquals(LoginState.SIGNED_OUT, record("user-id-1")?.loginState)
        assertEquals(mapOf("user-id-1@example.test" to 3), phone.rows)
    }

    @Test
    fun `an Android account without its Room row gets one in dry run`() = runTest {
        // "Clear storage" keeps the account and its rows; the state then follows from the credentials.
        start()
        directory.accounts[AccountId("user-id-1")] = "user-id-1@example.test"
        phone.rows["user-id-1@example.test"] = 3
        store.saved[AccountId("user-id-1")] = Tokens("uid-1", "access-1", "refresh-1")

        lifecycle.reconcile()

        assertEquals(AccountRecordEntity("user-id-1", "user-id-1@example.test", LoginState.ACTIVE), record("user-id-1"))
        assertEquals(SettingsEntity("user-id-1", SyncMode.DRY_RUN, 60), settings("user-id-1"))
        assertEquals(0, syncable("user-id-1"))
        assertEquals(emptyList<WorkInfo>(), jobs("user-id-1"))
        assertTrue(store.saved.containsKey(AccountId("user-id-1")))
        assertEquals(mapOf("user-id-1@example.test" to 3), phone.rows)
    }

    @Test
    fun `an account whose Android account is gone loses its credentials, Room rows, job, and contacts`() = runTest {
        start()
        existing("user-id-1", LoginState.ACTIVE, SyncMode.ON)
        existing("user-id-2", LoginState.SIGNED_OUT, uid = null)
        importRecord("user-id-1")
        importRecord("user-id-2")
        directory.accounts.remove(AccountId("user-id-1"))

        lifecycle.reconcile()

        assertNull(record("user-id-1"))
        assertNull(settings("user-id-1"))
        assertFalse(store.saved.containsKey(AccountId("user-id-1")))
        assertEquals(emptyList<WorkInfo>(), jobs("user-id-1"))
        assertEquals(mapOf("user-id-2@example.test" to 3), phone.rows)
        assertEquals(LoginState.SIGNED_OUT, record("user-id-2")?.loginState)
        assertEquals(emptyList<String>(), importRecords("user-id-1"))
        assertEquals(listOf("7->10"), importRecords("user-id-2"))
        assertEquals(emptyList<String>(), api.calls)
    }

    @Test
    fun `a gone account's running sync is stopped before anything of it is forgotten`() = runTest {
        // Otherwise the run could save maps after the Room rows went, and a later login would hold those contacts.
        start()
        existing("user-id-1", LoginState.ACTIVE, SyncMode.ON)
        engine.gates(1)
        val running = async { coordinator.request(AccountId("user-id-1"), SyncRequest.Full()) }
        runCurrent()
        directory.accounts.remove(AccountId("user-id-1"))
        val runsWhenCredentialsWent = mutableListOf<Int>()
        store.onDelete = { runsWhenCredentialsWent += engine.running }

        lifecycle.reconcile()

        assertEquals("the run had ended before anything was deleted", listOf(0), runsWhenCredentialsWent)
        assertEquals(1, engine.cancelled)
        assertNull(running.await())
        assertNull(record("user-id-1"))
    }

    @Test
    fun `a gone account's credentials go before its Room row, so a failed delete is finished next time`() = runTest {
        start()
        existing("user-id-1", LoginState.ACTIVE)
        directory.accounts.remove(AccountId("user-id-1"))
        store.deleteFailure = CredentialStoreException()

        assertTrue(runCatching { lifecycle.reconcile() }.exceptionOrNull() is CredentialStoreException)
        assertEquals("kept, so the next reconciliation finds the credentials", LoginState.ACTIVE, record("user-id-1")?.loginState)

        store.deleteFailure = null
        lifecycle.reconcile()

        assertNull(record("user-id-1"))
        assertFalse(store.saved.containsKey(AccountId("user-id-1")))
    }

    @Test
    fun `a reconciliation whose caller leaves once it has started still finishes`() = runTest {
        start()
        existing("user-id-1", LoginState.ACTIVE)
        directory.accounts.remove(AccountId("user-id-1"))

        // Undispatched: it takes the lock and runs to its first suspension before the caller leaves.
        val reconcile = async(start = CoroutineStart.UNDISPATCHED) { lifecycle.reconcile() }
        reconcile.cancel()
        advanceUntilIdle()

        assertNull(record("user-id-1"))
        assertFalse(store.saved.containsKey(AccountId("user-id-1")))
    }

    @Test
    fun `an account whose name an account without a proton_user_id still holds is kept whole`() = runTest {
        // Ambiguous: the ID can't be read, so nothing of the account is deleted.
        start()
        existing("user-id-1", LoginState.ACTIVE)
        directory.accounts.remove(AccountId("user-id-1"))
        directory.unlinked += "user-id-1@example.test"

        lifecycle.reconcile()

        assertEquals(LoginState.ACTIVE, record("user-id-1")?.loginState)
        assertTrue(store.saved.containsKey(AccountId("user-id-1")))
        assertEquals(mapOf("user-id-1@example.test" to 3), phone.rows)
    }

    @Test
    fun `rows under a name an Android account of the app's type holds are never swept`() = runTest {
        start()
        // A gone account whose name another account now holds, an account without a proton_user_id, and a live one.
        existing("user-id-1", LoginState.SIGNED_OUT, uid = null)
        directory.accounts.clear()
        directory.accounts[AccountId("user-id-2")] = "user-id-1@example.test"
        directory.unlinked += "unlinked@example.test"
        phone.rows["unlinked@example.test"] = 2
        phone.rows["gone@example.test"] = 4

        lifecycle.reconcile()

        assertEquals(mapOf("user-id-1@example.test" to 3, "unlinked@example.test" to 2), phone.rows)
        assertNull(record("user-id-1"))
    }

    @Test
    fun `rows the provider can't delete or list now are left for the next reconciliation`() = runTest {
        start()
        for (fail in listOf<(Exception) -> Unit>({ phone.deleteFailure = it }, { phone.namesFailure = it })) {
            for (failure in listOf(SecurityException(), ProviderUnavailableException(), IllegalArgumentException("Unknown URL"))) {
                phone.deleteFailure = null
                phone.namesFailure = null
                phone.rows.clear()
                phone.rows["gone@example.test"] = 4
                existing("user-id-1", LoginState.ACTIVE)
                directory.accounts.remove(AccountId("user-id-1"))
                fail(failure)

                lifecycle.reconcile()

                assertNull("$failure", record("user-id-1"))
                assertEquals("$failure", 4, phone.rows["gone@example.test"])
            }
        }
    }

    @Test
    fun `every point a new login can be killed at ends with nothing or a complete account`() = runTest {
        // Room row → credentials → Android account.
        start()
        for (step in 1..3) {
            db.clearAllTables()
            store.saved.clear()
            directory.accounts.clear()
            db.openHelper.writableDatabase.execSQL("INSERT INTO account_record VALUES ('user-id-1', 'user-id-1@example.test', 'ACTIVE')")
            db.openHelper.writableDatabase.execSQL("INSERT INTO settings (account_id, sync_mode, interval_minutes) VALUES ('user-id-1', 'DRY_RUN', 60)")
            if (step >= 2) store.saved[AccountId("user-id-1")] = Tokens("uid-1", "access-1", "refresh-1")
            if (step >= 3) directory.accounts[AccountId("user-id-1")] = "user-id-1@example.test"

            lifecycle.reconcile()

            if (step < 3) {
                assertNull("$step", record("user-id-1"))
                assertFalse("$step", store.saved.containsKey(AccountId("user-id-1")))
            } else {
                assertEquals("$step", LoginState.ACTIVE, record("user-id-1")?.loginState)
                assertTrue("$step", store.saved.containsKey(AccountId("user-id-1")))
            }
        }
    }

    /** What a crash mid-login leaves: an `ACTIVE` Room row (with credentials) and no Android account. */
    private fun crashedLogin(id: String) {
        db.openHelper.writableDatabase.execSQL("INSERT INTO account_record VALUES ('$id', '$id@example.test', 'ACTIVE')")
        db.openHelper.writableDatabase.execSQL("INSERT INTO settings (account_id, sync_mode, interval_minutes) VALUES ('$id', 'DRY_RUN', 60)")
        store.saved[AccountId(id)] = Tokens("uid-$id-old", "access", "refresh")
    }

    @Test
    fun `a crashed login's row doesn't block another user's login`() = runTest {
        start()
        crashedLogin("user-id-2")

        assertEquals(LoginCommit.Committed(resumed = false), lifecycle.completeLogin(session("user-id-1")))

        assertNull(record("user-id-2"))
        assertFalse(store.saved.containsKey(AccountId("user-id-2")))
    }

    @Test
    fun `a crashed login's row isn't taken for the account when the same user logs in again`() = runTest {
        start()
        crashedLogin("user-id-1")

        assertEquals(LoginCommit.Committed(resumed = false), lifecycle.completeLogin(session("user-id-1")))

        assertEquals("user-id-1@example.test", directory.accounts[AccountId("user-id-1")])
        assertEquals(LoginState.ACTIVE, record("user-id-1")?.loginState)
    }

    @Test
    fun `a reconciliation waits for an account operation in progress`() = runTest {
        start()
        existing("user-id-1", LoginState.ACTIVE)
        directory.accounts.remove(AccountId("user-id-1"))
        val gate = CompletableDeferred<Unit>()
        api.logout = { gate.await() }

        val logout = async { lifecycle.logout(AccountId("user-id-1")) }
        runCurrent()
        val reconcile = async { lifecycle.reconcile() }
        runCurrent()

        assertTrue("the logout still owns the account", record("user-id-1") != null)
        gate.complete(Unit)
        logout.await()
        reconcile.await()
        assertNull(record("user-id-1"))
    }

    @Test
    fun `a reconciliation whose caller leaves while it waits doesn't run`() = runTest {
        start()
        existing("user-id-1", LoginState.ACTIVE)
        existing("user-id-2", LoginState.SIGNED_OUT, uid = "uid-left")
        val gate = CompletableDeferred<Unit>()
        api.logout = { gate.await() }

        val logout = async { lifecycle.logout(AccountId("user-id-1")) }
        runCurrent()
        val reconcile = async { lifecycle.reconcile() }
        runCurrent()
        reconcile.cancel()
        gate.complete(Unit)
        logout.await()
        advanceUntilIdle()

        assertTrue("its wait was cancellable", store.saved.containsKey(AccountId("user-id-2")))
    }

    // ---- One at a time ----

    @Test
    fun `account operations run one at a time`() = runTest {
        start()
        existing("user-id-1", LoginState.ACTIVE)
        val gate = CompletableDeferred<Unit>()
        api.logout = { gate.await() }

        val logout = async { lifecycle.logout(AccountId("user-id-1")) }
        runCurrent()
        val login = async { lifecycle.completeLogin(session("user-id-2")) }
        runCurrent()

        assertFalse("the login waits for the logout", store.saved.containsKey(AccountId("user-id-2")))
        gate.complete(Unit)
        logout.await()
        assertEquals("the logout finished first, so the login isn't refused", LoginCommit.Committed(resumed = false), login.await())
    }

    // ---- Fakes ----

    inner class FakeStore : CredentialStore {
        /** The stored tokens by account; a save copies them, as the real envelope does. */
        val saved = mutableMapOf<AccountId, Tokens>()
        var saveFailure: Exception? = null
        var deleteFailure: Exception? = null
        var onDelete: () -> Unit = {}

        override fun save(account: AccountId, credentials: Credentials) {
            saveFailure?.let { throw it }
            saved[account] = credentials.tokens
            events += "save ${account.value} (Room: ${record(account.value)?.loginState})"
        }

        override fun load(account: AccountId): Credentials? =
            saved[account]?.let { Credentials(it, PasswordMode.ONE, emptyMap(), LockedKeyBundle(emptyList()), emptyMap()) }

        override fun delete(account: AccountId) {
            onDelete()
            deleteFailure?.let { throw it }
            saved.remove(account)
            events += "delete ${account.value} (Room: ${record(account.value)?.loginState}, A5: ${if (api.calls.isEmpty()) "not sent" else "sent"})"
        }

        override fun updateTokens(account: AccountId, tokens: Tokens) = error("not used by the lifecycle")
        override fun updateKeys(account: AccountId, uid: String, keys: LockedKeyBundle) = error("not used by the lifecycle")

        override fun deleteSession(account: AccountId, uid: String) = error("not used by the lifecycle")
    }

    inner class FakeDirectory : AccountDirectory {
        val accounts = mutableMapOf<AccountId, String>()
        val taken = mutableSetOf<String>()
        var refuseRemoval = false
        var addFailure: Exception? = null

        override fun add(account: AccountId, name: String): Boolean {
            addFailure?.let { throw it }
            if (name in taken || name in accounts.values) return false
            accounts[account] = name
            val saved = if (store.saved.containsKey(account)) "saved" else "missing"
            events += "add ${account.value} $name (Room: ${record(account.value)?.loginState}, credentials: $saved)"
            return true
        }

        override fun remove(account: AccountId): Boolean {
            events += "remove ${account.value}"
            if (refuseRemoval) return false
            accounts.remove(account)
            return true
        }

        override fun live(): Map<AccountId, String> = accounts.toMap()

        /** Accounts of the app's type without a `proton_user_id`. */
        val unlinked = mutableSetOf<String>()

        override fun names(): Set<String> = accounts.values.toSet() + unlinked
    }

    inner class FakePhone : DeviceContacts {
        /** Row counts by account name. */
        val rows = mutableMapOf<String, Int>()
        val calls = mutableListOf<String>()
        var deleteFailure: Exception? = null

        override fun query(account: PhoneAccount): List<DeviceContact> = error("the lifecycle reads no contacts")

        override fun apply(account: PhoneAccount, writes: List<ContactWrite>): List<ContactWriteResult> = error("the lifecycle writes no contacts")

        override fun clearDirty(account: PhoneAccount, protonId: String, existing: ExistingRow): ContactWriteResult = error("the lifecycle writes no contacts")
        override fun bind(account: PhoneAccount, protonId: String, rawContactId: Long): ContactWriteResult = error("the lifecycle writes no contacts")
        override fun removeTombstone(account: PhoneAccount, protonId: String, rawContactId: Long): ContactWriteResult = error("the lifecycle writes no contacts")
        override fun removeRow(account: PhoneAccount, protonId: String, existing: ExistingRow): ContactWriteResult = error("the lifecycle writes no contacts")
        override fun detach(account: PhoneAccount, protonId: String, existing: ExistingRow): ContactWriteResult = error("the lifecycle writes no contacts")
        override fun insertLocal(rows: List<DataRow>, starred: Boolean): ContactWriteResult = error("the lifecycle writes no contacts")
        override fun queryLocal(): List<DeviceContact> = error("the lifecycle reads no contacts")
        override fun insertImport(account: PhoneAccount, original: ExistingRow, rows: List<DataRow>, starred: Boolean): ContactWriteResult =
            error("the lifecycle writes no contacts")
        override fun rewrite(account: PhoneAccount, protonId: String, existing: ExistingRow, original: ExistingRow, rows: List<DataRow>, keepPhotoRow: Boolean): ContactWriteResult = error("the lifecycle writes no contacts")
        override fun removeOriginal(account: PhoneAccount, original: ExistingRow, protonId: String, copy: ExistingRow, copyStarred: Boolean): ContactWriteResult = error("the lifecycle writes no contacts")

        override fun maxPhotoDimension(): Int = error("the lifecycle reads no photos")

        override fun readRows(rawContactId: Long): List<DataRow> = error("the lifecycle reads no data rows")

        override fun photoHash(rawContactId: Long): String? = error("the lifecycle reads no photos")

        override fun photo(rawContactId: Long): ByteArray? = error("the lifecycle reads no photos")

        override fun untouched(rawContactId: Long): Boolean = error("the lifecycle reads no contacts")
        override fun thumbnailHash(rawContactId: Long): String? = error("the lifecycle reads no photos")

        override fun deleteAccountRows(accountName: String): Int {
            calls += "deleteAccountRows"
            deleteFailure?.let { throw it }
            events += "deleteRows $accountName (Room: ${record(accountName.substringBefore("@"))?.loginState})"
            return rows.remove(accountName) ?: 0
        }

        var namesFailure: Exception? = null

        override fun accountNamesWithRows(): Set<String> {
            namesFailure?.let { throw it }
            return rows.filterValues { it > 0 }.keys.toSet()
        }
    }

    inner class FakeApi : ProtonApi {
        val stores = mutableListOf<TokenStore>()
        val calls = mutableListOf<String>()
        var logout: suspend () -> Unit = {}

        override suspend fun logout() {
            val uid = stores.last().current()?.uid
            calls += "logout $uid"
            val id = uid?.removePrefix("uid-")
            val room = id?.let { record(it) }
            if (room != null) {
                events += "logout $uid (Room: ${room.loginState}, job: ${if (jobs(id).isEmpty()) "none" else "scheduled"}, syncable: ${syncable(id)})"
            }
            logout.invoke()
        }

        override suspend fun authInfo(username: String): AuthInfoDto = error("not used")
        override suspend fun auth(request: SrpAuthDto, captcha: CaptchaHeaders?): AuthDto = error("not used")
        override suspend fun submitTotp(code: String) = error("not used")
        override suspend fun user(): UserDto = error("not used")
        override suspend fun keySalts(): List<KeySaltDto> = error("not used")
        override suspend fun exportContacts(page: Int, pageSize: Int): ContactExportPageDto = error("not used")
        override suspend fun listContacts(page: Int, pageSize: Int): ContactListPageDto = error("not used")
        override suspend fun contact(id: ProtonContactId): ContactDto = error("not used")
        override suspend fun createContact(cards: List<CardDto>): CreateReply = error("not used")
        override suspend fun updateContact(id: ProtonContactId, cards: List<CardDto>): Unit = error("not used")
        override suspend fun deleteContact(id: ProtonContactId): Unit = error("not used")
        override suspend fun latestEventId(): EventId = error("not used")
        override suspend fun events(cursor: EventId): EventsReply = error("not used")
    }

    private companion object {
        // D4: tests use only the `.debug` account type.
        const val TYPE = "dev.rboza.android.opensyncforproton.debug"
    }
}
