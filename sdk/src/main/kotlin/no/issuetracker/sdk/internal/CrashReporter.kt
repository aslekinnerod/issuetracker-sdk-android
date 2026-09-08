package no.issuetracker.sdk.internal

import android.app.Application
import android.content.Context
import no.issuetracker.sdk.Runtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
 * The marker file is the only outbound queue this SDK keeps on disk,
 * so it is also what ADR-0003 Decision 9.6 is talking about: it is
 * persisted while the SDK is `OK`, and dropped atomically on the first
 * `TERMINATED` signal (see [purgePendingQueue], called from
 * [LifecycleStore.transitionToTerminated]).
 *
 * Limitations: we can't distinguish between an uncaught exception, an
 * OOM kill, and a force-quit by the user. The recorded `exceptionName`
 * / `exceptionReason` fields are only populated for true Java/Kotlin
 * exceptions. Native crashes are not captured (would require an NDK
 * signal handler — outside v1 scope).
 */
internal object CrashReporter {
    private const val PENDING_FILE = "pending_crash.json"

    /**
     * Bookkeeping key, stripped before the report goes on the wire.
     * A crash we could not deliver stays on disk for the next launch
     * (Decision 9.6: "persist queued reports to disk while OK"), but
     * a report the server keeps refusing for an unclassified reason
     * must not be retried forever.
     */
    private const val ATTEMPTS_KEY = "deliveryAttempts"
    private const val MAX_DELIVERY_ATTEMPTS = 3

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun pendingFile(context: Context): File =
        File(File(context.filesDir, "issuetracker").apply { mkdirs() }, PENDING_FILE)

    private fun deleteQuietly(file: File) {
        runCatching { file.delete() }
    }

    /**
     * ADR-0003 Decision 9.6 — "drop the queue atomically on first
     * TERMINATED signal". Called synchronously from
     * [LifecycleStore.transitionToTerminated] so the queue is gone
     * before anything can observe the terminated state, and so a
     * process restart has nothing left to re-attempt. One `unlink`,
     * once per install; deliberately not dispatched off-thread,
     * because "atomically" is worth more here than a sub-millisecond
     * disk touch on the caller's thread.
     */
    fun purgePendingQueue(context: Context) {
        deleteQuietly(pendingFile(context))
    }

    fun install(application: Application) {
        // Resolved once, at install time: the handler runs while the
        // process is already dying, so it should not be creating
        // directories.
        val pf = pendingFile(application)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                // ADR-0003 Decision 9.2: a terminated SDK runs no
                // background work. Recording a crash we are forbidden
                // to deliver would only re-create the queue we just
                // purged.
                if (!LifecycleStore.isTerminated) {
                    val report = JSONObject()
                    report.put("detectedAt", System.currentTimeMillis())
                    report.put("exceptionName", throwable.javaClass.name)
                    report.put("exceptionReason", throwable.message ?: "")
                    pf.writeText(report.toString())
                }
            } catch (_: Throwable) {
                // Don't let crash-reporting itself crash the process.
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /**
     * Delivers the previous session's crash, if there is one.
     *
     * Returns the delivery [Job] (or null when there was nothing to
     * send) purely as a test seam — production callers ignore it.
     */
    fun reportCrashIfAny(rt: Runtime): Job? {
        val pf = pendingFile(rt.application)
        if (!pf.exists()) return null

        // ADR-0003 Decision 9: a terminated install must not talk to
        // the endpoint at all — and this is the one path that would
        // otherwise do it silently, in the background, on a launch
        // where the user never opens the reporting surface. Purge
        // rather than merely skip, so the marker cannot be picked up
        // by some later code path.
        if (LifecycleStore.isTerminated) {
            deleteQuietly(pf)
            return null
        }

        val crashJson = try { JSONObject(pf.readText()) } catch (_: Exception) { null }
        if (crashJson == null) {
            // Unparseable marker: nothing to deliver, ever.
            deleteQuietly(pf)
            return null
        }
        val attempt = crashJson.optInt(ATTEMPTS_KEY, 0) + 1
        crashJson.remove(ATTEMPTS_KEY)
        if (attempt > MAX_DELIVERY_ATTEMPTS) {
            deleteQuietly(pf)
            return null
        }

        val crumbs = BreadcrumbStore.snapshot()
        return scope.launch {
            try {
                val payload = JSONObject()
                payload.put("apiKey", rt.apiKey)
                payload.put("title", "Crash: ${crashJson.optString("exceptionName", "Unknown")}")
                payload.put("description", crashJson.optString("exceptionReason", ""))
                payload.put("type", "bug")
                payload.put("context", ContextCollector.collect(rt.application))
                payload.put("reporter", ReporterIdentity.payload())
                payload.put("crashReport", crashJson)
                // Auto crash reports carry attestation too — on
                // testers-only projects an unattested crash report is
                // (correctly) rejected at ingest and dropped below.
                AttestationStore.testerToken?.let { payload.put("testerToken", it) }
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
                // Delivered — only now is it safe to drop.
                deleteQuietly(pf)
                BreadcrumbStore.clear()
            } catch (e: ApiException) {
                // ADR-0003 Decision 9: the crash upload is a dispatch
                // site like any other. It runs in the background,
                // hours after the crash, on a launch nobody is
                // watching — swallowing a terminal `details.error`
                // here is precisely how an install keeps talking to a
                // dead project forever. Same predicate as the submit
                // and config paths: `SdkErrorReason.isTerminal`.
                val reason = e.sdkErrorReason
                when {
                    reason != null && reason.isTerminal -> {
                        // The transition purges the queue itself; the
                        // explicit delete keeps this path correct even
                        // if we were beaten to the transition.
                        LifecycleStore.transitionToTerminated(reason, rt.onConfigurationError)
                        deleteQuietly(pf)
                    }
                    // Non-recoverable but not terminal (ADR-0005
                    // tester gating): the project is alive, but
                    // re-POSTing this identical unattested payload
                    // can never succeed. Drop it.
                    reason != null && !reason.isRecoverable -> deleteQuietly(pf)
                    // Recoverable (quota, transient) or an error the
                    // server sent no contract for: the project is not
                    // dead, so the report is not ours to throw away.
                    // Keep it for the next launch.
                    else -> retainForNextLaunch(pf, crashJson, attempt)
                }
            } catch (_: Exception) {
                // Network-level failure — offline, DNS, timeout. Same
                // reasoning: nothing told us the project is gone.
                retainForNextLaunch(pf, crashJson, attempt)
            }
        }
    }

    /**
     * Puts an undelivered crash back on disk with its attempt count
     * bumped. Re-checks the lifecycle first: a concurrent terminal
     * signal on another path (a submit, the config fetch) may have
     * purged the queue while this upload was in flight, and the purge
     * must win — otherwise we would resurrect a report for a project
     * the server has already told us is gone.
     */
    private fun retainForNextLaunch(pf: File, crashJson: JSONObject, attempt: Int) {
        if (LifecycleStore.isTerminated) {
            deleteQuietly(pf)
            return
        }
        runCatching {
            pf.writeText(crashJson.put(ATTEMPTS_KEY, attempt).toString())
        }
    }
}
