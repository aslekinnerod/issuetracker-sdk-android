package no.issuetracker.sdk.internal

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ProviderInfo
import android.content.pm.Signature
import android.database.MatrixCursor
import androidx.test.core.app.ApplicationProvider
import no.issuetracker.sdk.SdkErrorReason
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Companion ContentProvider client contract (ADR-0005 Decisions 2, 9
 * and 11). The identifiers asserted here are frozen public API — they
 * are baked into every customer binary built against this SDK, so a
 * failure in this suite means a field-breaking change, not a stale
 * test.
 *
 * The fail-closed case is the load-bearing one: while
 * `CompanionContract.SIGNING_SHA256` is empty (the Play App Signing
 * digest is filled in at release), no package is trusted and no token
 * is ever adopted — even one offered by a package using the frozen
 * applicationId and authority.
 */
@RunWith(RobolectricTestRunner::class)
class CompanionAttestationClientTest {

    private val prefsFile = "no.issuetracker.sdk.attestation"

    private fun appContext(): Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        AttestationStore.resetForTesting()
        LifecycleStore.resetForTesting()
        appContext().getSharedPreferences(prefsFile, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        appContext().getSharedPreferences("no.issuetracker.sdk.lifecycle", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @After
    fun tearDown() {
        AttestationStore.resetForTesting()
        LifecycleStore.resetForTesting()
    }

    /** Installs a package claiming the frozen companion identity. */
    private fun installFakeCompanion() {
        val info = PackageInfo().apply {
            packageName = CompanionContract.PACKAGE
            applicationInfo = ApplicationInfo().apply {
                packageName = CompanionContract.PACKAGE
            }
            @Suppress("DEPRECATION")
            signatures = arrayOf(Signature(byteArrayOf(1, 2, 3, 4)))
        }
        shadowOf(appContext().packageManager).installPackage(info)
        shadowOf(appContext().packageManager).addOrUpdateProvider(
            ProviderInfo().apply {
                packageName = CompanionContract.PACKAGE
                authority = CompanionContract.AUTHORITY
                name = "${CompanionContract.PACKAGE}.AttestationProvider"
                applicationInfo = ApplicationInfo().apply {
                    packageName = CompanionContract.PACKAGE
                }
            },
        )
    }

    @Test
    fun `frozen contract strings`() {
        assertEquals("no.issuetracker.testers", CompanionContract.PACKAGE)
        assertEquals("no.issuetracker.testers.attestation", CompanionContract.AUTHORITY)
        assertEquals(
            "content://no.issuetracker.testers.attestation/token",
            CompanionContract.TOKEN_URI.toString(),
        )
        assertEquals("no.issuetracker.sdk.apiKey", CompanionContract.QUERY_ARG_API_KEY)
        assertEquals("token", CompanionContract.COLUMN_TOKEN)
        assertEquals("expires_at", CompanionContract.COLUMN_EXPIRES_AT)
        assertEquals("project_key", CompanionContract.COLUMN_PROJECT_KEY)
        assertEquals("project_name", CompanionContract.COLUMN_PROJECT_NAME)
        assertEquals("scope", CompanionContract.COLUMN_SCOPE)
        assertEquals("tester_id", CompanionContract.COLUMN_TESTER_ID)
    }

    @Test
    fun `no package is trusted while the pin list is empty`() {
        installFakeCompanion()
        assertFalse(
            CompanionAttestationClient.isCompanionTrusted(
                appContext(),
                CompanionContract.PACKAGE,
            ),
        )
    }

    @Test
    fun `hydrate adopts nothing while the pin list is empty`() {
        installFakeCompanion()
        AttestationStore.install(appContext(), "it_abcdef1234567890")

        assertFalse(
            CompanionAttestationClient.hydrateBlocking(appContext(), "it_abcdef1234567890"),
        )
        assertNull(AttestationStore.testerToken)
        assertFalse(AttestationStore.canTriggerReport)
    }

    @Test
    fun `hydrate is a no-op with no companion installed`() {
        AttestationStore.install(appContext(), "it_abcdef1234567890")
        assertFalse(
            CompanionAttestationClient.hydrateBlocking(appContext(), "it_abcdef1234567890"),
        )
        assertNull(AttestationStore.testerToken)
    }

    @Test
    fun `terminated installs never attest`() {
        installFakeCompanion()
        AttestationStore.install(appContext(), "it_abcdef1234567890")
        LifecycleStore.install(appContext())
        LifecycleStore.transitionToTerminated(SdkErrorReason.API_KEY_REVOKED, null)

        assertFalse(
            CompanionAttestationClient.hydrateBlocking(appContext(), "it_abcdef1234567890"),
        )
        assertNull(AttestationStore.testerToken)
    }

    @Test
    fun `hydrate never clears a host-injected token`() {
        AttestationStore.install(appContext(), "it_abcdef1234567890")
        AttestationStore.setTesterToken("itt_manual", null)

        CompanionAttestationClient.hydrateBlocking(appContext(), "it_abcdef1234567890")
        assertEquals("itt_manual", AttestationStore.testerToken)
    }

    @Test
    fun `blank api key is never put on the wire`() {
        installFakeCompanion()
        assertFalse(CompanionAttestationClient.hydrateBlocking(appContext(), "   "))
    }

    @Test
    fun `digest normalisation accepts colons and mixed case`() {
        assertEquals(
            "ab12cd34",
            CompanionAttestationClient.normalizeDigest("AB:12:CD:34"),
        )
        assertNull(CompanionAttestationClient.normalizeDigest(""))
        assertNull(CompanionAttestationClient.normalizeDigest("abc"))
        assertNull(CompanionAttestationClient.normalizeDigest("zz11"))
    }

    @Test
    fun `hex parsing round-trips a digest`() {
        val bytes = CompanionAttestationClient.hexToBytes("00:7f:80:ff")
        assertEquals(4, bytes?.size)
        assertEquals(0, bytes?.get(0)?.toInt())
        assertEquals(127, bytes?.get(1)?.toInt())
        assertEquals(-128, bytes?.get(2)?.toInt())
        assertEquals(-1, bytes?.get(3)?.toInt())
        assertNull(CompanionAttestationClient.hexToBytes("xyz"))
    }

    // --- cursor contract (ADR-0005 Decision 11) ---
    //
    // These drive readAndStore directly. The signature pin is empty
    // until release, so it short-circuits every path that would reach
    // the parsing in a full hydrate() — leaving the column contract
    // that actually carries the feature untested unless it is exercised
    // here.

    private fun cursorOf(
        columns: Array<String>,
        vararg rows: Array<out Any?>,
    ): MatrixCursor = MatrixCursor(columns).apply { rows.forEach { addRow(it) } }

    @Test
    fun `a valid row is stored bound to the queried key`() {
        AttestationStore.install(appContext(), "it_projectA")
        val expires = System.currentTimeMillis() + 60_000
        val cursor = cursorOf(
            arrayOf(
                CompanionContract.COLUMN_TOKEN,
                CompanionContract.COLUMN_EXPIRES_AT,
                CompanionContract.COLUMN_PROJECT_KEY,
                CompanionContract.COLUMN_PROJECT_NAME,
                CompanionContract.COLUMN_SCOPE,
                CompanionContract.COLUMN_TESTER_ID,
            ),
            arrayOf("itt_live", expires, "ISU", "Issuetracker", "project", "tester1"),
        )

        assertTrue(CompanionAttestationClient.readAndStore(cursor, "it_projectA"))
        assertEquals("itt_live", AttestationStore.testerToken)

        // Bound to the key it was queried with, not left floating.
        AttestationStore.resetForTesting()
        AttestationStore.install(appContext(), "it_projectB")
        assertNull(AttestationStore.testerToken)
    }

    @Test
    fun `an absent expires_at column means no local expiry`() {
        AttestationStore.install(appContext(), "it_projectA")
        val cursor = cursorOf(
            arrayOf(CompanionContract.COLUMN_TOKEN),
            arrayOf("itt_noexpiry"),
        )

        assertTrue(CompanionAttestationClient.readAndStore(cursor, "it_projectA"))
        assertEquals("itt_noexpiry", AttestationStore.testerToken)
    }

    @Test
    fun `a null or non-positive expires_at means no local expiry`() {
        AttestationStore.install(appContext(), "it_projectA")

        assertTrue(
            CompanionAttestationClient.readAndStore(
                cursorOf(
                    arrayOf(CompanionContract.COLUMN_TOKEN, CompanionContract.COLUMN_EXPIRES_AT),
                    arrayOf("itt_null", null),
                ),
                "it_projectA",
            ),
        )
        assertEquals("itt_null", AttestationStore.testerToken)

        assertTrue(
            CompanionAttestationClient.readAndStore(
                cursorOf(
                    arrayOf(CompanionContract.COLUMN_TOKEN, CompanionContract.COLUMN_EXPIRES_AT),
                    arrayOf("itt_zero", 0L),
                ),
                "it_projectA",
            ),
        )
        assertEquals("itt_zero", AttestationStore.testerToken)
    }

    @Test
    fun `an already-expired row is rejected and never overwrites a good token`() {
        AttestationStore.install(appContext(), "it_projectA")
        AttestationStore.setTesterToken("itt_good", null)

        val cursor = cursorOf(
            arrayOf(CompanionContract.COLUMN_TOKEN, CompanionContract.COLUMN_EXPIRES_AT),
            arrayOf("itt_stale", System.currentTimeMillis() - 1000),
        )

        assertFalse(CompanionAttestationClient.readAndStore(cursor, "it_projectA"))
        assertEquals("itt_good", AttestationStore.testerToken)
    }

    @Test
    fun `an empty cursor adopts nothing`() {
        AttestationStore.install(appContext(), "it_projectA")
        val cursor = cursorOf(arrayOf(CompanionContract.COLUMN_TOKEN))

        assertFalse(CompanionAttestationClient.readAndStore(cursor, "it_projectA"))
        assertNull(AttestationStore.testerToken)
    }

    @Test
    fun `a row without a token column adopts nothing`() {
        AttestationStore.install(appContext(), "it_projectA")
        val cursor = cursorOf(
            arrayOf(CompanionContract.COLUMN_PROJECT_KEY),
            arrayOf("ISU"),
        )

        assertFalse(CompanionAttestationClient.readAndStore(cursor, "it_projectA"))
        assertNull(AttestationStore.testerToken)
    }

    @Test
    fun `a null or blank token adopts nothing`() {
        AttestationStore.install(appContext(), "it_projectA")

        assertFalse(
            CompanionAttestationClient.readAndStore(
                cursorOf(arrayOf(CompanionContract.COLUMN_TOKEN), arrayOf(null)),
                "it_projectA",
            ),
        )
        assertFalse(
            CompanionAttestationClient.readAndStore(
                cursorOf(arrayOf(CompanionContract.COLUMN_TOKEN), arrayOf("   ")),
                "it_projectA",
            ),
        )
        assertNull(AttestationStore.testerToken)
    }

    @Test
    fun `only the first row is read`() {
        AttestationStore.install(appContext(), "it_projectA")
        val cursor = cursorOf(
            arrayOf(CompanionContract.COLUMN_TOKEN),
            arrayOf("itt_first"),
            arrayOf("itt_second"),
        )

        assertTrue(CompanionAttestationClient.readAndStore(cursor, "it_projectA"))
        assertEquals("itt_first", AttestationStore.testerToken)
    }
}
