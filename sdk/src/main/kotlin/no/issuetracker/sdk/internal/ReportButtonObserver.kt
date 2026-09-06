package no.issuetracker.sdk.internal

import android.app.Activity
import android.app.Application
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import no.issuetracker.sdk.ui.OnboardingActivity
import no.issuetracker.sdk.ui.ReportActivity
import java.lang.ref.WeakReference

/**
 * Built-in visible entry point (ADR-0008 Decision 3). When enabled via
 * `showReportButton`, overlays a small floating "Report" pill on each
 * resumed host activity's decorView; tapping it opens the reporter.
 * This is the one-line path to WCAG 2.5.1/2.5.4 conformance for hosts
 * that enable gesture triggers but cannot (or will not) wire their own
 * visible control to `Issuetracker.report()`.
 *
 * Plain android.widget view rather than Compose: the trigger layer is
 * deliberately Compose-free (see ShakeObserver/LongPressObserver) so
 * it can attach to any host activity without imposing a composition.
 *
 * Lifecycle: install once at SDK configure(); the lifecycle callback
 * adds the button on resume and removes it on pause — same discipline
 * as [AccessibilityActionObserver]. Never shown on the SDK's own
 * activities, and never re-attached once the SDK is TERMINATED.
 * [setEnabled] is re-evaluated on every configure() call, so hosts can
 * flip the flag at runtime (a false value removes any visible button
 * immediately).
 */
internal object ReportButtonObserver {
    // Internal (not private) so ContrastTest can assert the white-on-pill
    // WCAG 1.4.3 ratio against the real value.
    internal const val BACKGROUND_COLOR = 0xFF1577AD.toInt()
    private const val RIPPLE_COLOR = 0x33FFFFFF
    private const val MARGIN_DP = 16
    private const val MIN_TOUCH_TARGET_DP = 48

    private var onTrigger: (() -> Unit)? = null
    private var installed = false

    @Volatile
    private var enabled = false

    // The one activity currently showing the button, plus the view we
    // added, so detach removes exactly ours and nothing of the host's.
    private var attached: WeakReference<Activity>? = null
    private var button: WeakReference<View>? = null

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
     * Called on every configure() with the current `showReportButton`
     * flag. Enabling attaches to the currently resumed activity (if
     * any); disabling removes any visible button immediately. The
     * lifecycle callback stays registered but inert while disabled.
     */
    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        if (value) {
            ActivityProvider.current()?.let { attach(it) }
        } else {
            detachCurrent()
        }
    }

    private fun attach(activity: Activity) {
        if (!enabled) return
        // Never on the SDK's own surfaces — the reporter is already
        // open (or opening) there.
        if (activity is ReportActivity || activity is OnboardingActivity) return
        // ADR-0003 Decision 9: a terminated SDK must not advertise a
        // report entry point. The button was removed when the host
        // activity paused under ReportActivity; it simply never comes
        // back after the transition.
        if (LifecycleStore.isTerminated) return
        if (attached?.get() === activity && button?.get() != null) return
        detachCurrent()
        val decor = activity.window?.decorView as? ViewGroup ?: return
        val view = buildButton(activity)
        val density = activity.resources.displayMetrics.density
        val margin = (MARGIN_DP * density).toInt()
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END,
        ).apply {
            bottomMargin = margin
            marginEnd = margin
        }
        // Keep the pill clear of gesture-nav bars, cutouts and 3-button
        // nav regardless of the host's edge-to-edge setup.
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            val params = v.layoutParams as? FrameLayout.LayoutParams
            if (params != null) {
                val isRtl = v.layoutDirection == View.LAYOUT_DIRECTION_RTL
                params.bottomMargin = margin + bars.bottom
                params.marginEnd = margin + if (isRtl) bars.left else bars.right
                v.layoutParams = params
            }
            insets
        }
        decor.addView(view, lp)
        ViewCompat.requestApplyInsets(view)
        attached = WeakReference(activity)
        button = WeakReference(view)
    }

    private fun buildButton(activity: Activity): View {
        val density = activity.resources.displayMetrics.density
        val minSize = (MIN_TOUCH_TARGET_DP * density).toInt()
        val pill = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = minSize / 2f
            setColor(BACKGROUND_COLOR)
        }
        return Button(activity).apply {
            text = "Report"
            contentDescription = "Report a bug"
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = RippleDrawable(ColorStateList.valueOf(RIPPLE_COLOR), pill, null)
            minWidth = minSize
            minHeight = minSize
            minimumWidth = minSize
            minimumHeight = minSize
            val hPad = (20 * density).toInt()
            setPadding(hPad, 0, hPad, 0)
            elevation = 6 * density
            setOnClickListener { onTrigger?.invoke() }
        }
    }

    private fun detach(activity: Activity) {
        if (attached?.get() !== activity) return
        detachCurrent()
    }

    private fun detachCurrent() {
        val view = button?.get()
        (view?.parent as? ViewGroup)?.removeView(view)
        attached = null
        button = null
    }

    /**
     * Test-only — removes any attached button and forgets the install
     * flag so a fresh Application (Robolectric hands out a new one per
     * test) can register its own lifecycle callbacks. Same seam as
     * `LifecycleStore.resetForTesting()`.
     */
    internal fun resetForTesting() {
        detachCurrent()
        enabled = false
        installed = false
        onTrigger = null
    }
}
