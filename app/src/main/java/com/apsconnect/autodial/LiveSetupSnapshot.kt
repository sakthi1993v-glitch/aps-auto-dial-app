package com.apsconnect.autodial

/** Deliberately contains no subscription ID, ICCID, phone number, device ID or cookie. */
data class LiveSetupSnapshot(
    val slot: Int?, val carrier: String?, val selected: Boolean, val needsConfirmation: Boolean,
    val phone: Boolean, val callLog: Boolean, val callPhone: Boolean,
    val notificationPermission: Boolean, val notificationsEnabled: Boolean, val callChannelEnabled: Boolean,
    val fullScreen: Boolean?, val defaultPhone: Boolean, val liveReporting: Boolean, val appVersion: String,
    val directDialReady: Boolean = false, val officeHandleMatched: Boolean = false
) {
    fun wire(): Map<String, Any?> = linkedMapOf(
        "supported" to true,
        "officeSim" to linkedMapOf("slot" to slot, "carrier" to carrier, "selected" to selected, "needsConfirmation" to needsConfirmation),
        "permissions" to linkedMapOf("phone" to phone, "callLog" to callLog, "callPhone" to callPhone,
            "notifications" to (notificationPermission && notificationsEnabled && callChannelEnabled),
            "notificationPermission" to notificationPermission, "notificationsEnabled" to notificationsEnabled,
            "callChannelEnabled" to callChannelEnabled, "fullScreen" to fullScreen),
        "defaultPhone" to defaultPhone, "liveReporting" to liveReporting, "appVersion" to appVersion,
        "autoDial" to linkedMapOf("ready" to directDialReady, "officeHandleMatched" to officeHandleMatched)
    )
}
