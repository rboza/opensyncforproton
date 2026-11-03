package dev.rboza.android.opensyncforproton.sync.engine

import dev.rboza.android.opensyncforproton.crypto.CryptoResult
import dev.rboza.android.opensyncforproton.crypto.RejectReason
import dev.rboza.android.opensyncforproton.mapping.ContactGraph
import dev.rboza.android.opensyncforproton.mapping.MappingIssueCode
import dev.rboza.android.opensyncforproton.proton.ProtonContactId
import dev.rboza.android.opensyncforproton.proton.ProtonError
import dev.rboza.android.opensyncforproton.proton.contactFingerprint
import dev.rboza.android.opensyncforproton.sync.db.Blocker
import dev.rboza.android.opensyncforproton.sync.db.ConflictHoldEntity
import dev.rboza.android.opensyncforproton.sync.db.ContactMapEntity
import dev.rboza.android.opensyncforproton.sync.db.HoldChoice
import dev.rboza.android.opensyncforproton.sync.db.HoldKind
import dev.rboza.android.opensyncforproton.sync.db.LastReportEntity
import dev.rboza.android.opensyncforproton.sync.db.ReportKind
import dev.rboza.android.opensyncforproton.sync.db.ReportTrigger
import dev.rboza.android.opensyncforproton.sync.provider.DeviceContact
import dev.rboza.android.opensyncforproton.sync.provider.dataRows
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

// The run report as the latest known state (docs/sync.md § Sync mode). Every value is made up for the test.
class RunReportTest : EngineHarness() {
    private val now = 1_767_225_600_000

    private fun fingerprint(n: Int) = contactFingerprint(ProtonContactId("proton-id-$n"), contact(n).cards).value

    /** A row made on the phone: no `SOURCE_ID`, dirty, showing [name]. */
    private fun newRow(raw: Long, name: String? = "Made here $raw") = DeviceContact(raw, null, dirty = true, deleted = false, version = 1, displayName = name).also {
        phone.rows += it
        phone.dataRowsByRaw[raw] = dataRows(ContactGraph(uid = null, formattedName = name), null)
    }

    /** A row made on the phone whose cards can't be made ("add a name or e-mail"), so nothing is sent for it. */
    private fun refusedRow(raw: Long) = newRow(raw, "No name $raw").also { mapper.createIssues["No name $raw"] = MappingIssueCode.NO_NAME_OR_EMAIL }

    /**
     * A full sync's report stored at 1 000, with a count in every line, three of the can't-be-synced and two of the
     * conflicts on the phone's side.
     */
    private fun earlier(blocker: Blocker? = null, kind: ReportKind = ReportKind.SYNC) = LastReportEntity(
        "user-id-1", kind, 1_000, blocker == null, blocker, newFromProton = 1, changedOnProton = 2, deletedOnProton = 3,
        newOnPhone = 4, changedOnPhone = 5, deletedOnPhone = 6, conflicts = 7, bulkGuardRemote = true, bulkGuardPhone = true,
        unreadable = 8, duplicateRows = 9, missingRows = 10, conflictsPhone = 2, unreadablePhone = 3, trigger = ReportTrigger.SCHEDULED,
    )

    @Test
    fun `an upload-only sync updates the phone's side and keeps Proton's side from the report before`() = runTest {
        reportDao.saved += earlier()
        newRow(30, "Ann")

        engine().upload(account)

        assertEquals(
            LastReportEntity(
                "user-id-1", ReportKind.SYNC, now, true, null, newFromProton = 1, changedOnProton = 2, deletedOnProton = 3,
                newOnPhone = 1, changedOnPhone = 0, deletedOnPhone = 0, conflicts = 5, bulkGuardRemote = true, bulkGuardPhone = false,
                unreadable = 5, duplicateRows = 0, missingRows = 10, conflictsPhone = 0, unreadablePhone = 0,
                trigger = ReportTrigger.PHONE_CHANGES, protonCountedAt = 1_000, phoneCountedAt = now,
            ),
            reportDao.saved.last(),
            "Proton's part of conflicts (5) and can't be synced (5) kept, the phone's replaced",
        )
    }

    @Test
    fun `Proton's side keeps the time it was counted through several partial runs`() = runTest {
        reportDao.saved += earlier().copy(protonCountedAt = 500)

        engine().upload(account)

        assertEquals(500L, reportDao.saved.last().protonCountedAt)
        assertEquals(now, reportDao.saved.last().phoneCountedAt)
    }

    @Test
    fun `a partial report after a dry run's has nothing from Proton's side`() = runTest {
        // A dry run's lines are what a sync would do, never what one did.
        reportDao.saved += earlier(kind = ReportKind.DRY_RUN)
        newRow(30, "Ann")

        engine().upload(account)

        val stored = reportDao.saved.last()
        assertEquals(listOf(0, 0, 0, 0, 0, 0), with(stored) { listOf(newFromProton, changedOnProton, deletedOnProton, conflicts, unreadable, missingRows) })
        assertFalse(stored.bulkGuardRemote)
        assertNull(stored.protonCountedAt, "never counted")
        assertEquals(1, stored.newOnPhone)
        assertEquals(now, stored.phoneCountedAt)
    }

    @Test
    fun `can't be synced and conflicts keep apart the part on rows the phone changed`() = runTest {
        // Unreadable on a clean row: Proton's side. A new row without a name: the phone's.
        api.pages += contact(1)
        crypto.results["proton-id-1"] = CryptoResult.Rejected(RejectReason.BAD_SIGNATURE)
        phone.rows += DeviceContact(10, "proton-id-1", dirty = false, deleted = false, version = 1, displayName = "Clean 10")
        mapDao.maps += ContactMapEntity("user-id-1", "proton-id-1", 10, fingerprint(1), rowsKeyed = true, photoBaseline = photoHashOf(10))
        refusedRow(30)
        // A tombstone whose remote moved: the phone's side. A clean row without a baseline gone from Proton: Proton's.
        api.pages += contact(4)
        phone.rows += DeviceContact(40, "proton-id-4", dirty = true, deleted = true, version = 1, displayName = "Deleted 40")
        mapDao.maps += ContactMapEntity("user-id-1", "proton-id-4", 40, "old-fingerprint", rowsKeyed = true, photoBaseline = photoHashOf(40))
        phone.rows += DeviceContact(50, "proton-id-5", dirty = false, deleted = false, version = 1, displayName = "Clean 50")
        mapDao.maps += ContactMapEntity("user-id-1", "proton-id-5", 50, null)

        engine().sync(account)

        val stored = reportDao.saved.single()
        assertEquals(2 to 1, stored.unreadable to stored.unreadablePhone)
        assertEquals(2 to 1, stored.conflicts to stored.conflictsPhone)
    }

    @Test
    fun `a refused upload counts on the phone's side`() = runTest {
        api.pages += contact(1)
        phone.rows += DeviceContact(10, "proton-id-1", dirty = true, deleted = false, version = 1, displayName = "Edited 10")
        phone.dataRowsByRaw[10] = dataRows(ContactGraph(uid = null, formattedName = "Edited 10"), null)
        mapDao.maps += ContactMapEntity("user-id-1", "proton-id-1", 10, fingerprint(1), rowsKeyed = true, photoBaseline = photoHashOf(10))
        api.updateFailures["proton-id-1"] = ProtonError.Api(422, 2000)

        engine().sync(account)

        val stored = reportDao.saved.single()
        assertEquals(1 to 1, stored.unreadable to stored.unreadablePhone)
    }

    @Test
    fun `a full sync replaces every line and names what started it`() = runTest {
        reportDao.saved += earlier()
        api.pages += contact(1)

        engine().sync(account, trigger = ReportTrigger.PROTON_CHANGES)

        assertEquals(
            LastReportEntity(
                "user-id-1", ReportKind.SYNC, now, true, null, newFromProton = 1, changedOnProton = 0, deletedOnProton = 0,
                newOnPhone = 0, changedOnPhone = 0, deletedOnPhone = 0, conflicts = 0, bulkGuardRemote = false, bulkGuardPhone = false,
                unreadable = 0, duplicateRows = 0, missingRows = 0, trigger = ReportTrigger.PROTON_CHANGES,
                protonCountedAt = now, phoneCountedAt = now,
            ),
            reportDao.saved.last(),
        )
    }

    /** A full sync's report stored at 1 000 with changes on both sides and nothing that needs the user. */
    private fun changesOnly() = LastReportEntity(
        "user-id-1", ReportKind.SYNC, 1_000, true, null, newFromProton = 1, changedOnProton = 2, deletedOnProton = 3,
        newOnPhone = 4, changedOnPhone = 5, deletedOnPhone = 6, conflicts = 0, bulkGuardRemote = false, bulkGuardPhone = false,
        unreadable = 0, duplicateRows = 0, missingRows = 0, trigger = ReportTrigger.IMPORT,
    )

    @Test
    fun `a sync that found nothing keeps each side's earlier changes with the time they were counted`() = runTest {
        // So a later run with nothing to do doesn't hide the import's counts.
        reportDao.saved += changesOnly()

        engine().sync(account, trigger = ReportTrigger.ANDROID_SETTINGS)

        assertEquals(
            changesOnly().copy(finishedAt = now, trigger = ReportTrigger.ANDROID_SETTINGS, protonCountedAt = 1_000, phoneCountedAt = 1_000),
            reportDao.saved.last(),
        )
    }

    @Test
    fun `an upload-only sync that found nothing after an import keeps the import's new on the phone`() = runTest {
        reportDao.saved += changesOnly().copy(newFromProton = 0, changedOnProton = 0, deletedOnProton = 0, newOnPhone = 1, changedOnPhone = 0, deletedOnPhone = 0)

        engine().upload(account)

        val stored = reportDao.saved.last()
        assertEquals(1 to 1_000L, stored.newOnPhone to stored.phoneCountedAt)
        assertEquals(now to ReportTrigger.PHONE_CHANGES, stored.finishedAt to stored.trigger)
    }

    @Test
    fun `a side the sync found something on is replaced, while the side it found nothing on is kept`() = runTest {
        reportDao.saved += changesOnly()
        api.pages += contact(1)

        engine().sync(account)

        assertEquals(
            changesOnly().copy(
                finishedAt = now, newFromProton = 1, changedOnProton = 0, deletedOnProton = 0, trigger = ReportTrigger.SYNC_NOW,
                protonCountedAt = now, phoneCountedAt = 1_000,
            ),
            reportDao.saved.last(),
        )

        reportDao.saved.clear(); api.pages.clear()
        reportDao.saved += changesOnly()
        newRow(30, "Ann")

        engine().upload(account)

        val stored = reportDao.saved.last()
        assertEquals(listOf(1, 0, 0), with(stored) { listOf(newOnPhone, changedOnPhone, deletedOnPhone) })
        assertEquals(now, stored.phoneCountedAt)
    }

    @Test
    fun `a side the sync found only a problem on is replaced`() = runTest {
        // The phone's side: a new row without a name or e-mail can't be synced. Proton's: a contact that won't verify.
        reportDao.saved += changesOnly()
        refusedRow(30)

        engine().upload(account)

        assertEquals(Triple(0, 1, now), reportDao.saved.last().let { Triple(it.newOnPhone, it.unreadablePhone, it.phoneCountedAt) })

        reportDao.saved.clear(); phone.rows.clear()
        reportDao.saved += changesOnly()
        api.pages += contact(1)
        crypto.results["proton-id-1"] = CryptoResult.Rejected(RejectReason.BAD_SIGNATURE)

        engine().sync(account)

        val stored = reportDao.saved.last()
        assertEquals(listOf(0, 0, 0, 1), with(stored) { listOf(newFromProton, changedOnProton, deletedOnProton, unreadable) })
        assertEquals(now to 1_000L, stored.protonCountedAt to stored.phoneCountedAt)
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = [
            "changed on Proton", "deleted on Proton", "Proton's conflict", "missing row",
            "changed on the phone", "deleted on the phone", "the phone's conflict", "duplicate rows",
        ],
    )
    fun `a side the sync found any one line on is replaced`(what: String) = runTest {
        reportDao.saved += changesOnly()
        fun row(raw: Long, n: Int, dirty: Boolean = false, deleted: Boolean = false) =
            DeviceContact(raw, "proton-id-$n", dirty = dirty, deleted = deleted, version = 1, displayName = "Row $raw")
        fun map(n: Int, raw: Long, fingerprint: String? = fingerprint(n)) =
            ContactMapEntity("user-id-1", "proton-id-$n", raw, fingerprint, rowsKeyed = true, photoBaseline = photoHashOf(raw))
        val proton = when (what) {
            "changed on Proton" -> { api.pages += contact(1); phone.rows += row(10, 1); mapDao.maps += map(1, 10, "old-fingerprint"); true }
            "deleted on Proton" -> { phone.rows += row(10, 1); mapDao.maps += map(1, 10); true }
            // A clean row without a baseline that Proton no longer has.
            "Proton's conflict" -> { phone.rows += row(10, 1); mapDao.maps += map(1, 10, null); true }
            // Beside an unchanged contact: no rows at all would be contacts hidden.
            "missing row" -> { api.pages += contact(1); mapDao.maps += map(1, 10); api.pages += contact(2); phone.rows += row(20, 2); mapDao.maps += map(2, 20); true }
            "changed on the phone" -> {
                api.pages += contact(1); phone.rows += row(10, 1, dirty = true); mapDao.maps += map(1, 10)
                phone.dataRowsByRaw[10] = dataRows(ContactGraph(uid = null, formattedName = "Edited 10"), null)
                false
            }
            "deleted on the phone" -> { api.pages += contact(1); phone.rows += row(10, 1, dirty = true, deleted = true); mapDao.maps += map(1, 10); false }
            // A tombstone whose remote moved.
            "the phone's conflict" -> { api.pages += contact(1); phone.rows += row(10, 1, dirty = true, deleted = true); mapDao.maps += map(1, 10, "old-fingerprint"); false }
            "duplicate rows" -> { api.pages += contact(1); phone.rows += row(10, 1); phone.rows += row(11, 1); mapDao.maps += map(1, 10); false }
            else -> error(what)
        }

        engine().sync(account)

        val stored = reportDao.saved.last()
        assertEquals(now, if (proton) stored.protonCountedAt else stored.phoneCountedAt, "the side it found something on")
    }

    @Test
    fun `a side whose guard stopped is replaced though its lines count nothing there`() = runTest {
        // 10 rows edited on the phone and gone from Proton: the remote guard stops, and each is a conflict on a row the
        // phone changed, so Proton's lines are all 0.
        reportDao.saved += changesOnly()
        api.pages += contact(99)
        phone.rows += DeviceContact(990, "proton-id-99", dirty = false, deleted = false, version = 1, displayName = "Row 990")
        mapDao.maps += ContactMapEntity("user-id-1", "proton-id-99", 990, fingerprint(99), rowsKeyed = true, photoBaseline = photoHashOf(990))
        for (n in 1..10) {
            phone.rows += DeviceContact(n * 10L, "proton-id-$n", dirty = true, deleted = false, version = 1, displayName = "Edited ${n * 10}")
            phone.dataRowsByRaw[n * 10L] = dataRows(ContactGraph(uid = null, formattedName = "Edited ${n * 10}"), null)
            mapDao.maps += ContactMapEntity("user-id-1", "proton-id-$n", n * 10L, fingerprint(n), rowsKeyed = true, photoBaseline = photoHashOf(n * 10L))
        }

        val report = engine().sync(account)

        assertTrue(report.counts().bulkGuardRemote)
        val stored = reportDao.saved.last()
        assertTrue(stored.bulkGuardRemote, "the guard stays shown, with its offer")
        assertEquals(listOf(0, 0, 0), with(stored) { listOf(newFromProton, changedOnProton, deletedOnProton) })
        assertEquals(now, stored.protonCountedAt)
    }

    @Test
    fun `a phone side whose guard stopped is replaced though its lines count nothing there`() = runTest {
        // 10 phone deletions held against Proton edits, each answered Use Proton: restored as new from Proton, so the
        // phone's lines are all 0 while its guard stops.
        reportDao.saved += changesOnly()
        api.pages += contact(99)
        phone.rows += DeviceContact(990, "proton-id-99", dirty = false, deleted = false, version = 1, displayName = "Row 990")
        mapDao.maps += ContactMapEntity("user-id-1", "proton-id-99", 990, fingerprint(99), rowsKeyed = true, photoBaseline = photoHashOf(990))
        for (n in 1..10) {
            api.pages += contact(n)
            phone.rows += DeviceContact(n * 10L, "proton-id-$n", dirty = true, deleted = true, version = 1, displayName = "Deleted ${n * 10}")
            mapDao.maps += ContactMapEntity("user-id-1", "proton-id-$n", n * 10L, "old-fingerprint", rowsKeyed = true, photoBaseline = photoHashOf(n * 10L))
            holdDao.holds += ConflictHoldEntity("user-id-1", "proton-id-$n", n * 10L, HoldKind.CONFLICT, HoldChoice.USE_PROTON, 1, fingerprint(n))
        }

        val report = engine().sync(account)

        assertTrue(report.counts().bulkGuardPhone)
        val stored = reportDao.saved.last()
        assertEquals(listOf(10, 0, 0, 0), with(stored) { listOf(newFromProton, newOnPhone, changedOnPhone, deletedOnPhone) })
        assertTrue(stored.bulkGuardPhone, "the guard stored")
        assertEquals(now, stored.phoneCountedAt)
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["newFromProton", "changedOnProton", "deletedOnProton", "newOnPhone", "changedOnPhone", "deletedOnPhone"])
    fun `a side holding any one change is kept by a sync that found nothing`(line: String) = runTest {
        val none = changesOnly().copy(newFromProton = 0, changedOnProton = 0, deletedOnProton = 0, newOnPhone = 0, changedOnPhone = 0, deletedOnPhone = 0)
        reportDao.saved += when (line) {
            "newFromProton" -> none.copy(newFromProton = 1)
            "changedOnProton" -> none.copy(changedOnProton = 1)
            "deletedOnProton" -> none.copy(deletedOnProton = 1)
            "newOnPhone" -> none.copy(newOnPhone = 1)
            "changedOnPhone" -> none.copy(changedOnPhone = 1)
            "deletedOnPhone" -> none.copy(deletedOnPhone = 1)
            else -> error(line)
        }

        engine().sync(account)

        val stored = reportDao.saved.last()
        val proton = line.endsWith("Proton")
        assertEquals(if (proton) 1_000L to now else now to 1_000L, stored.protonCountedAt to stored.phoneCountedAt)
        assertEquals(1, with(stored) { listOf(newFromProton, changedOnProton, deletedOnProton, newOnPhone, changedOnPhone, deletedOnPhone) }.sum())
    }

    @Test
    fun `an earlier side holding anything that needs the user is replaced by a sync that found nothing there`() = runTest {
        // A conflict or problem gone is news; only changes are kept.
        for ((what, earlier) in listOf(
            "Proton's conflict" to changesOnly().copy(conflicts = 1),
            "Proton's can't be synced" to changesOnly().copy(unreadable = 1),
            "missing rows" to changesOnly().copy(missingRows = 1),
            "the remote guard" to changesOnly().copy(bulkGuardRemote = true),
            "the phone's conflict" to changesOnly().copy(conflicts = 1, conflictsPhone = 1),
            "the phone's can't be synced" to changesOnly().copy(unreadable = 1, unreadablePhone = 1),
            "duplicate rows" to changesOnly().copy(duplicateRows = 1),
            "the phone guard" to changesOnly().copy(bulkGuardPhone = true),
        )) {
            reportDao.saved.clear()
            reportDao.saved += earlier

            engine().sync(account)

            val stored = reportDao.saved.last()
            val proton = what in listOf("Proton's conflict", "Proton's can't be synced", "missing rows", "the remote guard")
            assertEquals(if (proton) now to 1_000L else 1_000L to now, stored.protonCountedAt to stored.phoneCountedAt, what)
            assertEquals(
                if (proton) listOf(0, 0, 0, 4, 5, 6) else listOf(1, 2, 3, 0, 0, 0),
                with(stored) { listOf(newFromProton, changedOnProton, deletedOnProton, newOnPhone, changedOnPhone, deletedOnPhone) },
                what,
            )
            assertEquals(listOf(0, 0, 0, 0, 0, 0), with(stored) { listOf(conflicts, conflictsPhone, unreadable, unreadablePhone, duplicateRows, missingRows) }, what)
            assertFalse(stored.bulkGuardRemote || stored.bulkGuardPhone, what)
        }
    }

    @Test
    fun `a side with no changes before is counted again by a sync that found nothing`() = runTest {
        reportDao.saved += changesOnly().copy(newFromProton = 0, changedOnProton = 0, deletedOnProton = 0, newOnPhone = 0, changedOnPhone = 0, deletedOnPhone = 0)

        engine().sync(account)

        assertEquals(now to now, reportDao.saved.last().let { it.protonCountedAt to it.phoneCountedAt })
    }

    @Test
    fun `a full sync stopped before counting keeps every line, with its own time, blocker, and trigger`() = runTest {
        reportDao.saved += earlier()
        store.credentials = null

        val report = engine().sync(account, trigger = ReportTrigger.SCHEDULED)

        assertEquals(Blocker.SIGNED_OUT, report.blocker)
        assertEquals(
            earlier().copy(finishedAt = now, ready = false, blocker = Blocker.SIGNED_OUT, protonCountedAt = 1_000, phoneCountedAt = 1_000),
            reportDao.saved.last(),
        )
    }

    @Test
    fun `a sync stopped before counting, with no sync's report before it, has nothing counted`() = runTest {
        reportDao.saved += earlier(kind = ReportKind.DRY_RUN)
        store.credentials = null

        engine().sync(account)

        assertEquals(
            zeroCounts(Blocker.SIGNED_OUT, ReportKind.SYNC).copy(trigger = ReportTrigger.SYNC_NOW, protonCountedAt = null, phoneCountedAt = null),
            reportDao.saved.last(),
        )
    }

    @Test
    fun `a failed check keeps every line and stores its blocker`() = runTest {
        reportDao.saved += earlier()

        val report = engine().failedCheck(account, Blocker.PROTON_UNAVAILABLE)

        assertEquals(ReportKind.SYNC to Blocker.PROTON_UNAVAILABLE, report.kind to report.blocker)
        assertNull(report.plan)
        assertEquals(now, report.finishedAt)
        assertEquals(
            earlier().copy(
                finishedAt = now, ready = false, blocker = Blocker.PROTON_UNAVAILABLE, trigger = ReportTrigger.CHECK,
                protonCountedAt = 1_000, phoneCountedAt = 1_000,
            ),
            reportDao.saved.last(),
        )
        assertEquals(emptyList<Any>(), calls, "nothing sent")
    }

    @Test
    fun `a phone-changes run that never reached Proton keeps the problem only Proton shows gone`() = runTest {
        // The session, a captcha, and this app version. Signed out and keys that don't unlock it checks itself, first.
        for (blocker in listOf(Blocker.SESSION_EXPIRED, Blocker.CAPTCHA, Blocker.APP_VERSION_REJECTED)) {
            reportDao.saved.clear(); phone.rows.clear(); calls.clear()
            reportDao.saved += earlier(blocker)
            // Refused before anything is sent.
            refusedRow(30)

            val report = engine().upload(account)

            assertTrue(report.ready, "its own run met no blocker")
            assertFalse(report.reachedProton)
            assertEquals(emptyList<Any>(), calls)
            assertEquals(blocker to false, reportDao.saved.last().let { it.blocker to it.ready }, "$blocker")
        }
    }

    @Test
    fun `a run that never reached Proton keeps such a problem over its own offline or Proton unavailable`() = runTest {
        // So an offline night neither hides it nor notifies it again. The event loop checked the login and keys before
        // its failed check, so those it doesn't keep.
        for ((stored, kept) in listOf(
            Blocker.SESSION_EXPIRED to true, Blocker.CAPTCHA to true, Blocker.APP_VERSION_REJECTED to true,
            Blocker.SIGNED_OUT to false, Blocker.KEYS_DONT_UNLOCK to false,
        )) {
            for (own in listOf(Blocker.OFFLINE, Blocker.PROTON_UNAVAILABLE)) {
                reportDao.saved.clear()
                reportDao.saved += earlier(stored)

                val report = engine().failedCheck(account, own)

                assertEquals(own, report.blocker, "the run's own")
                assertEquals((if (kept) stored else own) to false, reportDao.saved.last().let { it.blocker to it.ready }, "$stored, $own")
            }
        }
    }

    @Test
    fun `a full sync offline keeps only what Proton shows gone, having loaded the credentials and keys first`() = runTest {
        online = false
        for ((stored, expected) in listOf(
            Blocker.SESSION_EXPIRED to Blocker.SESSION_EXPIRED,
            Blocker.CAPTCHA to Blocker.CAPTCHA,
            Blocker.APP_VERSION_REJECTED to Blocker.APP_VERSION_REJECTED,
            Blocker.SIGNED_OUT to Blocker.OFFLINE,
            Blocker.KEYS_DONT_UNLOCK to Blocker.OFFLINE,
        )) {
            reportDao.saved.clear()
            reportDao.saved += earlier(stored)

            engine().sync(account)

            assertEquals(expected, reportDao.saved.last().blocker, "$stored")
        }
    }

    @Test
    fun `a blocker of its own that isn't offline or Proton unavailable replaces the stored one`() = runTest {
        for ((stored, own) in listOf(Blocker.CAPTCHA to Blocker.SESSION_EXPIRED, Blocker.SESSION_EXPIRED to Blocker.INVENTORY_INCOMPLETE)) {
            reportDao.saved.clear()
            reportDao.saved += earlier(stored)

            engine().failedCheck(account, own)

            assertEquals(own, reportDao.saved.last().blocker, "$stored, $own")
        }
    }

    @Test
    fun `a problem a partial run can tell without Proton isn't kept`() = runTest {
        reportDao.saved += earlier(Blocker.PROTON_UNAVAILABLE)

        engine().upload(account)

        assertEquals(null to true, reportDao.saved.last().let { it.blocker to it.ready })
    }

    @Test
    fun `a phone-changes run Proton answered clears the session problem`() = runTest {
        reportDao.saved += earlier(Blocker.SESSION_EXPIRED)
        newRow(30, "Ann")

        val report = engine().upload(account)

        assertEquals(listOf("C4"), calls.filter { it == "C4" })
        assertTrue(report.reachedProton)
        assertEquals(null to true, reportDao.saved.last().let { it.blocker to it.ready })
    }

    @Test
    fun `Proton refusing a change is an answer too, and a network failure isn't`() = runTest {
        reportDao.saved += earlier(Blocker.CAPTCHA)
        newRow(30, "Ann")
        api.createAnswers += ProtonError.Api(422, 2000)

        assertTrue(engine().upload(account).reachedProton)

        reportDao.saved.clear(); phone.rows.clear(); pending.records.clear()
        reportDao.saved += earlier(Blocker.PROTON_UNAVAILABLE)
        newRow(40, "Ben")
        api.createAnswers += ProtonError.Api(503, null)

        val failed = engine().upload(account)

        assertFalse(failed.reachedProton, "a 5xx isn't an answer")
        assertEquals(Blocker.PROTON_UNAVAILABLE, reportDao.saved.last().blocker)

        reportDao.saved.clear(); phone.rows.clear(); pending.records.clear()
        reportDao.saved += earlier(Blocker.CAPTCHA)
        newRow(50, "Cy")
        api.createAnswers += ProtonError.Network()

        assertFalse(engine().upload(account).reachedProton, "nor is a network failure")
        assertEquals(Blocker.CAPTCHA, reportDao.saved.last().blocker, "kept over its own Proton unavailable")
    }

    @Test
    fun `a check's upload-only sync reached Proton through the feed`() = runTest {
        reportDao.saved += earlier(Blocker.CAPTCHA)

        val report = engine().upload(account, ReportTrigger.CHECK)

        assertEquals(emptyList<Any>(), calls, "nothing to send")
        assertTrue(report.reachedProton)
        assertEquals(Triple(null, true, ReportTrigger.CHECK), reportDao.saved.last().let { Triple(it.blocker, it.ready, it.trigger) })
    }

    @Test
    fun `a dry run replaces the report, whatever was stored, with every line counted by it and no trigger`() = runTest {
        reportDao.saved += earlier()
        store.credentials = null

        engine().dryRun(account)

        assertEquals(zeroCounts(Blocker.SIGNED_OUT), reportDao.saved.last())
        assertNull(reportDao.saved.last().trigger)
        assertEquals(now to now, reportDao.saved.last().let { it.protonCountedAt to it.phoneCountedAt })
    }
}
