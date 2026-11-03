package dev.rboza.android.opensyncforproton.sync.provider

import android.content.ContentProviderOperation
import android.content.ContentProviderResult
import android.content.ContentResolver
import android.content.ContentUris
import android.content.OperationApplicationException
import android.database.Cursor
import android.net.Uri
import android.os.RemoteException
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Photo
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.DisplayNameSources
import android.provider.ContactsContract.DisplayPhoto
import android.provider.ContactsContract.RawContacts
import dev.rboza.android.opensyncforproton.account.AccountId
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.coroutines.cancellation.CancellationException

/** The Android account a Proton account syncs into; [name] is the Proton e-mail, so `toString` hides it. */
data class PhoneAccount(val id: AccountId, val name: String) {
    override fun toString() = "PhoneAccount(redacted)"
}

/**
 * One raw contact of the account, deleted ones included. [sourceId] is the Proton ID and [displayName] the name Android
 * shows (for the report's on-screen list and import's matching only; never logged or stored), so `toString` hides both.
 * [starred] is the favourite star (`RawContacts.STARRED`), which Keep a copy copies. [contactId] is the contact Android
 * shows it in (`RawContacts.CONTACT_ID`), and [nameFromNameRow] whether [displayName] was built from a name row
 * (`DISPLAY_NAME_SOURCE` is `STRUCTURED_NAME`): import matches by both. [customRingtone] (a URI; `toString` hides it)
 * and [sendToVoicemail] are settings a copy doesn't take, so an original holding one stays, as does one with a default
 * number, e-mail address, or other value but its name and photo ([hasDefault], which only [DeviceContacts.queryLocal]
 * reads).
 */
data class DeviceContact(
    val rawContactId: Long,
    val sourceId: String?,
    val dirty: Boolean,
    val deleted: Boolean,
    val version: Long,
    val displayName: String?,
    val starred: Boolean = false,
    val contactId: Long? = null,
    val nameFromNameRow: Boolean = false,
    val customRingtone: String? = null,
    val sendToVoicemail: Boolean = false,
    val hasDefault: Boolean = false,
) {
    override fun toString() =
        "DeviceContact(rawContactId=$rawContactId, sourceId=${if (sourceId == null) "none" else "set"}, dirty=$dirty, deleted=$deleted, version=$version, starred=$starred, " +
            "contactId=$contactId, nameFromNameRow=$nameFromNameRow, customRingtone=${if (customRingtone == null) "none" else "set"}, sendToVoicemail=$sendToVoicemail, hasDefault=$hasDefault)"
}

/**
 * The row a write replaces, at the VERSION the plan read. With [clearDirty] (a settled edit whose remote moved, an
 * upload or bind, or a stored choice) the row may be dirty, and the write clears `DIRTY`; otherwise it must be clean.
 * With [bind] (a contact the app just created from the row) the row holds no Proton ID yet, and the write gives it one.
 */
data class ExistingRow(val rawContactId: Long, val version: Long, val clearDirty: Boolean = false, val bind: Boolean = false)

/**
 * One contact to write: a new row when [existing] is null, otherwise that row's data replaced, but for a photo row that
 * already holds [rows]' photo.
 */
data class ContactWrite(val protonId: String, val existing: ExistingRow?, val rows: List<DataRow>) {
    override fun toString() = "ContactWrite(existing=$existing, rows=${rows.size})"
}

sealed interface ContactWriteResult {
    data class Written(val rawContactId: Long) : ContactWriteResult

    /**
     * The phone moved on since the plan read it: the row changed, was edited or deleted on the phone, or is gone, or
     * (for a new contact) a row already holds the Proton ID. Nothing was written.
     */
    data object Stale : ContactWriteResult

    /**
     * The batch was refused, before sending or by the provider while its guard still held; nothing was written. After
     * the provider's process died mid-call the batch may have been committed after all; the next run finds that row by
     * its `SOURCE_ID`.
     */
    data class Failed(val reason: FailureReason) : ContactWriteResult
}

/** The provider gave no cursor (it isn't running, say); carries no message. */
class ProviderUnavailableException : Exception()

/**
 * The photo row names a display photo the provider no longer has: opened twice, the row read again in between, it's
 * still missing. The provider's photo clean-up deleted it, so the row's thumbnail is all that's left of the photo.
 * Carries no message.
 */
class PhotoFileGoneException : FileNotFoundException()

/** Why a write was refused. */
enum class FailureReason {
    /** An operation error while the guard held: Contact Scopes refuses every write this way. */
    OPERATION_REFUSED,

    /**
     * 500 operations or more, the guard included (counted before sending), more than one Binder transaction holds, or
     * the provider's process died.
     */
    TOO_LARGE,

    /** A value the provider rejects. */
    REJECTED_VALUE,
}

/** The app account's rows in the Contacts provider; every call blocks. */
interface DeviceContacts {
    /** Every raw contact of [account], deleted ones included. */
    fun query(account: PhoneAccount): List<DeviceContact>

    /**
     * The live raw contacts of the phone's local account (the one the composition root names), as [query] reads them.
     */
    fun queryLocal(): List<DeviceContact>

    /**
     * One provider batch per contact; results correspond 1:1 to the writes. A replaced row's photo row stays, given
     * the write's key, when the photo [photoHash] reads just before is the write's photo byte for byte: stored again,
     * the provider would compress it once more.
     */
    fun apply(account: PhoneAccount, writes: List<ContactWrite>): List<ContactWriteResult>

    /**
     * A settled edit, which the phone already shows as the remote does. Clears `DIRTY` of [existing], as the sync
     * adapter, while it's still [account]'s row holding [protonId] at the VERSION the plan read and not deleted, dirty
     * or not; its data rows stay as they are. The results are [apply]'s.
     */
    fun clearDirty(account: PhoneAccount, protonId: String, existing: ExistingRow): ContactWriteResult

    /**
     * Binds a create: gives [rawContactId], while it's [account]'s row holding no Proton ID, [protonId] as its
     * `SOURCE_ID`, as the sync adapter, whatever its VERSION, and deleted or not; nothing else changes, so an edit made
     * meanwhile stays dirty. The results are [apply]'s.
     */
    fun bind(account: PhoneAccount, protonId: String, rawContactId: Long): ContactWriteResult

    /**
     * Removes the tombstone [rawContactId] as the sync adapter, leaving nothing behind, while it's [account]'s deleted
     * row holding [protonId]; one that's gone, live, or holds another Proton ID is [ContactWriteResult.Stale]. The
     * results are [apply]'s.
     */
    fun removeTombstone(account: PhoneAccount, protonId: String, rawContactId: Long): ContactWriteResult

    /**
     * Deletes [existing] as the sync adapter, leaving no tombstone, while it's [account]'s live row holding [protonId]
     * at the VERSION read, and clean unless [ExistingRow.clearDirty] (the user chose to delete it here); one that's
     * gone, deleted, dirty when it must be clean, of another VERSION, or holds another Proton ID is
     * [ContactWriteResult.Stale]. The results are [apply]'s.
     */
    fun removeRow(account: PhoneAccount, protonId: String, existing: ExistingRow): ContactWriteResult

    /**
     * *Use phone* over a Proton deletion: clears the `SOURCE_ID` of [existing] as the sync adapter, while it's
     * [account]'s live row holding [protonId] at the VERSION read, dirty or not; its data rows and `DIRTY` stay, so the
     * row is new on the phone. [ContactWriteResult.Written] leaves it at that VERSION, which only a change to its data
     * rows raises (seen on API 37; a provider that raised it would only make the create's write stale, so the edit
     * uploads next run); anything else is [ContactWriteResult.Stale]. The results are [apply]'s.
     */
    fun detach(account: PhoneAccount, protonId: String, existing: ExistingRow): ContactWriteResult

    /**
     * Keep a copy: [rows] as one new raw contact of the phone's local account (the one the composition root names),
     * with [starred], written as an ordinary app, so it holds no `SOURCE_ID` and is `DIRTY` like any app's insert. The
     * results are [apply]'s, but never [ContactWriteResult.Stale].
     */
    fun insertLocal(rows: List<DataRow>, starred: Boolean): ContactWriteResult

    /**
     * Import: [rows] as one new raw contact of [account], with [starred], written as an ordinary app, so it holds no
     * `SOURCE_ID` and is `DIRTY` like any app's insert, while [original] is still the phone's local account's live row
     * at the VERSION read; otherwise [ContactWriteResult.Stale]. The results are [apply]'s.
     */
    fun insertImport(account: PhoneAccount, original: ExistingRow, rows: List<DataRow>, starred: Boolean): ContactWriteResult

    /**
     * Merge: replaces the data rows of [existing] of the field table's kinds by [rows], keys included, written as an
     * ordinary app, so the provider marks it `DIRTY` and the next sync uploads it; its rows of other kinds, its
     * `SOURCE_ID`, and its star stay, and with [keepPhotoRow] so does its photo row. Only while [existing] is still
     * [account]'s live, clean row holding [protonId] at the VERSION read, and [original] the phone's local account's
     * live row at its VERSION; otherwise [ContactWriteResult.Stale]. The results are [apply]'s.
     */
    fun rewrite(account: PhoneAccount, protonId: String, existing: ExistingRow, original: ExistingRow, rows: List<DataRow>, keepPhotoRow: Boolean): ContactWriteResult

    /**
     * Remove originals: deletes [original] as an ordinary app, which removes a local row at once, with no tombstone,
     * while it's still the phone's local account's live row at the VERSION its record holds, with no ringtone, send to
     * voicemail off, and unstarred unless [copyStarred] (none of which moves its VERSION on API 37), and [copy] is
     * still [account]'s live, clean row holding [protonId] at the VERSION read (a star changed on it marks it dirty);
     * otherwise [ContactWriteResult.Stale]. The results are [apply]'s.
     */
    fun removeOriginal(account: PhoneAccount, original: ExistingRow, protonId: String, copy: ExistingRow, copyStarred: Boolean): ContactWriteResult

    /** The provider's largest display-photo side, in pixels; always more than 0. */
    fun maxPhotoDimension(): Int

    /**
     * The data rows of [rawContactId] (a raw contact [query] gave), in row-ID order: each one's MIME type, its
     * `Data.SYNC1` key, and the data columns the field table names, so the column mapper can read them back.
     */
    fun readRows(rawContactId: Long): List<DataRow>

    /**
     * The SHA-256 (64 lowercase hex digits) of the display photo the provider keeps for [rawContactId], or of its photo
     * row's thumbnail when it keeps none (a photo no larger than the thumbnail, 96 px on API 37); null when the row has
     * no photo. A display photo the row names but that can't be read throws `IOException`.
     */
    fun photoHash(rawContactId: Long): String?

    /**
     * The photo [photoHash] hashes, as its bytes (an upload sends it, scaled); null when the row has none. A display
     * photo the row names but that can't be read throws `IOException`: [PhotoFileGoneException] when the provider no
     * longer has it.
     */
    fun photo(rawContactId: Long): ByteArray?

    /**
     * The SHA-256 (64 lowercase hex digits) of [rawContactId]'s photo row's thumbnail, the one part of a photo the
     * provider's photo clean-up leaves; null when the row has no photo.
     */
    fun thumbnailHash(rawContactId: Long): String?

    /**
     * Whether [rawContactId] is still there and not dirty: as the app's last write left it. A delete on the phone sets
     * `DIRTY` too, and only the sync adapter clears it.
     */
    fun untouched(rawContactId: Long): Boolean

    /**
     * Deletes, as the sync adapter, every raw contact of the app's type under exactly [accountName] (live, edited, and
     * deleted ones), with their data, and returns how many; rows with a data set and other types' rows stay. Only for
     * an account that is gone: Android can leave its rows until its next account update.
     */
    fun deleteAccountRows(accountName: String): Int

    /** The names of the accounts of the app's type that hold any raw contact, deleted ones included; never logged. */
    fun accountNamesWithRows(): Set<String>
}

/**
 * [DeviceContacts] over the Contacts provider, under [accountType] (the app ID, with `.debug` in debug builds: D4);
 * [localAccountName] and [localAccountType] are the phone's local account that Keep a copy writes into and import reads
 * and guards (`RawContacts.getLocalAccountName` and `getLocalAccountType`, both null on GrapheneOS). Every call blocks,
 * so callers run them on `Dispatchers.IO`, and the engine checks for cancellation between contacts; a missing contacts
 * permission throws `SecurityException`, no cursor [ProviderUnavailableException], and a delete with no Contacts
 * provider at all `IllegalArgumentException` (ContentResolver's "Unknown URL").
 */
class ProviderDeviceContacts(
    private val resolver: ContentResolver,
    private val accountType: String,
    private val localAccountName: String?,
    private val localAccountType: String?,
) : DeviceContacts {
    /** Every raw contact of [account], deleted ones included. Rows are selected through `RawContacts`. */
    override fun query(account: PhoneAccount): List<DeviceContact> = rawContacts(ACCOUNT_SELECTION, arrayOf(accountType, account.name))

    /**
     * The local account's live rows, by the same rule as [query]: a row with a data set is another app's. A null name
     * or type is selected with `IS NULL`, as GrapheneOS's local account is, since `=?` never matches null. Each one
     * [DeviceContact.hasDefault] when a data row of it is the contact's default (`IS_SUPER_PRIMARY`), but for the name
     * row and a photo, which AOSP's Contacts editor marks by itself and a copy loses nothing by, read by row ID alone:
     * from API 37 the `Data` view has no account columns.
     */
    override fun queryLocal(): List<DeviceContact> {
        val (type, typeArgs) = column(RawContacts.ACCOUNT_TYPE, localAccountType)
        val (name, nameArgs) = column(RawContacts.ACCOUNT_NAME, localAccountName)
        val rows = rawContacts("$type AND $name AND ${RawContacts.DATA_SET} IS NULL AND ${RawContacts.DELETED}=0", typeArgs + nameArgs)
        // With _ID in the projection: the provider gives distinct rows otherwise.
        val cursor = resolver.query(
            Data.CONTENT_URI,
            arrayOf(Data._ID, Data.RAW_CONTACT_ID),
            "${Data.IS_SUPER_PRIMARY}=1 AND ${Data.MIMETYPE} NOT IN (?, ?)",
            arrayOf(StructuredName.CONTENT_ITEM_TYPE, Photo.CONTENT_ITEM_TYPE),
            null,
        )
            ?: throw ProviderUnavailableException()
        val withDefault = cursor.use { buildSet { while (it.moveToNext()) add(it.getLong(1)) } }
        return rows.map { it.copy(hasDefault = it.rawContactId in withDefault) }
    }

    /** A selection of [column] equal to [value], `IS NULL` for null. */
    private fun column(column: String, value: String?): Pair<String, Array<String>> =
        if (value == null) "$column IS NULL" to emptyArray() else "$column=?" to arrayOf(value)

    private fun rawContacts(selection: String, args: Array<String>): List<DeviceContact> {
        val projection = arrayOf(
            RawContacts._ID, RawContacts.SOURCE_ID, RawContacts.DIRTY, RawContacts.DELETED, RawContacts.VERSION, RawContacts.DISPLAY_NAME_PRIMARY,
            RawContacts.STARRED, RawContacts.CONTACT_ID, RawContacts.DISPLAY_NAME_SOURCE, RawContacts.CUSTOM_RINGTONE, RawContacts.SEND_TO_VOICEMAIL,
        )
        val cursor = resolver.query(RawContacts.CONTENT_URI, projection, selection, args, null) ?: throw ProviderUnavailableException()
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    add(
                        DeviceContact(
                            it.getLong(0), it.getString(1), it.getInt(2) != 0, it.getInt(3) != 0, it.getLong(4), it.getString(5), it.getInt(6) != 0,
                            contactId = if (it.isNull(7)) null else it.getLong(7),
                            nameFromNameRow = !it.isNull(8) && it.getInt(8) == DisplayNameSources.STRUCTURED_NAME,
                            customRingtone = it.getString(9),
                            sendToVoicemail = it.getInt(10) != 0,
                        ),
                    )
                }
            }
        }
    }

    override fun readRows(rawContactId: Long): List<DataRow> {
        val cursor = resolver.query(Data.CONTENT_URI, arrayOf(Data.MIMETYPE) + READ_COLUMNS, "${Data.RAW_CONTACT_ID}=?", arrayOf("$rawContactId"), Data._ID)
            ?: throw ProviderUnavailableException()
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    val values = READ_COLUMNS.withIndex().mapNotNull { (i, column) ->
                        when (it.getType(i + 1)) {
                            Cursor.FIELD_TYPE_NULL -> null
                            Cursor.FIELD_TYPE_BLOB -> column to ColumnValue.Blob(it.getBlob(i + 1))
                            Cursor.FIELD_TYPE_INTEGER -> column to ColumnValue.Integer(it.getLong(i + 1))
                            else -> column to ColumnValue.Text(it.getString(i + 1))
                        }
                    }
                    add(DataRow(it.getString(0), values.toMap()))
                }
            }
        }
    }

    /**
     * The first photo row in row-ID order, as [readRows] maps it. The display photo is read by its `PHOTO_FILE_ID` (a
     * new one each time the photo is stored again, while its bytes stay the same), and hashed as it streams.
     */
    override fun photoHash(rawContactId: Long): String? = photoRow(rawContactId)?.let(::openPhoto)?.let(::sha256)

    /**
     * As [photoHash] reads it. Android reports a provider that died while the photo was opened as a missing file too,
     * so a missing one is opened once more, its row read again; still missing, the provider no longer has it.
     */
    override fun photo(rawContactId: Long): ByteArray? {
        val row = photoRow(rawContactId) ?: return null
        return try {
            openPhoto(row)?.use { it.readBytes() }
        } catch (_: FileNotFoundException) {
            val again = photoRow(rawContactId) ?: return null
            try {
                openPhoto(again)?.use { it.readBytes() }
            } catch (_: FileNotFoundException) {
                throw PhotoFileGoneException()
            }
        }
    }

    /**
     * The same photo row's thumbnail, whether or not the provider keeps a display photo for it; the provider stores an
     * empty one as none (seen on API 37).
     */
    override fun thumbnailHash(rawContactId: Long): String? = photoRow(rawContactId)?.thumbnail?.let { sha256(ByteArrayInputStream(it)) }

    private fun sha256(photo: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(photo, digest).use { it.copyTo(OutputStream.nullOutputStream()) }
        return HexFormat.of().formatHex(digest.digest())
    }

    /** [row]'s display photo's stream, or its thumbnail's bytes when the provider keeps none; null without either. */
    private fun openPhoto(row: PhotoRow): InputStream? = when {
        row.fileId != null -> resolver.openInputStream(ContentUris.withAppendedId(DisplayPhoto.CONTENT_URI, row.fileId)) ?: throw ProviderUnavailableException()
        row.thumbnail != null && row.thumbnail.isNotEmpty() -> ByteArrayInputStream(row.thumbnail)
        else -> null
    }

    /** A photo row's `Data._ID`, `PHOTO_FILE_ID` (null when the provider keeps no display photo), and thumbnail. */
    private class PhotoRow(val id: Long, val fileId: Long?, val thumbnail: ByteArray?)

    /** The first photo row of [rawContactId]; null without one. */
    private fun photoRow(rawContactId: Long): PhotoRow? {
        val cursor = resolver.query(
            Data.CONTENT_URI, arrayOf(Data._ID, Photo.PHOTO_FILE_ID, Photo.PHOTO), "${Data.RAW_CONTACT_ID}=? AND ${Data.MIMETYPE}=?",
            arrayOf("$rawContactId", Photo.CONTENT_ITEM_TYPE), Data._ID,
        ) ?: throw ProviderUnavailableException()
        return cursor.use { if (it.moveToFirst()) PhotoRow(it.getLong(0), if (it.isNull(1)) null else it.getLong(1), it.getBlob(2)) else null }
    }

    /**
     * The `Data._ID` of [rawContactId]'s photo row when the photo [photoHash] hashes is [photo]'s, byte for byte; null
     * when it's another, or can't be read (the provider's photo clean-up deleted it, say).
     */
    private fun heldPhotoRow(rawContactId: Long, photo: DataRow): Long? {
        val bytes = (photo.values[Photo.PHOTO] as? ColumnValue.Blob)?.bytes ?: return null
        val row = photoRow(rawContactId) ?: return null
        val held = try {
            openPhoto(row)?.let(::sha256)
        } catch (_: IOException) {
            null
        }
        return row.id.takeIf { held == sha256(ByteArrayInputStream(bytes)) }
    }

    override fun untouched(rawContactId: Long): Boolean {
        val cursor = resolver.query(RawContacts.CONTENT_URI, arrayOf(RawContacts._ID), "${RawContacts._ID}=? AND ${RawContacts.DIRTY}=0", arrayOf("$rawContactId"), null)
            ?: throw ProviderUnavailableException()
        return cursor.use { it.count == 1 }
    }

    /**
     * `DisplayPhoto.CONTENT_MAX_DIMENSIONS_URI`'s `DISPLAY_MAX_DIM` (720 px on API 37); no answer, or one of 0 or
     * less, is a provider that isn't answering.
     */
    override fun maxPhotoDimension(): Int {
        val cursor = resolver.query(DisplayPhoto.CONTENT_MAX_DIMENSIONS_URI, arrayOf(DisplayPhoto.DISPLAY_MAX_DIM), null, null, null)
            ?: throw ProviderUnavailableException()
        val max = cursor.use { if (it.moveToFirst()) it.getInt(0) else throw ProviderUnavailableException() }
        return if (max > 0) max else throw ProviderUnavailableException()
    }

    /**
     * Each write as its own batch, as the sync adapter (so `DIRTY` stays 0), with results in the same order. Each batch
     * starts with a guard the provider checks in the same transaction: a new contact's Proton ID is on no row of the
     * account (live or deleted), so no duplicate `SOURCE_ID` is made; a replaced row is still this account's, holds
     * this Proton ID, has the VERSION the plan read, and isn't deleted or (unless the write clears `DIRTY`) dirty. A
     * replacement then swaps all the row's data (but a photo row holding the write's photo, which stays with the
     * write's key), and last clears `DIRTY` if asked; the raw contact's own columns (the star, a ringtone) stay. A
     * batch of 500 operations or more (the provider's limit), the guard included, isn't sent: it's
     * [FailureReason.TOO_LARGE]. A refused batch is [ContactWriteResult.Stale] only if its guard no longer holds. A
     * `SecurityException` or [ProviderUnavailableException] ends the call, and the results of the writes before it are
     * lost; map repair finds those rows by `SOURCE_ID`.
     */
    override fun apply(account: PhoneAccount, writes: List<ContactWrite>): List<ContactWriteResult> = writes.map { write ->
        val existing = write.existing
        val guard = when {
            existing == null -> newContactGuard(account, write)
            existing.bind -> bindingGuard(account, existing.rawContactId, version = existing.version)
            else -> replacementGuard(account, write.protonId, existing, clean = !existing.clearDirty)
        }
        // A committed batch's new row ID is result 1, after the guard.
        batch(listOf(guard), { listOf(guard.operation()) + if (existing == null) insert(account, write) else replace(account, existing, write) }) { results ->
            existing?.rawContactId ?: ContentUris.parseId(checkNotNull(results[1].uri))
        }
    }

    /**
     * One batch as an ordinary app, so the provider marks the copy `DIRTY` as it does any app's insert: the raw contact
     * of the local account with its star, then its data rows. It has no guard, so a refused batch is never
     * [ContactWriteResult.Stale]; the rest map as [apply]'s do.
     */
    override fun insertLocal(rows: List<DataRow>, starred: Boolean): ContactWriteResult {
        val rawContact = ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
            .withValue(RawContacts.ACCOUNT_TYPE, localAccountType)
            .withValue(RawContacts.ACCOUNT_NAME, localAccountName)
            .withValue(RawContacts.STARRED, if (starred) 1 else 0)
            .build()
        // A committed batch's new row ID is result 0.
        return batch(guards = emptyList(), { listOf(rawContact) + rows.map { row -> dataInsert(Data.CONTENT_URI, row).withValueBackReference(Data.RAW_CONTACT_ID, 0).build() } }) { results ->
            ContentUris.parseId(checkNotNull(results[0].uri))
        }
    }

    /**
     * One batch as an ordinary app, led by the original's guard: the raw contact of [account] with its star, then its
     * data rows; the provider marks it `DIRTY` as it does any app's insert, so the next sync uploads it.
     */
    override fun insertImport(account: PhoneAccount, original: ExistingRow, rows: List<DataRow>, starred: Boolean): ContactWriteResult {
        val guard = originalGuard(original)
        val rawContact = ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
            .withValue(RawContacts.ACCOUNT_TYPE, accountType)
            .withValue(RawContacts.ACCOUNT_NAME, account.name)
            .withValue(RawContacts.STARRED, if (starred) 1 else 0)
            .build()
        // A committed batch's new row ID is result 1, after the guard.
        return batch(listOf(guard), { listOf(guard.operation(), rawContact) + rows.map { row -> dataInsert(Data.CONTENT_URI, row).withValueBackReference(Data.RAW_CONTACT_ID, 1).build() } }) { results ->
            ContentUris.parseId(checkNotNull(results[1].uri))
        }
    }

    /**
     * One batch as an ordinary app, led by both guards: the row's data rows of the field table's kinds deleted (its
     * photo row aside with [keepPhotoRow]), then [rows] inserted; the provider marks it `DIRTY` and raises its VERSION,
     * as it does for any app's edit.
     */
    override fun rewrite(account: PhoneAccount, protonId: String, existing: ExistingRow, original: ExistingRow, rows: List<DataRow>, keepPhotoRow: Boolean): ContactWriteResult {
        val guards = listOf(replacementGuard(account, protonId, existing, clean = true), originalGuard(original))
        val kinds = FIELD_TABLE_KINDS.filterNot { keepPhotoRow && it == Photo.CONTENT_ITEM_TYPE }
        val delete = ContentProviderOperation.newDelete(Data.CONTENT_URI)
            .withSelection("${Data.RAW_CONTACT_ID}=? AND ${Data.MIMETYPE} IN (${kinds.joinToString { "?" }})", arrayOf("${existing.rawContactId}") + kinds)
            .build()
        return batch(guards, { guards.map { it.operation() } + delete + rows.map { row -> dataInsert(Data.CONTENT_URI, row).withValue(Data.RAW_CONTACT_ID, existing.rawContactId).build() } }) {
            existing.rawContactId
        }
    }

    /**
     * One batch as an ordinary app, led by both guards: the original deleted, which the provider removes at once for
     * the local account. A copy not starred when read can't hold a starred original's star.
     */
    override fun removeOriginal(account: PhoneAccount, original: ExistingRow, protonId: String, copy: ExistingRow, copyStarred: Boolean): ContactWriteResult {
        val guards = listOf(originalGuard(original, removal = true, unstarred = !copyStarred), replacementGuard(account, protonId, copy, clean = true))
        val delete = ContentProviderOperation.newDelete(RawContacts.CONTENT_URI).withSelection("${RawContacts._ID}=?", arrayOf("${original.rawContactId}")).build()
        return batch(guards, { guards.map { it.operation() } + delete }) { original.rawContactId }
    }

    override fun clearDirty(account: PhoneAccount, protonId: String, existing: ExistingRow): ContactWriteResult {
        val guard = replacementGuard(account, protonId, existing, clean = false)
        return batch(listOf(guard), { listOf(guard.operation(), clean(account, existing.rawContactId, clearDirty = true)) }) { existing.rawContactId }
    }

    override fun bind(account: PhoneAccount, protonId: String, rawContactId: Long): ContactWriteResult {
        val guard = bindingGuard(account, rawContactId, version = null)
        return batch(listOf(guard), { listOf(guard.operation(), clean(account, rawContactId, clearDirty = false, protonId)) }) { rawContactId }
    }

    override fun removeTombstone(account: PhoneAccount, protonId: String, rawContactId: Long): ContactWriteResult {
        val guard = Guard(
            "${RawContacts._ID}=? AND $ACCOUNT_SELECTION AND ${RawContacts.SOURCE_ID}=? AND ${RawContacts.DELETED}=1",
            arrayOf(rawContactId.toString(), accountType, account.name, protonId),
            expected = 1,
        )
        return batch(listOf(guard), { listOf(guard.operation(), rowDelete(account, rawContactId)) }) { rawContactId }
    }

    override fun removeRow(account: PhoneAccount, protonId: String, existing: ExistingRow): ContactWriteResult {
        val guard = replacementGuard(account, protonId, existing, clean = !existing.clearDirty)
        return batch(listOf(guard), { listOf(guard.operation(), rowDelete(account, existing.rawContactId)) }) { existing.rawContactId }
    }

    override fun detach(account: PhoneAccount, protonId: String, existing: ExistingRow): ContactWriteResult {
        val guard = replacementGuard(account, protonId, existing, clean = false)
        val detach = ContentProviderOperation.newUpdate(syncAdapter(RawContacts.CONTENT_URI, account))
            .withSelection("${RawContacts._ID}=?", arrayOf(existing.rawContactId.toString()))
            .withValue(RawContacts.SOURCE_ID, null)
            .build()
        return batch(listOf(guard), { listOf(guard.operation(), detach) }) { existing.rawContactId }
    }

    /** As the sync adapter, a delete removes the row for good rather than marking it deleted. */
    private fun rowDelete(account: PhoneAccount, rawContactId: Long): ContentProviderOperation =
        ContentProviderOperation.newDelete(syncAdapter(RawContacts.CONTENT_URI, account))
            .withSelection("${RawContacts._ID}=?", arrayOf(rawContactId.toString()))
            .build()

    /**
     * Sends the [operations] built, led by [guards]' if there are any, as one batch, unless they reach the provider's
     * limit; [written] names the row once it's committed. A value refused while building is refused as when sending.
     */
    private fun batch(guards: List<Guard>, operations: () -> List<ContentProviderOperation>, written: (Array<ContentProviderResult>) -> Long): ContactWriteResult {
        val results = try {
            val built = operations()
            if (built.size >= MAX_OPERATIONS) return ContactWriteResult.Failed(FailureReason.TOO_LARGE)
            resolver.applyBatch(ContactsContract.AUTHORITY, ArrayList(built))
        } catch (_: OperationApplicationException) {
            // A guard failed, or the provider refused something else (Contact Scopes, an unknown column), which the
            // guards alone tell apart; without one, it's the latter.
            return if (guards.all { it.holds() }) ContactWriteResult.Failed(FailureReason.OPERATION_REFUSED) else ContactWriteResult.Stale
        } catch (_: RemoteException) {
            // TransactionTooLargeException included: more than one Binder transaction holds.
            return ContactWriteResult.Failed(FailureReason.TOO_LARGE)
        } catch (cancelled: CancellationException) {
            // A CancellationException is an IllegalStateException; it's never swallowed.
            throw cancelled
        } catch (_: IllegalArgumentException) {
            // A value the provider rejects (a missing MIME type, say) fails this contact; the others go on.
            return ContactWriteResult.Failed(FailureReason.REJECTED_VALUE)
        } catch (_: IllegalStateException) {
            return ContactWriteResult.Failed(FailureReason.REJECTED_VALUE)
        }
        return ContactWriteResult.Written(written(results))
    }

    /**
     * A selection on `RawContacts` that a batch asserts matches [expected] rows, and that can be checked again alone.
     */
    private inner class Guard(val selection: String, val args: Array<String>, val expected: Int) {
        fun operation(): ContentProviderOperation =
            ContentProviderOperation.newAssertQuery(RawContacts.CONTENT_URI).withSelection(selection, args).withExpectedCount(expected).build()

        fun holds(): Boolean {
            val cursor = resolver.query(RawContacts.CONTENT_URI, arrayOf(RawContacts._ID), selection, args, null) ?: throw ProviderUnavailableException()
            return cursor.use { it.count == expected }
        }
    }

    /**
     * [rawContactId] is still this account's and holds no Proton ID (none, or a blank one, which only another app
     * writes: blank as the planner reads it, ASCII whitespace included); with a [version], it has that VERSION and
     * isn't deleted.
     */
    private fun bindingGuard(account: PhoneAccount, rawContactId: Long, version: Long?) = Guard(
        "${RawContacts._ID}=? AND $ACCOUNT_SELECTION AND (${RawContacts.SOURCE_ID} IS NULL OR TRIM(${RawContacts.SOURCE_ID}, $BLANKS)='')" +
            (if (version != null) " AND ${RawContacts.VERSION}=? AND ${RawContacts.DELETED}=0" else ""),
        arrayOf(rawContactId.toString(), accountType, account.name) + listOfNotNull(version?.toString()),
        expected = 1,
    )

    /**
     * [original] is still the local account's own row (no data set), has the VERSION read, and isn't deleted. For a
     * removal, which none of these may lose and none moves the VERSION of (API 37), it also has no ringtone and isn't
     * sent to voicemail, and with [unstarred] has no star.
     */
    private fun originalGuard(original: ExistingRow, removal: Boolean = false, unstarred: Boolean = false): Guard {
        val (type, typeArgs) = column(RawContacts.ACCOUNT_TYPE, localAccountType)
        val (name, nameArgs) = column(RawContacts.ACCOUNT_NAME, localAccountName)
        return Guard(
            "${RawContacts._ID}=? AND $type AND $name AND ${RawContacts.DATA_SET} IS NULL AND ${RawContacts.VERSION}=? AND ${RawContacts.DELETED}=0" +
                (if (removal) " AND ${RawContacts.CUSTOM_RINGTONE} IS NULL AND ${RawContacts.SEND_TO_VOICEMAIL}=0" else "") +
                (if (unstarred) " AND ${RawContacts.STARRED}=0" else ""),
            arrayOf(original.rawContactId.toString()) + typeArgs + nameArgs + original.version.toString(),
            expected = 1,
        )
    }

    private fun newContactGuard(account: PhoneAccount, write: ContactWrite) =
        Guard("$ACCOUNT_SELECTION AND ${RawContacts.SOURCE_ID}=?", arrayOf(accountType, account.name, write.protonId), expected = 0)

    /**
     * [existing] is still this account's, holds [protonId], has the VERSION read, isn't deleted, and if [clean] isn't
     * dirty.
     */
    private fun replacementGuard(account: PhoneAccount, protonId: String, existing: ExistingRow, clean: Boolean) = Guard(
        "${RawContacts._ID}=? AND $ACCOUNT_SELECTION AND ${RawContacts.SOURCE_ID}=? AND ${RawContacts.VERSION}=? " +
            (if (clean) "AND ${RawContacts.DIRTY}=0 " else "") + "AND ${RawContacts.DELETED}=0",
        arrayOf(existing.rawContactId.toString(), accountType, account.name, protonId, existing.version.toString()),
        expected = 1,
    )

    private fun insert(account: PhoneAccount, write: ContactWrite): List<ContentProviderOperation> =
        listOf(
            ContentProviderOperation.newInsert(syncAdapter(RawContacts.CONTENT_URI, account))
                .withValue(RawContacts.ACCOUNT_TYPE, accountType)
                .withValue(RawContacts.ACCOUNT_NAME, account.name)
                .withValue(RawContacts.SOURCE_ID, write.protonId)
                .build(),
        ) + write.rows.map { row -> dataInsert(syncAdapter(Data.CONTENT_URI, account), row).withValueBackReference(Data.RAW_CONTACT_ID, 1).build() }

    /**
     * Replaces every data row of [existing] (after the guard, which is operation 0), then clears `DIRTY` if asked, and
     * gives it its Proton ID when binding.
     */
    private fun replace(account: PhoneAccount, existing: ExistingRow, write: ContactWrite): List<ContentProviderOperation> {
        // The photo the row holds isn't stored again, which would compress it once more; its row stays, with the
        // write's key.
        val photo = write.rows.firstOrNull { it.mimeType == Photo.CONTENT_ITEM_TYPE }
        val kept = photo?.let { heldPhotoRow(existing.rawContactId, it) }
        val rows = if (kept == null) write.rows else write.rows.filter { it !== photo }
        return listOf(
            ContentProviderOperation.newDelete(syncAdapter(Data.CONTENT_URI, account))
                .withSelection("${Data.RAW_CONTACT_ID}=?" + (if (kept != null) " AND ${Data._ID}<>?" else ""), arrayOf(existing.rawContactId.toString()) + listOfNotNull(kept?.toString()))
                .build(),
        ) + rows.map { row -> dataInsert(syncAdapter(Data.CONTENT_URI, account), row).withValue(Data.RAW_CONTACT_ID, existing.rawContactId).build() } +
            listOfNotNull(
                kept?.let {
                    ContentProviderOperation.newUpdate(syncAdapter(Data.CONTENT_URI, account))
                        .withSelection("${Data._ID}=?", arrayOf("$it"))
                        .withValue(Data.SYNC1, (checkNotNull(photo).values[Data.SYNC1] as? ColumnValue.Text)?.value)
                        .build()
                },
            ) +
            (if (existing.clearDirty || existing.bind) listOf(clean(account, existing.rawContactId, existing.clearDirty, write.protonId.takeIf { existing.bind })) else emptyList())
    }

    /**
     * Sets the row's `DIRTY` to 0 if [clearDirty], and its `SOURCE_ID` to [protonId] when one is given, as the sync
     * adapter.
     */
    private fun clean(account: PhoneAccount, rawContactId: Long, clearDirty: Boolean, protonId: String? = null): ContentProviderOperation =
        ContentProviderOperation.newUpdate(syncAdapter(RawContacts.CONTENT_URI, account))
            .withSelection("${RawContacts._ID}=?", arrayOf(rawContactId.toString()))
            .apply { if (clearDirty) withValue(RawContacts.DIRTY, 0) }
            .apply { if (protonId != null) withValue(RawContacts.SOURCE_ID, protonId) }
            .build()

    private fun dataInsert(uri: Uri, row: DataRow): ContentProviderOperation.Builder =
        ContentProviderOperation.newInsert(uri).withValue(Data.MIMETYPE, row.mimeType).apply {
            row.values.forEach { (column, value) ->
                when (value) {
                    is ColumnValue.Text -> withValue(column, value.value)
                    is ColumnValue.Integer -> withValue(column, value.value)
                    is ColumnValue.Blob -> withValue(column, value.bytes)
                }
            }
        }

    /**
     * One delete as the sync adapter, so nothing is left as a tombstone. The selection alone picks the rows (the
     * URI names no account), the same rule as [query]: rows with a data set stay.
     */
    override fun deleteAccountRows(accountName: String): Int {
        val uri = RawContacts.CONTENT_URI.buildUpon().appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true").build()
        return resolver.delete(uri, ACCOUNT_SELECTION, arrayOf(accountType, accountName))
    }

    override fun accountNamesWithRows(): Set<String> {
        val cursor = resolver.query(RawContacts.CONTENT_URI, arrayOf(RawContacts.ACCOUNT_NAME), TYPE_SELECTION, arrayOf(accountType), null)
            ?: throw ProviderUnavailableException()
        return cursor.use { buildSet { while (it.moveToNext()) add(checkNotNull(it.getString(0))) } }
    }

    private fun syncAdapter(uri: Uri, account: PhoneAccount): Uri = uri.buildUpon()
        .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(RawContacts.ACCOUNT_NAME, account.name)
        .appendQueryParameter(RawContacts.ACCOUNT_TYPE, accountType)
        .build()

    private companion object {
        /** The provider refuses a batch of this many operations (seen on API 37). */
        const val MAX_OPERATIONS = 500

        /** The account's own rows: a row with a data set is another app's, even under this type and name. */
        const val ACCOUNT_SELECTION = "${RawContacts.ACCOUNT_TYPE}=? AND ${RawContacts.ACCOUNT_NAME}=? AND ${RawContacts.DATA_SET} IS NULL"

        /** `Data.SYNC1` and the data columns the field table names (`data15` is the photo). */
        val READ_COLUMNS = arrayOf(Data.SYNC1) + (1..10).map { "data$it" } + Data.DATA15

        /**
         * SQLite's TRIM characters for a blank SOURCE_ID: space, tab, line feed, vertical tab, form feed, carriage
         * return.
         */
        const val BLANKS = "' ' || char(9) || char(10) || char(11) || char(12) || char(13)"

        /** The app's rows of every account, by the same rule. */
        const val TYPE_SELECTION = "${RawContacts.ACCOUNT_TYPE}=? AND ${RawContacts.DATA_SET} IS NULL"
    }
}
