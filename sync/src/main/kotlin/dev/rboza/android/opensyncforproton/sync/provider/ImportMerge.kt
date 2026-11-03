package dev.rboza.android.opensyncforproton.sync.provider

import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Nickname
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Photo
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.Data
import dev.rboza.android.opensyncforproton.sync.db.ImportRecordDao
import dev.rboza.android.opensyncforproton.sync.db.ImportRecordEntity
import java.io.IOException

/** Merge: what to do with a pair. */
enum class MergeChoice { COMBINE, KEEP_PROTON, KEEP_PHONE, KEEP_BOTH }

/**
 * The rows a pair's Proton row should hold, of the field table's kinds; with [keepPhotoRow] its photo row stays as it
 * is. [unchanged] when they're the row's own (no write), and [photoCopied] when they take the phone contact's photo.
 */
data class MergedRows(val rows: List<DataRow>, val keepPhotoRow: Boolean, val unchanged: Boolean, val photoCopied: Boolean)

/**
 * The rows a pair's Proton row should hold for **Combine both** or **Keep the phone's**, from [proton] (the Proton
 * row's, as [DeviceContacts.readRows] gives them), [phone] (the phone contact's), and [phonePhoto] (its display photo,
 * as Import reads it). A value that stays is the Proton row's own row, `Data.SYNC1` included, so the upload pairs it
 * with its property; a new one is [importRows]' row of the phone's. Rows of kinds the field table doesn't name aren't
 * given: the Proton row keeps them.
 *
 * **Combine both** keeps every Proton row and adds each value the phone shows that it lacks: a list value unless one of
 * Proton's equals it (an e-mail address or a number by the matching rules, anything else as Android shows it, whatever
 * its type and label), and a one-value kind's only when Proton has none, with no key. Proton's photo stays where it has
 * one.
 *
 * **Keep the phone's** gives the phone's values: each one Proton's row shows alike (its type and label included) is
 * Proton's own row; any other takes the key of the value it stands in for (a list value equal by the rules above, or a
 * one-value kind's value), so that property's hidden parts stay, an e-mail address's encryption settings among them,
 * and only the hidden parts of a value removed go; the rest have none, and a value only Proton has is gone. The phone's
 * photo replaces Proton's, which goes when the phone has none.
 *
 * Any other [choice] throws IllegalArgumentException.
 */
fun mergedRows(proton: List<DataRow>, phone: List<DataRow>, phonePhoto: ByteArray?, choice: MergeChoice): MergedRows {
    require(choice == MergeChoice.COMBINE || choice == MergeChoice.KEEP_PHONE) { "only Combine both and Keep the phone's write rows" }
    val (held, protonPhoto, values, photo) = sides(proton, phone, phonePhoto)
    val rows = when (choice) {
        MergeChoice.COMBINE -> {
            val result = held.toMutableList()
            for (value in values) {
                val has = when (value.slot) {
                    null -> result.any { it.slot == null && it.matches(value) }
                    else -> result.any { it.slot == value.slot && it.shown != null }
                }
                if (!has) result += value
            }
            result.map { it.row } + listOfNotNull(photo.takeIf { protonPhoto == null })
        }
        else -> {
            val unused = held.filter { it.shown != null }.toMutableList()
            val chosen = arrayOfNulls<DataRow>(values.size)
            // Proton's own row first wherever it shows a value alike, so a value it holds twice pairs with each.
            values.forEachIndexed { i, value ->
                unused.firstOrNull { it.alike(value) }?.let { alike ->
                    unused -= alike
                    chosen[i] = alike.row
                }
            }
            values.forEachIndexed { i, value ->
                if (chosen[i] != null) return@forEachIndexed
                val stood = unused.firstOrNull { it.slot == value.slot && (value.slot != null || it.matches(value)) }?.also { unused -= it }
                chosen[i] = value.row.keyed(stood?.row)
            }
            chosen.map(::checkNotNull) + listOfNotNull(photo?.keyed(protonPhoto))
        }
    }
    val keepPhotoRow = choice == MergeChoice.COMBINE && protonPhoto != null
    val photoCopied = photo != null && (choice == MergeChoice.KEEP_PHONE || protonPhoto == null)
    // The phone's photo taken is a row Proton's rows don't hold.
    val unchanged = (keepPhotoRow || protonPhoto == null) && bag(rows) == bag(held.map { it.row })
    return MergedRows(rows, keepPhotoRow, unchanged, photoCopied)
}

/**
 * Merge's Apply question: whether **Keep the phone's** would lose a value only Proton's contact has, from [proton] and
 * [phone] as [mergedRows] takes them: one Proton's row shows that none of the phone's matches (an e-mail address by the
 * matching rules, a number by them and as it's dialed too, as Remove originals compares it, anything else as Android
 * shows it, whatever its type and label), a one-value kind's included, or Proton's photo when the phone has none. A
 * photo the phone's replaces can't be compared, so it isn't one.
 */
fun losesProtonValues(proton: List<DataRow>, phone: List<DataRow>): Boolean {
    val (held, protonPhoto, values, photo) = sides(proton, phone, phonePhoto = null)
    return held.any { value -> value.shown != null && values.none { it.matchesAsDialed(value) } } || (protonPhoto != null && photo == null)
}

/**
 * A pair's two sides as merging compares them: the values of [proton]'s rows of the field table's kinds and its photo
 * row, if it holds a photo; the values a copy of [phone] would hold, and that copy's photo row, with [phonePhoto].
 */
private data class Sides(val held: List<Value>, val protonPhoto: DataRow?, val values: List<Value>, val photo: DataRow?)

private fun sides(proton: List<DataRow>, phone: List<DataRow>, phonePhoto: ByteArray?): Sides {
    val held = proton.filter { it.mimeType in FIELD_TABLE_KINDS && it.mimeType != Photo.CONTENT_ITEM_TYPE }.map(::Value)
    val protonPhoto = proton.firstOrNull { it.mimeType == Photo.CONTENT_ITEM_TYPE && keyedGraph(listOf(it)).contact.graph.photo != null }
    val offered = importRows(phone, phonePhoto)
    val photo = offered.firstOrNull { it.mimeType == Photo.CONTENT_ITEM_TYPE }
    // A copy's number has no normalized form: the phone's own row gives it, for matching.
    val normalized = phone.filter { it.mimeType == Phone.CONTENT_ITEM_TYPE }
        .mapNotNull { row -> row.text(Phone.NUMBER)?.let { number -> row.text(Phone.NORMALIZED_NUMBER)?.let { number to ColumnValue.Text(it) } } }
        .toMap()
    val values = offered.filter { it !== photo }.map { row ->
        // Phone.NUMBER is data1, which other kinds use too, and their data4 is another column.
        val form = normalized[row.text(Phone.NUMBER)].takeIf { row.mimeType == Phone.CONTENT_ITEM_TYPE }
        Value(row, shown(if (form == null) row else row.copy(values = row.values + (Phone.NORMALIZED_NUMBER to form))))
    }
    return Sides(held, protonPhoto, values, photo)
}

/** A row, the value it shows, and for a kind that holds one value, which kind that is. */
private class Value(val row: DataRow, val shown: Shown? = shown(row)) {
    val slot: String? = when (row.mimeType) {
        StructuredName.CONTENT_ITEM_TYPE, Organization.CONTENT_ITEM_TYPE, Note.CONTENT_ITEM_TYPE, Nickname.CONTENT_ITEM_TYPE -> row.mimeType
        // A birthday and an anniversary hold one each; the other dates are a list.
        Event.CONTENT_ITEM_TYPE -> row.number(Event.TYPE).takeIf { it == Event.TYPE_BIRTHDAY || it == Event.TYPE_ANNIVERSARY }?.let { "${row.mimeType}/$it" }
        else -> null
    }

    fun matches(other: Value) = shown != null && other.shown != null && shown.matches(other.shown)

    fun alike(other: Value) = shown != null && other.shown != null && shown.alike(other.shown)

    fun matchesAsDialed(other: Value) = shown != null && other.shown != null && shown.matchesAsDialed(other.shown)
}

/** The row with [from]'s key, or none when [from] is null or has none. */
private fun DataRow.keyed(from: DataRow?): DataRow = from?.values?.get(Data.SYNC1)?.let { copy(values = values + (Data.SYNC1 to it)) } ?: this

/** Rows without their order. */
private fun bag(rows: List<DataRow>) = rows.groupingBy { it }.eachCount()

/**
 * What [applyMerges] did: the pairs applied of each choice, those not applied, and whether it [wrote] a contact (a
 * rewrite, or **Keep both**'s copy), after which a sync is asked for.
 */
data class MergeResult(
    val combined: Int = 0,
    val keptProton: Int = 0,
    val keptPhone: Int = 0,
    val keptBoth: Int = 0,
    val notApplied: Int = 0,
    val wrote: Boolean = false,
)

/**
 * Merge's Apply: each of [chosen] (pairs as read for the list, each once) in turn. **Combine both** and **Keep the
 * phone's** read the phone contact's photo, then [DeviceContacts.rewrite] the Proton row with [mergedRows]' rows, while
 * it's at the `VERSION` read and the phone contact at its own, then save the import record naming the Proton row as the
 * copy, and whether the rows took the phone's photo (a display photo that couldn't be read leaves its thumbnail, which
 * isn't the original's photo as it is unless the provider no longer has it); rows left as they are write nothing and
 * only save the record. **Keep Proton's** saves the record alone, and **Keep both** imports the phone contact as
 * [importContacts] does. A rewrite not `Written` saves no record: the pair is listed again. The rest go on after one
 * not applied; a `SecurityException` or [ProviderUnavailableException] ends it, and the records saved stay.
 */
suspend fun applyMerges(contacts: DeviceContacts, records: ImportRecordDao, account: PhoneAccount, chosen: List<Pair<MergePair, MergeChoice>>): MergeResult {
    val applied = mutableMapOf<MergeChoice, Int>()
    var notApplied = 0
    var wrote = false
    for ((pair, choice) in chosen.distinctBy { (pair, _) -> pair.original.row.rawContactId }) {
        val original = pair.original.row
        val proton = pair.proton.row
        val record = ImportRecordEntity(account.id.value, original.rawContactId, original.version, copyRawContactId = proton.rawContactId)
        val done = when (choice) {
            MergeChoice.KEEP_PROTON -> {
                records.save(record)
                true
            }
            MergeChoice.KEEP_BOTH -> (importContacts(contacts, records, account, listOf(pair.original)).done > 0).also { if (it) wrote = true }
            MergeChoice.COMBINE, MergeChoice.KEEP_PHONE -> {
                var photoRead = true
                val photo = try {
                    contacts.photo(original.rawContactId)
                } catch (_: PhotoFileGoneException) {
                    null
                } catch (_: IOException) {
                    photoRead = false
                    null
                }
                val merged = mergedRows(pair.proton.rows, pair.original.rows, photo, choice)
                // Rows left as they are write nothing, and only save the record.
                val written = !merged.unchanged && contacts.rewrite(
                    account, checkNotNull(proton.sourceId), ExistingRow(proton.rawContactId, proton.version), ExistingRow(original.rawContactId, original.version),
                    merged.rows, merged.keepPhotoRow,
                ) is ContactWriteResult.Written
                if (written) wrote = true
                (merged.unchanged || written).also { if (it) records.save(record.copy(photoCopied = merged.photoCopied && photoRead)) }
            }
        }
        if (done) applied[choice] = applied.getOrDefault(choice, 0) + 1 else notApplied++
    }
    fun count(choice: MergeChoice) = applied.getOrDefault(choice, 0)
    return MergeResult(count(MergeChoice.COMBINE), count(MergeChoice.KEEP_PROTON), count(MergeChoice.KEEP_PHONE), count(MergeChoice.KEEP_BOTH), notApplied, wrote)
}
