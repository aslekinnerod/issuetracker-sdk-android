package no.issuetracker.sdk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the SDK error wire format. The raw values here
 * MUST match @issuetracker/shared SdkErrorReasonSchema byte-for-byte —
 * any drift breaks the lifecycle transition logic in LifecycleStore.
 *
 * See ADR-0003 Decision 9.
 */
class SdkErrorReasonTest {

    @Test
    fun `all canonical reasons present with correct raw values`() {
        val canonical = setOf(
            "project_deleted",
            "project_not_found",
            "api_key_revoked",
            "workspace_suspended",
            "invalid_api_key",
            "quota_exceeded",
            "transient",
        )
        val actual = SdkErrorReason.entries.map { it.rawValue }.toSet()
        assertEquals(canonical, actual)
    }

    @Test
    fun `workspace_deleted misnomer is rejected`() {
        // The OPERATIONS-FOLLOWUPS doc used WORKSPACE_DELETED for a
        // week as the wire name — it never was. A server typo here
        // MUST NOT enter the lifecycle as TERMINATED.
        assertNull(SdkErrorReason.fromRaw("workspace_deleted"))
        assertNull(SdkErrorReason.fromRaw("WORKSPACE_SUSPENDED"))
        assertNull(SdkErrorReason.fromRaw(""))
        assertNull(SdkErrorReason.fromRaw(null))
    }

    @Test
    fun `recoverable mapping matches shared schema`() {
        assertTrue(SdkErrorReason.QUOTA_EXCEEDED.isRecoverable)
        assertTrue(SdkErrorReason.TRANSIENT.isRecoverable)
        assertFalse(SdkErrorReason.PROJECT_DELETED.isRecoverable)
        assertFalse(SdkErrorReason.PROJECT_NOT_FOUND.isRecoverable)
        assertFalse(SdkErrorReason.API_KEY_REVOKED.isRecoverable)
        assertFalse(SdkErrorReason.WORKSPACE_SUSPENDED.isRecoverable)
        assertFalse(SdkErrorReason.INVALID_API_KEY.isRecoverable)
    }

    @Test
    fun `parse well-formed workspace_suspended payload`() {
        val details = SdkErrorDetails.fromJson(
            JSONObject().apply {
                put("error", "workspace_suspended")
                put("recoverable", false)
            }
        )
        assertNotNull(details)
        assertEquals(SdkErrorReason.WORKSPACE_SUSPENDED, details!!.reason)
        assertEquals(false, details.recoverable)
        assertNull(details.deletedAtMillis)
        assertNull(details.retryAfterSeconds)
    }

    @Test
    fun `parse project_deleted with deletedAt millis`() {
        val details = SdkErrorDetails.fromJson(
            JSONObject().apply {
                put("error", "project_deleted")
                put("recoverable", false)
                put("deletedAt", 1747000000000L)
            }
        )
        assertNotNull(details)
        assertEquals(SdkErrorReason.PROJECT_DELETED, details!!.reason)
        assertEquals(1747000000000L, details.deletedAtMillis)
    }

    @Test
    fun `parse quota_exceeded with retryAfterSeconds`() {
        val details = SdkErrorDetails.fromJson(
            JSONObject().apply {
                put("error", "quota_exceeded")
                put("recoverable", true)
                put("retryAfterSeconds", 30)
            }
        )
        assertNotNull(details)
        assertEquals(SdkErrorReason.QUOTA_EXCEEDED, details!!.reason)
        assertEquals(30, details.retryAfterSeconds)
    }

    @Test
    fun `parse unknown reason returns null`() {
        // Including workspace_deleted — the misnomer that lived in
        // OPERATIONS-FOLLOWUPS for a week.
        val details = SdkErrorDetails.fromJson(
            JSONObject().apply {
                put("error", "workspace_deleted")
                put("recoverable", false)
            }
        )
        assertNull(details)
    }

    @Test
    fun `parse missing recoverable returns null`() {
        val details = SdkErrorDetails.fromJson(
            JSONObject().apply { put("error", "project_deleted") }
        )
        assertNull(details)
    }

    @Test
    fun `parse ignores extra fields`() {
        val details = SdkErrorDetails.fromJson(
            JSONObject().apply {
                put("error", "project_deleted")
                put("recoverable", false)
                put("unexpected", "value")
                put("foo", 42)
            }
        )
        assertNotNull(details)
        assertEquals(SdkErrorReason.PROJECT_DELETED, details!!.reason)
    }

    @Test
    fun `parse null json returns null`() {
        assertNull(SdkErrorDetails.fromJson(null))
    }
}
