package com.apsconnect.autodial

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

// APS Connect Auto Dial — WebView shell around the existing CRM website. Login, My Queue,
// every other CRM feature stays exactly as the web CRM already is (zero rebuild). The ONLY
// thing this native shell adds: every tel: link (Call now / Call / phone2) is intercepted
// and dialed directly via ACTION_CALL (0-tap, no dialer screen) instead of the browser's
// normal tel: behaviour (which needs one tap on the dialer's own call button).
class MainActivity : ComponentActivity() {

    private val crmUrl = "https://agent.adiparasakthicharitabletrust.in"
    private val callPermissionRequestCode = 100
    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var errorView: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)

        webView = WebView(this)
        webView.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )

        // 2026-08-05: white blank screen complaint — munnadi WebView load aagardhukku edhuvum
        // kaatavillaya, page load fail aana user-kku edhuvum theriyathu (silent blank). Ippo
        // loading spinner + retry-able error screen add pannirukku, blank-a irundhu confuse
        // aagakoodathu.
        progressBar = ProgressBar(this)
        val progressParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        progressParams.gravity = Gravity.CENTER
        progressBar.layoutParams = progressParams

        errorView = buildErrorView()
        errorView.visibility = View.GONE

        root.addView(webView)
        root.addView(progressBar)
        root.addView(errorView)
        setContentView(root)

        // Session cookie (staff/manager login) persist aaganum na cookie accept explicit-a
        // ON pannanum — illana login pannina odane veetiyum login screen-kku thirumba pogum.
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true   // CRM login state uses sessionStorage

        // 2026-08-06 (v1.3): stale-cache complaints — server hang aana nerathu WebView pazhaya
        // failed/blank page-a cache pannitu, server sari aana appuram-um adha kaatitu irundhadhu.
        // Fix rendu layer:
        //  1. Ovvoru launch-ilum HTTP cache muzhusa clear + cacheMode=LOAD_NO_CACHE — page eppovum
        //     server-la irundhu fresh-a varum, pazhaya cached copy ODAVE run aagadhu.
        //  2. App version maarina (update install aana first launch) FULL wipe — WebStorage
        //     (localStorage/indexedDB) + cookies + history ellame delete, fresh-install madhiri.
        //     Cookies pona staff oru thadava re-login pannuvanga — adhu expected, one time.
        wipeStaleState()

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (url.scheme == "tel") {
                    dial(url.schemeSpecificPart)
                    return true
                }
                return false
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                progressBar.visibility = View.VISIBLE
                errorView.visibility = View.GONE
            }

            override fun onPageFinished(view: WebView, url: String?) {
                progressBar.visibility = View.GONE
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    progressBar.visibility = View.GONE
                    webView.visibility = View.GONE
                    errorView.visibility = View.VISIBLE
                }
            }
        }

        if (savedInstanceState == null) {
            webView.loadUrl(crmUrl)
        }

        requestCallPermissionIfNeeded()
        UpdateChecker.checkForUpdate(this)
    }

    // v1.3: pazhaya cache/state odave run aagakoodadhu.
    // Ovvoru launch: HTTP cache clear + LOAD_NO_CACHE (page eppovum server fresh).
    // Version upgrade first launch: WebStorage + cookies + history FULL wipe (fresh install
    // madhiri) — appuram indha versionCode-a kurichi vachukkum, adutha launches normal.
    private fun wipeStaleState() {
        webView.clearCache(true)
        webView.settings.cacheMode = WebSettings.LOAD_NO_CACHE

        val prefs = getSharedPreferences("app_state", MODE_PRIVATE)
        val lastVersion = prefs.getInt("lastVersionCode", -1)
        if (lastVersion != BuildConfig.VERSION_CODE) {
            webView.clearHistory()
            webView.clearFormData()
            WebStorage.getInstance().deleteAllData()
            val cm = CookieManager.getInstance()
            cm.removeAllCookies(null)
            cm.flush()
            prefs.edit().putInt("lastVersionCode", BuildConfig.VERSION_CODE).apply()
        }
    }

    private fun buildErrorView(): View {
        val container = FrameLayout(this)
        container.setBackgroundColor(Color.WHITE)
        container.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )

        val inner = android.widget.LinearLayout(this)
        inner.orientation = android.widget.LinearLayout.VERTICAL
        inner.gravity = Gravity.CENTER
        val innerParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        innerParams.gravity = Gravity.CENTER
        inner.layoutParams = innerParams

        val text = TextView(this)
        text.text = "CRM connect aagala. Internet check pannitu Retry pannunga."
        text.setPadding(40, 0, 40, 40)
        text.gravity = Gravity.CENTER
        text.textSize = 16f

        val retryBtn = Button(this)
        retryBtn.text = "Retry"
        retryBtn.setOnClickListener { webView.loadUrl(crmUrl) }

        inner.addView(text)
        inner.addView(retryBtn)
        container.addView(inner)
        return container
    }

    private fun requestCallPermissionIfNeeded() {
        // 2026-08-05: CALL_PHONE (auto-dial) + READ_PHONE_STATE + READ_CALL_LOG (call-duration
        // tracking) requested together, one dialog -- staff taps Allow once, not three times.
        // 2026-08-06 (v1.6): SEND_SMS joins the same batch, for Bulk SMS.
        val needed = arrayOf(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.SEND_SMS
        ).filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), callPermissionRequestCode)
        } else {
            startBackgroundWorkers()
        }
    }

    // Each worker is gated on ITS OWN permission -- a staff member who allows calls but
    // denies SMS (or the reverse) must still get the half that works, not neither.
    private fun startBackgroundWorkers() {
        val granted = { p: String -> ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED }

        if (granted(Manifest.permission.READ_PHONE_STATE) && granted(Manifest.permission.READ_CALL_LOG)) {
            CallLogReporter.startListening(this)
        }
        if (granted(Manifest.permission.SEND_SMS)) {
            // Label = what the manager sees in the dashboard's phone list.
            BulkSmsSender.start(this, android.os.Build.MODEL)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == callPermissionRequestCode) startBackgroundWorkers()
    }

    private fun dial(phoneNumber: String) {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Toast.makeText(
                this,
                "Call permission illa. Settings-la app permission allow pannunga, auto-dial work aagum",
                Toast.LENGTH_LONG
            ).show()
        }
        // Permission irundha ACTION_CALL -- neraga dial aagum (0-tap). Illana ACTION_DIAL --
        // dialer open aagum, staff 1-tap pannanum (permission kudukkura varaikkum fallback).
        val action = if (granted) Intent.ACTION_CALL else Intent.ACTION_DIAL
        startActivity(Intent(action, Uri.parse("tel:$phoneNumber")))
    }

    // v1.5 (2026-08-06): back button azhuthina UDANE app veliya pōyiduchu -- staff oru lead
    // update panra naduvula thappa back thottaa velai pōyidum (user complaint). Ippo WebView
    // history irundhaa pazhaya maadhiri back pōgum; app-a MUDIKKURA back-ku mattum
    // "veliya pōganumaa?" nu kēkkum. Confirm dialog = NATIVE AlertDialog, ENDHA reason-kum
    // JS confirm() illa -- indha app-la WebChromeClient set pannala, adhanaala
    // window.confirm() eppovum silent-a false thirumbum (CRM-la adhukku thani fix pōttaachu).
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
            return
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Veliya pōganumaa?")
            .setMessage("APS Connect app-a close pannattumaa?")
            .setPositiveButton("Aamaam, veliya pō") { _, _ -> finish() }
            .setNegativeButton("Illa, irukkatum", null)
            .show()
    }

    override fun onDestroy() {
        CallLogReporter.stopListening(this)
        BulkSmsSender.stop()
        super.onDestroy()
    }
}
