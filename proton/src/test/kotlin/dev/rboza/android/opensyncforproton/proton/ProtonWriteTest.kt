package dev.rboza.android.opensyncforproton.proton

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** C4–C6 (docs/protocol.md § Routes and § Errors and retries), each sent once. */
class ProtonWriteTest {
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

    /** Replays a captured fixture: its status and body. */
    private fun respond(name: String) {
        val json = fixture(name)
        respond(json.getValue("status").toString().toInt(), json.getValue("body").toString())
    }

    private fun respond(status: Int, body: String, vararg headers: Pair<String, String>) =
        server.enqueue(response(status, body, *headers))

    private fun response(status: Int, body: String, vararg headers: Pair<String, String>) =
        MockResponse.Builder().code(status).body(body).apply { headers.forEach { (name, value) -> addHeader(name, value) } }.build()

    private fun take(): RecordedRequest = checkNotNull(server.takeRequest(1, TimeUnit.SECONDS)) { "no request" }

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private val id = ProtonContactId("contact-1")
    private val cards = listOf(
        CardDto(type = 0, data = "clear-data"),
        CardDto(type = 2, data = "signed-data", signature = "signed-signature"),
        CardDto(type = 3, data = "encrypted-data", signature = "encrypted-signature"),
    )
    private val cardsJson = """
        [{"Type":0,"Data":"clear-data","Signature":null},
         {"Type":2,"Data":"signed-data","Signature":"signed-signature"},
         {"Type":3,"Data":"encrypted-data","Signature":"encrypted-signature"}]
    """

    /** A C4 reply with these items, in the captured replies' shape. */
    private fun createReply(vararg items: String) = """{"Code":1001,"Responses":[${items.joinToString(",")}]}"""

    private fun createItem(code: Int, index: Int = 0, contact: String? = """{"ID":"new-id"}""") =
        """{"Index":$index,"Response":{"Code":$code${contact?.let { ""","Contact":$it""" } ?: ""}}}"""

    /** A C6 reply with these items, in the captured replies' shape. */
    private fun deleteReply(vararg items: String) = """{"Code":1001,"Responses":[${items.joinToString(",")}]}"""

    private fun deleteItem(code: Int, id: String = "contact-1") = """{"ID":"$id","Response":{"Code":$code}}"""

    @Test
    fun `a create posts one contact's cards, with Overwrite, Labels, and Import all 0`() = runTest {
        respond("contact-create")

        api.createContact(cards)

        val request = take()
        assertEquals("POST /contacts/v4/contacts", "${request.method} ${request.url.encodedPath}")
        assertNull(request.url.encodedQuery)
        assertEquals(
            json("""{"Contacts":[{"Cards":$cardsJson}],"Overwrite":0,"Labels":0,"Import":0}"""),
            json(request.body!!.utf8()),
        )
        assertEquals("Bearer access-1", request.headers["Authorization"])
        assertEquals("uid-1", request.headers["x-pm-uid"])
        assertEquals("Other", request.headers["x-pm-appversion"])
    }

    // Create replies, with the captured fixtures.
    @Test
    fun `a create's item Code 1000 is created, with the new contact's ID`() = runTest {
        respond("contact-create")
        respond(200, createReply(createItem(1000)))

        val fixtureId = fixture("contact-create").getValue("body").jsonObject.getValue("Responses").jsonArray.single().jsonObject
            .getValue("Response").jsonObject.getValue("Contact").jsonObject.getValue("ID").jsonPrimitive.content
        assertEquals(fixtureId, assertInstanceOf(CreateReply.Created::class.java, api.createContact(cards)).id.value)
        assertEquals("new-id", assertInstanceOf(CreateReply.Created::class.java, api.createContact(cards)).id.value)
    }

    @Test
    fun `a created reply takes the contact's ID, not a contact e-mail's`() = runTest {
        respond(200, createReply(createItem(1000, contact = """{"ContactEmails":[{"ID":"email-id","ContactID":"new-id"}],"ID":"new-id"}""")))

        assertEquals("new-id", assertInstanceOf(CreateReply.Created::class.java, api.createContact(cards)).id.value)
    }

    @Test
    fun `a create's item Code 2500 means the UID exists, and 2001 a refused card`() = runTest {
        respond("contact-create-2500-same-uid")
        respond("contact-create-2001-refused-card")

        assertSame(CreateReply.UidExists, api.createContact(cards))
        assertSame(CreateReply.CardRefused, api.createContact(cards))
    }

    @ParameterizedTest
    @ValueSource(ints = [2501, 2061, 2000, 1001, 9001, 5002])
    fun `any other item Code is an error with that Code`(code: Int) = runTest {
        respond(200, createReply(createItem(code, contact = null)))

        val error = assertThrows<ProtonError.Api> { api.createContact(cards) }

        assertEquals(200 to code, error.http to error.code)
        // An item's Code isn't the reply's: an app-version Code in an item doesn't stop the client.
        respond("contact-create")
        api.createContact(cards)
    }

    @Test
    fun `a create reply without exactly one item for index 0, or without the created contact, is a bad response`() = runTest {
        val replies = listOf(
            createReply(),
            createReply(createItem(1000), createItem(1000, index = 1)),
            createReply(createItem(1000, index = 1)),
            createReply(createItem(1000, contact = null)),
            createReply(createItem(1000, contact = "{}")),
            createReply(createItem(1000, contact = """{"ID":""}""")),
            """{"Code":1001}""",
        )
        for (reply in replies) respond(200, reply)

        for (reply in replies) assertEquals(200, assertThrows<ProtonError.BadResponse>(reply) { api.createContact(cards) }.http)
    }

    @Test
    fun `a create reply whose own Code isn't 1001 is an error`() = runTest {
        respond(200, """{"Code":1000,"Responses":[${createItem(1000)}]}""")
        respond(422, """{"Code":2001,"Error":"x"}""")

        assertEquals(200 to 1000, assertThrows<ProtonError.Api> { api.createContact(cards) }.let { it.http to it.code })
        assertEquals(422 to 2001, assertThrows<ProtonError.Api> { api.createContact(cards) }.let { it.http to it.code })
    }

    @Test
    fun `an update puts the cards on the contact's path, and 1000 is updated`() = runTest {
        respond("contact-update")

        api.updateContact(ProtonContactId("a-b_c="), cards)

        val request = take()
        assertEquals("PUT /contacts/v4/contacts/a-b_c=", "${request.method} ${request.url.encodedPath}")
        assertEquals(json("""{"Cards":$cardsJson}"""), json(request.body!!.utf8()))
        assertEquals("Bearer access-1", request.headers["Authorization"])
        assertEquals("uid-1", request.headers["x-pm-uid"])
    }

    @Test
    fun `an update answered 2501 is gone, and 2061 an invalid ID`() = runTest {
        respond("contact-update-2501-does-not-exist")
        respond("contact-update-2061-invalid-id")

        assertEquals(422 to 2501, assertThrows<ProtonError.Api> { api.updateContact(id, cards) }.let { it.http to it.code })
        assertEquals(400 to 2061, assertThrows<ProtonError.Api> { api.updateContact(id, cards) }.let { it.http to it.code })
    }

    @Test
    fun `an update answered with another Code is an error, and one without a Code a bad response`() = runTest {
        respond(200, """{"Code":1001}""")
        respond(200, """{"Contact":{}}""")

        assertEquals(1001, assertThrows<ProtonError.Api> { api.updateContact(id, cards) }.code)
        assertThrows<ProtonError.BadResponse> { api.updateContact(id, cards) }
    }

    @Test
    fun `a delete puts the one ID, and item Code 1000 is deleted`() = runTest {
        respond(200, deleteReply(deleteItem(1000)))

        api.deleteContact(id)

        val request = take()
        assertEquals("PUT /contacts/v4/contacts/delete", "${request.method} ${request.url.encodedPath}")
        assertEquals(json("""{"IDs":["contact-1"]}"""), json(request.body!!.utf8()))
        assertEquals("Bearer access-1", request.headers["Authorization"])
        assertEquals("uid-1", request.headers["x-pm-uid"])
    }

    @Test
    fun `the captured delete replies, for a contact and for one already gone, are deleted`() = runTest {
        // The fixtures' IDs are placeholders, so each reply is sent the ID it names.
        for (name in listOf("contacts-delete", "contacts-delete-already-deleted")) {
            respond(name)
            val replied = fixture(name).getValue("body").jsonObject.getValue("Responses").jsonArray.single().jsonObject
                .getValue("ID").jsonPrimitive.content
            api.deleteContact(ProtonContactId(replied))
        }
    }

    @Test
    fun `a delete answered 2061 for the whole request is an invalid ID`() = runTest {
        respond("contacts-delete-2061-invalid-id")

        assertEquals(400 to 2061, assertThrows<ProtonError.Api> { api.deleteContact(id) }.let { it.http to it.code })
    }

    @Test
    fun `a delete's other item Code is an error with that Code`() = runTest {
        respond(200, deleteReply(deleteItem(2501)))

        assertEquals(200 to 2501, assertThrows<ProtonError.Api> { api.deleteContact(id) }.let { it.http to it.code })
    }

    @Test
    fun `a delete reply without exactly one item for the ID sent is a bad response`() = runTest {
        val replies = listOf(
            deleteReply(),
            deleteReply(deleteItem(1000), deleteItem(1000, id = "contact-2")),
            deleteReply(deleteItem(1000, id = "contact-2")),
            """{"Code":1001}""",
        )
        for (reply in replies) respond(200, reply)

        for (reply in replies) assertEquals(200, assertThrows<ProtonError.BadResponse>(reply) { api.deleteContact(id) }.http)
    }

    @Test
    fun `the captured two-item delete reply is a bad response for one ID`() = runTest {
        respond("contacts-delete-two")
        // Both items carry the same placeholder ID, so only the item count can refuse it.
        val replied = fixture("contacts-delete-two").getValue("body").jsonObject.getValue("Responses").jsonArray.first().jsonObject
            .getValue("ID").jsonPrimitive.content

        assertThrows<ProtonError.BadResponse> { api.deleteContact(ProtonContactId(replied)) }
    }

    @Test
    fun `a delete reply whose own Code isn't 1001 is an error`() = runTest {
        respond(200, """{"Code":1000,"Responses":[${deleteItem(1000)}]}""")

        assertEquals(1000, assertThrows<ProtonError.Api> { api.deleteContact(id) }.code)
    }

    // The outcome of a lost connection, a timeout, or a 5xx is unknown, and nothing is resent.
    private val writes: List<Pair<String, suspend () -> Unit>> = listOf(
        "create" to { api.createContact(cards) },
        "update" to { api.updateContact(id, cards) },
        "delete" to { api.deleteContact(id) },
    )

    @ParameterizedTest
    @ValueSource(ints = [500, 502, 503])
    fun `a 5xx on a write is its error, and nothing is resent`(status: Int) = runTest {
        for ((name, write) in writes) {
            respond(status, """{"Code":$status}""")
            assertEquals(status, assertThrows<ProtonError.Api>(name) { write() }.http)
        }
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `a lost connection on a write is a network error, and nothing is resent`() = runTest {
        for ((name, write) in writes) {
            server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
            assertThrows<ProtonError.Network>(name) { write() }
        }
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `a write that times out is a network error, and nothing is resent`() = runBlocking {
        // Longer than OkHttp's 10 s read timeout.
        server.enqueue(MockResponse.Builder().body(createReply(createItem(1000))).headersDelay(15, TimeUnit.SECONDS).build())

        assertThrows<ProtonError.Network> { api.createContact(cards) }

        assertEquals(1, server.requestCount)
    }

    // OkHttp's own resends, for writes: a pooled connection the server closed, and a 503 with Retry-After 0.
    @Test
    fun `OkHttp doesn't resend a write on a dead pooled connection`() = runTest {
        server.enqueue(response(200, deleteReply(deleteItem(1000))).newBuilder().onResponseEnd(SocketEffect.CloseSocket()).build())
        respond(200, deleteReply(deleteItem(1000)))

        api.deleteContact(id)
        assertThrows<ProtonError.Network> { api.updateContact(id, cards) }

        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a 503 with Retry-After 0 on a write reaches the server once`() = runTest {
        for ((name, write) in writes) {
            respond(503, """{"Code":503}""", "Retry-After" to "0")
            assertEquals(503, assertThrows<ProtonError.Api>(name) { write() }.http)
        }
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `a 429 on a write is rate limited with its delay, and nothing is resent`() = runTest {
        for ((name, write) in writes) {
            respond(429, """{"Code":2028}""", "Retry-After" to "7")
            assertEquals(7.seconds, assertThrows<ProtonError.RateLimited>(name) { write() }.retryAfter)
        }
        assertEquals(3, server.requestCount)
    }

    // A 401: the server acted on nothing, so the write refreshes and resends once.
    private fun serve(write: (RecordedRequest) -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = when (request.url.encodedPath) {
                "/core/v4/auth/refresh" -> response(200, refreshBody())
                else -> write(request)
            }
        }
    }

    private fun refreshBody(): String {
        val body = fixture("refresh").getValue("body").jsonObject.toMutableMap()
        body["AccessToken"] = JsonPrimitive("access-2")
        body["RefreshToken"] = JsonPrimitive("refresh-2")
        body["UID"] = JsonPrimitive("uid-1")
        body["Uid"] = JsonPrimitive("uid-1")
        return JsonObject(body).toString()
    }

    private val unauthorized get() = response(401, """{"Code":401}""")

    @Test
    fun `a 401 on a write refreshes and resends it once`() = runTest {
        val sent = CopyOnWriteArrayList<String>()
        serve { request ->
            sent += "${request.method} ${request.url.encodedPath} ${request.headers["Authorization"]} ${request.body?.utf8()}"
            when {
                request.headers["Authorization"] != "Bearer access-2" -> unauthorized
                request.url.encodedPath == "/contacts/v4/contacts" -> response(200, createReply(createItem(1000)))
                request.url.encodedPath == "/contacts/v4/contacts/delete" -> response(200, deleteReply(deleteItem(1000)))
                else -> response(200, """{"Code":1000}""")
            }
        }

        assertInstanceOf(CreateReply.Created::class.java, api.createContact(cards))
        val create = sent.toList()
        // The other two meet a 401 too: each goes out with the old access token.
        store.tokens = Tokens(uid = "uid-1", access = "access-1", refresh = "refresh-2")
        api.updateContact(id, cards)
        store.tokens = Tokens(uid = "uid-1", access = "access-1", refresh = "refresh-2")
        api.deleteContact(id)

        assertEquals(2, create.size)
        assertEquals(create[0].replace("access-1", "access-2"), create[1], "the same request, with the new token")
        assertEquals(
            listOf(
                "POST /contacts/v4/contacts", "POST /contacts/v4/contacts",
                "PUT /contacts/v4/contacts/contact-1", "PUT /contacts/v4/contacts/contact-1",
                "PUT /contacts/v4/contacts/delete", "PUT /contacts/v4/contacts/delete",
            ),
            sent.map { it.split(' ').take(2).joinToString(" ") },
        )
        assertEquals(listOf<String>(), store.rejectedUids)
    }

    @Test
    fun `a 401 on a write's resend rejects the session, and nothing more is sent`() = runTest {
        val sent = CopyOnWriteArrayList<String>()
        serve { request -> sent += request.url.encodedPath; unauthorized }

        assertThrows<ProtonError.SessionRejected> { api.createContact(cards) }
        assertThrows<ProtonError.SessionRejected> { api.deleteContact(id) }

        assertEquals(listOf("/contacts/v4/contacts", "/contacts/v4/contacts"), sent.toList())
        assertEquals(listOf("uid-1"), store.rejectedUids.toList())
    }

    @Test
    fun `a 404 on a write is an error, and the old spelling is never tried`() = runTest {
        for ((name, write) in writes) {
            respond(404, """{"Code":404}""")
            assertEquals(404, assertThrows<ProtonError.Api>(name) { write() }.http)
        }

        assertEquals(
            listOf("/contacts/v4/contacts", "/contacts/v4/contacts/contact-1", "/contacts/v4/contacts/delete"),
            List(3) { take().url.encodedPath },
        )
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `a write without a session sends nothing`() = runTest {
        store.tokens = null

        for ((name, write) in writes) assertThrows<ProtonError.NoSession>(name) { write() }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `an ID that would leave the contact's path is an invalid request, and nothing is sent`() = runTest {
        // A blank ID would put the cards on the collection's path.
        for (bad in listOf("..", ".", "")) {
            assertThrows<ProtonError.InvalidRequest>(bad) { api.updateContact(ProtonContactId(bad), cards) }
        }
        assertEquals(0, server.requestCount)
    }

    // No card, ID, UID, or address in a request's, reply's, or error's toString.
    @Test
    fun `write values show none of their content`() = runTest {
        val secretCards = listOf(CardDto(type = 2, data = "SECRET-data", signature = "SECRET-signature"))
        assertNoSecret(CreateContactsRequestDto.of(secretCards).toString())
        assertNoSecret(CardsRequestDto.of(secretCards).toString())
        assertNoSecret(DeleteContactsRequestDto.of(ProtonContactId("SECRET-id")).toString())
        assertNoSecret(CreateReply.Created(ProtonContactId("SECRET-id")).toString())
        assertEquals("CreateReply.UidExists", CreateReply.UidExists.toString())
        assertEquals("CreateReply.CardRefused", CreateReply.CardRefused.toString())

        val created = """{"ID":"SECRET-id","UID":"SECRET-uid","Name":"SECRET-name","ContactEmails":[{"Email":"SECRET@example.test"}]}"""
        val createDto = protonJson.decodeFromString(CreateContactsResponseDto.serializer(), createReply(createItem(1000, contact = created)))
        assertNoSecret(createDto.toString())
        assertNoSecret(createDto.responses.single().toString())
        assertNoSecret(createDto.responses.single().response.toString())
        val deleteDto = protonJson.decodeFromString(DeleteContactsResponseDto.serializer(), deleteReply(deleteItem(1000, id = "SECRET-id")))
        assertNoSecret(deleteDto.toString())
        assertNoSecret(deleteDto.responses.single().toString())

        respond(200, """{"Code":1001,"Responses":[{"Index":0,"Response":{"Code":2061,"Error":"SECRET-error","Contact":$created}}]}""")
        respond(422, """{"Code":2501,"Error":"SECRET-error","Details":{"ID":"SECRET-id"}}""")
        // A reply that doesn't decode: kotlinx.serialization's message would echo it.
        respond(200, createReply(createItem(1000, contact = """{"ID":["SECRET-id"]}""")))
        val errors = listOf<Throwable>(
            assertThrows<ProtonError.Api> { api.createContact(secretCards) },
            assertThrows<ProtonError.Api> { api.updateContact(ProtonContactId("SECRET-id"), secretCards) },
            assertThrows<ProtonError.BadResponse> { api.createContact(secretCards) },
        )
        for (error in errors) {
            assertNoSecret(error.toString())
            assertNull(error.message)
            assertNull(error.cause)
        }
    }

    private fun assertNoSecret(text: String) = assertFalse("SECRET" in text, text)
}
