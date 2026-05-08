package io.issuetracker.sdk.internal

import android.content.Intent
import android.graphics.Bitmap
import io.issuetracker.sdk.IssueReportType
import io.issuetracker.sdk.Runtime
import io.issuetracker.sdk.ui.ReportActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Orchestrates the "capture screenshot → launch ReportActivity →
 * submit" flow. The screenshot is captured in the host Activity's
 * window before ReportActivity is launched (the host's content is
 * still on-screen at that moment). We hand the runtime + bitmap to
 * ReportActivity via this singleton because Bitmap is too large for
 * Intent extras.
 */
internal object ReportingSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Volatile private var pendingRuntime: Runtime? = null
    @Volatile private var pendingScreenshot: Bitmap? = null

    fun present(runtime: Runtime) {
        scope.launch {
            val activity = ActivityProvider.current() ?: return@launch
            pendingRuntime = runtime
            pendingScreenshot = ScreenshotCapture.captureCurrentActivity()
            activity.startActivity(
                Intent(activity, ReportActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION),
            )
        }
    }

    /** Called by ReportActivity.onCreate. Returns null if the handoff
     *  was already consumed (e.g. config-change recreate). */
    fun consume(): Pair<Runtime, Bitmap?>? {
        val rt = pendingRuntime ?: return null
        val ss = pendingScreenshot
        pendingRuntime = null
        pendingScreenshot = null
        return rt to ss
    }

    suspend fun submit(
        runtime: Runtime,
        title: String,
        description: String,
        type: IssueReportType,
        screenshot: Bitmap?,
    ): Result<JSONObject> {
        return try {
            val payload = JSONObject()
            payload.put("apiKey", runtime.apiKey)
            payload.put("title", title)
            payload.put("type", type.wireValue)
            payload.put("context", ContextCollector.collect(runtime.application))
            payload.put("reporter", ReporterIdentity.payload())
            if (description.isNotEmpty()) payload.put("description", description)
            if (screenshot != null) {
                val baos = ByteArrayOutputStream()
                screenshot.compress(Bitmap.CompressFormat.JPEG, 85, baos)
                val b64 = android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
                val ssJson = JSONObject()
                ssJson.put("base64", b64)
                ssJson.put("contentType", "image/jpeg")
                ssJson.put("name", "screenshot-${System.currentTimeMillis() / 1000}.jpg")
                payload.put("screenshot", ssJson)
            }
            val crumbs = BreadcrumbStore.snapshot()
            if (crumbs.isNotEmpty()) {
                val arr = JSONArray()
                crumbs.forEach { c ->
                    val o = JSONObject()
                    o.put("timestamp", c.timestamp)
                    o.put("action", c.action)
                    c.metadata?.let { meta ->
                        val mo = JSONObject()
                        meta.forEach { (k, v) -> mo.put(k, v) }
                        o.put("metadata", mo)
                    }
                    arr.put(o)
                }
                payload.put("breadcrumbs", arr)
            }
            val result = ApiClient.call(runtime.endpoint, "createIssueFromSdk", payload)
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
