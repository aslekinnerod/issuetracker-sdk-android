import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Same local.properties key as sample-app — one key serves both hosts.
//
// CI has no local.properties, so the env var is the path a runner uses:
// the placeholder below is not merely useless there, it is actively
// misleading. Issuetracker.configure() fetches getSdkConfig with the
// key, the backend answers an unknown key with a terminal reason,
// LifecycleStore flips to one-way TERMINATED and every report trigger
// goes inert (AttestationStore.refreshRemoteConfig). A test that holds
// Play/Pause then waits for the reporter therefore cannot pass on the
// placeholder — which is why ci.yml's tv-e2e job asserts a real key is
// present before it runs anything.
//
// Precedence: env var (CI) > local.properties (laptop) > placeholder.
val sampleApiKey: String = run {
    val fromEnv = System.getenv("ISSUETRACKER_SDK_API_KEY")
    if (!fromEnv.isNullOrBlank()) return@run fromEnv
    val props = Properties()
    val f = rootProject.file("local.properties")
    if (f.exists()) props.load(f.inputStream())
    props.getProperty("issuetracker.sdk.apiKey", "it_staging_REPLACE_ME")
}

android {
    namespace = "no.issuetracker.sampletv"
    compileSdk = 36

    defaultConfig {
        applicationId = "no.issuetracker.sampletv"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "SDK_API_KEY", "\"$sampleApiKey\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug { isMinifyEnabled = false }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
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
        buildConfig = true
    }
}

dependencies {
    implementation(project(":sdk"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.uiautomator)
}
