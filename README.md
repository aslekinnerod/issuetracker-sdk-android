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
