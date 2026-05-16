package no.issuetracker.sdk.internal

import android.app.Application
import no.issuetracker.sdk.Runtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Heartbeat-based crash detector. On install we write a marker file;
 * on the next launch, if a crash report exists from the previous
 * session we send it through the SDK like a manually filed issue.
 *
 * Limitations: we can't distinguish between an uncaught exception, an
 * OOM kill, and a force-quit by the user. The recorded `exceptionName`
 * / `exceptionReason` fields are only populated for true Java/Kotlin
 * exceptions. Native crashes are not captured (would require an NDK
 * signal handler — outside v1 scope).
 */
internal object CrashReporter {
    private const val PENDING_FILE = "pending_crash.json"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun pendingFile(application: Application): File =
        File(File(application.filesDir, "issuetracker").apply { mkdirs() }, PENDING_FILE)

    fun install(application: Application) {
        val pf = pendingFile(application)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val report = JSONObject()
                report.put("detectedAt", System.currentTimeMillis())
                report.put("exceptionName", throwable.javaClass.name)
                report.put("exceptionReason", throwable.message ?: "")
                pf.writeText(report.toString())
            } catch (_: Throwable) {
                // Don't let crash-reporting itself crash the process.
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun reportCrashIfAny(rt: Runtime) {
        val pf = pendingFile(rt.application)
        if (!pf.exists()) return
        val crashJson = try { JSONObject(pf.readText()) } catch (_: Exception) { null }
        pf.delete()
        if (crashJson == null) return
        val crumbs = BreadcrumbStore.snapshot()
        scope.launch {
            try {
                val payload = JSONObject()
                payload.put("apiKey", rt.apiKey)
                payload.put("title", "Crash: ${crashJson.optString("exceptionName", "Unknown")}")
                payload.put("description", crashJson.optString("exceptionReason", ""))
                payload.put("type", "bug")
                payload.put("context", ContextCollector.collect(rt.application))
                payload.put("reporter", ReporterIdentity.payload())
                payload.put("crashReport", crashJson)
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
                ApiClient.call(rt.endpoint, "createIssueFromSdk", payload)
                BreadcrumbStore.clear()
            } catch (_: Exception) {
                // Best-effort; will retry on next launch if pending file
                // hasn't been deleted (it has, so this drops the report).
            }
        }
    }
}
