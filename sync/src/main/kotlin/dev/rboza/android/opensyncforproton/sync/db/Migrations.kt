package dev.rboza.android.opensyncforproton.sync.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v1 (download only) → v2: the new columns with their defaults, the empty tables, the old bulk guard flag dropped, and
 * every account with sync on put back in dry run with the notice set, so uploads are reviewed before anything is sent.
 * Nothing else changes. The SQL is `2.json`'s; Room runs it in one transaction. Added columns come last, so a migrated
 * table's column order differs from a new install's: name the columns in every write.
 */
internal val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `settings` ADD COLUMN `upgrade_notice` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE `settings` SET `sync_mode` = 'DRY_RUN', `upgrade_notice` = 1 WHERE `sync_mode` = 'ON'")
        db.execSQL("ALTER TABLE `contact_map` ADD COLUMN `rows_keyed` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `contact_map` ADD COLUMN `photo_baseline` TEXT")
        // A v1 report is never confirmed; the fresh dry run before Start replaces it.
        db.execSQL("ALTER TABLE `last_report` DROP COLUMN `bulk_guard_would_stop`")
        db.execSQL("ALTER TABLE `last_report` ADD COLUMN `bulk_guard_remote` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `last_report` ADD COLUMN `bulk_guard_phone` INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
            "CREATE TABLE `pending_contact_create` (`account_id` TEXT NOT NULL, `raw_contact_id` INTEGER NOT NULL, " +
                "`uid` TEXT NOT NULL, `version` INTEGER NOT NULL, PRIMARY KEY(`account_id`, `raw_contact_id`))",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX `index_pending_contact_create_account_id_uid` ON `pending_contact_create` (`account_id`, `uid`)",
        )
        db.execSQL(
            "CREATE TABLE `conflict_hold` (`account_id` TEXT NOT NULL, `proton_id` TEXT NOT NULL, " +
                "`raw_contact_id` INTEGER NOT NULL, `kind` TEXT NOT NULL, `choice` TEXT, `version` INTEGER, " +
                "`fingerprint` TEXT, PRIMARY KEY(`account_id`, `proton_id`))",
        )
        db.execSQL(
            "CREATE TABLE `event_cursor` (`account_id` TEXT NOT NULL, `event_id` TEXT, `last_full_sync_at` INTEGER, " +
                "PRIMARY KEY(`account_id`))",
        )
    }
}

/**
 * v2 → v3: `settings` rebuilt, since SQLite can't make `interval_minutes` NOT NULL in place. Manual (a null interval)
 * becomes the `manual` switch with the hour default kept for when it's turned off; every row gets the 15-minute check,
 * the period every v2 job had. `last_report` gains its phone parts, what started it, and when each side was counted: a
 * v2 report's lines were counted when it finished, all on Proton's side, so a full sync is made due to split them
 * before a partial run adds its phone part. `contact_map` gains the photo's thumbnail baseline, null for every map, so
 * a map compares its photo as a v2 map does until the app next writes the row. The new table and columns are
 * `3.json`'s; nothing else changes.
 */
internal val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE `settings_v3` (`account_id` TEXT NOT NULL, `sync_mode` TEXT NOT NULL DEFAULT 'DRY_RUN', " +
                "`interval_minutes` INTEGER NOT NULL DEFAULT 60, `check_minutes` INTEGER DEFAULT 15, " +
                "`manual` INTEGER NOT NULL DEFAULT 0, `upgrade_notice` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`account_id`))",
        )
        db.execSQL(
            "INSERT INTO `settings_v3` (`account_id`, `sync_mode`, `interval_minutes`, `check_minutes`, `manual`, `upgrade_notice`) " +
                "SELECT `account_id`, `sync_mode`, COALESCE(`interval_minutes`, 60), 15, `interval_minutes` IS NULL, `upgrade_notice` " +
                "FROM `settings`",
        )
        db.execSQL("DROP TABLE `settings`")
        db.execSQL("ALTER TABLE `settings_v3` RENAME TO `settings`")
        db.execSQL("ALTER TABLE `last_report` ADD COLUMN `conflicts_phone` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `last_report` ADD COLUMN `unreadable_phone` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `last_report` ADD COLUMN `started_by` TEXT")
        db.execSQL("ALTER TABLE `last_report` ADD COLUMN `proton_counted_at` INTEGER")
        db.execSQL("ALTER TABLE `last_report` ADD COLUMN `phone_counted_at` INTEGER")
        db.execSQL("UPDATE `last_report` SET `proton_counted_at` = `finished_at`, `phone_counted_at` = `finished_at`")
        db.execSQL("UPDATE `event_cursor` SET `last_full_sync_at` = NULL")
        db.execSQL("ALTER TABLE `contact_map` ADD COLUMN `photo_thumbnail_baseline` TEXT")
    }
}

/** v3 → v4: the empty `import_record` table, `4.json`'s; nothing else changes. */
internal val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE `import_record` (`account_id` TEXT NOT NULL, `original_raw_contact_id` INTEGER NOT NULL, " +
                "`original_version` INTEGER NOT NULL, `copy_raw_contact_id` INTEGER, `photo_copied` INTEGER NOT NULL DEFAULT 0, " +
                "PRIMARY KEY(`account_id`, `original_raw_contact_id`))",
        )
    }
}
