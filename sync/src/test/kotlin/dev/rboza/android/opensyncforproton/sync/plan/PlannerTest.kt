package dev.rboza.android.opensyncforproton.sync.plan

import dev.rboza.android.opensyncforproton.mapping.ContactGraph
import dev.rboza.android.opensyncforproton.mapping.KeyedGraph
import dev.rboza.android.opensyncforproton.mapping.AddressType
import dev.rboza.android.opensyncforproton.mapping.LabeledDate
import dev.rboza.android.opensyncforproton.mapping.TypedAddress
import dev.rboza.android.opensyncforproton.mapping.EmailType
import dev.rboza.android.opensyncforproton.mapping.Organization
import dev.rboza.android.opensyncforproton.mapping.PartialDate
import dev.rboza.android.opensyncforproton.mapping.PhoneType
import dev.rboza.android.opensyncforproton.mapping.RelationType
import dev.rboza.android.opensyncforproton.mapping.StructuredName
import dev.rboza.android.opensyncforproton.mapping.TypedEmail
import dev.rboza.android.opensyncforproton.mapping.TypedPhone
import dev.rboza.android.opensyncforproton.mapping.TypedRelation
import dev.rboza.android.opensyncforproton.proton.Fingerprint
import dev.rboza.android.opensyncforproton.proton.ProtonContactId
import dev.rboza.android.opensyncforproton.sync.db.ConflictHoldEntity
import dev.rboza.android.opensyncforproton.sync.db.ContactMapEntity
import dev.rboza.android.opensyncforproton.sync.db.HoldChoice
import dev.rboza.android.opensyncforproton.sync.db.HoldKind
import dev.rboza.android.opensyncforproton.sync.provider.DeviceContact
import dev.rboza.android.opensyncforproton.sync.provider.RowsRead
import dev.rboza.android.opensyncforproton.sync.provider.dataRows
import dev.rboza.android.opensyncforproton.sync.provider.keyedGraph
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// The planner: docs/sync.md § The plan. Every ID, name, and fingerprint is made up.
class PlannerTest {
    private fun id(n: Int) = ProtonContactId("proton-id-$n")
    private fun print(n: Int) = Fingerprint("v1:${"%064x".format(n)}")
    private fun named(name: String) = ContactGraph(uid = null, formattedName = name)
    private fun readable(n: Int, fingerprint: Int = n, graph: ContactGraph = named("Contact $n")) = RemoteContact.Readable(id(n), print(fingerprint), KeyedGraph(graph), cards = emptyList())
    private fun row(raw: Long, source: Int?, dirty: Boolean = false, deleted: Boolean = false, version: Long = 1, name: String? = "Row $raw") =
        DeviceContact(raw, source?.let { "proton-id-$it" }, dirty, deleted, version, name)
    /** A map whose rows the app wrote with their keys, unless [keyed] is false (written by `v0.1.0`, or rebuilt). */
    private fun map(n: Int, raw: Long, fingerprint: Int? = n, keyed: Boolean = true) =
        ContactMapEntity("account-1", "proton-id-$n", raw, fingerprint?.let { print(it).value }, rowsKeyed = keyed)

    /** What the phone shows for a dirty row; unless a test says otherwise, something the remote doesn't. */
    private fun shows(graph: ContactGraph, photo: PhonePhoto = PhonePhoto.Read(null), unmapped: Int = 0) =
        PhoneEdit(RowsRead(KeyedGraph(graph), unmapped), photo)
    private val editedOnPhone = shows(named("Edited on the phone"))

    private fun plan(
        remote: List<RemoteContact>,
        rows: List<DeviceContact> = emptyList(),
        maps: List<ContactMapEntity> = emptyList(),
        complete: Boolean = true,
        confirmed: Int? = null,
        confirmedRemote: Int? = null,
        holds: List<ConflictHoldEntity> = emptyList(),
        edits: (DeviceContact) -> PhoneEdit = { editedOnPhone },
    ) = planSync(
        remote, inventoryComplete = complete, rows = rows, maps = maps, readEdit = edits, confirmedPhoneDeletions = confirmed,
        confirmedRemoteDeletions = confirmedRemote, holds = holds,
    )

    /** A stored hold of contact [n] for row [raw], recording [version] and the remote's [fingerprint] (null: gone). */
    private fun held(n: Int, raw: Long, kind: HoldKind = HoldKind.CONFLICT, choice: HoldChoice? = HoldChoice.USE_PROTON, version: Long? = 1, fingerprint: Int? = n) =
        ConflictHoldEntity("account-1", "proton-id-$n", raw, kind, choice, version, fingerprint?.let { print(it).value })

    private fun SyncPlan.only(): PlanItem = items.single()

    @Test
    fun `new remote contacts on an empty phone are new from Proton and carried out`() {
        val result = plan(listOf(readable(1), readable(2)))

        assertEquals(listOf(PlanCategory.NEW_FROM_PROTON, PlanCategory.NEW_FROM_PROTON), result.items.map { it.category })
        assertTrue(result.items.all { it.carriedOut })
        assertEquals(listOf(id(1), id(2)), result.items.map { it.protonId })
        assertEquals(listOf("Contact 1", "Contact 2"), result.items.map { it.displayName })
        assertTrue(result.items.all { it.row == null })
    }

    @Test
    fun `an unchanged contact gives no item`() {
        val result = plan(listOf(readable(1)), rows = listOf(row(10, 1)), maps = listOf(map(1, 10)))

        assertEquals(emptyList<PlanItem>(), result.items)
        assertEquals(emptyList<MapRepair>(), result.mapRepairs)
    }

    @Test
    fun `a changed fingerprint on a clean row is changed on Proton and carried out`() {
        val remote = readable(1, fingerprint = 99)
        val phone = row(10, 1, version = 7)

        val item = plan(listOf(remote), rows = listOf(phone), maps = listOf(map(1, 10))).only()

        assertEquals(PlanCategory.CHANGED_ON_PROTON, item.category)
        assertTrue(item.carriedOut)
        assertSame(remote, item.remote)
        assertSame(phone, item.row)
    }

    @Test
    fun `phone changes are reported, and an edit and a phone deletion are carried out`() {
        val cases = listOf(
            Triple(row(10, 1, dirty = true), 1, PlanCategory.CHANGED_ON_PHONE),
            Triple(row(10, 1, dirty = true, deleted = true), 1, PlanCategory.DELETED_ON_PHONE),
            Triple(row(10, 1, deleted = true), 1, PlanCategory.DELETED_ON_PHONE),
            Triple(row(10, 1, dirty = true), 99, PlanCategory.CONFLICT),
            Triple(row(10, 1, dirty = true, deleted = true), 99, PlanCategory.CONFLICT),
            // A tombstone whose DIRTY a sync adapter cleared is still a phone delete.
            Triple(row(10, 1, deleted = true), 99, PlanCategory.CONFLICT),
        )
        cases.forEach { (phone, fingerprint, category) ->
            val item = plan(listOf(readable(1, fingerprint)), rows = listOf(phone), maps = listOf(map(1, 10))).only()

            assertEquals(category, item.category, "$phone, remote $fingerprint")
            // An edit uploads and a deletion is sent; a conflict waits for the user's choice.
            assertEquals(category == PlanCategory.CHANGED_ON_PHONE || category == PlanCategory.DELETED_ON_PHONE, item.carriedOut, "$phone, remote $fingerprint")
        }
    }

    @Test
    fun `a dirty row showing what the remote does is settled, carried out but not counted, whether or not the remote moved`() {
        // Nothing is sent; a real sync clears DIRTY, or writes the row from a remote that moved.
        val graph = named("Contact 1")
        for ((fingerprint, category) in listOf(1 to PlanCategory.SETTLED, 99 to PlanCategory.SETTLED_REMOTE_CHANGED)) {
            val remote = readable(1, fingerprint, graph)
            val phone = row(10, 1, dirty = true, version = 7)

            val result = plan(listOf(remote), rows = listOf(phone), maps = listOf(map(1, 10)), edits = { shows(graph) })

            val item = result.only()
            assertEquals(category, item.category, "remote $fingerprint")
            assertTrue(item.carriedOut)
            assertSame(remote, item.remote)
            assertSame(phone, item.row)
            assertEquals(ReportCounts(0, 0, 0, 0, 0, 0, 0, false, false, 0, 0, 0), result.counts(), "a dry run sees it as unchanged")
        }
    }

    @Test
    fun `settled compares the photo by its baseline`() {
        val graph = named("Contact 1")
        fun category(baseline: String?, photo: PhonePhoto, fingerprint: Int = 1) = plan(
            listOf(readable(1, fingerprint, graph)),
            rows = listOf(row(10, 1, dirty = true)),
            maps = listOf(map(1, 10).copy(photoBaseline = baseline)),
            edits = { shows(graph, photo) },
        ).only().category

        assertEquals(PlanCategory.SETTLED, category("a".repeat(64), PhonePhoto.Read("a".repeat(64))), "the photo the app wrote")
        assertEquals(PlanCategory.SETTLED, category(null, PhonePhoto.Read(null)), "no photo on either side")
        // Only the photo changed on the phone: it uploads, or the remote moved and it's a conflict.
        assertEquals(PlanCategory.CHANGED_ON_PHONE, category("a".repeat(64), PhonePhoto.Read("b".repeat(64))), "another photo")
        assertEquals(PlanCategory.CHANGED_ON_PHONE, category("a".repeat(64), PhonePhoto.Read(null)), "removed on the phone")
        assertEquals(PlanCategory.CHANGED_ON_PHONE, category(null, PhonePhoto.Read("b".repeat(64))), "added on the phone")
        assertEquals(PlanCategory.CHANGED_ON_PHONE, category("a".repeat(64), PhonePhoto.Unreadable(null)), "a photo that can't be read isn't known to be the same")
        assertEquals(PlanCategory.CONFLICT, category("a".repeat(64), PhonePhoto.Read("b".repeat(64)), fingerprint = 99))
    }

    @Test
    fun `a dirty row showing the remote with only the written photo's thumbnail left is written again from Proton`() {
        // The provider's photo clean-up deletes the display photo or clears its PHOTO_FILE_ID, leaving the thumbnail
        // read back after the write; that's the photo the app wrote, and writing the row again from Proton brings the
        // display photo back.
        val graph = named("Contact 1")
        val display = "a".repeat(64)
        val thumbnail = "c".repeat(64)
        fun category(photo: PhonePhoto, thumbnailBaseline: String? = thumbnail, baseline: String? = display, fingerprint: Int = 1) = plan(
            listOf(readable(1, fingerprint, graph)),
            rows = listOf(row(10, 1, dirty = true)),
            maps = listOf(map(1, 10).copy(photoBaseline = baseline, photoThumbnailBaseline = thumbnailBaseline)),
            edits = { shows(graph, photo) },
        ).only().category

        assertEquals(PlanCategory.SETTLED_REMOTE_CHANGED, category(PhonePhoto.Read(thumbnail)), "only the thumbnail left")
        assertEquals(PlanCategory.SETTLED_REMOTE_CHANGED, category(PhonePhoto.Unreadable(thumbnail)), "its display photo gone")
        assertEquals(PlanCategory.SETTLED_REMOTE_CHANGED, category(PhonePhoto.Read(thumbnail), fingerprint = 99), "the remote moved too")
        assertEquals(PlanCategory.SETTLED, category(PhonePhoto.Read(display)), "the display photo still there")
        assertEquals(PlanCategory.SETTLED, category(PhonePhoto.Read(thumbnail), baseline = thumbnail), "a photo no larger than its thumbnail keeps no display photo")
        assertEquals(PlanCategory.CHANGED_ON_PHONE, category(PhonePhoto.Read("b".repeat(64))), "another photo")
        assertEquals(PlanCategory.CHANGED_ON_PHONE, category(PhonePhoto.Unreadable("b".repeat(64))), "a display photo gone whose thumbnail is another")
        assertEquals(PlanCategory.CHANGED_ON_PHONE, category(PhonePhoto.Unreadable(null)), "a display photo gone with no thumbnail")
        // A map from before thumbnails were kept (migrated, or bound to a create) knows no thumbnail.
        assertEquals(PlanCategory.CHANGED_ON_PHONE, category(PhonePhoto.Read(thumbnail), thumbnailBaseline = null), "no thumbnail baseline")
        assertEquals(PlanCategory.CHANGED_ON_PHONE, category(PhonePhoto.Unreadable(thumbnail), thumbnailBaseline = null), "no thumbnail baseline, display photo gone")
        assertEquals(PlanCategory.CHANGED_ON_PHONE, category(PhonePhoto.Read(null), thumbnailBaseline = null), "removed on the phone, no thumbnail baseline")
        assertEquals(PlanCategory.CHANGED_ON_PHONE, category(PhonePhoto.Unreadable(null), thumbnailBaseline = null), "gone with nothing left, no thumbnail baseline")
        assertEquals(PlanCategory.CONFLICT, category(PhonePhoto.Read("b".repeat(64)), fingerprint = 99))
    }

    @Test
    fun `settled compares what Android shows, in any order, with line breaks and SIP whitespace as a vCard reads them`() {
        // A CR or CRLF reads back as LF, and the mapper trims X-SIP.
        val remote = ContactGraph(
            uid = "urn:uuid:not-shown",
            formattedName = "Contact 1",
            emails = listOf(TypedEmail("one@example.test", EmailType.Home), TypedEmail("two@example.test", EmailType.Work)),
            phones = listOf(TypedPhone("+1 555 0100", PhoneType.Mobile), TypedPhone("+1 555 0101", PhoneType.Home)),
            note = "line one\nline two",
            sipAddresses = listOf("one@sip.example.test"),
        )
        val phone = remote.copy(
            uid = null,
            emails = remote.emails.reversed(),
            phones = remote.phones.reversed(),
            note = "line one\r\nline two",
            sipAddresses = listOf(" one@sip.example.test\t"),
        )

        val item = plan(listOf(readable(1, graph = remote)), rows = listOf(row(10, 1, dirty = true)), maps = listOf(map(1, 10)), edits = { shows(phone) }).only()

        assertEquals(PlanCategory.SETTLED, item.category)
        val lone = remote.copy(note = "line one\rline two")
        assertEquals(PlanCategory.SETTLED, plan(listOf(readable(1, graph = remote)), rows = listOf(row(10, 1, dirty = true)), maps = listOf(map(1, 10)), edits = { shows(lone) }).only().category, "a lone CR")
        val other = phone.copy(emails = phone.emails.dropLast(1))
        assertEquals(PlanCategory.CHANGED_ON_PHONE, plan(listOf(readable(1, graph = remote)), rows = listOf(row(10, 1, dirty = true)), maps = listOf(map(1, 10)), edits = { shows(other) }).only().category)
        val twice = phone.copy(emails = phone.emails + phone.emails.first())
        assertEquals(PlanCategory.CHANGED_ON_PHONE, plan(listOf(readable(1, graph = remote)), rows = listOf(row(10, 1, dirty = true)), maps = listOf(map(1, 10)), edits = { shows(twice) }).only().category, "a value twice isn't the same list")
    }

    @Test
    fun `every shown text compares with its line breaks as a vCard reads them`() {
        // A parameter reads a CR back as LF too, so labels count as well.
        fun text(lf: Boolean, value: String) = if (lf) "$value\none" else "$value\r\none"
        fun graph(lf: Boolean) = ContactGraph(
            uid = null,
            formattedName = text(lf, "fn"),
            name = StructuredName(
                text(lf, "given"), text(lf, "family"), text(lf, "prefix"), text(lf, "middle"), text(lf, "suffix"),
                text(lf, "phonetic given"), text(lf, "phonetic middle"), text(lf, "phonetic family"),
            ),
            emails = listOf(TypedEmail(text(lf, "email"), EmailType.Custom, text(lf, "email label"))),
            phones = listOf(TypedPhone(text(lf, "phone"), PhoneType.Custom, text(lf, "phone label"))),
            addresses = listOf(
                TypedAddress(text(lf, "street"), text(lf, "po box"), text(lf, "city"), text(lf, "region"), text(lf, "postcode"), text(lf, "country"), AddressType.Home),
            ),
            organization = Organization(text(lf, "company"), units = listOf(text(lf, "unit")), title = text(lf, "title"), role = text(lf, "role")),
            customDates = listOf(LabeledDate(PartialDate(1, 2, null), text(lf, "date label"))),
            note = text(lf, "note"),
            urls = listOf(text(lf, "url")),
            nickname = text(lf, "nickname"),
            relations = listOf(TypedRelation(text(lf, "relation"), RelationType.Custom, text(lf, "relation label"))),
            sipAddresses = listOf(text(lf, "sip")),
        )

        val item = plan(listOf(readable(1, graph = graph(lf = true))), rows = listOf(row(10, 1, dirty = true)), maps = listOf(map(1, 10)), edits = { shows(graph(lf = false)) }).only()

        assertEquals(PlanCategory.SETTLED, item.category)
    }

    @Test
    fun `the phone's own read-back of what the app wrote is settled`() {
        // docs/mapping.md § Reading rows back: contact -> rows -> contact gives the same graph; lossy parts (an ORG
        // unit holding a semicolon, an unlabelled custom type) compare as the phone shows them.
        val remote = KeyedGraph(
            ContactGraph(
                uid = null,
                formattedName = "Dr. Ada Lovelace",
                name = StructuredName(given = "Ada", family = "Lovelace", prefix = "Dr."),
                emails = listOf(TypedEmail("ada@example.test", EmailType.Custom, "Gym")),
                phones = listOf(TypedPhone("+1 555 0100", PhoneType.Custom, null)),
                organization = Organization("Acme", units = listOf("R;D", "Lab"), title = "Engineer"),
                birthday = PartialDate(12, 10, null),
                note = "A note",
                urls = listOf("https://example.test"),
                nickname = "Ada",
                relations = listOf(TypedRelation("Byron", RelationType.Father)),
            ),
        )
        val phone = keyedGraph(dataRows(remote.graph, photo = null, keys = remote.keys))

        val item = plan(
            listOf(RemoteContact.Readable(id(1), print(1), remote, cards = emptyList())),
            rows = listOf(row(10, 1, dirty = true)),
            maps = listOf(map(1, 10)),
            edits = { PhoneEdit(phone, PhonePhoto.Read(null)) },
        ).only()

        assertEquals(PlanCategory.SETTLED, item.category)
    }

    @Test
    fun `a change to any one shown field isn't settled`() {
        val remote = ContactGraph(
            uid = null,
            formattedName = "Ada Lovelace",
            name = StructuredName(given = "Ada", family = "Lovelace", phoneticGiven = "A-da"),
            emails = listOf(TypedEmail("ada@example.test", EmailType.Custom, "Gym")),
            phones = listOf(TypedPhone("+1 555 0100", PhoneType.Custom, "Desk")),
            addresses = listOf(TypedAddress("1 Main St", null, "Springfield", null, "12345", null, AddressType.Home)),
            organization = Organization("Acme", units = listOf("Lab"), title = "Engineer", role = "Lead"),
            birthday = PartialDate(12, 10, 1815),
            anniversary = PartialDate(7, 8, null),
            customDates = listOf(LabeledDate(PartialDate(1, 2, null), "Met")),
            note = "A note",
            urls = listOf("https://example.test"),
            nickname = "Ada",
            relations = listOf(TypedRelation("Byron", RelationType.Custom, "Poet")),
            sipAddresses = listOf("ada@sip.example.test"),
        )
        val changes = mapOf(
            "display name" to remote.copy(formattedName = "Ada King"),
            "a name part" to remote.copy(name = remote.name!!.copy(family = "King")),
            "a phonetic name" to remote.copy(name = remote.name!!.copy(phoneticGiven = "Ah-da")),
            "an e-mail's label" to remote.copy(emails = listOf(TypedEmail("ada@example.test", EmailType.Custom, "Pool"))),
            "a phone" to remote.copy(phones = listOf(TypedPhone("+1 555 0199", PhoneType.Custom, "Desk"))),
            "a phone's label" to remote.copy(phones = listOf(TypedPhone("+1 555 0100", PhoneType.Custom, "Home office"))),
            "an address" to remote.copy(addresses = listOf(TypedAddress("2 Main St", null, "Springfield", null, "12345", null, AddressType.Home))),
            "an address's type" to remote.copy(addresses = listOf(TypedAddress("1 Main St", null, "Springfield", null, "12345", null, AddressType.Work))),
            "the company" to remote.copy(organization = remote.organization!!.copy(company = "Initech")),
            "the department" to remote.copy(organization = remote.organization!!.copy(units = listOf("Office"))),
            "the title" to remote.copy(organization = remote.organization!!.copy(title = "Manager")),
            "the role" to remote.copy(organization = remote.organization!!.copy(role = "Member")),
            "the birthday" to remote.copy(birthday = PartialDate(12, 11, 1815)),
            "the anniversary" to remote.copy(anniversary = PartialDate(7, 9, null)),
            "a custom date's label" to remote.copy(customDates = listOf(LabeledDate(PartialDate(1, 2, null), "First met"))),
            "the note" to remote.copy(note = "Another note"),
            "a website" to remote.copy(urls = listOf("https://other.example.test")),
            "the nickname" to remote.copy(nickname = "Countess"),
            "a relation's label" to remote.copy(relations = listOf(TypedRelation("Byron", RelationType.Custom, "Father"))),
            "a SIP address" to remote.copy(sipAddresses = listOf("king@sip.example.test")),
        )
        val unchanged = plan(listOf(readable(1, graph = remote)), rows = listOf(row(10, 1, dirty = true)), maps = listOf(map(1, 10)), edits = { shows(remote) })
        assertEquals(PlanCategory.SETTLED, unchanged.only().category)
        changes.forEach { (what, phone) ->
            val item = plan(listOf(readable(1, graph = remote)), rows = listOf(row(10, 1, dirty = true)), maps = listOf(map(1, 10)), edits = { shows(phone) }).only()

            assertEquals(PlanCategory.CHANGED_ON_PHONE, item.category, what)
        }
    }

    @Test
    fun `a dirty row with rows the app can't read back is never settled`() {
        // A write from the remote, or an upload, would lose them: can't be synced, or a conflict once the remote moves.
        val graph = named("Contact 1")
        fun plan(fingerprint: Int) = plan(listOf(readable(1, fingerprint, graph)), rows = listOf(row(10, 1, dirty = true)), maps = listOf(map(1, 10)), edits = { shows(graph, unmapped = 1) })

        val unchanged = plan(1)
        assertEquals(PlanCategory.UNMAPPED_ROWS, unchanged.only().category)
        assertFalse(unchanged.only().carriedOut)
        assertEquals(ReportCounts(0, 0, 0, 0, 0, 0, 0, false, false, 1, 0, 0), unchanged.counts(), "counted as can't be synced")
        assertEquals(PlanCategory.CONFLICT, plan(99).only().category)

        val left = plan(emptyList(), rows = listOf(row(10, 1, dirty = true)), maps = listOf(map(1, 10)), complete = false, edits = { shows(graph, unmapped = 2) })
        assertEquals(PlanCategory.UNMAPPED_ROWS, left.only().category, "left out of an incomplete inventory")
    }

    @Test
    fun `only a dirty live row with keys, or a live row made on the phone, is read back`() {
        val read = mutableListOf<Long>()
        val rows = listOf(
            row(10, 1, dirty = true), row(11, 2), row(12, 3, dirty = true, deleted = true), row(13, 4, dirty = true), row(14, 5, dirty = true),
            row(15, 6, dirty = true), row(16, 7, dirty = true), row(17, null, dirty = true), row(18, 9, dirty = true),
            row(19, null, dirty = true, deleted = true), row(20, null),
        )
        val maps = listOf(map(1, 10), map(2, 11), map(3, 12), map(4, 13, keyed = false), map(6, 99), map(7, 16), map(9, 18))
        val unreadable = RemoteContact.Unreadable(id(7), print(7), UnreadableReason.VERIFY)

        plan(listOf(readable(1), readable(2), readable(3), readable(4), readable(5), readable(6), unreadable), rows = rows, maps = maps, complete = false) {
            read += it.rawContactId
            editedOnPhone
        }

        // 13: no keys; 14: no map; 15: a map naming another row; 16: an unreadable contact; 19: made and deleted on the
        // phone. 17 and 20 were made on the phone, so their cards are made from what they show.
        assertEquals(listOf(10L, 18L, 17L, 20L), read)
    }

    @Test
    fun `a clean row written before keys is rewritten from Proton, carried out but not counted`() {
        // Written before keys: the write sets its keys; nothing is uploaded.
        val remote = readable(1)
        val phone = row(10, 1, version = 7)

        val result = plan(listOf(remote), rows = listOf(phone), maps = listOf(map(1, 10, keyed = false)))

        val item = result.only()
        assertEquals(PlanCategory.WRITTEN_BEFORE_KEYS, item.category)
        assertTrue(item.carriedOut)
        assertSame(remote, item.remote)
        assertSame(phone, item.row)
        assertEquals("Contact 1", item.displayName)
        assertEquals(ReportCounts(0, 0, 0, 0, 0, 0, 0, false, false, 0, 0, 0), result.counts())
        assertEquals(emptyList<MapRepair>(), result.mapRepairs)
    }

    @Test
    fun `a dirty row written before keys is held as edited before keys, counted as a conflict`() {
        val result = plan(listOf(readable(1)), rows = listOf(row(10, 1, dirty = true)), maps = listOf(map(1, 10, keyed = false)))

        assertEquals(PlanCategory.EDITED_BEFORE_KEYS, result.only().category)
        assertFalse(result.only().carriedOut)
        assertEquals(ReportCounts(0, 0, 0, 0, 0, 0, 1, false, false, 0, 0, 0), result.counts())
    }

    @Test
    fun `rows written before keys otherwise plan as any other`() {
        // Step 5's order: a conflict first, a missing key only after.
        val cases = listOf(
            Triple(row(10, 1), 99, PlanCategory.CHANGED_ON_PROTON),
            Triple(row(10, 1, dirty = true), 99, PlanCategory.CONFLICT),
            Triple(row(10, 1, deleted = true), 1, PlanCategory.DELETED_ON_PHONE),
            Triple(row(10, 1, dirty = true, deleted = true), 1, PlanCategory.DELETED_ON_PHONE),
            Triple(row(10, 1, deleted = true), 99, PlanCategory.CONFLICT),
        )
        cases.forEach { (phone, fingerprint, category) ->
            val item = plan(listOf(readable(1, fingerprint)), rows = listOf(phone), maps = listOf(map(1, 10, keyed = false))).only()

            assertEquals(category, item.category, "$phone, remote $fingerprint")
            // A deletion needs no keys.
            assertEquals(category == PlanCategory.CHANGED_ON_PROTON || category == PlanCategory.DELETED_ON_PHONE, item.carriedOut, "$phone, remote $fingerprint")
        }
        val unreadable = RemoteContact.Unreadable(id(1), print(1), UnreadableReason.DECRYPT)
        assertEquals(PlanCategory.UNREADABLE, plan(listOf(unreadable), rows = listOf(row(10, 1)), maps = listOf(map(1, 10, keyed = false))).only().category, "nothing to write it from")
        assertEquals(PlanCategory.CONFLICT, plan(emptyList(), rows = listOf(row(10, 1, dirty = true)), maps = listOf(map(1, 10, keyed = false))).only().category, "gone from Proton")
        // Another contact still on Proton, so the inventory isn't empty.
        val absent = plan(listOf(readable(2)), rows = listOf(row(10, 1), row(11, 2)), maps = listOf(map(1, 10, keyed = false), map(2, 11))).only()
        assertEquals(PlanCategory.DELETED_ON_PROTON, absent.category)
        assertTrue(absent.carriedOut, "a deletion needs no keys, only the baseline")
    }

    @Test
    fun `a dirty row whose map was rebuilt, or has an empty baseline, is a conflict`() {
        // The baseline is empty, so the remote counts as changed (step 5).
        assertEquals(PlanCategory.CONFLICT, plan(listOf(readable(1)), rows = listOf(row(10, 1, dirty = true))).only().category, "no map")
        assertEquals(
            PlanCategory.CONFLICT,
            plan(listOf(readable(1)), rows = listOf(row(10, 1, dirty = true)), maps = listOf(map(1, 10, fingerprint = null, keyed = false))).only().category,
            "rebuilt from SOURCE_ID",
        )
        assertEquals(PlanCategory.CHANGED_ON_PROTON, plan(listOf(readable(1)), rows = listOf(row(10, 1))).only().category, "clean: rewritten, so counted")
    }

    @Test
    fun `after an incomplete inventory a dirty row left out still needs its keys and its baseline`() {
        val rows = listOf(row(10, 1, dirty = true), row(11, 2, dirty = true), row(12, 3, dirty = true), row(13, 4, dirty = true), row(14, 5))
        val maps = listOf(map(1, 10), map(2, 11, keyed = false), map(3, 12, fingerprint = null, keyed = false), map(5, 14, keyed = false))

        val result = plan(emptyList(), rows = rows, maps = maps, complete = false)

        assertEquals(
            listOf(PlanCategory.CHANGED_ON_PHONE, PlanCategory.EDITED_BEFORE_KEYS, PlanCategory.CONFLICT, PlanCategory.CONFLICT),
            result.items.map { it.category },
            "an empty baseline (none, or a map repair) is a conflict; a clean row left out is nothing to rewrite from",
        )
        assertEquals(listOf(true, false, false, false), result.items.map { it.carriedOut }, "the edit uploads, its C2 deciding")
    }

    @Test
    fun `after an incomplete inventory a tombstone left out with an empty baseline is a conflict`() {
        // As it is when its contact arrives: the remote may have changed since a baseline nobody knows.
        val rows = listOf(row(10, 1, deleted = true), row(11, 2, dirty = true, deleted = true), row(12, 3, deleted = true), row(13, 4, deleted = true))
        val maps = listOf(map(2, 11, fingerprint = null, keyed = false), map(3, 12), map(4, 13, keyed = false))

        val result = plan(emptyList(), rows = rows, maps = maps, complete = false)

        assertEquals(
            listOf(PlanCategory.CONFLICT, PlanCategory.CONFLICT, PlanCategory.DELETED_ON_PHONE, PlanCategory.DELETED_ON_PHONE),
            result.items.map { it.category },
            "no map, or a rebuilt one: a conflict; a baseline, keyed or not: a phone deletion",
        )
        assertEquals(listOf(false, false, true, true), result.items.map { it.carriedOut }, "a deletion is re-fetched first")
    }

    @Test
    fun `a clean row missing from a complete inventory is deleted on Proton and carried out`() {
        // It's deleted on the phone; a mistaken deletion comes back with the next sync. Another contact still on
        // Proton, so the inventory isn't empty (an empty one stops the guard).
        val phone = row(10, 1, version = 7)
        val item = plan(listOf(readable(2)), rows = listOf(phone, row(11, 2)), maps = listOf(map(1, 10), map(2, 11))).only()

        assertEquals(PlanCategory.DELETED_ON_PROTON, item.category)
        assertTrue(item.carriedOut)
        assertEquals(id(1), item.protonId)
        assertSame(phone, item.row)
        assertEquals("Row 10", item.displayName)
        assertEquals(PlanCategory.CONFLICT, plan(emptyList(), rows = listOf(row(10, 1, dirty = true)), maps = listOf(map(1, 10))).only().category)
    }

    @Test
    fun `a clean row missing from a complete inventory with an empty baseline is a conflict, never deleted`() {
        // Never on an empty baseline: no map, a map naming another row, or a rebuilt one.
        val cases = listOf(
            "no map" to emptyList(),
            "another row's map" to listOf(map(1, 99)),
            "rebuilt from SOURCE_ID" to listOf(map(1, 10, fingerprint = null, keyed = false)),
        )
        for ((case, maps) in cases) {
            val result = plan(emptyList(), rows = listOf(row(10, 1)), maps = maps)

            assertEquals(PlanCategory.CONFLICT, result.only().category, case)
            assertFalse(result.only().carriedOut, case)
            assertEquals(1, result.counts().conflicts, case)
            assertEquals(0, result.counts().deletedOnProton, case)
        }
    }

    @Test
    fun `a tombstone missing from a complete inventory is deleted on both sides, its removal finished and not counted`() {
        // Absent from the inventory: nothing to send, and not a conflict.
        for (tombstone in listOf(row(10, 1, dirty = true, deleted = true), row(10, 1, deleted = true))) {
            val result = plan(emptyList(), rows = listOf(tombstone), maps = listOf(map(1, 10, fingerprint = 99)))

            val item = result.only()
            assertEquals(PlanCategory.DELETED_ON_BOTH, item.category, "$tombstone")
            assertTrue(item.carriedOut)
            assertEquals(id(1), item.protonId)
            assertSame(tombstone, item.row)
            assertEquals(ReportCounts(0, 0, 0, 0, 0, 0, 0, false, false, 0, 0, 0), result.counts())
            assertEquals(emptyList<MapRepair>(), result.mapRepairs)
        }
        assertEquals(PlanCategory.DELETED_ON_BOTH, plan(emptyList(), rows = listOf(row(10, 1, deleted = true))).only().category, "without a map too")
        assertEquals(PlanCategory.DELETED_ON_PHONE, plan(emptyList(), rows = listOf(row(10, 1, deleted = true)), maps = listOf(map(1, 10)), complete = false).only().category, "it may still be on Proton")
    }

    @Test
    fun `phone deletions aren't carried out while the bulk guard stops them`() {
        // No C6 at all; a removal already done on Proton still finishes.
        val rows = (1..10).map { row(it.toLong(), it, deleted = true) } + row(11, 11, deleted = true)
        val result = plan((1..10).map { readable(it) }, rows = rows, maps = (1..11).map { map(it, it.toLong()) })

        assertTrue(result.bulkGuardPhone)
        val deletions = result.items.filter { it.category == PlanCategory.DELETED_ON_PHONE }
        assertEquals(10, deletions.size)
        assertTrue(deletions.none { it.carriedOut })
        assertTrue(result.items.single { it.category == PlanCategory.DELETED_ON_BOTH }.carriedOut)
        assertTrue(plan((1..9).map { readable(it) }, rows = rows.take(9), maps = (1..9).map { map(it, it.toLong()) }).items.all { it.carriedOut }, "9 are sent")
    }

    @Test
    fun `while the guard stops deletions on the phone, nothing acts on Proton's absences`() {
        // An empty inventory, or many missing, may be wrong: a tombstone deleted on both sides waits; no map is stale.
        val rows = (1..20).map { row(it.toLong(), it) } + row(50, 50, deleted = true)
        val maps = (1..20).map { map(it, it.toLong()) } + map(50, 50) + map(60, 60)

        val result = plan(emptyList(), rows = rows, maps = maps)

        assertTrue(result.bulkGuardRemote)
        assertEquals(20, result.items.count { it.category == PlanCategory.DELETED_ON_PROTON })
        assertTrue(result.items.none { it.carriedOut }, "no row deleted, and the removal waits")
        assertEquals(emptyList<ProtonContactId>(), result.staleMaps)
        // Many missing from a complete inventory stop it too: 10 of 20 here.
        val many = plan((11..20).map { readable(it) }, rows = rows, maps = maps)
        assertTrue(many.bulkGuardRemote)
        assertEquals(10, many.items.count { it.category == PlanCategory.DELETED_ON_PROTON })
        assertTrue(many.items.none { it.carriedOut })
        assertEquals(emptyList<ProtonContactId>(), many.staleMaps)
        // One of 20 missing: the guard doesn't stop, so all are done.
        val unguarded = plan((2..20).map { readable(it) }, rows = rows, maps = maps)
        assertFalse(unguarded.bulkGuardRemote)
        assertTrue(unguarded.items.single { it.category == PlanCategory.DELETED_ON_PROTON }.carriedOut)
        assertTrue(unguarded.items.single { it.category == PlanCategory.DELETED_ON_BOTH }.carriedOut)
        assertEquals(listOf(id(60)), unguarded.staleMaps)
    }

    @Test
    fun `a tombstone of an unreadable contact unchanged since its baseline is a phone deletion`() {
        // Proton holds what the phone had when it was deleted there, so an upload-only sync, which reads no inventory,
        // and a full sync agree.
        val unreadable = RemoteContact.Unreadable(id(1), print(1), UnreadableReason.VERIFY)
        val item = plan(listOf(unreadable), rows = listOf(row(10, 1, deleted = true, name = "Row name")), maps = listOf(map(1, 10))).only()

        assertEquals(PlanCategory.DELETED_ON_PHONE, item.category)
        assertTrue(item.carriedOut)
        assertEquals("Row name", item.displayName)
        val moved = RemoteContact.Unreadable(id(1), print(99), UnreadableReason.VERIFY)
        assertEquals(PlanCategory.UNREADABLE, plan(listOf(moved), rows = listOf(row(10, 1, deleted = true)), maps = listOf(map(1, 10))).only().category, "changed there")
        assertEquals(PlanCategory.UNREADABLE, plan(listOf(unreadable), rows = listOf(row(10, 1, dirty = true)), maps = listOf(map(1, 10))).only().category, "an edit")
        assertEquals(PlanCategory.UNREADABLE, plan(listOf(unreadable), rows = listOf(row(10, 1, deleted = true))).only().category, "no baseline")
    }

    @Test
    fun `a map whose Proton ID is neither in a complete inventory nor on any row is stale`() {
        // Nothing is left for it to pair (a crash after a removal leaves one).
        val rows = listOf(row(10, 1), row(11, 2, deleted = true), row(12, 3, deleted = true), row(13, 7), row(14, 7))
        val maps = listOf(map(1, 10), map(2, 11), map(3, 12), map(4, 20), map(5, 21), map(6, 22), map(7, 13))

        val result = plan(listOf(readable(1), readable(2), readable(5)), rows = rows, maps = maps)

        assertEquals(listOf(id(4), id(6)), result.staleMaps, "a missing row's map, a tombstone's, and a duplicate's stay")
        assertEquals(emptyList<ProtonContactId>(), plan(listOf(readable(1)), rows = rows, maps = maps, complete = false).staleMaps, "after an incomplete inventory it may still be there")
    }

    @Test
    fun `the plan says which contacts its inventory left out`() {
        // A sync keeps the holds of contacts an incomplete inventory left out.
        val complete = plan(listOf(readable(1)))
        val incomplete = plan(listOf(readable(1)), complete = false)

        assertFalse(complete.leftOut(id(1)))
        assertFalse(complete.leftOut(id(2)), "a complete inventory leaves nothing out")
        assertFalse(incomplete.leftOut(id(1)))
        assertTrue(incomplete.leftOut(id(2)))
        // While the remote guard stops, a complete inventory's absences aren't trusted either.
        val guarded = plan(emptyList(), rows = listOf(row(10, 1)), maps = listOf(map(1, 10)))
        assertTrue(guarded.bulkGuardRemote)
        assertTrue(guarded.leftOut(id(1)))
        assertTrue(guarded.leftOut(id(2)))
        assertFalse(plan(listOf(readable(1)), rows = listOf(row(10, 1)), maps = listOf(map(1, 10, fingerprint = 9))).leftOut(id(2)), "unguarded")
    }

    @Test
    fun `a stored Use Proton matching both sides is carried out as what it does`() {
        // Counted as changed, deleted, or new from Proton, not as a conflict.
        val rows = listOf(
            row(10, 1, dirty = true), row(20, 2, dirty = true), row(30, 3, dirty = true, deleted = true), row(50, 5, dirty = true), row(60, 6),
        )
        val maps = listOf(map(1, 10, fingerprint = 91), map(2, 20), map(3, 30, fingerprint = 93), map(4, 40), map(5, 50, keyed = false), map(6, 60, fingerprint = null))
        val holds = listOf(
            // Edit vs edit; a phone edit vs a Proton deletion; a phone deletion vs a Proton edit; a missing row; a row
            // edited before keys; a clean row with an empty baseline gone from Proton.
            held(1, 10), held(2, 20, fingerprint = null), held(3, 30), held(4, 40, HoldKind.MISSING_ROW, version = null),
            held(5, 50, HoldKind.EDITED_BEFORE_KEYS), held(6, 60, fingerprint = null),
        )

        val result = plan(listOf(readable(1), readable(3), readable(4), readable(5)), rows = rows, maps = maps, holds = holds)

        val byId = result.items.associateBy { checkNotNull(it.protonId) }
        assertEquals(
            mapOf(
                id(1) to PlanCategory.CHANGED_ON_PROTON, id(2) to PlanCategory.DELETED_ON_PROTON, id(3) to PlanCategory.NEW_FROM_PROTON,
                id(4) to PlanCategory.NEW_FROM_PROTON, id(5) to PlanCategory.CHANGED_ON_PROTON, id(6) to PlanCategory.DELETED_ON_PROTON,
            ),
            byId.mapValues { it.value.category },
        )
        assertTrue(result.items.all { it.carriedOut })
        assertEquals((1..6).map { holds[it - 1] }, (1..6).map { byId.getValue(id(it)).hold }, "each carries the hold whose choice it carries out")
        assertEquals(30L, byId.getValue(id(3)).row?.rawContactId, "the tombstone the restore removes")
        assertNull(byId.getValue(id(4)).row)
        assertEquals(map(4, 40), byId.getValue(id(4)).map)
        val counts = result.counts()
        assertEquals(listOf(2, 2, 2, 0, 0), listOf(counts.changedOnProton, counts.deletedOnProton, counts.newFromProton, counts.conflicts, counts.missingRows))
    }

    @Test
    fun `a stored choice is carried out only while both sides are as it recorded them`() {
        // Contact 9, unchanged, keeps the inventory from being empty.
        val rows = listOf(row(10, 1, dirty = true, version = 2), row(90, 9))
        val maps = listOf(map(1, 10, fingerprint = 91), map(9, 90))

        fun category(hold: ConflictHoldEntity, remote: List<RemoteContact> = listOf(readable(1), readable(9)), complete: Boolean = true) =
            plan(remote, rows = rows, maps = maps, complete = complete, holds = listOf(hold)).only().let { it.category to it.hold }

        assertEquals(PlanCategory.CHANGED_ON_PROTON to held(1, 10, version = 2), category(held(1, 10, version = 2)))
        for ((why, hold) in listOf(
            "another VERSION" to held(1, 10, version = 1),
            "another fingerprint" to held(1, 10, version = 2, fingerprint = 7),
            "recorded gone" to held(1, 10, version = 2, fingerprint = null),
            "another kind" to held(1, 10, HoldKind.EDITED_BEFORE_KEYS, version = 2),
            "another row" to held(1, 11, version = 2),
            "no choice" to held(1, 10, choice = null, version = 2),
            "Use phone, another VERSION" to held(1, 10, choice = HoldChoice.USE_PHONE, version = 1),
            "another contact's" to held(2, 10, version = 2),
        )) {
            assertEquals(PlanCategory.CONFLICT to null, category(hold), why)
        }
        // An incomplete inventory that holds the contact sees its remote.
        assertEquals(PlanCategory.CHANGED_ON_PROTON to held(1, 10, version = 2), category(held(1, 10, version = 2), complete = false))
        // A contact the inventory left out: its remote isn't seen, so nothing is carried out.
        val gone = held(1, 10, version = 2, fingerprint = null)
        assertEquals(PlanCategory.DELETED_ON_PROTON to gone, category(gone, remote = listOf(readable(9))))
        assertEquals(PlanCategory.CHANGED_ON_PHONE to null, category(gone, remote = listOf(readable(9)), complete = false), "left out of an incomplete inventory")
        // A Use phone recorded gone recreates the contact only when a complete inventory shows it gone.
        val recreate = held(1, 10, choice = HoldChoice.USE_PHONE, version = 2, fingerprint = null)
        assertEquals(PlanCategory.NEW_ON_PHONE to recreate, category(recreate, remote = listOf(readable(9))))
        assertEquals(PlanCategory.CHANGED_ON_PHONE to null, category(recreate, remote = listOf(readable(9)), complete = false), "left out of an incomplete inventory")
    }

    @Test
    fun `a stored Use phone matching both sides is carried out as what it does, from what the row shows`() {
        // Counted as changed, new, or deleted on the phone.
        val rows = listOf(
            row(10, 1, dirty = true), row(20, 2, dirty = true), row(30, 3, dirty = true, deleted = true), row(50, 5, dirty = true),
            row(60, 6), row(70, 7, dirty = true),
        )
        val maps = listOf(map(1, 10, fingerprint = 91), map(2, 20), map(3, 30, fingerprint = 93), map(4, 40), map(5, 50, keyed = false), map(6, 60, fingerprint = null))
        val holds = listOf(
            // Edit vs edit; a phone edit vs a Proton deletion; a phone deletion vs a Proton edit; a missing row, which
            // offers only Restore here; a row edited before keys; a clean row with an empty baseline gone from Proton;
            // and a dirty row without a map.
            held(1, 10, choice = HoldChoice.USE_PHONE), held(2, 20, choice = HoldChoice.USE_PHONE, fingerprint = null),
            held(3, 30, choice = HoldChoice.USE_PHONE), held(4, 40, HoldKind.MISSING_ROW, choice = HoldChoice.USE_PHONE, version = null),
            held(5, 50, HoldKind.EDITED_BEFORE_KEYS, choice = HoldChoice.USE_PHONE), held(6, 60, choice = HoldChoice.USE_PHONE, fingerprint = null),
            held(7, 70, choice = HoldChoice.USE_PHONE),
        )
        val read = mutableListOf<Long>()

        val result = plan(
            listOf(readable(1), readable(3), readable(4), readable(5), readable(7)), rows = rows, maps = maps, holds = holds,
            edits = { read += it.rawContactId; editedOnPhone },
        )

        val byId = result.items.associateBy { checkNotNull(it.protonId) }
        assertEquals(
            mapOf(
                id(1) to PlanCategory.CHANGED_ON_PHONE, id(2) to PlanCategory.NEW_ON_PHONE, id(3) to PlanCategory.DELETED_ON_PHONE,
                id(4) to PlanCategory.MISSING_ROW, id(5) to PlanCategory.CHANGED_ON_PHONE, id(6) to PlanCategory.NEW_ON_PHONE,
                id(7) to PlanCategory.CHANGED_ON_PHONE,
            ),
            byId.mapValues { it.value.category },
        )
        assertEquals(setOf(id(1), id(2), id(3), id(5), id(6), id(7)), result.items.filter { it.carriedOut }.mapTo(HashSet()) { it.protonId })
        assertEquals(
            holds.associateBy { ProtonContactId(it.protonId) } - id(4), byId.mapValues { it.value.hold }.filterValues { it != null },
            "each carries the hold whose choice it carries out",
        )
        assertEquals(listOf(10L, 20L, 50L, 60L, 70L), read.sorted(), "each upload and recreate sends what its row shows")
        assertSame(editedOnPhone, byId.getValue(id(2)).edit)
        assertNull(byId.getValue(id(3)).edit)
        assertNull(byId.getValue(id(7)).map, "mapped again, with an empty baseline")
        val counts = result.counts()
        assertEquals(listOf(3, 2, 1, 0, 1), listOf(counts.changedOnPhone, counts.newOnPhone, counts.deletedOnPhone, counts.conflicts, counts.missingRows))
    }

    @Test
    fun `a stored Use phone whose row holds rows the app can't read back can't be synced`() {
        val unmapped = shows(named("Edited on the phone"), unmapped = 1)
        val rows = listOf(row(10, 1, dirty = true), row(20, 2, dirty = true))
        val maps = listOf(map(1, 10, fingerprint = 91), map(2, 20))
        val holds = listOf(held(1, 10, choice = HoldChoice.USE_PHONE), held(2, 20, choice = HoldChoice.USE_PHONE, fingerprint = null))

        val result = plan(listOf(readable(1)), rows = rows, maps = maps, holds = holds, edits = { unmapped })

        assertEquals(listOf(PlanCategory.UNMAPPED_ROWS, PlanCategory.UNMAPPED_ROWS), result.items.map { it.category })
        assertFalse(result.items.any { it.carriedOut })
        assertEquals(holds, result.items.map { it.hold }, "kept with its choice")
        assertEquals(2, result.counts().unreadable)
    }

    @Test
    fun `a stored Use phone over a Proton edit is a phone deletion under the phone guard`() {
        // C6, counted and stopped like any phone deletion.
        val rows = (1..10).map { row(it * 10L, it, deleted = true) } + row(110, 11, dirty = true, deleted = true)
        val maps = (1..10).map { map(it, it * 10L) } + map(11, 110, fingerprint = 911)
        val remote = (1..11).map { readable(it) }
        val holds = listOf(held(11, 110, choice = HoldChoice.USE_PHONE))

        val guarded = plan(remote, rows = rows, maps = maps, holds = holds)
        val confirmed = plan(remote, rows = rows, maps = maps, holds = holds, confirmed = 11)

        assertTrue(guarded.bulkGuardPhone)
        assertEquals(11, guarded.phoneDeletionsWaiting)
        val chosen = guarded.items.single { it.protonId == id(11) }
        assertEquals(PlanCategory.DELETED_ON_PHONE to holds.single(), chosen.category to chosen.hold)
        assertFalse(chosen.carriedOut)
        assertTrue(confirmed.items.single { it.protonId == id(11) }.carriedOut, "the user's confirmation lifts it")
    }

    @Test
    fun `while the remote guard stops, a choice recorded as gone isn't carried out`() {
        // The guard doesn't trust the inventory's absences.
        val rows = (1..10).map { row(it * 10L, it, dirty = it == 1) } + row(990, 99)
        val maps = (1..10).map { map(it, it * 10L) } + map(99, 990)

        val result = plan(listOf(readable(99)), rows = rows, maps = maps, holds = listOf(held(1, 10, fingerprint = null)))
        // Nor is a Use phone, which would recreate on Proton a contact the inventory may only have missed.
        val recreate = plan(listOf(readable(99)), rows = rows, maps = maps, holds = listOf(held(1, 10, choice = HoldChoice.USE_PHONE, fingerprint = null)))

        assertTrue(result.bulkGuardRemote)
        assertEquals(PlanCategory.CONFLICT, result.items.single { it.protonId == id(1) }.category)
        assertEquals(PlanCategory.CONFLICT to null, recreate.items.single { it.protonId == id(1) }.let { it.category to it.hold })
    }

    @Test
    fun `nothing is classified as missing after an incomplete inventory`() {
        val rows = listOf(row(10, 1), row(11, 2, dirty = true), row(12, 3, deleted = true))

        val result = plan(emptyList(), rows = rows, maps = listOf(map(1, 10), map(2, 11), map(3, 12)), complete = false)

        assertEquals(listOf(PlanCategory.CHANGED_ON_PHONE, PlanCategory.DELETED_ON_PHONE), result.items.map { it.category }, "only what the phone itself says")
        assertFalse(result.bulkGuardRemote)
        assertFalse(result.bulkGuardPhone)
    }

    @Test
    fun `a SOURCE_ID row without a map is repaired with an empty baseline`() {
        val result = plan(listOf(readable(1)), rows = listOf(row(10, 1)))

        assertEquals(listOf(MapRepair(id(1), 10)), result.mapRepairs)
        assertEquals(PlanCategory.CHANGED_ON_PROTON, result.only().category, "an empty baseline counts as changed")
        assertTrue(result.only().carriedOut)
    }

    @Test
    fun `a SOURCE_ID row without a map is repaired even when the remote is missing`() {
        val result = plan(emptyList(), rows = listOf(row(10, 1)), complete = false)

        assertEquals(listOf(MapRepair(id(1), 10)), result.mapRepairs)
        assertEquals(emptyList<PlanItem>(), result.items)
    }

    @Test
    fun `a map with an empty baseline counts as changed`() {
        val result = plan(listOf(readable(1)), rows = listOf(row(10, 1)), maps = listOf(map(1, 10, fingerprint = null)))

        assertEquals(PlanCategory.CHANGED_ON_PROTON, result.only().category)
        assertEquals(emptyList<MapRepair>(), result.mapRepairs)
    }

    @Test
    fun `SOURCE_ID wins over a map that points at another row`() {
        // The map says row 5, but row 7 holds the Proton ID; row 5 holds none.
        val result = plan(listOf(readable(1)), rows = listOf(row(5, null), row(7, 1)), maps = listOf(map(1, 5)))

        val paired = result.items.single { it.protonId == id(1) }
        assertEquals(7L, paired.row?.rawContactId)
        assertEquals(PlanCategory.CHANGED_ON_PROTON, paired.category, "the map's baseline was for another row")
        assertEquals(listOf(MapRepair(id(1), 7)), result.mapRepairs)
        assertEquals(PlanCategory.NEW_ON_PHONE, result.items.single { it.row?.rawContactId == 5L }.category)
    }

    @Test
    fun `a map whose row is gone is a missing row, held`() {
        // The map's raw ID now belongs to a row made on the phone, which is left alone.
        val result = plan(listOf(readable(1)), rows = listOf(row(9, null)), maps = listOf(map(1, 9)))

        val missing = result.items.single { it.protonId == id(1) }
        assertEquals(PlanCategory.MISSING_ROW, missing.category)
        assertFalse(missing.carriedOut)
        assertNull(missing.row)
        assertEquals(map(1, 9), missing.map, "the map names the row it's held for")
        assertEquals("Contact 1", missing.displayName)
        assertEquals(PlanCategory.NEW_ON_PHONE, result.items.single { it.row?.rawContactId == 9L }.category, "not paired, not recreated")
        assertEquals(emptyList<MapRepair>(), result.mapRepairs)
    }

    @Test
    fun `a stale map naming a reused raw ID stays a missing row`() {
        // Android reused row 7 for proton-id-2, which has no map yet; proton-id-1's map still names row 7.
        val result = plan(listOf(readable(1), readable(2)), rows = listOf(row(7, 2)), maps = listOf(map(1, 7)))

        assertEquals(PlanCategory.MISSING_ROW, result.items.single { it.protonId == id(1) }.category)
        assertEquals(PlanCategory.CHANGED_ON_PROTON, result.items.single { it.protonId == id(2) }.category)
        assertEquals(listOf(MapRepair(id(2), 7)), result.mapRepairs)
    }

    @Test
    fun `a map naming another row is repaired when the remote is absent too`() {
        val result = plan(emptyList(), rows = listOf(row(7, 1)), maps = listOf(map(1, 5)), complete = false)

        assertEquals(listOf(MapRepair(id(1), 7)), result.mapRepairs)
    }

    @Test
    fun `a tombstone is repaired, unless it is deleted on both sides`() {
        val present = plan(listOf(readable(1)), rows = listOf(row(10, 1, deleted = true)))
        val gone = plan(emptyList(), rows = listOf(row(10, 1, deleted = true)))

        val unknown = plan(emptyList(), rows = listOf(row(10, 1, deleted = true)), complete = false)

        assertEquals(listOf(MapRepair(id(1), 10)), present.mapRepairs)
        assertEquals(listOf(MapRepair(id(1), 10)), unknown.mapRepairs, "after an incomplete inventory it may still be on Proton")
        assertEquals(emptyList<MapRepair>(), gone.mapRepairs, "nothing left to protect")
    }

    @Test
    fun `a blank SOURCE_ID counts as none`() {
        val result = plan(listOf(readable(1)), rows = listOf(DeviceContact(10, "", dirty = false, deleted = false, version = 1, displayName = "Blank")))

        assertEquals(listOf(PlanCategory.NEW_FROM_PROTON, PlanCategory.NEW_ON_PHONE), result.items.map { it.category })
        assertEquals(emptyList<MapRepair>(), result.mapRepairs)
    }

    @Test
    fun `each remote contact appears once`() {
        assertThrows(IllegalArgumentException::class.java) { plan(listOf(readable(1), readable(1, 2))) }
    }

    @Test
    fun `an unreadable contact's row without a map is still repaired`() {
        val result = plan(listOf(RemoteContact.Unreadable(id(1), print(1), UnreadableReason.DECRYPT)), rows = listOf(row(10, 1)))

        assertEquals(listOf(MapRepair(id(1), 10)), result.mapRepairs)
    }

    @Test
    fun `an unreadable contact with duplicate rows is a duplicate`() {
        val unreadable = RemoteContact.Unreadable(id(1), print(1), UnreadableReason.PARSE)

        val item = plan(listOf(unreadable), rows = listOf(row(10, 1, name = "First"), row(11, 1))).only()

        assertEquals(PlanCategory.DUPLICATE_ROWS, item.category)
        assertEquals("First", item.displayName)
    }

    @Test
    fun `rows sharing a SOURCE_ID are one duplicate, held, never repaired`() {
        val rows = listOf(row(10, 1), row(11, 1, dirty = true), row(20, 2), row(21, 2))

        val result = plan(listOf(readable(1, fingerprint = 99)), rows = rows, complete = true)

        assertEquals(listOf(PlanCategory.DUPLICATE_ROWS, PlanCategory.DUPLICATE_ROWS), result.items.map { it.category })
        assertEquals(listOf(id(1), id(2)), result.items.map { it.protonId }, "one per Proton ID, remote or not")
        assertEquals(listOf("Contact 1", "Row 20"), result.items.map { it.displayName }, "the remote name, else the first row's")
        assertTrue(result.items.none { it.carriedOut })
        assertEquals(emptyList<MapRepair>(), result.mapRepairs)
        assertEquals(2, result.counts().duplicateRows)
    }

    @Test
    fun `an unreadable contact is reported with its reason and its row stays`() {
        val unreadable = RemoteContact.Unreadable(id(1), print(99), UnreadableReason.VERIFY)

        val withRow = plan(listOf(unreadable), rows = listOf(row(10, 1, name = "Ann")), maps = listOf(map(1, 10))).only()
        val withoutRow = plan(listOf(RemoteContact.Unreadable(id(2), print(2), UnreadableReason.PARSE))).only()

        assertEquals(PlanCategory.UNREADABLE to UnreadableReason.VERIFY, withRow.category to withRow.unreadableReason)
        assertFalse(withRow.carriedOut, "the existing row stays")
        assertEquals("Ann", withRow.displayName, "the phone's name, since the card can't be read")
        assertEquals(PlanCategory.UNREADABLE to UnreadableReason.PARSE, withoutRow.category to withoutRow.unreadableReason)
        assertFalse(withoutRow.carriedOut, "nothing inserted")
        assertNull(withoutRow.displayName)
    }

    @Test
    fun `phone rows without a SOURCE_ID are new on the phone, tombstones ignored`() {
        val result = plan(emptyList(), rows = listOf(row(30, null, dirty = true, name = "Made here"), row(31, null, deleted = true)))

        val item = result.only()
        assertEquals(PlanCategory.NEW_ON_PHONE, item.category)
        assertEquals("Made here", item.displayName)
        assertTrue(item.carriedOut, "created")
        assertSame(editedOnPhone, item.edit, "read back, so its cards can be made")
        assertNull(item.map)
        assertEquals(emptyList<MapRepair>(), result.mapRepairs)
    }

    @Test
    fun `a row made on the phone with rows the app can't read back is never created`() {
        // The first write from Proton after the create would lose them: can't be synced.
        val shown = shows(named("Made here"), unmapped = 1)

        val item = plan(emptyList(), rows = listOf(row(30, null, dirty = true, name = "Made here")), edits = { shown }).only()

        assertEquals(PlanCategory.UNMAPPED_ROWS, item.category)
        assertFalse(item.carriedOut)
        assertSame(shown, item.edit)
        assertEquals("Made here", item.displayName)
        assertEquals(ReportCounts(0, 0, 0, 0, 0, 0, 0, false, false, 1, 0, 0), plan(emptyList(), rows = listOf(row(30, null)), edits = { shown }).counts())
    }

    @Test
    fun `the display name comes from the contact`() {
        val cases = listOf(
            ContactGraph(uid = null, formattedName = "Formatted", name = StructuredName("Given", "Family")) to "Formatted",
            ContactGraph(uid = null, formattedName = null, name = StructuredName("Given", "Family")) to "Given Family",
            ContactGraph(uid = null, formattedName = null, name = StructuredName(null, "Family")) to "Family",
            ContactGraph(uid = null, formattedName = null, emails = listOf(TypedEmail("e@example.test", EmailType.Home)), phones = listOf(TypedPhone("+1 555", PhoneType.Home))) to "e@example.test",
            ContactGraph(uid = null, formattedName = null, name = StructuredName(null, null, prefix = "Dr"), emails = listOf(TypedEmail("e@example.test", EmailType.Home))) to "e@example.test",
            ContactGraph(uid = null, formattedName = null, phones = listOf(TypedPhone("+1 555", PhoneType.Home))) to "+1 555",
            ContactGraph(uid = null, formattedName = null) to null,
        )
        cases.forEach { (graph, name) -> assertEquals(name, plan(listOf(readable(1, graph = graph))).only().displayName) }
    }

    @Test
    fun `a conflict shows the remote name`() {
        val item = plan(listOf(readable(1, fingerprint = 99, graph = named("Remote"))), rows = listOf(row(10, 1, dirty = true, name = "Phone")), maps = listOf(map(1, 10))).only()

        assertEquals("Remote", item.displayName)
    }

    @Test
    fun `counts follow the categories`() {
        // Each category gets a different count, so no two can be swapped unseen.
        val remote = mutableListOf<RemoteContact>()
        val rows = mutableListOf<DeviceContact>()
        val maps = mutableListOf<ContactMapEntity>()
        var n = 0
        fun next() = ++n
        repeat(1) { remote += readable(next()) } // new from Proton: 1
        repeat(2) { val i = next(); remote += readable(i, 999); rows += row(i.toLong(), i); maps += map(i, i.toLong()) } // changed on Proton: 2
        repeat(3) { val i = next(); rows += row(i.toLong(), i); maps += map(i, i.toLong()) } // deleted on Proton: 3
        repeat(4) { rows += row(1000L + next(), null) } // new on the phone: 4
        repeat(5) { val i = next(); remote += readable(i); rows += row(i.toLong(), i, dirty = true); maps += map(i, i.toLong()) } // changed on the phone: 5
        repeat(6) { val i = next(); remote += readable(i); rows += row(i.toLong(), i, deleted = true); maps += map(i, i.toLong()) } // deleted on the phone: 6
        repeat(4) { val i = next(); remote += readable(i, 999); rows += row(i.toLong(), i, dirty = true); maps += map(i, i.toLong()) } // conflicts: 4
        repeat(3) { val i = next(); remote += readable(i); rows += row(i.toLong(), i, dirty = true); maps += map(i, i.toLong(), keyed = false) } // edited before keys: 3 more
        repeat(2) { val i = next(); remote += readable(i); rows += row(i.toLong(), i); maps += map(i, i.toLong(), keyed = false) } // written before keys: not counted
        repeat(8) { remote += RemoteContact.Unreadable(id(next()), print(0), UnreadableReason.DECRYPT) } // unreadable: 8
        repeat(9) { val i = next(); remote += readable(i); rows += row(2000L + i, i); rows += row(3000L + i, i) } // duplicates: 9
        repeat(10) { val i = next(); remote += readable(i); maps += map(i, 4000L + i) } // missing rows: 10

        val counts = plan(remote, rows = rows, maps = maps).counts()

        assertEquals(
            ReportCounts(
                newFromProton = 1, changedOnProton = 2, deletedOnProton = 3, newOnPhone = 4, changedOnPhone = 5, deletedOnPhone = 6,
                // 6 tombstones of 25 mapped contacts stop sending phone deletions to Proton; 3 absences don't stop
                // deleting rows.
                conflicts = 7, bulkGuardRemote = false, bulkGuardPhone = true, unreadable = 8, duplicateRows = 9, missingRows = 10,
            ),
            counts,
        )
    }

    @Test
    fun `the bulk guard stops at 10 missing, or 20 percent of 20 or more mapped`() {
        fun guard(missing: Int, mapped: Int): Boolean {
            val rows = (1..mapped).map { row(it.toLong(), it) }
            val remote = ((missing + 1)..mapped).map { readable(it) }
            val plan = plan(remote, rows = rows, maps = (1..mapped).map { map(it, it.toLong()) })
            assertFalse(plan.bulkGuardPhone, "no tombstones")
            return plan.bulkGuardRemote
        }
        assertTrue(guard(missing = 10, mapped = 100))
        assertFalse(guard(missing = 9, mapped = 100))
        assertTrue(guard(missing = 4, mapped = 20))
        assertFalse(guard(missing = 3, mapped = 20))
        assertFalse(guard(missing = 4, mapped = 19), "the share counts only from 20 mapped contacts")
        assertFalse(guard(missing = 0, mapped = 5))
    }

    @Test
    fun `duplicate rows don't count as mapped contacts`() {
        // 19 mapped contacts, 4 of them missing on Proton, and one Proton ID on two rows: 4 of 19 doesn't stop.
        val rows = (1..19).map { row(it.toLong(), it) } + listOf(row(50, 50), row(51, 50))
        val result = plan((5..19).map { readable(it) }, rows = rows, maps = (1..19).map { map(it, it.toLong()) })

        assertEquals(4, result.counts().deletedOnProton)
        assertNoGuard(result)
    }

    /** Neither direction stops. */
    private fun assertNoGuard(plan: SyncPlan, message: String? = null) {
        assertFalse(plan.bulkGuardRemote, message)
        assertFalse(plan.bulkGuardPhone, message)
    }

    @Test
    fun `the bulk guard counts dirty absences and changed tombstones too`() {
        // 5 clean and 5 dirty rows missing from Proton: 10 absences, though only 5 are plain deletions.
        val absent = (1..5).map { row(it.toLong(), it) } + (6..10).map { row(it.toLong(), it, dirty = true) }
        val absences = plan(listOf(readable(99)), rows = absent + row(99, 99), maps = (1..10).map { map(it, it.toLong()) })
        assertTrue(absences.bulkGuardRemote)
        assertFalse(absences.bulkGuardPhone)
        // 10 clean rows missing with empty baselines: conflicts, never deleted, but still 10 absences.
        val unmapped = plan(listOf(readable(99)), rows = (1..10).map { row(it.toLong(), it) } + row(99, 99), maps = listOf(map(99, 99)))
        assertEquals(10, unmapped.counts().conflicts)
        assertTrue(unmapped.bulkGuardRemote)
        // 10 tombstones whose remotes changed: conflicts, but still 10 phone deletions waiting.
        val tombstones = (1..10).map { row(it.toLong(), it, deleted = true) }
        val deletions = plan((1..10).map { readable(it, 999) }, rows = tombstones, maps = (1..10).map { map(it, it.toLong()) })
        assertTrue(deletions.bulkGuardPhone)
        assertFalse(deletions.bulkGuardRemote)
    }

    @Test
    fun `the bulk guard ignores what it can't know or what is gone on both sides`() {
        val ten = (1..10).map { map(it, it.toLong()) }
        assertNoGuard(plan(listOf(readable(99)), rows = (1..10).map { row(it.toLong(), it) } + row(99, 99), maps = ten, complete = false), "after an incomplete inventory")
        assertNoGuard(plan(listOf(readable(99)), rows = (1..10).map { row(it.toLong(), it, deleted = true) } + row(99, 99), maps = ten), "deleted on both sides")
    }

    @Test
    fun `rows deleted on both sides don't dilute the guard's share`() {
        // 25 Proton IDs on one row each: 5 deleted on both sides, and 4 of the 20 live ones missing on Proton.
        val rows = (1..5).map { row(it.toLong(), it, deleted = true) } + (6..25).map { row(it.toLong(), it) }
        val result = plan((10..25).map { readable(it) }, rows = rows, maps = (1..25).map { map(it, it.toLong()) })

        assertEquals(4, result.counts().deletedOnProton)
        assertTrue(result.bulkGuardRemote, "4 of 20 contacts still on either side")
    }

    @Test
    fun `phone deletions count after an incomplete inventory`() {
        val rows = (1..10).map { row(it.toLong(), it, deleted = true) } + row(99, 99)

        assertTrue(plan(listOf(readable(99)), rows = rows, maps = (1..10).map { map(it, it.toLong()) } + map(99, 99), complete = false).bulkGuardPhone)
    }

    @Test
    fun `after an incomplete inventory a tombstone is still a mapped contact`() {
        // Its remote may still be there, so it isn't deleted on both sides: 4 phone deletions of 20 mapped contacts.
        val rows = (1..4).map { row(it.toLong(), it, deleted = true) } + (5..20).map { row(it.toLong(), it) }

        assertTrue(plan((5..20).map { readable(it) }, rows = rows, maps = (1..20).map { map(it, it.toLong()) }, complete = false).bulkGuardPhone)
    }

    @Test
    fun `the bulk guard stops on an empty complete inventory while mapped rows exist`() {
        val empty = plan(emptyList(), rows = listOf(row(10, 1)), maps = listOf(map(1, 10)))
        assertTrue(empty.bulkGuardRemote, "deletions on the phone, from Proton's absences")
        assertFalse(empty.bulkGuardPhone)
        assertNoGuard(plan(emptyList(), rows = listOf(row(10, 1, deleted = true)), maps = listOf(map(1, 10))), "only tombstones: nothing at risk")
        assertNoGuard(plan(emptyList(), rows = listOf(row(10, 1)), maps = listOf(map(1, 10)), complete = false))
        assertNoGuard(plan(emptyList()))
    }

    @Test
    fun `the bulk guard watches phone deletions too`() {
        fun guard(tombstones: Int, mapped: Int): Boolean {
            val rows = (1..mapped).map { row(it.toLong(), it, deleted = it <= tombstones) }
            val plan = plan((1..mapped).map { readable(it) }, rows = rows, maps = (1..mapped).map { map(it, it.toLong()) })
            assertFalse(plan.bulkGuardRemote, "nothing missing on Proton")
            return plan.bulkGuardPhone
        }
        assertTrue(guard(tombstones = 10, mapped = 100))
        assertFalse(guard(tombstones = 9, mapped = 100))
        assertTrue(guard(tombstones = 4, mapped = 20))
        assertFalse(guard(tombstones = 4, mapped = 19))
    }

    @Test
    fun `the plan gives how many phone deletions the guard counted`() {
        // 10 tombstones, 3 of them conflicts, and one deleted on both sides, which doesn't count.
        val rows = (1..11).map { row(it.toLong(), it, deleted = true) } + row(20, 20)
        val maps = (1..11).map { map(it, it.toLong()) } + map(20, 20)
        val remote = (1..10).map { readable(it, fingerprint = if (it <= 3) 99 else it) } + readable(20)

        val result = plan(remote, rows = rows, maps = maps)

        assertEquals(10, result.phoneDeletionsWaiting)
        assertTrue(result.bulkGuardPhone)
        assertEquals(0, plan(listOf(readable(1)), rows = listOf(row(10, 1)), maps = listOf(map(1, 10))).phoneDeletionsWaiting)
        assertEquals(2, plan(emptyList(), rows = listOf(row(10, 1, deleted = true), row(11, 2, deleted = true)), maps = listOf(map(1, 10), map(2, 11)), complete = false).phoneDeletionsWaiting, "after an incomplete inventory, every one")
    }

    @Test
    fun `a confirmation lifts the phone guard only while no more are waiting than it counted`() {
        // The confirmed run counts again.
        val rows = (1..10).map { row(it.toLong(), it, deleted = true) }
        val maps = (1..10).map { map(it, it.toLong()) }
        val remote = (1..10).map { readable(it, fingerprint = if (it <= 3) 99 else it) }

        for (confirmed in listOf(10, 11)) {
            val result = plan(remote, rows = rows, maps = maps, confirmed = confirmed)

            assertFalse(result.bulkGuardPhone, "confirmed $confirmed")
            assertEquals(7, result.items.count { it.category == PlanCategory.DELETED_ON_PHONE && it.carriedOut }, "the plain ones")
            assertEquals(3, result.items.count { it.category == PlanCategory.CONFLICT }, "conflicts stay held")
        }
        val more = plan(remote, rows = rows, maps = maps, confirmed = 9)
        assertTrue(more.bulkGuardPhone, "more are waiting: it asks again")
        assertTrue(more.items.filter { it.category == PlanCategory.DELETED_ON_PHONE }.none { it.carriedOut })
        assertEquals(10, more.phoneDeletionsWaiting, "the new count")
    }

    @Test
    fun `a confirmation of phone deletions never lifts the other direction's guard`() {
        val result = plan(emptyList(), rows = listOf(row(10, 1), row(11, 2, deleted = true)), maps = listOf(map(1, 10), map(2, 11)), confirmed = 100)

        assertTrue(result.bulkGuardRemote)
        assertFalse(result.items.single { it.category == PlanCategory.DELETED_ON_BOTH }.carriedOut)
    }

    @Test
    fun `the plan gives how many deletions on the phone the guard counted`() {
        // 10 missing on Proton: 6 plain, 3 dirty, 1 with an empty baseline; one deleted on both sides doesn't count.
        val rows = (1..10).map { row(it.toLong(), it, dirty = it in 7..9) } + row(11, 11, deleted = true) + row(20, 20)
        val maps = (1..9).map { map(it, it.toLong()) } + map(11, 11) + map(20, 20)

        val result = plan(listOf(readable(20)), rows = rows, maps = maps)

        assertEquals(10, result.remoteDeletionsWaiting)
        assertTrue(result.bulkGuardRemote)
        assertEquals(0, plan(listOf(readable(1)), rows = listOf(row(10, 1)), maps = listOf(map(1, 10))).remoteDeletionsWaiting)
        assertEquals(0, plan(emptyList(), rows = rows, maps = maps, complete = false).remoteDeletionsWaiting, "nothing is missing from an incomplete inventory")
        val empty = plan(emptyList(), rows = listOf(row(10, 1), row(11, 2), row(12, 3, deleted = true)), maps = listOf(map(1, 10), map(2, 11), map(3, 12)))
        assertEquals(2, empty.remoteDeletionsWaiting, "an empty inventory: every live mapped row")
    }

    @Test
    fun `a confirmation lifts the remote guard only while no more are missing than it counted`() {
        // The confirmed run counts again, and deletes only rows still missing and still clean.
        val rows = (1..10).map { row(it.toLong(), it, dirty = it <= 3) } + row(11, 11, deleted = true) + row(20, 20)
        val maps = (1..11).map { map(it, it.toLong()) } + map(20, 20) + map(60, 60)

        for (confirmed in listOf(10, 11)) {
            val result = plan(listOf(readable(20)), rows = rows, maps = maps, confirmedRemote = confirmed)

            assertFalse(result.bulkGuardRemote, "confirmed $confirmed")
            assertEquals(7, result.items.count { it.category == PlanCategory.DELETED_ON_PROTON && it.carriedOut }, "the clean ones")
            assertEquals(3, result.items.count { it.category == PlanCategory.CONFLICT }, "dirty ones stay held")
            assertTrue(result.items.single { it.category == PlanCategory.DELETED_ON_BOTH }.carriedOut, "Proton's absences trusted again")
            assertEquals(listOf(id(60)), result.staleMaps)
        }
        val more = plan(listOf(readable(20)), rows = rows, maps = maps, confirmedRemote = 9)
        assertTrue(more.bulkGuardRemote, "more are missing: it asks again")
        assertTrue(more.items.none { it.carriedOut })
        assertEquals(10, more.remoteDeletionsWaiting, "the new count")
        assertEquals(emptyList<ProtonContactId>(), more.staleMaps)
    }

    @Test
    fun `a confirmation lifts the guard an empty inventory set`() {
        val result = plan(emptyList(), rows = listOf(row(10, 1), row(11, 2)), maps = listOf(map(1, 10), map(2, 11)), confirmedRemote = 2)

        assertFalse(result.bulkGuardRemote)
        assertTrue(result.items.all { it.category == PlanCategory.DELETED_ON_PROTON && it.carriedOut })
        assertTrue(plan(emptyList(), rows = listOf(row(10, 1), row(11, 2)), maps = listOf(map(1, 10), map(2, 11)), confirmedRemote = 1).bulkGuardRemote)
    }

    @Test
    fun `a confirmation of deletions on the phone never lifts the other direction's guard`() {
        val rows = (1..10).map { row(it.toLong(), it, deleted = true) } + row(20, 20) + row(21, 21)
        val result = plan((1..10).map { readable(it) } + readable(20), rows = rows, maps = (1..10).map { map(it, it.toLong()) } + map(20, 20) + map(21, 21), confirmedRemote = 100)

        assertTrue(result.bulkGuardPhone)
        assertFalse(result.bulkGuardRemote, "one missing: below the guard anyway")
        assertTrue(result.items.filter { it.category == PlanCategory.DELETED_ON_PHONE }.none { it.carriedOut })
    }

    @Test
    fun `toString shows no names or IDs`() {
        val result = plan(listOf(readable(1, graph = named("Secret Name"))), rows = listOf(row(40, null, name = "Phone Name")))

        assertEquals(2, result.items.size)
        val text = result.toString() + result.items.joinToString() + result.mapRepairs.joinToString()
        listOf("Secret Name", "Phone Name", "proton-id-1").forEach { assertFalse(text.contains(it), it) }
        val edit = shows(named("Secret Name"), PhonePhoto.Read("a".repeat(64))).toString() + PhonePhoto.Unreadable("a".repeat(64))
        listOf("Secret Name", "a".repeat(16)).forEach { assertFalse(edit.contains(it), "$it in $edit") }
    }
}
