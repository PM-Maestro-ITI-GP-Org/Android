plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.motorguard.ivi"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.motorguard.ivi"
        minSdk = 29          // AAOS baseline
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"

        ndk {
            // MapLibre is the only native dependency and it ships four ABIs, which is ~75 MB of
            // .so files we can never run. The Pi 5 image is arm64; x86_64 keeps the emulator
            // working. Add armeabi-v7a here if you ever target a 32-bit board.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // Compose (versions come from the BOM)
    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Pure-JVM tests for the navigation maths and the generated map style.
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.google.code.gson:gson:2.11.0")

    // Navigation stack — all OSS, no Google Play Services (this is an AOSP build).
    // Map rendering: MapLibre Native (BSD-2). Tiles + routing + search are plain HTTPS
    // calls made by hand, so there is nothing else to pull in. See ui/nav/README.md.
    implementation("org.maplibre.gl:android-sdk:11.8.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    // rememberLauncherForActivityResult, for the runtime location permission.
    implementation("androidx.activity:activity-compose:1.9.3")
}
