package no.issuetracker.sdk.internal

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.test.core.app.ApplicationProvider
import no.issuetracker.sdk.Runtime
import no.issuetracker.sdk.SdkErrorReason
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * ADR-0003 Decision 9, the parts that are not about parsing: once the
 * SDK is TERMINATED it must stop advertising a way in, must not deliver
 * anything still sitting on disk, and must show the terminal surface —
 * not the form — if a trigger does fire.
 *
 * Companion to [TerminationWireContractTest], which covers the error
 * contract itself.
 */
@RunWith(RobolectricTestRunner::class)
class TerminatedTriggerTest {

    private fun app(): Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        LifecycleStore.resetForTesting()
        AttestationStore.resetForTesting()
        app().getSharedPreferences("no.issuetracker.sdk.lifecycle", Context.MODE_PRIVATE)
            .edit().clear().commit()
        LifecycleStore.install(app())
        ReporterIdentity.install(app())
        BreadcrumbStore.install(app())
        // Robolectric hands out a fresh Application per test method, so
        // the install-once singletons must be re-pointed at it or their
        // lifecycle callbacks stay registered on a dead Application.
        ActivityProvider.resetForTesting()
        ReportButtonObserver.resetForTesting()
        ActivityProvider.install(app())
    }

    @After
    fun tearDown() {
        ReportButtonObserver.resetForTesting()
        ActivityProvider.resetForTesting()
        LifecycleStore.resetForTesting()
        AttestationStore.resetForTesting()
    }

    // --- triggers go away ---------------------------------------------

    @Test
    fun `floating report button is gone and never re-attaches once terminated`() {
        ReportButtonObserver.install(app()) { /* no-op */ }
        ReportButtonObserver.setEnabled(true)

        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        assertNotNull(
            "precondition: the button attaches while the SDK is OK",
            findReportButton(controller.get()),
        )

        LifecycleStore.transitionToTerminated(SdkErrorReason.PROJECT_DELETED, null)

        // A normal activity cycle is all it takes: the button is
        // removed on pause and must not come back on resume.
        controller.pause().resume()
        assertNull(findReportButton(controller.get()))

        // Nor on a brand-new activity, nor after the host flips the
        // flag again — TERMINATED outranks `showReportButton`.
        val next = Robolectric.buildActivity(Activity::class.java).setup()
        ReportButtonObserver.setEnabled(false)
        ReportButtonObserver.setEnabled(true)
        assertNull(findReportButton(next.get()))
    }

    @Test
    fun `report button never attaches at all when the process starts terminated`() {
        // The realistic shape: the SDK terminated yesterday, the app is
        // launched fresh today and rehydrates from SharedPreferences
        // before any activity is resumed.
        app().getSharedPreferences("no.issuetracker.sdk.lifecycle", Context.MODE_PRIVATE)
            .edit()
            .putString("terminated_reason", "api_key_revoked")
            .putLong("terminated_at", 1747000000000L)
            .commit()
        LifecycleStore.resetForTesting()
        LifecycleStore.install(app())
        assertTrue(LifecycleStore.isTerminated)

        ReportButtonObserver.install(app()) { /* no-op */ }
        ReportButtonObserver.setEnabled(true)
        val controller = Robolectric.buildActivity(Activity::class.java).setup()

        assertNull(findReportButton(controller.get()))
    }

    // --- the reporting surface shows the terminal view ----------------

    @Test
    fun `a trigger in the terminated state opens the reporter without capturing a screenshot`() {
        // Gesture triggers stay wired on purpose (ADR-0003 Decision 9.5:
        // the terminal message is shown when the cohort user opens the
        // reporting surface) — but nothing of the host's screen may be
        // captured, since no report can be filed.
        Robolectric.buildActivity(Activity::class.java).setup()
        LifecycleStore.transitionToTerminated(SdkErrorReason.WORKSPACE_SUSPENDED, null)

        ReportingSession.present(runtime())
        shadowOf(Looper.getMainLooper()).idle()

        val handoff = ReportingSession.consume()
        assertNotNull("the reporter surface must still open", handoff)
        assertNull("no screenshot may be captured once terminated", handoff!!.second)
    }

    // --- the local queue -----------------------------------------------

    /**
     * ADR-0003 Decision 9.6: "Drop the queue atomically on first
     * TERMINATED signal — and persist a `terminatedAt` marker so a
     * process restart does not re-attempt delivery."
     *
     * Android's only on-disk outbound artefact is the pending crash
     * report written by [CrashReporter]. It is read and POSTed from
     * `configure()` on the next launch with no reference to
     * [LifecycleStore], so a crash captured before termination is still
     * delivered — one report per terminated install, after the SDK has
     * been told the project is gone.
     *
     * Ignored, not deleted: this asserts the ADR-mandated behaviour and
     * fails against today's implementation. Un-ignore together with the
     * fix (gate `reportCrashIfAny` on `LifecycleStore.isTerminated`, and
     * delete the pending file on the transition).
     */
    @Test
    @Ignore("KNOWN GAP: CrashReporter does not consult LifecycleStore — verified failing on 2026-09-05; see the ADR-0003 audit findings")
    fun `a pending crash report is not delivered once terminated`() {
        val server = FakeCallableServer().also { it.start() }
        try {
            writePendingCrash()
            LifecycleStore.transitionToTerminated(SdkErrorReason.PROJECT_DELETED, null)

            CrashReporter.reportCrashIfAny(
                Runtime(app(), API_KEY, server.endpoint, onConfigurationError = null),
            )
            // The send is fire-and-forget on Dispatchers.IO; give it a
            // generous window before concluding nothing was sent.
            waitForRequest(server, timeoutMs = 2_000)

            assertEquals(emptyList<String>(), server.requestPaths.toList())
        } finally {
            server.stop()
        }
    }

    /**
     * ADR-0003 Decision 9.2: TERMINATED must "disable all background
     * tasks". `configure()` calls [AttestationStore.refreshRemoteConfig]
     * unconditionally, so a terminated install still POSTs
     * `getSdkConfig` on every single app launch, forever — bounded
     * (one request per launch) but exactly the class of traffic the
     * decision exists to stop, and it can never change the outcome
     * because TERMINATED is one-way.
     *
     * Ignored, not deleted: asserts the ADR-mandated behaviour and
     * fails against today's implementation.
     */
    @Test
    @Ignore("KNOWN GAP: refreshRemoteConfig runs while TERMINATED — verified failing on 2026-09-05; see the ADR-0003 audit findings")
    fun `the config refresh does not run once terminated`() {
        val server = FakeCallableServer().also { it.start() }
        try {
            LifecycleStore.transitionToTerminated(SdkErrorReason.API_KEY_REVOKED, null)
            AttestationStore.install(app(), API_KEY)

            kotlinx.coroutines.runBlocking {
                AttestationStore.refreshRemoteConfig(
                    Runtime(app(), API_KEY, server.endpoint, onConfigurationError = null),
                )
            }

            assertEquals(emptyList<String>(), server.requestPaths.toList())
        } finally {
            server.stop()
        }
    }

    @Test
    fun `a pending crash report is delivered while the SDK is OK`() {
        // The control case for the ignored test above: the same setup
        // minus the termination does reach the endpoint, so a failure
        // there means "not delivered", not "never wired up".
        val server = FakeCallableServer().also { it.start() }
        try {
            writePendingCrash()
            CrashReporter.reportCrashIfAny(
                Runtime(app(), API_KEY, server.endpoint, onConfigurationError = null),
            )
            waitForRequest(server, timeoutMs = 5_000)

            assertEquals(listOf("/v1/createIssueFromSdk"), server.requestPaths.toList())
        } finally {
            server.stop()
        }
    }

    // --- helpers --------------------------------------------------------

    private fun runtime() = Runtime(
        application = app(),
        apiKey = API_KEY,
        endpoint = "http://127.0.0.1:1/v1",
        onConfigurationError = null,
    )

    private fun writePendingCrash() {
        val dir = File(app().filesDir, "issuetracker").apply { mkdirs() }
        File(dir, "pending_crash.json").writeText(
            JSONObject()
                .put("detectedAt", 1747000000000L)
                .put("exceptionName", "java.lang.IllegalStateException")
                .put("exceptionReason", "boom")
                .toString(),
        )
    }

    private fun waitForRequest(server: FakeCallableServer, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && server.requestPaths.isEmpty()) {
            Thread.sleep(25)
        }
    }

    private fun findReportButton(activity: Activity): View? {
        val decor = activity.window?.decorView as? ViewGroup ?: return null
        return findButton(decor)
    }

    private fun findButton(view: View): View? {
        if (view is Button && view.text?.toString() == "Report") return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findButton(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private companion object {
        const val API_KEY = "it_dev_triggerTestKey"
    }
}
