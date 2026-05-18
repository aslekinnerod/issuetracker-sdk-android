import com.vanniktech.maven.publish.SonatypeHost

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.maven.publish)
}

group = "no.issuetracker"
version = "0.5.0"

// The Vanniktech plugin reads credentials from Gradle properties
// `mavenCentralUsername` / `mavenCentralPassword` and signing key
// material from `signingInMemoryKey` / `signingInMemoryKeyPassword`.
// In CI these come from environment variables prefixed with
// `ORG_GRADLE_PROJECT_` — see .github/workflows/publish.yml.
mavenPublishing {
    // Sonatype Central Portal endpoint (new system, replaces OSSRH).
    // Must be set explicitly — the plugin defaults to legacy OSSRH,
    // which returns HTTP 402 for accounts created after 2024.
    // `automaticRelease = true` skips the manual "Release" click in the
    // Sonatype UI — once the staging repo passes validation, the
    // artifacts are promoted to Maven Central immediately. Disable
    // if you want a manual gate per release.
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)
    signAllPublications()

    coordinates("no.issuetracker", "sdk", project.version.toString())

    pom {
        name.set("Issuetracker SDK for Android")
        description.set("Drop-in issue reporter SDK for Android apps. Shake the device or call Issuetracker.report() to capture a screenshot and file an issue.")
        inceptionYear.set("2026")
        url.set("https://github.com/aslekinnerod/issuetracker-sdk-android")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
                distribution.set("https://opensource.org/licenses/MIT")
            }
        }
        developers {
            developer {
                id.set("aslekinnerod")
                name.set("Asle Kinnerod")
                email.set("asle78@gmail.com")
                url.set("https://github.com/aslekinnerod")
            }
        }
        scm {
            url.set("https://github.com/aslekinnerod/issuetracker-sdk-android")
            connection.set("scm:git:git://github.com/aslekinnerod/issuetracker-sdk-android.git")
            developerConnection.set("scm:git:ssh://git@github.com/aslekinnerod/issuetracker-sdk-android.git")
        }
    }
}

android {
    namespace = "no.issuetracker.sdk"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "consumer-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
    // Robolectric needs Android resources on the unit-test classpath to
    // emulate Context + SharedPreferences. The default is `false`.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.tooling.preview)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    // Real org.json on the JVM test classpath. The Android SDK stubs
    // throw at runtime so unit tests need the actual library.
    testImplementation(libs.json)
}

// `./gradlew :sdk:publishToMavenLocal` still works during local
// development (handled by the maven-publish plugin pulled in
// transitively via vanniktech). The Maven Central publication is
// configured above via mavenPublishing { ... }.
