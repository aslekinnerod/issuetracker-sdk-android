package no.issuetracker.sdk

import android.app.Application
import no.issuetracker.sdk.internal.ActivityProvider
import no.issuetracker.sdk.internal.BreadcrumbStore
import no.issuetracker.sdk.internal.CrashReporter
import no.issuetracker.sdk.internal.LifecycleStore
import no.issuetracker.sdk.internal.LongPressObserver
import no.issuetracker.sdk.internal.OnboardingPresenter
import no.issuetracker.sdk.internal.OnboardingStore
import no.issuetracker.sdk.internal.ReporterIdentity
import no.issuetracker.sdk.internal.ReportingSession
import no.issuetracker.sdk.internal.ShakeObserver

/**
 * Public facade for the Issuetracker SDK. Apps integrate by calling
 * [configure] once at launch (typically from `Application.onCreate`);
 * everything else is driven by shake-to-report plus the optional
 * programmatic [report] trigger.
 */
object Issuetracker {

    @Volatile
    internal var runtime: Runtime? = null
        private set

    /**
     * Call once, as early as possible (typically `Application.onCreate`).
     * The key is stored for the lifetime of the process; subsequent
     * calls replace the configuration. The environment (production
     * vs. staging) is derived from the key prefix — there is no
     * endpoint to configure.
     *
     * @param onConfigurationError Optional callback invoked once when
     *   the SDK transitions to the terminated state because the server
     *   signalled a non-recoverable failure (project deleted, API key
     *   revoked, workspace suspended, etc. — see [SdkErrorReason]).
     *   Default behaviour is silent in production; host apps may
     *   forward this to their own telemetry. Once invoked, the SDK
     *   will not call the report endpoint again for the lifetime of
     *   this install — recovery requires a fresh [configure] call
     *   (typically an app relaunch). See ADR-0003 Decision 9.
     * @param showOnboarding If `true`, presents a one-time popover on
     *   first launch that teaches the user which gestures trigger the
     *   reporter — only the gestures currently enabled are shown.
     *   Persisted per install via SharedPreferences, so the popover
     *   never appears twice unless the host app calls [showOnboarding]
     *   explicitly. With both `shakeToReport` and `longPressToReport`
     *   disabled the popover is silently skipped — there's nothing to
     *   teach. Defaults to `false` so existing integrators are
     *   unaffected.
     */
    @JvmStatic
    @JvmOverloads
    fun configure(
        application: Application,
        apiKey: String,
        shakeToReport: Boolean = true,
        longPressToReport: Boolean = true,
        enableCrashReporting: Boolean = true,
        onConfigurationError: ((SdkErrorReason) -> Unit)? = null,
        showOnboarding: Boolean = false,
        terminatedUI: TerminatedUiStrings? = null,
    ) {
        val rt = Runtime(
            application = application,
            apiKey = apiKey,
            endpoint = Runtime.resolveEndpoint(apiKey),
            onConfigurationError = onConfigurationError,
            terminatedUI = terminatedUI,
        )
        runtime = rt
        ActivityProvider.install(application)
        // ReporterIdentity + BreadcrumbStore are auto-installed by
        // SdkInitProvider so identify()/recordAction() also work
        // before configure() is called. Re-install is a no-op.
        ReporterIdentity.install(application)
        BreadcrumbStore.install(application)
        // Lifecycle state survives process restarts via SharedPreferences;
        // installing here rehydrates any prior TERMINATED state before
        // triggers are wired so the pre-flight gate is authoritative.
        LifecycleStore.install(application)
        OnboardingStore.install(application)
        if (shakeToReport) {
            ShakeObserver.install(application) { report() }
        }
        if (longPressToReport) {
            LongPressObserver.install(application) { report() }
        }
        if (enableCrashReporting) {
            // Fire any pending crash from the previous session BEFORE
            // installing the new heartbeat — order matters so we don't
            // accidentally overwrite the previous session's marker.
            CrashReporter.reportCrashIfAny(rt)
            CrashReporter.install(application)
        }
        if (showOnboarding) {
            OnboardingPresenter.presentIfNeeded(
                application = application,
                shakeEnabled = shakeToReport,
                longPressEnabled = longPressToReport,
            )
        }
        // Capture the enabled-trigger snapshot for showOnboarding()
        // so a single source of truth governs both runtime behaviour
        // and onboarding content.
        enabledTriggers = EnabledTriggers(
            shake = shakeToReport,
            longPress = longPressToReport,
        )
    }

    @Volatile
    private var enabledTriggers: EnabledTriggers = EnabledTriggers(false, false)

    private data class EnabledTriggers(val shake: Boolean, val longPress: Boolean)

    /**
     * Re-presents the onboarding popover regardless of whether it has
     * been shown before on this install. Intended for a "Show
     * introduction again"-style entry in the host app's own settings
     * screen. Calling this with no triggers enabled is a no-op. Must
     * be called after [configure].
     */
    @JvmStatic
    fun showOnboarding() {
        val rt = runtime ?: return
        OnboardingPresenter.presentForced(
            application = rt.application,
            shakeEnabled = enabledTriggers.shake,
            longPressEnabled = enabledTriggers.longPress,
        )
    }

    /** Programmatic trigger — useful for a "report a bug" button. */
    @JvmStatic
    fun report() {
        val rt = runtime ?: return
        ReportingSession.present(rt)
    }

    /**
     * Sets the display name shown on reports submitted from this
     * install. Skips the "What should we call you?" prompt the first
     * time a user triggers a report. Safe to call before [configure].
     */
    @JvmStatic
    fun identify(name: String) {
        ReporterIdentity.setName(name)
    }

    /** Clears the stored display name. Next report re-prompts. */
    @JvmStatic
    fun clearIdentity() {
        ReporterIdentity.clearName()
    }

    /**
     * Records one user action (max 5 retained). Attached to any report
     * the user submits and to auto-generated crash reports on the next
     * launch. Safe to call before [configure].
     */
    @JvmStatic
    @JvmOverloads
    fun recordAction(action: String, metadata: Map<String, String>? = null) {
        BreadcrumbStore.record(action, metadata)
    }

    /**
     * Deliberately crashes the app so you can verify the auto-generated
     * crash report flows through on the next launch. SDK integration
     * testing only — do not ship calls to this from production.
     */
    @JvmStatic
    fun testCrash(): Nothing {
        throw RuntimeException("Issuetracker.testCrash() triggered")
    }
}

/**
 * Strings shown when the SDK has been terminated and a test-cohort
 * user opens the reporting surface. ADR-0003 Decision 9 mandates a
 * localised terminal message; English is the built-in default, and
 * host apps may inject translations via `Issuetracker.configure(...)`.
 *
 * Each field is optional — fields the host doesn't override fall back
 * to English. A missing instance falls back to all-English.
 */
data class TerminatedUiStrings(
    /** Big headline. Default: "Bug reporting is no longer available." */
    val title: String? = null,
    /** One-line follow-up. Default: "Contact your team." */
    val subtitle: String? = null,
    /** Close-button label. Default: "Close". */
    val closeLabel: String? = null,
)

internal data class Runtime(
    val application: Application,
    val apiKey: String,
    val endpoint: String,
    // Invoked exactly once, on the OK -> TERMINATED transition. Stored
    // here (rather than in LifecycleStore) because it's a configure-
    // time setting that the user owns; the store is the state machine.
    val onConfigurationError: ((SdkErrorReason) -> Unit)? = null,
    val terminatedUI: TerminatedUiStrings? = null,
) {
    companion object {
        // Routing is derived from the key prefix so integrators never
        // see any URL — they just paste the key the web UI gave them.
        //   it_dev_*      → dev backend (internal use only)
        //   it_staging_*  → staging backend
        //   it_*          → production (brand-domain)
        fun resolveEndpoint(apiKey: String): String = when {
            apiKey.startsWith("it_dev_") -> "https://issuetracker-api-dev.web.app/v1"
            apiKey.startsWith("it_staging_") -> "https://issuetracker-api-staging.web.app/v1"
            else -> "https://api.issuetracker.no/v1"
        }
    }
}
