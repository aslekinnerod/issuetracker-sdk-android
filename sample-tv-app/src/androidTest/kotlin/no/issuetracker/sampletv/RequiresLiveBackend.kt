package no.issuetracker.sampletv

/**
 * Marks an instrumented test that FILES A REAL ISSUE against the live
 * Issuetracker backend, so that running it has a side effect somebody
 * has to clean up.
 *
 * It does NOT mark "the tests that need an API key" — every test in
 * this module needs one. The SDK contacts getSdkConfig during
 * `configure()`, and a key the backend does not recognise (including
 * the `it_staging_REPLACE_ME` placeholder used when no key is
 * supplied) terminates the SDK before any trigger can fire. Supply a
 * key whose allowedClients whitelist includes `no.issuetracker.sampletv`
 * via `ISSUETRACKER_SDK_API_KEY` in the environment or
 * `issuetracker.sdk.apiKey` in `local.properties`.
 *
 * ci.yml's tv-e2e job runs the whole suite, this annotation included.
 * To skip the side-effecting tests locally:
 *
 *     ./gradlew :sample-tv-app:connectedDebugAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.notAnnotation=\
 *         no.issuetracker.sampletv.RequiresLiveBackend
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
annotation class RequiresLiveBackend
