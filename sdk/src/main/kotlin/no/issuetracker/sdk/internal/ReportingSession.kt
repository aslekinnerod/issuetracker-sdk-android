package no.issuetracker.sdk.internal

import android.content.Intent
import android.graphics.Bitmap
import no.issuetracker.sdk.IssueReportType
import no.issuetracker.sdk.Runtime
import no.issuetracker.sdk.SdkErrorReason
import no.issuetracker.sdk.ui.ReportActivity
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
            // ADR-0003 Decision 9: when terminated, the activity will
            // render TerminatedScreen — skip the screenshot capture
            // since no report form will be shown.
            pendingScreenshot = if (LifecycleStore.isTerminated) null
                                else ScreenshotCapture.captureCurrentActivity()
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
            // Attach attestation whenever we hold a token — in open
            // mode it still stamps the report with the tester's
            // identity (ADR-0005 Decision 5); in testers-only mode
            // it's what gets us past ingest.
            AttestationStore.testerToken?.let { payload.put("testerToken", it) }
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
        } catch (e: ApiException) {
            val reason = e.sdkErrorReason
            // Tester-gating rejections (ADR-0005) are non-recoverable
            // but NOT terminal — the project is alive, this install
            // just lacks valid attestation. Drop any stale token and
            // re-pull config so the gesture triggers go inert; the
            // user sees the server's message in the current activity.
            if (reason != null && reason.isTesterGating) {
                if (reason == SdkErrorReason.TESTER_TOKEN_INVALID) {
                    AttestationStore.clearTesterToken()
                }
                scope.launch { AttestationStore.refreshRemoteConfig(runtime) }
                return Result.failure(e)
            }
            // ADR-0003 Decision 9: terminal failures flip the SDK
            // into one-way TERMINATED. The user sees this submit's
            // error message in the current activity; the next trigger
            // (shake / long-press / programmatic) will hit the
            // pre-flight gate in present() and surface TerminatedScreen.
            if (reason != null && reason.isTerminal) {
                LifecycleStore.transitionToTerminated(reason, runtime.onConfigurationError)
            }
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
