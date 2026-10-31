// The Android client: the core plus the adapter over the provider's SDK. Needs the Android SDK to build;
// CI assembles it, the core's tests cover the logic above the adapter.
plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "dm.sent.voice"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    explicitApi()
}

dependencies {
    api(project(":voice-client-core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.sinch:voice-video-android:6.31.9")
}
