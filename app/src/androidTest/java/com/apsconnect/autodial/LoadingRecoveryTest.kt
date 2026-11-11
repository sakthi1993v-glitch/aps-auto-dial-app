package com.apsconnect.autodial

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ProgressBar
import java.io.ByteArrayInputStream

/**
 * Exercises the Activity's real loading callbacks and recovery controls without a backend,
 * SIM, call, SMS or permission grant. The student launcher uses the same WebView shell but
 * deliberately does not start staff phone workers or request phone permissions.
 */
@Suppress("DEPRECATION")
class LoadingRecoveryTest : InstrumentationTestCase() {
    private lateinit var activity: MainActivity
    private lateinit var webView: WebView
    private lateinit var callbacks: WebViewClient
    private lateinit var spinner: ProgressBar
    private lateinit var errorView: View
    private val firstUrl = "https://agent.adiparasakthicharitabletrust.in/student/login"
    private val nextUrl = "https://agent.adiparasakthicharitabletrust.in/student/"

    override fun setUp() {
        super.setUp()
        val intent = Intent(Intent.ACTION_MAIN).apply {
            component = ComponentName(instrumentation.targetContext.packageName,
                "com.apsconnect.autodial.StudentEntry")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity = instrumentation.startActivitySync(intent) as MainActivity
        onUi {
            webView = field("webView")
            spinner = field("progressBar")
            errorView = field("errorView")
            callbacks = webView.webViewClient
            // Keep the production callback instance, but isolate the tests from network
            // callbacks caused by launch/Retry. No page script or fake UI replaces it.
            webView.webViewClient = WebViewClient()
            webView.stopLoading()
        }
    }

    override fun tearDown() {
        try {
            if (::activity.isInitialized) onUi { activity.finish() }
            instrumentation.waitForIdleSync()
        } finally {
            super.tearDown()
        }
    }

    fun testHttpFailureRetryRestoresVisibleWebView() {
        onUi {
            callbacks.onPageStarted(webView, firstUrl, null)
            callbacks.onReceivedHttpError(webView, Request(firstUrl), unavailable())
            assertEquals(View.VISIBLE, errorView.visibility)
            assertEquals(View.GONE, webView.visibility)
            assertEquals(View.GONE, spinner.visibility)

            // A late finish following the failed request must not dismiss the error.
            callbacks.onPageFinished(webView, firstUrl)
            assertEquals(View.VISIBLE, errorView.visibility)

            val retry = findRetry(errorView)
            assertNotNull("Failure presents a real Retry control", retry)
            assertTrue(retry!!.performClick())
            webView.stopLoading()
            assertEquals("Retry restores the previously hidden content view", View.VISIBLE, webView.visibility)
            assertEquals(View.GONE, errorView.visibility)
            assertEquals(View.VISIBLE, spinner.visibility)
        }
    }

    fun testLoadDeadlineEndsSpinnerAndOffersRetry() {
        onUi {
            callbacks.onPageStarted(webView, firstUrl, null)
            // Fire the actual deadline action rather than waiting 30 seconds per test.
            field<Runnable>("loadTimeout").run()
            assertEquals(View.GONE, spinner.visibility)
            assertEquals(View.VISIBLE, errorView.visibility)
            assertNotNull("Timed-out loading is recoverable", findRetry(errorView))
            callbacks.onPageFinished(webView, firstUrl)
            assertEquals("Late completion cannot hide the timeout error", View.VISIBLE, errorView.visibility)
        }
    }

    fun testStaleFinishAndCommitDoNotCompleteNewNavigation() {
        onUi {
            callbacks.onPageStarted(webView, firstUrl, null)
            callbacks.onPageStarted(webView, nextUrl, null)
            callbacks.onPageFinished(webView, firstUrl)
            callbacks.onPageCommitVisible(webView, firstUrl)
            assertEquals("Previous navigation cannot end current loading", View.VISIBLE, spinner.visibility)
            callbacks.onPageFinished(webView, nextUrl)
            assertEquals("Current completion ends loading", View.GONE, spinner.visibility)
            assertEquals(View.VISIBLE, webView.visibility)
            assertEquals(View.GONE, errorView.visibility)
        }
    }

    fun testSecondaryResourceFailureDoesNotHideUsablePage() {
        onUi {
            callbacks.onPageStarted(webView, firstUrl, null)
            callbacks.onReceivedHttpError(webView, Request("https://assets.invalid/image.png", false), unavailable())
            callbacks.onPageCommitVisible(webView, firstUrl)
            assertEquals(View.VISIBLE, webView.visibility)
            assertEquals(View.GONE, errorView.visibility)
            assertEquals(View.GONE, spinner.visibility)
        }
    }

    fun testBlueOfficeSimConfirmationHidesWithoutChangingPermissions() {
        onUi {
            MainActivity::class.java.getDeclaredMethod("showSimInfo", String::class.java).apply {
                isAccessible = true
            }.invoke(activity, "Office SIM: Fixture Airtel")
            assertEquals(View.VISIBLE, field<View>("simBar").visibility)
        }
        val deadline = SystemClock.uptimeMillis() + 7500
        var hidden = false
        while (!hidden && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(100)
            onUi { hidden = field<View>("simBar").visibility == View.GONE }
        }
        assertTrue("Blue confirmation auto-hides after its five-second display", hidden)
    }

    private fun onUi(action: () -> Unit) = instrumentation.runOnMainSync(action)

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(name: String): T = MainActivity::class.java.getDeclaredField(name).run {
        isAccessible = true
        get(activity) as T
    }

    private fun findRetry(view: View): Button? {
        if (view is Button && view.text.toString() == "Retry") return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) findRetry(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun unavailable() = WebResourceResponse(
        "text/html", "UTF-8", 503, "Service Unavailable", emptyMap(),
        ByteArrayInputStream("Fixture service unavailable".toByteArray(Charsets.UTF_8))
    )

    private class Request(url: String, private val mainFrame: Boolean = true) : WebResourceRequest {
        private val uri = Uri.parse(url)
        override fun getUrl(): Uri = uri
        override fun isForMainFrame() = mainFrame
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = "GET"
        override fun getRequestHeaders(): MutableMap<String, String> = mutableMapOf()
    }
}
