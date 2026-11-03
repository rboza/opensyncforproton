package dev.rboza.android.opensyncforproton.proton

import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ComparableTimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Invocation
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Proton's routes A1–A5, U1, U3, C1–C6, E1, and E2 (docs/protocol.md § Routes). Each call either returns or throws a
 * [ProtonError] (a CancellationException when cancelled). Reads (GETs) retry by docs/protocol.md § Errors and retries;
 * everything else is sent once. A write (C4–C6) that ends in [ProtonError.Network], a 5xx, or a
 * [ProtonError.BadResponse] to a 2xx may or may not have been carried out: docs/sync.md § Writes says how a later run
 * resolves it. Calls may run concurrently; they share one refresh, one pace, and one Retry-After block. A call blocks
 * its thread only inside [TokenStore] calls.
 */
interface ProtonApi {
    suspend fun authInfo(username: String): AuthInfoDto
    suspend fun auth(request: SrpAuthDto, captcha: CaptchaHeaders? = null): AuthDto
    suspend fun submitTotp(code: String)
    suspend fun logout()
    suspend fun user(): UserDto
    suspend fun keySalts(): List<KeySaltDto>
    suspend fun exportContacts(page: Int, pageSize: Int): ContactExportPageDto
    suspend fun listContacts(page: Int, pageSize: Int): ContactListPageDto
    suspend fun contact(id: ProtonContactId): ContactDto
    suspend fun createContact(cards: List<CardDto>): CreateReply
    suspend fun updateContact(id: ProtonContactId, cards: List<CardDto>)
    suspend fun deleteContact(id: ProtonContactId)
    suspend fun latestEventId(): EventId
    suspend fun events(cursor: EventId): EventsReply
}

object ProtonApiFactory {
    /**
     * A new client: one per account, and one per login attempt. Throws IllegalArgumentException if
     * [ProtonConfig.baseUrl] doesn't end in `/`.
     */
    fun create(config: ProtonConfig, tokenStore: TokenStore): ProtonApi = create(config, tokenStore, Timing())

    internal fun create(config: ProtonConfig, tokenStore: TokenStore, timing: Timing): ProtonApi {
        val http = OkHttpClient.Builder()
            // Retries are decided per operation, never by OkHttp, and a 3xx is never followed.
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor(HeadersInterceptor(config, tokenStore))
            .addNetworkInterceptor(HideRetryAfter)
            .apply { config.trust?.let { sslSocketFactory(it.socketFactory, it.trustManager) } }
            .build()
        val routes = Retrofit.Builder()
            .baseUrl(config.baseUrl)
            .client(http)
            .addConverterFactory(protonJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(Routes::class.java)
        return RetrofitProtonApi(routes, tokenStore, timing)
    }
}

/**
 * Where the client's waits come from: the clock that paces sends and times a Retry-After block, and the backoff's
 * random part, a delay in `0..max`.
 */
internal class Timing(
    val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
    val jitter: (max: Duration) -> Duration = { max -> Random.nextLong(max.inWholeMilliseconds + 1).milliseconds },
)

/** A1, A2, and the refresh carry no session from the store; every other route sends the Bearer token and UID. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
private annotation class NoSession

private interface Routes {
    @NoSession @POST("core/v4/auth/info")
    suspend fun authInfo(@Body body: AuthInfoRequestDto): Response<ResponseBody>

    @NoSession @POST("core/v4/auth")
    suspend fun auth(
        @Body body: SrpAuthDto,
        @Header("x-pm-human-verification-token") captchaToken: String?,
        @Header("x-pm-human-verification-token-type") captchaType: String?,
    ): Response<ResponseBody>

    /** No Bearer token: the UID goes in its own header. */
    @NoSession @POST("core/v4/auth/refresh")
    suspend fun refresh(@Header("x-pm-uid") uid: String, @Body body: RefreshRequestDto): Response<ResponseBody>

    @POST("core/v4/auth/2fa")
    suspend fun submitTotp(@Body body: TotpRequestDto): Response<ResponseBody>

    @DELETE("core/v4/auth")
    suspend fun logout(): Response<ResponseBody>

    @GET("core/v4/users")
    suspend fun user(): Response<ResponseBody>

    @GET("users")
    suspend fun userOld(): Response<ResponseBody>

    @GET("core/v4/keys/salts")
    suspend fun keySalts(): Response<ResponseBody>

    @GET("contacts/v4/contacts/export")
    suspend fun exportContacts(@Query("Page") page: Int, @Query("PageSize") pageSize: Int): Response<ResponseBody>

    @GET("contacts/v4/export")
    suspend fun exportContactsOld(@Query("Page") page: Int, @Query("PageSize") pageSize: Int): Response<ResponseBody>

    @GET("contacts/v4/contacts")
    suspend fun listContacts(@Query("Page") page: Int, @Query("PageSize") pageSize: Int): Response<ResponseBody>

    @GET("contacts/v4")
    suspend fun listContactsOld(@Query("Page") page: Int, @Query("PageSize") pageSize: Int): Response<ResponseBody>

    @GET("contacts/v4/contacts/{id}")
    suspend fun contact(@Path("id") id: String): Response<ResponseBody>

    @GET("contacts/v4/{id}")
    suspend fun contactOld(@Path("id") id: String): Response<ResponseBody>

    @POST("contacts/v4/contacts")
    suspend fun createContacts(@Body body: CreateContactsRequestDto): Response<ResponseBody>

    @PUT("contacts/v4/contacts/{id}")
    suspend fun updateContact(@Path("id") id: String, @Body body: CardsRequestDto): Response<ResponseBody>

    @PUT("contacts/v4/contacts/delete")
    suspend fun deleteContacts(@Body body: DeleteContactsRequestDto): Response<ResponseBody>

    @GET("core/v4/events/latest")
    suspend fun latestEvent(): Response<ResponseBody>

    @GET("core/v5/events/{id}")
    suspend fun events(@Path("id") id: String): Response<ResponseBody>
}

/** The headers every request carries, and the session on every route but A1, A2, and the refresh. */
private class HeadersInterceptor(private val config: ProtonConfig, private val tokenStore: TokenStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        // Anything but an IOException thrown here is re-thrown on OkHttp's dispatcher thread, uncaught, and a header
        // value OkHttp refuses names itself in the message; any exception becomes a bare NotSent (HeaderRefused for a
        // refused session value). An Error (a store failing that badly) isn't caught: catching those is unsafe, and
        // the process is going down anyway.
        val request = try {
            withHeaders(chain.request())
        } catch (notSent: NotSent) {
            throw notSent
        } catch (_: Exception) {
            throw NotSent()
        }
        return chain.proceed(request)
    }

    private fun withHeaders(request: okhttp3.Request): okhttp3.Request {
        val builder = request.newBuilder()
            .header("x-pm-apiversion", "3")
            .header("Accept", "application/vnd.protonmail.v1+json")
            .header("x-pm-appversion", config.appVersion)
        val noSession = request.tag(Invocation::class.java)?.method()?.isAnnotationPresent(NoSession::class.java) == true
        if (!noSession) {
            // The client checks for a session before sending; one that disappeared since is a failed send.
            val tokens = tokenStore.current() ?: throw NotSent()
            try {
                builder.header("Authorization", "Bearer ${tokens.access}").header("x-pm-uid", tokens.uid)
            } catch (_: IllegalArgumentException) {
                throw HeaderRefused()
            }
        }
        return builder.build()
    }
}

/** The request couldn't be built, so nothing was sent; unlike a lost connection, a read doesn't retry it. */
private open class NotSent : IOException()

/** OkHttp refused a session value as a header: an invalid request, not a failed send. */
private class HeaderRefused : NotSent()

/**
 * OkHttp resends once, whatever the method, on a 503 with `Retry-After: 0`. Retries are this client's decision, so
 * the header is moved out of OkHttp's sight before it can act on it.
 */
private object HideRetryAfter : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val response = chain.proceed(chain.request())
        val retryAfter = response.header("Retry-After") ?: return response
        return response.newBuilder().removeHeader("Retry-After").header(RETRY_AFTER, retryAfter).build()
    }
}

private const val RETRY_AFTER = "OpenSync-Retry-After"

/** Whole seconds only (RFC 9110 delay-seconds); a Retry-After in any other form counts as none. */
private val DELAY_SECONDS = Regex("[0-9]{1,9}")

/**
 * A read route with two spellings: a 404 from the current one tries the old one, and the one that answers is kept
 * until it fails to answer. Decided from the route's own responses, so a refresh's errors never trigger it.
 */
private class ReadRoute {
    @Volatile var useOld = false

    /** [beforeOld] waits for the old spelling's turn: a second request waits like any other. */
    suspend fun send(
        current: suspend () -> Response<ResponseBody>,
        old: suspend () -> Response<ResponseBody>,
        beforeOld: suspend () -> Unit,
    ): Response<ResponseBody> {
        if (!useOld) {
            val response = current()
            if (response.code() != 404) return response
            response.errorBody()?.close()
            beforeOld()
        }
        val response = try {
            old()
        } catch (error: IOException) {
            useOld = false
            throw error
        }
        // Kept only if the old spelling answered: not a request that was stopped or got no response, and not a 404.
        useOld = response.code() != 404
        return response
    }
}

private class RetrofitProtonApi(
    private val routes: Routes,
    private val tokenStore: TokenStore,
    private val timing: Timing,
) : ProtonApi {
    /** Set by an app-version rejection; from then on this client sends nothing. */
    @Volatile private var rejectedCode: Int? = null

    /** Set by a rejected refresh, or a 401 on the resend after a refresh; from then on this client sends nothing. */
    @Volatile private var sessionRejected = false

    private val refreshLock = Mutex()
    private val secureRandom = SecureRandom()

    /** Refreshes finished (or abandoned) so far, and the last one's error; both change only under [refreshLock]. */
    @Volatile private var refreshAttempts = 0
    @Volatile private var lastRefreshFailure: ProtonError? = null

    /** When the last request went out; changes only under [paceLock]. */
    private var lastSend: ComparableTimeMark? = null
    private val paceLock = Mutex()

    /**
     * Set by a read's or write's Retry-After over 30 s, and only ever moved later: until then this client sends
     * nothing.
     */
    private val notBefore = AtomicReference<ComparableTimeMark?>(null)

    private val userRoute = ReadRoute()
    private val exportRoute = ReadRoute()
    private val listRoute = ReadRoute()
    private val contactRoute = ReadRoute()

    override suspend fun authInfo(username: String) =
        call(AuthInfoDto.serializer(), needsSession = false) { routes.authInfo(AuthInfoRequestDto(username, intent = "Proton")) }

    override suspend fun auth(request: SrpAuthDto, captcha: CaptchaHeaders?) =
        call(AuthDto.serializer(), needsSession = false) { routes.auth(request, captcha?.token, captcha?.let { "captcha" }) }

    override suspend fun submitTotp(code: String) {
        // A3's tokens are minutes old, so a 401 is about the code: no refresh.
        call(CodeDto.serializer(), needsSession = true, refreshOn401 = false) { routes.submitTotp(TotpRequestDto(code)) }
    }

    override suspend fun logout() {
        // Token refresh step 6: a 401 from A5 never triggers a refresh.
        call(CodeDto.serializer(), needsSession = true, refreshOn401 = false) { routes.logout() }
    }

    override suspend fun user() = read(userRoute, UserResponseDto.serializer(), routes::user, routes::userOld).user

    override suspend fun exportContacts(page: Int, pageSize: Int) = read(
        exportRoute, ContactExportPageDto.serializer(),
        { routes.exportContacts(page, pageSize) }, { routes.exportContactsOld(page, pageSize) },
    )

    override suspend fun listContacts(page: Int, pageSize: Int) = read(
        listRoute, ContactListPageDto.serializer(),
        { routes.listContacts(page, pageSize) }, { routes.listContactsOld(page, pageSize) },
    )

    override suspend fun contact(id: ProtonContactId) =
        read(contactRoute, ContactResponseDto.serializer(), { routes.contact(id.value) }, { routes.contactOld(id.value) }).contact

    override suspend fun keySalts() = read(KeySaltsResponseDto.serializer()) { routes.keySalts() }.keySalts

    override suspend fun createContact(cards: List<CardDto>): CreateReply {
        val request = CreateContactsRequestDto.of(cards)
        val item = write(CreateContactsResponseDto.serializer(), success = BATCH) { routes.createContacts(request) }
            .responses.singleOrNull()?.takeIf { it.index == 0 }?.response ?: throw ProtonError.BadResponse(ITEM_HTTP)
        return when (item.code) {
            1000 -> CreateReply.Created(
                ProtonContactId(item.contact?.id?.takeIf { it.isNotEmpty() } ?: throw ProtonError.BadResponse(ITEM_HTTP)),
            )
            2500 -> CreateReply.UidExists
            2001 -> CreateReply.CardRefused
            else -> throw ProtonError.Api(ITEM_HTTP, item.code)
        }
    }

    override suspend fun updateContact(id: ProtonContactId, cards: List<CardDto>) {
        // A blank ID would put the cards on the collection's path; Retrofit refuses only "." and "..".
        if (id.value.isEmpty()) throw ProtonError.InvalidRequest()
        val request = CardsRequestDto.of(cards)
        // The reply's contact carries no cards; the caller re-reads it.
        write(CodeDto.serializer(), success = 1000) { routes.updateContact(id.value, request) }
    }

    override suspend fun deleteContact(id: ProtonContactId) {
        val request = DeleteContactsRequestDto.of(id)
        val item = write(DeleteContactsResponseDto.serializer(), success = BATCH) { routes.deleteContacts(request) }
            .responses.singleOrNull()?.takeIf { it.id == id.value } ?: throw ProtonError.BadResponse(ITEM_HTTP)
        // A contact already deleted answers 1000 too.
        if (item.response.code != 1000) throw ProtonError.Api(ITEM_HTTP, item.response.code)
    }

    override suspend fun latestEventId() = read(LatestEventDto.serializer()) { routes.latestEvent() }.eventId

    override suspend fun events(cursor: EventId): EventsReply {
        // An empty cursor would ask for the events collection itself. Retrofit refuses "." and ".." when it builds the
        // request (InvalidRequest, nothing sent), and encodes anything else as one path segment.
        if (cursor.value.isEmpty()) throw ProtonError.InvalidRequest()
        return try {
            EventsReply.Page(read(EventPageResponseDto.serializer()) { routes.events(cursor.value) }.page)
        } catch (error: ProtonError.Api) {
            // An unknown cursor and a malformed one both answer this.
            if (error.http == 400 && error.code == 2061) EventsReply.CursorRejected else throw error
        }
    }

    /**
     * A write: sent once, never on the old spelling. A 401 refreshes and resends once (the server acted on nothing),
     * and a Retry-After over [MAX_WAIT] blocks this client as a read's does.
     */
    private suspend fun <T> write(strategy: DeserializationStrategy<T>, success: Int, send: suspend () -> Response<ResponseBody>): T =
        call(strategy, needsSession = true, blocksOnRetryAfter = true, success = success) { send() }

    private suspend fun <T> read(
        route: ReadRoute,
        strategy: DeserializationStrategy<T>,
        current: suspend () -> Response<ResponseBody>,
        old: suspend () -> Response<ResponseBody>,
    ): T = read(strategy) { turn -> route.send(current, old, beforeOld = turn::await) }

    private suspend fun <T> read(strategy: DeserializationStrategy<T>, send: suspend (Turn) -> Response<ResponseBody>): T =
        call(strategy, needsSession = true, retries = Retries(), send = send)

    /**
     * Sends and turns the response into [T] or a [ProtonError]: once, or for a read with [retries] until its limit. A
     * 401 on a session route refreshes the session and resends once, unless [refreshOn401] is false (A3, A5); the
     * refresh follows docs/protocol.md § Token refresh, whose steps the comments below cite. That resend isn't a
     * retry, and the read's count goes on. Reads, and writes with [blocksOnRetryAfter], let a long Retry-After block
     * this client. [success] is the route's success `Code`.
     */
    private suspend fun <T> call(
        strategy: DeserializationStrategy<T>,
        needsSession: Boolean,
        refreshOn401: Boolean = needsSession,
        retries: Retries? = null,
        blocksOnRetryAfter: Boolean = retries != null,
        success: Int = 1000,
        send: suspend (Turn) -> Response<ResponseBody>,
    ): T {
        checkCanSend(needsSession)
        val turn = Turn(needsSession)
        val response = send(send, retries, blocksOnRetryAfter, turn)
        if (response.code() != 401 || !refreshOn401) return decode(response, strategy, success)
        response.errorBody()?.close()
        refreshAfter401(sentBearer = response.raw().request.header("Authorization"), turn.refreshesBefore)
        val retriesBefore = retries?.made
        val resent = send(send, retries, blocksOnRetryAfter, turn)
        // Step 6: a 401 on the resent request itself counts as a rejection; one on a later retry of it just fails the
        // call. Under the lock, so a refresh in progress can't save new tokens after the credentials are deleted.
        if (resent.code() == 401 && retries?.made == retriesBefore) {
            resent.errorBody()?.close()
            // A session route always carries its UID; if it ever didn't, nothing is deleted and this client still
            // stops.
            refreshLock.withLock { rejectSession(resent.raw().request.header("x-pm-uid").orEmpty()) }
        }
        return decode(resent, strategy, success)
    }

    /**
     * What stops a send before it starts: a rejected session or app version, a Retry-After block that hasn't passed,
     * or (for session routes) no session.
     */
    private fun checkCanSend(needsSession: Boolean) {
        if (sessionRejected) throw ProtonError.SessionRejected()
        rejectedCode?.let { throw ProtonError.AppVersionRejected(it) }
        notBefore.get()?.let { if (it.hasNotPassedNow()) throw ProtonError.RateLimited(-it.elapsedNow()) }
        if (needsSession && currentTokens() == null) throw ProtonError.NoSession()
    }

    /**
     * Sends once, or for a read at most [READ_SENDS] times for a 429, a lost connection, or a 5xx, each retry after a
     * valid Retry-After or else the backoff. A Retry-After over [MAX_WAIT] isn't waited out: this client
     * sends nothing until it has passed, and the call ends with RateLimited (the time left on that block) for a 429,
     * or the 5xx's own error. Writes block the same way ([blocksOnRetryAfter]) but are never retried.
     */
    private suspend fun send(
        send: suspend (Turn) -> Response<ResponseBody>,
        retries: Retries?,
        blocksOnRetryAfter: Boolean,
        turn: Turn,
    ): Response<ResponseBody> {
        while (true) {
            val response = sendOnce(send, turn)
            if (response == null) {
                if (retries?.next(retryAfter = null) == true) continue
                throw ProtonError.Network()
            }
            val http = response.code()
            if (!blocksOnRetryAfter || (http != 429 && http !in 500..599)) return response
            // decode reads these Codes before the status: an app-version rejection stops the client, and 9001 asks for
            // human verification, whatever the status.
            if (peekCode(response).let { it in APP_VERSION_REJECTED || it == 9001 }) return response
            val retryAfter = retryAfter(response)
            if (retryAfter != null && retryAfter > MAX_WAIT) {
                val until = timing.timeSource.markNow() + retryAfter
                val block = notBefore.updateAndGet { current -> if (current != null && current > until) current else until }!!
                if (http != 429) return response
                // The client's whole block, which an earlier, longer Retry-After may have set.
                response.errorBody()?.close()
                throw ProtonError.RateLimited(-block.elapsedNow())
            }
            if (retries == null || !retries.next(retryAfter)) return response
            response.errorBody()?.close()
        }
    }

    /** One read's retries; the resend after a refresh isn't one. */
    private inner class Retries {
        var made = 0
            private set

        /**
         * Waits before retry n (a valid [retryAfter], or `min(min(2^n, 16) + random(0..min(2^n, 16)), 30)` seconds)
         * and returns true, or returns false once the read has made all its sends.
         */
        suspend fun next(retryAfter: Duration?): Boolean {
            if (made == READ_SENDS - 1) return false
            made++
            val base = minOf((1L shl made).seconds, 16.seconds)
            delay(retryAfter ?: minOf(base + timing.jitter(base), MAX_WAIT))
            return true
        }
    }

    /** At most one request every [PACE] from this client, whatever sends it. */
    private suspend fun pace() = paceLock.withLock {
        lastSend?.let { delay(PACE - it.elapsedNow()) }
        lastSend = timing.timeSource.markNow()
    }

    /** One call's turns to send. */
    private inner class Turn(private val needsSession: Boolean) {
        /** Refreshes finished when this call's latest request went out; only a later one is one it waited on. */
        var refreshesBefore = refreshAttempts
            private set

        /** Waits for this client's turn to send, then checks that nothing stopped it meanwhile. */
        suspend fun await() {
            pace()
            checkCanSend(needsSession)
            refreshesBefore = refreshAttempts
        }
    }

    /** One request, sent when its turn comes; null if the connection was lost before a response arrived. */
    private suspend fun sendOnce(send: suspend (Turn) -> Response<ResponseBody>, turn: Turn): Response<ResponseBody>? = try {
        turn.await()
        send(turn)
    } catch (_: HeaderRefused) {
        throw ProtonError.InvalidRequest()
    } catch (_: NotSent) {
        throw ProtonError.Network()
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        // OkHttp refusing a header value, or the converter refusing a body; both name the value.
        throw ProtonError.InvalidRequest()
    }

    /**
     * Step 1: one refresh at a time. A caller whose request went out with tokens that have since been replaced just
     * resends; one that waited on a refresh that failed gets that refresh's error instead of starting another.
     */
    private suspend fun refreshAfter401(sentBearer: String?, refreshesBefore: Int) = refreshLock.withLock {
        if (sessionRejected) throw ProtonError.SessionRejected()
        val tokens = currentTokens() ?: throw ProtonError.NoSession()
        if (sentBearer != "Bearer ${tokens.access}") return@withLock
        if (refreshAttempts != refreshesBefore) lastRefreshFailure?.let { throw it }
        // A cancelled refresh leaves its waiters a retryable failure.
        var failure: ProtonError? = ProtonError.Network()
        try {
            refresh(tokens)
            failure = null
        } catch (error: ProtonError) {
            failure = error
            throw error
        } finally {
            lastRefreshFailure = failure
            refreshAttempts++
        }
    }

    /** Steps 2–5. Anything but success or an explicit rejection leaves the stored tokens as they were. */
    private suspend fun refresh(tokens: Tokens) {
        val request = RefreshRequestDto(
            uid = tokens.uid, refreshToken = tokens.refresh, responseType = "token", grantType = "refresh_token",
            redirectUri = "https://protonmail.ch", state = freshState(),
        )
        val refreshed = try {
            call(RefreshDto.serializer(), needsSession = false, refreshOn401 = false) { routes.refresh(tokens.uid, request) }
        } catch (error: ProtonError.Api) {
            if (error.code == 10013 && error.http in REFRESH_REJECTED_HTTP) rejectSession(tokens.uid)
            throw error
        }
        // A refresh keeps the UID; another one is an answer to some other session.
        if (refreshed.uid != tokens.uid) throw ProtonError.BadResponse(200)
        try {
            tokenStore.replace(Tokens(refreshed.uid, refreshed.accessToken, refreshed.refreshToken))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Step 3: not saved, so not resent; the run fails as retryable (the stored refresh token still works).
            throw ProtonError.Network()
        }
    }

    /**
     * Step 4, called under [refreshLock]: the session [uid] is over. The store deletes the credentials if they still
     * hold it, and this client sends nothing more.
     */
    private fun rejectSession(uid: String): Nothing {
        sessionRejected = true
        try {
            tokenStore.onSessionRejected(uid)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The session is over either way; the store's failure has nothing to add (its message is never shown).
        }
        throw ProtonError.SessionRejected()
    }

    private fun freshState(): String {
        val bytes = ByteArray(16).also { secureRandom.nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun <T> decode(response: Response<ResponseBody>, strategy: DeserializationStrategy<T>, success: Int): T {
        val http = response.code()
        val text = try {
            (response.body() ?: response.errorBody())?.use { it.string() }
        } catch (_: IOException) {
            throw ProtonError.Network()
        }
        val json = text?.let { runCatching { protonJson.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        val code = (json?.get("Code") as? JsonPrimitive)?.intOrNull
        when {
            code != null && code in APP_VERSION_REJECTED -> {
                rejectedCode = code
                throw ProtonError.AppVersionRejected(code)
            }
            code == 9001 -> throw ProtonError.HumanVerification(humanVerification(json) ?: throw ProtonError.BadResponse(http))
            http == 429 -> throw ProtonError.RateLimited(retryAfter(response))
            http !in 200..299 -> throw ProtonError.Api(http, code)
            json == null || code == null -> throw ProtonError.BadResponse(http)
            code != success -> throw ProtonError.Api(http, code)
        }
        return try {
            protonJson.decodeFromJsonElement(strategy, json)
        } catch (_: IllegalArgumentException) {
            // kotlinx.serialization's messages echo the JSON and the bad value.
            throw ProtonError.BadResponse(http)
        }
    }

    /** The error body's `Code`, read without consuming it (Retrofit buffers error bodies). */
    private fun peekCode(response: Response<ResponseBody>): Int? {
        val text = runCatching { response.errorBody()?.source()?.peek()?.readUtf8() }.getOrNull() ?: return null
        val json = runCatching { protonJson.parseToJsonElement(text) as? JsonObject }.getOrNull()
        return (json?.get("Code") as? JsonPrimitive)?.intOrNull
    }

    /** Retry-After in seconds, or null when it's absent or not [DELAY_SECONDS]. */
    private fun retryAfter(response: Response<ResponseBody>): Duration? =
        response.headers()[RETRY_AFTER]?.takeIf { DELAY_SECONDS.matches(it) }?.toLong()?.seconds

    /** The session, or a failed send if the store can't read it (its message is never shown). */
    private fun currentTokens(): Tokens? = try {
        tokenStore.current()
    } catch (cancelled: CancellationException) {
        // A CancellationException is an IllegalStateException; it's never swallowed.
        throw cancelled
    } catch (_: Exception) {
        throw ProtonError.Network()
    }

    private fun humanVerification(json: JsonObject?): HvDetails? {
        val details = json?.get("Details") as? JsonObject ?: return null
        // The token goes back in an A2 header, so it must be one OkHttp accepts: visible ASCII only.
        val token = (details["HumanVerificationToken"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.isNotEmpty() && it.all { char -> char in '!'..'~' } } ?: return null
        val webUrl = (details["WebUrl"] as? JsonPrimitive)?.contentOrNull ?: return null
        val methods = (details["HumanVerificationMethods"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?: listOf("captcha")
        return HvDetails(token, webUrl, methods)
    }

    private companion object {
        /** App version missing, invalid, or no longer supported (5001–5003); product not valid (2064). */
        val APP_VERSION_REJECTED = setOf(5001, 5002, 5003, 2064)

        /** Token refresh step 4: `Code` 10013 with one of these statuses is a rejection. */
        val REFRESH_REJECTED_HTTP = setOf(400, 422)

        /** A read's sends, and the longest wait inside a run (a longer one would keep keys unlocked). */
        const val READ_SENDS = 5
        val MAX_WAIT = 30.seconds

        /** The web client's safe interval for 100 requests per 10 s. */
        val PACE = 100.milliseconds

        /** C4's and C6's success `Code`: each item then carries its own. */
        const val BATCH = 1001

        /** The status a batch reply's items arrive with; an item's error reports it. */
        const val ITEM_HTTP = 200
    }
}
