package dev.rboza.android.opensyncforproton.sync.engine

import android.Manifest
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.RawContacts
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import dev.rboza.android.opensyncforproton.mapping.EzvcardContactMapper
import dev.rboza.android.opensyncforproton.proton.AuthDto
import dev.rboza.android.opensyncforproton.proton.AuthInfoDto
import dev.rboza.android.opensyncforproton.proton.CaptchaHeaders
import dev.rboza.android.opensyncforproton.proton.CardDto
import dev.rboza.android.opensyncforproton.proton.ContactDto
import dev.rboza.android.opensyncforproton.proton.ContactExportPageDto
import dev.rboza.android.opensyncforproton.proton.ContactListPageDto
import dev.rboza.android.opensyncforproton.proton.CreateReply
import dev.rboza.android.opensyncforproton.proton.EventId
import dev.rboza.android.opensyncforproton.proton.EventsReply
import dev.rboza.android.opensyncforproton.proton.KeySaltDto
import dev.rboza.android.opensyncforproton.proton.ProtonApi
import dev.rboza.android.opensyncforproton.proton.ProtonContactId
import dev.rboza.android.opensyncforproton.proton.SrpAuthDto
import dev.rboza.android.opensyncforproton.proton.Tokens
import dev.rboza.android.opensyncforproton.proton.UserDto
import dev.rboza.android.opensyncforproton.sync.db.ContactMapDao
import dev.rboza.android.opensyncforproton.sync.db.ContactMapEntity
import dev.rboza.android.opensyncforproton.sync.db.SyncDatabase
import dev.rboza.android.opensyncforproton.sync.plan.PlanCategory
import dev.rboza.android.opensyncforproton.sync.provider.PhoneAccount
import dev.rboza.android.opensyncforproton.sync.provider.ProviderDeviceContacts
import dev.rboza.android.opensyncforproton.sync.provider.scalePhoto
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Killed between the provider write and the Room write, the rerun repairs it with exactly one row (docs/sync.md § The
 * plan, step 3's map repair). The real engine, Contacts provider, in-memory Room (it plays the database that outlives
 * the kill), and vCard mapper; Proton, the credentials, and the crypto are stand-ins, with made-up contacts under the
 * `.debug` account type only (D4). A process can't kill itself mid-test, so a map save that throws stands in for the
 * kill: it leaves the provider's row written and its map unsaved, as a kill there would.
 */
@RunWith(AndroidJUnit4::class)
class KilledSyncDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val resolver = instrumentation.targetContext.contentResolver
    private val contacts = ProviderDeviceContacts(resolver, TYPE, localAccountName = null, localAccountType = null)
    private val account = PhoneAccount(AccountId("test-only-user-id"), "test-only@example.test")
    private val db = Room.inMemoryDatabaseBuilder(instrumentation.targetContext, SyncDatabase::class.java).build()

    @Before
    fun setUp() {
        listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS).forEach {
            instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, it)
        }
        clean()
    }

    @After
    fun clean() {
        resolver.delete(RawContacts.CONTENT_URI.asSyncAdapter(), "${RawContacts.ACCOUNT_TYPE}=?", arrayOf(TYPE))
    }

    @After
    fun closeDb() = db.close()

    private fun Uri.asSyncAdapter(): Uri = buildUpon().appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true").build()

    private class Killed : RuntimeException()

    /** Room's maps, except that the first save throws while [killing], after the provider's write it records. */
    private inner class MapsKilledOnce(private val real: ContactMapDao) : ContactMapDao by real {
        var killing = true

        override suspend fun save(map: ContactMapEntity) {
            if (killing) {
                killing = false
                throw Killed()
            }
            real.save(map)
        }
    }

    private fun card(n: Int) = "BEGIN:VCARD\r\nVERSION:4.0\r\nFN:Test Contact $n\r\nUID:test-only-uid-$n\r\nEND:VCARD\r\n"

    private val remote = listOf(1, 2).map { ContactDto("test-only-proton-id-$it", listOf(CardDto(0, card(it), null))) }

    private fun engine(maps: ContactMapDao) = ContactSyncEngine(
        credentials = Store,
        newClient = { Api(remote) },
        keys = Keys,
        crypto = Crypto,
        mapper = EzvcardContactMapper(),
        deviceContacts = contacts,
        maps = maps,
        reports = db.lastReportDao(),
        pendingCreates = db.pendingCreateDao(),
        holds = db.conflictHoldDao(),
        cursors = db.eventCursorDao(),
        photos = { encoded, max -> scalePhoto(encoded, max) },
        isOnline = { true },
        now = { 1_767_225_600_000 },
        versionName = "0.0.0-test",
    )

    @Test
    fun aRunKilledBetweenTheProviderWriteAndItsMapIsRepairedWithExactlyOneRow() = runBlocking {
        val killed = MapsKilledOnce(db.contactMapDao())
        assertThrows(Killed::class.java) { runBlocking { engine(killed).sync(account) } }
        val written = contacts.query(account)
        assertEquals("the first contact's row was written", listOf("test-only-proton-id-1"), written.map { it.sourceId })
        assertEquals("and its map wasn't", emptyList<ContactMapEntity>(), db.contactMapDao().maps(account.id.value))
        assertNull("and nothing ran after the kill point", db.lastReportDao().report(account.id.value))

        val report = engine(db.contactMapDao()).sync(account)

        assertTrue("the rerun is ready", report.ready)
        assertEquals("through map repair", 1, report.plan!!.mapRepairs.size)
        assertEquals("the killed run's contact rewritten, the other new", listOf(1, 1), report.counts().let { listOf(it.changedOnProton, it.newFromProton) })
        val rows = contacts.query(account)
        assertEquals("one row per contact", listOf("test-only-proton-id-1", "test-only-proton-id-2"), rows.map { it.sourceId.orEmpty() }.sorted())
        assertEquals("the killed run's row is kept, not replaced by a second one", written.single().rawContactId, rows.single { it.sourceId == "test-only-proton-id-1" }.rawContactId)
        val maps = db.contactMapDao().maps(account.id.value).associate { it.protonId to it.rawContactId }
        assertEquals("each contact mapped to its row", rows.associate { it.sourceId to it.rawContactId }, maps)

        val next = engine(db.contactMapDao()).dryRun(account)

        assertTrue("the next run is ready", next.ready)
        assertEquals("and has nothing to do", emptyList<PlanCategory>(), next.plan!!.items.map { it.category })
    }

    private object Store : CredentialStore {
        override fun load(account: AccountId) = Credentials(
            Tokens("test-only-uid", "test-only-access", "test-only-refresh"), PasswordMode.ONE, mapOf("test-only-key" to "test-only-salt"),
            LockedKeyBundle(listOf(LockedUserKey("test-only-key", "test-only-armored", active = true, primary = true))),
            mapOf("test-only-key" to "test-only-passphrase".toByteArray()),
        )
        override fun save(account: AccountId, credentials: Credentials) = error("a sync saves no login")
        override fun delete(account: AccountId) = error("a sync deletes no account")
        override fun updateTokens(account: AccountId, tokens: Tokens) = error("no refresh in this test")
        override fun updateKeys(account: AccountId, uid: String, keys: LockedKeyBundle) = error("no key fetch in this test")
        override fun deleteSession(account: AccountId, uid: String) = error("no session ends in this test")
    }

    private object Keys : KeyHierarchy {
        override fun unlock(bundle: LockedKeyBundle, passphrasesByKeyId: Map<String, ByteArray>) =
            CryptoResult.Success(object : UnlockedKeys { override fun close() = Unit })
    }

    /** Type 0 cards are stored in the clear, so the card's text is its vCard. */
    private object Crypto : ItemCrypto {
        override fun decryptContact(keys: UnlockedKeys, cards: List<EncryptedCard>): CryptoResult<List<PlainCard>> =
            CryptoResult.Success(cards.map { PlainCard(it.type, it.data) })

        override fun encryptContact(keys: UnlockedKeys, cards: List<PlainCard>): CryptoResult<List<EncryptedCard>> = error("this test uploads nothing")
    }

    private class Api(private val contacts: List<ContactDto>) : ProtonApi {
        override suspend fun exportContacts(page: Int, pageSize: Int) =
            ContactExportPageDto(contacts.drop(page * pageSize).take(pageSize), total = contacts.size)

        override suspend fun authInfo(username: String): AuthInfoDto = error("not a sync call")
        override suspend fun auth(request: SrpAuthDto, captcha: CaptchaHeaders?): AuthDto = error("not a sync call")
        override suspend fun submitTotp(code: String) = error("not a sync call")
        override suspend fun logout() = error("not a sync call")
        override suspend fun user(): UserDto = error("not a sync call")
        override suspend fun keySalts(): List<KeySaltDto> = error("not a sync call")
        override suspend fun listContacts(page: Int, pageSize: Int): ContactListPageDto = error("export answers in this test")
        override suspend fun contact(id: ProtonContactId): ContactDto = error("export answers in this test")
        override suspend fun createContact(cards: List<CardDto>): CreateReply = error("this test uploads nothing")
        override suspend fun updateContact(id: ProtonContactId, cards: List<CardDto>): Unit = error("this test uploads nothing")
        override suspend fun deleteContact(id: ProtonContactId): Unit = error("this test uploads nothing")
        override suspend fun latestEventId(): EventId = error("the engine never reads events")
        override suspend fun events(cursor: EventId): EventsReply = error("the engine never reads events")
    }

    private companion object {
        const val TYPE = "dev.rboza.android.opensyncforproton.debug"
    }
}
