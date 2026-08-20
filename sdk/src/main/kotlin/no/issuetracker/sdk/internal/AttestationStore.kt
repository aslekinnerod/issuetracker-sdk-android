package no.issuetracker.sdk.internal

import android.content.Context
import android.content.SharedPreferences
import no.issuetracker.sdk.Runtime

/**
 * Tester attestation + remote config (ADR-0005). The server owns one
 * flag today — `requireTesterAttestation` — which decides whether the
 * report triggers work for everyone (open mode) or only on installs
 * holding a valid tester token (testers-only mode).
 *
 * Fail-mode (ADR-0005 Decision 4): the last cached value wins when the
 * config fetch fails. With no cache at all, prod-prefixed keys fail
 * CLOSED (triggers inert until the first successful fetch says open)
 * and dev/staging-prefixed keys fail OPEN. Conservative where real end
 * users are, frictionless where people develop and QA.
 *
 * The token itself will arrive silently from the companion app's
 * ContentProvider (ADR-0005 Decision 2) once that ships; until then
 * hosts can inject one via `Issuetracker.setTesterToken(...)`.
 */
internal object AttestationStore {
    private const val PREFS_NAME = "no.issuetracker.sdk.attestation"
    private const val CONFIG_API_KEY = "config_api_key"
    private const val CONFIG_REQUIRE = "config_require_tester_attestation"
    private const val TOKEN_KEY = "tester_token"
    private const val TOKEN_EXPIRES_KEY = "tester_token_expires_at"

    @Volatile private var prefs: SharedPreferences? = null
    @Volatile private var currentApiKey: String? = null
    // null = no fetched/cached value for the current key; fall back to
    // the per-prefix fail-mode default.
    @Volatile private var known: Boolean? = null

    /**
     * Prefs-only install — wired from SdkInitProvider at process start
     * so `Issuetracker.setTesterToken(...)` works before configure(),
     * matching identify()/recordAction(). Re-install is a no-op.
     */
    fun installPrefs(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    /**
     * Synchronous part of configure(): seed the in-memory value from
     * the cache so the very first trigger fires against real data when
     * we have any. The caller kicks the async refresh.
     */
    fun install(context: Context, apiKey: String) {
        installPrefs(context)
        val p = prefs ?: return
        currentApiKey = apiKey
        // A cached flag recorded for a different key must not leak
        // onto the new project after a reconfigure.
        known = if (p.getString(CONFIG_API_KEY, null) == apiKey && p.contains(CONFIG_REQUIRE)) {
            p.getBoolean(CONFIG_REQUIRE, false)
        } else {
            null
        }
    }

    suspend fun refreshRemoteConfig(runtime: Runtime) {
        try {
            val result = ApiClient.call(
                runtime.endpoint,
                "getSdkConfig",
                org.json.JSONObject().put("apiKey", runtime.apiKey),
            )
            adopt(result.optBoolean("requireTesterAttestation", false), runtime.apiKey)
        } catch (e: ApiException) {
            // A terminal signal on the config fetch (key revoked,
            // project deleted, …) is as authoritative as one on
            // submission — flip to TERMINATED here too so a dead
            // cohort stops before it ever reaches the report endpoint.
            // Anything else (offline, transient) leaves the cached /
            // fail-mode value in charge.
            val reason = e.sdkErrorReason
            if (reason != null && reason.isTerminal) {
                LifecycleStore.transitionToTerminated(reason, runtime.onConfigurationError)
            }
        } catch (_: Exception) {
            // Network-level failure — keep the cached/fail-mode value.
        }
    }

    /** Internal seam for tests and for refreshRemoteConfig. */
    fun adopt(requireTesterAttestation: Boolean, apiKey: String) {
        known = requireTesterAttestation
        prefs?.edit()
            ?.putString(CONFIG_API_KEY, apiKey)
            ?.putBoolean(CONFIG_REQUIRE, requireTesterAttestation)
            ?.apply()
    }

    /**
     * The assumed `requireTesterAttestation` value with no data at
     * all. Prod keys are exactly the ones without an env infix.
     */
    fun failModeDefault(apiKey: String): Boolean =
        !apiKey.startsWith("it_dev_") && !apiKey.startsWith("it_staging_")

    private val requireAttestation: Boolean
        get() {
            known?.let { return it }
            val key = currentApiKey ?: return false // not configured yet
            return failModeDefault(key)
        }

    /** Valid (non-expired) tester token, or null. */
    val testerToken: String?
        get() {
            val p = prefs ?: return null
            val token = p.getString(TOKEN_KEY, null) ?: return null
            val expiresAt = p.getLong(TOKEN_EXPIRES_KEY, 0L)
            if (expiresAt > 0L && expiresAt < System.currentTimeMillis()) return null
            return token
        }

    fun setTesterToken(token: String, expiresAtMillis: Long?) {
        val editor = prefs?.edit() ?: return
        editor.putString(TOKEN_KEY, token)
        if (expiresAtMillis != null) {
            editor.putLong(TOKEN_EXPIRES_KEY, expiresAtMillis)
        } else {
            editor.remove(TOKEN_EXPIRES_KEY)
        }
        editor.apply()
    }

    fun clearTesterToken() {
        prefs?.edit()
            ?.remove(TOKEN_KEY)
            ?.remove(TOKEN_EXPIRES_KEY)
            ?.apply()
    }

    /**
     * Gesture-trigger gate. In testers-only mode without a token the
     * triggers are silently inert — no UI, no hint the SDK exists
     * (ADR-0005 invariant 5). The programmatic `report()` path is
     * deliberately NOT gated on this: a host app's own "report a bug"
     * button should surface the attestation error message rather than
     * dying silently.
     */
    val canTriggerReport: Boolean
        get() = !requireAttestation || testerToken != null

    /**
     * Test-only — clears the in-memory singleton state so each test
     * case starts from a clean slate. The on-disk SharedPreferences
     * are not touched here; tests should clear them explicitly.
     */
    internal fun resetForTesting() {
        prefs = null
        currentApiKey = null
        known = null
    }
}
