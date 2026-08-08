package com.apsconnect.autodial

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import android.telephony.SmsManager
import android.util.Log
import android.webkit.CookieManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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

    // v1.8: framework-oda nijamaana send result vara vazhi (kizhe sendSms() paarunga).
    private const val SENT_ACTION = "com.apsconnect.autodial.SMS_SENT"
    private const val EXTRA_MSG_ID = "msgId"
    // Confirmation-kku evlo nēram kaathirukkalaam. Poll pacing (default 70s)-kku keezha
    // vachurukkanum, illana ovvoru message-um pacing-a thalli kondē pōgum.
    private const val SENT_TIMEOUT_MS = 45_000L

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

        val (ok, error, confirmed) = sendSms(context, id, phone, text)

        // Always report -- an unreported send would be re-handed after the claim goes stale
        // and the number would get the message twice.
        val body = JSONObject().apply {
            put("deviceId", deviceId)
            put("id", id)
            put("ok", ok)
            // v1.8: confirmed=false -> framework SENT_TIMEOUT_MS-kkulla badhil sollala.
            // Appo ok=true nu sollurom (duplicate SMS thavirkka -- sendSms() doc paarunga),
            // aana server indha flag-a vachu "unconfirmed" nu thani-ya kaattikkalaam.
            put("confirmed", confirmed)
            if (!ok) put("error", error)
        }
        val ack = postJson("$BASE/device/result", body)
        if (ack == null) Log.w(TAG, "result POST failed for id=$id -- server will reclaim it")

        return delayMs
    }

    /**
     * Oru SMS anuppi, framework-oda NIJAMAANA send result-kku kaathirukkum.
     *
     * 2026-08-08 (v1.8): munnadi `sendTextMessage(phone, null, text, null, null)` nu sentIntent
     * illaama anuppi, exception varalana `ok=true` nu server-ku sollitu irundhom. Aana andha
     * call return aanadhukku "framework-kku kudutthaachu" nu mattum dhaan artham -- signal
     * illa / radio off / null PDU / SIM prachanai edhukkum exception varaadhu. Adhanaala CRM-la
     * "sent" nu kaattum, manager count-um ērum, aana message pōgave pōgaadhu. Ippo ovvoru
     * part-kkum oru PendingIntent kudukkurom, framework thirumba anuppura result code-a
     * paathu dhaan ok/fail mudivu pannurom.
     *
     * @return Triple(ok, error, confirmed).
     *   confirmed=false -> timeout-kkulla framework badhil sollala. Andha nēram `ok=true` nu
     *   sollurom -- oru message pōgaama pōradha vida, lead-ku RENDU thadava message pōradhu
     *   mosam (staff-oda daily allowance-um veenaagum, lead-um erichal aaguvaanga), adhanaala
     *   server-a marupadi anuppa vidamaatom.
     */
    private fun sendSms(context: Context, msgId: Int, phone: String, text: String): Triple<Boolean, String, Boolean> {
        val sms = smsManager(context)
        // Long text must be split or it silently truncates at ~160 chars.
        val parts = sms.divideMessage(text)
        val latch = CountDownLatch(parts.size)
        val failures = Collections.synchronizedList(mutableListOf<String>())

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.getIntExtra(EXTRA_MSG_ID, -1) != msgId) return
                if (resultCode != android.app.Activity.RESULT_OK) failures.add(resultName(resultCode))
                latch.countDown()
            }
        }
        val filter = IntentFilter(SENT_ACTION)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU)
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else
            context.registerReceiver(receiver, filter)

        try {
            // FLAG_IMMUTABLE: namma vaikkura EXTRA_MSG_ID mattum thaan pōgum, framework extra
            // sēkka mudiyaadhu. Result CODE extra illa (broadcast result mechanism), adhanaala
            // idhu immutable-a irundhaalum sari-yaave varum.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val intents = ArrayList<PendingIntent>(parts.size)
            for (i in parts.indices) {
                val intent = Intent(SENT_ACTION)
                    .setPackage(context.packageName)
                    .putExtra(EXTRA_MSG_ID, msgId)
                intents.add(PendingIntent.getBroadcast(context, i, intent, flags))
            }
            if (parts.size > 1) sms.sendMultipartTextMessage(phone, null, parts, intents, null)
            else sms.sendTextMessage(phone, null, text, intents[0], null)
        } catch (e: Exception) {
            unregister(context, receiver)
            Log.e(TAG, "send FAILED to ***${phone.takeLast(4)}", e)
            return Triple(false, e.message ?: e.javaClass.simpleName, true)
        }

        val confirmed = try {
            latch.await(SENT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            false
        }
        unregister(context, receiver)

        if (!confirmed) {
            Log.w(
                TAG,
                "no confirmation in ${SENT_TIMEOUT_MS / 1000}s for ***${phone.takeLast(4)} " +
                    "-- reporting sent-but-unconfirmed (duplicate anuppa vidalai)"
            )
            return Triple(true, "", false)
        }

        val errs = failures.toList()
        return if (errs.isEmpty()) {
            Log.i(TAG, "sent + confirmed to ***${phone.takeLast(4)} (${parts.size} part(s))")
            Triple(true, "", true)
        } else {
            Log.e(TAG, "send FAILED to ***${phone.takeLast(4)}: ${errs.joinToString(",")}")
            Triple(false, errs.joinToString(","), true)
        }
    }

    private fun unregister(context: Context, receiver: BroadcastReceiver) {
        try {
            context.unregisterReceiver(receiver)
        } catch (e: IllegalArgumentException) {
            // Already unregistered -- ignore.
        }
    }

    private fun resultName(code: Int): String = when (code) {
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "generic_failure"
        SmsManager.RESULT_ERROR_NO_SERVICE -> "no_service"
        SmsManager.RESULT_ERROR_NULL_PDU -> "null_pdu"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "radio_off"
        else -> "error_$code"
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
