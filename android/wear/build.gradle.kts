plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.mypeople.walkolutionodometer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mypeople.walkolutionodometer"
        minSdk = 30  // Wear OS 3.0+
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    // Wear OS Compose (lightweight, optimized for watches)
    implementation(libs.wear.compose.material)
    implementation(libs.wear.compose.foundation)

    // Wear OS Tiles
    implementation(libs.wear.tiles)
    implementation(libs.wear.tiles.material)
    implementation(libs.wear.protolayout)
    implementation(libs.wear.protolayout.material)
    implementation(libs.guava)

    // Wear OS integration
    implementation(libs.play.services.wearable)

    // Note: Ambient mode support is built into Wear OS 6+ automatically
    // We'll rely on native Wear Compose lifecycle for ambient handling

    // Core Android (minimal dependencies for better performance)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    // UI - using Wear Compose only, no phone UI libraries needed
    debugImplementation(libs.androidx.ui.tooling)
}
