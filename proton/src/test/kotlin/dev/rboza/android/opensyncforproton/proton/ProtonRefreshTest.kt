package dev.rboza.android.opensyncforproton.proton

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** docs/protocol.md § Token refresh. */
class ProtonRefreshTest {
    private val server = MockWebServer()
    private val first = Tokens(uid = "uid-1", access = "access-1", refresh = "refresh-1")
    private val store = RecordingTokenStore(first)
    private lateinit var api: ProtonApi

    /** Server and store events, in the order they happened. */
    private val events = CopyOnWriteArrayList<String>()

    private inner class RecordingTokenStore(@Volatile var tokens: Tokens?) : TokenStore {
        var replaceFailure: Exception? = null
        var rejections = 0
        val rejectedUids = CopyOnWriteArrayList<String>()

        override fun current() = tokens

        /** Runs after a replace, as another component storing a new login would. */
        var afterReplace: () -> Unit = {}

        override fun replace(tokens: Tokens) {
            replaceFailure?.let { throw it }
            events += "replace ${tokens.access}"
            this.tokens = tokens
            afterReplace()
        }

        override fun onSessionRejected(uid: String) {
            events += "rejected"
            rejectedUids += uid
            rejections++
            tokens = null
        }
    }

    private val usersBody = fixtureBody("users")

    private fun fixtureBody(name: String): String {
        val text = checkNotNull(javaClass.getResource("/$name.json")).readText()
        return Json.parseToJsonElement(text).jsonObject.getValue("body").toString()
    }

    /** The captured refresh answer, with this test's UID and new tokens. */
    private fun refreshBody(access: String = "access-2", refresh: String = "refresh-2", uid: String = "uid-1"): String {
        val body = Json.parseToJsonElement(fixtureBody("refresh")).jsonObject.toMutableMap()
        body["AccessToken"] = JsonPrimitive(access)
        body["RefreshToken"] = JsonPrimitive(refresh)
        body["UID"] = JsonPrimitive(uid)
        body["Uid"] = JsonPrimitive(uid)
        return JsonObject(body).toString()
    }

    private fun response(status: Int, body: String) = MockResponse.Builder().code(status).body(body).build()

    private val unauthorized = response(401, """{"Code":401}""")

    /** The usual server: `GET users` answers only the newest access token; the refresh is [onRefresh]. */
    private fun serve(onRefresh: (RecordedRequest) -> MockResponse = { response(200, refreshBody()) }) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                events += "${request.method} $path ${request.headers["Authorization"] ?: "-"}"
                return when (path) {
                    "/core/v4/auth/refresh" -> onRefresh(request)
                    "/core/v4/users" ->
                        if (request.headers["Authorization"] == "Bearer access-2") response(200, usersBody) else unauthorized
                    else -> unauthorized
                }
            }
        }
    }

    private val refreshes get() = events.filter { it.startsWith("POST /core/v4/auth/refresh") }
    private val userRequests get() = events.filter { it.startsWith("GET /core/v4/users") }

    @BeforeEach
    fun start() {
        server.start()
        api = ProtonApiFactory.create(ProtonConfig(baseUrl = server.url("/"), appVersion = "Other"), store)
    }

    @AfterEach
    fun stop() = server.close()

    @Test
    fun `the refresh sends the UID header, no Bearer token, and every body field, with a fresh State`() = runBlocking {
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        serve { requests += it; response(200, refreshBody()) }

        api.user()
        store.tokens = first
        api.user()

        assertEquals(2, requests.size)
        requests.forEach { request ->
            assertEquals("POST", request.method)
            assertEquals("uid-1", request.headers["x-pm-uid"])
            assertNull(request.headers["Authorization"])
            assertEquals("Other", request.headers["x-pm-appversion"])
            val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
            val state = (body.getValue("State") as JsonPrimitive).content
            assertTrue(Regex("[0-9a-f]{32}").matches(state), "State is 32 random hex characters")
            assertEquals(
                mapOf(
                    "UID" to "uid-1", "RefreshToken" to "refresh-1", "ResponseType" to "token",
                    "GrantType" to "refresh_token", "RedirectURI" to "https://protonmail.ch",
                ),
                body.filterKeys { it != "State" }.mapValues { (it.value as JsonPrimitive).content },
            )
        }
        assertNotEquals(state(requests[0]), state(requests[1]), "a fresh State every time")
    }

    private fun state(request: RecordedRequest) =
        (Json.parseToJsonElement(request.body!!.utf8()).jsonObject.getValue("State") as JsonPrimitive).content

    @Test
    fun `new tokens are saved before the request is resent`() = runBlocking {
        serve()

        api.user()

        assertEquals(
            listOf(
                "GET /core/v4/users Bearer access-1",
                "POST /core/v4/auth/refresh -",
                "replace access-2",
                "GET /core/v4/users Bearer access-2",
            ),
            events.toList(),
        )
        assertEquals("refresh-2", store.tokens?.refresh)
        assertEquals("uid-1", store.tokens?.uid)
    }

    @Test
    fun `tokens that fail to save resend nothing, and the call is a network error`() = runBlocking {
        serve()
        store.replaceFailure = IllegalStateException("disk full")

        val error = assertThrows<ProtonError> { api.user() }

        assertInstanceOf(ProtonError.Network::class.java, error)
        assertEquals(1, userRequests.size, "the original request isn't resent")
        assertEquals(first, store.tokens)
        assertEquals(0, store.rejections)
    }

    @Test
    fun `concurrent 401s share one refresh, and each request is resent once`() = runBlocking {
        val callers = 5
        val allUnauthorized = CountDownLatch(callers)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                events += "${request.method} $path ${request.headers["Authorization"] ?: "-"}"
                return when {
                    path == "/core/v4/auth/refresh" -> {
                        // Hold the refresh until every caller has had its 401, so they all wait for this one.
                        allUnauthorized.await(5, TimeUnit.SECONDS)
                        response(200, refreshBody())
                    }
                    request.headers["Authorization"] == "Bearer access-2" -> response(200, usersBody)
                    else -> unauthorized.also { allUnauthorized.countDown() }
                }
            }
        }

        val users = (1..callers).map { async(Dispatchers.IO) { api.user() } }.awaitAll()

        assertEquals(callers, users.size)
        assertEquals(1, refreshes.size)
        assertEquals(callers, userRequests.count { it.endsWith("access-1") })
        assertEquals(callers, userRequests.count { it.endsWith("access-2") }, "each request resent once")
        assertEquals(1, events.count { it == "replace access-2" })
    }

    @Test
    fun `callers waiting on a refresh that failed get its error, without another refresh`() = runBlocking {
        val callers = 4
        val allUnauthorized = CountDownLatch(callers)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                events += "${request.method} $path ${request.headers["Authorization"] ?: "-"}"
                return if (path == "/core/v4/auth/refresh") {
                    allUnauthorized.await(5, TimeUnit.SECONDS)
                    response(503, """{"Code":503}""")
                } else {
                    unauthorized.also { allUnauthorized.countDown() }
                }
            }
        }

        val errors = (1..callers).map { async(Dispatchers.IO) { runCatching { api.user() }.exceptionOrNull() } }.awaitAll()

        assertEquals(1, refreshes.size)
        errors.forEach { assertTrue(it is ProtonError.Api && it.http == 503, "$it") }
        assertEquals(first, store.tokens)
    }

    @ParameterizedTest
    @ValueSource(ints = [400, 422])
    fun `a rejected refresh ends the session`(status: Int) = runBlocking {
        serve { response(status, fixtureBody("refresh-10013-invalid-token")) }

        assertThrows<ProtonError.SessionRejected> { api.user() }
        assertThrows<ProtonError.SessionRejected> { api.keySalts() }

        assertEquals(1, store.rejections)
        assertEquals(listOf("uid-1"), store.rejectedUids, "the session the refresh was for")
        assertEquals(1, refreshes.size, "no loop")
        assertEquals(listOf("GET /core/v4/users Bearer access-1", "POST /core/v4/auth/refresh -", "rejected"), events.toList())
    }

    @Test
    fun `a rejected refresh names the session it refreshed, not the one stored since`() = runBlocking {
        serve {
            // A new login stored its own session while this refresh was out.
            store.tokens = Tokens(uid = "uid-2", access = "access-9", refresh = "refresh-9")
            response(400, fixtureBody("refresh-10013-invalid-token"))
        }

        assertThrows<ProtonError.SessionRejected> { api.user() }

        assertEquals(listOf("uid-1"), store.rejectedUids)
    }

    @Test
    fun `a 401 on the resent request names the session it carried, not the one stored since`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.url.encodedPath == "/core/v4/auth/refresh" -> response(200, refreshBody())
                request.headers["Authorization"] == "Bearer access-2" -> {
                    // The resend; a new login stores its own session before the 401 arrives.
                    store.tokens = Tokens(uid = "uid-2", access = "access-9", refresh = "refresh-9")
                    unauthorized
                }
                else -> unauthorized
            }
        }

        assertThrows<ProtonError.SessionRejected> { api.user() }

        assertEquals(listOf("uid-1"), store.rejectedUids)
    }

    @Test
    fun `a resent request that carried a newer session names that session`() = runBlocking {
        // A new login is stored between the refresh and the resend, so the resend carries it.
        store.afterReplace = { store.tokens = Tokens(uid = "uid-2", access = "access-9", refresh = "refresh-9") }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.url.encodedPath == "/core/v4/auth/refresh") response(200, refreshBody()) else unauthorized
        }

        assertThrows<ProtonError.SessionRejected> { api.user() }

        assertEquals(listOf("uid-2"), store.rejectedUids)
    }

    @Test
    fun `a 401 on the resent request is a rejection`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                events += "${request.method} ${request.url.encodedPath} ${request.headers["Authorization"] ?: "-"}"
                return if (request.url.encodedPath == "/core/v4/auth/refresh") response(200, refreshBody()) else unauthorized
            }
        }

        assertThrows<ProtonError.SessionRejected> { api.user() }

        assertEquals(1, store.rejections)
        assertEquals(listOf("uid-1"), store.rejectedUids, "the session the resent request carried")
        assertEquals(1, refreshes.size)
        assertEquals(2, userRequests.size)
    }

    @Test
    fun `an interrupted refresh keeps the tokens on a dropped connection, 5xx, 429, another Code, a 401, or a bad answer`() = runBlocking {
        val answers = listOf<() -> MockResponse>(
            { MockResponse.Builder().onRequestStart(SocketEffect.CloseSocket()).build() },
            { response(502, """{"Code":502}""") },
            { MockResponse.Builder().code(429).body("""{"Code":2028}""").addHeader("Retry-After", "5").build() },
            { response(400, """{"Code":2001}""") },
            // 10013 is a rejection only with 400 or 422.
            { response(500, """{"Code":10013}""") },
            { response(401, """{"Code":401}""") },
            { response(200, """{"Code":1000}""") },
            { response(200, refreshBody(uid = "uid-other")) },
        )
        for (answer in answers) {
            events.clear()
            serve { answer() }

            val error = assertThrows<ProtonError> { api.user() }

            assertTrue(error !is ProtonError.SessionRejected, "$error")
            assertEquals(first, store.tokens)
            assertEquals(0, store.rejections)
            assertEquals(1, userRequests.size, "$error: the original request isn't resent")
            assertEquals(1, refreshes.size, "$error: a 401 from the refresh itself triggers no refresh")
        }
    }

    @Test
    fun `an interrupted refresh keeps the tokens on a timeout`() = runBlocking {
        // Longer than OkHttp's 10 s read timeout.
        serve { MockResponse.Builder().code(200).body(refreshBody()).headersDelay(15, TimeUnit.SECONDS).build() }

        assertThrows<ProtonError.Network> { api.user() }

        assertEquals(first, store.tokens)
        assertEquals(0, store.rejections)
        assertEquals(1, userRequests.size)
    }

    @Test
    fun `an interrupted refresh keeps the tokens when the run is cancelled, and waiters get a retryable error`() = runBlocking {
        val refreshStarted = CountDownLatch(1)
        val waiterUnauthorized = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                events += "${request.method} $path ${request.headers["Authorization"] ?: "-"}"
                return when {
                    path == "/core/v4/auth/refresh" && refreshes.size == 1 -> {
                        refreshStarted.countDown()
                        release.await(5, TimeUnit.SECONDS)
                        response(200, refreshBody())
                    }
                    path == "/core/v4/auth/refresh" -> response(200, refreshBody())
                    request.headers["Authorization"] == "Bearer access-2" -> response(200, usersBody)
                    path == "/core/v4/keys/salts" -> unauthorized.also { waiterUnauthorized.countDown() }
                    else -> unauthorized
                }
            }
        }

        val run = async(Dispatchers.IO) { api.user() }
        assertTrue(refreshStarted.await(5, TimeUnit.SECONDS))
        val waiter = async(Dispatchers.IO) { runCatching { api.keySalts() }.exceptionOrNull() }
        assertTrue(waiterUnauthorized.await(5, TimeUnit.SECONDS))
        run.cancel(CancellationException("run cancelled"))
        assertThrows<CancellationException> { run.await() }
        release.countDown()

        assertInstanceOf(ProtonError.Network::class.java, waiter.await(), "the waiter doesn't start its own refresh")
        assertEquals(1, refreshes.size)
        assertEquals(first, store.tokens)
        assertEquals(0, store.rejections)

        // The lock was released: the next call refreshes and succeeds.
        api.user()
        assertEquals(2, refreshes.size)
        assertEquals("access-2", store.tokens?.access)
    }

    @Test
    fun `a refresh that answers 404 during a read doesn't fall back to the old spelling`() = runBlocking {
        serve { response(404, """{"Code":404}""") }

        assertEquals(404, assertThrows<ProtonError.Api> { api.user() }.http)

        assertEquals(listOf("GET /core/v4/users Bearer access-1", "POST /core/v4/auth/refresh -"), events.toList())
    }

    /** A request that was waiting when another call's resend hit an app-version rejection sends nothing more. */
    @Test
    fun `a resend after an app-version rejection sends nothing`() = runBlocking {
        val aSent = CountDownLatch(1)
        val releaseA = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                val bearer = request.headers["Authorization"]
                events += "${request.method} $path ${bearer ?: "-"}"
                return when {
                    path == "/core/v4/auth/refresh" -> response(200, refreshBody())
                    path == "/core/v4/users" && bearer == "Bearer access-1" -> {
                        aSent.countDown()
                        releaseA.await(5, TimeUnit.SECONDS)
                        unauthorized
                    }
                    path == "/core/v4/keys/salts" && bearer == "Bearer access-2" -> response(400, """{"Code":5003}""")
                    else -> unauthorized
                }
            }
        }

        val a = async(Dispatchers.IO) { runCatching { api.user() }.exceptionOrNull() }
        assertTrue(aSent.await(5, TimeUnit.SECONDS))
        assertThrows<ProtonError.AppVersionRejected> { api.keySalts() }
        releaseA.countDown()

        assertInstanceOf(ProtonError.AppVersionRejected::class.java, a.await())
        assertEquals(listOf("GET /core/v4/users Bearer access-1"), userRequests, "A's resend is never sent")
    }

    /** A 401 on a resend rejects the session only after a refresh in progress has finished, so it can't be undone. */
    @Test
    fun `a rejection isn't undone by a refresh in progress`() = runBlocking {
        val secondRefreshStarted = CountDownLatch(1)
        val rejected = CountDownLatch(1)
        val saltsBody = fixtureBody("key-salts")
        val rejectingStore = object : TokenStore {
            @Volatile var tokens: Tokens? = first
            override fun current() = tokens
            override fun replace(tokens: Tokens) {
                events += "replace ${tokens.access}"
                this.tokens = tokens
            }
            override fun onSessionRejected(uid: String) {
                events += "rejected"
                tokens = null
                rejected.countDown()
            }
        }
        api = ProtonApiFactory.create(ProtonConfig(baseUrl = server.url("/"), appVersion = "Other"), rejectingStore)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                val bearer = request.headers["Authorization"]
                events += "${request.method} $path ${bearer ?: "-"}"
                return when {
                    path == "/core/v4/auth/refresh" && refreshes.size == 1 -> response(200, refreshBody())
                    path == "/core/v4/auth/refresh" -> {
                        secondRefreshStarted.countDown()
                        // A rejection that didn't wait for the lock would happen now, while this refresh is out.
                        rejected.await(2, TimeUnit.SECONDS)
                        response(200, refreshBody(access = "access-3", refresh = "refresh-3"))
                    }
                    path == "/core/v4/users" && bearer == "Bearer access-2" -> {
                        secondRefreshStarted.await(5, TimeUnit.SECONDS)
                        unauthorized
                    }
                    path == "/core/v4/keys/salts" && bearer == "Bearer access-3" -> response(200, saltsBody)
                    else -> unauthorized
                }
            }
        }

        val a = async(Dispatchers.IO) { runCatching { api.user() }.exceptionOrNull() }
        kotlinx.coroutines.withTimeout(10_000) { while ("replace access-2" !in events) kotlinx.coroutines.delay(10) }
        val b = async(Dispatchers.IO) { runCatching { api.keySalts() }.exceptionOrNull() }

        assertInstanceOf(ProtonError.SessionRejected::class.java, a.await())
        b.await()
        assertNull(rejectingStore.tokens, "the credentials stay deleted")
        assertEquals("rejected", events.last { it == "rejected" || it.startsWith("replace") })
    }

    /** A call already out with the refreshed token, whose 401 lands after another call's resend was rejected. */
    @Test
    fun `a caller whose 401 lands after a resend rejection gets SessionRejected`() = runBlocking {
        val cArrived = CountDownLatch(1)
        val rejected = CountDownLatch(1)
        val rejectingStore = object : TokenStore {
            @Volatile var tokens: Tokens? = first
            override fun current() = tokens
            override fun replace(tokens: Tokens) {
                events += "replace ${tokens.access}"
                this.tokens = tokens
            }
            override fun onSessionRejected(uid: String) {
                tokens = null
                rejected.countDown()
            }
        }
        api = ProtonApiFactory.create(ProtonConfig(baseUrl = server.url("/"), appVersion = "Other"), rejectingStore)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                val bearer = request.headers["Authorization"]
                events += "${request.method} $path ${bearer ?: "-"}"
                when {
                    path == "/core/v4/auth/refresh" -> return response(200, refreshBody())
                    // A's resend: rejected only once C is out with the same token.
                    path == "/core/v4/users" && bearer == "Bearer access-2" -> cArrived.await(5, TimeUnit.SECONDS)
                    path == "/core/v4/keys/salts" -> {
                        cArrived.countDown()
                        rejected.await(5, TimeUnit.SECONDS)
                    }
                }
                return unauthorized
            }
        }

        val a = async(Dispatchers.IO) { runCatching { api.user() }.exceptionOrNull() }
        kotlinx.coroutines.withTimeout(10_000) { while ("replace access-2" !in events) kotlinx.coroutines.delay(10) }
        val c = async(Dispatchers.IO) { runCatching { api.keySalts() }.exceptionOrNull() }

        assertInstanceOf(ProtonError.SessionRejected::class.java, a.await())
        assertInstanceOf(ProtonError.SessionRejected::class.java, c.await())
        assertEquals(1, refreshes.size)
    }

    /** A store that can't delete the credentials still leaves a rejected client that sends nothing more. */
    @Test
    fun `waiters on a rejected refresh don't refresh again, even when the store fails to delete`() = runBlocking {
        val callers = 3
        val allUnauthorized = CountDownLatch(callers)
        val failingStore = object : TokenStore {
            override fun current() = first
            override fun replace(tokens: Tokens) = Unit
            override fun onSessionRejected(uid: String) = throw IllegalStateException("disk")
        }
        api = ProtonApiFactory.create(ProtonConfig(baseUrl = server.url("/"), appVersion = "Other"), failingStore)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                events += "${request.method} ${request.url.encodedPath} ${request.headers["Authorization"] ?: "-"}"
                return if (request.url.encodedPath == "/core/v4/auth/refresh") {
                    allUnauthorized.await(5, TimeUnit.SECONDS)
                    response(400, fixtureBody("refresh-10013-invalid-token"))
                } else {
                    unauthorized.also { allUnauthorized.countDown() }
                }
            }
        }

        val errors = (1..callers).map { async(Dispatchers.IO) { runCatching { api.user() }.exceptionOrNull() } }.awaitAll()

        errors.forEach { assertInstanceOf(ProtonError.SessionRejected::class.java, it) }
        assertEquals(1, refreshes.size)
    }

    @Test
    fun `a 401 from A1, A2, A3, or A5 never refreshes`() = runBlocking {
        serve()
        val srp = SrpAuthDto(username = "ada", clientEphemeral = "eph", clientProof = "proof", srpSession = "session")

        assertEquals(401, assertThrows<ProtonError.Api> { api.authInfo("ada") }.http)
        assertEquals(401, assertThrows<ProtonError.Api> { api.auth(srp) }.http)
        // A3 runs minutes after A2 made the tokens: a 401 there is about the code, so the user can retype it.
        assertEquals(401, assertThrows<ProtonError.Api> { api.submitTotp("123456") }.http)
        assertEquals(401, assertThrows<ProtonError.Api> { api.logout() }.http)

        assertEquals(emptyList<String>(), refreshes)
        assertEquals(first, store.tokens)
        assertEquals(0, store.rejections)
    }
}
