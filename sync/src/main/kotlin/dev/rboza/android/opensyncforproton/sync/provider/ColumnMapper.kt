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
import dev.rboza.android.opensyncforproton.mapping.AddressType
import dev.rboza.android.opensyncforproton.mapping.ContactGraph
import dev.rboza.android.opensyncforproton.mapping.ContactPhoto
import dev.rboza.android.opensyncforproton.mapping.EmailType
import dev.rboza.android.opensyncforproton.mapping.GraphKeys
import dev.rboza.android.opensyncforproton.mapping.KeyedGraph
import dev.rboza.android.opensyncforproton.mapping.LabeledDate
import dev.rboza.android.opensyncforproton.mapping.Organization as Org
import dev.rboza.android.opensyncforproton.mapping.PartialDate
import dev.rboza.android.opensyncforproton.mapping.PhoneType
import dev.rboza.android.opensyncforproton.mapping.RelationType
import dev.rboza.android.opensyncforproton.mapping.SourceKey
import dev.rboza.android.opensyncforproton.mapping.StructuredName as Name
import dev.rboza.android.opensyncforproton.mapping.TypedAddress
import dev.rboza.android.opensyncforproton.mapping.TypedEmail
import dev.rboza.android.opensyncforproton.mapping.TypedPhone
import dev.rboza.android.opensyncforproton.mapping.TypedRelation
import java.time.LocalDate
import java.util.Locale

/** A provider column's value; a column with no value is left out of its row. `toString` shows no content. */
sealed interface ColumnValue {
    data class Text(val value: String) : ColumnValue {
        override fun toString() = "Text"
    }

    data class Integer(val value: Long) : ColumnValue

    /** Keeps its own copy of the bytes and hands out copies. */
    class Blob(bytes: ByteArray) : ColumnValue {
        private val data = bytes.copyOf()
        val bytes: ByteArray get() = data.copyOf()
        override fun toString() = "Blob(size=${data.size})"
    }
}

/**
 * One `ContactsContract.Data` row: its MIME type and its columns. `toString` shows only the column names. A
 * [ColumnValue.Blob] compares by reference, so two photo rows with the same bytes aren't equal.
 */
data class DataRow(val mimeType: String, val values: Map<String, ColumnValue>) {
    override fun toString() = "DataRow(mimeType=$mimeType, columns=${values.keys})"
}

/**
 * docs/mapping.md § Field table, contact to rows: the data rows that hold [graph] on the phone, name first, then in the
 * table's order. [photo] is the photo as it goes to the provider (the engine's scaled, an import's as the provider
 * stored it), or null for none; the graph's own photo isn't read here. Each row whose value has a key in [keys] holds
 * it in `Data.SYNC1` (D28). The column names and types are ContactsContract's constants, which the compiler inlines, so
 * this runs on the JVM without Android. Pure, so any thread may call it.
 */
fun dataRows(graph: ContactGraph, photo: ByteArray?, keys: GraphKeys = GraphKeys.none(graph)): List<DataRow> = buildList {
    // No FN and no N means no StructuredName row at all. `:mapping` gives no name for phonetic names alone, so any name
    // here came from FN or N.
    if (graph.formattedName != null || graph.name != null) {
        val name = graph.name
        add(
            row(
                StructuredName.CONTENT_ITEM_TYPE,
                StructuredName.DISPLAY_NAME to graph.formattedName,
                StructuredName.GIVEN_NAME to name?.given,
                StructuredName.FAMILY_NAME to name?.family,
                StructuredName.PREFIX to name?.prefix,
                StructuredName.MIDDLE_NAME to name?.middle,
                StructuredName.SUFFIX to name?.suffix,
                StructuredName.PHONETIC_GIVEN_NAME to name?.phoneticGiven,
                StructuredName.PHONETIC_MIDDLE_NAME to name?.phoneticMiddle,
                StructuredName.PHONETIC_FAMILY_NAME to name?.phoneticFamily,
            ).keyed(keys.name),
        )
    }
    graph.emails.forEachIndexed { index, email ->
        val type = when (email.type) {
            EmailType.Home -> Email.TYPE_HOME
            EmailType.Work -> Email.TYPE_WORK
            EmailType.Other -> Email.TYPE_OTHER
            EmailType.Custom -> Email.TYPE_CUSTOM
        }
        add(row(Email.CONTENT_ITEM_TYPE, Email.ADDRESS to email.value, Email.TYPE to type, Email.LABEL to email.customLabel).keyed(keys.emails[index]))
    }
    graph.phones.forEachIndexed { index, phone ->
        val type = when (phone.type) {
            PhoneType.Home -> Phone.TYPE_HOME
            PhoneType.Mobile -> Phone.TYPE_MOBILE
            PhoneType.Work -> Phone.TYPE_WORK
            PhoneType.FaxWork -> Phone.TYPE_FAX_WORK
            PhoneType.FaxHome -> Phone.TYPE_FAX_HOME
            PhoneType.Pager -> Phone.TYPE_PAGER
            PhoneType.Other -> Phone.TYPE_OTHER
            PhoneType.Main -> Phone.TYPE_MAIN
            PhoneType.Custom -> Phone.TYPE_CUSTOM
        }
        add(row(Phone.CONTENT_ITEM_TYPE, Phone.NUMBER to phone.value, Phone.TYPE to type, Phone.LABEL to phone.customLabel).keyed(keys.phones[index]))
    }
    graph.addresses.forEachIndexed { index, address ->
        val type = when (address.type) {
            AddressType.Home -> StructuredPostal.TYPE_HOME
            AddressType.Work -> StructuredPostal.TYPE_WORK
            AddressType.Other -> StructuredPostal.TYPE_OTHER
        }
        // No FORMATTED_ADDRESS: the provider composes it from the parts.
        add(
            row(
                StructuredPostal.CONTENT_ITEM_TYPE,
                StructuredPostal.STREET to address.street,
                StructuredPostal.POBOX to address.poBox,
                StructuredPostal.CITY to address.city,
                StructuredPostal.REGION to address.region,
                StructuredPostal.POSTCODE to address.postcode,
                StructuredPostal.COUNTRY to address.country,
                StructuredPostal.TYPE to type,
            ).keyed(keys.addresses[index]),
        )
    }
    graph.organization?.let { org ->
        add(
            row(
                Organization.CONTENT_ITEM_TYPE,
                Organization.COMPANY to org.company,
                Organization.DEPARTMENT to org.units.takeIf { it.isNotEmpty() }?.joinToString(";"),
                Organization.TITLE to org.title,
                Organization.JOB_DESCRIPTION to org.role,
                Organization.TYPE to Organization.TYPE_WORK,
            ).keyed(keys.organization),
        )
    }
    photo?.let { add(DataRow(Photo.CONTENT_ITEM_TYPE, mapOf(Photo.PHOTO to ColumnValue.Blob(it))).keyed(keys.photo)) }
    graph.birthday?.let { add(event(it, Event.TYPE_BIRTHDAY).keyed(keys.birthday)) }
    graph.anniversary?.let { add(event(it, Event.TYPE_ANNIVERSARY).keyed(keys.anniversary)) }
    // Android shows a label only on a custom event, so an unlabelled X-ABDATE is OTHER.
    graph.customDates.forEachIndexed { index, date ->
        add(event(date.date, if (date.label == null) Event.TYPE_OTHER else Event.TYPE_CUSTOM, date.label).keyed(keys.customDates[index]))
    }
    graph.note?.let { add(row(Note.CONTENT_ITEM_TYPE, Note.NOTE to it).keyed(keys.note)) }
    graph.urls.forEachIndexed { index, url -> add(row(Website.CONTENT_ITEM_TYPE, Website.URL to url, Website.TYPE to Website.TYPE_OTHER).keyed(keys.urls[index])) }
    graph.nickname?.let { add(row(Nickname.CONTENT_ITEM_TYPE, Nickname.NAME to it, Nickname.TYPE to Nickname.TYPE_DEFAULT).keyed(keys.nickname)) }
    graph.relations.forEachIndexed { index, relation ->
        add(
            row(
                Relation.CONTENT_ITEM_TYPE,
                Relation.NAME to relation.name,
                Relation.TYPE to relationType(relation.type),
                Relation.LABEL to relation.customLabel,
            ).keyed(keys.relations[index]),
        )
    }
    graph.sipAddresses.forEachIndexed { index, sip ->
        add(row(SipAddress.CONTENT_ITEM_TYPE, SipAddress.SIP_ADDRESS to sip, SipAddress.TYPE to SipAddress.TYPE_OTHER).keyed(keys.sipAddresses[index]))
    }
}

/** The row with [key] in `Data.SYNC1`, or as it is without one. */
private fun DataRow.keyed(key: SourceKey?) = if (key == null) this else copy(values = values + (Data.SYNC1 to ColumnValue.Text(key.text)))

/** An Event row: `YYYY-MM-DD`, or `--MM-DD` without a year, in ASCII digits whatever the locale. */
private fun event(date: PartialDate, type: Int, label: String? = null): DataRow {
    val start = date.year?.let { "%04d-%02d-%02d".format(Locale.ROOT, it, date.month, date.day) }
        ?: "--%02d-%02d".format(Locale.ROOT, date.month, date.day)
    return row(Event.CONTENT_ITEM_TYPE, Event.START_DATE to start, Event.TYPE to type, Event.LABEL to label)
}

private fun relationType(type: RelationType): Int = when (type) {
    RelationType.Assistant -> Relation.TYPE_ASSISTANT
    RelationType.Brother -> Relation.TYPE_BROTHER
    RelationType.Child -> Relation.TYPE_CHILD
    RelationType.DomesticPartner -> Relation.TYPE_DOMESTIC_PARTNER
    RelationType.Father -> Relation.TYPE_FATHER
    RelationType.Friend -> Relation.TYPE_FRIEND
    RelationType.Manager -> Relation.TYPE_MANAGER
    RelationType.Mother -> Relation.TYPE_MOTHER
    RelationType.Parent -> Relation.TYPE_PARENT
    RelationType.Partner -> Relation.TYPE_PARTNER
    RelationType.ReferredBy -> Relation.TYPE_REFERRED_BY
    RelationType.Relative -> Relation.TYPE_RELATIVE
    RelationType.Sister -> Relation.TYPE_SISTER
    RelationType.Spouse -> Relation.TYPE_SPOUSE
    RelationType.Custom -> Relation.TYPE_CUSTOM
}

/** A row of text and integer columns; a null value leaves its column out. */
private fun row(mimeType: String, vararg columns: Pair<String, Any?>) = DataRow(
    mimeType,
    columns.mapNotNull { (column, value) ->
        when (value) {
            null -> null
            is String -> column to ColumnValue.Text(value)
            is Int -> column to ColumnValue.Integer(value.toLong())
            else -> error("not a column value")
        }
    }.toMap(),
)


/**
 * A contact read back from its data rows (`toString` shows no content). [unmappedRows] counts the rows its graph can't
 * hold: a second row of a kind that holds one value, an event whose date doesn't read, or a row of a kind the field
 * table doesn't name.
 */
data class RowsRead(val contact: KeyedGraph, val unmappedRows: Int = 0)

/**
 * docs/mapping.md § Field table, rows to contact: the contact [rows] hold (one raw contact's, in row-ID order), with
 * each value's key from its `Data.SYNC1`, so contact → rows → contact gives the same graph. Types follow
 * docs/mapping.md § Types from Android's side. A blank value and a column the table doesn't name aren't read; a row of
 * another kind is counted as unmapped. A name row whose key names an `FN` but no `N` reads no name parts: the provider
 * split them from the display name. One that names an `N` but no `FN` reads no display name: the provider built it from
 * the parts. Pure, so any thread may call it.
 */
fun keyedGraph(rows: List<DataRow>): RowsRead {
    var unmapped = 0
    val filled = mutableSetOf<String>()

    /** The first value of a kind that holds one maps; each later one is counted. */
    fun first(kind: String) = filled.add(kind).also { if (!it) unmapped++ }

    var formattedName: String? = null
    var name: Name? = null
    var nameKey: SourceKey? = null
    var organization: Pair<Org, SourceKey?>? = null
    var photo: Pair<ContactPhoto, SourceKey?>? = null
    var birthday: Pair<PartialDate, SourceKey?>? = null
    var anniversary: Pair<PartialDate, SourceKey?>? = null
    var note: Pair<String, SourceKey?>? = null
    var nickname: Pair<String, SourceKey?>? = null
    val emails = mutableListOf<Pair<TypedEmail, SourceKey?>>()
    val phones = mutableListOf<Pair<TypedPhone, SourceKey?>>()
    val addresses = mutableListOf<Pair<TypedAddress, SourceKey?>>()
    val customDates = mutableListOf<Pair<LabeledDate, SourceKey?>>()
    val urls = mutableListOf<Pair<String, SourceKey?>>()
    val relations = mutableListOf<Pair<TypedRelation, SourceKey?>>()
    val sipAddresses = mutableListOf<Pair<String, SourceKey?>>()

    for (row in rows) {
        val key = SourceKey.parse(row.text(Data.SYNC1))
        when (row.mimeType) {
            StructuredName.CONTENT_ITEM_TYPE -> {
                val names = key?.properties?.map { it.substringBefore('.') }.orEmpty()
                // Only what the provider filled in is hidden: the parts it split from FN, or the display name it
                // built from N.
                val display = row.text(StructuredName.DISPLAY_NAME).takeUnless { "N" in names && "FN" !in names }
                val parts = !("FN" in names && "N" !in names)
                val read = Name(
                    given = row.text(StructuredName.GIVEN_NAME).takeIf { parts },
                    family = row.text(StructuredName.FAMILY_NAME).takeIf { parts },
                    prefix = row.text(StructuredName.PREFIX).takeIf { parts },
                    middle = row.text(StructuredName.MIDDLE_NAME).takeIf { parts },
                    suffix = row.text(StructuredName.SUFFIX).takeIf { parts },
                    phoneticGiven = row.text(StructuredName.PHONETIC_GIVEN_NAME),
                    phoneticMiddle = row.text(StructuredName.PHONETIC_MIDDLE_NAME),
                    phoneticFamily = row.text(StructuredName.PHONETIC_FAMILY_NAME),
                ).takeIf { it != Name(given = null, family = null) }
                if ((display != null || read != null) && first(row.mimeType)) {
                    formattedName = display
                    name = read
                    nameKey = key
                }
            }
            Email.CONTENT_ITEM_TYPE -> row.text(Email.ADDRESS)?.let { address ->
                val label = row.text(Email.LABEL)
                val email = when (row.number(Email.TYPE)) {
                    Email.TYPE_HOME -> TypedEmail(address, EmailType.Home)
                    Email.TYPE_WORK -> TypedEmail(address, EmailType.Work)
                    Email.TYPE_CUSTOM if label != null -> TypedEmail(address, EmailType.Custom, label)
                    // OTHER, MOBILE (which no vCard type names), and anything else upload without a TYPE.
                    else -> TypedEmail(address, EmailType.Other)
                }
                emails += email to key
            }
            Phone.CONTENT_ITEM_TYPE -> row.text(Phone.NUMBER)?.let { number ->
                val label = row.text(Phone.LABEL)
                val type = when (row.number(Phone.TYPE)) {
                    Phone.TYPE_HOME -> PhoneType.Home
                    Phone.TYPE_MOBILE -> PhoneType.Mobile
                    Phone.TYPE_WORK -> PhoneType.Work
                    Phone.TYPE_FAX_WORK -> PhoneType.FaxWork
                    Phone.TYPE_FAX_HOME -> PhoneType.FaxHome
                    Phone.TYPE_PAGER -> PhoneType.Pager
                    Phone.TYPE_MAIN -> PhoneType.Main
                    Phone.TYPE_CUSTOM if label != null -> PhoneType.Custom
                    // OTHER, types 8–11 and 13–20, and anything else upload without a TYPE.
                    else -> PhoneType.Other
                }
                phones += TypedPhone(number, type, label.takeIf { type == PhoneType.Custom }) to key
            }
            StructuredPostal.CONTENT_ITEM_TYPE -> {
                val type = when (row.number(StructuredPostal.TYPE)) {
                    StructuredPostal.TYPE_HOME -> AddressType.Home
                    StructuredPostal.TYPE_WORK -> AddressType.Work
                    else -> AddressType.Other
                }
                val address = TypedAddress(
                    street = row.text(StructuredPostal.STREET),
                    poBox = row.text(StructuredPostal.POBOX),
                    city = row.text(StructuredPostal.CITY),
                    region = row.text(StructuredPostal.REGION),
                    postcode = row.text(StructuredPostal.POSTCODE),
                    country = row.text(StructuredPostal.COUNTRY),
                    type = type,
                )
                if (address != TypedAddress(null, null, null, null, null, null, type)) addresses += address to key
            }
            Organization.CONTENT_ITEM_TYPE -> {
                val org = Org(
                    company = row.text(Organization.COMPANY),
                    units = row.text(Organization.DEPARTMENT)?.split(';')?.filter { it.isNotBlank() }.orEmpty(),
                    title = row.text(Organization.TITLE),
                    role = row.text(Organization.JOB_DESCRIPTION),
                )
                if (org != Org(company = null) && first(row.mimeType)) organization = org to key
            }
            Photo.CONTENT_ITEM_TYPE -> {
                val bytes = (row.values[Photo.PHOTO] as? ColumnValue.Blob)?.bytes?.takeIf { it.isNotEmpty() }
                if (bytes != null && first(row.mimeType)) photo = ContactPhoto(bytes, mediaType = null) to key
            }
            Event.CONTENT_ITEM_TYPE -> row.text(Event.START_DATE)?.let { start ->
                val date = eventDate(start) ?: return@let unmapped++
                when (row.number(Event.TYPE)) {
                    Event.TYPE_BIRTHDAY -> if (first("birthday")) birthday = date to key
                    Event.TYPE_ANNIVERSARY -> if (first("anniversary")) anniversary = date to key
                    // Android shows a label only on a custom event.
                    Event.TYPE_CUSTOM -> customDates += LabeledDate(date, row.text(Event.LABEL)) to key
                    else -> customDates += LabeledDate(date, null) to key
                }
            }
            Note.CONTENT_ITEM_TYPE -> row.text(Note.NOTE)?.let { if (first(row.mimeType)) note = it to key }
            Website.CONTENT_ITEM_TYPE -> row.text(Website.URL)?.let { urls += it to key }
            Nickname.CONTENT_ITEM_TYPE -> row.text(Nickname.NAME)?.let { if (first(row.mimeType)) nickname = it to key }
            Relation.CONTENT_ITEM_TYPE -> row.text(Relation.NAME)?.let { relationName ->
                val type = RELATION_TYPES[row.number(Relation.TYPE)]
                relations += (if (type == null) TypedRelation(relationName, RelationType.Custom, row.text(Relation.LABEL)) else TypedRelation(relationName, type)) to key
            }
            SipAddress.CONTENT_ITEM_TYPE -> row.text(SipAddress.SIP_ADDRESS)?.let { sipAddresses += it to key }
            // A kind the field table doesn't name (an IM, a group membership, another app's own): a write or an upload
            // would lose it.
            else -> unmapped++
        }
    }

    val graph = ContactGraph(
        uid = null,
        formattedName = formattedName,
        name = name,
        emails = emails.map { it.first },
        phones = phones.map { it.first },
        addresses = addresses.map { it.first },
        organization = organization?.first,
        photo = photo?.first,
        birthday = birthday?.first,
        anniversary = anniversary?.first,
        customDates = customDates.map { it.first },
        note = note?.first,
        urls = urls.map { it.first },
        nickname = nickname?.first,
        relations = relations.map { it.first },
        sipAddresses = sipAddresses.map { it.first },
    )
    val keys = GraphKeys(
        name = nameKey,
        emails = emails.map { it.second },
        phones = phones.map { it.second },
        addresses = addresses.map { it.second },
        organization = organization?.second,
        photo = photo?.second,
        birthday = birthday?.second,
        anniversary = anniversary?.second,
        customDates = customDates.map { it.second },
        note = note?.second,
        urls = urls.map { it.second },
        nickname = nickname?.second,
        relations = relations.map { it.second },
        sipAddresses = sipAddresses.map { it.second },
    )
    return RowsRead(KeyedGraph(graph, keys), unmapped)
}

/**
 * Step 5's settled check (docs/sync.md § The plan): whether [phone] (read back from a row) shows what [remote] would,
 * the photo aside (it's compared by its baseline). The remote is read as the phone would show it, through the rows it
 * would be written as, so a round trip's losses count on both sides. List values compare in any order; text compares
 * with a CR or CRLF as LF, and SIP addresses trimmed, as a vCard and the mapper read them back. The `UID` isn't shown.
 */
fun shownEqual(phone: ContactGraph, remote: KeyedGraph): Boolean =
    shown(phone) == shown(keyedGraph(dataRows(remote.graph, photo = null, keys = remote.keys)).contact.graph)

private fun shown(graph: ContactGraph): List<Any?> = with(graph) {
    listOf(
        formattedName?.let(::lf),
        name?.run {
            Name(
                given?.let(::lf), family?.let(::lf), prefix?.let(::lf), middle?.let(::lf), suffix?.let(::lf),
                phoneticGiven?.let(::lf), phoneticMiddle?.let(::lf), phoneticFamily?.let(::lf),
            )
        },
        bag(emails.map { it.copy(value = lf(it.value), customLabel = it.customLabel?.let(::lf)) }),
        bag(phones.map { it.copy(value = lf(it.value), customLabel = it.customLabel?.let(::lf)) }),
        bag(
            addresses.map {
                it.copy(
                    street = it.street?.let(::lf), poBox = it.poBox?.let(::lf), city = it.city?.let(::lf), region = it.region?.let(::lf),
                    postcode = it.postcode?.let(::lf), country = it.country?.let(::lf),
                )
            },
        ),
        organization?.run { Org(company?.let(::lf), units.map(::lf), title?.let(::lf), role?.let(::lf)) },
        birthday,
        anniversary,
        bag(customDates.map { it.copy(label = it.label?.let(::lf)) }),
        note?.let(::lf),
        bag(urls.map(::lf)),
        nickname?.let(::lf),
        bag(relations.map { it.copy(name = lf(it.name), customLabel = it.customLabel?.let(::lf)) }),
        bag(sipAddresses.map { lf(it).trim() }),
    )
}

/** Line breaks as a vCard reads them back. */
private fun lf(text: String) = text.replace("\r\n", "\n").replace('\r', '\n')

/** A list's values without their order. */
private fun <T> bag(values: List<T>) = values.groupingBy { it }.eachCount()

/** A text column's value, or null when it's missing or blank. */
internal fun DataRow.text(column: String): String? = (values[column] as? ColumnValue.Text)?.value?.ifBlank { null }

/** A type column: the provider stores numbers in text columns, so either form reads. */
internal fun DataRow.number(column: String): Int? = when (val value = values[column]) {
    is ColumnValue.Integer -> value.value.toInt()
    is ColumnValue.Text -> value.value.toIntOrNull()
    else -> null
}

/** `YYYY-MM-DD`, or `--MM-DD` without a year, as the app writes them; year 0 is no year. */
private fun eventDate(text: String): PartialDate? {
    val match = EVENT_DATE.matchEntire(text) ?: return null
    val (year, month, day) = match.destructured
    val known = year.ifEmpty { null }?.toInt()?.takeIf { it != 0 }
    // 2000 is a leap year, so --02-29 stays.
    if (runCatching { LocalDate.of(known ?: 2000, month.toInt(), day.toInt()) }.isFailure) return null
    return PartialDate(month.toInt(), day.toInt(), known)
}

private val EVENT_DATE = Regex("(?:([0-9]{4})|-)-([0-9]{2})-([0-9]{2})")

private val RELATION_TYPES = RelationType.entries.filter { it != RelationType.Custom }.associateBy(::relationType)
