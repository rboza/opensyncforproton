package dev.rboza.android.opensyncforproton.sync.provider

import dev.rboza.android.opensyncforproton.sync.db.ImportRecordDao

/**
 * Remove originals: each [OriginalState.REMOVABLE] one of [originals] (as read for the list, each once) in turn,
 * deleted by [DeviceContacts.removeOriginal] while it's at its record's `VERSION` and its copy at the `VERSION` read,
 * then its record deleted. One not removed (`Stale`, refused, its reply lost) keeps its record, and the rest go on; a
 * `SecurityException` or [ProviderUnavailableException] ends it, and the records of those removed are deleted already.
 * Only `Written` counts as done, and the others passed don't count.
 */
suspend fun removeOriginals(contacts: DeviceContacts, records: ImportRecordDao, account: PhoneAccount, originals: List<ImportedOriginal>): ActionResult {
    var done = 0
    var notDone = 0
    for (imported in originals.filter { it.state == OriginalState.REMOVABLE }.distinctBy { it.original.rawContactId }) {
        val original = imported.original
        val copy = imported.copy
        val result = contacts.removeOriginal(
            account, ExistingRow(original.rawContactId, imported.recordVersion), checkNotNull(copy.sourceId), ExistingRow(copy.rawContactId, copy.version), copy.starred,
        )
        if (result is ContactWriteResult.Written) {
            records.delete(account.id.value, original.rawContactId)
            done++
        } else {
            notDone++
        }
    }
    return ActionResult(done, notDone)
}
