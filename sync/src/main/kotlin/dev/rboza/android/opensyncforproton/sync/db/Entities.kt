package dev.rboza.android.opensyncforproton.sync.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

// Room never stores secrets, decrypted content, contact names, or key packets; enums are stored by name.

enum class LoginState { ACTIVE, SIGNED_OUT }

@Entity(tableName = "account_record", primaryKeys = ["account_id"])
data class AccountRecordEntity(
    /** Proton's `User.ID`. */
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "android_account_name") val androidAccountName: String,
    @ColumnInfo(name = "login_state") val loginState: LoginState,
)

enum class SyncMode { DRY_RUN, ON }

@Entity(tableName = "settings", primaryKeys = ["account_id"])
data class SettingsEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "sync_mode", defaultValue = "DRY_RUN") val syncMode: SyncMode = SyncMode.DRY_RUN,
    /** The full sync's interval. */
    @ColumnInfo(name = "interval_minutes", defaultValue = "60") val intervalMinutes: Int = 60,
    /** The check's: the periodic job's period, never longer than the full sync's; null means Same as full sync. */
    @ColumnInfo(name = "check_minutes", defaultValue = "15") val checkMinutes: Int? = 15,
    /** Only Sync now syncs; both intervals are kept for when it's turned off. */
    @ColumnInfo(name = "manual", defaultValue = "0") val manual: Boolean = false,
    /** Set by the v1→v2 migration for an account it put back in dry run; cleared when the user dismisses it. */
    @ColumnInfo(name = "upgrade_notice", defaultValue = "0") val upgradeNotice: Boolean = false,
)

enum class ReportKind { DRY_RUN, SYNC }

/**
 * What started a sync: Sync now, Start (sync turned on), a sync from Android Settings, an import from this phone, the
 * event loop's full sync for changes on Proton or for the full-sync interval, an upload-only sync after phone edits, or
 * the event loop's check that found nothing on Proton (or failed). Full syncs merged into one run name the first of
 * Sync now, Start, Android Settings, and an import that asked for one, in that order.
 */
enum class ReportTrigger { SYNC_NOW, START, ANDROID_SETTINGS, IMPORT, PROTON_CHANGES, SCHEDULED, PHONE_CHANGES, CHECK }

/** Why a real sync couldn't run now: the one blocker a report names. */
enum class Blocker {
    OFFLINE, SIGNED_OUT, SESSION_EXPIRED, PERMISSION_MISSING, CONTACTS_HIDDEN, KEYS_DONT_UNLOCK, CAPTCHA,
    PROTON_UNAVAILABLE, INVENTORY_INCOMPLETE, APP_VERSION_REJECTED, CONTACTS_UNAVAILABLE,
}

/**
 * The last report's counts only: contact names live in memory, never here. A sync's report is the latest known state: a
 * run updates the lines it counted, Proton's side or the phone's, and keeps the others with the time they were counted,
 * as it keeps a side's changes when it found nothing there.
 */
@Entity(tableName = "last_report", primaryKeys = ["account_id"])
data class LastReportEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "kind") val kind: ReportKind,
    /** Epoch milliseconds. */
    @ColumnInfo(name = "finished_at") val finishedAt: Long,
    @ColumnInfo(name = "ready") val ready: Boolean,
    @ColumnInfo(name = "blocker") val blocker: Blocker?,
    @ColumnInfo(name = "new_from_proton") val newFromProton: Int,
    @ColumnInfo(name = "changed_on_proton") val changedOnProton: Int,
    @ColumnInfo(name = "deleted_on_proton") val deletedOnProton: Int,
    @ColumnInfo(name = "new_on_phone") val newOnPhone: Int,
    @ColumnInfo(name = "changed_on_phone") val changedOnPhone: Int,
    @ColumnInfo(name = "deleted_on_phone") val deletedOnPhone: Int,
    @ColumnInfo(name = "conflicts") val conflicts: Int,
    /** The bulk guard stopped deletions on the phone, from Proton's absences. */
    @ColumnInfo(name = "bulk_guard_remote", defaultValue = "0") val bulkGuardRemote: Boolean,
    /** The bulk guard stopped deletions on Proton, from the phone's tombstones. */
    @ColumnInfo(name = "bulk_guard_phone", defaultValue = "0") val bulkGuardPhone: Boolean,
    /**
     * Can't be synced: unreadable contacts, dirty rows holding rows the app can't read back, and after a sync those
     * whose write or photo was refused.
     */
    @ColumnInfo(name = "unreadable") val unreadable: Int,
    @ColumnInfo(name = "duplicate_rows") val duplicateRows: Int,
    @ColumnInfo(name = "missing_rows") val missingRows: Int,
    /**
     * The part of [conflicts] on rows the phone changed (dirty, deleted, or new), which an upload-only sync counts
     * again.
     */
    @ColumnInfo(name = "conflicts_phone", defaultValue = "0") val conflictsPhone: Int = 0,
    /** The part of [unreadable] on rows the phone changed. */
    @ColumnInfo(name = "unreadable_phone", defaultValue = "0") val unreadablePhone: Int = 0,
    /** What started a sync; null for a dry run and for a report migrated from v2. */
    @ColumnInfo(name = "started_by") val trigger: ReportTrigger? = null,
    /**
     * When Proton's side was counted (the lines from Proton, missing rows, the remote guard, and Proton's part of
     * conflicts and can't be synced), epoch ms: [finishedAt] when this run counted it; null when no sync has.
     */
    @ColumnInfo(name = "proton_counted_at") val protonCountedAt: Long? = finishedAt,
    /**
     * When the phone's side was counted (the lines to Proton, duplicate rows, the phone guard, and the phone's parts).
     */
    @ColumnInfo(name = "phone_counted_at") val phoneCountedAt: Long? = finishedAt,
)

/**
 * A raw ID isn't unique here: when Android reuses a row's ID for another contact, the old map stays and keeps its
 * contact held as a missing row, since `SOURCE_ID` decides pairing.
 */
@Entity(tableName = "contact_map", primaryKeys = ["account_id", "proton_id"])
data class ContactMapEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "proton_id") val protonId: String,
    @ColumnInfo(name = "raw_contact_id") val rawContactId: Long,
    /** `<tag>:<64 hex digits>`; empty only for a map rebuilt from `SOURCE_ID`, until the next write. */
    @ColumnInfo(name = "fingerprint") val fingerprint: String?,
    /**
     * The app wrote the contact's rows with their `Data.SYNC1` keys (D28) and knows its photo baseline; false for maps
     * migrated from v1, rebuilt ones, ones bound to a create without a write, and a write whose photo baseline couldn't
     * be read back.
     */
    @ColumnInfo(name = "rows_keyed", defaultValue = "0") val rowsKeyed: Boolean = false,
    /**
     * SHA-256 (64 lowercase hex digits) of the display photo read back after the last write, or of the thumbnail when
     * the provider keeps no display photo; null when it left none. Unknown while [rowsKeyed] is false.
     */
    @ColumnInfo(name = "photo_baseline") val photoBaseline: String? = null,
    /**
     * SHA-256 of the photo row's thumbnail read back after the last write, which is what the provider's photo clean-up
     * leaves of the photo; null when it left none, and for maps migrated from v2 or bound to a create without a
     * write. Unknown while [rowsKeyed] is false.
     */
    @ColumnInfo(name = "photo_thumbnail_baseline") val photoThumbnailBaseline: String? = null,
)

/** A C4 about to be sent, saved first: the `UID` lives only here until the create is bound. */
@Entity(
    tableName = "pending_contact_create",
    primaryKeys = ["account_id", "raw_contact_id"],
    indices = [Index("account_id", "uid", unique = true)],
)
data class PendingContactCreateEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "raw_contact_id") val rawContactId: Long,
    /** A UUID URN. */
    @ColumnInfo(name = "uid") val uid: String,
    /** The row's `VERSION`, read before sending. */
    @ColumnInfo(name = "version") val version: Long,
)

enum class HoldKind { CONFLICT, EDITED_BEFORE_KEYS, MISSING_ROW }

enum class HoldChoice { USE_PHONE, USE_PROTON }

/** What a real sync left for the user to answer; names aren't stored. */
@Entity(tableName = "conflict_hold", primaryKeys = ["account_id", "proton_id"])
data class ConflictHoldEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "proton_id") val protonId: String,
    /** The row it concerns: live, a tombstone, or gone. */
    @ColumnInfo(name = "raw_contact_id") val rawContactId: Long,
    @ColumnInfo(name = "kind") val kind: HoldKind,
    /** Null while unanswered and after Skip; made for the state below. */
    @ColumnInfo(name = "choice") val choice: HoldChoice?,
    /** The row's `VERSION` the sync found; null for a missing row. */
    @ColumnInfo(name = "version") val version: Long?,
    /** The remote's fingerprint the sync found; null when the contact was gone from Proton, or not yet seen. */
    @ColumnInfo(name = "fingerprint") val fingerprint: String?,
)

/** No row, or a null [eventId], means no cursor yet. */
@Entity(tableName = "event_cursor", primaryKeys = ["account_id"])
data class EventCursorEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "event_id") val eventId: String?,
    /** Epoch milliseconds when the last full sync ended, unless on a retryable blocker; null means one is due. */
    @ColumnInfo(name = "last_full_sync_at") val lastFullSyncAt: Long?,
)

/**
 * A local contact imported or merged: row IDs, a `VERSION`, and a flag only, no contact content. Logout keeps it; it's
 * deleted only when Remove originals removes its original, when its copy was refused, or with the account.
 */
@Entity(tableName = "import_record", primaryKeys = ["account_id", "original_raw_contact_id"])
data class ImportRecordEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    /** The local account's raw contact. */
    @ColumnInfo(name = "original_raw_contact_id") val originalRawContactId: Long,
    /** The original's `VERSION` when it was read for the import or merge. */
    @ColumnInfo(name = "original_version") val originalVersion: Long,
    /**
     * The app account's row that holds it: a new copy, or the Proton contact a pair was merged into; null for one saved
     * before its write and not yet confirmed.
     */
    @ColumnInfo(name = "copy_raw_contact_id") val copyRawContactId: Long?,
    /**
     * The write put the original's photo into the copy: the one way a photo counts as held, since Android re-encodes
     * stored photos.
     */
    @ColumnInfo(name = "photo_copied", defaultValue = "0") val photoCopied: Boolean = false,
)
