package com.apsconnect.autodial

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import android.webkit.CookieManager
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// 2026-08-05 (READ_CALL_LOG phase): tracks when a call started via this app ends, reads the
// real duration from CallLog, and reports it back to the CRM so staff/manager can see real
// talk time (crm/index.html's stats bubble + staff progress card were already wired to
// display this the moment call-event data starts arriving -- this is the missing producer).
//
// 2026-08-06 (v1.3): manager complained ZERO call-events ever reached the server -- silent
// exception-swallowing in postCallEvent() meant a permission gap / gateway hang / dead network
// during the earlier :5007 outage all failed the SAME invisible way. Fix: every failed post
// (or one that can't even be attempted -- no cookie yet, app not in foreground) is persisted
// to a local SharedPreferences queue instead of dropped, and re-flushed on the next call-end AND
// on every app launch. Logcat tag "CallLogReporter" also added for on-device debugging.
//
// 2026-11 (v1.9) CALL-REPORT CONTRACT -- reports ALL phone calls (not just answered/dialled
// ones) so managers get per-staff call reports (calls, missed, minutes, per number, lead vs
// non-lead).
//   POST https://agent.adiparasakthicharitabletrust.in/crm/api/leads/call-event
//   (same WebView-cookie auth, same local retry queue)
//   JSON: { phone, durationSec, answered, type, at, eventId }
//     type     = "outgoing" | "incoming" | "missed" | "rejected" | "other"
//     at       = ISO-8601 UTC of CallLog.Calls.DATE (real call time). The server rejects 'at'
//                older than ~3 days, so we never read further back than that.
//     eventId  = "${_ID}-${DATE}" of the call-log row -> retries / resyncs are idempotent.
//     answered = type is outgoing or incoming AND durationSec > 0
//   Nothing else is sent: no contact names, no contacts read.
// Sync = read every call-log row newer than a persisted cursor (last synced DATE), oldest
// first, max 200 per run; the cursor moves past a row only once it was posted or queued.
// Triggers: call ends (IDLE after RINGING/OFFHOOK, so missed calls are caught), app start/
// resume, and every 15 min while the app process is alive.
object CallLogReporter {

    private const val CALL_EVENT_URL = "https://agent.adiparasakthicharitabletrust.in/crm/api/leads/call-event"
    private const val PREFS = "call_event_queue"
    private const val QUEUE_KEY = "pending"
    private const val CURSOR_KEY = "sync_cursor_date"
    private const val TAG = "CallLogReporter"
    private const val MAX_AGE_MS = 3L * 24 * 60 * 60 * 1000   // server rejects older 'at'
    private const val MAX_ROWS_PER_RUN = 200
    private const val MAX_QUEUE = 500
    private const val PERIODIC_MS = 15L * 60 * 1000
    private const val ISO_FMT = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"

    private var legacyListener: PhoneStateListener? = null
    private var modernCallback: Any? = null   // TelephonyCallback (API 31+)
    @Volatile private var sawActivity = false // RINGING or OFFHOOK seen since last IDLE
    private val syncLock = Any()
    private val queueLock = Any()
    private val handler = Handler(Looper.getMainLooper())
    private var periodic: Runnable? = null

    private fun isListening() = legacyListener != null || modernCallback != null

    private fun onState(context: Context, state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING, TelephonyManager.CALL_STATE_OFFHOOK -> sawActivity = true
            TelephonyManager.CALL_STATE_IDLE -> if (sawActivity) {
                sawActivity = false
                // CallLog row can land a moment after IDLE (esp. missed calls); wait, then
                // sync. The periodic sync is the safety net if it lands even later.
                Thread {
                    try { Thread.sleep(1500) } catch (_: InterruptedException) {}
                    syncCallLog(context)
                }.start()
            }
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    private class ModernCallback(val onChange: (Int) -> Unit) :
        TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) = onChange(state)
    }

    fun startListening(context: Context) {
        val app = context.applicationContext
        if (isListening()) {   // already registered (onResume) -- just catch up
            syncCallLog(app)
            flushQueue(app)
            return
        }
        val tm = app.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val cb = ModernCallback { st -> onState(app, st) }
                tm.registerTelephonyCallback(ContextCompat.getMainExecutor(app), cb)
                modernCallback = cb
            } else {
                @Suppress("DEPRECATION")
                val l = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Android API, used only below API 31")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) = onState(app, state)
                }
                @Suppress("DEPRECATION")
                tm.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
                legacyListener = l
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "startListening: phone-state permission missing", e)
            return
        }
        Log.i(TAG, "startListening: call-state listener registered (api ${Build.VERSION.SDK_INT})")
        val r = object : Runnable {
            override fun run() {
                syncCallLog(app)
                handler.postDelayed(this, PERIODIC_MS)
            }
        }
        periodic = r
        handler.postDelayed(r, PERIODIC_MS)
        syncCallLog(app)   // app start -- catch up on calls made while we were not running
        flushQueue(app)    // app launch -- retry anything stuck from a previous session
    }

    fun stopListening(context: Context) {
        val tm = context.applicationContext.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (modernCallback as? TelephonyCallback)?.let { tm.unregisterTelephonyCallback(it) }
            } else {
                @Suppress("DEPRECATION")
                legacyListener?.let { tm.listen(it, PhoneStateListener.LISTEN_NONE) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "stopListening: ${e.message}")
        }
        modernCallback = null
        legacyListener = null
        periodic?.let { handler.removeCallbacks(it) }
        periodic = null
    }

    private fun typeName(t: Int) = when (t) {
        CallLog.Calls.OUTGOING_TYPE -> "outgoing"
        CallLog.Calls.INCOMING_TYPE -> "incoming"
        CallLog.Calls.MISSED_TYPE -> "missed"
        CallLog.Calls.REJECTED_TYPE -> "rejected"
        else -> "other"   // BLOCKED, VOICEMAIL, ANSWERED_EXTERNALLY, ...
    }

    private fun isoFormat() = SimpleDateFormat(ISO_FMT, Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    // Reads all call-log rows newer than the cursor (oldest first, <=200, <=3 days back),
    // posts each, and advances the cursor only after the row was sent or queued. Safe to call
    // from any thread at any time; logs and gives up (no crash) when permission is missing.
    fun syncCallLog(context: Context) {
        Thread {
            synchronized(syncLock) {
                try {
                    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    val floor = System.currentTimeMillis() - MAX_AGE_MS + 60_000   // margin vs server clock
                    val since = maxOf(prefs.getLong(CURSOR_KEY, 0L), floor)
                    val cursor = context.contentResolver.query(
                        CallLog.Calls.CONTENT_URI,
                        arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.DURATION,
                            CallLog.Calls.TYPE, CallLog.Calls.DATE),
                        "${CallLog.Calls.DATE} > ?", arrayOf(since.toString()),
                        "${CallLog.Calls.DATE} ASC LIMIT $MAX_ROWS_PER_RUN"
                    )
                    if (cursor != null) {
                        var processed = 0
                        cursor.use {
                            val iId = it.getColumnIndexOrThrow(CallLog.Calls._ID)
                            val iNum = it.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                            val iDur = it.getColumnIndexOrThrow(CallLog.Calls.DURATION)
                            val iType = it.getColumnIndexOrThrow(CallLog.Calls.TYPE)
                            val iDate = it.getColumnIndexOrThrow(CallLog.Calls.DATE)
                            while (it.moveToNext()) {
                                val date = it.getLong(iDate)
                                val number = it.getString(iNum)
                                if (!number.isNullOrBlank()) {   // private/unknown numbers can't be attributed
                                    val dur = it.getInt(iDur)
                                    val type = typeName(it.getInt(iType))
                                    val answered = (type == "outgoing" || type == "incoming") && dur > 0
                                    val ev = eventJson(number, dur, answered, type, date, "${it.getLong(iId)}-$date")
                                    if (!sendOne(ev)) enqueue(context, ev)
                                    processed++
                                }
                                prefs.edit().putLong(CURSOR_KEY, date).apply()
                            }
                        }
                        if (processed > 0) Log.i(TAG, "syncCallLog: processed $processed call(s)")
                    }
                } catch (e: Exception) {
                    // Permission revoked etc. -- never crash; the next trigger retries.
                    Log.e(TAG, "syncCallLog: CallLog read failed", e)
                }
            }
            flushQueue(context)
        }.start()
    }

    private fun eventJson(
        number: String, durationSec: Int, answered: Boolean,
        type: String, dateMs: Long, eventId: String
    ) = JSONObject().apply {
        put("phone", number)
        put("durationSec", durationSec)
        put("answered", answered)
        put("type", type)
        put("at", isoFormat().format(Date(dateMs)))
        put("eventId", eventId)
    }

    // true = server accepted (2xx). false = anything else (network/timeout/non-2xx) -- caller
    // queues it, never silently drops.
    private fun sendOne(event: JSONObject): Boolean {
        return try {
            val url = URL(CALL_EVENT_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            // WebView login session cookie reuse pannurom -- native HTTP call oda own
            // cookie jar illa, gateway (auth.requireAuth) idha kaanama reject pannidum.
            val cookie = CookieManager.getInstance().getCookie("https://agent.adiparasakthicharitabletrust.in")
            if (cookie != null) conn.setRequestProperty("Cookie", cookie)
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.outputStream.use { it.write(event.toString().toByteArray()) }
            val code = conn.responseCode
            val ok = code in 200..299
            if (ok) conn.inputStream.use { it.readBytes() } else conn.errorStream?.use { it.readBytes() }
            conn.disconnect()
            if (!ok) Log.w(TAG, "sendOne: server rejected, code=$code")
            ok
        } catch (e: Exception) {
            Log.e(TAG, "sendOne: network error", e)
            false
        }
    }

    private fun enqueue(context: Context, event: JSONObject) {
        synchronized(queueLock) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val arr = JSONArray(prefs.getString(QUEUE_KEY, "[]"))
            val out = JSONArray()
            val start = maxOf(0, arr.length() + 1 - MAX_QUEUE)   // cap: drop oldest
            for (i in start until arr.length()) out.put(arr.get(i))
            out.put(event)
            prefs.edit().putString(QUEUE_KEY, out.toString()).apply()
        }
    }

    // Drains the local retry queue. Stops at the first still-failing event (keeps order,
    // avoids hammering the server with the whole backlog when it's down). Events older than
    // 3 days are dropped first: the server would reject them forever and block the queue.
    fun flushQueue(context: Context) {
        Thread {
            synchronized(queueLock) {
                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val raw = JSONArray(prefs.getString(QUEUE_KEY, "[]"))
                if (raw.length() == 0) return@synchronized
                val cutoff = System.currentTimeMillis() - MAX_AGE_MS
                val fmt = isoFormat()
                val arr = JSONArray()
                for (k in 0 until raw.length()) {
                    val ev = raw.getJSONObject(k)
                    val at = ev.optString("at", "")
                    val ms = if (at.isEmpty()) Long.MAX_VALUE else try {
                        fmt.parse(at)?.time ?: Long.MAX_VALUE
                    } catch (_: Exception) { Long.MAX_VALUE }
                    if (ms >= cutoff) arr.put(ev)
                }
                Log.i(TAG, "flushQueue: ${arr.length()} pending event(s)")
                var i = 0
                while (i < arr.length()) {
                    if (!sendOne(arr.getJSONObject(i))) break
                    i++
                }
                val remaining = JSONArray()
                for (j in i until arr.length()) remaining.put(arr.get(j))
                prefs.edit().putString(QUEUE_KEY, remaining.toString()).apply()
                Log.i(TAG, "flushQueue: sent $i, ${remaining.length()} still pending")
            }
        }.start()
    }
}
