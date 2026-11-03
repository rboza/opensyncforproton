package dev.rboza.android.opensyncforproton.sync.provider

import android.provider.ContactsContract.CommonDataKinds.BaseTypes
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
import dev.rboza.android.opensyncforproton.mapping.ContactGraph
import dev.rboza.android.opensyncforproton.mapping.MappingIssueCode
import dev.rboza.android.opensyncforproton.sync.db.ImportRecordEntity
import java.util.Locale

/** A raw contact and its data rows, as [DeviceContacts.readRows] gives them. `toString` shows no content. */
data class ReadContact(val row: DeviceContact, val rows: List<DataRow>)

/** Why a phone contact matches one in Proton: the first rule that holds, in this order. */
enum class MatchReason { SAME_CONTACT, EMAIL, PHONE, NAME }

enum class ImportMark { NONE, IMPORTED_BEFORE, LOOKS_IN_PROTON }

/**
 * A phone contact listed under "On this phone only", with its mark. [cantImport] is its card issue (it can't be ticked
 * when set), and [unmappedRows] whether its copy would hold less than it does (Import's question counts it).
 */
data class ImportCandidate(val original: ReadContact, val mark: ImportMark, val cantImport: MappingIssueCode?, val unmappedRows: Boolean)

/** A phone contact matching exactly one contact in Proton, [proton], which no other phone contact matches. */
data class MergePair(val original: ReadContact, val proton: ReadContact, val reason: MatchReason, val cantImport: MappingIssueCode?)

enum class OriginalState { REMOVABLE, CHANGED, HOLDS_MORE }

/**
 * An imported original whose record names a copy on Proton: a live, clean row of the app's account with a Proton ID.
 */
data class ImportedOriginal(val original: DeviceContact, val recordVersion: Long, val copy: DeviceContact, val state: OriginalState)

data class ImportList(val candidates: List<ImportCandidate>, val pairs: List<MergePair>, val originals: List<ImportedOriginal>)

/**
 * Import from this phone's list: the [local] account's live contacts sorted into candidates (in their order) and pairs,
 * matched against the contacts in Proton among [synced] (the app account's live rows holding a Proton ID), with [held]
 * the Proton IDs held as conflicts; and the imported originals [records] name whose copy is on Proton. A contact's card
 * issue is [cardIssue] of the graph its rows hold. A record whose original isn't read is left out, never dropped:
 * Contact Scopes hides rows without an error.
 */
fun importList(
    local: List<ReadContact>,
    synced: List<ReadContact>,
    records: List<ImportRecordEntity>,
    held: Set<String>,
    cardIssue: (ContactGraph) -> MappingIssueCode?,
): ImportList {
    val phone = local.filterNot { it.row.deleted }
    // A blank SOURCE_ID counts as none, as the planner reads it.
    val proton = synced.filter { !it.row.deleted && !it.row.sourceId.isNullOrBlank() }
    val recorded = records.associateBy { it.originalRawContactId }
    // Contacts imported before match nothing, so they make no other match ambiguous.
    val matches = phone.filterNot { it.row.rawContactId in recorded }.associate { contact ->
        contact.row.rawContactId to proton.mapNotNull { other -> match(contact, other)?.let { other to it } }
    }
    val matchedBy = matches.values.flatten().groupingBy { (other, _) -> other.row.rawContactId }.eachCount()

    val candidates = mutableListOf<ImportCandidate>()
    val pairs = mutableListOf<MergePair>()
    for (contact in phone) {
        val issue = cardIssue(keyedGraph(contact.rows).contact.graph)
        val found = matches[contact.row.rawContactId]
        val only = found?.singleOrNull()?.takeIf { (other, _) ->
            matchedBy[other.row.rawContactId] == 1 && !other.row.dirty && other.row.sourceId !in held
        }
        when {
            only != null -> pairs += MergePair(contact, only.first, only.second, issue)
            else -> {
                val mark = when {
                    found == null -> ImportMark.IMPORTED_BEFORE
                    found.isEmpty() -> ImportMark.NONE
                    else -> ImportMark.LOOKS_IN_PROTON
                }
                candidates += ImportCandidate(contact, mark, issue, copyLoses(contact))
            }
        }
    }

    val originals = phone.associateBy { it.row.rawContactId }
    // A copy held as a conflict isn't known to be on Proton until it's settled.
    val copies = proton.filterNot { it.row.dirty || it.row.sourceId in held }.associateBy { it.row.rawContactId }
    val imported = records.mapNotNull { record ->
        val original = originals[record.originalRawContactId] ?: return@mapNotNull null
        val copy = record.copyRawContactId?.let(copies::get) ?: return@mapNotNull null
        val state = when {
            original.row.version != record.originalVersion -> OriginalState.CHANGED
            holdsMore(original, copy, record.photoCopied) -> OriginalState.HOLDS_MORE
            else -> OriginalState.REMOVABLE
        }
        ImportedOriginal(original.row, record.originalVersion, copy.row, state)
    }
    return ImportList(candidates, pairs, imported)
}

/** The first rule by which [contact] matches [other], in [MatchReason]'s order; null when none holds. */
private fun match(contact: ReadContact, other: ReadContact): MatchReason? {
    val otherEmails = emails(other)
    val otherPhones = phones(other)
    return when {
        contact.row.contactId != null && contact.row.contactId == other.row.contactId -> MatchReason.SAME_CONTACT
        emails(contact).any { it in otherEmails } -> MatchReason.EMAIL
        phones(contact).any { number -> otherPhones.any { samePhone(number, it) } } -> MatchReason.PHONE
        name(contact)?.let { it == name(other) } == true -> MatchReason.NAME
        else -> null
    }
}

private fun emails(contact: ReadContact) = contact.rows.filter { it.mimeType == Email.CONTENT_ITEM_TYPE }.mapNotNull { it.text(Email.ADDRESS)?.let(::email) }

/** An e-mail address as matching compares it: trimmed, in lower case. */
private fun email(address: String) = address.trim().lowercase(Locale.ROOT)

private fun phones(contact: ReadContact) = contact.rows.filter { it.mimeType == Phone.CONTENT_ITEM_TYPE }.mapNotNull(::number)

/**
 * A phone number as matching compares it: its ASCII digits, and the provider's normalized form when it gave one; and as
 * removal does, [dialed]: as written but for whitespace, dashes, brackets, and dots.
 */
internal class Number(val digits: String, val normalized: String?, val dialed: String)

private fun number(row: DataRow) = row.text(Phone.NUMBER)?.let {
    Number(it.filter { c -> c in '0'..'9' }, row.text(Phone.NORMALIZED_NUMBER), it.filterNot { c -> c.isWhitespace() || c in "-()." })
}

/** The normalized forms when both have one, else the same digits, at least one. */
private fun samePhone(a: Number, b: Number) =
    if (a.normalized != null && b.normalized != null) a.normalized == b.normalized else a.digits.isNotEmpty() && a.digits == b.digits

/**
 * The name Android shows, when built from a name row: trimmed, each whitespace run one space, in lower case, accents
 * kept.
 */
private fun name(contact: ReadContact): String? =
    contact.row.displayName?.takeIf { contact.row.nameFromNameRow }?.trim()?.replace(WHITESPACE, " ")?.lowercase(Locale.ROOT)?.ifEmpty { null }

private val WHITESPACE = Regex("\\s+")

/**
 * Whether a copy of [contact] would hold less than it does: a ringtone, send to voicemail, or a default (any value but
 * its name and photo), which a copy never takes; a row the column mapper can't hold (a kind the field table doesn't
 * name, or a second value of a kind that holds one); a value in a column the field table doesn't read for its kind (the
 * columns the provider fills in itself aside); or a value the copy wouldn't show as the original does, its type and
 * label included (a phone type the upload loses, an e-mail address's MOBILE).
 */
private fun copyLoses(contact: ReadContact): Boolean {
    val rows = contact.rows
    val read = keyedGraph(rows)
    return contact.row.customRingtone != null || contact.row.sendToVoicemail || contact.row.hasDefault || read.unmappedRows > 0 || rows.any(::unreadValue) ||
        !shows(dataRows(read.contact.graph, photo = null), rows)
}

/**
 * Remove originals: whether [original] holds a value its [copy] doesn't show: its star, a value of its rows, or an
 * address's formatted line, which the copy's provider built from the same parts. A photo can't be compared, since
 * Android re-encodes every photo it stores: it's held when the record says the write [photoCopied] and the copy has
 * one.
 */
private fun holdsMore(original: ReadContact, copy: ReadContact, photoCopied: Boolean): Boolean =
    copyLoses(original) || (original.row.starred && !copy.row.starred) || !shows(copy.rows, original.rows) ||
        !formattedLines(copy.rows).containsAll(formattedLines(original.rows)) || (hasPhoto(original.rows) && !(photoCopied && hasPhoto(copy.rows)))

/** The formatted lines of the addresses in [rows] (one without parts is already a loss: [unreadValue]). */
private fun formattedLines(rows: List<DataRow>) =
    rows.filter { it.mimeType == StructuredPostal.CONTENT_ITEM_TYPE }.mapNotNull { it.text(StructuredPostal.FORMATTED_ADDRESS) }

private fun hasParts(row: DataRow) = SHOWN_COLUMNS.getValue(StructuredPostal.CONTENT_ITEM_TYPE).any { row.text(it) != null }

private fun hasPhoto(rows: List<DataRow>) = keyedGraph(rows).contact.graph.photo != null

/**
 * Whether [copy] shows every value [original] shows: an e-mail address by the matching rules, a number by them and as
 * it's dialed too (the provider's normalized form leaves out an extension, and digits alone a `+`, letters, or pauses),
 * anything else as it is.
 */
private fun shows(copy: List<DataRow>, original: List<DataRow>): Boolean {
    val held = copy.mapNotNull(::shown)
    return original.mapNotNull(::shown).all { value -> held.any { it.alike(value) } }
}

/**
 * One value a row shows, as compared: its kind, its columns, and, for the kinds whose type the Contacts app shows, its
 * type and its label (shown only on a custom type). The local account's editor (AOSP's `FallbackAccountType`) shows no
 * type for an organization, a website, a nickname, or a SIP address, and writes none for the first and last.
 */
internal class Shown(val kind: String, val values: List<String?>, val type: Int?, val label: String?, private val number: Number?) {
    /**
     * The same value as Android shows it, its type and label included: a number by the matching rules and as dialed.
     */
    fun alike(other: Shown) =
        kind == other.kind && type == other.type && label == other.label &&
            if (number != null && other.number != null) samePhone(number, other.number) && number.dialed == other.number.dialed else values == other.values

    /** The same value, whatever its type and label: an e-mail address or a number by the matching rules. */
    fun matches(other: Shown) =
        kind == other.kind && if (number != null && other.number != null) samePhone(number, other.number) else values == other.values

    /** As [matches], a number dialed alike too: the value itself, whatever its type and label. */
    fun matchesAsDialed(other: Shown) = matches(other) && (number == null || other.number == null || number.dialed == other.number.dialed)
}

internal fun shown(row: DataRow): Shown? {
    val columns = SHOWN_COLUMNS[row.mimeType] ?: return null
    val values = columns.map { row.text(it) }.takeUnless { it.all { value -> value == null } } ?: return null
    val type = row.number(TYPE).takeIf { row.mimeType in TYPED_KINDS }
    return Shown(
        row.mimeType,
        if (row.mimeType == Email.CONTENT_ITEM_TYPE) values.map { it?.let(::email) } else values,
        type,
        row.text(LABEL).takeIf { type == BaseTypes.TYPE_CUSTOM },
        if (row.mimeType == Phone.CONTENT_ITEM_TYPE) number(row) else null,
    )
}

/** A value in a column the field table doesn't read for [row]'s kind, and the provider doesn't fill in. */
private fun unreadValue(row: DataRow): Boolean {
    val read = TABLE_COLUMNS[row.mimeType] ?: return false
    val filled = PROVIDER_FILLED[row.mimeType].orEmpty()
    // The provider fills in an address's formatted line only from its parts: one without parts holds it alone.
    val parts = row.mimeType != StructuredPostal.CONTENT_ITEM_TYPE || hasParts(row)
    return row.values.any { (column, value) ->
        column != Data.SYNC1 && column !in read && !(column in filled && parts) && when (value) {
            is ColumnValue.Text -> value.value.isNotBlank()
            is ColumnValue.Integer -> true
            is ColumnValue.Blob -> value.bytes.isNotEmpty()
        }
    }
}

/** Every typed kind keeps its type in `data2` and its label in `data3`. */
private const val TYPE = Data.DATA2
private const val LABEL = Data.DATA3

/** The kinds whose type the field table keeps, and the Contacts app shows. */
private val TYPED_KINDS = setOf(Email.CONTENT_ITEM_TYPE, Phone.CONTENT_ITEM_TYPE, StructuredPostal.CONTENT_ITEM_TYPE, Event.CONTENT_ITEM_TYPE, Relation.CONTENT_ITEM_TYPE)

/** The columns of each field-table kind that show its value (the photo is compared by its record). */
private val SHOWN_COLUMNS = mapOf(
    StructuredName.CONTENT_ITEM_TYPE to (1..9).map { "data$it" },
    Email.CONTENT_ITEM_TYPE to listOf(Email.ADDRESS),
    Phone.CONTENT_ITEM_TYPE to listOf(Phone.NUMBER),
    StructuredPostal.CONTENT_ITEM_TYPE to listOf(
        StructuredPostal.STREET, StructuredPostal.POBOX, StructuredPostal.CITY, StructuredPostal.REGION, StructuredPostal.POSTCODE, StructuredPostal.COUNTRY,
    ),
    Organization.CONTENT_ITEM_TYPE to listOf(Organization.COMPANY, Organization.DEPARTMENT, Organization.TITLE, Organization.JOB_DESCRIPTION),
    Event.CONTENT_ITEM_TYPE to listOf(Event.START_DATE),
    Note.CONTENT_ITEM_TYPE to listOf(Note.NOTE),
    Website.CONTENT_ITEM_TYPE to listOf(Website.URL),
    Nickname.CONTENT_ITEM_TYPE to listOf(Nickname.NAME),
    Relation.CONTENT_ITEM_TYPE to listOf(Relation.NAME),
    SipAddress.CONTENT_ITEM_TYPE to listOf(SipAddress.SIP_ADDRESS),
)

/** The kinds the field table gives one fixed type, whose type and label aren't compared. */
private val FIXED_TYPE_KINDS = setOf(Organization.CONTENT_ITEM_TYPE, Website.CONTENT_ITEM_TYPE, Nickname.CONTENT_ITEM_TYPE, SipAddress.CONTENT_ITEM_TYPE)

/** The columns the field table reads for each kind, its type and label included (a note has neither). */
private val TABLE_COLUMNS = SHOWN_COLUMNS.mapValues { (kind, columns) ->
    columns.toSet() + if (kind in TYPED_KINDS || kind in FIXED_TYPE_KINDS) setOf(TYPE, LABEL) else emptySet()
} + (Photo.CONTENT_ITEM_TYPE to setOf(Photo.PHOTO))

/**
 * The columns the provider fills in by itself on a row written with only the field table's columns (pinned per kind
 * on the emulator by the instrumented `ProviderDeviceContactsTest`): a name's full-name style, a number's normalized
 * form, and an address's formatted line (from its parts). An organization's phonetic name style isn't one: API 37 left
 * it empty, a phonetic name given or not.
 */
internal val PROVIDER_FILLED = mapOf(
    StructuredName.CONTENT_ITEM_TYPE to setOf(StructuredName.FULL_NAME_STYLE),
    Phone.CONTENT_ITEM_TYPE to setOf(Phone.NORMALIZED_NUMBER),
    StructuredPostal.CONTENT_ITEM_TYPE to setOf(StructuredPostal.FORMATTED_ADDRESS),
)
