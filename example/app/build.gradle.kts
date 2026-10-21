plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The published SDK version this example resolves.
val sdkVersion = "0.3.13"

android {
    namespace = "com.wirexapp.baas.example"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.wirexapp.baas.example.android"
        // Matches the SDK's floor, so the example proves the floor rather than assuming it.
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = sdkVersion
    }

    buildTypes {
        getByName("debug") { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }
    sourceSets { getByName("main") { java.srcDirs("src/main/kotlin") } }
}

dependencies {
    // The published SDK — one artifact carries all four surfaces (card reveal, card collect,
    // 3-D Secure, wallet top-up).
    implementation("com.wirexapp.baas:mobile-sdk:$sdkVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // The wallet checkout opens top-level in a Chrome Custom Tab (CustomTabsIntent).
    implementation("androidx.browser:browser:1.8.0")
}
