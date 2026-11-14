package com.apsconnect.autodial

import android.content.Context

/**
 * In-app language switch (English / Tamil) for the native screens. English is the default.
 * The choice is stored per phone; screens read it each time a dialog or view is built, so after
 * [set] the caller only has to recreate its Activity. The CRM web pages are not affected.
 */
object Lang {
    private const val PREFS = "app_language"
    private const val KEY = "lang"

    fun isTamil(c: Context): Boolean =
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "en") == "ta"

    fun set(c: Context, tamil: Boolean) {
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, if (tamil) "ta" else "en").apply()
    }

    /** English text, or the Tamil text when the staff member chose Tamil. */
    fun t(c: Context, en: String, ta: String): String = if (isTamil(c)) ta else en

    /** Label for the toggle button: it names the language you will switch TO. */
    fun toggleLabel(c: Context): String = if (isTamil(c)) "English" else "தமிழ்"
}
