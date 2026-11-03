package dev.rboza.android.opensyncforproton.sync.engine

import dev.rboza.android.opensyncforproton.account.AccountId
import dev.rboza.android.opensyncforproton.crypto.CryptoResult
import dev.rboza.android.opensyncforproton.crypto.RejectReason
import dev.rboza.android.opensyncforproton.mapping.CardPlacement
import dev.rboza.android.opensyncforproton.proton.HvDetails
import dev.rboza.android.opensyncforproton.proton.ProtonContactId
import dev.rboza.android.opensyncforproton.proton.ProtonError
import dev.rboza.android.opensyncforproton.proton.Tokens
import dev.rboza.android.opensyncforproton.proton.contactFingerprint
import dev.rboza.android.opensyncforproton.sync.db.Blocker
import dev.rboza.android.opensyncforproton.sync.db.ContactMapEntity
import dev.rboza.android.opensyncforproton.sync.db.LastReportEntity
import dev.rboza.android.opensyncforproton.sync.db.ReportKind
import dev.rboza.android.opensyncforproton.sync.plan.PlanCategory
import dev.rboza.android.opensyncforproton.sync.plan.PlanItem
import dev.rboza.android.opensyncforproton.sync.plan.SyncPlan
import dev.rboza.android.opensyncforproton.sync.plan.ReportCounts
import dev.rboza.android.opensyncforproton.sync.plan.UnreadableReason
import dev.rboza.android.opensyncforproton.sync.provider.DeviceContact
import dev.rboza.android.opensyncforproton.sync.provider.PhoneAccount
import dev.rboza.android.opensyncforproton.sync.provider.ProviderUnavailableException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Dry runs and their blockers: docs/sync.md § Sync mode and § The plan. Every value is made up for the test.
class DryRunTest : EngineHarness() {
    @Test
    fun `a dry run plans and stores only its report`() = runTest {
        api.pages += listOf(contact(1), contact(2))

        val report = engine().dryRun(account)

        assertTrue(report.ready)
        assertNull(report.blocker)
        assertEquals(listOf(PlanCategory.NEW_FROM_PROTON, PlanCategory.NEW_FROM_PROTON), report.plan!!.items.map { it.category })
        assertEquals(listOf("Contact proton-id-1", "Contact proton-id-2"), report.plan!!.items.map { it.displayName })
        assertEquals(
            listOf(
                LastReportEntity(
                    "user-id-1", ReportKind.DRY_RUN, 1_767_225_600_000, ready = true, blocker = null, newFromProton = 2, changedOnProton = 0,
                    deletedOnProton = 0, newOnPhone = 0, changedOnPhone = 0, deletedOnPhone = 0, conflicts = 0, bulkGuardRemote = false,
                    bulkGuardPhone = false, unreadable = 0, duplicateRows = 0, missingRows = 0,
                ),
            ),
            reportDao.saved,
        )
        assertEquals(listOf("C3 0"), calls.filter { it.startsWith("C") || it.startsWith("A") || it.startsWith("U") }, "reads only")
        assertEquals(emptyList<String>(), events, "no provider write, no map write")
        assertEquals(0, phone.maxDimensionReads)
        assertTrue(photos.calls.isEmpty())
    }

    @Test
    fun `the client uses the account's persisted session`() = runTest {
        engine().dryRun(account)

        assertEquals("access-1", store_given!!.current()?.access)
    }

    @Test
    fun `the keys are unlocked with the stored passphrases and closed after the run`() = runTest {
        api.pages += listOf(contact(1))

        engine().dryRun(account)

        assertEquals(mapOf("key-1" to "passphrase-1"), keys.passphrasesSeen)
        assertTrue(keys.closed, "unlocked keys closed")
        assertTrue(store.loaded.isNotEmpty())
        assertTrue(store.loaded.all { creds -> creds.passphrases.values.all { bytes -> bytes.all { it == 0.toByte() } } }, "passphrases zeroed")
    }

    @Test
    fun `card types become placements, 1 and 3 encrypted`() = runTest {
        api.pages += listOf(contact(1, 0, 1, 2, 3))

        engine().dryRun(account)

        assertEquals(listOf(CardPlacement.CLEAR, CardPlacement.ENCRYPTED, CardPlacement.SIGNED, CardPlacement.ENCRYPTED), mapper.placements.single())
        assertEquals(listOf("plain(data-1-0)", "plain(data-1-1)", "plain(data-1-2)", "plain(data-1-3)"), mapper.texts.single())
    }

    @Test
    fun `unreadable contacts keep their reason`() = runTest {
        api.pages += listOf(contact(1), contact(2), contact(3), contact(4))
        crypto.results["proton-id-1"] = CryptoResult.Rejected(RejectReason.BAD_SIGNATURE)
        crypto.results["proton-id-2"] = CryptoResult.Rejected(RejectReason.CORRUPT_DATA)
        mapper.invalid += "plain(data-3-1)"

        val items = engine().dryRun(account).plan!!.items

        assertEquals(
            listOf(UnreadableReason.VERIFY, UnreadableReason.DECRYPT, UnreadableReason.PARSE, null),
            items.map { it.unreadableReason },
        )
        assertEquals(PlanCategory.NEW_FROM_PROTON, items[3].category)
    }

    @Test
    fun `a contact whose cards match the map's fingerprint is unchanged`() = runTest {
        val remote = contact(1)
        api.pages += listOf(remote)
        phone.rows += DeviceContact(10, "proton-id-1", dirty = false, deleted = false, version = 1, displayName = "Row")
        mapDao.maps += ContactMapEntity("user-id-1", "proton-id-1", 10, contactFingerprint(ProtonContactId("proton-id-1"), remote.cards).value, rowsKeyed = true)

        val report = engine().dryRun(account)

        assertEquals(emptyList<Any>(), report.plan!!.items)
        assertEquals(listOf("user-id-1"), mapDao.asked)
    }

    @Test
    fun `the dry run before Start plans every direction, and writes and sends nothing`() = runTest {
        // After an upgrade from a download-only version, what it left behind is seen before anything uploads.
        fun print(n: Int) = contactFingerprint(ProtonContactId("proton-id-$n"), contact(n).cards).value
        api.pages += listOf(contact(1), contact(2), contact(3), contact(4), contact(5), contact(6), contact(7))
        phone.rows += listOf(
            DeviceContact(10, "proton-id-1", dirty = true, deleted = false, version = 1, displayName = "Edited"),
            DeviceContact(20, "proton-id-2", dirty = true, deleted = true, version = 1, displayName = "Deleted"),
            DeviceContact(30, "proton-id-3", dirty = true, deleted = false, version = 1, displayName = "Edited under v0.1.0"),
            DeviceContact(40, "proton-id-4", dirty = false, deleted = false, version = 1, displayName = "Written by v0.1.0"),
            DeviceContact(50, "proton-id-5", dirty = true, deleted = false, version = 1, displayName = "Both"),
            DeviceContact(60, "proton-id-6", dirty = false, deleted = false, version = 1, displayName = "Changed there"),
            DeviceContact(80, "proton-id-8", dirty = false, deleted = false, version = 1, displayName = "Gone there"),
            DeviceContact(90, null, dirty = true, deleted = false, version = 1, displayName = "Made here"),
            DeviceContact(95, "proton-id-9", dirty = true, deleted = true, version = 1, displayName = "Gone on both"),
        )
        mapDao.maps += listOf(
            ContactMapEntity("user-id-1", "proton-id-1", 10, print(1), rowsKeyed = true),
            ContactMapEntity("user-id-1", "proton-id-2", 20, print(2), rowsKeyed = false),
            ContactMapEntity("user-id-1", "proton-id-3", 30, print(3), rowsKeyed = false),
            ContactMapEntity("user-id-1", "proton-id-4", 40, print(4), rowsKeyed = false),
            ContactMapEntity("user-id-1", "proton-id-5", 50, "old-fingerprint", rowsKeyed = true),
            ContactMapEntity("user-id-1", "proton-id-6", 60, "old-fingerprint", rowsKeyed = true),
            ContactMapEntity("user-id-1", "proton-id-8", 80, "fingerprint-8", rowsKeyed = true),
            ContactMapEntity("user-id-1", "proton-id-9", 95, "fingerprint-9", rowsKeyed = true),
            // Nothing left to pair: a real sync would delete it.
            ContactMapEntity("user-id-1", "proton-id-99", 99, "fingerprint-99", rowsKeyed = true),
        )
        val maps = mapDao.maps.toList()

        val report = engine().dryRun(account)

        assertEquals(
            listOf(
                PlanCategory.CHANGED_ON_PHONE, PlanCategory.DELETED_ON_PHONE, PlanCategory.EDITED_BEFORE_KEYS, PlanCategory.WRITTEN_BEFORE_KEYS,
                PlanCategory.CONFLICT, PlanCategory.CHANGED_ON_PROTON, PlanCategory.NEW_FROM_PROTON, PlanCategory.DELETED_ON_PROTON,
                PlanCategory.DELETED_ON_BOTH, PlanCategory.NEW_ON_PHONE,
            ),
            report.plan!!.items.map { it.category },
        )
        assertEquals(ReportCounts(1, 1, 1, 1, 1, 1, 2, false, false, 0, 0, 0), report.counts(), "written before keys isn't counted")
        assertEquals(listOf("C3 0"), calls, "no C4, C5, or C6")
        // Row 10's photo has no baseline, so the edit's is read to prepare its upload; row 90's cards are made too.
        assertEquals(
            listOf("read back 10", "read back 50", "read back 90", "photo 10", "photo 90"), events,
            "the dirty rows with keys and the new row read back; no provider write, no map or hold write",
        )
        assertEquals(maps, mapDao.maps)
        assertEquals(ReportKind.DRY_RUN, reportDao.saved.single().kind)
    }

    @Test
    fun `no credentials means signed out, and nothing is sent`() = runTest {
        store.credentials = null
        // Signed out beats offline: the network is asked for only after the keys unlock.
        online = false

        val report = engine().dryRun(account)

        assertEquals(Blocker.SIGNED_OUT, report.blocker)
        assertFalse(report.ready)
        assertNull(report.plan)
        assertEquals(listOf(zeroCounts(Blocker.SIGNED_OUT)), reportDao.saved)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `keys that don't unlock stop before any request`() = runTest {
        keys.result = CryptoResult.Rejected(RejectReason.WRONG_PASSPHRASE)
        online = false

        val report = engine().dryRun(account)

        assertEquals(Blocker.KEYS_DONT_UNLOCK, report.blocker)
        assertEquals(listOf(zeroCounts(Blocker.KEYS_DONT_UNLOCK)), reportDao.saved)
        assertTrue(calls.isEmpty())
        assertTrue(store.loaded.isNotEmpty())
        assertTrue(store.loaded.all { creds -> creds.passphrases.values.all { bytes -> bytes.all { it == 0.toByte() } } })
    }

    @Test
    fun `inventory errors map to blockers, and the plan still counts what arrived`() = runTest {
        val cases = listOf(
            ProtonError.SessionRejected() to Blocker.SESSION_EXPIRED,
            ProtonError.NoSession() to Blocker.SESSION_EXPIRED,
            ProtonError.Network() to Blocker.PROTON_UNAVAILABLE,
            ProtonError.RateLimited(60.seconds) to Blocker.PROTON_UNAVAILABLE,
            ProtonError.BadResponse(200) to Blocker.PROTON_UNAVAILABLE,
            ProtonError.Api(503, null) to Blocker.PROTON_UNAVAILABLE,
            ProtonError.Api(500, null) to Blocker.PROTON_UNAVAILABLE,
            ProtonError.HumanVerification(HvDetails("hv-token", "https://verify.proton.me/", listOf("captcha"))) to Blocker.CAPTCHA,
            ProtonError.AppVersionRejected(5003) to Blocker.APP_VERSION_REJECTED,
            ProtonError.Api(422, 2003) to Blocker.INVENTORY_INCOMPLETE,
            ProtonError.Api(499, null) to Blocker.INVENTORY_INCOMPLETE,
            ProtonError.InvalidRequest() to Blocker.INVENTORY_INCOMPLETE,
        )
        cases.forEach { (error, blocker) ->
            api.pages.clear()
            api.pages += List(50) { contact(it) }
            api.failure = error
            reportDao.saved.clear()

            val report = engine().dryRun(account)

            assertEquals(blocker, report.blocker, "$error")
            assertEquals(50, report.plan!!.counts().newFromProton, "the first page still counts: $error")
            assertEquals(blocker, reportDao.saved.single().blocker)
            assertFalse(reportDao.saved.single().ready)
        }
    }

    @Test
    fun `a network error while the device is offline is offline`() = runTest {
        // The network drops after the run asked for it: the request is sent and fails.
        api.onExport = { online = false }
        api.failure = ProtonError.Network()
        api.pages += List(50) { contact(it) }

        assertEquals(Blocker.OFFLINE, engine().dryRun(account).blocker)
        assertEquals(listOf("C3 0", "C3 1"), calls)
    }

    @Test
    fun `a device without a network is offline at once, with nothing sent`() = runTest {
        // Offline: no request is tried, retried, and timed out first.
        online = false
        api.pages += List(50) { contact(it) }
        phone.rows += DeviceContact(99, "proton-id-99", dirty = false, deleted = false, version = 1, displayName = "Row")
        mapDao.maps += ContactMapEntity("user-id-1", "proton-id-99", 99, null)

        val report = engine().dryRun(account)

        assertEquals(Blocker.OFFLINE, report.blocker)
        assertEquals(emptyList<String>(), calls, "no request to Proton")
        assertNull(store_given, "no client made")
        // As after any incomplete inventory: the phone's rows are still read, and nothing is gone from Proton.
        assertEquals(listOf(true), phone.keysClosedWhenQueried, "the rows read, after the keys closed")
        assertEquals(listOf(0, 0), report.counts().let { listOf(it.newFromProton, it.deletedOnProton) })
        assertFalse(report.plan!!.bulkGuardRemote, "an empty inventory that never arrived isn't an empty account")
        assertFalse(report.plan!!.bulkGuardPhone)
        assertEquals(Blocker.OFFLINE, reportDao.saved.single().blocker)
        assertTrue(keys.closed)
    }

    @Test
    fun `an incomplete inventory without an error is inventory incomplete`() = runTest {
        // The same full page twice: a repeated page.
        api.pages += List(50) { contact(it) }
        api.repeatFirstPage = true

        val report = engine().dryRun(account)

        assertEquals(Blocker.INVENTORY_INCOMPLETE, report.blocker)
        assertEquals(50, report.plan!!.counts().newFromProton)
    }

    @Test
    fun `a missing contacts permission stops the run`() = runTest {
        api.pages += listOf(contact(1))
        phone.failure = SecurityException()

        val report = engine().dryRun(account)

        assertEquals(Blocker.PERMISSION_MISSING, report.blocker)
        assertNull(report.plan)
        assertEquals(listOf(zeroCounts(Blocker.PERMISSION_MISSING)), reportDao.saved)
        assertTrue(keys.closed)
    }

    @Test
    fun `no rows while maps exist means contacts are hidden`() = runTest {
        api.pages += listOf(contact(1))
        mapDao.maps += ContactMapEntity("user-id-1", "proton-id-1", 10, null)

        val report = engine().dryRun(account)

        assertEquals(Blocker.CONTACTS_HIDDEN, report.blocker)
        assertNull(report.plan, "planning on no rows would call every contact new")
        assertEquals(listOf(zeroCounts(Blocker.CONTACTS_HIDDEN)), reportDao.saved)
    }

    @Test
    fun `no rows and no maps is just an empty phone`() = runTest {
        api.pages += listOf(contact(1))

        assertNull(engine().dryRun(account).blocker)
    }

    @Test
    fun `the first blocker wins, except that the rows' blocker beats the inventory's`() = runTest {
        // An inventory blocker comes before a key or credential one can't, so order matters between the inventory and
        // the rows: without the rows nothing is counted, so their blocker is the report's.
        online = false
        api.failure = ProtonError.Network()
        api.pages += List(50) { contact(it) }
        val cases = listOf(
            { phone.failure = SecurityException() } to Blocker.PERMISSION_MISSING,
            { phone.failure = ProviderUnavailableException() } to Blocker.CONTACTS_UNAVAILABLE,
            { phone.failure = null; mapDao.maps += ContactMapEntity("user-id-1", "proton-id-1", 10, null) } to Blocker.CONTACTS_HIDDEN,
        )
        cases.forEach { (setUp, blocker) ->
            setUp()
            reportDao.saved.clear()

            val report = engine().dryRun(account)

            assertEquals(blocker, report.blocker)
            assertNull(report.plan)
            assertEquals(listOf(zeroCounts(blocker)), reportDao.saved)
        }
    }

    @Test
    fun `the keys are closed before the phone is read`() = runTest {
        api.pages += listOf(contact(1))

        engine().dryRun(account)

        assertEquals(listOf(true), phone.keysClosedWhenQueried)
    }

    @Test
    fun `a contact an incomplete inventory left out isn't deleted on Proton`() = runTest {
        api.pages += List(50) { contact(it) }
        api.failure = ProtonError.Network()
        phone.rows += DeviceContact(99, "proton-id-99", dirty = false, deleted = false, version = 1, displayName = "Row")
        mapDao.maps += ContactMapEntity("user-id-1", "proton-id-99", 99, null)

        assertEquals(0, engine().dryRun(account).counts().deletedOnProton)
    }

    @Test
    fun `a contacts provider that doesn't answer is its own blocker`() = runTest {
        api.pages += listOf(contact(1))
        phone.failure = ProviderUnavailableException()

        val report = engine().dryRun(account)

        assertEquals(Blocker.CONTACTS_UNAVAILABLE, report.blocker)
        assertNull(report.plan)
        assertEquals(listOf(zeroCounts(Blocker.CONTACTS_UNAVAILABLE)), reportDao.saved)
        assertTrue(keys.closed)
    }

    @Test
    fun `a network error on the first page maps the same way`() = runTest {
        api.failure = ProtonError.Network()

        assertEquals(Blocker.PROTON_UNAVAILABLE, engine().dryRun(account).blocker)
        // The network drops after the run asked for it.
        api.onExport = { online = false }
        assertEquals(Blocker.OFFLINE, engine().dryRun(account).blocker)
        assertEquals(listOf("C3 0", "C3 0"), calls, "both sent their request")
    }

    @Test
    fun `keys whose unlock throws leave zeroed passphrases and no report`() = runTest {
        keys.failure = IllegalStateException()

        val outcome = runCatching { engine().dryRun(account) }.exceptionOrNull()

        assertTrue(outcome is IllegalStateException, "$outcome")
        assertTrue(store.loaded.isNotEmpty())
        assertTrue(store.loaded.all { creds -> creds.passphrases.values.all { bytes -> bytes.all { it == 0.toByte() } } })
        assertTrue(reportDao.saved.isEmpty())
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `a refresh during a dry run saves the new tokens`() = runTest {
        // The client's token store is the account's persisted one: a refresh writes it. The fake API calls the store as
        // the client would; `:proton`'s ProtonRefreshTest covers the client's refresh itself.
        api.onExport = { tokens -> tokens.replace(Tokens("uid-1", "access-2", "refresh-2")) }

        engine().dryRun(account)

        assertEquals(listOf(Triple(AccountId("user-id-1"), "access-2", "refresh-2")), store.updated.map { (account, tokens) -> Triple(account, tokens.access, tokens.refresh) })
    }

    @Test
    fun `a rejected session is deleted and reported as expired`() = runTest {
        api.onExport = { tokens -> tokens.onSessionRejected("uid-1"); throw ProtonError.SessionRejected() }

        val report = engine().dryRun(account)

        assertEquals(Blocker.SESSION_EXPIRED, report.blocker)
        assertEquals(listOf(AccountId("user-id-1") to "uid-1"), store.sessionsDeleted)
        api.onExport = {}
        assertEquals(Blocker.SIGNED_OUT, engine().dryRun(account).blocker, "the next run")
    }

    @Test
    fun `another account's credentials are never used`() = runTest {
        val other = PhoneAccount(AccountId("user-id-2"), "other@example.test")

        assertEquals(Blocker.SIGNED_OUT, engine().dryRun(other).blocker)
    }

    @Test
    fun `cancelling stops before the next contact`() = runTest {
        api.pages += List(5) { contact(it) }
        val run = launch { engine().dryRun(account) }
        crypto.onDecrypt = { n -> if (n == 2) run.cancel() }

        run.join()

        assertTrue(run.isCancelled)
        assertEquals(2, crypto.decrypted, "nothing decrypted after the cancel")
        assertTrue(keys.closed)
        assertTrue(reportDao.saved.isEmpty())
    }

    @Test
    fun `the stored report carries every count`() {
        // A plan whose categories each have a different count, so no two columns can be swapped unseen; edited before
        // keys counts as a conflict (11 + 7), unmapped rows as can't be synced (14 + 8), and written before keys and
        // settled edits (13, 15) aren't counted.
        assertEquals(PlanCategory.EDITED_BEFORE_KEYS, PlanCategory.entries[10])
        assertEquals(PlanCategory.WRITTEN_BEFORE_KEYS, PlanCategory.entries[11])
        assertEquals(PlanCategory.SETTLED, PlanCategory.entries[12])
        assertEquals(PlanCategory.UNMAPPED_ROWS, PlanCategory.entries[13])
        assertEquals(PlanCategory.SETTLED_REMOTE_CHANGED, PlanCategory.entries[14])
        val items = PlanCategory.entries.flatMapIndexed { index, category -> List(index + 1) { PlanItem(category, null, null, null, null, false) } }
        val report = RunReport(ReportKind.DRY_RUN, 42, Blocker.INVENTORY_INCOMPLETE, SyncPlan(items, emptyList(), bulkGuardRemote = true, bulkGuardPhone = false))

        assertEquals(
            LastReportEntity(
                "user-id-1", ReportKind.DRY_RUN, 42, ready = false, blocker = Blocker.INVENTORY_INCOMPLETE,
                newFromProton = 1, changedOnProton = 2, deletedOnProton = 3, newOnPhone = 4, changedOnPhone = 5, deletedOnPhone = 6,
                conflicts = 18, bulkGuardRemote = true, bulkGuardPhone = false, unreadable = 22, duplicateRows = 9, missingRows = 10,
            ),
            lastReport(account, report),
        )
        val phone = RunReport(ReportKind.DRY_RUN, 42, null, SyncPlan(emptyList(), emptyList(), bulkGuardRemote = false, bulkGuardPhone = true))
        assertEquals(listOf(false, true), lastReport(account, phone).let { listOf(it.bulkGuardRemote, it.bulkGuardPhone) })
    }

    @Test
    fun `a cancellation propagates, stores nothing, and closes the keys`() = runTest {
        api.cancel = true

        val outcome = runCatching { engine().dryRun(account) }.exceptionOrNull()

        assertTrue(outcome is CancellationException, "$outcome")
        assertTrue(keys.closed)
        assertTrue(reportDao.saved.isEmpty())
    }

    @Test
    fun `an unexpected error propagates and closes the keys`() = runTest {
        api.pages += listOf(contact(1))
        mapper.failure = IllegalStateException()

        val outcome = runCatching { engine().dryRun(account) }.exceptionOrNull()

        assertTrue(outcome is IllegalStateException, "$outcome")
        assertTrue(keys.closed)
        assertTrue(reportDao.saved.isEmpty())
    }

    @Test
    fun `the report's counts come from the plan`() = runTest {
        api.pages += listOf(contact(1))
        phone.rows += DeviceContact(30, null, dirty = true, deleted = false, version = 1, displayName = "Made here")

        val report = engine().dryRun(account)

        assertEquals(ReportCounts(1, 0, 0, 1, 0, 0, 0, false, false, 0, 0, 0), report.counts())
    }
}
