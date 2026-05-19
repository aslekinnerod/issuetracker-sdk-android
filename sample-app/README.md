# Issuetracker SDK — Android sample app

A real installable Android app that exercises every public surface of
the SDK so you can shake-test changes in 30 seconds. Same feature
checklist applies to the iOS, Flutter, and React Native sample apps
— keep them in lockstep when adding capabilities.

## How to run

1. Drop an API key from the Issuetracker admin UI into
   `<repo-root>/local.properties`:

   ```
   issuetracker.sdk.apiKey=it_staging_xxxxxxxxxxxxxxxxxxxxxxxx
   ```

   If you skip this step the build still works but the SDK will fall
   into `invalid_api_key` → TERMINATED on the first report — a useful
   demo path, just not the happy one.

2. Open the project in Android Studio (`File → Open` on the
   `sdk-android` directory) or run from the command line:

   ```
   JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
     ./gradlew :sample-app:installDebug
   ```

   AGP 8.10 needs JDK 17–21; the bundled JBR works.

3. Launch **Issuetracker SDK demo** from the device's app drawer.

## Feature checklist

Each section in the app maps 1:1 to a surface in the SDK. When you
add a feature to the SDK, add a section here AND in the sister sample
apps so the four platforms stay comparable.

| Section | What it exercises | SDK API |
|---|---|---|
| **Lifecycle** | Shows the last `onConfigurationError` reason the SDK reported. Has a "Reset" button so you can retry a path that has already fired. | `onConfigurationError` callback on `configure(...)` |
| **Reporting** | One button programmatically opens the reporter. Shake-to-report and two-finger long-press also trigger it. | `Issuetracker.report()` + `shakeToReport` + `longPressToReport` |
| **Identity** | Sets / clears the display name that stamps every report. | `identify(name)` / `clearIdentity()` |
| **Breadcrumbs** | Records up to two action breadcrumbs with optional metadata. The most-recent 5 ride along on every report. | `recordAction(name, metadata?)` |
| **Onboarding** | Re-presents the first-launch trigger introduction popover regardless of whether it has been shown before. | `Issuetracker.showOnboarding()` |
| **TERMINATED-UI i18n** | Toggle a Norwegian translation of the terminal-state strings. Restart the app to apply (`configure(...)` runs once in `Application.onCreate`). | `terminatedUI: TerminatedUiStrings` on `configure(...)` |
| **Destructive** | Crash-test button (with confirmation). The next launch picks up the crash and queues a report. | `Issuetracker.testCrash()` |

## Folder layout

```
sample-app/
  README.md                  ← you are here
  build.gradle.kts           ← reads local.properties → BuildConfig.SDK_API_KEY
  src/main/
    AndroidManifest.xml
    kotlin/no/issuetracker/sample/
      SampleApplication.kt   ← Issuetracker.configure() runs here
      MainActivity.kt        ← demo screen + one composable per section
```

The companion sample apps mirror this layout per platform convention:

- `sdk-ios/example-app/` (Swift Package + SwiftUI single screen)
- `sdk-flutter/example/` (existing — extend to match this feature set)
- `sdk-react-native/example/` (existing — extend to match)
- `sdk-web/example/` (existing — extend to match)

Each sample-app README is the same shape (How to run + Feature
checklist table) so a person can hop between platforms and find the
same affordances.

## Adding a new feature

When the SDK gains a new public surface:

1. Add a section to **this** app (Compose composable in
   `MainActivity.kt` + supporting code in `SampleApplication.kt` if
   it needs configure-time wiring).
2. Add the row to the **Feature checklist** table above.
3. Replicate to the other three sample apps using the same section
   title + same affordance (button name / toggle label).
4. Add an entry to `docs/OPERATIONS-FOLLOWUPS.md` only if a real
   manual verification step is owed; otherwise the sample apps are
   self-documenting.

## What this app intentionally is NOT

- Not a production reference app — uses `MODE_PRIVATE`
  SharedPreferences for tiny state (i18n toggle + last error display),
  not Room / DataStore.
- Not a layout exemplar — single scroll, surfaceVariant cards. The
  point is to surface every SDK affordance, not to look pretty.
- Not multi-screen — keeps the surface flat so anyone testing the SDK
  can find every feature without learning a navigation tree.
