package dev.rboza.android.opensyncforproton.sync.engine

import org.junit.jupiter.api.Assertions.assertNull
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Photo
import dev.rboza.android.opensyncforproton.mapping.CardPlacement
import dev.rboza.android.opensyncforproton.mapping.MappingIssueCode
import dev.rboza.android.opensyncforproton.mapping.PhotoUpload
import dev.rboza.android.opensyncforproton.crypto.CryptoResult
import dev.rboza.android.opensyncforproton.crypto.RejectReason
import dev.rboza.android.opensyncforproton.proton.CardDto
import dev.rboza.android.opensyncforproton.proton.HvDetails
import dev.rboza.android.opensyncforproton.proton.ProtonError
import dev.rboza.android.opensyncforproton.sync.provider.ScaledPhoto
import dev.rboza.android.opensyncforproton.mapping.ContactGraph
import dev.rboza.android.opensyncforproton.mapping.ContactPhoto
import dev.rboza.android.opensyncforproton.mapping.GraphKeys
import dev.rboza.android.opensyncforproton.mapping.SourceKey
import dev.rboza.android.opensyncforproton.proton.ProtonContactId
import dev.rboza.android.opensyncforproton.proton.contactFingerprint
import dev.rboza.android.opensyncforproton.sync.db.Blocker
import dev.rboza.android.opensyncforproton.sync.db.ContactMapEntity
import dev.rboza.android.opensyncforproton.sync.db.ReportKind
import dev.rboza.android.opensyncforproton.sync.db.ReportTrigger
import dev.rboza.android.opensyncforproton.sync.plan.PlanCategory
import dev.rboza.android.opensyncforproton.sync.plan.ReportCounts
import dev.rboza.android.opensyncforproton.sync.provider.ColumnValue
import dev.rboza.android.opensyncforproton.sync.provider.ContactWriteResult
import dev.rboza.android.opensyncforproton.sync.provider.DataRow
import dev.rboza.android.opensyncforproton.sync.provider.DeviceContact
import dev.rboza.android.opensyncforproton.sync.provider.ExistingRow
import dev.rboza.android.opensyncforproton.sync.provider.FailureReason
import dev.rboza.android.opensyncforproton.sync.provider.ProviderUnavailableException
import dev.rboza.android.opensyncforproton.sync.provider.dataRows
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Uploading phone edits: docs/sync.md § The plan, step 5, and § Writes. Every value is made up for the test.
class SyncUploadTest : EngineHarness() {
    private fun fingerprint(n: Int) = contactFingerprint(ProtonContactId("proton-id-$n"), contact(n).cards).value
    private fun row(raw: Long, n: Int, dirty: Boolean = true, version: Long = 1) = DeviceContact(raw, "proton-id-$n", dirty, deleted = false, version, "Row $raw")

    /** A map whose rows the app wrote with their keys, the photo it read back as its baseline. */
    private fun map(n: Int, raw: Long, fingerprint: String = fingerprint(n)) =
        ContactMapEntity("user-id-1", "proton-id-$n", raw, fingerprint, rowsKeyed = true, photoBaseline = photoHashOf(raw))

    /** The map a write leaves: the contact's fingerprint, its rows keyed, and the photo and its thumbnail read back. */
    private fun written(n: Int, raw: Long, fingerprint: String = fingerprint(n)) = map(n, raw, fingerprint).copy(photoThumbnailBaseline = thumbnailHashOf(raw))

    /** The fake mapper's contact [n], as its rows hold it on the phone. */
    private fun remoteRows(n: Int): List<DataRow> = dataRows(ContactGraph(uid = null, formattedName = "Contact proton-id-$n"), null)

    @Test
    fun `a settled edit whose remote didn't move has only its DIRTY cleared, and nothing is sent`() = runTest {
        // The rows stay as they are, so per-row choices stay too.
        api.pages += contact(1)
        phone.rows += row(10, 1, version = 7)
        phone.dataRowsByRaw[10] = remoteRows(1)
        mapDao.maps += map(1, 10)

        val report = engine().sync(account)

        assertEquals(listOf("proton-id-1" to ExistingRow(10, 7)), phone.cleared, "guarded by the VERSION read")
        assertEquals(emptyList<Any>(), phone.writes, "no data row written")
        assertEquals(listOf(map(1, 10)), mapDao.maps, "the map already holds the remote and the photo")
        assertEquals(listOf("read back 10", "clear dirty proton-id-1"), events, "its photo hashed while planning only; no map saved")
        assertEquals(ReportCounts(0, 0, 0, 0, 0, 0, 0, false, false, 0, 0, 0), report.counts(), "not counted")
        assertTrue(report.ready)
        assertEquals(listOf("C3 0"), calls, "nothing uploaded")
    }

    @Test
    fun `an edit made just after a settled edit's DIRTY is cleared keeps its map, keys and baseline`() = runTest {
        // Its rows weren't rewritten, so the map still holds what the app last wrote; the edit uploads as any other.
        api.pages += contact(1)
        phone.rows += row(10, 1)
        phone.dataRowsByRaw[10] = remoteRows(1)
        mapDao.maps += map(1, 10)
        phone.touched += 10

        engine().sync(account)

        assertEquals(listOf(map(1, 10)), mapDao.maps)
    }

    @Test
    fun `a settled edit whose remote moved is written from the remote with DIRTY cleared, its photo included`() = runTest {
        // What changed on Proton (the photo, or parts Android doesn't show) still reaches the phone.
        api.pages += contact(1)
        mapper.nameKeys["1"] = SourceKey(listOf("FN.0123456789abcdef"))
        mapper.photos["1"] = ContactPhoto(byteArrayOf(1, 2, 3), "image/jpeg")
        phone.rows += row(10, 1, version = 7)
        phone.dataRowsByRaw[10] = remoteRows(1)
        mapDao.maps += map(1, 10, "old-fingerprint")

        val report = engine().sync(account)

        val write = phone.writes.single()
        assertEquals(ExistingRow(10, 7, clearDirty = true), write.existing, "guarded by the VERSION read, as the sync adapter")
        assertEquals(
            dataRows(ContactGraph(uid = null, formattedName = "Contact proton-id-1"), byteArrayOf(3, 2, 1), GraphKeys(name = SourceKey(listOf("FN.0123456789abcdef")))).map { it.mimeType to it.values.keys },
            write.rows.map { it.mimeType to it.values.keys },
            "written as a download does: its keys, and the remote's photo, scaled",
        )
        assertEquals(listOf(3, 2, 1).map { it.toByte() }, (write.rows.single { it.mimeType == Photo.CONTENT_ITEM_TYPE }.values[Photo.PHOTO] as ColumnValue.Blob).bytes.toList())
        assertEquals(emptyList<Any>(), phone.cleared)
        assertEquals(listOf(written(1, 10)), mapDao.maps, "the remote's fingerprint and the photo read back")
        assertEquals(ReportCounts(0, 0, 0, 0, 0, 0, 0, false, false, 0, 0, 0), report.counts(), "not counted")
        assertEquals(listOf("C3 0"), calls, "nothing uploaded")
    }

    @Test
    fun `a settled row the phone made stale, or whose write is refused, keeps its map and stays dirty`() = runTest {
        // 1 and 2 only clear DIRTY; 3 and 4 moved on Proton, so they're written.
        api.pages += listOf(contact(1), contact(2), contact(3), contact(4))
        phone.rows += listOf(row(10, 1), row(20, 2), row(30, 3), row(40, 4))
        (1..4).forEach { phone.dataRowsByRaw[it * 10L] = remoteRows(it) }
        val maps = listOf(map(1, 10), map(2, 20), map(3, 30, "old-fingerprint"), map(4, 40, "old-fingerprint"))
        mapDao.maps += maps
        phone.clearResults["proton-id-1"] = ContactWriteResult.Stale
        phone.clearResults["proton-id-2"] = ContactWriteResult.Failed(FailureReason.REJECTED_VALUE)
        phone.results["proton-id-3"] = ContactWriteResult.Stale
        phone.results["proton-id-4"] = ContactWriteResult.Failed(FailureReason.TOO_LARGE)

        val report = engine().sync(account)

        assertEquals(maps, mapDao.maps)
        assertEquals(ReportCounts(0, 0, 0, 0, 0, 0, 0, false, false, 2, 0, 0), report.counts(), "a stale write is planned again, not refused")
        assertEquals(
            listOf("proton-id-2" to NotSyncedReason.VALUE_REJECTED, "proton-id-4" to NotSyncedReason.WRITE_TOO_LARGE),
            report.refused.map { it.item.protonId?.value to it.reason },
        )
    }

    @Test
    fun `a settled row whose clear the provider refuses while its guard holds stops the run as contacts hidden`() = runTest {
        api.pages += listOf(contact(1), contact(2))
        phone.rows += listOf(row(10, 1), row(20, 2))
        phone.dataRowsByRaw[10] = remoteRows(1)
        phone.dataRowsByRaw[20] = remoteRows(2)
        mapDao.maps += listOf(map(1, 10), map(2, 20))
        phone.clearResults["proton-id-1"] = ContactWriteResult.Failed(FailureReason.OPERATION_REFUSED)

        val report = engine().sync(account)

        assertEquals(Blocker.CONTACTS_HIDDEN, report.blocker)
        assertEquals(listOf("proton-id-1"), phone.cleared.map { it.first }, "nothing after it")
        assertEquals(listOf(map(1, 10), map(2, 20)), mapDao.maps)
    }

    @Test
    fun `a photo that can't be read while planning isn't known to be the same`() = runTest {
        // Not even as no photo, when the app wrote none.
        for (baseline in listOf(photoHashOf(10), null)) {
            reportDao.saved.clear(); phone.rows.clear(); mapDao.maps.clear(); api.pages.clear()
            api.pages += contact(1)
            phone.rows += row(10, 1)
            phone.dataRowsByRaw[10] = remoteRows(1)
            phone.onPhotoHash = { throw IOException() }
            mapDao.maps += map(1, 10).copy(photoBaseline = baseline)

            val report = engine().sync(account)

            assertEquals(PlanCategory.CHANGED_ON_PHONE, report.plan!!.items.single().category, "baseline $baseline")
            assertTrue(report.ready)
        }
    }

    @Test
    fun `a revoked permission or a provider that stops answering while reading an edit stops the run before any write`() = runTest {
        for ((failure, blocker) in listOf(SecurityException() to Blocker.PERMISSION_MISSING, ProviderUnavailableException() to Blocker.CONTACTS_UNAVAILABLE)) {
            // The thumbnail is read when the display photo can't be.
            for (step in listOf("rows", "photo", "thumbnail")) {
                events.clear(); reportDao.saved.clear(); phone.rows.clear(); mapDao.maps.clear(); api.pages.clear()
                api.pages += listOf(contact(1), contact(2))
                // Row 20 has no map: a repair that must not be written either.
                phone.rows += listOf(row(10, 1), row(20, 2, dirty = false))
                mapDao.maps += map(1, 10)
                phone.readRowsFailure = failure.takeIf { step == "rows" }
                phone.onPhotoHash = {
                    if (step == "photo") throw failure
                    if (step == "thumbnail") throw IOException()
                }
                phone.onThumbnailHash = { if (step == "thumbnail") throw failure }

                val report = engine().sync(account)

                assertEquals(blocker, report.blocker, "$failure, reading the $step")
                assertEquals(blocker, reportDao.saved.single().blocker)
                assertEquals(emptyList<String>(), events.filter { it.startsWith("apply") || it.startsWith("map") || it.startsWith("repairs") })
                assertEquals(listOf(map(1, 10)), mapDao.maps)
            }
        }
    }

    @Test
    fun `a dirty row holding rows the app can't read back is held, counted as can't be synced`() = runTest {
        // A second note, which only another app writes (docs/mapping.md § Reading rows back).
        api.pages += contact(1)
        phone.rows += row(10, 1)
        phone.dataRowsByRaw[10] = remoteRows(1) + DataRow(Note.CONTENT_ITEM_TYPE, mapOf(Note.NOTE to ColumnValue.Text("one"))) +
            DataRow(Note.CONTENT_ITEM_TYPE, mapOf(Note.NOTE to ColumnValue.Text("two")))
        mapDao.maps += map(1, 10)

        val report = engine().sync(account)

        assertEquals(PlanCategory.UNMAPPED_ROWS, report.plan!!.items.single().category)
        assertEquals(emptyList<String>(), events.filter { it.startsWith("apply") || it.startsWith("map") })
        assertEquals(1, report.counts().unreadable)
        assertEquals(1, reportDao.saved.single().unreadable)
        assertEquals(listOf(map(1, 10)), mapDao.maps)
    }

    @Test
    fun `a dry run plans a settled row the same and writes nothing`() = runTest {
        api.pages += contact(1)
        phone.rows += row(10, 1)
        phone.dataRowsByRaw[10] = remoteRows(1)
        mapDao.maps += map(1, 10, "old-fingerprint")

        val dry = engine().dryRun(account)
        val real = engine().sync(account)

        assertEquals(real.plan!!.items.map { it.category to it.carriedOut }, dry.plan!!.items.map { it.category to it.carriedOut })
        assertEquals(PlanCategory.SETTLED_REMOTE_CHANGED, dry.plan!!.items.single().category)
        assertEquals(ReportCounts(0, 0, 0, 0, 0, 0, 0, false, false, 0, 0, 0), dry.counts(), "a dry run sees it as unchanged")
        assertEquals(1, phone.writes.size, "only the real sync wrote")
    }

    // --- Uploading an edit. ---

    /** The fingerprint of contact [n] as Proton stores it after an upload (the fake's C5). */
    private fun uploaded(n: Int) = contactFingerprint(ProtonContactId("proton-id-$n"), api.pages.single { it.id == "proton-id-$n" }.cards).value

    @Test
    fun `an edit whose remote is unchanged is patched from the inventory, re-fetched, sent once, and its row written from a re-read`() = runTest {
        // The plan patches and encrypts; C2's fingerprint equals the inventory's.
        api.pages += contact(1, 0, 2, 3)
        mapper.nameKeys["1"] = SourceKey(listOf("FN.0123456789abcdef"))
        phone.rows += row(10, 1, version = 7)
        mapDao.maps += map(1, 10, contactFingerprint(ProtonContactId("proton-id-1"), contact(1, 0, 2, 3).cards).value)

        val report = engine().sync(account)

        val (shown, cards, photo) = mapper.patched.single()
        assertEquals("Edited on the phone", shown.graph.formattedName, "the phone's rows, read back")
        assertEquals(listOf(CardPlacement.CLEAR, CardPlacement.SIGNED, CardPlacement.ENCRYPTED), cards.map { it.placement })
        assertEquals(listOf("plain(data-1-0)", "plain(data-1-2)", "plain(data-1-3)"), cards.map { it.vcard }, "the inventory's cards")
        assertEquals(PhotoUpload.Keep, photo, "the photo is the one written")
        assertEquals(listOf(0, 2, 3), crypto.encrypted.single().map { it.type })
        assertEquals(listOf("C3 0", "C2 1", "C5 1", "C2 1"), calls, "sent once, then read again")
        val sent = api.sent.single()
        assertEquals("proton-id-1", sent.first)
        assertEquals(listOf(0 to null, 2 to "signature", 3 to "signature"), sent.second.map { it.type to it.signature })
        assertTrue(sent.second.all { it.data.startsWith("encrypted(") && "patched(plain(data-1-" in it.data })
        val write = phone.writes.single()
        assertEquals(ExistingRow(10, 7, clearDirty = true), write.existing, "guarded by the VERSION read before sending, DIRTY cleared")
        assertEquals(dataRows(ContactGraph(uid = null, formattedName = "Contact proton-id-1"), null, GraphKeys(name = SourceKey(listOf("FN.0123456789abcdef")))), write.rows)
        assertEquals(listOf("read back 10", "map proton-id-1", "apply proton-id-1", "read back 10", "map proton-id-1"), events, "the baseline first, then the row")
        assertEquals(listOf(written(1, 10, uploaded(1))), mapDao.maps, "the re-read's fingerprint and the photo read back")
        assertEquals(1, report.counts().changedOnPhone)
        assertTrue(report.ready)
    }

    @Test
    fun `an edit whose row moved on after its VERSION was read is uploaded, but the row stays dirty for the next run`() = runTest {
        api.pages += contact(1)
        phone.rows += row(10, 1, version = 7)
        mapDao.maps += map(1, 10)
        phone.results["proton-id-1"] = ContactWriteResult.Stale

        engine().sync(account)

        assertEquals(1, api.sent.size)
        assertEquals(listOf(map(1, 10, uploaded(1))), mapDao.maps, "the new baseline, the rows' keys and photo as they were")
    }

    @Test
    fun `a remote that moved since the baseline, or is gone, is a conflict and nothing is sent`() = runTest {
        val gone = ProtonError.Api(422, 2501)
        for (answer in listOf<Any>(contact(1).copy(cards = listOf(CardDto(1, "data-1-1-moved"))), gone)) {
            calls.clear(); api.sent.clear(); api.refetches.clear(); api.pages.clear(); phone.rows.clear(); mapDao.maps.clear(); phone.writes.clear()
            api.pages += contact(1)
            api.refetches["proton-id-1"] = ArrayDeque(listOf(answer))
            phone.rows += row(10, 1)
            mapDao.maps += map(1, 10)

            val report = engine().sync(account)

            assertEquals(listOf("C3 0", "C2 1"), calls, "$answer")
            assertEquals(listOf(map(1, 10)), mapDao.maps)
            assertEquals(emptyList<Any>(), phone.writes)
            assertEquals(listOf("proton-id-1" to PlanCategory.CONFLICT), report.reclassified.map { it.item.protonId?.value to it.category })
            assertEquals(1 to 0, report.counts().let { it.conflicts to it.changedOnPhone }, "counted as a conflict")
        }
    }

    @Test
    fun `a contact gone when C5 is sent is a conflict`() = runTest {
        api.pages += contact(1)
        phone.rows += row(10, 1)
        mapDao.maps += map(1, 10)
        api.updateFailures["proton-id-1"] = ProtonError.Api(422, 2501)

        val report = engine().sync(account)

        assertEquals(listOf(PlanCategory.CONFLICT), report.reclassified.map { it.category })
        assertEquals(listOf(map(1, 10)), mapDao.maps)
        assertTrue(report.ready)
    }

    @Test
    fun `a remote that moved to what the phone shows is settled from the re-read, and nothing is sent`() = runTest {
        // A C5 whose reply was lost, say: the shown values are the same on both sides.
        api.pages += contact(1)
        val moved = contact(1).copy(cards = listOf(CardDto(1, "data-1-1-moved")))
        api.refetches["proton-id-1"] = ArrayDeque(listOf(moved))
        mapper.graphs["plain(data-1-1-moved)"] = ContactGraph(uid = null, formattedName = "Edited on the phone")
        phone.rows += row(10, 1, version = 7)
        mapDao.maps += map(1, 10)

        val report = engine().sync(account)

        assertEquals(listOf("C3 0", "C2 1"), calls)
        assertEquals(ExistingRow(10, 7, clearDirty = true), phone.writes.single().existing)
        assertEquals(dataRows(ContactGraph(uid = null, formattedName = "Edited on the phone"), null), phone.writes.single().rows)
        assertEquals(listOf(written(1, 10, contactFingerprint(ProtonContactId("proton-id-1"), moved.cards).value)), mapDao.maps)
        assertEquals(listOf(PlanCategory.SETTLED_REMOTE_CHANGED), report.reclassified.map { it.category })
        assertEquals(ReportCounts(0, 0, 0, 0, 0, 0, 0, false, false, 0, 0, 0), report.counts(), "not counted")
    }

    @Test
    fun `a remote that moved to the phone's values, while the phone changed the photo, is a conflict`() = runTest {
        api.pages += contact(1)
        api.refetches["proton-id-1"] = ArrayDeque(listOf(contact(1).copy(cards = listOf(CardDto(1, "data-1-1-moved")))))
        mapper.graphs["plain(data-1-1-moved)"] = ContactGraph(uid = null, formattedName = "Edited on the phone")
        phone.rows += row(10, 1)
        phone.photoHashes[10] = "f".repeat(64)
        mapDao.maps += map(1, 10)

        val report = engine().sync(account)

        assertEquals(listOf(PlanCategory.CONFLICT), report.reclassified.map { it.category })
        assertEquals(emptyList<Any>(), phone.writes)
    }

    @Test
    fun `a C5 whose outcome is unknown, or that's rate limited, stores nothing and stops sending, and the run is retryable`() = runTest {
        for (failure in listOf(ProtonError.Network(), ProtonError.Api(503, null), ProtonError.BadResponse(200), ProtonError.RateLimited(null))) {
            calls.clear(); api.sent.clear(); api.pages.clear(); phone.rows.clear(); mapDao.maps.clear(); phone.writes.clear(); api.updateFailures.clear()
            api.pages += listOf(contact(1), contact(2))
            phone.rows += listOf(row(10, 1), row(20, 2))
            mapDao.maps += listOf(map(1, 10), map(2, 20))
            api.updateFailures["proton-id-1"] = failure

            val report = engine().sync(account)

            assertEquals(listOf("C3 0", "C2 1", "C5 1"), calls, "nothing more sent: $failure")
            assertEquals(listOf(map(1, 10), map(2, 20)), mapDao.maps)
            assertEquals(emptyList<Any>(), phone.writes)
            assertEquals(Blocker.PROTON_UNAVAILABLE, report.blocker, "$failure")
            assertTrue(report.retryable)
        }
    }

    @Test
    fun `a failure that isn't about the contact stops sending with the blocker an inventory failure gets`() = runTest {
        val cases = listOf(
            ProtonError.SessionRejected() to Blocker.SESSION_EXPIRED,
            ProtonError.HumanVerification(HvDetails("token", "https://example.test", listOf("captcha"))) to Blocker.CAPTCHA,
            ProtonError.AppVersionRejected(5003) to Blocker.APP_VERSION_REJECTED,
            ProtonError.Network() to Blocker.PROTON_UNAVAILABLE,
        )
        for ((failure, blocker) in cases) {
            calls.clear(); api.refetches.clear(); api.pages.clear(); phone.rows.clear(); mapDao.maps.clear()
            api.pages += listOf(contact(1), contact(2))
            phone.rows += listOf(row(10, 1), row(20, 2))
            mapDao.maps += listOf(map(1, 10), map(2, 20))
            api.refetches["proton-id-1"] = ArrayDeque(listOf(failure))

            val report = engine().sync(account)

            assertEquals(listOf("C3 0", "C2 1"), calls, "$failure")
            assertEquals(blocker, report.blocker, "$failure")
        }
    }

    @Test
    fun `an error about one contact counts it as can't be synced, and the next one is sent`() = runTest {
        api.pages += listOf(contact(1), contact(2), contact(3))
        phone.rows += listOf(row(10, 1), row(20, 2), row(30, 3))
        mapDao.maps += listOf(map(1, 10), map(2, 20), map(3, 30))
        api.updateFailures["proton-id-1"] = ProtonError.Api(422, 2001)
        api.refetches["proton-id-2"] = ArrayDeque(listOf(ProtonError.Api(400, 2061)))
        api.pages += contact(4)
        phone.rows += row(40, 4)
        mapDao.maps += map(4, 40)
        api.refetches["proton-id-4"] = ArrayDeque(listOf(ProtonError.InvalidRequest()))

        val report = engine().sync(account)

        assertEquals(listOf("proton-id-3"), api.sent.map { it.first })
        assertEquals(
            listOf("proton-id-1" to NotSyncedReason.PROTON_REFUSED, "proton-id-2" to NotSyncedReason.PROTON_REFUSED, "proton-id-4" to NotSyncedReason.PROTON_REFUSED),
            report.refused.map { it.item.protonId?.value to it.reason },
        )
        assertEquals(1 to 3, report.counts().let { it.changedOnPhone to it.unreadable })
        assertTrue(report.ready)
    }

    @Test
    fun `a re-read that Proton refuses for the contact stores nothing more, and the next edit is sent`() = runTest {
        api.pages += listOf(contact(1), contact(2))
        phone.rows += listOf(row(10, 1), row(20, 2))
        mapDao.maps += listOf(map(1, 10), map(2, 20))
        api.refetches["proton-id-1"] = ArrayDeque(listOf(contact(1), ProtonError.Api(422, 2501)))

        val report = engine().sync(account)

        assertEquals(listOf("proton-id-1", "proton-id-2"), api.sent.map { it.first })
        assertEquals(map(1, 10), mapDao.maps.single { it.protonId == "proton-id-1" }, "the next run's re-fetch decides")
        assertEquals(emptyList<Any>(), report.refused, "uploaded")
        assertEquals(emptyList<Any>(), report.reclassified, "uploaded, not a conflict")
        assertEquals(2 to 0, report.counts().let { it.changedOnPhone to it.conflicts })
        assertTrue(report.ready)
    }

    @Test
    fun `an edit that can't be encrypted or signed stays dirty, can't be synced with its reason, in a dry run too`() = runTest {
        for ((rejected, reason) in listOf(RejectReason.ENCRYPT_OR_SIGN_FAILED to NotSyncedReason.ENCRYPT_FAILED, RejectReason.NO_PRIMARY_KEY to NotSyncedReason.NO_PRIMARY_KEY)) {
            for (dry in listOf(true, false)) {
                calls.clear(); api.pages.clear(); phone.rows.clear(); mapDao.maps.clear(); phone.writes.clear()
                api.pages += contact(1)
                phone.rows += row(10, 1)
                mapDao.maps += map(1, 10)
                crypto.encryptResult = CryptoResult.Rejected(rejected)

                val report = if (dry) engine().dryRun(account) else engine().sync(account)

                assertEquals(listOf("C3 0"), calls, "nothing sent: $rejected, dry run $dry")
                assertEquals(listOf("proton-id-1" to reason), report.refused.map { it.item.protonId?.value to it.reason })
                assertEquals(0 to 1, report.counts().let { it.changedOnPhone to it.unreadable })
                assertEquals(emptyList<Any>(), phone.writes)
            }
        }
    }

    @Test
    fun `a patch that fails closed stays dirty, can't be synced with its reason, in a dry run too`() = runTest {
        val cases = mapOf(
            MappingIssueCode.UNKEYED_PROPERTY to NotSyncedReason.UNKEYED_PROPERTY,
            MappingIssueCode.UNWRITABLE_LABEL to NotSyncedReason.UNWRITABLE_LABEL,
            MappingIssueCode.NO_NAME_OR_EMAIL to NotSyncedReason.NO_NAME_OR_EMAIL,
            MappingIssueCode.UNENCRYPTED_PROPERTY to NotSyncedReason.UNENCRYPTED_PROPERTY,
        )
        for ((issue, reason) in cases) {
            for (dry in listOf(true, false)) {
                calls.clear(); api.pages.clear(); phone.rows.clear(); mapDao.maps.clear(); crypto.encrypted.clear()
                api.pages += contact(1)
                phone.rows += row(10, 1)
                mapDao.maps += map(1, 10)
                mapper.patchIssues["1"] = issue

                val report = if (dry) engine().dryRun(account) else engine().sync(account)

                assertEquals(listOf("C3 0"), calls, "$issue, dry run $dry")
                assertEquals(listOf(reason), report.refused.map { it.reason })
                assertEquals(emptyList<Any>(), crypto.encrypted, "nothing to encrypt")
            }
        }
    }

    @Test
    fun `the photo uploads by its baseline`() = runTest {
        // Kept when unchanged, the phone's (scaled) when changed or new, removed when gone.
        data class Case(val baseline: String?, val phoneHash: String?, val expected: (PhotoUpload) -> Boolean, val what: String)
        val cases = listOf(
            Case(photoHashOf(10), photoHashOf(10), { it == PhotoUpload.Keep }, "unchanged"),
            Case(photoHashOf(10), "f".repeat(64), { it is PhotoUpload.Replace && it.jpeg.toList() == listOf<Byte>(3, 2, 1) }, "changed"),
            Case(null, "f".repeat(64), { it is PhotoUpload.Replace && it.jpeg.toList() == listOf<Byte>(3, 2, 1) }, "new on a keyed map"),
            Case(photoHashOf(10), null, { it == PhotoUpload.Remove }, "removed"),
            Case(null, null, { it == PhotoUpload.Keep }, "none on either side"),
        )
        for (case in cases) {
            api.pages.clear(); phone.rows.clear(); mapDao.maps.clear(); mapper.patched.clear(); photos.calls.clear()
            api.pages += contact(1)
            phone.rows += row(10, 1)
            phone.photoHashes[10] = case.phoneHash
            phone.photoBytes[10] = byteArrayOf(1, 2, 3)
            mapDao.maps += map(1, 10).copy(photoBaseline = case.baseline)

            engine().dryRun(account)

            assertTrue(case.expected(mapper.patched.single().third), case.what)
            if (mapper.patched.single().third is PhotoUpload.Replace) assertEquals(listOf(listOf<Byte>(1, 2, 3) to 720), photos.calls, "scaled to the phone's maximum")
        }
    }

    // The provider's photo clean-up deletes the display photo the app wrote, or clears its PHOTO_FILE_ID, leaving the
    // thumbnail read back after the write. That's the photo written.

    @Test
    fun `an edit with only the written photo's thumbnail left keeps Proton's photo`() = runTest {
        data class Case(val arrange: () -> Unit, val thumbnailBaseline: String?, val expected: (PhotoUpload?) -> Boolean, val what: String)
        val thumbnail = thumbnailHashOf(10)
        val refused: (PhotoUpload?) -> Boolean = { it == null }
        val cases = listOf(
            Case({ phone.photoHashes[10] = thumbnail }, thumbnail, { it == PhotoUpload.Keep }, "only the thumbnail left"),
            Case({ phone.onPhotoHash = { throw IOException() } }, thumbnail, { it == PhotoUpload.Keep }, "its display photo gone"),
            Case({ phone.photoHashes[10] = thumbnail }, null, { it is PhotoUpload.Replace }, "a map from before thumbnails were kept uploads it, as before"),
            Case({ phone.photoHashes[10] = "c".repeat(64); phone.thumbnailHashes[10] = "c".repeat(64) }, thumbnail, { it is PhotoUpload.Replace }, "another small photo"),
            Case({ phone.onPhotoHash = { throw IOException() }; phone.thumbnailHashes[10] = "c".repeat(64) }, thumbnail, refused, "a display photo gone whose thumbnail is another"),
            Case({ phone.onPhotoHash = { throw IOException() } }, null, refused, "a display photo gone on a map from before thumbnails were kept"),
            Case({ phone.photoHashes[10] = null }, null, { it == PhotoUpload.Remove }, "removed, on a map from before thumbnails were kept"),
        )
        for (case in cases) {
            api.pages.clear(); phone.rows.clear(); mapDao.maps.clear(); mapper.patched.clear()
            phone.photoHashes.clear(); phone.thumbnailHashes.clear(); phone.onPhotoHash = {}
            api.pages += contact(1)
            phone.rows += row(10, 1)
            phone.photoBytes[10] = byteArrayOf(1, 2, 3)
            mapDao.maps += map(1, 10).copy(photoThumbnailBaseline = case.thumbnailBaseline)
            case.arrange()

            val report = engine().dryRun(account)

            assertTrue(case.expected(mapper.patched.singleOrNull()?.third), case.what)
            val reasons = if (case.expected === refused) listOf(NotSyncedReason.PHOTO_UNREADABLE) else emptyList()
            assertEquals(reasons, report.refused.map { it.reason }, case.what)
        }
    }

    @Test
    fun `an edit with only the written photo's thumbnail left is sent with Proton's photo, and its row written from the re-read`() = runTest {
        // Written again, the row has its display photo back, and its map the thumbnail read back.
        api.pages += contact(1)
        phone.rows += row(10, 1, version = 7)
        phone.onPhotoHash = { throw IOException() }
        phone.onApply = { phone.onPhotoHash = {} }
        mapDao.maps += map(1, 10).copy(photoThumbnailBaseline = thumbnailHashOf(10))

        val report = engine().sync(account)

        assertEquals(PhotoUpload.Keep, mapper.patched.single().third)
        assertEquals(listOf("proton-id-1"), api.sent.map { it.first })
        assertEquals(ExistingRow(10, 7, clearDirty = true), phone.writes.single().existing)
        assertEquals(listOf(written(1, 10, uploaded(1))), mapDao.maps)
        assertEquals(1, report.counts().changedOnPhone)
    }

    @Test
    fun `a settled edit with only the written photo's thumbnail left is written again from Proton, nothing sent`() = runTest {
        // The display photo comes back on the phone; not counted, as no settled edit is.
        for (displayGone in listOf(false, true)) {
            val what = "display photo gone: $displayGone"
            calls.clear(); api.pages.clear(); phone.rows.clear(); phone.writes.clear(); phone.cleared.clear(); mapDao.maps.clear()
            phone.photoHashes.clear(); phone.onPhotoHash = {}
            api.pages += contact(1)
            phone.rows += row(10, 1, version = 7)
            phone.dataRowsByRaw[10] = remoteRows(1)
            mapDao.maps += map(1, 10).copy(photoThumbnailBaseline = thumbnailHashOf(10))
            if (displayGone) phone.onPhotoHash = { throw IOException() } else phone.photoHashes[10] = thumbnailHashOf(10)
            // Written again, the row has its display photo back.
            phone.onApply = { phone.photoHashes.clear(); phone.onPhotoHash = {} }

            val report = engine().sync(account)

            assertEquals(PlanCategory.SETTLED_REMOTE_CHANGED, report.plan!!.items.single().category, what)
            assertEquals(ExistingRow(10, 7, clearDirty = true), phone.writes.single().existing, what)
            assertEquals(emptyList<Any>(), phone.cleared, what)
            assertEquals(listOf(written(1, 10)), mapDao.maps, what)
            assertEquals(ReportCounts(0, 0, 0, 0, 0, 0, 0, false, false, 0, 0, 0), report.counts(), what)
            assertEquals(listOf("C3 0"), calls, what)
        }
    }

    @Test
    fun `a settled edit of a photo no larger than its thumbnail only has its DIRTY cleared`() = runTest {
        // Such a photo keeps no display photo, so its baseline is its thumbnail: nothing was lost.
        api.pages += contact(1)
        phone.rows += row(10, 1, version = 7)
        phone.dataRowsByRaw[10] = remoteRows(1)
        phone.photoHashes[10] = thumbnailHashOf(10)
        mapDao.maps += map(1, 10).copy(photoBaseline = thumbnailHashOf(10), photoThumbnailBaseline = thumbnailHashOf(10))

        engine().sync(account)

        assertEquals(listOf("proton-id-1" to ExistingRow(10, 7)), phone.cleared)
        assertEquals(emptyList<Any>(), phone.writes)
    }

    @Test
    fun `an edit the re-fetch shows Proton has, with only the written photo's thumbnail left, is written again from it`() = runTest {
        for ((uploadOnly, displayGone) in listOf(true to false, false to false, true to true)) {
            val what = "upload-only: $uploadOnly, display photo gone: $displayGone"
            calls.clear(); api.sent.clear(); api.pages.clear(); phone.rows.clear(); mapDao.maps.clear(); phone.cleared.clear(); phone.writes.clear()
            phone.photoHashes.clear(); phone.onPhotoHash = {}
            api.repeatFirstPage = !uploadOnly
            api.pages += contact(2)
            api.refetches["proton-id-1"] = ArrayDeque(listOf(contact(1)))
            phone.rows += row(10, 1, version = 7)
            phone.dataRowsByRaw[10] = remoteRows(1)
            if (displayGone) phone.onPhotoHash = { throw IOException() } else phone.photoHashes[10] = thumbnailHashOf(10)
            phone.onApply = { phone.photoHashes.clear(); phone.onPhotoHash = {} }
            mapDao.maps += map(1, 10).copy(photoThumbnailBaseline = thumbnailHashOf(10))

            val report = if (uploadOnly) engine().upload(account) else engine().sync(account)

            assertEquals(emptyList<Any>(), api.sent, what)
            assertEquals(ExistingRow(10, 7, clearDirty = true), phone.writes.single { it.protonId == "proton-id-1" }.existing, what)
            assertEquals(emptyList<Any>(), phone.cleared, what)
            assertEquals(written(1, 10), mapDao.maps.single { it.protonId == "proton-id-1" }, what)
            assertEquals(listOf(PlanCategory.SETTLED_REMOTE_CHANGED), report.reclassified.map { it.category }, what)
            assertEquals(0, report.counts().changedOnPhone, what)
        }
    }

    @Test
    fun `an edit whose remote moved to what it shows, with only the written photo's thumbnail left, is settled from the re-fetch`() = runTest {
        api.refetches["proton-id-1"] = ArrayDeque(listOf(contact(1).copy(cards = listOf(CardDto(1, "data-1-1-moved")))))
        mapper.graphs["plain(data-1-1-moved)"] = ContactGraph(uid = null, formattedName = "Edited on the phone")
        phone.rows += row(10, 1)
        phone.photoHashes[10] = thumbnailHashOf(10)
        mapDao.maps += map(1, 10).copy(photoThumbnailBaseline = thumbnailHashOf(10))

        val report = engine().upload(account)

        assertEquals(emptyList<Any>(), api.sent)
        assertEquals(listOf(PlanCategory.SETTLED_REMOTE_CHANGED), report.reclassified.map { it.category }, "not a conflict")
    }

    @Test
    fun `a phone photo that can't be read, or that the scaler refuses, can't be synced`() = runTest {
        val cases = listOf<Pair<() -> Unit, NotSyncedReason>>(
            { phone.photoFailure = IOException() } to NotSyncedReason.PHOTO_UNREADABLE,
            { phone.onPhotoHash = { throw IOException() } } to NotSyncedReason.PHOTO_UNREADABLE,
            { photos.result = ScaledPhoto.Malformed } to NotSyncedReason.PHOTO_MALFORMED,
            { photos.result = ScaledPhoto.Oversized } to NotSyncedReason.PHOTO_OVERSIZED,
        )
        for ((arrange, reason) in cases) {
            calls.clear(); api.pages.clear(); phone.rows.clear(); mapDao.maps.clear(); phone.photoFailure = null; phone.onPhotoHash = {}; photos.result = null
            api.pages += contact(1)
            phone.rows += row(10, 1)
            phone.photoHashes[10] = "f".repeat(64)
            mapDao.maps += map(1, 10)
            arrange()

            val report = engine().sync(account)

            assertEquals(listOf(reason), report.refused.map { it.reason })
            assertEquals(listOf("C3 0"), calls)
        }
    }

    @Test
    fun `the keys are unlocked again only to prepare or send an edit, and closed once the uploads end`() = runTest {
        keys.logged = true
        api.pages += listOf(contact(1), contact(2))
        phone.rows += listOf(row(10, 1), row(20, 2, dirty = false))
        mapDao.maps += listOf(map(1, 10), map(2, 20, "old-fingerprint"))

        engine().sync(account)

        assertEquals(2, keys.unlocks)
        assertTrue(keys.closed)
        assertEquals(listOf(true), phone.keysClosedWhenQueried, "the inventory's keys closed before the phone is read")
        val second = events.lastIndexOf("keys unlocked")
        assertTrue(events.indexOf("map proton-id-1") in second..events.lastIndexOf("keys closed"), "uploaded with the keys open: $events")
        assertTrue(events.lastIndexOf("keys closed") < events.indexOf("apply proton-id-2"), "closed before the downloads: $events")

        events.clear(); keys.unlocks = 0; phone.rows.clear(); mapDao.maps.clear(); api.pages.clear()
        api.pages += contact(2)
        phone.rows += row(20, 2, dirty = false)
        mapDao.maps += map(2, 20, "old-fingerprint")
        engine().sync(account)
        assertEquals(1, keys.unlocks, "nothing to upload")

        keys.unlocks = 0; phone.rows.clear(); mapDao.maps.clear(); api.pages.clear()
        api.pages += contact(1)
        phone.rows += row(10, 1)
        mapDao.maps += map(1, 10)
        engine().dryRun(account)
        assertEquals(2, keys.unlocks)
        assertTrue(keys.closed, "a dry run closes them once prepared")

        // A dry run doesn't send, so an edit an incomplete inventory left out has nothing to prepare.
        keys.unlocks = 0; phone.rows.clear(); mapDao.maps.clear(); api.pages.clear()
        api.repeatFirstPage = true
        api.pages += contact(2)
        phone.rows += row(10, 1)
        mapDao.maps += map(1, 10)
        engine().dryRun(account)
        assertEquals(1, keys.unlocks)
    }

    @Test
    fun `keys that don't unlock the second time stop the run before anything is sent or written`() = runTest {
        // As at step 1: a transient Keystore error, say, only blocks this run.
        api.pages += listOf(contact(1), contact(2))
        phone.rows += listOf(row(10, 1), row(20, 2, dirty = false))
        mapDao.maps += listOf(map(1, 10), map(2, 20, "old-fingerprint"))
        keys.secondUnlock = CryptoResult.Rejected(RejectReason.WRONG_PASSPHRASE)

        val report = engine().sync(account)

        assertEquals(Blocker.KEYS_DONT_UNLOCK, report.blocker)
        assertEquals(listOf("C3 0"), calls, "nothing sent")
        assertEquals(emptyList<Any>(), phone.writes, "nothing written, downloads included")
        assertEquals(listOf(map(1, 10), map(2, 20, "old-fingerprint")), mapDao.maps)
        assertTrue(keys.closed)

        // Credentials gone by then (a logout in between, say) are signed out.
        keys.secondUnlock = null
        calls.clear()
        val real = checkNotNull(store.credentials)
        var loads = 0
        store.credentials = { real() }
        store.loadAnswer = { if (++loads > 1) null else real() }

        val report2 = engine().sync(account)

        assertEquals(Blocker.SIGNED_OUT, report2.blocker)
        assertEquals(listOf("C3 0"), calls)
        assertEquals(emptyList<Any>(), phone.writes)
    }

    @Test
    fun `a legacy encrypted card goes up encrypted and signed`() = runTest {
        // docs/protocol.md § Contact cards: type 1 comes back as type 3.
        api.pages += contact(1)
        phone.rows += row(10, 1)
        mapDao.maps += map(1, 10)

        engine().sync(account)

        assertEquals(listOf(3), crypto.encrypted.single().map { it.type })
        assertEquals(listOf(3 to "signature"), api.sent.single().second.map { it.type to it.signature })
    }

    @Test
    fun `an edit left out of an incomplete inventory is re-fetched, then patched and sent`() = runTest {
        // A repeated page leaves the inventory incomplete, but no request failed.
        api.repeatFirstPage = true
        api.pages += contact(2)
        api.refetches["proton-id-1"] = ArrayDeque(listOf(contact(1)))
        phone.rows += row(10, 1)
        mapDao.maps += map(1, 10)

        val report = engine().sync(account)

        assertEquals(Blocker.INVENTORY_INCOMPLETE, report.blocker)
        assertEquals(listOf("plain(data-1-1)"), mapper.patched.single().second.map { it.vcard }, "patched from the re-fetched cards")
        assertEquals(listOf("proton-id-1"), api.sent.map { it.first })
        assertEquals(listOf("C2 1", "C5 1", "C2 1"), calls.filterNot { it.startsWith("C3") })
    }

    @Test
    fun `nothing uploads while offline or after a failed inventory request`() = runTest {
        for (offline in listOf(true, false)) {
            calls.clear(); phone.rows.clear(); mapDao.maps.clear()
            online = !offline
            api.failure = if (offline) null else ProtonError.Network()
            phone.rows += row(10, 1)
            mapDao.maps += map(1, 10)

            engine().sync(account)

            assertEquals(emptyList<String>(), calls.filterNot { it.startsWith("C3") }, "offline: $offline")
        }
    }

    @Test
    fun `a write the provider refuses while its guard holds after an upload stops the run as contacts hidden`() = runTest {
        api.pages += listOf(contact(1), contact(2), contact(3))
        phone.rows += listOf(row(10, 1), row(20, 2), row(30, 3, dirty = false))
        mapDao.maps += listOf(map(1, 10), map(2, 20), map(3, 30, "old-fingerprint"))
        phone.results["proton-id-1"] = ContactWriteResult.Failed(FailureReason.OPERATION_REFUSED)

        val report = engine().sync(account)

        assertEquals(Blocker.CONTACTS_HIDDEN, report.blocker)
        assertEquals(listOf("proton-id-1"), api.sent.map { it.first }, "nothing more sent")
        assertEquals(listOf("proton-id-1"), phone.writes.map { it.protonId }, "nothing more written, downloads included")
    }

    @Test
    fun `a re-read that fails after C5 stores nothing more, and a lost connection stops sending`() = runTest {
        api.pages += listOf(contact(1), contact(2))
        phone.rows += listOf(row(10, 1), row(20, 2))
        mapDao.maps += listOf(map(1, 10), map(2, 20))
        api.refetches["proton-id-1"] = ArrayDeque(listOf(contact(1), ProtonError.Network()))

        val report = engine().sync(account)

        assertEquals(listOf("C3 0", "C2 1", "C5 1", "C2 1"), calls)
        assertEquals(listOf(map(1, 10), map(2, 20)), mapDao.maps, "the next run's re-fetch decides")
        assertEquals(Blocker.PROTON_UNAVAILABLE, report.blocker)
    }

    @Test
    fun `a dry run prepares the same edits and sends or clears nothing`() = runTest {
        api.pages += listOf(contact(1), contact(2))
        phone.rows += listOf(row(10, 1), row(20, 2))
        mapDao.maps += listOf(map(1, 10), map(2, 20))
        mapper.patchIssues["2"] = MappingIssueCode.NO_NAME_OR_EMAIL

        val dry = engine().dryRun(account)

        assertEquals(listOf("C3 0"), calls)
        assertEquals(emptyList<Any>(), phone.writes)
        assertEquals(listOf(map(1, 10), map(2, 20)), mapDao.maps)
        assertEquals(1, crypto.encrypted.size, "prepared as a sync would")
        assertEquals(1 to 1, dry.counts().let { it.changedOnPhone to it.unreadable })

        val real = engine().sync(account)
        assertEquals(dry.counts(), real.counts(), "the same plan")
    }

    // --- Upload-only syncs, after a phone edit. ---

    @Test
    fun `an upload-only sync re-fetches and uploads each edit, without the inventory or downloads, and stores the phone's side`() = runTest {
        api.pages += listOf(contact(1), contact(2))
        phone.rows += listOf(row(10, 1, version = 7), row(20, 2, dirty = false), row(30, 3, dirty = false))
        // Row 20 changed on Proton and row 30 has no map: a full sync would write both.
        mapDao.maps += listOf(map(1, 10), map(2, 20, "old-fingerprint"))

        val report = engine().upload(account)

        assertEquals(listOf("C2 1", "C5 1", "C2 1"), calls, "no inventory")
        assertEquals(listOf("proton-id-1"), phone.writes.map { it.protonId }, "the edit's write-back only")
        assertEquals(ExistingRow(10, 7, clearDirty = true), phone.writes.single().existing)
        assertEquals(listOf(written(1, 10, uploaded(1)), map(2, 20, "old-fingerprint")), mapDao.maps.sortedBy { it.protonId }, "no map repair either")
        // The report is the latest known state: the phone's side counted now, Proton's never.
        val stored = reportDao.saved.single()
        assertEquals(Triple(ReportTrigger.PHONE_CHANGES, 1, 0), Triple(stored.trigger, stored.changedOnPhone, stored.changedOnProton))
        assertEquals(null to report.finishedAt, stored.protonCountedAt to stored.phoneCountedAt)
        assertEquals(ReportKind.SYNC, report.kind)
        assertTrue(report.ready)
    }

    @Test
    fun `an upload-only sync settles, conflicts, and refuses as a full sync does`() = runTest {
        api.pages += listOf(contact(1), contact(2), contact(3))
        api.refetches["proton-id-1"] = ArrayDeque(listOf(contact(1).copy(cards = listOf(CardDto(1, "data-1-1-moved")))))
        mapper.graphs["plain(data-1-1-moved)"] = ContactGraph(uid = null, formattedName = "Edited on the phone")
        api.refetches["proton-id-2"] = ArrayDeque(listOf(contact(2).copy(cards = listOf(CardDto(1, "data-2-1-moved")))))
        mapper.patchIssues["3"] = MappingIssueCode.NO_NAME_OR_EMAIL
        phone.rows += listOf(row(10, 1), row(20, 2), row(30, 3))
        mapDao.maps += listOf(map(1, 10), map(2, 20), map(3, 30))

        val report = engine().upload(account)

        assertEquals(emptyList<Any>(), api.sent)
        assertEquals(listOf(PlanCategory.SETTLED_REMOTE_CHANGED, PlanCategory.CONFLICT), report.reclassified.map { it.category })
        assertEquals(listOf("proton-id-3" to NotSyncedReason.NO_NAME_OR_EMAIL), report.refused.map { it.item.protonId?.value to it.reason })
    }

    @Test
    fun `an edit the re-fetch shows Proton already has is settled by clearing DIRTY alone, nothing sent`() = runTest {
        // A star, or a save without changes: the plan had no remote to compare with (an upload-only sync, or an
        // incomplete inventory), so the re-fetch decides as step 5 does.
        for (uploadOnly in listOf(true, false)) {
            calls.clear(); api.sent.clear(); api.pages.clear(); phone.rows.clear(); mapDao.maps.clear(); phone.cleared.clear(); phone.writes.clear()
            api.repeatFirstPage = !uploadOnly
            api.pages += contact(2)
            api.refetches["proton-id-1"] = ArrayDeque(listOf(contact(1)))
            phone.rows += row(10, 1, version = 7)
            phone.dataRowsByRaw[10] = remoteRows(1)
            mapDao.maps += map(1, 10)

            val report = if (uploadOnly) engine().upload(account) else engine().sync(account)

            assertEquals(emptyList<Any>(), api.sent, "upload-only: $uploadOnly")
            assertEquals(listOf("proton-id-1" to ExistingRow(10, 7)), phone.cleared, "upload-only: $uploadOnly")
            assertEquals(emptyList<String>(), phone.writes.map { it.protonId }.filter { it == "proton-id-1" })
            assertEquals(map(1, 10), mapDao.maps.single { it.protonId == "proton-id-1" }, "the map already holds this remote")
            assertEquals(listOf(PlanCategory.SETTLED), report.reclassified.map { it.category })
            assertEquals(0, report.counts().changedOnPhone, "not counted")
        }
    }

    @Test
    fun `an edit the re-fetch shows Proton has but for a photo changed on the phone uploads the photo`() = runTest {
        api.refetches["proton-id-1"] = ArrayDeque(listOf(contact(1)))
        phone.rows += row(10, 1)
        phone.dataRowsByRaw[10] = remoteRows(1)
        phone.photoHashes[10] = "f".repeat(64)
        mapDao.maps += map(1, 10)

        engine().upload(account)

        assertEquals(listOf("proton-id-1"), api.sent.map { it.first })
        assertTrue(mapper.patched.single().third is PhotoUpload.Replace)
        assertEquals(emptyList<Any>(), phone.cleared)
    }

    @Test
    fun `an upload-only sync with nothing to upload sends nothing, its keys unlocked only to check them`() = runTest {
        phone.rows += row(10, 1, dirty = false)
        mapDao.maps += map(1, 10)

        val report = engine().upload(account)

        assertEquals(emptyList<String>(), calls)
        assertEquals(1, keys.unlocks, "a login may be needed")
        assertTrue(keys.closed)
        assertTrue(report.ready)
    }

    @Test
    fun `while a login is needed an upload-only sync stops first, sending and writing nothing`() = runTest {
        // Keys that don't unlock, or a login gone, pause sync until the user logs in.
        phone.rows += listOf(row(10, 1), DeviceContact(20, "proton-id-2", dirty = true, deleted = true, version = 1, displayName = "Gone 20"))
        mapDao.maps += listOf(map(1, 10), map(2, 20))
        keys.result = CryptoResult.Rejected(RejectReason.WRONG_PASSPHRASE)

        val report = engine().upload(account)

        assertEquals(Blocker.KEYS_DONT_UNLOCK, report.blocker)
        assertNull(report.plan, "nothing counted")
        assertEquals(emptyList<String>(), calls, "no deletion either")
        assertEquals(emptyList<String>(), events)
        assertEquals(Blocker.KEYS_DONT_UNLOCK to ReportTrigger.PHONE_CHANGES, reportDao.saved.single().let { it.blocker to it.trigger }, "kept on screen with Log in")

        keys.result = null
        store.credentials = null
        assertEquals(Blocker.SIGNED_OUT, engine().upload(account).blocker)
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `an upload-only sync offline, signed out, or without the contacts permission sends nothing`() = runTest {
        phone.rows += row(10, 1)
        mapDao.maps += map(1, 10)

        online = false
        assertEquals(Blocker.OFFLINE, engine().upload(account).blocker)
        assertTrue(engine().upload(account).retryable)
        assertEquals(2, keys.unlocks, "the keys are checked first, offline too")
        assertTrue(keys.closed)
        online = true

        phone.failure = SecurityException()
        assertEquals(Blocker.PERMISSION_MISSING, engine().upload(account).blocker)
        phone.failure = null

        store.credentials = null
        assertEquals(Blocker.SIGNED_OUT, engine().upload(account).blocker)

        assertEquals(emptyList<String>(), calls)
        assertEquals(
            listOf(Blocker.OFFLINE, Blocker.OFFLINE, Blocker.PERMISSION_MISSING, Blocker.SIGNED_OUT),
            reportDao.saved.map { it.blocker },
            "each stored with its blocker",
        )
    }
}
