package no.issuetracker.sdk.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import no.issuetracker.sdk.internal.OnboardingStore

/**
 * Hosts the onboarding popover on top of the host app. Same translucent
 * activity pattern as ReportActivity. The host-controlled
 * "configure-time" call goes through OnboardingPresenter, which decides
 * whether to launch this activity based on the persisted "has-been-
 * shown" flag and the enabled-trigger set.
 *
 * Reads the trigger flags + `markShown` instruction from the launching
 * Intent rather than from a static handoff field — the onboarding flow
 * is small enough that an Intent extras round-trip is cleaner than
 * extending the existing ReportingSession pattern.
 */
internal class OnboardingActivity : ComponentActivity() {

    companion object {
        const val EXTRA_SHAKE_ENABLED = "shake_enabled"
        const val EXTRA_LONG_PRESS_ENABLED = "long_press_enabled"
        const val EXTRA_MARK_SHOWN = "mark_shown"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shakeEnabled = intent.getBooleanExtra(EXTRA_SHAKE_ENABLED, false)
        val longPressEnabled = intent.getBooleanExtra(EXTRA_LONG_PRESS_ENABLED, false)
        val markShownAfter = intent.getBooleanExtra(EXTRA_MARK_SHOWN, true)

        setContent {
            MaterialTheme {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    OnboardingScreen(
                        showsShake = shakeEnabled,
                        showsLongPress = longPressEnabled,
                        onDismiss = {
                            if (markShownAfter) OnboardingStore.markShown()
                            finish()
                        },
                    )
                }
            }
        }
    }
}
