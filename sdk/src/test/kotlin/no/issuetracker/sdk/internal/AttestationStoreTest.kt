package no.issuetracker.sdk.internal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * AttestationStore contract tests (ADR-0005). Mirrors the sdk-web
 * suite in sdk-web/src/attestation.test.ts and the sdk-ios suite in
 * sdk-ios/Tests/IssuetrackerSDKTests/AttestationStoreTests.swift —
 * all three must stay in lockstep on fail-mode defaults, per-key
 * cache scoping, and token expiry semantics.
 */
@RunWith(RobolectricTestRunner::class)
class AttestationStoreTest {

    private val prefsFile = "no.issuetracker.sdk.attestation"

    private fun appContext(): Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        AttestationStore.resetForTesting()
        appContext().getSharedPreferences(prefsFile, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @After
    fun tearDown() {
        AttestationStore.resetForTesting()
    }

    @Test
    fun `fails closed for prod keys with no cache`() {
        AttestationStore.install(appContext(), "it_abcdef1234567890")
        assertFalse(AttestationStore.canTriggerReport)
    }

    @Test
    fun `fails open for dev and staging keys`() {
        AttestationStore.install(appContext(), "it_dev_abcdef1234567890")
        assertTrue(AttestationStore.canTriggerReport)

        AttestationStore.install(appContext(), "it_staging_abcdef1234567890")
        assertTrue(AttestationStore.canTriggerReport)
    }

    @Test
    fun `prefers cached config over fail-mode default`() {
        AttestationStore.install(appContext(), "it_abcdef1234567890")
        AttestationStore.adopt(requireTesterAttestation = false, apiKey = "it_abcdef1234567890")

        AttestationStore.resetForTesting()
        AttestationStore.install(appContext(), "it_abcdef1234567890")
        assertTrue(AttestationStore.canTriggerReport)
    }

    @Test
    fun `ignores cached config for different key`() {
        AttestationStore.install(appContext(), "it_otherkey")
        AttestationStore.adopt(requireTesterAttestation = false, apiKey = "it_otherkey")

        AttestationStore.resetForTesting()
        AttestationStore.install(appContext(), "it_abcdef1234567890")
        assertFalse(AttestationStore.canTriggerReport)
    }

    @Test
    fun `adopted server value beats fail-open default`() {
        AttestationStore.install(appContext(), "it_dev_abcdef1234567890")
        assertTrue(AttestationStore.canTriggerReport)
        AttestationStore.adopt(requireTesterAttestation = true, apiKey = "it_dev_abcdef1234567890")
        assertFalse(AttestationStore.canTriggerReport)
    }

    @Test
    fun `token unlocks testers-only mode`() {
        AttestationStore.install(appContext(), "it_abcdef1234567890")
        assertFalse(AttestationStore.canTriggerReport)

        AttestationStore.setTesterToken("itt_sometoken", null)
        assertTrue(AttestationStore.canTriggerReport)

        AttestationStore.clearTesterToken()
        assertFalse(AttestationStore.canTriggerReport)
    }

    @Test
    fun `expired token is treated as absent`() {
        AttestationStore.install(appContext(), "it_abcdef1234567890")
        AttestationStore.setTesterToken("itt_sometoken", System.currentTimeMillis() - 1000)
        assertNull(AttestationStore.testerToken)
        assertFalse(AttestationStore.canTriggerReport)
    }

    @Test
    fun `future expiry is honoured`() {
        AttestationStore.install(appContext(), "it_abcdef1234567890")
        AttestationStore.setTesterToken("itt_sometoken", System.currentTimeMillis() + 60_000)
        assertEquals("itt_sometoken", AttestationStore.testerToken)
    }

    @Test
    fun `fail-mode default per prefix`() {
        assertTrue(AttestationStore.failModeDefault("it_abc"))
        assertFalse(AttestationStore.failModeDefault("it_dev_abc"))
        assertFalse(AttestationStore.failModeDefault("it_staging_abc"))
    }

    @Test
    fun `prefs-only install allows token storage before configure`() {
        AttestationStore.installPrefs(appContext())
        AttestationStore.setTesterToken("itt_pretoken", null)
        assertEquals("itt_pretoken", AttestationStore.testerToken)
    }
}
