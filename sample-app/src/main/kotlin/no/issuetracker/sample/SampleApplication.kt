package no.issuetracker.sample

import android.app.Application
import android.content.SharedPreferences
import no.issuetracker.sdk.Issuetracker
import no.issuetracker.sdk.SdkErrorReason
import no.issuetracker.sdk.TerminatedUiStrings

/**
 * Sample-app entry point. Configures the Issuetracker SDK before any
 * Activity starts so shake / long-press triggers + crash reporting
 * are wired from the first frame.
 *
 * The API key comes from `local.properties`
 * (`issuetracker.sdk.apiKey=...`) via the BuildConfig field that the
 * build script emits. The placeholder `it_staging_REPLACE_ME` will
 * trip the SDK's terminal path with `invalid_api_key` on the first
 * report attempt — useful for demoing the TERMINATED flow if you
 * leave it in.
 *
 * The terminated-state UI strings can be overridden in two ways:
 *   1. The MainActivity i18n section, which writes the
 *      [PREF_USE_NORWEGIAN_TERMINATED_UI] preference.
 *   2. Pre-seed the SharedPreferences before first launch.
 *
 * Flipping the i18n preference requires an app relaunch since the
 * strings are read here. The accessibility toggles (ADR-0008) are
 * different: MainActivity re-runs [configureSdk] on flip, so
 * `accessibilityAction` / `showReportButton` apply live — that's the
 * re-configure semantics the SDK promises.
 */
class SampleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        configureSdk(this)
    }

    companion object {
        const val PREFS_NAME = "issuetracker.sample"
        const val PREF_USE_NORWEGIAN_TERMINATED_UI = "useNorwegianTerminatedUI"
        const val PREF_ACCESSIBILITY_ACTION = "accessibilityAction"
        const val PREF_SHOW_REPORT_BUTTON = "showReportButton"
        const val PREF_LAST_CONFIG_ERROR = "lastConfigError"
        const val PREF_LAST_CONFIG_ERROR_AT = "lastConfigErrorAt"

        /**
         * Reads the persisted toggles and (re-)configures the SDK.
         * Called once from [onCreate] and again from MainActivity's
         * accessibility section whenever a toggle flips — subsequent
         * `configure()` calls replace the configuration live.
         */
        fun configureSdk(application: Application) {
            val prefs = samplePrefs(application)
            val useNorwegian = prefs.getBoolean(PREF_USE_NORWEGIAN_TERMINATED_UI, false)

            val terminatedUI: TerminatedUiStrings? = if (useNorwegian) {
                TerminatedUiStrings(
                    title = "Feilrapportering er ikke lenger tilgjengelig.",
                    subtitle = "Kontakt teamet ditt.",
                    closeLabel = "Lukk",
                )
            } else null

            Issuetracker.configure(
                application = application,
                apiKey = BuildConfig.SDK_API_KEY,
                shakeToReport = true,
                longPressToReport = true,
                accessibilityAction = prefs.getBoolean(PREF_ACCESSIBILITY_ACTION, false),
                showReportButton = prefs.getBoolean(PREF_SHOW_REPORT_BUTTON, false),
                enableCrashReporting = true,
                showOnboarding = true,
                onConfigurationError = { reason: SdkErrorReason ->
                    // Persist so the lifecycle section in MainActivity can
                    // show which reason was last received. The SDK has
                    // already transitioned to TERMINATED by the time this
                    // fires.
                    prefs.edit()
                        .putString(PREF_LAST_CONFIG_ERROR, reason.rawValue)
                        .putLong(PREF_LAST_CONFIG_ERROR_AT, System.currentTimeMillis())
                        .apply()
                },
                terminatedUI = terminatedUI,
            )
        }
    }
}

internal fun samplePrefs(app: Application): SharedPreferences =
    app.getSharedPreferences(SampleApplication.PREFS_NAME, Application.MODE_PRIVATE)
