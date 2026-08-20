package no.issuetracker.sdk

import org.json.JSONObject

/**
 * Machine-readable reason for an SDK-callable failure. The [rawValue]
 * strings match the server-side `SdkErrorReasonSchema` in
 * `@issuetracker/shared` byte-for-byte — they are the wire contract
 * across all five SDKs. See ADR-0003 Decision 9.
 *
 * Recoverable reasons ([QUOTA_EXCEEDED], [TRANSIENT]) keep the SDK in
 * the `OK` state and rely on the user retrying via the existing UI.
 * Non-recoverable reasons transition the SDK into a one-way
 * `TERMINATED` state — see `LifecycleStore`.
 */
enum class SdkErrorReason(val rawValue: String) {
    PROJECT_DELETED("project_deleted"),
    PROJECT_NOT_FOUND("project_not_found"),
    API_KEY_REVOKED("api_key_revoked"),
    WORKSPACE_SUSPENDED("workspace_suspended"),
    INVALID_API_KEY("invalid_api_key"),
    QUOTA_EXCEEDED("quota_exceeded"),
    TRANSIENT("transient"),
    TESTER_ATTESTATION_REQUIRED("tester_attestation_required"),
    TESTER_TOKEN_INVALID("tester_token_invalid");

    val isRecoverable: Boolean
        get() = this == QUOTA_EXCEEDED || this == TRANSIENT

    /**
     * Whether this reason must flip the SDK into one-way TERMINATED.
     * Tester-gating rejections (ADR-0005) are non-recoverable —
     * retrying the same request cannot succeed — but NOT terminal:
     * the project is alive and the key is valid; only this install
     * lacks (valid) attestation.
     */
    val isTerminal: Boolean
        get() = when (this) {
            QUOTA_EXCEEDED, TRANSIENT,
            TESTER_ATTESTATION_REQUIRED, TESTER_TOKEN_INVALID -> false
            PROJECT_DELETED, PROJECT_NOT_FOUND, API_KEY_REVOKED,
            WORKSPACE_SUSPENDED, INVALID_API_KEY -> true
        }

    internal val isTesterGating: Boolean
        get() = this == TESTER_ATTESTATION_REQUIRED || this == TESTER_TOKEN_INVALID

    companion object {
        fun fromRaw(raw: String?): SdkErrorReason? =
            raw?.let { v -> entries.firstOrNull { it.rawValue == v } }
    }
}

/**
 * Structured failure payload parsed out of the server's HttpsError
 * `details` object. Internal — host apps only see [SdkErrorReason]
 * via the `onConfigurationError` callback.
 */
internal data class SdkErrorDetails(
    val reason: SdkErrorReason,
    val recoverable: Boolean,
    val deletedAtMillis: Long?,
    val retryAfterSeconds: Int?,
) {
    companion object {
        fun fromJson(json: JSONObject?): SdkErrorDetails? {
            if (json == null) return null
            val reason = SdkErrorReason.fromRaw(json.optString("error").takeIf { it.isNotEmpty() })
                ?: return null
            if (!json.has("recoverable")) return null
            val recoverable = json.optBoolean("recoverable")
            val deletedAt =
                if (json.has("deletedAt") && !json.isNull("deletedAt"))
                    json.optLong("deletedAt").takeIf { it > 0 }
                else null
            val retryAfter =
                if (json.has("retryAfterSeconds") && !json.isNull("retryAfterSeconds"))
                    json.optInt("retryAfterSeconds").takeIf { it > 0 }
                else null
            return SdkErrorDetails(reason, recoverable, deletedAt, retryAfter)
        }
    }
}
