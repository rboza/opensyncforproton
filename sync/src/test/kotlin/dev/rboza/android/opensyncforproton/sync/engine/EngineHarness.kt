package dev.rboza.android.opensyncforproton.sync.engine

import dev.rboza.android.opensyncforproton.account.AccountId
import dev.rboza.android.opensyncforproton.account.CredentialStore
import dev.rboza.android.opensyncforproton.account.Credentials
import dev.rboza.android.opensyncforproton.account.PasswordMode
import dev.rboza.android.opensyncforproton.crypto.CryptoResult
import dev.rboza.android.opensyncforproton.crypto.EncryptedCard
import dev.rboza.android.opensyncforproton.crypto.ItemCrypto
import dev.rboza.android.opensyncforproton.crypto.KeyHierarchy
import dev.rboza.android.opensyncforproton.crypto.LockedKeyBundle
import dev.rboza.android.opensyncforproton.crypto.LockedUserKey
import dev.rboza.android.opensyncforproton.crypto.PlainCard
import dev.rboza.android.opensyncforproton.crypto.UnlockedKeys
import dev.rboza.android.opensyncforproton.mapping.CardFragment
import dev.rboza.android.opensyncforproton.mapping.CardPlacement
import dev.rboza.android.opensyncforproton.mapping.ContactGraph
import dev.rboza.android.opensyncforproton.mapping.ContactPhoto
import dev.rboza.android.opensyncforproton.mapping.GraphKeys
import dev.rboza.android.opensyncforproton.mapping.KeyedGraph
import dev.rboza.android.opensyncforproton.mapping.ContactMapper
import dev.rboza.android.opensyncforproton.mapping.ContactPatch
import dev.rboza.android.opensyncforproton.mapping.PatchedCard
import dev.rboza.android.opensyncforproton.mapping.PatchedLine
import dev.rboza.android.opensyncforproton.mapping.PhotoUpload
import dev.rboza.android.opensyncforproton.mapping.MappingIssue
import dev.rboza.android.opensyncforproton.mapping.MappingIssueCode
import dev.rboza.android.opensyncforproton.mapping.MappingResult
import dev.rboza.android.opensyncforproton.mapping.SourceKey
import dev.rboza.android.opensyncforproton.proton.AuthDto
import dev.rboza.android.opensyncforproton.proton.AuthInfoDto
import dev.rboza.android.opensyncforproton.proton.CaptchaHeaders
import dev.rboza.android.opensyncforproton.proton.CardDto
import dev.rboza.android.opensyncforproton.proton.ContactDto
import dev.rboza.android.opensyncforproton.proton.ContactExportPageDto
import dev.rboza.android.opensyncforproton.proton.ContactIdDto
import dev.rboza.android.opensyncforproton.proton.ContactListPageDto
import dev.rboza.android.opensyncforproton.proton.CreateReply
import dev.rboza.android.opensyncforproton.proton.EventId
import dev.rboza.android.opensyncforproton.proton.EventsReply
import dev.rboza.android.opensyncforproton.proton.KeySaltDto
import dev.rboza.android.opensyncforproton.proton.ProtonApi
import dev.rboza.android.opensyncforproton.proton.ProtonContactId
import dev.rboza.android.opensyncforproton.proton.ProtonError
import dev.rboza.android.opensyncforproton.proton.SrpAuthDto
import dev.rboza.android.opensyncforproton.proton.TokenStore
import dev.rboza.android.opensyncforproton.proton.Tokens
import dev.rboza.android.opensyncforproton.proton.UserDto
import dev.rboza.android.opensyncforproton.sync.db.Blocker
import dev.rboza.android.opensyncforproton.sync.db.ConflictHoldDao
import dev.rboza.android.opensyncforproton.sync.db.ConflictHoldEntity
import dev.rboza.android.opensyncforproton.sync.db.ContactMapDao
import dev.rboza.android.opensyncforproton.sync.db.ContactMapEntity
import dev.rboza.android.opensyncforproton.sync.db.EventCursorDao
import dev.rboza.android.opensyncforproton.sync.db.EventCursorEntity
import dev.rboza.android.opensyncforproton.sync.db.HoldChoice
import dev.rboza.android.opensyncforproton.sync.db.HoldKind
import dev.rboza.android.opensyncforproton.sync.db.LastReportDao
import dev.rboza.android.opensyncforproton.sync.db.LastReportEntity
import dev.rboza.android.opensyncforproton.sync.db.PendingContactCreateEntity
import dev.rboza.android.opensyncforproton.sync.db.PendingCreateDao
import dev.rboza.android.opensyncforproton.sync.db.ReportKind
import dev.rboza.android.opensyncforproton.sync.provider.DeviceContact
import dev.rboza.android.opensyncforproton.sync.provider.DeviceContacts
import dev.rboza.android.opensyncforproton.sync.provider.ContactWrite
import dev.rboza.android.opensyncforproton.sync.provider.DataRow
import dev.rboza.android.opensyncforproton.sync.provider.ExistingRow
import dev.rboza.android.opensyncforproton.sync.provider.ContactWriteResult
import dev.rboza.android.opensyncforproton.sync.provider.PhoneAccount
import dev.rboza.android.opensyncforproton.sync.provider.PhotoScaler
import dev.rboza.android.opensyncforproton.sync.provider.ScaledPhoto
import dev.rboza.android.opensyncforproton.sync.provider.dataRows
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** The engine with every dependency faked, shared by the dry-run and sync tests. */
abstract class EngineHarness {
    protected val account = PhoneAccount(AccountId("user-id-1"), "test-only@example.test")
    protected val calls = mutableListOf<String>()

    protected val store = FakeCredentialStore()
    protected val api = FakeApi()
    protected val keys = FakeKeys()
    protected val crypto = FakeCrypto()
    protected val mapper = FakeMapper()
    protected val phone = FakeDeviceContacts()
    protected val mapDao = FakeMaps()
    protected val reportDao = FakeReports()
    protected val photos = FakePhotos()
    protected val pending = FakePendingCreates()
    protected val holdDao = FakeHolds()
    protected val cursorDao = FakeCursors()

    /** Everything that happened, in order, across the fakes that write (map saves, provider writes). */
    protected val events = mutableListOf<String>()
    protected var online = true
    protected var store_given: TokenStore? = null

    /** The UIDs handed out so far; each is `urn:uuid:test-<n>`, counting from 1. */
    protected var uidsMade = 0

    protected fun engine() = ContactSyncEngine(
        credentials = store,
        newClient = { tokens -> store_given = tokens; api },
        keys = keys,
        crypto = crypto,
        mapper = mapper,
        deviceContacts = phone,
        maps = mapDao,
        reports = reportDao,
        pendingCreates = pending,
        holds = holdDao,
        cursors = cursorDao,
        photos = photos,
        isOnline = { online },
        now = { 1_767_225_600_000 },
        versionName = "0.2.0-test",
        newUid = { "urn:uuid:test-${++uidsMade}" },
    )

    /** A made-up photo hash for a raw contact: 64 hex digits, like a real one. */
    protected fun photoHashOf(rawContactId: Long) = "%064x".format(rawContactId)

    /** A made-up hash of a raw contact's photo thumbnail, unlike any [photoHashOf]. */
    protected fun thumbnailHashOf(rawContactId: Long) = "e" + "%063x".format(rawContactId)

    protected fun contact(n: Int, vararg types: Int = intArrayOf(1)) =
        ContactDto("proton-id-$n", types.map { CardDto(it, "data-$n-$it", if (it >= 2) "signature-$n" else null) })

    protected fun zeroCounts(blocker: Blocker, kind: ReportKind = ReportKind.DRY_RUN) = LastReportEntity(
        "user-id-1", kind, 1_767_225_600_000, ready = false, blocker = blocker, newFromProton = 0, changedOnProton = 0,
        deletedOnProton = 0, newOnPhone = 0, changedOnPhone = 0, deletedOnPhone = 0, conflicts = 0, bulkGuardRemote = false,
        bulkGuardPhone = false, unreadable = 0, duplicateRows = 0, missingRows = 0,
    )

    inner class FakeCredentialStore : CredentialStore {
        var credentials: (() -> Credentials)? = {
            Credentials(
                Tokens("uid-1", "access-1", "refresh-1"), PasswordMode.ONE, mapOf("key-1" to "salt-1"),
                LockedKeyBundle(listOf(LockedUserKey("key-1", "armored-1", active = true, primary = true))),
                mapOf("key-1" to "passphrase-1".toByteArray()),
            )
        }
        val loaded = mutableListOf<Credentials>()

        val updated = mutableListOf<Pair<AccountId, Tokens>>()
        val sessionsDeleted = mutableListOf<Pair<AccountId, String>>()

        /** Answers each load in place of [credentials] when set. */
        var loadAnswer: (() -> Credentials?)? = null

        override fun load(account: AccountId): Credentials? =
            if (account != AccountId("user-id-1")) null else (loadAnswer?.invoke() ?: credentials?.takeIf { loadAnswer == null }?.invoke())?.also { loaded += it }
        override fun save(account: AccountId, credentials: Credentials) = error("a dry run saves no new login")
        override fun delete(account: AccountId) = error("a dry run deletes no account")
        override fun updateTokens(account: AccountId, tokens: Tokens) {
            updated += account to tokens
        }
        /** What updateKeys stored: the session's UID, and each key's ID, armored text, active, and primary. */
        val keysUpdated = mutableListOf<Pair<String, List<List<Any>>>>()

        /** Thrown by updateKeys in place of storing. */
        var keysFailure: Exception? = null

        override fun updateKeys(account: AccountId, uid: String, keys: LockedKeyBundle) {
            calls += "keys saved"
            keysFailure?.let { throw it }
            keysUpdated += uid to keys.userKeys.map { listOf(it.id, it.armoredPrivateKey, it.active, it.primary) }
        }
        override fun deleteSession(account: AccountId, uid: String) {
            sessionsDeleted += account to uid
            // As the real store does: the envelope goes when it holds that session.
            if (uid == "uid-1") credentials = null
        }
    }

    inner class FakeApi : ProtonApi {
        val pages = mutableListOf<ContactDto>()
        var failure: ProtonError? = null
        var repeatFirstPage = false
        var cancel = false
        var onExport: (TokenStore) -> Unit = {}

        override suspend fun exportContacts(page: Int, pageSize: Int): ContactExportPageDto {
            calls += "C3 $page"
            onExport(store_given!!)
            if (cancel) throw CancellationException()
            if (page == 0) {
                if (pages.isEmpty()) failure?.let { throw it }
                return ContactExportPageDto(pages.take(pageSize), total = if (repeatFirstPage) 1000 else pages.size)
            }
            failure?.let { throw it }
            if (repeatFirstPage) return ContactExportPageDto(pages.take(pageSize), total = 1000)
            return ContactExportPageDto(pages.drop(page * pageSize).take(pageSize), total = pages.size)
        }

        override suspend fun authInfo(username: String): AuthInfoDto = error("not a sync call")
        override suspend fun auth(request: SrpAuthDto, captcha: CaptchaHeaders?): AuthDto = error("not a sync call")
        override suspend fun submitTotp(code: String) = error("not a sync call")
        override suspend fun logout() = error("not a sync call")
        /** U1's answer: a UserDto, or a ProtonError thrown. */
        var userAnswer: () -> Any = { error("not a sync call") }

        override suspend fun user(): UserDto {
            calls += "U1"
            return when (val answer = userAnswer()) {
                is ProtonError -> throw answer
                else -> answer as UserDto
            }
        }
        override suspend fun keySalts(): List<KeySaltDto> = error("not a sync call")
        /** C1's listing, which only a create's UID lookup reads here; a failure is thrown instead. */
        val listed = mutableListOf<ContactIdDto>()
        var listFailure: ProtonError? = null

        override suspend fun listContacts(page: Int, pageSize: Int): ContactListPageDto {
            calls += "C1 $page"
            listFailure?.let { throw it }
            return ContactListPageDto(listed.drop(page * pageSize).take(pageSize), total = listed.size)
        }

        /**
         * C2's answers by Proton ID, in turn (a ContactDto, or a ProtonError thrown); then the contact as Proton keeps
         * it, or, for one it doesn't keep, 2501 as Proton answers.
         */
        val refetches = mutableMapOf<String, ArrayDeque<Any>>()

        override suspend fun contact(id: ProtonContactId): ContactDto {
            calls += "C2 ${id.value.substringAfterLast('-')}"
            when (val answer = refetches[id.value]?.removeFirstOrNull()) {
                null -> Unit
                is ProtonError -> throw answer
                else -> return answer as ContactDto
            }
            return pages.firstOrNull { it.id == id.value } ?: throw ProtonError.Api(422, 2501)
        }

        /** What C5 sent, by Proton ID; a failure by Proton ID is thrown instead. */
        val sent = mutableListOf<Pair<String, List<CardDto>>>()
        val updateFailures = mutableMapOf<String, ProtonError>()

        /** What C4 sent, and the pending records saved by then; then the answers in turn, else Created. */
        val created = mutableListOf<List<CardDto>>()
        val recordsWhenCreated = mutableListOf<List<PendingContactCreateEntity>>()
        val createAnswers = ArrayDeque<Any>()

        /**
         * Created answers with `proton-id-<100 + n>` for the n-th create, stored as Proton keeps it: its cards' data
         * `data-<id>-<type>-created`, so it reads back as the fake mapper's contact of that number.
         */
        override suspend fun createContact(cards: List<CardDto>): CreateReply {
            calls += "C4"
            created += cards
            recordsWhenCreated += pending.records.toList()
            when (val answer = createAnswers.removeFirstOrNull()) {
                null -> Unit
                is ProtonError -> throw answer
                else -> return answer as CreateReply
            }
            val n = 100 + created.size
            pages += ContactDto("proton-id-$n", cards.map { CardDto(it.type, "data-$n-${it.type}-created", it.signature) })
            return CreateReply.Created(ProtonContactId("proton-id-$n"))
        }

        /** As Proton does, it stores cards that differ from the sent ones, so the contact's fingerprint moves. */
        override suspend fun updateContact(id: ProtonContactId, cards: List<CardDto>) {
            val n = id.value.substringAfterLast('-')
            calls += "C5 $n"
            updateFailures[id.value]?.let { throw it }
            sent += id.value to cards
            val stored = ContactDto(id.value, cards.map { CardDto(it.type, "data-$n-${it.type}-uploaded", it.signature) })
            val index = pages.indexOfFirst { it.id == id.value }
            if (index >= 0) pages[index] = stored else pages += stored
        }
        /**
         * What C6 sent, in turn; a failure by Proton ID is thrown instead, else the contact is gone from [pages], and
         * [onDelete] is called.
         */
        val deleted = mutableListOf<String>()
        val deleteFailures = mutableMapOf<String, ProtonError>()
        var onDelete: (String) -> Unit = {}

        override suspend fun deleteContact(id: ProtonContactId) {
            calls += "C6 ${id.value.substringAfterLast('-')}"
            deleted += id.value
            deleteFailures[id.value]?.let { throw it }
            pages.removeAll { it.id == id.value }
            onDelete(id.value)
        }
        /** E1's answers in turn: an EventId, or a ProtonError thrown. */
        val latest = ArrayDeque<Any>()

        /** E2's answers by cursor: an EventsReply, or a ProtonError thrown; [onEvents] is called first. */
        val feed = mutableMapOf<String, Any>()
        var onEvents: () -> Unit = {}

        override suspend fun latestEventId(): EventId {
            calls += "E1"
            return when (val answer = latest.removeFirst()) {
                is ProtonError -> throw answer
                else -> answer as EventId
            }
        }

        override suspend fun events(cursor: EventId): EventsReply {
            calls += "E2 ${cursor.value}"
            onEvents()
            return when (val answer = feed.getValue(cursor.value)) {
                is ProtonError -> throw answer
                else -> answer as EventsReply
            }
        }
    }

    inner class FakeKeys : KeyHierarchy {
        var result: CryptoResult<UnlockedKeys>? = null
        var failure: Exception? = null
        var passphrasesSeen = emptyMap<String, String>()
        var unlocks = 0
        var open = 0

        /** Whether unlocking and closing are logged in [events] too. */
        var logged = false

        /** An answer for the second unlock only, in place of success. */
        var secondUnlock: CryptoResult<UnlockedKeys>? = null

        /** Every set of keys unlocked so far is closed again. */
        val closed: Boolean get() = unlocks > 0 && open == 0

        override fun unlock(bundle: LockedKeyBundle, passphrasesByKeyId: Map<String, ByteArray>): CryptoResult<UnlockedKeys> {
            passphrasesSeen = passphrasesByKeyId.mapValues { String(it.value) }
            failure?.let { throw it }
            if (unlocks == 1) secondUnlock?.let { unlocks++; return it }
            return result ?: CryptoResult.Success(
                object : UnlockedKeys {
                    override fun close() {
                        open--
                        if (logged) events += "keys closed"
                    }
                },
            ).also {
                unlocks++
                open++
                if (logged) events += "keys unlocked"
            }
        }
    }

    class FakeCrypto : ItemCrypto {
        val results = mutableMapOf<String, CryptoResult<List<PlainCard>>>()
        var decrypted = 0
        var onDecrypt: (Int) -> Unit = {}

        override fun decryptContact(keys: UnlockedKeys, cards: List<EncryptedCard>): CryptoResult<List<PlainCard>> {
            onDecrypt(++decrypted)
            val id = cards.first().data.split("-")[1]
            return results["proton-id-$id"] ?: CryptoResult.Success(cards.map { PlainCard(it.type, "plain(${it.data})") })
        }

        /** The cards given to encrypt, in turn; the answer a test sets, else each card "encrypted", 2 and 3 signed. */
        val encrypted = mutableListOf<List<PlainCard>>()
        var encryptResult: CryptoResult<List<EncryptedCard>>? = null

        override fun encryptContact(keys: UnlockedKeys, cards: List<PlainCard>): CryptoResult<List<EncryptedCard>> {
            encrypted += cards
            return encryptResult ?: CryptoResult.Success(cards.map { EncryptedCard(it.type, "encrypted(${it.text})", if (it.type >= 2) "signature" else null) })
        }
    }

    class FakeMapper : ContactMapper {
        val placements = mutableListOf<List<CardPlacement>>()
        val texts = mutableListOf<List<String>>()
        val invalid = mutableSetOf<String>()
        var failure: Exception? = null

        /** Photos by contact number, as the vCard would hold them. */
        val photos = mutableMapOf<String, ContactPhoto>()

        /** The name's key by contact number; without one, no value has a key. */
        val nameKeys = mutableMapOf<String, SourceKey>()

        /** A contact's graph by its first card's text, in place of the usual one. */
        val graphs = mutableMapOf<String, ContactGraph>()

        override fun merge(cards: List<CardFragment>): MappingResult<KeyedGraph> {
            failure?.let { throw it }
            placements += cards.map { it.placement }
            texts += cards.map { it.vcard }
            if (cards.any { it.vcard in invalid }) return MappingResult.Invalid(listOf(MappingIssue(MappingIssueCode.MALFORMED_INPUT)))
            val id = cards.first().vcard.removePrefix("plain(data-").substringBefore("-")
            val graph = graphs[cards.first().vcard] ?: ContactGraph(uid = null, formattedName = "Contact proton-id-$id", photo = photos[id])
            return MappingResult.Success(KeyedGraph(graph, GraphKeys(name = nameKeys[id])))
        }

        /** What each patch was given; an issue by contact number fails it. */
        val patched = mutableListOf<Triple<KeyedGraph, List<CardFragment>, PhotoUpload>>()
        val patchIssues = mutableMapOf<String, MappingIssueCode>()

        /** Each card patched in place, as "patched(<its text>)", keeping its placement. */
        override fun patch(phone: KeyedGraph, remote: List<CardFragment>, photo: PhotoUpload): MappingResult<ContactPatch> {
            patched += Triple(phone, remote, photo)
            val id = remote.first().vcard.removePrefix("plain(data-").substringBefore("-")
            patchIssues[id]?.let { return MappingResult.Invalid(listOf(MappingIssue(it))) }
            return MappingResult.Success(ContactPatch(remote.map { PatchedCard(it.placement, "4.0", listOf(PatchedLine("patched(${it.vcard})", true))) }, changed = true))
        }

        /** What a create was given; the photo as a list, so it compares by content. */
        data class Created(val phone: ContactGraph, val uid: String, val versionName: String, val photo: List<Byte>?)

        val created = mutableListOf<Created>()

        /** An issue by the phone's display name fails its create. */
        val createIssues = mutableMapOf<String?, MappingIssueCode>()

        /** Called after each create is recorded, with how many there have been. */
        var onCreate: (Int) -> Unit = {}

        /** A signed card naming the UID and an encrypted one naming the phone's display name. */
        override fun create(phone: ContactGraph, uid: String, versionName: String, photo: ByteArray?): MappingResult<List<CardFragment>> {
            created += Created(phone, uid, versionName, photo?.toList())
            onCreate(created.size)
            createIssues[phone.formattedName]?.let { return MappingResult.Invalid(listOf(MappingIssue(it))) }
            return MappingResult.Success(listOf(CardFragment(CardPlacement.SIGNED, "created($uid)"), CardFragment(CardPlacement.ENCRYPTED, "created(${phone.formattedName})")))
        }
    }

    inner class FakeDeviceContacts : DeviceContacts {
        val rows = mutableListOf<DeviceContact>()
        var failure: Exception? = null
        val keysClosedWhenQueried = mutableListOf<Boolean>()
        val writes = mutableListOf<ContactWrite>()

        /** Results by Proton ID; a write without one is Written, a new contact getting raw ID 1000 + its number. */
        val results = mutableMapOf<String, ContactWriteResult>()
        var applyFailure: Exception? = null
        var onApply: (ContactWrite) -> Unit = {}
        var maxDimensionReads = 0
        var maxDimensionFailure: Exception? = null

        override fun query(account: PhoneAccount): List<DeviceContact> {
            keysClosedWhenQueried += keys.closed
            failure?.let { throw it }
            return rows.toList()
        }

        /** What each new contact's insert came to, after the guard below. */
        val insertResults = mutableListOf<ContactWriteResult>()

        override fun apply(account: PhoneAccount, writes: List<ContactWrite>): List<ContactWriteResult> = writes.map { write ->
            onApply(write)
            applyFailure?.let { throw it }
            // As the provider's guard does: no new row for a Proton ID a row already holds, or was just given.
            if (write.existing == null) {
                val held = rows.any { it.sourceId == write.protonId } || bound.any { it.first == write.protonId } ||
                    this.writes.any { it.existing?.bind == true && it.protonId == write.protonId && results[it.protonId] == null }
                if (held) return@map ContactWriteResult.Stale.also { insertResults += it }
            }
            this.writes += write
            events += "apply ${write.protonId}"
            (results[write.protonId] ?: ContactWriteResult.Written(write.existing?.rawContactId ?: (1000L + write.protonId.substringAfterLast("-").toLong())))
                .also { if (write.existing == null) insertResults += it }
        }

        /** Settled rows whose DIRTY was cleared, with the row guarding each; results by Proton ID, else Written. */
        val cleared = mutableListOf<Pair<String, ExistingRow>>()
        val clearResults = mutableMapOf<String, ContactWriteResult>()

        override fun clearDirty(account: PhoneAccount, protonId: String, existing: ExistingRow): ContactWriteResult {
            cleared += protonId to existing
            events += "clear dirty $protonId"
            return clearResults[protonId] ?: ContactWriteResult.Written(existing.rawContactId)
        }

        /** Rows bound to a Proton ID alone, in turn; results by Proton ID, else Written. */
        val bound = mutableListOf<Pair<String, Long>>()
        val bindResults = mutableMapOf<String, ContactWriteResult>()

        var onBind: (String) -> Unit = {}

        override fun bind(account: PhoneAccount, protonId: String, rawContactId: Long): ContactWriteResult {
            onBind(protonId)
            bound += protonId to rawContactId
            events += "bind $protonId"
            return bindResults[protonId] ?: ContactWriteResult.Written(rawContactId)
        }

        /**
         * Tombstones removed, in turn; results by Proton ID, else as the provider's guard decides: the account's
         * tombstone holding the Proton ID is gone (Written), and anything else is Stale.
         */
        val removed = mutableListOf<Pair<String, Long>>()
        val removeResults = mutableMapOf<String, ContactWriteResult>()

        override fun removeTombstone(account: PhoneAccount, protonId: String, rawContactId: Long): ContactWriteResult {
            removed += protonId to rawContactId
            events += "remove $protonId"
            removeResults[protonId]?.let { return it }
            val tombstone = rows.firstOrNull { it.rawContactId == rawContactId && it.deleted && it.sourceId == protonId } ?: return ContactWriteResult.Stale
            rows.remove(tombstone)
            return ContactWriteResult.Written(rawContactId)
        }

        /**
         * Rows deleted because Proton's contact is gone, in turn, with the row each was guarded by; results by Proton
         * ID, else as the provider's guard decides: the account's live row holding the Proton ID at the VERSION read,
         * clean unless the row says it may be dirty, is gone (Written), and anything else is Stale.
         */
        val rowsRemoved = mutableListOf<Pair<String, ExistingRow>>()
        val removeRowResults = mutableMapOf<String, ContactWriteResult>()
        var onRemoveRow: (String) -> Unit = {}

        override fun removeRow(account: PhoneAccount, protonId: String, existing: ExistingRow): ContactWriteResult {
            rowsRemoved += protonId to existing
            events += "remove row $protonId"
            onRemoveRow(protonId)
            removeRowResults[protonId]?.let { return it }
            val row = rows.firstOrNull {
                it.rawContactId == existing.rawContactId && it.sourceId == protonId && it.version == existing.version && (existing.clearDirty || !it.dirty) && !it.deleted
            } ?: return ContactWriteResult.Stale
            rows.remove(row)
            return ContactWriteResult.Written(existing.rawContactId)
        }

        /**
         * Rows given up by their Proton ID, in turn, with the row each was guarded by; results by Proton ID, else as
         * the provider's guard decides: the account's live row holding the Proton ID at the VERSION read, dirty or not,
         * loses it at that VERSION (Written), and anything else is Stale.
         */
        val detached = mutableListOf<Pair<String, ExistingRow>>()
        val detachResults = mutableMapOf<String, ContactWriteResult>()

        override fun detach(account: PhoneAccount, protonId: String, existing: ExistingRow): ContactWriteResult {
            detached += protonId to existing
            events += "detach $protonId"
            detachResults[protonId]?.let { return it }
            val index = rows.indexOfFirst { it.rawContactId == existing.rawContactId && it.sourceId == protonId && it.version == existing.version && !it.deleted }
            if (index < 0) return ContactWriteResult.Stale
            rows[index] = rows[index].copy(sourceId = null)
            return ContactWriteResult.Written(existing.rawContactId)
        }

        override fun maxPhotoDimension(): Int {
            maxDimensionReads++
            maxDimensionFailure?.let { throw it }
            return 720
        }

        /** Photo hashes by raw ID (null: no photo); a raw ID not listed hashes as [photoHashOf] it. */
        val photoHashes = mutableMapOf<Long, String?>()
        var onPhotoHash: (Long) -> Unit = {}

        /** Raw IDs edited or deleted on the phone since the app wrote them. */
        val touched = mutableSetOf<Long>()
        var untouchedFailure: Exception? = null

        override fun photoHash(rawContactId: Long): String? {
            events += "read back $rawContactId"
            onPhotoHash(rawContactId)
            return if (rawContactId in photoHashes) photoHashes[rawContactId] else photoHashOf(rawContactId)
        }

        /**
         * Thumbnail hashes by raw ID (null: no photo); a raw ID not listed hashes as [thumbnailHashOf] it, or has none
         * when [photoHashes] lists it without a photo.
         */
        val thumbnailHashes = mutableMapOf<Long, String?>()
        var onThumbnailHash: (Long) -> Unit = {}

        override fun thumbnailHash(rawContactId: Long): String? {
            onThumbnailHash(rawContactId)
            return when {
                rawContactId in thumbnailHashes -> thumbnailHashes[rawContactId]
                rawContactId in photoHashes && photoHashes[rawContactId] == null -> null
                else -> thumbnailHashOf(rawContactId)
            }
        }

        /** Photo bytes by raw ID; a raw ID not listed has the one byte of its raw ID. */
        val photoBytes = mutableMapOf<Long, ByteArray?>()
        var photoFailure: Exception? = null

        override fun photo(rawContactId: Long): ByteArray? {
            events += "photo $rawContactId"
            photoFailure?.let { throw it }
            return if (rawContactId in photoBytes) photoBytes[rawContactId] else byteArrayOf(rawContactId.toByte())
        }

        override fun untouched(rawContactId: Long): Boolean {
            untouchedFailure?.let { throw it }
            return rawContactId !in touched
        }

        /** Data rows by raw ID; a raw ID not listed shows an edit no remote contact has. */
        val dataRowsByRaw = mutableMapOf<Long, List<DataRow>>()
        var readRowsFailure: Exception? = null

        override fun readRows(rawContactId: Long): List<DataRow> {
            readRowsFailure?.let { throw it }
            return dataRowsByRaw[rawContactId] ?: dataRows(ContactGraph(uid = null, formattedName = "Edited on the phone"), null)
        }

        // The engine never deletes an account's rows; only the account lifecycle does.

        override fun deleteAccountRows(accountName: String): Int = throw UnsupportedOperationException()

        override fun insertLocal(rows: List<DataRow>, starred: Boolean): ContactWriteResult = error("a sync never writes to the local account")
        override fun queryLocal(): List<DeviceContact> = error("a sync never reads the local account")
        override fun insertImport(account: PhoneAccount, original: ExistingRow, rows: List<DataRow>, starred: Boolean): ContactWriteResult =
            error("a sync never imports")
        override fun rewrite(account: PhoneAccount, protonId: String, existing: ExistingRow, original: ExistingRow, rows: List<DataRow>, keepPhotoRow: Boolean): ContactWriteResult = error("a sync never merges")
        override fun removeOriginal(account: PhoneAccount, original: ExistingRow, protonId: String, copy: ExistingRow, copyStarred: Boolean): ContactWriteResult = error("a sync never removes originals")

        override fun accountNamesWithRows(): Set<String> = throw UnsupportedOperationException()
    }

    inner class FakeMaps : ContactMapDao {
        val maps = mutableListOf<ContactMapEntity>()
        val asked = mutableListOf<String>()

        override suspend fun maps(accountId: String): List<ContactMapEntity> = maps.toList().also { asked += accountId }

        /** As Room's suspending DAO calls do, a cancelled caller gets a CancellationException and nothing is saved. */
        override suspend fun save(map: ContactMapEntity) {
            currentCoroutineContext().ensureActive()
            events += "map ${map.protonId}"
            maps.removeAll { it.accountId == map.accountId && it.protonId == map.protonId }
            maps += map
        }

        override suspend fun delete(accountId: String, protonId: String) {
            currentCoroutineContext().ensureActive()
            events += "map deleted $protonId"
            maps.removeAll { it.accountId == accountId && it.protonId == protonId }
        }

        override suspend fun saveAll(maps: List<ContactMapEntity>) {
            events += "repairs ${maps.map { it.protonId }}"
            maps.forEach { map ->
                this.maps.removeAll { it.accountId == map.accountId && it.protonId == map.protonId }
                this.maps += map
            }
        }
    }

    /** Scales by reversing the bytes, so a test can tell scaled from original; or answers what a test sets. */
    class FakePhotos : PhotoScaler {
        val calls = mutableListOf<Pair<List<Byte>, Int>>()
        var result: ScaledPhoto? = null

        override fun scale(encoded: ByteArray, maxDimension: Int): ScaledPhoto {
            calls += encoded.toList() to maxDimension
            return result ?: ScaledPhoto.Jpeg(encoded.reversedArray())
        }
    }

    /** Room's pending-create table: a record is never replaced. */
    inner class FakePendingCreates : PendingCreateDao {
        val records = mutableListOf<PendingContactCreateEntity>()

        override suspend fun records(accountId: String) = records.filter { it.accountId == accountId }

        override suspend fun save(record: PendingContactCreateEntity) {
            currentCoroutineContext().ensureActive()
            check(records.none { it.accountId == record.accountId && (it.rawContactId == record.rawContactId || it.uid == record.uid) }) { "a record is never replaced" }
            events += "record ${record.rawContactId}"
            records += record
        }

        override suspend fun delete(accountId: String, rawContactId: Long) {
            events += "record deleted $rawContactId"
            records.removeAll { it.accountId == accountId && it.rawContactId == rawContactId }
        }
    }

    /**
     * Room's conflict_hold table, by its DAO's rules: a hold found keeps its choice only while its kind, row, VERSION,
     * and (when the remote was seen) fingerprint are the ones recorded, and keeps its fingerprint when the remote
     * wasn't seen.
     */
    inner class FakeHolds : ConflictHoldDao {
        val holds = mutableListOf<ConflictHoldEntity>()

        /** Run after each read, numbered from 1, once its result is taken: a choice the user makes meanwhile. */
        var onHolds: (Int) -> Unit = {}
        private var reads = 0

        override suspend fun holds(accountId: String) = holds.filter { it.accountId == accountId }.also { onHolds(++reads) }

        override suspend fun found(accountId: String, protonId: String, rawContactId: Long, kind: HoldKind, version: Long?, fingerprint: String?, remoteSeen: Boolean) {
            currentCoroutineContext().ensureActive()
            events += "hold $protonId"
            val old = holds.firstOrNull { it.accountId == accountId && it.protonId == protonId }
            val same = old != null && old.kind == kind && old.rawContactId == rawContactId && old.version == version && (!remoteSeen || old.fingerprint == fingerprint)
            holds.remove(old)
            holds += ConflictHoldEntity(accountId, protonId, rawContactId, kind, old?.choice?.takeIf { same }, version, if (remoteSeen) fingerprint else old?.fingerprint)
        }

        override suspend fun choose(accountId: String, protonId: String, kind: HoldKind, rawContactId: Long, version: Long?, fingerprint: String?, choice: HoldChoice?): Int {
            val index = holds.indexOfFirst {
                it.accountId == accountId && it.protonId == protonId && it.kind == kind && it.rawContactId == rawContactId && it.version == version && it.fingerprint == fingerprint
            }
            if (index < 0) return 0
            holds[index] = holds[index].copy(choice = choice)
            return 1
        }

        override suspend fun delete(accountId: String, protonId: String) {
            currentCoroutineContext().ensureActive()
            events += "hold deleted $protonId"
            holds.removeAll { it.accountId == accountId && it.protonId == protonId }
        }
    }

    /** The account's event-loop row, as Room keeps it: each write sets one column and keeps the other. */
    class FakeCursors : EventCursorDao {
        val rows = mutableMapOf<String, EventCursorEntity>()

        override suspend fun cursor(accountId: String) = rows[accountId]

        override suspend fun saveCursor(accountId: String, eventId: String?) {
            rows[accountId] = (rows[accountId] ?: EventCursorEntity(accountId, null, null)).copy(eventId = eventId)
        }

        override suspend fun fullSyncFinished(accountId: String, at: Long) {
            rows[accountId] = (rows[accountId] ?: EventCursorEntity(accountId, null, null)).copy(lastFullSyncAt = at)
        }
    }

    class FakeReports : LastReportDao {
        val saved = mutableListOf<LastReportEntity>()

        override suspend fun save(report: LastReportEntity) {
            saved += report
        }

        override suspend fun report(accountId: String) = saved.lastOrNull { it.accountId == accountId }
    }
}
