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

/** One staff setup screen; all permissions, SIM changes and Phone-role choices stay explicit. English / Tamil via [Lang]. */
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
    private fun t(en: String, ta: String) = Lang.t(this, en, ta)
    private fun allowed(value: Boolean) = if (value) t("Allowed","அனுமதிக்கப்பட்டது") else t("Not allowed","அனுமதிக்கப்படவில்லை")
    private fun carrierMissing() = t("Carrier unavailable","நெட்வொர்க் தகவல் இல்லை")
    private fun text(value: String, size: Float = 14f) { body.addView(TextView(this).apply { text=value; textSize=size; setPadding(0,dp(5),0,dp(7)) }) }
    private fun section(title: String) { text(title,19f) }
    private fun button(title: String, action: () -> Unit) { body.addView(Button(this).apply { text=title; isAllCaps=false; setOnClickListener { action() } }) }
    private fun openSettings(intent: Intent) {
        try { startActivity(intent) }
        catch (_: ActivityNotFoundException) { Toast.makeText(this,t("This settings page is unavailable on this phone. Open Android Settings → Apps → APS Connect.","இந்த அமைப்புப் பக்கம் இந்த போனில் கிடைக்கவில்லை. Android அமைப்புகள் → Apps → APS Connect என்பதைத் திறக்கவும்."),Toast.LENGTH_LONG).show() }
        catch (_: SecurityException) { Toast.makeText(this,t("Open this setting manually in Android Settings → Apps → APS Connect.","Android அமைப்புகள் → Apps → APS Connect-இல் இந்த அமைப்பை நீங்களே திறக்கவும்."),Toast.LENGTH_LONG).show() }
    }
    private fun appSettings() = openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$packageName")))
    private fun requestPermissionSet(permissions: Array<String>, requestCode: Int) {
        val missing=permissions.filter { ContextCompat.checkSelfPermission(this,it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) { appSettings(); return }
        val needsSettings=missing.any { asked.getBoolean(it,false) && !ActivityCompat.shouldShowRequestPermissionRationale(this,it) }
        if (needsSettings) {
            AlertDialog.Builder(this).setTitle(t("Allow access in Android settings","Android அமைப்புகளில் அணுகலை அனுமதிக்கவும்"))
                .setMessage(t("Android may have blocked another permission request. Open App info → Permissions and allow the required access. If a sideloaded app shows a restricted-setting warning, Android may also require your explicit confirmation in App info first.",
                    "அனுமதி கோரிக்கையை Android தடுத்திருக்கலாம். App info → Permissions என்பதைத் திறந்து தேவையான அணுகலை அனுமதிக்கவும். வெளியிலிருந்து நிறுவிய செயலிக்கு restricted-setting எச்சரிக்கை வந்தால், முதலில் App info-வில் உங்கள் உறுதிப்படுத்தலை Android கேட்கலாம்."))
                .setNegativeButton(t("Cancel","ரத்து செய்"),null).setPositiveButton(t("Open App settings","App அமைப்புகளைத் திற")) { _,_ -> appSettings() }.show()
            return
        }
        val edit=asked.edit(); missing.forEach { edit.putBoolean(it,true) }; edit.apply()
        ActivityCompat.requestPermissions(this,missing.toTypedArray(),requestCode)
    }
    private fun simLabel(sim: OfficeSim.Sim): String = t("SIM ","சிம் ")+"${if(sim.slot>=0) sim.slot+1 else "?"} · ${sim.carrier.ifBlank { carrierMissing() }}"
    private fun chooseOfficeSim() {
        val sims=OfficeSim.listActive(this)
        if(sims.isEmpty()) {
            AlertDialog.Builder(this).setTitle(t("SIM list unavailable","சிம் பட்டியல் கிடைக்கவில்லை")).setMessage(t("Allow Phone access in step 1, then try again. Android must report an active SIM before it can be selected.","படி 1-இல் போன் அணுகலை அனுமதித்து, மீண்டும் முயற்சிக்கவும். தேர்ந்தெடுக்கும் முன் செயலில் உள்ள சிம்மை Android காட்ட வேண்டும்.")).setPositiveButton(t("OK","சரி"),null).show(); return
        }
        val current=OfficeSim.setupSnapshot(this).chosen
        var selected=sims.indexOfFirst { it.subId==current?.subId }
        lateinit var dialog: AlertDialog
        dialog=AlertDialog.Builder(this).setTitle(t("Choose the Office SIM","அலுவலக சிம்மைத் தேர்ந்தெடுக்கவும்"))
            .setSingleChoiceItems(sims.map(::simLabel).toTypedArray(),selected) { _,which -> selected=which; dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=true }
            .setNegativeButton(t("Cancel","ரத்து செய்"),null)
            .setPositiveButton(t("Save Office SIM","அலுவலக சிம்மைச் சேமி")) { _,_ -> if(selected>=0) { OfficeSim.choose(this,sims[selected]); CallLogReporter.syncCallLog(this); render() } }.create()
        simDialog=dialog; dialog.show(); dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=selected>=0
    }
    private fun render() {
        if (!::body.isInitialized || isFinishing) return
        body.removeAllViews()
        val status=LivePhoneSetupStatus.capture(this)
        text(t("Phone / SIM setup","போன் / சிம் அமைப்பு"),25f)
        button(Lang.toggleLabel(this)) { Lang.set(this,!Lang.isTamil(this)); recreate() }
        text(t("Complete the steps below for staff auto dial and live CRM reports. Android asks for every permission or default-app change; this screen cannot grant access silently.",
            "ஊழியர் தானியங்கி அழைப்பு மற்றும் நேரடி CRM அறிக்கைகளுக்கு கீழுள்ள படிகளை முடிக்கவும். ஒவ்வொரு அனுமதிக்கும் அல்லது இயல்புநிலைச் செயலி மாற்றத்துக்கும் Android கேட்கும்; இந்தத் திரை அமைதியாக அணுகலை வழங்க முடியாது."))
        section(t("1. Phone and call-log access","1. போன் மற்றும் அழைப்புப் பதிவு அணுகல்"))
        text(t("Phone state: ${allowed(status.phone)}\nCall history: ${allowed(status.callLog)}\nPlace calls: ${allowed(status.callPhone)}",
            "போன் நிலை: ${allowed(status.phone)}\nஅழைப்பு வரலாறு: ${allowed(status.callLog)}\nஅழைப்புகள் செய்தல்: ${allowed(status.callPhone)}"))
        text(t("Phone state supports live activity. Call history supplies completed calls, missed/rejected calls and talk time. Place calls supports the staff dial buttons.",
            "போன் நிலை நேரடிச் செயல்பாட்டுக்கு உதவுகிறது. அழைப்பு வரலாறு முடிந்த, தவறிய/நிராகரித்த அழைப்புகளையும் பேசிய நேரத்தையும் வழங்குகிறது. அழைப்புகள் செய்தல் ஊழியர் டயல் பொத்தான்களுக்கு உதவுகிறது."))
        button(if(status.phone && status.callLog && status.callPhone) t("View / change App permissions","App அனுமதிகளைப் பார் / மாற்று") else t("Allow Phone and call-log access","போன் மற்றும் அழைப்புப் பதிவு அணுகலை அனுமதி")) { requestPermissionSet(phonePermissions,91) }
        if(!(status.phone && status.callLog && status.callPhone)) text(t("If access remains blocked or the permission dialog does not appear, use Open App settings below. Android restricted-setting warnings require your own confirmation.",
            "அணுகல் தடையாகவே இருந்தால் அல்லது அனுமதி உரையாடல் தோன்றவில்லை என்றால், கீழே App அமைப்புகளைத் திறக்கவும். Android restricted-setting எச்சரிக்கைகளுக்கு உங்கள் உறுதிப்படுத்தல் தேவை."))
        section(t("2. Office SIM — choose or change SIM 1 / SIM 2","2. அலுவலக சிம் — சிம் 1 / சிம் 2 தேர்ந்தெடு அல்லது மாற்று"))
        text(if(status.selected) t("Selected: SIM ${status.slot ?: "?"} · ${status.carrier ?: carrierMissing()}","தேர்ந்தெடுத்தது: சிம் ${status.slot ?: "?"} · ${status.carrier ?: carrierMissing()}")
            else t("Office SIM is not selected or cannot be confirmed.","அலுவலக சிம் தேர்ந்தெடுக்கப்படவில்லை அல்லது உறுதிசெய்ய முடியவில்லை."))
        if(status.needsConfirmation) text(t("Confirm the Office SIM explicitly. Only the phone’s actual active SIMs are listed; no SIM is changed until you tap Save Office SIM.",
            "அலுவலக சிம்மைத் தெளிவாக உறுதிசெய்யவும். போனில் செயலில் உள்ள சிம்கள் மட்டுமே காட்டப்படும்; அலுவலக சிம்மைச் சேமி என்பதைத் தொடும் வரை எந்த சிம்மும் மாறாது."))
        text(if(status.directDialReady) t("CRM auto dial: ready for the available Office SIM account.","CRM தானியங்கி அழைப்பு: கிடைக்கும் அலுவலக சிம் கணக்குக்குத் தயார்.")
            else t("CRM auto dial is blocked until Phone access and a usable Office SIM are confirmed. On multi-SIM phones, APS will not silently call from the personal/default SIM.",
                "போன் அணுகலும் பயன்படுத்தக்கூடிய அலுவலக சிம்மும் உறுதியாகும் வரை CRM தானியங்கி அழைப்பு தடுக்கப்பட்டுள்ளது. பல சிம் போன்களில் தனிப்பட்ட/இயல்புநிலை சிம்மிலிருந்து APS அமைதியாக அழைக்காது."))
        text(t("Only positively matched Office-SIM calls are reported, including numbers outside the CRM. Personal or ambiguous SIM details stay hidden.",
            "உறுதியாகப் பொருந்திய அலுவலக சிம் அழைப்புகள் மட்டுமே பதிவாகும் (CRM-க்கு வெளியே உள்ள எண்கள் உட்பட). தனிப்பட்ட அல்லது தெளிவற்ற சிம் விவரங்கள் மறைக்கப்படும்."))
        button(if(status.selected) t("Change / confirm Office SIM","அலுவலக சிம்மை மாற்று / உறுதிசெய்") else t("Choose Office SIM","அலுவலக சிம்மைத் தேர்ந்தெடு")) { chooseOfficeSim() }
        section(t("3. Live reporting consent","3. நேரடி அறிக்கை ஒப்புதல்"))
        text(t("Live reporting: ${if(status.liveReporting) "Enabled" else "Off"}. When enabled, your Office-SIM status, available counterpart number and connection time are visible to your branch manager. No audio is recorded or sent.",
            "நேரடி அறிக்கை: ${if(status.liveReporting) "இயக்கத்தில்" else "நிறுத்தத்தில்"}. இயக்கினால், உங்கள் அலுவலக சிம் நிலை, கிடைக்கும் மறுமுனை எண், இணைப்பு நேரம் ஆகியவை உங்கள் கிளை மேலாளருக்குத் தெரியும். ஒலி பதிவு செய்யப்படாது, அனுப்பப்படாது."))
        button(if(status.liveReporting) t("Disable live reporting","நேரடி அறிக்கையை நிறுத்து") else t("Enable live reporting","நேரடி அறிக்கையை இயக்கு")) {
            if(status.liveReporting) { LiveCallReporter.setEnabled(this,false); render() }
            else AlertDialog.Builder(this).setTitle(t("Share live Office-SIM activity?","அலுவலக சிம் செயல்பாட்டை நேரடியாகப் பகிரவா?"))
                .setMessage(t("Office-SIM call status and available number/connection time will be shown in CRM to your manager. Numbers outside assigned leads are included. No audio is sent. You can disable this here.",
                    "அலுவலக சிம் அழைப்பு நிலை, கிடைக்கும் எண்/இணைப்பு நேரம் CRM-இல் உங்கள் மேலாளருக்குக் காட்டப்படும். ஒதுக்கப்பட்ட லீட்களுக்கு வெளியே உள்ள எண்களும் அடங்கும். ஒலி அனுப்பப்படாது. இதை இங்கேயே நிறுத்தலாம்."))
                .setNegativeButton(t("Cancel","ரத்து செய்"),null).setPositiveButton(t("Enable","இயக்கு")) { _,_ -> LiveCallReporter.setEnabled(this,true); render() }.show()
        }
        section(t("4. Call notifications","4. அழைப்பு அறிவிப்புகள்"))
        val notifPerm = if(Build.VERSION.SDK_INT>=33) allowed(status.notificationPermission) else t("Not separately required on this Android version","இந்த Android பதிப்பில் தனியாகத் தேவையில்லை")
        text(t("Notification permission: $notifPerm\nApp notifications: ${if(status.notificationsEnabled) "Enabled" else "Disabled in Android settings"}\nPhone-call notification channel: ${if(status.callChannelEnabled) "Enabled / ready" else "Disabled"}",
            "அறிவிப்பு அனுமதி: $notifPerm\nApp அறிவிப்புகள்: ${if(status.notificationsEnabled) "இயக்கத்தில்" else "Android அமைப்புகளில் முடக்கப்பட்டுள்ளது"}\nபோன் அழைப்பு அறிவிப்பு சேனல்: ${if(status.callChannelEnabled) "இயக்கத்தில் / தயார்" else "முடக்கப்பட்டுள்ளது"}"))
        if(Build.VERSION.SDK_INT>=33 && !status.notificationPermission) button(t("Allow notification permission","அறிவிப்பு அனுமதியை வழங்கு")) { requestPermissionSet(arrayOf(Manifest.permission.POST_NOTIFICATIONS),92) }
        button(t("Open App notification settings","App அறிவிப்பு அமைப்புகளைத் திற")) {
            if(Build.VERSION.SDK_INT>=26) openSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,packageName)) else appSettings()
        }
        if(Build.VERSION.SDK_INT>=26 && !status.callChannelEnabled) button(t("Enable Phone-call notification channel","போன் அழைப்பு அறிவிப்பு சேனலை இயக்கு")) { openSettings(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,packageName).putExtra(Settings.EXTRA_CHANNEL_ID,"aps_calls")) }
        section(t("5. Default Phone app — precise live calls","5. இயல்புநிலை போன் செயலி — துல்லியமான நேரடி அழைப்புகள்"))
        text(t("Default Phone app: ${if(status.defaultPhone) "APS Connect" else "Another app"}. Exact dialing/connected state and counterparty require the Android Phone role. Without it, activity may be coarse or the number unavailable. APS Connect supplies the incoming and ongoing call screen when you choose this role.",
            "இயல்புநிலை போன் செயலி: ${if(status.defaultPhone) "APS Connect" else "வேறு செயலி"}. துல்லியமான டயலிங்/இணைப்பு நிலை மற்றும் மறுமுனைக்கு Android Phone role தேவை. அது இல்லையெனில் செயல்பாடு தோராயமாக இருக்கலாம் அல்லது எண் கிடைக்காமல் போகலாம். இந்த role-ஐத் தேர்ந்தால் உள்வரும் மற்றும் நடப்பு அழைப்புத் திரையை APS Connect வழங்கும்."))
        if(!status.defaultPhone) button(t("Choose APS Connect as default Phone app","APS Connect-ஐ இயல்புநிலை போன் செயலியாகத் தேர்ந்தெடு")) {
            if(!(status.notificationPermission && status.notificationsEnabled && status.callChannelEnabled)) {
                AlertDialog.Builder(this).setMessage(t("Allow call notifications in step 4 first so you can answer calls while CRM is in the background.","CRM பின்னணியில் இருக்கும்போது அழைப்புகளை ஏற்க, முதலில் படி 4-இல் அழைப்பு அறிவிப்புகளை அனுமதிக்கவும்.")).setPositiveButton(t("OK","சரி"),null).show(); return@button
            }
            AlertDialog.Builder(this).setTitle(t("Change the default Phone app?","இயல்புநிலை போன் செயலியை மாற்றவா?"))
                .setMessage(t("Android will ask you to choose APS Connect. Its incoming/ongoing call controls replace the current Phone app. You can restore the previous app in Default apps settings.",
                    "APS Connect-ஐத் தேர்ந்தெடுக்க Android கேட்கும். அதன் உள்வரும்/நடப்பு அழைப்புக் கட்டுப்பாடுகள் தற்போதைய போன் செயலிக்குப் பதிலாக வரும். முந்தைய செயலியை Default apps அமைப்புகளில் மீட்டெடுக்கலாம்."))
                .setNegativeButton(t("Cancel","ரத்து செய்"),null).setPositiveButton(t("Continue","தொடர்க")) { _,_ ->
                    if(Build.VERSION.SDK_INT>=29) {
                        val roles=getSystemService(RoleManager::class.java)
                        if(roles.isRoleAvailable(RoleManager.ROLE_DIALER)) openSettings(roles.createRequestRoleIntent(RoleManager.ROLE_DIALER))
                        else Toast.makeText(this,t("The Phone role is unavailable on this device","இந்தச் சாதனத்தில் Phone role கிடைக்கவில்லை"),Toast.LENGTH_LONG).show()
                    } else openSettings(Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME,packageName))
                }.show()
        }
        button(t("View / restore default apps","இயல்புநிலைச் செயலிகளைப் பார் / மீட்டெடு")) { openSettings(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
        section(t("6. Incoming full-screen access","6. உள்வரும் முழுத்திரை அணுகல்"))
        text(when(status.fullScreen) {
            null -> t("Not separately required on this Android version.","இந்த Android பதிப்பில் தனியாகத் தேவையில்லை.")
            true -> t("Allowed — Android can display the incoming-call screen over the lock screen.","அனுமதிக்கப்பட்டது — பூட்டுத் திரையின் மேல் உள்வரும் அழைப்புத் திரையை Android காட்ட முடியும்.")
            false -> t("Not allowed — locked-screen incoming calls may be limited to notifications until you allow this setting.","அனுமதிக்கப்படவில்லை — இந்த அமைப்பை அனுமதிக்கும் வரை பூட்டிய நிலையில் உள்வரும் அழைப்புகள் அறிவிப்புகளுக்கு மட்டுமே வரையறுக்கப்படலாம்.") })
        if(Build.VERSION.SDK_INT>=34) button(t("Open full-screen call settings","முழுத்திரை அழைப்பு அமைப்புகளைத் திற")) { openSettings(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,Uri.parse("package:$packageName"))) }
        section(t("7. Status and Android App settings","7. நிலை மற்றும் Android App அமைப்புகள்"))
        text(t("App ${BuildConfig.VERSION_NAME}\n${LiveCallReporter.deliveryStatus}\nReturn from Android settings to refresh this checklist automatically.",
            "செயலி ${BuildConfig.VERSION_NAME}\n${LiveCallReporter.deliveryStatus}\nஇந்தப் பட்டியல் தானாகப் புதுப்பிக்க Android அமைப்புகளிலிருந்து திரும்பவும்."))
        button(t("Refresh setup status","அமைப்பு நிலையைப் புதுப்பி")) { render() }
        button(t("Open App settings / permissions","App அமைப்புகள் / அனுமதிகளைத் திற")) { appSettings() }
        button(t("Back to CRM","CRM-க்குத் திரும்பு")) { finish() }
    }
}
