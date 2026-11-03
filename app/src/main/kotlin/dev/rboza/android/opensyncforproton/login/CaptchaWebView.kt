package dev.rboza.android.opensyncforproton.login

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebChromeClient
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import java.net.URI
import org.json.JSONObject

/**
 * What Proton's verify page may do (docs/architecture.md § Captcha WebView). [webUrl] was already checked by the
 * interactor (https, a Proton host, no user info or port), so its origin is scheme and host.
 */
class CaptchaPolicy(val webUrl: String) {
    private val host: String = URI(webUrl).host

    /** The exact origin of [webUrl], as WebView reports a frame's origin. */
    val origin = "https://$host"

    /** Main-frame navigation only within [origin]; anything else is blocked, never opened elsewhere. */
    fun allowsMainFrame(url: String): Boolean = try {
        val uri = URI(url)
        uri.scheme == "https" && uri.rawAuthority == host
    } catch (_: Exception) {
        false
    }

    /**
     * "Done": a `HUMAN_VERIFICATION_SUCCESS` for a captcha, with a non-empty token, from [origin]'s main frame; null
     * for anything else. The embedded page doesn't redeem the captcha, so its token is the retry's
     * (docs/protocol.md § Captcha (Code 9001)); `:account` checks it before it becomes a header.
     */
    fun doneToken(message: String?, sourceOrigin: String, isMainFrame: Boolean): String? {
        if (!isMainFrame || sourceOrigin != origin || message == null) return null
        return try {
            val json = JSONObject(message)
            val payload = json.opt("payload") as? JSONObject ?: return null
            val token = payload.opt("token") as? String
            token?.takeIf { json.opt("type") == "HUMAN_VERIFICATION_SUCCESS" && payload.opt("type") == "captcha" && it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        /** The JavaScript object `addWebMessageListener` gives [origin]'s frames. */
        const val LISTENER = "OspVerify"

        /**
         * Proton's page reports through `window.AndroidInterface.dispatch(json)`; this defines that and nothing else.
         */
        const val DOCUMENT_START_SCRIPT = "window.AndroidInterface = { dispatch: m => $LISTENER.postMessage(m) };"

        /** Without either feature the page isn't shown (fail closed: "update Android System WebView"). */
        fun supported(isFeatureSupported: (String) -> Boolean) =
            isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) && isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
    }
}

/**
 * JavaScript and DOM storage, which the page needs; no files, content, mixed content, new windows, or geolocation;
 * the page's console messages are dropped and its dialogs cancelled ([CaptchaChromeClient]).
 */
@SuppressLint("SetJavaScriptEnabled")
internal fun WebView.configureForCaptcha() {
    with(settings) {
        javaScriptEnabled = true
        domStorageEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        setSupportMultipleWindows(false)
        javaScriptCanOpenWindowsAutomatically = false
        // With a chrome client set, a geolocation request would reach it; off, it is refused before that.
        setGeolocationEnabled(false)
    }
    webChromeClient = CaptchaChromeClient()
}

/** On success, cancel, or error: everything the page left (history, cache, form data, cookies, DOM storage) goes. */
internal fun WebView.clearAndDestroy() {
    clearHistory()
    clearCache(true)
    clearFormData()
    CookieManager.getInstance().removeAllCookies(null)
    CookieManager.getInstance().flush()
    WebStorage.getInstance().deleteAllData()
    destroy()
}

/**
 * Keeps the main frame on the page's origin, and reports a main page that fails to load. Open only for the device test.
 */
open class CaptchaWebViewClient(private val policy: CaptchaPolicy, private val onLoadFailed: () -> Unit) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        request.isForMainFrame && !policy.allowsMainFrame(request.url.toString())

    /**
     * A backstop for a main frame that left the origin anyway (a server redirect neither guard saw): the page counts as
     * failed, so the screen drops this WebView. It can't stop that page, only keep it from staying; it can never report
     * done, having no listener.
     */
    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        if (!policy.allowsMainFrame(url.orEmpty())) onLoadFailed()
    }

    /**
     * A main-frame request that leaves the origin anyway (a POST never reaches [shouldOverrideUrlLoading]) gets an
     * empty refusal, so no other origin's page ever runs; the 403 then reports a failed page. Runs off the main thread,
     * reading only [policy].
     */
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
        if (request.isForMainFrame && !policy.allowsMainFrame(request.url.toString())) {
            WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        } else {
            null
        }

    // The error itself isn't read (nullable, so a test can call this without one).
    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError?) {
        if (request.isForMainFrame) onLoadFailed()
    }

    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
        if (request.isForMainFrame) onLoadFailed()
    }
}

/**
 * The captcha page. "Done" arrives only through the [CaptchaPolicy.LISTENER] message listener, for
 * [CaptchaPolicy.origin] alone, and counts once; [tearDown] removes it and clears everything. Needs
 * [CaptchaPolicy.supported]. All calls on the main thread.
 */
@SuppressLint("ViewConstructor")
class CaptchaWebView(
    context: Context,
    private val policy: CaptchaPolicy,
    onDone: (token: String) -> Unit,
    onLoadFailed: () -> Unit,
    client: WebViewClient = CaptchaWebViewClient(policy, onLoadFailed),
) : WebView(context) {
    private var done = false
    private val script: ScriptHandler

    init {
        configureForCaptcha()
        webViewClient = client
        WebViewCompat.addWebMessageListener(this, CaptchaPolicy.LISTENER, setOf(policy.origin)) { _, message, sourceOrigin, isMainFrame, _ ->
            // Only text is read: reading another kind of message's data throws.
            if (!done && message.type == WebMessageCompat.TYPE_STRING) {
                policy.doneToken(message.data, sourceOrigin.toString(), isMainFrame)?.let { token ->
                    done = true
                    onDone(token)
                }
            }
        }
        script = WebViewCompat.addDocumentStartJavaScript(this, CaptchaPolicy.DOCUMENT_START_SCRIPT, setOf(policy.origin))
    }

    /** Loads the page as given: the interactor has already added `embed=true`. */
    fun load() = loadUrl(policy.webUrl)

    fun tearDown() {
        WebViewCompat.removeWebMessageListener(this, CaptchaPolicy.LISTENER)
        script.remove()
        clearAndDestroy()
    }
}

/**
 * Drops the page's console messages, which Chromium would otherwise log with the page's URL, and that URL holds the
 * captcha token. The page's dialogs are cancelled, so no frame can put a native prompt over the login screen (a
 * cancelled `beforeunload` keeps the page). File choosers, permission requests, and new windows keep the base class's
 * refusals.
 */
internal class CaptchaChromeClient : WebChromeClient() {
    override fun onConsoleMessage(consoleMessage: ConsoleMessage) = true

    override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult) = refuse(result)

    override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult) = refuse(result)

    override fun onJsPrompt(view: WebView, url: String?, message: String?, defaultValue: String?, result: JsPromptResult) =
        refuse(result)

    override fun onJsBeforeUnload(view: WebView, url: String?, message: String?, result: JsResult) = refuse(result)

    private fun refuse(result: JsResult): Boolean {
        result.cancel()
        return true
    }
}
