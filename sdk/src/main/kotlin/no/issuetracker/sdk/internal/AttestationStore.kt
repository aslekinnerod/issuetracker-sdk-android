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
 * The token itself arrives silently from the companion app's
 * ContentProvider (ADR-0005 Decision 2, see CompanionAttestationClient);
 * hosts can also inject one via `Issuetracker.setTesterToken(...)`,
 * which is the documented path for dev/staging keys since no non-prod
 * companion build exists.
 *
 * Both the config cache and the token slot are apiKey-bound (ADR-0005
 * Decision 9): a token minted for project A must never ride along into
 * project B after a reconfigure.
 */
internal object AttestationStore {
    private const val PREFS_NAME = "no.issuetracker.sdk.attestation"
    private const val CONFIG_API_KEY = "config_api_key"
    private const val CONFIG_REQUIRE = "config_require_tester_attestation"
    private const val TOKEN_KEY = "tester_token"
    private const val TOKEN_EXPIRES_KEY = "tester_token_expires_at"
    private const val TOKEN_API_KEY = "tester_token_api_key"

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
        // Token binding (ADR-0005 Decision 9), mirroring the config
        // cache line for line. A token written before configure() has
        // no key stamped on it — configure() is the first moment we
        // know which project it belongs to, so we adopt it here. A
        // token stamped with a DIFFERENT key is dropped, never carried
        // forward: it would otherwise be attached to reports filed
        // against another project.
        if (p.contains(TOKEN_KEY)) {
            when (p.getString(TOKEN_API_KEY, null)) {
                null -> bindToken(apiKey)
                apiKey -> Unit
                else -> clearTesterToken()
            }
        }
    }

    suspend fun refreshRemoteConfig(runtime: Runtime) {
        // ADR-0003 Decision 9.2: TERMINATED "disables all background
        // tasks". configure() calls this on every launch, so without
        // this gate a terminated install POSTs getSdkConfig forever —
        // once per launch, across every deployed instance, for a
        // project the server has already told us is gone. It can never
        // change the outcome either: TERMINATED is one-way, and
        // recovery is an explicit host-app re-init, never a poll.
        if (LifecycleStore.isTerminated) return
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

    /**
     * Valid (non-expired) tester token for the configured project, or
     * null.
     *
     * Expiry rule, unified across all four SDKs (ADR-0005 Decision 10):
     * absent or `<= 0` means no local expiry; a positive expiry in the
     * past means treat as absent AND clear, so a dead token does not
     * sit on disk waiting to be mistaken for a live one.
     *
     * Binding: a token stamped with another project's key is invisible
     * here even if install() has not yet swept it. Reads before
     * configure() (currentApiKey == null) cannot evaluate the binding
     * and return the token as-is — that is the pre-configure injection
     * path, and install() reconciles it a moment later.
     */
    val testerToken: String?
        get() {
            val p = prefs ?: return null
            val token = p.getString(TOKEN_KEY, null) ?: return null
            val boundTo = p.getString(TOKEN_API_KEY, null)
            val active = currentApiKey
            if (active != null && boundTo != null && boundTo != active) return null
            val expiresAt = p.getLong(TOKEN_EXPIRES_KEY, 0L)
            if (expiresAt > 0L && expiresAt < System.currentTimeMillis()) {
                clearTesterToken()
                return null
            }
            return token
        }

    /**
     * Host-injected token (`Issuetracker.setTesterToken`). Bound to the
     * configured key when there is one; written unbound before
     * configure(), for install() to adopt.
     */
    fun setTesterToken(token: String, expiresAtMillis: Long?) {
        storeTesterToken(token, expiresAtMillis, currentApiKey)
    }

    /**
     * Companion-provided token. Always bound, because the provider is
     * queried with a specific API key and answers for that project
     * alone.
     */
    fun storeTesterToken(token: String, expiresAtMillis: Long?, apiKey: String?) {
        val editor = prefs?.edit() ?: return
        editor.putString(TOKEN_KEY, token)
        if (expiresAtMillis != null) {
            editor.putLong(TOKEN_EXPIRES_KEY, expiresAtMillis)
        } else {
            editor.remove(TOKEN_EXPIRES_KEY)
        }
        if (apiKey != null) {
            editor.putString(TOKEN_API_KEY, apiKey)
        } else {
            editor.remove(TOKEN_API_KEY)
        }
        editor.apply()
    }

    /**
     * Internal seam: stamps the stored token with the project it
     * belongs to. Called by install() when adopting a token that was
     * written before configure(); exposed for tests alongside adopt().
     */
    fun bindToken(apiKey: String) {
        val p = prefs ?: return
        if (!p.contains(TOKEN_KEY)) return
        p.edit().putString(TOKEN_API_KEY, apiKey).apply()
    }

    fun clearTesterToken() {
        prefs?.edit()
            ?.remove(TOKEN_KEY)
            ?.remove(TOKEN_EXPIRES_KEY)
            ?.remove(TOKEN_API_KEY)
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
