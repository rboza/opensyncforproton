package dev.rboza.android.opensyncforproton.sync.engine

import dev.rboza.android.opensyncforproton.crypto.RejectReason
import dev.rboza.android.opensyncforproton.crypto.CryptoResult
import dev.rboza.android.opensyncforproton.account.CredentialStoreException
import dev.rboza.android.opensyncforproton.account.Credentials
import dev.rboza.android.opensyncforproton.account.PersistedTokenStore
import dev.rboza.android.opensyncforproton.proton.ContactEmailEventDto
import dev.rboza.android.opensyncforproton.proton.ContactEventDto
import dev.rboza.android.opensyncforproton.proton.EventId
import dev.rboza.android.opensyncforproton.proton.EventPageDto
import dev.rboza.android.opensyncforproton.proton.EventsReply
import dev.rboza.android.opensyncforproton.proton.ProtonError
import dev.rboza.android.opensyncforproton.proton.Tokens
import dev.rboza.android.opensyncforproton.proton.UserDto
import dev.rboza.android.opensyncforproton.proton.UserKeyDto
import dev.rboza.android.opensyncforproton.sync.db.AccountRecordEntity
import dev.rboza.android.opensyncforproton.sync.db.Blocker
import dev.rboza.android.opensyncforproton.sync.db.EventCursorEntity
import dev.rboza.android.opensyncforproton.sync.db.LoginState
import dev.rboza.android.opensyncforproton.sync.db.ReportKind
import dev.rboza.android.opensyncforproton.sync.db.ReportTrigger
import dev.rboza.android.opensyncforproton.sync.db.SettingsEntity
import dev.rboza.android.opensyncforproton.sync.db.SyncMode
import dev.rboza.android.opensyncforproton.sync.provider.PhoneAccount
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// The event loop: docs/sync.md § Event loop. Every value is made up for the test.
class EventLoopTest : EngineHarness() {
    private val now = 1_767_225_600_000
    private val accounts = SyncCoordinatorTest.FakeAccounts().apply {
        records["user-id-1"] = AccountRecordEntity("user-id-1", "test-only@example.test", LoginState.ACTIVE)
        settings["user-id-1"] = SettingsEntity("user-id-1", SyncMode.ON, intervalMinutes = 60)
    }
    private val work = FakeWork()

    /** U1's account, with a new primary key, the old one demoted, and an inactive one (Active and Primary as sent). */
    private val fetched = UserDto(
        "user-id-1", "test-only", "test-only@example.test", "Test Only",
        listOf(
            UserKeyDto("key-2", "armored-2", active = 1, primary = 1, fingerprint = "fingerprint-2", version = 3),
            UserKeyDto("key-1", "armored-1", active = 1, primary = 0, fingerprint = "fingerprint-1", version = 3),
            UserKeyDto("key-0", "armored-0", active = 0, primary = 2, fingerprint = "fingerprint-0", version = 3),
        ),
    )
    private val fetchedKeys = listOf(
        listOf("key-2", "armored-2", true, true),
        listOf("key-1", "armored-1", true, false),
        listOf("key-0", "armored-0", false, false),
    )

    init {
        api.userAnswer = { fetched }
    }

    /** E1 or the pages, then the keys fetched again and stored, then the full sync. */
    private val keysThenSync = listOf("U1", "keys saved", "sync")

    private fun loop() = EventLoop(work, cursorDao, accounts, store, keys, newClient = { tokens -> store_given = tokens; api }, isOnline = { online }, now = { now })

    /** A stored cursor, and a full sync [minutesAgo] (none when null). */
    private fun stored(cursor: String?, minutesAgo: Long? = 1) {
        cursorDao.rows["user-id-1"] = EventCursorEntity("user-id-1", cursor, minutesAgo?.let { now - it * 60_000 })
    }

    private fun page(
        next: String,
        more: Boolean = false,
        refresh: Int = 0,
        contacts: Int = 0,
        contactEmails: Int = 0,
        user: Boolean = false,
        addresses: Boolean = false,
    ) = EventsReply.Page(
        EventPageDto(
            EventId(next), more, refresh,
            List(contacts) { ContactEventDto("proton-id-$it", action = 2, contact = null) },
            List(contactEmails) { ContactEmailEventDto("email-id-$it", action = 1) },
            userChanged = user, addressesChanged = addresses,
        ),
    )

    private val cursor get() = cursorDao.rows["user-id-1"]?.eventId

    @Test
    fun `no cursor reads E1, runs a full sync, then stores that cursor`() = runTest {
        for (row in listOf(null, EventCursorEntity("user-id-1", null, now - 1))) {
            cursorDao.rows.clear()
            row?.let { cursorDao.rows["user-id-1"] = it }
            calls.clear()
            api.latest += EventId("event-1")

            val report = loop().run(account)

            assertEquals(listOf("E1") + keysThenSync, calls, "$row: the keys may have changed before E1's cursor")
            assertEquals(listOf<String?>(null), work.cursorsSeen, "never stored before the full sync ends")
            assertEquals("event-1", cursor)
            assertSame(work.returned.last(), report)
            work.cursorsSeen.clear()
        }
        assertTrue(store_given is PersistedTokenStore, "the account's own tokens")
    }

    @Test
    fun `E1's cursor isn't stored after a full sync with a retryable blocker, and is after a lasting one`() = runTest {
        // A lasting blocker moves the cursor on, since the next full sync reads everything; a retryable one keeps it,
        // so the job's retry starts again from E1.
        for ((blocker, stored) in listOf(
            Blocker.OFFLINE to null, Blocker.PROTON_UNAVAILABLE to null, Blocker.CONTACTS_UNAVAILABLE to null,
            Blocker.INVENTORY_INCOMPLETE to "event-1", Blocker.PERMISSION_MISSING to "event-1", Blocker.SESSION_EXPIRED to "event-1",
        )) {
            cursorDao.rows.clear()
            calls.clear()
            api.latest += EventId("event-1")
            work.blocker = blocker

            val report = loop().run(account)

            assertEquals(listOf("E1") + keysThenSync, calls, "$blocker")
            assertEquals(stored, cursor, "$blocker")
            assertSame(work.returned.last(), report)
        }
    }

    @Test
    fun `pages are read while More, and one full sync acts for them all`() = runTest {
        stored("event-0")
        api.feed["event-0"] = page("event-1", more = true, contacts = 1)
        api.feed["event-1"] = page("event-2")

        val report = loop().run(account)

        assertEquals(listOf("E2 event-0", "E2 event-1", "sync"), calls, "an earlier page's change counts")
        assertEquals(listOf<String?>("event-0"), work.cursorsSeen)
        assertEquals("event-2", cursor)
        assertSame(work.returned.last(), report)
    }

    @Test
    fun `after 50 pages with More still set, a full sync stores the 50th page's cursor`() = runTest {
        for (keys in listOf(false, true)) {
            stored("event-0")
            calls.clear()
            for (n in 0..60) api.feed["event-$n"] = page("event-${n + 1}", more = true, user = keys && n == 3)

            loop().run(account)

            assertEquals((0 until 50).map { "E2 event-$it" } + if (keys) keysThenSync else listOf("sync"), calls, "keys $keys")
            assertEquals("event-50", cursor, "the next poll goes on from there")
        }
    }

    @Test
    fun `a quiet feed runs an upload-only sync and moves the cursor on`() = runTest {
        stored("event-0")
        api.feed["event-0"] = page("event-1", more = true)
        api.feed["event-1"] = page("event-2")

        val report = loop().run(account)

        assertEquals(listOf("E2 event-0", "E2 event-1", "upload"), calls)
        assertEquals("event-2", cursor)
        assertSame(work.returned.last(), report)
    }

    @Test
    fun `each full sync says what started it, and a quiet check's upload-only sync that it checked`() = runTest {
        val cases = listOf<Pair<() -> Unit, Pair<String, ReportTrigger>>>(
            { stored(null); api.latest += EventId("event-1") } to ("sync" to ReportTrigger.SCHEDULED),
            { stored("event-0"); api.feed["event-0"] = EventsReply.CursorRejected; api.latest += EventId("event-1") } to ("sync" to ReportTrigger.SCHEDULED),
            { stored("event-0"); api.feed["event-0"] = page("event-1", contacts = 1) } to ("sync" to ReportTrigger.PROTON_CHANGES),
            { stored("event-0"); api.feed["event-0"] = page("event-1", user = true) } to ("sync" to ReportTrigger.PROTON_CHANGES),
            { stored("event-0", 60); api.feed["event-0"] = page("event-1", contacts = 1) } to ("sync" to ReportTrigger.PROTON_CHANGES),
            { stored("event-0", 60); api.feed["event-0"] = page("event-1") } to ("sync" to ReportTrigger.SCHEDULED),
            { stored("event-0", 60); api.feed["event-0"] = ProtonError.Network() } to ("sync" to ReportTrigger.SCHEDULED),
            { stored("event-0"); api.feed["event-0"] = page("event-1") } to ("upload" to ReportTrigger.CHECK),
        )
        for ((n, case) in cases.withIndex()) {
            calls.clear()
            work.triggers.clear()
            case.first()

            loop().run(account)

            assertEquals(case.second.first, calls.last(), "$n")
            assertEquals(listOf(case.second.second), work.triggers, "$n")
        }
    }

    @Test
    fun `a refresh, a contact change, or a key change runs a full sync`() = runTest {
        // A key change, or a refresh, which may stand for one, fetches the keys again first.
        for ((trigger, keys) in listOf(
            page("event-1", refresh = 1) to true,
            page("event-1", refresh = 255) to true,
            page("event-1", refresh = 2) to true,
            page("event-1", contacts = 1) to false,
            page("event-1", contactEmails = 1) to false,
            page("event-1", user = true) to true,
            page("event-1", addresses = true) to true,
        )) {
            stored("event-0")
            calls.clear()
            api.feed["event-0"] = trigger

            loop().run(account)

            assertEquals(listOf("E2 event-0") + if (keys) keysThenSync else listOf("sync"), calls, "$trigger")
            assertEquals("event-1", cursor)
        }
    }

    @Test
    fun `fetched keys are stored for the session that fetched them, before the full sync`() = runTest {
        // U1, as at login; the store carries the passphrases over by key ID, so a new primary without one makes the
        // full sync's keys not unlock.
        stored("event-0")
        api.feed["event-0"] = page("event-1", more = true, user = true)
        api.feed["event-1"] = page("event-2")

        loop().run(account)

        assertEquals(listOf("E2 event-0", "E2 event-1") + keysThenSync, calls, "an earlier page's key change counts")
        assertEquals(listOf("uid-1" to fetchedKeys), store.keysUpdated)
        assertEquals("event-2", cursor)
    }

    @Test
    fun `a rejected cursor fetches the keys again after E1`() = runTest {
        stored("event-0")
        api.feed["event-0"] = EventsReply.CursorRejected
        api.latest += EventId("event-9")

        loop().run(account)

        assertEquals(listOf("E2 event-0", "E1") + keysThenSync, calls)
        assertEquals("event-9", cursor)
    }

    @Test
    fun `keys that can't be fetched or stored keep the cursor, and a full sync runs only when due`() = runTest {
        // As a failed feed, so a key change outlives a lasting blocker, which would move the cursor.
        val failures = listOf<Pair<() -> Unit, Blocker>>(
            { api.userAnswer = { ProtonError.Network() } } to Blocker.PROTON_UNAVAILABLE,
            { api.userAnswer = { ProtonError.Api(422, 2001) } } to Blocker.INVENTORY_INCOMPLETE,
            { api.userAnswer = { ProtonError.SessionRejected() } } to Blocker.SESSION_EXPIRED,
            // As at login, another user's keys are a bad answer.
            { api.userAnswer = { UserDto("user-id-2", "other", "other@example.test", "Other", fetched.keys) } } to Blocker.PROTON_UNAVAILABLE,
            // A login meanwhile, or a write that failed: the account is still signed in, so the job retries.
            { store.keysFailure = CredentialStoreException() } to Blocker.PROTON_UNAVAILABLE,
        )
        for ((n, failure) in failures.withIndex()) {
            for (first in listOf(true, false)) {
                for (due in listOf(false, true)) {
                    api.userAnswer = { fetched }
                    store.keysFailure = null
                    failure.first()
                    calls.clear()
                    store.keysUpdated.clear()
                    if (first) {
                        stored(null, if (due) null else 1)
                        api.latest += EventId("event-9")
                    } else {
                        stored("event-0", if (due) 60 else 1)
                        api.feed["event-0"] = page("event-1", user = true)
                    }
                    work.blocker = Blocker.INVENTORY_INCOMPLETE

                    val report = loop().run(account)

                    val read = listOf(if (first) "E1" else "E2 event-0", "U1") + if (n == 4) listOf("keys saved") else emptyList()
                    assertEquals(read + if (due) "sync" else "failed check", calls, "$n first $first due $due")
                    assertEquals(if (first) null else "event-0", cursor, "$n first $first due $due")
                    assertEquals(emptyList<Any>(), store.keysUpdated)
                    assertSame(work.returned.last(), report)
                    if (!due) assertEquals(failure.second, report.blocker, "$n")
                }
            }
        }
    }

    @Test
    fun `keys are stored for the session read before U1, so a login meanwhile keeps its own`() = runTest {
        stored("event-0")
        api.feed["event-0"] = page("event-1", user = true)
        val login = store.credentials!!
        api.userAnswer = {
            store.credentials = {
                val old = login()
                Credentials(Tokens("uid-2", "access-2", "refresh-2"), old.passwordMode, old.keySalts, old.keys, old.passphrases)
            }
            fetched
        }

        loop().run(account)

        assertEquals(listOf("uid-1"), store.keysUpdated.map { it.first }, "the store refuses it for uid-2's envelope")
    }

    @Test
    fun `without a session the keys aren't fetched`() = runTest {
        for (due in listOf(false, true)) {
            stored("event-0", if (due) 60 else 1)
            calls.clear()
            api.feed["event-0"] = page("event-1", user = true)
            val login = store.credentials
            api.onEvents = { store.credentials = null }

            val report = loop().run(account)

            assertEquals(listOf("E2 event-0", if (due) "sync" else "failed check"), calls, "due $due")
            assertSame(work.returned.last(), report)
            if (!due) assertEquals(Blocker.SIGNED_OUT, report.blocker)
            assertEquals("event-0", cursor)
            store.credentials = login
        }
    }

    @Test
    fun `once the keys are stored, a lasting blocker moves the cursor on`() = runTest {
        stored("event-0")
        api.feed["event-0"] = page("event-1", user = true)
        work.blocker = Blocker.INVENTORY_INCOMPLETE

        loop().run(account)

        assertEquals(listOf("E2 event-0") + keysThenSync, calls)
        assertEquals("event-1", cursor)
    }

    @Test
    fun `the cursor stays when the pages' work ends with a retryable blocker, and the next run reads the same pages`() = runTest {
        for (quiet in listOf(true, false)) {
            stored("event-0")
            calls.clear()
            api.feed["event-0"] = if (quiet) page("event-1") else page("event-1", contacts = 1)
            work.blocker = Blocker.PROTON_UNAVAILABLE

            val report = loop().run(account)

            assertEquals("event-0", cursor, "quiet $quiet")
            assertEquals(Blocker.PROTON_UNAVAILABLE, report.blocker)
            work.blocker = null
            loop().run(account)
            assertEquals(listOf("E2 event-0", if (quiet) "upload" else "sync").let { it + it }, calls)
            assertEquals("event-1", cursor)
        }
    }

    @Test
    fun `the cursor moves on when the pages' work ends with a lasting blocker`() = runTest {
        for (quiet in listOf(true, false)) {
            stored("event-0")
            calls.clear()
            api.feed["event-0"] = if (quiet) page("event-1") else page("event-1", contacts = 1)
            work.blocker = Blocker.INVENTORY_INCOMPLETE

            val report = loop().run(account)

            assertEquals(listOf("E2 event-0", if (quiet) "upload" else "sync"), calls)
            assertEquals(Blocker.INVENTORY_INCOMPLETE, report.blocker)
            assertEquals("event-1", cursor, "quiet $quiet: the next full sync, once due, reads everything")
        }
    }

    @Test
    fun `a full sync is due when none is recorded, or the full sync's interval since the last one has passed`() = runTest {
        // The full sync's interval (null: Manual on, with an hour kept), minutes since the last full sync (null: none
        // recorded), and the run. The check's period plays no part.
        for ((interval, minutesAgo, expected) in listOf(
            Triple(60, null, "sync"),
            Triple(60, 60L, "sync"),
            Triple(60, 59L, "upload"),
            Triple(15, 15L, "sync"),
            Triple(1440, 1439L, "upload"),
            Triple(null, 100_000L, "upload"),
            Triple(null, null, "sync"),
            // A clock moved back since: due.
            Triple(60, -1L, "sync"),
            Triple(null, -1L, "upload"),
        )) {
            accounts.settings["user-id-1"] =
                SettingsEntity("user-id-1", SyncMode.ON, intervalMinutes = interval ?: 60, checkMinutes = 15, manual = interval == null)
            stored("event-0", minutesAgo)
            calls.clear()
            api.feed["event-0"] = page("event-1")

            loop().run(account)

            assertEquals(listOf("E2 event-0", expected), calls, "interval $interval, last full sync $minutesAgo minutes ago")
            assertEquals("event-1", cursor)
        }
    }

    @Test
    fun `a rejected cursor is dropped, and the same run starts again from no cursor`() = runTest {
        for (later in listOf(false, true)) {
            stored("event-0")
            calls.clear()
            work.cursorsSeen.clear()
            if (later) {
                api.feed["event-0"] = page("event-1", more = true, contacts = 1)
                api.feed["event-1"] = EventsReply.CursorRejected
            } else {
                api.feed["event-0"] = EventsReply.CursorRejected
            }
            api.latest += EventId("event-9")

            loop().run(account)

            val read = if (later) listOf("E2 event-0", "E2 event-1") else listOf("E2 event-0")
            assertEquals(read + listOf("E1") + keysThenSync, calls, "later $later")
            assertEquals(listOf<String?>(null), work.cursorsSeen, "dropped before E1")
            assertEquals("event-9", cursor)
        }
    }

    @Test
    fun `an E1 that fails after a rejection leaves no cursor`() = runTest {
        stored("event-0")
        api.feed["event-0"] = EventsReply.CursorRejected
        api.latest += ProtonError.Network()

        val report = loop().run(account)

        assertEquals(listOf("E2 event-0", "E1", "failed check"), calls)
        assertNull(cursor)
        assertEquals(Blocker.PROTON_UNAVAILABLE, report.blocker)
    }

    @Test
    fun `a feed that fails ends the run with its blocker as a failed check when no full sync is due, and nothing else runs`() = runTest {
        for ((failure, blocker) in listOf(
            ProtonError.Network() to Blocker.PROTON_UNAVAILABLE,
            ProtonError.Api(503, null) to Blocker.PROTON_UNAVAILABLE,
            ProtonError.RateLimited(null) to Blocker.PROTON_UNAVAILABLE,
            ProtonError.SessionRejected() to Blocker.SESSION_EXPIRED,
            ProtonError.NoSession() to Blocker.SESSION_EXPIRED,
        )) {
            for (first in listOf(true, false)) {
                calls.clear()
                if (first) {
                    stored(null)
                    api.latest += failure
                } else {
                    stored("event-0")
                    api.feed["event-0"] = failure
                }

                val report = loop().run(account)

                // The engine stores its report: every line kept, its time and blocker its own.
                assertEquals(listOf(if (first) "E1" else "E2 event-0", "failed check"), calls, "$failure")
                assertEquals(blocker, report.blocker, "$failure")
                assertSame(work.returned.last(), report)
                assertEquals(if (first) null else "event-0", cursor)
            }
        }
    }

    @Test
    fun `a feed that fails still runs the full sync that's due, leaving the cursor`() = runTest {
        // The interval's full syncs go on, and their report and notifications show a session or captcha problem.
        for ((first, minutesAgo) in listOf(true to null, false to 60L, true to 61L, false to null)) {
            calls.clear()
            if (first) {
                stored(null, minutesAgo)
                api.latest += ProtonError.SessionRejected()
            } else {
                stored("event-0", minutesAgo)
                api.feed["event-0"] = ProtonError.Api(422, 2001)
            }
            work.blocker = Blocker.SESSION_EXPIRED

            val report = loop().run(account)

            assertEquals(listOf(if (first) "E1" else "E2 event-0", "sync"), calls, "$first $minutesAgo")
            assertSame(work.returned.last(), report)
            assertEquals(if (first) null else "event-0", cursor)
        }
    }

    @Test
    fun `while a login is needed the run stops first as a failed check, sending nothing, due or not`() = runTest {
        // Keys that don't unlock, or a login gone, pause sync until the user logs in.
        for (online in listOf(true, false)) {
            this@EventLoopTest.online = online
            for ((setUp, blocker) in listOf<Pair<() -> Unit, Blocker>>(
                { keys.result = CryptoResult.Rejected(RejectReason.WRONG_PASSPHRASE) } to Blocker.KEYS_DONT_UNLOCK,
                { store.credentials = null } to Blocker.SIGNED_OUT,
            )) {
                val login = store.credentials
                calls.clear()
                stored("event-0", 60)
                api.feed["event-0"] = page("event-1", contacts = 1)
                setUp()

                val report = loop().run(account)

                assertEquals(listOf("failed check"), calls, "online $online")
                assertEquals(blocker, report.blocker)
                assertEquals("event-0", cursor)
                keys.result = null
                store.credentials = login
            }
        }
    }

    @Test
    fun `a check unlocks the keys first and closes them`() = runTest {
        stored("event-0")
        api.feed["event-0"] = page("event-1")

        loop().run(account)

        assertEquals(1 to true, keys.unlocks to keys.closed)
    }

    @Test
    fun `with no network the run is offline and sends nothing`() = runTest {
        online = false
        stored("event-0")
        api.feed["event-0"] = page("event-1")

        val report = loop().run(account)

        assertEquals(listOf("failed check"), calls, "nothing sent")
        assertEquals(Blocker.OFFLINE, report.blocker)
        assertEquals("event-0", cursor)
    }

    @Test
    fun `a network error with the device offline is offline`() = runTest {
        stored("event-0")
        api.feed["event-0"] = ProtonError.Network()
        api.onEvents = { online = false }

        assertEquals(Blocker.OFFLINE, loop().run(account).blocker)
    }

    /** The engine's sync and upload, recorded with the cursor each saw; each returns a report with [blocker]. */
    inner class FakeWork : SyncEngine {
        var blocker: Blocker? = null
        val returned = mutableListOf<RunReport>()
        val cursorsSeen = mutableListOf<String?>()

        override suspend fun dryRun(account: PhoneAccount): RunReport = error("the event loop runs no dry run")

        /** What each run's report names as having started it, in turn. */
        val triggers = mutableListOf<ReportTrigger>()

        override suspend fun sync(account: PhoneAccount, confirmedPhoneDeletions: Int?, confirmedRemoteDeletions: Int?, trigger: ReportTrigger): RunReport {
            assertNull(confirmedPhoneDeletions)
            assertNull(confirmedRemoteDeletions)
            return run("sync", trigger)
        }

        override suspend fun upload(account: PhoneAccount, trigger: ReportTrigger) = run("upload", trigger)

        /** A failed check's report, stored by the engine with its own blocker. */
        override suspend fun failedCheck(account: PhoneAccount, blocker: Blocker): RunReport {
            calls += "failed check"
            triggers += ReportTrigger.CHECK
            return RunReport(ReportKind.SYNC, now, blocker, plan = null).also { returned += it }
        }

        private fun run(name: String, trigger: ReportTrigger): RunReport {
            calls += name
            triggers += trigger
            cursorsSeen += cursor
            return RunReport(ReportKind.SYNC, now, blocker, plan = null).also { returned += it }
        }
    }
}
