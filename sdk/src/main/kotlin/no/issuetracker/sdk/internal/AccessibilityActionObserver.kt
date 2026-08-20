package no.issuetracker.sdk.internal

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityManager
import androidx.core.view.ViewCompat
import no.issuetracker.sdk.ui.OnboardingActivity
import no.issuetracker.sdk.ui.ReportActivity
import java.lang.ref.WeakReference

/**
 * Screen-reader trigger (ADR-0008 Decision 2). While an accessibility
 * service with touch exploration is running (TalkBack), registers a
 * custom accessibility action "Report a bug" on the resumed Activity's
 * decorView. TalkBack announces it in the actions menu, giving
 * screen-reader users a gesture-free path into the reporter — the
 * population most likely to be locked out of both shake (motion
 * actuation, WCAG 2.5.4) and the two-finger long-press (multipoint
 * gesture, WCAG 2.5.1, typically swallowed by TalkBack's own
 * multi-finger gestures).
 *
 * Lifecycle: install once at SDK configure(); the lifecycle callback
 * attaches/detaches per Activity automatically — same discipline as
 * [LongPressObserver]. The action also appears/disappears live with
 * the touch-exploration state, so it exists only while a screen
 * reader is actually driving the device. [setEnabled] is re-evaluated
 * on every configure() call, so hosts can flip the flag at runtime.
 */
internal object AccessibilityActionObserver {
    private const val ACTION_LABEL = "Report a bug"

    private var onTrigger: (() -> Unit)? = null
    private var installed = false

    @Volatile
    private var enabled = false

    @Volatile
    private var touchExplorationOn = false

    private var stateListener: AccessibilityManager.TouchExplorationStateChangeListener? = null

    // The one activity currently carrying the action, plus the id
    // ViewCompat handed back, so detach removes exactly what attach
    // added and nothing of the host's own actions.
    private var attached: WeakReference<Activity>? = null
    private var actionId = View.NO_ID

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
     * Called on every configure() with the current `accessibilityAction`
     * flag. Enabling registers the touch-exploration listener and
     * attaches to the currently resumed activity (if any); disabling
     * unregisters the listener and removes any registered action
     * immediately — no uninstall plumbing needed for the lifecycle
     * callback itself, which stays registered but inert.
     */
    fun setEnabled(application: Application, value: Boolean) {
        if (enabled == value) return
        enabled = value
        val am = application
            .getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        if (value) {
            touchExplorationOn = am?.isTouchExplorationEnabled == true
            if (am != null && stateListener == null) {
                val listener = AccessibilityManager.TouchExplorationStateChangeListener { on ->
                    // Called on the main thread. Reconcile immediately so
                    // the action tracks TalkBack without waiting for the
                    // next pause/resume cycle.
                    touchExplorationOn = on
                    if (on) {
                        ActivityProvider.current()?.let { attach(it) }
                    } else {
                        detachCurrent()
                    }
                }
                stateListener = listener
                am.addTouchExplorationStateChangeListener(listener)
            }
            ActivityProvider.current()?.let { attach(it) }
        } else {
            stateListener?.let { am?.removeTouchExplorationStateChangeListener(it) }
            stateListener = null
            detachCurrent()
        }
    }

    private fun attach(activity: Activity) {
        if (!enabled || !touchExplorationOn) return
        // Never on the SDK's own surfaces — the reporter is already
        // open (or opening), so advertising "Report a bug" there would
        // only re-enter the flow.
        if (activity is ReportActivity || activity is OnboardingActivity) return
        // ADR-0003 Decision 9: a terminated SDK must not advertise a
        // report entry point. The action simply never re-attaches after
        // the transition (the host activity was paused — and the action
        // removed — while ReportActivity was on top).
        if (LifecycleStore.isTerminated) return
        if (attached?.get() === activity && actionId != View.NO_ID) return
        detachCurrent()
        val decor = activity.window?.decorView ?: return
        val id = ViewCompat.addAccessibilityAction(decor, ACTION_LABEL) { _, _ ->
            onTrigger?.invoke()
            true
        }
        if (id != View.NO_ID) {
            attached = WeakReference(activity)
            actionId = id
        }
    }

    private fun detach(activity: Activity) {
        if (attached?.get() !== activity) return
        detachCurrent()
    }

    private fun detachCurrent() {
        val activity = attached?.get()
        if (activity != null && actionId != View.NO_ID) {
            activity.window?.decorView?.let {
                ViewCompat.removeAccessibilityAction(it, actionId)
            }
        }
        attached = null
        actionId = View.NO_ID
    }
}
