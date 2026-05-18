package no.issuetracker.sdk.internal

import android.app.Application
import android.content.Intent
import no.issuetracker.sdk.ui.OnboardingActivity

/**
 * Decides whether (and when) to launch the onboarding popover.
 *
 * Two entry points:
 * - `presentIfNeeded(...)` is the configure-time path. Silently no-ops
 *   if the popover has been shown before or no triggers are enabled.
 * - `presentForced(...)` is the public `Issuetracker.showOnboarding()`
 *   path. Bypasses the persisted flag but still no-ops on no-triggers.
 *
 * Activity launch happens from the application context with the
 * NEW_TASK flag, mirroring how ReportingSession launches ReportActivity
 * — so the popover works even when configure() runs from
 * Application.onCreate before any host-app activity is alive.
 */
internal object OnboardingPresenter {

    fun presentIfNeeded(
        application: Application,
        shakeEnabled: Boolean,
        longPressEnabled: Boolean,
    ) {
        if (OnboardingStore.hasBeenShown) return
        present(application, shakeEnabled, longPressEnabled, markShownAfter = true)
    }

    fun presentForced(
        application: Application,
        shakeEnabled: Boolean,
        longPressEnabled: Boolean,
    ) {
        present(application, shakeEnabled, longPressEnabled, markShownAfter = false)
    }

    private fun present(
        application: Application,
        shakeEnabled: Boolean,
        longPressEnabled: Boolean,
        markShownAfter: Boolean,
    ) {
        // With both gestures off there is nothing the popover can teach
        // the user — programmatic-only integrations don't need a UI
        // nudge. We still mark "shown" in the configure-time path so
        // flipping a gesture on later doesn't surprise the user with an
        // out-of-context popover.
        if (!shakeEnabled && !longPressEnabled) {
            if (markShownAfter) OnboardingStore.markShown()
            return
        }

        val intent = Intent(application, OnboardingActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(OnboardingActivity.EXTRA_SHAKE_ENABLED, shakeEnabled)
            putExtra(OnboardingActivity.EXTRA_LONG_PRESS_ENABLED, longPressEnabled)
            putExtra(OnboardingActivity.EXTRA_MARK_SHOWN, markShownAfter)
        }
        application.startActivity(intent)
    }
}
