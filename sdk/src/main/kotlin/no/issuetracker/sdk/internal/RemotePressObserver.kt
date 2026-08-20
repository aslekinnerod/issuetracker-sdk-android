package no.issuetracker.sdk.internal

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.Window

/**
 * TV trigger: hold Play/Pause on the remote for 2 seconds. The TV
 * sibling of the two-finger long-press — installed INSTEAD of
 * [LongPressObserver] on television form factors, under the same
 * `longPressToReport` flag. Contract: sdk-spec/TV_TRIGGERS.md.
 *
 * Play/Pause is the only safe remote button: Back is system
 * navigation, D-pad center carries every focus interaction (and its
 * long-press is the platform convention for context menus). A short
 * Play/Pause tap is common in playback UIs, but a 2-second HOLD has
 * no system meaning and no common in-app meaning.
 *
 * Implemented by replacing each Activity's Window.Callback with a
 * delegating wrapper that taps `dispatchKeyEvent`. Events are only
 * observed, never consumed — per the contract, the initial key-down
 * may reach the host (a playback surface may toggle pause before the
 * reporter opens at 2 s); the SDK must never eat a normal tap.
 *
 * Lifecycle: install once at SDK configure(); the lifecycle callback
 * attaches/detaches per Activity automatically — same pattern as
 * [LongPressObserver].
 */
internal object RemotePressObserver {
    /** TV_TRIGGERS.md: 2000 ms on every TV platform. Deliberately NOT
     *  the 3000 ms touch value — holding a physical button feels
     *  longer than holding a touch gesture. */
    internal const val HOLD_MS = 2_000L

    private var onTrigger: (() -> Unit)? = null
    private var installed = false

    fun install(application: Application, onTrigger: () -> Unit) {
        if (installed) return
        installed = true
        this.onTrigger = onTrigger
        application.registerActivityLifecycleCallbacks(object :
            Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) = attach(activity)
            override fun onActivityPaused(activity: Activity) = detach(activity)
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) = detach(activity)
        })
    }

    /**
     * The Play/Pause key in all its remote incarnations: combined
     * KEYCODE_MEDIA_PLAY_PAUSE (Google TV, Fire TV reference remotes)
     * plus the split PLAY / PAUSE codes some third-party and IR
     * remotes send instead. Holding any of them is "holding
     * Play/Pause" in the sense of the contract.
     */
    internal fun isPlayPauseKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE,
        -> true
        else -> false
    }

    private fun attach(activity: Activity) {
        val window = activity.window ?: return
        val original = window.callback ?: return
        if (original is Wrapper) return
        window.callback = Wrapper(original) { onTrigger?.invoke() }
    }

    private fun detach(activity: Activity) {
        val window = activity.window ?: return
        val current = window.callback as? Wrapper ?: return
        window.callback = current.original
    }

    /**
     * Pure hold-detection state machine, separated from the window
     * plumbing so it's unit-testable. Fires once when the threshold
     * is reached (not on release); auto-repeat key-downs are ignored;
     * releasing Play/Pause early or pressing any other key cancels.
     * ACTION_UP of OTHER keys is deliberately ignored — a stray
     * release from a previous press must not cancel a deliberate hold
     * that started just after it.
     */
    internal class KeyHoldDetector(
        private val holdMs: Long = HOLD_MS,
        private val handler: Handler = Handler(Looper.getMainLooper()),
        private val fire: () -> Unit,
    ) {
        private var tracking = false
        private val trigger = Runnable {
            tracking = false
            fire()
        }

        fun onKeyEvent(event: KeyEvent) {
            val playPause = isPlayPauseKey(event.keyCode)
            when {
                playPause && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 -> {
                    cancel()
                    tracking = true
                    handler.postDelayed(trigger, holdMs)
                }
                // Auto-repeat downs while held: keep the timer running.
                playPause && event.action == KeyEvent.ACTION_DOWN -> Unit
                playPause && event.action == KeyEvent.ACTION_UP -> cancel()
                !playPause && event.action == KeyEvent.ACTION_DOWN -> cancel()
            }
        }

        private fun cancel() {
            if (!tracking) return
            handler.removeCallbacks(trigger)
            tracking = false
        }
    }

    private class Wrapper(
        val original: Window.Callback,
        fire: () -> Unit,
    ) : Window.Callback by original {
        private val detector = KeyHoldDetector(fire = fire)

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            detector.onKeyEvent(event)
            // Never consume — observation only (TV_TRIGGERS.md).
            return original.dispatchKeyEvent(event)
        }
    }
}
