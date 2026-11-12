package com.apsconnect.autodial

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.VideoProfile
import android.telecom.TelecomManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Incoming and ongoing controls remain usable even when CRM/network is unavailable. */
class InCallActivity : Activity() {
    private val onChanged: () -> Unit = { runOnUiThread { render() } }
    private lateinit var content: LinearLayout
    private var muted = false
    private var speaker = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24,40,24,24); gravity = Gravity.CENTER_HORIZONTAL }
        setContentView(ScrollView(this).apply { addView(content) }); render()
    }
    override fun onResume() { super.onResume(); LiveInCallService.listeners.add(onChanged); render() }
    override fun onPause() { LiveInCallService.listeners.remove(onChanged); super.onPause() }
    private fun button(label: String, action: () -> Unit) { content.addView(Button(this).apply { text = label; setOnClickListener { try { action() } catch (_: SecurityException) { } } }) }
    private fun render() {
        content.removeAllViews()
        val calls = LiveInCallService.snapshot()
        if (calls.isEmpty()) { finish(); return }
        for ((call, _) in calls) {
            val number = if (call.details.handlePresentation == TelecomManager.PRESENTATION_ALLOWED) call.details.handle?.schemeSpecificPart ?: "Unknown number" else "Private number"
            content.addView(TextView(this).apply { text = number; textSize = 26f })
            content.addView(TextView(this).apply { text = when(call.state) { Call.STATE_ACTIVE -> "Connected"; Call.STATE_RINGING -> "Incoming call"; Call.STATE_DIALING, Call.STATE_CONNECTING -> "Dialing"; Call.STATE_HOLDING -> "On hold"; else -> "Call state updating" } })
            if (call.state == Call.STATE_RINGING) {
                button("Answer audio") { call.answer(VideoProfile.STATE_AUDIO_ONLY) }
                button("Decline") { call.reject(false, null) }
            } else button("End call") { call.disconnect() }
            if (call.details.can(Call.Details.CAPABILITY_HOLD)) {
                if (call.state == Call.STATE_HOLDING) button("Resume call") { call.unhold() }
                else if (call.state == Call.STATE_ACTIVE) button("Hold call") { call.hold() }
            }
            if (call.state == Call.STATE_ACTIVE) {
                val pad = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                for (row in listOf("123","456","789","*0#")) {
                    val line = LinearLayout(this)
                    row.forEach { digit -> line.addView(Button(this).apply { text = digit.toString(); setOnTouchListener { _, event -> when(event.action) { MotionEvent.ACTION_DOWN -> call.playDtmfTone(digit); MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> call.stopDtmfTone() }; true } }, LinearLayout.LayoutParams(0,80,1f)) }
                    pad.addView(line)
                }
                content.addView(pad)
            }
        }
        button(if (muted) "Unmute" else "Mute") { muted = !muted; LiveInCallService.current?.setMuted(muted); render() }
        button(if (speaker) "Earpiece" else "Speaker") { speaker = !speaker; LiveInCallService.current?.setAudioRoute(if(speaker) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_EARPIECE); render() }
        if ((LiveInCallService.current?.callAudioState?.supportedRouteMask ?: 0) and CallAudioState.ROUTE_BLUETOOTH != 0) button("Bluetooth") { LiveInCallService.current?.setAudioRoute(CallAudioState.ROUTE_BLUETOOTH) }
        button("Open CRM") { startActivity(Intent(this, MainActivity::class.java)) }
    }
}
