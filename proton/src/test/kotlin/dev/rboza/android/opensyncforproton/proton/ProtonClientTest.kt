package dev.rboza.android.opensyncforproton.proton

import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** Against MockWebServer: headers, errors, routes A1–A3, A5, U1, U3, and C1–C3, no OkHttp retries, no redirects. */
class ProtonClientTest {
    private val server = MockWebServer()
    private val tokens = FakeTokenStore(Tokens(uid = "uid-1", access = "access-1", refresh = "refresh-1"))
    private lateinit var api: ProtonApi

    private class FakeTokenStore(var tokens: Tokens?) : TokenStore {
        var failure: Exception? = null

        /**
         * Reads that succeed before [failure] is thrown: 1 lets the client's own check pass, so the interceptor's read
         * fails.
         */
        var readsBeforeFailure = 0

        override fun current(): Tokens? {
            val failure = failure
            if (failure != null && readsBeforeFailure-- <= 0) throw failure
            return tokens
        }

        override fun replace(tokens: Tokens) {
            this.tokens = tokens
        }

        override fun onSessionRejected(uid: String) {
            tokens = null
        }
    }

    /** Exceptions OkHttp re-throws on its dispatcher threads; on Android these crash the app. */
    private val uncaught = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    @BeforeEach
    fun start() {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, error -> uncaught += error }
        server.start()
        api = ProtonApiFactory.create(ProtonConfig(baseUrl = server.url("/"), appVersion = "Other"), tokens)
    }

    @AfterEach
    fun stop() {
        server.close()
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
    }

    /** OkHttp re-throws on a dispatcher thread after the call has failed, so give it a moment. */
    private fun assertNothingUncaught() {
        Thread.sleep(200)
        assertEquals(emptyList<Throwable>(), uncaught.toList())
    }

    /** Replays a captured fixture: its status and body. */
    private fun respond(fixture: String) {
        val text = checkNotNull(javaClass.getResource("/$fixture.json")) { "fixtures/http/$fixture.json is missing" }.readText()
        val json = Json.parseToJsonElement(text).jsonObject
        respond(json.getValue("status").toString().toInt(), json.getValue("body").toString())
    }

    private fun respond(status: Int, body: String, vararg headers: Pair<String, String>) {
        server.enqueue(
            MockResponse.Builder().code(status).body(body).apply { headers.forEach { (name, value) -> addHeader(name, value) } }.build(),
        )
    }

    private fun take(): RecordedRequest = checkNotNull(server.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS)) { "no request" }

    private val srp = SrpAuthDto(username = "ada", clientEphemeral = "eph", clientProof = "proof", srpSession = "session")

    private fun assertCommonHeaders(request: RecordedRequest) {
        assertEquals("3", request.headers["x-pm-apiversion"])
        assertEquals("application/vnd.protonmail.v1+json", request.headers["Accept"])
        assertEquals("Other", request.headers["x-pm-appversion"])
    }

    /** The exact header set: ours, plus the transport headers OkHttp itself adds. */
    private fun assertHeaderNames(request: RecordedRequest, vararg ours: String) {
        val transport = setOf("host", "connection", "accept-encoding", "user-agent") +
            if (request.bodySize > 0) setOf("content-type", "content-length") else emptySet()
        val expected = transport + setOf("x-pm-apiversion", "accept", "x-pm-appversion") + ours.map { it.lowercase() }
        assertEquals(expected.sorted(), request.headers.names().map { it.lowercase() }.sorted())
    }

    // A1 and A2 carry no Bearer token or UID.
    @Test
    fun `login calls send the common headers and no session`() = runTest {
        respond("auth-info")
        respond("auth-one-password")

        api.authInfo("ada")
        api.auth(srp)

        val info = take()
        assertEquals("POST", info.method)
        assertEquals("/core/v4/auth/info", info.url.encodedPath)
        assertEquals(Json.parseToJsonElement("""{"Username":"ada","Intent":"Proton"}"""), Json.parseToJsonElement(info.body!!.utf8()))
        val auth = take()
        assertEquals("POST", auth.method)
        assertEquals("/core/v4/auth", auth.url.encodedPath)
        assertEquals(
            Json.parseToJsonElement("""{"Username":"ada","ClientEphemeral":"eph","ClientProof":"proof","SRPSession":"session"}"""),
            Json.parseToJsonElement(auth.body!!.utf8()),
        )
        for (request in listOf(info, auth)) {
            assertCommonHeaders(request)
            assertHeaderNames(request)
            assertNull(request.headers["Authorization"])
            assertNull(request.headers["x-pm-uid"])
            assertNull(request.headers["x-pm-human-verification-token"])
            assertNull(request.headers["x-pm-human-verification-token-type"])
        }
    }

    @Test
    fun `A2 sends captcha headers only when asked`() = runTest {
        respond("auth-one-password")

        api.auth(srp, CaptchaHeaders(token = "hv-token"))

        val request = take()
        assertEquals("hv-token", request.headers["x-pm-human-verification-token"])
        assertEquals("captcha", request.headers["x-pm-human-verification-token-type"])
        assertHeaderNames(request, "x-pm-human-verification-token", "x-pm-human-verification-token-type")
    }

    @Test
    fun `authenticated calls send the session and the routes`() = runTest {
        respond("auth-2fa")
        respond("users")
        respond("key-salts")
        respond("logout")

        api.submitTotp("123456")
        assertEquals(1, api.user().keys.size)
        assertEquals(2, api.keySalts().size)
        api.logout()

        val expected = listOf(
            "POST /core/v4/auth/2fa", "GET /core/v4/users", "GET /core/v4/keys/salts", "DELETE /core/v4/auth",
        )
        val requests = expected.map { take() }
        assertEquals(expected, requests.map { "${it.method} ${it.url.encodedPath}" })
        assertEquals(Json.parseToJsonElement("""{"TwoFactorCode":"123456"}"""), Json.parseToJsonElement(requests[0].body!!.utf8()))
        for (request in requests) {
            assertCommonHeaders(request)
            assertHeaderNames(request, "Authorization", "x-pm-uid")
            assertEquals("Bearer access-1", request.headers["Authorization"])
            assertEquals("uid-1", request.headers["x-pm-uid"])
        }
    }

    // C1–C3: Page and PageSize on the pages, the ID in C2's path, the session on all.
    @Test
    fun `contact routes send the session, the page, and the ID`() = runTest {
        respond("contacts-export")
        respond("contacts-list")
        respond("contact")

        assertEquals(3, api.exportContacts(page = 2, pageSize = 50).contacts.size)
        assertEquals(2, api.listContacts(page = 0, pageSize = 50).contacts.size)
        assertEquals(2, api.contact(ProtonContactId("a-b_c=")).cards.size)

        val requests = List(3) { take() }
        assertEquals(
            listOf(
                "GET /contacts/v4/contacts/export?Page=2&PageSize=50",
                "GET /contacts/v4/contacts?Page=0&PageSize=50",
                "GET /contacts/v4/contacts/a-b_c=",
            ),
            requests.map { "${it.method} ${it.url.encodedPath}" + (it.url.encodedQuery?.let { query -> "?$query" } ?: "") },
        )
        for (request in requests) {
            assertCommonHeaders(request)
            assertHeaderNames(request, "Authorization", "x-pm-uid")
        }
    }

    @Test
    fun `a token store that fails is a network error, and nothing escapes on OkHttp's threads`() = runTest {
        tokens.failure = IllegalStateException("SECRET-store")

        val error = assertThrows<ProtonError.Network> { api.user() }

        assertNoSecret(error)
        assertNothingUncaught()
    }

    @Test
    fun `a token store that fails while headers are added is a network error, and nothing escapes`() = runTest {
        tokens.failure = IllegalStateException("SECRET-store")
        tokens.readsBeforeFailure = 1

        assertNoSecret(assertThrows<ProtonError.Network> { api.user() })
        assertEquals(0, server.requestCount)
        assertNothingUncaught()
    }

    @Test
    fun `a cancellation from the token store isn't turned into an error`() = runTest {
        tokens.failure = kotlinx.coroutines.CancellationException("cancelled")

        assertThrows<kotlinx.coroutines.CancellationException> { api.user() }
    }

    @Test
    fun `a session UID that can't be a header value is an invalid request, and the UID shows nowhere`() = runTest {
        tokens.tokens = Tokens(uid = "SECRET-uid\u00e9", access = "access-1", refresh = "refresh-1")

        // InvalidRequest, as for any header value OkHttp refuses; not retryable.
        assertNoSecret(assertThrows<ProtonError.InvalidRequest> { api.user() })
        assertEquals(0, server.requestCount)
        assertNothingUncaught()
    }

    @Test
    fun `an authenticated call without a session sends nothing`() = runTest {
        tokens.tokens = null

        assertThrows<ProtonError.NoSession> { api.user() }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `any Code but 1000 is an error`() = runTest {
        respond(200, """{"Code":2000,"Error":"x"}""")
        respond("contact-2501-does-not-exist")
        respond(500, "not json")

        val ok = assertThrows<ProtonError.Api> { api.user() }
        assertEquals(200 to 2000, ok.http to ok.code)
        val missing = assertThrows<ProtonError.Api> { api.user() }
        assertEquals(422 to 2501, missing.http to missing.code)
        // A read would retry the 5xx (ProtonRetryTest); A1 is sent once.
        val server = assertThrows<ProtonError.Api> { api.authInfo("ada") }
        assertEquals(500 to null, server.http to server.code)
    }

    @Test
    fun `a success without a Code is a bad response`() = runTest {
        respond(200, """{"User":{}}""")

        assertEquals(200, assertThrows<ProtonError.BadResponse> { api.user() }.http)
    }

    @Test
    fun `JSON of the wrong shape is a bad response, never another exception`() = runTest {
        respond(200, """{"Code":{"x":1}}""")
        respond(200, """{"Code":[1000]}""")
        respond(422, """{"Code":9001,"Details":{"HumanVerificationToken":{"x":1},"WebUrl":"https://verify.proton.me/"}}""")
        respond(422, """{"Code":9001,"Details":{"HumanVerificationToken":"t","WebUrl":["x"]}}""")
        respond(200, "[1000]")

        assertThrows<ProtonError.BadResponse> { api.user() }
        assertThrows<ProtonError.BadResponse> { api.user() }
        assertThrows<ProtonError.BadResponse> { api.auth(srp) }
        assertThrows<ProtonError.BadResponse> { api.auth(srp) }
        assertThrows<ProtonError.BadResponse> { api.user() }
    }

    @Test
    fun `a captcha token that can't be a header value is refused without showing it`() = runTest {
        respond(422, """{"Code":9001,"Details":{"HumanVerificationToken":"SECRET-t\u00f6ken","WebUrl":"https://verify.proton.me/"}}""")

        assertNoSecret(assertThrows<ProtonError.BadResponse> { api.auth(srp) })

        assertNoSecret(assertThrows<ProtonError.InvalidRequest> { api.auth(srp, CaptchaHeaders("SECRET-t\u00f6ken")) })
        assertEquals(1, server.requestCount)
        assertNothingUncaught()
    }

    @Test
    fun `a 9001 without a token or URL is a bad response`() = runTest {
        respond(422, """{"Code":9001,"Details":{"WebUrl":"https://verify.proton.me/"}}""")
        respond(422, """{"Code":9001,"Details":{"HumanVerificationToken":"t"}}""")
        respond(422, """{"Code":9001,"Details":{"HumanVerificationToken":"","WebUrl":"https://verify.proton.me/"}}""")

        repeat(3) { assertThrows<ProtonError.BadResponse> { api.auth(srp) } }
    }

    @Test
    fun `a 9001 without methods means captcha`() = runTest {
        respond(422, """{"Code":9001,"Details":{"HumanVerificationToken":"t","WebUrl":"https://verify.proton.me/"}}""")

        assertEquals(listOf("captcha"), assertThrows<ProtonError.HumanVerification> { api.auth(srp) }.details.methods)
    }

    @Test
    fun `Code 9001 is human verification`() = runTest {
        respond("auth-9001-captcha")

        val error = assertThrows<ProtonError.HumanVerification> { api.auth(srp) }

        assertEquals(listOf("captcha"), error.details.methods)
        assertFalse(error.details.token.isEmpty())
        assertEquals("https", error.details.webUrl.substringBefore(':'))
    }

    // A1 is sent once; a read would retry the 429 (ProtonRetryTest).
    @Test
    fun `HTTP 429 is rate limited, with Retry-After in seconds when it's given`() = runTest {
        respond(429, """{"Code":2028}""", "Retry-After" to "7")
        respond(429, """{"Code":2028}""")
        for (value in listOf("-5", "+7", "7.5", "99999999999999", "Wed, 21 Oct 2026 07:28:00 GMT", "")) {
            respond(429, """{"Code":2028}""", "Retry-After" to value)
        }

        assertEquals(7.seconds, assertThrows<ProtonError.RateLimited> { api.authInfo("ada") }.retryAfter)
        assertNull(assertThrows<ProtonError.RateLimited> { api.authInfo("ada") }.retryAfter)
        repeat(6) { assertNull(assertThrows<ProtonError.RateLimited> { api.authInfo("ada") }.retryAfter, "case $it") }
    }

    // These codes stop every later request from this client.
    @ParameterizedTest
    @ValueSource(ints = [5001, 5002, 5003, 2064])
    fun `an app-version rejection stops further requests`(code: Int) = runTest {
        respond(400, """{"Code":$code,"Error":"x"}""")

        assertEquals(code, assertThrows<ProtonError.AppVersionRejected> { api.authInfo("ada") }.code)
        assertThrows<ProtonError.AppVersionRejected> { api.user() }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `the captured app-version refusal is a rejection`() = runTest {
        respond("auth-info-5002-invalid-app-version")
        assertEquals(5002, assertThrows<ProtonError.AppVersionRejected> { api.authInfo("ada") }.code)
    }

    // No server text, captcha value, IO message, or decoded value reaches an exception.
    @Test
    fun `errors carry no server text, captcha value, or response value`() = runTest {
        respond(422, """{"Code":2501,"Error":"SECRET-error-text"}""")
        respond(422, """{"Code":9001,"Error":"SECRET-error-text","Details":{"HumanVerificationToken":"SECRET-hv","WebUrl":"https://verify.proton.me/?token=SECRET-url","HumanVerificationMethods":["captcha"]}}""")
        respond(200, """{"Code":1000,"User":{"ID":"SECRET-id","Name":"SECRET-name"}}""")
        respond(200, """{"Code":1000,"AccessToken":"SECRET-token","RefreshToken":SECRET-raw}""")
        respond(200, """{"Code":1000,"User":{"ID":"SECRET-id","Name":"x","Email":"x","DisplayName":"x","Keys":[{"ID":"x","PrivateKey":"SECRET-key","Active":"SECRET-active","Primary":1,"Fingerprint":"x","Version":3}]}}""")

        val errors = listOf<Throwable>(
            assertThrows<ProtonError.Api> { api.user() },
            assertThrows<ProtonError.HumanVerification> { api.auth(srp) },
            assertThrows<ProtonError.BadResponse> { api.user() },
            assertThrows<ProtonError.BadResponse> { api.auth(srp) },
            assertThrows<ProtonError.BadResponse> { api.user() },
        )
        for (error in errors) assertNoSecret(error)
    }

    @Test
    fun `a network failure carries no message or cause`() = runTest {
        server.enqueue(MockResponse.Builder().onRequestStart(SocketEffect.CloseSocket()).build())

        // A1 is sent once; a read would retry the lost connection (ProtonRetryTest).
        val error = assertThrows<ProtonError.Network> { api.authInfo("ada") }

        assertNull(error.message)
        assertNull(error.cause)
        assertFalse("localhost" in error.toString() || "127.0.0.1" in error.toString() || "auth" in error.toString(), error.toString())
    }

    private fun assertNoSecret(error: Throwable) {
        var current: Throwable? = error
        while (current != null) {
            val shown = listOf(current.message, current.toString(), current.localizedMessage, (current as? ProtonError.HumanVerification)?.details?.toString())
            for (text in shown.filterNotNull()) assertFalse("SECRET" in text, text)
            current = current.cause
        }
        assertNull(error.cause)
    }

    // OkHttp silently resends on a pooled connection the server closed; this client must not.
    // A1 is sent once, so any resend would be OkHttp's (reads retry by their own rule, ProtonRetryTest).
    @Test
    fun `a dropped connection isn't retried`() = runTest {
        val authInfo = checkNotNull(javaClass.getResource("/auth-info.json")).readText()
            .let { Json.parseToJsonElement(it).jsonObject.getValue("body").toString() }
        // The server closes the connection after the first response; the client still holds it in its pool.
        server.enqueue(MockResponse.Builder().body(authInfo).onResponseEnd(SocketEffect.CloseSocket()).build())
        respond("auth-info")

        api.authInfo("ada")
        assertThrows<ProtonError.Network> { api.authInfo("ada") }
        api.authInfo("ada")

        assertEquals(2, server.requestCount)
    }

    // OkHttp also resends once on a 503 with Retry-After 0, whatever the method.
    @Test
    fun `a 503 with Retry-After 0 isn't resent`() = runTest {
        respond(503, """{"Code":503}""", "Retry-After" to "0")
        respond("auth-2fa")

        assertEquals(503, assertThrows<ProtonError.Api> { api.submitTotp("123456") }.http)
        assertEquals(1, server.requestCount)
    }

    @ParameterizedTest
    @ValueSource(ints = [301, 302, 307, 308])
    fun `a redirect is an error and isn't followed`(status: Int) = runTest {
        server.enqueue(MockResponse.Builder().code(status).addHeader("Location", server.url("/elsewhere").toString()).build())
        respond("users")

        assertEquals(status, assertThrows<ProtonError.Api> { api.user() }.http)
        assertEquals(1, server.requestCount)
    }

    // U1 and C1: a 404 tries the old spelling once, and the one that answered is kept per route.
    @Test
    fun `reads fall back to the old spelling on 404 and keep it`() = runTest {
        respond(404, """{"Code":404}""")
        respond("users")
        respond("users")
        respond(404, """{"Code":404}""")
        respond("contacts-list")

        api.user()
        api.user()
        api.listContacts(page = 0, pageSize = 50)

        assertEquals(
            listOf("/core/v4/users", "/users", "/users", "/contacts/v4/contacts", "/contacts/v4"),
            List(5) { take().url.encodedPath },
        )
    }

    // The fallback for C1–C3.
    @Test
    fun `contact reads fall back to the old spelling on 404 and keep it`() = runTest {
        for (fixture in listOf("contacts-export", "contacts-list", "contact")) {
            respond(404, """{"Code":404}""")
            respond(fixture)
            respond(fixture)
        }

        repeat(2) { api.exportContacts(page = 1, pageSize = 50) }
        repeat(2) { api.listContacts(page = 1, pageSize = 50) }
        repeat(2) { api.contact(ProtonContactId("id-1")) }

        assertEquals(
            listOf(
                "/contacts/v4/contacts/export", "/contacts/v4/export", "/contacts/v4/export",
                "/contacts/v4/contacts", "/contacts/v4", "/contacts/v4",
                "/contacts/v4/contacts/id-1", "/contacts/v4/id-1", "/contacts/v4/id-1",
            ),
            List(9) { take().url.encodedPath },
        )
    }

    @Test
    fun `a 404 on both spellings is an error, and the next call tries the current one again`() = runTest {
        respond(404, """{"Code":404}""")
        respond(404, """{"Code":404}""")
        respond("users")

        assertEquals(404, assertThrows<ProtonError.Api> { api.user() }.http)
        api.user()

        assertEquals(listOf("/core/v4/users", "/users", "/core/v4/users"), List(3) { take().url.encodedPath })
    }

    @Test
    fun `an old spelling that didn't answer isn't kept`() = runTest {
        // The 404's connection closes after it, so the old spelling meets a dead pooled connection: no answer.
        server.enqueue(MockResponse.Builder().code(404).body("""{"Code":404}""").onResponseEnd(SocketEffect.CloseSocket()).build())
        respond("users")
        respond("users")

        // The read's retry (ProtonRetryTest) and the next call both go to the current spelling.
        api.user()
        api.user()

        assertEquals(listOf("/core/v4/users", "/core/v4/users", "/core/v4/users"), List(3) { take().url.encodedPath })
    }

    @Test
    fun `only a 404 falls back, and login routes and salts never do`() = runTest {
        respond("contact-2501-does-not-exist")
        respond(404, """{"Code":404}""")
        respond(404, """{"Code":404}""")

        assertThrows<ProtonError.Api> { api.user() }
        assertThrows<ProtonError.Api> { api.authInfo("ada") }
        assertThrows<ProtonError.Api> { api.keySalts() }

        assertEquals(listOf("/core/v4/users", "/core/v4/auth/info", "/core/v4/keys/salts"), List(3) { take().url.encodedPath })
    }

    @Test
    fun `secret-holding values show none of it`() {
        assertEquals("Tokens(redacted)", Tokens("uid", "access", "refresh").toString())
        assertEquals("CaptchaHeaders(redacted)", CaptchaHeaders("hv").toString())
        assertEquals("SrpAuthDto(redacted)", srp.toString())
        assertEquals("HvDetails(methods=[captcha])", HvDetails("hv", "https://verify.proton.me", listOf("captcha")).toString())
        assertInstanceOf(ProtonError::class.java, ProtonError.Api(422, 2501))
        assertEquals("ProtonError.Api(http=422, code=2501)", ProtonError.Api(422, 2501).toString())
    }
}
