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
    }

    @Test
    fun twoSecondHoldOpensReporter() {
        pressPlayPause(holdMs = 2_300)
        assertTrue(
            "reporter did not open after a 2.3 s hold",
            reporterAppears(timeoutMs = 5_000),
        )
    }

    /**
     * Full round-trip against the real backend for the configured
     * key: hold → name prompt (fresh install) → form → submit. The
     * success signal is the reporter dismissing itself — ReportScreen
     * only closes on a successful createIssueFromSdk response; any
     * failure keeps the form up with an inline error.
     */
    @Test
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
