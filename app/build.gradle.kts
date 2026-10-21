import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    kotlin("plugin.compose")
    kotlin("plugin.serialization")
}

android {
    namespace = "app.gymquest"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.gymquest"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Phones only: keeps the APK much smaller than shipping x86 emulator libraries too.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            // R8 shrinks and optimises the app: a smaller, faster APK.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    // The pose model is bundled in the APK, so counting works with no internet.
    androidResources {
        noCompress += listOf("tflite", "lite", "mp3")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))

    // Compose
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.navigation:navigation-compose:2.10.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.core:core-ktx:1.19.1")

    // Camera + on-device pose detection and image labelling (models ship inside the app)
    implementation("androidx.camera:camera-core:1.6.2")
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")
    implementation("com.google.mlkit:pose-detection:17.0.0")
    implementation("com.google.mlkit:pose-detection-accurate:17.0.0")
    implementation("com.google.mlkit:image-labeling:17.0.9")

    // Location, maps, background work, music
    implementation("com.google.android.gms:play-services-location:21.4.0")
    implementation("org.maplibre.gl:android-sdk:13.7.1")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("androidx.media3:media3-exoplayer:1.11.1")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // Instrumented smoke test (runs on an emulator in CI)
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
