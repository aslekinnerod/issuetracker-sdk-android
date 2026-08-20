# Issuetracker SDK for Android

Drop-in issue reporter for Android apps. Shake the device, two-finger
long-press for 3 seconds, or call `Issuetracker.report()` — capture a
screenshot and file an issue directly into a pre-configured Issuetracker
project.

## Install

`build.gradle.kts`:

```kotlin
dependencies {
  implementation("no.issuetracker:sdk:0.5.0")
}
```

The artifact is published to Maven Central — no extra repositories needed.

## Quickstart

```kotlin
class MyApp : Application() {
  override fun onCreate() {
    super.onCreate()
    Issuetracker.configure(this, apiKey = "it_...")
  }
}
```

Register the `Application` class in `AndroidManifest.xml`:

```xml
<application android:name=".MyApp" ...>
```

## Android TV / Google TV / Fire TV

The same artifact serves televisions — no separate dependency, no
extra setup. On TV form factors the flags change meaning at runtime
(see `sdk-spec/TV_TRIGGERS.md`):

- `longPressToReport` wires **hold Play/Pause on the remote for
  2 seconds** instead of the two-finger touch press.
- `shakeToReport` is inert — there is no shake in the TV trigger
  model.
- The screenshot annotation editor (touch-only) is hidden; everything
  else — TERMINATED lifecycle, attestation, breadcrumbs, identity —
  behaves exactly as on phones. Reports are stamped
  `formFactor: "tv"`.

Apps with heavy playback surfaces, where users hold Play/Pause
instinctively, should pass `longPressToReport = false` and wire
`Issuetracker.report()` to a settings row instead. A held button is
invisible UX — opt into the one-time onboarding panel with
`showOnboarding = true`. See `sample-tv-app/` for a runnable leanback
host.

## Accessibility

The built-in triggers are gestures: shake is motion actuation (WCAG
2.5.4) and the two-finger long-press is a multipoint gesture (WCAG
2.5.1). Neither is reliably available to everyone — TalkBack intercepts
multi-finger gestures, and users with motor impairments or mounted
devices may be unable to shake. If your integration enables either
trigger, you MUST provide an accessible, single-pointer, motion-free
alternative. Two ways to get one (ADR-0008):

1. **Let the SDK provide it** — enable one or both opt-in flags:

   ```kotlin
   Issuetracker.configure(
     this,
     apiKey = "it_...",
     // Registers a TalkBack custom action "Report a bug" on the
     // current activity whenever a screen reader with touch
     // exploration is running. No visible chrome for anyone else.
     accessibilityAction = true,
     // Overlays a small floating "Report" button (bottom-end corner)
     // on every activity — the no-code visible entry point.
     showReportButton = true,
   )
   ```

2. **Wire your own visible control** — for example a "Report a bug"
   item in a settings or help menu that calls
   `Issuetracker.report()`.

`accessibilityAction` alone covers screen-reader users but not
sighted users who cannot shake; pair it with `showReportButton` or
your own control for full coverage. Both flags follow live
re-`configure` — flipping them at runtime takes effect immediately.

You should also offer an in-app user setting to turn motion actuation
off — for example a "Shake to report" toggle that re-runs
`Issuetracker.configure(...)` with `shakeToReport = false`.

## Full documentation

API reference, triggers, TERMINATED behavior, crash reporting, identity
flow, breadcrumbs, and troubleshooting — see
**[docs.issuetracker.no/sdk/android](https://docs.issuetracker.no/sdk/android)**.

## Requirements

- Android 8.0+ (API 26)
- Kotlin 1.9+
- compileSdk 34+

## License

MIT
