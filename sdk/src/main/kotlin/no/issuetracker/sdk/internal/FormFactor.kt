package no.issuetracker.sdk.internal

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

/**
 * Form-factor detection. One artifact serves phones/tablets and TVs
 * (Android TV, Google TV, Fire TV), so trigger wiring is decided at
 * configure-time from the runtime form factor rather than at build
 * time — see sdk-spec/TV_TRIGGERS.md.
 *
 * `resources.configuration.uiMode` is the primary signal (it is what
 * the system itself uses to pick leanback behaviour); the feature
 * flags are belt-and-suspenders for devices that expose leanback
 * without the TV uiMode. `amazon.hardware.fire_tv` covers Fire OS
 * devices, which are Android TV in all the ways this SDK cares about.
 */
internal object FormFactor {

    fun isTelevision(context: Context): Boolean {
        val uiMode = context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
        if (uiMode == Configuration.UI_MODE_TYPE_TELEVISION) return true
        val pm = context.packageManager
        return pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            pm.hasSystemFeature("amazon.hardware.fire_tv")
    }
}
