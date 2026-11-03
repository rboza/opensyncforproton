package dev.rboza.android.opensyncforproton.proton

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** E1 and E2 (docs/protocol.md § Routes and § JSON), read like every GET. */
class ProtonEventTest {
    private val server = MockWebServer()
    private val store = object : TokenStore {
        @Volatile var tokens: Tokens? = Tokens(uid = "uid-1", access = "access-1", refresh = "refresh-1")
        val rejectedUids = CopyOnWriteArrayList<String>()

        override fun current() = tokens
        override fun replace(tokens: Tokens) {
            this.tokens = tokens
        }
        override fun onSessionRejected(uid: String) {
            rejectedUids += uid
            tokens = null
        }
    }
    private lateinit var api: ProtonApi

    @BeforeEach
    fun start() {
        server.start()
        api = ProtonApiFactory.create(ProtonConfig(baseUrl = server.url("/"), appVersion = "Other"), store)
    }

    @AfterEach
    fun stop() = server.close()

    private fun fixture(name: String) =
        Json.parseToJsonElement(checkNotNull(javaClass.getResource("/$name.json")) { "fixtures/http/$name.json is missing" }.readText())
            .jsonObject

    private fun fixtureBody(name: String) = fixture(name).getValue("body").jsonObject

    /** Replays a captured fixture: its status and body. */
    private fun respond(name: String) {
        val json = fixture(name)
        respond(json.getValue("status").toString().toInt(), json.getValue("body").toString())
    }

    private fun respond(status: Int, body: String) = server.enqueue(response(status, body))

    private fun response(status: Int, body: String) = MockResponse.Builder().code(status).body(body).build()

    private fun take(): RecordedRequest = checkNotNull(server.takeRequest(1, TimeUnit.SECONDS)) { "no request" }

    private val cursor = EventId("cursor-1")

    /** A page with these fields on top of the ones every page carries. */
    private fun page(vararg fields: String, more: String = "0", refresh: String = "0", eventId: String = "\"next-1\"") =
        """{"Code":1000,"EventID":$eventId,"More":$more,"Refresh":$refresh${fields.joinToString("") { ",$it" }}}"""

    private suspend fun events(): EventPageDto = assertInstanceOf(EventsReply.Page::class.java, api.events(cursor)).page

    @Test
    fun `the latest event ID is E1's EventID`() = runTest {
        respond("events-latest")

        val latest = api.latestEventId()

        assertEquals(fixtureBody("events-latest").getValue("EventID").jsonPrimitive.content, latest.value)
        val request = take()
        assertEquals("GET /core/v4/events/latest", "${request.method} ${request.url.encodedPath}")
        assertNull(request.url.encodedQuery)
        assertEquals("Bearer access-1", request.headers["Authorization"])
        assertEquals("uid-1", request.headers["x-pm-uid"])
        assertEquals("Other", request.headers["x-pm-appversion"])
    }

    @Test
    fun `a latest reply without an EventID, or with a blank one, is a bad response`() = runTest {
        val replies = listOf("""{"Code":1000}""", """{"Code":1000,"EventID":""}""", """{"Code":1000,"EventID":null}""")
        for (reply in replies) respond(200, reply)

        for (reply in replies) assertEquals(200, assertThrows<ProtonError.BadResponse>(reply) { api.latestEventId() }.http)
    }

    @Test
    fun `a latest reply answered 2061 is a plain error`() = runTest {
        respond(400, """{"Code":2061}""")

        assertEquals(400 to 2061, assertThrows<ProtonError.Api> { api.latestEventId() }.let { it.http to it.code })
    }

    // Event pages, with the captured fixtures.
    @Test
    fun `an event page is read from E2, with the cursor in its path`() = runTest {
        respond("events")

        events()

        val request = take()
        assertEquals("GET /core/v5/events/cursor-1", "${request.method} ${request.url.encodedPath}")
        assertNull(request.url.encodedQuery)
        assertEquals("Bearer access-1", request.headers["Authorization"])
        assertEquals("uid-1", request.headers["x-pm-uid"])
    }

    @Test
    fun `the captured page gives its EventID, More, Refresh, and the contact entries`() = runTest {
        respond("events")
        val body = fixtureBody("events")

        val page = events()

        assertEquals(body.getValue("EventID").jsonPrimitive.content, page.eventId.value)
        assertFalse(page.more)
        assertEquals(0, page.refresh)
        assertFalse(page.userChanged)
        assertFalse(page.addressesChanged)
        val contacts = body.getValue("Contacts").jsonArray.map { it.jsonObject }
        assertEquals(contacts.map { it.getValue("ID").jsonPrimitive.content to it.getValue("Action").jsonPrimitive.content.toInt() },
            page.contacts.map { it.id to it.action })
        assertEquals(listOf(1, 1, 1, 1, 0, 0, 0), page.contacts.map { it.action })
        for ((entry, sent) in page.contacts.zip(contacts)) {
            val sentContact = sent["Contact"]?.jsonObject
            if (sentContact == null) {
                assertNull(entry.contact)
                continue
            }
            val contact = checkNotNull(entry.contact)
            assertEquals(sentContact.getValue("ID").jsonPrimitive.content, contact.id)
            assertEquals(cardsOf(sentContact), contact.cards)
        }
        val emails = body.getValue("ContactEmails").jsonArray.map { it.jsonObject }
        assertEquals(emails.map { it.getValue("ID").jsonPrimitive.content to it.getValue("Action").jsonPrimitive.content.toInt() },
            page.contactEmails.map { it.id to it.action })
    }

    private fun cardsOf(contact: JsonObject) = contact.getValue("Cards").jsonArray.map { it.jsonObject }.map {
        CardDto(it.getValue("Type").jsonPrimitive.content.toInt(), it.getValue("Data").jsonPrimitive.content,
            it.getValue("Signature").jsonPrimitive.content)
    }

    @Test
    fun `the captured update page gives one Action 2 with the whole contact, its cards in the order sent`() = runTest {
        respond("events-update")
        val sent = fixtureBody("events-update").getValue("Contacts").jsonArray.single().jsonObject.getValue("Contact").jsonObject

        val entry = events().contacts.single()

        assertEquals(2, entry.action)
        // The web app wrote the encrypted card first: card order isn't fixed, so it's kept as sent.
        assertEquals(listOf(3, 2), checkNotNull(entry.contact).cards.map { it.type })
        assertEquals(cardsOf(sent), entry.contact?.cards)
    }

    @Test
    fun `a page without contact entries has none`() = runTest {
        respond(200, page())

        val page = events()

        assertEquals(emptyList<ContactEventDto>(), page.contacts)
        assertEquals(emptyList<ContactEmailEventDto>(), page.contactEmails)
    }

    @Test
    fun `More 1 means fetch again, and any other value than 0 or 1 is a bad response`() = runTest {
        respond(200, page(more = "1"))
        for (more in listOf("2", "-1", "true", "null")) respond(200, page(more = more))

        assertTrue(events().more)
        for (more in listOf("2", "-1", "true", "null")) assertThrows<ProtonError.BadResponse>(more) { api.events(cursor) }
    }

    @Test
    fun `Refresh is the bit field as sent`() = runTest {
        for (refresh in listOf(1, 255, 2)) respond(200, page(refresh = "$refresh"))

        assertEquals(listOf(1, 255, 2), List(3) { events().refresh })
    }

    @Test
    fun `a User or a nonempty Addresses means keys may have changed`() = runTest {
        respond(200, page(""""User":{"ID":"user-1","Keys":[]}"""))
        respond(200, page(""""Addresses":[{"ID":"address-1","Action":1,"Address":{"ID":"address-1"}}]"""))
        respond(200, page(""""Addresses":[{"ID":"address-1","Action":0}]"""))
        respond(200, page(""""User":null""", """"Addresses":[]"""))

        assertEquals(true to false, events().let { it.userChanged to it.addressesChanged })
        assertEquals(false to true, events().let { it.userChanged to it.addressesChanged })
        assertEquals(false to true, events().let { it.userChanged to it.addressesChanged })
        assertEquals(false to false, events().let { it.userChanged to it.addressesChanged })
    }

    @Test
    fun `every other field is ignored`() = runTest {
        respond(
            200,
            page(
                """"Labels":[{"ID":"label-1","Action":1}]""", """"MailSettings":{"x":1}""", """"Messages":[{"ID":"m","Action":3}]""",
                """"Notices":["n"]""", """"Notifications":[{"ID":"n"}]""", """"UsedSpace":1""", """"EventOrder":7""",
                """"ContactEmails":[{"ID":"email-1","Action":1,"ContactEmail":{"Email":"a@example.test","Unknown":[1]}}]""",
                """"Contacts":[{"ID":"contact-1","Action":2,"Extra":1,"Contact":{"ID":"contact-1","Cards":[],"Name":"n","Size":1}}]""",
            ),
        )

        val page = events()

        assertEquals(listOf("contact-1" to 2), page.contacts.map { it.id to it.action })
        assertEquals(listOf("email-1" to 1), page.contactEmails.map { it.id to it.action })
        assertFalse(page.userChanged)
        assertFalse(page.addressesChanged)
    }

    @Test
    fun `a page without an EventID, More, or Refresh, or with a blank EventID, is a bad response`() = runTest {
        val replies = listOf(
            """{"Code":1000,"More":0,"Refresh":0}""",
            """{"Code":1000,"EventID":"next-1","Refresh":0}""",
            """{"Code":1000,"EventID":"next-1","More":0}""",
            page(eventId = "\"\""),
            page(eventId = "null"),
        )
        for (reply in replies) respond(200, reply)

        for (reply in replies) assertEquals(200, assertThrows<ProtonError.BadResponse>(reply) { api.events(cursor) }.http)
    }

    @Test
    fun `an entry without its ID or Action is a bad response`() = runTest {
        val replies = listOf(
            page(""""Contacts":[{"Action":0}]"""),
            page(""""Contacts":[{"ID":"contact-1"}]"""),
            page(""""ContactEmails":[{"Action":0}]"""),
            page(""""ContactEmails":[{"ID":"email-1"}]"""),
            page(""""Contacts":[{"ID":"contact-1","Action":1,"Contact":{"ID":"contact-1"}}]"""),
        )
        for (reply in replies) respond(200, reply)

        for (reply in replies) assertThrows<ProtonError.BadResponse>(reply) { api.events(cursor) }
    }

    @Test
    fun `a page whose Code isn't 1000 is an error`() = runTest {
        respond(200, page().replace("\"Code\":1000", "\"Code\":1001"))

        assertEquals(1001, assertThrows<ProtonError.Api> { api.events(cursor) }.code)
    }

    // Proton's answer to an unknown or malformed cursor.
    @Test
    fun `HTTP 400 with Code 2061 is a rejected cursor`() = runTest {
        respond("events-2061-invalid-id")

        assertSame(EventsReply.CursorRejected, api.events(cursor))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `2061 with another status, or another Code on a 400, is a plain error`() = runTest {
        respond(422, """{"Code":2061}""")
        respond(400, """{"Code":2001}""")
        respond(422, """{"Code":2501}""")

        assertEquals(422 to 2061, assertThrows<ProtonError.Api> { api.events(cursor) }.let { it.http to it.code })
        assertEquals(400 to 2001, assertThrows<ProtonError.Api> { api.events(cursor) }.let { it.http to it.code })
        assertEquals(422 to 2501, assertThrows<ProtonError.Api> { api.events(cursor) }.let { it.http to it.code })
    }

    @Test
    fun `a cursor that would leave the events path is an invalid request, and nothing is sent`() = runTest {
        // A blank cursor would ask for the events collection itself.
        for (bad in listOf("..", ".", "")) {
            assertThrows<ProtonError.InvalidRequest>(bad) { api.events(EventId(bad)) }
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a cursor stays one path segment`() = runTest {
        respond(200, page())

        api.events(EventId("a/b?c#d"))

        assertEquals("/core/v5/events/a%2Fb%3Fc%23d", take().url.encodedPath)
    }

    // Event reads: refresh and no fallback spelling here; retries and pacing in ProtonRetryTest.
    private fun serve(answer: (RecordedRequest) -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = when (request.url.encodedPath) {
                "/core/v4/auth/refresh" -> response(200, refreshBody())
                else -> answer(request)
            }
        }
    }

    private fun refreshBody(): String {
        val body = fixtureBody("refresh").toMutableMap()
        body["AccessToken"] = JsonPrimitive("access-2")
        body["RefreshToken"] = JsonPrimitive("refresh-2")
        body["UID"] = JsonPrimitive("uid-1")
        body["Uid"] = JsonPrimitive("uid-1")
        return JsonObject(body).toString()
    }

    @Test
    fun `a 401 on an event read refreshes and resends it once`() = runTest {
        val sent = CopyOnWriteArrayList<String>()
        serve { request ->
            sent += "${request.url.encodedPath} ${request.headers["Authorization"]}"
            when {
                request.headers["Authorization"] != "Bearer access-2" -> response(401, """{"Code":401}""")
                request.url.encodedPath == "/core/v4/events/latest" -> response(200, """{"Code":1000,"EventID":"latest-1"}""")
                else -> response(200, page())
            }
        }

        assertEquals("latest-1", api.latestEventId().value)
        store.tokens = Tokens(uid = "uid-1", access = "access-1", refresh = "refresh-2")
        assertEquals("next-1", events().eventId.value)

        assertEquals(
            listOf(
                "/core/v4/events/latest Bearer access-1", "/core/v4/events/latest Bearer access-2",
                "/core/v5/events/cursor-1 Bearer access-1", "/core/v5/events/cursor-1 Bearer access-2",
            ),
            sent.toList(),
        )
        // The two refreshes.
        assertEquals(6, server.requestCount)
        assertEquals(listOf<String>(), store.rejectedUids)
    }

    @Test
    fun `a 404 on an event read is an error, and no other spelling is tried`() = runTest {
        respond(404, """{"Code":404}""")
        respond(404, """{"Code":404}""")

        assertEquals(404, assertThrows<ProtonError.Api> { api.latestEventId() }.http)
        assertEquals(404, assertThrows<ProtonError.Api> { api.events(cursor) }.http)

        assertEquals(listOf("/core/v4/events/latest", "/core/v5/events/cursor-1"), List(2) { take().url.encodedPath })
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `an event read without a session sends nothing`() = runTest {
        store.tokens = null

        assertThrows<ProtonError.NoSession> { api.latestEventId() }
        assertThrows<ProtonError.NoSession> { api.events(cursor) }
        assertEquals(0, server.requestCount)
    }

    // No ID or card in any toString.
    @Test
    fun `event values show none of their content`() = runTest {
        assertNoSecret(EventId("SECRET-cursor").toString())
        assertEquals("EventsReply.CursorRejected", EventsReply.CursorRejected.toString())
        val contact = """{"ID":"SECRET-id","UID":"SECRET-uid","Name":"SECRET-name","Cards":[{"Type":2,"Data":"SECRET-data","Signature":"SECRET-sig"}]}"""
        respond(
            200,
            page(
                """"Contacts":[{"ID":"SECRET-id","Action":2,"Contact":$contact}]""",
                """"ContactEmails":[{"ID":"SECRET-email-id","Action":1,"ContactEmail":{"Email":"SECRET@example.test"}}]""",
                """"User":{"ID":"SECRET-user"}""", """"Addresses":[{"ID":"SECRET-address","Action":1}]""",
                eventId = "\"SECRET-next\"",
            ),
        )
        respond(200, """{"Code":1000,"EventID":"SECRET-latest"}""")

        val reply = api.events(cursor)
        val page = assertInstanceOf(EventsReply.Page::class.java, reply).page
        for (text in listOf(reply, page, page.eventId, page.contacts.single(), page.contactEmails.single(), page.contacts.single().contact)) {
            assertNoSecret(text.toString())
        }
        assertNoSecret(api.latestEventId().toString())
        // A page built by hand, as :sync's fakes build them: no JSON types needed.
        val built = EventPageDto(
            eventId = EventId("SECRET-next"), more = false, refresh = 0,
            contacts = listOf(ContactEventDto("SECRET-id", 1, ContactDto("SECRET-id", listOf(CardDto(0, "SECRET-data"))))),
            contactEmails = listOf(ContactEmailEventDto("SECRET-email-id", 1)), userChanged = true, addressesChanged = false,
        )
        assertNoSecret(built.toString())
        assertNoSecret(EventsReply.Page(built).toString())

        respond(400, """{"Code":2061,"Error":"SECRET-error","Details":{"ID":"SECRET-cursor"}}""")
        respond(422, """{"Code":2061,"Error":"SECRET-error"}""")
        // A page that doesn't decode: kotlinx.serialization's message would echo it.
        respond(200, page(""""Contacts":[{"ID":["SECRET-id"],"Action":1}]"""))
        respond(200, """{"Code":1000,"EventID":["SECRET-latest"]}""")
        assertSame(EventsReply.CursorRejected, api.events(EventId("SECRET-cursor")))
        val errors = listOf<Throwable>(
            assertThrows<ProtonError.Api> { api.events(cursor) },
            assertThrows<ProtonError.BadResponse> { api.events(cursor) },
            assertThrows<ProtonError.BadResponse> { api.latestEventId() },
        )
        for (error in errors) {
            assertNoSecret(error.toString())
            assertNull(error.message)
            assertNull(error.cause)
        }
    }

    private fun assertNoSecret(text: String) = assertFalse("SECRET" in text, text)
}
