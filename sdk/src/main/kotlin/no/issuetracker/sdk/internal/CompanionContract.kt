package no.issuetracker.sdk.internal

import android.net.Uri

/**
 * The frozen wire contract between this SDK and the "Issuetracker
 * Testers" companion app (ADR-0005 Decision 11).
 *
 * Every string here ends up baked into customer binaries the moment
 * they ship a build against this SDK, so they are frozen forever:
 * renaming any of them silently breaks attestation on every install
 * already in the field. Do not "tidy" them, do not derive them, do not
 * make them configurable.
 *
 *   applicationId       no.issuetracker.testers
 *   authority           no.issuetracker.testers.attestation  (exported,
 *                       no custom permission — the provider
 *                       authenticates its caller by uid instead)
 *   provider URI        content://no.issuetracker.testers.attestation/token
 *   api key transport   query-args Bundle, key no.issuetracker.sdk.apiKey
 *
 * The provider is keyed on the raw API key and returns AT MOST ONE row
 * — tokens are per project (Decision 9). There is no collection URI to
 * enumerate, by design.
 */
internal object CompanionContract {

    /** Companion applicationId. Also the `<queries>` entry in our manifest. */
    const val PACKAGE = "no.issuetracker.testers"

    /** Exported ContentProvider authority. */
    const val AUTHORITY = "no.issuetracker.testers.attestation"

    /** The single supported path. */
    const val TOKEN_PATH = "token"

    /**
     * `content://no.issuetracker.testers.attestation/token`.
     *
     * Built lazily so class initialisation stays free of Android
     * framework calls — the constants above are readable from a plain
     * JVM test without a Robolectric environment.
     */
    val TOKEN_URI: Uri by lazy { Uri.parse("content://$AUTHORITY/$TOKEN_PATH") }

    /** Bundle key carrying the raw API key into `ContentResolver.query`. */
    const val QUERY_ARG_API_KEY = "no.issuetracker.sdk.apiKey"

    // Cursor columns. The SDK only consumes `token` and `expires_at`;
    // `project_key` / `project_name` / `scope` / `tester_id` are part of
    // the contract for the companion's own diagnostics and for future
    // SDK use. Reading only what we need is deliberate: an added or
    // reordered column must never be able to break hydration.
    const val COLUMN_TOKEN = "token"
    const val COLUMN_EXPIRES_AT = "expires_at"
    const val COLUMN_PROJECT_KEY = "project_key"
    const val COLUMN_PROJECT_NAME = "project_name"
    const val COLUMN_SCOPE = "scope"
    const val COLUMN_TESTER_ID = "tester_id"

    /**
     * Play **App Signing** certificate SHA-256 digests of the companion
     * app — the certificate Google signs the delivered APK with, NOT
     * the upload key.
     *
     * A LIST, not a constant, from day one: pinning a single digest
     * would make a Play signing-key rotation a breaking event for every
     * customer binary already shipped against an older SDK. A rotation
     * is handled by publishing an SDK release whose list carries both
     * the old and the new digest, and by `hasSigningCertificate`'s own
     * rotation awareness on API 28+.
     *
     * Format: lowercase hex, with or without colons (normalised before
     * comparison).
     *
     * EMPTY UNTIL RELEASE. The real digest is read off the Play Console
     * ("App integrity" → "App signing key certificate" → SHA-256) once
     * the companion's first internal-track build is uploaded, and filled
     * in here before this SDK version is published. While the list is
     * empty the client FAILS CLOSED: no package is ever trusted and no
     * token is ever adopted from the provider. That is the only safe
     * default — an unpinned query would hand the raw API key to whoever
     * squatted the authority first.
     */
    val SIGNING_SHA256: List<String> = emptyList()
}
