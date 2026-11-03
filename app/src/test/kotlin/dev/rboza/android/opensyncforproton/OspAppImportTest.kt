package dev.rboza.android.opensyncforproton

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.res.Resources
import android.database.Cursor
import android.database.MatrixCursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import dev.rboza.android.opensyncforproton.account.AccountId
import dev.rboza.android.opensyncforproton.mapping.MappingIssueCode
import dev.rboza.android.opensyncforproton.sync.db.ReportTrigger
import dev.rboza.android.opensyncforproton.sync.provider.ActionResult
import dev.rboza.android.opensyncforproton.sync.provider.ColumnValue
import dev.rboza.android.opensyncforproton.sync.provider.ImportMark
import dev.rboza.android.opensyncforproton.sync.provider.MatchReason
import dev.rboza.android.opensyncforproton.sync.provider.MergeChoice
import dev.rboza.android.opensyncforproton.sync.provider.MergeResult
import dev.rboza.android.opensyncforproton.sync.provider.OriginalState
import dev.rboza.android.opensyncforproton.sync.engine.RunReport
import dev.rboza.android.opensyncforproton.sync.engine.SyncRequest
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The composition root's import (docs/sync.md § Import from this phone): its list reads the local account Android names
 * (`RawContacts.getLocalAccountName` and `getLocalAccountType`, made up and different, so a swap is told apart) and the
 * app's account by its Android name, with the account's import records and holds from Room; its import writes copies
 * into the app's account and asks the coordinator for `Full(IMPORT)` after a copy, and its removal deletes originals
 * and asks for nothing, all off the main thread. A stand-in Contacts provider answers with made-up contacts and records
 * what it's asked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = OspAppImportTest.RequestingApp::class)
class OspAppImportTest {
    private val app = RuntimeEnvironment.getApplication() as OspApp

    @Before
    fun provider() {
        asked.clear()
        inserted.clear()
        deleted.clear()
        localRows = listOf(nameRow(LOCAL_PERSON), emailRow("local@example.test"))
        appName = OTHER_PERSON
        guardHolds = true
        requests.clear()
        held = null
        Robolectric.setupContentProvider(Contacts::class.java, ContactsContract.AUTHORITY)
    }

    private fun sql(statement: String) =
        SQLiteDatabase.openDatabase(app.getDatabasePath("sync.db").path, null, SQLiteDatabase.OPEN_READWRITE).use { it.execSQL(statement) }

    @Test
    fun `the list reads the local account Android names and the app's account by its name, off the main thread`() = runBlocking {
        val list = app.importList(AccountId(PROTON_ID), NAME)

        val raw = asked.filter { it.uri == RawContacts.CONTENT_URI && it.args.isNotEmpty() }.map { it.args }
        assertTrue(raw.toString(), listOf(LOCAL_TYPE, LOCAL_NAME) in raw)
        assertTrue(raw.toString(), listOf(app.packageName, NAME) in raw)
        assertFalse("on the main thread", asked.any { it.main })
        assertEquals(listOf(21L), list.candidates.map { it.original.row.rawContactId })
        assertEquals(ImportMark.NONE, list.candidates.single().mark)
        assertEquals(listOf(ColumnValue.Text("local@example.test")), list.candidates.single().original.rows.filter { it.mimeType == Email.CONTENT_ITEM_TYPE }.map { it.values[Email.ADDRESS] })
    }

    @Test
    fun `a contact in Proton with the same name pairs, and a held one doesn't`() = runBlocking {
        appName = LOCAL_PERSON
        val pairs = app.importList(AccountId(PROTON_ID), NAME).pairs
        assertEquals(listOf(21L to 7L), pairs.map { it.original.row.rawContactId to it.proton.row.rawContactId })
        assertEquals(MatchReason.NAME, pairs.single().reason)

        sql("INSERT INTO conflict_hold (account_id, proton_id, raw_contact_id, kind, choice, version, fingerprint) VALUES ('$PROTON_ID', '$APP_PROTON_ID', 7, 'CONFLICT', NULL, 3, 'fingerprint-1')")
        val list = app.importList(AccountId(PROTON_ID), NAME)
        assertEquals(emptyList<Any>(), list.pairs)
        assertEquals(ImportMark.LOOKS_IN_PROTON, list.candidates.single().mark)
    }

    @Test
    fun `the account's import records mark a contact imported before`() = runBlocking {
        // Opens Room, so its file is there.
        app.accounts()
        sql("INSERT INTO import_record (account_id, original_raw_contact_id, original_version, copy_raw_contact_id, photo_copied) VALUES ('$PROTON_ID', 21, 3, NULL, 0)")
        assertEquals(ImportMark.IMPORTED_BEFORE, app.importList(AccountId(PROTON_ID), NAME).candidates.single().mark)
        assertEquals(ImportMark.NONE, app.importList(AccountId("someone-else"), NAME).candidates.single().mark)
    }

    @Test
    fun `a contact a new card can't be made for can't be imported`() = runBlocking {
        localRows = listOf(phoneRow("+1 555 0100"))
        assertEquals(MappingIssueCode.NO_NAME_OR_EMAIL, app.importList(AccountId(PROTON_ID), NAME).candidates.single().cantImport)
        localRows = listOf(nameRow(LOCAL_PERSON))
        assertNull(app.importList(AccountId(PROTON_ID), NAME).candidates.single().cantImport)
    }

    @Test
    fun `an import writes the copy into the app's account, then asks for a full sync started by the import`() = runBlocking {
        recordAtStart()
        sql("UPDATE account_record SET login_state = 'ACTIVE' WHERE account_id = '$PROTON_ID'")
        sql("UPDATE settings SET sync_mode = 'ON' WHERE account_id = '$PROTON_ID'")
        val chosen = app.importList(AccountId(PROTON_ID), NAME).candidates.map { it.original }
        asked.clear()

        val result = app.importContacts(AccountId(PROTON_ID), NAME, chosen)

        assertEquals(ActionResult(done = 1, notDone = 0), result)
        assertFalse("on the main thread", asked.any { it.main })
        val raw = inserted.first()
        assertEquals(RawContacts.CONTENT_URI, raw.first)
        assertEquals(NAME, raw.second.getAsString(RawContacts.ACCOUNT_NAME))
        assertEquals(app.packageName, raw.second.getAsString(RawContacts.ACCOUNT_TYPE))
        assertEquals(listOf(StructuredName.CONTENT_ITEM_TYPE, Email.CONTENT_ITEM_TYPE), inserted.drop(1).map { it.second.getAsString(Data.MIMETYPE) })

        // No credentials, so the sync stops at once, signed out, and saves its report.
        val deadline = System.nanoTime() + 10_000_000_000L
        while (app.lastReport(AccountId(PROTON_ID)) == null && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals(ReportTrigger.IMPORT, app.lastReport(AccountId(PROTON_ID))?.trigger)
    }

    @Test
    fun `an import that copies nothing asks for no sync`() = runBlocking {
        recordAtStart()
        sql("UPDATE account_record SET login_state = 'ACTIVE' WHERE account_id = '$PROTON_ID'")
        sql("UPDATE settings SET sync_mode = 'ON' WHERE account_id = '$PROTON_ID'")
        val chosen = app.importList(AccountId(PROTON_ID), NAME).candidates.map { it.original }
        guardHolds = false

        assertEquals(ActionResult(done = 0, notDone = 1), app.importContacts(AccountId(PROTON_ID), NAME, chosen))

        Thread.sleep(1_000)
        assertEquals(emptyList<SyncRequest>(), requests.toList())
        assertNull(app.lastReport(AccountId(PROTON_ID)))
    }

    @Test
    fun `the import's result doesn't wait for the sync it asks for`() = runBlocking<Unit> {
        val chosen = app.importList(AccountId(PROTON_ID), NAME).candidates.map { it.original }
        held = CompletableDeferred()

        val result = withTimeout(10_000) { app.importContacts(AccountId(PROTON_ID), NAME, chosen) }

        assertEquals(ActionResult(done = 1, notDone = 0), result)
        val deadline = System.nanoTime() + 10_000_000_000L
        while (requests.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals(listOf(SyncRequest.Full(trigger = ReportTrigger.IMPORT)), requests.toList())
        checkNotNull(held).complete(Unit)
    }

    @Test
    fun `a merge rewrites Proton's row in the app's account, then asks for a full sync started by the import`() = runBlocking {
        appName = LOCAL_PERSON
        val pair = app.importList(AccountId(PROTON_ID), NAME).pairs.single()
        asked.clear()

        val result = app.applyMerges(AccountId(PROTON_ID), NAME, listOf(pair to MergeChoice.COMBINE))

        assertEquals(MergeResult(combined = 1, wrote = true), result)
        assertFalse("on the main thread", asked.any { it.main })
        // The phone's e-mail address joins Proton's name, written into Proton's row.
        assertEquals(listOf(7L, 7L), inserted.map { it.second.getAsLong(Data.RAW_CONTACT_ID) })
        assertEquals(listOf(StructuredName.CONTENT_ITEM_TYPE, Email.CONTENT_ITEM_TYPE), inserted.map { it.second.getAsString(Data.MIMETYPE) })
        val deadline = System.nanoTime() + 10_000_000_000L
        while (requests.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals(listOf(SyncRequest.Full(trigger = ReportTrigger.IMPORT)), requests.toList())
    }

    @Test
    fun `a merge that writes no contact asks for no sync`() = runBlocking {
        appName = LOCAL_PERSON
        val pair = app.importList(AccountId(PROTON_ID), NAME).pairs.single()

        assertEquals(MergeResult(keptProton = 1), app.applyMerges(AccountId(PROTON_ID), NAME, listOf(pair to MergeChoice.KEEP_PROTON)))
        guardHolds = false
        assertEquals(MergeResult(notApplied = 1), app.applyMerges(AccountId(PROTON_ID), NAME, listOf(pair to MergeChoice.COMBINE)))

        Thread.sleep(1_000)
        assertEquals(emptyList<Pair<Uri, ContentValues>>(), inserted.toList())
        assertEquals(emptyList<SyncRequest>(), requests.toList())
    }

    @Test
    fun `removing originals deletes the local account's original as an ordinary app off the main thread, and asks for no sync`() = runBlocking {
        // The copy shows what the original shows, so it's removable.
        localRows = listOf(nameRow(LOCAL_PERSON))
        appName = LOCAL_PERSON
        app.accounts()
        sql("INSERT INTO import_record (account_id, original_raw_contact_id, original_version, copy_raw_contact_id, photo_copied) VALUES ('$PROTON_ID', 21, 3, 7, 0)")
        val originals = app.importList(AccountId(PROTON_ID), NAME).originals
        assertEquals(listOf(OriginalState.REMOVABLE), originals.map { it.state })
        asked.clear()

        val result = app.removeOriginals(AccountId(PROTON_ID), NAME, originals)

        assertEquals(ActionResult(done = 1, notDone = 0), result)
        assertFalse("on the main thread", asked.any { it.main } || deleted.any { it.main })
        assertEquals(listOf(Asked(RawContacts.CONTENT_URI, listOf("21"), main = false)), deleted.toList())
        // The copy's guard names the app's account by its Android name, and the copy's Proton ID.
        assertTrue(asked.toString(), asked.any { it.args.firstOrNull() == "7" && NAME in it.args && APP_PROTON_ID in it.args })
        assertEquals("its record is deleted", emptyList<Any>(), app.importList(AccountId(PROTON_ID), NAME).originals)
        Thread.sleep(1_000)
        assertEquals(emptyList<SyncRequest>(), requests.toList())
    }

    /** The local account's made-up names, with each sync request recorded, and held until [held] completes when set. */
    class RequestingApp : OspAppKeepCopyTest.LocalAccountApp() {
        override suspend fun requestSync(account: AccountId, request: SyncRequest): RunReport? {
            requests += request
            held?.await()
            return super.requestSync(account, request)
        }
    }

    /** As OspAppTest's: an Android account the app has no record of, then a start whose reconciliation records it. */
    private suspend fun recordAtStart() {
        awaitStartUp()
        AccountManager.get(app).addAccountExplicitly(Account(NAME, app.packageName), null, Bundle().apply { putString("proton_user_id", PROTON_ID) })
        app.onCreate()
        awaitStartUp()
        assertTrue("no record after start-up reconciliation", app.accounts().any { it.accountId == PROTON_ID })
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

    /** What a query named: its URI, its selection's arguments, and whether it ran on the main thread. */
    data class Asked(val uri: Uri, val args: List<String>, val main: Boolean)

    /**
     * One live local raw contact (21) with [localRows], and one live row of the app's account (7) holding a Proton ID
     * and named [appName], each with no photo; the guards on either hold while [guardHolds]. Inserts are recorded in
     * order.
     */
    class Contacts : ContentProvider() {
        override fun onCreate() = true

        override fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, sortOrder: String?): Cursor {
            val given = args.orEmpty().toList()
            asked += Asked(uri, given, Looper.myLooper() == Looper.getMainLooper())
            // The import's guard: an assert with no projection, or its check after a refused batch.
            if (uri == RawContacts.CONTENT_URI && (projection == null || projection.contentEquals(arrayOf(RawContacts._ID)))) {
                return MatrixCursor(arrayOf(RawContacts._ID)).apply { if (guardHolds && given.firstOrNull() in setOf("21", "7")) addRow(arrayOf(given.first().toLong())) }
            }
            val columns = checkNotNull(projection)
            val rows: List<Map<String, Any?>> = when {
                uri == RawContacts.CONTENT_URI && LOCAL_TYPE in given -> listOf(rawContact(21, null, LOCAL_PERSON, contactId = 50))
                uri == RawContacts.CONTENT_URI -> listOf(rawContact(7, APP_PROTON_ID, appName, contactId = 60))
                // A default number or e-mail (queryLocal), and the photo row: none.
                selection.orEmpty().contains(Data.IS_SUPER_PRIMARY) || given.size > 1 -> emptyList()
                given == listOf("21") -> localRows
                else -> listOf(nameRow(appName))
            }
            return MatrixCursor(columns).apply { rows.forEach { row -> addRow(columns.map { row[it] }) } }
        }

        private fun rawContact(id: Long, sourceId: String?, name: String, contactId: Long) = mapOf(
            RawContacts._ID to id, RawContacts.SOURCE_ID to sourceId, RawContacts.DIRTY to 0, RawContacts.DELETED to 0, RawContacts.VERSION to 3L,
            RawContacts.DISPLAY_NAME_PRIMARY to name, RawContacts.STARRED to 0, RawContacts.CONTACT_ID to contactId,
            RawContacts.DISPLAY_NAME_SOURCE to ContactsContract.DisplayNameSources.STRUCTURED_NAME, RawContacts.SEND_TO_VOICEMAIL to 0,
        )

        override fun insert(uri: Uri, values: ContentValues?): Uri {
            inserted += uri to ContentValues(values)
            return ContentUris.withAppendedId(uri, 100L + inserted.size)
        }

        override fun getType(uri: Uri): String? = null
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<String>?) = 0
        override fun delete(uri: Uri, selection: String?, args: Array<String>?): Int {
            deleted += Asked(uri, args.orEmpty().toList(), Looper.myLooper() == Looper.getMainLooper())
            return 1
        }
    }

    private companion object {
        const val NAME = "someone.made.up@example.test"
        const val LOCAL_NAME = "made-up-local-name"
        const val LOCAL_TYPE = "made.up.local.type"

        /** Has letters outside hex, so it can't show up inside a pseudonym by chance. */
        const val PROTON_ID = "made-up-user"
        const val APP_PROTON_ID = "made-up-proton-id"
        const val LOCAL_PERSON = "Made Up Local"
        const val OTHER_PERSON = "Someone Else Made Up"

        val asked = mutableListOf<Asked>()
        val requests: MutableList<SyncRequest> = Collections.synchronizedList(mutableListOf())
        @Volatile var held: CompletableDeferred<Unit>? = null
        val inserted = mutableListOf<Pair<Uri, ContentValues>>()
        val deleted = mutableListOf<Asked>()
        var localRows = emptyList<Map<String, Any?>>()
        var appName = OTHER_PERSON
        var guardHolds = true

        fun nameRow(name: String): Map<String, Any?> = mapOf(Data.MIMETYPE to StructuredName.CONTENT_ITEM_TYPE, StructuredName.DISPLAY_NAME to name)
        fun emailRow(address: String): Map<String, Any?> = mapOf(Data.MIMETYPE to Email.CONTENT_ITEM_TYPE, Email.ADDRESS to address)
        fun phoneRow(number: String): Map<String, Any?> = mapOf(Data.MIMETYPE to Phone.CONTENT_ITEM_TYPE, Phone.NUMBER to number)
    }
}
