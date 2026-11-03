package dev.rboza.android.opensyncforproton.proton

/**
 * What a fetch of every contact found (docs/protocol.md § Contact inventory). It's complete only when [incomplete] is
 * null; [contacts] fetched successfully may be applied either way, but nothing that depends on absence (deletion) may
 * follow an incomplete one.
 */
class Inventory(val contacts: List<ContactDto>, val incomplete: Incomplete?) {
    override fun toString() = "Inventory(contacts=${contacts.size}, incomplete=$incomplete)"
}

/** Why an inventory is incomplete. */
sealed interface Incomplete {
    /** A page added no ID that wasn't already seen. */
    data object RepeatedPage : Incomplete

    /** The `Total` wasn't the same on every page, was negative, or didn't match the unique IDs. */
    data object ContradictoryTotal : Incomplete

    /** 1,000 pages (`Page` 0–999) without reaching the last one. */
    data object PageCap : Incomplete

    /** C2 answered 2501 (doesn't exist) or 2061 (invalid ID) for some contacts; they were skipped. */
    data object ContactsGone : Incomplete

    /** A page or a C2 failed; nothing more was fetched. */
    class Failed(val error: ProtonError) : Incomplete {
        override fun toString() = "Failed($error)"
    }
}

/**
 * Fetches every contact: C3 export pages, or, when export's first page fails with a 404, C1 pages and then C2 for
 * each contact, one request at a time. Paging stops at the first sign of incompleteness, and a failed list page
 * still leaves the IDs it listed to fetch. A C2 answering 2501 or 2061 skips that contact; any other C2 failure ends
 * the fetch. A [ProtonError] is never thrown: it makes the inventory [Incomplete.Failed]. Only a cancellation is
 * thrown.
 */
suspend fun fetchInventory(api: ProtonApi): Inventory {
    val export = pageThrough(ContactDto::id) { page -> api.exportContacts(page, PAGE_SIZE).let { it.contacts to it.total } }
    // Any 404 on the first page means no export, whichever spelling (or a refresh) gave it; a list path that fails the
    // same way stays incomplete.
    val noExport = export.pages == 0 && ((export.incomplete as? Incomplete.Failed)?.error as? ProtonError.Api)?.http == 404
    if (!noExport) return Inventory(export.entries, export.incomplete)

    val listing = pageThrough(ContactIdDto::id) { page -> api.listContacts(page, PAGE_SIZE).let { it.contacts to it.total } }
    // Like any incomplete listing, a failed one still fetches the IDs it has.
    var incomplete = listing.incomplete
    val contacts = mutableListOf<ContactDto>()
    for (entry in listing.entries) {
        val contact = try {
            api.contact(ProtonContactId(entry.id))
        } catch (error: ProtonError) {
            // A contact that's gone is skipped; any other failure stops fetching.
            if (error is ProtonError.Api && error.code in GONE) {
                if (incomplete == null) incomplete = Incomplete.ContactsGone
                continue
            }
            return Inventory(contacts, Incomplete.Failed(error))
        }
        // An answer for another contact can't be mapped to this one.
        if (contact.id != entry.id) return Inventory(contacts, Incomplete.Failed(ProtonError.BadResponse(200)))
        contacts += contact
    }
    return Inventory(contacts, incomplete)
}

/** The contacts C1 lists with a `UID`, and why the listing is incomplete, if it is (then more may exist). */
class UidMatches(val ids: List<ProtonContactId>, val incomplete: Incomplete?) {
    override fun toString() = "UidMatches(ids=${ids.size}, incomplete=$incomplete)"
}

/**
 * The contacts holding [uid], for a create whose outcome is unknown (docs/sync.md § Writes): C1 paged by the
 * inventory's rules, each listed `UID` compared exactly (C1 lists a new contact with its `UID`). Only a complete
 * listing says none. A [ProtonError] isn't thrown but makes the listing [Incomplete.Failed].
 */
suspend fun findByUid(api: ProtonApi, uid: String): UidMatches {
    val listing = pageThrough(ContactIdDto::id) { page -> api.listContacts(page, PAGE_SIZE).let { it.contacts to it.total } }
    return UidMatches(listing.entries.filter { it.uid == uid }.map { ProtonContactId(it.id) }, listing.incomplete)
}

/** One paging's unique entries (first copy kept), how many pages answered, and why it's incomplete, if it is. */
private class Paged<T>(val entries: List<T>, val pages: Int, val incomplete: Incomplete?)

/**
 * Pages from 0 until the listing ends or proves incomplete. A `Total` is trustworthy when it's present, at least 0,
 * and the same on every page: then paging ends on a short or empty page that brings the unique IDs to it. Without
 * one, a short or empty page ends it.
 */
private suspend fun <T> pageThrough(id: (T) -> String, fetch: suspend (page: Int) -> Pair<List<T>, Int?>): Paged<T> {
    val seen = LinkedHashMap<String, T>()
    var total: Int? = null
    for (page in 0 until PAGE_CAP) {
        val (entries, pageTotal) = try {
            fetch(page)
        } catch (error: ProtonError) {
            return Paged(seen.values.toList(), page, Incomplete.Failed(error))
        }
        if (page == 0) total = pageTotal
        val before = seen.size
        for (entry in entries) seen.putIfAbsent(id(entry), entry)
        val short = entries.size < PAGE_SIZE
        val incomplete = when {
            pageTotal != total -> Incomplete.ContradictoryTotal
            entries.isNotEmpty() && seen.size == before -> Incomplete.RepeatedPage
            total == null -> if (short) return Paged(seen.values.toList(), page + 1, null) else null
            // Also a negative Total: no count is below it.
            seen.size > total -> Incomplete.ContradictoryTotal
            // Reached on a full page, the Total could be too low: the next page must come back empty.
            short -> if (seen.size == total) return Paged(seen.values.toList(), page + 1, null) else Incomplete.ContradictoryTotal
            else -> null
        }
        if (incomplete != null) return Paged(seen.values.toList(), page + 1, incomplete)
    }
    return Paged(seen.values.toList(), PAGE_CAP, Incomplete.PageCap)
}

/** Pages hold at most 50: export refuses 100 (`Code` 2003). */
private const val PAGE_SIZE = 50

/** `Page` 0–999; `Page` 1000 is never requested. */
private const val PAGE_CAP = 1000

/** C2's codes for a contact that doesn't exist (2501) or an invalid ID (2061). */
private val GONE = setOf(2501, 2061)
