package dev.rboza.android.opensyncforproton.sync.provider

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.DisplayPhoto
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * How [ProviderDeviceContacts.photo] tells a display photo the provider no longer has (docs/sync.md § Photos) from a
 * provider that died while it was opened, which Android reports as a missing file too. The provider here is a stand-in
 * answering each read of the photo row in turn; every value is made up.
 */
@RunWith(RobolectricTestRunner::class)
class ProviderPhotoReadTest {
    private val resolver = RuntimeEnvironment.getApplication().contentResolver
    private val provider = Robolectric.buildContentProvider(StandIn::class.java).create(ContactsContract.AUTHORITY).get()
    private val contacts = ProviderDeviceContacts(resolver, "dev.rboza.android.opensyncforproton.debug", localAccountName = null, localAccountType = null)

    /**
     * The display photo stored as [fileId]: each open takes the next answer, a photo's bytes, [UNREADABLE] for one that
     * opens but can't be read, or null for a missing file.
     */
    private fun displayPhoto(fileId: Long, vararg answers: ByteArray?) {
        val left = ArrayDeque(answers.toList())
        shadowOf(resolver).registerInputStreamSupplier(ContentUris.withAppendedId(DisplayPhoto.CONTENT_URI, fileId)) {
            opened++
            when (val answer = left.removeFirst()) {
                null -> throw FileNotFoundException("test-only")
                UNREADABLE -> object : InputStream() {
                    override fun read(): Int = throw IOException("test-only")
                }
                else -> ByteArrayInputStream(answer)
            }
        }
    }

    private var opened = 0

    @Test
    fun `a display photo missing once, as when the provider died, is read the second time, its row read again`() {
        provider.photoRows += listOf(PhotoRow(fileId = 9), PhotoRow(fileId = 9))
        displayPhoto(9, null, byteArrayOf(7, 7))

        assertArrayEquals(byteArrayOf(7, 7), contacts.photo(1))
        assertEquals(2, provider.reads)
        assertEquals(2, opened)
    }

    @Test
    fun `a display photo still missing the second time is one the provider no longer has`() {
        provider.photoRows += listOf(PhotoRow(fileId = 9), PhotoRow(fileId = 9))
        displayPhoto(9, null, null)

        assertThrows(PhotoFileGoneException::class.java) { contacts.photo(1) }
        assertEquals(2, provider.reads)
        assertEquals(2, opened)
    }

    @Test
    fun `a row read again that names another display photo gives that one`() {
        provider.photoRows += listOf(PhotoRow(fileId = 9), PhotoRow(fileId = 10))
        displayPhoto(9, null)
        displayPhoto(10, byteArrayOf(8, 8))

        assertArrayEquals(byteArrayOf(8, 8), contacts.photo(1))
    }

    @Test
    fun `a row read again that names no display photo gives its thumbnail, as the clean-up leaves it`() {
        provider.photoRows += listOf(PhotoRow(fileId = 9), PhotoRow(fileId = null))
        displayPhoto(9, null)

        assertArrayEquals(THUMBNAIL, contacts.photo(1))
    }

    @Test
    fun `a photo row gone when read again is no photo`() {
        provider.photoRows += listOf(PhotoRow(fileId = 9), null)
        displayPhoto(9, null)

        assertNull(contacts.photo(1))
    }

    @Test
    fun `only a missing display photo is opened again, and only a missing one is gone`() {
        // A full photo taken for gone would let Remove originals delete an original whose copy holds its thumbnail.
        provider.photoRows += listOf(PhotoRow(fileId = 9))
        displayPhoto(9, UNREADABLE)

        val thrown = assertThrows(IOException::class.java) { contacts.photo(1) }
        assertFalse(thrown is PhotoFileGoneException)
        assertEquals(1, provider.reads)
        assertEquals(1, opened)

        provider.photoRows += listOf(PhotoRow(fileId = 10), PhotoRow(fileId = 10))
        displayPhoto(10, null, UNREADABLE)

        val again = assertThrows(IOException::class.java) { contacts.photo(1) }
        assertFalse("missing, then unreadable", again is PhotoFileGoneException)
    }

    @Test
    fun `a display photo read the first time is read once`() {
        provider.photoRows += listOf(PhotoRow(fileId = 9))
        displayPhoto(9, byteArrayOf(7, 7))

        assertArrayEquals(byteArrayOf(7, 7), contacts.photo(1))
        assertEquals(1, provider.reads)
    }

    /** A photo row as [ProviderDeviceContacts] reads it: `Data._ID`, `PHOTO_FILE_ID`, and the thumbnail. */
    class PhotoRow(val fileId: Long?)

    /** Answers each query of a photo row with the next of [photoRows] (null for none); nothing else. */
    class StandIn : ContentProvider() {
        val photoRows = ArrayDeque<PhotoRow?>()
        var reads = 0

        override fun onCreate() = true

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            reads++
            val cursor = MatrixCursor(projection)
            photoRows.removeFirst()?.let { cursor.addRow(arrayOf(5L, it.fileId, THUMBNAIL)) }
            return cursor
        }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("reads only")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = error("reads only")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = error("reads only")
    }

    private companion object {
        val THUMBNAIL = byteArrayOf(1, 2)
        val UNREADABLE = byteArrayOf()
    }
}
