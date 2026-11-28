plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The published SDK version this example resolves. `-PwirexSdkVersion` names another, for `-PwirexSdkRepo`.
val sdkVersion = providers.gradleProperty("wirexSdkVersion").orNull ?: "0.3.15"

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

    // BuildConfig.DEBUG gates the launch extras: a release build never reads them.
    buildFeatures { buildConfig = true }

    kotlinOptions { jvmTarget = "17" }
    sourceSets {
        getByName("main") { java.srcDirs("src/main/kotlin") }
        getByName("test") { java.srcDirs("src/test/kotlin") }
    }
}

// The Android binding's loader runs first and cannot load on the JVM, so unit tests take the JVM one.
configurations.matching { it.name.contains("UnitTest") }.configureEach {
    exclude(group = "fr.acinq.secp256k1", module = "secp256k1-kmp-jni-android")
}

dependencies {
    // The published SDK — one artifact carries all four surfaces (card reveal, card collect,
    // 3-D Secure, wallet top-up).
    implementation("com.wirexapp.baas:mobile-sdk:$sdkVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // The wallet checkout opens top-level in a Chrome Custom Tab (CustomTabsIntent).
    implementation("androidx.browser:browser:1.8.0")
    // TestSigner only: the example signs with a test user's key. The SDK itself holds no key.
    implementation("fr.acinq.secp256k1:secp256k1-kmp-jni-android") { version { strictly("0.19.0") } }
    implementation("org.kotlincrypto.hash:sha3") { version { strictly("0.7.0") } }

    testImplementation("junit:junit:4.13.2")
    testImplementation("fr.acinq.secp256k1:secp256k1-kmp-jni-jvm") { version { strictly("0.19.0") } }
    testImplementation("fr.acinq.secp256k1:secp256k1-kmp") { version { strictly("0.19.0") } }
}
