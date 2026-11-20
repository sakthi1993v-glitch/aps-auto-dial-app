package com.apsconnect.autodial

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat

/**
 * Auto calling needs the CRM back on screen when a call it placed ends, because the CRM then shows the feedback timer and dials the next lead.
 * With the phone's own call screen in front that never happens by itself. The CRM page calls dial(); [armed] remembers it, and when the phone goes idle
 * again [onCallEnded] brings MainActivity to the front.
 * Android lets an app start an activity from the background only with an exemption: being the default Phone app (then the in-call screen already finishes
 * back into the CRM) or having "Display over other apps". Without either, a high-priority "Call ended - back to CRM" notification is shown instead.
 */
object CallReturn {
    private const val CHANNEL = "aps_return"
    private const val WINDOW_MS = 3 * 60 * 60 * 1000L
    @Volatile private var armedAt = 0L
    @Volatile private var ended = false

    /** MainActivity.onResume / onPause keep this true only while the CRM is on screen. */
    @Volatile var crmVisible = false

    fun arm() { armedAt = System.currentTimeMillis(); ended = false }
    fun isDefaultPhone(context: Context): Boolean =
        try { Build.VERSION.SDK_INT >= 29 && context.getSystemService(android.app.role.RoleManager::class.java).isRoleHeld(android.app.role.RoleManager.ROLE_DIALER) } catch (_: Exception) { false }
    fun overlayGranted(context: Context): Boolean = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(context)
    /** true when a call from the CRM can bring the CRM back by itself */
    fun ready(context: Context): Boolean = isDefaultPhone(context) || overlayGranted(context)
    /** MainActivity asks once after resuming: did a call from the CRM just end? (then the page is told to show its feedback sheet) */
    fun consumeEnded(): Boolean { val e = ended; ended = false; return e }

    fun onCallEnded(context: Context) {
        val app = context.applicationContext
        if (armedAt == 0L || System.currentTimeMillis() - armedAt > WINDOW_MS) return
        armedAt = 0L; ended = true
        if (crmVisible) return                                   // already there
        val open = Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (ready(app)) {
            try { app.startActivity(open); return } catch (_: Exception) { /* fall through to the notification */ }
        }
        notifyReturn(app, open)
    }

    private fun notifyReturn(app: Context, open: Intent) {
        try {
            val manager = app.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL, Lang.t(app, "Back to CRM", "CRM-க்குத் திரும்பு"), NotificationManager.IMPORTANCE_HIGH))
            val pending = PendingIntent.getActivity(app, 8602, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(app, CHANNEL).setSmallIcon(android.R.drawable.sym_call_outgoing)
                .setContentTitle(Lang.t(app, "Call ended", "அழைப்பு முடிந்தது"))
                .setContentText(Lang.t(app, "Tap to return to the CRM and save the result", "CRM-க்குத் திரும்பி முடிவைப் பதிவு செய்ய தொடவும்"))
                .setContentIntent(pending).setAutoCancel(true).setTimeoutAfter(120000)
                .setPriority(NotificationCompat.PRIORITY_MAX).setCategory(NotificationCompat.CATEGORY_CALL).setFullScreenIntent(pending, false).build()
            manager.notify(8602, n)
        } catch (_: SecurityException) { /* notification permission missing: the setup screen shows it */ }
    }
}
