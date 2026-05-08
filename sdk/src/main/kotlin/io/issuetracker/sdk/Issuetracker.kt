package io.issuetracker.sdk

import android.app.Application
import io.issuetracker.sdk.internal.ActivityProvider
import io.issuetracker.sdk.internal.BreadcrumbStore
import io.issuetracker.sdk.internal.CrashReporter
import io.issuetracker.sdk.internal.LongPressObserver
import io.issuetracker.sdk.internal.ReporterIdentity
import io.issuetracker.sdk.internal.ReportingSession
import io.issuetracker.sdk.internal.ShakeObserver

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
     * The key and endpoint are stored for the lifetime of the process;
     * subsequent calls replace the configuration.
     */
    @JvmStatic
    @JvmOverloads
    fun configure(
        application: Application,
        apiKey: String,
        endpoint: String,
        shakeToReport: Boolean = true,
        longPressToReport: Boolean = true,
        enableCrashReporting: Boolean = true,
    ) {
        val rt = Runtime(application, apiKey, endpoint.trimEnd('/'))
        runtime = rt
        ActivityProvider.install(application)
        // ReporterIdentity + BreadcrumbStore are auto-installed by
        // SdkInitProvider so identify()/recordAction() also work
        // before configure() is called. Re-install is a no-op.
        ReporterIdentity.install(application)
        BreadcrumbStore.install(application)
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

internal data class Runtime(
    val application: Application,
    val apiKey: String,
    val endpoint: String,
)
