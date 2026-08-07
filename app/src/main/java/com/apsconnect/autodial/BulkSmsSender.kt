package com.apsconnect.autodial

import android.content.Context
import android.provider.Settings
import android.telephony.SmsManager
import android.util.Log
import android.webkit.CookieManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 2026-08-06 (v1.6): Bulk SMS worker.
 *
 * The CRM holds the queue; this only asks for ONE number at a time, sends it through the
 * phone's own SIM, and reports the outcome back. Everything that decides policy -- the daily
 * cap, the gap between messages, whether sending is even switched on -- lives on the server,
 * so a reinstall or a cleared app cache cannot be used to exceed a limit, and the manager can
 * stop a run from the dashboard without touching the phone.
 *
 * Pacing: the server hands back `delaySec` with every reply and this waits exactly that long.
 * The default (70s) is set so we stay under Android's own throttle -- roughly 30 messages per
 * 30 minutes -- above which the OS starts showing a confirmation dialog for EVERY message,
 * which would strand an unattended run. Sending faster does not deliver faster; it just makes
 * the phone start asking.
 *
 * Reuses CallLogReporter's proven conventions: WebView session cookie for auth, a single
 * background thread, and a Logcat tag so a failure on a staff phone can actually be diagnosed.
 */
object BulkSmsSender {

    private const val BASE = "https://agent.adiparasakthicharitabletrust.in/crm/api/bulksms"
    private const val TAG = "BulkSmsSender"

    // Fallback pause when the server gives us nothing to do (stopped / no pending / cap hit).
    // Long on purpose: an idle phone should not sit there hammering the gateway.
    private const val IDLE_WAIT_MS = 60_000L

    @Volatile private var running = false
    private var worker: Thread? = null

    /** Stable per-install id. Survives app updates; changes on reinstall (server re-registers). */
    fun deviceId(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown-device"

    fun start(context: Context, label: String?) {
        if (running) return
        running = true
        val appCtx = context.applicationContext
        val id = deviceId(appCtx)
        worker = Thread {
            Log.i(TAG, "worker started, deviceId=$id")
            register(id, label)
            while (running) {
                val waitMs = try {
                    pollAndSend(appCtx, id)
                } catch (e: Exception) {
                    Log.e(TAG, "poll loop error", e)
                    IDLE_WAIT_MS
                }
                var slept = 0L
                while (running && slept < waitMs) {   // wake promptly on stop()
                    Thread.sleep(minOf(1000L, waitMs - slept))
                    slept += 1000L
                }
            }
            Log.i(TAG, "worker stopped")
        }
        worker?.start()
    }

    fun stop() {
        running = false
        worker = null
    }

    private fun register(deviceId: String, label: String?) {
        val body = JSONObject().apply {
            put("deviceId", deviceId)
            if (!label.isNullOrBlank()) put("label", label)
        }
        val res = postJson("$BASE/device/register", body)
        Log.i(TAG, "register -> ${if (res != null) "ok" else "FAILED"}")
    }

    /** @return how long to wait before the next poll, in ms. */
    private fun pollAndSend(context: Context, deviceId: String): Long {
        val res = getJson("$BASE/device/next?deviceId=$deviceId") ?: run {
            Log.w(TAG, "poll failed (offline / not logged in) -- backing off")
            return IDLE_WAIT_MS
        }

        // Server-supplied pacing wins over anything hardcoded here.
        val delayMs = res.optInt("delaySec", 70).coerceAtLeast(30) * 1000L

        if (res.isNull("message")) {
            val reason = res.optString("reason", "idle")
            Log.i(TAG, "nothing to send (reason=$reason)")
            // "stopped" / "limit_full" mean don't come back soon; a race is momentary.
            return if (reason == "race") delayMs else IDLE_WAIT_MS
        }

        val msg = res.getJSONObject("message")
        val id = msg.optInt("id")
        val phone = msg.optString("phone")
        val text = msg.optString("text")
        if (id == 0 || phone.isBlank() || text.isBlank()) {
            Log.w(TAG, "malformed message from server, skipping")
            return delayMs
        }

        var ok = false
        var error = ""
        try {
            val sms = smsManager(context)
            // Long text must be split or it silently truncates at ~160 chars.
            val parts = sms.divideMessage(text)
            if (parts.size > 1) sms.sendMultipartTextMessage(phone, null, parts, null, null)
            else sms.sendTextMessage(phone, null, text, null, null)
            ok = true
            Log.i(TAG, "sent to ***${phone.takeLast(4)} (${parts.size} part(s))")
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
            Log.e(TAG, "send FAILED to ***${phone.takeLast(4)}", e)
        }

        // Always report -- an unreported send would be re-handed after the claim goes stale
        // and the number would get the message twice.
        val body = JSONObject().apply {
            put("deviceId", deviceId)
            put("id", id)
            put("ok", ok)
            if (!ok) put("error", error)
        }
        val ack = postJson("$BASE/device/result", body)
        if (ack == null) Log.w(TAG, "result POST failed for id=$id -- server will reclaim it")

        return delayMs
    }

    @Suppress("DEPRECATION")
    private fun smsManager(context: Context): SmsManager =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
            context.getSystemService(SmsManager::class.java)
        else SmsManager.getDefault()

    // ── HTTP (WebView session cookie = auth, same as CallLogReporter) ────────
    private fun cookie(): String? =
        CookieManager.getInstance().getCookie("https://agent.adiparasakthicharitabletrust.in")

    private fun getJson(url: String): JSONObject? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            cookie()?.let { setRequestProperty("Cookie", it) }
            connectTimeout = 10000
            readTimeout = 10000
        }
        val code = conn.responseCode
        val text = if (code in 200..299) conn.inputStream.bufferedReader().readText()
                   else { conn.errorStream?.bufferedReader()?.readText(); null }
        conn.disconnect()
        if (text == null) { Log.w(TAG, "GET $url -> HTTP $code"); null } else JSONObject(text)
    } catch (e: Exception) {
        Log.e(TAG, "GET failed: $url", e); null
    }

    private fun postJson(url: String, body: JSONObject): JSONObject? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            cookie()?.let { setRequestProperty("Cookie", it) }
            connectTimeout = 10000
            readTimeout = 10000
        }
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = conn.responseCode
        val text = if (code in 200..299) conn.inputStream.bufferedReader().readText()
                   else { conn.errorStream?.bufferedReader()?.readText(); null }
        conn.disconnect()
        if (text == null) { Log.w(TAG, "POST $url -> HTTP $code"); null } else JSONObject(text)
    } catch (e: Exception) {
        Log.e(TAG, "POST failed: $url", e); null
    }
}
