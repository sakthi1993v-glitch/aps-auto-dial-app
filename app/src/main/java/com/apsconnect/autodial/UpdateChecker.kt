package com.apsconnect.autodial

import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

// Auto-update (2026-08-05): no Play Store, so the app checks a small version.json file
// (committed to the repo's main branch) every launch. Newer version found -> download the
// APK via DownloadManager -> prompt install via the system package installer. Android still
// requires the final "Install" tap (no app can silently self-install without root/device-
// owner privileges) -- this is the closest to fully-automatic that's possible.
object UpdateChecker {

    private const val VERSION_JSON_URL =
        "https://raw.githubusercontent.com/sakthi1993v-glitch/aps-auto-dial-app/main/version.json"

    fun checkForUpdate(activity: MainActivity) {
        Thread {
            try {
                val json = fetchVersionJson()
                val remoteVersionCode = json.getInt("versionCode")
                val remoteVersionName = json.getString("versionName")
                val apkUrl = json.getString("apkUrl")

                if (remoteVersionCode > BuildConfig.VERSION_CODE) {
                    Handler(Looper.getMainLooper()).post {
                        promptUpdate(activity, remoteVersionName, apkUrl)
                    }
                }
            } catch (e: Exception) {
                // Network illa / version.json illa -> silent-a skip pannanum, app normal-a
                // vela seyyanum (update check fail aanadhu-nala app use panna koodathu).
            }
        }.start()
    }

    private fun fetchVersionJson(): JSONObject {
        val conn = URL(VERSION_JSON_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.requestMethod = "GET"
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        return JSONObject(text)
    }

    private fun promptUpdate(activity: MainActivity, versionName: String, apkUrl: String) {
        AlertDialog.Builder(activity)
            .setTitle("Update available")
            .setMessage("Puthu version ($versionName) irukku. Ippo download pannalama?")
            .setPositiveButton("Update") { _, _ -> downloadAndInstall(activity, apkUrl) }
            .setNegativeButton("Later", null)
            .setCancelable(true)
            .show()
    }

    private fun downloadAndInstall(activity: MainActivity, apkUrl: String) {
        val downloadDir = File(activity.getExternalFilesDir(null), "downloads")
        if (!downloadDir.exists()) downloadDir.mkdirs()
        val apkFile = File(downloadDir, "app-update.apk")
        if (apkFile.exists()) apkFile.delete()

        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle("APS Auto Dial update")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(apkFile))

        val downloadManager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = downloadManager.enqueue(request)
        Toast.makeText(activity, "Download aaguthu...", Toast.LENGTH_SHORT).show()

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    activity.unregisterReceiver(this)
                    installApk(activity, apkFile)
                }
            }
        }
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            activity.registerReceiver(receiver, filter)
        }
    }

    private fun installApk(activity: MainActivity, apkFile: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            Toast.makeText(
                activity,
                "Settings-la 'Install unknown apps' allow pannunga, apparam update install aagum",
                Toast.LENGTH_LONG
            ).show()
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .setData(Uri.parse("package:" + activity.packageName))
            activity.startActivity(intent)
            return
        }

        val uri = FileProvider.getUriForFile(
            activity, "com.apsconnect.autodial.fileprovider", apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(intent)
    }
}
