package no.issuetracker.sdk.internal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import no.issuetracker.sdk.SdkErrorReason
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * LifecycleStore contract tests. Mirrors the sdk-web suite in
 * sdk-web/src/lifecycle.test.ts and the sdk-ios suite in
 * sdk-ios/Tests/IssuetrackerSDKTests/LifecycleStoreTests.swift —
 * all three must stay in lockstep on the canonical reason set and
 * first-signal-wins semantics.
 *
 * Uses Robolectric for a real-ish SharedPreferences. Each test calls
 * resetForTesting() (test-only API) to drop the in-memory singleton
 * state, then clears the SharedPreferences file too.
 */
@RunWith(RobolectricTestRunner::class)
class LifecycleStoreTest {

    private val prefsFile = "no.issuetracker.sdk.lifecycle"

    @Before
    fun setUp() {
        LifecycleStore.resetForTesting()
        appContext().getSharedPreferences(prefsFile, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @After
    fun tearDown() {
        LifecycleStore.resetForTesting()
    }

    @Test
    fun `starts in Ok state`() {
        LifecycleStore.install(appContext())
        assertFalse(LifecycleStore.isTerminated)
    }

    @Test
    fun `transitions to Terminated on non-recoverable signal`() {
        LifecycleStore.install(appContext())
        LifecycleStore.transitionToTerminated(SdkErrorReason.WORKSPACE_SUSPENDED, null)
        assertTrue(LifecycleStore.isTerminated)
    }

    @Test
    fun `callback fires exactly once across repeated signals`() {
        LifecycleStore.install(appContext())
        var callCount = 0
        var receivedReason: SdkErrorReason? = null
        val cb: (SdkErrorReason) -> Unit = { reason ->
            callCount++
            receivedReason = reason
        }
        LifecycleStore.transitionToTerminated(SdkErrorReason.WORKSPACE_SUSPENDED, cb)
        LifecycleStore.transitionToTerminated(SdkErrorReason.PROJECT_DELETED, cb)
        LifecycleStore.transitionToTerminated(SdkErrorReason.API_KEY_REVOKED, cb)
        assertEquals(1, callCount)
        assertEquals(SdkErrorReason.WORKSPACE_SUSPENDED, receivedReason)
    }

    @Test
    fun `persists terminated reason to SharedPreferences`() {
        LifecycleStore.install(appContext())
        LifecycleStore.transitionToTerminated(SdkErrorReason.PROJECT_DELETED, null)
        val prefs = appContext().getSharedPreferences(prefsFile, Context.MODE_PRIVATE)
        assertEquals("project_deleted", prefs.getString("terminated_reason", null))
        assertNotEquals(0L, prefs.getLong("terminated_at", 0L))
    }

    @Test
    fun `rehydrates Terminated from SharedPreferences on fresh install`() {
        // Simulate a previous process having persisted the terminated
        // state, then a process restart. install() must read the
        // marker and refuse further reports immediately.
        appContext().getSharedPreferences(prefsFile, Context.MODE_PRIVATE)
            .edit()
            .putString("terminated_reason", "workspace_suspended")
            .putLong("terminated_at", 1747000000000L)
            .commit()
        LifecycleStore.install(appContext())
        assertTrue(LifecycleStore.isTerminated)
    }

    @Test
    fun `ignores malformed persisted reason`() {
        appContext().getSharedPreferences(prefsFile, Context.MODE_PRIVATE)
            .edit()
            .putString("terminated_reason", "workspace_deleted")
            .putLong("terminated_at", 1747000000000L)
            .commit()
        LifecycleStore.install(appContext())
        assertFalse(LifecycleStore.isTerminated)
    }

    @Test
    fun `preserves first reason on second signal`() {
        // Server says workspace_suspended first; a later report
        // somehow gets project_deleted. The lifecycle keeps the
        // first reason — the audit value is which signal caused
        // termination, not the most recent one.
        LifecycleStore.install(appContext())
        LifecycleStore.transitionToTerminated(SdkErrorReason.WORKSPACE_SUSPENDED, null)
        LifecycleStore.transitionToTerminated(SdkErrorReason.PROJECT_DELETED, null)
        val prefs = appContext().getSharedPreferences(prefsFile, Context.MODE_PRIVATE)
        assertEquals("workspace_suspended", prefs.getString("terminated_reason", null))
    }

    private fun appContext(): Context = ApplicationProvider.getApplicationContext()
}
