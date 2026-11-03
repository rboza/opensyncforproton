package dev.rboza.android.opensyncforproton.sync.engine

import dev.rboza.android.opensyncforproton.account.AccountId
import dev.rboza.android.opensyncforproton.account.CredentialStore
import dev.rboza.android.opensyncforproton.account.PersistedTokenStore
import dev.rboza.android.opensyncforproton.crypto.CryptoResult
import dev.rboza.android.opensyncforproton.sync.provider.shownEqual
import dev.rboza.android.opensyncforproton.proton.CardDto
import dev.rboza.android.opensyncforproton.mapping.PhotoUpload
import dev.rboza.android.opensyncforproton.mapping.MappingIssueCode
import dev.rboza.android.opensyncforproton.crypto.PlainCard
import dev.rboza.android.opensyncforproton.crypto.EncryptedCard
import dev.rboza.android.opensyncforproton.crypto.ItemCrypto
import dev.rboza.android.opensyncforproton.crypto.KeyHierarchy
import dev.rboza.android.opensyncforproton.crypto.RejectReason
import dev.rboza.android.opensyncforproton.crypto.UnlockedKeys
import dev.rboza.android.opensyncforproton.mapping.CardFragment
import dev.rboza.android.opensyncforproton.mapping.CardPlacement
import dev.rboza.android.opensyncforproton.mapping.ContactMapper
import dev.rboza.android.opensyncforproton.mapping.MappingResult
import dev.rboza.android.opensyncforproton.mapping.newContactUid
import dev.rboza.android.opensyncforproton.proton.ContactDto
import dev.rboza.android.opensyncforproton.proton.ContactListPageDto
import dev.rboza.android.opensyncforproton.proton.CreateReply
import dev.rboza.android.opensyncforproton.proton.findByUid
import dev.rboza.android.opensyncforproton.proton.Incomplete
import dev.rboza.android.opensyncforproton.proton.Inventory
import dev.rboza.android.opensyncforproton.proton.ProtonApi
import dev.rboza.android.opensyncforproton.proton.ProtonContactId
import dev.rboza.android.opensyncforproton.proton.ProtonError
import dev.rboza.android.opensyncforproton.proton.TokenStore
import dev.rboza.android.opensyncforproton.proton.contactFingerprint
import dev.rboza.android.opensyncforproton.proton.fetchInventory
import dev.rboza.android.opensyncforproton.sync.db.Blocker
import dev.rboza.android.opensyncforproton.sync.db.ConflictHoldDao
import dev.rboza.android.opensyncforproton.sync.db.ContactMapDao
import dev.rboza.android.opensyncforproton.sync.db.ContactMapEntity
import dev.rboza.android.opensyncforproton.sync.db.EventCursorDao
import dev.rboza.android.opensyncforproton.sync.db.HoldKind
import dev.rboza.android.opensyncforproton.sync.db.LastReportDao
import dev.rboza.android.opensyncforproton.sync.db.LastReportEntity
import dev.rboza.android.opensyncforproton.sync.db.PendingContactCreateEntity
import dev.rboza.android.opensyncforproton.sync.db.PendingCreateDao
import dev.rboza.android.opensyncforproton.sync.db.ReportKind
import dev.rboza.android.opensyncforproton.sync.db.ReportTrigger
import dev.rboza.android.opensyncforproton.sync.plan.PlanCategory
import dev.rboza.android.opensyncforproton.sync.plan.PhoneEdit
import dev.rboza.android.opensyncforproton.sync.plan.PhonePhoto
import dev.rboza.android.opensyncforproton.sync.plan.PhoneParts
import dev.rboza.android.opensyncforproton.sync.plan.PlanItem
import dev.rboza.android.opensyncforproton.sync.plan.RemoteContact
import dev.rboza.android.opensyncforproton.sync.plan.ReportCounts
import dev.rboza.android.opensyncforproton.sync.plan.SyncPlan
import dev.rboza.android.opensyncforproton.sync.plan.UnreadableReason
import dev.rboza.android.opensyncforproton.sync.plan.holdKind
import dev.rboza.android.opensyncforproton.sync.plan.isWritten
import dev.rboza.android.opensyncforproton.sync.plan.planSync
import dev.rboza.android.opensyncforproton.sync.plan.thumbnailLeft
import dev.rboza.android.opensyncforproton.sync.provider.ContactWrite
import dev.rboza.android.opensyncforproton.sync.provider.ContactWriteResult
import dev.rboza.android.opensyncforproton.sync.provider.DeviceContact
import dev.rboza.android.opensyncforproton.sync.provider.DeviceContacts
import dev.rboza.android.opensyncforproton.sync.provider.ExistingRow
import dev.rboza.android.opensyncforproton.sync.provider.FailureReason
import dev.rboza.android.opensyncforproton.sync.provider.PhoneAccount
import dev.rboza.android.opensyncforproton.sync.provider.PhotoScaler
import dev.rboza.android.opensyncforproton.sync.provider.ProviderUnavailableException
import dev.rboza.android.opensyncforproton.sync.provider.ScaledPhoto
import dev.rboza.android.opensyncforproton.sync.provider.dataRows
import dev.rboza.android.opensyncforproton.sync.provider.keyedGraph
import java.io.IOException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Why a carried-out contact wasn't written or uploaded; the report lists it as can't be synced, with its reason. */
enum class NotSyncedReason {
    WRITE_TOO_LARGE, VALUE_REJECTED, PHOTO_MALFORMED, PHOTO_OVERSIZED,

    /** An upload's: the row stays dirty. */
    PHOTO_UNREADABLE, UNKEYED_PROPERTY, UNWRITABLE_LABEL, NO_NAME_OR_EMAIL, UNENCRYPTED_PROPERTY, ENCRYPT_FAILED, NO_PRIMARY_KEY,
    PROTON_REFUSED, REMOTE_UNREADABLE,

    /** A create Proton says it already holds, whose `UID` no single contact has. */
    UID_UNCONFIRMED,
}

/**
 * A carried-out plan item, or a new row, that the provider, the photo scaler, the mapper, the crypto, or Proton
 * refused.
 */
class Refusal(val item: PlanItem, val reason: NotSyncedReason) {
    override fun toString() = "Refusal($item, $reason)"
}

/**
 * An edit that the upload's re-fetch (C2) found changed on Proton since the plan: a [PlanCategory.CONFLICT], or
 * [PlanCategory.SETTLED_REMOTE_CHANGED] when the phone shows what it now does; or one the plan had no remote for, which
 * the re-fetch shows Proton already has ([PlanCategory.SETTLED]). A phone deletion whose re-fetch moved since the
 * baseline is a conflict. [remoteFingerprint] is the fingerprint a conflict's re-fetch found, null when the contact was
 * gone, which its hold records. A stored choice the user took back or changed after planning is [withdrawn]: not
 * carried out, and counted as the kind it's held as.
 */
class Reclassified(val item: PlanItem, val category: PlanCategory, val remoteFingerprint: String? = null, val withdrawn: Boolean = false) {
    override fun toString() = "Reclassified($item, $category)"
}

/**
 * A run's outcome, dry run or sync: the plan (with names, in memory only) unless a blocker stopped it first, the
 * contacts that couldn't be written or uploaded, and in a sync the edits its uploads found changed on Proton.
 */
class RunReport(
    val kind: ReportKind,
    val finishedAt: Long,
    val blocker: Blocker?,
    val plan: SyncPlan?,
    val refused: List<Refusal> = emptyList(),
    val reclassified: List<Reclassified> = emptyList(),
    /**
     * Proton answered the run: any run that met no blocker, except an upload-only sync after phone edits that sent
     * nothing Proton answered.
     */
    val reachedProton: Boolean = blocker == null,
) {
    val ready: Boolean get() = blocker == null

    /** The run ended on a failure that may pass by itself, so the job retries it with backoff. */
    val retryable: Boolean get() = blocker in RETRYABLE

    /**
     * The plan's counts, each refused contact moved from its category to can't be synced, and each reclassified one.
     */
    fun counts(): ReportCounts {
        val refusedCounts = refused.fold(plan?.counts() ?: NO_COUNTS) { counts, refusal ->
            counts.without(refusal.item.category).let { it.copy(unreadable = it.unreadable + 1) }
        }
        // A settled one isn't counted.
        return reclassified.fold(refusedCounts) { counts, moved ->
            val left = counts.without(moved.item.category)
            when (moved.category) {
                PlanCategory.CONFLICT, PlanCategory.EDITED_BEFORE_KEYS -> left.copy(conflicts = left.conflicts + 1)
                PlanCategory.MISSING_ROW -> left.copy(missingRows = left.missingRows + 1)
                else -> left
            }
        }
    }

    /**
     * The parts of [counts]' conflicts and can't be synced on rows the phone changed, refused and reclassified ones
     * included.
     */
    fun phoneParts(): PhoneParts {
        val planned = plan?.phoneParts() ?: PhoneParts(0, 0)
        val refusedHere = refused.count { it.item.onChangedRow }
        val heldHere = reclassified.count { (it.category == PlanCategory.CONFLICT || it.category == PlanCategory.EDITED_BEFORE_KEYS) && it.item.onChangedRow }
        return PhoneParts(planned.conflicts + heldHere, planned.unreadable + refusedHere)
    }

    /** These counts less a carried-out item of [category]. */
    private fun ReportCounts.without(category: PlanCategory): ReportCounts = when (category) {
        PlanCategory.NEW_FROM_PROTON -> copy(newFromProton = newFromProton - 1)
        PlanCategory.CHANGED_ON_PROTON -> copy(changedOnProton = changedOnProton - 1)
        PlanCategory.DELETED_ON_PROTON -> copy(deletedOnProton = deletedOnProton - 1)
        PlanCategory.CHANGED_ON_PHONE -> copy(changedOnPhone = changedOnPhone - 1)
        PlanCategory.NEW_ON_PHONE -> copy(newOnPhone = newOnPhone - 1)
        PlanCategory.DELETED_ON_PHONE -> copy(deletedOnPhone = deletedOnPhone - 1)
        // Not counted until refused.
        PlanCategory.WRITTEN_BEFORE_KEYS, PlanCategory.SETTLED, PlanCategory.SETTLED_REMOTE_CHANGED, PlanCategory.DELETED_ON_BOTH -> this
        // Only these ten are carried out (planSync).
        else -> error("only a carried-out item is refused or reclassified")
    }

    override fun toString() = "RunReport($kind, blocker=$blocker, plan=$plan, refused=${refused.size}, reclassified=${reclassified.size})"
}

private val RETRYABLE = setOf(Blocker.OFFLINE, Blocker.PROTON_UNAVAILABLE, Blocker.CONTACTS_UNAVAILABLE)

/**
 * The category a hold of this kind is counted in: a row edited before keys among the conflicts, as the plan counts it.
 */
private val HoldKind.category: PlanCategory
    get() = if (this == HoldKind.MISSING_ROW) PlanCategory.MISSING_ROW else PlanCategory.CONFLICT

/** The categories a real sync sends to Proton, before the downloads. */
private val SENT = setOf(PlanCategory.CHANGED_ON_PHONE, PlanCategory.NEW_ON_PHONE, PlanCategory.DELETED_ON_PHONE)

private val NO_COUNTS = ReportCounts(0, 0, 0, 0, 0, 0, 0, false, false, 0, 0, 0)

/**
 * The sync engine. A dry run does everything a real sync does up to writing and sending, then stores only its report;
 * Proton and the phone's contacts are left as they were. The client's token store is the account's own, so a refresh
 * during a dry run saves the new tokens, and a rejected session deletes them. The first failure, in pipeline order, is
 * the report's blocker, except that a failure to read the rows beats an inventory one; after any inventory failure the
 * plan is still made from what arrived, but isn't ready. Called on `Dispatchers.IO`: the provider and the key work
 * block.
 */
class ContactSyncEngine(
    private val credentials: CredentialStore,
    private val newClient: (TokenStore) -> ProtonApi,
    private val keys: KeyHierarchy,
    private val crypto: ItemCrypto,
    private val mapper: ContactMapper,
    private val deviceContacts: DeviceContacts,
    private val maps: ContactMapDao,
    private val reports: LastReportDao,
    private val pendingCreates: PendingCreateDao,
    private val holds: ConflictHoldDao,
    private val cursors: EventCursorDao,
    private val photos: PhotoScaler,
    private val isOnline: () -> Boolean,
    private val now: () -> Long,
    /** The app's version, for a new contact's `PRODID` (D4). */
    private val versionName: String,
    /** A fresh `UID` for each new contact. */
    private val newUid: () -> String = ::newContactUid,
) : SyncEngine {
    /**
     * A real sync: the same plan as a dry run, then its carried-out items. Map repairs are written first, in one
     * transaction. Then the edits upload, while the keys unlocked for them are open, and the keys are closed. Then each
     * contact written from Proton is checked for cancellation, its photo scaled, written as the sync adapter, and
     * mapped at once, so a crash in between is mended by map repair; rows written before keys and settled edits
     * included, each write sets the rows' keys, so its map says so, and a settled edit's clears `DIRTY` (one whose
     * remote didn't move only has `DIRTY` cleared, and its map, which already holds that remote and photo, stays). A
     * failure sending to Proton that isn't about one contact stops the uploads, and the downloads still apply; a
     * refused operation (Contact Scopes) stops the run as contacts hidden, keeping what was written. Phone deletions
     * are sent after the creates, the rows deleted on Proton deleted and the removals of rows deleted on both sides
     * finished with the downloads, and the stale maps deleted with the map repairs. Last, the run's holds are saved,
     * unless it was cancelled. One that ends with no blocker, or a lasting one, is when the event loop's interval
     * counts from.
     */
    override suspend fun sync(account: PhoneAccount, confirmedPhoneDeletions: Int?, confirmedRemoteDeletions: Int?, trigger: ReportTrigger): RunReport {
        val report = carriedOut(account, Mode.SYNC, confirmedPhoneDeletions, confirmedRemoteDeletions)
        reports.save(storedReport(reports.report(account.id.value), account, report, trigger, partial = false))
        if (!report.retryable) cursors.fullSyncFinished(account.id.value, report.finishedAt)
        return report
    }

    /**
     * An upload-only sync, after a phone edit or for a check that found nothing on Proton: no inventory, so each edit
     * is re-fetched and uploaded as a real sync does, and nothing is downloaded. Its report updates the phone's side of
     * the stored one. It adds the holds it finds and removes none. A check reached Proton through its feed; after phone
     * edits, only an answer to what the run sent did.
     */
    override suspend fun upload(account: PhoneAccount, trigger: ReportTrigger): RunReport {
        val report = carriedOut(account, Mode.UPLOAD, fed = trigger == ReportTrigger.CHECK)
        reports.save(storedReport(reports.report(account.id.value), account, report, trigger, partial = true))
        return report
    }

    /** The event loop's failed check, with no full sync due: its time and blocker, every line kept. */
    override suspend fun failedCheck(account: PhoneAccount, blocker: Blocker): RunReport {
        val report = RunReport(ReportKind.SYNC, now(), blocker, plan = null)
        reports.save(storedReport(reports.report(account.id.value), account, report, ReportTrigger.CHECK, partial = true))
        return report
    }

    override suspend fun dryRun(account: PhoneAccount): RunReport {
        val report = plan(account, Mode.DRY_RUN).use { it.report }
        reports.save(lastReport(account, report))
        return report
    }

    private enum class Mode { DRY_RUN, SYNC, UPLOAD }

    /** [fed]: an upload-only sync whose check read Proton's feed first. */
    private suspend fun carriedOut(
        account: PhoneAccount, mode: Mode, confirmedPhoneDeletions: Int? = null, confirmedRemoteDeletions: Int? = null, fed: Boolean = false,
    ): RunReport =
        plan(account, mode, confirmedPhoneDeletions, confirmedRemoteDeletions).use { planned ->
            val report = if (planned.report.plan == null) {
                RunReport(ReportKind.SYNC, planned.report.finishedAt, planned.report.blocker, plan = null)
            } else {
                carryOut(account, planned, downloads = mode == Mode.SYNC)
            }
            if (mode != Mode.UPLOAD) {
                report
            } else {
                val answered = (planned.client as? Answering)?.answered == true
                with(report) { RunReport(kind, finishedAt, blocker, plan, refused, reclassified, reachedProton = fed || answered) }
            }
        }

    /**
     * A plan with what its uploads need: the client the inventory came through, keys unlocked again for them (closed by
     * [close]), each prepared edit's and new row's cards, the account's pending creates by the row they're for, those
     * whose row is a tombstone without a Proton ID or gone ([orphans], each with its row if any) and those whose row
     * holds one ([finished]), and the provider's photo maximum, read once per run.
     */
    private inner class Planned(
        var report: RunReport,
        val client: ProtonApi? = null,
        private var uploadKeys: UnlockedKeys? = null,
        val records: Map<Long, PendingContactCreateEntity> = emptyMap(),
        val orphans: List<Pair<PendingContactCreateEntity, DeviceContact?>> = emptyList(),
        val finished: List<PendingContactCreateEntity> = emptyList(),
    ) : AutoCloseable {
        val prepared = HashMap<PlanItem, List<EncryptedCard>>()
        val creates = HashMap<PlanItem, PreparedCreate>()

        /** The contacts this run's creates and lookups mapped. */
        val mapped = HashSet<ProtonContactId>()

        /**
         * The items carried out (or made stale by the phone): a stored choice among them is resolved, and its hold
         * goes.
         */
        val resolved = HashSet<PlanItem>()
        private var maxDimension: Int? = null

        val keys: UnlockedKeys? get() = uploadKeys

        fun maxPhotoDimension() = maxDimension ?: deviceContacts.maxPhotoDimension().also { maxDimension = it }

        override fun close() {
            uploadKeys?.close()
            uploadKeys = null
        }
    }

    /** [downloads] false (an upload-only sync): the uploads alone, without map repairs or downloads. */
    private suspend fun carryOut(account: PhoneAccount, planned: Planned, downloads: Boolean): RunReport {
        val plan = checkNotNull(planned.report.plan)
        val accountId = account.id.value
        if (downloads && plan.mapRepairs.isNotEmpty()) {
            // A rebuilt map never has keys.
            maps.saveAll(plan.mapRepairs.map { ContactMapEntity(accountId, it.protonId.value, it.rawContactId, fingerprint = null, rowsKeyed = false) })
        }
        // Maps with nothing left to pair, which only a complete inventory finds, so an upload-only sync has none.
        plan.staleMaps.forEach { maps.delete(accountId, it.value) }
        // A create bound before a crash left its record behind.
        planned.finished.forEach { pendingCreates.delete(accountId, it.rawContactId) }
        val refused = planned.report.refused.toMutableList()
        val reclassified = mutableListOf<Reclassified>()
        var stopped: Blocker? = null
        var runStopped = false
        try {
            val client = planned.client
            val uploadKeys = planned.keys
            if (client != null) {
                // Keys are unlocked whenever there's an edit or a new row to send; a deletion needs none. The edits
                // come before the new rows in the plan, and the deletions follow both.
                val uploads = plan.items.filter { it.category == PlanCategory.CHANGED_ON_PHONE || it.category == PlanCategory.NEW_ON_PHONE }.takeIf { uploadKeys != null }.orEmpty()
                val deletions = plan.items.filter { it.category == PlanCategory.DELETED_ON_PHONE && it.carriedOut }
                for (item in uploads + deletions) {
                    if (refused.any { it.item === item }) continue
                    currentCoroutineContext().ensureActive()
                    if (withdrawn(accountId, item)) {
                        reclassified += Reclassified(item, checkNotNull(item.hold).kind.category, withdrawn = true)
                        continue
                    }
                    val outcome = when (item.category) {
                        PlanCategory.NEW_ON_PHONE -> if (item.hold == null) {
                            create(account, item, planned, client, checkNotNull(uploadKeys))
                        } else {
                            recreate(account, item, planned, client, checkNotNull(uploadKeys))
                        }
                        PlanCategory.CHANGED_ON_PHONE -> upload(account, item, planned, client, checkNotNull(uploadKeys))
                        else -> delete(account, item, client)
                    }
                    // A choice carried out, or settled by its re-fetch, is resolved; one it finds moved is held again.
                    if (outcome == Outcome.Done || outcome is Outcome.Moved && outcome.category != PlanCategory.CONFLICT) planned.resolved += item
                    when (outcome) {
                        Outcome.Done -> Unit
                        is Outcome.Refused -> refused += Refusal(item, outcome.reason)
                        is Outcome.Moved -> reclassified += Reclassified(item, outcome.category, outcome.fingerprint)
                        is Outcome.Stop -> {
                            stopped = outcome.blocker
                            runStopped = outcome.run
                            break
                        }
                    }
                }
                if (stopped == null) {
                    for ((record, row) in planned.orphans) {
                        currentCoroutineContext().ensureActive()
                        val outcome = settle(account, record, row, client, planned)
                        if (outcome is Outcome.Stop) {
                            stopped = outcome.blocker
                            runStopped = outcome.run
                            break
                        }
                    }
                }
            }
            // The uploads are over, so their keys close before the downloads.
            planned.close()
            if (downloads && !runStopped) {
                // A contact a create may have made isn't downloaded as a second row: one whose UID a record had when
                // planning (a deleted row's included), or one this run's creates or lookups mapped.
                val waiting = planned.records.values.mapTo(HashSet()) { it.uid }
                for (item in plan.items) {
                    if (!item.carriedOut || item.category in SENT) continue
                    currentCoroutineContext().ensureActive()
                    if (withdrawn(accountId, item)) {
                        reclassified += Reclassified(item, checkNotNull(item.hold).kind.category, withdrawn = true)
                        continue
                    }
                    val row = item.row
                    val outcome = when (item.category) {
                        // Nothing to send: Proton no longer has it.
                        PlanCategory.DELETED_ON_BOTH -> removed(account, checkNotNull(item.protonId), checkNotNull(row))
                        // A chosen deletion takes the row as it was when the user chose, dirty or not.
                        PlanCategory.DELETED_ON_PROTON -> deletedHere(account, checkNotNull(item.protonId), checkNotNull(row), mayBeDirty = item.hold != null)
                        else -> {
                            // Only a readable contact is written from Proton (planSync).
                            val remote = item.remote as RemoteContact.Readable
                            if (item.category == PlanCategory.NEW_FROM_PROTON && (remote.graph.uid in waiting || remote.id in planned.mapped)) continue
                            if (item.category == PlanCategory.SETTLED) {
                                // The remote didn't move and the phone already shows it, so its rows stay and only
                                // DIRTY is cleared; the map already holds this remote and photo, so a phone edit made
                                // meanwhile still pairs with the keys it describes.
                                written(deviceContacts.clearDirty(account, remote.id.value, ExistingRow(checkNotNull(row).rawContactId, row.version))) { Outcome.Done }
                            } else if (row != null && row.deleted) {
                                // A chosen restore: the tombstone and its map go first, since the provider takes no
                                // second row for the Proton ID, then the contact is written as a new row.
                                when (val removal = removed(account, remote.id, row)) {
                                    Outcome.Done -> write(account, remote, existing = null, planned)
                                    else -> removal
                                }
                            } else {
                                // A settled edit whose remote moved, or a chosen write over an edit, is written from
                                // it, DIRTY cleared in the same batch.
                                val clearDirty = item.category == PlanCategory.SETTLED_REMOTE_CHANGED || item.hold != null
                                val existing = row?.let { ExistingRow(it.rawContactId, it.version, clearDirty) }
                                write(account, remote, existing, planned)
                            }
                        }
                    }
                    if (outcome == Outcome.Done) planned.resolved += item
                    when (outcome) {
                        Outcome.Done, is Outcome.Moved -> Unit
                        is Outcome.Refused -> refused += Refusal(item, outcome.reason)
                        is Outcome.Stop -> {
                            stopped = outcome.blocker
                            break
                        }
                    }
                }
            }
        } catch (_: SecurityException) {
            stopped = Blocker.PERMISSION_MISSING
        } catch (_: ProviderUnavailableException) {
            stopped = Blocker.CONTACTS_UNAVAILABLE
        }
        held(account, plan, reclassified, planned.resolved)
        // As in planning, the phone's blocker beats the inventory's.
        return RunReport(ReportKind.SYNC, now(), stopped ?: planned.report.blocker, plan, refused, reclassified)
    }

    /**
     * The run's holds, its plan's, the chosen ones it didn't carry out, and the conflicts its re-fetches found, each
     * naming its row (a missing row's is the one its map names) and recording the row's VERSION and the remote's
     * fingerprint, which keeps a choice only while they're unchanged. A hold it no longer finds is removed, unless the
     * inventory left that contact out (SyncPlan.leftOut), so an upload-only sync, which reads none, removes none.
     */
    private suspend fun held(account: PhoneAccount, plan: SyncPlan, reclassified: List<Reclassified>, resolved: Set<PlanItem>) {
        val accountId = account.id.value
        val found = plan.items.mapNotNull { item ->
            val kind = item.category.holdKind
            val hold = item.hold
            when {
                kind != null -> {
                    val id = checkNotNull(item.protonId)
                    Found(id, item.row?.rawContactId ?: checkNotNull(item.map).rawContactId, kind, item.row?.version, item.remote?.fingerprint?.value, !plan.leftOut(id))
                }
                // As recorded, so its choice stays for the next run.
                hold != null && item !in resolved -> Found(checkNotNull(item.protonId), hold.rawContactId, hold.kind, hold.version, hold.fingerprint, remoteSeen = true)
                else -> null
            }
        } + reclassified.filter { it.category == PlanCategory.CONFLICT && !it.withdrawn }.map {
            val row = checkNotNull(it.item.row)
            Found(checkNotNull(it.item.protonId), row.rawContactId, HoldKind.CONFLICT, row.version, it.remoteFingerprint, remoteSeen = true)
        }
        val ids = found.mapTo(HashSet()) { it.protonId.value }
        holds.holds(accountId).filter { it.protonId !in ids && !plan.leftOut(ProtonContactId(it.protonId)) }.forEach { holds.delete(accountId, it.protonId) }
        found.forEach { holds.found(accountId, it.protonId.value, it.rawContactId, it.kind, it.version, it.fingerprint, it.remoteSeen) }
    }

    /**
     * Whether [item]'s stored choice was taken back or changed since planning: it's read again just before it's carried
     * out, since the screen saves choices while a run goes. Its hold then stays as stored.
     */
    private suspend fun withdrawn(accountId: String, item: PlanItem): Boolean {
        val hold = item.hold ?: return false
        return holds.holds(accountId).none { it == hold }
    }

    /** A hold a run found, and the state it found the contact in. */
    private class Found(val protonId: ProtonContactId, val rawContactId: Long, val kind: HoldKind, val version: Long?, val fingerprint: String?, val remoteSeen: Boolean)

    /** What carrying out one item came to. */
    private sealed interface Outcome {
        data object Done : Outcome

        class Refused(val reason: NotSyncedReason) : Outcome

        /** [fingerprint]: a conflict's remote as its re-fetch found it, null when gone. */
        class Moved(val category: PlanCategory, val fingerprint: String? = null) : Outcome

        /** Nothing more is sent; with [run], nothing more is written either. */
        class Stop(val blocker: Blocker, val run: Boolean) : Outcome
    }

    /**
     * Writes [remote] to the phone as a download does: its photo scaled, then the row, then at once its map, with the
     * photo read back as its baseline.
     */
    private suspend fun write(account: PhoneAccount, remote: RemoteContact.Readable, existing: ExistingRow?, planned: Planned): Outcome {
        val original = remote.graph.photo
        val photo = if (original == null) {
            null
        } else {
            when (val scaled = photos.scale(original.bytes, planned.maxPhotoDimension())) {
                is ScaledPhoto.Jpeg -> scaled.bytes
                // A malformed or oversized photo rejects that contact's write.
                ScaledPhoto.Malformed -> return Outcome.Refused(NotSyncedReason.PHOTO_MALFORMED)
                ScaledPhoto.Oversized -> return Outcome.Refused(NotSyncedReason.PHOTO_OVERSIZED)
            }
        }
        val result = deviceContacts.apply(account, listOf(ContactWrite(remote.id.value, existing, dataRows(remote.graph, photo, remote.contact.keys)))).single()
        return written(result) { rawContactId ->
            // The row is written, with its keys, so its map is saved even if the run was cancelled meanwhile. A photo
            // baseline that couldn't be read back is unknown, which an unkeyed map stands for; a stop while reading
            // back comes after the map.
            val readBack = readBack(rawContactId)
            withContext(NonCancellable) {
                maps.save(
                    ContactMapEntity(
                        account.id.value, remote.id.value, rawContactId, remote.fingerprint.value,
                        rowsKeyed = readBack is ReadBack.Known, photoBaseline = (readBack as? ReadBack.Known)?.hash,
                        photoThumbnailBaseline = (readBack as? ReadBack.Known)?.thumbnail,
                    ),
                )
            }
            (readBack as? ReadBack.Unknown)?.stop?.let { throw it }
            Outcome.Done
        }
    }

    /**
     * A provider write's result: [mapped] after a write, nothing for a stale one, and a refusal or stop for the others.
     */
    private suspend fun written(result: ContactWriteResult, mapped: suspend (Long) -> Outcome): Outcome = when (result) {
        is ContactWriteResult.Written -> mapped(result.rawContactId)
        // The phone moved on; the next run plans it again.
        ContactWriteResult.Stale -> Outcome.Done
        is ContactWriteResult.Failed -> when (result.reason) {
            // Contact Scopes refuses every write this way.
            FailureReason.OPERATION_REFUSED -> Outcome.Stop(Blocker.CONTACTS_HIDDEN, run = true)
            FailureReason.TOO_LARGE -> Outcome.Refused(NotSyncedReason.WRITE_TOO_LARGE)
            FailureReason.REJECTED_VALUE -> Outcome.Refused(NotSyncedReason.VALUE_REJECTED)
        }
    }

    /**
     * An update: re-fetch the contact (C2). Moved on Proton since the baseline, it's settled from the re-read when the
     * phone shows what it now does, else a conflict. Otherwise its cards, prepared when planning (or now, for a contact
     * an incomplete inventory left out), are sent once (C5), the contact is read again (its reply has no cards), the
     * new fingerprint is stored as the baseline, and the row is written from the re-read, guarded by the VERSION read
     * when planning, clearing `DIRTY`. A stored Use phone is compared with the remote the user chose over instead of
     * the baseline, which may be empty (no map: the plan's repair saved one without keys) or have no keys, whose
     * unknown photo isn't compared.
     */
    private suspend fun upload(account: PhoneAccount, item: PlanItem, planned: Planned, client: ProtonApi, uploadKeys: UnlockedKeys): Outcome {
        val id = checkNotNull(item.protonId)
        val row = checkNotNull(item.row)
        val map = item.map ?: ContactMapEntity(account.id.value, id.value, row.rawContactId, fingerprint = null, rowsKeyed = false)
        val edit = checkNotNull(item.edit)
        val expected = if (item.hold != null) item.hold.fingerprint else map.fingerprint
        val photoShown = !map.rowsKeyed || edit.photo.isWritten(map)
        val refetched = try {
            client.contact(id)
        } catch (error: ProtonError) {
            return sendFailure(error)
        }
        val current = read(refetched, uploadKeys) as? RemoteContact.Readable ?: return Outcome.Refused(NotSyncedReason.REMOTE_UNREADABLE)
        val existing = ExistingRow(row.rawContactId, row.version, clearDirty = true)
        if (current.fingerprint.value != expected) {
            val settled = photoShown && shownEqual(edit.shown.contact.graph, current.contact)
            if (!settled) return Outcome.Moved(PlanCategory.CONFLICT, current.fingerprint.value)
            return when (val written = write(account, current, existing, planned)) {
                Outcome.Done -> Outcome.Moved(PlanCategory.SETTLED_REMOTE_CHANGED)
                else -> written
            }
        }
        // Step 5's settled check (docs/sync.md § The plan), which decides for an edit the plan had no remote to compare
        // with (an upload-only sync, or one an incomplete inventory left out), or whose map has no keys: what Proton
        // already shows only has its DIRTY cleared, unless only the written photo's thumbnail is left, which writing it
        // brings back.
        if (photoShown && shownEqual(edit.shown.contact.graph, current.contact)) {
            if (map.rowsKeyed && edit.photo.thumbnailLeft(map)) {
                return when (val written = write(account, current, existing, planned)) {
                    Outcome.Done -> Outcome.Moved(PlanCategory.SETTLED_REMOTE_CHANGED)
                    else -> written
                }
            }
            return when (val cleared = written(deviceContacts.clearDirty(account, id.value, ExistingRow(row.rawContactId, row.version))) { Outcome.Done }) {
                Outcome.Done -> Outcome.Moved(PlanCategory.SETTLED)
                else -> cleared
            }
        }
        val cards = planned.prepared[item] ?: when (val prepared = prepare(item, current.cards, uploadKeys, planned)) {
            is Prepared.Ready -> prepared.value
            is Prepared.Refused -> return Outcome.Refused(prepared.reason)
        }
        try {
            client.updateContact(id, cards.map { CardDto(it.type, it.data, it.signature) })
        } catch (error: ProtonError) {
            return sendFailure(error)
        }
        val stored = try {
            client.contact(id)
        } catch (error: ProtonError) {
            // Nothing is stored; the next run's re-fetch decides.
            return sendFailure(error).takeIf { it is Outcome.Stop } ?: Outcome.Done
        }
        val uploaded = read(stored, uploadKeys) as? RemoteContact.Readable ?: return Outcome.Done
        // The new baseline first, so a write the phone makes stale still uploads its next edit over it.
        withContext(NonCancellable) { maps.save(map.copy(fingerprint = uploaded.fingerprint.value)) }
        return write(account, uploaded, existing, planned)
    }

    /**
     * A C2 or C5 that failed: gone (2501) is a conflict; another error about the contact is "Proton refused the
     * change"; anything else stops sending, with the blocker an inventory failure gets.
     */
    private fun sendFailure(error: ProtonError): Outcome = when {
        error is ProtonError.Api && error.code == 2501 -> Outcome.Moved(PlanCategory.CONFLICT)
        error is ProtonError.Api && error.http < 500 || error is ProtonError.InvalidRequest -> Outcome.Refused(NotSyncedReason.PROTON_REFUSED)
        else -> Outcome.Stop(blocker(error), run = false)
    }

    /** A C4 or C6 that failed: about the contact, "Proton refused the change"; otherwise as [sendFailure]. */
    private fun createFailure(error: ProtonError): Outcome = when (val outcome = sendFailure(error)) {
        is Outcome.Moved -> Outcome.Refused(NotSyncedReason.PROTON_REFUSED)
        else -> outcome
    }

    /**
     * A phone deletion: the contact re-fetched (C2) and compared with the baseline by the fingerprint of its cards as
     * Proton stores them, so no keys are needed. Moved: a conflict, nothing sent. Gone (2501): only the removal is
     * left. Otherwise C6, then the removal. Another error about the contact counts it as "Proton refused the change",
     * the tombstone kept; anything else stops sending, and the next run re-fetches. A stored Use phone is compared with
     * the remote the user chose over, so a tombstone without a map has one too.
     */
    private suspend fun delete(account: PhoneAccount, item: PlanItem, client: ProtonApi): Outcome {
        val id = checkNotNull(item.protonId)
        val row = checkNotNull(item.row)
        val expected = if (item.hold != null) item.hold.fingerprint else checkNotNull(item.map).fingerprint
        val refetched = try {
            client.contact(id)
        } catch (error: ProtonError) {
            if (error is ProtonError.Api && error.code == 2501) return removed(account, id, row)
            return createFailure(error)
        }
        val found = contactFingerprint(id, refetched.cards).value
        if (found != expected) return Outcome.Moved(PlanCategory.CONFLICT, found)
        try {
            client.deleteContact(id)
        } catch (error: ProtonError) {
            return createFailure(error)
        }
        return removed(account, id, row)
    }

    /**
     * A tombstone removed as the sync adapter (a phone deletion finished, a removal of a contact deleted on both sides,
     * or a chosen restore's first step), then the map deleted, also when the provider finds it stale (gone, live, or
     * holding another Proton ID); a cancelled run still deletes it. A removal refused keeps the map.
     */
    private suspend fun removed(account: PhoneAccount, id: ProtonContactId, row: DeviceContact): Outcome {
        val result = deviceContacts.removeTombstone(account, id.value, row.rawContactId)
        if (result is ContactWriteResult.Failed) return written(result) { Outcome.Done }
        withContext(NonCancellable) { maps.delete(account.id.value, id.value) }
        return Outcome.Done
    }

    /**
     * A clean row whose contact a complete inventory left out (or, [mayBeDirty], one the user chose to delete here),
     * deleted as the sync adapter while it's still as planned, then its map, which a cancelled run still deletes; a row
     * the phone changed meanwhile keeps its map for the next run, and a refused one keeps it too.
     */
    private suspend fun deletedHere(account: PhoneAccount, id: ProtonContactId, row: DeviceContact, mayBeDirty: Boolean): Outcome =
        written(deviceContacts.removeRow(account, id.value, ExistingRow(row.rawContactId, row.version, clearDirty = mayBeDirty))) {
            withContext(NonCancellable) { maps.delete(account.id.value, id.value) }
            Outcome.Done
        }

    /**
     * Use phone over a Proton deletion: the row's `SOURCE_ID` cleared as the sync adapter, then its map and hold
     * deleted, so it's new on the phone, then created as any new row is, with the cards made when planning, at the
     * VERSION read, which clearing leaves. A crash in between leaves a map whose contact is on neither side, which the
     * next complete inventory deletes; a row the phone changed only drops the hold.
     */
    private suspend fun recreate(account: PhoneAccount, item: PlanItem, planned: Planned, client: ProtonApi, uploadKeys: UnlockedKeys): Outcome {
        val id = checkNotNull(item.protonId)
        val row = checkNotNull(item.row)
        val detached = deviceContacts.detach(account, id.value, ExistingRow(row.rawContactId, row.version))
        if (detached !is ContactWriteResult.Written) return written(detached) { Outcome.Done }
        // Its hold is gone whatever the create comes to: the next run sees a new row.
        planned.resolved += item
        withContext(NonCancellable) {
            maps.delete(account.id.value, id.value)
            holds.delete(account.id.value, id.value)
        }
        return create(account, item, planned, client, uploadKeys)
    }

    /** A new row's cards, made while planning, with the `UID` they hold. */
    private class PreparedCreate(val uid: String, val cards: List<EncryptedCard>)

    /**
     * A create: the record (row, `UID`, VERSION) saved before C4 is sent, unless one waits from an earlier run, whose
     * `UID` the cards hold. Created: bound. 2500: C1 paged for the `UID`; one match is bound, several or none (or a
     * listing that can't tell) leave it held with its record. 2001: a fresh record is dropped, and the next run tries a
     * new `UID`; a waiting one is looked up first. A failed listing stops sending. Any other failure keeps the record,
     * so the next run sends the same create again, which 2500 recovers if this one happened.
     */
    private suspend fun create(account: PhoneAccount, item: PlanItem, planned: Planned, client: ProtonApi, uploadKeys: UnlockedKeys): Outcome {
        val row = checkNotNull(item.row)
        val prepared = checkNotNull(planned.creates[item])
        val waiting = planned.records[row.rawContactId]
        val record = waiting ?: PendingContactCreateEntity(account.id.value, row.rawContactId, prepared.uid, row.version).also {
            withContext(NonCancellable) { pendingCreates.save(it) }
        }
        val reply = try {
            client.createContact(prepared.cards.map { CardDto(it.type, it.data, it.signature) })
        } catch (error: ProtonError) {
            return createFailure(error)
        }
        return when (reply) {
            // This run's cards.
            is CreateReply.Created -> bound(account, item, record, reply.id, known = true, client, uploadKeys, planned)
            CreateReply.UidExists -> {
                val found = findByUid(client, record.uid)
                (found.incomplete as? Incomplete.Failed)?.let { return Outcome.Stop(blocker(it.error), run = false) }
                val id = found.ids.singleOrNull()?.takeIf { found.incomplete == null } ?: return Outcome.Refused(NotSyncedReason.UID_UNCONFIRMED)
                // An earlier run's create, which holds this plan's cards only if the row hasn't changed since.
                bound(account, item, record, id, known = waiting?.version == row.version, client, uploadKeys, planned)
            }
            // A waiting record's earlier send may have landed: nothing says which code Proton gives a UID it holds with
            // cards it refuses, so it's looked up before the record goes.
            CreateReply.CardRefused -> if (waiting == null) {
                withContext(NonCancellable) { pendingCreates.delete(account.id.value, row.rawContactId) }
                Outcome.Refused(NotSyncedReason.PROTON_REFUSED)
            } else {
                val found = findByUid(client, record.uid)
                (found.incomplete as? Incomplete.Failed)?.let { return Outcome.Stop(blocker(it.error), run = false) }
                val id = found.ids.singleOrNull()?.takeIf { found.incomplete == null }
                when {
                    id != null -> bound(account, item, record, id, known = waiting.version == row.version, client, uploadKeys, planned)
                    found.incomplete == null && found.ids.isEmpty() -> {
                        withContext(NonCancellable) { pendingCreates.delete(account.id.value, row.rawContactId) }
                        Outcome.Refused(NotSyncedReason.PROTON_REFUSED)
                    }
                    else -> Outcome.Refused(NotSyncedReason.PROTON_REFUSED)
                }
            }
        }
    }

    /**
     * A created contact bound to its row: re-read first (C2; the reply has no cards), and a re-read that fails stores
     * nothing but the record. When Proton is [known] to hold this plan's cards, the row is written from the re-read as
     * a download does, given its Proton ID, guarded by the VERSION read when planning, `DIRTY` cleared; the map takes
     * the re-read and the photo read back. A row the phone changed meanwhile only gets its Proton ID, and the map the
     * photo the plan read, so its edit uploads next run. Otherwise (an older create, or a re-read that can't be read)
     * only the Proton ID is written, and the map has no keys: the edit is held as edited before keys. Then the record
     * goes.
     */
    private suspend fun bound(
        account: PhoneAccount, item: PlanItem, record: PendingContactCreateEntity, id: ProtonContactId, known: Boolean,
        client: ProtonApi, uploadKeys: UnlockedKeys, planned: Planned,
    ): Outcome {
        val row = checkNotNull(item.row)
        val stored = try {
            client.contact(id)
        } catch (error: ProtonError) {
            // The next run's create, refused as 2500, finds it by its UID.
            return sendFailure(error).takeIf { it is Outcome.Stop } ?: Outcome.Done
        }
        val fingerprint = contactFingerprint(id, stored.cards).value
        val remote = read(stored, uploadKeys) as? RemoteContact.Readable
        if (!known || remote == null) return bindAlone(account, planned, record, id, fingerprint, rowsKeyed = false, photoBaseline = null)
        // Prepared, so the plan read its photo.
        val planPhoto = (checkNotNull(item.edit).photo as PhonePhoto.Read).hash
        val photo = remote.graph.photo?.let { original ->
            when (val scaled = photos.scale(original.bytes, planned.maxPhotoDimension())) {
                is ScaledPhoto.Jpeg -> scaled.bytes
                ScaledPhoto.Malformed -> return refusedOnceBound(account, planned, record, id, fingerprint, planPhoto, NotSyncedReason.PHOTO_MALFORMED)
                ScaledPhoto.Oversized -> return refusedOnceBound(account, planned, record, id, fingerprint, planPhoto, NotSyncedReason.PHOTO_OVERSIZED)
            }
        }
        val existing = ExistingRow(row.rawContactId, row.version, clearDirty = true, bind = true)
        return when (val result = deviceContacts.apply(account, listOf(ContactWrite(id.value, existing, dataRows(remote.graph, photo, remote.contact.keys)))).single()) {
            is ContactWriteResult.Written -> {
                val readBack = readBack(result.rawContactId)
                planned.mapped += id
                withContext(NonCancellable) {
                    maps.save(
                        ContactMapEntity(
                            account.id.value, id.value, result.rawContactId, fingerprint,
                            rowsKeyed = readBack is ReadBack.Known, photoBaseline = (readBack as? ReadBack.Known)?.hash,
                            photoThumbnailBaseline = (readBack as? ReadBack.Known)?.thumbnail,
                        ),
                    )
                    pendingCreates.delete(account.id.value, record.rawContactId)
                }
                (readBack as? ReadBack.Unknown)?.stop?.let { throw it }
                Outcome.Done
            }
            ContactWriteResult.Stale -> bindAlone(account, planned, record, id, fingerprint, rowsKeyed = true, photoBaseline = planPhoto)
            is ContactWriteResult.Failed -> when (val refusal = written(result) { Outcome.Done }) {
                is Outcome.Refused -> refusedOnceBound(account, planned, record, id, fingerprint, planPhoto, refusal.reason)
                else -> refusal
            }
        }
    }

    /**
     * A write from the re-read the phone refused: the row is bound alone, as an edit made meanwhile is, and counted.
     */
    private suspend fun refusedOnceBound(
        account: PhoneAccount, planned: Planned, record: PendingContactCreateEntity, id: ProtonContactId, fingerprint: String, planPhoto: String?, reason: NotSyncedReason,
    ): Outcome = when (val bound = bindAlone(account, planned, record, id, fingerprint, rowsKeyed = true, photoBaseline = planPhoto)) {
        Outcome.Done -> Outcome.Refused(reason)
        else -> bound
    }

    /**
     * The record's row given its Proton ID alone (whatever its VERSION, a tombstone too), then the map, then the
     * record goes. A row gone, or given another Proton ID meanwhile, still gets the map, so the contact is held as a
     * missing row rather than made again.
     */
    private suspend fun bindAlone(
        account: PhoneAccount, planned: Planned, record: PendingContactCreateEntity, id: ProtonContactId, fingerprint: String, rowsKeyed: Boolean, photoBaseline: String?,
    ): Outcome {
        val result = deviceContacts.bind(account, id.value, record.rawContactId)
        if (result is ContactWriteResult.Failed) return written(result) { Outcome.Done }
        mapped(account, planned, record, id, fingerprint, rowsKeyed, photoBaseline)
        return Outcome.Done
    }

    private suspend fun mapped(
        account: PhoneAccount, planned: Planned, record: PendingContactCreateEntity, id: ProtonContactId, fingerprint: String, rowsKeyed: Boolean, photoBaseline: String?,
    ) {
        planned.mapped += id
        withContext(NonCancellable) {
            maps.save(ContactMapEntity(account.id.value, id.value, record.rawContactId, fingerprint, rowsKeyed, photoBaseline))
            pendingCreates.delete(account.id.value, record.rawContactId)
        }
    }

    /**
     * A record whose row was deleted (a tombstone without a Proton ID) or is gone: its `UID` looked up first. None on
     * Proton: dropped. One: the tombstone is given its Proton ID and mapped from a re-read, so the next run sees a
     * phone deletion of that contact; a row that's gone only gets the map, a missing row. Several, or a listing that
     * can't tell, keep the record. Nothing here is counted.
     */
    private suspend fun settle(account: PhoneAccount, record: PendingContactCreateEntity, row: DeviceContact?, client: ProtonApi, planned: Planned): Outcome {
        val found = findByUid(client, record.uid)
        (found.incomplete as? Incomplete.Failed)?.let { return Outcome.Stop(blocker(it.error), run = false) }
        if (found.incomplete != null || found.ids.size > 1) return Outcome.Done
        val id = found.ids.singleOrNull() ?: return Outcome.Done.also {
            withContext(NonCancellable) { pendingCreates.delete(account.id.value, record.rawContactId) }
        }
        val stored = try {
            client.contact(id)
        } catch (error: ProtonError) {
            return sendFailure(error).takeIf { it is Outcome.Stop } ?: Outcome.Done
        }
        val fingerprint = contactFingerprint(id, stored.cards).value
        if (row == null) {
            mapped(account, planned, record, id, fingerprint, rowsKeyed = false, photoBaseline = null)
            return Outcome.Done
        }
        return bindAlone(account, planned, record, id, fingerprint, rowsKeyed = false, photoBaseline = null)
    }

    /** An upload's cards or photo, made while planning, or why they can't be (the contact can't be synced). */
    private sealed interface Prepared<out T> {
        class Ready<T>(val value: T) : Prepared<T>

        class Refused(val reason: NotSyncedReason) : Prepared<Nothing>
    }

    /**
     * The edit's cards, patched from [remote]'s and encrypted, with the photo decided by its baseline: kept when the
     * phone's is the one written, or only its thumbnail is left, removed when the phone has none, else the phone's,
     * scaled. A map without keys, or none, doesn't know the photo the app wrote, so Proton's is kept.
     */
    private fun prepare(item: PlanItem, remote: List<CardFragment>, uploadKeys: UnlockedKeys, planned: Planned): Prepared<List<EncryptedCard>> {
        val edit = checkNotNull(item.edit)
        val map = item.map?.takeIf { it.rowsKeyed }
        val photo = if (map == null || edit.photo.isWritten(map)) PhotoUpload.Keep else when (val phonePhoto = edit.photo) {
            is PhonePhoto.Unreadable -> return Prepared.Refused(NotSyncedReason.PHOTO_UNREADABLE)
            is PhonePhoto.Read -> when (phonePhoto.hash) {
                null -> PhotoUpload.Remove
                else -> when (val scaled = phonePhoto(checkNotNull(item.row).rawContactId, planned)) {
                    is Prepared.Ready -> PhotoUpload.Replace(scaled.value)
                    is Prepared.Refused -> return scaled
                }
            }
        }
        return when (val patched = mapper.patch(edit.shown.contact, remote, photo)) {
            is MappingResult.Success -> encrypted(patched.value.fragments(), uploadKeys)
            is MappingResult.Invalid -> Prepared.Refused(refusal(patched.issues.first().code))
        }
    }

    /**
     * A new row's cards, made from what it shows, with [uid] and its photo, scaled, and encrypted.
     */
    private fun prepareCreate(item: PlanItem, uid: String, uploadKeys: UnlockedKeys, planned: Planned): Prepared<List<EncryptedCard>> {
        val edit = checkNotNull(item.edit)
        val photo = when (val phonePhoto = edit.photo) {
            is PhonePhoto.Unreadable -> return Prepared.Refused(NotSyncedReason.PHOTO_UNREADABLE)
            is PhonePhoto.Read -> if (phonePhoto.hash == null) {
                null
            } else {
                when (val scaled = phonePhoto(checkNotNull(item.row).rawContactId, planned)) {
                    is Prepared.Ready -> scaled.value
                    is Prepared.Refused -> return scaled
                }
            }
        }
        return when (val made = mapper.create(edit.shown.contact.graph, uid, versionName, photo)) {
            is MappingResult.Success -> encrypted(made.value, uploadKeys)
            is MappingResult.Invalid -> Prepared.Refused(refusal(made.issues.first().code))
        }
    }

    /** The phone's photo of [rawContactId], scaled; one that's gone or unreadable is refused. */
    private fun phonePhoto(rawContactId: Long, planned: Planned): Prepared<ByteArray> {
        val bytes = try {
            deviceContacts.photo(rawContactId)
        } catch (_: IOException) {
            null
        } ?: return Prepared.Refused(NotSyncedReason.PHOTO_UNREADABLE)
        return when (val scaled = photos.scale(bytes, planned.maxPhotoDimension())) {
            is ScaledPhoto.Jpeg -> Prepared.Ready(scaled.bytes)
            ScaledPhoto.Malformed -> Prepared.Refused(NotSyncedReason.PHOTO_MALFORMED)
            ScaledPhoto.Oversized -> Prepared.Refused(NotSyncedReason.PHOTO_OVERSIZED)
        }
    }

    private fun refusal(code: MappingIssueCode) = when (code) {
        MappingIssueCode.UNKEYED_PROPERTY -> NotSyncedReason.UNKEYED_PROPERTY
        MappingIssueCode.UNWRITABLE_LABEL -> NotSyncedReason.UNWRITABLE_LABEL
        MappingIssueCode.NO_NAME_OR_EMAIL -> NotSyncedReason.NO_NAME_OR_EMAIL
        MappingIssueCode.UNENCRYPTED_PROPERTY -> NotSyncedReason.UNENCRYPTED_PROPERTY
        // The cards read once already, so they parse.
        MappingIssueCode.MALFORMED_INPUT, MappingIssueCode.NO_CONTENT, MappingIssueCode.CONFLICTING_UID -> NotSyncedReason.REMOTE_UNREADABLE
    }

    /** An encrypted card is always written as type 3, encrypted and signed. */
    private fun encrypted(cards: List<CardFragment>, uploadKeys: UnlockedKeys): Prepared<List<EncryptedCard>> {
        val plain = cards.map { PlainCard(typeOf(it.placement), it.vcard) }
        return when (val encrypted = crypto.encryptContact(uploadKeys, plain)) {
            is CryptoResult.Success -> Prepared.Ready(encrypted.value)
            is CryptoResult.Rejected -> Prepared.Refused(if (encrypted.reason == RejectReason.NO_PRIMARY_KEY) NotSyncedReason.NO_PRIMARY_KEY else NotSyncedReason.ENCRYPT_FAILED)
        }
    }

    private fun typeOf(placement: CardPlacement) = when (placement) {
        CardPlacement.CLEAR -> 0
        CardPlacement.SIGNED -> 2
        CardPlacement.ENCRYPTED -> 3
    }

    /**
     * The photo baseline of a row this run just wrote, read back, with its thumbnail's. Both are hashed before the row
     * is checked, so a phone edit made since the write, which a hash may hold, shows as a dirty or deleted row. A
     * display photo that can't be read, or a row the phone changed, leaves it unknown; so does a revoked permission or
     * a provider that stops answering, which then stops the run.
     */
    private fun readBack(rawContactId: Long): ReadBack = try {
        val hash = deviceContacts.photoHash(rawContactId)
        val thumbnail = deviceContacts.thumbnailHash(rawContactId)
        if (deviceContacts.untouched(rawContactId)) ReadBack.Known(hash, thumbnail) else ReadBack.Unknown(stop = null)
    } catch (_: IOException) {
        ReadBack.Unknown(stop = null)
    } catch (stop: SecurityException) {
        ReadBack.Unknown(stop)
    } catch (stop: ProviderUnavailableException) {
        ReadBack.Unknown(stop)
    }

    private sealed interface ReadBack {
        /** [hash] and [thumbnail] are null for a row without a photo. */
        class Known(val hash: String?, val thumbnail: String?) : ReadBack

        /** [stop] is the failure that ends the run, if any. */
        class Unknown(val stop: Exception?) : ReadBack
    }

    /**
     * Steps 1–6 of docs/sync.md § The plan, then each edit prepared for upload and each new row's cards made, and the
     * account's pending creates read; sends and writes nothing. The keys that read the inventory are closed before the
     * phone is read; edits to prepare, cards to make, or a sync's edits unlock them again, and the caller closes those;
     * a deleted row's record, only looked up, and a phone deletion, compared by fingerprint, need none.
     */
    private suspend fun plan(account: PhoneAccount, mode: Mode, confirmedPhoneDeletions: Int? = null, confirmedRemoteDeletions: Int? = null): Planned {
        var client: ProtonApi? = null
        val inventory: Inventory?
        val remote: List<RemoteContact>
        val blocker: Blocker?
        if (mode == Mode.UPLOAD) {
            // Step 1 as in a full sync: while a login is needed, nothing is read or sent.
            loginBlocker(credentials, keys, account.id)?.let { return blocked(it) }
            // An upload-only sync reads no inventory; its uploads unlock the keys they need.
            inventory = null
            remote = emptyList()
            val online = isOnline()
            if (online) client = Answering(newClient(PersistedTokenStore(account.id, credentials)))
            blocker = if (online) null else Blocker.OFFLINE
        } else {
            // Step 1. A missing or unreadable envelope (a transient Keystore error included) only blocks this run.
            val unlocked = unlock(account) ?: return blocked(Blocker.SIGNED_OUT)
            if (unlocked !is CryptoResult.Success) return blocked(Blocker.KEYS_DONT_UNLOCK)
            // Step 2. The keys are closed once every contact is read, before the phone is. A device without a network
            // is offline at once, with nothing sent.
            val fetched = unlocked.value.use { userKeys ->
                if (!isOnline()) return@use null to emptyList()
                val api = newClient(PersistedTokenStore(account.id, credentials)).also { client = it }
                val fetched = fetchInventory(api)
                fetched to fetched.contacts.map {
                    // A large inventory takes a while to decrypt; a cancelled run stops before the next contact.
                    currentCoroutineContext().ensureActive()
                    read(it, userKeys)
                }
            }
            inventory = fetched.first
            remote = fetched.second
            blocker = when (val incomplete = inventory?.incomplete) {
                null -> if (inventory == null) Blocker.OFFLINE else null
                is Incomplete.Failed -> blocker(incomplete.error)
                else -> Blocker.INVENTORY_INCOMPLETE
            }
        }
        // Step 3. Without the rows nothing is counted, so their blocker beats the inventory's.
        val rows = try {
            deviceContacts.query(account)
        } catch (_: SecurityException) {
            return blocked(Blocker.PERMISSION_MISSING)
        } catch (_: ProviderUnavailableException) {
            return blocked(Blocker.CONTACTS_UNAVAILABLE)
        }
        val known = maps.maps(account.id.value)
        val stored = holds.holds(account.id.value)
        // Contact Scopes hides the app's own rows.
        if (rows.isEmpty() && known.isNotEmpty()) return blocked(Blocker.CONTACTS_HIDDEN)
        // A dirty row is read back while planning, so its blockers are the rows' too.
        val plan = try {
            planSync(
                remote, inventoryComplete = inventory != null && inventory.incomplete == null, rows = rows, maps = known, readEdit = ::readEdit,
                confirmedPhoneDeletions = confirmedPhoneDeletions,
                confirmedRemoteDeletions = confirmedRemoteDeletions, holds = stored,
            )
        } catch (_: SecurityException) {
            return blocked(Blocker.PERMISSION_MISSING)
        } catch (_: ProviderUnavailableException) {
            return blocked(Blocker.CONTACTS_UNAVAILABLE)
        }
        val edits = plan.items.filter { it.category == PlanCategory.CHANGED_ON_PHONE }
        // Edits upload only once the inventory came without a failed request, or online in an upload-only sync:
        // offline, or after a failed one, nothing more is sent.
        val inventoryAllows = if (mode == Mode.UPLOAD) client != null else inventory != null && inventory.incomplete !is Incomplete.Failed
        val toPrepare = edits.filter { it.remote is RemoteContact.Readable }
        // Each new row's cards, so whatever stops one is counted, a dry run's included.
        val creates = plan.items.filter { it.category == PlanCategory.NEW_ON_PHONE }
        val records = pendingCreates.records(account.id.value)
        val byRaw = rows.associateBy { it.rawContactId }
        val finished = records.filterNot { byRaw[it.rawContactId]?.sourceId.isNullOrBlank() }
        val orphans = records.filter { byRaw[it.rawContactId].let { row -> row == null || row.deleted && row.sourceId.isNullOrBlank() } }.map { it to byRaw[it.rawContactId] }
        val deletions = plan.items.any { it.category == PlanCategory.DELETED_ON_PHONE }
        val sends = mode != Mode.DRY_RUN && inventoryAllows && (edits.isNotEmpty() || creates.isNotEmpty() || orphans.isNotEmpty() || deletions)
        val byRecord = records.associateBy { it.rawContactId }
        // A deleted row's record is only looked up, and a phone deletion compared by fingerprint, which need no keys.
        if (toPrepare.isEmpty() && creates.isEmpty() && !(sends && edits.isNotEmpty())) {
            return Planned(RunReport(ReportKind.DRY_RUN, now(), blocker, plan), client.takeIf { sends }, null, byRecord, orphans, finished)
        }
        // As at step 1, keys that don't unlock again block the whole run (a transient Keystore error included).
        val again = unlock(account) ?: return blocked(Blocker.SIGNED_OUT)
        if (again !is CryptoResult.Success) return blocked(Blocker.KEYS_DONT_UNLOCK)
        val planned = Planned(RunReport(ReportKind.DRY_RUN, now(), blocker, plan), client.takeIf { sends }, again.value, byRecord, orphans, finished)
        val refused = mutableListOf<Refusal>()
        try {
            for (item in toPrepare) {
                currentCoroutineContext().ensureActive()
                when (val prepared = prepare(item, (item.remote as RemoteContact.Readable).cards, again.value, planned)) {
                    is Prepared.Ready -> planned.prepared[item] = prepared.value
                    is Prepared.Refused -> refused += Refusal(item, prepared.reason)
                }
            }
            for (item in creates) {
                currentCoroutineContext().ensureActive()
                // A waiting record's UID is sent again; a dry run's is never stored, so it's thrown away with the
                // cards.
                val uid = byRecord[checkNotNull(item.row).rawContactId]?.uid ?: newUid()
                when (val prepared = prepareCreate(item, uid, again.value, planned)) {
                    is Prepared.Ready -> planned.creates[item] = PreparedCreate(uid, prepared.value)
                    is Prepared.Refused -> refused += Refusal(item, prepared.reason)
                }
            }
        } catch (e: Throwable) {
            planned.close()
            return when (e) {
                is SecurityException -> blocked(Blocker.PERMISSION_MISSING)
                is ProviderUnavailableException -> blocked(Blocker.CONTACTS_UNAVAILABLE)
                else -> throw e
            }
        }
        planned.report = RunReport(ReportKind.DRY_RUN, now(), blocker, plan, refused)
        return planned
    }

    /** The account's keys, unlocked; null when its credentials are missing or unreadable. */
    private fun unlock(account: PhoneAccount) = credentials.load(account.id)?.use { keys.unlock(it.keys, it.passphrases) }

    /**
     * A dirty or new row as the phone shows it (steps 5 and 6 of docs/sync.md § The plan); a photo that can't be read
     * isn't known, unless what's left of it is the written one's thumbnail.
     */
    private fun readEdit(row: DeviceContact): PhoneEdit {
        val shown = keyedGraph(deviceContacts.readRows(row.rawContactId))
        val photo = try {
            PhonePhoto.Read(deviceContacts.photoHash(row.rawContactId))
        } catch (_: IOException) {
            PhonePhoto.Unreadable(deviceContacts.thumbnailHash(row.rawContactId))
        }
        return PhoneEdit(shown, photo)
    }

    /**
     * Decrypt, verify, and map one contact; its fingerprint comes from the cards as Proton stores them. Crypto warnings
     * (a legacy encrypted card, a key left out) change nothing in a dry run.
     */
    private fun read(contact: ContactDto, userKeys: UnlockedKeys): RemoteContact {
        val id = ProtonContactId(contact.id)
        val fingerprint = contactFingerprint(id, contact.cards)
        val plain = when (val result = crypto.decryptContact(userKeys, contact.cards.map { EncryptedCard(it.type, it.data, it.signature) })) {
            is CryptoResult.Rejected -> {
                val reason = if (result.reason == RejectReason.BAD_SIGNATURE) UnreadableReason.VERIFY else UnreadableReason.DECRYPT
                return RemoteContact.Unreadable(id, fingerprint, reason)
            }
            is CryptoResult.Success -> result.value
        }
        val cards = plain.map { CardFragment(placement(it.type), it.text) }
        return when (val mapped = mapper.merge(cards)) {
            is MappingResult.Success -> RemoteContact.Readable(id, fingerprint, mapped.value, cards)
            is MappingResult.Invalid -> RemoteContact.Unreadable(id, fingerprint, UnreadableReason.PARSE)
        }
    }

    /** A card's type: 0 clear, 2 signed, 1 and 3 encrypted (ItemCrypto rejects any other type). */
    private fun placement(type: Int) = when (type) {
        0 -> CardPlacement.CLEAR
        2 -> CardPlacement.SIGNED
        else -> CardPlacement.ENCRYPTED
    }

    private fun blocker(error: ProtonError): Blocker = blockerOf(error, isOnline)

    private fun blocked(blocker: Blocker) = Planned(RunReport(ReportKind.DRY_RUN, now(), blocker, plan = null))
}

/**
 * An upload-only sync's client, noting whether Proton answered anything it sent: a reply, or a refusal about the
 * contact. A rejected session, a captcha, the network, or Proton being unavailable isn't an answer.
 */
private class Answering(private val client: ProtonApi) : ProtonApi by client {
    var answered = false
        private set

    override suspend fun contact(id: ProtonContactId): ContactDto = noted { client.contact(id) }

    override suspend fun createContact(cards: List<CardDto>): CreateReply = noted { client.createContact(cards) }

    override suspend fun updateContact(id: ProtonContactId, cards: List<CardDto>) = noted { client.updateContact(id, cards) }

    override suspend fun deleteContact(id: ProtonContactId) = noted { client.deleteContact(id) }

    override suspend fun listContacts(page: Int, pageSize: Int): ContactListPageDto = noted { client.listContacts(page, pageSize) }

    private inline fun <T> noted(call: () -> T): T = try {
        call().also { answered = true }
    } catch (error: ProtonError) {
        if (error is ProtonError.Api && error.http < 500 || error is ProtonError.InvalidRequest) answered = true
        throw error
    }
}

/**
 * Whether the account's login and keys are there: null when its keys unlock, closed again at once; signed out without
 * readable credentials; keys don't unlock otherwise. Every run checks this first and a run that needs a login stops
 * there, sending nothing, so sync pauses until the user logs in.
 */
internal fun loginBlocker(credentials: CredentialStore, keys: KeyHierarchy, account: AccountId): Blocker? {
    val unlocked = credentials.load(account)?.use { keys.unlock(it.keys, it.passphrases) } ?: return Blocker.SIGNED_OUT
    if (unlocked !is CryptoResult.Success) return Blocker.KEYS_DONT_UNLOCK
    unlocked.value.close()
    return null
}

/** A failed request's report blocker; the event loop's feed uses it too. */
internal fun blockerOf(error: ProtonError, isOnline: () -> Boolean): Blocker = when (error) {
    is ProtonError.SessionRejected, is ProtonError.NoSession -> Blocker.SESSION_EXPIRED
    is ProtonError.Network -> if (isOnline()) Blocker.PROTON_UNAVAILABLE else Blocker.OFFLINE
    is ProtonError.RateLimited, is ProtonError.BadResponse -> Blocker.PROTON_UNAVAILABLE
    is ProtonError.Api -> if (error.http >= 500) Blocker.PROTON_UNAVAILABLE else Blocker.INVENTORY_INCOMPLETE
    is ProtonError.HumanVerification -> Blocker.CAPTCHA
    is ProtonError.AppVersionRejected -> Blocker.APP_VERSION_REJECTED
    is ProtonError.InvalidRequest -> Blocker.INVENTORY_INCOMPLETE
}

/** Problems only Proton's answer shows gone: a run Proton never answered keeps them. */
private val PROTON_PROBLEMS = setOf(Blocker.SESSION_EXPIRED, Blocker.CAPTCHA, Blocker.APP_VERSION_REJECTED)

/** A run's own blockers that say nothing about the problems above. */
private val UNREACHED = setOf(Blocker.OFFLINE, Blocker.PROTON_UNAVAILABLE)

/**
 * The row Room keeps after a sync's [report]: the latest known state. The side the run counted, Proton's or the phone's
 * ([partial] runs count the phone's alone), is replaced, unless the run found nothing there and the stored side holds
 * only changes, which it keeps; the other is kept from [previous] with the time it was counted, if [previous] is a
 * sync's (a dry run's lines say what a sync would do), and is otherwise not counted yet. A run that counted nothing
 * keeps both. One Proton never answered, with no blocker of its own or only offline or Proton unavailable, keeps
 * [previous]'s problem that only Proton shows gone; every run checks the login and keys itself first.
 */
internal fun storedReport(previous: LastReportEntity?, account: PhoneAccount, report: RunReport, trigger: ReportTrigger, partial: Boolean): LastReportEntity {
    val counts = report.counts()
    val parts = report.phoneParts()
    val earlier = previous?.takeIf { it.kind == ReportKind.SYNC }
    // A side this run found nothing on keeps the earlier side's changes, when it holds nothing that needs the user. A
    // guard can stop with every line of its side 0: rows edited on the phone and gone from Proton are conflicts on the
    // phone's side, and tombstones answered Use Proton are new from Proton.
    val protonNothing = with(counts) { newFromProton + changedOnProton + deletedOnProton + conflicts - parts.conflicts + unreadable - parts.unreadable + missingRows } == 0 &&
        !counts.bulkGuardRemote
    val phoneNothing = with(counts) { newOnPhone + changedOnPhone + deletedOnPhone + parts.conflicts + parts.unreadable + duplicateRows } == 0 &&
        !counts.bulkGuardPhone
    val protonChangesOnly = earlier?.run {
        newFromProton + changedOnProton + deletedOnProton > 0 && conflicts - conflictsPhone + unreadable - unreadablePhone + missingRows == 0 && !bulkGuardRemote
    } == true
    val phoneChangesOnly = earlier?.run {
        newOnPhone + changedOnPhone + deletedOnPhone > 0 && conflictsPhone + unreadablePhone + duplicateRows == 0 && !bulkGuardPhone
    } == true
    val protonCounted = report.plan != null && !partial && !(protonNothing && protonChangesOnly)
    val phoneCounted = report.plan != null && !(phoneNothing && phoneChangesOnly)
    fun <T> protonSide(counted: T, none: T, kept: LastReportEntity.() -> T) = if (protonCounted) counted else earlier?.kept() ?: none
    fun phoneSide(counted: Int, kept: LastReportEntity.() -> Int) = if (phoneCounted) counted else earlier?.kept() ?: 0
    val conflictsPhone = phoneSide(parts.conflicts) { conflictsPhone }
    val unreadablePhone = phoneSide(parts.unreadable) { unreadablePhone }
    val unchecked = !report.reachedProton && (report.blocker == null || report.blocker in UNREACHED)
    val kept = earlier?.blocker?.takeIf { unchecked && it in PROTON_PROBLEMS }
    val blocker = kept ?: report.blocker
    return LastReportEntity(
        accountId = account.id.value,
        kind = ReportKind.SYNC,
        finishedAt = report.finishedAt,
        ready = blocker == null,
        blocker = blocker,
        newFromProton = protonSide(counts.newFromProton, 0) { newFromProton },
        changedOnProton = protonSide(counts.changedOnProton, 0) { changedOnProton },
        deletedOnProton = protonSide(counts.deletedOnProton, 0) { deletedOnProton },
        newOnPhone = phoneSide(counts.newOnPhone) { newOnPhone },
        changedOnPhone = phoneSide(counts.changedOnPhone) { changedOnPhone },
        deletedOnPhone = phoneSide(counts.deletedOnPhone) { deletedOnPhone },
        conflicts = protonSide(counts.conflicts - parts.conflicts, 0) { conflicts - this.conflictsPhone } + conflictsPhone,
        bulkGuardRemote = protonSide(counts.bulkGuardRemote, false) { bulkGuardRemote },
        bulkGuardPhone = if (phoneCounted) counts.bulkGuardPhone else earlier?.bulkGuardPhone ?: false,
        unreadable = protonSide(counts.unreadable - parts.unreadable, 0) { unreadable - this.unreadablePhone } + unreadablePhone,
        duplicateRows = phoneSide(counts.duplicateRows) { duplicateRows },
        missingRows = protonSide(counts.missingRows, 0) { missingRows },
        conflictsPhone = conflictsPhone,
        unreadablePhone = unreadablePhone,
        trigger = trigger,
        protonCountedAt = if (protonCounted) report.finishedAt else earlier?.protonCountedAt,
        phoneCountedAt = if (phoneCounted) report.finishedAt else earlier?.phoneCountedAt,
    )
}

/**
 * The row Room keeps for a dry run's report, which replaces the stored one: counts only, never names. Its phone parts
 * stay 0, since no sync keeps a side of a dry run's report.
 */
internal fun lastReport(account: PhoneAccount, report: RunReport): LastReportEntity {
    val counts = report.counts()
    return LastReportEntity(
        accountId = account.id.value,
        kind = report.kind,
        finishedAt = report.finishedAt,
        ready = report.ready,
        blocker = report.blocker,
        newFromProton = counts.newFromProton,
        changedOnProton = counts.changedOnProton,
        deletedOnProton = counts.deletedOnProton,
        newOnPhone = counts.newOnPhone,
        changedOnPhone = counts.changedOnPhone,
        deletedOnPhone = counts.deletedOnPhone,
        conflicts = counts.conflicts,
        bulkGuardRemote = counts.bulkGuardRemote,
        bulkGuardPhone = counts.bulkGuardPhone,
        unreadable = counts.unreadable,
        duplicateRows = counts.duplicateRows,
        missingRows = counts.missingRows,
    )
}
