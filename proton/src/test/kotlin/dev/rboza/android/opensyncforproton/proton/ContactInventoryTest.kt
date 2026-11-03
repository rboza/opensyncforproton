package dev.rboza.android.opensyncforproton.proton

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** docs/protocol.md § Contact inventory, against the real client on the test scheduler's clock. */
class ContactInventoryTest {
    private val server = MockWebServer()
    private val store = object : TokenStore {
        override fun current() = Tokens(uid = "uid-1", access = "access-1", refresh = "refresh-1")
        override fun replace(tokens: Tokens) = Unit
        override fun onSessionRejected(uid: String) = Unit
    }

    /** Each request as `path` or `path?Page=n`, in order. */
    private val requests = CopyOnWriteArrayList<String>()
    private val wrongPageSizes = CopyOnWriteArrayList<String>()

    /** Answers by route: an export page, a list page, or one contact's detail. Unscripted routes answer 404. */
    private var exportPage: (Int) -> MockResponse = { notFound }
    private var exportOldPage: (Int) -> MockResponse = { notFound }
    private var listPage: (Int) -> MockResponse = { notFound }
    private var detail: (String) -> MockResponse = { notFound }

    private val notFound get() = response(404, """{"Code":404}""")

    private fun response(status: Int, body: String) = MockResponse.Builder().code(status).body(body).build()

    private fun contact(id: String, data: String = "data-$id") = """{"ID":"$id","Cards":[{"Type":0,"Data":"$data"}]}"""

    private fun page(entries: List<String>, total: Int?) =
        response(200, """{"Code":1000,"Contacts":[${entries.joinToString(",")}]${total?.let { ""","Total":$it""" } ?: ""}}""")

    private fun exportOf(ids: List<String>, total: Int?) = page(ids.map { contact(it) }, total)

    private fun listPageOf(ids: List<String>, total: Int?) = page(ids.map { """{"ID":"$it","Name":"n"}""" }, total)

    private fun ids(range: IntRange) = range.map { "c-$it" }

    private fun fixtureResponse(name: String): MockResponse {
        val json = kotlinx.serialization.json.Json.parseToJsonElement(checkNotNull(javaClass.getResource("/$name.json")).readText())
            .let { it as kotlinx.serialization.json.JsonObject }
        return response(json.getValue("status").toString().toInt(), json.getValue("body").toString())
    }

    private fun TestScope.newApi(): ProtonApi {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                val page = request.url.queryParameter("Page")?.toInt()
                requests += path + (page?.let { "?Page=$it" } ?: "")
                // Recorded, not asserted: a failure on MockWebServer's thread would leave the call hanging.
                val paged = path == EXPORT || path == EXPORT_OLD || path == LIST
                if (paged && (page == null || request.url.queryParameter("PageSize") != "50")) {
                    wrongPageSizes += path
                    return response(400, """{"Code":2001}""")
                }
                return when {
                    path == EXPORT -> exportPage(page!!)
                    path == EXPORT_OLD -> exportOldPage(page!!)
                    path == LIST -> listPage(page!!)
                    path.startsWith("$LIST/") -> detail(path.removePrefix("$LIST/"))
                    else -> notFound
                }
            }
        }
        return ProtonApiFactory.create(ProtonConfig(server.url("/"), "Other"), store, Timing(testScheduler.timeSource) { it })
    }

    private suspend fun ProtonApi.inventory() = fetchInventory(this)

    @BeforeEach
    fun start() = server.start()

    @AfterEach
    fun stop() {
        server.close()
        assertEquals(emptyList<String>(), wrongPageSizes.toList(), "pages are asked for by Page, 50 at a time")
    }

    // Complete inventories.
    @Test
    fun `one short page with its Total is complete`() = runTest {
        val api = newApi()
        exportPage = { exportOf(ids(1..3), total = 3) }

        val inventory = api.inventory()

        assertNull(inventory.incomplete)
        assertEquals(ids(1..3), inventory.contacts.map { it.id })
        assertEquals(listOf("$EXPORT?Page=0"), requests.toList())
    }

    @Test
    fun `pages are fetched until the unique IDs reach the Total`() = runTest {
        val api = newApi()
        exportPage = { page -> exportOf(ids(page * 50 until minOf(page * 50 + 50, 120)), total = 120) }

        val inventory = api.inventory()

        assertNull(inventory.incomplete)
        assertEquals(ids(0 until 120), inventory.contacts.map { it.id })
        assertEquals(List(3) { "$EXPORT?Page=$it" }, requests.toList())
    }

    // A Total reached on a full page could be too low, so one more page must come back empty.
    @Test
    fun `a Total reached on a full page needs one more, empty page`() = runTest {
        val api = newApi()
        exportPage = { page -> exportOf(if (page < 2) ids(page * 50 until page * 50 + 50) else emptyList(), total = 100) }

        assertNull(api.inventory().incomplete)
        assertEquals(List(3) { "$EXPORT?Page=$it" }, requests.toList())
    }

    @Test
    fun `a Total that's too low on a page boundary is contradictory`() = runTest {
        val api = newApi()
        // 60 contacts, but Total says 50: page 0 is full and reaches it.
        exportPage = { page -> exportOf(if (page == 0) ids(0..49) else ids(50..59), total = 50) }

        val inventory = api.inventory()

        assertEquals(Incomplete.ContradictoryTotal, inventory.incomplete)
        assertEquals(60, inventory.contacts.size)
        assertEquals(List(2) { "$EXPORT?Page=$it" }, requests.toList())
    }

    @ParameterizedTest
    @ValueSource(ints = [7, 0])
    fun `without a Total, a short or empty page ends it`(last: Int) = runTest {
        val api = newApi()
        exportPage = { page -> exportOf(ids(page * 50 until page * 50 + if (page < 2) 50 else last), total = null) }

        val inventory = api.inventory()

        assertNull(inventory.incomplete)
        assertEquals(100 + last, inventory.contacts.size)
        assertEquals(List(3) { "$EXPORT?Page=$it" }, requests.toList())
    }

    @ParameterizedTest
    @ValueSource(strings = ["0", "none"])
    fun `an empty account is complete`(total: String) = runTest {
        val api = newApi()
        exportPage = { exportOf(emptyList(), total.toIntOrNull()) }

        val inventory = api.inventory()

        assertNull(inventory.incomplete)
        assertEquals(0, inventory.contacts.size)
    }

    @Test
    fun `a duplicate is counted once, and its first copy is kept`() = runTest {
        val api = newApi()
        exportPage = { page(listOf(contact("a"), contact("b", "first"), contact("b", "second"), contact("c")), total = 3) }

        val inventory = api.inventory()

        assertNull(inventory.incomplete)
        assertEquals(listOf("a", "b", "c"), inventory.contacts.map { it.id })
        assertEquals("first", inventory.contacts[1].cards.single().data)
    }

    @Test
    fun `a page that repeats some IDs but adds new ones goes on`() = runTest {
        val api = newApi()
        exportPage = { page -> exportOf(listOf(ids(0..49), ids(49..98)).getOrElse(page) { emptyList() }, total = 99) }

        val inventory = api.inventory()

        assertNull(inventory.incomplete)
        assertEquals(99, inventory.contacts.size)
    }

    // Incomplete inventories: paging stops at the first sign, and what was fetched is kept.
    @Test
    fun `a repeated page is incomplete`() = runTest {
        val api = newApi()
        exportPage = { exportOf(ids(0..49), total = null) }

        val inventory = api.inventory()

        assertEquals(Incomplete.RepeatedPage, inventory.incomplete)
        assertEquals(50, inventory.contacts.size)
        assertEquals(List(2) { "$EXPORT?Page=$it" }, requests.toList())
    }

    @Test
    fun `a page with only IDs already seen is incomplete`() = runTest {
        val api = newApi()
        exportPage = { page -> exportOf(if (page == 0) ids(0..49) else ids(10..12), total = null) }

        assertEquals(Incomplete.RepeatedPage, api.inventory().incomplete)
    }

    @ParameterizedTest
    @ValueSource(strings = ["changes", "vanishes", "appears"])
    fun `a Total that differs between pages is contradictory`(how: String) = runTest {
        val api = newApi()
        val totals = when (how) {
            "changes" -> listOf(120, 121)
            "vanishes" -> listOf(120, null)
            else -> listOf(null, 120)
        }
        exportPage = { page -> exportOf(ids(page * 50 until page * 50 + 50), totals[page]) }

        val inventory = api.inventory()

        assertEquals(Incomplete.ContradictoryTotal, inventory.incomplete)
        // The second page arrived, so its contacts may still be applied.
        assertEquals(100, inventory.contacts.size)
        assertEquals(List(2) { "$EXPORT?Page=$it" }, requests.toList())
    }

    // A full page too, so that it's the count and not the short page that ends it.
    @ParameterizedTest
    @ValueSource(ints = [3, 50])
    fun `more unique IDs than the Total is contradictory`(entries: Int) = runTest {
        val api = newApi()
        exportPage = { exportOf(ids(1..entries), total = entries - 1) }

        val inventory = api.inventory()

        assertEquals(Incomplete.ContradictoryTotal, inventory.incomplete)
        assertEquals(entries, inventory.contacts.size)
        assertEquals(listOf("$EXPORT?Page=0"), requests.toList())
    }

    @ParameterizedTest
    @ValueSource(ints = [3, 0])
    fun `a short or empty page before the Total is reached is contradictory`(entries: Int) = runTest {
        val api = newApi()
        exportPage = { exportOf(ids(1..entries), total = 5) }

        assertEquals(Incomplete.ContradictoryTotal, api.inventory().incomplete)
    }

    @Test
    fun `a negative Total is contradictory`() = runTest {
        val api = newApi()
        exportPage = { exportOf(emptyList(), total = -1) }

        assertEquals(Incomplete.ContradictoryTotal, api.inventory().incomplete)
    }

    @Test
    fun `a failed page ends it, keeping the pages before it`() = runTest {
        val api = newApi()
        exportPage = { page -> if (page == 0) exportOf(ids(0..49), total = 120) else response(422, """{"Code":2001}""") }

        val inventory = api.inventory()

        assertEquals(422, ((inventory.incomplete as Incomplete.Failed).error as ProtonError.Api).http)
        assertEquals(50, inventory.contacts.size)
        assertEquals(List(2) { "$EXPORT?Page=$it" }, requests.toList())
    }

    // Only a 404 failing page 0 means "no export"; a later 404 is a failed page.
    @Test
    fun `a 404 on a later export page is a failed page, not the list path`() = runTest {
        val api = newApi()
        exportPage = { page -> if (page == 0) exportOf(ids(0..49), total = 120) else notFound }
        exportOldPage = { notFound }

        val inventory = api.inventory()

        assertEquals(404, ((inventory.incomplete as Incomplete.Failed).error as ProtonError.Api).http)
        assertTrue(requests.none { it.startsWith("$LIST?") }, requests.toString())
    }

    @Test
    fun `a failed first page other than 404 ends it, without the list path`() = runTest {
        val api = newApi()
        exportPage = { response(422, """{"Code":2003}""") }

        val inventory = api.inventory()

        assertEquals(2003, ((inventory.incomplete as Incomplete.Failed).error as ProtonError.Api).code)
        assertEquals(listOf("$EXPORT?Page=0"), requests.toList())
    }

    // The page cap: Page 0–999 without reaching the last page; Page 1000 is never requested. A thousand
    // requests over loopback take about 40 s, so this one rule runs against a stand-in for the client.
    @ParameterizedTest
    @ValueSource(strings = ["none", "60000"])
    fun `1000 full pages without the last one are incomplete`(total: String) = runTest {
        val pages = CopyOnWriteArrayList<Int>()
        val api = ExportOnly { page, pageSize ->
            assertEquals(50, pageSize)
            pages += page
            ContactExportPageDto(ids(page * 50 until page * 50 + 50).map { ContactDto(it, emptyList()) }, total.toIntOrNull())
        }

        val inventory = api.inventory()

        assertEquals(Incomplete.PageCap, inventory.incomplete)
        assertEquals(50_000, inventory.contacts.size)
        assertEquals(List(1000) { it }, pages.toList())
    }

    /** A client that only answers export pages. */
    private class ExportOnly(private val export: (page: Int, pageSize: Int) -> ContactExportPageDto) : ProtonApi {
        override suspend fun exportContacts(page: Int, pageSize: Int) = export(page, pageSize)
        override suspend fun authInfo(username: String) = error("unused")
        override suspend fun auth(request: SrpAuthDto, captcha: CaptchaHeaders?) = error("unused")
        override suspend fun submitTotp(code: String) = error("unused")
        override suspend fun logout() = error("unused")
        override suspend fun user() = error("unused")
        override suspend fun keySalts() = error("unused")
        override suspend fun listContacts(page: Int, pageSize: Int) = error("unused")
        override suspend fun contact(id: ProtonContactId) = error("unused")
        override suspend fun createContact(cards: List<CardDto>): CreateReply = error("unused")
        override suspend fun updateContact(id: ProtonContactId, cards: List<CardDto>): Unit = error("unused")
        override suspend fun deleteContact(id: ProtonContactId): Unit = error("unused")
        override suspend fun latestEventId(): EventId = error("unused")
        override suspend fun events(cursor: EventId): EventsReply = error("unused")
    }

    // The list + detail path: export answers 404 on both spellings.
    @Test
    fun `without export, C1 pages and C2 details give the same inventory`() = runTest {
        val api = newApi()
        listPage = { listPageOf(listOf("a", "b", "c"), total = 3) }
        detail = { id -> response(200, """{"Code":1000,"Contact":${contact(id)}}""") }

        val inventory = api.inventory()

        assertNull(inventory.incomplete)
        assertEquals(listOf("a", "b", "c"), inventory.contacts.map { it.id })
        assertEquals("data-b", inventory.contacts[1].cards.single().data)
        assertEquals(
            listOf("$EXPORT?Page=0", "$EXPORT_OLD?Page=0", "$LIST?Page=0", "$LIST/a", "$LIST/b", "$LIST/c"),
            requests.toList(),
        )
    }

    @Test
    fun `the list path pages by the same rules, and still fetches the IDs it has`() = runTest {
        val api = newApi()
        listPage = { listPageOf(ids(0..49), total = null) }
        detail = { id -> response(200, """{"Code":1000,"Contact":${contact(id)}}""") }

        val inventory = api.inventory()

        assertEquals(Incomplete.RepeatedPage, inventory.incomplete)
        assertEquals(ids(0..49), inventory.contacts.map { it.id })
    }

    // Like any incomplete listing, a failed one still fetches the IDs it has.
    @Test
    fun `a failed list page still fetches details for the IDs it has, and stays failed`() = runTest {
        val api = newApi()
        listPage = { page -> if (page == 0) listPageOf(ids(0..49), total = null) else response(422, """{"Code":2001}""") }
        detail = { id -> response(200, """{"Code":1000,"Contact":${contact(id)}}""") }

        val inventory = api.inventory()

        assertEquals(422, ((inventory.incomplete as Incomplete.Failed).error as ProtonError.Api).http)
        assertEquals(ids(0..49), inventory.contacts.map { it.id })
    }

    // A contact that's gone is skipped; any other failure stops. The captured answers for a contact that doesn't exist
    // and an invalid ID.
    @ParameterizedTest
    @ValueSource(strings = ["contact-2501-does-not-exist", "contact-2061-invalid-id"])
    fun `a contact that's gone or invalid is skipped, and the others are fetched`(fixture: String) = runTest {
        val api = newApi()
        listPage = { listPageOf(listOf("a", "b", "c"), total = 3) }
        detail = { id -> if (id == "b") fixtureResponse(fixture) else response(200, """{"Code":1000,"Contact":${contact(id)}}""") }

        val inventory = api.inventory()

        assertEquals(Incomplete.ContactsGone, inventory.incomplete)
        assertEquals(listOf("a", "c"), inventory.contacts.map { it.id })
    }

    @ParameterizedTest
    @ValueSource(ints = [403, 503])
    fun `any other failed detail stops fetching`(status: Int) = runTest {
        val api = newApi()
        listPage = { listPageOf(listOf("a", "b", "c"), total = 3) }
        detail = { id -> if (id == "b") response(status, """{"Code":$status}""") else response(200, """{"Code":1000,"Contact":${contact(id)}}""") }

        val inventory = api.inventory()

        assertEquals(status, ((inventory.incomplete as Incomplete.Failed).error as ProtonError.Api).http)
        assertEquals(listOf("a"), inventory.contacts.map { it.id })
        assertTrue("$LIST/c" !in requests, requests.toString())
    }

    @Test
    fun `a paging reason is kept when a contact then turns out gone`() = runTest {
        val api = newApi()
        listPage = { listPageOf(ids(0..49), total = null) }
        detail = { id -> if (id == "c-3") response(422, """{"Code":2501}""") else response(200, """{"Code":1000,"Contact":${contact(id)}}""") }

        val inventory = api.inventory()

        assertEquals(Incomplete.RepeatedPage, inventory.incomplete)
        assertEquals(49, inventory.contacts.size)
    }

    @Test
    fun `a failure after a contact that's gone is reported as the failure`() = runTest {
        val api = newApi()
        listPage = { listPageOf(listOf("a", "b", "c"), total = 3) }
        detail = { id ->
            when (id) {
                "a" -> response(422, """{"Code":2501}""")
                "b" -> response(403, """{"Code":403}""")
                else -> response(200, """{"Code":1000,"Contact":${contact(id)}}""")
            }
        }

        assertTrue(api.inventory().incomplete is Incomplete.Failed)
    }

    @Test
    fun `a detail that answers for another ID is a bad response and stops fetching`() = runTest {
        val api = newApi()
        listPage = { listPageOf(listOf("a", "b", "c"), total = 3) }
        detail = { id -> response(200, """{"Code":1000,"Contact":${contact(if (id == "b") "z" else id)}}""") }

        val inventory = api.inventory()

        assertTrue((inventory.incomplete as Incomplete.Failed).error is ProtonError.BadResponse)
        assertEquals(listOf("a"), inventory.contacts.map { it.id })
        assertTrue("$LIST/c" !in requests, requests.toString())
    }

    @Test
    fun `cancelling stops the inventory and sends nothing more`() = runTest {
        val api = newApi()
        exportPage = { page -> exportOf(ids(page * 50 until page * 50 + 50), total = null) }
        val job = launch { api.inventory() }

        // Run until a few pages are in, moving the clock for pacing.
        val deadline = System.nanoTime() + 5_000_000_000
        while (requests.size < 3) {
            check(System.nanoTime() < deadline) { "timed out" }
            advanceTimeBy(100.milliseconds)
            runCurrent()
            Thread.sleep(2)
        }
        job.cancel()
        advanceUntilIdle()
        val sent = requests.size
        Thread.sleep(100)

        assertTrue(job.isCancelled)
        assertEquals(sent, requests.size)
        assertTrue(sent < 1000)
    }

    // findByUid, for a create whose outcome is unknown (docs/sync.md § Writes): C1 lists each contact's UID.

    private fun listed(vararg entries: Pair<String, String?>) =
        entries.map { (id, uid) -> """{"ID":"$id","Name":"n"${uid?.let { ""","UID":"$it"""" } ?: ""}}""" }

    @Test
    fun `a UID is looked up by paging C1, matching it exactly`() = runTest {
        val api = newApi()
        val first = listed("a" to "urn:uuid:test-1", "b" to "urn:uuid:test-2", "c" to null) + ids(0..46).map { """{"ID":"$it","UID":"other"}""" }
        listPage = { page -> if (page == 0) page(first, total = 52) else page(listed("d" to "URN:UUID:TEST-1", "e" to "urn:uuid:test-1 "), total = 52) }

        val found = findByUid(api, "urn:uuid:test-1")

        assertNull(found.incomplete)
        assertEquals(listOf(ProtonContactId("a")), found.ids, "not another case, nor with a space, nor a contact without a UID")
        assertEquals(listOf("$LIST?Page=0", "$LIST?Page=1"), requests.toList(), "C1 alone: no export, no details")
    }

    @Test
    fun `a UID on no contact, or on several, gives what C1 lists`() = runTest {
        val api = newApi()
        listPage = { page(listed("a" to "urn:uuid:test-1", "b" to "urn:uuid:test-2", "c" to "urn:uuid:test-1"), total = 3) }

        assertEquals(emptyList<ProtonContactId>(), findByUid(api, "urn:uuid:test-9").ids)
        assertEquals(listOf(ProtonContactId("a"), ProtonContactId("c")), findByUid(api, "urn:uuid:test-1").ids)
    }

    @Test
    fun `a UID lookup pages by the inventory's rules, and says why it's incomplete`() = runTest {
        val api = newApi()
        listPage = { page(listed("a" to "urn:uuid:test-1") + ids(0..48).map { """{"ID":"$it"}""" }, total = null) }

        val repeated = findByUid(api, "urn:uuid:test-1")
        assertEquals(Incomplete.RepeatedPage, repeated.incomplete)
        assertEquals(listOf(ProtonContactId("a")), repeated.ids)

        listPage = { page -> if (page == 0) page(listed("a" to "urn:uuid:test-1") + ids(0..48).map { """{"ID":"$it"}""" }, total = 60) else response(503, """{"Code":503}""") }
        val failed = findByUid(api, "urn:uuid:test-1")
        assertTrue((failed.incomplete as Incomplete.Failed).error is ProtonError.Api)
        assertEquals(listOf(ProtonContactId("a")), failed.ids, "the pages before it are kept")
    }

    private companion object {
        const val EXPORT = "/contacts/v4/contacts/export"
        const val EXPORT_OLD = "/contacts/v4/export"
        const val LIST = "/contacts/v4/contacts"
    }
}
