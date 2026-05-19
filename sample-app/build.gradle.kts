import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Read the SDK API key from local.properties so the value never lands
// in git. Falls back to a placeholder that the SDK will reject, so a
// fresh checkout still builds and the user is nudged to set their own
// key when nothing reports. Add to local.properties (sibling of this
// file's parent settings.gradle.kts):
//
//     issuetracker.sdk.apiKey=it_staging_xxxxxxxxxxxxxxxxxxxxxxxx
val sampleApiKey: String = run {
    val props = Properties()
    val f = rootProject.file("local.properties")
    if (f.exists()) props.load(f.inputStream())
    props.getProperty("issuetracker.sdk.apiKey", "it_staging_REPLACE_ME")
}

android {
    namespace = "no.issuetracker.sample"
    compileSdk = 36

    defaultConfig {
        applicationId = "no.issuetracker.sample"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "SDK_API_KEY", "\"$sampleApiKey\"")
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
}
