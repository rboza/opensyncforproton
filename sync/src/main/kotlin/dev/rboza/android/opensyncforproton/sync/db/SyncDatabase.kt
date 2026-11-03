package dev.rboza.android.opensyncforproton.sync.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert

@Database(
    version = 4,
    entities = [
        AccountRecordEntity::class, SettingsEntity::class, LastReportEntity::class, ContactMapEntity::class,
        PendingContactCreateEntity::class, ConflictHoldEntity::class, EventCursorEntity::class, ImportRecordEntity::class,
    ],
    exportSchema = true,
)
abstract class SyncDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun accountStateDao(): AccountStateDao
    abstract fun contactMapDao(): ContactMapDao
    abstract fun lastReportDao(): LastReportDao
    abstract fun pendingCreateDao(): PendingCreateDao
    abstract fun conflictHoldDao(): ConflictHoldDao
    abstract fun eventCursorDao(): EventCursorDao
    abstract fun importRecordDao(): ImportRecordDao
}

/**
 * The app's `sync.db`, migrated from every earlier version. Room opens it on first use; the DAOs' calls suspend and run
 * on Room's own threads, so any thread may make them. A migration that is missing or fails throws from every DAO call.
 */
fun openSyncDatabase(context: Context): SyncDatabase =
    Room.databaseBuilder(context, SyncDatabase::class.java, "sync.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()

/** An account's maps; a map is never looked up by raw ID, which may appear more than once. */
@Dao
interface ContactMapDao {
    @Query("SELECT * FROM contact_map WHERE account_id = :accountId")
    suspend fun maps(accountId: String): List<ContactMapEntity>

    /** Adds or replaces one Proton contact's map. */
    @Upsert
    suspend fun save(map: ContactMapEntity)

    /** Adds or replaces several maps in one transaction (a run's map repairs). */
    @Upsert
    suspend fun saveAll(maps: List<ContactMapEntity>)

    /**
     * Deletes one Proton contact's map: once its tombstone or row is removed or found stale (a deletion on either side
     * or both, or a restore's first step) or detached for a recreate, or when nothing is left to pair.
     */
    @Query("DELETE FROM contact_map WHERE account_id = :accountId AND proton_id = :protonId")
    suspend fun delete(accountId: String, protonId: String)
}

/** An account's creates sent, or about to be, and not yet settled. */
@Dao
interface PendingCreateDao {
    @Query("SELECT * FROM pending_contact_create WHERE account_id = :accountId")
    suspend fun records(accountId: String): List<PendingContactCreateEntity>

    /** Saved before C4 is sent; never replaced, since its `UID` is the one Proton may hold. */
    @Insert
    suspend fun save(record: PendingContactCreateEntity)

    /** The create is bound, or dropped. */
    @Query("DELETE FROM pending_contact_create WHERE account_id = :accountId AND raw_contact_id = :rawContactId")
    suspend fun delete(accountId: String, rawContactId: Long)
}

/** What a real sync left for the user to answer. */
@Dao
interface ConflictHoldDao {
    @Query("SELECT * FROM conflict_hold WHERE account_id = :accountId")
    suspend fun holds(accountId: String): List<ConflictHoldEntity>

    /**
     * A hold a sync found: added, or its kind, row, `VERSION`, and fingerprint recorded, keeping its choice only while
     * they're the ones the choice was made for. Unless [remoteSeen], the run didn't see the remote, so the fingerprint
     * the hold had stays and isn't compared.
     */
    @Query(
        """INSERT INTO conflict_hold (account_id, proton_id, raw_contact_id, kind, choice, version, fingerprint)
        VALUES (:accountId, :protonId, :rawContactId, :kind, NULL, :version, CASE WHEN :remoteSeen THEN :fingerprint END)
        ON CONFLICT (account_id, proton_id) DO UPDATE SET
            choice = CASE WHEN kind = excluded.kind AND raw_contact_id = excluded.raw_contact_id AND version IS excluded.version
                AND (NOT :remoteSeen OR fingerprint IS excluded.fingerprint) THEN choice END,
            fingerprint = CASE WHEN :remoteSeen THEN excluded.fingerprint ELSE fingerprint END,
            kind = excluded.kind, raw_contact_id = excluded.raw_contact_id, version = excluded.version""",
    )
    suspend fun found(accountId: String, protonId: String, rawContactId: Long, kind: HoldKind, version: Long?, fingerprint: String?, remoteSeen: Boolean)

    /**
     * The user's [choice] (null: Skip), saved only while the hold still records the kind, row, `VERSION`, and
     * fingerprint it was made for; how many holds took it (0 or 1).
     */
    @Query(
        """UPDATE conflict_hold SET choice = :choice WHERE account_id = :accountId AND proton_id = :protonId AND kind = :kind
        AND raw_contact_id = :rawContactId AND version IS :version AND fingerprint IS :fingerprint""",
    )
    suspend fun choose(accountId: String, protonId: String, kind: HoldKind, rawContactId: Long, version: Long?, fingerprint: String?, choice: HoldChoice?): Int

    /** A hold the sync no longer finds. */
    @Query("DELETE FROM conflict_hold WHERE account_id = :accountId AND proton_id = :protonId")
    suspend fun delete(accountId: String, protonId: String)
}

/** An account's event-loop row: each write sets one column and keeps the other. */
@Dao
interface EventCursorDao {
    @Query("SELECT * FROM event_cursor WHERE account_id = :accountId")
    suspend fun cursor(accountId: String): EventCursorEntity?

    /** The cursor to read the feed from next; null drops it. */
    @Query(
        """INSERT INTO event_cursor (account_id, event_id, last_full_sync_at) VALUES (:accountId, :eventId, NULL)
        ON CONFLICT (account_id) DO UPDATE SET event_id = excluded.event_id""",
    )
    suspend fun saveCursor(accountId: String, eventId: String?)

    /** A full sync ended with no blocker at [at] (epoch milliseconds). */
    @Query(
        """INSERT INTO event_cursor (account_id, event_id, last_full_sync_at) VALUES (:accountId, NULL, :at)
        ON CONFLICT (account_id) DO UPDATE SET last_full_sync_at = excluded.last_full_sync_at""",
    )
    suspend fun fullSyncFinished(accountId: String, at: Long)
}

/** The local contacts an import or a merge recorded. */
@Dao
interface ImportRecordDao {
    @Query("SELECT * FROM import_record WHERE account_id = :accountId")
    suspend fun records(accountId: String): List<ImportRecordEntity>

    /** Adds or replaces one original's record: saved before the write, then again with its copy. */
    @Upsert
    suspend fun save(record: ImportRecordEntity)

    /** Remove originals removed the original, or its copy was refused. */
    @Query("DELETE FROM import_record WHERE account_id = :accountId AND original_raw_contact_id = :originalRawContactId")
    suspend fun delete(accountId: String, originalRawContactId: Long)
}

@Dao
interface LastReportDao {
    /** Replaces the account's last report. */
    @Upsert
    suspend fun save(report: LastReportEntity)

    /** What the main screen shows after a restart. */
    @Query("SELECT * FROM last_report WHERE account_id = :accountId")
    suspend fun report(accountId: String): LastReportEntity?
}

/**
 * What the coordinator and the sync triggers read: the account's login state and Android name, and its sync mode.
 */
@Dao
interface AccountStateDao {
    @Query("SELECT * FROM account_record WHERE account_id = :accountId")
    suspend fun record(accountId: String): AccountRecordEntity?

    @Query("SELECT * FROM settings WHERE account_id = :accountId")
    suspend fun settings(accountId: String): SettingsEntity?

    /**
     * The account Android names; the name is the Proton address it was added with, never renamed, and Android names
     * are unique per account type, so at most one row matches.
     */
    @Query("SELECT * FROM account_record WHERE android_account_name = :name")
    suspend fun recordByAndroidName(name: String): AccountRecordEntity?
}

/** The account lifecycle's writes. */
@Dao
abstract class AccountDao {
    /** Every account the app keeps, signed out ones included. */
    @Query("SELECT * FROM account_record")
    abstract suspend fun records(): List<AccountRecordEntity>

    /** A new account's two rows together; an existing one is an error (the lifecycle checks first). */
    @Transaction
    open suspend fun insertAccount(record: AccountRecordEntity, settings: SettingsEntity) {
        insertRecord(record)
        insertSettings(settings)
    }

    @Insert
    protected abstract suspend fun insertRecord(record: AccountRecordEntity)

    @Insert
    protected abstract suspend fun insertSettings(settings: SettingsEntity)

    @Query("UPDATE account_record SET login_state = :state WHERE account_id = :accountId")
    abstract suspend fun setLoginState(accountId: String, state: LoginState)

    @Query("UPDATE settings SET sync_mode = :mode WHERE account_id = :accountId")
    abstract suspend fun setSyncMode(accountId: String, mode: SyncMode)

    /** The user dismissed the upgrade notice. */
    @Query("UPDATE settings SET upgrade_notice = 0 WHERE account_id = :accountId")
    abstract suspend fun dismissUpgradeNotice(accountId: String)

    /** The full sync's; a longer check drops to it. */
    @Query(
        "UPDATE settings SET interval_minutes = :minutes, check_minutes = MIN(check_minutes, :minutes) WHERE account_id = :accountId",
    )
    abstract suspend fun setInterval(accountId: String, minutes: Int)

    /** `null` is Same as full sync. */
    @Query("UPDATE settings SET check_minutes = :minutes WHERE account_id = :accountId")
    abstract suspend fun setCheckInterval(accountId: String, minutes: Int?)

    @Query("UPDATE settings SET manual = :manual WHERE account_id = :accountId")
    abstract suspend fun setManual(accountId: String, manual: Boolean)

    /** Removes everything Room keeps for [accountId], in one transaction. */
    @Transaction
    open suspend fun deleteAccount(accountId: String) {
        deleteContactMap(accountId)
        deletePendingCreates(accountId)
        deleteConflictHolds(accountId)
        deleteEventCursor(accountId)
        deleteImportRecords(accountId)
        deleteLastReport(accountId)
        deleteSettings(accountId)
        deleteAccountRecord(accountId)
    }

    @Query("DELETE FROM contact_map WHERE account_id = :accountId")
    protected abstract suspend fun deleteContactMap(accountId: String)

    @Query("DELETE FROM pending_contact_create WHERE account_id = :accountId")
    protected abstract suspend fun deletePendingCreates(accountId: String)

    @Query("DELETE FROM conflict_hold WHERE account_id = :accountId")
    protected abstract suspend fun deleteConflictHolds(accountId: String)

    @Query("DELETE FROM event_cursor WHERE account_id = :accountId")
    protected abstract suspend fun deleteEventCursor(accountId: String)

    @Query("DELETE FROM import_record WHERE account_id = :accountId")
    protected abstract suspend fun deleteImportRecords(accountId: String)

    @Query("DELETE FROM last_report WHERE account_id = :accountId")
    protected abstract suspend fun deleteLastReport(accountId: String)

    @Query("DELETE FROM settings WHERE account_id = :accountId")
    protected abstract suspend fun deleteSettings(accountId: String)

    @Query("DELETE FROM account_record WHERE account_id = :accountId")
    protected abstract suspend fun deleteAccountRecord(accountId: String)
}
