package com.apsconnect.autodial

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.CallLog
import android.telecom.TelecomManager
import android.net.Uri
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/** ACTION_DIAL entry with dial pad and recent history required by the Phone role. */
class DialerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24,32,24,24) }
        val number = EditText(this).apply { inputType = android.text.InputType.TYPE_CLASS_PHONE; setText(intent.data?.schemeSpecificPart ?: ""); hint = "Phone number" }
        body.addView(TextView(this).apply { text = "APS Connect Phone"; textSize = 24f }); body.addView(number)
        for (row in listOf("123","456","789","*0#")) {
            val line = LinearLayout(this)
            row.forEach { d -> line.addView(Button(this).apply { text = d.toString(); setOnClickListener { number.append(d.toString()) } },LinearLayout.LayoutParams(0,80,1f)) }
            body.addView(line)
        }
        body.addView(Button(this).apply { text = "Call"; setOnClickListener {
            val raw = number.text.toString().trim()
            if (raw.isNotEmpty()) {
                if (ContextCompat.checkSelfPermission(this@DialerActivity, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) ActivityCompat.requestPermissions(this@DialerActivity, arrayOf(Manifest.permission.CALL_PHONE),90)
                else try { (getSystemService(TELECOM_SERVICE) as TelecomManager).placeCall(Uri.fromParts("tel",raw,null), Bundle()) } catch (_: SecurityException) { }
            }
        } })
        body.addView(Button(this).apply { text = "Live reporting setup"; setOnClickListener { startActivity(Intent(this@DialerActivity,LiveCallSetupActivity::class.java)) } })
        body.addView(TextView(this).apply { text = "Recent calls"; textSize = 20f })
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED) {
            try { contentResolver.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.NUMBER,CallLog.Calls.TYPE,CallLog.Calls.DURATION),null,null,"${CallLog.Calls.DATE} DESC")?.use { c ->
                var n=0
                while (n++ < 20 && c.moveToNext()) body.addView(Button(this).apply { val phone = c.getString(0) ?: ""; text = "$phone · ${c.getLong(2)}s"; setOnClickListener { number.setText(phone) } })
            } } catch (_: SecurityException) { }
        } else body.addView(TextView(this).apply { text = "Call-log permission is required to show recent calls." })
        setContentView(ScrollView(this).apply { addView(body) })
    }
}
