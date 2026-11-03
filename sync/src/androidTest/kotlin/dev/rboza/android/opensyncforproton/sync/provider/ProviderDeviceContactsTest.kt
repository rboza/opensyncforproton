package dev.rboza.android.opensyncforproton.sync.provider

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rboza.android.opensyncforproton.account.AccountId
import dev.rboza.android.opensyncforproton.mapping.AddressType
import dev.rboza.android.opensyncforproton.mapping.ContactGraph
import dev.rboza.android.opensyncforproton.mapping.EmailType
import dev.rboza.android.opensyncforproton.mapping.GraphKeys
import dev.rboza.android.opensyncforproton.mapping.KeyedGraph
import dev.rboza.android.opensyncforproton.mapping.LabeledDate
import dev.rboza.android.opensyncforproton.mapping.Organization
import dev.rboza.android.opensyncforproton.mapping.PartialDate
import dev.rboza.android.opensyncforproton.mapping.PhoneType
import dev.rboza.android.opensyncforproton.mapping.RelationType
import dev.rboza.android.opensyncforproton.mapping.SourceKey
import dev.rboza.android.opensyncforproton.mapping.StructuredName
import dev.rboza.android.opensyncforproton.mapping.TypedAddress
import dev.rboza.android.opensyncforproton.mapping.TypedEmail
import dev.rboza.android.opensyncforproton.mapping.TypedPhone
import dev.rboza.android.opensyncforproton.mapping.TypedRelation
import java.io.ByteArrayOutputStream
import kotlin.random.Random
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real Contacts provider on the API 37 emulator, under the `.debug` account type (D4) only, with made-up account
 * names and contacts. Every row of the test account types is deleted, as the sync adapter, before and after each test.
 */
@RunWith(AndroidJUnit4::class)
class ProviderDeviceContactsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val resolver = instrumentation.targetContext.contentResolver
    private val contacts = ProviderDeviceContacts(resolver, TYPE, localAccountName = LOCAL_NAME, localAccountType = LOCAL_TYPE)
    private val account = PhoneAccount(AccountId("test-only-user-id"), "test-only@example.test")
    private val otherAccount = PhoneAccount(AccountId("test-only-other-id"), "test-only-other@example.test")

    @Before
    fun setUp() {
        listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS).forEach {
            instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, it)
        }
        clean()
    }

    @After
    fun clean() {
        listOf(TYPE, OTHER_TYPE, LOCAL_TYPE).forEach { type ->
            resolver.delete(RawContacts.CONTENT_URI.asSyncAdapter(), "${RawContacts.ACCOUNT_TYPE}=?", arrayOf(type))
        }
    }

    private fun Uri.asSyncAdapter(): Uri = buildUpon().appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true").build()

    private val full = ContactGraph(
        uid = "uid-1",
        formattedName = "Ann Lee",
        name = StructuredName(given = "Ann", family = "Lee", phoneticGiven = "an"),
        emails = listOf(TypedEmail("ann@example.test", EmailType.Work), TypedEmail("a@example.test", EmailType.Custom, "School")),
        phones = listOf(TypedPhone("+1 555 0100", PhoneType.Mobile), TypedPhone("+1 555 0101", PhoneType.Main)),
        addresses = listOf(TypedAddress("1 Main St\nFlat 2", null, "Town", null, "12345", "Country", AddressType.Home)),
        organization = Organization("Acme", units = listOf("R&D"), title = "Engineer", role = "Lead"),
        birthday = PartialDate(5, 7, 1990),
        anniversary = PartialDate(6, 1, null),
        customDates = listOf(LabeledDate(PartialDate(7, 2, null), "Met")),
        note = "test-only note",
        urls = listOf("https://example.test/ann"),
        nickname = "Annie",
        relations = listOf(TypedRelation("Bob", RelationType.Spouse), TypedRelation("Sam", RelationType.Custom, "Coach")),
        sipAddresses = listOf("ann@sip.example.test"),
    )

    private fun photo(): ByteArray {
        val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        return (scalePhoto(png, maxDimension = 720) as ScaledPhoto.Jpeg).bytes
    }

    private fun insert(protonId: String, graph: ContactGraph = full, photo: ByteArray? = null, into: PhoneAccount = account): Long {
        val result = contacts.apply(into, listOf(ContactWrite(protonId, existing = null, rows = dataRows(graph, photo))))
        return (result.single() as ContactWriteResult.Written).rawContactId
    }

    private fun <T> Cursor?.rows(read: (Cursor) -> T): List<T> = checkNotNull(this).use { cursor ->
        buildList { while (cursor.moveToNext()) add(read(cursor)) }
    }

    /** The data rows stored for [rawContactId]: MIME type and every data column the provider holds. */
    private fun storedRows(rawContactId: Long): List<Map<String, Any?>> {
        val columns = listOf(Data.MIMETYPE) + (1..15).map { "data$it" }
        return resolver.query(Data.CONTENT_URI, columns.toTypedArray(), "${Data.RAW_CONTACT_ID}=?", arrayOf("$rawContactId"), null).rows { cursor ->
            columns.associateWith { column ->
                val index = cursor.getColumnIndexOrThrow(column)
                when (cursor.getType(index)) {
                    Cursor.FIELD_TYPE_NULL -> null
                    Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                    Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(index).toList()
                    else -> cursor.getString(index)
                }
            }
        }
    }

    private fun rawColumn(rawContactId: Long, column: String): String? =
        resolver.query(RawContacts.CONTENT_URI, arrayOf(column), "${RawContacts._ID}=?", arrayOf("$rawContactId"), null).rows { it.getString(0) }.single()

    /** An ordinary app (not the sync adapter) changing the contact's note, as the Contacts app would. */
    private fun editAsAnApp(rawContactId: Long) {
        val values = ContentValues().apply { put(Data.DATA1, "edited on the phone") }
        val changed = resolver.update(Data.CONTENT_URI, values, "${Data.RAW_CONTACT_ID}=? AND ${Data.MIMETYPE}=?", arrayOf("$rawContactId", NOTE))
        assertEquals(1, changed)
    }

    /** A row under this account's type and name but with a data set, which only another app would write. */
    private fun insertWithDataSet(protonId: String, accountName: String = account.name): Long {
        val values = ContentValues().apply {
            put(RawContacts.ACCOUNT_TYPE, TYPE)
            put(RawContacts.ACCOUNT_NAME, accountName)
            put(RawContacts.DATA_SET, "test-only-set")
            put(RawContacts.SOURCE_ID, protonId)
        }
        return ContentUris.parseId(checkNotNull(resolver.insert(RawContacts.CONTENT_URI.asSyncAdapter(), values)))
    }

    private fun manyRows(count: Int) = (1..count).map { DataRow(NOTE_TYPE_EMAIL, mapOf("data1" to ColumnValue.Text("n$it@example.test"), "data2" to ColumnValue.Integer(3))) }

    private fun current(rawContactId: Long): DeviceContact = contacts.query(account).single { it.rawContactId == rawContactId }

    @Test
    fun anInsertWritesEveryRowUnderTheAccountAndReadsBackEqual() {
        val rows = dataRows(full, photo())

        val result = contacts.apply(account, listOf(ContactWrite("proton-id-1", existing = null, rows = rows)))

        val id = (result.single() as ContactWriteResult.Written).rawContactId
        assertEquals(TYPE, rawColumn(id, RawContacts.ACCOUNT_TYPE))
        assertEquals(account.name, rawColumn(id, RawContacts.ACCOUNT_NAME))
        val stored = storedRows(id)
        assertEquals(rows.map { it.mimeType }.sorted(), stored.map { it[Data.MIMETYPE] as String }.sorted())
        // Every column written reads back as written (data columns hold text, so a type 2 reads back as "2"); the
        // provider may add its own (a normalized number, say). It keeps its own thumbnail of the photo, so only the
        // photo row's presence is compared.
        rows.filter { it.mimeType != PHOTO }.forEach { row ->
            val written = row.values.mapValues { (_, value) ->
                when (value) {
                    is ColumnValue.Text -> value.value
                    is ColumnValue.Integer -> value.value.toString()
                    is ColumnValue.Blob -> value.bytes.toList()
                }
            }
            assertTrue("${row.mimeType} ${written.keys}", stored.any { it[Data.MIMETYPE] == row.mimeType && written.all { (column, value) -> it[column] == value } })
        }
        assertNotNull(stored.single { it[Data.MIMETYPE] == PHOTO }["data15"])
    }

    @Test
    fun queryReturnsTheAccountsRowsWithTheirSyncColumns() {
        val id = insert("proton-id-1")

        val row = contacts.query(account).single()

        assertEquals(id, row.rawContactId)
        assertEquals("proton-id-1", row.sourceId)
        assertEquals(false, row.dirty)
        assertEquals(false, row.deleted)
        assertEquals(rawColumn(id, RawContacts.VERSION)?.toLong(), row.version)
        assertEquals("the name Android shows", "Ann Lee", row.displayName)
    }

    @Test
    fun queryReturnsOnlyThisAccountsRows() {
        val mine = insert("proton-id-1")
        insert("proton-id-2", into = otherAccount)
        val other = ProviderDeviceContacts(resolver, OTHER_TYPE, localAccountName = null, localAccountType = null).apply(account, listOf(ContactWrite("proton-id-3", null, dataRows(full, null))))
        assertTrue(other.single() is ContactWriteResult.Written)
        insertWithDataSet("proton-id-4")

        assertEquals(listOf(mine), contacts.query(account).map { it.rawContactId })
    }

    @Test
    fun queryShowsRowsEditedOrDeletedOnThePhone() {
        val edited = insert("proton-id-1")
        val deleted = insert("proton-id-2")
        val before = current(edited).version
        editAsAnApp(edited)
        assertEquals(1, resolver.delete(RawContacts.CONTENT_URI, "${RawContacts._ID}=?", arrayOf("$deleted")))

        val rows = contacts.query(account).associateBy { it.rawContactId }

        assertEquals(true, rows.getValue(edited).dirty)
        assertTrue("an edit bumps VERSION", rows.getValue(edited).version > before)
        assertEquals(true, rows.getValue(deleted).deleted)
        assertEquals("a phone delete stays as a tombstone", "proton-id-2", rows.getValue(deleted).sourceId)
    }

    @Test
    fun aRowMadeOnThePhoneHasNoSourceId() {
        val values = ContentValues().apply {
            put(RawContacts.ACCOUNT_TYPE, TYPE)
            put(RawContacts.ACCOUNT_NAME, account.name)
        }
        resolver.insert(RawContacts.CONTENT_URI, values)

        // "New on the phone" is told by the missing SOURCE_ID alone: a bare raw contact inserted by an app was seen
        // with DIRTY 0 on API 37.
        assertEquals(null, contacts.query(account).single().sourceId)
    }

    @Test
    fun anUpdateReplacesTheDataRowsAndKeepsTheStar() {
        val id = insert("proton-id-1")
        resolver.update(RawContacts.CONTENT_URI.asSyncAdapter(), ContentValues().apply { put(RawContacts.STARRED, 1) }, "${RawContacts._ID}=?", arrayOf("$id"))
        val changed = ContactGraph(uid = "uid-1", formattedName = "Ann Smith", emails = listOf(TypedEmail("new@example.test", EmailType.Home)))

        val result = contacts.apply(account, listOf(ContactWrite("proton-id-1", ExistingRow(id, current(id).version), dataRows(changed, null))))

        assertEquals(ContactWriteResult.Written(id), result.single())
        assertEquals(listOf(NAME, EMAIL), storedRows(id).map { it[Data.MIMETYPE] }.sortedBy { listOf(NAME, EMAIL).indexOf(it) })
        assertEquals("new@example.test", storedRows(id).single { it[Data.MIMETYPE] == EMAIL }["data1"])
        assertEquals("1", rawColumn(id, RawContacts.STARRED))
        assertEquals("a sync-adapter write leaves the row clean", false, current(id).dirty)
    }

    @Test
    fun anOutdatedVersionWritesNothing() {
        val id = insert("proton-id-1")
        val stale = current(id).version
        // Another sync-adapter edit bumps VERSION and leaves DIRTY 0, so only the VERSION check can stop the write.
        val values = ContentValues().apply { put(Data.DATA1, "changed meanwhile") }
        resolver.update(Data.CONTENT_URI.asSyncAdapter(), values, "${Data.RAW_CONTACT_ID}=? AND ${Data.MIMETYPE}=?", arrayOf("$id", NOTE))
        assertTrue(current(id).version > stale)
        assertEquals(false, current(id).dirty)
        val before = storedRows(id)

        val result = contacts.apply(account, listOf(ContactWrite("proton-id-1", ExistingRow(id, stale), dataRows(ContactGraph("uid-1", "Other"), null))))

        assertEquals(ContactWriteResult.Stale, result.single())
        assertEquals(before, storedRows(id))
    }

    @Test
    fun aRowEditedOnThePhoneIsNeverOverwritten() {
        val id = insert("proton-id-1")
        editAsAnApp(id)
        val now = current(id)
        val before = storedRows(id)

        val result = contacts.apply(account, listOf(ContactWrite("proton-id-1", ExistingRow(id, now.version), dataRows(ContactGraph("uid-1", "Other"), null))))

        assertEquals("dirty, even at its current VERSION", ContactWriteResult.Stale, result.single())
        assertEquals(before, storedRows(id))
    }

    @Test
    fun clearingDirtyKeepsEveryDataRowOfAnEditedRow() {
        // A settled edit only has DIRTY cleared, so a row of another kind and a per-row choice (the Contacts app's
        // default) stay.
        val id = insert("proton-id-1")
        editAsAnApp(id)
        resolver.insert(Data.CONTENT_URI, ContentValues().apply {
            put(Data.RAW_CONTACT_ID, id)
            put(Data.MIMETYPE, IM)
            put(Data.DATA1, "ann")
        })
        fun defaults() = resolver.query(Data.CONTENT_URI, arrayOf(Data._ID, Data.IS_SUPER_PRIMARY), "${Data.RAW_CONTACT_ID}=? AND ${Data.MIMETYPE}=?", arrayOf("$id", EMAIL), Data._ID)
            .rows { it.getLong(0) to it.getInt(1) }
        val email = defaults().first().first
        resolver.update(Data.CONTENT_URI, ContentValues().apply { put(Data.IS_SUPER_PRIMARY, 1) }, "${Data._ID}=?", arrayOf("$email"))
        val chosen = defaults()
        assertEquals(1, chosen.first().second)
        val now = current(id)
        assertEquals(true, now.dirty)
        val before = storedRows(id)

        val result = contacts.clearDirty(account, "proton-id-1", ExistingRow(id, now.version))

        assertEquals(ContactWriteResult.Written(id), result)
        assertEquals(before, storedRows(id))
        assertEquals("the default e-mail stays", chosen, defaults())
        assertEquals(false, current(id).dirty)
        assertTrue("as the app's write left it", contacts.untouched(id))
    }

    @Test
    fun clearingDirtyAtAnOutdatedVersionChangesNothing() {
        val id = insert("proton-id-1")
        editAsAnApp(id)
        val stale = current(id).version
        val values = ContentValues().apply { put(Data.DATA1, "edited again") }
        resolver.update(Data.CONTENT_URI, values, "${Data.RAW_CONTACT_ID}=? AND ${Data.MIMETYPE}=?", arrayOf("$id", NOTE))
        assertTrue(current(id).version > stale)

        val result = contacts.clearDirty(account, "proton-id-1", ExistingRow(id, stale))

        assertEquals(ContactWriteResult.Stale, result)
        assertEquals("still waiting to upload", true, current(id).dirty)
    }

    @Test
    fun clearingDirtyNeverTouchesATombstoneOrAnotherContact() {
        val id = insert("proton-id-1")
        resolver.delete(RawContacts.CONTENT_URI, "${RawContacts._ID}=?", arrayOf("$id"))
        val other = insert("proton-id-2")
        editAsAnApp(other)

        val tombstone = contacts.clearDirty(account, "proton-id-1", ExistingRow(id, current(id).version))
        val wrongId = contacts.clearDirty(account, "proton-id-1", ExistingRow(other, current(other).version))

        assertEquals(ContactWriteResult.Stale, tombstone)
        assertEquals(true to true, current(id).let { it.deleted to it.dirty })
        assertEquals("the row holds another Proton ID", ContactWriteResult.Stale, wrongId)
        assertEquals(true, current(other).dirty)
    }

    @Test
    fun aSettledWriteReplacesAnEditedRowAndClearsDirty() {
        // A settled edit whose remote moved is written from it as the sync adapter, guarded by VERSION, DIRTY cleared
        // in the same batch.
        val id = insert("proton-id-1")
        editAsAnApp(id)
        val now = current(id)
        assertEquals(true, now.dirty)
        val settled = ContactGraph(uid = "uid-1", formattedName = "Ann Smith", emails = listOf(TypedEmail("new@example.test", EmailType.Home)))

        val result = contacts.apply(account, listOf(ContactWrite("proton-id-1", ExistingRow(id, now.version, clearDirty = true), dataRows(settled, null))))

        assertEquals(ContactWriteResult.Written(id), result.single())
        assertEquals(listOf(NAME, EMAIL), storedRows(id).map { it[Data.MIMETYPE] }.sortedBy { listOf(NAME, EMAIL).indexOf(it) })
        assertEquals(false, current(id).dirty)
        assertTrue("as the app's write left it", contacts.untouched(id))
    }

    @Test
    fun aSettledWriteAtAnOutdatedVersionWritesNothing() {
        val id = insert("proton-id-1")
        editAsAnApp(id)
        val stale = current(id).version
        val values = ContentValues().apply { put(Data.DATA1, "edited again") }
        resolver.update(Data.CONTENT_URI, values, "${Data.RAW_CONTACT_ID}=? AND ${Data.MIMETYPE}=?", arrayOf("$id", NOTE))
        assertTrue(current(id).version > stale)
        val before = storedRows(id)

        val result = contacts.apply(account, listOf(ContactWrite("proton-id-1", ExistingRow(id, stale, clearDirty = true), dataRows(ContactGraph("uid-1", "Other"), null))))

        assertEquals(ContactWriteResult.Stale, result.single())
        assertEquals(before, storedRows(id))
        assertEquals("still waiting to upload", true, current(id).dirty)
    }

    @Test
    fun aSettledWriteNeverBringsBackATombstone() {
        val id = insert("proton-id-1")
        resolver.delete(RawContacts.CONTENT_URI, "${RawContacts._ID}=?", arrayOf("$id"))
        val now = current(id)

        val result = contacts.apply(account, listOf(ContactWrite("proton-id-1", ExistingRow(id, now.version, clearDirty = true), dataRows(full, null))))

        assertEquals(ContactWriteResult.Stale, result.single())
        assertEquals(true to true, current(id).let { it.deleted to it.dirty })
    }

    /** A contact made on the phone, as the Contacts app makes one (not the sync adapter): no SOURCE_ID, dirty. */
    private fun insertAsAnApp(name: String, into: PhoneAccount = account): Long {
        val raw = resolver.insert(RawContacts.CONTENT_URI, ContentValues().apply {
            put(RawContacts.ACCOUNT_TYPE, TYPE)
            put(RawContacts.ACCOUNT_NAME, into.name)
        })
        val id = ContentUris.parseId(checkNotNull(raw))
        resolver.insert(Data.CONTENT_URI, ContentValues().apply {
            put(Data.RAW_CONTACT_ID, id)
            put(Data.MIMETYPE, NAME)
            put(Data.DATA1, name)
        })
        assertEquals(true, contacts.query(into).single { it.rawContactId == id }.dirty)
        return id
    }

    // Binding a created contact to its row.

    @Test
    fun aBindingWriteNamesTheRowReplacesItsDataAndClearsDirty() {
        val id = insertAsAnApp("Ann")
        val now = current(id)
        val created = ContactGraph(uid = "uid-1", formattedName = "Ann", emails = listOf(TypedEmail("ann@example.test", EmailType.Home)))

        val result = contacts.apply(account, listOf(ContactWrite("proton-id-1", ExistingRow(id, now.version, clearDirty = true, bind = true), dataRows(created, null))))

        assertEquals(ContactWriteResult.Written(id), result.single())
        assertEquals("proton-id-1", current(id).sourceId)
        assertEquals(listOf(NAME, EMAIL), storedRows(id).map { it[Data.MIMETYPE] }.sortedBy { listOf(NAME, EMAIL).indexOf(it) })
        assertTrue("as the app's write left it", contacts.untouched(id))
    }

    @Test
    fun aBindingWriteAtAnOutdatedVersionOrOnATombstoneWritesNothing() {
        val edited = insertAsAnApp("Ann")
        val stale = current(edited).version
        editAsAnAppByName(edited)
        val deleted = insertAsAnApp("Bob")
        resolver.delete(RawContacts.CONTENT_URI, "${RawContacts._ID}=?", arrayOf("$deleted"))
        val before = storedRows(edited)

        val results = contacts.apply(
            account,
            listOf(
                ContactWrite("proton-id-1", ExistingRow(edited, stale, clearDirty = true, bind = true), dataRows(full, null)),
                ContactWrite("proton-id-2", ExistingRow(deleted, current(deleted).version, clearDirty = true, bind = true), dataRows(full, null)),
            ),
        )

        assertEquals(listOf(ContactWriteResult.Stale, ContactWriteResult.Stale), results)
        assertEquals(before, storedRows(edited))
        assertEquals(null to true, current(edited).let { it.sourceId to it.dirty })
        assertEquals(null, current(deleted).sourceId)
    }

    @Test
    fun aBindingWriteNeverTakesARowThatHoldsAProtonId() {
        val id = insert("proton-id-1")

        val result = contacts.apply(account, listOf(ContactWrite("proton-id-2", ExistingRow(id, current(id).version, clearDirty = true, bind = true), dataRows(full, null))))

        assertEquals(ContactWriteResult.Stale, result.single())
        assertEquals("proton-id-1", current(id).sourceId)
    }

    @Test
    fun bindingAloneNamesAnEditedRowAtAnyVersionAndChangesNothingElse() {
        val id = insertAsAnApp("Ann")
        editAsAnAppByName(id)
        val before = storedRows(id)

        val result = contacts.bind(account, "proton-id-1", id)

        assertEquals(ContactWriteResult.Written(id), result)
        assertEquals("proton-id-1", current(id).sourceId)
        assertEquals(before, storedRows(id))
        assertEquals("its edit still uploads", true, current(id).dirty)
    }

    @Test
    fun bindingAloneNamesATombstoneWhichStaysDeleted() {
        val id = insertAsAnApp("Ann")
        resolver.delete(RawContacts.CONTENT_URI, "${RawContacts._ID}=?", arrayOf("$id"))

        val result = contacts.bind(account, "proton-id-1", id)

        assertEquals(ContactWriteResult.Written(id), result)
        assertEquals(Triple("proton-id-1", true, true), current(id).let { Triple(it.sourceId, it.deleted, it.dirty) })
    }

    @Test
    fun bindingAloneNeverTakesARowThatHoldsAProtonIdOrIsAnotherAccountsOrGone() {
        val held = insert("proton-id-1")
        val other = insertAsAnApp("Bob", into = otherAccount)
        val gone = insertAsAnApp("Cy")
        resolver.delete(RawContacts.CONTENT_URI.asSyncAdapter(), "${RawContacts._ID}=?", arrayOf("$gone"))

        assertEquals(ContactWriteResult.Stale, contacts.bind(account, "proton-id-2", held))
        assertEquals(ContactWriteResult.Stale, contacts.bind(account, "proton-id-2", other))
        assertEquals(ContactWriteResult.Stale, contacts.bind(account, "proton-id-2", gone))
        assertEquals("proton-id-1", current(held).sourceId)
        assertEquals(null, contacts.query(otherAccount).single().sourceId)
    }

    @Test
    fun aBlankSourceIdCountsAsNoneForBinding() {
        // Only another app could write one; a blank SOURCE_ID counts as none.
        val blank = insertAsAnApp("Ann")
        val spaces = insertAsAnApp("Bob")
        val tab = insertAsAnApp("Cy")
        listOf(blank to "", spaces to "  ", tab to "\t\n").forEach { (id, value) ->
            assertEquals(1, resolver.update(RawContacts.CONTENT_URI, ContentValues().apply { put(RawContacts.SOURCE_ID, value) }, "${RawContacts._ID}=?", arrayOf("$id")))
        }

        val written = contacts.apply(account, listOf(ContactWrite("proton-id-1", ExistingRow(blank, current(blank).version, clearDirty = true, bind = true), dataRows(full, null))))

        assertEquals(ContactWriteResult.Written(blank), written.single())
        assertEquals("proton-id-1", current(blank).sourceId)
        assertEquals(ContactWriteResult.Written(spaces), contacts.bind(account, "proton-id-2", spaces))
        assertEquals("proton-id-2", current(spaces).sourceId)
        assertEquals("blank as the planner reads it", ContactWriteResult.Written(tab), contacts.bind(account, "proton-id-3", tab))
    }

    /** An ordinary app changing the name of a row [insertAsAnApp] made. */
    private fun editAsAnAppByName(rawContactId: Long) {
        val values = ContentValues().apply { put(Data.DATA1, "Edited on the phone") }
        assertEquals(1, resolver.update(Data.CONTENT_URI, values, "${Data.RAW_CONTACT_ID}=? AND ${Data.MIMETYPE}=?", arrayOf("$rawContactId", NAME)))
    }

    @Test
    fun aRowDeletedOnThePhoneIsNeverBroughtBack() {
        val id = insert("proton-id-1")
        resolver.delete(RawContacts.CONTENT_URI, "${RawContacts._ID}=?", arrayOf("$id"))
        val now = current(id)

        val result = contacts.apply(account, listOf(ContactWrite("proton-id-1", ExistingRow(id, now.version), dataRows(full, null))))

        assertEquals(ContactWriteResult.Stale, result.single())
        assertEquals(true, current(id).deleted)
    }

    @Test
    fun aCleanTombstoneIsNeverBroughtBackEither() {
        // A tombstone whose DIRTY a sync adapter cleared (any app may claim to be one) is still a phone delete.
        val id = insert("proton-id-1")
        resolver.delete(RawContacts.CONTENT_URI, "${RawContacts._ID}=?", arrayOf("$id"))
        resolver.update(RawContacts.CONTENT_URI.asSyncAdapter(), ContentValues().apply { put(RawContacts.DIRTY, 0) }, "${RawContacts._ID}=?", arrayOf("$id"))
        val now = current(id)
        assertEquals(true to false, now.deleted to now.dirty)

        val result = contacts.apply(account, listOf(ContactWrite("proton-id-1", ExistingRow(id, now.version), dataRows(full, null))))

        assertEquals(ContactWriteResult.Stale, result.single())
        assertEquals(true, current(id).deleted)
    }

    @Test
    fun aRowThatIsGoneIsStale() {
        val id = insert("proton-id-1")
        val version = current(id).version
        clean()

        val result = contacts.apply(account, listOf(ContactWrite("proton-id-1", ExistingRow(id, version), dataRows(full, null))))

        assertEquals(ContactWriteResult.Stale, result.single())
        assertEquals(emptyList<DeviceContact>(), contacts.query(account))
    }

    @Test
    fun anUpdateOnlyTouchesItsOwnAccountAndContact() {
        val theirs = insert("proton-id-2", into = otherAccount)
        val theirVersion = contacts.query(otherAccount).single().version
        val mine = insert("proton-id-1")
        val before = storedRows(theirs) to storedRows(mine)

        val results = contacts.apply(
            account,
            listOf(
                ContactWrite("proton-id-2", ExistingRow(theirs, theirVersion), dataRows(ContactGraph("uid-1", "Other"), null)),
                ContactWrite("proton-id-9", ExistingRow(mine, current(mine).version), dataRows(ContactGraph("uid-1", "Other"), null)),
            ),
        )

        assertEquals("another account's row, and a row holding another Proton ID", listOf(ContactWriteResult.Stale, ContactWriteResult.Stale), results)
        assertEquals(before, storedRows(theirs) to storedRows(mine))
    }

    @Test
    fun eachContactIsItsOwnBatchAndResultsMatchTheWrites() {
        val stale = insert("proton-id-2")
        val staleVersion = current(stale).version
        editAsAnApp(stale)

        val results = contacts.apply(
            account,
            listOf(
                ContactWrite("proton-id-1", null, dataRows(full, null)),
                ContactWrite("proton-id-2", ExistingRow(stale, staleVersion), dataRows(full, null)),
                ContactWrite("proton-id-3", null, dataRows(full, null)),
            ),
        )

        assertTrue(results[0] is ContactWriteResult.Written)
        assertEquals(ContactWriteResult.Stale, results[1])
        assertTrue(results[2] is ContactWriteResult.Written)
        assertEquals(setOf("proton-id-1", "proton-id-2", "proton-id-3"), contacts.query(account).map { it.sourceId }.toSet())
    }

    @Test
    fun anInsertOfAProtonIdAlreadyOnThePhoneWritesNothing() {
        insert("proton-id-1")
        val tombstone = insert("proton-id-2")
        resolver.delete(RawContacts.CONTENT_URI, "${RawContacts._ID}=?", arrayOf("$tombstone"))

        val results = contacts.apply(
            account,
            listOf(ContactWrite("proton-id-1", null, dataRows(full, null)), ContactWrite("proton-id-2", null, dataRows(full, null))),
        )

        assertEquals("a live row or a tombstone already holds it", listOf(ContactWriteResult.Stale, ContactWriteResult.Stale), results)
        assertEquals(2, contacts.query(account).size)
    }

    @Test
    fun aRowWithADataSetIsNotTheAccounts() {
        val foreign = insertWithDataSet("proton-id-1")

        assertEquals(emptyList<DeviceContact>(), contacts.query(account))
        val replaced = contacts.apply(account, listOf(ContactWrite("proton-id-1", ExistingRow(foreign, rawColumn(foreign, RawContacts.VERSION)!!.toLong()), dataRows(full, null))))
        assertEquals(ContactWriteResult.Stale, replaced.single())
        assertTrue("it doesn't block the account's own insert", contacts.apply(account, listOf(ContactWrite("proton-id-1", null, dataRows(full, null)))).single() is ContactWriteResult.Written)
    }

    @Test
    fun aReplacementLeavesTheAccountsOtherContactsAlone() {
        val other = insert("proton-id-2")
        val id = insert("proton-id-1")
        val before = storedRows(other)

        val result = contacts.apply(account, listOf(ContactWrite("proton-id-1", ExistingRow(id, current(id).version), dataRows(ContactGraph("uid-1", "Other"), null))))

        assertEquals(ContactWriteResult.Written(id), result.single())
        assertEquals(before, storedRows(other))
    }

    @Test
    fun tooManyRowsForOneBatchFailsAndWritesNothing() {
        val id = insert("proton-id-1")
        val before = storedRows(id)

        val results = contacts.apply(
            account,
            listOf(
                ContactWrite("proton-id-2", null, manyRows(600)),
                ContactWrite("proton-id-1", ExistingRow(id, current(id).version), manyRows(600)),
            ),
        )

        // Counted before sending: 500 operations or more, the guard included, never reach the provider.
        val tooLarge = ContactWriteResult.Failed(FailureReason.TOO_LARGE)
        assertEquals("refused before sending; the rows didn't move on", listOf(tooLarge, tooLarge), results)
        assertEquals(listOf("proton-id-1"), contacts.query(account).map { it.sourceId })
        assertEquals(before, storedRows(id))
    }

    @Test
    fun theOperationLimitIsCountedWithTheGuard() {
        // Guard, raw contact, and data rows: 497 rows make 499 operations, 498 make 500.
        val results = contacts.apply(account, listOf(ContactWrite("proton-id-1", null, manyRows(497)), ContactWrite("proton-id-2", null, manyRows(498))))

        assertTrue(results[0] is ContactWriteResult.Written)
        assertEquals(ContactWriteResult.Failed(FailureReason.TOO_LARGE), results[1])
    }

    @Test
    fun theOperationLimitOfAReplacementIsCountedWithTheGuard() {
        // Guard, the data rows' delete, and data rows: 497 rows make 499 operations, 498 make 500.
        val id = insert("proton-id-1")

        val written = contacts.apply(account, listOf(ContactWrite("proton-id-1", ExistingRow(id, current(id).version), manyRows(497))))
        val before = storedRows(id)
        val refused = contacts.apply(account, listOf(ContactWrite("proton-id-1", ExistingRow(id, current(id).version), manyRows(498))))

        assertEquals(ContactWriteResult.Written(id), written.single())
        assertEquals(ContactWriteResult.Failed(FailureReason.TOO_LARGE), refused.single())
        assertEquals(before, storedRows(id))
    }

    @Test
    fun theOperationLimitOfASettledWriteCountsClearingDirty() {
        // Guard, the data rows' delete, data rows, and clearing DIRTY: 496 rows make 499 operations, 497 make 500.
        val id = insert("proton-id-1")
        editAsAnApp(id)

        val refused = contacts.apply(account, listOf(ContactWrite("proton-id-1", ExistingRow(id, current(id).version, clearDirty = true), manyRows(497))))
        val written = contacts.apply(account, listOf(ContactWrite("proton-id-1", ExistingRow(id, current(id).version, clearDirty = true), manyRows(496))))

        assertEquals(ContactWriteResult.Failed(FailureReason.TOO_LARGE), refused.single())
        assertEquals(ContactWriteResult.Written(id), written.single())
        assertEquals(false, current(id).dirty)
    }

    @Test
    fun anOperationTheProviderRefusesWhileTheGuardHoldsIsRefused() {
        // As Contact Scopes refuses every write; here an unknown column gives the same OperationApplicationException.
        val odd = DataRow(NOTE, mapOf("not_a_column" to ColumnValue.Text("x")))

        val result = contacts.apply(account, listOf(ContactWrite("proton-id-1", null, listOf(odd))))

        assertEquals(ContactWriteResult.Failed(FailureReason.OPERATION_REFUSED), result.single())
        assertEquals(emptyList<DeviceContact>(), contacts.query(account))
    }

    @Test
    fun aValueTheProviderRejectsFailsThatContactOnly() {
        val bad = DataRow("", mapOf("data1" to ColumnValue.Text("x")))

        val results = contacts.apply(account, listOf(ContactWrite("proton-id-1", null, listOf(bad)), ContactWrite("proton-id-2", null, dataRows(full, null))))

        assertEquals(ContactWriteResult.Failed(FailureReason.REJECTED_VALUE), results[0])
        assertTrue(results[1] is ContactWriteResult.Written)
        assertEquals(listOf("proton-id-2"), contacts.query(account).map { it.sourceId })
    }

    @Test
    fun aContactTooLargeForOneBatchFailsAndWritesNothing() {
        val huge = DataRow(PHOTO, mapOf("data15" to ColumnValue.Blob(Random(1).nextBytes(2 * 1024 * 1024))))

        val result = contacts.apply(account, listOf(ContactWrite("proton-id-1", null, dataRows(full, null) + huge)))

        assertEquals(ContactWriteResult.Failed(FailureReason.TOO_LARGE), result.single())
        assertEquals(emptyList<DeviceContact>(), contacts.query(account))
    }

    @Test
    fun aScaledLargePhotoFitsTheBatch() {
        val random = Random(4)
        val noise = Bitmap.createBitmap(IntArray(2000 * 2000) { random.nextInt() or Color.BLACK }, 2000, 2000, Bitmap.Config.ARGB_8888)
        val png = ByteArrayOutputStream().also { noise.compress(Bitmap.CompressFormat.JPEG, 100, it) }.toByteArray()
        val scaled = scalePhoto(png, maxDimension = 720) as ScaledPhoto.Jpeg

        val id = insert("proton-id-1", photo = scaled.bytes)

        assertNotNull(storedRows(id).single { it[Data.MIMETYPE] == PHOTO }["data15"])
    }

    @Test
    fun aContactWithoutANameShowsItsEmail() {
        val id = insert("proton-id-1", ContactGraph(uid = "uid-1", formattedName = null, emails = listOf(TypedEmail("only@example.test", EmailType.Home))))
        val contactId = rawColumn(id, RawContacts.CONTACT_ID)

        val shown = resolver.query(ContactsContract.Contacts.CONTENT_URI, arrayOf(ContactsContract.Contacts.DISPLAY_NAME), "${ContactsContract.Contacts._ID}=?", arrayOf(contactId), null)
            .rows { it.getString(0) }.single()

        assertEquals("only@example.test", shown)
    }

    @Test
    fun theProvidersMaximumPhotoSizeIsRead() {
        // The provider re-encodes display photos to at most 720 px on API 37.
        assertEquals(720, contacts.maxPhotoDimension())
    }

    @Test
    fun aContactWithOnlyAPhoneShowsItsNumber() {
        val id = insert("proton-id-1", ContactGraph(uid = "uid-1", formattedName = null, phones = listOf(TypedPhone("+1 555 0199", PhoneType.Mobile))))
        val contactId = rawColumn(id, RawContacts.CONTACT_ID)

        val shown = resolver.query(ContactsContract.Contacts.CONTENT_URI, arrayOf(ContactsContract.Contacts.DISPLAY_NAME), "${ContactsContract.Contacts._ID}=?", arrayOf(contactId), null)
            .rows { it.getString(0) }.single()

        assertEquals("+1 555 0199", shown)
    }

    @Test
    fun aPhotoBecomesTheContactsDisplayPhoto() {
        val id = insert("proton-id-1", ContactGraph("uid-1", "Ann"), photo = photo())

        // The provider keeps the display photo as a file and its own thumbnail in data15.
        val fileId = resolver.query(Data.CONTENT_URI, arrayOf(ContactsContract.CommonDataKinds.Photo.PHOTO_FILE_ID), "${Data.RAW_CONTACT_ID}=? AND ${Data.MIMETYPE}=?", arrayOf("$id", PHOTO), null)
            .rows { it.getLong(0) }.single()
        assertTrue(fileId > 0)
    }

    /** Every raw contact (deleted ones too) under [type] and [accountName], data sets included. */
    private fun allRawContacts(accountName: String, type: String = TYPE): List<Long> = resolver.query(
        RawContacts.CONTENT_URI.asSyncAdapter(), arrayOf(RawContacts._ID), "${RawContacts.ACCOUNT_TYPE}=? AND ${RawContacts.ACCOUNT_NAME}=?", arrayOf(type, accountName), null,
    ).rows { it.getLong(0) }

    private fun dataRowCount(rawContactIds: List<Long>): Int = rawContactIds.sumOf { id ->
        resolver.query(Data.CONTENT_URI, arrayOf(Data._ID), "${Data.RAW_CONTACT_ID}=?", arrayOf("$id"), null).rows { it.getLong(0) }.size
    }

    /** A phone deletion, as the Contacts app makes one: a tombstone stays until the sync adapter removes it. */
    private fun deleteAsAnApp(rawContactId: Long) {
        assertEquals(1, resolver.delete(RawContacts.CONTENT_URI, "${RawContacts._ID}=?", arrayOf("$rawContactId")))
    }

    @Test
    fun removingATombstoneLeavesNoRowAndNothingElseChanges() {
        // Once Proton's contact is gone, the phone deletion is finished.
        val id = insert("proton-id-1")
        val kept = insert("proton-id-2")
        deleteAsAnApp(id)

        assertEquals(ContactWriteResult.Written(id), contacts.removeTombstone(account, "proton-id-1", id))

        assertEquals("not even a tombstone is left", listOf(kept), allRawContacts(account.name))
        assertEquals(0, dataRowCount(listOf(id)))
        assertEquals(false, current(kept).deleted)
    }

    @Test
    fun removingATombstoneNeverTouchesALiveRowAnotherProtonIdAnotherAccountsRowOrAGoneOne() {
        val live = insert("proton-id-1")
        val otherId = insert("proton-id-2").also { deleteAsAnApp(it) }
        val other = insert("proton-id-3", into = otherAccount).also { deleteAsAnApp(it) }
        val dataSet = insertWithDataSet("proton-id-4").also { deleteAsAnApp(it) }
        val gone = insert("proton-id-5")
        resolver.delete(RawContacts.CONTENT_URI.asSyncAdapter(), "${RawContacts._ID}=?", arrayOf("$gone"))

        assertEquals(ContactWriteResult.Stale, contacts.removeTombstone(account, "proton-id-1", live))
        assertEquals(ContactWriteResult.Stale, contacts.removeTombstone(account, "proton-id-9", otherId))
        assertEquals(ContactWriteResult.Stale, contacts.removeTombstone(account, "proton-id-3", other))
        assertEquals(ContactWriteResult.Stale, contacts.removeTombstone(account, "proton-id-4", dataSet))
        assertEquals(ContactWriteResult.Stale, contacts.removeTombstone(account, "proton-id-5", gone))

        assertEquals(false, current(live).deleted)
        assertEquals(true, current(otherId).deleted)
        assertEquals(listOf(other), allRawContacts(otherAccount.name))
        assertTrue("a data set's row stays", dataSet in allRawContacts(account.name))
    }

    @Test
    fun removingATombstoneNamesItsRowAndProtonIdTogether() {
        // The account's tombstone holding the Proton ID is another row: neither is removed.
        val holder = insert("proton-id-1").also { deleteAsAnApp(it) }
        val named = insert("proton-id-2").also { deleteAsAnApp(it) }

        assertEquals(ContactWriteResult.Stale, contacts.removeTombstone(account, "proton-id-1", named))

        assertEquals(listOf(holder, named), allRawContacts(account.name).sorted())
    }

    private fun version(rawContactId: Long) = checkNotNull(rawColumn(rawContactId, RawContacts.VERSION)).toLong()

    @Test
    fun removingARowDeletedOnProtonLeavesNoRowAndNothingElseChanges() {
        // A clean row whose contact a complete inventory left out.
        val id = insert("proton-id-1")
        val kept = insert("proton-id-2")

        assertEquals(ContactWriteResult.Written(id), contacts.removeRow(account, "proton-id-1", ExistingRow(id, version(id))))

        assertEquals("not even a tombstone is left", listOf(kept), allRawContacts(account.name))
        assertEquals(0, dataRowCount(listOf(id)))
        assertEquals(false, current(kept).deleted)
    }

    @Test
    fun removingARowDeletedOnProtonNeverTouchesAChangedRowAnotherProtonIdAnotherAccountsRowOrAGoneOne() {
        // Each is read at its VERSION after the change, so only the change itself stops it.
        val dirty = insert("proton-id-1").also { editAsAnApp(it) }
        val tombstone = insert("proton-id-2").also { deleteAsAnApp(it) }
        val rewritten = insert("proton-id-3")
        val before = version(rewritten)
        contacts.apply(account, listOf(ContactWrite("proton-id-3", ExistingRow(rewritten, before), dataRows(full, null))))
        val otherId = insert("proton-id-4")
        val other = insert("proton-id-5", into = otherAccount)
        val dataSet = insertWithDataSet("proton-id-6")
        val gone = insert("proton-id-7")
        val goneVersion = version(gone)
        resolver.delete(RawContacts.CONTENT_URI.asSyncAdapter(), "${RawContacts._ID}=?", arrayOf("$gone"))

        assertEquals(ContactWriteResult.Stale, contacts.removeRow(account, "proton-id-1", ExistingRow(dirty, version(dirty))))
        assertEquals(ContactWriteResult.Stale, contacts.removeRow(account, "proton-id-2", ExistingRow(tombstone, version(tombstone))))
        assertEquals(ContactWriteResult.Stale, contacts.removeRow(account, "proton-id-3", ExistingRow(rewritten, before)))
        assertEquals(ContactWriteResult.Stale, contacts.removeRow(account, "proton-id-9", ExistingRow(otherId, version(otherId))))
        assertEquals(ContactWriteResult.Stale, contacts.removeRow(account, "proton-id-5", ExistingRow(other, version(other))))
        assertEquals(ContactWriteResult.Stale, contacts.removeRow(account, "proton-id-6", ExistingRow(dataSet, version(dataSet))))
        assertEquals(ContactWriteResult.Stale, contacts.removeRow(account, "proton-id-7", ExistingRow(gone, goneVersion)))

        assertEquals(listOf(dirty, tombstone, rewritten, otherId, dataSet).sorted(), allRawContacts(account.name).sorted())
        assertEquals(true, current(dirty).dirty)
        assertEquals(true, current(tombstone).deleted)
        assertEquals(listOf(other), allRawContacts(otherAccount.name))
    }

    @Test
    fun removingARowTheUserChoseToDeleteHereTakesItDirtyOrCleanAtItsVersionOnly() {
        // Use Proton on a phone edit vs a Proton deletion.
        val dirty = insert("proton-id-1").also { editAsAnApp(it) }
        val clean = insert("proton-id-2")
        val tombstone = insert("proton-id-3").also { deleteAsAnApp(it) }
        val edited = insert("proton-id-4")
        val before = version(edited)
        editAsAnApp(edited)
        val otherId = insert("proton-id-5").also { editAsAnApp(it) }
        val other = insert("proton-id-6", into = otherAccount).also { editAsAnApp(it) }
        val kept = insert("proton-id-7")

        assertEquals(ContactWriteResult.Written(dirty), contacts.removeRow(account, "proton-id-1", ExistingRow(dirty, version(dirty), clearDirty = true)))
        assertEquals(ContactWriteResult.Written(clean), contacts.removeRow(account, "proton-id-2", ExistingRow(clean, version(clean), clearDirty = true)))
        assertEquals(ContactWriteResult.Stale, contacts.removeRow(account, "proton-id-3", ExistingRow(tombstone, version(tombstone), clearDirty = true)))
        assertEquals(ContactWriteResult.Stale, contacts.removeRow(account, "proton-id-4", ExistingRow(edited, before, clearDirty = true)))
        assertEquals(ContactWriteResult.Stale, contacts.removeRow(account, "proton-id-9", ExistingRow(otherId, version(otherId), clearDirty = true)))
        assertEquals(ContactWriteResult.Stale, contacts.removeRow(account, "proton-id-6", ExistingRow(other, version(other), clearDirty = true)))

        assertEquals("not even a tombstone is left", listOf(tombstone, edited, otherId, kept).sorted(), allRawContacts(account.name).sorted())
        assertEquals(0, dataRowCount(listOf(dirty, clean)))
        assertEquals(listOf(other), allRawContacts(otherAccount.name))
    }

    @Test
    fun removingARowDeletedOnProtonNamesItsRowAndProtonIdTogether() {
        // The account's row holding the Proton ID is another row: neither is deleted.
        val holder = insert("proton-id-1")
        val named = insert("proton-id-2")

        assertEquals(ContactWriteResult.Stale, contacts.removeRow(account, "proton-id-1", ExistingRow(named, version(named))))

        assertEquals(listOf(holder, named), allRawContacts(account.name).sorted())
    }

    @Test
    fun detachingARowClearsItsSourceIdAloneAtTheSameVersion() {
        // Use phone on a phone edit vs a Proton deletion, dirty or clean.
        val dirty = insert("proton-id-1").also { editAsAnApp(it) }
        val clean = insert("proton-id-2")
        val rows = contacts.readRows(dirty)
        val dirtyVersion = version(dirty)
        val cleanVersion = version(clean)

        assertEquals(ContactWriteResult.Written(dirty), contacts.detach(account, "proton-id-1", ExistingRow(dirty, dirtyVersion)))
        assertEquals(ContactWriteResult.Written(clean), contacts.detach(account, "proton-id-2", ExistingRow(clean, cleanVersion)))

        assertEquals(listOf(null, null), listOf(current(dirty).sourceId, current(clean).sourceId))
        assertEquals("DIRTY stays as it was", listOf(true, false), listOf(current(dirty).dirty, current(clean).dirty))
        assertEquals("its data rows and their keys stay", rows, contacts.readRows(dirty))
        // A sync-adapter change to the raw contact alone leaves VERSION, so the create's write is guarded by the one
        // the plan read.
        assertEquals(listOf(dirtyVersion, cleanVersion), listOf(version(dirty), version(clean)))
        val bound = contacts.apply(account, listOf(ContactWrite("proton-id-9", ExistingRow(dirty, dirtyVersion, clearDirty = true, bind = true), dataRows(full, null))))
        assertEquals(listOf(ContactWriteResult.Written(dirty)), bound)
        assertEquals("proton-id-9", current(dirty).sourceId)
    }

    @Test
    fun detachingNeverTouchesATombstoneAnotherVersionAnotherProtonIdAnotherAccountsRowADataSetsRowOrAGoneOne() {
        val tombstone = insert("proton-id-1").also { deleteAsAnApp(it) }
        val edited = insert("proton-id-2")
        val before = version(edited)
        editAsAnApp(edited)
        val otherId = insert("proton-id-3")
        val other = insert("proton-id-4", into = otherAccount)
        val dataSet = insertWithDataSet("proton-id-5")
        val gone = insert("proton-id-6")
        val goneVersion = version(gone)
        resolver.delete(RawContacts.CONTENT_URI.asSyncAdapter(), "${RawContacts._ID}=?", arrayOf("$gone"))
        val holder = insert("proton-id-7")
        val named = insert("proton-id-8")

        assertEquals(ContactWriteResult.Stale, contacts.detach(account, "proton-id-1", ExistingRow(tombstone, version(tombstone))))
        assertEquals(ContactWriteResult.Stale, contacts.detach(account, "proton-id-2", ExistingRow(edited, before)))
        assertEquals(ContactWriteResult.Stale, contacts.detach(account, "proton-id-9", ExistingRow(otherId, version(otherId))))
        assertEquals(ContactWriteResult.Stale, contacts.detach(account, "proton-id-4", ExistingRow(other, version(other))))
        assertEquals(ContactWriteResult.Stale, contacts.detach(account, "proton-id-5", ExistingRow(dataSet, version(dataSet))))
        assertEquals(ContactWriteResult.Stale, contacts.detach(account, "proton-id-6", ExistingRow(gone, goneVersion)))
        assertEquals(ContactWriteResult.Stale, contacts.detach(account, "proton-id-7", ExistingRow(named, version(named))))

        val sources = listOf(tombstone, edited, otherId, dataSet, holder, named).map { rawColumn(it, RawContacts.SOURCE_ID) }
        assertEquals((1..8).filter { it != 4 && it != 6 }.map { "proton-id-$it" }, sources)
        assertEquals("proton-id-4", rawColumn(other, RawContacts.SOURCE_ID))
    }

    @Test
    fun deletingAnAccountsRowsRemovesEveryOneForGood() {
        // "Remove from this phone" leaves no rows, even before Android's own cleanup runs. The account isn't in
        // AccountManager, as after a removal whose rows Android hasn't deleted yet.
        val live = insert("proton-id-1")
        val edited = insert("proton-id-2").also { editAsAnApp(it) }
        val tombstone = insert("proton-id-3")
        assertEquals(1, resolver.delete(RawContacts.CONTENT_URI, "${RawContacts._ID}=?", arrayOf("$tombstone")))
        val bare = checkNotNull(resolver.insert(RawContacts.CONTENT_URI, ContentValues().apply {
            put(RawContacts.ACCOUNT_TYPE, TYPE)
            put(RawContacts.ACCOUNT_NAME, account.name)
        })).let { ContentUris.parseId(it) }
        val ids = listOf(live, edited, tombstone, bare)
        assertTrue(dataRowCount(ids) > 0)

        assertEquals(4, contacts.deleteAccountRows(account.name))

        assertEquals(emptyList<DeviceContact>(), contacts.query(account))
        assertEquals("not even a tombstone is left", emptyList<Long>(), allRawContacts(account.name))
        assertEquals("their data is no longer readable", 0, dataRowCount(ids))
    }

    @Test
    fun deletingAnAccountsRowsLeavesEveryOtherRowAlone() {
        insert("proton-id-1")
        val otherAccounts = insert("proton-id-2", into = otherAccount)
        val otherType = ProviderDeviceContacts(resolver, OTHER_TYPE, localAccountName = null, localAccountType = null).apply(account, listOf(ContactWrite("proton-id-3", null, dataRows(full, null))))
        val otherTypeId = (otherType.single() as ContactWriteResult.Written).rawContactId
        val dataSet = insertWithDataSet("proton-id-4")
        // Only exactly this name: Android account names are case-sensitive.
        val caseVariant = PhoneAccount(AccountId("test-only-case-id"), "Test-Only@example.test")
        val caseVariantId = insert("proton-id-5", into = caseVariant)

        assertEquals(1, contacts.deleteAccountRows(account.name))

        assertEquals(listOf(caseVariantId), contacts.query(caseVariant).map { it.rawContactId })
        assertEquals(listOf(otherAccounts), contacts.query(otherAccount).map { it.rawContactId })
        assertEquals("the same name under another type", listOf(otherTypeId), allRawContacts(account.name, OTHER_TYPE))
        assertEquals("a row with a data set isn't the account's", listOf(dataSet), allRawContacts(account.name))
    }

    @Test
    fun deletingTheRowsOfAnAccountWithNoneDeletesNothing() {
        insert("proton-id-1", into = otherAccount)

        assertEquals(0, contacts.deleteAccountRows(account.name))

        assertEquals(1, contacts.query(otherAccount).size)
    }

    @Test
    fun theAccountNamesWithRowsAreListedTombstonesIncluded() {
        insert("proton-id-1")
        insert("proton-id-2")
        val tombstone = insert("proton-id-3", into = otherAccount)
        assertEquals(1, resolver.delete(RawContacts.CONTENT_URI, "${RawContacts._ID}=?", arrayOf("$tombstone")))
        ProviderDeviceContacts(resolver, OTHER_TYPE, localAccountName = null, localAccountType = null).apply(PhoneAccount(AccountId("test-only-type-id"), "test-only-type@example.test"), listOf(ContactWrite("proton-id-4", null, dataRows(full, null))))
        insertWithDataSet("proton-id-5", accountName = "test-only-set@example.test")

        assertEquals(setOf(account.name, otherAccount.name), contacts.accountNamesWithRows())
    }

    @Test
    fun noRowsMeansNoAccountNames() {
        assertEquals(emptySet<String>(), contacts.accountNamesWithRows())
    }

    // Pairing rows with properties: Data.SYNC1 keys (D28).

    /** A made-up key for every value of [graph] (the name's lists FN, N, and the phonetic name), each different. */
    private fun keysOf(graph: ContactGraph): GraphKeys {
        var n = 0L
        fun key(vararg names: String) = SourceKey(names.map { "$it.%016x".format(java.util.Locale.ROOT, ++n) })
        return GraphKeys(
            name = key("FN", "N", "X-PHONETIC-FIRST-NAME"),
            emails = graph.emails.map { key("EMAIL") },
            phones = graph.phones.map { key("TEL") },
            addresses = graph.addresses.map { key("ADR") },
            organization = key("ORG", "TITLE", "ROLE"),
            birthday = key("BDAY"),
            anniversary = key("ANNIVERSARY"),
            customDates = graph.customDates.map { key("X-ABDATE") },
            note = key("NOTE"),
            urls = graph.urls.map { key("URL") },
            nickname = key("NICKNAME"),
            relations = graph.relations.map { key("RELATED") },
            sipAddresses = graph.sipAddresses.map { key("X-SIP") },
        )
    }

    private fun insertKeyed(protonId: String, graph: ContactGraph, keys: GraphKeys): Long {
        val result = contacts.apply(account, listOf(ContactWrite(protonId, existing = null, rows = dataRows(graph, null, keys))))
        return (result.single() as ContactWriteResult.Written).rawContactId
    }

    @Test
    fun keysWrittenAsTheSyncAdapterReadBackWithTheirValues() {
        val keys = keysOf(full)
        val id = insertKeyed("proton-id-1", full, keys)

        val read = keyedGraph(contacts.readRows(id))

        assertEquals(RowsRead(KeyedGraph(full.copy(uid = null), keys)), read)
    }

    @Test
    fun theRowsReadBackInRowOrderWithTheirKeys() {
        val keys = keysOf(full)
        val id = insertKeyed("proton-id-1", full, keys)

        val rows = contacts.readRows(id)

        assertEquals(dataRows(full, null, keys).map { it.mimeType }, rows.map { it.mimeType })
        assertEquals(dataRows(full, null, keys).map { it.values[Data.SYNC1] }, rows.map { it.values[Data.SYNC1] })
    }

    @Test
    fun anOrdinaryEditOfARowsValueKeepsItsKey() {
        val keys = keysOf(full)
        val id = insertKeyed("proton-id-1", full, keys)

        editAsAnApp(id)

        val read = keyedGraph(contacts.readRows(id)).contact
        assertEquals("edited on the phone", read.graph.note)
        assertEquals(keys.note, read.keys.note)
        assertEquals("1", rawColumn(id, RawContacts.DIRTY))
    }

    @Test
    fun aRowAnOrdinaryWriteInsertedHasNoKey() {
        val keys = keysOf(full)
        val id = insertKeyed("proton-id-1", full, keys)
        val values = ContentValues().apply {
            put(Data.RAW_CONTACT_ID, id)
            put(Data.MIMETYPE, EMAIL)
            put(Email.ADDRESS, "added@example.test")
            put(Email.TYPE, Email.TYPE_HOME)
        }
        assertNotNull(resolver.insert(Data.CONTENT_URI, values))

        val read = keyedGraph(contacts.readRows(id)).contact

        assertEquals(TypedEmail("added@example.test", EmailType.Home), read.graph.emails.last())
        assertEquals(keys.emails + null, read.keys.emails)
    }

    @Test
    fun aDeletedRowIsGoneFromTheRead() {
        val keys = keysOf(full)
        val id = insertKeyed("proton-id-1", full, keys)

        val deleted = resolver.delete(Data.CONTENT_URI, "${Data.RAW_CONTACT_ID}=? AND ${Data.MIMETYPE}=?", arrayOf("$id", NOTE))

        assertEquals(1, deleted)
        val read = keyedGraph(contacts.readRows(id)).contact
        assertEquals(KeyedGraph(full.copy(uid = null, note = null), keys.copy(note = null)), read)
    }

    @Test
    fun aNameTheProviderSplitOrBuiltReadsBackAsWritten() {
        val fnOnly = ContactGraph(uid = null, formattedName = "Ann B Lee")
        val nOnly = ContactGraph(uid = null, formattedName = null, name = StructuredName(given = "Ann", family = "Lee"))
        val fnKey = GraphKeys(name = SourceKey(listOf("FN.0123456789abcdef")))
        val nKey = GraphKeys(name = SourceKey(listOf("N.0123456789abcdef")))
        val split = insertKeyed("proton-id-1", fnOnly, fnKey)
        val built = insertKeyed("proton-id-2", nOnly, nKey)
        // The provider did fill in what the vCard didn't have.
        assertTrue(storedRows(split).single()[Data.DATA2] != null)
        assertTrue(storedRows(built).single()[Data.DATA1] != null)

        assertEquals(RowsRead(KeyedGraph(fnOnly, fnKey)), keyedGraph(contacts.readRows(split)))
        assertEquals(RowsRead(KeyedGraph(nOnly, nKey)), keyedGraph(contacts.readRows(built)))
    }

    @Test
    fun theProvidersPhotoRowReadsBackWithItsKey() {
        val keys = GraphKeys(photo = SourceKey(listOf("PHOTO.0123456789abcdef")))
        val graph = ContactGraph(uid = null, formattedName = null)
        val result = contacts.apply(account, listOf(ContactWrite("proton-id-1", null, dataRows(graph, photo(), keys))))
        val id = (result.single() as ContactWriteResult.Written).rawContactId

        val read = keyedGraph(contacts.readRows(id)).contact

        assertNotNull(read.graph.photo)
        assertEquals(keys.photo, read.keys.photo)
    }

    @Test
    fun readRowsOfAnUnknownRawContactIsEmpty() {
        assertEquals(emptyList<DataRow>(), contacts.readRows(Long.MAX_VALUE))
    }

    // Keep a copy: the star, and the copy into the local account. The local account is a made-up one under its own
    // `.debug` type, so a copy into the app's type is told apart, and clean() removes its rows too.

    @Test
    fun queryReadsEachRowsStar() {
        val starred = insert("proton-id-1")
        val plain = insert("proton-id-2")
        resolver.update(RawContacts.CONTENT_URI.asSyncAdapter(), ContentValues().apply { put(RawContacts.STARRED, 1) }, "${RawContacts._ID}=?", arrayOf("$starred"))

        val rows = contacts.query(account).associateBy { it.rawContactId }

        assertEquals(true, rows.getValue(starred).starred)
        assertEquals(false, rows.getValue(plain).starred)
    }

    @Test
    fun aLocalCopyIsOneNewRawContactOfTheLocalAccountWrittenAsAnApp() {
        val rows = dataRows(full, null)
        val synced = insert("proton-id-1")

        val starred = contacts.insertLocal(rows, starred = true)
        val plain = contacts.insertLocal(rows, starred = false)

        val copies = allRawContacts(LOCAL_NAME, LOCAL_TYPE)
        assertEquals(copies.map { ContactWriteResult.Written(it) }.toSet(), setOf(starred, plain))
        assertEquals(2, copies.size)
        val starredId = (starred as ContactWriteResult.Written).rawContactId
        val plainId = (plain as ContactWriteResult.Written).rawContactId
        listOf(starredId, plainId).forEach { id ->
            assertEquals(LOCAL_TYPE, rawColumn(id, RawContacts.ACCOUNT_TYPE))
            assertEquals(LOCAL_NAME, rawColumn(id, RawContacts.ACCOUNT_NAME))
            assertEquals(null, rawColumn(id, RawContacts.SOURCE_ID))
            assertEquals(null, rawColumn(id, RawContacts.DATA_SET))
            assertEquals("an ordinary app's insert", "1", rawColumn(id, RawContacts.DIRTY))
            assertEquals("the rows given", storedRows(synced).sortedBy { it.toString() }, storedRows(id).sortedBy { it.toString() })
            // With _ID in the projection: the provider gives distinct rows otherwise.
            val keys = resolver.query(Data.CONTENT_URI, arrayOf(Data._ID, Data.SYNC1), "${Data.RAW_CONTACT_ID}=?", arrayOf("$id"), null).rows { it.getString(1) }
            assertEquals(rows.map { null }, keys)
        }
        assertEquals("1", rawColumn(starredId, RawContacts.STARRED))
        assertEquals("0", rawColumn(plainId, RawContacts.STARRED))
    }

    @Test
    fun aPhotoRowGivenTheDisplayPhotoGetsADisplayPhoto() {
        val result = contacts.insertLocal(dataRows(ContactGraph("uid-1", "Ann"), photo()), starred = false)

        val id = (result as ContactWriteResult.Written).rawContactId
        val deadline = System.currentTimeMillis() + 10_000
        var fileId: Long? = null
        while (fileId == null && System.currentTimeMillis() < deadline) {
            fileId = resolver.query(Data.CONTENT_URI, arrayOf(ContactsContract.CommonDataKinds.Photo.PHOTO_FILE_ID), "${Data.RAW_CONTACT_ID}=? AND ${Data.MIMETYPE}=?", arrayOf("$id", PHOTO), null)
                .rows { if (it.isNull(0)) null else it.getLong(0) }.single()
            if (fileId == null) Thread.sleep(50)
        }
        assertNotNull(fileId)
    }

    @Test
    fun aLocalCopyLeavesTheAppAccountsRowsAlone() {
        val id = insert("proton-id-1")
        val before = current(id)
        val rowsBefore = storedRows(id)

        contacts.insertLocal(dataRows(full, null), starred = true)

        // Android may join the copy with the row, which puts the row in a new contact (API 37).
        assertEquals(before.copy(contactId = null), current(id).copy(contactId = null))
        assertEquals(rowsBefore, storedRows(id))
        assertEquals(listOf(id), contacts.query(account).map { it.rawContactId })
    }

    @Test
    fun theOperationLimitOfALocalCopyCountsTheRawContact() {
        // The raw contact and its data rows: 499 rows make 500 operations, 498 make 499.
        val refused = contacts.insertLocal(manyRows(499), starred = false)
        assertEquals(ContactWriteResult.Failed(FailureReason.TOO_LARGE), refused)
        assertEquals(emptyList<Long>(), allRawContacts(LOCAL_NAME, LOCAL_TYPE))

        val written = contacts.insertLocal(manyRows(498), starred = false)
        assertEquals(allRawContacts(LOCAL_NAME, LOCAL_TYPE).map { ContactWriteResult.Written(it) }, listOf(written))
    }

    @Test
    fun aLocalCopyTooLargeForOneBatchFailsAndWritesNothing() {
        val huge = DataRow(PHOTO, mapOf("data15" to ColumnValue.Blob(Random(1).nextBytes(2 * 1024 * 1024))))

        assertEquals(ContactWriteResult.Failed(FailureReason.TOO_LARGE), contacts.insertLocal(dataRows(full, null) + huge, starred = false))
        assertEquals(emptyList<Long>(), allRawContacts(LOCAL_NAME, LOCAL_TYPE))
    }

    @Test
    fun aLocalCopyWithAValueTheProviderRejectsFails() {
        val bad = DataRow("", mapOf("data1" to ColumnValue.Text("x")))

        assertEquals(ContactWriteResult.Failed(FailureReason.REJECTED_VALUE), contacts.insertLocal(listOf(bad), starred = false))
        assertEquals(emptyList<Long>(), allRawContacts(LOCAL_NAME, LOCAL_TYPE))
    }

    @Test
    fun aLocalCopyTheProviderRefusesIsRefusedNeverStale() {
        // As Contact Scopes refuses every write; an unknown column gives the same OperationApplicationException.
        val odd = DataRow(NOTE, mapOf("not_a_column" to ColumnValue.Text("x")))

        assertEquals(ContactWriteResult.Failed(FailureReason.OPERATION_REFUSED), contacts.insertLocal(listOf(odd), starred = false))
        assertEquals(emptyList<Long>(), allRawContacts(LOCAL_NAME, LOCAL_TYPE))
    }

    // The import list: queryLocal and the columns importList reads.

    /** Rows this class made in the emulator's null account, deleted by their IDs after each test. */
    private val nullAccountRows = mutableListOf<Long>()

    @After
    fun deleteNullAccountRows() {
        nullAccountRows.forEach { resolver.delete(RawContacts.CONTENT_URI.asSyncAdapter(), "${RawContacts._ID}=?", arrayOf("$it")) }
        nullAccountRows.clear()
    }

    private fun named(name: String) = dataRows(ContactGraph(uid = null, formattedName = name, name = StructuredName(name.substringBefore(' '), name.substringAfter(' '))), photo = null)

    private fun written(result: ContactWriteResult) = (result as ContactWriteResult.Written).rawContactId

    private fun rawLong(rawContactId: Long, column: String): Long = checkNotNull(rawColumn(rawContactId, column)).toLong()

    @Test
    fun queryLocalReadsTheLocalAccountsLiveRowsOnly() {
        val named = written(contacts.insertLocal(named("Testonly Lou"), starred = false))
        val emailOnly = written(contacts.insertLocal(listOf(DataRow(EMAIL, mapOf("data1" to ColumnValue.Text("lou@example.test"), "data2" to ColumnValue.Integer(1)))), starred = true))
        val ringtone = "content://test-only/ringtone/1"
        val routed = ContentValues().apply {
            put(RawContacts.CUSTOM_RINGTONE, ringtone)
            put(RawContacts.SEND_TO_VOICEMAIL, 1)
        }
        assertEquals(1, resolver.update(RawContacts.CONTENT_URI, routed, "${RawContacts._ID}=?", arrayOf("$emailOnly")))
        // The Contacts app's "Set default" marks the row primary for its raw contact and for the contact.
        val asDefault = ContentValues().apply {
            put(Data.IS_PRIMARY, 1)
            put(Data.IS_SUPER_PRIMARY, 1)
        }
        assertEquals(1, resolver.update(Data.CONTENT_URI, asDefault, "${Data.RAW_CONTACT_ID}=?", arrayOf("$emailOnly")))
        // Primary for its raw contact alone isn't the contact's default.
        val primaryOnly = ContentValues().apply { put(Data.IS_PRIMARY, 1) }
        assertEquals(1, resolver.update(Data.CONTENT_URI, primaryOnly, "${Data.RAW_CONTACT_ID}=?", arrayOf("$named")))
        val deleted = written(contacts.insertLocal(named("Testonly Del"), starred = false))
        // An ordinary app's delete: the made-up local type has no sync adapter to remove it, so it stays marked.
        assertEquals(1, resolver.delete(RawContacts.CONTENT_URI, "${RawContacts._ID}=?", arrayOf("$deleted")))
        assertEquals("1", rawColumn(deleted, RawContacts.DELETED))
        val withDataSet = ContentUris.parseId(
            checkNotNull(
                resolver.insert(
                    RawContacts.CONTENT_URI.asSyncAdapter(),
                    ContentValues().apply {
                        put(RawContacts.ACCOUNT_TYPE, LOCAL_TYPE)
                        put(RawContacts.ACCOUNT_NAME, LOCAL_NAME)
                        put(RawContacts.DATA_SET, "test-only-set")
                    },
                ),
            ),
        )
        val otherType = ContentUris.parseId(
            checkNotNull(
                resolver.insert(
                    RawContacts.CONTENT_URI.asSyncAdapter(),
                    ContentValues().apply {
                        put(RawContacts.ACCOUNT_TYPE, OTHER_TYPE)
                        put(RawContacts.ACCOUNT_NAME, LOCAL_NAME)
                    },
                ),
            ),
        )
        val otherName = ContentUris.parseId(
            checkNotNull(
                resolver.insert(
                    RawContacts.CONTENT_URI.asSyncAdapter(),
                    ContentValues().apply {
                        put(RawContacts.ACCOUNT_TYPE, LOCAL_TYPE)
                        put(RawContacts.ACCOUNT_NAME, "test-only-other-local@example.test")
                    },
                ),
            ),
        )
        val appRow = insert("proton-id-1")

        val read = contacts.queryLocal()

        assertEquals(listOf(named, emailOnly), read.map { it.rawContactId }.sorted())
        listOf(withDataSet, otherType, otherName, appRow, deleted).forEach { assertTrue(read.none { row -> row.rawContactId == it }) }
        read.forEach { row ->
            assertEquals(rawLong(row.rawContactId, RawContacts.CONTACT_ID), row.contactId)
            assertEquals(rawLong(row.rawContactId, RawContacts.VERSION), row.version)
            assertEquals(null, row.sourceId)
            assertEquals(false, row.deleted)
        }
        val byId = read.associateBy { it.rawContactId }
        assertEquals("Testonly Lou", byId.getValue(named).displayName)
        assertEquals(true, byId.getValue(named).nameFromNameRow)
        assertEquals(false, byId.getValue(named).starred)
        assertEquals(null, byId.getValue(named).customRingtone)
        assertEquals(false, byId.getValue(named).sendToVoicemail)
        assertEquals(ringtone, byId.getValue(emailOnly).customRingtone)
        assertEquals(true, byId.getValue(emailOnly).sendToVoicemail)
        assertEquals(false, byId.getValue(named).hasDefault)
        assertEquals(true, byId.getValue(emailOnly).hasDefault)
        // Android names a contact with no name row after its e-mail address.
        assertEquals("lou@example.test", byId.getValue(emailOnly).displayName)
        assertEquals(false, byId.getValue(emailOnly).nameFromNameRow)
        assertEquals(true, byId.getValue(emailOnly).starred)
    }

    @Test
    fun queryLocalCountsADefaultOfAnyKindButTheNameAndPhoto() {
        val graph = ContactGraph(
            uid = null,
            formattedName = "Testonly Ann",
            name = StructuredName("Testonly", "Ann"),
            phones = listOf(TypedPhone("555-0101", PhoneType.Mobile)),
            sipAddresses = listOf("ann@sip.example.test"),
        )
        val id = written(contacts.insertLocal(dataRows(graph, photo = photo()), starred = false))
        fun markDefault(mimeType: String, default: Boolean = true) {
            val asDefault = ContentValues().apply {
                put(Data.IS_PRIMARY, if (default) 1 else 0)
                put(Data.IS_SUPER_PRIMARY, if (default) 1 else 0)
            }
            assertEquals(1, resolver.update(Data.CONTENT_URI, asDefault, "${Data.RAW_CONTACT_ID}=? AND ${Data.MIMETYPE}=?", arrayOf("$id", mimeType)))
        }
        fun hasDefault() = contacts.queryLocal().single { it.rawContactId == id }.hasDefault
        // AOSP's Contacts editor marks the name row the default when a name is typed, and a photo it sets
        // (KindSectionView, RawContactEditorView): neither is a default the copy loses.
        markDefault(NAME)
        markDefault(PHOTO)

        assertEquals(false, hasDefault())

        markDefault(PHONE)

        assertEquals(true, hasDefault())

        // Any other kind's default counts too, a SIP address's say.
        markDefault(PHONE, default = false)
        markDefault(SIP)

        assertEquals(true, hasDefault())
    }

    @Test
    fun queryLocalReadsTheNullAccountWhenThatIsTheLocalOne() {
        // GrapheneOS's local account is the null account: this test reads and deletes only its own rows there.
        val nullLocal = ProviderDeviceContacts(resolver, TYPE, localAccountName = null, localAccountType = null)
        val own = written(nullLocal.insertLocal(named("Testonly Nul"), starred = true)).also { nullAccountRows += it }
        assertEquals(null, rawColumn(own, RawContacts.ACCOUNT_TYPE))
        assertEquals(null, rawColumn(own, RawContacts.ACCOUNT_NAME))
        val madeUpLocal = written(contacts.insertLocal(named("Testonly Loc"), starred = false))
        val appRow = insert("proton-id-1")

        val read = nullLocal.queryLocal()

        val mine = read.single { it.rawContactId == own }
        assertEquals(rawLong(own, RawContacts.CONTACT_ID), mine.contactId)
        assertEquals(rawLong(own, RawContacts.VERSION), mine.version)
        assertEquals("Testonly Nul", mine.displayName)
        assertEquals(true, mine.nameFromNameRow)
        assertEquals(true, mine.starred)
        assertTrue(read.none { it.rawContactId == madeUpLocal || it.rawContactId == appRow })
    }

    @Test
    fun queryReadsTheContactAndAJoinedLocalRowSharesItWithItsVersionUnchanged() {
        val local = written(contacts.insertLocal(named("Testonly Joined"), starred = false))
        val versionBefore = contacts.queryLocal().single().version

        val app = insert("proton-id-1", ContactGraph(uid = null, formattedName = "Testonly Joined", name = StructuredName("Testonly", "Joined")))

        val synced = current(app)
        val original = contacts.queryLocal().single()
        assertEquals(rawLong(app, RawContacts.CONTACT_ID), synced.contactId)
        assertEquals(true, synced.nameFromNameRow)
        assertEquals("Android joins them as one contact", synced.contactId, original.contactId)
        assertEquals(local, original.rawContactId)
        assertEquals(versionBefore, original.version)
    }

    @Test
    fun readRowsGivesTheNumberTheProviderNormalized() {
        // A number reserved for drama in the UK (Ofcom), which the provider can place.
        val id = insert("proton-id-1", ContactGraph(uid = null, formattedName = "Ann", phones = listOf(TypedPhone("+44 20 7946 0123", PhoneType.Mobile))))

        val phone = contacts.readRows(id).single { it.mimeType == ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE }

        assertEquals(ColumnValue.Text("+442079460123"), phone.values["data4"])
    }

    @Test
    fun aRowWrittenWithTheFieldTablesColumnsReadsBackWithOnlyTheColumnsTheProviderFillsIn() {
        // Every kind of the field table, as an ordinary app writes it into the local account, with a number the
        // provider can place and an address with parts, so each column the provider fills in shows.
        val rows = dataRows(full.copy(phones = listOf(TypedPhone("+44 20 7946 0123", PhoneType.Mobile))), photo())
        val id = written(contacts.insertLocal(rows, starred = false))

        val read = contacts.readRows(id)

        assertEquals(rows.map { it.mimeType }, read.map { it.mimeType })
        rows.zip(read).forEach { (wrote, back) ->
            assertEquals(back.mimeType, PROVIDER_FILLED[back.mimeType].orEmpty(), back.values.keys - wrote.values.keys - Data.SYNC1)
        }
        assertEquals(PROVIDER_FILLED.keys, read.filter { PROVIDER_FILLED[it.mimeType].orEmpty().any(it.values::containsKey) }.map { it.mimeType }.toSet())
    }

    // The import's copy: insertImport.

    /** A made-up local contact to import, as queryLocal reads it. */
    private fun localOriginal(name: String = "Testonly Orig"): DeviceContact {
        val id = written(contacts.insertLocal(named(name), starred = false))
        return contacts.queryLocal().single { it.rawContactId == id }
    }

    /** An ordinary app changing the contact's name, as the Contacts app would. */
    private fun renameAsAnApp(rawContactId: Long) {
        val values = ContentValues().apply { put(Data.DATA1, "Testonly Renamed") }
        assertEquals(1, resolver.update(Data.CONTENT_URI, values, "${Data.RAW_CONTACT_ID}=? AND ${Data.MIMETYPE}=?", arrayOf("$rawContactId", NAME)))
    }

    @Test
    fun anImportIsOneNewRawContactOfTheAppsAccountWrittenAsAnApp() {
        val first = localOriginal("Testonly First")
        val second = localOriginal("Testonly Second")
        val originalRows = listOf(first, second).map { storedRows(it.rawContactId) }
        val rows = dataRows(full, null)
        val synced = insert("proton-id-1")

        val starred = contacts.insertImport(account, ExistingRow(first.rawContactId, first.version), rows, starred = true)
        val plain = contacts.insertImport(account, ExistingRow(second.rawContactId, second.version), rows, starred = false)

        val starredId = written(starred)
        val plainId = written(plain)
        assertEquals(setOf(synced, starredId, plainId), allRawContacts(account.name).toSet())
        listOf(starredId, plainId).forEach { id ->
            assertEquals(TYPE, rawColumn(id, RawContacts.ACCOUNT_TYPE))
            assertEquals(null, rawColumn(id, RawContacts.SOURCE_ID))
            assertEquals(null, rawColumn(id, RawContacts.DATA_SET))
            assertEquals("an ordinary app's insert", "1", rawColumn(id, RawContacts.DIRTY))
            assertEquals("the rows given", storedRows(synced).sortedBy { it.toString() }, storedRows(id).sortedBy { it.toString() })
            // With _ID in the projection: the provider gives distinct rows otherwise.
            val keys = resolver.query(Data.CONTENT_URI, arrayOf(Data._ID, Data.SYNC1), "${Data.RAW_CONTACT_ID}=?", arrayOf("$id"), null).rows { it.getString(1) }
            assertEquals(rows.map { null }, keys)
        }
        assertEquals("1", rawColumn(starredId, RawContacts.STARRED))
        assertEquals("0", rawColumn(plainId, RawContacts.STARRED))
        val after = contacts.queryLocal().associateBy { it.rawContactId }
        assertEquals("the originals stay", listOf(first, second).map { it.version }, listOf(first, second).map { after.getValue(it.rawContactId).version })
        assertEquals(originalRows, listOf(first, second).map { storedRows(it.rawContactId) })
    }

    @Test
    fun anImportWhoseOriginalMovedIsStaleAndWritesNothing() {
        val moved = localOriginal("Testonly Moved")
        renameAsAnApp(moved.rawContactId)
        assertTrue(rawLong(moved.rawContactId, RawContacts.VERSION) != moved.version)
        val deleted = localOriginal("Testonly Deleted")
        // An ordinary app's delete: the made-up local type has no sync adapter to remove it, so it stays marked.
        assertEquals(1, resolver.delete(RawContacts.CONTENT_URI, "${RawContacts._ID}=?", arrayOf("${deleted.rawContactId}")))
        val appRow = insert("proton-id-1")
        val otherType = written(ProviderDeviceContacts(resolver, TYPE, LOCAL_NAME, OTHER_TYPE).insertLocal(named("Testonly Other"), starred = false))
        val otherName = written(ProviderDeviceContacts(resolver, TYPE, "test-only-other-local@example.test", LOCAL_TYPE).insertLocal(named("Testonly Name"), starred = false))
        val withDataSet = ContentUris.parseId(
            checkNotNull(
                resolver.insert(
                    RawContacts.CONTENT_URI.asSyncAdapter(),
                    ContentValues().apply {
                        put(RawContacts.ACCOUNT_TYPE, LOCAL_TYPE)
                        put(RawContacts.ACCOUNT_NAME, LOCAL_NAME)
                        put(RawContacts.DATA_SET, "test-only-set")
                    },
                ),
            ),
        )

        val results = listOf(
            ExistingRow(moved.rawContactId, moved.version),
            ExistingRow(deleted.rawContactId, rawLong(deleted.rawContactId, RawContacts.VERSION)),
            ExistingRow(appRow, current(appRow).version),
            ExistingRow(otherType, rawLong(otherType, RawContacts.VERSION)),
            ExistingRow(otherName, rawLong(otherName, RawContacts.VERSION)),
            ExistingRow(withDataSet, rawLong(withDataSet, RawContacts.VERSION)),
        ).map { contacts.insertImport(account, it, named("Testonly Copy"), starred = false) }

        assertEquals(List(6) { ContactWriteResult.Stale }, results)
        assertEquals(listOf(appRow), allRawContacts(account.name))
    }

    @Test
    fun anImportsGuardHoldsForAnOriginalOfTheNullAccount() {
        // GrapheneOS's local account is the null account: this test reads and deletes only its own rows there.
        val nullLocal = ProviderDeviceContacts(resolver, TYPE, localAccountName = null, localAccountType = null)
        val ownId = written(nullLocal.insertLocal(named("Testonly Nul"), starred = false)).also { nullAccountRows += it }
        val own = nullLocal.queryLocal().single { it.rawContactId == ownId }
        val madeUpLocal = localOriginal("Testonly Loc")

        val stale = nullLocal.insertImport(account, ExistingRow(own.rawContactId, own.version + 1), named("Testonly Nul"), starred = false)
        val notNull = nullLocal.insertImport(account, ExistingRow(madeUpLocal.rawContactId, madeUpLocal.version), named("Testonly Loc"), starred = false)
        val copied = nullLocal.insertImport(account, ExistingRow(own.rawContactId, own.version), named("Testonly Nul"), starred = false)

        assertEquals(ContactWriteResult.Stale, stale)
        assertEquals(ContactWriteResult.Stale, notNull)
        assertEquals(listOf(written(copied)), allRawContacts(account.name))
    }

    @Test
    fun theOperationLimitOfAnImportCountsTheGuardAndTheRawContact() {
        // The guard, the raw contact, and its data rows: 498 rows make 500 operations, 497 make 499.
        val original = localOriginal()

        val refused = contacts.insertImport(account, ExistingRow(original.rawContactId, original.version), manyRows(498), starred = false)
        assertEquals(ContactWriteResult.Failed(FailureReason.TOO_LARGE), refused)
        assertEquals(emptyList<Long>(), allRawContacts(account.name))

        val written = contacts.insertImport(account, ExistingRow(original.rawContactId, original.version), manyRows(497), starred = false)
        assertEquals(allRawContacts(account.name).map { ContactWriteResult.Written(it) }, listOf(written))
    }

    @Test
    fun anImportWithAValueTheProviderRejectsFails() {
        val original = localOriginal()
        val bad = DataRow("", mapOf("data1" to ColumnValue.Text("x")))

        assertEquals(ContactWriteResult.Failed(FailureReason.REJECTED_VALUE), contacts.insertImport(account, ExistingRow(original.rawContactId, original.version), listOf(bad), starred = false))
        assertEquals(emptyList<Long>(), allRawContacts(account.name))
    }

    @Test
    fun anImportTheProviderRefusesWhileTheGuardHoldsIsRefused() {
        // As Contact Scopes refuses every write; an unknown column gives the same OperationApplicationException.
        val original = localOriginal()
        val odd = DataRow(NOTE, mapOf("not_a_column" to ColumnValue.Text("x")))

        assertEquals(ContactWriteResult.Failed(FailureReason.OPERATION_REFUSED), contacts.insertImport(account, ExistingRow(original.rawContactId, original.version), listOf(odd), starred = false))
        assertEquals(emptyList<Long>(), allRawContacts(account.name))
    }

    // Merging: rewrite.

    /**
     * A Proton row as a sync leaves it: keyed, clean, and starred, with an IM row, a kind the field table doesn't name,
     * and a photo when [photo] is given.
     */
    private fun protonRow(protonId: String = "proton-id-1", photo: ByteArray? = null, into: PhoneAccount = account): Long {
        val graph = ContactGraph(uid = null, formattedName = "Testonly Proton", emails = listOf(TypedEmail("proton@example.test", EmailType.Work)))
        val keys = GraphKeys(
            name = SourceKey(listOf("FN.0000000000000001")),
            emails = listOf(SourceKey(listOf("EMAIL.0000000000000002"))),
            photo = photo?.let { SourceKey(listOf("PHOTO.0000000000000003")) },
        )
        val id = written(contacts.apply(into, listOf(ContactWrite(protonId, existing = null, rows = dataRows(graph, photo, keys)))).single())
        addIm(id)
        resolver.update(RawContacts.CONTENT_URI.asSyncAdapter(), ContentValues().apply { put(RawContacts.STARRED, 1) }, "${RawContacts._ID}=?", arrayOf("$id"))
        assertEquals("as a sync leaves it", "0", rawColumn(id, RawContacts.DIRTY))
        return id
    }

    /** An IM row, a kind the field table doesn't name, added as the sync adapter, so the row stays clean. */
    private fun addIm(rawContactId: Long) {
        val im = ContentValues().apply {
            put(Data.RAW_CONTACT_ID, rawContactId)
            put(Data.MIMETYPE, IM)
            put(Data.DATA1, "testonly-im")
        }
        assertNotNull(resolver.insert(Data.CONTENT_URI.asSyncAdapter(), im))
    }

    /** A row's values as text, as the provider stores them in its data columns. */
    private fun DataRow.texts(): Map<String, String> = values.mapValues { (_, value) ->
        when (value) {
            is ColumnValue.Text -> value.value
            is ColumnValue.Integer -> value.value.toString()
            is ColumnValue.Blob -> value.bytes.toList().toString()
        }
    }

    /** [rawContactId]'s photo row: `Data._ID`, `PHOTO_FILE_ID` (once the display photo is stored), and key. */
    private fun photoRowOf(rawContactId: Long): Triple<Long, Long?, String?>? {
        val photoFile = ContactsContract.CommonDataKinds.Photo.PHOTO_FILE_ID
        val deadline = System.currentTimeMillis() + 10_000
        while (true) {
            val row = resolver.query(Data.CONTENT_URI, arrayOf(Data._ID, photoFile, Data.SYNC1), "${Data.RAW_CONTACT_ID}=? AND ${Data.MIMETYPE}=?", arrayOf("$rawContactId", PHOTO), null)
                .rows { Triple(it.getLong(0), if (it.isNull(1)) null else it.getLong(1), it.getString(2)) }.singleOrNull()
            if (row?.second != null || row == null || System.currentTimeMillis() > deadline) return row
            Thread.sleep(50)
        }
    }

    @Test
    fun aMergeReplacesTheRowsOfTheFieldTablesKindsWithThoseGivenAsAnApp() {
        val id = protonRow()
        val original = localOriginal()
        val originalRows = storedRows(original.rawContactId)
        val before = current(id)
        val rows = listOf(
            DataRow(NAME, mapOf("data1" to ColumnValue.Text("Testonly Proton"), Data.SYNC1 to ColumnValue.Text("FN.0000000000000001"))),
            DataRow(EMAIL, mapOf("data1" to ColumnValue.Text("proton@example.test"), "data2" to ColumnValue.Integer(2), Data.SYNC1 to ColumnValue.Text("EMAIL.0000000000000002"))),
            DataRow(EMAIL, mapOf("data1" to ColumnValue.Text("phone@example.test"), "data2" to ColumnValue.Integer(1))),
        )

        val result = contacts.rewrite(account, "proton-id-1", ExistingRow(id, before.version), ExistingRow(original.rawContactId, original.version), rows, keepPhotoRow = false)

        assertEquals(ContactWriteResult.Written(id), result)
        val after = current(id)
        assertEquals("an ordinary app's edit", true, after.dirty)
        assertTrue(after.version > before.version)
        assertEquals("proton-id-1", after.sourceId)
        assertEquals("1", rawColumn(id, RawContacts.STARRED))
        val read = contacts.readRows(id)
        assertEquals("a row of another kind stays", listOf("testonly-im"), read.filter { it.mimeType == IM }.map { it.text(Data.DATA1) })
        val replaced = read.filter { it.mimeType != IM }
        assertEquals(rows.map { it.mimeType to it.texts() }, replaced.zip(rows).map { (row, given) -> row.mimeType to row.texts().filterKeys(given.values::containsKey) })
        assertEquals(rows.size, replaced.size)
        assertEquals("the original stays", original.version, contacts.queryLocal().single { it.rawContactId == original.rawContactId }.version)
        assertEquals(originalRows, storedRows(original.rawContactId))
    }

    @Test
    fun eachPartOfAMergeIsAnOrdinaryAppsWriteSoEitherMarksTheRowDirty() {
        // Only the deletes: no rows given, on a row holding every kind of the field table and a photo, which all go.
        // Only the inserts: a row with nothing of the field table's kinds to delete.
        val emptied = insert("proton-id-1", photo = photo()).also(::addIm)
        val bare = written(contacts.apply(account, listOf(ContactWrite("proton-id-2", existing = null, rows = emptyList()))).single()).also(::addIm)
        assertEquals(listOf("0", "0"), listOf(emptied, bare).map { rawColumn(it, RawContacts.DIRTY) })
        val original = localOriginal()
        val atOriginal = ExistingRow(original.rawContactId, original.version)

        val deleted = contacts.rewrite(account, "proton-id-1", ExistingRow(emptied, current(emptied).version), atOriginal, emptyList(), keepPhotoRow = false)
        val inserted = contacts.rewrite(account, "proton-id-2", ExistingRow(bare, current(bare).version), atOriginal, named("Testonly Bare"), keepPhotoRow = false)

        assertEquals(listOf(ContactWriteResult.Written(emptied), ContactWriteResult.Written(bare)), listOf(deleted, inserted))
        assertEquals(listOf(IM), storedRows(emptied).map { it[Data.MIMETYPE] })
        assertEquals(listOf("1", "1"), listOf(emptied, bare).map { rawColumn(it, RawContacts.DIRTY) })
    }

    @Test
    fun aMergeKeepingThePhotoRowLeavesItWithItsPhotoFileAndKeyAndOneThatDoesntRemovesIt() {
        val keeps = protonRow("proton-id-1", photo = photo())
        val drops = protonRow("proton-id-2", photo = photo())
        val photoRow = checkNotNull(photoRowOf(keeps))
        assertNotNull("the provider keeps a display photo", photoRow.second)
        assertEquals("PHOTO.0000000000000003", photoRow.third)
        val original = localOriginal()

        val kept = contacts.rewrite(account, "proton-id-1", ExistingRow(keeps, current(keeps).version), ExistingRow(original.rawContactId, original.version), named("Testonly Proton"), keepPhotoRow = true)
        val dropped = contacts.rewrite(account, "proton-id-2", ExistingRow(drops, current(drops).version), ExistingRow(original.rawContactId, original.version), named("Testonly Proton"), keepPhotoRow = false)

        assertEquals(ContactWriteResult.Written(keeps), kept)
        assertEquals(photoRow, photoRowOf(keeps))
        assertEquals(ContactWriteResult.Written(drops), dropped)
        assertEquals(null, photoRowOf(drops))
    }

    @Test
    fun aMergeWhoseRowOrOriginalMovedIsStaleAndWritesNothing() {
        val good = protonRow("proton-id-1")
        val dirty = protonRow("proton-id-2").also(::renameAsAnApp)
        val deleted = protonRow("proton-id-3").also(::deleteAsAnApp)
        val otherAccounts = protonRow("proton-id-1", into = otherAccount)
        val original = localOriginal("Testonly Orig")
        val moved = localOriginal("Testonly Moved").also { renameAsAnApp(it.rawContactId) }
        val gone = localOriginal("Testonly Gone").also { deleteAsAnApp(it.rawContactId) }
        val ids = listOf(good, dirty, deleted, otherAccounts)
        val before = ids.map { storedRows(it) to rawColumn(it, RawContacts.VERSION) }
        fun at(id: Long) = ExistingRow(id, rawLong(id, RawContacts.VERSION))
        val atOriginal = ExistingRow(original.rawContactId, original.version)

        val results = listOf(
            contacts.rewrite(account, "proton-id-2", at(dirty), atOriginal, named("Testonly New"), keepPhotoRow = false),
            contacts.rewrite(account, "proton-id-3", at(deleted), atOriginal, named("Testonly New"), keepPhotoRow = false),
            contacts.rewrite(account, "proton-id-1", ExistingRow(good, rawLong(good, RawContacts.VERSION) + 1), atOriginal, named("Testonly New"), keepPhotoRow = false),
            contacts.rewrite(account, "proton-id-9", at(good), atOriginal, named("Testonly New"), keepPhotoRow = false),
            contacts.rewrite(account, "proton-id-1", at(otherAccounts), atOriginal, named("Testonly New"), keepPhotoRow = false),
            contacts.rewrite(account, "proton-id-1", at(good), ExistingRow(moved.rawContactId, moved.version), named("Testonly New"), keepPhotoRow = false),
            contacts.rewrite(account, "proton-id-1", at(good), ExistingRow(gone.rawContactId, rawLong(gone.rawContactId, RawContacts.VERSION)), named("Testonly New"), keepPhotoRow = false),
            contacts.rewrite(account, "proton-id-1", at(good), ExistingRow(otherAccounts, rawLong(otherAccounts, RawContacts.VERSION)), named("Testonly New"), keepPhotoRow = false),
        )

        assertEquals(List(8) { ContactWriteResult.Stale }, results)
        assertEquals(before, ids.map { storedRows(it) to rawColumn(it, RawContacts.VERSION) })
        assertEquals("0", rawColumn(good, RawContacts.DIRTY))
    }

    @Test
    fun aMergeTheProviderRefusesWhileTheGuardsHoldIsRefused() {
        // As Contact Scopes refuses every write; an unknown column gives the same OperationApplicationException.
        val id = protonRow()
        val original = localOriginal()
        val before = storedRows(id)
        val odd = DataRow(NOTE, mapOf("not_a_column" to ColumnValue.Text("x")))

        val result = contacts.rewrite(account, "proton-id-1", ExistingRow(id, current(id).version), ExistingRow(original.rawContactId, original.version), listOf(odd), keepPhotoRow = false)

        assertEquals(ContactWriteResult.Failed(FailureReason.OPERATION_REFUSED), result)
        assertEquals(before, storedRows(id))
        assertEquals("0", rawColumn(id, RawContacts.DIRTY))
    }

    // Removing originals: removeOriginal.

    /** A copy's raw contact as the removal checks it: its data rows, `VERSION`, `DIRTY`, and star. */
    private fun copyState(rawContactId: Long) = listOf(storedRows(rawContactId), rawColumn(rawContactId, RawContacts.VERSION), rawColumn(rawContactId, RawContacts.DIRTY), rawColumn(rawContactId, RawContacts.STARRED))

    private fun at(rawContactId: Long) = ExistingRow(rawContactId, rawLong(rawContactId, RawContacts.VERSION))

    /** An ordinary app changing raw contact columns, as the Contacts app does through the contact. */
    private fun updateAsAnApp(rawContactId: Long, values: ContentValues) =
        assertEquals(1, resolver.update(RawContacts.CONTENT_URI, values, "${RawContacts._ID}=?", arrayOf("$rawContactId")))

    @Test
    fun removingAnOriginalDeletesItAsAnAppAndLeavesItsCopyAsItWas() {
        val original = localOriginal()
        val copy = insert("proton-id-1")
        val before = copyState(copy)

        val result = contacts.removeOriginal(account, ExistingRow(original.rawContactId, original.version), "proton-id-1", at(copy), copyStarred = false)

        assertEquals(ContactWriteResult.Written(original.rawContactId), result)
        // An ordinary app's delete: the made-up local type has no sync adapter to remove it, so it stays marked.
        assertEquals("1", rawColumn(original.rawContactId, RawContacts.DELETED))
        assertEquals(before, copyState(copy))
    }

    @Test
    fun removingAnOriginalOfTheNullAccountRemovesItAtOnceAndLeavesItsJoinedCopyAsItWas() {
        // GrapheneOS's local account is the null account: this test reads and deletes only its own rows there.
        val nullLocal = ProviderDeviceContacts(resolver, TYPE, localAccountName = null, localAccountType = null)
        val ownId = written(nullLocal.insertLocal(named("Testonly Joined"), starred = false)).also { nullAccountRows += it }
        val own = nullLocal.queryLocal().single { it.rawContactId == ownId }
        val copy = insert("proton-id-1", ContactGraph(uid = null, formattedName = "Testonly Joined", name = StructuredName("Testonly", "Joined")))
        assertEquals("Android joins them as one contact", rawLong(ownId, RawContacts.CONTACT_ID), rawLong(copy, RawContacts.CONTACT_ID))
        val before = copyState(copy)

        val result = nullLocal.removeOriginal(account, ExistingRow(ownId, own.version), "proton-id-1", at(copy), copyStarred = false)

        assertEquals(ContactWriteResult.Written(ownId), result)
        val left = resolver.query(RawContacts.CONTENT_URI, arrayOf(RawContacts._ID), "${RawContacts._ID}=?", arrayOf("$ownId"), null).rows { it.getLong(0) }
        assertEquals("removed at once, with no tombstone", emptyList<Long>(), left)
        assertEquals(before, copyState(copy))
    }

    @Test
    fun aRemovalWhoseOriginalOrCopyMovedIsStaleAndDeletesNothing() {
        val copy = insert("proton-id-1")
        val dirty = insert("proton-id-2").also(::renameAsAnApp)
        val deleted = insert("proton-id-3").also(::deleteAsAnApp)
        val gone = insert("proton-id-4").also { resolver.delete(RawContacts.CONTENT_URI.asSyncAdapter(), "${RawContacts._ID}=?", arrayOf("$it")) }
        val original = localOriginal("Testonly Orig")
        val moved = localOriginal("Testonly Moved").also { renameAsAnApp(it.rawContactId) }
        val goneOriginal = localOriginal("Testonly Gone").also { deleteAsAnApp(it.rawContactId) }
        val otherType = written(ProviderDeviceContacts(resolver, TYPE, LOCAL_NAME, OTHER_TYPE).insertLocal(named("Testonly Other"), starred = false))
        val atOriginal = ExistingRow(original.rawContactId, original.version)
        val originals = listOf(original.rawContactId, moved.rawContactId, otherType)
        val before = originals.map { rawColumn(it, RawContacts.DELETED) to rawColumn(it, RawContacts.VERSION) } to copyState(copy)

        val results = listOf(
            contacts.removeOriginal(account, ExistingRow(original.rawContactId, original.version + 1), "proton-id-1", at(copy), copyStarred = false),
            contacts.removeOriginal(account, ExistingRow(moved.rawContactId, moved.version), "proton-id-1", at(copy), copyStarred = false),
            contacts.removeOriginal(account, at(goneOriginal.rawContactId), "proton-id-1", at(copy), copyStarred = false),
            contacts.removeOriginal(account, at(otherType), "proton-id-1", at(copy), copyStarred = false),
            contacts.removeOriginal(account, at(copy), "proton-id-1", at(copy), copyStarred = false),
            contacts.removeOriginal(account, ExistingRow(-1, 1), "proton-id-1", at(copy), copyStarred = false),
            contacts.removeOriginal(account, atOriginal, "proton-id-2", at(dirty), copyStarred = false),
            contacts.removeOriginal(account, atOriginal, "proton-id-3", at(deleted), copyStarred = false),
            contacts.removeOriginal(account, atOriginal, "proton-id-4", ExistingRow(gone, 1), copyStarred = false),
            contacts.removeOriginal(account, atOriginal, "proton-id-1", ExistingRow(copy, rawLong(copy, RawContacts.VERSION) + 1), copyStarred = false),
            contacts.removeOriginal(account, atOriginal, "proton-id-9", at(copy), copyStarred = false),
            contacts.removeOriginal(otherAccount, atOriginal, "proton-id-1", at(copy), copyStarred = false),
        )

        assertEquals(List(12) { ContactWriteResult.Stale }, results)
        assertEquals(before, originals.map { rawColumn(it, RawContacts.DELETED) to rawColumn(it, RawContacts.VERSION) } to copyState(copy))
    }

    @Test
    fun aRemovalWhoseOriginalGainedAStarARingtoneOrSendToVoicemailIsStaleThoughItsVersionStays() {
        // None of these moves the original's VERSION (API 37), so the guard checks them.
        val copy = insert("proton-id-1")
        val starred = localOriginal("Testonly Star").also { updateAsAnApp(it.rawContactId, ContentValues().apply { put(RawContacts.STARRED, 1) }) }
        val ringtone = localOriginal("Testonly Ring").also { updateAsAnApp(it.rawContactId, ContentValues().apply { put(RawContacts.CUSTOM_RINGTONE, "content://test-only/ringtone/1") }) }
        val voicemail = localOriginal("Testonly Voice").also { updateAsAnApp(it.rawContactId, ContentValues().apply { put(RawContacts.SEND_TO_VOICEMAIL, 1) }) }
        val changed = listOf(starred, ringtone, voicemail)
        assertEquals("their VERSIONs stay", changed.map { it.version }, changed.map { rawLong(it.rawContactId, RawContacts.VERSION) })

        val results = changed.map { contacts.removeOriginal(account, ExistingRow(it.rawContactId, it.version), "proton-id-1", at(copy), copyStarred = false) }

        assertEquals(List(3) { ContactWriteResult.Stale }, results)
        assertEquals(List(3) { "0" }, changed.map { rawColumn(it.rawContactId, RawContacts.DELETED) })
    }

    @Test
    fun aStarredOriginalIsRemovedWhileItsCopyIsStillStarredAndAnUnstarredCopyIsStale() {
        val kept = protonRow("proton-id-1")
        val unstarred = protonRow("proton-id-2")
        val first = localOriginal("Testonly First").also { updateAsAnApp(it.rawContactId, ContentValues().apply { put(RawContacts.STARRED, 1) }) }
        val second = localOriginal("Testonly Second").also { updateAsAnApp(it.rawContactId, ContentValues().apply { put(RawContacts.STARRED, 1) }) }
        val atUnstarred = at(unstarred)
        updateAsAnApp(unstarred, ContentValues().apply { put(RawContacts.STARRED, 0) })
        assertEquals("a star changed on a copy marks it dirty, its VERSION as it was", listOf("1", atUnstarred.version.toString()), listOf(rawColumn(unstarred, RawContacts.DIRTY), rawColumn(unstarred, RawContacts.VERSION)))

        val removed = contacts.removeOriginal(account, ExistingRow(first.rawContactId, first.version), "proton-id-1", at(kept), copyStarred = true)
        val stale = contacts.removeOriginal(account, ExistingRow(second.rawContactId, second.version), "proton-id-2", atUnstarred, copyStarred = true)

        assertEquals(ContactWriteResult.Written(first.rawContactId), removed)
        assertEquals(ContactWriteResult.Stale, stale)
        assertEquals("0", rawColumn(second.rawContactId, RawContacts.DELETED))
    }

    @Test
    fun anImportAndAMergeOfAStarredOriginalWithARingtoneAndSendToVoicemailAreWritten() {
        // Only a removal checks these: an import copies the star, and the others are counted in its question.
        val settings = ContentValues().apply {
            put(RawContacts.STARRED, 1)
            put(RawContacts.CUSTOM_RINGTONE, "content://test-only/ringtone/1")
            put(RawContacts.SEND_TO_VOICEMAIL, 1)
        }
        val original = localOriginal().also { updateAsAnApp(it.rawContactId, settings) }
        val atOriginal = ExistingRow(original.rawContactId, original.version)
        val proton = protonRow()

        val imported = contacts.insertImport(account, atOriginal, named("Testonly Copy"), starred = true)
        val merged = contacts.rewrite(account, "proton-id-1", at(proton), atOriginal, named("Testonly Proton"), keepPhotoRow = false)

        assertTrue(imported.toString(), imported is ContactWriteResult.Written)
        assertEquals(ContactWriteResult.Written(proton), merged)
    }

    @Test
    fun aRemovalWhoseOriginalWasGivenADefaultIsStaleSinceItsVersionMoves() {
        val copy = insert("proton-id-1")
        val original = localOriginal()
        // The Contacts app's "Set default" marks the row primary for its raw contact and for the contact.
        val asDefault = ContentValues().apply {
            put(Data.IS_PRIMARY, 1)
            put(Data.IS_SUPER_PRIMARY, 1)
        }
        assertEquals(1, resolver.update(Data.CONTENT_URI, asDefault, "${Data.RAW_CONTACT_ID}=?", arrayOf("${original.rawContactId}")))
        assertTrue(rawLong(original.rawContactId, RawContacts.VERSION) != original.version)

        val result = contacts.removeOriginal(account, ExistingRow(original.rawContactId, original.version), "proton-id-1", at(copy), copyStarred = false)

        assertEquals(ContactWriteResult.Stale, result)
        assertEquals("0", rawColumn(original.rawContactId, RawContacts.DELETED))
    }

    private companion object {
        const val LOCAL_NAME = "test-only-local@example.test"
        const val LOCAL_TYPE = "dev.rboza.android.opensyncforproton.debug.local-test-only"
        const val TYPE = "dev.rboza.android.opensyncforproton.debug"
        const val OTHER_TYPE = "dev.rboza.android.opensyncforproton.debug.other-test-only"
        const val NAME = "vnd.android.cursor.item/name"
        const val EMAIL = Email.CONTENT_ITEM_TYPE
        const val NOTE = "vnd.android.cursor.item/note"
        const val IM = "vnd.android.cursor.item/im"
        const val NOTE_TYPE_EMAIL = "vnd.android.cursor.item/email_v2"
        const val PHOTO = "vnd.android.cursor.item/photo"
        const val PHONE = "vnd.android.cursor.item/phone_v2"
        const val SIP = "vnd.android.cursor.item/sip_address"
    }
}
