package io.issuetracker.sdk.internal

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.util.Locale
import java.util.TimeZone

/**
 * Gathers non-PII device + app metadata useful when triaging a report.
 * Only public, no-permission APIs. Deliberately avoids Build.SERIAL,
 * IMEI, advertising ID, and anything tied to the user's identity.
 */
internal object ContextCollector {
    fun collect(context: Context): JSONObject {
        val out = JSONObject()
        out.put("platform", "Android")
        out.put("osVersion", Build.VERSION.RELEASE ?: "")
        out.put("deviceModel", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
        out.put("locale", Locale.getDefault().toLanguageTag())
        out.put("timeZone", TimeZone.getDefault().id)
        out.put("appBundleId", context.packageName)
        try {
            val pm = context.packageManager
            val info = pm.getPackageInfo(context.packageName, 0)
            out.put("appVersion", info.versionName ?: "")
            val build = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode.toString()
            } else {
                @Suppress("DEPRECATION") info.versionCode.toString()
            }
            out.put("appBuild", build)
        } catch (_: Exception) {
            // Ignore — appVersion/appBuild are optional in the wire schema.
        }
        return out
    }
}
