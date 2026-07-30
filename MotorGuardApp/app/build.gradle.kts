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

        // The reasoning core is native C++ shared with the Linux build.
        ndk {
            // arm64 for the Pi/device, x86_64 so it still runs on the emulator.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake { cppFlags += "-std=c++17" }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // .onnx wake-word models must not be compressed, or ORT can't mmap them.
    androidResources {
        noCompress += listOf("onnx")
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
    // ComposeView inside the voice-overlay service window needs its own
    // ViewTree owners (see VoiceOverlaySession.OverlayHost).
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")

    // Wake word (openWakeWord models run on ONNX Runtime). If the models are
    // absent the detector disables itself and the rail mic button still works.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")

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
