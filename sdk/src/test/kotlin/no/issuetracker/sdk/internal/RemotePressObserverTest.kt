package no.issuetracker.sdk.internal

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * Contract tests for the TV hold-to-report trigger — see
 * sdk-spec/TV_TRIGGERS.md: fire once when the 2000 ms threshold is
 * reached (not on release), ignore auto-repeat key-downs, cancel on
 * early release or any other key-down, and never fire twice for one
 * hold.
 */
@RunWith(RobolectricTestRunner::class)
class RemotePressObserverTest {

    private var fired = 0

    private fun detector() = RemotePressObserver.KeyHoldDetector(
        handler = Handler(Looper.getMainLooper()),
        fire = { fired++ },
    )

    private fun key(action: Int, code: Int, repeat: Int = 0): KeyEvent {
        val now = SystemClock.uptimeMillis()
        return KeyEvent(now, now, action, code, repeat)
    }

    private fun advance(ms: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    }

    @Test
    fun firesOnceWhenThresholdReached() {
        val d = detector()
        d.onKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        advance(1_999)
        assertEquals(0, fired)
        advance(1)
        assertEquals(1, fired)
        // Still held long past the threshold — no repeat fire.
        advance(5_000)
        assertEquals(1, fired)
    }

    @Test
    fun firesAtThresholdNotOnRelease() {
        val d = detector()
        d.onKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        advance(2_000)
        assertEquals(1, fired)
        // The eventual release must not fire again.
        d.onKeyEvent(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        advance(2_000)
        assertEquals(1, fired)
    }

    @Test
    fun earlyReleaseCancels() {
        val d = detector()
        d.onKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        advance(1_000)
        d.onKeyEvent(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        advance(5_000)
        assertEquals(0, fired)
    }

    @Test
    fun autoRepeatDownsDoNotRestartTheTimer() {
        val d = detector()
        d.onKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        advance(1_500)
        // System auto-repeat while held: repeatCount > 0.
        d.onKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, repeat = 3))
        advance(500)
        // Fires 2000 ms after the ORIGINAL down, not 2000 ms after the repeat.
        assertEquals(1, fired)
    }

    @Test
    fun otherKeyDownCancels() {
        val d = detector()
        d.onKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        advance(1_000)
        d.onKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
        advance(5_000)
        assertEquals(0, fired)
    }

    @Test
    fun strayReleaseOfOtherKeyDoesNotCancel() {
        // A previous press's ACTION_UP arriving just after the hold
        // starts must not cancel a deliberate hold.
        val d = detector()
        d.onKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        advance(100)
        d.onKeyEvent(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN))
        advance(1_900)
        assertEquals(1, fired)
    }

    @Test
    fun splitPlayAndPauseKeycodesAlsoTrigger() {
        val d = detector()
        d.onKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
        advance(2_000)
        assertEquals(1, fired)

        d.onKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
        advance(2_000)
        assertEquals(2, fired)
    }

    @Test
    fun secondHoldFiresAgain() {
        val d = detector()
        d.onKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        advance(2_000)
        d.onKeyEvent(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        d.onKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        advance(2_000)
        assertEquals(2, fired)
    }

    @Test
    fun keycodeClassification() {
        assertTrue(RemotePressObserver.isPlayPauseKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertTrue(RemotePressObserver.isPlayPauseKey(KeyEvent.KEYCODE_MEDIA_PLAY))
        assertTrue(RemotePressObserver.isPlayPauseKey(KeyEvent.KEYCODE_MEDIA_PAUSE))
        assertFalse(RemotePressObserver.isPlayPauseKey(KeyEvent.KEYCODE_DPAD_CENTER))
        assertFalse(RemotePressObserver.isPlayPauseKey(KeyEvent.KEYCODE_BACK))
        assertFalse(RemotePressObserver.isPlayPauseKey(KeyEvent.KEYCODE_MEDIA_STOP))
    }

    @Test
    fun holdDurationMatchesTvTriggersContract() {
        // TV_TRIGGERS.md: 2000 ms, deliberately not the 3000 ms touch
        // value. A "harmonising" change here breaks the spec.
        assertEquals(2_000L, RemotePressObserver.HOLD_MS)
    }
}
