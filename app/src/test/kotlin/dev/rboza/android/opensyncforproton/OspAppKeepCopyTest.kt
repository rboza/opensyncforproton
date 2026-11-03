package dev.rboza.android.opensyncforproton

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.res.Resources
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Looper
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import dev.rboza.android.opensyncforproton.account.AccountId
import dev.rboza.android.opensyncforproton.sync.provider.CopyResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The composition root's Keep a copy (docs/sync.md § Keep a copy) reads the app's account by its Android name and
 * writes into the local account Android names (`RawContacts.getLocalAccountName` and `getLocalAccountType`, which read
 * the platform's config strings), off the main thread. A stand-in Contacts provider answers with one made-up contact
 * and records what it's asked; the platform's strings are made up, and differ, so a name and type swapped are told
 * apart.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = OspAppKeepCopyTest.LocalAccountApp::class)
class OspAppKeepCopyTest {
    private val app = RuntimeEnvironment.getApplication() as OspApp

    @Before
    fun provider() {
        asked.clear()
        inserted.clear()
        Robolectric.setupContentProvider(Contacts::class.java, ContactsContract.AUTHORITY)
    }

    @Test
    fun `it copies the app's account into the local account Android names, off the main thread`() = runBlocking {
        val result = app.keepCopy(AccountId("a1"), NAME)

        assertEquals(CopyResult(copied = 1, notCopied = 0), result)
        assertEquals(listOf(app.packageName, NAME), asked.first { it.uri == RawContacts.CONTENT_URI }.args)
        assertFalse("on the main thread", asked.any { it.main })
        val raw = inserted.first()
        assertEquals(RawContacts.CONTENT_URI, raw.first)
        assertEquals(LOCAL_NAME, raw.second.getAsString(RawContacts.ACCOUNT_NAME))
        assertEquals(LOCAL_TYPE, raw.second.getAsString(RawContacts.ACCOUNT_TYPE))
        assertEquals(1, raw.second.getAsInteger(RawContacts.STARRED))
        assertEquals(listOf(StructuredName.CONTENT_ITEM_TYPE), inserted.drop(1).map { it.second.getAsString(Data.MIMETYPE) })
    }

    /** The Application, with the platform's config strings for the local account made up; open for OspAppImportTest. */
    open class LocalAccountApp : OspApp() {
        private val made: Resources by lazy {
            val base = super.getResources()
            @Suppress("DEPRECATION")
            object : Resources(base.assets, base.displayMetrics, base.configuration) {
                override fun getString(id: Int): String = when (id) {
                    base.getIdentifier("config_rawContactsLocalAccountName", "string", "android") -> LOCAL_NAME
                    base.getIdentifier("config_rawContactsLocalAccountType", "string", "android") -> LOCAL_TYPE
                    else -> super.getString(id)
                }
            }
        }

        override fun getResources(): Resources = made
    }

    /** What a query named: its URI, its selection's arguments, and whether it ran on the main thread. */
    data class Asked(val uri: Uri, val args: List<String>, val main: Boolean)

    /** One live raw contact of the app's account, with one name row and no photo; inserts are recorded in order. */
    class Contacts : ContentProvider() {
        override fun onCreate() = true

        override fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, sortOrder: String?): Cursor {
            asked += Asked(uri, args.orEmpty().toList(), Looper.myLooper() == Looper.getMainLooper())
            val columns = checkNotNull(projection)
            val row: Map<String, Any?> = when {
                uri == RawContacts.CONTENT_URI -> mapOf(
                    RawContacts._ID to 7L, RawContacts.SOURCE_ID to "made-up-proton-id", RawContacts.DIRTY to 0, RawContacts.DELETED to 0,
                    RawContacts.VERSION to 1L, RawContacts.DISPLAY_NAME_PRIMARY to "Made Up", RawContacts.STARRED to 1,
                )
                // The photo row's query names its MIME type: there's none.
                args.orEmpty().size > 1 -> return MatrixCursor(columns)
                else -> mapOf(Data.MIMETYPE to StructuredName.CONTENT_ITEM_TYPE, StructuredName.DISPLAY_NAME to "Made Up")
            }
            return MatrixCursor(columns).apply { addRow(columns.map { row[it] }) }
        }

        override fun insert(uri: Uri, values: ContentValues?): Uri {
            inserted += uri to ContentValues(values)
            return ContentUris.withAppendedId(uri, inserted.size.toLong())
        }

        override fun getType(uri: Uri): String? = null
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<String>?) = 0
        override fun delete(uri: Uri, selection: String?, args: Array<String>?) = 0
    }

    private companion object {
        const val NAME = "someone.made.up@example.test"
        const val LOCAL_NAME = "made-up-local-name"
        const val LOCAL_TYPE = "made.up.local.type"

        val asked = mutableListOf<Asked>()
        val inserted = mutableListOf<Pair<Uri, ContentValues>>()
    }
}
