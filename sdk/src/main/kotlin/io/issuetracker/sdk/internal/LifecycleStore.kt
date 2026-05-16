package io.issuetracker.sdk.internal

import android.content.Context
import android.content.SharedPreferences
import io.issuetracker.sdk.SdkErrorReason

/**
 * One-way SDK lifecycle state. See ADR-0003 Decision 9.
 *
 * Starts in [State.Ok]. The first non-recoverable server error
 * transitions to [State.Terminated] and the SDK stays there for the
 * lifetime of the install — recovery requires an explicit host-app
 * re-init (in practice an app relaunch), never a poll, so a deployed
 * cohort cannot hammer a dead endpoint regardless of scale.
 *
 * [State.Suspended] is reserved for a future per-report retry queue
 * (Phase C+) and is not produced today; recoverable errors keep the
 * SDK in [State.Ok] and rely on the user retrying via the existing
 * UI.
 *
 * Persisted in a sandboxed SharedPreferences file so a process
 * restart does not re-attempt delivery against an endpoint the server
 * has already told us is gone.
 */
internal object LifecycleStore {
    private const val PREFS_NAME = "io.issuetracker.sdk.lifecycle"
    private const val REASON_KEY = "terminated_reason"
    private const val AT_KEY = "terminated_at"

    sealed interface State {
        data object Ok : State
        data object Suspended : State
        data class Terminated(val reason: SdkErrorReason, val atMillis: Long) : State
    }

    @Volatile private var prefs: SharedPreferences? = null

    @Volatile
    var state: State = State.Ok
        private set

    val isTerminated: Boolean
        get() = state is State.Terminated

    fun install(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = p
        val raw = p.getString(REASON_KEY, null)
        val reason = SdkErrorReason.fromRaw(raw)
        if (reason != null) {
            val at = p.getLong(AT_KEY, System.currentTimeMillis())
            state = State.Terminated(reason, at)
        }
    }

    /**
     * Idempotent: re-terminating with a different reason keeps the
     * first one. The first non-recoverable failure is authoritative;
     * later failures should have been gated and only happen if a
     * pre-flight check missed the state.
     */
    @Synchronized
    fun transitionToTerminated(
        reason: SdkErrorReason,
        callback: ((SdkErrorReason) -> Unit)?,
    ) {
        if (isTerminated) return
        val now = System.currentTimeMillis()
        state = State.Terminated(reason, now)
        prefs?.edit()
            ?.putString(REASON_KEY, reason.rawValue)
            ?.putLong(AT_KEY, now)
            ?.apply()
        callback?.invoke(reason)
    }
}
