// Native Android example.
//
// The app depends on the PUBLISHED SDK coordinate `com.wirexapp.baas:mobile-sdk` — one artifact that
// carries all four surfaces (card reveal, card collect, 3-D Secure, wallet top-up).
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "mobile-sdk-example"
include(":app")
