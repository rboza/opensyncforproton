package dev.rboza.android.opensyncforproton.sync.provider

import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Nickname
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Photo
import android.provider.ContactsContract.CommonDataKinds.Relation
import android.provider.ContactsContract.CommonDataKinds.SipAddress
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.CommonDataKinds.Website
import android.provider.ContactsContract.Data
import java.io.IOException

/**
 * What Keep a copy's copy holds: the [rows] (as [DeviceContacts.readRows] gives them) whose kind the field table names,
 * in order and without `Data.SYNC1`, with [photo] (the display photo [DeviceContacts.photo] reads) in the first photo
 * row; null keeps the thumbnail that row holds. A contact without a photo row gets none, and a photo row left with no
 * picture is dropped, so it never makes an empty contact.
 */
fun localCopy(rows: List<DataRow>, photo: ByteArray?): List<DataRow> {
    val photoRow = rows.firstOrNull { it.mimeType == Photo.CONTENT_ITEM_TYPE }
    return rows.filter { it.mimeType in FIELD_TABLE_KINDS }.map { row ->
        val values = row.values - Data.SYNC1
        DataRow(row.mimeType, if (row === photoRow && photo != null) values + (Photo.PHOTO to ColumnValue.Blob(photo)) else values)
    }.filterNot { it.mimeType == Photo.CONTENT_ITEM_TYPE && Photo.PHOTO !in it.values }
}

/** The data kinds docs/mapping.md § Field table names. */
internal val FIELD_TABLE_KINDS = setOf(
    StructuredName.CONTENT_ITEM_TYPE, Email.CONTENT_ITEM_TYPE, Phone.CONTENT_ITEM_TYPE, StructuredPostal.CONTENT_ITEM_TYPE,
    Organization.CONTENT_ITEM_TYPE, Photo.CONTENT_ITEM_TYPE, Event.CONTENT_ITEM_TYPE, Note.CONTENT_ITEM_TYPE, Website.CONTENT_ITEM_TYPE,
    Nickname.CONTENT_ITEM_TYPE, Relation.CONTENT_ITEM_TYPE, SipAddress.CONTENT_ITEM_TYPE,
)

/** How many of the account's live rows Keep a copy wrote into the local account, and how many it couldn't. */
data class CopyResult(val copied: Int, val notCopied: Int)

/**
 * Keep a copy: each live row of [account], read as it is now, copied by [DeviceContacts.insertLocal] with its star, one
 * batch each; a copy with no rows isn't written. Only `Written` counts as copied, and the rest go on after one that
 * isn't. A display photo that can't be read leaves the row's thumbnail. A `SecurityException` or
 * [ProviderUnavailableException] ends it, and the copies made stay. Nothing is written to [account].
 */
fun keepCopy(contacts: DeviceContacts, account: PhoneAccount): CopyResult {
    var copied = 0
    var notCopied = 0
    contacts.query(account).filterNot { it.deleted }.forEach { contact ->
        val rows = contacts.readRows(contact.rawContactId)
        val photo = try {
            contacts.photo(contact.rawContactId)
        } catch (_: IOException) {
            null
        }
        val copy = localCopy(rows, photo)
        if (copy.isNotEmpty() && contacts.insertLocal(copy, contact.starred) is ContactWriteResult.Written) copied++ else notCopied++
    }
    return CopyResult(copied, notCopied)
}
