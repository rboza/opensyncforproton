package dev.rboza.android.opensyncforproton.account

import dev.rboza.android.opensyncforproton.crypto.CryptoResult
import dev.rboza.android.opensyncforproton.crypto.KeyHierarchy
import dev.rboza.android.opensyncforproton.crypto.LockedKeyBundle
import dev.rboza.android.opensyncforproton.crypto.LockedUserKey
import dev.rboza.android.opensyncforproton.crypto.RejectReason
import dev.rboza.android.opensyncforproton.crypto.SrpClient
import dev.rboza.android.opensyncforproton.crypto.SrpException
import dev.rboza.android.opensyncforproton.proton.CaptchaHeaders
import dev.rboza.android.opensyncforproton.proton.HvDetails
import dev.rboza.android.opensyncforproton.proton.KeySaltDto
import dev.rboza.android.opensyncforproton.proton.ProtonApi
import dev.rboza.android.opensyncforproton.proton.ProtonError
import dev.rboza.android.opensyncforproton.proton.SecondFactor
import dev.rboza.android.opensyncforproton.proton.SrpAuthDto
import dev.rboza.android.opensyncforproton.proton.TokenStore
import dev.rboza.android.opensyncforproton.proton.Tokens
import dev.rboza.android.opensyncforproton.proton.UserDto
import dev.rboza.android.opensyncforproton.proton.secondFactor
import java.net.URI
import java.net.URISyntaxException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A verified login, handed to the app to save (docs/sync.md § Login). [accountName] is U1's `Email`. */
class NewSession(val accountId: AccountId, val accountName: String, val credentials: Credentials) {
    override fun toString() = "NewSession(redacted)"
}

/** Why a login stopped; the UI words each one. No Proton error code is needed to tell them apart. */
enum class LoginFailure {
    /** A blank username or code, or an empty password; nothing was sent. */
    EMPTY_INPUT,

    /** Proton refused A1 or A2 (for example, a wrong username or password). */
    LOGIN_REFUSED,

    /** A bad signed modulus or a `ServerProof` mismatch: possibly not Proton. */
    SERVER_NOT_TRUSTED,
    FIDO_ONLY,
    TWO_FA_UNSUPPORTED,
    PASSWORD_MODE_UNSUPPORTED,

    /** Proton asked for a verification other than a captcha (or one off A2), which only its web app offers. */
    VERIFY_IN_WEB_APP,

    /** A captcha `WebUrl` that isn't `https` on `proton.me`, `protonmail.com`, or a subdomain of either. */
    CAPTCHA_URL_REFUSED,

    /** The captcha page's answer isn't 1 to 4096 printable ASCII characters; nothing was sent. */
    CAPTCHA_TOKEN_REFUSED,
    KEY_V6,
    KEY_POST_QUANTUM,
    KEY_UNSUPPORTED,
    KEY_CORRUPT,
    RATE_LIMITED,
    NETWORK,
    APP_VERSION_REJECTED,
    SERVER_ERROR,
}

sealed interface LoginStep {
    data class Success(val session: NewSession) : LoginStep

    /** [wrongCode]: the last code was refused; the session is kept. */
    data class NeedTotp(val wrongCode: Boolean) : LoginStep

    /** [wrongPassword]: the last mailbox password didn't unlock the keys; the session is kept. */
    data class NeedMailboxPassword(val wrongPassword: Boolean) : LoginStep

    /** Show [webUrl] (checked by [isApprovedCaptchaUrl], then embedded), then call [LoginInteractor.captchaSolved]. */
    class NeedCaptcha(val webUrl: String) : LoginStep {
        override fun toString() = "NeedCaptcha(redacted)"
    }

    /**
     * The login is over, except for [LoginFailure.EMPTY_INPUT], after which the same step can be tried again.
     * [retryAfter] is set for [LoginFailure.RATE_LIMITED] when Proton gave a delay.
     */
    data class Failed(val reason: LoginFailure, val retryAfter: Duration? = null) : LoginStep
}

/** docs/protocol.md § Login, for one attempt. Persists nothing; zeroes every password array it receives. */
interface LoginInteractor {
    suspend fun submitCredentials(username: String, password: CharArray): LoginStep
    suspend fun submitTotp(code: String): LoginStep
    suspend fun submitMailboxPassword(password: CharArray): LoginStep

    /**
     * The embedded captcha page said it's done, with its answer: the retry's token, since the embedded page doesn't
     * redeem the captcha itself (docs/protocol.md § Captcha (Code 9001)).
     */
    suspend fun captchaSolved(token: String): LoginStep

    /** Ends the attempt: one best-effort A5 if a session exists, then its tokens are discarded. */
    fun cancel()
}

/**
 * The one [LoginInteractor]. [newClient] makes this attempt's client around an in-memory [TokenStore]; [scope] runs
 * the best-effort A5 that [cancel] and a failure after A2 send. Every call, [cancel] included, comes from one thread
 * (the UI's), one step at a time, in the order the steps ask for; a step out of order throws [IllegalStateException]
 * and leaves the attempt as it was. SRP, key passphrases, and the key check run on [cpu] and touch none of this
 * object's state, so it is only ever read and written on the caller's thread. A cancellation or an unexpected error is
 * rethrown once the attempt has ended. Step numbers in this file's comments are docs/protocol.md § Login's.
 */
class ProtonLoginInteractor(
    newClient: (TokenStore) -> ProtonApi,
    private val srp: SrpClient,
    private val keys: KeyHierarchy,
    private val scope: CoroutineScope,
    private val cpu: CoroutineDispatcher = Dispatchers.Default,
) : LoginInteractor {
    private enum class Stage { CREDENTIALS, CAPTCHA, TOTP, MAILBOX_PASSWORD, DONE }

    private val tokens = MemoryTokenStore()
    private val api = newClient(tokens)

    @Volatile private var stage = Stage.CREDENTIALS
    @Volatile private var running: Job? = null
    @Volatile private var ended = false
    private var passwordMode = PasswordMode.ONE
    private var userId: String? = null

    /**
     * The login password, kept while the captcha is shown (the retry starts again at A1), and in one-password mode
     * until the keys are unlocked after 2FA (U3 needs the full scope).
     */
    private var loginPassword: CharArray? = null
    private var username: String? = null

    private var fetched: Pair<UserDto, List<KeySaltDto>>? = null

    /** Ends the login with a [LoginStep]; thrown from inside a step. */
    private class Stop(val step: LoginStep) : Exception()

    override suspend fun submitCredentials(username: String, password: CharArray): LoginStep = try {
        step(Stage.CREDENTIALS) {
            if (username.isBlank() || password.isEmpty()) return@step LoginStep.Failed(LoginFailure.EMPTY_INPUT)
            signIn(username, password)
        }
    } finally {
        password.fill('\u0000')
    }

    override suspend fun submitTotp(code: String): LoginStep = step(Stage.TOTP) {
        if (code.isBlank()) return@step LoginStep.Failed(LoginFailure.EMPTY_INPUT)
        // Step 4: six digits; anything else would only use up a try.
        val digits = code.filterNot { it == ' ' }
        if (digits.length != 6 || digits.any { it !in '0'..'9' }) return@step LoginStep.NeedTotp(wrongCode = true)
        try {
            rateLimited { api.submitTotp(digits) }
        } catch (e: ProtonError.Api) {
            // A 401 on A3 never refreshes: the code was wrong, and the user types it again.
            if (e.http == 401) return@step LoginStep.NeedTotp(wrongCode = true)
            throw e
        }
        afterSecondFactor()
    }

    override suspend fun submitMailboxPassword(password: CharArray): LoginStep = try {
        step(Stage.MAILBOX_PASSWORD) {
            if (password.isEmpty()) return@step LoginStep.Failed(LoginFailure.EMPTY_INPUT)
            unlock(password)
        }
    } finally {
        password.fill('\u0000')
    }

    override suspend fun captchaSolved(token: String): LoginStep = step(Stage.CAPTCHA) {
        // An answer that can't safely be a header value ends the login before anything is sent.
        if (!isAcceptableCaptchaToken(token)) return@step LoginStep.Failed(LoginFailure.CAPTCHA_TOKEN_REFUSED)
        // Like afterSecondFactor: the retry gets the password as its own, and keeps a new copy only if it needs one. The
        // answer is used for this retry only (a new 9001 brings a new captcha).
        val password = checkNotNull(loginPassword)
        val name = checkNotNull(username)
        loginPassword = null
        username = null
        try {
            signIn(name, password, CaptchaHeaders(token))
        } finally {
            password.fill('\u0000')
        }
    }

    override fun cancel() {
        end()
        running?.cancel()
    }

    /**
     * Runs one step in its own child scope (so [cancel] ends the step, not the caller's coroutine): checks the order,
     * maps errors, and ends the attempt on a failure or a cancellation.
     */
    private suspend fun step(expected: Stage, block: suspend () -> LoginStep): LoginStep = coroutineScope {
        check(stage == expected) { "login step out of order" }
        running = coroutineContext[Job]
        try {
            block().also { if (it is LoginStep.Failed && it.reason != LoginFailure.EMPTY_INPUT) end() }
        } catch (e: Stop) {
            end()
            e.step
        } catch (e: ProtonError) {
            end()
            LoginStep.Failed(failure(e), (e as? ProtonError.RateLimited)?.retryAfter)
        } catch (e: Throwable) {
            // Cancellation, or anything unexpected (a library error, say): the session still ends (step 7).
            end()
            throw e
        } finally {
            running = null
        }
    }

    /**
     * Steps 1–5 from A1: at most [LOGIN_SENDS] tries, each 429 restarting at A1 with a fresh SRP session. [captcha] is
     * the captcha page's answer, sent on A2.
     */
    private suspend fun signIn(username: String, password: CharArray, captcha: CaptchaHeaders? = null): LoginStep {
        val auth = try {
            rateLimited {
                val info = api.authInfo(username)
                val proofs = try {
                    withContext(cpu) { srp.proofs(info.version, username, password, info.salt, info.modulus, info.serverEphemeral) }
                } catch (_: SrpException) {
                    throw Stop(LoginStep.Failed(LoginFailure.SERVER_NOT_TRUSTED))
                }
                try {
                    api.auth(SrpAuthDto(username, proofs.clientEphemeral, proofs.clientProof, info.srpSession), captcha) to proofs
                } catch (e: ProtonError.HumanVerification) {
                    // Only A2 takes captcha headers; a 9001 anywhere else stays VERIFY_IN_WEB_APP.
                    throw CaptchaAsked(e.details)
                } catch (e: ProtonError.Api) {
                    // 12087: the token is used up and the response names no new one, so the login restarts at A1
                    // without captcha headers, and that A2 gets a new 9001. Without a token sent, 12087 is just a
                    // refusal.
                    if (captcha != null && e.code == TOKEN_INVALID) throw CaptchaAsked(null)
                    throw e
                }
            }
        } catch (e: CaptchaAsked) {
            return e.details?.let { askForCaptcha(username, password, it) } ?: signIn(username, password)
        }.let { (auth, proofs) ->
            // Step 3: from here the session exists, so a failure ends it (step 7).
            tokens.replace(Tokens(auth.uid, auth.accessToken, auth.refreshToken))
            if (auth.serverProof != proofs.expectedServerProof) throw Stop(LoginStep.Failed(LoginFailure.SERVER_NOT_TRUSTED))
            auth
        }
        userId = auth.userId
        passwordMode = when (auth.passwordMode) {
            1 -> PasswordMode.ONE
            2 -> PasswordMode.TWO
            else -> throw Stop(LoginStep.Failed(LoginFailure.PASSWORD_MODE_UNSUPPORTED))
        }
        if (passwordMode == PasswordMode.ONE) loginPassword = password.copyOf()
        return when (auth.twoFactor.secondFactor()) {
            SecondFactor.NONE -> afterSecondFactor()
            SecondFactor.TOTP -> LoginStep.NeedTotp(wrongCode = false).also { stage = Stage.TOTP }
            SecondFactor.FIDO2_ONLY -> LoginStep.Failed(LoginFailure.FIDO_ONLY)
            SecondFactor.UNSUPPORTED -> LoginStep.Failed(LoginFailure.TWO_FA_UNSUPPORTED)
        }
    }

    /** Only a captcha, and only on an approved `WebUrl`; the password is kept for the retry. */
    private fun askForCaptcha(username: String, password: CharArray, details: HvDetails): LoginStep {
        if ("captcha" !in details.methods) return LoginStep.Failed(LoginFailure.VERIFY_IN_WEB_APP)
        if (!isApprovedCaptchaUrl(details.webUrl)) return LoginStep.Failed(LoginFailure.CAPTCHA_URL_REFUSED)
        this.username = username
        loginPassword = password.copyOf()
        stage = Stage.CAPTCHA
        return LoginStep.NeedCaptcha(embeddedCaptchaUrl(details.webUrl))
    }

    private suspend fun afterSecondFactor(): LoginStep = when (passwordMode) {
        PasswordMode.TWO -> LoginStep.NeedMailboxPassword(wrongPassword = false).also { stage = Stage.MAILBOX_PASSWORD }
        PasswordMode.ONE -> {
            // The work below runs off this thread, so it gets the password as its own: cancel() can't zero it mid-use.
            val password = checkNotNull(loginPassword)
            loginPassword = null
            try {
                unlock(password)
            } finally {
                password.fill('\u0000')
            }
        }
    }

    /** Step 6: U1 and U3 (once per attempt), the key passphrases, and a check that they unlock the keys. */
    private suspend fun unlock(password: CharArray): LoginStep {
        val (user, salts) = fetched ?: Pair(api.user(), api.keySalts()).also { fetched = it }
        if (user.id != userId) throw Stop(LoginStep.Failed(LoginFailure.SERVER_ERROR))
        // Salts are matched to keys by ID, never one for all; a key without one gets no passphrase.
        val saltsById = salts.associate { it.id to it.keySalt }
        val passphrases = LinkedHashMap<String, ByteArray>()
        val bundle = lockedKeys(user)
        var handedOver = false
        try {
            // Off the caller's thread, touching only locals; a cancel that came meanwhile (cancel() cancels this step
            // too) is seen when withContext returns.
            val result = withContext(cpu) {
                try {
                    user.keys.forEach { key -> saltsById[key.id]?.let { passphrases[key.id] = srp.keyPassphrase(password, it) } }
                } catch (_: SrpException) {
                    throw Stop(LoginStep.Failed(LoginFailure.KEY_UNSUPPORTED))
                }
                keys.unlock(bundle, passphrases).also { if (it is CryptoResult.Success) it.value.close() }
            }
            return when (result) {
                is CryptoResult.Success -> {
                    val credentials = Credentials(checkNotNull(tokens.current()), passwordMode, saltsById, bundle, passphrases)
                    handedOver = true
                    stage = Stage.DONE
                    // The session now belongs to the caller; this attempt keeps nothing.
                    tokens.clear()
                    forgetPassword()
                    LoginStep.Success(NewSession(AccountId(user.id), user.email, credentials))
                }
                is CryptoResult.Rejected ->
                    if (passwordMode == PasswordMode.TWO && result.reason == RejectReason.WRONG_PASSPHRASE) {
                        LoginStep.NeedMailboxPassword(wrongPassword = true)
                    } else {
                        LoginStep.Failed(keyFailure(result.reason))
                    }
            }
        } finally {
            if (!handedOver) passphrases.values.forEach { it.fill(0) }
        }
    }

    /** At most [LOGIN_SENDS] sends, retried only after a 429 with a Retry-After of at most [MAX_LOGIN_WAIT]. */
    private suspend fun <T> rateLimited(send: suspend () -> T): T {
        var sends = 1
        while (true) {
            try {
                return send()
            } catch (e: ProtonError.RateLimited) {
                val wait = e.retryAfter
                if (sends == LOGIN_SENDS || wait == null || wait > MAX_LOGIN_WAIT) throw e
                delay(wait)
                sends++
            }
        }
    }

    /** Step 7: one best-effort A5 with this attempt's tokens, which are then discarded. */
    private fun end() {
        if (ended) return
        ended = true
        stage = Stage.DONE
        forgetPassword()
        username = null
        if (tokens.current() == null) return
        scope.launch {
            try {
                api.logout()
            } catch (_: Exception) {
                // Best effort: an unended session expires on its own.
            } finally {
                tokens.clear()
            }
        }
    }

    private fun forgetPassword() {
        loginPassword?.fill('\u0000')
        loginPassword = null
    }

    private fun failure(error: ProtonError): LoginFailure = when (error) {
        is ProtonError.Api -> if (userId == null) LoginFailure.LOGIN_REFUSED else LoginFailure.SERVER_ERROR
        is ProtonError.Network -> LoginFailure.NETWORK
        is ProtonError.RateLimited -> LoginFailure.RATE_LIMITED
        is ProtonError.AppVersionRejected -> LoginFailure.APP_VERSION_REJECTED
        // A 9001 off A2 (signIn handles A2's): no captcha can be sent there.
        is ProtonError.HumanVerification -> LoginFailure.VERIFY_IN_WEB_APP
        else -> LoginFailure.SERVER_ERROR
    }

    private fun keyFailure(reason: RejectReason): LoginFailure = when (reason) {
        RejectReason.V6_KEY -> LoginFailure.KEY_V6
        RejectReason.POST_QUANTUM_KEY -> LoginFailure.KEY_POST_QUANTUM
        RejectReason.CORRUPT_DATA, RejectReason.BAD_SIGNATURE -> LoginFailure.KEY_CORRUPT
        // In one-password mode the login password must unlock the keys; a refusal isn't a typo to retry.
        RejectReason.WRONG_PASSPHRASE, RejectReason.NO_PASSPHRASE, RejectReason.NO_PRIMARY_KEY, RejectReason.UNSUPPORTED_KEY,
        // Only writing a contact gives this one; an unlock never does.
        RejectReason.ENCRYPT_OR_SIGN_FAILED,
        -> LoginFailure.KEY_UNSUPPORTED
    }

    /** A2's 9001 ([details]), or its 12087 (null: fetch a new captcha), carried out of [rateLimited] to [signIn]. */
    private class CaptchaAsked(val details: HvDetails?) : Exception()

    private companion object {
        const val LOGIN_SENDS = 3

        /** The captcha token was refused or already used. */
        const val TOKEN_INVALID = 12087
        val MAX_LOGIN_WAIT = 30.seconds
    }
}

/**
 * `https` on `proton.me`, `protonmail.com`, or a subdomain of either, with no user info or port. The WebView parses
 * URLs its own (WHATWG) way, so anything two parsers might read differently is refused: non-ASCII characters (`URI`
 * itself refuses spaces, control characters, and backslashes) and an upper-case scheme or host (the WebView's origin,
 * which docs/architecture.md § Captcha WebView matches exactly, is lower case).
 */
internal fun isApprovedCaptchaUrl(url: String): Boolean {
    if (url.any { it.code > 0x7f }) return false
    val uri = try {
        URI(url)
    } catch (_: URISyntaxException) {
        return false
    }
    val host = uri.host ?: return false
    // The authority is the host alone: no user info, and no port (not even an empty one).
    if (uri.scheme != "https" || uri.rawAuthority != host) return false
    if (host.any { it in 'A'..'Z' }) return false
    return CAPTCHA_HOSTS.any { host == it || host.endsWith(".$it") }
}

private val CAPTCHA_HOSTS = listOf("proton.me", "protonmail.com")

/** U1's user keys as the envelope stores them: at login, and when the event loop fetches them again. */
fun lockedKeys(user: UserDto) =
    LockedKeyBundle(user.keys.map { LockedUserKey(it.id, it.privateKey, active = it.active == 1, primary = it.primary == 1) })

/** This attempt's tokens, never written anywhere (step 3). */
private class MemoryTokenStore : TokenStore {
    @Volatile private var tokens: Tokens? = null

    override fun current() = tokens

    override fun replace(tokens: Tokens) {
        this.tokens = tokens
    }

    override fun onSessionRejected(uid: String) {
        if (tokens?.uid == uid) tokens = null
    }

    fun clear() {
        tokens = null
    }
}

/**
 * The checked `WebUrl` with `embed=true`, replacing any `embed` it has, its other parameters and fragment kept.
 * Embedded, Proton's page fits the WebView and hands the captcha's answer to the app.
 */
internal fun embeddedCaptchaUrl(webUrl: String): String {
    val hash = webUrl.indexOf('#')
    val beforeFragment = if (hash < 0) webUrl else webUrl.substring(0, hash)
    val fragment = if (hash < 0) "" else webUrl.substring(hash)
    val question = beforeFragment.indexOf('?')
    val base = if (question < 0) beforeFragment else beforeFragment.substring(0, question)
    val query = if (question < 0) "" else beforeFragment.substring(question + 1)
    val kept = query.split('&').filter { it.isNotEmpty() && it.substringBefore('=') != "embed" }
    return base + "?" + (kept + "embed=true").joinToString("&") + fragment
}

/** 1 to 4096 printable ASCII characters, so the answer is a safe header value. */
internal fun isAcceptableCaptchaToken(token: String): Boolean = token.length in 1..4096 && token.all { it in '!'..'~' }
