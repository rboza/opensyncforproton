package dev.rboza.android.opensyncforproton.proton

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Read retries (docs/protocol.md § Errors and retries) and pacing (docs/protocol.md § Conventions).
 * Time is the test scheduler's: waits pass without real time, and each request records when it arrived.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProtonRetryTest {
    private val server = MockWebServer()
    private val store = object : TokenStore {
        @Volatile var tokens: Tokens? = Tokens(uid = "uid-1", access = "access-1", refresh = "refresh-1")
        @Volatile var rejections = 0

        /** Reads so far, and the first one that throws. */
        @Volatile var reads = 0
        @Volatile var failFromRead = Int.MAX_VALUE

        override fun current(): Tokens? {
            if (++reads >= failFromRead) throw IllegalStateException("store failed")
            return tokens
        }
        override fun replace(tokens: Tokens) {
            this.tokens = tokens
        }
        override fun onSessionRejected(uid: String) {
            rejections++
            tokens = null
        }
    }

    /** Runs on MockWebServer's thread when a request arrives, before it's answered. */
    @Volatile private var onArrival: (String) -> Unit = {}

    /** Scripted answers per path, in order; a path with none left answers 500 and fails the test. */
    private val answers = mutableMapOf<String, ConcurrentLinkedQueue<MockResponse>>()

    /** Each request's path and the scheduler's time when it arrived. */
    private val arrivals = CopyOnWriteArrayList<Pair<String, Duration>>()
    private val unscripted = CopyOnWriteArrayList<String>()

    /** The backoff's random part, scripted: the maxima it was asked for, and what it returns. */
    private val jitterMaxima = CopyOnWriteArrayList<Duration>()
    private var jitter: (Duration) -> Duration = { Duration.ZERO }

    private val usersBody = fixtureBody("users")

    private fun fixtureBody(name: String): String {
        val text = checkNotNull(javaClass.getResource("/$name.json")).readText()
        return Json.parseToJsonElement(text).jsonObject.getValue("body").toString()
    }

    private fun response(status: Int, body: String, retryAfter: String? = null) =
        MockResponse.Builder().code(status).body(body).apply { retryAfter?.let { addHeader("Retry-After", it) } }.build()

    private val ok get() = response(200, usersBody)
    private fun tooMany(retryAfter: String? = null) = response(429, """{"Code":2028}""", retryAfter)
    private fun unavailable(retryAfter: String? = null) = response(503, """{"Code":503}""", retryAfter)

    /** The connection drops after the request arrives, before any response. */
    private val dropped get() = MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build()

    private fun failure(kind: String) = when (kind) {
        "429" -> tooMany()
        "500" -> response(500, """{"Code":500}""")
        "503" -> unavailable()
        "drop" -> dropped
        else -> error(kind)
    }

    private fun script(path: String, vararg responses: MockResponse) {
        answers.getOrPut(path) { ConcurrentLinkedQueue() }.addAll(responses)
    }

    private fun TestScope.newApi(): ProtonApi {
        val scheduler = testScheduler
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                arrivals += path to scheduler.currentTime.milliseconds
                onArrival(path)
                return answers[path]?.poll() ?: response(500, """{"Code":500}""").also { unscripted += path }
            }
        }
        val timing = Timing(timeSource = scheduler.timeSource) { max -> jitterMaxima += max; jitter(max) }
        return ProtonApiFactory.create(ProtonConfig(baseUrl = server.url("/"), appVersion = "Other"), store, timing)
    }

    /**
     * Runs what's due now until [condition] holds, without moving the clock: requests and responses travel on real
     * threads meanwhile. A test that suspends instead lets runTest skip ahead while a request is in flight.
     */
    private fun TestScope.runCurrentUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5.seconds.inWholeNanoseconds
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out" }
            runCurrent()
            Thread.sleep(2)
        }
    }

    private fun sent(path: String = USERS) = arrivals.filter { it.first == path }.map { it.second }

    /** The time between each request and the one before it. */
    private fun gaps(times: List<Duration>) = times.zipWithNext { a, b -> b - a }

    @BeforeEach
    fun start() = server.start()

    @AfterEach
    fun stop() {
        server.close()
        assertEquals(emptyList<String>(), unscripted.toList(), "requests nobody scripted")
    }

    // At most 5 sends for a 429, a lost connection, or a 5xx.
    @ParameterizedTest
    @ValueSource(strings = ["429", "500", "503", "drop"])
    fun `a read is sent at most 5 times`(kind: String) = runTest {
        val api = newApi()
        script(USERS, failure(kind), failure(kind), failure(kind), failure(kind), failure(kind), ok)

        val error = assertThrows<ProtonError> { api.user() }

        assertEquals(5, sent().size)
        when (kind) {
            "429" -> assertTrue(error is ProtonError.RateLimited, error.toString())
            "drop" -> assertTrue(error is ProtonError.Network, error.toString())
            else -> assertEquals(kind.toInt(), (error as ProtonError.Api).http)
        }
        answers.getValue(USERS).clear()
    }

    @Test
    fun `every read is retried`() = runTest {
        val api = newApi()
        script(USERS, tooMany(), response(200, fixtureBody("users")))
        script("/core/v4/keys/salts", unavailable(), response(200, fixtureBody("key-salts")))

        api.user()
        api.keySalts()

        assertEquals(List(2) { USERS } + List(2) { "/core/v4/keys/salts" }, arrivals.map { it.first })
    }

    // E1 and E2 are reads like the others.
    @Test
    fun `event reads are retried like every read`() = runTest {
        val api = newApi()
        script(LATEST, tooMany(), response(200, """{"Code":1000,"EventID":"latest-1"}"""))
        script(EVENTS, unavailable(), dropped, response(200, """{"Code":1000,"EventID":"next-1","More":0,"Refresh":0}"""))

        api.latestEventId()
        api.events(EventId("cursor-1"))

        assertEquals(List(2) { LATEST } + List(3) { EVENTS }, arrivals.map { it.first })
    }

    @Test
    fun `an event page is sent at most 5 times`() = runTest {
        val api = newApi()
        script(EVENTS, tooMany(), tooMany(), tooMany(), tooMany(), tooMany())

        assertThrows<ProtonError.RateLimited> { api.events(EventId("cursor-1")) }

        assertEquals(5, sent(EVENTS).size)
    }

    @Test
    fun `a read that recovers within 5 sends returns the answer`() = runTest {
        val api = newApi()
        script(USERS, tooMany(), dropped, unavailable(), response(502, """{"Code":502}"""), ok)

        api.user()
        assertEquals(5, sent().size)
    }

    @Test
    fun `the backoff is 2^n seconds plus jitter, capped at 16 plus 16 and at 30 seconds`() = runTest {
        val api = newApi()
        jitter = { max -> max }
        script(USERS, tooMany(), tooMany(), tooMany(), tooMany(), ok)

        api.user()

        assertEquals(listOf(2, 4, 8, 16).map { it.seconds }, jitterMaxima.toList())
        assertEquals(listOf(4, 8, 16, 30).map { it.seconds }, gaps(sent()))
    }

    @Test
    fun `with no jitter the backoff is 2, 4, 8, and 16 seconds`() = runTest {
        val api = newApi()
        script(USERS, dropped, dropped, dropped, dropped, ok)

        api.user()

        assertEquals(listOf(2, 4, 8, 16).map { it.seconds }, gaps(sent()))
    }

    @Test
    fun `the jitter is added to the base, never more than the cap`() = runTest {
        val api = newApi()
        jitter = { max -> max / 2 }
        script(USERS, unavailable(), unavailable(), unavailable(), unavailable(), ok)

        api.user()

        assertEquals(listOf(3, 6, 12, 24).map { it.seconds }, gaps(sent()))
    }

    @Test
    fun `a valid Retry-After on a 429 or a 5xx is the delay, and an invalid one uses the formula`() = runTest {
        val api = newApi()
        script(USERS, tooMany("3"), unavailable("7"), tooMany("30"), tooMany("7.5"), ok)

        api.user()

        // The fourth wait is retry 4's formula with no jitter: 16 s. Only that one draws jitter.
        assertEquals(listOf(3, 7, 30, 16).map { it.seconds }, gaps(sent()))
        assertEquals(listOf(16.seconds), jitterMaxima.toList())
    }

    @Test
    fun `a Retry-After of 0 retries after the pacing interval`() = runTest {
        val api = newApi()
        script(USERS, tooMany("0"), ok)

        api.user()

        assertEquals(listOf(100.milliseconds), gaps(sent()))
    }

    @Test
    fun `a Retry-After over 30 seconds ends the call, and nothing is sent until it passes`() = runTest {
        val api = newApi()
        script(USERS, tooMany("31"), ok)

        assertEquals(31.seconds, assertThrows<ProtonError.RateLimited> { api.user() }.retryAfter)
        assertEquals(1, arrivals.size)

        // Every route, not just the one that was limited.
        assertTrue(assertThrows<ProtonError.RateLimited> { api.user() }.retryAfter!! in 30.seconds..31.seconds)
        assertThrows<ProtonError.RateLimited> { api.authInfo("ada") }
        assertThrows<ProtonError.RateLimited> { api.keySalts() }
        assertThrows<ProtonError.RateLimited> { api.logout() }
        advanceTimeBy(31.seconds - 1.milliseconds)
        assertEquals(1.milliseconds, assertThrows<ProtonError.RateLimited> { api.user() }.retryAfter)
        assertEquals(1, arrivals.size)

        advanceTimeBy(1.milliseconds)
        api.user()
        assertEquals(listOf(Duration.ZERO, 31.seconds), sent())
    }

    @Test
    fun `a 5xx with a Retry-After over 30 seconds keeps its error and blocks sends too`() = runTest {
        val api = newApi()
        script(USERS, unavailable("45"))

        assertEquals(503, assertThrows<ProtonError.Api> { api.user() }.http)
        assertEquals(45.seconds, assertThrows<ProtonError.RateLimited> { api.keySalts() }.retryAfter)
        assertEquals(1, arrivals.size)
    }

    @Test
    fun `cancelling during the wait sends nothing more`() = runTest {
        val api = newApi()
        script(USERS, tooMany(), ok)
        val call = launch { api.user() }

        // The jitter is drawn just before the wait starts.
        runCurrentUntil { jitterMaxima.isNotEmpty() }
        advanceTimeBy(1.seconds)
        call.cancel()
        advanceUntilIdle()

        assertTrue(call.isCancelled)
        assertEquals(1, arrivals.size)
        answers.getValue(USERS).clear()
    }

    @ParameterizedTest
    @ValueSource(strings = ["429", "drop"])
    fun `a retry sends nothing once another call's app-version rejection has stopped the client`(kind: String) = runTest {
        val api = newApi()
        script(USERS, failure(kind), ok)
        script(AUTH_INFO, response(400, """{"Code":5002}"""))
        val read = async { runCatching { api.user() } }
        runCurrentUntil { jitterMaxima.isNotEmpty() }

        // Time moves only here, so the read can't retry before the rejection arrives.
        val login = async { runCatching { api.authInfo("ada") } }
        advanceTimeBy(100.milliseconds)
        runCurrentUntil { login.isCompleted }
        advanceUntilIdle()

        assertTrue(login.getCompleted().exceptionOrNull() is ProtonError.AppVersionRejected)
        assertTrue(read.getCompleted().exceptionOrNull() is ProtonError.AppVersionRejected)
        assertEquals(1, sent().size)
        answers.getValue(USERS).clear()
    }

    @Test
    fun `calls queued for pacing send nothing once another call's app-version rejection has stopped the client`() = runTest {
        val api = newApi()
        script(AUTH_INFO, response(400, """{"Code":5002}"""))
        script(USERS, ok, ok)
        val login = async { runCatching { api.authInfo("ada") } }
        val reads = List(2) { async { runCatching { api.user() } } }

        // The reads wait their turn while the rejection travels; time moves only once it has arrived.
        runCurrentUntil { login.isCompleted }
        advanceUntilIdle()
        runCurrentUntil { reads.all { it.isCompleted } }

        assertTrue(login.getCompleted().exceptionOrNull() is ProtonError.AppVersionRejected)
        reads.forEach { assertTrue(it.getCompleted().exceptionOrNull() is ProtonError.AppVersionRejected) }
        assertEquals(listOf(AUTH_INFO), arrivals.map { it.first })
        answers.getValue(USERS).clear()
    }

    @Test
    fun `a call queued for pacing sends nothing once a long Retry-After has blocked the client`() = runTest {
        val api = newApi()
        script(USERS, tooMany("3600"))
        script("/core/v4/keys/salts", response(200, fixtureBody("key-salts")))
        val limited = async { runCatching { api.user() } }
        val queued = async { runCatching { api.keySalts() } }

        runCurrentUntil { limited.isCompleted }
        advanceUntilIdle()
        runCurrentUntil { queued.isCompleted }

        assertTrue(queued.getCompleted().exceptionOrNull() is ProtonError.RateLimited)
        assertEquals(listOf(USERS), arrivals.map { it.first })
        answers.getValue("/core/v4/keys/salts").clear()
    }

    @Test
    fun `the old spelling isn't sent once another call has stopped the client`() = runTest {
        val api = newApi()
        // The 404 is slow, so the login is queued first: it goes out at 100 ms, and the old spelling's turn is 200 ms.
        script(USERS, response(404, """{"Code":404}""").slow(300))
        script("/users", ok)
        script(AUTH_INFO, response(400, """{"Code":5002}"""))
        val read = async { runCatching { api.user() } }
        runCurrentUntil { arrivals.size == 1 }
        val login = async { runCatching { api.authInfo("ada") } }
        runCurrent()
        advanceTimeBy(100.milliseconds)
        runCurrentUntil { login.isCompleted }

        // Nothing else is in flight now, so the read can run on with the clock.
        assertTrue(read.await().exceptionOrNull() is ProtonError.AppVersionRejected)
        assertEquals(listOf(USERS, AUTH_INFO), arrivals.map { it.first })
        answers.getValue("/users").clear()
    }

    @Test
    fun `cancelling a call that waits for pacing sends nothing`() = runTest {
        val api = newApi()
        script(USERS, ok, ok)
        val first = async { api.user() }
        val second = launch { api.user() }

        runCurrentUntil { first.isCompleted }
        second.cancel()
        advanceUntilIdle()

        assertTrue(second.isCancelled)
        assertEquals(1, sent().size)
        answers.getValue(USERS).clear()
    }

    @Test
    fun `a later, shorter Retry-After doesn't shorten the block`() = runTest {
        val api = newApi()
        // Both reads are out before either answer arrives: the longer answer is back first, the shorter one later.
        script(USERS, tooMany("3600").slow(300))
        script("/core/v4/keys/salts", tooMany("60").slow(900))
        val long = async { runCatching { api.user() } }
        val short = async { runCatching { api.keySalts() } }
        runCurrentUntil { arrivals.size == 1 }
        advanceTimeBy(100.milliseconds)
        runCurrentUntil { long.isCompleted && short.isCompleted }

        // The shorter answer's call reports the client's block, not its own 60 s.
        assertTrue((short.getCompleted().exceptionOrNull() as ProtonError.RateLimited).retryAfter!! > 3000.seconds)
        advanceTimeBy(61.seconds)

        assertTrue(assertThrows<ProtonError.RateLimited> { api.keySalts() }.retryAfter!! > 3000.seconds)
        assertEquals(2, arrivals.size)
    }

    private fun MockResponse.slow(millis: Long) = newBuilder().headersDelay(millis, java.util.concurrent.TimeUnit.MILLISECONDS).build()

    @Test
    fun `a request that can't be built isn't retried`() = runTest {
        val api = newApi()
        store.tokens = Tokens(uid = "uid\n1", access = "access-1", refresh = "refresh-1")

        // A header value OkHttp refuses is an invalid request, never a lost connection.
        assertThrows<ProtonError.InvalidRequest> { api.user() }

        assertEquals(emptyList<Duration>(), jitterMaxima.toList())
        assertEquals(0, arrivals.size)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun `a token store that fails while headers are added isn't retried`() = runTest {
        val api = newApi()
        // The call's check and its turn's check read the store; the third read is the one that adds the headers.
        store.failFromRead = 3

        assertThrows<ProtonError.Network> { api.user() }

        assertEquals(3, store.reads)
        assertEquals(emptyList<Duration>(), jitterMaxima.toList())
        assertEquals(0, arrivals.size)
    }

    // An app-version Code stops the client whatever the status, so a 429 or 5xx carrying one isn't retried.
    @ParameterizedTest
    @ValueSource(ints = [429, 500, 503])
    fun `a retryable status with an app-version Code isn't retried`(status: Int) = runTest {
        val api = newApi()
        script(USERS, response(status, """{"Code":5002}"""), ok)

        assertEquals(5002, assertThrows<ProtonError.AppVersionRejected> { api.user() }.code)
        assertEquals(1, sent().size)
        answers.getValue(USERS).clear()
    }

    // decode reads Code 9001 before the status, so a retryable status carrying it is human verification.
    @ParameterizedTest
    @ValueSource(strings = ["", "3", "31"])
    fun `a 429 carrying Code 9001 is human verification, not retried or blocked`(retryAfter: String) = runTest {
        val api = newApi()
        val details = """{"HumanVerificationToken":"hv","WebUrl":"https://verify.example","HumanVerificationMethods":["captcha"]}"""
        script(USERS, response(429, """{"Code":9001,"Details":$details}""", retryAfter.ifEmpty { null }), ok, ok)

        assertThrows<ProtonError.HumanVerification> { api.user() }
        api.user()

        assertEquals(2, sent().size)
        answers.getValue(USERS).clear()
    }

    @Test
    fun `a 401 on a retry of the resend fails the call without rejecting the session`() = runTest {
        val api = newApi()
        val unauthorized = response(401, """{"Code":401}""")
        script(USERS, unauthorized, unavailable(), unauthorized)
        script(REFRESH, response(200, refreshBody()))

        // Only the resend itself is evidence for docs/protocol.md § Token refresh, step 6.
        assertEquals(401, assertThrows<ProtonError.Api> { api.user() }.http)

        assertEquals(0, store.rejections)
        // The refreshed tokens stay stored.
        assertTrue(store.tokens.let { it != null && it.access != "access-1" })
        assertEquals(1, sent(REFRESH).size)
        assertEquals(3, sent().size)
    }

    @Test
    fun `the old spelling's 401 refreshes, even if another call's refresh failed before it went out`() = runTest {
        val api = newApi()
        val unauthorized = response(401, """{"Code":401}""")
        // The 404 is slow, so the other call is queued for pacing first.
        script(USERS, response(404, """{"Code":404}""").slow(300))
        script("/users", unauthorized, ok)
        script("/core/v4/keys/salts", unauthorized)
        script(REFRESH, unavailable(), response(200, refreshBody()))
        val read = async { api.user() }
        runCurrentUntil { arrivals.size == 1 }

        // Another call's refresh fails while the read's old spelling waits its turn (time moves only here).
        val other = async { runCatching { api.keySalts() } }
        advanceTimeBy(100.milliseconds)
        runCurrentUntil { arrivals.size == 2 }
        advanceTimeBy(100.milliseconds)
        runCurrentUntil { other.isCompleted }
        assertEquals(503, (other.getCompleted().exceptionOrNull() as ProtonError.Api).http)

        // The old spelling went out after that refresh ended, so its 401 refreshes for itself.
        read.await()
        assertEquals(2, sent(REFRESH).size)
    }

    @Test
    fun `a fallback stopped before the old spelling went out doesn't keep it`() = runTest {
        val api = newApi()
        script(USERS, response(404, """{"Code":404}"""), ok)
        val tokens = store.tokens
        // The session disappears while the 404 is on its way back.
        onArrival = { store.tokens = null }

        assertThrows<ProtonError.NoSession> { api.user() }
        onArrival = {}
        store.tokens = tokens
        api.user()

        assertEquals(listOf(USERS, USERS), arrivals.map { it.first })
    }

    @Test
    fun `a 401 after a retry's wait refreshes, even if another call's refresh failed meanwhile`() = runTest {
        val api = newApi()
        val unauthorized = response(401, """{"Code":401}""")
        script(USERS, unavailable(), unauthorized, ok)
        script("/core/v4/keys/salts", unauthorized)
        script(REFRESH, unavailable(), response(200, refreshBody()))
        val read = async { api.user() }
        runCurrentUntil { jitterMaxima.isNotEmpty() }

        // While the read waits 2 s, another call's refresh fails.
        val other = async { runCatching { api.keySalts() } }
        advanceTimeBy(100.milliseconds)
        runCurrentUntil { arrivals.size == 2 }
        advanceTimeBy(100.milliseconds)
        runCurrentUntil { other.isCompleted }
        assertEquals(503, (other.getCompleted().exceptionOrNull() as ProtonError.Api).http)

        // The read's 401 comes after that refresh ended, so it never waited on it: it refreshes for itself.
        read.await()
        assertEquals(2, sent(REFRESH).size)
    }

    @Test
    fun `a retry sends nothing once another call's refresh has rejected the session`() = runTest {
        val api = newApi()
        script(USERS, tooMany(), ok)
        script("/core/v4/keys/salts", response(401, """{"Code":401}"""))
        script(REFRESH, response(400, """{"Code":10013}"""))
        val read = async { runCatching { api.user() } }
        runCurrentUntil { jitterMaxima.isNotEmpty() }

        val other = async { runCatching { api.keySalts() } }
        advanceTimeBy(100.milliseconds)
        runCurrentUntil { arrivals.size == 2 }
        advanceTimeBy(100.milliseconds)
        runCurrentUntil { other.isCompleted }
        advanceUntilIdle()

        assertTrue(read.getCompleted().exceptionOrNull() is ProtonError.SessionRejected)
        assertEquals(1, sent().size)
        answers.getValue(USERS).clear()
    }

    @Test
    fun `other errors are not retried`() = runTest {
        val api = newApi()
        script(USERS, response(422, """{"Code":2001}"""), response(400, """{"Code":1000}"""), response(200, """{"Code":2501}"""))

        assertEquals(422, assertThrows<ProtonError.Api> { api.user() }.http)
        assertEquals(400, assertThrows<ProtonError.Api> { api.user() }.http)
        assertEquals(2501, assertThrows<ProtonError.Api> { api.user() }.code)
        assertEquals(3, sent().size)
    }

    // Login calls, A3, A5, and the refresh are sent once (login retries: docs/protocol.md § Login; the refresh:
    // § Token refresh, step 5).
    @ParameterizedTest
    @ValueSource(strings = ["429", "503", "drop"])
    fun `only reads are retried`(kind: String) = runTest {
        val api = newApi()
        script(AUTH_INFO, failure(kind))
        script(AUTH, failure(kind))
        script(TOTP, failure(kind))
        script(AUTH, failure(kind))
        script(USERS, response(401, """{"Code":401}"""))
        script(REFRESH, failure(kind))

        assertThrows<ProtonError> { api.authInfo("ada") }
        assertThrows<ProtonError> { api.auth(SrpAuthDto("ada", "eph", "proof", "session")) }
        assertThrows<ProtonError> { api.submitTotp("123456") }
        assertThrows<ProtonError> { api.logout() }
        assertThrows<ProtonError> { api.user() }

        assertEquals(listOf(AUTH_INFO, AUTH, TOTP, AUTH, USERS, REFRESH), arrivals.map { it.first })
    }

    @Test
    fun `the resend after a refresh isn't a retry, and the count goes on`() = runTest {
        val api = newApi()
        val unauthorized = response(401, """{"Code":401}""")
        script(USERS, tooMany(), tooMany(), tooMany(), tooMany(), unauthorized, tooMany(), ok)
        script(REFRESH, response(200, refreshBody()))

        assertThrows<ProtonError.RateLimited> { api.user() }

        // Five 429s, plus the 401 that refreshed.
        assertEquals(6, sent().size)
        assertEquals(1, sent(REFRESH).size)
        answers.getValue(USERS).clear()
    }

    @Test
    fun `a read that needed a refresh is still retried after it`() = runTest {
        val api = newApi()
        script(USERS, unavailable(), response(401, """{"Code":401}"""), dropped, ok)
        script(REFRESH, response(200, refreshBody()))

        api.user()

        assertEquals(4, sent().size)
    }

    private fun refreshBody(): String {
        val body = Json.parseToJsonElement(fixtureBody("refresh")).jsonObject.toMutableMap()
        body["UID"] = kotlinx.serialization.json.JsonPrimitive("uid-1")
        body["Uid"] = kotlinx.serialization.json.JsonPrimitive("uid-1")
        return kotlinx.serialization.json.JsonObject(body).toString()
    }

    // Pacing: two requests for one account are never less than 100 ms apart.
    @Test
    fun `back-to-back calls are 100 ms apart`() = runTest {
        val api = newApi()
        script(USERS, ok, ok, ok)

        repeat(3) { api.user() }

        assertEquals(listOf(Duration.ZERO, 100.milliseconds, 200.milliseconds), sent())
    }

    @Test
    fun `concurrent calls are 100 ms apart`() = runTest {
        val api = newApi()
        script(USERS, ok, ok, ok, ok)

        val calls = List(4) { async { api.user() } }
        // Time moves only here, so each request is recorded at the time it was sent.
        for (n in 1..4) {
            runCurrentUntil { arrivals.size == n }
            advanceTimeBy(100.milliseconds)
        }
        runCurrentUntil { calls.all { it.isCompleted } }

        assertEquals(listOf(0, 100, 200, 300).map { it.milliseconds }, sent())
    }

    @Test
    fun `the refresh, its resend, and the old spelling are paced too`() = runTest {
        val api = newApi()
        script(USERS, response(401, """{"Code":401}"""), response(404, """{"Code":404}"""))
        script(REFRESH, response(200, refreshBody()))
        script("/users", ok)

        api.user()

        assertEquals(
            listOf(USERS to 0, REFRESH to 100, USERS to 200, "/users" to 300).map { (path, ms) -> path to ms.milliseconds },
            arrivals.toList(),
        )
    }

    @Test
    fun `event reads are 100 ms apart, with each other and with other reads`() = runTest {
        val api = newApi()
        script(LATEST, response(200, """{"Code":1000,"EventID":"latest-1"}"""))
        script(EVENTS, response(200, """{"Code":1000,"EventID":"next-1","More":0,"Refresh":0}"""))
        script(USERS, ok)

        api.latestEventId()
        api.events(EventId("cursor-1"))
        api.user()

        assertEquals(
            listOf(LATEST to 0, EVENTS to 100, USERS to 200).map { (path, ms) -> path to ms.milliseconds },
            arrivals.toList(),
        )
    }

    @Test
    fun `a call after a pause longer than the interval isn't delayed`() = runTest {
        val api = newApi()
        script(USERS, ok, ok)

        api.user()
        advanceTimeBy(250.milliseconds)
        api.user()

        assertEquals(listOf(Duration.ZERO, 250.milliseconds), sent())
    }

    // C4–C6 keep the 100 ms pacing, with each other and with reads.
    @Test
    fun `writes are 100 ms apart, like every request`() = runTest {
        val api = newApi()
        script(CREATE, response(200, """{"Code":1001,"Responses":[{"Index":0,"Response":{"Code":1000,"Contact":{"ID":"new"}}}]}"""))
        script(UPDATE, response(200, """{"Code":1000}"""))
        script(DELETE, response(200, """{"Code":1001,"Responses":[{"ID":"id-1","Response":{"Code":1000}}]}"""))
        script(USERS, ok)

        api.createContact(listOf(CardDto(type = 0, data = "x")))
        api.updateContact(ProtonContactId("id-1"), listOf(CardDto(type = 0, data = "x")))
        api.deleteContact(ProtonContactId("id-1"))
        api.user()

        assertEquals(
            listOf(CREATE to 0, UPDATE to 100, DELETE to 200, USERS to 300).map { (path, ms) -> path to ms.milliseconds },
            arrivals.toList(),
        )
    }

    // A write's Retry-After over 30 s blocks the client as a read's does.
    @Test
    fun `a write's Retry-After over 30 seconds ends it, and nothing is sent until it passes`() = runTest {
        val api = newApi()
        script(DELETE, tooMany("31"))
        script(USERS, ok)

        assertEquals(31.seconds, assertThrows<ProtonError.RateLimited> { api.deleteContact(ProtonContactId("id-1")) }.retryAfter)
        assertTrue(assertThrows<ProtonError.RateLimited> { api.user() }.retryAfter!! in 30.seconds..31.seconds)
        assertThrows<ProtonError.RateLimited> { api.createContact(listOf(CardDto(type = 0, data = "x"))) }
        assertEquals(1, arrivals.size)

        advanceTimeBy(31.seconds)
        api.user()
        assertEquals(listOf(DELETE to Duration.ZERO, USERS to 31.seconds), arrivals.toList())
    }

    @Test
    fun `a write's 5xx with a Retry-After over 30 seconds keeps its error and blocks sends too`() = runTest {
        val api = newApi()
        script(UPDATE, unavailable("45"))

        assertEquals(503, assertThrows<ProtonError.Api> { api.updateContact(ProtonContactId("id-1"), emptyList()) }.http)
        assertEquals(45.seconds, assertThrows<ProtonError.RateLimited> { api.user() }.retryAfter)
        assertEquals(1, arrivals.size)
    }

    @Test
    fun `a write's shorter Retry-After isn't waited out or resent, and doesn't block`() = runTest {
        val api = newApi()
        script(CREATE, tooMany("30"))
        script(USERS, ok)

        assertEquals(30.seconds, assertThrows<ProtonError.RateLimited> { api.createContact(emptyList()) }.retryAfter)
        api.user()

        assertEquals(listOf(CREATE to Duration.ZERO, USERS to 100.milliseconds), arrivals.toList())
    }

    @Test
    fun `a write's 429 carrying Code 9001 is human verification, not blocked`() = runTest {
        val api = newApi()
        val details = """{"HumanVerificationToken":"hv","WebUrl":"https://verify.example","HumanVerificationMethods":["captcha"]}"""
        script(CREATE, response(429, """{"Code":9001,"Details":$details}""", "31"))
        script(USERS, ok)

        assertThrows<ProtonError.HumanVerification> { api.createContact(emptyList()) }
        api.user()

        assertEquals(listOf(CREATE, USERS), arrivals.map { it.first })
    }

    private companion object {
        const val CREATE = "/contacts/v4/contacts"
        const val UPDATE = "/contacts/v4/contacts/id-1"
        const val DELETE = "/contacts/v4/contacts/delete"
        const val USERS = "/core/v4/users"
        const val AUTH_INFO = "/core/v4/auth/info"
        const val AUTH = "/core/v4/auth"
        const val TOTP = "/core/v4/auth/2fa"
        const val REFRESH = "/core/v4/auth/refresh"
        const val LATEST = "/core/v4/events/latest"
        const val EVENTS = "/core/v5/events/cursor-1"
    }
}
