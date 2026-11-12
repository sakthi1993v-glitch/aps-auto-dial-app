package com.apsconnect.autodial

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.webkit.CookieManager
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.Executors

/** Latest-state transport only: no stale offline outbox and no raw phone/cookie logging. */
object LiveCallReporter {
    const val BASE = "https://agent.adiparasakthicharitabletrust.in"
    private const val PREFS = "live_call_reporting"
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var context: Context? = null
    private var tick: Runnable? = null
    private var session = UUID.randomUUID().toString()
    private var sequence = 0L
    private var cookie = ""
    private var phase = "unknown"
    private var phone: String? = null
    private var direction = "unknown"
    private var started = 0L
    private var connected = 0L
    private var source = "telephony"
    private var held = false
    private var callIdentity: String? = null
    private val identityGuard = LiveIdentityGuard()
    private var latestVersion = 0L
    @Volatile private var version = 0L
    @Volatile var deliveryStatus = "Not connected"
        private set

    fun enabled(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", false)
    fun defaultDialer(context: Context) = (context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager).defaultDialerPackage == context.packageName
    fun setEnabled(context: Context, enabled: Boolean) {
        if (!enabled) { publish(context, "unknown", null, "unknown", 0, 0, "telephony"); stop() }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", enabled).apply()
        if (enabled) start(context)
    }
    private fun iso(ms: Long) = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))
    private fun has(context: Context, permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    fun start(context: Context) {
        if (!enabled(context)) return
        this.context = context.applicationContext
        if (tick != null) return
        val task = object : Runnable { override fun run() { send(); handler.postDelayed(this, 15000) } }
        tick = task; handler.post(task)
    }
    fun stop() { tick?.let { handler.removeCallbacks(it) }; tick = null; cookie = "" }
    private fun begin() { session = UUID.randomUUID().toString(); sequence = 0; cookie = CookieManager.getInstance().getCookie(BASE) ?: "" }
    @Synchronized fun publish(context: Context, next: String, number: String?, dir: String, callStarted: Long, connectedAt: Long, via: String, isHeld: Boolean = false, identity: String? = null) {
        if (!enabled(context)) return
        start(context)
        if ((identity != null && identity != callIdentity) || (phase in listOf("idle", "ended", "unknown") && next !in listOf("idle", "unknown")) || cookie.isEmpty()) begin()
        callIdentity = identity
        if (next == "ended" || next == "idle") identityGuard.callFinished()
        val allowed = identityGuard.mayShare(identity)
        phase = if (allowed) next else "unknown"; phone = if (allowed) number?.takeIf { it.isNotBlank() } else null; direction = if (allowed) dir else "unknown"
        started = if (allowed) callStarted else 0; connected = if (allowed) connectedAt else 0; source = via; held = allowed && isHeld
        send()
    }
    fun telephony(context: Context, state: Int) {
        if (!enabled(context) || LiveInCallService.hasCalls()) return
        val office = OfficeSim.snapshot(context)
        if (!LiveCallPolicy.coarseOfficeAllowed(office.hold, office.chosen != null, office.active.size)) {
            publish(context, "unknown", null, "unknown", 0, 0, "telephony")
            return
        }
        // Multi-SIM callbacks cannot reliably identify the account or counterpart.
        // Preserve the existing office-SIM boundary rather than upload a personal number.
        val next = when (state) { TelephonyManager.CALL_STATE_RINGING -> "ringing"; TelephonyManager.CALL_STATE_OFFHOOK -> "offhook"; else -> if (phase in listOf("ringing","dialing","offhook","connected")) "ended" else "idle" }
        val nextDirection = if (next == "ringing") "incoming" else if (next == "offhook" && direction == "incoming") "incoming" else "unknown"
        publish(context, next, null, nextDirection, if (started > 0 && next != "idle") started else System.currentTimeMillis(), 0, "telephony")
    }
    @Synchronized private fun send() {
        val app = context ?: return
        if (!enabled(app)) return
        val currentCookie = CookieManager.getInstance().getCookie(BASE) ?: ""
        if (cookie.isEmpty()) begin()
        if (LiveCookiePolicy.authToken(cookie) == null) { deliveryStatus = "Sign in as staff to report live calls"; return }
        if (!LiveCookiePolicy.sameIdentity(currentCookie, cookie)) {
            // Never attribute an in-progress call to a newly signed-in account.
            val oldAuth = cookie
            val clear = terminalPacket(app)
            worker.execute { post(clear, oldAuth, 1200) }
            identityGuard.accountChanged(callIdentity)
            phase = "unknown"; phone = null; connected = 0; started = 0
            begin()
        } else cookie = currentCookie // retain refreshed non-auth cookies without changing identity
        if (LiveCookiePolicy.authToken(cookie) == null) { deliveryStatus = "Sign in as staff to report live calls"; return }
        val permission = has(app, Manifest.permission.READ_PHONE_STATE)
        val precise = source == "telecom" && defaultDialer(app)
        val packet = JSONObject().put("deviceId", deviceId(app)).put("sessionId", session).put("sequence", ++sequence)
            .put("phase", if (permission || precise) phase else "unknown").put("direction", direction)
            .put("at", iso(System.currentTimeMillis())).put("source", source).put("held", held)
            .put("capabilities", JSONObject().put("liveState", permission || precise).put("phoneNumber", phone != null && precise)
                .put("preciseConnection", precise).put("callLog", has(app, Manifest.permission.READ_CALL_LOG)))
        if (phone != null && precise) packet.put("phone", phone)
        if (started > 0) packet.put("callStartedAt", iso(started))
        if (connected > 0 && phase == "connected" && precise) packet.put("connectedAt", iso(connected))
        val auth = cookie
        val observedAt = System.currentTimeMillis()
        val requestVersion = ++latestVersion
        version = requestVersion
        worker.execute {
            if (version != requestVersion) return@execute
            if (LiveCallPolicy.fresh(observedAt, System.currentTimeMillis())) post(packet, auth, 6000)

        }
    }
    private fun terminalPacket(app: Context): JSONObject = JSONObject()
        .put("deviceId", deviceId(app)).put("sessionId", session).put("sequence", ++sequence)
        .put("phase", "unknown").put("direction", "unknown").put("source", source).put("at", iso(System.currentTimeMillis()))
        .put("capabilities", JSONObject().put("liveState", false).put("phoneNumber", false).put("preciseConnection", false).put("callLog", false))

    /** Called on a background thread by the origin-restricted WebView message bridge. */
    fun clearBeforeLogout(): Boolean {
        val snapshot = synchronized(this) {
            val app = context ?: return false
            val oldAuth = cookie
            if (oldAuth.isEmpty()) return false
            val packet = terminalPacket(app)
            identityGuard.accountChanged(callIdentity)
            phase = "unknown"; phone = null; connected = 0; started = 0
            version = ++latestVersion
            cookie = ""
            packet to oldAuth
        }
        return post(snapshot.first, snapshot.second, 1200)
    }

    private fun post(packet: JSONObject, auth: String, timeout: Int): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL("$BASE/crm/api/live-calls/event").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"; connection.connectTimeout = timeout; connection.readTimeout = timeout
            connection.instanceFollowRedirects = false; connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json"); connection.setRequestProperty("Cookie", auth)
            connection.outputStream.use { it.write(packet.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val accepted = if (status in 200..299) {
                val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    val result = StringBuilder(); val buffer = CharArray(512)
                    while (result.length < 4096) {
                        val count = reader.read(buffer,0,minOf(buffer.size,4096-result.length))
                        if (count < 0) break
                        result.append(buffer,0,count)
                    }
                    result.toString()
                }
                runCatching { JSONObject(body).optBoolean("accepted",false) }.getOrDefault(false)
            } else false
            val ok = LiveCallPolicy.acknowledged(status,accepted)
            deliveryStatus = if (ok) "Live state reported" else if (status == 401 || status == 403) "Staff sign-in required" else if (status in 200..299) "Live state was not accepted; awaiting a fresh report" else "Live server unavailable; retrying"
            ok
        } catch (_: Exception) { deliveryStatus = "Offline; live state will retry"; false }
        finally { connection?.disconnect() }
    }
    private fun deviceId(context: Context): String {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.getString("deviceId", null) ?: UUID.randomUUID().toString().also { p.edit().putString("deviceId", it).apply() }
    }
}
