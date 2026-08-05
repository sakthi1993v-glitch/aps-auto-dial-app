package com.apsconnect.autodial

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        webView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        setContentView(webView)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true   // CRM login state uses sessionStorage

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (url.scheme == "tel") {
                    dial(url.schemeSpecificPart)
                    return true
                }
                return false
            }
        }

        if (savedInstanceState == null) {
            webView.loadUrl(crmUrl)
        }

        requestCallPermissionIfNeeded()
    }

    private fun requestCallPermissionIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CALL_PHONE), callPermissionRequestCode
            )
        }
    }

    private fun dial(phoneNumber: String) {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Toast.makeText(
                this,
                "Call permission illa — Settings-la app permission allow pannunga, auto-dial work aagum",
                Toast.LENGTH_LONG
            ).show()
        }
        // Permission irundha ACTION_CALL -- neraga dial aagum (0-tap). Illana ACTION_DIAL --
        // dialer open aagum, staff 1-tap pannanum (permission kudukkura varaikkum fallback).
        val action = if (granted) Intent.ACTION_CALL else Intent.ACTION_DIAL
        startActivity(Intent(action, Uri.parse("tel:$phoneNumber")))
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}
