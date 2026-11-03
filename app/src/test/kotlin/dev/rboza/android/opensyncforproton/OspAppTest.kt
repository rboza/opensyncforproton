package dev.rboza.android.opensyncforproton

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentResolver
import android.database.sqlite.SQLiteDatabase
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Looper
import android.provider.ContactsContract
import androidx.work.Data
import androidx.work.ForegroundUpdater
import androidx.work.ProgressUpdater
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.utils.taskexecutor.WorkManagerTaskExecutor
import dev.rboza.android.opensyncforproton.account.AccountId
import dev.rboza.android.opensyncforproton.diagnostics.LogEntry
import dev.rboza.android.opensyncforproton.diagnostics.Operation
import dev.rboza.android.opensyncforproton.diagnostics.Outcome
import dev.rboza.android.opensyncforproton.sync.db.Blocker
import dev.rboza.android.opensyncforproton.sync.db.ConflictHoldEntity
import dev.rboza.android.opensyncforproton.sync.db.HoldChoice
import dev.rboza.android.opensyncforproton.sync.db.HoldKind
import dev.rboza.android.opensyncforproton.sync.db.LoginState
import dev.rboza.android.opensyncforproton.sync.engine.SyncRequest
import dev.rboza.android.opensyncforproton.sync.schedule.SyncWorker
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.internal.platform.PlatformRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.w3c.dom.Element

/**
 * The composition root: the Application is the screens' AppGraph and WorkManager's configuration, builds SyncWorker,
 * reads the stores, records diagnostics with pseudonyms, and reconciles at start. The crypto library (native) is never
 * touched here. Names and IDs are made up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = OspApp::class)
class OspAppTest {
    private val app = RuntimeEnvironment.getApplication() as OspApp

    private fun params(account: String = "a1"): WorkerParameters {
        val executor = Executors.newSingleThreadExecutor()
        return WorkerParameters(
            UUID.randomUUID(), Data.Builder().putString(SyncWorker.ACCOUNT_ID, account).build(), emptyList(),
            WorkerParameters.RuntimeExtras(), 0, 0, executor, EmptyCoroutineContext, WorkManagerTaskExecutor(executor),
            object : WorkerFactory() {
                override fun createWorker(appContext: android.content.Context, workerClassName: String, workerParameters: WorkerParameters) = null
            },
            ProgressUpdater { _, _, _ -> error("unused") },
            ForegroundUpdater { _, _, _ -> error("unused") },
        )
    }

    @Test
    fun `the manifest names it as the Application`() {
        // Robolectric reports the Application this class is configured with, so read the manifest itself (the tests
        // run in the module's directory).
        val application = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
            .getElementsByTagName("application").item(0) as Element
        assertEquals(".OspApp", application.getAttributeNS(ANDROID, "name"))
    }

    @Test
    fun `it tells OkHttp the app's context at start`() {
        // OkHttp keeps the context in a static, so an earlier test's app would still be there. PlatformRegistry is
        // OkHttp's internal API: if an upgrade moves it, this stops compiling; find where initialize keeps it now.
        PlatformRegistry.applicationContext = null
        app.onCreate()
        assertSame(app, PlatformRegistry.applicationContext)
    }

    @Test
    fun `WorkManager's configuration is built once`() {
        assertSame(app.workManagerConfiguration, app.workManagerConfiguration)
    }

    @Test
    fun `the Proton client uses protocol md's base URL and app version`() {
        assertEquals("https://mail-api.proton.me/", OspApp.PROTON.baseUrl.toString())
        assertEquals("Other", OspApp.PROTON.appVersion)
        // Only the system's CAs (D8): the extra trust exists for the end-to-end test's own server.
        assertNull(OspApp.PROTON.trust)
        assertSame(OspApp.PROTON, app.protonConfig)
    }

    @Test
    fun `WorkManager logs nothing of its own`() {
        assertEquals(android.util.Log.ASSERT, app.workManagerConfiguration.minimumLoggingLevel)
    }

    @Test
    fun `WorkManager's factory builds the sync worker, and nothing else`() {
        val factory = app.workManagerConfiguration.workerFactory
        assertTrue(factory.createWorker(app, SyncWorker::class.java.name, params()) is SyncWorker)
        assertNull(factory.createWorker(app, "made.up.Worker", params()))
    }

    @Test
    fun `the stores start empty`() = runBlocking {
        assertEquals(emptyList<Any>(), app.accounts())
        assertNull(app.settings(AccountId("a1")))
        assertNull(app.lastReport(AccountId("a1")))
        // Robolectric has no AndroidKeyStore, so this never loads; the credential wiring is left to EndToEndTest.
        assertFalse(app.hasCredentials(AccountId("a1")))
        assertTrue(app.getDatabasePath("sync.db").isFile)
    }

    @Test
    fun `dismissing the upgrade notice clears Room's flag`() = runBlocking {
        recordAtStart("a1", NAME)
        // As the v1→v2 migration leaves an account that had sync on.
        SQLiteDatabase.openDatabase(app.getDatabasePath("sync.db").path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("UPDATE settings SET upgrade_notice = 1 WHERE account_id = 'a1'")
        }
        assertTrue(app.settings(AccountId("a1"))!!.upgradeNotice)

        app.dismissUpgradeNotice(AccountId("a1"))

        assertFalse(app.settings(AccountId("a1"))!!.upgradeNotice)
    }

    @Test
    fun `held contacts are read from Room, and a choice is saved only for the state the hold records`() = runBlocking {
        recordAtStart("a1", NAME)
        // As a sync leaves one.
        SQLiteDatabase.openDatabase(app.getDatabasePath("sync.db").path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("INSERT INTO conflict_hold (account_id, proton_id, raw_contact_id, kind, choice, version, fingerprint) VALUES ('a1', 'proton-id-1', 10, 'CONFLICT', NULL, 3, 'fingerprint-1')")
        }
        val hold = ConflictHoldEntity("a1", "proton-id-1", 10, HoldKind.CONFLICT, null, 3, "fingerprint-1")
        assertEquals(listOf(hold), app.holds(AccountId("a1")))

        assertTrue(app.choose(hold, HoldChoice.USE_PHONE))
        assertFalse("a state the hold no longer records", app.choose(hold.copy(version = 2), HoldChoice.USE_PROTON))

        assertEquals(listOf(hold.copy(choice = HoldChoice.USE_PHONE)), app.holds(AccountId("a1")))
        assertEquals(emptyList<Any>(), app.holds(AccountId("a2")))
    }

    @Test
    fun `credentials are kept in no-backup storage, one file per account`() = runBlocking {
        recordAtStart("a1", NAME)
        val envelope = File(app.noBackupFilesDir, "credentials/" + sha256Hex("a1"))
        envelope.parentFile!!.mkdirs()
        envelope.writeBytes(byteArrayOf(1, 2, 3))

        app.logout(AccountId("a1"))

        assertFalse(envelope.exists())
    }

    @Test
    fun `Android's automatic sync is on only with both switches on`() = runBlocking {
        val account = Account(NAME, app.packageName)
        ContentResolver.setMasterSyncAutomatically(true)
        ContentResolver.setSyncAutomatically(account, ContactsContract.AUTHORITY, true)
        assertTrue(app.androidAutoSync(NAME))

        ContentResolver.setSyncAutomatically(account, ContactsContract.AUTHORITY, false)
        assertFalse(app.androidAutoSync(NAME))

        ContentResolver.setSyncAutomatically(account, ContactsContract.AUTHORITY, true)
        ContentResolver.setMasterSyncAutomatically(false)
        assertFalse(app.androidAutoSync(NAME))
    }

    @Test
    fun `the permissions are both contacts ones, and the notification one`() {
        val shadow = shadowOf(app)
        shadow.grantPermissions(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS, Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(app.hasContactsPermission())
        assertTrue(app.hasNotificationPermission())

        shadow.denyPermissions(Manifest.permission.WRITE_CONTACTS, Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(app.hasContactsPermission())
        assertFalse(app.hasNotificationPermission())
    }

    @Test
    fun `a record is written with the time and the account's pseudonym, never its ID`() = runBlocking {
        app.log(LogEntry(Operation.LOGOUT, AccountId(NAME), Outcome.DONE))
        val line = exported()
        assertTrue(line, Regex("""\{"at":\d+,"op":"LOGOUT","account":"[0-9a-f]{24}","outcome":"DONE"\}""").matches(line.trim()))
        assertFalse(line.contains(NAME))
        assertTrue(File(app.noBackupFilesDir, "diagnostics/current.jsonl").isFile)
        assertEquals(32L, File(app.noBackupFilesDir, "diagnostics.key").length())
    }

    @Test
    fun `every run is recorded, with the account's pseudonym`() = runBlocking {
        recordAtStart(PROTON_ID, NAME)

        // No credentials, so the run stops at once, signed out, without the crypto library.
        app.requestSync(AccountId(PROTON_ID), SyncRequest.DryRun)

        val line = exported()
        assertTrue(line, Regex(""".*"op":"RUN_DRY","account":"[0-9a-f]{24}","outcome":"SIGNED_OUT".*""").matches(line.trim()))
        assertFalse(line.contains(PROTON_ID))
    }

    @Test
    fun `each report Room saves is told to the screens, and nothing at once`() = runBlocking {
        recordAtStart(PROTON_ID, NAME)
        val changes = Channel<Unit>(Channel.UNLIMITED)
        val watching = launch(Dispatchers.IO) { app.reportChanges().collect { changes.send(Unit) } }
        try {
            // Also the time the collector takes to start watching before the run saves.
            assertNull(withTimeoutOrNull(1_000) { changes.receive() })

            // No credentials, so the dry run stops at once, signed out, and saves its report.
            app.requestSync(AccountId(PROTON_ID), SyncRequest.DryRun)

            assertNotNull(withTimeoutOrNull(10_000) { changes.receive() })
        } finally {
            watching.cancel()
        }
    }

    @Test
    fun `an incremental run goes through the event loop, recorded as its own run`() = runBlocking {
        recordAtStart(PROTON_ID, NAME)
        SQLiteDatabase.openDatabase(app.getDatabasePath("sync.db").path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("UPDATE account_record SET login_state = 'ACTIVE' WHERE account_id = '$PROTON_ID'")
            it.execSQL("UPDATE settings SET sync_mode = 'ON' WHERE account_id = '$PROTON_ID'")
        }
        // No stored login, so the event loop's login check stops it first, offline too, and nothing is sent: sync
        // pauses while a login is needed.
        shadowOf(app.getSystemService(ConnectivityManager::class.java)).setActiveNetworkInfo(null)

        val report = app.requestSync(AccountId(PROTON_ID), SyncRequest.Incremental)

        assertEquals(Blocker.SIGNED_OUT, report?.blocker)
        val line = exported()
        assertTrue(line, Regex(""".*"op":"RUN_INCREMENTAL","account":"[0-9a-f]{24}","outcome":"SIGNED_OUT".*""").matches(line.trim()))
    }

    @Test
    fun `records keep their order`() = runBlocking {
        repeat(20) { app.log(LogEntry(Operation.SYNC_NOW, null, if (it % 2 == 0) Outcome.RAN else Outcome.FAILED)) }
        val outcomes = exported().lines().filter { it.isNotBlank() }.map { Regex(""""outcome":"(\w+)"""").find(it)!!.groupValues[1] }
        assertEquals(List(20) { if (it % 2 == 0) "RAN" else "FAILED" }, outcomes)
    }

    @Test
    fun `without its key, the log records nothing and still exports`() = runBlocking {
        File(app.noBackupFilesDir, "diagnostics.key").mkdirs() // a directory where the key file should be
        app.log(LogEntry(Operation.LOGOUT, null, Outcome.DONE))
        assertEquals("", exported())
    }

    @Test
    fun `at start, reconciliation recreates the record of an Android account the app has no record for`() = runBlocking {
        recordAtStart("a1", NAME)

        val record = app.accounts().single()
        assertEquals("a1", record.accountId)
        assertEquals(NAME, record.androidAccountName)
        assertEquals(LoginState.ACTIVE, record.loginState)
    }

    @Test
    fun `the sync worker reconciles before its run`() = runBlocking {
        recordAtStart("a1", NAME)
        ContentResolver.setMasterSyncAutomatically(true)
        ContentResolver.setSyncAutomatically(Account(NAME, app.packageName), ContactsContract.AUTHORITY, true)
        addAndroidAccount("a2", OTHER_NAME)

        val worker = app.workManagerConfiguration.workerFactory.createWorker(app, SyncWorker::class.java.name, params("a1")) as SyncWorker
        worker.doWork()

        assertEquals(setOf("a1", "a2"), app.accounts().map { it.accountId }.toSet())
    }

    @Test
    fun `the device is online with a network that reaches the internet`() {
        val connectivity = shadowOf(app.getSystemService(ConnectivityManager::class.java))
        val network = app.getSystemService(ConnectivityManager::class.java).activeNetwork
        val internet = ShadowNetworkCapabilities.newInstance().also { shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) }
        connectivity.setNetworkCapabilities(network, internet)
        assertTrue(app.isOnline())

        connectivity.setNetworkCapabilities(network, ShadowNetworkCapabilities.newInstance())
        assertFalse(app.isOnline())

        connectivity.setActiveNetworkInfo(null)
        assertFalse(app.isOnline())
    }

    private fun addAndroidAccount(protonId: String, name: String) {
        AccountManager.get(app).addAccountExplicitly(Account(name, app.packageName), null, Bundle().apply { putString("proton_user_id", protonId) })
    }

    /**
     * Adds an Android account the app has no record of, starts the app again, and waits for that start's
     * reconciliation. The test runner's own start is waited for first, so no reconciliation is left running after.
     */
    private suspend fun recordAtStart(protonId: String, name: String) {
        awaitStartUp()
        addAndroidAccount(protonId, name)
        app.onCreate()
        awaitStartUp()
        assertTrue("no record after start-up reconciliation", app.accounts().any { it.accountId == protonId })
    }

    private fun awaitStartUp() {
        val startUp = checkNotNull(app.reconciling)
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!startUp.isCompleted && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("start-up reconciliation still running", startUp.isCompleted)
    }

    private fun sha256Hex(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Waits for the log's single writer to have written everything handed to it, then exports. */
    private suspend fun exported(): String {
        val out = ByteArrayOutputStream()
        app.exportLogs(out)
        return out.toString(Charsets.UTF_8)
    }

    private companion object {
        const val NAME = "someone.made.up@example.test"
        const val ANDROID = "http://schemas.android.com/apk/res/android"
        const val OTHER_NAME = "someone.else.made.up@example.test"

        /** Has letters outside hex, so it can't show up inside a pseudonym by chance. */
        const val PROTON_ID = "made-up-user"
    }
}
