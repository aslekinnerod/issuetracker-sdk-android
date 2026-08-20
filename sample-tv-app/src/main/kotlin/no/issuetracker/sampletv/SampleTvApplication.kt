package no.issuetracker.sampletv

import android.app.Application
import no.issuetracker.sdk.Issuetracker

/**
 * TV sample-app entry point. Configures the Issuetracker SDK before
 * any Activity starts. On the television form factor the SDK wires
 * `longPressToReport` to hold-Play/Pause (2 s) and leaves shake
 * inert — see sdk-spec/TV_TRIGGERS.md.
 *
 * The API key comes from `local.properties`
 * (`issuetracker.sdk.apiKey=...`) via BuildConfig, same as sample-app.
 */
class SampleTvApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Issuetracker.configure(
            application = this,
            apiKey = BuildConfig.SDK_API_KEY,
            showOnboarding = true,
        )
    }
}
