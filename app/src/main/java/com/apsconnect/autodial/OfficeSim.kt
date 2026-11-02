package com.apsconnect.autodial

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat

// v1.10: staff may carry a personal SIM and an office SIM. Only the OFFICE SIM's calls are
// reported/counted. This object lists the active SIMs, stores the staff's one-time (changeable)
// choice, and maps a call-log row's PHONE_ACCOUNT_ID to "chosen SIM / other SIM / unknown".
// Privacy: we never read or store the SIM's own phone number; labels use slot + carrier + the
// last 4 characters of the SIM's ICC id (or subscription id when the ICC id is hidden).
object OfficeSim {

    private const val TAG = "OfficeSim"
    private const val PREFS = "office_sim"

    data class Sim(
        val subId: Int,
        val slot: Int,          // 0-based slot index
        val carrier: String,
        val iccId: String,      // may be "" (hidden on API 29+ for non-privileged apps)
        val accountIds: Set<String>   // values the call log may put in PHONE_ACCOUNT_ID
    ) {
        val maskedLabel: String
            get() = "SIM ${slot + 1} - $carrier (" +
                (if (iccId.length >= 4) "..." + iccId.takeLast(4) else "id $subId") + ")"
    }

    private fun hasPhoneState(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

    // PhoneAccountHandle -> subscription id (API 30+), or null when it cannot be resolved.
    private fun handleSubId(context: Context, h: PhoneAccountHandle): Int? {
        if (Build.VERSION.SDK_INT < 30) return null
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val id = tm.getSubscriptionId(h)
            if (id >= 0) id else null
        } catch (e: Exception) {
            null
        }
    }

    private fun callCapableHandles(context: Context): List<PhoneAccountHandle> {
        if (Build.VERSION.SDK_INT < 23 || !hasPhoneState(context)) return emptyList()
        return try {
            val tc = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            tc.callCapablePhoneAccounts ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "callCapablePhoneAccounts: ${e.message}")
            emptyList()
        }
    }

    // Active SIMs, sorted by slot. Empty when permission is missing, the list is null/empty, or
    // anything throws (callers treat "empty" as "cannot tell", never as a crash).
    fun activeSims(context: Context): List<Sim> {
        if (!hasPhoneState(context)) return emptyList()
        return try {
            val sm = context.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
            val subs = sm.activeSubscriptionInfoList ?: return emptyList()
            val handles = callCapableHandles(context)
            subs.map { s ->
                val ids = HashSet<String>()
                ids.add(s.subscriptionId.toString())
                val icc = s.iccId ?: ""
                if (icc.isNotEmpty()) ids.add(icc)
                for (h in handles) if (handleSubId(context, h) == s.subscriptionId) ids.add(h.id)
                Sim(s.subscriptionId, s.simSlotIndex, (s.carrierName ?: "").toString().trim().ifEmpty { "SIM" }, icc, ids)
            }.sortedBy { it.slot }
        } catch (e: Exception) {
            Log.w(TAG, "activeSims failed: ${e.message}")
            emptyList()
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // Stores subscriptionId + slotIndex + carrier + the identifiers the call log uses.
    fun choose(context: Context, sim: Sim) {
        prefs(context).edit()
            .putInt("subId", sim.subId)
            .putInt("slot", sim.slot)
            .putString("carrier", sim.carrier)
            .putString("iccId", sim.iccId)
            .putStringSet("accountIds", sim.accountIds)
            .apply()
    }

    // The saved choice, but only if that SIM is still active (same subId, else same ICC id).
    // Returns the CURRENT Sim object (fresh slot / account ids), or null.
    fun chosen(context: Context, active: List<Sim> = activeSims(context)): Sim? {
        val p = prefs(context)
        if (!p.contains("subId")) return null
        val subId = p.getInt("subId", -1)
        val icc = p.getString("iccId", "") ?: ""
        return active.firstOrNull { it.subId == subId }
            ?: active.firstOrNull { icc.isNotEmpty() && it.iccId == icc }
    }

    sealed class State {
        object Unknown : State()                       // cannot list SIMs: do not filter
        data class Ready(val sim: Sim?, val multi: Boolean) : State()   // sim == null && multi -> must choose
    }

    // Resolves what to do now. Single active SIM is auto-selected (and saved) so the UI can still
    // show which one it is.
    fun state(context: Context): State {
        val active = activeSims(context)
        if (active.isEmpty()) return State.Unknown
        if (active.size == 1) {
            val only = active[0]
            if (chosen(context, active) == null) choose(context, only)
            return State.Ready(only, false)
        }
        return State.Ready(chosen(context, active), true)
    }

    fun needsChoice(context: Context): Boolean {
        val s = state(context)
        return s is State.Ready && s.multi && s.sim == null
    }

    enum class Match { CHOSEN, OTHER, UNKNOWN }

    // Classify a call-log row by its PHONE_ACCOUNT_ID.
    fun classify(accountId: String?, chosen: Sim, active: List<Sim>): Match {
        if (accountId.isNullOrEmpty()) return Match.UNKNOWN
        if (accountId in chosen.accountIds) return Match.CHOSEN
        if (active.any { it.subId != chosen.subId && accountId in it.accountIds }) return Match.OTHER
        return Match.UNKNOWN
    }

    // PhoneAccountHandle for placing a call from the chosen SIM, or null (use default behaviour).
    fun chosenHandle(context: Context): PhoneAccountHandle? {
        if (Build.VERSION.SDK_INT < 23) return null
        return try {
            val active = activeSims(context)
            if (active.size < 2) return null   // one SIM: default routing is already right
            val sim = chosen(context, active) ?: return null
            callCapableHandles(context).firstOrNull { h ->
                handleSubId(context, h) == sim.subId || h.id in sim.accountIds
            }
        } catch (e: Exception) {
            null
        }
    }
}
