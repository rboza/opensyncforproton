package dev.rboza.android.opensyncforproton.mapping

import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Random
import java.util.UUID

/** Where a card's plaintext came from (docs/protocol.md § Contact cards). */
enum class CardPlacement { CLEAR, SIGNED, ENCRYPTED }

/** [vcard] is a decrypted card, so `toString` shows only its placement and length. */
data class CardFragment(val placement: CardPlacement, val vcard: String) {
    override fun toString() = "CardFragment(placement=$placement, length=${vcard.length})"
}

/** One Proton contact as Android shows it (docs/mapping.md § Field table). */
data class ContactGraph(
    val uid: String?,
    val formattedName: String?,
    val name: StructuredName? = null,
    val emails: List<TypedEmail> = emptyList(),
    val phones: List<TypedPhone> = emptyList(),
    val addresses: List<TypedAddress> = emptyList(),
    val organization: Organization? = null,
    val photo: ContactPhoto? = null,
    val birthday: PartialDate? = null,
    val anniversary: PartialDate? = null,
    val customDates: List<LabeledDate> = emptyList(),
    val note: String? = null,
    val urls: List<String> = emptyList(),
    val nickname: String? = null,
    val relations: List<TypedRelation> = emptyList(),
    val sipAddresses: List<String> = emptyList(),
) {
    override fun toString() = redacted(
        "ContactGraph",
        "uid" to uid, "formattedName" to formattedName, "name" to name, "emails" to emails, "phones" to phones,
        "addresses" to addresses, "organization" to organization, "photo" to photo, "birthday" to birthday,
        "anniversary" to anniversary, "customDates" to customDates, "note" to note, "urls" to urls,
        "nickname" to nickname, "relations" to relations, "sipAddresses" to sipAddresses,
    )
}

data class StructuredName(
    val given: String?,
    val family: String?,
    val prefix: String? = null,
    val middle: String? = null,
    val suffix: String? = null,
    val phoneticGiven: String? = null,
    val phoneticMiddle: String? = null,
    val phoneticFamily: String? = null,
) {
    override fun toString() = redacted(
        "StructuredName",
        "given" to given, "family" to family, "prefix" to prefix, "middle" to middle, "suffix" to suffix,
        "phoneticGiven" to phoneticGiven, "phoneticMiddle" to phoneticMiddle, "phoneticFamily" to phoneticFamily,
    )
}

/** [units] are the `ORG` values after the company, kept apart; `:sync` joins them for Android. */
data class Organization(
    val company: String?,
    val units: List<String> = emptyList(),
    val title: String? = null,
    val role: String? = null,
) {
    override fun toString() = redacted("Organization", "company" to company, "units" to units, "title" to title, "role" to role)
}

/** An `EMAIL`'s type (docs/mapping.md § Types); [Custom] carries its label (docs/mapping.md § Custom labels). */
enum class EmailType { Home, Work, Other, Custom }

data class TypedEmail(val value: String, val type: EmailType, val customLabel: String? = null) {
    override fun toString() = redacted("TypedEmail", "value" to value, "type" to type, "customLabel" to customLabel)
}

enum class PhoneType { Home, Mobile, Work, FaxWork, FaxHome, Pager, Other, Main, Custom }

data class TypedPhone(val value: String, val type: PhoneType, val customLabel: String? = null) {
    override fun toString() = redacted("TypedPhone", "value" to value, "type" to type, "customLabel" to customLabel)
}

enum class AddressType { Home, Work, Other }

/** An `ADR` without its extended-address part, which Android doesn't show. */
data class TypedAddress(
    val street: String?,
    val poBox: String?,
    val city: String?,
    val region: String?,
    val postcode: String?,
    val country: String?,
    val type: AddressType,
) {
    override fun toString() = redacted(
        "TypedAddress",
        "street" to street, "poBox" to poBox, "city" to city, "region" to region, "postcode" to postcode,
        "country" to country, "type" to type,
    )
}

/** Android's Relation types; [Custom] carries its label, if there is one (docs/mapping.md § Custom labels). */
enum class RelationType {
    Assistant, Brother, Child, DomesticPartner, Father, Friend, Manager,
    Mother, Parent, Partner, ReferredBy, Relative, Sister, Spouse, Custom,
}

data class TypedRelation(val name: String, val type: RelationType, val customLabel: String? = null) {
    override fun toString() = redacted("TypedRelation", "name" to name, "type" to type, "customLabel" to customLabel)
}

/**
 * An embedded photo, encoded as the vCard holds it (`:sync` decodes and scales it). It keeps its own
 * copy of the bytes, hands out copies, and its `toString` shows only the size, never the content.
 */
class ContactPhoto(bytes: ByteArray, val mediaType: String?) {
    private val data = bytes.copyOf()

    val bytes: ByteArray get() = data.copyOf()

    override fun equals(other: Any?) = other is ContactPhoto && data.contentEquals(other.data) && mediaType == other.mediaType

    override fun hashCode() = 31 * data.contentHashCode() + mediaType.hashCode()

    override fun toString() = "ContactPhoto(size=${data.size})"
}

/** A date Android's Event row can hold: always a month and a day, the year only when known. */
data class PartialDate(val month: Int, val day: Int, val year: Int?) {
    override fun toString() = redacted("PartialDate", "month" to month, "day" to day, "year" to year)
}

data class LabeledDate(val date: PartialDate, val label: String?) {
    override fun toString() = redacted("LabeledDate", "date" to date, "label" to label)
}

/**
 * The remote properties one mapped value came from (D28; docs/mapping.md § Keys), each by its key: the property's
 * name in upper case, a dot, 16 hex digits, and `~2`, `~3`, … for repeats. [text] is what a data row's `Data.SYNC1`
 * holds. Keys are made from contact content, so `toString` shows only how many there are.
 */
data class SourceKey(val properties: List<String>) {
    val text: String get() = properties.joinToString(" ")

    override fun toString() = "SourceKey(properties=${properties.size})"

    companion object {
        private val PROPERTY_KEY = Regex("[A-Z0-9-]+\\.[0-9a-f]{16}(~[0-9]+)?")

        /** The key a row's `Data.SYNC1` holds; null when there's none, or when the text isn't a key. */
        fun parse(text: String?): SourceKey? {
            val properties = text?.split(' ') ?: return null
            return if (properties.all { PROPERTY_KEY.matches(it) }) SourceKey(properties) else null
        }
    }
}

/**
 * Which remote properties each value of a [ContactGraph] came from: each list lines up with the graph's own, and null
 * means no key (a value added on the phone, say). `toString` shows no key.
 */
data class GraphKeys(
    val name: SourceKey? = null,
    val emails: List<SourceKey?> = emptyList(),
    val phones: List<SourceKey?> = emptyList(),
    val addresses: List<SourceKey?> = emptyList(),
    val organization: SourceKey? = null,
    val photo: SourceKey? = null,
    val birthday: SourceKey? = null,
    val anniversary: SourceKey? = null,
    val customDates: List<SourceKey?> = emptyList(),
    val note: SourceKey? = null,
    val urls: List<SourceKey?> = emptyList(),
    val nickname: SourceKey? = null,
    val relations: List<SourceKey?> = emptyList(),
    val sipAddresses: List<SourceKey?> = emptyList(),
) {
    override fun toString() = redacted(
        "GraphKeys",
        "name" to name, "emails" to emails, "phones" to phones, "addresses" to addresses, "organization" to organization,
        "photo" to photo, "birthday" to birthday, "anniversary" to anniversary, "customDates" to customDates, "note" to note,
        "urls" to urls, "nickname" to nickname, "relations" to relations, "sipAddresses" to sipAddresses,
    )

    companion object {
        /** No value of [graph] has a key. */
        fun none(graph: ContactGraph) = GraphKeys(
            emails = graph.emails.map { null },
            phones = graph.phones.map { null },
            addresses = graph.addresses.map { null },
            customDates = graph.customDates.map { null },
            urls = graph.urls.map { null },
            relations = graph.relations.map { null },
            sipAddresses = graph.sipAddresses.map { null },
        )
    }
}

/** A contact's values and the remote properties they came from; [keys] must line up with [graph]. */
data class KeyedGraph(val graph: ContactGraph, val keys: GraphKeys = GraphKeys.none(graph)) {
    init {
        val lists = listOf(
            graph.emails to keys.emails, graph.phones to keys.phones, graph.addresses to keys.addresses,
            graph.customDates to keys.customDates, graph.urls to keys.urls, graph.relations to keys.relations,
            graph.sipAddresses to keys.sipAddresses,
        )
        // A key only for a value that is there; the name's row exists with an FN or an N.
        val singles = listOf(
            (graph.formattedName ?: graph.name) to keys.name, graph.organization to keys.organization, graph.photo to keys.photo,
            graph.birthday to keys.birthday, graph.anniversary to keys.anniversary, graph.note to keys.note, graph.nickname to keys.nickname,
        )
        require(lists.all { (values, keys) -> values.size == keys.size } && singles.all { (value, key) -> key == null || value != null }) {
            "keys don't line up with the graph"
        }
    }
}

/**
 * The re-fetched remote contact with the phone's edits (docs/mapping.md § Writing): each card's lines, untouched ones
 * exactly as written, edited ones in their place, and new ones last in the card the card table puts them in (a card the
 * contact didn't have comes last, as vCard 4.0). [changed] is false when the upload would leave the remote as it is;
 * it can be true while the phone shows what the remote does, since a remote without a name gains an `FN`
 * (docs/mapping.md § `FN` on upload). Lines hold contact content, so `toString` shows only counts.
 */
class ContactPatch(val cards: List<PatchedCard>, val changed: Boolean) {
    override fun toString() = "ContactPatch(cards=${cards.size}, changed=$changed)"

    /** The cards to upload, in order; a card left with no lines isn't sent. */
    fun fragments(): List<CardFragment> = cards.filter { it.lines.isNotEmpty() }.map { CardFragment(it.placement, it.vcard) }
}

/**
 * What an upload does with the photo (docs/sync.md § Photos): [Keep] Proton's, [Remove] it, or [Replace] it with
 * the phone's, already scaled and re-encoded as JPEG by `:sync`.
 */
sealed interface PhotoUpload {
    data object Keep : PhotoUpload

    data object Remove : PhotoUpload

    /** Keeps its own copy of the bytes; `toString` shows only the size. */
    class Replace(jpeg: ByteArray) : PhotoUpload {
        private val data = jpeg.copyOf()
        val jpeg: ByteArray get() = data.copyOf()

        override fun toString() = "PhotoUpload.Replace(size=${data.size})"
    }
}

/** A card's lines after the patch; [version] is its `VERSION`. */
data class PatchedCard(val placement: CardPlacement, val version: String, val lines: List<PatchedLine>) {
    /** The card's text: `VERSION` first, then each line unfolded, with CRLF line breaks. */
    val vcard: String get() = (listOf("BEGIN:VCARD", "VERSION:$version") + lines.map { it.text } + "END:VCARD").joinToString("\r\n", postfix = "\r\n")

    override fun toString() = "PatchedCard(placement=$placement, lines=${lines.size})"
}

/** A property's line, unfolded; [edited] when the patch rewrote or added it. */
data class PatchedLine(val text: String, val edited: Boolean) {
    override fun toString() = "PatchedLine(edited=$edited)"
}

/** A new contact's `UID`: a version 4 UUID from [random] as a URN, fresh each time. */
fun newContactUid(random: Random = SecureRandom()): String {
    val bytes = ByteArray(16).also(random::nextBytes)
    bytes[6] = (bytes[6].toInt() and 0x0f or 0x40).toByte()
    bytes[8] = (bytes[8].toInt() and 0x3f or 0x80).toByte()
    val buffer = ByteBuffer.wrap(bytes)
    return "urn:uuid:" + UUID(buffer.long, buffer.long)
}

/**
 * [MALFORMED_INPUT]: a card isn't exactly one vCard with a `VERSION`, or ez-vcard skipped or couldn't decode a line.
 * [NO_CONTENT]: the cards hold nothing but `VERSION`, `PRODID`, and `UID`.
 * [CONFLICTING_UID]: the clear and signed cards together hold more than one distinct non-blank `UID` (trimmed); an
 * encrypted card's is ignored.
 * [UNKEYED_PROPERTY]: a value the phone changed came from a property the patch can't find the line of.
 * [UNWRITABLE_LABEL]: a custom label the phone changed can't be written in its line (a carriage return; in vCard 2.1,
 * more).
 * [NO_NAME_OR_EMAIL]: an upload with no name and no e-mail to give its `FN` ("add a name or e-mail").
 * [UNENCRYPTED_PROPERTY]: the phone changed a value whose property is in a clear (type 0) card, where it would upload
 * unencrypted; re-saving the contact in Proton's web app moves it to the encrypted card.
 */
enum class MappingIssueCode { MALFORMED_INPUT, NO_CONTENT, CONFLICTING_UID, UNKEYED_PROPERTY, UNWRITABLE_LABEL, NO_NAME_OR_EMAIL, UNENCRYPTED_PROPERTY }

/** A code only: never parser text or contact content. */
data class MappingIssue(val code: MappingIssueCode)

sealed interface MappingResult<out T> {
    data class Success<T>(val value: T) : MappingResult<T>
    data class Invalid(val issues: List<MappingIssue>) : MappingResult<Nothing>
}

/**
 * A Proton contact's cards to and from what Android shows. Each call is a pure function of its arguments, with no I/O
 * and no mutable state of its own (ez-vcard's caches are synchronized), so any thread may call it. Cards or edits the
 * mapper can't handle come back as [MappingResult.Invalid] with the codes each method names.
 */
interface ContactMapper {
    /**
     * The contact the [cards] make, with each value's key (docs/mapping.md § Reading). Fails with `MALFORMED_INPUT`,
     * `NO_CONTENT`, or `CONFLICTING_UID`.
     */
    fun merge(cards: List<CardFragment>): MappingResult<KeyedGraph>

    /**
     * The [remote] cards with the [phone]'s edits and the [photo] `:sync` decided by its baseline (docs/mapping.md
     * § Writing). Fails as [merge] does, or with `UNKEYED_PROPERTY`, `UNWRITABLE_LABEL`, `UNENCRYPTED_PROPERTY`, or
     * `NO_NAME_OR_EMAIL`.
     */
    fun patch(phone: KeyedGraph, remote: List<CardFragment>, photo: PhotoUpload = PhotoUpload.Keep): MappingResult<ContactPatch>

    /**
     * A new contact's cards (docs/mapping.md § Creating a contact), with [uid] and, when the phone has one, its
     * [photo] (a JPEG). Fails with `NO_NAME_OR_EMAIL` or `UNWRITABLE_LABEL`.
     */
    fun create(phone: ContactGraph, uid: String, versionName: String, photo: ByteArray? = null): MappingResult<List<CardFragment>>
}

/**
 * The `toString` of every type that holds contact content (docs/architecture.md § Diagnostics): the fields
 * that are present, list sizes, and enum values, never the content itself.
 */
internal fun redacted(type: String, vararg fields: Pair<String, Any?>): String =
    fields.mapNotNull { (name, value) ->
        when (value) {
            null -> null
            is Collection<*> -> if (value.isEmpty()) null else "$name=${value.size}"
            is Enum<*> -> "$name=$value"
            else -> name
        }
    }.joinToString(", ", prefix = "$type(", postfix = ")")
