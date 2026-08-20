package no.issuetracker.sdk

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import no.issuetracker.sdk.internal.AccessibilityActionObserver
import no.issuetracker.sdk.internal.ActivityProvider
import no.issuetracker.sdk.internal.AttestationStore
import no.issuetracker.sdk.internal.BreadcrumbStore
import no.issuetracker.sdk.internal.CrashReporter
import no.issuetracker.sdk.internal.FormFactor
import no.issuetracker.sdk.internal.LifecycleStore
import no.issuetracker.sdk.internal.LongPressObserver
import no.issuetracker.sdk.internal.OnboardingPresenter
import no.issuetracker.sdk.internal.OnboardingStore
import no.issuetracker.sdk.internal.RemotePressObserver
import no.issuetracker.sdk.internal.ReportButtonObserver
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

    private val configScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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
     * @param shakeToReport If `true` (default), a device shake brings
     *   up the reporter. Inert on television form factors (Android TV,
     *   Google TV, Fire TV) — there is no shake in the TV trigger
     *   model. See sdk-spec/TV_TRIGGERS.md.
     * @param longPressToReport If `true` (default), the platform's
     *   long-press trigger brings up the reporter: on phones/tablets a
     *   two-finger press held for 3 seconds; on television form
     *   factors, holding Play/Pause on the remote for 2 seconds. One
     *   flag, one meaning — "the platform's long-press trigger".
     * @param accessibilityAction If `true`, and an accessibility
     *   service with touch exploration is running (TalkBack), the SDK
     *   registers a custom accessibility action "Report a bug" on the
     *   resumed activity so screen-reader users have a gesture-free
     *   path into the reporter — TalkBack claims multi-finger gestures,
     *   so the long-press trigger typically never reaches them, and
     *   shake is motion actuation (WCAG 2.5.4). The action follows the
     *   activity lifecycle and the live TalkBack state; it adds no
     *   visible chrome for anyone else. Defaults to `false` so existing
     *   integrations are unaffected (ADR-0008 Decision 2). Hosts
     *   enabling gesture triggers should enable this (and/or
     *   [showReportButton]) or wire their own visible control calling
     *   [report].
     * @param showReportButton If `true`, the SDK overlays a small
     *   floating "Report" button (bottom-end corner) on each resumed
     *   activity that opens the reporter — the no-code path to a
     *   visible, single-pointer, motion-free report entry point
     *   (ADR-0008 Decision 3). Inert on television form factors; TV
     *   hosts should wire [report] to a settings row instead. Defaults
     *   to `false`.
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
        accessibilityAction: Boolean = false,
        showReportButton: Boolean = false,
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
        // Seed remote config (testers-only gating, ADR-0005) from the
        // SharedPreferences cache before the observers install, so the
        // very first gesture consults real data when we have any. The
        // network refresh runs async below.
        AttestationStore.install(application, apiKey)
        // Gesture triggers are gated per-fire rather than at install
        // time: config can flip while the app runs, and a fire-time
        // check reconciles instantly with no uninstall plumbing. In
        // testers-only mode without a token the gestures are silently
        // inert (ADR-0005 invariant 5). The programmatic report() is
        // deliberately ungated — a host app's own button should
        // surface the attestation message instead.
        //
        // Form factor decides what the flags mean (TV_TRIGGERS.md):
        // one artifact serves phones and TVs, so on television
        // (Android TV / Google TV / Fire TV) `longPressToReport`
        // installs the hold-Play/Pause remote trigger instead of the
        // two-finger touch press, and `shakeToReport` is inert —
        // there is no accelerometer in the TV trigger model.
        val isTv = FormFactor.isTelevision(application)
        if (shakeToReport && !isTv) {
            ShakeObserver.install(application) {
                if (AttestationStore.canTriggerReport) report()
            }
        }
        if (longPressToReport) {
            if (isTv) {
                RemotePressObserver.install(application) {
                    if (AttestationStore.canTriggerReport) report()
                }
            } else {
                LongPressObserver.install(application) {
                    if (AttestationStore.canTriggerReport) report()
                }
            }
        }
        // ADR-0008: accessible activation paths. Both observers
        // register their lifecycle callbacks once (like the gesture
        // observers above); the enabled state is re-evaluated on every
        // configure() call so a host settings screen can flip the
        // flags live — enabling attaches to the current activity
        // immediately, disabling detaches immediately. Both paths call
        // report() ungated by attestation: they are visible/announced
        // controls, and — like a host app's own button — should
        // surface the server's message rather than silently do
        // nothing. TERMINATED gating happens at attach time inside
        // the observers (they never re-attach once terminated; the
        // pre-flight gate in present() covers a mid-session flip).
        AccessibilityActionObserver.install(application) { report() }
        AccessibilityActionObserver.setEnabled(application, accessibilityAction)
        ReportButtonObserver.install(application) { report() }
        // A floating touch button is meaningless on television form
        // factors — TV hosts wire report() to a settings row instead
        // (TV_TRIGGERS.md).
        ReportButtonObserver.setEnabled(showReportButton && !isTv)
        if (enableCrashReporting) {
            // Fire any pending crash from the previous session BEFORE
            // installing the new heartbeat — order matters so we don't
            // accidentally overwrite the previous session's marker.
            CrashReporter.reportCrashIfAny(rt)
            CrashReporter.install(application)
        }
        configScope.launch {
            AttestationStore.refreshRemoteConfig(rt)
            // Onboarding waits for the config refresh so we never
            // advertise gestures that are gated off for this install —
            // and never wrongly suppress it on a prod key's first
            // launch just because the fail-closed default was still in
            // effect.
            if (showOnboarding && AttestationStore.canTriggerReport) {
                OnboardingPresenter.presentIfNeeded(
                    application = application,
                    shakeEnabled = shakeToReport && !isTv,
                    longPressEnabled = longPressToReport && !isTv,
                    remoteEnabled = longPressToReport && isTv,
                )
            }
        }
        // Capture the enabled-trigger snapshot for showOnboarding()
        // so a single source of truth governs both runtime behaviour
        // and onboarding content.
        enabledTriggers = EnabledTriggers(
            shake = shakeToReport && !isTv,
            longPress = longPressToReport && !isTv,
            remote = longPressToReport && isTv,
        )
    }

    @Volatile
    private var enabledTriggers: EnabledTriggers = EnabledTriggers(false, false, false)

    private data class EnabledTriggers(
        val shake: Boolean,
        val longPress: Boolean,
        val remote: Boolean,
    )

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
            remoteEnabled = enabledTriggers.remote,
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
     * Stores a tester attestation token (ADR-0005). On projects in
     * testers-only mode this is what unlocks the report triggers and
     * gets reports past the server; in open mode it stamps reports
     * with the tester's identity. The token will normally arrive
     * silently from the companion app's ContentProvider; this API is
     * the manual injection point until that ships (and for
     * integration tests). Safe to call before [configure].
     */
    @JvmStatic
    @JvmOverloads
    fun setTesterToken(token: String, expiresAtMillis: Long? = null) {
        AttestationStore.setTesterToken(token, expiresAtMillis)
    }

    /**
     * Removes the stored tester token. On testers-only projects the
     * gesture triggers go inert again from the next gesture. Safe to
     * call before [configure].
     */
    @JvmStatic
    fun clearTesterToken() {
        AttestationStore.clearTesterToken()
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
