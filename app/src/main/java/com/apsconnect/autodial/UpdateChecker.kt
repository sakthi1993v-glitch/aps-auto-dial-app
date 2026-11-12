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
                    if(foreground(a) && !prefs(a).contains("id")) AlertDialog.Builder(a).setTitle("Update available · $name")
                        .setMessage("Download the update, then confirm Install in Android. CRM stays available if downloading fails.")
                        .setPositiveButton("Update"){_,_->begin(a,code,name,url)}.setNegativeButton("Later",null)
                        .setNeutralButton("Open in browser"){_,_->browser(a,url)}.show()
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
        catch(_:Exception){Toast.makeText(a,"Open Chrome and download the update from the APS Connect GitHub release page.",Toast.LENGTH_LONG).show()}
    }
    private fun trustedUrl(url:String):Boolean=runCatching{val u=Uri.parse(url);u.scheme=="https" && u.host=="github.com" && u.path?.startsWith("/sakthi1993v-glitch/aps-auto-dial-app/releases/download/")==true}.getOrDefault(false)
    private fun begin(a:MainActivity,code:Int,name:String,url:String){
        dismiss();progressHidden=false
        try{
            val dm=a.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            prefs(a).getLong("id",-1).takeIf{it>=0}?.let{dm.remove(it)}
            val file=File(a.getExternalFilesDir(null)?:throw IllegalStateException("Phone storage unavailable"),"downloads/aps-update-$code-${System.currentTimeMillis()}.apk")
            file.parentFile?.mkdirs()
            val request=DownloadManager.Request(Uri.parse(url)).setTitle("APS Connect $name update")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true).setDestinationUri(Uri.fromFile(file))
            val id=dm.enqueue(request)
            prefs(a).edit().putLong("id",id).putInt("code",code).putString("name",name).putString("url",url)
                .putString("file",file.absolutePath).putString("phase","download").putLong("started",System.currentTimeMillis()).apply()
            query(a) // No receiver registration race; also recovers an already-completed download.
        }catch(_:Exception){error(a,"Update download could not start. Check internet/storage or use Open in browser.")}
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
                "failed"->{handler.removeCallbacks(poll);error(a,"Download failed (Android code $reason). Check internet and storage, then Retry or open the APK in Chrome.")}
                "missing"->{handler.removeCallbacks(poll);error(a,"The saved update download is unavailable. Retry the download or use Chrome.")}
                "stalled"->{handler.removeCallbacks(poll);error(a,"The download has not finished after five minutes. Retry on a working connection or use Chrome.")}
                else->{showProgress(a,bytes,total,status==DownloadManager.STATUS_PAUSED);handler.removeCallbacks(poll);if(active)handler.postDelayed(poll,1000)}
            }
        }catch(_:Exception){error(a,"Android could not read the update download status. Retry or use Chrome.")}
    }
    private fun showProgress(a:MainActivity,bytes:Long,total:Long,paused:Boolean){
        if(!active || progressHidden)return
        if(dialog==null){
            val body=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL;setPadding(32,20,32,16)}
            progressText=TextView(a).also{body.addView(it)}
            progress=ProgressBar(a,null,android.R.attr.progressBarStyleHorizontal).also{body.addView(it)}
            dialog=AlertDialog.Builder(a).setTitle("Downloading APS Connect update").setView(body).setCancelable(false)
                .setNegativeButton("Keep using CRM"){_,_->progressHidden=true;dismiss()}
                .setNeutralButton("Open in browser"){_,_->progressHidden=true;browser(a,prefs(a).getString("url","")?:"");dismiss()}.create()
            dialog?.show()
        }
        progress?.isIndeterminate=total<=0
        if(total>0)progress?.progress=((bytes*100/total).coerceIn(0,100)).toInt()
        progressText?.text=if(paused)"Android is waiting for a network connection. You can keep using CRM." else if(total>0)"${bytes/1024} / ${total/1024} KB" else "${bytes/1024} KB downloaded…"
    }
    private fun canInstall(a:MainActivity)=Build.VERSION.SDK_INT<26 || a.packageManager.canRequestPackageInstalls()
    private fun permission(a:MainActivity){
        if(!usable(a))return;dismiss()
        dialog=AlertDialog.Builder(a).setTitle("Allow this app to install the update")
            .setMessage("The APK downloaded successfully. In Android, enable Allow from this source for APS Connect, then return here. The installer will resume. Android still asks you to tap Install.")
            .setPositiveButton("Open Android settings"){_,_->prefs(a).edit().putString("phase","permission").apply();try{a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:${a.packageName}")))}catch(_:Exception){error(a,"Open Android Settings → Apps → Special app access → Install unknown apps → APS Connect.")}}
            .setNegativeButton("Later"){_,_->prefs(a).edit().putString("phase","installer").apply();dismiss()}
            .setNeutralButton("Open in browser"){_,_->browser(a,prefs(a).getString("url","")?:"")}.create();dialog?.show()
    }
    private fun showRetryInstall(a:MainActivity){
        dismiss();dialog=AlertDialog.Builder(a).setTitle("Downloaded update is ready")
            .setMessage("If Android's installer was canceled, tap Install again. Your CRM data has not been deleted.")
            .setPositiveButton("Install again"){_,_->validateAndInstall(a)}.setNegativeButton("Later"){_,_->dismiss()}
            .setNeutralButton("Open in browser"){_,_->browser(a,prefs(a).getString("url","")?:"")}.create();dialog?.show()
    }
    private fun validateAndInstall(a:MainActivity){
        if(!validating.compareAndSet(false,true))return
        val app=a.applicationContext;val p=prefs(app)
        Thread{
            try{
                val download=File(p.getString("file","")?:"")
                if(!download.isFile || download.length()<100000)throw IllegalStateException("Downloaded APK is missing or incomplete")
                val dir=File(app.cacheDir,"verified_updates").apply{mkdirs()}
                val file=File(dir,"aps-update-${p.getInt("code",0)}.apk")
                download.copyTo(file,overwrite=true)
                val flags=if(Build.VERSION.SDK_INT>=28)PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
                val archive=app.packageManager.getPackageArchiveInfo(file.absolutePath,flags)?:throw IllegalStateException("Download is not a valid Android APK")
                val installed=app.packageManager.getPackageInfo(app.packageName,flags)
                if(!UpdatePolicy.archiveAllowed(archive.packageName,app.packageName,version(archive),p.getInt("code",0).toLong(),BuildConfig.VERSION_CODE.toLong(),signers(archive),signers(installed)))throw IllegalStateException("APK package, version or signing certificate did not match this app")
                handler.post{if(foreground(a)){
                    dismiss()
                    if(!canInstall(a)){p.edit().putString("phase","permission").apply();permission(a)}
                    else{
                        val uri=FileProvider.getUriForFile(a,"com.apsconnect.autodial.fileprovider",file)
                        p.edit().putString("phase","installer").apply()
                        try{a.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))}
                        catch(_:Exception){error(a,"Android's APK installer could not open. Retry or open the download in Chrome.")}
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
        dialog=AlertDialog.Builder(a).setTitle("Update could not finish").setMessage(message)
            .setPositiveButton("Retry download"){_,_->val url=p.getString("url","")?:"";if(trustedUrl(url))begin(a,p.getInt("code",0),p.getString("name","")?:"",url)else{p.edit().clear().apply();checkForUpdate(a)}}
            .setNegativeButton("Use CRM"){_,_->dismiss()}
            .setNeutralButton("Open in browser"){_,_->browser(a,p.getString("url","")?:"https://github.com/sakthi1993v-glitch/aps-auto-dial-app/releases/latest")}.create();dialog?.show()
    }
}
