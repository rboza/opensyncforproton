package dev.rboza.android.opensyncforproton.sync.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The migrations from every schema a build installed (docs/architecture.md § Storage). v1→v2, from the committed
 * `1.json`, puts sync back in dry run with the upgrade notice (docs/sync.md § Sync mode); v2→v3, from `2.json`, which a
 * 0.2.0 check build installed, adds the scheduling and report columns and the thumbnail baseline; v3→v4, from `3.json`,
 * which `v0.3.0` installed, adds the import records.
 */
@RunWith(AndroidJUnit4::class)
class SyncMigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @get:Rule
    val helper = MigrationTestHelper(instrumentation, SyncDatabase::class.java)

    @Before
    @After
    fun deleteDatabase() {
        context.deleteDatabase(NAME)
    }

    /** Three accounts as `v0.1.0` left them: one syncing, one in dry run with Manual, one signed out while syncing. */
    private fun createV1() = helper.createDatabase(NAME, 1).use { db ->
        db.execSQL("INSERT INTO account_record VALUES ('a1', 'name-a1', 'ACTIVE')")
        db.execSQL("INSERT INTO account_record VALUES ('a2', 'name-a2', 'ACTIVE')")
        db.execSQL("INSERT INTO account_record VALUES ('a3', 'name-a3', 'SIGNED_OUT')")
        db.execSQL("INSERT INTO settings VALUES ('a1', 'ON', 120)")
        db.execSQL("INSERT INTO settings VALUES ('a2', 'DRY_RUN', NULL)")
        db.execSQL("INSERT INTO settings VALUES ('a3', 'ON', 60)")
        // Columns in v1's order: ..., conflicts, bulk_guard_would_stop, unreadable, duplicate_rows, missing_rows.
        db.execSQL("INSERT INTO last_report VALUES ('a1', 'SYNC', 1767225600000, 1, NULL, 1, 2, 3, 4, 5, 6, 7, 1, 8, 9, 10)")
        db.execSQL("INSERT INTO last_report VALUES ('a2', 'DRY_RUN', 1767225700000, 0, 'OFFLINE', 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)")
        db.execSQL("INSERT INTO contact_map VALUES ('a1', 'p1', 7, 'contact-fp-v1:${"a".repeat(64)}')")
        db.execSQL("INSERT INTO contact_map VALUES ('a1', 'p2', 8, NULL)")
        db.execSQL("INSERT INTO contact_map VALUES ('a2', 'p1', 7, 'contact-fp-v1:${"b".repeat(64)}')")
    }

    /** Runs the migration, then checks the result against the committed `2.json`, dropped tables included. */
    private fun migrate(): SupportSQLiteDatabase = helper.runMigrationsAndValidate(NAME, 2, true, MIGRATION_1_2)

    private fun SupportSQLiteDatabase.rows(sql: String): List<List<String?>> = query(sql).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add((0 until cursor.columnCount).map { if (cursor.isNull(it)) null else cursor.getString(it) })
        }
    }

    @Test
    fun an_account_with_sync_on_is_put_in_dry_run_with_the_notice() {
        createV1()

        val db = migrate()

        assertEquals(
            listOf(listOf("a1", "DRY_RUN", "120", "1"), listOf("a2", "DRY_RUN", null, "0"), listOf("a3", "DRY_RUN", "60", "1")),
            db.rows("SELECT account_id, sync_mode, interval_minutes, upgrade_notice FROM settings ORDER BY account_id"),
        )
    }

    @Test
    fun records_reports_and_maps_are_kept_with_the_new_columns_at_their_defaults() {
        createV1()

        val db = migrate()

        assertEquals(
            listOf(listOf("a1", "name-a1", "ACTIVE"), listOf("a2", "name-a2", "ACTIVE"), listOf("a3", "name-a3", "SIGNED_OUT")),
            db.rows("SELECT account_id, android_account_name, login_state FROM account_record ORDER BY account_id"),
        )
        // A v1 report is never confirmed: both bulk guard flags are false, whatever v1's flag said.
        assertEquals(
            listOf(
                listOf("a1", "SYNC", "1767225600000", "1", null, "1", "2", "3", "4", "5", "6", "7", "0", "0", "8", "9", "10"),
                listOf("a2", "DRY_RUN", "1767225700000", "0", "OFFLINE", "0", "0", "0", "0", "0", "0", "0", "0", "0", "0", "0", "0"),
            ),
            db.rows(
                "SELECT account_id, kind, finished_at, ready, blocker, new_from_proton, changed_on_proton, deleted_on_proton, " +
                    "new_on_phone, changed_on_phone, deleted_on_phone, conflicts, bulk_guard_remote, bulk_guard_phone, " +
                    "unreadable, duplicate_rows, missing_rows FROM last_report ORDER BY account_id",
            ),
        )
        // v0.1.0 wrote rows without keys, so no map has them; an empty fingerprint stays empty.
        assertEquals(
            listOf(
                listOf("a1", "p1", "7", "contact-fp-v1:${"a".repeat(64)}", "0", null),
                listOf("a1", "p2", "8", null, "0", null),
                listOf("a2", "p1", "7", "contact-fp-v1:${"b".repeat(64)}", "0", null),
            ),
            db.rows("SELECT account_id, proton_id, raw_contact_id, fingerprint, rows_keyed, photo_baseline FROM contact_map ORDER BY account_id, proton_id"),
        )
    }

    @Test
    fun the_new_tables_are_empty() {
        createV1()

        val db = migrate()

        for (table in listOf("pending_contact_create", "conflict_hold", "event_cursor")) {
            assertEquals(table, listOf(listOf("0")), db.rows("SELECT COUNT(*) FROM $table"))
        }
    }

    /** What the app opens: an installed v1 database is migrated, not refused or rebuilt. */
    @Test
    fun the_app_database_migrates_a_v1_file() = runBlocking {
        createV1()

        val db = openSyncDatabase(context)
        try {
            assertEquals(SettingsEntity("a1", SyncMode.DRY_RUN, 120, upgradeNotice = true), db.accountStateDao().settings("a1"))
            assertEquals(SettingsEntity("a2", SyncMode.DRY_RUN, 60, manual = true), db.accountStateDao().settings("a2"))
            assertEquals(3, db.accountDao().records().size)
        } finally {
            db.close()
        }
    }

    /** Accounts a 0.2.0 check build left: one syncing, one in dry run with Manual and the notice, one at 15. */
    private fun createV2() = helper.createDatabase(NAME, 2).use { db ->
        db.execSQL("INSERT INTO account_record VALUES ('a1', 'name-a1', 'ACTIVE')")
        db.execSQL("INSERT INTO account_record VALUES ('a2', 'name-a2', 'ACTIVE')")
        db.execSQL("INSERT INTO account_record VALUES ('a3', 'name-a3', 'SIGNED_OUT')")
        db.execSQL("INSERT INTO settings (account_id, sync_mode, interval_minutes, upgrade_notice) VALUES ('a1', 'ON', 120, 0)")
        db.execSQL("INSERT INTO settings (account_id, sync_mode, interval_minutes, upgrade_notice) VALUES ('a2', 'DRY_RUN', NULL, 1)")
        db.execSQL("INSERT INTO settings (account_id, sync_mode, interval_minutes, upgrade_notice) VALUES ('a3', 'ON', 15, 0)")
        db.execSQL("INSERT INTO contact_map (account_id, proton_id, raw_contact_id, fingerprint, rows_keyed, photo_baseline) VALUES ('a1', 'p1', 7, NULL, 1, '$BASELINE')")
        db.execSQL("INSERT INTO event_cursor (account_id, event_id, last_full_sync_at) VALUES ('a1', 'e1', 1767225600000)")
        db.execSQL(
            "INSERT INTO last_report (account_id, kind, finished_at, ready, blocker, new_from_proton, changed_on_proton, " +
                "deleted_on_proton, new_on_phone, changed_on_phone, deleted_on_phone, conflicts, bulk_guard_remote, " +
                "bulk_guard_phone, unreadable, duplicate_rows, missing_rows) " +
                "VALUES ('a1', 'SYNC', 1767225600000, 1, NULL, 1, 2, 3, 4, 5, 6, 7, 0, 1, 8, 9, 10)",
        )
    }

    /** v2→v3, checked against the committed `3.json`. */
    private fun migrateV2(): SupportSQLiteDatabase = helper.runMigrationsAndValidate(NAME, 3, true, MIGRATION_2_3)

    @Test
    fun manual_becomes_the_switch_with_the_hour_kept_and_every_account_checks_every_fifteen_minutes() {
        createV2()

        val db = migrateV2()

        assertEquals(
            listOf(
                listOf("a1", "ON", "120", "15", "0", "0"),
                listOf("a2", "DRY_RUN", "60", "15", "1", "1"),
                listOf("a3", "ON", "15", "15", "0", "0"),
            ),
            db.rows("SELECT account_id, sync_mode, interval_minutes, check_minutes, manual, upgrade_notice FROM settings ORDER BY account_id"),
        )
    }

    /**
     * A report from before the report kept each side's time: its lines were counted when it finished, with no phone
     * parts, since a full sync counts those again, and no trigger.
     */
    @Test
    fun a_report_keeps_its_counts_as_counted_when_it_finished_with_no_phone_parts_or_trigger() {
        createV2()

        val db = migrateV2()

        assertEquals(
            listOf(listOf("0", "0", null, "1767225600000", "1767225600000")),
            db.rows("SELECT conflicts_phone, unreadable_phone, started_by, proton_counted_at, phone_counted_at FROM last_report"),
        )
    }

    /**
     * The first run after the upgrade is a full sync, which splits a v2 report's counts into their sides, so a partial
     * run doesn't add its phone part to totals that already hold it. The cursor stays.
     */
    @Test
    fun a_full_sync_is_due_after_the_upgrade() {
        createV2()

        val db = migrateV2()

        assertEquals(listOf(listOf("a1", "e1", null)), db.rows("SELECT account_id, event_id, last_full_sync_at FROM event_cursor"))
    }

    /** A v2 map has no thumbnail baseline, so its photo is compared by the photo baseline until the next write. */
    @Test
    fun a_map_knows_no_photo_thumbnail() {
        createV2()

        val db = migrateV2()

        assertEquals(listOf(listOf("a1", "p1", BASELINE, null)), db.rows("SELECT account_id, proton_id, photo_baseline, photo_thumbnail_baseline FROM contact_map"))
    }

    @Test
    fun nothing_else_changes() {
        createV2()

        val db = migrateV2()

        assertEquals(
            listOf(listOf("a1", "name-a1", "ACTIVE"), listOf("a2", "name-a2", "ACTIVE"), listOf("a3", "name-a3", "SIGNED_OUT")),
            db.rows("SELECT account_id, android_account_name, login_state FROM account_record ORDER BY account_id"),
        )
        assertEquals(
            listOf(listOf("a1", "p1", "7", null, "1", BASELINE)),
            db.rows("SELECT account_id, proton_id, raw_contact_id, fingerprint, rows_keyed, photo_baseline FROM contact_map"),
        )
        assertEquals(listOf(listOf("a1", "e1")), db.rows("SELECT account_id, event_id FROM event_cursor"))
        assertEquals(
            listOf(listOf("a1", "SYNC", "1767225600000", "1", null, "1", "2", "3", "4", "5", "6", "7", "0", "1", "8", "9", "10")),
            db.rows(
                "SELECT account_id, kind, finished_at, ready, blocker, new_from_proton, changed_on_proton, deleted_on_proton, " +
                    "new_on_phone, changed_on_phone, deleted_on_phone, conflicts, bulk_guard_remote, bulk_guard_phone, " +
                    "unreadable, duplicate_rows, missing_rows FROM last_report",
            ),
        )
    }

    /** What the app opens: the check build's v2 database is migrated, not refused or rebuilt. */
    @Test
    fun the_app_database_migrates_a_v2_file() = runBlocking {
        createV2()

        val db = openSyncDatabase(context)
        try {
            assertEquals(SettingsEntity("a1", SyncMode.ON, 120, checkMinutes = 15), db.accountStateDao().settings("a1"))
            assertEquals(SettingsEntity("a2", SyncMode.DRY_RUN, 60, manual = true, upgradeNotice = true), db.accountStateDao().settings("a2"))
            assertEquals(3, db.accountDao().records().size)
            assertEquals(listOf(ContactMapEntity("a1", "p1", 7, null, rowsKeyed = true, photoBaseline = BASELINE, photoThumbnailBaseline = null)), db.contactMapDao().maps("a1"))
            assertEquals(
                LastReportEntity("a1", ReportKind.SYNC, 1767225600000, true, null, 1, 2, 3, 4, 5, 6, 7, false, true, 8, 9, 10),
                db.lastReportDao().report("a1"),
            )
        } finally {
            db.close()
        }
    }

    /** Every v3 table with a row, as `v0.3.0` could leave them; returns every row written. */
    private fun createV3() = helper.createDatabase(NAME, 3).use { db ->
        db.execSQL("INSERT INTO account_record VALUES ('a1', 'name-a1', 'ACTIVE')")
        db.execSQL("INSERT INTO account_record VALUES ('a2', 'name-a2', 'SIGNED_OUT')")
        db.execSQL("INSERT INTO settings VALUES ('a1', 'ON', 120, NULL, 1, 1)")
        db.execSQL("INSERT INTO settings VALUES ('a2', 'DRY_RUN', 60, 15, 0, 0)")
        db.execSQL(
            "INSERT INTO last_report VALUES ('a1', 'SYNC', 1767225600000, 1, NULL, 1, 2, 3, 4, 5, 6, 7, 0, 1, 8, 9, 10, 2, 3, " +
                "'PHONE_CHANGES', 1767225500000, 1767225600000)",
        )
        db.execSQL("INSERT INTO contact_map VALUES ('a1', 'p1', 7, 'contact-fp-v1:${"a".repeat(64)}', 1, '$BASELINE', '$BASELINE')")
        db.execSQL("INSERT INTO contact_map VALUES ('a1', 'p2', 7, NULL, 0, NULL, NULL)")
        db.execSQL("INSERT INTO pending_contact_create VALUES ('a1', 8, 'urn:uuid:u1', 3)")
        db.execSQL("INSERT INTO conflict_hold VALUES ('a1', 'p1', 7, 'CONFLICT', 'USE_PHONE', 4, 'fp-1')")
        db.execSQL("INSERT INTO event_cursor VALUES ('a1', 'e1', NULL)")
        db.v3Rows()
    }

    private val v3Tables = listOf(
        "account_record", "settings", "last_report", "contact_map", "pending_contact_create", "conflict_hold", "event_cursor",
    )

    /** Every row of every v3 table, in key order. */
    private fun SupportSQLiteDatabase.v3Rows() = v3Tables.associateWith { rows("SELECT * FROM $it ORDER BY 1, 2") }

    /** v3→v4, checked against the committed `4.json`. */
    @Test
    fun the_import_record_table_is_added_empty_and_every_v3_row_is_kept() {
        val before = createV3()

        val db = helper.runMigrationsAndValidate(NAME, 4, true, MIGRATION_3_4)

        assertEquals(listOf(listOf("0")), db.rows("SELECT COUNT(*) FROM import_record"))
        assertEquals(before, db.v3Rows())
        assertEquals(2, before.getValue("contact_map").size)
    }

    /** Every installed version reaches v4 through the migrations in order. */
    @Test
    fun v1_and_v2_files_migrate_through_to_v4() {
        createV1()
        helper.runMigrationsAndValidate(NAME, 4, true, MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).use { db ->
            assertEquals(listOf(listOf("0")), db.rows("SELECT COUNT(*) FROM import_record"))
            assertEquals(listOf(listOf("a1", "DRY_RUN", "120", "15", "0", "1")), db.rows("SELECT account_id, sync_mode, interval_minutes, check_minutes, manual, upgrade_notice FROM settings WHERE account_id = 'a1'"))
        }
        context.deleteDatabase(NAME)

        createV2()
        helper.runMigrationsAndValidate(NAME, 4, true, MIGRATION_2_3, MIGRATION_3_4).use { db ->
            assertEquals(listOf(listOf("0")), db.rows("SELECT COUNT(*) FROM import_record"))
            assertEquals(listOf(listOf("a2", "DRY_RUN", "60", "15", "1", "1")), db.rows("SELECT account_id, sync_mode, interval_minutes, check_minutes, manual, upgrade_notice FROM settings WHERE account_id = 'a2'"))
        }
    }

    /** What the app opens: `v0.3.0`'s v3 database is migrated, not refused or rebuilt, and has no import records. */
    @Test
    fun the_app_database_migrates_a_v3_file() = runBlocking {
        createV3()

        val db = openSyncDatabase(context)
        try {
            assertEquals(SettingsEntity("a1", SyncMode.ON, 120, checkMinutes = null, manual = true, upgradeNotice = true), db.accountStateDao().settings("a1"))
            assertEquals(2, db.accountDao().records().size)
            assertEquals(2, db.contactMapDao().maps("a1").size)
            assertEquals(emptyList<ImportRecordEntity>(), db.importRecordDao().records("a1"))
        } finally {
            db.close()
        }
    }

    private companion object {
        const val NAME = "sync.db"
        val BASELINE = "ab".repeat(32)
    }
}
