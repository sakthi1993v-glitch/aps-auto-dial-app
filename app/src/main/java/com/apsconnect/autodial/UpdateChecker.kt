package com.apsconnect.autodial

import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** Download state survives an Activity/process restart. Polling avoids broadcast races
 * and DownloadManager sender/export differences. Android still owns the Install decision. */
object UpdateChecker {
    private fun t(c:Context,en:String,ta:String)=Lang.t(c,en,ta)
    private const val VERSION_URL="https://raw.githubusercontent.com/sakthi1993v-glitch/aps-auto-dial-app/main/version.json"
    private const val PREFS="apk_update"
    private val handler=Handler(Looper.getMainLooper())
    private var screen=WeakReference<MainActivity>(null)
    private var active=false
    private var dialog: AlertDialog?=null
    private var progress: ProgressBar?=null
    private var progressText: TextView?=null
    private var progressHidden=false
    private val checking=AtomicBoolean(false)
    private val validating=AtomicBoolean(false)
    private val poll=object:Runnable { override fun run(){ screen.get()?.takeIf{active && !it.isFinishing && !it.isDestroyed}?.let { query(it) } } }
    private fun prefs(c:Context)=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
    private fun usable(a:MainActivity)=!a.isFinishing && !a.isDestroyed
    private fun foreground(a:MainActivity)=usable(a) && active && screen.get()===a
    private fun dismiss(){ dialog?.dismiss();dialog=null;progress=null;progressText=null }
    fun onResume(a:MainActivity){ screen=WeakReference(a);active=true;progressHidden=false;resume(a) }
    fun onPause(a:MainActivity){ if(screen.get()===a){active=false;handler.removeCallbacks(poll);dismiss()} }
    fun detach(a:MainActivity){onPause(a);if(screen.get()===a)screen.clear()}
    fun checkForUpdate(a:MainActivity){
        screen=WeakReference(a)
        if(prefs(a).contains("id")){resume(a);return}
        if(!checking.compareAndSet(false,true))return
        Thread {
            try {
                val conn=URL(VERSION_URL).openConnection() as HttpURLConnection
                conn.connectTimeout=8000;conn.readTimeout=8000
                val data=try{conn.inputStream.bufferedReader().use{JSONObject(it.readText())}}finally{conn.disconnect()}
                val code=data.getInt("versionCode");val name=data.getString("versionName");val url=data.getString("apkUrl")
                if(code>BuildConfig.VERSION_CODE && trustedUrl(url)) handler.post {
                    if(foreground(a) && !prefs(a).contains("id")) AlertDialog.Builder(a).setTitle(t(a,"Update available · $name","புதிய பதிப்பு உள்ளது · $name"))
                        .setMessage(t(a,"Download the update, then confirm Install in Android. CRM stays available if downloading fails.","புதுப்பிப்பைப் பதிவிறக்கி, Android-இல் Install என்பதை உறுதிசெய்யவும். பதிவிறக்கம் தோல்வியடைந்தாலும் CRM-ஐத் தொடர்ந்து பயன்படுத்தலாம்."))
                        .setPositiveButton(t(a,"Update","புதுப்பி")){_,_->begin(a,code,name,url)}.setNegativeButton(t(a,"Later","பிறகு"),null)
                        .setNeutralButton(t(a,"Open in browser","உலாவியில் திற")){_,_->browser(a,url)}.show()
                }
            }catch(_:Exception){ /* A background update check must not block CRM. */ }
            finally{checking.set(false)}
        }.start()
    }
    fun apkNavigation(a:MainActivity,url:String):Boolean {
        if(!UpdatePolicy.webApk(url))return false
        browser(a,url);return true // APK bytes must never replace the CRM WebView document.
    }
    fun browser(a:MainActivity,url:String){
        if(Uri.parse(url).scheme !in listOf("https","http"))return
        try{a.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))}
        catch(_:Exception){Toast.makeText(a,t(a,"Open Chrome and download the update from the APS Connect GitHub release page.","Chrome-ஐத் திறந்து, APS Connect GitHub வெளியீட்டுப் பக்கத்திலிருந்து புதுப்பிப்பைப் பதிவிறக்கவும்."),Toast.LENGTH_LONG).show()}
    }
    private fun trustedUrl(url:String):Boolean=runCatching{val u=Uri.parse(url);u.scheme=="https" && u.host=="github.com" && u.path?.startsWith("/sakthi1993v-glitch/aps-auto-dial-app/releases/download/")==true}.getOrDefault(false)
    private fun begin(a:MainActivity,code:Int,name:String,url:String){
        dismiss();progressHidden=false
        try{
            val dm=a.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            prefs(a).getLong("id",-1).takeIf{it>=0}?.let{dm.remove(it)}
            val file=File(a.getExternalFilesDir(null)?:throw IllegalStateException(t(a,"Phone storage unavailable","போன் சேமிப்பிடம் கிடைக்கவில்லை")),"downloads/aps-update-$code-${System.currentTimeMillis()}.apk")
            file.parentFile?.mkdirs()
            val request=DownloadManager.Request(Uri.parse(url)).setTitle("APS Connect $name update")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true).setDestinationUri(Uri.fromFile(file))
            val id=dm.enqueue(request)
            prefs(a).edit().putLong("id",id).putInt("code",code).putString("name",name).putString("url",url)
                .putString("file",file.absolutePath).putString("phase","download").putLong("started",System.currentTimeMillis()).apply()
            query(a) // No receiver registration race; also recovers an already-completed download.
        }catch(_:Exception){error(a,t(a,"Update download could not start. Check internet/storage or use Open in browser.","புதுப்பிப்புப் பதிவிறக்கம் தொடங்கவில்லை. இணையம்/சேமிப்பிடத்தைச் சரிபார்க்கவும் அல்லது உலாவியில் திறக்கவும்."))}
    }
    private fun resume(a:MainActivity){
        val p=prefs(a);if(!p.contains("id"))return
        if(p.getInt("code",0)<=BuildConfig.VERSION_CODE){
            runCatching{(a.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(p.getLong("id",-1))};p.edit().clear().apply();return
        }
        when(UpdatePolicy.resumeAction(p.getString("phase","")?:"",canInstall(a))){
            "install"->validateAndInstall(a)
            "permission"->permission(a)
            "retry-choice"->{if(usable(a))showRetryInstall(a)}
            else->query(a)
        }
    }
    private fun query(a:MainActivity){
        if(!usable(a))return
        val p=prefs(a);val id=p.getLong("id",-1);if(id<0)return
        try{
            val dm=a.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            var status:Int?=null;var total=0L;var bytes=0L;var reason=0
            dm.query(DownloadManager.Query().setFilterById(id))?.use{c->if(c.moveToFirst()){
                status=c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));reason=c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                bytes=c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));total=c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            }}
            when(UpdatePolicy.state(status,System.currentTimeMillis()-p.getLong("started",System.currentTimeMillis()))){
                "ready"->{handler.removeCallbacks(poll);dismiss();if(validating.get()){if(active)handler.postDelayed(poll,1000)}else validateAndInstall(a)}
                "failed"->{handler.removeCallbacks(poll);error(a,t(a,"Download failed (Android code $reason). Check internet and storage, then Retry or open the APK in Chrome.","பதிவிறக்கம் தோல்வியடைந்தது (Android குறியீடு $reason). இணையம் மற்றும் சேமிப்பிடத்தைச் சரிபார்த்து, மீண்டும் முயற்சிக்கவும் அல்லது Chrome-இல் APK-ஐத் திறக்கவும்."))}
                "missing"->{handler.removeCallbacks(poll);error(a,t(a,"The saved update download is unavailable. Retry the download or use Chrome.","சேமித்த புதுப்பிப்புப் பதிவிறக்கம் கிடைக்கவில்லை. மீண்டும் பதிவிறக்கவும் அல்லது Chrome-ஐப் பயன்படுத்தவும்."))}
                "stalled"->{handler.removeCallbacks(poll);error(a,t(a,"The download has not finished after five minutes. Retry on a working connection or use Chrome.","ஐந்து நிமிடங்களுக்குப் பிறகும் பதிவிறக்கம் முடியவில்லை. நல்ல இணைப்பில் மீண்டும் முயற்சிக்கவும் அல்லது Chrome-ஐப் பயன்படுத்தவும்."))}
                else->{showProgress(a,bytes,total,status==DownloadManager.STATUS_PAUSED);handler.removeCallbacks(poll);if(active)handler.postDelayed(poll,1000)}
            }
        }catch(_:Exception){error(a,t(a,"Android could not read the update download status. Retry or use Chrome.","புதுப்பிப்புப் பதிவிறக்க நிலையை Android-ஆல் படிக்க முடியவில்லை. மீண்டும் முயற்சிக்கவும் அல்லது Chrome-ஐப் பயன்படுத்தவும்."))}
    }
    private fun showProgress(a:MainActivity,bytes:Long,total:Long,paused:Boolean){
        if(!active || progressHidden)return
        if(dialog==null){
            val body=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL;setPadding(32,20,32,16)}
            progressText=TextView(a).also{body.addView(it)}
            progress=ProgressBar(a,null,android.R.attr.progressBarStyleHorizontal).also{body.addView(it)}
            dialog=AlertDialog.Builder(a).setTitle(t(a,"Downloading APS Connect update","APS Connect புதுப்பிப்பு பதிவிறக்கப்படுகிறது")).setView(body).setCancelable(false)
                .setNegativeButton(t(a,"Keep using CRM","CRM-ஐத் தொடர்ந்து பயன்படுத்து")){_,_->progressHidden=true;dismiss()}
                .setNeutralButton(t(a,"Open in browser","உலாவியில் திற")){_,_->progressHidden=true;browser(a,prefs(a).getString("url","")?:"");dismiss()}.create()
            dialog?.show()
        }
        progress?.isIndeterminate=total<=0
        if(total>0)progress?.progress=((bytes*100/total).coerceIn(0,100)).toInt()
        progressText?.text=if(paused)t(a,"Android is waiting for a network connection. You can keep using CRM.","நெட்வொர்க் இணைப்புக்காக Android காத்திருக்கிறது. நீங்கள் CRM-ஐத் தொடர்ந்து பயன்படுத்தலாம்.") else if(total>0)"${bytes/1024} / ${total/1024} KB" else t(a,"${bytes/1024} KB downloaded…","${bytes/1024} KB பதிவிறக்கப்பட்டது…")
    }
    private fun canInstall(a:MainActivity)=Build.VERSION.SDK_INT<26 || a.packageManager.canRequestPackageInstalls()
    private fun permission(a:MainActivity){
        if(!usable(a))return;dismiss()
        dialog=AlertDialog.Builder(a).setTitle(t(a,"Allow this app to install the update","புதுப்பிப்பை நிறுவ இந்தச் செயலிக்கு அனுமதி வழங்கவும்"))
            .setMessage(t(a,"The APK downloaded successfully. In Android, enable Allow from this source for APS Connect, then return here. The installer will resume. Android still asks you to tap Install.","APK வெற்றிகரமாகப் பதிவிறக்கப்பட்டது. Android-இல் APS Connect-க்கு Allow from this source என்பதை இயக்கி, இங்கு திரும்பவும். நிறுவல் தொடரும். Install என்பதை நீங்கள் தொட வேண்டும்."))
            .setPositiveButton(t(a,"Open Android settings","Android அமைப்புகளைத் திற")){_,_->prefs(a).edit().putString("phase","permission").apply();try{a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:${a.packageName}")))}catch(_:Exception){error(a,t(a,"Open Android Settings → Apps → Special app access → Install unknown apps → APS Connect.","Android அமைப்புகள் → Apps → Special app access → Install unknown apps → APS Connect என்பதைத் திறக்கவும்."))}}
            .setNegativeButton(t(a,"Later","பிறகு")){_,_->prefs(a).edit().putString("phase","installer").apply();dismiss()}
            .setNeutralButton(t(a,"Open in browser","உலாவியில் திற")){_,_->browser(a,prefs(a).getString("url","")?:"")}.create();dialog?.show()
    }
    private fun showRetryInstall(a:MainActivity){
        dismiss();dialog=AlertDialog.Builder(a).setTitle(t(a,"Downloaded update is ready","பதிவிறக்கிய புதுப்பிப்பு தயார்"))
            .setMessage(t(a,"If Android's installer was canceled, tap Install again. Your CRM data has not been deleted.","Android நிறுவல் ரத்தானால் மீண்டும் Install என்பதைத் தொடவும். உங்கள் CRM தரவு நீக்கப்படவில்லை."))
            .setPositiveButton(t(a,"Install again","மீண்டும் நிறுவு")){_,_->validateAndInstall(a)}.setNegativeButton(t(a,"Later","பிறகு")){_,_->dismiss()}
            .setNeutralButton(t(a,"Open in browser","உலாவியில் திற")){_,_->browser(a,prefs(a).getString("url","")?:"")}.create();dialog?.show()
    }
    private fun validateAndInstall(a:MainActivity){
        if(!validating.compareAndSet(false,true))return
        val app=a.applicationContext;val p=prefs(app)
        Thread{
            try{
                val download=File(p.getString("file","")?:"")
                if(!download.isFile || download.length()<100000)throw IllegalStateException(t(app,"Downloaded APK is missing or incomplete","பதிவிறக்கிய APK இல்லை அல்லது முழுமையடையவில்லை"))
                val dir=File(app.cacheDir,"verified_updates").apply{mkdirs()}
                val file=File(dir,"aps-update-${p.getInt("code",0)}.apk")
                download.copyTo(file,overwrite=true)
                val flags=if(Build.VERSION.SDK_INT>=28)PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
                val archive=app.packageManager.getPackageArchiveInfo(file.absolutePath,flags)?:throw IllegalStateException(t(app,"Download is not a valid Android APK","பதிவிறக்கம் சரியான Android APK அல்ல"))
                val installed=app.packageManager.getPackageInfo(app.packageName,flags)
                if(!UpdatePolicy.archiveAllowed(archive.packageName,app.packageName,version(archive),p.getInt("code",0).toLong(),BuildConfig.VERSION_CODE.toLong(),signers(archive),signers(installed)))throw IllegalStateException(t(app,"APK package, version or signing certificate did not match this app","APK தொகுப்பு, பதிப்பு அல்லது கையொப்பச் சான்றிதழ் இந்தச் செயலியுடன் பொருந்தவில்லை"))
                handler.post{if(foreground(a)){
                    dismiss()
                    if(!canInstall(a)){p.edit().putString("phase","permission").apply();permission(a)}
                    else{
                        val uri=FileProvider.getUriForFile(a,"com.apsconnect.autodial.fileprovider",file)
                        p.edit().putString("phase","installer").apply()
                        try{a.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))}
                        catch(_:Exception){error(a,t(a,"Android's APK installer could not open. Retry or open the download in Chrome.","Android APK நிறுவி திறக்கவில்லை. மீண்டும் முயற்சிக்கவும் அல்லது Chrome-இல் பதிவிறக்கத்தைத் திறக்கவும்."))}
                    }
                }}
            }catch(e:Exception){handler.post{if(foreground(a))error(a,e.message?:"APK validation failed. Download again.")}}
            finally{validating.set(false)}
        }.start()
    }
    @Suppress("DEPRECATION") private fun signers(info:PackageInfo):Set<String>{
        val list=if(Build.VERSION.SDK_INT>=28)info.signingInfo?.apkContentsSigners else info.signatures
        return list?.map{MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString(""){b->"%02x".format(b)}}?.toSet()?:emptySet()
    }
    @Suppress("DEPRECATION") private fun version(info:PackageInfo)=if(Build.VERSION.SDK_INT>=28)info.longVersionCode else info.versionCode.toLong()
    private fun error(a:MainActivity,message:String){
        if(!usable(a))return;dismiss()
        val p=prefs(a)
        dialog=AlertDialog.Builder(a).setTitle(t(a,"Update could not finish","புதுப்பிப்பை முடிக்க முடியவில்லை")).setMessage(message)
            .setPositiveButton(t(a,"Retry download","மீண்டும் பதிவிறக்கு")){_,_->val url=p.getString("url","")?:"";if(trustedUrl(url))begin(a,p.getInt("code",0),p.getString("name","")?:"",url)else{p.edit().clear().apply();checkForUpdate(a)}}
            .setNegativeButton(t(a,"Use CRM","CRM-ஐப் பயன்படுத்து")){_,_->dismiss()}
            .setNeutralButton(t(a,"Open in browser","உலாவியில் திற")){_,_->browser(a,p.getString("url","")?:"https://github.com/sakthi1993v-glitch/aps-auto-dial-app/releases/latest")}.create();dialog?.show()
    }
}
