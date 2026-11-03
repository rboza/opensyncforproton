package dev.rboza.android.opensyncforproton.mapping

import dev.rboza.android.opensyncforproton.mapping.EzvcardContactMapper.Companion.IGNORED_PHONE_TYPES
import dev.rboza.android.opensyncforproton.mapping.EzvcardContactMapper.Companion.IGNORED_TYPES
import dev.rboza.android.opensyncforproton.mapping.EzvcardContactMapper.Companion.LABEL_PARAMETER
import dev.rboza.android.opensyncforproton.mapping.EzvcardContactMapper.Companion.NO_NAME
import dev.rboza.android.opensyncforproton.mapping.EzvcardContactMapper.Companion.RELATION_TYPES
import java.util.Base64

/**
 * The mapper's read of the re-fetched cards, as the patch needs it: the graph and its keys, each card's lines and each
 * line's key (lined up with them), and, for each property name of a field Android shows once, the key of every
 * property that would give the value if it came first, in order (null: that property has no key).
 */
internal class RemoteContact(
    val keyed: KeyedGraph,
    val cards: List<CardLines>,
    val lineKeys: List<List<String>>,
    val candidates: Map<String, List<String?>>,
)

private typealias Parameters = List<Pair<String, List<String>>>

/**
 * Pairs each of the phone's values with a remote property, by key, else by equal shown value, and rewrites only the
 * lines of what the phone changed (docs/mapping.md § Writing). Every other line stays exactly as written. An instance
 * keeps one patch's edits, so [patch] is called once per instance, on one thread.
 */
internal class ContactPatcher(private val placements: List<CardPlacement>, private val contact: RemoteContact) {
    /** [version] is its card's `VERSION`, which says how an edited line is written. */
    private class Line(val card: Int, val version: String, val property: PropertyLine, val key: String)

    /** The patch can't be written: `UNKEYED_PROPERTY`, `UNWRITABLE_LABEL`, or `UNENCRYPTED_PROPERTY`. */
    private class Unwritable(val code: MappingIssueCode) : Exception()

    private val lines = contact.cards.mapIndexed { card, cardLines ->
        cardLines.lines.mapIndexed { index, line -> Line(card, cardLines.version, line, contact.lineKeys[card][index]) }
    }
    private val byKey = lines.flatten().associateBy { it.key }

    /** The lines that take a line's place (none when it's removed); its own text there isn't an edit. */
    private val edits = HashMap<Line, List<String>>()

    /** New lines by card, past the contact's own cards for the cards it didn't have ([newCards]). */
    private val appended = HashMap<Int, MutableList<String>>()
    private val newCards = mutableListOf<CardPlacement>()

    /** New groups go past every `item` group in the contact. */
    private var nextGroup = 1L + (lines.flatten().mapNotNull { line -> line.property.group?.let(ITEM_GROUP::matchEntire)?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull() ?: 0)

    /**
     * [phone] carries the `FN` the upload gives it (docs/mapping.md § `FN` on upload); [nameRemoved] when the phone
     * itself has no name, so every `FN` goes as for any field removed on the phone, and the upload's is a new one.
     */
    fun patch(phone: KeyedGraph, nameRemoved: Boolean = false, photo: PhotoUpload = PhotoUpload.Keep): MappingResult<ContactPatch> {
        try {
            if (nameRemoved) contact.candidates["FN"].orEmpty().drop(1).forEach { remove(line(it)) }
            patchFields(phone.graph, phone.keys, contact.keyed.graph, contact.keyed.keys)
            patchPhoto(photo)
        } catch (e: Unwritable) {
            return MappingResult.Invalid(listOf(MappingIssue(e.code)))
        }
        val cards = lines.mapIndexed { card, cardLines ->
            val patched = cardLines.flatMap { line ->
                edits[line]?.map { PatchedLine(it, it != line.property.line) } ?: listOf(PatchedLine(line.property.line, false))
            }
            PatchedCard(placements[card], contact.cards[card].version, patched + newLines(card))
        } + newCards.mapIndexed { index, placement -> PatchedCard(placement, NEW_CARD_VERSION, newLines(lines.size + index)) }
        val changed = appended.isNotEmpty() || edits.any { (line, texts) -> texts != listOf(line.property.line) }
        return MappingResult.Success(ContactPatch(cards, changed))
    }

    /** Field by field, in the graph's order, so new lines come in that order. The photo is [patchPhoto]'s. */
    private fun patchFields(p: ContactGraph, pk: GraphKeys, r: ContactGraph, rk: GraphKeys) {
        val sameName = p.formattedName == r.formattedName && (p.name ?: NO_NAME) == (r.name ?: NO_NAME)
        val name = paired(pk.name, rk.name, p.formattedName != null || p.name != null, r.formattedName != null || r.name != null, sameName)
        part("FN", name, p.formattedName, r.formattedName, edit = ::editedText) { fn, version -> newLine("FN", escapedText(fn), version) }
        part("N", name, nameParts(p.name), nameParts(r.name), edit = ::editedName) { n, version -> newLine("N", structuredText(n.components()), version) }
        // Phonetic names without a name aren't shown, so they go once the phone has a name without them.
        val phoneHasName = p.formattedName != null || p.name != null
        PHONETIC.forEach { (property, of) ->
            part(property, name, p.name?.let(of), r.name?.let(of), edit = ::editedText, hiddenGo = phoneHasName) { value, version -> newLine(property, escapedText(value), version) }
        }

        // Proton refused a create whose e-mail had no group, and its web app gives each one its own.
        list("EMAIL", p.emails, pk.emails, r.emails, rk.emails, edit = ::editEmail) { email, version ->
            listOf(propertyLine(newGroup(), "EMAIL", types(emailTypes(email)) + label(email.customLabel, version), escapedText(email.value), version))
        }
        list("TEL", p.phones, pk.phones, r.phones, rk.phones, edit = ::editPhone) { phone, version ->
            listOf(newLine("TEL", escapedText(phone.value), version, types(phoneTypes(phone)) + label(phone.customLabel, version)))
        }
        list("ADR", p.addresses, pk.addresses, r.addresses, rk.addresses, edit = ::editAddress) { address, version ->
            val components = listOf(address.poBox, null, address.street, address.city, address.region, address.postcode, address.country)
            listOf(newLine("ADR", structuredText(components.map(::listOfNotNull)), version, types(addressTypes(address))))
        }

        val organization = paired(pk.organization, rk.organization, p.organization != null, r.organization != null, sameOrganization(p.organization, r.organization))
        part("ORG", organization, orgParts(p.organization), orgParts(r.organization), ::sameOrgParts, edit = ::editedOrganization) { org, version ->
            newLine("ORG", semiStructuredText(listOf(org.company.orEmpty()) + org.units), version)
        }
        part("TITLE", organization, p.organization?.title, r.organization?.title, edit = ::editedText) { title, version -> newLine("TITLE", escapedText(title), version) }
        part("ROLE", organization, p.organization?.role, r.organization?.role, edit = ::editedText) { role, version -> newLine("ROLE", escapedText(role), version) }

        part("BDAY", paired(pk.birthday, rk.birthday, p.birthday, r.birthday), p.birthday, r.birthday, edit = ::editedDate) { date, version ->
            newLine("BDAY", newDate(date), version)
        }
        part("ANNIVERSARY", paired(pk.anniversary, rk.anniversary, p.anniversary, r.anniversary), p.anniversary, r.anniversary, edit = ::editedDate) { date, version ->
            newLine("ANNIVERSARY", newDate(date), version)
        }
        list("X-ABDATE", p.customDates, pk.customDates, r.customDates, rk.customDates, edit = ::editCustomDate) { date, version ->
            val group = date.label?.let { newGroup() }
            listOfNotNull(
                propertyLine(group, "X-ABDATE", emptyList(), newDate(date.date), version),
                date.label?.let { propertyLine(group, "X-ABLabel", emptyList(), labelText(it), version) },
            )
        }
        part("NOTE", paired(pk.note, rk.note, p.note, r.note), p.note, r.note, edit = ::editedText) { note, version -> newLine("NOTE", escapedText(note), version) }
        // A URI isn't text, so it's written as it is, but for its backslashes.
        list("URL", p.urls, pk.urls, r.urls, rk.urls, edit = { line, _, url -> replace(line, edited(line, value = escapedUri(url))) }) { url, version ->
            listOf(newLine("URL", escapedUri(url), version))
        }
        part("NICKNAME", paired(pk.nickname, rk.nickname, p.nickname, r.nickname), p.nickname, r.nickname, edit = ::editedNickname) { nickname, version ->
            newLine("NICKNAME", escapedText(nickname), version)
        }
        list("RELATED", p.relations, pk.relations, r.relations, rk.relations, edit = ::editRelation) { relation, version ->
            val (types, android) = relationTypes(relation)
            val parameters = listOf("VALUE" to listOf("text")) + types(types) + ("X-ANDROID-TYPE" to listOf(android)) + label(relation.customLabel, version)
            listOf(newLine("RELATED", escapedText(relation.name), version, parameters))
        }
        list("X-SIP", p.sipAddresses, pk.sipAddresses, r.sipAddresses, rk.sipAddresses, edit = ::editSip) { sip, version ->
            listOf(newLine("X-SIP", sipScheme(sip) + escapedText(sip), version))
        }
    }

    /**
     * The photo, a field Android shows once, of embedded photos only (a URL is never mapped, so it stays). A new
     * photo takes the first one's line, in its card and group, as a JPEG in that card's syntax, and the later ones stay
     * behind it; with none, it's a new line; removed, every one goes.
     */
    private fun patchPhoto(photo: PhotoUpload) {
        val candidates = contact.candidates["PHOTO"].orEmpty()
        when (photo) {
            PhotoUpload.Keep -> Unit
            PhotoUpload.Remove -> candidates.forEach { remove(line(it)) }
            is PhotoUpload.Replace -> {
                val base64 = Base64.getEncoder().encodeToString(photo.jpeg)
                if (candidates.isEmpty()) {
                    append("PHOTO") { version -> listOf(photoLine(null, "PHOTO", base64, version)) }
                } else {
                    line(candidates.first()).let { replace(it, photoLine(it.property.group, it.property.writtenName, base64, it.version)) }
                }
            }
        }
    }

    /** A row pairs by its key, else by an equal shown value; a key naming another property counts as none. */
    private fun paired(phoneKey: SourceKey?, remoteKey: SourceKey?, phone: Boolean, remote: Boolean, same: Boolean) =
        phone && remote && (phoneKey != null && phoneKey == remoteKey || same)

    private fun <T> paired(phoneKey: SourceKey?, remoteKey: SourceKey?, phone: T?, remote: T?) =
        paired(phoneKey, remoteKey, phone != null, remote != null, phone == remote)

    /**
     * One property name of a field Android shows once. Paired, a change rewrites the first property's shown parts and
     * the later ones stay; gone from the phone, every property that would give the value goes; otherwise the phone's
     * value is a new line in the first property's place, in its card (whose group goes with it), so it still reads
     * first, or, with no such property, a new line in the card the card table puts it in. [new] writes a new line in a
     * card of the version it's given.
     */
    private fun <T : Any> part(
        name: String,
        paired: Boolean,
        phone: T?,
        remote: T?,
        same: (T, T) -> Boolean = { a, b -> a == b },
        hiddenGo: Boolean = false,
        edit: (Line, T, T) -> String,
        new: (T, String) -> String,
    ) {
        val candidates = contact.candidates[name].orEmpty()
        when {
            phone == null -> if (remote != null || hiddenGo) candidates.forEach { remove(line(it)) }
            paired && remote != null -> if (!same(remote, phone)) line(candidates.first()).let { replace(it, edit(it, remote, phone)) }
            candidates.isEmpty() -> append(name) { version -> listOf(new(phone, version)) }
            else -> line(candidates.first()).let {
                replace(it, new(phone, it.version))
                removeGroup(it)
            }
        }
    }

    /**
     * A field Android shows as a list: rows pair by key first, then by equal shown value with the first unpaired
     * property in the remote's order. Unpaired properties go; unpaired rows are new [name] lines, in the card the card
     * table puts them in.
     */
    private fun <T> list(
        name: String,
        phone: List<T>,
        phoneKeys: List<SourceKey?>,
        remote: List<T>,
        remoteKeys: List<SourceKey?>,
        edit: (Line, T, T) -> Unit,
        new: (T, String) -> List<String>,
    ) {
        val pairs = arrayOfNulls<Int>(phone.size)
        val taken = BooleanArray(remote.size)
        fun pair(index: Int, matches: (Int) -> Boolean) {
            val match = remote.indices.firstOrNull { !taken[it] && matches(it) } ?: return
            pairs[index] = match
            taken[match] = true
        }
        phone.indices.forEach { i -> phoneKeys[i]?.let { key -> pair(i) { remoteKeys[it] == key } } }
        phone.indices.filter { pairs[it] == null }.forEach { i -> pair(i) { remote[it] == phone[i] } }

        remote.indices.filterNot { taken[it] }.forEach { remove(line(remoteKeys[it])) }
        phone.indices.forEach { i ->
            val match = pairs[i]
            when {
                match == null -> append(name) { version -> new(phone[i], version) }
                remote[match] != phone[i] -> edit(line(remoteKeys[match]), remote[match], phone[i])
            }
        }
    }

    private fun editEmail(line: Line, old: TypedEmail, new: TypedEmail) {
        // The encryption settings belong to the old address.
        if (old.value != new.value) removeGroup(line) { it.name == "KEY" || it.name.startsWith("X-PM-") }
        val value = if (old.value != new.value) escapedText(new.value) else line.property.value
        val parameters = if (old.type != new.type || old.customLabel != new.customLabel) {
            withLabel(line, withTypes(line.property.parameters, emailTypes(new), IGNORED_TYPES), new.customLabel)
        } else {
            line.property.parameters
        }
        replace(line, edited(line, parameters, value))
    }

    private fun editPhone(line: Line, old: TypedPhone, new: TypedPhone) {
        var parameters = line.property.parameters
        var value = line.property.value
        if (old.value != new.value) {
            // A tel: URI's own parameters aren't shown, so an edited number is written as text, as Proton's web app
            // writes it.
            value = escapedText(new.value)
            parameters = withParameter(parameters, "VALUE", emptyList())
        }
        if (old.type != new.type || old.customLabel != new.customLabel) {
            parameters = withLabel(line, withTypes(parameters, phoneTypes(new), IGNORED_PHONE_TYPES), new.customLabel)
        }
        replace(line, edited(line, parameters, value))
    }

    private fun editAddress(line: Line, old: TypedAddress, new: TypedAddress) {
        val components = padded(structuredValue(line.property.value), 7)
        listOf(0 to TypedAddress::poBox, 2 to TypedAddress::street, 3 to TypedAddress::city, 4 to TypedAddress::region, 5 to TypedAddress::postcode, 6 to TypedAddress::country)
            .forEach { (index, part) -> if (part(old) != part(new)) components[index] = listOfNotNull(part(new)) }
        val parameters = if (old.type != new.type) withTypes(line.property.parameters, addressTypes(new), IGNORED_TYPES) else line.property.parameters
        replace(line, edited(line, parameters, structuredText(components)))
    }

    /** The date in its line, and the label in its group's `X-ABLabel` (added in a new group when the date has none). */
    private fun editCustomDate(line: Line, old: LabeledDate, new: LabeledDate) {
        val (parameters, value) = if (old.date != new.date) editedDate(line.property, new.date) else line.property.parameters to line.property.value
        val group = line.property.group
        val label = group?.let { lines[line.card].firstOrNull { it.property.name == "X-ABLABEL" && it.property.group.equals(group, ignoreCase = true) } }
        when {
            old.label == new.label -> replace(line, edited(line, parameters, value))
            label != null -> {
                replace(line, edited(line, parameters, value))
                replace(label, listOfNotNull(new.label?.let { edited(label, value = labelText(it)) }))
            }
            else -> {
                val labelled = group ?: newGroup()
                val date = propertyLine(labelled, line.property.writtenName, parameters, value, line.version)
                replace(line, listOf(date, propertyLine(labelled, "X-ABLabel", emptyList(), labelText(new.label!!), line.version)))
            }
        }
    }

    private fun editRelation(line: Line, old: TypedRelation, new: TypedRelation) {
        var parameters = line.property.parameters
        var value = line.property.value
        if (old.name != new.name) {
            // Only a text value is escaped; a URI is written as it is.
            val text = parameters.any { (name, values) -> name == "VALUE" && values.any { it.equals("text", ignoreCase = true) } }
            value = if (text) escapedText(new.name) else escapedUri(new.name)
        }
        if (old.type != new.type || old.customLabel != new.customLabel) {
            val (types, android) = relationTypes(new)
            parameters = withLabel(line, withParameter(withTypes(parameters, types, IGNORED_TYPES), "X-ANDROID-TYPE", listOf(android)), new.customLabel)
        }
        replace(line, edited(line, parameters, value))
    }

    /** A `sip:` scheme the line had stays. */
    private fun editSip(line: Line, @Suppress("UNUSED_PARAMETER") old: String, new: String) {
        val written = unescapedText(line.property.value).trim()
        val scheme = if (written.startsWith("sip:", ignoreCase = true)) written.take(4) else sipScheme(new)
        replace(line, edited(line, value = scheme + escapedText(new)))
    }

    /** The label in `X-ANDROID-LABEL` too (docs/mapping.md § Custom labels), or none when the type isn't custom. */
    private fun withLabel(line: Line, parameters: Parameters, label: String?): Parameters =
        withParameter(parameters, LABEL_PARAMETER, listOfNotNull(shown(label)?.let { writable(it, line.version) }))

    private fun editedText(line: Line, @Suppress("UNUSED_PARAMETER") old: String, new: String) = edited(line, value = escapedText(new))

    /** Only the components that changed; a changed one is one value. */
    private fun editedName(line: Line, old: StructuredName, new: StructuredName): String {
        val components = padded(structuredValue(line.property.value), 5)
        listOf(0 to StructuredName::family, 1 to StructuredName::given, 2 to StructuredName::middle, 3 to StructuredName::prefix, 4 to StructuredName::suffix)
            .forEach { (index, part) -> if (part(old) != part(new)) components[index] = listOfNotNull(part(new)) }
        return edited(line, value = structuredText(components))
    }

    /** The company and the department, each kept when it didn't change; a changed department is split at each `;`. */
    private fun editedOrganization(line: Line, old: Organization, new: Organization): String {
        val values = semiStructuredValue(line.property.value)
        val company = if (old.company != new.company) new.company.orEmpty() else values.firstOrNull().orEmpty()
        val units = if (department(old) != department(new)) new.units else values.drop(1)
        return edited(line, value = semiStructuredText(listOf(company) + units))
    }

    /** The first value with text is the one shown; the others stay. */
    private fun editedNickname(line: Line, @Suppress("UNUSED_PARAMETER") old: String, new: String): String {
        val values = listValue(line.property.value).toMutableList()
        values[values.indexOfFirst { it.isNotBlank() }] = new
        return edited(line, value = listText(values))
    }

    private fun editedDate(line: Line, @Suppress("UNUSED_PARAMETER") old: PartialDate, new: PartialDate): String {
        val (parameters, value) = editedDate(line.property, new)
        return edited(line, parameters, value)
    }

    /**
     * The line's own date form, time, and offset; without a year from the phone, a placeholder year stays, otherwise
     * `--MMDD` without a time. A year from the phone drops `X-APPLE-OMIT-YEAR`.
     */
    private fun editedDate(property: PropertyLine, date: PartialDate): Pair<Parameters, String> {
        val placeholder = property.parameters.firstOrNull { it.first == "X-APPLE-OMIT-YEAR" }?.second?.firstOrNull()
        val parameters = if (date.year != null) withParameter(property.parameters, "X-APPLE-OMIT-YEAR", emptyList()) else property.parameters
        val match = DATE.matchEntire(property.value) ?: return parameters to newDate(date)
        val (year, yearDash, _, dayDash, _, time) = match.destructured
        val monthDay = digits(date.month, 2) + dayDash + digits(date.day, 2)
        val value = when {
            date.year != null && year == "--" -> digits(date.year, 4) + dayDash + monthDay
            date.year != null -> digits(date.year, 4) + yearDash + monthDay + time
            year != "--" && (year == "0000" || year == placeholder) -> year + yearDash + monthDay + time
            else -> "--$monthDay"
        }
        return parameters to value
    }

    /**
     * A new [name] line goes last in the first card of its placement, or in a new card when the contact has none
     * (docs/mapping.md § Writing, step 4); [write] writes it for that card's version.
     */
    private fun append(name: String, write: (String) -> List<String>) {
        val placement = if (name in SIGNED_NAMES) CardPlacement.SIGNED else CardPlacement.ENCRYPTED
        var card = placements.indexOf(placement)
        if (card < 0) {
            if (placement !in newCards) newCards += placement
            card = lines.size + newCards.indexOf(placement)
        }
        appended.getOrPut(card) { mutableListOf() } += write(contact.cards.getOrNull(card)?.version ?: NEW_CARD_VERSION)
    }

    private fun newLines(card: Int) = appended[card].orEmpty().map { PatchedLine(it, true) }

    private fun line(key: SourceKey?): Line = line(key?.properties?.single())

    private fun line(key: String?): Line = key?.let(byKey::get) ?: throw Unwritable(MappingIssueCode.UNKEYED_PROPERTY)

    /** Nothing of the phone's is written in a clear card, unencrypted and unsigned; removing a line there is fine. */
    private fun replace(line: Line, texts: List<String>) {
        if (placements[line.card] == CardPlacement.CLEAR && texts.any { it != line.property.line }) throw Unwritable(MappingIssueCode.UNENCRYPTED_PROPERTY)
        edits[line] = texts
    }

    private fun replace(line: Line, text: String) = replace(line, listOf(text))

    /** A property goes with its group's lines in its card that the mapper doesn't read. */
    private fun remove(line: Line) {
        replace(line, emptyList())
        removeGroup(line)
    }

    private fun removeGroup(line: Line, which: (PropertyLine) -> Boolean = { it.name !in PropertyKeys.NAMES }) {
        val group = line.property.group ?: return
        lines[line.card].filter { it !== line && it.property.group.equals(group, ignoreCase = true) && which(it.property) }.forEach { replace(it, emptyList()) }
    }

    private fun newGroup() = "item${nextGroup++}"

    private companion object {
        /**
         * A date the rewrite keeps the form of: a year or `--`, the month, and the day, with or without dashes, and a
         * time.
         */
        val DATE = Regex("(\\d{4}|--)(-?)(\\d{2})(-?)(\\d{2})(T.*)?")

        val ITEM_GROUP = Regex("item([0-9]+)", RegexOption.IGNORE_CASE)

        val UNWRITABLE_IN_2_1 = setOf(':', '\n')

        /** Of the properties the patch writes new, the ones the signed card holds; the rest are encrypted. */
        val SIGNED_NAMES = setOf("FN", "EMAIL")

        /** A card the contact didn't have. */
        const val NEW_CARD_VERSION = "4.0"

        val PHONETIC: List<Pair<String, (StructuredName) -> String?>> = listOf(
            "X-PHONETIC-FIRST-NAME" to StructuredName::phoneticGiven,
            "X-PHONETIC-MIDDLE-NAME" to StructuredName::phoneticMiddle,
            "X-PHONETIC-LAST-NAME" to StructuredName::phoneticFamily,
        )

        /** An edited line keeps its group and its name as written. */
        fun edited(line: Line, parameters: Parameters = line.property.parameters, value: String) =
            propertyLine(line.property.group, line.property.writtenName, parameters, value, line.version)

        fun newLine(name: String, value: String, version: String, parameters: Parameters = emptyList()) = propertyLine(null, name, parameters, value, version)

        /**
         * A JPEG photo: a `data:` URL in vCard 4.0, as Proton's web app writes it; base64 with its encoding in 2.1 and
         * 3.0.
         */
        fun photoLine(group: String?, name: String, base64: String, version: String) = when (version) {
            "4.0" -> propertyLine(group, name, emptyList(), "data:image/jpeg;base64,$base64", version)
            else -> propertyLine(group, name, listOf("ENCODING" to listOf(if (version == "2.1") "BASE64" else "b"), "TYPE" to listOf("JPEG")), base64, version)
        }

        /** vCard 4.0's basic form. */
        fun newDate(date: PartialDate) = (date.year?.let { digits(it, 4) } ?: "--") + digits(date.month, 2) + digits(date.day, 2)

        fun digits(number: Int, width: Int) = number.toString().padStart(width, '0')

        fun types(values: List<String>): Parameters = if (values.isEmpty()) emptyList() else listOf("TYPE" to values)

        /** A new line's label parameter, for a card of [version]. */
        fun label(label: String?, version: String): Parameters = listOfNotNull(shown(label)?.let { LABEL_PARAMETER to listOf(writable(it, version)) })

        /** A blank label is none, as `:sync`'s column mapper reads one. */
        fun shown(label: String?) = label?.ifBlank { null }

        /**
         * A label goes in parameters, which hold no carriage return (it reads back as a line feed); vCard 2.1's can't
         * hold a colon or a line break either, and lose leading whitespace (Java's, as vinnie trims it; a no-break
         * space stays). Such a label fails closed.
         */
        fun writable(label: String, version: String): String {
            val unwritable = '\r' in label || version == "2.1" && (label.any { it in UNWRITABLE_IN_2_1 } || Character.isWhitespace(label.first()))
            return if (unwritable) throw Unwritable(MappingIssueCode.UNWRITABLE_LABEL) else label
        }

        /** A label Apple's wrapper would change on reading ([appleLabel]) is wrapped once more, so it reads back. */
        fun labelText(label: String) = escapedText(if (appleLabel(label) != label) "_\$!<$label>!\$_" else label)

        /** The mapper drops one `sip:`, so an address that starts with one gets another. */
        fun sipScheme(address: String) = if (address.startsWith("sip:", ignoreCase = true)) "sip:" else ""

        /** The new type's values, then the old values that never decide a type; no `TYPE` when there are none. */
        fun withTypes(parameters: Parameters, types: List<String>, ignored: Set<String>): Parameters {
            val kept = parameters.filter { it.first == "TYPE" }.flatMap { it.second }.filter { it.lowercase() in ignored }
            return withParameter(parameters, "TYPE", types + kept)
        }

        /** [name] set to [values] in its place, or added last; vinnie writes no parameter that has no values. */
        fun withParameter(parameters: Parameters, name: String, values: List<String>): Parameters =
            if (parameters.none { it.first == name }) parameters + (name to values) else parameters.map { if (it.first == name) name to values else it }

        fun padded(components: List<List<String>>, size: Int) =
            (components + List((size - components.size).coerceAtLeast(0)) { emptyList<String>() }).toMutableList()

        fun nameParts(name: StructuredName?) =
            name?.copy(phoneticGiven = null, phoneticMiddle = null, phoneticFamily = null)?.takeIf { it != NO_NAME }

        fun StructuredName.components() = listOf(family, given, middle, prefix, suffix).map(::listOfNotNull)

        fun orgParts(organization: Organization?) =
            organization?.let { Organization(it.company, it.units) }?.takeIf { it.company != null || it.units.isNotEmpty() }

        /**
         * The department's text, which organizations compare by: an `ORG` unit holding an escaped semicolon doesn't
         * round-trip through the phone's rows.
         */
        fun department(organization: Organization) = organization.units.joinToString(";")

        fun sameOrgParts(a: Organization, b: Organization) = a.company == b.company && department(a) == department(b)

        fun sameOrganization(a: Organization?, b: Organization?) =
            a == null && b == null || a != null && b != null && sameOrgParts(a, b) && a.title == b.title && a.role == b.role

        fun emailTypes(email: TypedEmail) = when (email.type) {
            EmailType.Home -> listOf("home")
            EmailType.Work -> listOf("work")
            EmailType.Other -> emptyList()
            EmailType.Custom -> listOfNotNull(shown(email.customLabel))
        }

        fun phoneTypes(phone: TypedPhone) = when (phone.type) {
            PhoneType.Home -> listOf("home")
            PhoneType.Mobile -> listOf("cell")
            PhoneType.Work -> listOf("work")
            PhoneType.FaxWork -> listOf("fax")
            PhoneType.FaxHome -> listOf("fax", "home")
            PhoneType.Pager -> listOf("pager")
            PhoneType.Main -> listOf("main")
            PhoneType.Other -> emptyList()
            PhoneType.Custom -> listOfNotNull(shown(phone.customLabel))
        }

        fun addressTypes(address: TypedAddress) = when (address.type) {
            AddressType.Home -> listOf("home")
            AddressType.Work -> listOf("work")
            AddressType.Other -> emptyList()
        }

        /** `TYPE`'s values and `X-ANDROID-TYPE`: Android's type name in both, or the label with `custom`. */
        fun relationTypes(relation: TypedRelation): Pair<List<String>, String> {
            if (relation.type == RelationType.Custom) return listOfNotNull(shown(relation.customLabel)) to "custom"
            val name = RELATION_TYPES.entries.first { it.value == relation.type }.key
            return listOf(name) to name
        }
    }
}
