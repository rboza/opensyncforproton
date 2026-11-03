package dev.rboza.android.opensyncforproton

import android.Manifest
import android.accounts.AccountManager
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Photo
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.proton.gopenpgp.srp.Server
import com.proton.gopenpgp.srp.Srp
import dev.rboza.android.opensyncforproton.account.AccountId
import dev.rboza.android.opensyncforproton.account.LoginStep
import dev.rboza.android.opensyncforproton.crypto.CryptoResult
import dev.rboza.android.opensyncforproton.crypto.EncryptedCard
import dev.rboza.android.opensyncforproton.crypto.GoItemCrypto
import dev.rboza.android.opensyncforproton.crypto.GoKeyHierarchy
import dev.rboza.android.opensyncforproton.crypto.GoSrpClient
import dev.rboza.android.opensyncforproton.crypto.LockedKeyBundle
import dev.rboza.android.opensyncforproton.crypto.LockedUserKey
import dev.rboza.android.opensyncforproton.crypto.PlainCard
import dev.rboza.android.opensyncforproton.mapping.ContactGraph
import dev.rboza.android.opensyncforproton.mapping.EmailType
import dev.rboza.android.opensyncforproton.mapping.PhoneType
import dev.rboza.android.opensyncforproton.mapping.StructuredName as Name
import dev.rboza.android.opensyncforproton.mapping.TypedEmail
import dev.rboza.android.opensyncforproton.mapping.TypedPhone
import dev.rboza.android.opensyncforproton.proton.CardDto
import dev.rboza.android.opensyncforproton.proton.ProtonConfig
import dev.rboza.android.opensyncforproton.proton.ProtonContactId
import dev.rboza.android.opensyncforproton.proton.ProtonTrust
import dev.rboza.android.opensyncforproton.proton.contactFingerprint
import dev.rboza.android.opensyncforproton.sync.db.Blocker
import dev.rboza.android.opensyncforproton.sync.db.ImportRecordDao
import dev.rboza.android.opensyncforproton.sync.db.ImportRecordEntity
import dev.rboza.android.opensyncforproton.sync.db.SyncMode
import dev.rboza.android.opensyncforproton.sync.engine.SyncRequest
import dev.rboza.android.opensyncforproton.sync.lifecycle.LoginCommit
import dev.rboza.android.opensyncforproton.sync.lifecycle.RemovalResult
import dev.rboza.android.opensyncforproton.sync.provider.ContactWriteResult
import dev.rboza.android.opensyncforproton.sync.provider.MatchReason
import dev.rboza.android.opensyncforproton.sync.provider.MergeChoice
import dev.rboza.android.opensyncforproton.sync.provider.MergePair
import dev.rboza.android.opensyncforproton.sync.provider.MergeResult
import dev.rboza.android.opensyncforproton.sync.provider.PhoneAccount
import dev.rboza.android.opensyncforproton.sync.provider.ProviderDeviceContacts
import dev.rboza.android.opensyncforproton.sync.provider.ReadContact
import dev.rboza.android.opensyncforproton.sync.provider.applyMerges
import dev.rboza.android.opensyncforproton.sync.provider.dataRows
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.Base64
import java.util.Collections
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End to end: the real composition root ([E2eApp] only swaps the Proton server for MockWebServer over HTTPS, with a
 * test-only certificate only this test trusts), the real Keystore SecretBox, AccountManager, Contacts provider, Room,
 * and crypto library with the fixture account (fixtures/crypto, test-only keys): log in (real SRP, answered by go-srp's
 * own server with the fixture's verifier), the credentials then load, turn sync on, sync one contact, and its row and
 * its map exist. Then: a phone edit uploaded with cards the fixture keys read; a create whose reply is lost, sent again
 * with the same `UID` and bound; a photo set on the phone that reaches Proton byte for byte and stays on the phone as
 * it was; a pair merged on the phone (Combine both, Keep the phone's) whose upload patches Proton's contact; and one
 * Proton changed meanwhile, held. Only the `.debug` account types are touched (D4), the local contacts a made-up
 * account's of its own type; emulator only (`mise run device-tests`).
 *
 * The test can never reach Proton: every connection that isn't to the loopback test server goes to a dead local proxy,
 * so a Proton client that ignored the test's config would fail here instead of sending anything.
 */
@RunWith(AndroidJUnit4::class)
class EndToEndTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as E2eApp
    private val accountManager = AccountManager.get(app)
    private val account = AccountId(USER_ID)

    private val srp = fixture("srp.json")
    private val keys = fixture("keys.json")
    private val contact = fixture("contacts.json").getJSONArray("contacts").objects().single { it.getString("name") == "all_types" }

    /** Recorded on MockWebServer's thread and asserted after: an assertion there would leave the call hanging. */
    private val problems = Collections.synchronizedList(mutableListOf<String>())
    private lateinit var server: MockWebServer
    private lateinit var proton: FakeProton
    private var previousProxy: ProxySelector? = null

    @Before
    fun setUp() {
        previousProxy = ProxySelector.getDefault()
        // D4: everything below removes accounts of the app's type, so it must be the test type.
        assertEquals("dev.rboza.android.opensyncforproton.debug", app.packageName)
        // A real sync needs them, and so does the sweep (turning sync on asks for them in the app).
        for (permission in listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)) {
            instrumentation.uiAutomation.grantRuntimePermission(app.packageName, permission)
        }
        sweep()
        ProxySelector.setDefault(LoopbackOnly)
        // The guard holds: a name that never resolves (RFC 6761) is sent to the dead proxy, not looked up.
        val client = OkHttpClient()
        val probe = runCatching { client.newCall(Request.Builder().url("https://example.invalid/").build()).execute().close() }
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        assertTrue("not sent to the dead proxy: ${probe.exceptionOrNull()}", probe.exceptionOrNull() is ConnectException)

        val root = HeldCertificate.Builder().certificateAuthority(0).commonName("test-only root").build()
        val leaf = HeldCertificate.Builder().signedBy(root).addSubjectAlternativeName("localhost").commonName("localhost").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(leaf).build()
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(root.certificate).build()
        server = MockWebServer()
        server.useHttps(serverCertificates.sslSocketFactory())
        proton = FakeProton()
        server.dispatcher = proton
        server.start()
        assertEquals("localhost", server.url("/").host)
        app.testServer = ProtonConfig(
            server.url("/"), "Other", ProtonTrust(clientCertificates.sslSocketFactory(), clientCertificates.trustManager),
        )
    }

    @After
    fun tearDown() {
        app.testServer = null
        if (::server.isInitialized) server.close()
        // Android can replace the default selector when the network's proxy changes; the guard must have held
        // throughout.
        val guard = ProxySelector.getDefault()
        ProxySelector.setDefault(previousProxy)
        if (::server.isInitialized) assertSame("the guard was replaced during the test", LoopbackOnly, guard)
        sweep()
        assertEquals(emptyList<String>(), problems.toList())
    }

    @Test
    fun aLoginThenASyncWritesTheContactAndItsMap() = runBlocking {
        logInAndSync()

        val row = rows().single()
        assertEquals(CONTACT_ID, row.sourceId)
        assertEquals("Test Only Person", row.displayName)
        val (mappedRawId, fingerprint) = checkNotNull(map(CONTACT_ID)) { "no map" }
        assertEquals(row.id, mappedRawId)
        // docs/protocol.md § Content fingerprints, of exactly the cards the server sent.
        val cards = contact.getJSONArray("cards").objects().map { CardDto(it.getInt("type"), it.getString("data"), it.optString("signature").ifEmpty { null }) }
        assertEquals(contactFingerprint(ProtonContactId(CONTACT_ID), cards).value, fingerprint)

        assertEquals(RemovalResult(accountRemoved = true, sessionEnded = true), app.removeFromPhone(account))
    }

    /** C5's cards decrypt and verify with the fixture keys, unmapped parts kept. */
    @Test
    fun aPhoneEditUploadsCardsTheFixtureKeysRead() = runBlocking {
        logInAndSync()
        val row = rows().single()
        // As the user would: not as the sync adapter, so the provider marks the row dirty.
        val edited = app.contentResolver.update(
            Data.CONTENT_URI,
            ContentValues().apply { put(Phone.NUMBER, "+1 555 0199") },
            "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ?",
            arrayOf(row.id.toString(), Phone.CONTENT_ITEM_TYPE),
        )
        assertEquals(1, edited)
        assertTrue(rows().single().dirty)

        val sent = proton.routes.size
        val report = checkNotNull(app.requestSync(account, SyncRequest.Full())) { "nothing ran" }

        assertNull(report.blocker)
        assertEquals(emptyList<Any>(), report.refused)
        assertEquals(1, report.counts().changedOnPhone)
        // docs/sync.md § Writes: re-fetched (C2) before C5, and re-read after it, since neither reply has the cards.
        assertEquals(listOf(EXPORT, "GET $CONTACTS/$CONTACT_ID", "PUT $CONTACTS/$CONTACT_ID", "GET $CONTACTS/$CONTACT_ID"), proton.routes.drop(sent))
        val stored = proton.cards(CONTACT_ID)
        val original = contact.getJSONArray("cards").objects()
        val plaintexts = contact.getJSONArray("plaintexts").let { array -> List(array.length()) { array.getString(it) } }
        val read = decrypt(stored).value
        // Cards by type, since nothing may depend on their order (docs/protocol.md § JSON). The legacy type 1 card goes
        // back as type 3 (docs/protocol.md § Contact cards), and CATEGORIES (unmapped) exactly as Proton stored it.
        assertEquals(listOf(0, 2, 3, 3), read.map { it.type }.sorted())
        assertEquals(original[0].getString("data"), stored.single { it.type == 0 }.data)
        assertEquals(plaintexts[2], read.single { it.type == 2 }.text)
        // The library returns types 1 and 3 with LF endings; only the edited TEL changed.
        assertEquals(
            setOf(lf(plaintexts[1]), lf(plaintexts[3].replace("+1 555 0100", "+1 555 0199"))),
            read.filter { it.type == 3 }.map { it.text }.toSet(),
        )

        // Written back from the re-read: clean, and the map holds what Proton now stores (in its own order).
        val after = rows().single()
        assertEquals(CONTACT_ID, after.sourceId)
        assertFalse("still dirty", after.dirty)
        assertEquals(after.id to contactFingerprint(ProtonContactId(CONTACT_ID), stored).value, map(CONTACT_ID))

        assertEquals(RemovalResult(accountRemoved = true, sessionEnded = true), app.removeFromPhone(account))
    }

    /**
     * A create whose C4 reply is lost (MockWebServer disconnects after reading the request) is sent again with the same
     * `UID`, answered 2500, found by C1, and bound: one copy on the server.
     */
    @Test
    fun aCreateWhoseReplyIsLostIsSentAgainAndBound() = runBlocking {
        logInAndSync()
        val created = app.contentResolver.applyBatch(
            ContactsContract.AUTHORITY,
            arrayListOf(
                ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
                    .withValue(RawContacts.ACCOUNT_TYPE, app.packageName)
                    .withValue(RawContacts.ACCOUNT_NAME, EMAIL)
                    .build(),
                ContentProviderOperation.newInsert(Data.CONTENT_URI)
                    .withValueBackReference(Data.RAW_CONTACT_ID, 0)
                    .withValue(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(StructuredName.GIVEN_NAME, "New")
                    .withValue(StructuredName.FAMILY_NAME, "Test Only")
                    .build(),
                ContentProviderOperation.newInsert(Data.CONTENT_URI)
                    .withValueBackReference(Data.RAW_CONTACT_ID, 0)
                    .withValue(Data.MIMETYPE, Email.CONTENT_ITEM_TYPE)
                    .withValue(Email.ADDRESS, "new-test-only@example.test")
                    .build(),
            ),
        )
        val newRow = checkNotNull(created[0].uri).lastPathSegment!!.toLong()

        proton.dropNextCreateReply = true
        val first = proton.routes.size
        val lost = checkNotNull(app.requestSync(account, SyncRequest.Full())) { "nothing ran" }

        // A lost connection stops sending, the record kept (docs/sync.md § Writes).
        assertEquals(Blocker.PROTON_UNAVAILABLE, lost.blocker)
        assertEquals(listOf(EXPORT, "POST $CONTACTS"), proton.routes.drop(first))
        assertEquals(2, proton.contactCount())
        assertNull(rows().single { it.id == newRow }.sourceId)
        assertEquals(1, pendingCreates())

        val second = proton.routes.size
        val again = checkNotNull(app.requestSync(account, SyncRequest.Full())) { "nothing ran" }

        assertNull(again.blocker)
        assertEquals(emptyList<Any>(), again.refused)
        val newId = proton.idOfUid(proton.creates.first())
        // The 2500 is looked up by C1, never by the inventory, then the contact re-read (C2) and bound.
        assertEquals(listOf(EXPORT, "POST $CONTACTS", "GET $CONTACTS", "GET $CONTACTS/$newId"), proton.routes.drop(second))
        // The same create, its UID kept: Proton refused it as one it holds, and the app found that one by its UID.
        assertEquals(2, proton.creates.size)
        assertEquals(1, proton.creates.toSet().size)
        assertTrue(proton.creates.first(), proton.creates.first().matches(Regex("urn:uuid:[0-9a-f-]{36}")))
        assertEquals(2, proton.contactCount())
        // Bound to that contact, not downloaded as a second row: the account holds the fixture contact and this one.
        val rows = rows()
        assertEquals(listOf(CONTACT_ID, newId).sorted(), rows.map { it.sourceId.toString() }.sorted())
        val bound = rows.single { it.id == newRow }
        assertEquals(newId, bound.sourceId)
        assertFalse("still dirty", bound.dirty)
        assertEquals(newRow to contactFingerprint(ProtonContactId(newId), proton.cards(newId)).value, map(newId))
        assertEquals(0, pendingCreates())
        // The created cards decrypt and verify with the fixture keys.
        assertTrue(decrypt(proton.cards(newId)).value.single { it.type == 2 }.text.contains("item1.EMAIL:new-test-only@example.test"))

        assertEquals(RemovalResult(accountRemoved = true, sessionEnded = true), app.removeFromPhone(account))
    }

    /**
     * A photo set on the phone needs no change, so Proton gets the phone's photo byte for byte, and the row written
     * back from the re-read keeps it as it was (docs/mapping.md § Photos, docs/sync.md § Photos): both ends hold the
     * same photo.
     */
    @Test
    fun aPhotoSetOnThePhoneReachesProtonAsItIsAndStays() = runBlocking {
        logInAndSync()
        val row = rows().single()
        val held = setPhotoAsAnApp(row.id)
        assertTrue(rows().single().dirty)

        val report = checkNotNull(app.requestSync(account, SyncRequest.Full())) { "nothing ran" }

        assertNull(report.blocker)
        assertEquals(emptyList<Any>(), report.refused)
        assertEquals(1, report.counts().changedOnPhone)
        // A new photo line, unfolded, in an encrypted card (docs/mapping.md § Writing).
        val photoLine = decrypt(proton.cards(CONTACT_ID)).value.filter { it.type == 3 }
            .flatMap { lf(it.text).replace("\n ", "").lines() }
            .single { it.startsWith("PHOTO:") }
        assertTrue(photoLine.take(40), photoLine.startsWith("PHOTO:data:image/jpeg;base64,"))
        assertArrayEquals("Proton's photo is the phone's", held, decode(photoLine.substringAfter("base64,")))
        assertFalse("still dirty", rows().single().dirty)
        assertArrayEquals("the phone's photo as it was", held, displayPhoto(row.id))

        // Settled: the next sync finds nothing to send, and the photo stays as both ends hold it.
        val next = proton.routes.size
        val again = checkNotNull(app.requestSync(account, SyncRequest.Full())) { "nothing ran" }
        assertNull(again.blocker)
        assertEquals(listOf(EXPORT), proton.routes.drop(next))
        assertArrayEquals(held, displayPhoto(row.id))

        assertEquals(RemovalResult(accountRemoved = true, sessionEnded = true), app.removeFromPhone(account))
    }

    // Merging: a pair merged on the phone, as applyMerges writes it, then uploaded by the next sync as any phone edit.
    // The local contact is a made-up account's.

    /** The fixture contact paired with a made-up local contact holding [graph], as importList would pair them. */
    private fun pairWith(graph: ContactGraph): MergePair {
        val written = localContacts.insertLocal(dataRows(graph, photo = null), starred = false)
        val id = (written as ContactWriteResult.Written).rawContactId
        val local = localContacts.queryLocal().single { it.rawContactId == id }
        val proton = localContacts.query(PhoneAccount(account, EMAIL)).single { !it.deleted }
        return MergePair(ReadContact(local, localContacts.readRows(id)), ReadContact(proton, localContacts.readRows(proton.rawContactId)), MatchReason.NAME, cantImport = null)
    }

    /** The lines of [cards], unfolded, as the fixture keys read them (the clear card as it is). */
    private fun lines(cards: List<CardDto>): List<String> =
        decrypt(cards).value.flatMap { lf(it.text).replace("\n ", "").lines() }.filterNot { it.isEmpty() || it == "BEGIN:VCARD" || it == "END:VCARD" || it.startsWith("VERSION:") }

    /** The fixture contact's lines as Proton first holds them. */
    private fun fixtureLines(): List<String> {
        val plaintexts = contact.getJSONArray("plaintexts").let { array -> List(array.length()) { array.getString(it) } }
        return plaintexts.flatMap { lf(it).lines() }.filterNot { it.isEmpty() || it == "BEGIN:VCARD" || it == "END:VCARD" || it.startsWith("VERSION:") }
    }

    /** The phone contact Android shows as the fixture's: the same name and e-mail address. */
    private val samePerson = ContactGraph(
        uid = null,
        formattedName = "Test Only Person",
        name = Name(given = "Test Only", family = "Person"),
        emails = listOf(TypedEmail("test-only@example.org", EmailType.Work)),
    )

    @Test
    fun aCombinedRowUploadsAsOnePatchKeepingEveryPropertyAndAddingThePhonesValues() = runBlocking {
        logInAndSync()
        val pair = pairWith(
            samePerson.copy(
                emails = samePerson.emails + TypedEmail("phone-only@example.test", EmailType.Home),
                phones = listOf(TypedPhone("+1 555 0177", PhoneType.Mobile)),
                note = "the phone's note",
            ),
        )

        val before = proton.routes.toList()
        val versionBefore = pair.proton.row.version
        assertEquals(MergeResult(combined = 1, wrote = true), applyMerges(localContacts, records, PhoneAccount(account, EMAIL), listOf(pair to MergeChoice.COMBINE)))
        // The row has been seen already clean here, cause unknown. This says what Proton was sent and how the row's
        // VERSION moved: unchanged, no write; one step, written and then cleaned; more, a later write.
        val merged = localContacts.query(PhoneAccount(account, EMAIL)).single { !it.deleted }
        assertTrue(
            "the merged row isn't dirty; VERSION $versionBefore, then ${merged.version}; Proton was sent $before, then ${proton.routes.toList()}; " +
                "its rows ${localContacts.readRows(merged.rawContactId).map { it.mimeType }}",
            merged.dirty,
        )
        val sent = proton.routes.size
        val report = checkNotNull(app.requestSync(account, SyncRequest.Full())) { "nothing ran" }

        assertNull(report.blocker)
        assertEquals(emptyList<Any>(), report.refused)
        assertEquals(1, report.counts().changedOnPhone)
        assertEquals(listOf(EXPORT, "GET $CONTACTS/$CONTACT_ID", "PUT $CONTACTS/$CONTACT_ID", "GET $CONTACTS/$CONTACT_ID"), proton.routes.drop(sent))
        val stored = proton.cards(CONTACT_ID)
        val after = lines(stored)
        assertEquals("every property Proton had, as it was", emptyList<String>(), fixtureLines() - after.toSet())
        assertEquals(contact.getJSONArray("cards").objects()[0].getString("data"), stored.single { it.type == 0 }.data)
        assertTrue(after.joinToString("\n"), after.any { it.contains("EMAIL") && it.endsWith(":phone-only@example.test") })
        assertTrue(after.joinToString("\n"), after.any { it.startsWith("TEL") && it.endsWith(":+1 555 0177") })
        assertFalse("Proton's note stays", after.any { it.contains("the phone's note") })
        assertFalse("still dirty", rows().single().dirty)

        assertEquals(RemovalResult(accountRemoved = true, sessionEnded = true), app.removeFromPhone(account))
    }

    @Test
    fun aReplacedRowsPatchRemovesProtonsShownValuesThePhoneLackedAndKeepsItsUnshownProperties() = runBlocking {
        logInAndSync()
        // The e-mail address is Proton's with another type: its line is edited in place, its group kept.
        val pair = pairWith(samePerson.copy(emails = listOf(TypedEmail("test-only@example.org", EmailType.Home)), phones = listOf(TypedPhone("+1 555 0177", PhoneType.Mobile))))

        assertEquals(MergeResult(keptPhone = 1, wrote = true), applyMerges(localContacts, records, PhoneAccount(account, EMAIL), listOf(pair to MergeChoice.KEEP_PHONE)))
        val sent = proton.routes.size
        val report = checkNotNull(app.requestSync(account, SyncRequest.Full())) { "nothing ran" }

        assertNull(report.blocker)
        assertEquals(emptyList<Any>(), report.refused)
        assertEquals(listOf(EXPORT, "GET $CONTACTS/$CONTACT_ID", "PUT $CONTACTS/$CONTACT_ID", "GET $CONTACTS/$CONTACT_ID"), proton.routes.drop(sent))
        val stored = proton.cards(CONTACT_ID)
        val after = lines(stored)
        // The number, the note, and the nickname Proton showed and the phone lacked are gone; the rest stays as it was.
        val gone = listOf("TEL;TYPE=cell:+1 555 0100", "NOTE:test-only note", "NICKNAME:tester")
        val retyped = "ITEM1.EMAIL;TYPE=work:test-only@example.org"
        assertEquals(gone + retyped, (gone + retyped).filter { it in fixtureLines() })
        assertEquals(
            (fixtureLines() - gone.toSet() - retyped + "ITEM1.EMAIL;TYPE=home:test-only@example.org").sorted(),
            after.filterNot { it.startsWith("TEL") && it.endsWith(":+1 555 0177") }.sorted(),
        )
        assertTrue(after.joinToString("\n"), after.any { it.startsWith("TEL") && it.endsWith(":+1 555 0177") })
        assertEquals("the clear card, its CATEGORIES unshown, as it was", contact.getJSONArray("cards").objects()[0].getString("data"), stored.single { it.type == 0 }.data)

        assertEquals(RemovalResult(accountRemoved = true, sessionEnded = true), app.removeFromPhone(account))
    }

    @Test
    fun aMergedRowProtonChangedSinceItsBaselineIsHeldAndNothingIsSent() = runBlocking {
        logInAndSync()
        val pair = pairWith(samePerson.copy(phones = listOf(TypedPhone("+1 555 0177", PhoneType.Mobile))))
        assertEquals(MergeResult(combined = 1, wrote = true), applyMerges(localContacts, records, PhoneAccount(account, EMAIL), listOf(pair to MergeChoice.COMBINE)))
        // Proton's copy changed meanwhile: the same cards in another order, so another fingerprint.
        proton.reorder(CONTACT_ID)
        val held = proton.cards(CONTACT_ID)

        val sent = proton.routes.size
        val report = checkNotNull(app.requestSync(account, SyncRequest.Full())) { "nothing ran" }

        assertNull(report.blocker)
        assertEquals(1, report.counts().conflicts)
        assertEquals(emptyList<String>(), proton.routes.drop(sent).filterNot { it.startsWith("GET ") })
        assertEquals(held, proton.cards(CONTACT_ID))
        assertTrue("held, still dirty", rows().single().dirty)

        assertEquals(RemovalResult(accountRemoved = true, sessionEnded = true), app.removeFromPhone(account))
    }

    /** The app's rows and a made-up local account of its own type. */
    private val localContacts by lazy { ProviderDeviceContacts(app.contentResolver, app.packageName, LOCAL_NAME, LOCAL_TYPE) }

    /** The import records applyMerges saves; the upload doesn't read them. */
    private val records = object : ImportRecordDao {
        private val saved = Collections.synchronizedMap(mutableMapOf<Long, ImportRecordEntity>())

        override suspend fun records(accountId: String) = saved.values.filter { it.accountId == accountId }

        override suspend fun save(record: ImportRecordEntity) {
            saved[record.originalRawContactId] = record
        }

        override suspend fun delete(accountId: String, originalRawContactId: Long) {
            saved.remove(originalRawContactId)
        }
    }

    private fun displayPhotoUri(rawContactId: Long): Uri =
        Uri.withAppendedPath(ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawContactId), RawContacts.DisplayPhoto.CONTENT_DIRECTORY)

    /** The display photo the provider keeps for [rawContactId]. */
    private fun displayPhoto(rawContactId: Long): ByteArray =
        checkNotNull(app.contentResolver.openAssetFileDescriptor(displayPhotoUri(rawContactId), "r")).use { it.createInputStream().readBytes() }

    /**
     * Sets a drawn, test-only photo on [rawContactId] as the Contacts app does (not as the sync adapter, so the row is
     * dirty), by writing its display photo; once the provider has stored it (in the background), the display photo it
     * made of it. Noise, since the provider compressing a flat colour again gives the same bytes, which would hide a
     * photo stored again.
     */
    private fun setPhotoAsAnApp(rawContactId: Long): ByteArray {
        val random = Random(1)
        val bitmap = Bitmap.createBitmap(IntArray(300 * 200) { random.nextInt() or Color.BLACK }, 300, 200, Bitmap.Config.ARGB_8888)
        val jpeg = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
        checkNotNull(app.contentResolver.openAssetFileDescriptor(displayPhotoUri(rawContactId), "rw")).use { it.createOutputStream().write(jpeg) }
        val deadline = System.currentTimeMillis() + 10_000
        while (photoFileId(rawContactId) == null && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue("the provider stored the photo", photoFileId(rawContactId) != null)
        return displayPhoto(rawContactId)
    }

    private fun photoFileId(rawContactId: Long): Long? = app.contentResolver.query(
        Data.CONTENT_URI, arrayOf(Photo.PHOTO_FILE_ID), "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ?", arrayOf("$rawContactId", Photo.CONTENT_ITEM_TYPE), null,
    )?.use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }

    /**
     * Logs in (and commits), checks the credentials load again, turns sync on, and runs one full sync with no blocker.
     * Manual is chosen first, so only the test starts syncs: under any other interval Android starts its own (a full
     * sync once sync is on, an upload-only one after a phone change) and the app's periodic job its incremental runs,
     * racing the test's (docs/sync.md § Scheduling).
     */
    private suspend fun logInAndSync() {
        val step = app.newLoginInteractor().submitCredentials(USERNAME, srp.getString("password").toCharArray())
        assertTrue("login gave $step", step is LoginStep.Success)
        val session = (step as LoginStep.Success).session
        assertEquals(LoginCommit.Committed(resumed = false), app.completeLogin(session))

        // The credentials saved through the real Keystore SecretBox load again.
        assertTrue(app.hasCredentials(account))

        app.setManual(account, true)
        app.setSyncMode(account, SyncMode.ON)
        val report = checkNotNull(app.requestSync(account, SyncRequest.Full())) { "nothing ran" }
        assertNull(report.blocker)
    }

    private class Row(val id: Long, val sourceId: String?, val displayName: String?, val dirty: Boolean)

    /** The account's live raw contacts. */
    private fun rows(): List<Row> = app.contentResolver.query(
        RawContacts.CONTENT_URI,
        arrayOf(RawContacts._ID, RawContacts.SOURCE_ID, RawContacts.DISPLAY_NAME_PRIMARY, RawContacts.DIRTY),
        "${RawContacts.ACCOUNT_TYPE} = ? AND ${RawContacts.ACCOUNT_NAME} = ? AND ${RawContacts.DELETED} = 0",
        arrayOf(app.packageName, EMAIL),
        null,
    )!!.use { cursor ->
        buildList { while (cursor.moveToNext()) add(Row(cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getInt(3) != 0)) }
    }

    /** A contact's map in the app's database (read-only, beside Room): its raw contact ID and fingerprint. */
    private fun map(protonId: String): Pair<Long, String?>? = database { db ->
        db.rawQuery("SELECT raw_contact_id, fingerprint FROM contact_map WHERE account_id = ? AND proton_id = ?", arrayOf(USER_ID, protonId))
            .use { if (it.moveToFirst()) it.getLong(0) to it.getString(1) else null }
    }

    private fun pendingCreates(): Int = database { db ->
        db.rawQuery("SELECT COUNT(*) FROM pending_contact_create WHERE account_id = ?", arrayOf(USER_ID)).use { it.moveToFirst(); it.getInt(0) }
    }

    private fun <T> database(read: (SQLiteDatabase) -> T): T =
        SQLiteDatabase.openDatabase(app.getDatabasePath("sync.db").path, null, SQLiteDatabase.OPEN_READONLY).use(read)

    /**
     * [cards] read by the app's read path (`:crypto`) with the fixture's user keys, unlocked from its login password:
     * it rejects a type 2 or 3 card without a signature that verifies, and here no warning is allowed either (no legacy
     * card).
     */
    private fun decrypt(cards: List<CardDto>): CryptoResult.Success<List<PlainCard>> {
        val salts = keys.getJSONArray("keySalts").objects().associate { it.getString("id") to it.getString("keySalt") }
        val userKeys = keys.getJSONArray("userKeys").objects().map {
            LockedUserKey(it.getString("id"), it.getString("armoredPrivateKey"), it.getBoolean("active"), it.getBoolean("primary"))
        }
        val passphrases = userKeys.associate { it.id to GoSrpClient().keyPassphrase(keys.getString("loginPassword").toCharArray(), salts.getValue(it.id)) }
        val unlocked = (GoKeyHierarchy().unlock(LockedKeyBundle(userKeys), passphrases) as CryptoResult.Success).value
        return unlocked.use {
            val result = GoItemCrypto().decryptContact(it, cards.map { card -> EncryptedCard(card.type, card.data, card.signature) })
            assertTrue("the cards don't read: $result", result is CryptoResult.Success && result.warnings.isEmpty())
            @Suppress("UNCHECKED_CAST")
            result as CryptoResult.Success<List<PlainCard>>
        }
    }

    /**
     * Removes every account of the app's (`.debug`) type and its rows, and the made-up local account's rows, then lets
     * the app reconcile what's left.
     */
    private fun sweep() {
        accountManager.getAccountsByType(app.packageName).forEach { accountManager.removeAccountExplicitly(it) }
        val asSyncAdapter = RawContacts.CONTENT_URI.buildUpon().appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true").build()
        listOf(app.packageName, LOCAL_TYPE).forEach { app.contentResolver.delete(asSyncAdapter, "${RawContacts.ACCOUNT_TYPE} = ?", arrayOf(it)) }
        runBlocking { app.reconcile() }
    }

    /**
     * Proton's routes the login and the syncs use, answered from the fixture account. It keeps the contacts it holds:
     * C5 replaces a contact's cards, and C4 adds one unless one already has its `UID` (item `Code` 2500).
     */
    private inner class FakeProton : Dispatcher() {
        @Volatile private var srpServer: Server? = null

        /** The next C4 is carried out, but its connection is shut once the request is read, so its reply is lost. */
        @Volatile var dropNextCreateReply = false

        private val contacts = Collections.synchronizedMap(
            linkedMapOf(CONTACT_ID to contact.getJSONArray("cards").objects().map { CardDto(it.getInt("type"), it.getString("data"), it.optString("signature").ifEmpty { null }) }),
        )

        /** Each C4's `UID`, in order. */
        val creates: MutableList<String> = Collections.synchronizedList(mutableListOf())

        /** Every request's method and path, in order. */
        val routes: MutableList<String> = Collections.synchronizedList(mutableListOf())

        fun cards(id: String): List<CardDto> = checkNotNull(contacts[id]) { "no contact $id" }

        fun contactCount() = contacts.size

        /** Proton's copy changed: the same cards, in another order. */
        fun reorder(id: String) {
            contacts[id] = cards(id).reversed()
        }

        fun idOfUid(uid: String): String = synchronized(contacts) { contacts.entries.single { uidOf(it.value) == uid }.key }

        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.url.encodedPath
            val route = "${request.method} $path"
            routes += route
            if (route !in NO_SESSION && (request.headers["Authorization"] != "Bearer $ACCESS_TOKEN" || request.headers["x-pm-uid"] != UID)) {
                problems += "$route without the session"
            }
            val contactId = CONTACT_PATH.matchEntire(path)?.groupValues?.get(1)
            return when {
                route == "POST /core/v4/auth/info" -> {
                    val server = Srp.newServerFromSigned(srp.getString("signedModulus"), decode(srp.getString("verifier")), SRP_BITS)
                    srpServer = server
                    ok(
                        JSONObject()
                            .put("Modulus", srp.getString("signedModulus"))
                            .put("ServerEphemeral", encode(server.generateChallenge()))
                            .put("Version", srp.getInt("version"))
                            .put("Salt", srp.getString("salt"))
                            .put("SRPSession", SRP_SESSION),
                    )
                }
                route == "POST /core/v4/auth" -> auth(JSONObject(request.body!!.utf8()))
                route == "GET /core/v4/users" -> ok(JSONObject().put("User", user()))
                route == "GET /core/v4/keys/salts" -> ok(JSONObject().put("KeySalts", keySalts()))
                route == "GET /contacts/v4/contacts/export" -> page(request) { id, cards -> contactJson(id, cards) }
                // C1, as a create's lookup reads it: each contact's ID and UID.
                route == "GET /contacts/v4/contacts" -> page(request) { id, cards -> JSONObject().put("ID", id).put("UID", uidOf(cards)) }
                route == "POST /contacts/v4/contacts" -> create(JSONObject(request.body!!.utf8()))
                request.method == "GET" && contactId != null -> contacts[contactId]?.let { ok(JSONObject().put("Contact", contactJson(contactId, it))) } ?: gone(route)
                request.method == "PUT" && contactId != null && contactId != "delete" -> update(contactId, JSONObject(request.body!!.utf8()))
                route == "DELETE /core/v4/auth" -> ok(JSONObject())
                else -> {
                    problems += "unexpected $route"
                    MockResponse.Builder().code(404).body("""{"Code":2501}""").build()
                }
            }
        }

        private fun auth(body: JSONObject): MockResponse {
            val server = srpServer
            if (server == null || body.getString("SRPSession") != SRP_SESSION || body.getString("Username") != USERNAME) {
                problems += "auth without its info"
                return MockResponse.Builder().code(422).body("""{"Code":8002}""").build()
            }
            val serverProof = try {
                server.verifyProofs(decode(body.getString("ClientEphemeral")), decode(body.getString("ClientProof")))
            } catch (e: Exception) {
                problems += "the client's proof didn't verify"
                return MockResponse.Builder().code(422).body("""{"Code":8002}""").build()
            }
            return ok(
                JSONObject()
                    .put("AccessToken", ACCESS_TOKEN)
                    .put("RefreshToken", "test-only-refresh-token")
                    .put("TokenType", "Bearer")
                    .put("UID", UID)
                    .put("UserID", USER_ID)
                    .put("ServerProof", encode(serverProof))
                    .put("PasswordMode", 1)
                    .put("ExpiresIn", 3600)
                    .put("2FA", JSONObject().put("Enabled", 0).put("TOTP", 0)),
            )
        }

        /** C4 with one contact: created (1000) unless its `UID` is held (2500); the reply dropped when asked. */
        private fun create(body: JSONObject): MockResponse {
            val sent = body.getJSONArray("Contacts").objects()
            if (sent.size != 1 || listOf("Overwrite", "Labels", "Import").any { body.getInt(it) != 0 }) problems += "C4 not one contact, all flags 0"
            val cards = cardsOf(sent.single())
            val uid = uidOf(cards)
            creates += uid ?: "no UID".also { problems += "C4 without a UID" }
            val item = synchronized(contacts) {
                if (contacts.values.any { uidOf(it) == uid }) {
                    JSONObject().put("Code", 2500)
                } else {
                    val id = "test-only-contact-${contacts.size + 1}"
                    contacts[id] = stored(cards)
                    JSONObject().put("Code", 1000).put("Contact", JSONObject().put("ID", id))
                }
            }
            val reply = JSONObject().put("Code", 1001).put("Responses", JSONArray().put(JSONObject().put("Index", 0).put("Response", item)))
            return if (dropNextCreateReply) {
                dropNextCreateReply = false
                MockResponse.Builder().onResponseStart(SocketEffect.ShutdownConnection).build()
            } else {
                MockResponse.Builder().code(200).body(reply.toString()).build()
            }
        }

        /** C5: the contact's cards replaced. */
        private fun update(id: String, body: JSONObject): MockResponse {
            if (id !in contacts) return gone("PUT $id")
            contacts[id] = stored(cardsOf(body))
            return ok(JSONObject().put("Contact", JSONObject().put("ID", id)))
        }

        /** Page 0 holds every contact; later pages are empty. */
        private fun page(request: RecordedRequest, entry: (String, List<CardDto>) -> JSONObject): MockResponse {
            val all = synchronized(contacts) { contacts.map { (id, cards) -> entry(id, cards) } }
            val page = request.url.queryParameter("Page")?.toInt() ?: -1
            return ok(JSONObject().put("Contacts", if (page == 0) JSONArray(all) else JSONArray()).put("Total", all.size))
        }

        private fun gone(route: String): MockResponse {
            problems += "$route: no such contact"
            return MockResponse.Builder().code(422).body("""{"Code":2501}""").build()
        }

        private fun user() = JSONObject()
            .put("ID", USER_ID)
            .put("Name", USERNAME)
            .put("Email", EMAIL)
            .put("DisplayName", "Test Only")
            .put(
                "Keys",
                JSONArray(
                    keys.getJSONArray("userKeys").objects().map {
                        JSONObject()
                            .put("ID", it.getString("id"))
                            .put("PrivateKey", it.getString("armoredPrivateKey"))
                            .put("Active", if (it.getBoolean("active")) 1 else 0)
                            .put("Primary", if (it.getBoolean("primary")) 1 else 0)
                            .put("Fingerprint", "test-only")
                            .put("Version", 3)
                    },
                ),
            )

        private fun keySalts() = JSONArray(
            keys.getJSONArray("keySalts").objects().map { JSONObject().put("ID", it.getString("id")).put("KeySalt", it.getString("keySalt")) },
        )

        private fun contactJson(id: String, cards: List<CardDto>) = JSONObject()
            .put("ID", id)
            .put(
                "Cards",
                JSONArray(
                    cards.map { card ->
                        JSONObject().put("Type", card.type).put("Data", card.data).also { if (card.signature != null) it.put("Signature", card.signature) }
                    },
                ),
            )

        private fun cardsOf(contact: JSONObject) = contact.getJSONArray("Cards").objects().map {
            // docs/protocol.md § JSON: every card sends its Signature, null when unsigned.
            if (!it.has("Signature")) problems += "a card without its Signature key"
            CardDto(it.getInt("Type"), it.getString("Data"), if (it.isNull("Signature")) null else it.getString("Signature"))
        }

        /**
         * Written cards as this fake stores them: in another order than sent, which Proton may do
         * (docs/protocol.md § JSON), so a map made from the cards sent rather than from the re-read differs from what
         * Proton holds.
         */
        private fun stored(cards: List<CardDto>) = cards.reversed()

        /** The `UID` in a contact's clear or signed cards, as Proton reads it. */
        private fun uidOf(cards: List<CardDto>): String? =
            cards.filter { it.type == 0 || it.type == 2 }.firstNotNullOfOrNull { UID_LINE.find(it.data)?.groupValues?.get(1) }

        private fun ok(body: JSONObject) = MockResponse.Builder().code(200).body(body.put("Code", 1000).toString()).build()
    }

    /**
     * Loopback goes direct; anything else goes to a local port nothing listens on, so it fails before leaving the
     * device.
     */
    private object LoopbackOnly : ProxySelector() {
        private val dead = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 9))

        override fun select(uri: URI): List<Proxy> = if (uri.host == "localhost" || uri.host == "127.0.0.1") listOf(Proxy.NO_PROXY) else listOf(dead)

        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
    }

    /** The library returns decrypted text with LF line endings; the fixture's plaintexts are CRLF. */
    private fun lf(text: String) = text.replace("\r\n", "\n")

    private fun fixture(name: String) = JSONObject(instrumentation.context.assets.open(name).bufferedReader().use { it.readText() })

    private fun JSONArray.objects() = List(length()) { getJSONObject(it) }

    private companion object {
        const val USERNAME = "test-only-user"
        const val EMAIL = "test-only-user@example.test"
        const val USER_ID = "test-only-user-id"
        const val UID = "test-only-session-uid"
        const val ACCESS_TOKEN = "test-only-access-token"
        const val SRP_SESSION = "test-only-srp-session"
        const val CONTACT_ID = "test-only-contact-1"
        const val LOCAL_NAME = "test-only-local@example.test"
        const val LOCAL_TYPE = "dev.rboza.android.opensyncforproton.debug.local-test-only"
        const val SRP_BITS = 2048L
        val NO_SESSION = setOf("POST /core/v4/auth/info", "POST /core/v4/auth")
        val CONTACT_PATH = Regex("/contacts/v4/contacts/([^/]+)")
        val UID_LINE = Regex("(?m)^UID:([^\r\n]*)")
        const val CONTACTS = "/contacts/v4/contacts"
        const val EXPORT = "GET $CONTACTS/export"

        fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

        fun decode(text: String): ByteArray = Base64.getDecoder().decode(text)
    }
}
