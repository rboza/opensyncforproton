package dev.rboza.android.opensyncforproton.sync.engine

import dev.rboza.android.opensyncforproton.crypto.CryptoResult
import dev.rboza.android.opensyncforproton.crypto.PlainCard
import dev.rboza.android.opensyncforproton.crypto.RejectReason
import dev.rboza.android.opensyncforproton.mapping.ContactGraph
import dev.rboza.android.opensyncforproton.mapping.ContactPhoto
import dev.rboza.android.opensyncforproton.mapping.MappingIssueCode
import dev.rboza.android.opensyncforproton.proton.CardDto
import dev.rboza.android.opensyncforproton.proton.ContactIdDto
import dev.rboza.android.opensyncforproton.proton.CreateReply
import dev.rboza.android.opensyncforproton.proton.HvDetails
import dev.rboza.android.opensyncforproton.proton.ProtonContactId
import dev.rboza.android.opensyncforproton.proton.ProtonError
import dev.rboza.android.opensyncforproton.proton.contactFingerprint
import dev.rboza.android.opensyncforproton.sync.db.Blocker
import dev.rboza.android.opensyncforproton.sync.db.ContactMapEntity
import dev.rboza.android.opensyncforproton.sync.db.PendingContactCreateEntity
import dev.rboza.android.opensyncforproton.sync.plan.PlanCategory
import dev.rboza.android.opensyncforproton.sync.plan.ReportCounts
import dev.rboza.android.opensyncforproton.sync.provider.ContactWriteResult
import dev.rboza.android.opensyncforproton.sync.provider.DataRow
import dev.rboza.android.opensyncforproton.sync.provider.DeviceContact
import dev.rboza.android.opensyncforproton.sync.provider.ExistingRow
import dev.rboza.android.opensyncforproton.sync.provider.FailureReason
import dev.rboza.android.opensyncforproton.sync.provider.ProviderUnavailableException
import dev.rboza.android.opensyncforproton.sync.provider.ScaledPhoto
import dev.rboza.android.opensyncforproton.sync.provider.dataRows
import java.io.IOException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Creating contacts on Proton: docs/sync.md § The plan, step 6, and § Writes. Every value is made up for the test.
class SyncCreateTest : EngineHarness() {
    /** A row made on the phone: no `SOURCE_ID`, dirty, showing [name]. */
    private fun newRow(raw: Long, name: String? = "Made here $raw") = DeviceContact(raw, null, dirty = true, deleted = false, version = 1, displayName = name).also {
        phone.rows += it
        phone.dataRowsByRaw[raw] = dataRows(ContactGraph(uid = null, formattedName = name), null)
    }

    /** The fake mapper's contact [n], as its rows hold it on the phone. */
    private fun remoteRows(n: Int): List<DataRow> = dataRows(ContactGraph(uid = null, formattedName = "Contact proton-id-$n"), null)

    private fun counts(newOnPhone: Int, unreadable: Int = 0) = ReportCounts(0, 0, 0, newOnPhone, 0, 0, 0, false, false, unreadable, 0, 0)

    @Test
    fun `a dry run makes each new contact's cards from what the phone shows, with a UID it never stores`() = runTest {
        // Whatever stops a create shows before sync is on; the UID is made again when it's sent.
        newRow(30, "Ann")
        phone.photoHashes[30] = null
        newRow(40, "Bob")

        val report = engine().dryRun(account)

        assertEquals(
            listOf(
                FakeMapper.Created(mapper.created[0].phone, "urn:uuid:test-1", "0.2.0-test", photo = null),
                FakeMapper.Created(mapper.created[1].phone, "urn:uuid:test-2", "0.2.0-test", photo = listOf(40.toByte())),
            ),
            mapper.created,
            "the phone's photo, scaled",
        )
        assertEquals(listOf("Ann", "Bob"), mapper.created.map { it.phone.formattedName }, "what the rows show")
        assertEquals(listOf(listOf(40.toByte()) to 720), photos.calls)
        assertEquals(
            listOf(
                listOf(PlainCard(2, "created(urn:uuid:test-1)"), PlainCard(3, "created(Ann)")),
                listOf(PlainCard(2, "created(urn:uuid:test-2)"), PlainCard(3, "created(Bob)")),
            ),
            crypto.encrypted,
            "signed and encrypted",
        )
        assertEquals(listOf(PlanCategory.NEW_ON_PHONE, PlanCategory.NEW_ON_PHONE), report.plan!!.items.map { it.category })
        assertEquals(counts(newOnPhone = 2), report.counts())
        assertTrue(report.ready)
        assertEquals(listOf("C3 0"), calls, "nothing sent")
        assertEquals(listOf("read back 30", "read back 40", "photo 40"), events, "nothing written or mapped")
        assertTrue(keys.closed)
    }

    /** The map a bound create leaves: the re-read's fingerprint, and keys and the photo read back unless said. */
    private fun boundMap(n: Int, raw: Long, keyed: Boolean = true, baseline: String? = photoHashOf(raw)) =
        ContactMapEntity("user-id-1", "proton-id-$n", raw, contactFingerprint(ProtonContactId("proton-id-$n"), (api.pages.singleOrNull { it.id == "proton-id-$n" } ?: contact(n, 2, 3)).cards).value, keyed, baseline)

    /** The map a create bound and written from the re-read leaves: the photo and its thumbnail read back. */
    private fun writtenBound(n: Int, raw: Long) = boundMap(n, raw).copy(photoThumbnailBaseline = thumbnailHashOf(raw))

    /** Proton holds contact [n] (cards of types 2 and 3) outside the inventory: only C2 finds it. */
    private fun heldOutsideTheInventory(n: Int) {
        api.refetches["proton-id-$n"] = ArrayDeque(listOf(contact(n, 2, 3)))
    }

    private fun record(raw: Long, uid: String, version: Long = 1) = PendingContactCreateEntity("user-id-1", raw, uid, version)

    @Test
    fun `a new row is created once its record is saved, then bound and written from the re-read, and the record deleted`() = runTest {
        // After a successful create, the baseline comes from C2: the reply has no cards.
        newRow(30, "Ann")

        val report = engine().sync(account)

        assertEquals(listOf(listOf(record(30, "urn:uuid:test-1"))), api.recordsWhenCreated, "the record (row, UID, VERSION) saved before C4")
        assertEquals(listOf(listOf(CardDto(2, "encrypted(created(urn:uuid:test-1))", "signature"), CardDto(3, "encrypted(created(Ann))", "signature"))), api.created)
        assertEquals(listOf("C3 0", "C4", "C2 101"), calls)
        val write = phone.writes.single()
        assertEquals("proton-id-101", write.protonId)
        assertEquals(ExistingRow(30, 1, clearDirty = true, bind = true), write.existing, "guarded by the VERSION read when planning")
        assertEquals(remoteRows(101).map { it.mimeType }, write.rows.map { it.mimeType }, "written from the re-read, as a download does")
        assertEquals(listOf(writtenBound(101, 30)), mapDao.maps)
        assertEquals(emptyList<Any>(), pending.records)
        assertEquals(listOf("read back 30", "photo 30", "record 30", "apply proton-id-101", "read back 30", "map proton-id-101", "record deleted 30"), events)
        assertEquals(counts(newOnPhone = 1), report.counts())
        assertTrue(report.ready)
        assertTrue(keys.closed)
    }

    @Test
    fun `a waiting record is sent again with its UID, and a created contact is bound`() = runTest {
        // A crash, or a lost reply: the same create, Overwrite 0.
        newRow(30, "Ann")
        pending.records += record(30, "urn:uuid:old")

        engine().sync(account)

        assertEquals(listOf("urn:uuid:old"), mapper.created.map { it.uid })
        assertEquals(listOf(listOf(record(30, "urn:uuid:old"))), api.recordsWhenCreated, "not saved again")
        assertEquals(ExistingRow(30, 1, clearDirty = true, bind = true), phone.writes.single().existing)
        assertEquals(listOf(writtenBound(101, 30)), mapDao.maps)
        assertEquals(emptyList<Any>(), pending.records)
    }

    @Test
    fun `a waiting record sent again after the row changed is guarded by the VERSION the plan read`() = runTest {
        // This run's cards are what Proton now holds, so the row is written from the re-read.
        newRow(30, "Ann")
        pending.records += record(30, "urn:uuid:old", version = 0)

        engine().sync(account)

        assertEquals(ExistingRow(30, 1, clearDirty = true, bind = true), phone.writes.single().existing)
        assertEquals(listOf(writtenBound(101, 30)), mapDao.maps)
    }

    @Test
    fun `a UID lookup that fails after a 2500 keeps the record and stops sending`() = runTest {
        newRow(30, "Ann")
        newRow(40, "Bob")
        pending.records += record(30, "urn:uuid:old")
        api.createAnswers += CreateReply.UidExists
        api.listFailure = ProtonError.Network()

        val report = engine().sync(account)

        assertEquals(Blocker.PROTON_UNAVAILABLE, report.blocker)
        assertEquals(1, calls.count { it == "C4" }, "Bob isn't sent")
        assertEquals(listOf(record(30, "urn:uuid:old")), pending.records)
    }

    @Test
    fun `a UID lookup Proton refuses stops sending as a failed inventory request does`() = runTest {
        // A listing that fails isn't about one contact.
        newRow(30, "Ann")
        newRow(40, "Bob")
        pending.records += record(30, "urn:uuid:old")
        api.createAnswers += CreateReply.UidExists
        api.listFailure = ProtonError.Api(400, 2001)

        val report = engine().sync(account)

        assertEquals(Blocker.INVENTORY_INCOMPLETE, report.blocker)
        assertEquals(1, calls.count { it == "C4" })
        assertEquals(emptyList<Any>(), report.refused)

        // The same for a deleted row's record.
        phone.rows.clear(); calls.clear(); pending.records.clear()
        phone.rows += DeviceContact(50, null, dirty = true, deleted = true, version = 2, displayName = null)
        pending.records += record(50, "urn:uuid:found")
        assertEquals(Blocker.INVENTORY_INCOMPLETE, engine().sync(account).blocker)
    }

    @Test
    fun `a waiting record's resend refused as 2001 is looked up first, as the earlier send may have landed`() = runTest {
        // Nothing says which code Proton gives when the UID exists and the new cards are refused.
        for ((listing, kept) in listOf(
            listOf(ContactIdDto("proton-id-7", "urn:uuid:old")) to false,
            emptyList<ContactIdDto>() to false,
            listOf(ContactIdDto("proton-id-7", "urn:uuid:old"), ContactIdDto("proton-id-8", "urn:uuid:old")) to true,
        )) {
            phone.rows.clear(); pending.records.clear(); api.listed.clear(); calls.clear(); mapDao.maps.clear(); phone.writes.clear()
            newRow(30, "Ann")
            pending.records += record(30, "urn:uuid:old")
            api.createAnswers += CreateReply.CardRefused
            api.listed += listing
            heldOutsideTheInventory(7)

            val report = engine().sync(account)

            assertEquals(listOf("C4", "C1 0"), calls.filter { it == "C4" || it.startsWith("C1") }, "$listing")
            if (listing.size == 1) {
                assertEquals(listOf(writtenBound(7, 30)), mapDao.maps, "bound: the row hasn't changed since its record")
                assertEquals(emptyList<Any>(), report.refused)
            } else {
                assertEquals(listOf(NotSyncedReason.PROTON_REFUSED), report.refused.map { it.reason })
                assertEquals(emptyList<Any>(), mapDao.maps)
            }
            assertEquals(if (kept) listOf(record(30, "urn:uuid:old")) else emptyList(), pending.records, "$listing")
        }
    }

    @Test
    fun `a waiting record's 2001 whose lookup Proton refuses stops sending too`() = runTest {
        newRow(30, "Ann")
        newRow(40, "Bob")
        pending.records += record(30, "urn:uuid:old")
        api.createAnswers += CreateReply.CardRefused
        api.listFailure = ProtonError.Api(400, 2001)

        val report = engine().sync(account)

        assertEquals(Blocker.INVENTORY_INCOMPLETE, report.blocker)
        assertEquals(1, calls.count { it == "C4" })
        assertEquals(listOf(record(30, "urn:uuid:old")), pending.records)
    }

    @Test
    fun `a waiting record's 2001 whose lookup can't tell keeps the record`() = runTest {
        // An incomplete listing may hide the contact the earlier send made.
        newRow(30, "Ann")
        pending.records += record(30, "urn:uuid:old")
        api.createAnswers += CreateReply.CardRefused
        api.listed += List(50) { ContactIdDto("proton-id-7", "urn:uuid:other") }

        val report = engine().sync(account)

        assertEquals(listOf(NotSyncedReason.PROTON_REFUSED), report.refused.map { it.reason })
        assertEquals(listOf(record(30, "urn:uuid:old")), pending.records)
    }

    @Test
    fun `a waiting record's 2001 match for a row changed since its record is bound alone, its edit held`() = runTest {
        newRow(30, "Ann")
        pending.records += record(30, "urn:uuid:old", version = 0)
        api.createAnswers += CreateReply.CardRefused
        api.listed += ContactIdDto("proton-id-7", "urn:uuid:old")
        heldOutsideTheInventory(7)

        engine().sync(account)

        assertEquals(listOf("proton-id-7" to 30L), phone.bound)
        assertEquals(emptyList<Any>(), phone.writes)
        assertEquals(listOf(boundMap(7, 30, keyed = false, baseline = null)), mapDao.maps)
    }

    @Test
    fun `an upload-only sync with only a deleted row's record looks it up without keys`() = runTest {
        phone.rows += DeviceContact(40, null, dirty = true, deleted = true, version = 2, displayName = null)
        pending.records += record(40, "urn:uuid:none")

        engine().upload(account)

        assertEquals(listOf("C1 0"), calls)
        assertEquals(1, keys.unlocks, "only the login check's")
        assertTrue(keys.closed)
        assertEquals(emptyList<Any>(), pending.records)
    }

    @Test
    fun `a waiting record's 2001 whose lookup fails keeps the record and stops sending`() = runTest {
        newRow(30, "Ann")
        newRow(40, "Bob")
        pending.records += record(30, "urn:uuid:old")
        api.createAnswers += CreateReply.CardRefused
        api.listFailure = ProtonError.Network()

        val report = engine().sync(account)

        assertEquals(Blocker.PROTON_UNAVAILABLE, report.blocker)
        assertEquals(1, calls.count { it == "C4" })
        assertEquals(listOf(record(30, "urn:uuid:old")), pending.records)
    }

    @Test
    fun `a contact with the UID of a deleted row's waiting record isn't downloaded`() = runTest {
        // The lookup can't tell (two contacts hold it), so the record waits, and so does the contact.
        phone.rows += DeviceContact(40, null, dirty = true, deleted = true, version = 2, displayName = null)
        pending.records += record(40, "urn:uuid:found")
        api.pages += listOf(contact(7, 2, 3), contact(8))
        mapper.graphs["plain(data-7-2)"] = ContactGraph(uid = "urn:uuid:found", formattedName = "Ann")
        api.listed += listOf(ContactIdDto("proton-id-7", "urn:uuid:found"), ContactIdDto("proton-id-9", "urn:uuid:found"))

        engine().sync(account)

        assertEquals(listOf("proton-id-8"), phone.writes.map { it.protonId })
        assertEquals(listOf(record(40, "urn:uuid:found")), pending.records)
    }

    @Test
    fun `edits upload before new contacts are created`() = runTest {
        api.pages += contact(1)
        phone.rows += DeviceContact(10, "proton-id-1", dirty = true, deleted = false, version = 1, displayName = "Edited")
        mapDao.maps += ContactMapEntity("user-id-1", "proton-id-1", 10, contactFingerprint(ProtonContactId("proton-id-1"), contact(1).cards).value, rowsKeyed = true, photoBaseline = photoHashOf(10))
        newRow(30, "Ann")

        engine().sync(account)

        assertEquals(listOf("C3 0", "C2 1", "C5 1", "C2 1", "C4", "C2 101"), calls)
    }

    @Test
    fun `a created contact whose re-read photo the phone can't use is bound alone, counted with its reason`() = runTest {
        newRow(30, "Ann")
        // The phone's row has none, so only the re-read's photo is scaled.
        phone.photoHashes[30] = null
        mapper.photos["101"] = ContactPhoto(byteArrayOf(1, 2, 3), "image/jpeg")
        photos.result = ScaledPhoto.Malformed

        val report = engine().sync(account)

        assertEquals(listOf(NotSyncedReason.PHOTO_MALFORMED), report.refused.map { it.reason })
        assertEquals(listOf("proton-id-101" to 30L), phone.bound)
        assertEquals(listOf(boundMap(101, 30, baseline = null)), mapDao.maps, "the photo the plan read: none")
        assertEquals(emptyList<Any>(), pending.records)
    }

    @Test
    fun `a run cancelled once a created contact is written still saves its map and drops its record`() = runTest {
        newRow(30, "Ann")
        newRow(40, "Bob")
        phone.results["proton-id-102"] = ContactWriteResult.Stale
        lateinit var run: Job
        phone.onApply = { if (it.protonId == "proton-id-101") run.cancel() }

        run = launch { engine().sync(account) }
        run.join()

        assertTrue(run.isCancelled)
        assertEquals(listOf(writtenBound(101, 30)), mapDao.maps)
        assertEquals(emptyList<Any>(), pending.records.filter { it.rawContactId == 30L })
        assertEquals(0, calls.count { it == "C2 102" }, "nothing more")

        // And once a row is bound alone: Cy's create is the second, so it's proton-id-102, whose write is stale.
        phone.rows.clear(); pending.records.clear(); mapDao.maps.clear(); phone.onApply = {}
        newRow(50, "Cy")
        phone.onBind = { run.cancel() }
        run = launch { engine().sync(account) }
        run.join()
        assertEquals(listOf("proton-id-102"), mapDao.maps.map { it.protonId })
        assertEquals(emptyList<Any>(), pending.records)
    }

    @Test
    fun `a contact a deleted row's record found isn't downloaded again in the same run`() = runTest {
        // The row is gone, so the contact is held as a missing row, not made again (Planner: hold, don't recreate).
        pending.records += record(50, "urn:uuid:gone-row")
        api.pages += contact(8, 2, 3)
        api.listed += ContactIdDto("proton-id-8", "urn:uuid:gone-row")
        phone.rows += DeviceContact(60, "proton-id-60", dirty = false, deleted = false, version = 1, displayName = "Other")

        engine().sync(account)

        assertEquals(listOf(boundMap(8, 50, keyed = false, baseline = null)), mapDao.maps.filter { it.protonId == "proton-id-8" })
        assertTrue(phone.writes.none { it.protonId == "proton-id-8" }, "${phone.writes}")
    }

    @Test
    fun `a deleted row's record whose re-read fails is kept, and a failure not about the contact stops sending`() = runTest {
        phone.rows += DeviceContact(40, null, dirty = true, deleted = true, version = 2, displayName = null)
        pending.records += record(40, "urn:uuid:found")
        api.listed += ContactIdDto("proton-id-7", "urn:uuid:found")
        api.refetches["proton-id-7"] = ArrayDeque(listOf(ProtonError.Network()))

        val report = engine().sync(account)

        assertEquals(Blocker.PROTON_UNAVAILABLE, report.blocker)
        assertEquals(listOf(record(40, "urn:uuid:found")), pending.records)
        assertEquals(emptyList<Any>(), phone.bound)
    }

    @Test
    fun `deleted rows' records alone unlock no keys`() = runTest {
        phone.rows += DeviceContact(40, null, dirty = true, deleted = true, version = 2, displayName = null)
        pending.records += record(40, "urn:uuid:none")
        keys.secondUnlock = CryptoResult.Rejected(RejectReason.WRONG_PASSPHRASE)

        val report = engine().sync(account)

        assertEquals(1, keys.unlocks, "the inventory's only")
        assertTrue(report.ready)
        assertEquals(emptyList<Any>(), pending.records, "looked up, and dropped")
    }

    @Test
    fun `a C4 answering that the contact doesn't exist is refused, not a conflict`() = runTest {
        newRow(30, "Ann")
        api.createAnswers += ProtonError.Api(422, 2501)

        val report = engine().sync(account)

        assertEquals(listOf(NotSyncedReason.PROTON_REFUSED), report.refused.map { it.reason })
        assertEquals(emptyList<Any>(), report.reclassified)
    }

    @Test
    fun `a UID Proton already holds is looked up, and one match is bound`() = runTest {
        newRow(30, "Ann")
        pending.records += record(30, "urn:uuid:old")
        api.createAnswers += CreateReply.UidExists
        api.pages += contact(7, 2, 3)
        api.listed += listOf(ContactIdDto("proton-id-6", "urn:uuid:other"), ContactIdDto("proton-id-7", "urn:uuid:old"), ContactIdDto("proton-id-8"))

        engine().sync(account)

        assertEquals(listOf("C3 0", "C4", "C1 0", "C2 7"), calls)
        assertEquals(ExistingRow(30, 1, clearDirty = true, bind = true), phone.writes.single { it.protonId == "proton-id-7" }.existing, "the row unchanged since the record: Proton holds its cards")
        assertEquals(writtenBound(7, 30), mapDao.maps.single { it.protonId == "proton-id-7" })
        assertEquals(emptyList<Any>(), pending.records)
    }

    @Test
    fun `a match found for a row that changed since its record, or for a fresh UID, is bound alone and its edit held`() = runTest {
        // Proton may hold an older create, so its photo baseline isn't known; edited before keys.
        newRow(30, "Ann")
        pending.records += record(30, "urn:uuid:old", version = 0)
        newRow(40, "Bob")
        api.createAnswers.addAll(listOf(CreateReply.UidExists, CreateReply.UidExists))
        heldOutsideTheInventory(7)
        heldOutsideTheInventory(8)
        api.listed += listOf(ContactIdDto("proton-id-7", "urn:uuid:old"), ContactIdDto("proton-id-8", "urn:uuid:test-1"))

        engine().sync(account)

        assertEquals(listOf("proton-id-7" to 30L, "proton-id-8" to 40L), phone.bound)
        assertEquals(emptyList<Any>(), phone.writes.filter { it.existing?.bind == true })
        assertEquals(listOf(boundMap(7, 30, keyed = false, baseline = null), boundMap(8, 40, keyed = false, baseline = null)), mapDao.maps.sortedBy { it.protonId })
        assertEquals(emptyList<Any>(), pending.records)
    }

    @Test
    fun `the contact a lost reply created, listed by the inventory, is bound and not downloaded as a second row`() = runTest {
        // Skipped once bound; the provider's guard would refuse a new row for it anyway (ProviderDeviceContactsTest).
        newRow(30, "Ann")
        pending.records += record(30, "urn:uuid:old")
        api.createAnswers += CreateReply.UidExists
        api.pages += contact(7, 2, 3)
        api.listed += ContactIdDto("proton-id-7", "urn:uuid:old")

        engine().sync(account)

        assertEquals(listOf(writtenBound(7, 30)), mapDao.maps)
        assertEquals(emptyList<Any>(), phone.insertResults, "no insert of it")
    }

    @Test
    fun `a contact the inventory lists with a waiting record's UID isn't downloaded while the record waits`() = runTest {
        // It may be the row's own create; downloading it would make a second row.
        newRow(30, "Ann")
        pending.records += record(30, "urn:uuid:old")
        api.createAnswers += CreateReply.UidExists
        api.pages += listOf(contact(7, 2, 3), contact(8))
        mapper.graphs["plain(data-7-2)"] = ContactGraph(uid = "urn:uuid:old", formattedName = "Ann")
        api.listed += listOf(ContactIdDto("proton-id-7", "urn:uuid:old"), ContactIdDto("proton-id-9", "urn:uuid:old"))

        val report = engine().sync(account)

        assertEquals(listOf("proton-id-8"), phone.writes.map { it.protonId }, "only the other new contact")
        assertEquals(listOf(record(30, "urn:uuid:old")), pending.records)
        assertEquals(listOf(NotSyncedReason.UID_UNCONFIRMED), report.refused.map { it.reason })
    }

    @Test
    fun `a UID on several Proton contacts, on none, or after an incomplete C1, is held with its record`() = runTest {
        for (listing in listOf(
            listOf(ContactIdDto("proton-id-7", "urn:uuid:old"), ContactIdDto("proton-id-8", "urn:uuid:old")),
            listOf(ContactIdDto("proton-id-7", "urn:uuid:other")),
            List(50) { ContactIdDto("proton-id-7", "urn:uuid:old") },
        )) {
            phone.rows.clear(); pending.records.clear(); api.listed.clear(); calls.clear()
            newRow(30, "Ann")
            pending.records += record(30, "urn:uuid:old")
            api.createAnswers += CreateReply.UidExists
            api.listed += listing

            val report = engine().sync(account)

            assertEquals(listOf("Ann" to NotSyncedReason.UID_UNCONFIRMED), report.refused.map { it.item.displayName to it.reason })
            assertEquals(counts(newOnPhone = 0, unreadable = 1), report.counts())
            assertEquals(listOf(record(30, "urn:uuid:old")), pending.records, "sent again next run")
            assertEquals(emptyList<Any>(), phone.writes)
            assertEquals(emptyList<Any>(), mapDao.maps)
        }
    }

    @Test
    fun `a card Proton refuses drops the record, and the next run tries again with a new UID`() = runTest {
        newRow(30, "Ann")
        api.createAnswers += CreateReply.CardRefused

        val report = engine().sync(account)
        assertEquals(listOf(NotSyncedReason.PROTON_REFUSED), report.refused.map { it.reason })
        assertEquals(emptyList<Any>(), pending.records)
        assertEquals(emptyList<Any>(), phone.writes)

        engine().sync(account)
        assertEquals(listOf("urn:uuid:test-1", "urn:uuid:test-2"), api.recordsWhenCreated.map { it.single().uid })
    }

    @Test
    fun `another refusal about the contact keeps its record, and the next one is sent`() = runTest {
        // An item Code other than 1000, 2500, and 2001, or a 4xx: the create may have happened, so 2500 recovers it.
        newRow(30, "Ann")
        newRow(40, "Bob")
        api.createAnswers += ProtonError.Api(200, 2005)

        val report = engine().sync(account)

        assertEquals(listOf("Ann" to NotSyncedReason.PROTON_REFUSED), report.refused.map { it.item.displayName to it.reason })
        assertEquals(listOf(record(30, "urn:uuid:test-1")), pending.records)
        assertEquals(listOf("proton-id-102"), mapDao.maps.map { it.protonId }, "Bob was created")
    }

    @Test
    fun `a create whose outcome is unknown, or that can't be sent now, keeps its record and stops sending`() = runTest {
        for ((error, blocker) in listOf(
            ProtonError.Network() to Blocker.PROTON_UNAVAILABLE,
            ProtonError.Api(503, 503) to Blocker.PROTON_UNAVAILABLE,
            ProtonError.BadResponse(200) to Blocker.PROTON_UNAVAILABLE,
            ProtonError.RateLimited(60.seconds) to Blocker.PROTON_UNAVAILABLE,
            ProtonError.SessionRejected() to Blocker.SESSION_EXPIRED,
            ProtonError.HumanVerification(HvDetails("token", "https://example.test", listOf("captcha"))) to Blocker.CAPTCHA,
        )) {
            phone.rows.clear(); pending.records.clear(); calls.clear(); api.pages.clear(); mapDao.maps.clear()
            api.pages += contact(1)
            newRow(30, "Ann")
            newRow(40, "Bob")
            api.createAnswers += error

            val report = engine().sync(account)

            assertEquals(blocker, report.blocker, "$error")
            assertEquals(listOf(record(30, "urn:uuid:test-${uidsMade - 1}")), pending.records)
            assertEquals(1, calls.count { it == "C4" }, "nothing more sent")
            assertTrue("apply proton-id-1" in events, "the downloads still apply")
            events.clear()
        }
    }

    @Test
    fun `a create whose re-read fails stores nothing but its record`() = runTest {
        // The next run's create is refused as 2500, and the lookup binds it.
        newRow(30, "Ann")
        newRow(40, "Bob")
        api.refetches["proton-id-101"] = ArrayDeque(listOf(ProtonError.Api(422, 2501)))
        api.refetches["proton-id-102"] = ArrayDeque(listOf(ProtonError.Network()))

        val report = engine().sync(account)

        assertEquals(listOf(record(30, "urn:uuid:test-1"), record(40, "urn:uuid:test-2")), pending.records)
        assertEquals(emptyList<Any>(), phone.writes)
        assertEquals(emptyList<Any>(), mapDao.maps)
        assertEquals(Blocker.PROTON_UNAVAILABLE, report.blocker, "a failure not about the contact stops sending")
    }

    @Test
    fun `a row edited while its create was out is bound alone, and its edit uploads next run`() = runTest {
        // Its SOURCE_ID is written whatever its VERSION; the map holds the re-read and the photo the plan read.
        newRow(30, "Ann")
        phone.results["proton-id-101"] = ContactWriteResult.Stale

        engine().sync(account)

        assertEquals(listOf("proton-id-101" to 30L), phone.bound)
        assertEquals(listOf(boundMap(101, 30, keyed = true, baseline = photoHashOf(30))), mapDao.maps)
        assertEquals(emptyList<Any>(), pending.records)
    }

    @Test
    fun `a row gone by the time it's bound still gets its map, so the contact is held as a missing row`() = runTest {
        newRow(30, "Ann")
        phone.results["proton-id-101"] = ContactWriteResult.Stale
        phone.bindResults["proton-id-101"] = ContactWriteResult.Stale

        engine().sync(account)

        assertEquals(listOf("proton-id-101"), mapDao.maps.map { it.protonId })
        assertEquals(emptyList<Any>(), pending.records)
    }

    @Test
    fun `a write from the re-read the phone refuses binds the row alone, counted with its reason`() = runTest {
        newRow(30, "Ann")
        phone.results["proton-id-101"] = ContactWriteResult.Failed(FailureReason.TOO_LARGE)

        val report = engine().sync(account)

        assertEquals(listOf(NotSyncedReason.WRITE_TOO_LARGE), report.refused.map { it.reason })
        assertEquals(listOf("proton-id-101" to 30L), phone.bound)
        assertEquals(listOf(boundMap(101, 30, keyed = true, baseline = photoHashOf(30))), mapDao.maps)
        assertEquals(emptyList<Any>(), pending.records)
    }

    @Test
    fun `a write refused while its guard holds stops the run as contacts hidden, keeping the record`() = runTest {
        api.pages += contact(1)
        newRow(30, "Ann")
        phone.results["proton-id-101"] = ContactWriteResult.Failed(FailureReason.OPERATION_REFUSED)

        val report = engine().sync(account)

        assertEquals(Blocker.CONTACTS_HIDDEN, report.blocker)
        assertEquals(listOf(record(30, "urn:uuid:test-1")), pending.records)
        assertTrue("apply proton-id-1" !in events, "no download either")
    }

    @Test
    fun `a created contact whose photo can't be read back is mapped without keys`() = runTest {
        newRow(30, "Ann")
        phone.touched += 30

        engine().sync(account)

        assertEquals(listOf(boundMap(101, 30, keyed = false, baseline = null)), mapDao.maps)
        assertEquals(emptyList<Any>(), pending.records)
    }

    @Test
    fun `a created contact whose re-read can't be read is bound alone, without keys`() = runTest {
        newRow(30, "Ann")
        crypto.results["proton-id-101"] = CryptoResult.Rejected(RejectReason.BAD_SIGNATURE)

        engine().sync(account)

        assertEquals(listOf("proton-id-101" to 30L), phone.bound)
        assertEquals(listOf(boundMap(101, 30, keyed = false, baseline = null)), mapDao.maps)
        assertEquals(emptyList<Any>(), pending.records)
    }

    @Test
    fun `a record whose row was deleted is looked up first`() = runTest {
        // None on Proton: dropped. One: a phone deletion of that contact, so the tombstone is bound and mapped.
        phone.rows += DeviceContact(30, null, dirty = true, deleted = true, version = 2, displayName = null)
        phone.rows += DeviceContact(40, null, dirty = true, deleted = true, version = 2, displayName = null)
        pending.records += listOf(record(30, "urn:uuid:none"), record(40, "urn:uuid:found"), record(50, "urn:uuid:gone-row"))
        heldOutsideTheInventory(7)
        heldOutsideTheInventory(8)
        api.listed += listOf(ContactIdDto("proton-id-7", "urn:uuid:found"), ContactIdDto("proton-id-8", "urn:uuid:gone-row"))

        val report = engine().sync(account)

        assertEquals(listOf("proton-id-7" to 40L), phone.bound, "row 50 is gone, so only its map is saved")
        assertEquals(listOf(boundMap(7, 40, keyed = false, baseline = null), boundMap(8, 50, keyed = false, baseline = null)), mapDao.maps.sortedBy { it.protonId })
        assertEquals(emptyList<Any>(), pending.records)
        assertEquals(counts(newOnPhone = 0), report.counts(), "counted by the next run, as a phone deletion and a missing row")
        assertEquals(0, calls.count { it == "C4" })
    }

    @Test
    fun `a deleted row's record stays while the lookup can't tell, and a failed lookup stops sending`() = runTest {
        phone.rows += DeviceContact(30, null, dirty = true, deleted = true, version = 2, displayName = null)
        pending.records += record(30, "urn:uuid:found")
        api.listed += listOf(ContactIdDto("proton-id-7", "urn:uuid:found"), ContactIdDto("proton-id-8", "urn:uuid:found"))

        engine().sync(account)
        assertEquals(listOf(record(30, "urn:uuid:found")), pending.records, "several")

        api.listed.clear()
        api.listed += List(50) { ContactIdDto("proton-id-7", "urn:uuid:found") }
        engine().sync(account)
        assertEquals(listOf(record(30, "urn:uuid:found")), pending.records, "one match, but a listing that can't tell")
        assertEquals(emptyList<Any>(), phone.bound)

        api.listFailure = ProtonError.Network()
        newRow(40, "Bob")
        val report = engine().sync(account)
        assertEquals(Blocker.PROTON_UNAVAILABLE, report.blocker)
        assertEquals(listOf(record(30, "urn:uuid:found")), pending.records.filter { it.rawContactId == 30L })
    }

    @Test
    fun `a create that stops sending looks up no deleted row's record`() = runTest {
        newRow(30, "Ann")
        api.createAnswers += ProtonError.Network()
        phone.rows += DeviceContact(40, null, dirty = true, deleted = true, version = 2, displayName = null)
        pending.records += record(40, "urn:uuid:found")

        engine().sync(account)

        assertTrue(calls.none { it.startsWith("C1") }, "$calls")
    }

    @Test
    fun `a record whose row holds a Proton ID is done, and only a real sync drops it`() = runTest {
        // A crash after the binding, before the record was deleted.
        phone.rows += DeviceContact(30, "proton-id-7", dirty = false, deleted = false, version = 2, displayName = "Ann")
        phone.rows += DeviceContact(40, "proton-id-8", dirty = true, deleted = true, version = 2, displayName = null)
        pending.records += listOf(record(30, "urn:uuid:old"), record(40, "urn:uuid:deleted"))

        engine().dryRun(account)
        assertEquals(2, pending.records.size, "a dry run writes no sync state")

        engine().sync(account)
        assertEquals(emptyList<Any>(), pending.records)
        assertTrue(calls.none { it == "C4" || it.startsWith("C1") })
    }

    @Test
    fun `a dry run saves no record and sends nothing`() = runTest {
        newRow(30, "Ann")
        phone.rows += DeviceContact(40, null, dirty = true, deleted = true, version = 2, displayName = null)
        pending.records += record(40, "urn:uuid:found")

        val report = engine().dryRun(account)

        assertEquals(listOf("C3 0"), calls, "no C4, and no lookup")
        assertEquals(listOf(record(40, "urn:uuid:found")), pending.records)
        assertEquals(counts(newOnPhone = 1), report.counts())
    }

    @Test
    fun `offline, or after an inventory request failed, nothing is created`() = runTest {
        newRow(30, "Ann")

        online = false
        engine().sync(account)
        online = true
        api.failure = ProtonError.Api(503, 503)
        engine().sync(account)

        assertEquals(0, calls.count { it == "C4" })
        assertEquals(emptyList<Any>(), pending.records)
    }

    @Test
    fun `a tombstone made and deleted on the phone, with no record, is ignored`() = runTest {
        phone.rows += DeviceContact(30, null, dirty = true, deleted = true, version = 2, displayName = null)

        engine().sync(account)

        assertEquals(listOf("C3 0"), calls)
        assertEquals(1, keys.unlocks, "nothing to send")
    }

    @Test
    fun `a new contact whose cards can't be made can't be synced, with its reason, in a dry run and a sync`() = runTest {
        newRow(31, "No name")
        mapper.createIssues["No name"] = MappingIssueCode.NO_NAME_OR_EMAIL
        newRow(32, "Label")
        mapper.createIssues["Label"] = MappingIssueCode.UNWRITABLE_LABEL
        newRow(33, "Photo can't be hashed")
        newRow(34, "Photo gone")
        phone.photoBytes[34] = null
        newRow(35, "Fine")
        phone.onPhotoHash = { if (it == 33L) throw IOException() }

        for (sync in listOf(false, true)) {
            val report = if (sync) engine().sync(account) else engine().dryRun(account)
            val sent = if (sync) listOf("Fine") else emptyList()

            assertEquals(
                listOf(
                    "No name" to NotSyncedReason.NO_NAME_OR_EMAIL, "Label" to NotSyncedReason.UNWRITABLE_LABEL,
                    "Photo can't be hashed" to NotSyncedReason.PHOTO_UNREADABLE, "Photo gone" to NotSyncedReason.PHOTO_UNREADABLE,
                ),
                report.refused.map { it.item.displayName to it.reason },
            )
            assertEquals(counts(newOnPhone = 1, unreadable = 4), report.counts(), "moved from new on the phone to can't be synced")
            assertEquals(sent, api.created.map { it.last().data.removePrefix("encrypted(created(").removeSuffix("))") }, "only a sync sends, and only the contact whose cards were made")
            calls.clear()
        }
    }

    @Test
    fun `a phone photo that can't be read while making the cards can't be synced`() = runTest {
        newRow(30)
        phone.photoFailure = IOException()

        assertEquals(listOf(NotSyncedReason.PHOTO_UNREADABLE), engine().dryRun(account).refused.map { it.reason })
    }

    @Test
    fun `a phone photo that isn't an image, or is too large, can't be synced`() = runTest {
        newRow(30)

        photos.result = ScaledPhoto.Malformed
        assertEquals(listOf(NotSyncedReason.PHOTO_MALFORMED), engine().dryRun(account).refused.map { it.reason })
        photos.result = ScaledPhoto.Oversized
        assertEquals(listOf(NotSyncedReason.PHOTO_OVERSIZED), engine().dryRun(account).refused.map { it.reason })
    }

    @Test
    fun `a new contact that can't be encrypted can't be synced, and nothing else is tried`() = runTest {
        newRow(30)

        crypto.encryptResult = CryptoResult.Rejected(RejectReason.NO_PRIMARY_KEY)
        assertEquals(listOf(NotSyncedReason.NO_PRIMARY_KEY), engine().dryRun(account).refused.map { it.reason })
        crypto.encryptResult = CryptoResult.Rejected(RejectReason.ENCRYPT_OR_SIGN_FAILED)
        assertEquals(listOf(NotSyncedReason.ENCRYPT_FAILED), engine().sync(account).refused.map { it.reason })
        assertEquals(listOf("C3 0", "C3 0"), calls)
    }

    @Test
    fun `new rows alone unlock the keys a second time, closed once the cards are made`() = runTest {
        newRow(30)

        engine().dryRun(account)
        assertEquals(2, keys.unlocks)
        assertTrue(keys.closed)

        keys.unlocks = 0
        keys.secondUnlock = CryptoResult.Rejected(RejectReason.WRONG_PASSPHRASE)
        val report = engine().sync(account)
        assertEquals(Blocker.KEYS_DONT_UNLOCK, report.blocker, "as at step 1")
        assertNull(report.plan)
        assertEquals(1, mapper.created.size, "only the dry run's: the sync made no card")
    }

    @Test
    fun `a new row holding rows the app can't read back gets no cards`() = runTest {
        newRow(30, "Other app's fields")
        phone.dataRowsByRaw[30] = phone.dataRowsByRaw.getValue(30) + DataRow("vnd.example.test/other", emptyMap())
        newRow(40, "Fine")

        val report = engine().dryRun(account)

        assertEquals(listOf("Fine"), mapper.created.map { it.phone.formattedName })
        assertEquals(listOf(PlanCategory.UNMAPPED_ROWS, PlanCategory.NEW_ON_PHONE), report.plan!!.items.map { it.category })
        assertEquals(counts(newOnPhone = 1, unreadable = 1), report.counts())
    }

    @Test
    fun `a dry run offline, or after an inventory request failed, still makes the cards`() = runTest {
        // Whatever stops a create is counted whether or not Proton answered.
        newRow(30)
        mapper.createIssues["Made here 30"] = MappingIssueCode.NO_NAME_OR_EMAIL

        online = false
        val offline = engine().dryRun(account)
        assertEquals(Blocker.OFFLINE, offline.blocker)
        assertEquals(listOf(NotSyncedReason.NO_NAME_OR_EMAIL), offline.refused.map { it.reason })

        online = true
        api.failure = ProtonError.Network()
        val failed = engine().dryRun(account)
        assertEquals(Blocker.PROTON_UNAVAILABLE, failed.blocker)
        assertEquals(listOf(NotSyncedReason.NO_NAME_OR_EMAIL), failed.refused.map { it.reason })
        assertEquals(2, mapper.created.size)
    }

    @Test
    fun `a cancelled run makes no more cards, and closes the keys`() = runTest {
        newRow(30)
        newRow(40)
        lateinit var run: Job
        mapper.onCreate = { run.cancel() }

        run = launch { engine().dryRun(account) }
        run.join()

        assertTrue(run.isCancelled)
        assertEquals(1, mapper.created.size, "stopped before the next contact")
        assertTrue(keys.closed)
        assertEquals(emptyList<Any>(), reportDao.saved)
    }

    @Test
    fun `an upload-only sync creates new rows and settles waiting records as a full sync does`() = runTest {
        // Android asks for one after a contact is added on the phone.
        newRow(30, "Ann")
        phone.rows += DeviceContact(40, "proton-id-7", dirty = false, deleted = false, version = 2, displayName = "Bob")
        pending.records += record(40, "urn:uuid:old")

        engine().upload(account)

        assertEquals(listOf("C4", "C2 101"), calls, "no inventory")
        assertEquals(listOf(writtenBound(101, 30)), mapDao.maps)
        assertEquals(emptyList<Any>(), pending.records)
        assertTrue(keys.closed)
    }

    @Test
    fun `a revoked permission or a provider that stops answering while a new row is read stops the run`() = runTest {
        newRow(30)

        phone.readRowsFailure = SecurityException()
        assertEquals(Blocker.PERMISSION_MISSING, engine().dryRun(account).blocker)
        phone.readRowsFailure = null
        phone.photoFailure = ProviderUnavailableException()
        assertEquals(Blocker.CONTACTS_UNAVAILABLE, engine().sync(account).blocker, "its photo, while making the cards")
        assertTrue(keys.closed)
        assertEquals(emptyList<Any>(), mapDao.maps)
    }
}
