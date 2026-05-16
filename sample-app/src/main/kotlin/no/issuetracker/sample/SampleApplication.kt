package no.issuetracker.sample

import android.app.Application
import no.issuetracker.sdk.Issuetracker

class SampleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Issuetracker.configure(
            application = this,
            apiKey = BuildConfig_Sdk.SDK_API_KEY,
        )
    }
}

/**
 * Replace at integration time. Kept as a separate Kotlin object (not
 * BuildConfig) so the sample doesn't need a custom buildConfigField.
 */
private object BuildConfig_Sdk {
    const val SDK_API_KEY: String = "it_staging_replace_with_real_key_from_admin_ui"
}
