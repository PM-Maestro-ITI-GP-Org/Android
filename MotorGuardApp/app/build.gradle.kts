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

        // Filament (via SceneView) ships native renderers for four ABIs. Keep only the
        // two this project targets: x86_64 for the dev emulator, arm64-v8a for the RPi 5.
        ndk {
            abiFilters += listOf("x86_64", "arm64-v8a")
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
    androidResources {
        // car_model.glb is already-compressed PNG payload; re-zipping it at build time
        // only costs time and gains nothing.
        noCompress += "glb"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // Phase 1: diagnostics domain + fake data source (DI swap target for Phase 2)
    implementation(project(":core:vehicle-data-api"))
    implementation(project(":core:vehicle-data-fake"))

    // Diagnostics 3D car stage (Filament under the hood). Pinned to 2.3.0: it is the last
    // release built against kotlin-stdlib 2.0.21, which is this project's Kotlin version.
    // Every 4.x needs Kotlin 2.3+, which would force a toolchain bump on all fragment owners.
    implementation("io.github.sceneview:sceneview:2.3.0")

    testImplementation("junit:junit:4.13.2")

    // Compose (versions come from the BOM)
    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
