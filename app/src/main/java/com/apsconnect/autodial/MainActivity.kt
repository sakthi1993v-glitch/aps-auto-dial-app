package com.apsconnect.autodial

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
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

    private val crmBase = "https://agent.adiparasakthicharitabletrust.in"
    // 2026-09: launched via the "APS Student" home-screen icon (.StudentEntry alias in the
    // manifest, same MainActivity) vs the regular "APS Auto Dial" (staff) icon -- intent's
    // component name reflects WHICH launcher icon was tapped, even though both resolve to
    // this same Activity, so this is enough to route without a second Activity/APK.
    private val isStudentEntry: Boolean
        get() = intent?.component?.shortClassName == ".StudentEntry"
    private val crmUrl: String
        get() = if (isStudentEntry) "$crmBase/student/login" else crmBase
    private val callPermissionRequestCode = 100
    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var errorView: View
    private lateinit var permissionBanner: View
    private lateinit var permissionBannerText: TextView
    private lateinit var simBar: View
    private lateinit var simBarText: TextView
    private var simDialog: android.app.AlertDialog? = null
    private val loadingHandler = Handler(Looper.getMainLooper())
    private var mainFrameFailed = false
    private lateinit var errorText: TextView
    private val loadTimeout = Runnable {
        if (progressBar.visibility == View.VISIBLE) {
            // Mark failure before stopping; a late onPageFinished must not hide Retry.
            showLoadError("CRM load aaga neram aaguthu. Internet check pannitu Retry pannunga.")
            webView.stopLoading()
        }
    }

    private fun beginPageLoad() {
        mainFrameFailed = false
        webView.visibility = View.VISIBLE
        errorView.visibility = View.GONE
        progressBar.visibility = View.VISIBLE
        loadingHandler.removeCallbacks(loadTimeout)
        loadingHandler.postDelayed(loadTimeout, 30000)
    }

    private fun pageReady() {
        loadingHandler.removeCallbacks(loadTimeout)
        progressBar.visibility = View.GONE
    }

    private fun showLoadError(message: String) {
        mainFrameFailed = true
        pageReady()
        errorText.text = message
        webView.visibility = View.GONE
        errorView.visibility = View.VISIBLE
    }

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

        permissionBanner = buildPermissionBanner()
        permissionBanner.visibility = View.GONE

        root.addView(webView)
        root.addView(progressBar)
        root.addView(errorView)
        root.addView(permissionBanner)
        simBar = buildSimBar()
        simBar.visibility = View.GONE
        // WebView area takes all remaining height; the SIM strip sits BELOW it (no overlay).
        val outer = android.widget.LinearLayout(this)
        outer.orientation = android.widget.LinearLayout.VERTICAL
        root.layoutParams = android.widget.LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        )
        outer.addView(root)
        outer.addView(simBar)
        setContentView(outer)

        // Session cookie (staff/manager login) persist aaganum na cookie accept explicit-a
        // ON pannanum — illana login pannina odane veetiyum login screen-kku thirumba pogum.
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true   // CRM login state uses sessionStorage

        // Reuse HTTP cache according to server freshness headers and versioned asset URLs.
        // Preserve the existing one-time upgrade migration below.
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
                beginPageLoad()
            }

            override fun onPageCommitVisible(view: WebView, url: String?) {
                // A slow image/third-party subresource must not keep a usable page spinning.
                if (!mainFrameFailed) pageReady()
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (!mainFrameFailed) pageReady()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    showLoadError("CRM connect aagala. Internet check pannitu Retry pannunga.")
                }
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame && response.statusCode >= 400) {
                    showLoadError("CRM server response ${response.statusCode}. Konjam neram kazhithu Retry pannunga.")
                }
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                // Keep failed secondary assets from replacing an otherwise usable CRM page.
                if (error.url == view.url) {
                    showLoadError("Secure CRM connection verify aagala. Admin-kitta sollunga; appuram Retry pannunga.")
                }
            }
        }

        beginPageLoad()
        val restored = savedInstanceState?.getBundle("crmWebViewState")?.let { webView.restoreState(it) }
        if (restored == null) {
            webView.loadUrl(crmUrl)
        }

        // Auto-dial, call-log tracking and Bulk SMS are staff-only features -- a student has
        // no reason to be asked for CALL_PHONE/SMS/call-log permissions at all.
        if (!isStudentEntry) requestCallPermissionIfNeeded()
        UpdateChecker.checkForUpdate(this)
    }

    // 2026-09: on Android 13+, an app installed by sideload (APK download, not Play Store) has
    // SEND_SMS / READ_CALL_LOG / READ_PHONE_STATE marked "restricted" -- the in-app permission
    // dialog either never shows for them or tapping Allow silently does nothing, and the
    // Settings toggle stays greyed out until staff open App info -> (top-right) -> "Allow
    // restricted settings" first. There is no API to lift this from code (it's an Android anti-
    // malware guard), so requestCallPermissionIfNeeded() alone can leave call-log tracking and
    // Bulk SMS permanently silent with staff never knowing why. This banner is the fix: it
    // stays visible with exact steps whenever either permission group is still missing, and
    // onResume() re-checks so the banner disappears (and the worker starts) the moment staff
    // grant it via Settings and switch back -- no app restart needed.
    override fun onResume() {
        super.onResume()
        if (!isStudentEntry) {
            startBackgroundWorkers()
            updatePermissionBanner()
            updateSimBar()
        }
    }

    // v1.10 Office SIM: strip BELOW the WebView (never overlays the CRM page) showing which SIM is
    // counted + Change, or a visible warning when calls cannot be matched / are being held.
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildSimBar(): View {
        val bar = android.widget.LinearLayout(this)
        bar.orientation = android.widget.LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(Color.parseColor("#E7F1FF"))
        bar.setPadding(dp(12), dp(4), dp(12), dp(4))
        bar.layoutParams = android.widget.LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )

        simBarText = TextView(this)
        simBarText.textSize = 12f
        simBarText.layoutParams = android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

        val changeBtn = Button(this)
        changeBtn.text = "Change"
        changeBtn.setOnClickListener { showSimChooser() }

        bar.addView(simBarText)
        bar.addView(changeBtn)
        return bar
    }

    // The blue "Office SIM: ..." strip is only a confirmation: it hides itself 5 s after it appears (owner: it stayed at the
    // bottom after Save). Warnings (red) stay until the problem is fixed. It shows again for 5 s on every app open.
    private val simBarHideRunnable = Runnable { simBar.visibility = View.GONE }

    private fun showSimWarning(text: String, withChange: Boolean) {
        simBar.removeCallbacks(simBarHideRunnable)
        simBar.setBackgroundColor(Color.parseColor("#F8D7DA"))
        simBarText.setTextColor(Color.parseColor("#842029"))
        simBarText.text = text
        simBar.visibility = View.VISIBLE
        (simBar as ViewGroup).getChildAt(1).visibility = if (withChange) View.VISIBLE else View.GONE
    }

    private fun showSimInfo(text: String) {
        simBar.setBackgroundColor(Color.parseColor("#E7F1FF"))
        simBarText.setTextColor(Color.parseColor("#084298"))
        simBarText.text = text
        simBar.visibility = View.VISIBLE
        (simBar as ViewGroup).getChildAt(1).visibility = View.VISIBLE
        simBar.removeCallbacks(simBarHideRunnable)
        simBar.postDelayed(simBarHideRunnable, 5000)
    }

    private fun updateSimBar() {
        if (isStudentEntry) return
        val snap = OfficeSim.snapshot(this)
        val callPhoneGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED
        when (snap.status) {
            OfficeSim.Status.NO_PERMISSION -> showSimWarning(
                "Phone permission illa -- SIM check aagala, calls report aagathu" +
                    (if (callPhoneGranted) " (auto-dial default SIM-la pogum)" else ""), false)
            OfficeSim.Status.UNAVAILABLE ->
                if (snap.hold) showSimWarning("SIM list padikka mudiyala -- calls hold-la irukku, admin-kitta sollunga", false)
                else { simBar.removeCallbacks(simBarHideRunnable); simBar.visibility = View.GONE }
            OfficeSim.Status.NEED_CHOICE -> {
                showSimWarning((snap.note ?: "Office SIM select pannala") + " -- calls report aagathu", true)
                if (!OfficeSim.snoozed(this)) showSimChooser()
            }
            OfficeSim.Status.SINGLE, OfficeSim.Status.MULTI_READY -> {
                val sim = snap.chosen
                val label = if (sim != null) "Office SIM: ${sim.maskedLabel}" else "Office SIM"
                if (OfficeSim.unknownHold(this)) {
                    showSimWarning("Could not match SIM calls – tell admin. ($label)", true)
                } else {
                    showSimInfo(label + (snap.note?.let { n -> " -- $n" } ?: ""))
                }
            }
        }
    }

    private fun showSimChooser() {
        if (isFinishing || isDestroyed || simDialog?.isShowing == true) return
        val sims = OfficeSim.listActive(this)
        if (sims.isEmpty()) {
            Toast.makeText(this, "SIM details padikka mudiyala (Phone permission check pannunga)", Toast.LENGTH_LONG).show()
            return
        }
        val current = OfficeSim.snapshot(this).chosen
        var picked = sims.indexOfFirst { it.subId == current?.subId }
        lateinit var dlg: android.app.AlertDialog
        dlg = android.app.AlertDialog.Builder(this)
            .setTitle("Office SIM-a select pannunga")
            .setSingleChoiceItems(sims.map { it.maskedLabel }.toTypedArray(), picked) { _, which ->
                picked = which
                dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.isEnabled = true
            }
            .setPositiveButton("Save") { _, _ ->
                if (picked >= 0) {
                    OfficeSim.choose(this, sims[picked])
                    CallLogReporter.syncCallLog(this)   // upload the office-SIM calls held back until now
                }
                updateSimBar()
            }
            .setNegativeButton("Later") { _, _ ->
                OfficeSim.snooze(this)   // do not re-open on every resume for 6 hours
            }
            .create()
        simDialog = dlg
        dlg.show()
        dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.isEnabled = picked >= 0
    }

    private fun buildPermissionBanner(): View {
        val bar = android.widget.LinearLayout(this)
        bar.orientation = android.widget.LinearLayout.VERTICAL
        bar.setBackgroundColor(Color.parseColor("#FFF3CD"))
        bar.setPadding(24, 20, 24, 20)
        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.gravity = Gravity.TOP
        bar.layoutParams = params

        permissionBannerText = TextView(this)
        permissionBannerText.setTextColor(Color.parseColor("#664D03"))
        permissionBannerText.textSize = 13f

        val fixBtn = Button(this)
        fixBtn.text = "Settings-ku po, fix pannunga"
        fixBtn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            })
        }

        bar.addView(permissionBannerText)
        bar.addView(fixBtn)
        return bar
    }

    // Call-log tracking (READ_PHONE_STATE + READ_CALL_LOG) and Bulk SMS (SEND_SMS) each have
    // their own worker, so the banner names exactly which one is broken -- staff shouldn't be
    // told to "fix permissions" for a feature that's already working.
    private fun updatePermissionBanner() {
        val granted = { p: String -> ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED }
        val callLogMissing = !granted(Manifest.permission.READ_PHONE_STATE) || !granted(Manifest.permission.READ_CALL_LOG)
        val smsMissing = !granted(Manifest.permission.SEND_SMS)

        if (!callLogMissing && !smsMissing) {
            permissionBanner.visibility = View.GONE
            return
        }

        val broken = mutableListOf<String>()
        if (callLogMissing) broken.add("Call duration tracking")
        if (smsMissing) broken.add("Bulk SMS")

        permissionBannerText.text =
            "${broken.joinToString(" & ")} work aagala -- permission illa.\n" +
            "Settings > Apps > APS Connect > ⋮ (top-right) > \"Allow restricted settings\" " +
            "adhukku appuram Permissions-la Call logs / Phone / SMS-a Allow pannunga."
        permissionBanner.visibility = View.VISIBLE
    }

    // Honour HTTP freshness on normal launches. Keep the existing one-time version-upgrade
    // migration, so a stale asset is discarded once rather than on every app opening.
    private fun wipeStaleState() {
        webView.settings.cacheMode = WebSettings.LOAD_DEFAULT

        val prefs = getSharedPreferences("app_state", MODE_PRIVATE)
        val lastVersion = prefs.getInt("lastVersionCode", -1)
        if (lastVersion != BuildConfig.VERSION_CODE) {
            webView.clearCache(true)
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
        errorText = text
        text.text = "CRM connect aagala. Internet check pannitu Retry pannunga."
        text.setPadding(40, 0, 40, 40)
        text.gravity = Gravity.CENTER
        text.textSize = 16f

        val retryBtn = Button(this)
        retryBtn.text = "Retry"
        retryBtn.setOnClickListener {
            beginPageLoad()
            webView.loadUrl(crmUrl)
        }

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
        if (requestCode == callPermissionRequestCode) {
            startBackgroundWorkers()
            updatePermissionBanner()
            updateSimBar()
        }
    }

    private fun dial(phoneNumber: String) {
        // Students never requested CALL_PHONE (0-tap auto-dial is a staff-only feature) --
        // straight to the normal dialer, no "permission illa" toast that means nothing to them.
        if (isStudentEntry) {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phoneNumber")))
            return
        }
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
        val callIntent = Intent(action, Uri.parse("tel:$phoneNumber"))
        // v1.10: place the call from the chosen office SIM only when its handle POSITIVELY maps to
        // that SIM (2+ SIMs); otherwise the phone's default behaviour. The sync later learns the
        // call-log PHONE_ACCOUNT_ID of this call (recordDial) as an extra id for the chosen SIM.
        if (granted) {
            try {
                val handle = OfficeSim.chosenHandle(this, OfficeSim.snapshot(this))
                if (handle != null) {
                    callIntent.putExtra(android.telecom.TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
                    OfficeSim.recordDial(this, phoneNumber)
                }
            } catch (e: Exception) {
                android.util.Log.w("OfficeSim", "dial: SIM handle skipped: ${e.message}")
            }
        }
        startActivity(callIntent)
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

    override fun onSaveInstanceState(outState: Bundle) {
        val webState = Bundle()
        webView.saveState(webState)
        outState.putBundle("crmWebViewState", webState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        loadingHandler.removeCallbacks(loadTimeout)
        simBar.removeCallbacks(simBarHideRunnable)
        simDialog?.dismiss()
        simDialog = null
        CallLogReporter.stopListening(this)
        BulkSmsSender.stop()
        super.onDestroy()
    }
}
