package dev.rboza.android.opensyncforproton.proton

import kotlin.time.Duration

/**
 * What a [ProtonApi] call fails with (docs/protocol.md § Errors and retries). No error carries a message or a cause:
 * Proton's `Error` text, captcha values, IO messages (which name hosts and paths), and decoder messages (which echo
 * the JSON) never reach one.
 */
sealed class ProtonError : Exception() {
    /** Any HTTP status or `Code` the client doesn't type further; [code] is null when the body had none. */
    class Api(val http: Int, val code: Int?) : ProtonError() {
        override fun toString() = "ProtonError.Api(http=$http, code=$code)"
    }

    /** The refresh token was rejected, or the resend after a refresh got a 401: this client sends nothing more. */
    class SessionRejected : ProtonError() {
        override fun toString() = "ProtonError.SessionRejected"
    }

    /** `Code` 9001, whatever the HTTP status: Proton asks for human verification. */
    class HumanVerification(val details: HvDetails) : ProtonError() {
        override fun toString() = "ProtonError.HumanVerification"
    }

    /** 5001, 5002, 5003, or 2064: this client sends nothing more. */
    class AppVersionRejected(val code: Int) : ProtonError() {
        override fun toString() = "ProtonError.AppVersionRejected(code=$code)"
    }

    /**
     * HTTP 429, or a call made while a Retry-After over 30 s blocks this client. [retryAfter] is the 429's valid
     * Retry-After or the block's time left; null when the 429 had no valid one.
     */
    class RateLimited(val retryAfter: Duration?) : ProtonError() {
        override fun toString() = "ProtonError.RateLimited(retryAfter=$retryAfter)"
    }

    /**
     * No usable response: a lost connection, a request not sent, a body not read, a [TokenStore] that failed, or a
     * refresh the call waited on that was cancelled.
     */
    class Network : ProtonError() {
        override fun toString() = "ProtonError.Network"
    }

    /** A response that doesn't decode into what the route returns. */
    class BadResponse(val http: Int) : ProtonError() {
        override fun toString() = "ProtonError.BadResponse(http=$http)"
    }

    /** A request that can't be built from what it was given (a header value OkHttp refuses, say); nothing was sent. */
    class InvalidRequest : ProtonError() {
        override fun toString() = "ProtonError.InvalidRequest"
    }

    /** An authenticated call with no session in the token store, before sending or when a 401 needs a refresh. */
    class NoSession : ProtonError() {
        override fun toString() = "ProtonError.NoSession"
    }
}

/** A 9001's details (docs/protocol.md § Captcha (Code 9001)); only the methods show in `toString`. */
class HvDetails(val token: String, val webUrl: String, val methods: List<String>) {
    override fun toString() = "HvDetails(methods=$methods)"
}
