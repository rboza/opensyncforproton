package dev.rboza.android.opensyncforproton.mapping

import ezvcard.Ezvcard
import ezvcard.VCard
import ezvcard.VCardVersion
import ezvcard.io.ParseWarning
import ezvcard.parameter.Encoding
import ezvcard.parameter.VCardParameters
import ezvcard.property.Address
import ezvcard.property.Organization as Organization_
import ezvcard.property.DateOrTimeProperty
import ezvcard.property.Photo
import ezvcard.property.ProductId
import ezvcard.property.RawProperty
import ezvcard.property.Related
import ezvcard.property.Role
import ezvcard.property.Title
import ezvcard.property.Uid
import ezvcard.property.VCardProperty
import ezvcard.util.TelUri
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.security.MessageDigest
import java.time.ZoneOffset
import java.util.Base64
import java.util.HexFormat
import java.util.IdentityHashMap

class EzvcardContactMapper : ContactMapper {
    override fun merge(cards: List<CardFragment>): MappingResult<KeyedGraph> = when (val read = read(cards)) {
        is MappingResult.Success -> MappingResult.Success(read.value.keyed)
        is MappingResult.Invalid -> read
    }

    /**
     * The re-fetched [remote] cards with the [phone]'s edits (docs/mapping.md § Writing): its rows paired with the
     * remote's properties by key, else by value, an `FN` as docs/mapping.md § `FN` on upload says, and the [photo] as
     * `:sync` decided by its baseline (the phone's graph's own photo is never read here).
     */
    override fun patch(phone: KeyedGraph, remote: List<CardFragment>, photo: PhotoUpload): MappingResult<ContactPatch> = when (val read = read(remote)) {
        is MappingResult.Success -> named(phone.graph)?.let {
            val nameRemoved = phone.graph.formattedName.nonBlank() == null && phone.graph.name == null
            ContactPatcher(remote.map { it.placement }, read.value).patch(KeyedGraph(it, phone.keys), nameRemoved, photo)
        } ?: invalid(MappingIssueCode.NO_NAME_OR_EMAIL)
        is MappingResult.Invalid -> read
    }

    /**
     * The [phone]'s cards for a new contact with [uid] (docs/mapping.md § Creating a contact). The signed card holds
     * `VERSION:4.0`, D4's `PRODID` for the app's [versionName], `UID`, `FN` (as [named] gives it), and the e-mails;
     * the encrypted card, when there's anything else, the rest; there's no clear card. The [photo] given (the phone's,
     * scaled) is a new line, last in the encrypted card; the graph's own photo is never written.
     */
    override fun create(phone: ContactGraph, uid: String, versionName: String, photo: ByteArray?): MappingResult<List<CardFragment>> {
        val named = named(phone) ?: return invalid(MappingIssueCode.NO_NAME_OR_EMAIL)
        val nothing = RemoteContact(KeyedGraph(ContactGraph(uid = null, formattedName = null)), emptyList(), emptyList(), emptyMap())
        return when (val patch = ContactPatcher(emptyList(), nothing).patch(KeyedGraph(named), photo = photo?.let(PhotoUpload::Replace) ?: PhotoUpload.Keep)) {
            is MappingResult.Success -> {
                // The UID as it was made, a URN with nothing to escape.
                val identity = listOf(
                    propertyLine(null, "PRODID", emptyList(), escapedText("-//OpenSync for Proton//Contacts $versionName//EN")),
                    propertyLine(null, "UID", emptyList(), uid),
                ).map { PatchedLine(it, true) }
                // FN is always new, so the signed card is always there; nothing else adds a card.
                val cards = patch.value.cards.map { if (it.placement == CardPlacement.SIGNED) it.copy(lines = identity + it.lines) else it }
                MappingResult.Success(cards.map { CardFragment(it.placement, it.vcard) })
            }
            is MappingResult.Invalid -> patch
        }
    }

    /**
     * [graph] with the `FN` every upload carries: the phone's name; else one built from the structured name (from its
     * phonetic name when that's all it has); else the first e-mail. Never a phone number: null when there's none.
     */
    private fun named(graph: ContactGraph): ContactGraph? {
        val built = graph.name?.let {
            listOfNotNull(it.prefix, it.given, it.middle, it.family, it.suffix).joinedNonBlank()
                ?: listOfNotNull(it.phoneticGiven, it.phoneticMiddle, it.phoneticFamily).joinedNonBlank()
        }
        val fn = graph.formattedName.nonBlank() ?: built ?: graph.emails.firstNotNullOfOrNull { it.value.nonBlank() } ?: return null
        return graph.copy(formattedName = fn)
    }

    private fun read(cards: List<CardFragment>): MappingResult<RemoteContact> {
        val parsed = cards.map { parse(it.vcard) ?: return invalid(MappingIssueCode.MALFORMED_INPUT) }
        if (parsed.all { card -> card.properties.all { it is ProductId || it is Uid } }) {
            return invalid(MappingIssueCode.NO_CONTENT)
        }
        // A UID in an encrypted card is ignored, as Proton's web app strips it.
        val uids = parsed.filterIndexed { i, _ -> cards[i].placement != CardPlacement.ENCRYPTED }
            .flatMap { it.getProperties(Uid::class.java) }.mapNotNull { it.value?.trim()?.ifBlank { null } }.distinct()
        if (uids.size > 1) return invalid(MappingIssueCode.CONFLICTING_UID)

        val keys = PropertyKeys(cards, parsed)
        // A field Android shows once takes the first property with a value; the later ones are its hidden parts.
        val fns = parsed.flatMap { it.formattedNames }.filter { it.value.nonBlank() != null }
        val names = parsed.flatMap { it.getProperties(NameComponents::class.java) }.map(::nameComponents).filter { it.first != NO_NAME }
        val phonetics = PHONETIC_NAMES.map { name -> parsed.flatMap { it.getExtendedProperties(name) }.filter { it.value?.let(::unescapedText).nonBlank() != null } }
        val orgs = parsed.flatMap { it.organizations }.filter { org -> org.values.any { it.nonBlank() != null } }
        val titles = parsed.flatMap { it.titles }.filter { it.value.nonBlank() != null }
        val roles = parsed.flatMap { it.roles }.filter { it.value.nonBlank() != null }
        val birthdays = parsed.flatMap { it.birthdays }.mapNotNull { b -> partialDate(b)?.to(b) }
        val anniversaries = parsed.flatMap { it.anniversaries }.mapNotNull { a -> partialDate(a)?.to(a) }
        val notes = parsed.flatMap { it.notes }.filter { it.value.nonBlank() != null }
        val nicknames = parsed.flatMap { it.nicknames }.mapNotNull { n -> n.values.firstNotNullOfOrNull { it.nonBlank() }?.to(n) }

        val fn = fns.firstOrNull()
        val (name, nameProperties) = structuredName(names.firstOrNull(), phonetics.map { it.firstOrNull() }, hasFormattedName = fn != null)
        val emails = parsed.flatMap { it.emails }.mapNotNull { email ->
            val value = email.value?.ifBlank { null } ?: return@mapNotNull null
            val (type, label) = emailType(email.parameters)
            TypedEmail(value, type, label) to keys.of(email)
        }
        val phones = parsed.flatMap { it.telephoneNumbers }.mapNotNull { tel ->
            val value = (tel.text ?: tel.uri?.let(::phoneNumber))?.ifBlank { null } ?: return@mapNotNull null
            val (type, label) = phoneType(tel.parameters)
            TypedPhone(value, type, label) to keys.of(tel)
        }
        val addresses = parsed.flatMap { it.addresses }.mapNotNull { adr -> address(adr)?.to(keys.of(adr)) }
        val (organization, organizationProperties) = organization(orgs.firstOrNull(), titles.firstOrNull(), roles.firstOrNull())
        // The first embedded photo; a URL is never fetched, so it isn't mapped.
        val photo = parsed.firstNotNullOfOrNull { card -> card.photos.firstNotNullOfOrNull { embeddedPhoto(card, it)?.to(keys.of(it)) } }
        val embeddedPhotos = parsed.flatMap { card -> card.photos.filter { embeddedPhoto(card, it) != null } }
        val customDates = parsed.flatMap(::appleDates).map { (date, property) -> date to keys.of(property) }
        val urls = parsed.flatMap { it.urls }.mapNotNull { it.value.nonBlank()?.to(keys.of(it)) }
        val relations = parsed.flatMap { it.relations }.mapNotNull { related ->
            val value = (related.text ?: related.uri).nonBlank() ?: return@mapNotNull null
            val (type, label) = relationType(related)
            TypedRelation(value, type, label) to keys.of(related)
        }
        val sipAddresses = parsed.flatMap { it.getExtendedProperties("X-SIP") }
            .mapNotNull { sip -> sip.value?.let(::unescapedText)?.trim()?.withoutSipScheme()?.trim().nonBlank()?.to(keys.of(sip)) }

        val graph = ContactGraph(
            uid = uids.singleOrNull(),
            formattedName = fn?.value,
            name = name,
            emails = emails.map { it.first },
            phones = phones.map { it.first },
            addresses = addresses.map { it.first },
            organization = organization,
            photo = photo?.first,
            birthday = birthdays.firstOrNull()?.first,
            anniversary = anniversaries.firstOrNull()?.first,
            customDates = customDates.map { it.first },
            note = notes.firstOrNull()?.value,
            urls = urls.map { it.first },
            nickname = nicknames.firstOrNull()?.first,
            relations = relations.map { it.first },
            sipAddresses = sipAddresses.map { it.first },
        )
        val graphKeys = GraphKeys(
            // A StructuredName row exists only with FN or N.
            name = if (fn != null || name != null) keys.of(listOfNotNull(fn) + nameProperties) else null,
            emails = emails.map { it.second },
            phones = phones.map { it.second },
            addresses = addresses.map { it.second },
            organization = organization?.let { keys.of(organizationProperties) },
            photo = photo?.second,
            birthday = birthdays.firstOrNull()?.let { keys.of(it.second) },
            anniversary = anniversaries.firstOrNull()?.let { keys.of(it.second) },
            customDates = customDates.map { it.second },
            note = notes.firstOrNull()?.let { keys.of(it) },
            urls = urls.map { it.second },
            nickname = nicknames.firstOrNull()?.let { keys.of(it.second) },
            relations = relations.map { it.second },
            sipAddresses = sipAddresses.map { it.second },
        )
        val candidates = mapOf(
            "FN" to fns, "N" to names.map { it.second }, "ORG" to orgs, "TITLE" to titles, "ROLE" to roles,
            "BDAY" to birthdays.map { it.second }, "ANNIVERSARY" to anniversaries.map { it.second }, "NOTE" to notes,
            "NICKNAME" to nicknames.map { it.second }, "PHOTO" to embeddedPhotos,
        ) + PHONETIC_NAMES.zip(phonetics)
        return MappingResult.Success(
            RemoteContact(KeyedGraph(graph, graphKeys), keys.cards, keys.lineKeys, candidates.mapValues { (_, properties) -> properties.map(keys::single) }),
        )
    }

    /** An `N`'s parts: every value of each component, extra values joined with a space and blank ones dropped. */
    private fun nameComponents(n: NameComponents): Pair<StructuredName, NameComponents> {
        fun part(index: Int) = n.components.getOrNull(index).orEmpty().joinedNonBlank()
        return StructuredName(given = part(1), family = part(0), prefix = part(3), middle = part(2), suffix = part(4)) to n
    }

    /**
     * The first non-blank `N` ([n]), extra values joined, and the first non-blank of each phonetic name ([phonetic]:
     * first, middle, last). Phonetic names only go with a name from `FN` or `N`, never on their own.
     */
    private fun structuredName(
        n: Pair<StructuredName, NameComponents>?,
        phonetic: List<RawProperty?>,
        hasFormattedName: Boolean,
    ): Pair<StructuredName?, List<VCardProperty>> {
        val fromN = n?.first ?: NO_NAME
        if (fromN == NO_NAME && !hasFormattedName) return null to emptyList()
        val (phoneticGiven, phoneticMiddle, phoneticFamily) = phonetic
        val name = fromN.copy(
            phoneticGiven = phoneticGiven?.value?.let(::unescapedText),
            phoneticMiddle = phoneticMiddle?.value?.let(::unescapedText),
            phoneticFamily = phoneticFamily?.value?.let(::unescapedText),
        )
        return name.takeIf { it != NO_NAME } to listOfNotNull(n?.second, phoneticGiven, phoneticMiddle, phoneticFamily)
    }

    /**
     * The photo's bytes when they're embedded; the media type is only a hint. In 4.0, ez-vcard gives bytes
     * only for a `data:` URL, and leaves 3.0-style `ENCODING=b` data (kept by Proton's import) in the URL,
     * so that is decoded here, strictly. In 2.1 and 3.0 it base64-decodes any value that doesn't start with
     * `http` (a `cid:` or `file:` URL included), so only `ENCODING=b` or `BASE64` counts there.
     */
    private fun embeddedPhoto(card: VCard, photo: Photo): ContactPhoto? {
        val base64 = photo.parameters.encoding in BASE64_ENCODINGS
        val bytes = when {
            card.version == VCardVersion.V4_0 && base64 ->
                photo.url?.let { runCatching { Base64.getDecoder().decode(it.filterNot(Char::isWhitespace)) }.getOrNull() }
            card.version == VCardVersion.V4_0 || base64 -> photo.data
            else -> null
        }
        return bytes?.takeIf { it.isNotEmpty() }?.let { ContactPhoto(it, photo.contentType?.mediaType.nonBlank()) }
    }

    /** Street lines keep their line breaks; other parts with several values are joined with a comma. */
    private fun address(adr: Address): TypedAddress? {
        val address = TypedAddress(
            street = adr.streetAddresses.joinedNonBlank("\n"),
            poBox = adr.poBoxes.joinedNonBlank(", "),
            city = adr.localities.joinedNonBlank(", "),
            region = adr.regions.joinedNonBlank(", "),
            postcode = adr.postalCodes.joinedNonBlank(", "),
            country = adr.countries.joinedNonBlank(", "),
            type = deciding(adr.parameters.types, IGNORED_TYPES).firstNotNullOfOrNull { ADDRESS_TYPES[it.lowercase()] }
                ?: AddressType.Other,
        )
        return address.takeUnless { it == TypedAddress(null, null, null, null, null, null, it.type) }
    }

    /**
     * Our own `X-ANDROID-TYPE` when it names an Android relation, then our label (see [writtenLabel]), otherwise the
     * `TYPE` rules. With `X-ANDROID-TYPE=custom` the `TYPE` value is a label, even one that names a type, so a custom
     * "Friend" stays a label once an upload writes it.
     */
    private fun relationType(related: Related): Pair<RelationType, String?> {
        val types = deciding(related.parameters.types, IGNORED_TYPES)
        val android = related.parameters.first("X-ANDROID-TYPE")?.lowercase()
        val androidType = android?.let { RELATION_TYPES[it] }
        val label = writtenLabel(related.parameters)
        return when {
            androidType != null -> androidType to null
            label != null -> RelationType.Custom to label
            android == "custom" -> RelationType.Custom to types.firstOrNull()
            else -> chooseType(types, RELATION_TYPES, RelationType.Custom, RelationType.Custom)
        }
    }

    /** The first `ORG` with a value, and the first `TITLE` and `ROLE`, with the properties they came from. */
    private fun organization(org: Organization_?, title: Title?, role: Role?): Pair<Organization?, List<VCardProperty>> {
        val organization = Organization(
            company = org?.values?.firstOrNull().nonBlank(),
            units = org?.values.orEmpty().drop(1).mapNotNull { it.nonBlank() },
            title = title?.value,
            role = role?.value,
        )
        return organization.takeIf { it != Organization(company = null) } to listOfNotNull(org, title, role)
    }

    /**
     * A date with a month and a day, whatever its form; the year is left out when there is none, or when Apple's
     * `X-APPLE-OMIT-YEAR` names it as a placeholder. Text, and dates without a month or a day, aren't mapped.
     */
    private fun partialDate(property: DateOrTimeProperty): PartialDate? {
        val full = property.date?.let { date ->
            // ez-vcard 0.12.2 only returns LocalDate, LocalDateTime, OffsetDateTime, or Instant here; any
            // other Temporal fails closed instead of ending the whole contact's merge.
            try {
                if (date is Instant) date.atOffset(ZoneOffset.UTC).toLocalDate() else LocalDate.from(date)
            } catch (_: DateTimeException) {
                null
            }
        }
        val year = full?.year ?: property.partialDate?.year
        val month = full?.monthValue ?: property.partialDate?.month ?: return null
        val day = full?.dayOfMonth ?: property.partialDate?.date ?: return null
        val placeholderYear = property.parameters.first("X-APPLE-OMIT-YEAR")?.toIntOrNull()
        // Year 0 is a "no year" placeholder some apps write, never a real year.
        val known = year?.takeIf { it != placeholderYear && it != 0 }
        // ez-vcard's partial dates check the month but not the day; 2000 is a leap year, so --0229 stays.
        if (runCatching { LocalDate.of(known ?: 2000, month, day) }.isFailure) return null
        return PartialDate(month, day, known)
    }

    /** `X-ABDATE`s, each labelled by the `X-ABLabel` in its group, with the property it came from. */
    private fun appleDates(card: VCard): List<Pair<LabeledDate, AppleDate>> {
        val labels = card.getExtendedProperties("X-ABLabel")
        return card.getProperties(AppleDate::class.java).mapNotNull { date ->
            val label = date.group?.let { group -> labels.firstOrNull { it.group.equals(group, ignoreCase = true) } }
            LabeledDate(partialDate(date) ?: return@mapNotNull null, label?.value?.let(::unescapedText)?.let(::appleLabel).nonBlank()) to date
        }
    }

    private fun String?.nonBlank() = this?.ifBlank { null }

    private fun List<String>.joinedNonBlank(separator: String = " ") =
        mapNotNull { it.nonBlank() }.joinToString(separator).ifEmpty { null }

    /** Android's SIP address column holds the address without its scheme. */
    private fun String.withoutSipScheme() = if (startsWith("sip:", ignoreCase = true)) substring(4) else this

    /** A `tel:` URI's number and extension only; its other parameters aren't shown. */
    private fun phoneNumber(uri: TelUri): String? {
        // ez-vcard decodes the parameters (the extension) but not the number.
        val number = percentDecoded(uri.number).ifBlank { return null }
        return uri.extension?.let { "$number;ext=$it" } ?: number
    }

    /** Runs of `%XX` decoded as UTF-8; anything else (a malformed escape, invalid UTF-8) is kept as it is. */
    private fun percentDecoded(text: String) = PERCENT_ESCAPES.replace(text) { run ->
        val bytes = run.value.chunked(3).map { it.substring(1).toInt(16).toByte() }.toByteArray()
        try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (_: CharacterCodingException) {
            run.value
        }
    }

    private fun emailType(parameters: VCardParameters): Pair<EmailType, String?> =
        writtenLabel(parameters)?.let { EmailType.Custom to it }
            ?: chooseType(deciding(parameters.types, IGNORED_TYPES), EMAIL_TYPES, EmailType.Other, EmailType.Custom)

    private fun phoneType(parameters: VCardParameters): Pair<PhoneType, String?> {
        writtenLabel(parameters)?.let { return PhoneType.Custom to it }
        val values = deciding(parameters.types, IGNORED_PHONE_TYPES)
        val lower = values.map { it.lowercase() }
        return when {
            "fax" in lower && "home" in lower -> PhoneType.FaxHome to null
            "fax" in lower -> PhoneType.FaxWork to null
            else -> chooseType(values, PHONE_TYPES, PhoneType.Other, PhoneType.Custom)
        }
    }

    /**
     * The label in our `X-ANDROID-LABEL` (its values joined again where a comma split it), as long as `TYPE`'s values,
     * joined the same way, still begin with it. Another app that changed `TYPE` (Proton's type picker) leaves it to the
     * `TYPE` rules.
     */
    private fun writtenLabel(parameters: VCardParameters): String? {
        val label = parameters.get(LABEL_PARAMETER).joinToString(",").nonBlank() ?: return null
        val types = parameters.types.joinToString(",")
        return label.takeIf { types == it || types.startsWith("$it,") }
    }

    /** An empty `TYPE=` counts as no type. */
    private fun deciding(types: List<String>, ignored: Set<String>) =
        types.filter { it.isNotBlank() && it.lowercase() !in ignored }

    /** The first value with a type of its own wins; with none, the first other value is a custom label. */
    private fun <T> chooseType(values: List<String>, known: Map<String, T>, other: T, custom: T): Pair<T, String?> {
        val type = values.firstNotNullOfOrNull { known[it.lowercase()] }
        return when {
            type != null -> type to null
            values.isEmpty() -> other to null
            else -> custom to values.first()
        }
    }

    private fun invalid(code: MappingIssueCode) = MappingResult.Invalid(listOf(MappingIssue(code)))

    /**
     * Exactly one complete vCard, or null. ez-vcard is lenient: it accepts a card without `END:VCARD`,
     * silently drops whatever follows the first `END`, and only warns about lines it skips, so all of
     * these count as a failed parse here. A value it can't parse (a date, say) is left to the field's
     * mapping.
     */
    private fun parse(vcard: String): VCard? {
        val lines = vcard.lines().filter { it.isNotBlank() }
        if (!lines.firstOrNull().equals("BEGIN:VCARD", ignoreCase = true) ||
            !lines.lastOrNull().equals("END:VCARD", ignoreCase = true) ||
            lines.count { it.equals("BEGIN:VCARD", ignoreCase = true) } != 1 ||
            lines.count { it.equals("END:VCARD", ignoreCase = true) } != 1
        ) {
            return null
        }
        val warnings = mutableListOf<List<ParseWarning>>()
        // Only exceptions: an Error such as a missing class must not pass for a malformed card.
        val card = try {
            Ezvcard.parse(vcard).register(NameComponentsScribe).register(AppleDateScribe)
                .warnings(warnings).all().singleOrNull()
        } catch (_: RuntimeException) {
            null
        }
        return card?.takeIf { warnings.flatten().none { it.code in STRUCTURAL_WARNINGS } }
    }

    internal companion object {
        /**
         * ez-vcard's warning codes (its messages.properties) for a card whose text is damaged: 27 a
         * line it skipped or couldn't decode (no colon, an unknown VERSION, broken quoted-printable,
         * an unknown character set), 39 no VERSION.
         */
        val STRUCTURAL_WARNINGS = setOf(27, 39)

        val PERCENT_ESCAPES = Regex("(%[0-9A-Fa-f]{2})+")

        val BASE64_ENCODINGS = setOf(Encoding.B, Encoding.BASE64)

        val NO_NAME = StructuredName(given = null, family = null)

        val PHONETIC_NAMES = listOf("X-PHONETIC-FIRST-NAME", "X-PHONETIC-MIDDLE-NAME", "X-PHONETIC-LAST-NAME")

        /** `TYPE` values that never decide a type, in lower case. */
        val IGNORED_TYPES = setOf("pref", "internet")

        /** On `TEL`, `voice` doesn't decide one either. */
        val IGNORED_PHONE_TYPES = setOf("pref", "internet", "voice")

        /** An `EMAIL`, `TEL`, or `RELATED` custom label, next to its `TYPE` (docs/mapping.md § Custom labels). */
        const val LABEL_PARAMETER = "X-ANDROID-LABEL"

        val ADDRESS_TYPES = mapOf("home" to AddressType.Home, "work" to AddressType.Work, "other" to AddressType.Other)

        /** Android's Relation type names, lower-case; `X-ANDROID-TYPE` uses the same spelling. */
        val RELATION_TYPES = mapOf(
            "assistant" to RelationType.Assistant,
            "brother" to RelationType.Brother,
            "child" to RelationType.Child,
            "domestic_partner" to RelationType.DomesticPartner,
            "father" to RelationType.Father,
            "friend" to RelationType.Friend,
            "manager" to RelationType.Manager,
            "mother" to RelationType.Mother,
            "parent" to RelationType.Parent,
            "partner" to RelationType.Partner,
            "referred_by" to RelationType.ReferredBy,
            "relative" to RelationType.Relative,
            "sister" to RelationType.Sister,
            "spouse" to RelationType.Spouse,
        )

        val EMAIL_TYPES = mapOf("home" to EmailType.Home, "work" to EmailType.Work, "other" to EmailType.Other)
        val PHONE_TYPES = mapOf(
            "home" to PhoneType.Home,
            "cell" to PhoneType.Mobile,
            "work" to PhoneType.Work,
            "pager" to PhoneType.Pager,
            "main" to PhoneType.Main,
            "other" to PhoneType.Other,
        )
    }
}

/**
 * Each property line's key, `NAME.hash`, where hash is the first 16 hex digits of SHA-256 over the card's placement, a
 * line feed, and the line as written (unfolded); the second identical one in the contact gets `~2`, and so on. A mapped
 * property's key is its line's: ez-vcard keeps a name's properties in line order, so they line up one to one, unless it
 * dropped a line or kept one as raw text (a 3.0 `BDAY` that isn't a date). Then that name has no keys in that card.
 */
internal class PropertyKeys(cards: List<CardFragment>, parsed: List<VCard>) {
    private val keys = IdentityHashMap<VCardProperty, String>()

    /** Each card's lines, and each line's key, lined up with them. */
    val cards = cards.map { cardLines(it.vcard) }
    val lineKeys: List<List<String>>

    init {
        val seen = HashMap<String, Int>()
        lineKeys = cards.mapIndexed { index, card ->
            val lines = this.cards[index].lines
            val lineKeys = lines.map { line ->
                val base = line.name + "." + sha256Hex("${card.placement.name}\n${line.line}").take(16)
                val count = seen.merge(base, 1, Int::plus)!!
                if (count == 1) base else "$base~$count"
            }
            KEYED.forEach { (name, propertiesOf) ->
                val properties = propertiesOf(parsed[index])
                val named = lines.indices.filter { lines[it].name == name }
                if (properties.size == named.size) properties.zip(named).forEach { (property, line) -> keys[property] = lineKeys[line] }
            }
            lineKeys
        }
    }

    /** The key of [property]'s line, or null when its name has no keys in its card. */
    fun single(property: VCardProperty): String? = keys[property]

    fun of(property: VCardProperty): SourceKey? = of(listOf(property))

    /** A value made from several properties has a key only if each of them has one. */
    fun of(properties: List<VCardProperty>): SourceKey? = SourceKey(properties.map { keys[it] ?: return null })

    companion object {
        /** Each property name the mapper reads, and its properties in an ez-vcard card. */
        private val KEYED: List<Pair<String, (VCard) -> List<VCardProperty>>> = listOf(
            "FN" to { it.formattedNames },
            "N" to { it.getProperties(NameComponents::class.java) },
            "X-PHONETIC-FIRST-NAME" to { it.getExtendedProperties("X-PHONETIC-FIRST-NAME") },
            "X-PHONETIC-MIDDLE-NAME" to { it.getExtendedProperties("X-PHONETIC-MIDDLE-NAME") },
            "X-PHONETIC-LAST-NAME" to { it.getExtendedProperties("X-PHONETIC-LAST-NAME") },
            "EMAIL" to { it.emails },
            "TEL" to { it.telephoneNumbers },
            "ADR" to { it.addresses },
            "ORG" to { it.organizations },
            "TITLE" to { it.titles },
            "ROLE" to { it.roles },
            "PHOTO" to { it.photos },
            "BDAY" to { it.birthdays },
            "ANNIVERSARY" to { it.anniversaries },
            "X-ABDATE" to { it.getProperties(AppleDate::class.java) },
            "NOTE" to { it.notes },
            "URL" to { it.urls },
            "NICKNAME" to { it.nicknames },
            "RELATED" to { it.relations },
            "X-SIP" to { it.getExtendedProperties("X-SIP") },
        )

        /** The names of the properties the mapper reads values from. */
        val NAMES = KEYED.map { it.first }.toSet()

        private fun sha256Hex(text: String): String =
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))
    }
}
