package com.apsconnect.autodial

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.role.RoleManager
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.net.Uri
import android.telecom.TelecomManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/** All live monitoring and replacement of the Phone app are staff-initiated choices. */
class LiveCallSetupActivity : Activity() {
    private lateinit var body: LinearLayout
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24,32,24,24) }; setContentView(body) }
    override fun onResume() { super.onResume(); render(); LiveCallReporter.start(this) }
    private fun button(title: String, action: () -> Unit) { body.addView(Button(this).apply { text = title; setOnClickListener { action() } }) }
    private fun render() {
        body.removeAllViews()
        body.addView(TextView(this).apply { text = "Live call reporting"; textSize = 24f })
        body.addView(TextView(this).apply { text = "When enabled, your office-SIM call status and available counterpart number are shared with your branch manager in CRM, including numbers outside assigned leads. Audio is never recorded.\n\nExact dialing/connected states require choosing APS Connect as your default Phone app. APS then provides your incoming and ongoing call controls. This is optional; without it only coarse call activity (often no number) is available. Unknown or personal SIM calls are not uploaded as office calls.\n\nLive reporting: ${if (LiveCallReporter.enabled(this@LiveCallSetupActivity)) "enabled" else "off"}\nDefault Phone app: ${if (LiveCallReporter.defaultDialer(this@LiveCallSetupActivity)) "APS Connect" else "another app"}\n${LiveCallReporter.deliveryStatus}" })
        val sim = OfficeSim.snapshot(this)
        body.addView(TextView(this).apply { text = "Office SIM: ${sim.chosen?.maskedLabel ?: "not selected / unavailable"}\n${if (sim.hold) "Office SIM needs confirmation; live details are withheld." else "Only positively matched office-SIM calls are reported; personal/ambiguous SIM details stay hidden."}" })
        button("Choose / confirm Office SIM") {
            val sims = OfficeSim.listActive(this)
            if (sims.isEmpty()) AlertDialog.Builder(this).setMessage("Allow Phone permission first, then return here to choose the office SIM.").setPositiveButton("OK",null).show()
            else {
                var selected = sims.indexOfFirst { it.subId == sim.chosen?.subId }
                AlertDialog.Builder(this).setTitle("Office SIM for CRM reporting")
                    .setSingleChoiceItems(sims.map { it.maskedLabel }.toTypedArray(), selected) { _, which -> selected = which }
                    .setNegativeButton("Cancel",null).setPositiveButton("Save office SIM") { _,_ -> if (selected >= 0) { OfficeSim.choose(this,sims[selected]); render() } }.show()
            }
        }
        button(if (LiveCallReporter.enabled(this)) "Disable live reporting" else "Enable live reporting") {
            if (LiveCallReporter.enabled(this)) { LiveCallReporter.setEnabled(this, false); render() }
            else AlertDialog.Builder(this).setTitle("Share live office call activity?").setMessage("Office-SIM call status, number when available, and connection time will be visible to your manager. No call audio is sent. You can disable reporting here.").setNegativeButton("Cancel",null).setPositiveButton("Enable") { _,_ -> LiveCallReporter.setEnabled(this,true); render() }.show()
        }
        button("Phone and call-log permissions") { ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG, Manifest.permission.CALL_PHONE), 91) }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            button("Allow incoming-call notifications") { ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS),92) }
        }
        if (Build.VERSION.SDK_INT >= 34 && !getSystemService(NotificationManager::class.java).canUseFullScreenIntent()) {
            body.addView(TextView(this).apply { text = "Incoming call full-screen access is not enabled. Locked-screen incoming UI may be limited to notifications until you allow it in Android settings." })
            button("Incoming full-screen call settings") { startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName"))) }
        }
        if (!LiveCallReporter.defaultDialer(this)) button("Choose APS Connect as default Phone app") {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                AlertDialog.Builder(this).setMessage("Allow incoming-call notifications first so you can answer calls while CRM is in the background.").setPositiveButton("OK",null).show(); return@button
            }
            AlertDialog.Builder(this).setTitle("Change your default Phone app?").setMessage("Android will ask you to choose APS Connect. Its call screen replaces the current Phone app for incoming and ongoing calls. You can restore the previous Phone app in Android Default apps settings.").setNegativeButton("Cancel",null).setPositiveButton("Continue") { _,_ ->
                if (Build.VERSION.SDK_INT >= 29) {
                    val role = getSystemService(RoleManager::class.java)
                    if (role.isRoleAvailable(RoleManager.ROLE_DIALER)) startActivityForResult(role.createRequestRoleIntent(RoleManager.ROLE_DIALER), 93)
                } else startActivityForResult(Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName),93)
            }.show()
        }
        button("Default app settings") { startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
        button("Back to CRM") { finish() }
    }
}
