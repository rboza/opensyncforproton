package dev.rboza.android.opensyncforproton.mapping

import dev.rboza.android.opensyncforproton.mapping.ContactPatchTest.Companion.vcard
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

// Custom labels (docs/mapping.md § Custom labels): what the phone uploads reads back as the phone has it, so the next
// sync finds nothing to upload. Every value is made up for the test.
class CustomLabelTest {
    private val mapper = EzvcardContactMapper()

    @ParameterizedTest(name = "{0}")
    @MethodSource("labelCases")
    fun `an edited line's custom label reads back`(case: LabelCase) {
        val cards = listOf(CardFragment(CardPlacement.ENCRYPTED, vcard(case.version, listOf("FN:Ann", case.kind.line))))
        val remote = merge(cards)
        val phone = KeyedGraph(case.kind.labelled(remote.graph, case.label), remote.keys)

        val result = mapper.patch(phone, cards)

        if (case.unwritable) assertEquals(UNWRITABLE, result) else assertSettles(phone.graph, success(result))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("labelCases")
    fun `a new line's custom label reads back`(case: LabelCase) {
        // A new line goes in the card the card table puts it in, written in that card's version.
        val cards = listOf(CardFragment(CardPlacement.SIGNED, vcard(case.version, listOf("FN:Ann"))), CardFragment(CardPlacement.ENCRYPTED, vcard(case.version, listOf("NOTE:x"))))
        val remote = merge(cards)
        val phone = case.kind.labelled(case.kind.added(remote.graph), case.label)
        val result = mapper.patch(KeyedGraph(phone), cards)

        if (case.unwritable) assertEquals(UNWRITABLE, result) else assertSettles(phone, success(result))
    }

    @Test
    fun `a custom label is written in TYPE and in X-ANDROID-LABEL`() {
        val email = "EMAIL;TYPE=WORK,pref:a@example.test"
        val tel = "TEL;TYPE=cell,voice:+1 555 0100"
        val related = "RELATED;TYPE=spouse:Bob"
        val cards = listOf(CardPlacement.ENCRYPTED to listOf("FN:Ann", email, tel, related))
        val remote = merge(cards.map(::fragment))
        val graph = remote.graph.copy(
            emails = remote.graph.emails.map { it.copy(type = EmailType.Custom, customLabel = "Home, Gym") },
            phones = remote.graph.phones.map { it.copy(type = PhoneType.Custom, customLabel = "voice") },
            relations = remote.graph.relations.map { it.copy(type = RelationType.Custom, customLabel = "Friend") },
        )

        val patch = success(mapper.patch(KeyedGraph(graph, remote.keys), cards.map(::fragment)))

        assertEquals(
            listOf(
                "FN:Ann",
                "EMAIL;TYPE=\"Home, Gym\",pref;X-ANDROID-LABEL=\"Home, Gym\":a@example.test",
                "TEL;TYPE=voice,voice;X-ANDROID-LABEL=voice:+1 555 0100",
                "RELATED;TYPE=Friend;X-ANDROID-TYPE=custom;X-ANDROID-LABEL=Friend:Bob",
            ),
            patch.cards.single().lines.map { it.text },
        )
    }

    @Test
    fun `a label's type changed back drops X-ANDROID-LABEL`() {
        val email = "EMAIL;TYPE=Gym,pref;X-ANDROID-LABEL=Gym:a@example.test"
        val related = "RELATED;TYPE=Gym;X-ANDROID-TYPE=custom;X-ANDROID-LABEL=Gym:Bob"
        val cards = listOf(CardPlacement.ENCRYPTED to listOf("FN:Ann", email, related))
        val remote = merge(cards.map(::fragment))
        val graph = remote.graph.copy(
            emails = remote.graph.emails.map { it.copy(type = EmailType.Work, customLabel = null) },
            relations = remote.graph.relations.map { it.copy(type = RelationType.Friend, customLabel = null) },
        )

        val patch = success(mapper.patch(KeyedGraph(graph, remote.keys), cards.map(::fragment)))

        assertEquals(
            listOf("FN:Ann", "EMAIL;TYPE=work,pref:a@example.test", "RELATED;TYPE=friend;X-ANDROID-TYPE=friend:Bob"),
            patch.cards.single().lines.map { it.text },
        )
    }

    @Test
    fun `new lines with a custom label`() {
        val cards = listOf(CardPlacement.ENCRYPTED to listOf("FN:Ann"))
        val remote = merge(cards.map(::fragment))
        val graph = remote.graph.copy(
            emails = listOf(TypedEmail("a@example.test", EmailType.Custom, "work")),
            phones = listOf(TypedPhone("+1 555 0100", PhoneType.Custom, "Gym")),
            relations = listOf(TypedRelation("Bob", RelationType.Custom, "Home, Gym")),
        )

        val patch = success(mapper.patch(KeyedGraph(graph), cards.map(::fragment)))

        assertEquals(
            listOf(
                "TEL;TYPE=Gym;X-ANDROID-LABEL=Gym:+1 555 0100",
                "RELATED;VALUE=text;TYPE=\"Home, Gym\";X-ANDROID-TYPE=custom;X-ANDROID-LABEL=\"Home, Gym\":Bob",
                "item1.EMAIL;TYPE=work;X-ANDROID-LABEL=work:a@example.test",
            ),
            newLines(patch),
        )
    }

    @Test
    fun `a 2_1 label that 2_1 can hold is written in 2_1's syntax`() {
        val cards = listOf(CardFragment(CardPlacement.ENCRYPTED, vcard("2.1", listOf("FN:Ann", "TEL;CELL;VOICE:+1 555 0100"))))
        val remote = merge(cards)
        val graph = remote.graph.copy(phones = remote.graph.phones.map { it.copy(type = PhoneType.Custom, customLabel = "Home, Gym") })

        val patch = success(mapper.patch(KeyedGraph(graph, remote.keys), cards))

        assertEquals(listOf("FN:Ann", "TEL;TYPE=Home, Gym;TYPE=VOICE;X-ANDROID-LABEL=Home, Gym:+1 555 0100"), patch.cards.single().lines.map { it.text })
        assertSettles(graph, patch)
    }

    @Test
    fun `a 2_1 label that 2_1 can't hold fails closed only when the label changes`() {
        val cards = listOf(CardFragment(CardPlacement.ENCRYPTED, vcard("2.1", listOf("FN:Ann", "TEL;TYPE=Gym:+1 555 0100"))))
        val remote = merge(cards)
        val labelled = remote.graph.copy(phones = remote.graph.phones.map { it.copy(customLabel = "Gym: back") })
        val numberOnly = remote.graph.copy(phones = remote.graph.phones.map { it.copy(value = "+1 555 0199") })

        assertEquals(UNWRITABLE, mapper.patch(KeyedGraph(labelled, remote.keys), cards))
        // The label as it was isn't written again.
        assertSettles(numberOnly, success(mapper.patch(KeyedGraph(numberOnly, remote.keys), cards)))
    }

    @ParameterizedTest(name = "{0} {1} [{2}]")
    @MethodSource("blankLabels")
    fun `a blank custom label is written as no label, as the column mapper reads one`(version: String, kind: Kind, label: String) {
        val cards = listOf(CardFragment(CardPlacement.ENCRYPTED, vcard(version, listOf("FN:Ann", kind.line))))
        val remote = merge(cards)
        val edited = success(mapper.patch(KeyedGraph(kind.labelled(remote.graph, label), remote.keys), cards))
        val added = success(mapper.patch(KeyedGraph(kind.labelled(kind.added(remote.graph.copy(emails = emptyList(), phones = emptyList(), relations = emptyList())), label)), cards))

        val written = edited.cards.single().lines.map { it.text }.drop(1) + newLines(added)
        assertEquals(kind.blank(version), written)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dateLabels")
    fun `a custom date's label reads back`(version: String, label: String) {
        val labelled = "item1.X-ABDATE:2001-07-02"
        val cards = listOf(
            CardFragment(CardPlacement.ENCRYPTED, vcard(version, listOf("FN:Ann", "X-ABDATE:2002-08-03", labelled, "item1.X-ABLabel:Met"))),
        )
        val remote = merge(cards)
        // A label added to a date without a group, a label changed, and a new date.
        val graph = remote.graph.copy(
            customDates = remote.graph.customDates.map { it.copy(label = label) } + LabeledDate(PartialDate(9, 4, null), label),
        )

        assertSettles(graph, success(mapper.patch(KeyedGraph(graph, remote.keys.copy(customDates = remote.keys.customDates + null)), cards)))
    }

    @Test
    fun `a label that Apple's wrapper would change is wrapped once more`() {
        val cards = listOf(CardPlacement.ENCRYPTED to listOf("FN:Ann", "item1.X-ABDATE:2001-07-02", "item1.X-ABLabel:Met"))
        val remote = merge(cards.map(::fragment))
        val graph = remote.graph.copy(customDates = remote.graph.customDates.map { it.copy(label = "_\$!<Other>!\$_") })

        val patch = success(mapper.patch(KeyedGraph(graph, remote.keys), cards.map(::fragment)))

        assertEquals(
            listOf("FN:Ann", "item1.X-ABDATE:2001-07-02", "item1.X-ABLabel:_\$!<_\$!<Other>!\$_>!\$_"),
            patch.cards.single().lines.map { it.text },
        )
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("sipAddresses")
    fun `a SIP address typed with its scheme reads back`(line: String?, address: String, written: String) {
        val cards = listOf(CardPlacement.ENCRYPTED to listOfNotNull("FN:Ann", line))
        val remote = merge(cards.map(::fragment))
        val keyed = KeyedGraph(remote.graph.copy(sipAddresses = listOf(address)), remote.keys.copy(sipAddresses = remote.keys.sipAddresses.ifEmpty { listOf(null) }))

        val patch = success(mapper.patch(keyed, cards.map(::fragment)))

        assertEquals(written, patch.cards.single().lines.map { it.text }.drop(1).single())
        assertSettles(keyed.graph, patch)
    }

    /** The patch reads back as the phone's graph, and patching what it wrote again changes nothing. */
    private fun assertSettles(phone: ContactGraph, patch: ContactPatch) {
        val written = patch.fragments()
        val readBack = merge(written)

        assertEquals(phone.copy(uid = null), readBack.graph.copy(uid = null), "read back")
        assertFalse(success(mapper.patch(KeyedGraph(phone, readBack.keys), written)).changed, "patched again")
    }

    /** The lines the patch added, card by card. */
    private fun newLines(patch: ContactPatch) = patch.cards.flatMap { card -> card.lines.filter { it.edited }.map { it.text } }

    private fun merge(cards: List<CardFragment>): KeyedGraph = success(mapper.merge(cards))

    private fun <T> success(result: MappingResult<T>): T = assertInstanceOf(MappingResult.Success::class.java, result).value.let {
        @Suppress("UNCHECKED_CAST")
        it as T
    }

    /** A field with a custom label, its remote line, and how the phone adds one. */
    enum class Kind(
        val line: String,
        val labelled: (ContactGraph, String) -> ContactGraph,
        val added: (ContactGraph) -> ContactGraph,
        /**
         * An edited line and a new one with a blank label, in a card of the version (2.1's `TYPE=work,pref` is one
         * value).
         */
        val blank: (String) -> List<String>,
    ) {
        EMAIL(
            "EMAIL;TYPE=work,pref:a@example.test",
            { g, label -> g.copy(emails = g.emails.map { it.copy(type = EmailType.Custom, customLabel = label) }) },
            { g -> g.copy(emails = listOf(TypedEmail("a@example.test", EmailType.Home))) },
            { version -> listOf(if (version == "2.1") "EMAIL:a@example.test" else "EMAIL;TYPE=pref:a@example.test", "item1.EMAIL:a@example.test") },
        ),
        TEL(
            "TEL;TYPE=cell,voice:+1 555 0100",
            { g, label -> g.copy(phones = g.phones.map { it.copy(type = PhoneType.Custom, customLabel = label) }) },
            { g -> g.copy(phones = listOf(TypedPhone("+1 555 0100", PhoneType.Home))) },
            { version -> listOf(if (version == "2.1") "TEL:+1 555 0100" else "TEL;TYPE=voice:+1 555 0100", "TEL:+1 555 0100") },
        ),
        RELATED(
            "RELATED;TYPE=spouse:Bob",
            { g, label -> g.copy(relations = g.relations.map { it.copy(type = RelationType.Custom, customLabel = label) }) },
            { g -> g.copy(relations = listOf(TypedRelation("Bob", RelationType.Spouse))) },
            { listOf("RELATED;X-ANDROID-TYPE=custom:Bob", "RELATED;VALUE=text;X-ANDROID-TYPE=custom:Bob") },
        ),
    }

    class LabelCase(val version: String, val kind: Kind, val label: String) {
        /**
         * Parameters hold no carriage return (it reads back as a line feed); 2.1's lose more (docs/mapping.md
         * § Custom labels).
         */
        val unwritable = '\r' in label || version == "2.1" && label in UNWRITABLE_IN_2_1

        override fun toString() = "$version $kind [${label.replace("\n", "\\n").replace("\t", "\\t")}]"
    }

    companion object {
        /** Labels that spell a type, ones TYPE's reading would split or trim, and characters each syntax escapes. */
        private val LABELS = listOf(
            "Home", "home", "work", "WORK", "other", "Other", "pref", "internet", "voice", "cell", "Cell", "fax", "main", "pager",
            "Friend", "spouse", "custom", "Home, Gym", "Gym,", ",Gym", "a,,b", "Gym;x", "Gym:x", "say \"hi\"", "line\nbreak",
            "cr\rhere", "crlf\r\nhere", " Gym", "\tGym", "\u2003Gym", "\u00a0Gym", "Gym ", "back\\slash", "end\\", "x^y", "a=b", "tab\there", "Ünï", "_\$!<Other>!\$_",
        )

        /**
         * vCard 2.1 parameter values can't hold a colon or a line break, and lose leading whitespace but a no-break
         * space.
         */
        private val UNWRITABLE_IN_2_1 = setOf("Gym:x", "line\nbreak", " Gym", "\tGym", "\u2003Gym")

        private val UNWRITABLE = MappingResult.Invalid(listOf(MappingIssue(MappingIssueCode.UNWRITABLE_LABEL)))

        @JvmStatic
        fun labelCases() = listOf("2.1", "3.0", "4.0").flatMap { version -> Kind.entries.flatMap { kind -> LABELS.map { LabelCase(version, kind, it) } } }

        @JvmStatic
        fun blankLabels() = listOf("2.1", "3.0", "4.0").flatMap { version -> Kind.entries.flatMap { kind -> listOf("", " ").map { arrayOf<Any>(version, kind, it) } } }

        @JvmStatic
        fun dateLabels() = listOf("2.1", "3.0", "4.0").flatMap { version ->
            listOf("_\$!<Other>!\$_", "_\$!<Anniversary>!\$_", "_\$!<>!\$_", "_\$!<Other>!\$_ ", "Other", "Met, again", "a\nb").map { arrayOf(version, it) }
        }

        @JvmStatic
        fun sipAddresses() = listOf(
            arrayOf(null, "sip:ann@example.test", "X-SIP:sip:sip:ann@example.test"),
            arrayOf(null, "SIP:ann@example.test", "X-SIP:sip:SIP:ann@example.test"),
            arrayOf("X-SIP:ann@example.test", "sip:bob@example.test", "X-SIP:sip:sip:bob@example.test"),
            arrayOf("X-SIP:SIP:ann@example.test", "sip:bob@example.test", "X-SIP:SIP:sip:bob@example.test"),
            arrayOf("X-SIP:ann@example.test", "bob@example.test", "X-SIP:bob@example.test"),
        )

        private fun fragment(card: Pair<CardPlacement, List<String>>) = ContactPatchTest.fragment(card)
    }
}
