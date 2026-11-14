package com.apsconnect.autodial

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.InCallService
import android.telecom.TelecomManager
import androidx.core.app.NotificationCompat
import java.util.UUID

/** System-bound dialer service. Local controls cover every Telecom call; reporting
 * remains opt-in and fails closed unless the selected office SIM is positively matched. */
class LiveInCallService : InCallService() {
    private fun t(en: String, ta: String) = Lang.t(this, en, ta)
    companion object {
        var current: LiveInCallService? = null
            private set
        private val trackedCalls = linkedMapOf<Call, String>()
        val listeners = linkedSetOf<() -> Unit>()
        fun hasCalls() = trackedCalls.isNotEmpty()
        fun snapshot(): List<Pair<Call,String>> = trackedCalls.map { it.key to it.value }
        fun find(id: String?) = trackedCalls.entries.firstOrNull { it.value == id }?.key
    }
    private val callbacks = mutableMapOf<Call, Call.Callback>()
    private val starts = mutableMapOf<Call, Long>()
    private val handler = Handler(Looper.getMainLooper())
    override fun onCreate() { super.onCreate(); current = this; LiveCallReporter.start(this) }
    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        trackedCalls[call] = UUID.randomUUID().toString(); starts[call] = System.currentTimeMillis()
        val callback = object : Call.Callback() {
            override fun onStateChanged(call: Call, state: Int) { changed() }
            override fun onDetailsChanged(call: Call, details: Call.Details) { changed() }
        }
        callbacks[call] = callback; call.registerCallback(callback, handler)
        changed(); openCallUi()
    }
    override fun onCallRemoved(call: Call) {
        callbacks.remove(call)?.let { call.unregisterCallback(it) }
        trackedCalls.remove(call); starts.remove(call)
        if (trackedCalls.isEmpty()) {
            LiveCallReporter.publish(this, "ended", null, "unknown", 0, 0, "telecom")
            getSystemService(NotificationManager::class.java).cancel(8601)
            CallLogReporter.syncCallLog(this)
        } else changed()
        listeners.toList().forEach { it() }
        super.onCallRemoved(call)
    }
    override fun onBringToForeground(showDialpad: Boolean) { openCallUi() }
    override fun onDestroy() {
        callbacks.forEach { (call, cb) -> call.unregisterCallback(cb) }; callbacks.clear()
        trackedCalls.clear(); starts.clear(); current = null
        LiveCallReporter.publish(this, "unknown", null, "unknown", 0, 0, "telecom")
        listeners.toList().forEach { it() }; super.onDestroy()
    }
    fun refreshReporting() { changed() }
    private fun changed() {
        val call = trackedCalls.keys.firstOrNull { it.state == Call.STATE_ACTIVE }
            ?: trackedCalls.keys.firstOrNull { it.state == Call.STATE_RINGING } ?: trackedCalls.keys.firstOrNull() ?: return
        val details = call.details
        val snap = OfficeSim.snapshot(this)
        val handle = details.accountHandle
        val office = !snap.hold && OfficeSim.classify(handle?.id, handle?.componentName?.flattenToString(), snap) == OfficeSim.Match.CHOSEN
        val number = if (LiveCallPolicy.mayShareNumber(office, details.handlePresentation == TelecomManager.PRESENTATION_ALLOWED)) details.handle?.schemeSpecificPart else null
        val direction = if (Build.VERSION.SDK_INT >= 29) when(details.callDirection) { Call.Details.DIRECTION_INCOMING -> "incoming"; Call.Details.DIRECTION_OUTGOING -> "outgoing"; else -> "unknown" } else "unknown"
        val connectedAt = LiveCallPolicy.connectionTime(call.state, details.connectTimeMillis, System.currentTimeMillis())
        val phase = LiveCallPolicy.phase(call.state).let { if (it == "connected" && connectedAt == null) "offhook" else it }
        LiveCallReporter.publish(this, if (office) phase else "unknown", number, if (office) direction else "unknown", if (office) starts[call] ?: 0L else 0L, if (office) connectedAt ?: 0L else 0L, "telecom", office && call.state == Call.STATE_HOLDING, trackedCalls[call])
        notifyCall(call)
        listeners.toList().forEach { it() }
    }
    private fun openCallUi() {
        try { startActivity(Intent(this, InCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)) } catch (_: Exception) { /* full-screen notification remains available */ }
    }
    private fun notifyCall(call: Call) {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel("aps_calls", t("Phone calls","போன் அழைப்புகள்"), NotificationManager.IMPORTANCE_HIGH).apply { lockscreenVisibility = Notification.VISIBILITY_PRIVATE })
        val intent = Intent(this, InCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val open = PendingIntent.getActivity(this, 8601, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, "aps_calls").setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle(if (call.state == Call.STATE_RINGING) t("Incoming phone call","உள்வரும் போன் அழைப்பு") else t("Phone call in progress","போன் அழைப்பு நடைபெறுகிறது"))
            .setContentText(t("Tap to answer or manage this call","இந்த அழைப்பை ஏற்க அல்லது நிர்வகிக்க தொடவும்")).setContentIntent(open).setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL).setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        if (call.state == Call.STATE_RINGING) notification.setFullScreenIntent(open, true)
        try { manager.notify(8601, notification.build()) } catch (_: SecurityException) { /* permission status is shown in setup */ }
    }
}
