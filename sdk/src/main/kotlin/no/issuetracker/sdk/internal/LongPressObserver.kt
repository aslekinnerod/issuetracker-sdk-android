package no.issuetracker.sdk.internal

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.Window

/**
 * Two-finger long-press (3s) trigger. Same gesture as iOS + web for
 * cross-platform consistency.
 *
 * Implemented by replacing each Activity's Window.Callback with a
 * delegating wrapper that taps `dispatchTouchEvent` to track 2-finger
 * presses. The original callback handles every event normally — we
 * only observe — so host-app gestures keep working.
 *
 * Lifecycle: install once at SDK configure(); the lifecycle callback
 * attaches/detaches per Activity automatically.
 */
internal object LongPressObserver {
    private const val MIN_PRESS_MS = 3_000L
    // ~30dp at xhdpi. Generous tolerance — finger jitter while
    // intentionally holding still is normal.
    private const val MOVE_TOLERANCE_PX_SQ = 60f * 60f

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

    private class Wrapper(
        val original: Window.Callback,
        val fire: () -> Unit,
    ) : Window.Callback by original {
        private val handler = Handler(Looper.getMainLooper())
        private var startCoords: List<Pair<Float, Float>>? = null
        private val trigger = Runnable {
            startCoords = null
            fire()
        }

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (event.pointerCount == 2) {
                        startCoords = listOf(
                            event.getX(0) to event.getY(0),
                            event.getX(1) to event.getY(1),
                        )
                        handler.postDelayed(trigger, MIN_PRESS_MS)
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    val start = startCoords
                    if (start != null) {
                        if (event.pointerCount != 2) {
                            cancel()
                        } else {
                            val moved = (0 until 2).any { i ->
                                val (sx, sy) = start[i]
                                val dx = event.getX(i) - sx
                                val dy = event.getY(i) - sy
                                dx * dx + dy * dy > MOVE_TOLERANCE_PX_SQ
                            }
                            if (moved) cancel()
                        }
                    }
                }
                MotionEvent.ACTION_POINTER_UP,
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> cancel()
            }
            return original.dispatchTouchEvent(event)
        }

        private fun cancel() {
            handler.removeCallbacks(trigger)
            startCoords = null
        }
    }
}
