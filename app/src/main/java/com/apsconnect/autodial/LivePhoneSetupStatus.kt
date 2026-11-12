package com.apsconnect.autodial

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object LivePhoneSetupStatus {
    fun capture(context: Context): LiveSetupSnapshot {
        fun granted(p: String) = ContextCompat.checkSelfPermission(context,p) == PackageManager.PERMISSION_GRANTED
        val sim = OfficeSim.setupSnapshot(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        val selected = sim.chosen != null && !sim.hold
        val slot = sim.chosen?.slot?.takeIf { it in 0..4 }?.plus(1)
        val channelEnabled = if (Build.VERSION.SDK_INT >= 26) manager.getNotificationChannel("aps_calls")?.importance != NotificationManager.IMPORTANCE_NONE else true
        return LiveSetupSnapshot(slot,sim.chosen?.carrier?.takeIf { it.isNotBlank() },selected,!selected || !OfficeSim.explicitChoice(context),
            granted(Manifest.permission.READ_PHONE_STATE),granted(Manifest.permission.READ_CALL_LOG),granted(Manifest.permission.CALL_PHONE),
            Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS),NotificationManagerCompat.from(context).areNotificationsEnabled(),channelEnabled,
            if (Build.VERSION.SDK_INT >= 34) manager.canUseFullScreenIntent() else null,
            LiveCallReporter.defaultDialer(context),LiveCallReporter.enabled(context),BuildConfig.VERSION_NAME,
            granted(Manifest.permission.CALL_PHONE) && LiveCallPolicy.directDialAllowed(sim.hold,sim.active.size,selected,OfficeSim.chosenHandle(context,sim) != null),
            OfficeSim.chosenHandle(context,sim) != null)
    }
}
