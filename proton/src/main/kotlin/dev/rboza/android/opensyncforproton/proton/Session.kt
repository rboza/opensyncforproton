package dev.rboza.android.opensyncforproton.proton

import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import okhttp3.HttpUrl

/**
 * Where the client sends requests and the `x-pm-appversion` it sends (D4). [trust] is null for the app, which trusts
 * only the system's CAs (D8); only the end-to-end test sets it, for its own server.
 */
class ProtonConfig(val baseUrl: HttpUrl, val appVersion: String, val trust: ProtonTrust? = null)

/** The TLS trust a client uses instead of the system's. */
class ProtonTrust(val socketFactory: SSLSocketFactory, val trustManager: X509TrustManager)

class Tokens(val uid: String, val access: String, val refresh: String) {
    override fun toString() = "Tokens(redacted)"
}

/**
 * Where the client reads and stores the session's tokens (docs/protocol.md § Token refresh). [current] may be called
 * from several threads at once, OkHttp's included, and while [replace] or [onSessionRejected] runs; those two are
 * called one at a time per client. An exception from [current] or [replace] fails the call as [ProtonError.Network],
 * except that a CancellationException thrown on the caller's coroutine is re-thrown (OkHttp's threads call [current]
 * too). One from [onSessionRejected], other than a CancellationException, is ignored, since the session is over
 * either way.
 */
interface TokenStore {
    fun current(): Tokens?

    /** A persisted store returns only once the new tokens are durable. */
    fun replace(tokens: Tokens)

    /**
     * The session [uid] was explicitly rejected: a persisted store deletes the credentials if they still hold that
     * session, and keeps a newer login's.
     */
    fun onSessionRejected(uid: String)
}

/** The captcha page's token, sent in A2's human-verification headers after the captcha is solved. */
class CaptchaHeaders(val token: String) {
    override fun toString() = "CaptchaHeaders(redacted)"
}
