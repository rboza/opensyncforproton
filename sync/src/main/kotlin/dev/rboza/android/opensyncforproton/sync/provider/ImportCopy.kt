package dev.rboza.android.opensyncforproton.sync.provider

import android.provider.ContactsContract.CommonDataKinds.Photo
import dev.rboza.android.opensyncforproton.sync.db.ImportRecordDao
import dev.rboza.android.opensyncforproton.sync.db.ImportRecordEntity
import java.io.IOException

/**
 * An import's copy of [rows] (as [DeviceContacts.readRows] gives them), holding only what sync uploads: the contact the
 * column mapper reads from them, written again as rows, with no `Data.SYNC1`. [photo] (the display photo
 * [DeviceContacts.photo] reads) goes in the photo row; null keeps the thumbnail the row holds, and a contact without a
 * photo row gets none, as Keep a copy's.
 */
fun importRows(rows: List<DataRow>, photo: ByteArray?): List<DataRow> {
    val graph = keyedGraph(rows).contact.graph
    return dataRows(graph, photo?.takeIf { rows.any { it.mimeType == Photo.CONTENT_ITEM_TYPE } } ?: graph.photo?.bytes)
}

/** How many contacts an import-screen action carried out, and how many it couldn't. */
data class ActionResult(val done: Int, val notDone: Int)

/**
 * Import: each of [chosen] (local contacts as read for the list, each once) in turn, its photo read, then its record
 * saved, then its copy written by [DeviceContacts.insertImport] into [account] with its star, while the original is at
 * the `VERSION` read, then the record given the copy and whether the copy holds the original's photo (its thumbnail,
 * when the provider no longer has the display photo its row names). A copy refused (`Stale`, a rejected value, a
 * refused operation) deletes its record again; one whose reply was lost (`TOO_LARGE`) keeps it, since it may have been
 * written. A contact imported before (ticked by hand) is marked by its record already, which stays as it is until a new
 * copy written replaces it. A copy with no rows isn't written. Only `Written` counts as done, and the rest go on after
 * one that isn't; a `SecurityException` or [ProviderUnavailableException] ends it, and the records saved stay.
 */
suspend fun importContacts(contacts: DeviceContacts, records: ImportRecordDao, account: PhoneAccount, chosen: List<ReadContact>): ActionResult {
    var done = 0
    var notDone = 0
    val recorded = records.records(account.id.value).map { it.originalRawContactId }.toSet()
    // A contact chosen twice is imported once: a second pass would leave the first copy unrecorded.
    for (contact in chosen.distinctBy { it.row.rawContactId }) {
        val original = contact.row
        // A display photo that can't be read leaves the thumbnail, which isn't the original's photo as it is, unless
        // the provider no longer has it: then the thumbnail is all that's left of it.
        var photoRead = true
        val photo = try {
            contacts.photo(original.rawContactId)
        } catch (_: PhotoFileGoneException) {
            null
        } catch (_: IOException) {
            photoRead = false
            null
        }
        val rows = importRows(contact.rows, photo)
        if (rows.isEmpty()) {
            notDone++
            continue
        }
        val record = ImportRecordEntity(account.id.value, original.rawContactId, original.version, copyRawContactId = null)
        // Saved before the write, so a copy a crash leaves unrecorded is still marked imported before; one imported
        // before is marked already, and its record stays until a new copy replaces it.
        val importedBefore = original.rawContactId in recorded
        if (!importedBefore) records.save(record)
        when (val result = contacts.insertImport(account, ExistingRow(original.rawContactId, original.version), rows, original.starred)) {
            is ContactWriteResult.Written -> {
                val photoCopied = photoRead && rows.any { it.mimeType == Photo.CONTENT_ITEM_TYPE }
                records.save(record.copy(copyRawContactId = result.rawContactId, photoCopied = photoCopied))
                done++
            }
            ContactWriteResult.Failed(FailureReason.TOO_LARGE) -> notDone++
            else -> {
                if (!importedBefore) records.delete(account.id.value, original.rawContactId)
                notDone++
            }
        }
    }
    return ActionResult(done, notDone)
}
