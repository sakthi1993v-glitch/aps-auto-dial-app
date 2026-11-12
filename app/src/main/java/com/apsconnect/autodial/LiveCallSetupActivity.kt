package com.apsconnect.autodial

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.net.Uri
import android.provider.Settings
import android.telecom.TelecomManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/** One staff setup screen; all permissions, SIM changes and Phone-role choices stay explicit. */
class LiveCallSetupActivity : Activity() {
    private lateinit var body: LinearLayout
    private var simDialog: AlertDialog? = null
    private val phonePermissions = arrayOf(Manifest.permission.READ_PHONE_STATE,Manifest.permission.READ_CALL_LOG,Manifest.permission.CALL_PHONE)
    private val asked by lazy { getSharedPreferences("phone_setup_requests",MODE_PRIVATE) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18),dp(20),dp(18),dp(24)) }
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(body) })
    }
    override fun onResume() { super.onResume(); render(); LiveCallReporter.start(this) }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        render()
    }
    override fun onDestroy() { simDialog?.dismiss(); simDialog = null; super.onDestroy() }
    private fun dp(n: Int) = (resources.displayMetrics.density*n).toInt()
    private fun allowed(value: Boolean) = if (value) "Allowed" else "Not allowed"
    private fun text(value: String, size: Float = 14f) { body.addView(TextView(this).apply { text=value; textSize=size; setPadding(0,dp(5),0,dp(7)) }) }
    private fun section(title: String) { text(title,19f) }
    private fun button(title: String, action: () -> Unit) { body.addView(Button(this).apply { text=title; isAllCaps=false; setOnClickListener { action() } }) }
    private fun openSettings(intent: Intent) {
        try { startActivity(intent) }
        catch (_: ActivityNotFoundException) { Toast.makeText(this,"This settings page is unavailable on this phone. Open Android Settings → Apps → APS Connect.",Toast.LENGTH_LONG).show() }
        catch (_: SecurityException) { Toast.makeText(this,"Open this setting manually in Android Settings → Apps → APS Connect.",Toast.LENGTH_LONG).show() }
    }
    private fun appSettings() = openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$packageName")))
    private fun requestPermissionSet(permissions: Array<String>, requestCode: Int) {
        val missing=permissions.filter { ContextCompat.checkSelfPermission(this,it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) { appSettings(); return }
        val needsSettings=missing.any { asked.getBoolean(it,false) && !ActivityCompat.shouldShowRequestPermissionRationale(this,it) }
        if (needsSettings) {
            AlertDialog.Builder(this).setTitle("Allow access in Android settings")
                .setMessage("Android may have blocked another permission request. Open App info → Permissions and allow the required access. If a sideloaded app shows a restricted-setting warning, Android may also require your explicit confirmation in App info first.")
                .setNegativeButton("Cancel",null).setPositiveButton("Open App settings") { _,_ -> appSettings() }.show()
            return
        }
        val edit=asked.edit(); missing.forEach { edit.putBoolean(it,true) }; edit.apply()
        ActivityCompat.requestPermissions(this,missing.toTypedArray(),requestCode)
    }
    private fun simLabel(sim: OfficeSim.Sim): String = "SIM ${if(sim.slot>=0) sim.slot+1 else "?"} · ${sim.carrier.ifBlank { "Carrier unavailable" }}"
    private fun chooseOfficeSim() {
        val sims=OfficeSim.listActive(this)
        if(sims.isEmpty()) {
            AlertDialog.Builder(this).setTitle("SIM list unavailable").setMessage("Allow Phone access in step 1, then try again. Android must report an active SIM before it can be selected.").setPositiveButton("OK",null).show(); return
        }
        val current=OfficeSim.setupSnapshot(this).chosen
        var selected=sims.indexOfFirst { it.subId==current?.subId }
        lateinit var dialog: AlertDialog
        dialog=AlertDialog.Builder(this).setTitle("Choose the Office SIM")
            .setSingleChoiceItems(sims.map(::simLabel).toTypedArray(),selected) { _,which -> selected=which; dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=true }
            .setNegativeButton("Cancel",null)
            .setPositiveButton("Save Office SIM") { _,_ -> if(selected>=0) { OfficeSim.choose(this,sims[selected]); CallLogReporter.syncCallLog(this); render() } }.create()
        simDialog=dialog; dialog.show(); dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=selected>=0
    }
    private fun render() {
        if (!::body.isInitialized || isFinishing) return
        body.removeAllViews()
        val status=LivePhoneSetupStatus.capture(this)
        text("Phone / SIM setup",25f)
        text("Complete the steps below for staff auto dial and live CRM reports. Android asks for every permission or default-app change; this screen cannot grant access silently.")
        section("1. Phone and call-log access")
        text("Phone state: ${allowed(status.phone)}\nCall history: ${allowed(status.callLog)}\nPlace calls: ${allowed(status.callPhone)}")
        text("Phone state supports live activity. Call history supplies completed calls, missed/rejected calls and talk time. Place calls supports the staff dial buttons.")
        button(if(status.phone && status.callLog && status.callPhone) "View / change App permissions" else "Allow Phone and call-log access") { requestPermissionSet(phonePermissions,91) }
        if(!(status.phone && status.callLog && status.callPhone)) text("If access remains blocked or the permission dialog does not appear, use Open App settings below. Android restricted-setting warnings require your own confirmation.")
        section("2. Office SIM — choose or change SIM 1 / SIM 2")
        text(if(status.selected) "Selected: SIM ${status.slot ?: "?"} · ${status.carrier ?: "Carrier unavailable"}" else "Office SIM is not selected or cannot be confirmed.")
        if(status.needsConfirmation) text("Confirm the Office SIM explicitly. Only the phone’s actual active SIMs are listed; no SIM is changed until you tap Save Office SIM.")
        text(if(status.directDialReady) "CRM auto dial: ready for the available Office SIM account." else "CRM auto dial is blocked until Phone access and a usable Office SIM are confirmed. On multi-SIM phones, APS will not silently call from the personal/default SIM.")
        text("Only positively matched Office-SIM calls are reported, including numbers outside the CRM. Personal or ambiguous SIM details stay hidden.")
        button(if(status.selected) "Change / confirm Office SIM" else "Choose Office SIM") { chooseOfficeSim() }
        section("3. Live reporting consent")
        text("Live reporting: ${if(status.liveReporting) "Enabled" else "Off"}. When enabled, your Office-SIM status, available counterpart number and connection time are visible to your branch manager. No audio is recorded or sent.")
        button(if(status.liveReporting) "Disable live reporting" else "Enable live reporting") {
            if(status.liveReporting) { LiveCallReporter.setEnabled(this,false); render() }
            else AlertDialog.Builder(this).setTitle("Share live Office-SIM activity?")
                .setMessage("Office-SIM call status and available number/connection time will be shown in CRM to your manager. Numbers outside assigned leads are included. No audio is sent. You can disable this here.")
                .setNegativeButton("Cancel",null).setPositiveButton("Enable") { _,_ -> LiveCallReporter.setEnabled(this,true); render() }.show()
        }
        section("4. Call notifications")
        text("Notification permission: ${if(Build.VERSION.SDK_INT>=33) allowed(status.notificationPermission) else "Not separately required on this Android version"}\nApp notifications: ${if(status.notificationsEnabled) "Enabled" else "Disabled in Android settings"}\nPhone-call notification channel: ${if(status.callChannelEnabled) "Enabled / ready" else "Disabled"}")
        if(Build.VERSION.SDK_INT>=33 && !status.notificationPermission) button("Allow notification permission") { requestPermissionSet(arrayOf(Manifest.permission.POST_NOTIFICATIONS),92) }
        button("Open App notification settings") {
            if(Build.VERSION.SDK_INT>=26) openSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,packageName)) else appSettings()
        }
        if(Build.VERSION.SDK_INT>=26 && !status.callChannelEnabled) button("Enable Phone-call notification channel") { openSettings(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,packageName).putExtra(Settings.EXTRA_CHANNEL_ID,"aps_calls")) }
        section("5. Default Phone app — precise live calls")
        text("Default Phone app: ${if(status.defaultPhone) "APS Connect" else "Another app"}. Exact dialing/connected state and counterparty require the Android Phone role. Without it, activity may be coarse or the number unavailable. APS Connect supplies the incoming and ongoing call screen when you choose this role.")
        if(!status.defaultPhone) button("Choose APS Connect as default Phone app") {
            if(!(status.notificationPermission && status.notificationsEnabled && status.callChannelEnabled)) {
                AlertDialog.Builder(this).setMessage("Allow call notifications in step 4 first so you can answer calls while CRM is in the background.").setPositiveButton("OK",null).show(); return@button
            }
            AlertDialog.Builder(this).setTitle("Change the default Phone app?")
                .setMessage("Android will ask you to choose APS Connect. Its incoming/ongoing call controls replace the current Phone app. You can restore the previous app in Default apps settings.")
                .setNegativeButton("Cancel",null).setPositiveButton("Continue") { _,_ ->
                    if(Build.VERSION.SDK_INT>=29) {
                        val roles=getSystemService(RoleManager::class.java)
                        if(roles.isRoleAvailable(RoleManager.ROLE_DIALER)) openSettings(roles.createRequestRoleIntent(RoleManager.ROLE_DIALER))
                        else Toast.makeText(this,"The Phone role is unavailable on this device",Toast.LENGTH_LONG).show()
                    } else openSettings(Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME,packageName))
                }.show()
        }
        button("View / restore default apps") { openSettings(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
        section("6. Incoming full-screen access")
        text(when(status.fullScreen) { null -> "Not separately required on this Android version."; true -> "Allowed — Android can display the incoming-call screen over the lock screen."; false -> "Not allowed — locked-screen incoming calls may be limited to notifications until you allow this setting." })
        if(Build.VERSION.SDK_INT>=34) button("Open full-screen call settings") { openSettings(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,Uri.parse("package:$packageName"))) }
        section("7. Status and Android App settings")
        text("App ${BuildConfig.VERSION_NAME}\n${LiveCallReporter.deliveryStatus}\nReturn from Android settings to refresh this checklist automatically.")
        button("Refresh setup status") { render() }
        button("Open App settings / permissions") { appSettings() }
        button("Back to CRM") { finish() }
    }
}
