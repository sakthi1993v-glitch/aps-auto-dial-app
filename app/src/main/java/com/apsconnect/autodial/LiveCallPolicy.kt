package com.apsconnect.autodial

/** Pure mapping: OFFHOOK is never represented as an answered call. */
object LiveCallPolicy {
    fun phase(state: Int): String = when (state) {
        1 -> "dialing" // Call.STATE_DIALING
        2, 13 -> "ringing"
        8, 9, 11 -> "dialing" // connecting / select account / pulling
        4 -> "connected" // Call.STATE_ACTIVE only
        7 -> "ended"
        3, 10 -> "offhook" // holding / disconnecting, not active talk
        else -> "unknown"
    }
    fun coarseOfficeAllowed(hold: Boolean, chosen: Boolean, activeSims: Int) = !hold && chosen && activeSims == 1
    fun mayShareNumber(officeMatched: Boolean, presentationAllowed: Boolean) = officeMatched && presentationAllowed
    fun acknowledged(status: Int, accepted: Boolean) = status in 200..299 && accepted
    fun fresh(observedAt: Long, now: Long) = now - observedAt in 0..45000L
    fun connectionTime(state: Int, connectedAt: Long, now: Long): Long? =
        if (state == 4 && connectedAt in 1..now) connectedAt else null
}

/** Prevent an old active call's details being assigned to a newly logged-in user. */
class LiveIdentityGuard {
    private var blocked: String? = null
    @Synchronized fun accountChanged(identity: String?) { blocked = identity ?: "coarse-call" }
    @Synchronized fun mayShare(identity: String?): Boolean = blocked == null || blocked != (identity ?: "coarse-call")
    @Synchronized fun callFinished() { blocked = null }
}

/** Compare only the authenticated session, not rotating Cloudflare/pref cookies. */
object LiveCookiePolicy {
    fun authToken(header: String): String? = header.split(';').map { it.trim() }.firstOrNull { it.startsWith("wa_sess=") }?.substringAfter('=')?.takeIf { it.isNotEmpty() }
    fun sameIdentity(before: String, after: String): Boolean = authToken(before)?.let { it == authToken(after) } ?: false
}

object LiveBridgePolicy {
    fun allowed(scheme: String?, host: String?, path: String?, mainFrame: Boolean): Boolean = mainFrame && scheme == "https" && host == "agent.adiparasakthicharitabletrust.in" && (path == "/" || path == "/crm" || path?.startsWith("/crm/") == true || path == "/login")
}
