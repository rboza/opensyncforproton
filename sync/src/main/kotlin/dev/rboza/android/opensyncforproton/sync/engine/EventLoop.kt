package dev.rboza.android.opensyncforproton.sync.engine

import dev.rboza.android.opensyncforproton.account.CredentialStore
import dev.rboza.android.opensyncforproton.account.CredentialStoreException
import dev.rboza.android.opensyncforproton.account.PersistedTokenStore
import dev.rboza.android.opensyncforproton.account.lockedKeys
import dev.rboza.android.opensyncforproton.crypto.KeyHierarchy
import dev.rboza.android.opensyncforproton.proton.EventId
import dev.rboza.android.opensyncforproton.proton.EventPageDto
import dev.rboza.android.opensyncforproton.proton.EventsReply
import dev.rboza.android.opensyncforproton.proton.ProtonApi
import dev.rboza.android.opensyncforproton.proton.ProtonError
import dev.rboza.android.opensyncforproton.proton.TokenStore
import dev.rboza.android.opensyncforproton.sync.db.AccountStateDao
import dev.rboza.android.opensyncforproton.sync.db.Blocker
import dev.rboza.android.opensyncforproton.sync.db.EventCursorDao
import dev.rboza.android.opensyncforproton.sync.db.ReportTrigger
import dev.rboza.android.opensyncforproton.sync.provider.PhoneAccount

/**
 * The incremental run: first the login and keys, which stop it as a failed check while a login is needed; then Proton's
 * core feed (E1, E2) read from the stored cursor, then one full sync for everything the pages show or when one is due,
 * or an upload-only sync when they're quiet. Without a cursor, or after Proton rejects it, E1's cursor follows a full
 * sync instead. A key change, a refresh, or a run without a cursor fetches the keys again before that work. The new
 * cursor is stored after that work unless it ended on a retryable blocker. A failed feed still runs a full sync that's
 * due; otherwise the run ends with its blocker, as a failed check whose report the engine stores. Each full sync's
 * report says what started it: changes on Proton, or the full-sync interval (a run without a cursor included); a quiet
 * feed's upload-only sync, that Proton was checked. [engine] is the app's, so its runs notify and record as any other.
 */
class EventLoop(
    private val engine: SyncEngine,
    private val cursors: EventCursorDao,
    private val accounts: AccountStateDao,
    private val credentials: CredentialStore,
    private val keys: KeyHierarchy,
    private val newClient: (TokenStore) -> ProtonApi,
    private val isOnline: () -> Boolean,
    private val now: () -> Long,
) {
    suspend fun run(account: PhoneAccount): RunReport {
        val id = account.id.value
        // While a login is needed, sync pauses: nothing is read or sent until the user logs in.
        loginBlocker(credentials, keys, account.id)?.let { return engine.failedCheck(account, it) }
        // A device without a network is offline at once, with nothing sent.
        if (!isOnline()) return engine.failedCheck(account, Blocker.OFFLINE)
        val tokens = PersistedTokenStore(account.id, credentials)
        val client = newClient(tokens)
        val stored = cursors.cursor(id)
        val due = fullSyncDue(id, stored?.lastFullSyncAt)
        val feed = try {
            val cursor = stored?.eventId?.let(::EventId)
            val read = cursor?.let { pages(client, it) }
            if (read != null) {
                read
            } else {
                // Rejected (HTTP 400, `Code` 2061): dropped at once. How Proton answers an expired cursor is UNKNOWN
                // (docs/protocol.md § Unknown).
                if (cursor != null) cursors.saveCursor(id, null)
                // Read before the full sync, stored after it: changes made in between are read again next time.
                Fresh(client.latestEventId())
            }
        } catch (error: ProtonError) {
            // The interval's full syncs go on whatever the feed does, and the cursor stays.
            return if (due) scheduled(account) else engine.failedCheck(account, blockerOf(error, isOnline))
        }
        // A full sync reads with the stored keys and fetches none. Keys that can't be fetched or stored count as a
        // failed feed, so the cursor stays even past a lasting blocker, and the next run fetches them again.
        if (feed.keysChanged) fetchKeys(account, client, tokens)?.let { return if (due) scheduled(account) else engine.failedCheck(account, it) }
        val report = when {
            feed is Read && feed.changed -> engine.sync(account, trigger = ReportTrigger.PROTON_CHANGES)
            feed is Fresh || due -> scheduled(account)
            else -> engine.upload(account, ReportTrigger.CHECK)
        }
        // A retryable blocker keeps the cursor, so the job's retry reads the same pages again; a lasting one moves it
        // on, since the next full sync, once due, reads everything.
        if (!report.retryable) cursors.saveCursor(id, feed.next.value)
        return report
    }

    /**
     * No full sync recorded, or the full sync's interval since it has passed (or the clock moved back); Manual is never
     * due by time.
     */
    private suspend fun fullSyncDue(accountId: String, last: Long?): Boolean {
        if (last == null) return true
        val settings = accounts.settings(accountId)?.takeUnless { it.manual } ?: return false
        val interval = settings.intervalMinutes
        val elapsed = now() - last
        return elapsed < 0 || elapsed >= interval * MINUTE
    }

    /**
     * U1's user keys stored in place of the account's, for the session the stored tokens belong to; the store carries
     * the passphrases over by key ID, so a new primary key without one doesn't unlock until the next login. A blocker
     * when that fails, with nothing stored.
     */
    private suspend fun fetchKeys(account: PhoneAccount, client: ProtonApi, tokens: TokenStore): Blocker? {
        // Read before U1, so keys an old session fetched never replace those of a login made meanwhile.
        val uid = tokens.current()?.uid ?: return Blocker.SIGNED_OUT
        val user = try {
            client.user()
        } catch (error: ProtonError) {
            return blockerOf(error, isOnline)
        }
        // As at login, another user's keys are a bad answer.
        if (user.id != account.id.value) return Blocker.PROTON_UNAVAILABLE
        return try {
            credentials.updateKeys(account.id, uid, lockedKeys(user))
            null
        } catch (_: CredentialStoreException) {
            // A login meanwhile, or a write that failed: still signed in, so the job retries.
            Blocker.PROTON_UNAVAILABLE
        }
    }

    private sealed interface Feed {
        val next: EventId
        val keysChanged: Boolean
    }

    /** No cursor to read from: E1's, to store after a full sync. Keys may have changed before it. */
    private class Fresh(override val next: EventId) : Feed {
        override val keysChanged get() = true
    }

    /** The last page's cursor, whether the pages ask for a full sync, and whether for keys fetched again first. */
    private class Read(override val next: EventId, val changed: Boolean, override val keysChanged: Boolean) : Feed

    /**
     * E2 while `More`, up to [MAX_PAGES]; with `More` still set after that, a full sync goes on from the last one read.
     * Null when Proton rejects a cursor.
     */
    private suspend fun pages(client: ProtonApi, from: EventId): Read? {
        var cursor = from
        var changed = false
        var keys = false
        repeat(MAX_PAGES) {
            val page = when (val reply = client.events(cursor)) {
                EventsReply.CursorRejected -> return null
                is EventsReply.Page -> reply.page
            }
            cursor = page.eventId
            changed = changed || page.asksForFullSync()
            keys = keys || page.asksForKeys()
            if (!page.more) return Read(cursor, changed, keys)
        }
        return Read(cursor, changed = true, keys)
    }

    /** Anything else (labels, mail, calendar) changes nothing here. */
    private fun EventPageDto.asksForFullSync() = asksForKeys() || contacts.isNotEmpty() || contactEmails.isNotEmpty()

    /** A key change, or a refresh, which may stand for one. */
    private fun EventPageDto.asksForKeys() = refresh != 0 || userChanged || addressesChanged

    /** The full sync the interval asks for, or the first one, which stores E1's cursor. */
    private suspend fun scheduled(account: PhoneAccount) = engine.sync(account, trigger = ReportTrigger.SCHEDULED)

    private companion object {
        const val MAX_PAGES = 50
        const val MINUTE = 60_000L
    }
}
