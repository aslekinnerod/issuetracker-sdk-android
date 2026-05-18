package no.issuetracker.sdk.internal

import android.content.Context
import android.content.SharedPreferences

/**
 * Tracks whether the onboarding popover has been shown on this install.
 * Persisted in sandboxed SharedPreferences alongside the other SDK
 * stores; survives process restarts. The SDK never shows the popover
 * twice for the same install — unless the host app explicitly calls
 * `Issuetracker.showOnboarding()` (which bypasses the flag).
 */
internal object OnboardingStore {
    private const val PREFS_NAME = "no.issuetracker.sdk.onboarding"
    private const val SHOWN_KEY = "shown"

    private var prefs: SharedPreferences? = null

    fun install(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    val hasBeenShown: Boolean
        get() = prefs?.getBoolean(SHOWN_KEY, false) == true

    fun markShown() {
        prefs?.edit()?.putBoolean(SHOWN_KEY, true)?.apply()
    }
}
