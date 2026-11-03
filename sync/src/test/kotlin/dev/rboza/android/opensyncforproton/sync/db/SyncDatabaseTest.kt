package dev.rboza.android.opensyncforproton.sync.db

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** The v4 schema (docs/architecture.md § Storage) and its DAOs. */
@RunWith(RobolectricTestRunner::class)
class SyncDatabaseTest {
    private lateinit var db: SyncDatabase

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), SyncDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun close() = db.close()

    private fun rows(sql: String): List<List<String?>> = db.openHelper.readableDatabase.query(sql).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add((0 until cursor.columnCount).map { if (cursor.isNull(it)) null else cursor.getString(it) })
        }
    }

    private fun exec(sql: String) = db.openHelper.writableDatabase.execSQL(sql)

    /** The app's tables: everything but SQLite's and Room's own. */
    private fun tables() = rows("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name").map { it[0]!! }
        .filterNot { it.startsWith("sqlite_") || it == "room_master_table" || it == "android_metadata" }

    @Test
    fun `v4 has exactly the eight tables`() {
        assertEquals(
            listOf(
                "account_record", "conflict_hold", "contact_map", "event_cursor", "import_record", "last_report",
                "pending_contact_create", "settings",
            ),
            tables(),
        )
    }

    /** Per column: name, type, NOT NULL, default, position in the primary key. Nothing else exists. */
    @Test
    fun `every column, and no other`() {
        val expected = mapOf(
            "account_record" to listOf(
                listOf("account_id", "TEXT", "1", null, "1"),
                listOf("android_account_name", "TEXT", "1", null, "0"),
                listOf("login_state", "TEXT", "1", null, "0"),
            ),
            "settings" to listOf(
                listOf("account_id", "TEXT", "1", null, "1"),
                listOf("sync_mode", "TEXT", "1", "'DRY_RUN'", "0"),
                listOf("interval_minutes", "INTEGER", "1", "60", "0"),
                listOf("check_minutes", "INTEGER", "0", "15", "0"),
                listOf("manual", "INTEGER", "1", "0", "0"),
                listOf("upgrade_notice", "INTEGER", "1", "0", "0"),
            ),
            "last_report" to listOf(
                listOf("account_id", "TEXT", "1", null, "1"),
                listOf("kind", "TEXT", "1", null, "0"),
                listOf("finished_at", "INTEGER", "1", null, "0"),
                listOf("ready", "INTEGER", "1", null, "0"),
                listOf("blocker", "TEXT", "0", null, "0"),
                listOf("new_from_proton", "INTEGER", "1", null, "0"),
                listOf("changed_on_proton", "INTEGER", "1", null, "0"),
                listOf("deleted_on_proton", "INTEGER", "1", null, "0"),
                listOf("new_on_phone", "INTEGER", "1", null, "0"),
                listOf("changed_on_phone", "INTEGER", "1", null, "0"),
                listOf("deleted_on_phone", "INTEGER", "1", null, "0"),
                listOf("conflicts", "INTEGER", "1", null, "0"),
                listOf("bulk_guard_remote", "INTEGER", "1", "0", "0"),
                listOf("bulk_guard_phone", "INTEGER", "1", "0", "0"),
                listOf("unreadable", "INTEGER", "1", null, "0"),
                listOf("duplicate_rows", "INTEGER", "1", null, "0"),
                listOf("missing_rows", "INTEGER", "1", null, "0"),
                listOf("conflicts_phone", "INTEGER", "1", "0", "0"),
                listOf("unreadable_phone", "INTEGER", "1", "0", "0"),
                listOf("started_by", "TEXT", "0", null, "0"),
                listOf("proton_counted_at", "INTEGER", "0", null, "0"),
                listOf("phone_counted_at", "INTEGER", "0", null, "0"),
            ),
            "contact_map" to listOf(
                listOf("account_id", "TEXT", "1", null, "1"),
                listOf("proton_id", "TEXT", "1", null, "2"),
                listOf("raw_contact_id", "INTEGER", "1", null, "0"),
                listOf("fingerprint", "TEXT", "0", null, "0"),
                listOf("rows_keyed", "INTEGER", "1", "0", "0"),
                listOf("photo_baseline", "TEXT", "0", null, "0"),
                listOf("photo_thumbnail_baseline", "TEXT", "0", null, "0"),
            ),
            "pending_contact_create" to listOf(
                listOf("account_id", "TEXT", "1", null, "1"),
                listOf("raw_contact_id", "INTEGER", "1", null, "2"),
                listOf("uid", "TEXT", "1", null, "0"),
                listOf("version", "INTEGER", "1", null, "0"),
            ),
            "conflict_hold" to listOf(
                listOf("account_id", "TEXT", "1", null, "1"),
                listOf("proton_id", "TEXT", "1", null, "2"),
                listOf("raw_contact_id", "INTEGER", "1", null, "0"),
                listOf("kind", "TEXT", "1", null, "0"),
                listOf("choice", "TEXT", "0", null, "0"),
                listOf("version", "INTEGER", "0", null, "0"),
                listOf("fingerprint", "TEXT", "0", null, "0"),
            ),
            "event_cursor" to listOf(
                listOf("account_id", "TEXT", "1", null, "1"),
                listOf("event_id", "TEXT", "0", null, "0"),
                listOf("last_full_sync_at", "INTEGER", "0", null, "0"),
            ),
            "import_record" to listOf(
                listOf("account_id", "TEXT", "1", null, "1"),
                listOf("original_raw_contact_id", "INTEGER", "1", null, "2"),
                listOf("original_version", "INTEGER", "1", null, "0"),
                listOf("copy_raw_contact_id", "INTEGER", "0", null, "0"),
                listOf("photo_copied", "INTEGER", "1", "0", "0"),
            ),
        )
        assertEquals(tables(), expected.keys.sorted())
        for ((table, columns) in expected) {
            // PRAGMA table_info: cid, name, type, notnull, dflt_value, pk.
            assertEquals(table, columns, rows("PRAGMA table_info($table)").map { it.drop(1) })
        }
    }

    @Test
    fun `a Proton contact has one map per account, and a stale map may name a reused raw ID`() {
        exec("INSERT INTO contact_map (account_id, proton_id, raw_contact_id) VALUES ('a1', 'p1', 7)")
        exec("INSERT INTO contact_map (account_id, proton_id, raw_contact_id) VALUES ('a2', 'p1', 7)")
        // SOURCE_ID decides pairing; a map whose raw ID Android reused for another contact stays, so its contact is
        // still held as a missing row.
        exec("INSERT INTO contact_map (account_id, proton_id, raw_contact_id) VALUES ('a1', 'p2', 7)")

        assertThrows(SQLiteConstraintException::class.java) {
            exec("INSERT INTO contact_map (account_id, proton_id, raw_contact_id) VALUES ('a1', 'p1', 8)")
        }
    }

    @Test
    fun `a UID is pending once per account, and a phone row has one pending create`() {
        exec("INSERT INTO pending_contact_create VALUES ('a1', 7, 'urn:uuid:u1', 3)")
        exec("INSERT INTO pending_contact_create VALUES ('a2', 7, 'urn:uuid:u1', 3)")
        exec("INSERT INTO pending_contact_create VALUES ('a1', 8, 'urn:uuid:u2', 3)")

        assertThrows(SQLiteConstraintException::class.java) {
            exec("INSERT INTO pending_contact_create VALUES ('a1', 9, 'urn:uuid:u1', 3)")
        }
        assertThrows(SQLiteConstraintException::class.java) {
            exec("INSERT INTO pending_contact_create VALUES ('a1', 7, 'urn:uuid:u3', 4)")
        }
    }

    @Test
    fun `pending creates are saved, read by account, and deleted by row`() = runTest {
        // Saved before C4, deleted once bound or dropped.
        val dao = db.pendingCreateDao()
        dao.save(PendingContactCreateEntity("account-1", 10, "urn:uuid:test-1", version = 3))
        dao.save(PendingContactCreateEntity("account-1", 20, "urn:uuid:test-2", version = 4))
        dao.save(PendingContactCreateEntity("account-2", 10, "urn:uuid:test-3", version = 5))

        assertEquals(
            listOf(PendingContactCreateEntity("account-1", 10, "urn:uuid:test-1", 3), PendingContactCreateEntity("account-1", 20, "urn:uuid:test-2", 4)),
            dao.records("account-1").sortedBy { it.rawContactId },
        )
        dao.delete("account-1", 10)
        assertEquals(listOf(20L), dao.records("account-1").map { it.rawContactId })
        assertEquals("another account's row is left", listOf(10L), dao.records("account-2").map { it.rawContactId })
        // A record is never replaced: its UID is the one sent, and stays until the create is settled.
        val refused = runCatching { dao.save(PendingContactCreateEntity("account-1", 20, "urn:uuid:test-9", 6)) }.exceptionOrNull()
        assertTrue("$refused", refused is SQLiteConstraintException)
        assertEquals(listOf("urn:uuid:test-2"), dao.records("account-1").map { it.uid })
    }

    @Test
    fun `a map's new columns hold their defaults`() = runTest {
        exec("INSERT INTO contact_map (account_id, proton_id, raw_contact_id) VALUES ('a1', 'p1', 7)")
        exec("INSERT INTO settings (account_id) VALUES ('a1')")

        assertEquals(ContactMapEntity("a1", "p1", 7, null, rowsKeyed = false, photoBaseline = null, photoThumbnailBaseline = null), db.contactMapDao().maps("a1").single())
        assertEquals(SettingsEntity("a1", SyncMode.DRY_RUN, 60, upgradeNotice = false), db.accountStateDao().settings("a1"))
    }

    @Test
    fun `a map keeps whether its rows are keyed and its photo baselines`() = runTest {
        val map = ContactMapEntity("a1", "p1", 7, "contact-fp-v1:00", rowsKeyed = true, photoBaseline = "ab".repeat(32), photoThumbnailBaseline = "cd".repeat(32))
        db.contactMapDao().save(map)

        assertEquals(listOf(map), db.contactMapDao().maps("a1"))
        assertEquals(listOf(listOf("1", "ab".repeat(32), "cd".repeat(32))), rows("SELECT rows_keyed, photo_baseline, photo_thumbnail_baseline FROM contact_map"))
    }

    @Test
    fun `maps are read by account`() = runTest {
        exec("INSERT INTO contact_map VALUES ('a1', 'p1', 7, 'contact-fp-v1:00', 0, NULL, NULL)")
        exec("INSERT INTO contact_map VALUES ('a1', 'p2', 8, NULL, 0, NULL, NULL)")
        exec("INSERT INTO contact_map VALUES ('a2', 'p1', 9, NULL, 0, NULL, NULL)")

        val maps = db.contactMapDao().maps("a1").sortedBy { it.protonId }

        assertEquals(listOf(ContactMapEntity("a1", "p1", 7, "contact-fp-v1:00"), ContactMapEntity("a1", "p2", 8, null)), maps)
    }

    @Test
    fun `a map is deleted by account and Proton ID, leaving the others`() = runTest {
        // A phone deletion finished, or a map with nothing left to pair.
        exec("INSERT INTO contact_map VALUES ('a1', 'p1', 7, NULL, 0, NULL, NULL)")
        exec("INSERT INTO contact_map VALUES ('a1', 'p2', 7, NULL, 0, NULL, NULL)")
        exec("INSERT INTO contact_map VALUES ('a2', 'p1', 7, NULL, 0, NULL, NULL)")

        db.contactMapDao().delete("a1", "p1")
        db.contactMapDao().delete("a1", "p9")

        assertEquals(listOf(listOf("a1", "p2"), listOf("a2", "p1")), rows("SELECT account_id, proton_id FROM contact_map ORDER BY account_id, proton_id"))
    }

    @Test
    fun `a map is saved by Proton ID, and repairs all at once`() = runTest {
        val dao = db.contactMapDao()
        dao.save(ContactMapEntity("a1", "p1", 7, "contact-fp-v1:00"))
        dao.save(ContactMapEntity("a2", "p1", 7, null))

        dao.save(ContactMapEntity("a1", "p1", 8, "contact-fp-v1:11"))
        dao.save(ContactMapEntity("a1", "p2", 6, "contact-fp-v1:22"))
        dao.saveAll(listOf(ContactMapEntity("a1", "p2", 9, null), ContactMapEntity("a1", "p3", 7, null)))

        assertEquals(
            listOf(listOf("a1", "p1", "8", "contact-fp-v1:11"), listOf("a1", "p2", "9", null), listOf("a1", "p3", "7", null), listOf("a2", "p1", "7", null)),
            rows("SELECT account_id, proton_id, raw_contact_id, fingerprint FROM contact_map ORDER BY account_id, proton_id"),
        )
    }

    private fun hold(account: String, id: String, raw: Long, kind: HoldKind, choice: HoldChoice?, version: Long?, fingerprint: String?) =
        ConflictHoldEntity(account, id, raw, kind, choice, version, fingerprint)

    @Test
    fun `a hold found is added, or updated keeping its choice only while its state is the one recorded`() = runTest {
        // A hold records the row's VERSION and the remote's fingerprint (null: gone).
        val dao = db.conflictHoldDao()
        val start = listOf(
            hold("a1", "same", 7, HoldKind.CONFLICT, HoldChoice.USE_PROTON, 3, "fp-1"),
            hold("a1", "version", 7, HoldKind.CONFLICT, HoldChoice.USE_PROTON, 3, "fp-1"),
            hold("a1", "moved", 7, HoldKind.CONFLICT, HoldChoice.USE_PHONE, 3, "fp-1"),
            hold("a1", "gone", 7, HoldKind.CONFLICT, HoldChoice.USE_PHONE, 3, "fp-1"),
            hold("a1", "unseen", 7, HoldKind.CONFLICT, HoldChoice.USE_PHONE, 3, "fp-1"),
            hold("a1", "unseen-version", 7, HoldKind.CONFLICT, HoldChoice.USE_PHONE, 3, "fp-1"),
            hold("a1", "kind", 7, HoldKind.EDITED_BEFORE_KEYS, HoldChoice.USE_PROTON, 3, "fp-1"),
            hold("a1", "row", 7, HoldKind.CONFLICT, HoldChoice.USE_PROTON, 3, "fp-1"),
            hold("a1", "missing", 7, HoldKind.MISSING_ROW, HoldChoice.USE_PROTON, null, "fp-1"),
            hold("a2", "same", 7, HoldKind.CONFLICT, HoldChoice.USE_PROTON, 3, "fp-1"),
        )
        start.forEach {
            exec("INSERT INTO conflict_hold VALUES ('${it.accountId}', '${it.protonId}', ${it.rawContactId}, '${it.kind}', '${it.choice}', ${it.version}, '${it.fingerprint}')")
        }

        dao.found("a1", "same", 7, HoldKind.CONFLICT, 3, "fp-1", remoteSeen = true)
        dao.found("a1", "version", 7, HoldKind.CONFLICT, 4, "fp-1", remoteSeen = true)
        dao.found("a1", "moved", 7, HoldKind.CONFLICT, 3, "fp-2", remoteSeen = true)
        dao.found("a1", "gone", 7, HoldKind.CONFLICT, 3, null, remoteSeen = true)
        dao.found("a1", "unseen", 7, HoldKind.CONFLICT, 3, null, remoteSeen = false)
        dao.found("a1", "unseen-version", 7, HoldKind.CONFLICT, 4, null, remoteSeen = false)
        dao.found("a1", "kind", 7, HoldKind.CONFLICT, 3, "fp-1", remoteSeen = true)
        dao.found("a1", "row", 8, HoldKind.CONFLICT, 3, "fp-1", remoteSeen = true)
        dao.found("a1", "missing", 7, HoldKind.MISSING_ROW, null, "fp-1", remoteSeen = true)
        dao.found("a1", "new", 9, HoldKind.CONFLICT, 5, null, remoteSeen = true)
        dao.found("a1", "new-unseen", 9, HoldKind.CONFLICT, 5, "fp-9", remoteSeen = false)

        assertEquals(
            listOf(
                hold("a1", "gone", 7, HoldKind.CONFLICT, null, 3, null),
                hold("a1", "kind", 7, HoldKind.CONFLICT, null, 3, "fp-1"),
                hold("a1", "missing", 7, HoldKind.MISSING_ROW, HoldChoice.USE_PROTON, null, "fp-1"),
                hold("a1", "moved", 7, HoldKind.CONFLICT, null, 3, "fp-2"),
                hold("a1", "new", 9, HoldKind.CONFLICT, null, 5, null),
                // A new hold whose remote this run didn't see records none.
                hold("a1", "new-unseen", 9, HoldKind.CONFLICT, null, 5, null),
                hold("a1", "row", 8, HoldKind.CONFLICT, null, 3, "fp-1"),
                hold("a1", "same", 7, HoldKind.CONFLICT, HoldChoice.USE_PROTON, 3, "fp-1"),
                // Unseen: the fingerprint it had stays, and so does the choice while the VERSION does.
                hold("a1", "unseen", 7, HoldKind.CONFLICT, HoldChoice.USE_PHONE, 3, "fp-1"),
                hold("a1", "unseen-version", 7, HoldKind.CONFLICT, null, 4, "fp-1"),
                hold("a1", "version", 7, HoldKind.CONFLICT, null, 4, "fp-1"),
            ),
            dao.holds("a1").sortedBy { it.protonId },
        )
        assertEquals("another account's hold is left", listOf(start.last()), dao.holds("a2"))
    }

    @Test
    fun `a choice is saved only while the hold records the state it was made for`() = runTest {
        val dao = db.conflictHoldDao()
        exec("INSERT INTO conflict_hold VALUES ('a1', 'p1', 7, 'CONFLICT', NULL, 3, 'fp-1')")
        exec("INSERT INTO conflict_hold VALUES ('a1', 'p2', 7, 'MISSING_ROW', 'USE_PROTON', NULL, NULL)")
        exec("INSERT INTO conflict_hold VALUES ('a2', 'p1', 7, 'CONFLICT', NULL, 3, 'fp-1')")
        val p1 = hold("a1", "p1", 7, HoldKind.CONFLICT, null, 3, "fp-1")

        for (stale in listOf(p1.copy(kind = HoldKind.EDITED_BEFORE_KEYS), p1.copy(rawContactId = 8), p1.copy(version = 4), p1.copy(fingerprint = "fp-2"), p1.copy(fingerprint = null))) {
            assertEquals("$stale", 0, dao.choose("a1", "p1", stale.kind, stale.rawContactId, stale.version, stale.fingerprint, HoldChoice.USE_PHONE))
        }
        assertEquals(p1, dao.holds("a1").single { it.protonId == "p1" })

        assertEquals(1, dao.choose("a1", "p1", HoldKind.CONFLICT, 7, 3, "fp-1", HoldChoice.USE_PHONE))
        // Nulls compare as equal: a missing row has no VERSION, a gone contact no fingerprint. Skip clears it.
        assertEquals(1, dao.choose("a1", "p2", HoldKind.MISSING_ROW, 7, null, null, null))

        assertEquals(
            listOf(p1.copy(choice = HoldChoice.USE_PHONE), hold("a1", "p2", 7, HoldKind.MISSING_ROW, null, null, null)),
            dao.holds("a1").sortedBy { it.protonId },
        )
        assertEquals(listOf(null), dao.holds("a2").map { it.choice })
    }

    @Test
    fun `holds are deleted one by one`() = runTest {
        exec("INSERT INTO conflict_hold VALUES ('a1', 'p1', 7, 'CONFLICT', NULL, NULL, NULL)")
        exec("INSERT INTO conflict_hold VALUES ('a1', 'p2', 7, 'CONFLICT', NULL, NULL, NULL)")
        exec("INSERT INTO conflict_hold VALUES ('a2', 'p1', 7, 'CONFLICT', NULL, NULL, NULL)")

        db.conflictHoldDao().delete("a1", "p1")
        db.conflictHoldDao().delete("a1", "p9")

        assertEquals(listOf(listOf("a1", "p2"), listOf("a2", "p1")), rows("SELECT account_id, proton_id FROM conflict_hold ORDER BY account_id, proton_id"))
    }

    @Test
    fun `saving a report replaces the account's last one, and only its`() = runTest {
        fun report(account: String, kind: ReportKind, blocker: Blocker?) =
            LastReportEntity(account, kind, 1_767_225_600_000, blocker == null, blocker, 1, 2, 3, 4, 5, 6, 7, blocker == null, blocker != null, 8, 9, 10)
        db.lastReportDao().save(report("a1", ReportKind.SYNC, null))
        db.lastReportDao().save(report("a2", ReportKind.SYNC, null))

        db.lastReportDao().save(report("a1", ReportKind.DRY_RUN, Blocker.APP_VERSION_REJECTED))

        assertEquals(
            listOf(listOf("a1", "DRY_RUN", "0", "APP_VERSION_REJECTED", "0", "1"), listOf("a2", "SYNC", "1", null, "1", "0")),
            rows("SELECT account_id, kind, ready, blocker, bulk_guard_remote, bulk_guard_phone FROM last_report ORDER BY account_id"),
        )
    }

    @Test
    fun `a report keeps what started it, its phone parts, and when each side was counted`() = runTest {
        val partial = LastReportEntity(
            "a1", ReportKind.SYNC, 1_767_225_600_000, true, null, 1, 2, 3, 4, 5, 6, 7, true, false, 8, 9, 10,
            conflictsPhone = 2, unreadablePhone = 3, trigger = ReportTrigger.PHONE_CHANGES, protonCountedAt = 1_000, phoneCountedAt = 1_767_225_600_000,
        )
        val neverCounted = partial.copy(accountId = "a2", trigger = ReportTrigger.CHECK, protonCountedAt = null, phoneCountedAt = null)
        db.lastReportDao().save(partial)
        db.lastReportDao().save(neverCounted)

        assertEquals(partial, db.lastReportDao().report("a1"))
        assertEquals(neverCounted, db.lastReportDao().report("a2"))
    }

    @Test
    fun `an account's last report is read by account`() = runTest {
        val a1 = LastReportEntity("a1", ReportKind.SYNC, 1_767_225_600_000, true, null, 1, 2, 3, 4, 5, 6, 7, true, false, 8, 9, 10)
        val a2 = LastReportEntity("a2", ReportKind.DRY_RUN, 1_767_225_700_000, false, Blocker.OFFLINE, 0, 0, 0, 0, 0, 0, 0, false, true, 0, 0, 0)
        db.lastReportDao().save(a1)
        db.lastReportDao().save(a2)

        assertEquals(a1, db.lastReportDao().report("a1"))
        assertEquals(a2, db.lastReportDao().report("a2"))
        assertNull(db.lastReportDao().report("a3"))
    }

    @Test
    fun `an account's record and settings are read by account`() = runTest {
        exec("INSERT INTO account_record VALUES ('a1', 'name-a1', 'ACTIVE')")
        exec("INSERT INTO account_record VALUES ('a2', 'name-a2', 'SIGNED_OUT')")
        exec("INSERT INTO settings (account_id, sync_mode, interval_minutes, check_minutes, manual) VALUES ('a1', 'ON', 120, NULL, 1)")
        exec("INSERT INTO settings (account_id) VALUES ('a2')")
        val dao = db.accountStateDao()

        assertEquals(AccountRecordEntity("a1", "name-a1", LoginState.ACTIVE), dao.record("a1"))
        assertEquals(AccountRecordEntity("a2", "name-a2", LoginState.SIGNED_OUT), dao.record("a2"))
        assertEquals(SettingsEntity("a1", SyncMode.ON, intervalMinutes = 120, checkMinutes = null, manual = true), dao.settings("a1"))
        // The defaults: a full sync every hour, a check every 15 minutes, Manual off.
        assertEquals(SettingsEntity("a2", SyncMode.DRY_RUN, intervalMinutes = 60, checkMinutes = 15, manual = false), dao.settings("a2"))
        assertNull(dao.record("a3"))
        assertNull(dao.settings("a3"))
        assertEquals(AccountRecordEntity("a2", "name-a2", LoginState.SIGNED_OUT), dao.recordByAndroidName("name-a2"))
        assertNull(dao.recordByAndroidName("name-a3"))
    }

    @Test
    fun `only dismissing clears the upgrade notice, and only that account's`() = runTest {
        // The upgrade notice: shown until the user dismisses it.
        exec("INSERT INTO settings (account_id, sync_mode, interval_minutes, upgrade_notice) VALUES ('a1', 'DRY_RUN', 60, 1)")
        exec("INSERT INTO settings (account_id, sync_mode, interval_minutes, upgrade_notice) VALUES ('a2', 'DRY_RUN', 15, 1)")
        val dao = db.accountDao()

        dao.setSyncMode("a1", SyncMode.ON)
        dao.setInterval("a1", 30)
        assertEquals(SettingsEntity("a1", SyncMode.ON, 30, upgradeNotice = true), db.accountStateDao().settings("a1"))

        dao.dismissUpgradeNotice("a1")
        dao.dismissUpgradeNotice("a3")

        assertEquals(SettingsEntity("a1", SyncMode.ON, 30, upgradeNotice = false), db.accountStateDao().settings("a1"))
        assertEquals(SettingsEntity("a2", SyncMode.DRY_RUN, 15, upgradeNotice = true), db.accountStateDao().settings("a2"))
        assertNull(db.accountStateDao().settings("a3"))
    }

    @Test
    fun `the timing is written apart, and a full sync shorter than the check lowers the check`() = runTest {
        // The check is never longer than the full sync.
        exec("INSERT INTO settings (account_id, interval_minutes, check_minutes) VALUES ('a1', 360, 120)")
        exec("INSERT INTO settings (account_id, interval_minutes, check_minutes) VALUES ('a2', 360, 120)")
        val dao = db.accountDao()
        suspend fun a1() = db.accountStateDao().settings("a1")

        dao.setInterval("a1", 1440)
        assertEquals(SettingsEntity("a1", intervalMinutes = 1440, checkMinutes = 120), a1())
        dao.setInterval("a1", 120)
        assertEquals("equal stays", SettingsEntity("a1", intervalMinutes = 120, checkMinutes = 120), a1())
        dao.setInterval("a1", 30)
        assertEquals(SettingsEntity("a1", intervalMinutes = 30, checkMinutes = 30), a1())
        dao.setCheckInterval("a1", 15)
        assertEquals(SettingsEntity("a1", intervalMinutes = 30, checkMinutes = 15), a1())
        dao.setCheckInterval("a1", null)
        dao.setInterval("a1", 15)
        assertEquals("Same as full sync follows it", SettingsEntity("a1", intervalMinutes = 15, checkMinutes = null), a1())
        dao.setManual("a1", true)
        assertEquals(SettingsEntity("a1", intervalMinutes = 15, checkMinutes = null, manual = true), a1())
        dao.setManual("a1", false)
        assertEquals("both intervals kept", SettingsEntity("a1", intervalMinutes = 15, checkMinutes = null, manual = false), a1())

        assertEquals("another account's row untouched", SettingsEntity("a2", intervalMinutes = 360, checkMinutes = 120), db.accountStateDao().settings("a2"))
    }

    @Test
    fun `import records are saved, read by account, given their copy, and deleted by original`() = runTest {
        // Saved before the copy's write with no copy, then given the copy.
        val dao = db.importRecordDao()
        dao.save(ImportRecordEntity("a1", originalRawContactId = 7, originalVersion = 3, copyRawContactId = null))
        dao.save(ImportRecordEntity("a1", originalRawContactId = 8, originalVersion = 5, copyRawContactId = 21, photoCopied = true))
        dao.save(ImportRecordEntity("a2", originalRawContactId = 7, originalVersion = 4, copyRawContactId = 22))

        assertEquals(
            listOf(ImportRecordEntity("a1", 7, 3, null, photoCopied = false), ImportRecordEntity("a1", 8, 5, 21, photoCopied = true)),
            dao.records("a1").sortedBy { it.originalRawContactId },
        )
        assertEquals("photo_copied is false by default", listOf(ImportRecordEntity("a2", 7, 4, 22, photoCopied = false)), dao.records("a2"))

        // A second save for the same original replaces it: how a record gets its copy, or a merge names Proton's row.
        dao.save(ImportRecordEntity("a1", originalRawContactId = 7, originalVersion = 3, copyRawContactId = 20, photoCopied = true))
        dao.save(ImportRecordEntity("a1", originalRawContactId = 8, originalVersion = 6, copyRawContactId = null))
        assertEquals(
            listOf(ImportRecordEntity("a1", 7, 3, 20, photoCopied = true), ImportRecordEntity("a1", 8, 6, null, photoCopied = false)),
            dao.records("a1").sortedBy { it.originalRawContactId },
        )

        dao.delete("a1", 7)
        dao.delete("a1", 9)
        assertEquals(listOf(8L), dao.records("a1").map { it.originalRawContactId })
        assertEquals("another account's record is left", listOf(7L), dao.records("a2").map { it.originalRawContactId })
        assertEquals(emptyList<ImportRecordEntity>(), dao.records("a3"))
    }

    @Test
    fun `an import record's photo_copied defaults to false`() = runTest {
        exec("INSERT INTO import_record (account_id, original_raw_contact_id, original_version) VALUES ('a1', 7, 3)")

        assertEquals(listOf(ImportRecordEntity("a1", 7, 3, null, photoCopied = false)), db.importRecordDao().records("a1"))
    }

    /** One row in each table but `account_record` and `settings`. */
    private fun insertRows(account: String) {
        exec("INSERT INTO last_report VALUES ('$account', 'DRY_RUN', 1767225600000, 1, NULL, 1, 2, 3, 4, 5, 6, 7, 0, 0, 8, 9, 10, 0, 0, NULL, 1767225600000, 1767225600000)")
        exec("INSERT INTO contact_map VALUES ('$account', 'p1', 7, 'contact-fp-v1:00', 1, NULL, NULL)")
        exec("INSERT INTO pending_contact_create VALUES ('$account', 8, 'urn:uuid:u1', 3)")
        exec("INSERT INTO conflict_hold VALUES ('$account', 'p1', 7, 'CONFLICT', NULL, NULL, NULL)")
        exec("INSERT INTO event_cursor VALUES ('$account', 'e1', 1767225600000)")
        exec("INSERT INTO import_record VALUES ('$account', 9, 4, 10, 1)")
    }

    @Test
    fun `deleting an account empties every table and leaves other accounts alone`() = runTest {
        for (account in listOf("a1", "a2")) {
            exec("INSERT INTO account_record VALUES ('$account', 'name-$account', 'ACTIVE')")
            exec("INSERT INTO settings (account_id) VALUES ('$account')")
            insertRows(account)
        }

        db.accountDao().deleteAccount("a1")

        // Every table the schema has, so a table added later is covered without touching this test.
        for (table in tables()) {
            assertEquals(table, "0", rows("SELECT COUNT(*) FROM $table WHERE account_id = 'a1'").single().single())
            assertEquals(table, "1", rows("SELECT COUNT(*) FROM $table WHERE account_id = 'a2'").single().single())
        }
    }

    /**
     * One pass per table: every other table's delete fails. Whatever order deleteAccount uses, in the pass for
     * the table it deletes first, that delete succeeds before a later one fails; one transaction undoes it.
     */
    @Test
    fun `an account is deleted in one transaction`() {
        for (survivor in tables()) {
            for (table in tables()) exec("DELETE FROM $table")
            exec("INSERT INTO account_record VALUES ('a1', 'name-a1', 'ACTIVE')")
            exec("INSERT INTO settings (account_id) VALUES ('a1')")
            insertRows("a1")
            val failing = tables() - survivor
            for (table in failing) exec("CREATE TRIGGER fail_$table BEFORE DELETE ON $table BEGIN SELECT RAISE(ABORT, 'x'); END")

            assertThrows(survivor, SQLiteConstraintException::class.java) { runBlocking { db.accountDao().deleteAccount("a1") } }

            for (table in tables()) {
                assertEquals("$survivor: $table", "1", rows("SELECT COUNT(*) FROM $table WHERE account_id = 'a1'").single().single())
            }
            for (table in failing) exec("DROP TRIGGER fail_$table")
        }
    }

    @Test
    fun `the event cursor and the last full sync are written apart, each keeping the other`() = runTest {
        // No row, and a null event_id, read as no cursor.
        val cursors = db.eventCursorDao()
        assertNull(cursors.cursor("a1"))

        cursors.fullSyncFinished("a1", 1_767_225_600_000)
        assertEquals(EventCursorEntity("a1", null, 1_767_225_600_000), cursors.cursor("a1"))
        cursors.saveCursor("a1", "e1")
        assertEquals(EventCursorEntity("a1", "e1", 1_767_225_600_000), cursors.cursor("a1"))
        cursors.fullSyncFinished("a1", 1_767_225_700_000)
        assertEquals(EventCursorEntity("a1", "e1", 1_767_225_700_000), cursors.cursor("a1"))
        cursors.saveCursor("a1", null)
        assertEquals(EventCursorEntity("a1", null, 1_767_225_700_000), cursors.cursor("a1"))

        cursors.saveCursor("a2", "e2")
        assertEquals(EventCursorEntity("a2", "e2", null), cursors.cursor("a2"))
        assertEquals("another account's row untouched", EventCursorEntity("a1", null, 1_767_225_700_000), cursors.cursor("a1"))
    }

    /** SyncMigrationTest starts from `1.json`: it's what `v0.1.0` installed, so it never changes. */
    @Test
    fun `the committed v1 schema is v0_1_0's`() {
        val schema = File("schemas/${SyncDatabase::class.java.name}/1.json").readText()

        assertTrue(schema.contains("\"version\": 1,"))
        assertTrue(schema.contains("\"identityHash\": \"eb5f9f9c24212aa22cd97c112f361b57\""))
    }

    /** A 0.2.0 check build installed v2, so it never changes. */
    @Test
    fun `the committed v2 schema is the 0_2_0 check build's`() {
        val schema = File("schemas/${SyncDatabase::class.java.name}/2.json").readText()

        assertTrue(schema.contains("\"version\": 2,"))
        assertTrue(schema.contains("\"identityHash\": \"18e62fe95142acc45d18ec3579d6812a\""))
    }

    /** `v0.3.0` installed v3, so it never changes. */
    @Test
    fun `the committed v3 schema is v0_3_0's`() {
        val schema = File("schemas/${SyncDatabase::class.java.name}/3.json").readText()

        assertTrue(schema.contains("\"version\": 3,"))
        assertTrue(schema.contains("\"identityHash\": \"fd106c7b5753f4d79085af977b525cd8\""))
    }

    @Test
    fun `the committed v4 schema is the compiled one`() {
        val schema = File("schemas/${SyncDatabase::class.java.name}/4.json")

        assertTrue(schema.path, schema.isFile)
        assertTrue(schema.readText().contains("\"version\": 4"))
        val identityHash = rows("SELECT identity_hash FROM room_master_table").single().single()!!
        assertTrue(identityHash, schema.readText().contains("\"identityHash\": \"$identityHash\""))
    }
}
