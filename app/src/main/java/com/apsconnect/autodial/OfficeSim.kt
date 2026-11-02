package com.apsconnect.autodial

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat

// v1.10: staff may carry a personal SIM and an office SIM. Only the OFFICE SIM's calls are
// reported/counted. This object lists the active SIMs, stores the staff's choice, and maps a
// call-log row's PHONE_ACCOUNT_ID to chosen SIM / other SIM / unknown.
//
// Safety rules (priority: never lose calls silently, never upload personal-SIM calls silently):
//  * ONE snapshot() per sync; everything (filter, fields, warnings) is derived from it.
//  * 2+ SIMs: a row is uploaded only if it POSITIVELY belongs to the chosen SIM. Rows that
//    positively belong to another SIM are skipped (cursor moves on). UNKNOWN rows are never
//    uploaded and never skipped: the sync holds before them and the strip shows a warning.
//  * A lone SIM that was auto-selected is stored as "auto", not as a deliberate choice; if the
//    SIM set changes, the auto choice is dropped. A deliberate choice whose SIM vanished holds
//    uploads until the staff confirm again.
//  * Cannot read the SIM list while a deliberate choice exists -> hold (fail closed).
// Privacy: the SIM's own phone number is never read, stored or sent.
object OfficeSim {

    private const val TAG = "OfficeSim"
    private const val PREFS = "office_sim"
    private const val SNOOZE_MS = 6L * 60 * 60 * 1000
    private const val DIAL_LEARN_WINDOW_MS = 10L * 60 * 1000

    data class Sim(
        val subId: Int,
        val slot: Int,          // 0-based slot index, may be -1 when unknown
        val carrier: String,
        val iccId: String,      // may be "" (hidden on API 29+ for non-privileged apps)
        val accountIds: Set<String>   // values the call log may put in PHONE_ACCOUNT_ID
    ) {
        val maskedLabel: String
            get() = "SIM ${if (slot >= 0) (slot + 1).toString() else "?"} - $carrier (" +
                (if (iccId.length >= 4) "..." + iccId.takeLast(4) else "id $subId") + ")"
    }

    enum class Status { NO_PERMISSION, UNAVAILABLE, SINGLE, MULTI_READY, NEED_CHOICE }

    class Snapshot(
        val status: Status,
        val active: List<Sim>,
        val chosen: Sim?,
        val learned: Set<String>,
        val hold: Boolean,          // true = upload nothing and do not move the cursor
        val note: String?           // extra text for the strip (e.g. SIM changed)
    ) {
        val filtering: Boolean get() = active.size > 1 && chosen != null && !hold
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hasPhoneState(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

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

    private fun handleKey(h: PhoneAccountHandle): String =
        h.componentName.flattenToString() + "/" + h.id

    // Active SIMs sorted by slot. Empty on missing permission / null / empty list / any error.
    private fun activeSims(context: Context): List<Sim> {
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
                for (h in handles) {
                    // handle id == ICC id, or a handle positively tied to this subscription
                    if ((icc.isNotEmpty() && h.id == icc) || handleSubId(context, h) == s.subscriptionId) {
                        ids.add(h.id)
                        ids.add(handleKey(h))
                    }
                }
                Sim(s.subscriptionId, s.simSlotIndex, (s.carrierName ?: "").toString().trim().ifEmpty { "SIM" }, icc, ids)
            }.sortedBy { it.slot }
        } catch (e: Exception) {
            Log.w(TAG, "activeSims failed: ${e.message}")
            emptyList()
        }
    }

    private fun saved(p: SharedPreferences, active: List<Sim>): Sim? {
        if (!p.contains("subId")) return null
        val subId = p.getInt("subId", -1)
        val icc = p.getString("iccId", "") ?: ""
        return active.firstOrNull { it.subId == subId }
            ?: active.firstOrNull { icc.isNotEmpty() && it.iccId == icc }
    }

    private fun write(context: Context, sim: Sim, auto: Boolean) {
        val p = prefs(context)
        val sameSim = p.getInt("subId", -2) == sim.subId
        val e = p.edit()
            .putInt("subId", sim.subId)
            .putInt("slot", sim.slot)
            .putString("carrier", sim.carrier)
            .putString("iccId", sim.iccId)
            .putBoolean("auto", auto)
        if (!sameSim) e.remove("learned")   // learned ids belong to one SIM
        e.apply()
    }

    // Deliberate choice by the staff (dialog Save).
    fun choose(context: Context, sim: Sim) {
        write(context, sim, false)
        prefs(context).edit().putBoolean("unknownHold", false).remove("snoozeUntil").apply()
    }

    private fun clearChoice(context: Context) {
        prefs(context).edit().remove("subId").remove("slot").remove("carrier").remove("iccId")
            .remove("auto").remove("learned").apply()
    }

    fun snapshot(context: Context): Snapshot {
        val p = prefs(context)
        val hasSaved = p.contains("subId")
        val deliberate = hasSaved && !p.getBoolean("auto", false)
        val learned: Set<String> = HashSet(p.getStringSet("learned", emptySet()) ?: emptySet())
        if (!hasPhoneState(context)) {
            return Snapshot(Status.NO_PERMISSION, emptyList(), null, learned, deliberate, null)
        }
        val active = activeSims(context)
        if (active.isEmpty()) {   // list null/empty/error: fail closed only when a deliberate choice exists
            return Snapshot(Status.UNAVAILABLE, emptyList(), null, learned, deliberate, null)
        }
        val match = saved(p, active)
        if (active.size == 1) {
            val only = active[0]
            if (match != null) return Snapshot(Status.SINGLE, active, match, learned, false, null)
            if (!hasSaved) {
                write(context, only, true)
                return Snapshot(Status.SINGLE, active, only, learned, false, null)
            }
            if (!deliberate) {   // auto choice whose SIM changed: re-pick the lone SIM, flag it
                write(context, only, true)
                return Snapshot(Status.SINGLE, active, only, emptySet(), false, "SIM maari irukku")
            }
            // deliberate choice vanished, a different lone SIM is present: hold until confirmed
            return Snapshot(Status.NEED_CHOICE, active, null, learned, true, "Pazhaya office SIM illa -- confirm pannunga")
        }
        if (deliberate && match != null) return Snapshot(Status.MULTI_READY, active, match, learned, false, null)
        if (hasSaved && !deliberate) clearChoice(context)   // auto choice is not valid for 2+ SIMs
        return Snapshot(Status.NEED_CHOICE, active, null, learned, true, null)
    }

    enum class Match { CHOSEN, OTHER, UNKNOWN }

    // Classify a call-log row by PHONE_ACCOUNT_ID (+ optional component name).
    fun classify(accountId: String?, component: String?, snap: Snapshot): Match {
        val chosen = snap.chosen ?: return Match.UNKNOWN
        if (accountId.isNullOrEmpty()) return Match.UNKNOWN
        val keys = HashSet<String>()
        keys.add(accountId)
        if (!component.isNullOrEmpty()) keys.add("$component/$accountId")
        val isChosen = keys.any { it in chosen.accountIds || it in snap.learned }
        val isOther = snap.active.any { s -> s.subId != chosen.subId && keys.any { it in s.accountIds } }
        return when {
            isChosen && !isOther -> Match.CHOSEN
            isOther && !isChosen -> Match.OTHER
            else -> Match.UNKNOWN   // no match, or ambiguous (matches both): never guess
        }
    }

    // ---- learning the call-log id from calls the app dials itself ----

    fun recordDial(context: Context, number: String) {
        val digits = number.filter { it.isDigit() }.takeLast(10)
        if (digits.isEmpty()) return
        prefs(context).edit().putString("dialNum", digits).putLong("dialAt", System.currentTimeMillis()).apply()
    }

    // If this row is the call we just dialled with the chosen SIM's handle, remember its
    // PHONE_ACCOUNT_ID as accepted for the chosen SIM. Returns true when learned.
    fun learnFromRow(context: Context, number: String?, dateMs: Long, accountId: String?, component: String?): Boolean {
        if (accountId.isNullOrEmpty() || number.isNullOrEmpty()) return false
        val p = prefs(context)
        val dialAt = p.getLong("dialAt", 0L)
        val dialNum = p.getString("dialNum", "") ?: ""
        if (dialAt == 0L || dialNum.isEmpty()) return false
        if (System.currentTimeMillis() - dialAt > DIAL_LEARN_WINDOW_MS) return false
        if (dateMs < dialAt - 60_000) return false
        if (number.filter { it.isDigit() }.takeLast(10) != dialNum) return false
        val set = HashSet(p.getStringSet("learned", emptySet()) ?: emptySet())
        set.add(accountId)
        if (!component.isNullOrEmpty()) set.add("$component/$accountId")
        p.edit().putStringSet("learned", set).remove("dialNum").remove("dialAt").apply()
        return true
    }

    // PhoneAccountHandle for placing a call from the chosen SIM, only when it POSITIVELY maps
    // to that SIM (subscription id of the handle, handle id == ICC id, or an id learned earlier).
    fun chosenHandle(context: Context, snap: Snapshot): PhoneAccountHandle? {
        if (Build.VERSION.SDK_INT < 23 || snap.active.size < 2 || snap.hold) return null
        val sim = snap.chosen ?: return null
        return try {
            callCapableHandles(context).firstOrNull { h ->
                handleSubId(context, h) == sim.subId ||
                    (sim.iccId.isNotEmpty() && h.id == sim.iccId) ||
                    h.id in snap.learned || handleKey(h) in snap.learned
            }
        } catch (e: Exception) {
            null
        }
    }

    // ---- UI helpers ----

    fun unknownHold(context: Context) = prefs(context).getBoolean("unknownHold", false)

    fun setUnknownHold(context: Context, v: Boolean) {
        val p = prefs(context)
        if (p.getBoolean("unknownHold", false) != v) p.edit().putBoolean("unknownHold", v).apply()
    }

    fun snoozed(context: Context) = System.currentTimeMillis() < prefs(context).getLong("snoozeUntil", 0L)

    fun snooze(context: Context) {
        prefs(context).edit().putLong("snoozeUntil", System.currentTimeMillis() + SNOOZE_MS).apply()
    }

    // Slot sent to the server: 0..4, or null (omit the field) when unknown.
    fun slotForServer(sim: Sim): Int? = if (sim.slot in 0..4) sim.slot else null

    // For the dialog / strip: the current active SIM list (same code path as snapshot()).
    fun listActive(context: Context): List<Sim> = activeSims(context)
}
