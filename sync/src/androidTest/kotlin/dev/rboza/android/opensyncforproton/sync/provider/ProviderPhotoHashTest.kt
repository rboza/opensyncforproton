package dev.rboza.android.opensyncforproton.sync.provider

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Photo
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rboza.android.opensyncforproton.account.AccountId
import dev.rboza.android.opensyncforproton.mapping.ContactGraph
import dev.rboza.android.opensyncforproton.mapping.GraphKeys
import dev.rboza.android.opensyncforproton.mapping.SourceKey
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.util.HexFormat
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The photo baseline's hash (docs/sync.md § Photos), on the real Contacts provider of the API 37 emulator, under the
 * `.debug` account type (D4) only, with made-up contacts. Every row of the test account type is deleted, as the sync
 * adapter, before and after each test.
 */
@RunWith(AndroidJUnit4::class)
class ProviderPhotoHashTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val resolver = instrumentation.targetContext.contentResolver
    private val contacts = ProviderDeviceContacts(resolver, TYPE, localAccountName = null, localAccountType = null)
    private val account = PhoneAccount(AccountId("test-only-user-id"), "test-only@example.test")
    private val graph = ContactGraph(uid = null, formattedName = "Ann", note = "test-only note")

    @Before
    fun setUp() {
        listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS).forEach {
            instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, it)
        }
        clean()
    }

    @After
    fun clean() {
        resolver.delete(RawContacts.CONTENT_URI.asSyncAdapter(), "${RawContacts.ACCOUNT_TYPE}=?", arrayOf(TYPE))
    }

    private fun Uri.asSyncAdapter(): Uri = buildUpon().appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true").build()

    /** A [size]-pixel square photo, as the engine writes it (scaled to JPEG). */
    private fun photo(size: Int, color: Int = Color.BLUE): ByteArray {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
            eraseColor(color)
            setPixel(0, 0, Color.WHITE)
        }
        val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        return (scalePhoto(png, maxDimension = 720) as ScaledPhoto.Jpeg).bytes
    }

    private fun insert(photo: ByteArray?): Long {
        val result = contacts.apply(account, listOf(ContactWrite("proton-id-1", existing = null, rows = dataRows(graph, photo))))
        return (result.single() as ContactWriteResult.Written).rawContactId
    }

    /** Replaces every data row as the sync adapter, as a download write does. */
    private fun replace(rawContactId: Long, photo: ByteArray?) {
        val version = contacts.query(account).single { it.rawContactId == rawContactId }.version
        val result = contacts.apply(account, listOf(ContactWrite("proton-id-1", ExistingRow(rawContactId, version), dataRows(graph, photo))))
        assertEquals(ContactWriteResult.Written(rawContactId), result.single())
    }

    /** Replaces every data row as the sync adapter, with [rows]; the write's result. */
    private fun write(rawContactId: Long, rows: List<DataRow>, clearDirty: Boolean = false): ContactWriteResult {
        val version = contacts.query(account).single { it.rawContactId == rawContactId }.version
        return contacts.apply(account, listOf(ContactWrite("proton-id-1", ExistingRow(rawContactId, version, clearDirty), rows))).single()
    }

    private fun <T> Cursor?.rows(read: (Cursor) -> T): List<T> = checkNotNull(this).use { cursor ->
        buildList { while (cursor.moveToNext()) add(read(cursor)) }
    }

    /** The photo row's `PHOTO_FILE_ID` (null when the provider kept no display photo) and thumbnail. */
    private fun photoRow(rawContactId: Long): Pair<Long?, ByteArray> = resolver.query(
        Data.CONTENT_URI, arrayOf(Photo.PHOTO_FILE_ID, Photo.PHOTO), "${Data.RAW_CONTACT_ID}=? AND ${Data.MIMETYPE}=?", arrayOf("$rawContactId", Photo.CONTENT_ITEM_TYPE), null,
    ).rows { (if (it.isNull(0)) null else it.getLong(0)) to it.getBlob(1) }.single()

    /** The photo row's `Data._ID` and key (`Data.SYNC1`). */
    private fun photoRowIdAndKey(rawContactId: Long): Pair<Long, String?> = resolver.query(
        Data.CONTENT_URI, arrayOf(Data._ID, Data.SYNC1), "${Data.RAW_CONTACT_ID}=? AND ${Data.MIMETYPE}=?", arrayOf("$rawContactId", Photo.CONTENT_ITEM_TYPE), null,
    ).rows { it.getLong(0) to it.getString(1) }.single()

    /** The display photo read through the raw contact's own directory, not the way the app reads it. */
    private fun displayPhoto(rawContactId: Long): ByteArray {
        val uri = Uri.withAppendedPath(ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawContactId), RawContacts.DisplayPhoto.CONTENT_DIRECTORY)
        return checkNotNull(resolver.openAssetFileDescriptor(uri, "r")).use { it.createInputStream().readBytes() }
    }

    private fun sha256(bytes: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /** An ordinary app (not the sync adapter) changing the contact's note, as the Contacts app would. */
    private fun editNoteAsAnApp(rawContactId: Long) {
        val values = ContentValues().apply { put(Note.NOTE, "edited on the phone") }
        assertEquals(1, resolver.update(Data.CONTENT_URI, values, "${Data.RAW_CONTACT_ID}=? AND ${Data.MIMETYPE}=?", arrayOf("$rawContactId", Note.CONTENT_ITEM_TYPE)))
    }

    @Test
    fun aPhotosHashIsTheSha256OfTheDisplayPhotoTheProviderKeeps() {
        val id = insert(photo(200))
        assertNotNull("the provider kept a display photo", photoRow(id).first)

        val hash = contacts.photoHash(id)

        assertEquals(sha256(displayPhoto(id)), hash)
        assertTrue(checkNotNull(hash).matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun aThumbnailSizedPhotosHashIsTheSha256OfItsThumbnail() {
        // The provider keeps no display photo for a photo no larger than its thumbnail.
        val id = insert(photo(96))
        val (fileId, thumbnail) = photoRow(id)
        assertNull(fileId)

        assertEquals(sha256(thumbnail), contacts.photoHash(id))
    }

    @Test
    fun aRowWithoutAPhotoHasNoHash() {
        val id = insert(photo = null)

        assertNull(contacts.photoHash(id))
        assertNull("an unknown row has none either", contacts.photoHash(Long.MAX_VALUE))
    }

    @Test
    fun theHashStaysTheSameAfterAnUnrelatedEdit() {
        for (size in listOf(200, 96)) {
            clean()
            val id = insert(photo(size))
            val before = contacts.photoHash(id)

            editNoteAsAnApp(id)

            assertEquals("$size px", before, contacts.photoHash(id))
        }
    }

    @Test
    fun theHashStaysTheSameWhenTheRowsAreReplacedWithTheSamePhotoWhilePhotoFileIdChanges() {
        // PHOTO_FILE_ID is no baseline: writing the same photo again changes it.
        val id = insert(photo(200))
        val fileId = photoRow(id).first
        val before = contacts.photoHash(id)

        replace(id, photo(200))

        assertNotEquals(fileId, photoRow(id).first)
        assertEquals(before, contacts.photoHash(id))
    }

    @Test
    fun aThumbnailSizedPhotosHashStaysTheSameWhenTheRowsAreReplacedWithIt() {
        val id = insert(photo(96))
        val before = contacts.photoHash(id)

        replace(id, photo(96))

        assertEquals(before, contacts.photoHash(id))
    }

    @Test
    fun anotherPhotoHashesDifferently() {
        for (size in listOf(200, 96)) {
            clean()
            val id = insert(photo(size))
            val before = contacts.photoHash(id)

            replace(id, photo(size, Color.RED))

            assertNotEquals("$size px", before, contacts.photoHash(id))
        }
    }

    /**
     * Sets [rawContactId]'s photo as the Contacts app does, by writing its display photo, and waits until the provider
     * has stored it; the `PHOTO_FILE_ID` it stored it under.
     */
    private fun setPhotoAsAnApp(rawContactId: Long, photo: ByteArray): Long? {
        val fileId = photoRow(rawContactId).first
        val uri = Uri.withAppendedPath(ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawContactId), RawContacts.DisplayPhoto.CONTENT_DIRECTORY)

        checkNotNull(resolver.openAssetFileDescriptor(uri, "rw")).use { it.createOutputStream().write(photo) }

        // The provider stores a written display photo in the background, once the stream is closed.
        val deadline = System.currentTimeMillis() + 10_000
        while (photoRow(rawContactId).first == fileId && System.currentTimeMillis() < deadline) Thread.sleep(50)
        val stored = photoRow(rawContactId).first
        assertNotEquals("the provider stored the new photo", fileId, stored)
        return stored
    }

    @Test
    fun aPhotoSetOnThePhoneChangesTheHash() {
        val id = insert(photo(200))
        val before = contacts.photoHash(id)

        val stored = setPhotoAsAnApp(id, photo(300, Color.GREEN))

        val after = try {
            contacts.photoHash(id)
        } catch (_: FileNotFoundException) {
            // The provider's photo clean-up, once at start-up and then at most once a day, deletes a photo stored while
            // it runs (as after a cold emulator boot), and the row keeps naming it. A photo set again comes after that
            // clean-up.
            assertEquals("the row still names the photo the clean-up deleted", stored, photoRow(id).first)
            setPhotoAsAnApp(id, photo(300, Color.GREEN))
            contacts.photoHash(id)
        }
        assertNotNull(after)
        assertNotEquals(before, after)
    }

    @Test
    fun aPhotoRemovedOnThePhoneHasNoHash() {
        val id = insert(photo(200))

        assertEquals(1, resolver.delete(Data.CONTENT_URI, "${Data.RAW_CONTACT_ID}=? AND ${Data.MIMETYPE}=?", arrayOf("$id", Photo.CONTENT_ITEM_TYPE)))

        assertNull(contacts.photoHash(id))
    }

    @Test
    fun aThumbnailsHashIsTheSha256OfThePhotoRowsThumbnail() {
        for (size in listOf(200, 96)) {
            clean()
            val id = insert(photo(size))

            assertEquals("$size px", sha256(photoRow(id).second), contacts.thumbnailHash(id))
        }
        clean()
        val none = insert(photo = null)
        assertNull(contacts.thumbnailHash(none))
        assertNull("an unknown row has none either", contacts.thumbnailHash(Long.MAX_VALUE))
    }

    @Test
    fun theThumbnailsHashStaysTheSameWhenTheRowsAreReplacedWithTheSamePhoto() {
        val id = insert(photo(200))
        val before = contacts.thumbnailHash(id)

        replace(id, photo(200))
        assertEquals(before, contacts.thumbnailHash(id))

        replace(id, photo(200, Color.RED))
        assertNotEquals("another photo", before, contacts.thumbnailHash(id))
    }

    /** An ordinary app (not the sync adapter) setting the photo row's `PHOTO_FILE_ID`, as the photo clean-up does. */
    private fun setPhotoFileIdAsAnApp(rawContactId: Long, fileId: Long?) {
        val values = ContentValues().apply { if (fileId == null) putNull(Photo.PHOTO_FILE_ID) else put(Photo.PHOTO_FILE_ID, fileId) }
        assertEquals(1, resolver.update(Data.CONTENT_URI, values, "${Data.RAW_CONTACT_ID}=? AND ${Data.MIMETYPE}=?", arrayOf("$rawContactId", Photo.CONTENT_ITEM_TYPE)))
    }

    @Test
    fun whatThePhotoCleanUpLeavesOfAPhotoIsItsThumbnail() {
        // The clean-up deletes a display photo while its row still names it, and a later one clears PHOTO_FILE_ID as an
        // ordinary app; the thumbnail stays (API 37 emulator).
        val id = insert(photo(200))
        val thumbnail = contacts.thumbnailHash(id)

        setPhotoFileIdAsAnApp(id, Long.MAX_VALUE)
        assertThrows("the display photo is gone", FileNotFoundException::class.java) { contacts.photoHash(id) }
        assertEquals(thumbnail, contacts.thumbnailHash(id))

        setPhotoFileIdAsAnApp(id, null)
        assertEquals("only the thumbnail is left", thumbnail, contacts.photoHash(id))
        assertEquals(thumbnail, contacts.thumbnailHash(id))
        assertFalse("an edit on the phone", contacts.untouched(id))
    }

    @Test
    fun aDisplayPhotoTheProviderNoLongerHasIsGoneWhileItsThumbnailStays() {
        // The clean-up deleted the display photo while its row still names it: only the thumbnail is left.
        val id = insert(photo(200))
        val thumbnail = photoRow(id).second

        setPhotoFileIdAsAnApp(id, Long.MAX_VALUE)

        assertThrows(PhotoFileGoneException::class.java) { contacts.photo(id) }
        val rows = contacts.readRows(id)
        assertArrayEquals("the photo row's thumbnail, as read", thumbnail, keyedGraph(rows).contact.graph.photo?.bytes)
    }

    @Test
    fun aPhotosBytesAreTheOnesItsHashIsOf() {
        // An upload sends the photo the baseline hashes.
        val display = insert(photo(200))
        assertArrayEquals(displayPhoto(display), contacts.photo(display))
        assertEquals(contacts.photoHash(display), sha256(checkNotNull(contacts.photo(display))))

        clean()
        val small = insert(photo(96))
        assertArrayEquals(photoRow(small).second, contacts.photo(small))

        clean()
        val none = insert(photo = null)
        assertNull(contacts.photo(none))
        assertNull(contacts.photo(Long.MAX_VALUE))
    }

    @Test
    fun aWriteOfThePhotoTheRowHoldsKeepsItsPhotoRow() {
        // Stored again, the photo would be compressed once more, so after an upload of the phone's photo the phone's
        // copy would no longer be Proton's.
        val key = GraphKeys(photo = SourceKey(listOf("PHOTO.0123456789abcdef")))
        for (size in listOf(200, 64)) {
            clean()
            val id = insert(photo(size))
            val held = checkNotNull(contacts.photo(id))
            val rowId = photoRowIdAndKey(id).first
            val (fileId, thumbnail) = photoRow(id)

            assertEquals(ContactWriteResult.Written(id), write(id, dataRows(graph.copy(note = "changed on Proton"), held, key)))

            assertEquals("$size px: the same row, with the write's key", rowId to "PHOTO.0123456789abcdef", photoRowIdAndKey(id))
            assertEquals("$size px", fileId, photoRow(id).first)
            assertArrayEquals("$size px", thumbnail, photoRow(id).second)
            assertArrayEquals("$size px", held, contacts.photo(id))
            assertEquals("$size px: the other rows replaced", "changed on Proton", keyedGraph(contacts.readRows(id)).contact.graph.note)

            assertEquals(ContactWriteResult.Written(id), write(id, dataRows(graph, held)))
            assertEquals("$size px: a write without a key clears it", rowId to null, photoRowIdAndKey(id))
            assertArrayEquals("$size px", held, contacts.photo(id))
        }
    }

    @Test
    fun aWriteOfAPhotoTheRowNamesButCantReadStoresItAgain() {
        // The photo clean-up deleted the display photo the row names; a write brings it back.
        val id = insert(photo(200))
        val held = checkNotNull(contacts.photo(id))
        setPhotoFileIdAsAnApp(id, Long.MAX_VALUE)

        assertEquals(ContactWriteResult.Written(id), write(id, dataRows(graph, held), clearDirty = true))

        assertNotEquals(Long.MAX_VALUE, photoRow(id).first)
        assertNotNull(contacts.photoHash(id))
    }

    @Test
    fun aRowIsUntouchedUntilThePhoneEditsOrDeletesIt() {
        val edited = insert(photo = null)
        assertTrue("as written", contacts.untouched(edited))
        replace(edited, photo(200))
        assertTrue("written again as the sync adapter", contacts.untouched(edited))

        editNoteAsAnApp(edited)
        assertFalse("edited on the phone", contacts.untouched(edited))

        clean()
        val deleted = insert(photo = null)
        assertEquals(1, resolver.delete(RawContacts.CONTENT_URI, "${RawContacts._ID}=?", arrayOf("$deleted")))
        assertFalse("a tombstone", contacts.untouched(deleted))
        assertFalse("gone", contacts.untouched(Long.MAX_VALUE))
    }

    private companion object {
        const val TYPE = "dev.rboza.android.opensyncforproton.debug"
    }
}
