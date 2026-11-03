package dev.rboza.android.opensyncforproton.sync.plan

import dev.rboza.android.opensyncforproton.mapping.CardFragment
import dev.rboza.android.opensyncforproton.mapping.ContactGraph
import dev.rboza.android.opensyncforproton.mapping.KeyedGraph
import dev.rboza.android.opensyncforproton.proton.Fingerprint
import dev.rboza.android.opensyncforproton.proton.ProtonContactId
import dev.rboza.android.opensyncforproton.sync.db.ConflictHoldEntity
import dev.rboza.android.opensyncforproton.sync.db.ContactMapEntity
import dev.rboza.android.opensyncforproton.sync.db.HoldChoice
import dev.rboza.android.opensyncforproton.sync.db.HoldKind
import dev.rboza.android.opensyncforproton.sync.provider.DeviceContact
import dev.rboza.android.opensyncforproton.sync.provider.RowsRead
import dev.rboza.android.opensyncforproton.sync.provider.shownEqual

/** Why a remote contact can't be synced. */
enum class UnreadableReason { DECRYPT, VERIFY, PARSE }

/** One contact of the inventory, with the fingerprint of its cards. */
sealed interface RemoteContact {
    val id: ProtonContactId
    val fingerprint: Fingerprint

    /** [cards] are its decrypted cards, which an upload patches. */
    class Readable(
        override val id: ProtonContactId,
        override val fingerprint: Fingerprint,
        val contact: KeyedGraph,
        val cards: List<CardFragment>,
    ) : RemoteContact {
        val graph: ContactGraph get() = contact.graph

        override fun toString() = "RemoteContact.Readable"
    }

    class Unreadable(override val id: ProtonContactId, override val fingerprint: Fingerprint, val reason: UnreadableReason) : RemoteContact {
        override fun toString() = "RemoteContact.Unreadable($reason)"
    }
}

/**
 * The report categories, and two for rows whose map has no keys: [EDITED_BEFORE_KEYS], a dirty row with no conflict,
 * counts as a conflict; [WRITTEN_BEFORE_KEYS], a clean one, is written again from Proton to set its keys and isn't
 * counted. A settled edit, a dirty row showing what the remote does, isn't counted either: [SETTLED] only has `DIRTY`
 * cleared, and [SETTLED_REMOTE_CHANGED], whose remote moved since the baseline, is written from Proton, as is one with
 * only the written photo's thumbnail left. [UNMAPPED_ROWS], a dirty or new row holding rows the app can't read back,
 * which an upload or a write would lose, counts as can't be synced. [DELETED_ON_BOTH], a tombstone missing from a
 * complete inventory, isn't counted; a real sync finishes its removal.
 */
enum class PlanCategory {
    NEW_FROM_PROTON, CHANGED_ON_PROTON, DELETED_ON_PROTON,
    NEW_ON_PHONE, CHANGED_ON_PHONE, DELETED_ON_PHONE,
    CONFLICT, UNREADABLE, DUPLICATE_ROWS, MISSING_ROW, EDITED_BEFORE_KEYS, WRITTEN_BEFORE_KEYS, SETTLED, UNMAPPED_ROWS,
    SETTLED_REMOTE_CHANGED, DELETED_ON_BOTH,
}

/** The hold a real sync keeps for a category the user answers, if any. */
val PlanCategory.holdKind: HoldKind?
    get() = when (this) {
        PlanCategory.CONFLICT -> HoldKind.CONFLICT
        PlanCategory.EDITED_BEFORE_KEYS -> HoldKind.EDITED_BEFORE_KEYS
        PlanCategory.MISSING_ROW -> HoldKind.MISSING_ROW
        else -> null
    }

/** A row's photo as read back. */
sealed interface PhonePhoto {
    /** [hash] is null for a row without a photo; `toString` doesn't show it. */
    data class Read(val hash: String?) : PhonePhoto {
        override fun toString() = "PhonePhoto.Read(${if (hash == null) "none" else "hash"})"
    }

    /**
     * The display photo couldn't be read, so it isn't known to be the one the app wrote, unless its [thumbnail]'s hash
     * (null without one) shows it is; `toString` doesn't show it.
     */
    data class Unreadable(val thumbnail: String?) : PhonePhoto {
        override fun toString() = "PhonePhoto.Unreadable(${if (thumbnail == null) "none" else "thumbnail"})"
    }
}

/**
 * Whether the phone shows the photo the app last wrote, by [map]'s baselines: as written, or with only the thumbnail
 * read back after the write left ([thumbnailLeft]). [map] has keys.
 */
fun PhonePhoto.isWritten(map: ContactMapEntity) = this == PhonePhoto.Read(map.photoBaseline) || thumbnailLeft(map)

/**
 * What the provider's photo clean-up leaves of the photo the app wrote, its display photo deleted or its
 * `PHOTO_FILE_ID` cleared: the thumbnail [map] read back. A photo no larger than its thumbnail keeps no display photo,
 * so its baseline is that thumbnail and it lost nothing; a map from before thumbnails were kept knows none.
 */
fun PhonePhoto.thumbnailLeft(map: ContactMapEntity): Boolean {
    val thumbnail = map.photoThumbnailBaseline ?: return false
    return this != PhonePhoto.Read(map.photoBaseline) && (this == PhonePhoto.Read(thumbnail) || this == PhonePhoto.Unreadable(thumbnail))
}

/** A dirty row as the phone shows it: its data rows read back, and its photo. */
class PhoneEdit(val shown: RowsRead, val photo: PhonePhoto) {
    override fun toString() = "PhoneEdit($shown, $photo)"
}

/**
 * One contact in the plan. [carriedOut] says whether a real sync carries it out; [displayName] is for the report's
 * on-screen list only. [map] is the row's map (its baseline), and [edit] the dirty or new row as the phone shows it,
 * when the plan read it back. [hold] is the stored hold whose choice the item carries out, if it does. `toString` shows
 * neither the name nor the IDs.
 */
class PlanItem(
    val category: PlanCategory,
    val protonId: ProtonContactId?,
    val remote: RemoteContact?,
    val row: DeviceContact?,
    val displayName: String?,
    val carriedOut: Boolean,
    val map: ContactMapEntity? = null,
    val edit: PhoneEdit? = null,
    val hold: ConflictHoldEntity? = null,
) {
    val unreadableReason: UnreadableReason? get() = (remote as? RemoteContact.Unreadable)?.reason

    /**
     * Its row is one the phone changed: dirty, deleted, or made on the phone. An upload-only sync reads every such row,
     * so it counts these again; the rest only a full sync counts.
     */
    val onChangedRow: Boolean get() = row != null && (row.dirty || row.deleted || row.sourceId.isNullOrBlank())

    override fun toString() = "PlanItem($category, carriedOut=$carriedOut)"
}

/** A row that holds a Proton ID but has no map (or a map naming another row): mapped again, with an empty baseline. */
data class MapRepair(val protonId: ProtonContactId, val rawContactId: Long) {
    override fun toString() = "MapRepair(rawContactId=$rawContactId)"
}

/**
 * The report's counts, one per category (Room's `last_report`); [unreadable] (can't be synced) also counts rows with
 * unmapped rows, and after a sync the contacts whose write or photo was refused (RunReport.counts).
 */
data class ReportCounts(
    val newFromProton: Int,
    val changedOnProton: Int,
    val deletedOnProton: Int,
    val newOnPhone: Int,
    val changedOnPhone: Int,
    val deletedOnPhone: Int,
    val conflicts: Int,
    val bulkGuardRemote: Boolean,
    val bulkGuardPhone: Boolean,
    val unreadable: Int,
    val duplicateRows: Int,
    val missingRows: Int,
)

/**
 * The parts of the conflicts and can't-be-synced counts on rows the phone changed (dirty, deleted, or made on the
 * phone), which an upload-only sync counts again.
 */
data class PhoneParts(val conflicts: Int, val unreadable: Int)

/**
 * The bulk guard's two directions, kept apart: [bulkGuardRemote] stops deletions on the phone, from Proton's absences
 * or an empty inventory; [bulkGuardPhone] stops deletions on Proton, from the phone's tombstones. [staleMaps] are the
 * Proton IDs whose maps a real sync deletes: neither in a complete inventory nor on any row. [phoneDeletionsWaiting]
 * and [remoteDeletionsWaiting] are each direction's count, which the screen asks with: the phone's tombstones holding a
 * Proton ID, and the rows a complete inventory left out. [inventory] is the Proton IDs the inventory gave, and
 * [inventoryComplete] whether it gave them all.
 */
class SyncPlan(
    val items: List<PlanItem>,
    val mapRepairs: List<MapRepair>,
    val bulkGuardRemote: Boolean,
    val bulkGuardPhone: Boolean,
    val staleMaps: List<ProtonContactId> = emptyList(),
    val phoneDeletionsWaiting: Int = 0,
    val remoteDeletionsWaiting: Int = 0,
    private val inventory: Set<ProtonContactId> = emptySet(),
    private val inventoryComplete: Boolean = false,
) {
    /**
     * Whether the inventory may have left out [id]: an incomplete one may, and a complete one whose absences the remote
     * guard doesn't trust.
     */
    fun leftOut(id: ProtonContactId): Boolean = (!inventoryComplete || bulkGuardRemote) && id !in inventory

    fun counts(): ReportCounts {
        fun count(category: PlanCategory) = items.count { it.category == category }
        return ReportCounts(
            newFromProton = count(PlanCategory.NEW_FROM_PROTON),
            changedOnProton = count(PlanCategory.CHANGED_ON_PROTON),
            deletedOnProton = count(PlanCategory.DELETED_ON_PROTON),
            newOnPhone = count(PlanCategory.NEW_ON_PHONE),
            changedOnPhone = count(PlanCategory.CHANGED_ON_PHONE),
            deletedOnPhone = count(PlanCategory.DELETED_ON_PHONE),
            conflicts = count(PlanCategory.CONFLICT) + count(PlanCategory.EDITED_BEFORE_KEYS),
            bulkGuardRemote = bulkGuardRemote,
            bulkGuardPhone = bulkGuardPhone,
            unreadable = count(PlanCategory.UNREADABLE) + count(PlanCategory.UNMAPPED_ROWS),
            duplicateRows = count(PlanCategory.DUPLICATE_ROWS),
            missingRows = count(PlanCategory.MISSING_ROW),
        )
    }

    fun phoneParts(): PhoneParts {
        fun onChangedRows(vararg categories: PlanCategory) = items.count { it.category in categories && it.onChangedRow }
        return PhoneParts(
            conflicts = onChangedRows(PlanCategory.CONFLICT, PlanCategory.EDITED_BEFORE_KEYS),
            unreadable = onChangedRows(PlanCategory.UNREADABLE, PlanCategory.UNMAPPED_ROWS),
        )
    }

    override fun toString() = "SyncPlan(items=${items.size}, mapRepairs=${mapRepairs.size}, bulkGuardRemote=$bulkGuardRemote, bulkGuardPhone=$bulkGuardPhone)"
}

/**
 * docs/sync.md § The plan, steps 3–6: what a dry run reports and a real sync starts from. Rows pair with remote
 * contacts by `SOURCE_ID` (D13); a map only supplies the baseline, and only for the row it names. Remote contacts come
 * first in inventory order, then rows whose Proton ID isn't in the inventory, then rows made on the phone. Carried out:
 * "new from Proton", a row written from Proton (a clean one changed there or written before keys, or a settled one
 * whose remote moved), a settled one whose `DIRTY` is cleared, an edit, a new row, and, unless the bulk guard stops
 * them, a phone deletion, a deletion on Proton, and a removal finished. [readEdit] reads back from the phone a dirty
 * live row whose map has keys (unless it shares its Proton ID with another row, its contact is unreadable, or a
 * complete inventory left it out), a live row made on the phone, and a row a stored Use phone sends; nothing else is
 * read, and nothing written. [confirmedPhoneDeletions] and [confirmedRemoteDeletions] are the user's confirmations of
 * each direction's guard, if the run carries them. [holds] are the account's stored holds, whose matching choices the
 * plan carries out. Runs on the caller's thread; throws IllegalArgumentException if [remote] holds one contact twice,
 * and passes on what [readEdit] throws.
 */
fun planSync(
    remote: List<RemoteContact>,
    inventoryComplete: Boolean,
    rows: List<DeviceContact>,
    maps: List<ContactMapEntity>,
    readEdit: (DeviceContact) -> PhoneEdit,
    confirmedPhoneDeletions: Int? = null,
    confirmedRemoteDeletions: Int? = null,
    holds: List<ConflictHoldEntity> = emptyList(),
): SyncPlan {
    // The inventory gives each contact once; two would mean two writes to one row.
    require(remote.distinctBy { it.id }.size == remote.size) { "a remote contact appears twice" }
    // A blank SOURCE_ID (only another app could write one) counts as none.
    val bySource = rows.filterNot { it.sourceId.isNullOrBlank() }.groupBy { ProtonContactId(checkNotNull(it.sourceId)) }
    val mapsById = maps.associateBy { ProtonContactId(it.protonId) }
    val remoteIds = remote.mapTo(HashSet()) { it.id }
    val items = mutableListOf<PlanItem>()
    val repairs = mutableListOf<MapRepair>()

    // The bulk guard, each direction on its own: every row a complete inventory left out, dirty or not, and every
    // tombstone holding a Proton ID, conflict or not, against the Proton IDs held by exactly one row, leaving out rows
    // deleted on both sides (contacts on neither side any more).
    val single = bySource.values.filter { it.size == 1 }.map { it.single() }
    val goneOnBoth = { row: DeviceContact -> inventoryComplete && row.deleted && ProtonContactId(checkNotNull(row.sourceId)) !in remoteIds }
    val mapped = single.count { !goneOnBoth(it) }
    val proton = if (inventoryComplete) single.count { ProtonContactId(checkNotNull(it.sourceId)) !in remoteIds && !it.deleted } else 0
    val phone = single.count { it.deleted && (ProtonContactId(checkNotNull(it.sourceId)) in remoteIds || !inventoryComplete) }
    val emptyInventory = inventoryComplete && remote.isEmpty() && single.any { !it.deleted }
    // Either direction's confirmation, the count the user saw, lifts its guard only while no more are waiting; an empty
    // inventory's missing set is every live mapped row.
    val bulkGuardRemote = (emptyInventory || stops(proton, mapped)) && (confirmedRemoteDeletions == null || proton > confirmedRemoteDeletions)
    val bulkGuardPhone = stops(phone, mapped) && (confirmedPhoneDeletions == null || phone > confirmedPhoneDeletions)

    /**
     * Whether a real sync carries [category] out: a phone deletion only while its guard lets it, and a deletion on
     * Proton or a removal deleted on both sides, which act on Proton's absence, only while the other one does.
     */
    fun carried(category: PlanCategory) = when (category) {
        PlanCategory.DELETED_ON_PHONE -> !bulkGuardPhone
        PlanCategory.DELETED_ON_PROTON, PlanCategory.DELETED_ON_BOTH -> !bulkGuardRemote
        else -> category in CARRIED_OUT
    }

    val holdsById = holds.associateBy { ProtonContactId(it.protonId) }

    /**
     * A held contact's stored choice, carried out only while its hold records both sides as they are and the remote is
     * seen (in the inventory, or gone from a complete one the remote guard trusts), as what it does. Use Proton:
     * written from Proton, deleted here, or restored as a new row. Use phone: uploaded, recreated on Proton, or deleted
     * there; a missing row offers only Restore here. Null when it isn't.
     */
    fun chosen(category: PlanCategory, id: ProtonContactId, remote: RemoteContact?, row: DeviceContact?, rawContactId: Long): Pair<PlanCategory, ConflictHoldEntity>? {
        val hold = holdsById[id] ?: return null
        val seen = remote != null || inventoryComplete && !bulkGuardRemote
        val recorded = hold.kind == category.holdKind && hold.rawContactId == rawContactId && hold.version == row?.version &&
            hold.fingerprint == remote?.fingerprint?.value
        if (!seen || !recorded) return null
        return when (hold.choice) {
            null -> null
            HoldChoice.USE_PROTON -> when {
                remote == null -> PlanCategory.DELETED_ON_PROTON
                row == null || row.deleted -> PlanCategory.NEW_FROM_PROTON
                else -> PlanCategory.CHANGED_ON_PROTON
            }
            HoldChoice.USE_PHONE -> when {
                row == null -> null
                row.deleted -> PlanCategory.DELETED_ON_PHONE
                remote == null -> PlanCategory.NEW_ON_PHONE
                else -> PlanCategory.CHANGED_ON_PHONE
            }
        }?.let { it to hold }
    }

    /**
     * An item of [category], or of what its stored choice does. A chosen upload or recreate sends what the row shows,
     * read back here when step 5 didn't, unless it holds rows the app can't read back, which sending would lose.
     */
    fun item(category: PlanCategory, id: ProtonContactId, remote: RemoteContact?, row: DeviceContact?, name: String?, map: ContactMapEntity?, edit: PhoneEdit?): PlanItem {
        val rawContactId = row?.rawContactId ?: map?.rawContactId
        val choice = if (category.holdKind == null || rawContactId == null) null else chosen(category, id, remote, row, rawContactId)
        val sends = choice != null && (choice.first == PlanCategory.CHANGED_ON_PHONE || choice.first == PlanCategory.NEW_ON_PHONE)
        val shown = if (sends) edit ?: readEdit(checkNotNull(row)) else edit
        val done = if (sends && checkNotNull(shown).shown.unmappedRows > 0) PlanCategory.UNMAPPED_ROWS else choice?.first ?: category
        return PlanItem(done, id, remote, row, name, carried(done), map, shown, choice?.second)
    }

    /**
     * Map repair (step 3): the row's map, or null when it's missing or names another row, so the row is mapped again
     * with an empty baseline and without keys.
     */
    fun mapOf(id: ProtonContactId, row: DeviceContact): ContactMapEntity? {
        val map = mapsById[id]
        if (map?.rawContactId != row.rawContactId) {
            repairs += MapRepair(id, row.rawContactId)
            return null
        }
        return map
    }

    for (contact in remote) {
        val paired = bySource[contact.id].orEmpty()
        val name = (contact as? RemoteContact.Readable)?.graph?.let(::displayName)
        when {
            // Step 4: never pick one of several rows.
            paired.size > 1 -> items += PlanItem(PlanCategory.DUPLICATE_ROWS, contact.id, contact, null, name ?: paired.first().displayName, false)
            paired.isEmpty() -> items += when {
                contact is RemoteContact.Unreadable -> PlanItem(PlanCategory.UNREADABLE, contact.id, contact, null, null, false)
                // The user may have deleted it: hold, don't recreate; the map names the row it's held for.
                contact.id in mapsById -> item(PlanCategory.MISSING_ROW, contact.id, contact, null, name, mapsById[contact.id], null)
                else -> PlanItem(PlanCategory.NEW_FROM_PROTON, contact.id, contact, null, name, carried(PlanCategory.NEW_FROM_PROTON))
            }
            else -> {
                val row = paired.single()
                val map = mapOf(contact.id, row)
                val remoteChanged = map?.fingerprint != contact.fingerprint.value
                val keyed = map?.rowsKeyed == true
                val edit = if (contact is RemoteContact.Readable && keyed && row.dirty && !row.deleted) readEdit(row) else null
                // Step 5, in its order: settled, then a conflict, then a dirty row without keys. An unreadable contact
                // leaves its row as it is, unless the row is a tombstone and Proton still holds its baseline: then it's
                // a phone deletion, as an upload-only sync, which reads nothing, sees it too.
                val category = when {
                    contact is RemoteContact.Unreadable && !(row.deleted && !remoteChanged) -> PlanCategory.UNREADABLE
                    // One with only the written photo's thumbnail left is written again, which brings its display
                    // photo back.
                    edit != null && settled(edit, contact as RemoteContact.Readable, checkNotNull(map)) ->
                        if (remoteChanged || edit.photo.thumbnailLeft(map)) PlanCategory.SETTLED_REMOTE_CHANGED else PlanCategory.SETTLED
                    remoteChanged && (row.dirty || row.deleted) -> PlanCategory.CONFLICT
                    remoteChanged -> PlanCategory.CHANGED_ON_PROTON
                    row.deleted -> PlanCategory.DELETED_ON_PHONE
                    row.dirty && !keyed -> PlanCategory.EDITED_BEFORE_KEYS
                    row.dirty -> changedOnPhone(checkNotNull(edit))
                    !keyed -> PlanCategory.WRITTEN_BEFORE_KEYS
                    else -> null
                }
                if (category != null) items += item(category, contact.id, contact, row, name ?: row.displayName, map, edit)
            }
        }
    }

    // Rows whose Proton ID isn't in the inventory. Only a complete inventory says a contact is gone.
    for ((id, paired) in bySource) {
        if (id in remoteIds) continue
        if (paired.size > 1) {
            items += PlanItem(PlanCategory.DUPLICATE_ROWS, id, null, null, paired.first().displayName, false)
            continue
        }
        val row = paired.single()
        var edit: PhoneEdit? = null
        // Deleted on both sides needs no map repair: there's nothing left to protect.
        val map = if (inventoryComplete && row.deleted) mapsById[id] else mapOf(id, row)
        val category = when {
            // Deleted on both sides: nothing to send or report, only the removal to finish.
            inventoryComplete && row.deleted -> PlanCategory.DELETED_ON_BOTH
            // A live row is deleted only while clean and with a baseline.
            inventoryComplete && (row.dirty || map?.fingerprint == null) -> PlanCategory.CONFLICT
            inventoryComplete -> PlanCategory.DELETED_ON_PROTON
            // Step 5: a phone change over an empty baseline is a conflict, as it is when the contact arrives; then a
            // dirty row without keys is held.
            (row.deleted || row.dirty) && map?.fingerprint == null -> PlanCategory.CONFLICT
            row.deleted -> PlanCategory.DELETED_ON_PHONE
            row.dirty && map?.rowsKeyed != true -> PlanCategory.EDITED_BEFORE_KEYS
            row.dirty -> changedOnPhone(readEdit(row).also { edit = it })
            else -> null
        }
        if (category != null) items += item(category, id, null, row, row.displayName, map, edit)
    }

    // Step 6; a tombstone made and deleted on the phone is ignored. A new row is read back, since its cards are made
    // from what it shows, unless it holds rows a write from Proton after the create would lose.
    rows.filter { it.sourceId.isNullOrBlank() && !it.deleted }.forEach {
        val edit = readEdit(it)
        val category = if (edit.shown.unmappedRows > 0) PlanCategory.UNMAPPED_ROWS else PlanCategory.NEW_ON_PHONE
        items += PlanItem(category, null, null, it, it.displayName, carried(category), edit = edit)
    }

    // A map with nothing left to pair, which only a complete inventory can tell, and only while the guard trusts its
    // absences.
    val stale = if (inventoryComplete && !bulkGuardRemote) maps.map { ProtonContactId(it.protonId) }.filter { it !in remoteIds && it !in bySource } else emptyList()
    return SyncPlan(
        items, repairs, bulkGuardRemote, bulkGuardPhone, stale, phoneDeletionsWaiting = phone, remoteDeletionsWaiting = proton,
        inventory = remoteIds, inventoryComplete = inventoryComplete,
    )
}

/**
 * The categories a real sync carries out, besides the deletions the bulk guard may stop: each contact or row written
 * from Proton, a settled one cleared, an edit uploaded, and a new row created.
 */
private val CARRIED_OUT = setOf(
    PlanCategory.NEW_FROM_PROTON, PlanCategory.CHANGED_ON_PROTON, PlanCategory.WRITTEN_BEFORE_KEYS, PlanCategory.SETTLED, PlanCategory.SETTLED_REMOTE_CHANGED,
    PlanCategory.CHANGED_ON_PHONE, PlanCategory.NEW_ON_PHONE,
)

/**
 * Step 5's settled edit: the phone shows what the remote does, the photo by its baseline, and holds nothing the app
 * can't read back (writing it from the remote would lose that).
 */
private fun settled(edit: PhoneEdit, remote: RemoteContact.Readable, map: ContactMapEntity) =
    edit.shown.unmappedRows == 0 && edit.photo.isWritten(map) && shownEqual(edit.shown.contact.graph, remote.contact)

/** An edit to upload, unless the row holds rows an upload would lose. */
private fun changedOnPhone(edit: PhoneEdit) = if (edit.shown.unmappedRows > 0) PlanCategory.UNMAPPED_ROWS else PlanCategory.CHANGED_ON_PHONE

/** The bulk guard: 10 or more, or at least 20 % of 20 or more mapped contacts. */
private fun stops(deletions: Int, mapped: Int) = deletions >= 10 || (mapped >= 20 && deletions * 5 >= mapped)

/** The name the report lists: `FN`, else the name parts, else the first e-mail, else the first phone. */
private fun displayName(graph: ContactGraph): String? =
    graph.formattedName
        ?: graph.name?.let { listOfNotNull(it.given, it.family).joinToString(" ").ifEmpty { null } }
        ?: graph.emails.firstOrNull()?.value
        ?: graph.phones.firstOrNull()?.value
