package no.issuetracker.sdk.internal

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import no.issuetracker.sdk.IssueReportType
import no.issuetracker.sdk.Issuetracker
import no.issuetracker.sdk.Runtime
import no.issuetracker.sdk.SdkErrorReason
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * End-to-end contract tests for ADR-0003 Decision 9, driven through the
 * real network path: a local HTTP server speaks the Firebase callable
 * wire format, [ApiClient] parses it, [ReportingSession] dispatches on
 * it, and [LifecycleStore] persists the outcome.
 *
 * This is the layer ADR-0003's implementation status called out as
 * deferred ("integration tests against the Firebase callable surface").
 * The unit suites (`SdkErrorReasonTest`, `LifecycleStoreTest`) cover the
 * enum and the store in isolation; what was untested until now is that
 * an actual HTTP response with a `details` object reaches the store —
 * and, critically, that the SDK dispatches on `details.error` rather
 * than on the HTTP status.
 *
 * Mirrors the sdk-ios and sdk-web suites of the same name.
 */
@RunWith(RobolectricTestRunner::class)
class TerminationWireContractTest {

    private lateinit var server: FakeCallableServer
    private val received = CopyOnWriteArrayList<SdkErrorReason>()

    private fun app(): Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        LifecycleStore.resetForTesting()
        AttestationStore.resetForTesting()
        clearPrefs("no.issuetracker.sdk.lifecycle")
        clearPrefs("no.issuetracker.sdk.attestation")
        received.clear()
        LifecycleStore.install(app())
        ReporterIdentity.install(app())
        BreadcrumbStore.install(app())
        AttestationStore.install(app(), API_KEY)
        // Dev-prefixed key: fail-open, so `canTriggerReport` never
        // depends on a token in these tests.
        server = FakeCallableServer().also { it.start() }
    }

    @After
    fun tearDown() {
        server.stop()
        LifecycleStore.resetForTesting()
        AttestationStore.resetForTesting()
    }

    // --- 1. Every non-recoverable reason terminates -------------------

    @Test
    fun `project_deleted terminates and reports the reason`() {
        server.replyWithError(404, "project_deleted", recoverable = false) {
            it.put("deletedAt", 1747000000000L)
        }
        val result = submit()

        assertTrue(result.isFailure)
        assertTrue(LifecycleStore.isTerminated)
        assertEquals(listOf(SdkErrorReason.PROJECT_DELETED), received)
    }

    @Test
    fun `project_not_found terminates`() {
        assertTerminates(404, SdkErrorReason.PROJECT_NOT_FOUND)
    }

    @Test
    fun `api_key_revoked terminates`() {
        assertTerminates(403, SdkErrorReason.API_KEY_REVOKED)
    }

    @Test
    fun `workspace_suspended terminates`() {
        assertTerminates(403, SdkErrorReason.WORKSPACE_SUSPENDED)
    }

    @Test
    fun `invalid_api_key terminates`() {
        assertTerminates(401, SdkErrorReason.INVALID_API_KEY)
    }

    /** Runs one terminal reason at its ADR-mandated HTTP status. */
    private fun assertTerminates(status: Int, reason: SdkErrorReason) {
        server.replyWithError(status, reason.rawValue, recoverable = false)
        val result = submit()

        assertTrue(result.isFailure)
        assertTrue(LifecycleStore.isTerminated)
        assertEquals(listOf(reason), received)
        assertEquals(
            reason.rawValue,
            lifecyclePrefs().getString("terminated_reason", null),
        )
    }

    // --- 2. Recoverable reasons do NOT terminate ----------------------

    @Test
    fun `quota_exceeded does not terminate`() {
        server.replyWithError(429, "quota_exceeded", recoverable = true) {
            it.put("retryAfterSeconds", 30)
        }
        val result = submit()

        assertTrue(result.isFailure)
        assertFalse(LifecycleStore.isTerminated)
        assertTrue(received.isEmpty())
        assertNull(lifecyclePrefs().getString("terminated_reason", null))
    }

    @Test
    fun `transient does not terminate`() {
        server.replyWithError(503, "transient", recoverable = true)
        submit()

        assertFalse(LifecycleStore.isTerminated)
        assertTrue(received.isEmpty())
    }

    @Test
    fun `tester gating rejection does not terminate`() {
        // ADR-0005: non-recoverable, but the project is alive — this
        // install just lacks attestation. Must never be terminal.
        server.replyWithError(403, "tester_attestation_required", recoverable = false)
        submit()

        assertFalse(LifecycleStore.isTerminated)
        assertTrue(received.isEmpty())
    }

    // --- 3. Dispatch is on details.error, never the HTTP status -------

    @Test
    fun `terminal reason on a non-terminal status still terminates`() {
        // A 429 that carries `project_deleted` is terminal. Anything
        // keying off the status alone would treat this as a retryable
        // rate limit and keep hammering a dead project.
        server.replyWithError(429, "project_deleted", recoverable = false)
        submit()

        assertTrue(LifecycleStore.isTerminated)
        assertEquals(listOf(SdkErrorReason.PROJECT_DELETED), received)
    }

    @Test
    fun `recoverable reason on a 404 does not terminate`() {
        // The mirror image: a 404 carrying `quota_exceeded` must NOT
        // terminate. `details.error` wins over the status in both
        // directions, which is the whole point of the contract.
        server.replyWithError(404, "quota_exceeded", recoverable = true)
        submit()

        assertFalse(LifecycleStore.isTerminated)
        assertTrue(received.isEmpty())
    }

    @Test
    fun `404 without a details object does not terminate`() {
        // A bare 404 (wrong URL, proxy error page, gateway) carries no
        // contract. Terminating on it would let any middlebox kill the
        // SDK for every install behind it.
        server.reply(404, """{"error":{"message":"Not Found","status":"NOT_FOUND"}}""")
        submit()

        assertFalse(LifecycleStore.isTerminated)
        assertTrue(received.isEmpty())
    }

    @Test
    fun `unknown reason string does not terminate`() {
        // Forward compatibility: a reason this SDK build has never
        // heard of parses to null and leaves the lifecycle alone.
        server.replyWithError(404, "project_incinerated", recoverable = false)
        submit()

        assertFalse(LifecycleStore.isTerminated)
        assertTrue(received.isEmpty())
    }

    @Test
    fun `non-json error body does not terminate`() {
        server.reply(502, "<html><body>Bad Gateway</body></html>")
        submit()

        assertFalse(LifecycleStore.isTerminated)
        assertTrue(received.isEmpty())
    }

    // --- 4. The config fetch is a terminal surface too ----------------

    @Test
    fun `terminal reason on the config fetch terminates`() {
        // getSdkConfig runs on every configure(), before any report is
        // ever filed — a revoked key must stop the install there.
        server.replyWithError(403, "api_key_revoked", recoverable = false)
        runBlocking { AttestationStore.refreshRemoteConfig(runtime()) }

        assertTrue(LifecycleStore.isTerminated)
        assertEquals(listOf(SdkErrorReason.API_KEY_REVOKED), received)
    }

    @Test
    fun `network failure on the config fetch does not terminate`() {
        server.stop()
        runBlocking { AttestationStore.refreshRemoteConfig(runtime()) }

        assertFalse(LifecycleStore.isTerminated)
    }

    // --- 5. TERMINATED survives a restart ----------------------------

    @Test
    fun `terminated state survives a simulated process restart`() {
        server.replyWithError(404, "project_deleted", recoverable = false)
        submit()
        assertTrue(LifecycleStore.isTerminated)

        // Process restart: the singleton's in-memory state is gone,
        // SharedPreferences is not. install() is what configure() calls.
        simulateProcessRestart()

        assertTrue(LifecycleStore.isTerminated)
    }

    @Test
    fun `terminated state survives a clock change`() {
        server.replyWithError(403, "workspace_suspended", recoverable = false)
        submit()
        val storedAt = lifecyclePrefs().getLong("terminated_at", 0L)
        assertTrue(storedAt > 0L)

        // The user rolls the device clock back a decade — and forward
        // a century. Neither may resurrect the SDK: nothing in the
        // rehydration path compares timestamps.
        listOf(0L, -1L, 1L, Long.MAX_VALUE).forEach { skewed ->
            lifecyclePrefs().edit().putLong("terminated_at", skewed).commit()
            simulateProcessRestart()
            assertTrue("clock skew $skewed resurrected the SDK", LifecycleStore.isTerminated)
        }
    }

    // --- 6. TERMINATED is one-way ------------------------------------

    @Test
    fun `a later successful call does not clear terminated`() {
        server.replyWithError(404, "project_deleted", recoverable = false)
        submit()
        assertTrue(LifecycleStore.isTerminated)

        // The project is restored server-side (or a load balancer
        // stops lying) and the very next call succeeds. The SDK must
        // still be terminated — recovery is host-app re-init only.
        server.replySuccess()
        val second = submit()

        assertTrue(second.isSuccess)
        assertTrue(LifecycleStore.isTerminated)
        assertEquals("project_deleted", lifecyclePrefs().getString("terminated_reason", null))
    }

    @Test
    fun `a second terminal signal keeps the first reason and fires once`() {
        server.replyWithError(403, "workspace_suspended", recoverable = false)
        submit()
        server.replyWithError(404, "project_deleted", recoverable = false)
        submit()

        assertEquals(listOf(SdkErrorReason.WORKSPACE_SUSPENDED), received)
        assertEquals(
            "workspace_suspended",
            lifecyclePrefs().getString("terminated_reason", null),
        )
    }

    @Test
    fun `re-configure does not clear terminated`() {
        server.replyWithError(404, "project_deleted", recoverable = false)
        submit()
        assertTrue(LifecycleStore.isTerminated)

        Issuetracker.configure(
            application = app(),
            apiKey = API_KEY,
            shakeToReport = false,
            longPressToReport = false,
            enableCrashReporting = false,
        )

        assertTrue(LifecycleStore.isTerminated)
    }

    @Test
    fun `re-configure with a different api key does not clear terminated`() {
        server.replyWithError(401, "invalid_api_key", recoverable = false)
        submit()
        assertTrue(LifecycleStore.isTerminated)

        // Swapping the key is not an escape hatch: the persisted marker
        // is install-scoped, so a second project's key cannot be used
        // to walk the SDK back to OK.
        Issuetracker.configure(
            application = app(),
            apiKey = "it_dev_someOtherProjectKey",
            shakeToReport = false,
            longPressToReport = false,
            enableCrashReporting = false,
        )
        simulateProcessRestart()

        assertTrue(LifecycleStore.isTerminated)
        assertEquals("invalid_api_key", lifecyclePrefs().getString("terminated_reason", null))
    }

    // --- helpers ------------------------------------------------------

    private fun submit(): Result<JSONObject> = runBlocking {
        ReportingSession.submit(
            runtime = runtime(),
            title = "Title",
            description = "Description",
            type = IssueReportType.BUG,
            screenshot = null,
        )
    }

    private fun runtime() = Runtime(
        application = app(),
        apiKey = API_KEY,
        endpoint = server.endpoint,
        onConfigurationError = { received.add(it) },
    )

    /**
     * Drops the in-memory singleton state and rehydrates from disk —
     * exactly what a cold process start plus `configure()` does.
     */
    private fun simulateProcessRestart() {
        LifecycleStore.resetForTesting()
        LifecycleStore.install(app())
    }

    private fun lifecyclePrefs() =
        app().getSharedPreferences("no.issuetracker.sdk.lifecycle", Context.MODE_PRIVATE)

    private fun clearPrefs(name: String) {
        app().getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private companion object {
        const val API_KEY = "it_dev_contractTestKey"
    }
}

/**
 * Minimal stand-in for the Firebase callable endpoint. Speaks the same
 * envelope the real one does: `{"result": …}` on success,
 * `{"error": {"message", "status", "details"}}` on failure.
 */
internal class FakeCallableServer {
    // Raw sockets rather than com.sun.net.httpserver: the Android unit
    // test classpath is android.jar + the JDK's public API, and the
    // JDK's bundled HTTP server is not visible there.
    private val socket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    private var thread: Thread? = null

    @Volatile private var stopped = false

    @Volatile private var status: Int = 200

    @Volatile private var body: String = """{"result":{"issueId":"ISU-1"}}"""

    /** Paths hit, in order — lets a test assert that nothing was sent. */
    val requestPaths = CopyOnWriteArrayList<String>()

    val endpoint: String get() = "http://127.0.0.1:${socket.localPort}/v1"

    fun start() {
        thread = Thread {
            while (!stopped) {
                try {
                    socket.accept().use { client ->
                        val input = client.getInputStream()
                        val requestLine = readLine(input) ?: return@use
                        var contentLength = 0
                        while (true) {
                            val header = readLine(input) ?: break
                            if (header.isEmpty()) break
                            if (header.startsWith("Content-Length:", ignoreCase = true)) {
                                contentLength = header.substringAfter(':').trim().toIntOrNull() ?: 0
                            }
                        }
                        // Drain the request body before answering.
                        var read = 0
                        while (read < contentLength) {
                            val n = input.read(ByteArray(contentLength - read))
                            if (n <= 0) break
                            read += n
                        }
                        requestPaths.add(requestLine.split(' ').getOrElse(1) { "" })
                        val payload = body.toByteArray(Charsets.UTF_8)
                        val head = buildString {
                            append("HTTP/1.1 $status ${statusName(status)}\r\n")
                            append("Content-Type: application/json\r\n")
                            append("Content-Length: ${payload.size}\r\n")
                            append("Connection: close\r\n\r\n")
                        }
                        client.getOutputStream().apply {
                            write(head.toByteArray(Charsets.US_ASCII))
                            write(payload)
                            flush()
                        }
                    }
                } catch (_: SocketException) {
                    // Socket closed by stop() — expected on teardown.
                } catch (_: Exception) {
                    // Never let a test server thread take the JVM down.
                }
            }
        }.apply { isDaemon = true; start() }
    }

    fun stop() {
        if (stopped) return
        stopped = true
        socket.close()
        thread?.join(1_000)
    }

    /** Reads one CRLF-terminated line as ASCII. */
    private fun readLine(input: InputStream): String? {
        val out = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1) return if (out.isEmpty()) null else out.toString()
            if (c == '\n'.code) return out.toString().removeSuffix("\r")
            out.append(c.toChar())
        }
    }

    fun replySuccess(json: String = """{"issueId":"ISU-1"}""") {
        status = 200
        body = """{"result":$json}"""
    }

    fun reply(status: Int, rawBody: String) {
        this.status = status
        this.body = rawBody
    }

    fun replyWithError(
        status: Int,
        error: String,
        recoverable: Boolean,
        extras: (JSONObject) -> Unit = {},
    ) {
        val details = JSONObject()
            .put("error", error)
            .put("recoverable", recoverable)
            .also(extras)
        val envelope = JSONObject().put(
            "error",
            JSONObject()
                .put("message", "Request failed: $error")
                .put("status", statusName(status))
                .put("details", details),
        )
        reply(status, envelope.toString())
    }

    private fun statusName(status: Int): String = when (status) {
        401 -> "UNAUTHENTICATED"
        403 -> "PERMISSION_DENIED"
        404 -> "NOT_FOUND"
        429 -> "RESOURCE_EXHAUSTED"
        503 -> "UNAVAILABLE"
        else -> "INTERNAL"
    }
}
