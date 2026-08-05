package com.apsconnect.autodial

import android.content.Context
import android.provider.CallLog
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.webkit.CookieManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// 2026-08-05 (READ_CALL_LOG phase): tracks when a call started via this app ends, reads the
// real duration from CallLog, and reports it back to the CRM so staff/manager can see real
// talk time (crm/index.html's stats bubble + staff progress card were already wired to
// display this the moment call-event data starts arriving -- this is the missing producer).
object CallLogReporter {

    private const val CALL_EVENT_URL = "https://agent.adiparasakthicharitabletrust.in/crm/api/leads/call-event"

    private var listener: PhoneStateListener? = null
    private var wasOffHook = false

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
    }

    fun stopListening(context: Context) {
        val l = listener ?: return
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        telephonyManager.listen(l, PhoneStateListener.LISTEN_NONE)
        listener = null
    }

    private fun reportLatestCall(context: Context) {
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
                val durationSec = it.getInt(it.getColumnIndexOrThrow(CallLog.Calls.DURATION))
                val type = it.getInt(it.getColumnIndexOrThrow(CallLog.Calls.TYPE))
                // OUTGOING + duration>0 -> answered. MISSED/REJECTED/duration=0 -> not answered.
                val answered = type == CallLog.Calls.OUTGOING_TYPE && durationSec > 0
                postCallEvent(number, durationSec, answered)
            }
        } catch (e: Exception) {
            // CallLog read fail aana (permission revoke aagirukkalam) -- app crash aagakoodathu,
            // idha silent-a skip pannanum, next call try pannum.
        }
    }

    private fun postCallEvent(number: String, durationSec: Int, answered: Boolean) {
        Thread {
            try {
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

                val body = JSONObject().apply {
                    put("phone", number)
                    put("durationSec", durationSec)
                    put("answered", answered)
                }
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                conn.inputStream.use { it.readBytes() }
                conn.disconnect()
            } catch (e: Exception) {
                // Network illa / gateway down -- silent skip, next call-layil try pannum.
            }
        }.start()
    }
}
