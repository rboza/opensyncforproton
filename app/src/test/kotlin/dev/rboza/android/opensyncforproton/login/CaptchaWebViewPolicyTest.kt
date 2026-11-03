package dev.rboza.android.opensyncforproton.login

import android.net.Uri
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.WebViewFeature
import java.io.File
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadow.api.Shadow

/**
 * What the captcha page may do (docs/architecture.md § Captcha WebView). Robolectric for Android's JSON and WebView
 * settings; the real message path is CaptchaWebViewDeviceTest.
 */
@RunWith(RobolectricTestRunner::class)
class CaptchaWebViewPolicyTest {
    private val policy = CaptchaPolicy(WEB_URL)

    private fun done(type: Any? = "HUMAN_VERIFICATION_SUCCESS", payloadType: Any? = "captcha", token: Any? = "page-token") =
        org.json.JSONObject().apply {
            put("type", type)
            put("payload", org.json.JSONObject().apply { put("type", payloadType); put("token", token) })
        }.toString()

    @Test
    fun `the origin is the checked page's exact origin`() {
        assertEquals("https://verify.proton.me", policy.origin)
        assertEquals("https://proton.me", CaptchaPolicy("https://proton.me/verify?token=t").origin)
    }

    @Test
    fun `main-frame navigation stays on that origin`() {
        assertTrue(policy.allowsMainFrame("https://verify.proton.me/"))
        assertTrue(policy.allowsMainFrame("https://verify.proton.me/other/path?x=1#y"))
        listOf(
            "http://verify.proton.me/",
            "https://proton.me/",
            "https://account.proton.me/",
            "https://evil.verify.proton.me/",
            "https://verify.proton.me.example.test/",
            "https://verify.proton.me:443/",
            "https://verify.proton.me:8443/",
            "https://user@verify.proton.me/",
            "https://*.proton.me/",
            "javascript:alert(1)",
            "data:text/html,hi",
            "file:///sdcard/x",
            "intent://verify.proton.me#Intent;end",
            "https://verify.proton.me\\@example.test/",
            "not a url",
            "",
        ).forEach { assertFalse(it, policy.allowsMainFrame(it)) }
    }

    @Test
    fun `only a captcha success with a token, from the page's main frame, counts as done, and gives its token`() {
        assertEquals("page-token", policy.doneToken(done(), "https://verify.proton.me", isMainFrame = true))

        assertNull(policy.doneToken(done(), "https://verify.proton.me", isMainFrame = false))
        listOf("https://proton.me", "http://verify.proton.me", "https://verify.proton.me:443", "https://evil.example.test", "null", "")
            .forEach { assertNull(it, policy.doneToken(done(), it, isMainFrame = true)) }
        listOf(
            done(type = "LOADED"), done(type = "CLOSE"), done(type = "ERROR"), done(type = "NOTIFICATION"), done(type = true),
            done(payloadType = "sms"), done(payloadType = "email"), done(payloadType = null), done(payloadType = 1),
            done(token = ""), done(token = null), done(token = 42), done(token = org.json.JSONObject()),
            """{"type":"HUMAN_VERIFICATION_SUCCESS"}""",
            """{"type":"HUMAN_VERIFICATION_SUCCESS","payload":"captcha"}""",
            """["HUMAN_VERIFICATION_SUCCESS"]""",
            "HUMAN_VERIFICATION_SUCCESS",
            "{",
            "",
        ).forEach { assertNull(it, policy.doneToken(it, "https://verify.proton.me", isMainFrame = true)) }
        assertNull(policy.doneToken(null, "https://verify.proton.me", isMainFrame = true))
    }

    @Test
    fun `the document-start script defines only AndroidInterface's dispatch, posting to the listener`() {
        assertEquals("OspVerify", CaptchaPolicy.LISTENER)
        assertEquals(
            "window.AndroidInterface = { dispatch: m => OspVerify.postMessage(m) };",
            CaptchaPolicy.DOCUMENT_START_SCRIPT,
        )
    }

    @Test
    fun `without either WebView feature the page isn't shown`() {
        val both = setOf(WebViewFeature.WEB_MESSAGE_LISTENER, WebViewFeature.DOCUMENT_START_SCRIPT)
        assertTrue(CaptchaPolicy.supported { it in both })
        assertFalse(CaptchaPolicy.supported { it == WebViewFeature.WEB_MESSAGE_LISTENER })
        assertFalse(CaptchaPolicy.supported { it == WebViewFeature.DOCUMENT_START_SCRIPT })
        assertFalse(CaptchaPolicy.supported { false })
    }

    @Test
    fun `the page gets JavaScript and DOM storage, and nothing else`() {
        val view = WebView(RuntimeEnvironment.getApplication()).apply { configureForCaptcha() }
        with(view.settings) {
            assertTrue(javaScriptEnabled)
            assertTrue(domStorageEnabled)
            assertFalse(allowFileAccess)
            assertFalse(allowContentAccess)
            assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW, mixedContentMode)
            assertFalse(supportMultipleWindows())
            assertFalse(javaScriptCanOpenWindowsAutomatically)
        }
        assertNull(shadowOf(view).downloadListener)
        // A chrome client that only drops console messages: Chromium logs them with the page's URL, which holds the
        // captcha token. Everything else keeps the base class's refusals.
        val chrome = shadowOf(view).webChromeClient
        assertTrue(chrome is CaptchaChromeClient)
        assertTrue(chrome!!.onConsoleMessage(ConsoleMessage("made-up", WEB_URL, 1, ConsoleMessage.MessageLevel.LOG)))
        assertEquals(
            setOf("onConsoleMessage", "onJsAlert", "onJsConfirm", "onJsPrompt", "onJsBeforeUnload"),
            CaptchaChromeClient::class.java.declaredMethods.filter { Modifier.isPublic(it.modifiers) }.map { it.name }.toSet(),
        )
    }

    @Test
    fun `the page's dialogs are refused, never shown over the login screen`() {
        val chrome = CaptchaChromeClient()
        val view = WebView(RuntimeEnvironment.getApplication())
        // JsResult's constructor is hidden; Robolectric makes one and records whether it was cancelled.
        val results = List(3) { Shadow.newInstanceOf(JsResult::class.java) }
        val prompt = Shadow.newInstanceOf(JsPromptResult::class.java)
        assertTrue(chrome.onJsAlert(view, WEB_URL, "made-up", results[0]))
        assertTrue(chrome.onJsConfirm(view, WEB_URL, "made-up", results[1]))
        assertTrue(chrome.onJsPrompt(view, WEB_URL, "Password?", "", prompt))
        assertTrue(chrome.onJsBeforeUnload(view, WEB_URL, "made-up", results[2]))
        (results + prompt).forEach { assertTrue(shadowOf(it).wasCancelled()) }
    }

    @Test
    fun `teardown clears everything the page left and destroys the view`() {
        val view = WebView(RuntimeEnvironment.getApplication())
        CookieManager.getInstance().setCookie("https://verify.proton.me", "made-up=1")
        view.clearAndDestroy()
        val shadow = shadowOf(view)
        assertTrue(shadow.wasClearHistoryCalled())
        assertTrue(shadow.wasClearCacheCalled())
        assertTrue(shadow.didClearCacheIncludeDiskFiles())
        assertTrue(shadow.wasClearFormDataCalled())
        assertTrue(shadow.wasDestroyCalled())
        assertFalse(CookieManager.getInstance().hasCookies())
    }

    private fun request(url: String, mainFrame: Boolean) = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame() = mainFrame
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = "GET"
        override fun getRequestHeaders() = emptyMap<String, String>()
    }

    @Test
    fun `the client keeps the main frame on the origin and reports only a main page that fails`() {
        var failures = 0
        val client = CaptchaWebViewClient(policy) { failures++ }
        val view = WebView(RuntimeEnvironment.getApplication())

        assertTrue(client.shouldOverrideUrlLoading(view, request("https://evil.example.test/", mainFrame = true)))
        assertFalse(client.shouldOverrideUrlLoading(view, request("https://verify.proton.me/next", mainFrame = true)))
        assertFalse(client.shouldOverrideUrlLoading(view, request("https://hcaptcha.example.test/frame", mainFrame = false)))

        client.onReceivedError(view, request("https://hcaptcha.example.test/frame", mainFrame = false), null)
        client.onReceivedHttpError(view, request("https://verify.proton.me/x.js", mainFrame = false), WebResourceResponse("text/plain", "utf-8", null))
        assertEquals(0, failures)

        client.onReceivedError(view, request(WEB_URL, mainFrame = true), null)
        assertEquals(1, failures)
        client.onReceivedHttpError(view, request(WEB_URL, mainFrame = true), WebResourceResponse("text/plain", "utf-8", null))
        assertEquals(2, failures)

        // A backstop for a main frame that left the origin anyway (a redirect neither guard saw): fail the page, so the
        // screen drops this WebView.
        client.onPageStarted(view, "https://verify.proton.me/next", null)
        assertEquals(2, failures)
        client.onPageStarted(view, "https://evil.example.test/", null)
        assertEquals(3, failures)
        client.onPageStarted(view, null, null)
        assertEquals(4, failures)
    }

    @Test
    fun `a main-frame request to another origin gets an empty refusal, whatever its method`() {
        val client = CaptchaWebViewClient(policy) {}
        val view = WebView(RuntimeEnvironment.getApplication())
        val refused = client.shouldInterceptRequest(view, request("https://evil.example.test/", mainFrame = true))!!
        assertEquals(403, refused.statusCode)
        assertEquals(-1, refused.data.read())
        assertNull(client.shouldInterceptRequest(view, request("https://verify.proton.me/next", mainFrame = true)))
        assertNull(client.shouldInterceptRequest(view, request("https://hcaptcha.example.test/frame", mainFrame = false)))
    }

    @Test
    fun `no app source uses addJavascriptInterface`() {
        val sources = File("src/main").walk().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(sources.isNotEmpty())
        assertEquals(emptyList<String>(), sources.filter { "addJavascriptInterface" in it.readText() }.map { it.name })
    }

    private companion object {
        const val WEB_URL = "https://verify.proton.me/?methods=captcha&token=made-up"
    }
}
