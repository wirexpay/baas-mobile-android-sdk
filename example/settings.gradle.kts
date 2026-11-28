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
        // DEV ONLY: `-PwirexSdkRepo=<dir>` takes the SDK from a Maven repository the SDK's `publishToMavenLocal`
        // filled, with `-PwirexSdkVersion` naming its version. Only the SDK's group resolves there.
        providers.gradleProperty("wirexSdkRepo").orNull?.let { repo ->
            exclusiveContent {
                forRepository { maven { url = uri(repo) } }
                filter { includeGroup("com.wirexapp.baas") }
            }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "mobile-sdk-example"
include(":app")

// DEV ONLY: `-PwirexSdkSource=<baas-android-sdk checkout>` builds against the SDK's sources instead of the published
// artifact. Without it the published coordinate is used.
providers.gradleProperty("wirexSdkSource").orNull?.let { source ->
    includeBuild(source) {
        dependencySubstitution {
            substitute(module("com.wirexapp.baas:mobile-sdk")).using(project(":mobile-sdk"))
        }
    }
}
