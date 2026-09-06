package no.issuetracker.sampletv

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device E2E for the TV trigger contract (sdk-spec/TV_TRIGGERS.md):
 * a short Play/Pause tap must do nothing; a 2-second hold must open
 * the reporter. Runs on an Android TV emulator/device via
 * `./gradlew :sample-tv-app:connectedDebugAndroidTest`.
 *
 * Key events are injected through UiAutomation with real timing —
 * `input keyevent` can't hold a key, and the TV AVD exposes no
 * keyboard evdev node for sendevent, so instrumentation is the only
 * automatable path to a genuine hold.
 *
 * EVERY test here needs a real API key, not just the round-trip one.
 * On the `it_staging_REPLACE_ME` placeholder that build.gradle.kts
 * falls back to, `Issuetracker.configure()` fetches getSdkConfig, the
 * backend answers the unknown key with a terminal reason, and
 * `AttestationStore.refreshRemoteConfig` flips the SDK to one-way
 * TERMINATED — the triggers are inert before the first hold is
 * injected. Supply one via `ISSUETRACKER_SDK_API_KEY` in the
 * environment or `issuetracker.sdk.apiKey` in local.properties, with
 * `no.issuetracker.sampletv` in its allowedClients whitelist.
 */
@RunWith(AndroidJUnit4::class)
class RemoteHoldE2ETest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val appContext: Context =
        InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun launchApp() {
        appContext.startActivity(
            Intent(appContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        assertTrue(
            "sample app did not come to foreground",
            device.wait(Until.hasObject(By.pkg(appContext.packageName)), 10_000),
        )
        // First launch on a clean install shows the onboarding panel;
        // dismiss it so the trigger tests run against the host screen.
        val gotIt = device.wait(Until.findObject(By.text("Got it")), 3_000)
        gotIt?.click()
        device.waitForIdle()
    }

    @Test
    fun shortTapDoesNotOpenReporter() {
        pressPlayPause(holdMs = 50)
        assertFalse(
            "reporter opened on a short tap — violates TV_TRIGGERS.md",
            reporterAppears(timeoutMs = 3_500),
        )
        // The assertion above passes for free whenever the trigger is
        // dead — a TERMINATED install, an unregistered observer, a key
        // the backend rejects — so on its own it is not evidence that
        // short taps are being ignored. Prove the trigger was live in
        // this very session before trusting the negative. (Ported from
        // sdk-tvos's RemoteHoldE2ETests, which found the same defect.)
        pressPlayPause(holdMs = 2_300)
        failIfTerminated()
        assertTrue(
            "control failed: the hold trigger was not live, so 'short tap " +
                "did nothing' proves nothing",
            reporterAppears(timeoutMs = 5_000),
        )
    }

    @Test
    fun twoSecondHoldOpensReporter() {
        pressPlayPause(holdMs = 2_300)
        failIfTerminated()
        assertTrue(
            "reporter did not open after a 2.3 s hold",
            reporterAppears(timeoutMs = 5_000),
        )
    }

    /**
     * ADR-0003 terminal state. A terminated install makes every other
     * assertion meaningless — fail loudly instead of letting tests
     * half-pass against the terminal screen. (A submit against a key
     * whose allowedClients whitelist excludes this app's bundle id
     * terminates the SDK with invalid_api_key — clear app data and use
     * a key provisioned for no.issuetracker.sampletv.)
     */
    private fun failIfTerminated() {
        if (device.hasObject(By.text("Bug reporting is no longer available."))) {
            fail(
                "SDK is TERMINATED on this install — clear app data and " +
                    "verify the API key's client whitelist",
            )
        }
    }

    /**
     * Full round-trip against the real backend for the configured
     * key: hold → name prompt (fresh install) → form → submit. The
     * success signal is the reporter dismissing itself — ReportScreen
     * only closes on a successful createIssueFromSdk response; any
     * failure keeps the form up with an inline error.
     *
     * Marked [RequiresLiveBackend] because it files a real issue in a
     * real project every time it runs. ci.yml's tv-e2e job runs it
     * anyway when enabled — the whole suite needs a live key, so
     * filtering this one out would buy nothing and hide a gap. Use the
     * annotation to exclude it locally when you do not want the noise:
     *
     *     ./gradlew :sample-tv-app:connectedDebugAndroidTest \
     *       -Pandroid.testInstrumentationRunnerArguments.notAnnotation=\
     *         no.issuetracker.sampletv.RequiresLiveBackend
     */
    @Test
    @RequiresLiveBackend
    fun fullSubmissionRoundTripSucceeds() {
        pressPlayPause(holdMs = 2_300)
        assertTrue("reporter did not open", reporterAppears(timeoutMs = 5_000))

        if (device.hasObject(By.text("What should we call you?"))) {
            val nameField = device.wait(
                Until.findObject(By.clazz("android.widget.EditText")), 3_000,
            )
            nameField.text = "E2E Bot"
            device.findObject(By.text("Continue"))?.click()
        }
        assertTrue(
            "report form did not appear",
            device.wait(Until.hasObject(By.text("Report an issue")), 5_000),
        )
        val titleField = device.wait(
            Until.findObject(By.clazz("android.widget.EditText")), 3_000,
        )
        titleField.text = "E2E: TV remote trigger verification"
        device.findObject(By.text("Send report"))?.click()

        assertTrue(
            "submission did not complete (form still up after 25 s)",
            device.wait(Until.gone(By.text("Send report")), 25_000),
        )
        assertFalse(
            "submission surfaced an error",
            device.hasObject(By.textStartsWith("Error:")),
        )
        // Form dismissal alone is AMBIGUOUS: a terminal server error
        // swaps the form for TerminatedScreen (ADR-0003), which also
        // removes "Send report". Only dismissal WITHOUT the terminal
        // screen is a genuine createIssueFromSdk success.
        SystemClock.sleep(1_000)
        failIfTerminated()
    }

    /** Injects ACTION_DOWN, waits, then ACTION_UP — a genuine hold. */
    private fun pressPlayPause(holdMs: Long) {
        val code = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        val downTime = SystemClock.uptimeMillis()
        inject(KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, code, 0))
        SystemClock.sleep(holdMs)
        inject(KeyEvent(downTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0))
    }

    private fun inject(event: KeyEvent) {
        event.source = InputDevice.SOURCE_KEYBOARD
        assertTrue(
            "key injection failed — is the screen on?",
            instrumentation.uiAutomation.injectInputEvent(event, true),
        )
    }

    /** The reporter's first screen is the name prompt on a fresh
     *  install, the report form otherwise — accept either. */
    private fun reporterAppears(timeoutMs: Long): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (device.hasObject(By.text("Report an issue")) ||
                device.hasObject(By.text("What should we call you?"))
            ) {
                return true
            }
            SystemClock.sleep(250)
        }
        return false
    }
}
