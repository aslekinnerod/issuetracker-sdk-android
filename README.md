# Issuetracker SDK for Android

Drop-in issue reporter for Android apps. Shake the device (or call `Issuetracker.report()`) to capture a screenshot and file an issue directly into a pre-configured Issuetracker project.

## Install

Maven Central (once released):

```kotlin
dependencies {
    implementation("io.issuetracker:sdk:0.1.0")
}
```

## Usage

1. Create an API key in the Issuetracker web app: `Project → ⚙ settings → API keys → Generate key`.
2. Configure once at launch in your `Application`:

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Issuetracker.configure(
            application = this,
            apiKey = "it_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
        )
    }
}
```

That's it. Either gesture brings up the reporter:

| Trigger | Notes |
| --- | --- |
| Shake the device | Accelerometer-based — real devices only, not the emulator |
| Two-finger long-press for 3 seconds | Anywhere in the app; works in the emulator too |
| `Issuetracker.report()` | Programmatic, e.g. from a "Report a bug" menu item |

Both gestures are enabled by default. Disable individually via `shakeToReport = false` or `longPressToReport = false` on `configure(...)`.

The SDK talks to Issuetracker's hosted backend — there is no endpoint to configure. Staging-prefixed keys (`it_staging_…`) are routed to the staging environment automatically; everything else hits production.

## Manual trigger

```kotlin
Button(onClick = { Issuetracker.report() }) { Text("Report bug") }
```

## Identify the reporter

If your app already knows the user's name, skip the "What should we call you?" prompt:

```kotlin
Issuetracker.identify(name = "Alice Andersen")
```

## Breadcrumbs

Record up to 5 recent user actions to attach to any report (or auto-generated crash report on the next launch):

```kotlin
Issuetracker.recordAction("login_tapped")
Issuetracker.recordAction("viewed_product", metadata = mapOf("sku" to "abc-123"))
```

## Platform

- Android 8.0 (API 26) and up
- Kotlin + Jetpack Compose
- No third-party runtime dependencies (Compose runtime adds ~2 MB to app size if not already used)

## License

MIT
