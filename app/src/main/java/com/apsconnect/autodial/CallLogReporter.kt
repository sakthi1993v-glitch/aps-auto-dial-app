package com.apsconnect.autodial

import android.content.Context
import android.provider.CallLog
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import android.webkit.CookieManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

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
object CallLogReporter {

    private const val CALL_EVENT_URL = "https://agent.adiparasakthicharitabletrust.in/crm/api/leads/call-event"
    private const val PREFS = "call_event_queue"
    private const val QUEUE_KEY = "pending"
    private const val TAG = "CallLogReporter"

    // Oru dial pannina number evlo nēram "indha app dial pannadhu" nu nyaabagam vaikkanum.
    // Number match dhaan mukkiyamaana guard -- idhu thevai-illaadha pazhaya expectation-a
    // (dial pannitu call nadakkaama pōna case) thooki podharukku mattum. Neenda call-um
    // (call mudinja pinnadi dhaan report aagum) idhukkulla adangum.
    private const val EXPECT_TTL_MS = 4 * 60 * 60 * 1000L

    private var listener: PhoneStateListener? = null
    private var wasOffHook = false

    // 2026-08-08 (v1.8): munnadi call mudinja odane call log-la irukkura KADAISI entry-a
    // eduthu CRM-ku anuppitu irundhom -- indha app dial pannadhaa illaya nu paakaama. Adhanaala
    // staff-oda sondha outgoing call, veetla irundhu vandha incoming call, ellame lead-oda
    // call-event-a CRM-la ratta aaguchu -> manager stats-la illaadha talk time theriyuchu.
    // Ippo dial() ovvoru number-aiyum inga sollum, andha number-oda match aana call mattum
    // report aagum.
    @Volatile private var expectedNumber: String? = null
    @Volatile private var expectedAtMs = 0L

    /** MainActivity ACTION_CALL / ACTION_DIAL anuppura munnadi idha kooppidum. */
    fun expectCall(number: String) {
        expectedNumber = number
        expectedAtMs = System.currentTimeMillis()
        Log.i(TAG, "expectCall: ***${number.takeLast(4)}")
    }

    // Digits mattum, kadaisi 9. Call log "+91 98765 43210" nu kudukkum, CRM-la tel: link
    // "9876543210" or "09876543210" nu irukkum -- adhanaala neraga string compare panna
    // koodadhu, prefix/space/dash vidhyaasathula match miss aagidum.
    private fun tail(number: String): String = number.filter { it.isDigit() }.takeLast(9)

    fun startListening(context: Context) {
        if (listener != null) return  // already listening -- avoid double-register
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        val l = object : PhoneStateListener() {
            @Deprecated("Deprecated in Android API, still functional -- avoids API-level branching for this scope")
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                when (state) {
                    TelephonyManager.CALL_STATE_OFFHOOK -> wasOffHook = true
                    TelephonyManager.CALL_STATE_IDLE -> {
                        if (wasOffHook) {
                            wasOffHook = false
                            // CallLog write-ku konjam delay irukkalam -- call end aana odane
                            // andha thadava query pannina pazhaya entry varalaam.
                            Thread {
                                Thread.sleep(1500)
                                reportLatestCall(context)
                            }.start()
                        }
                    }
                }
            }
        }
        telephonyManager.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
        listener = l
        Log.i(TAG, "startListening: PhoneStateListener registered")
        flushQueue(context)   // app launch -- retry anything stuck from a previous session
    }

    fun stopListening(context: Context) {
        val l = listener ?: return
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        telephonyManager.listen(l, PhoneStateListener.LISTEN_NONE)
        listener = null
    }

    private fun reportLatestCall(context: Context) {
        // v1.8: indha app dial panna call-a mattum report pannanum.
        val expected = expectedNumber
        if (expected == null) {
            Log.i(TAG, "reportLatestCall: app dial pannina call illa (incoming / manual) -- skip")
            return
        }
        if (System.currentTimeMillis() - expectedAtMs > EXPECT_TTL_MS) {
            Log.i(TAG, "reportLatestCall: pazhaya expectation (>${EXPECT_TTL_MS / 3600000}h) -- skip")
            expectedNumber = null
            return
        }

        try {
            val cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DURATION, CallLog.Calls.TYPE),
                null, null,
                "${CallLog.Calls.DATE} DESC LIMIT 1"
            ) ?: return

            cursor.use {
                if (!it.moveToFirst()) return
                val number = it.getString(it.getColumnIndexOrThrow(CallLog.Calls.NUMBER)) ?: return

                // Kadaisi call log entry namma dial pannadhu illana -- naduvula oru incoming
                // call vandhurukkum, illa staff native dialer-la vera number-ku pēsirukkum.
                // Adha lead call-event-a anuppa koodadhu.
                if (tail(number) != tail(expected)) {
                    Log.i(
                        TAG,
                        "reportLatestCall: number match aagala (log ***${number.takeLast(4)}, " +
                            "expected ***${expected.takeLast(4)}) -- skip"
                    )
                    return
                }
                // Indha call report aayiduchu -- adutha incoming/manual call idhē expectation-a
                // marupadi use panniikka koodadhu.
                expectedNumber = null

                val durationSec = it.getInt(it.getColumnIndexOrThrow(CallLog.Calls.DURATION))
                val type = it.getInt(it.getColumnIndexOrThrow(CallLog.Calls.TYPE))
                // OUTGOING + duration>0 -> answered. MISSED/REJECTED/duration=0 -> not answered.
                val answered = type == CallLog.Calls.OUTGOING_TYPE && durationSec > 0
                Log.i(TAG, "reportLatestCall: number=***${number.takeLast(4)} durationSec=$durationSec answered=$answered")
                postCallEvent(context, number, durationSec, answered)
            }
        } catch (e: Exception) {
            // CallLog read fail aana (permission revoke aagirukkalam) -- app crash aagakoodathu,
            // idha silent-a skip pannanum, next call try pannum.
            Log.e(TAG, "reportLatestCall: CallLog read failed", e)
        }
    }

    private fun eventJson(number: String, durationSec: Int, answered: Boolean) = JSONObject().apply {
        put("phone", number)
        put("durationSec", durationSec)
        put("answered", answered)
    }

    private fun postCallEvent(context: Context, number: String, durationSec: Int, answered: Boolean) {
        val event = eventJson(number, durationSec, answered)
        Thread {
            if (!sendOne(event)) {
                Log.w(TAG, "postCallEvent: send failed, queuing for retry")
                enqueue(context, event)
            } else {
                Log.i(TAG, "postCallEvent: sent OK")
            }
            flushQueue(context)   // pazhaya queued events-um try pannu, idhoda sequence-la
        }.start()
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
            val ok = conn.responseCode in 200..299
            if (ok) conn.inputStream.use { it.readBytes() } else conn.errorStream?.use { it.readBytes() }
            conn.disconnect()
            if (!ok) Log.w(TAG, "sendOne: server rejected, code=${conn.responseCode}")
            ok
        } catch (e: Exception) {
            Log.e(TAG, "sendOne: network error", e)
            false
        }
    }

    private fun enqueue(context: Context, event: JSONObject) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString(QUEUE_KEY, "[]"))
        arr.put(event)
        prefs.edit().putString(QUEUE_KEY, arr.toString()).apply()
    }

    // Drains the local retry queue. Stops at the first still-failing event (keeps order,
    // avoids hammering the server with the whole backlog when it's down).
    fun flushQueue(context: Context) {
        Thread {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val arr = JSONArray(prefs.getString(QUEUE_KEY, "[]"))
            if (arr.length() == 0) return@Thread
            Log.i(TAG, "flushQueue: ${arr.length()} pending event(s)")
            var i = 0
            while (i < arr.length()) {
                if (!sendOne(arr.getJSONObject(i))) break
                i++
            }
            if (i > 0) {
                val remaining = JSONArray()
                for (j in i until arr.length()) remaining.put(arr.get(j))
                prefs.edit().putString(QUEUE_KEY, remaining.toString()).apply()
                Log.i(TAG, "flushQueue: sent $i, ${remaining.length()} still pending")
            }
        }.start()
    }
}
