package no.issuetracker.sdk.internal

import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.os.Build
import android.os.Bundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.issuetracker.sdk.Runtime
import java.security.MessageDigest

/**
 * Reads a tester attestation token from the "Issuetracker Testers"
 * companion app's exported ContentProvider (ADR-0005 Decision 2).
 *
 * The whole path is silent. A tester who has the companion installed
 * and is enrolled gets working triggers; everyone else gets exactly the
 * behaviour they had before — no dialog, no toast, no log line, no
 * exception reaching the host. Invariant 5 (testers-only mode leaves no
 * trace of the SDK for non-testers) applies to the transport too.
 *
 * Trust direction, and why the order below is not negotiable:
 *
 *  1. The authority is exported without a permission, so ANY app can
 *     claim it if it installs first. We therefore resolve the authority
 *     to a package, require that package to be the frozen companion id,
 *     and verify its signing certificate against a pinned list BEFORE
 *     the API key is put on the wire. The key is low-value (it already
 *     sits in the calling APK) but the provider hands back a live
 *     30-day write credential, so a squatter must never see either half
 *     of the exchange.
 *  2. A TERMINATED SDK never attempts attestation at all — a dead
 *     cohort does not do IPC on every cold start.
 *
 * Failure is always "no token", never an error: provider absent,
 * package absent, signature mismatch, empty cursor, missing columns,
 * SecurityException from a provider that rejects our uid, a dead binder
 * — all of them return quietly and leave whatever token the store
 * already had in place. We never CLEAR on absence: a host that injected
 * a token via `Issuetracker.setTesterToken(...)` (the documented path
 * for dev/staging keys, which have no companion build) must not have it
 * yanked by a cold start.
 */
internal object CompanionAttestationClient {

    /**
     * Queries the companion and stores any token it returns. Suspending
     * and IO-dispatched, exactly like [ApiClient.call]: `query` is a
     * synchronous binder round-trip and the caller is on Main.
     *
     * @return true when a token was adopted.
     */
    suspend fun hydrate(runtime: Runtime): Boolean = withContext(Dispatchers.IO) {
        hydrateBlocking(runtime.application, runtime.apiKey)
    }

    /**
     * Internal seam — the body of [hydrate] without the dispatcher, so
     * tests can drive it directly. Never call this from the main
     * thread in production.
     */
    internal fun hydrateBlocking(context: Context, apiKey: String): Boolean {
        // 1. Terminated installs never attest (ADR-0005 invariant).
        if (LifecycleStore.isTerminated) return false
        if (apiKey.isBlank()) return false

        return try {
            val ctx = context.applicationContext ?: context
            // 2. The authority must resolve, and it must resolve to the
            //    frozen companion package — not to whoever squatted it.
            val providerPackage = resolveProviderPackage(ctx) ?: return false
            if (providerPackage != CompanionContract.PACKAGE) return false
            // 3. Signature pin, before the API key leaves this process.
            if (!isCompanionTrusted(ctx, providerPackage)) return false

            val args = Bundle().apply {
                putString(CompanionContract.QUERY_ARG_API_KEY, apiKey)
            }
            val cursor: Cursor = ctx.contentResolver
                ?.query(CompanionContract.TOKEN_URI, null, args, null)
                ?: return false
            cursor.use { readAndStore(it, apiKey) }
        } catch (_: Exception) {
            // Provider crashed, binder died, uid rejected, malformed
            // response — all indistinguishable from "not a tester".
            false
        }
    }

    /**
     * At most one row, ever (ADR-0005 Decision 9). A provider that
     * returns more is treated as returning the first: this client must
     * not become the thing that makes an enumeration URI useful.
     *
     * Internal rather than private so the column contract is directly
     * testable: while [CompanionContract.SIGNING_SHA256] is empty the
     * signature pin short-circuits every path that would otherwise
     * reach this function, so without a seam the parsing that actually
     * carries the feature would ship with no coverage at all.
     */
    internal fun readAndStore(cursor: Cursor, apiKey: String): Boolean {
        if (!cursor.moveToFirst()) return false
        val tokenIndex = cursor.getColumnIndex(CompanionContract.COLUMN_TOKEN)
        if (tokenIndex < 0) return false
        val token = runCatching { cursor.getString(tokenIndex) }.getOrNull()
        if (token.isNullOrBlank()) return false

        // `expires_at` is optional and advisory: absent, null or <= 0
        // all mean "no local expiry", matching the store's rule.
        val expiresIndex = cursor.getColumnIndex(CompanionContract.COLUMN_EXPIRES_AT)
        val expiresAt = if (expiresIndex >= 0 && !cursor.isNull(expiresIndex)) {
            runCatching { cursor.getLong(expiresIndex) }.getOrNull()?.takeIf { it > 0L }
        } else {
            null
        }
        // An already-expired token is worthless; adopting it would only
        // overwrite a good one.
        if (expiresAt != null && expiresAt <= System.currentTimeMillis()) return false

        AttestationStore.storeTesterToken(token, expiresAt, apiKey)
        return true
    }

    /** Package that currently owns the companion authority, or null. */
    private fun resolveProviderPackage(context: Context): String? =
        runCatching {
            context.packageManager
                ?.resolveContentProvider(CompanionContract.AUTHORITY, 0)
                ?.packageName
        }.getOrNull()

    /**
     * Signature pin. Internal rather than private so the fail-closed
     * behaviour is directly testable.
     *
     * API 28+ uses [PackageManager.hasSigningCertificate], which is
     * rotation-aware (an app that rotated its signing key still matches
     * its former certificate). API 26–27 has no rotation concept, so we
     * fall back to `GET_SIGNATURES` and require EVERY signature in the
     * set to be pinned — a multi-signed APK carrying one untrusted
     * certificate must not pass.
     *
     * With an empty pin list this returns false for everything. That is
     * the deliberate fail-closed default while the Play App Signing
     * digest is still unknown (see [CompanionContract.SIGNING_SHA256]).
     */
    internal fun isCompanionTrusted(context: Context, packageName: String): Boolean {
        val pinned = CompanionContract.SIGNING_SHA256
            .mapNotNull { normalizeDigest(it) }
            .toSet()
        if (pinned.isEmpty()) return false
        val pm = context.packageManager ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pinned.any { digest ->
                    val bytes = hexToBytes(digest) ?: return@any false
                    pm.hasSigningCertificate(
                        packageName,
                        bytes,
                        PackageManager.CERT_INPUT_SHA256,
                    )
                }
            } else {
                @Suppress("DEPRECATION")
                val signatures = pm
                    .getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
                    .signatures
                !signatures.isNullOrEmpty() &&
                    signatures.all { sha256Hex(it.toByteArray()) in pinned }
            }
        } catch (_: Exception) {
            false
        }
    }

    /** Lowercase, colon-free hex, or null when the input is not a hex digest. */
    internal fun normalizeDigest(raw: String): String? {
        val cleaned = raw.trim().replace(":", "").replace(" ", "").lowercase()
        if (cleaned.isEmpty() || cleaned.length % 2 != 0) return null
        if (!cleaned.all { it in '0'..'9' || it in 'a'..'f' }) return null
        return cleaned
    }

    /** Hex string to bytes, or null when it is malformed. */
    internal fun hexToBytes(hex: String): ByteArray? {
        val normalized = normalizeDigest(hex) ?: return null
        return try {
            ByteArray(normalized.length / 2) { i ->
                normalized.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
