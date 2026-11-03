package dev.rboza.android.opensyncforproton.login

import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The captcha WebView's real message path on the API 37 emulator (docs/architecture.md § Captcha WebView). The test
 * serves every page itself through request interception, so nothing reaches the network; the origins are made-up
 * stand-ins for Proton's verify page and another site.
 */
@RunWith(AndroidJUnit4::class)
class CaptchaWebViewDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val policy = CaptchaPolicy(WEB_URL)
    private val done = AtomicInteger()
    private val tokens = CopyOnWriteArrayList<String>()
    private val doneLatch = CountDownLatch(1)
    private val loadFailed = CountDownLatch(1)
    private val requested = CopyOnWriteArrayList<String>()
    private var page: CaptchaWebView? = null

    /** The production client, with every request answered from [served] (anything else is a 404). */
    private inner class ServingClient(private val served: Map<String, Pair<Int, String>>) :
        CaptchaWebViewClient(policy, { loadFailed.countDown() }) {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
            val url = request.url.toString()
            requested += url
            super.shouldInterceptRequest(view, request)?.let { return it }
            val (status, body) = served[url] ?: (404 to "")
            return WebResourceResponse(
                "text/html", "utf-8", status, if (status == 200) "OK" else "Error", emptyMap(), ByteArrayInputStream(body.toByteArray()),
            )
        }
    }

    private fun show(vararg served: Pair<String, String>, status: Int = 200) {
        val pages = served.associate { (url, html) -> url to ((if (url == WEB_URL) status else 200) to html) }
        instrumentation.runOnMainSync {
            page = CaptchaWebView(
                context, policy, onDone = { token -> tokens += token; done.incrementAndGet(); doneLatch.countDown() }, onLoadFailed = { loadFailed.countDown() },
                client = ServingClient(pages),
            ).also { it.load() }
        }
    }

    /** Waits until the main page's title is [title] (the page's own way of saying it got that far). */
    private fun awaitTitle(title: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            var current: String? = null
            instrumentation.runOnMainSync { current = page?.title }
            if (current == title) return
            Thread.sleep(100)
        }
        error("the page never reached the title $title")
    }

    private fun runScript(script: String) = instrumentation.runOnMainSync { page!!.evaluateJavascript(script, null) }

    @Before
    fun featuresPresent() {
        assertTrue(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
        assertTrue(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
    }

    @After
    fun tearDown() = instrumentation.runOnMainSync { page?.tearDown() }

    @Test
    fun aDispatchFromThePageCountsAndHandsOverItsToken() {
        show(WEB_URL to page("<script>AndroidInterface.dispatch($SUCCESS)</script>"))
        assertTrue(doneLatch.await(15, TimeUnit.SECONDS))
        Thread.sleep(500)
        assertEquals(1, done.get())
        assertEquals(listOf("page-token"), tokens.toList())
        // A normal page never trips the redirect backstop.
        assertEquals(1L, loadFailed.count)
    }

    @Test
    fun onlyTheFirstDoneCounts() {
        show(WEB_URL to page("<script>AndroidInterface.dispatch($SUCCESS); AndroidInterface.dispatch($SUCCESS)</script>"))
        assertTrue(doneLatch.await(15, TimeUnit.SECONDS))
        Thread.sleep(1000)
        assertEquals(1, done.get())
    }

    @Test
    fun otherMessagesDontCount() {
        show(
            WEB_URL to page(
                """<script>
                AndroidInterface.dispatch(JSON.stringify({type: 'LOADED'}));
                AndroidInterface.dispatch(JSON.stringify({type: 'CLOSE'}));
                AndroidInterface.dispatch(JSON.stringify({type: 'HUMAN_VERIFICATION_SUCCESS', payload: {token: '', type: 'captcha'}}));
                AndroidInterface.dispatch(JSON.stringify({type: 'HUMAN_VERIFICATION_SUCCESS', payload: {token: 't', type: 'sms'}}));
                setTimeout(() => { document.title = 'sent' }, 1000);
                </script>""",
            ),
        )
        awaitTitle("sent")
        Thread.sleep(1000)
        assertEquals(0, done.get())
        runScript("AndroidInterface.dispatch($SUCCESS)")
        assertTrue(doneLatch.await(15, TimeUnit.SECONDS))
    }

    @Test
    fun subFramesCantReportDone() {
        val own = """
            try { window.AndroidInterface.dispatch($SUCCESS) } catch (e) {}
            try { window.OspVerify.postMessage($SUCCESS) } catch (e) {}"""
        // Another origin can't reach the page's own AndroidInterface either. (A same-origin frame can, as it can do
        // anything the page itself can: it is Proton's page; see sameOriginFramesActAsThePage.)
        val viaTop = "try { window.top.AndroidInterface.dispatch($SUCCESS) } catch (e) {}"
        show(
            WEB_URL to page(
                """<iframe src="$SAME_ORIGIN_FRAME"></iframe><iframe src="$OTHER_ORIGIN_FRAME"></iframe>
                <script>window.onload = () => setTimeout(() => { document.title = 'framed' }, 1500)</script>""",
            ),
            SAME_ORIGIN_FRAME to page("<script>$own</script>"),
            OTHER_ORIGIN_FRAME to page("<script>$own $viaTop</script>"),
        )
        awaitTitle("framed")
        assertTrue(SAME_ORIGIN_FRAME in requested)
        assertTrue(OTHER_ORIGIN_FRAME in requested)
        Thread.sleep(1000)
        assertEquals(0, done.get())

        // The listener is still there for the page itself.
        runScript("AndroidInterface.dispatch($SUCCESS)")
        assertTrue(doneLatch.await(15, TimeUnit.SECONDS))
    }

    @Test
    fun otherOriginsGetNeitherTheListenerNorTheScript() {
        show(
            WEB_URL to page(
                """<script>window.onmessage = e => { document.title = 'probe:' + e.data }</script>
                <iframe src="$OTHER_ORIGIN_FRAME"></iframe>""",
            ),
            OTHER_ORIGIN_FRAME to page("<script>parent.postMessage(typeof OspVerify + ',' + typeof AndroidInterface, '*')</script>"),
        )
        awaitTitle("probe:undefined,undefined")
    }

    @Test
    fun theSameOriginGetsBoth() {
        show(WEB_URL to page("<script>document.title = typeof OspVerify + ',' + typeof AndroidInterface</script>"))
        awaitTitle("object,object")
    }

    @Test
    fun aMessageThatIsntTextIsIgnored() {
        assertTrue(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_ARRAY_BUFFER))
        show(WEB_URL to page("<script>OspVerify.postMessage(new ArrayBuffer(8)); AndroidInterface.dispatch($SUCCESS)</script>"))
        assertTrue(doneLatch.await(15, TimeUnit.SECONDS))
        Thread.sleep(500)
        assertEquals(1, done.get())
    }

    @Test
    fun sameOriginFramesActAsThePage() {
        // The same origin may script the page, so its call through the page's own AndroidInterface is the page's.
        show(
            WEB_URL to page("""<iframe src="$SAME_ORIGIN_FRAME"></iframe>"""),
            SAME_ORIGIN_FRAME to page("<script>window.top.AndroidInterface.dispatch($SUCCESS)</script>"),
        )
        assertTrue(doneLatch.await(15, TimeUnit.SECONDS))
    }

    @Test
    fun thePageCantLeaveItsOrigin() {
        show(
            WEB_URL to page(
                """<script>
                window.onload = () => {
                  location.href = '$OTHER_ORIGIN_PAGE';
                  setTimeout(() => { document.title = 'stayed' }, 1500);
                };
                </script>""",
            ),
            OTHER_ORIGIN_PAGE to page("<script>document.title = 'left'</script>"),
        )
        awaitTitle("stayed")
        assertFalse(OTHER_ORIGIN_PAGE in requested)
        var url: String? = null
        instrumentation.runOnMainSync { url = page?.url }
        assertEquals(WEB_URL, url)
    }

    @Test
    fun aPostCantLeaveTheOriginEither() {
        // shouldOverrideUrlLoading isn't called for a POST; the client answers it with an empty refusal instead, so
        // no other origin's page ever runs, and reports a failed page.
        show(
            WEB_URL to page(
                """<form id="f" method="post" action="$OTHER_ORIGIN_PAGE"><input name="a" value="b"></form>
                <script>window.onload = () => document.getElementById('f').submit()</script>""",
            ),
            OTHER_ORIGIN_PAGE to page("<script>document.title = 'left'; AndroidInterface.dispatch($SUCCESS)</script>"),
        )
        assertTrue(loadFailed.await(15, TimeUnit.SECONDS))
        Thread.sleep(1500)
        var title: String? = null
        instrumentation.runOnMainSync { title = page?.title }
        assertFalse(title == "left")
        assertEquals(0, done.get())
    }

    @Test
    fun thePageMayMoveWithinItsOrigin() {
        show(
            WEB_URL to page("<script>window.onload = () => { location.href = '$SAME_ORIGIN_NEXT' }</script>"),
            SAME_ORIGIN_NEXT to page("<script>document.title = 'next'; AndroidInterface.dispatch($SUCCESS)</script>"),
        )
        awaitTitle("next")
        assertTrue(doneLatch.await(15, TimeUnit.SECONDS))
        assertEquals(1L, loadFailed.count)
    }

    @Test
    fun aPageThatFailsToLoadIsReported() {
        show(WEB_URL to page("<p>error</p>"), status = 500)
        assertTrue(loadFailed.await(15, TimeUnit.SECONDS))
        assertEquals(0, done.get())
    }

    @Test
    fun teardownLeavesNoCookies() {
        show(WEB_URL to page("<script>document.cookie = 'made-up=1; Secure'; document.title = 'set'</script>"))
        awaitTitle("set")
        instrumentation.runOnMainSync { CookieManager.getInstance().flush() }
        assertTrue(CookieManager.getInstance().hasCookies())
        instrumentation.runOnMainSync { page?.tearDown() }
        page = null
        assertFalse(CookieManager.getInstance().hasCookies())
    }

    @Test
    fun teardownLeavesNoDomStorage() {
        show(WEB_URL to page("<script>localStorage.setItem('made-up', '1'); document.title = 'stored'</script>"))
        awaitTitle("stored")
        instrumentation.runOnMainSync { page?.tearDown() }
        Thread.sleep(1000)
        show(WEB_URL to page("<script>document.title = 'found:' + localStorage.getItem('made-up')</script>"))
        awaitTitle("found:null")
    }

    private companion object {
        const val WEB_URL = "https://verify.proton.me/?methods=captcha&token=made-up"
        const val SAME_ORIGIN_FRAME = "https://verify.proton.me/frame"
        const val SAME_ORIGIN_NEXT = "https://verify.proton.me/next"
        const val OTHER_ORIGIN_FRAME = "https://other.example.test/frame"
        const val OTHER_ORIGIN_PAGE = "https://other.example.test/page"
        const val SUCCESS = "JSON.stringify({type: 'HUMAN_VERIFICATION_SUCCESS', payload: {token: 'page-token', type: 'captcha'}})"

        fun page(body: String) = "<!doctype html><html><head><title>start</title></head><body>$body</body></html>"
    }
}
