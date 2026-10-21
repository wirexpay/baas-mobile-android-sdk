# Wirex Mobile SDK for Android

Wirex card and wallet features for a partner Android app. The partner app never handles the card
values.

- **Reveal** (`WirexReveal`, `WirexRevealFieldView`) shows cardholders their own card number, expiry,
  CVV and PIN. The SDK draws each field; the host places it and supplies strings, colours and icons.
- **Collect** (`WirexCollect`, `WirexCollectForm`) takes a card the user is entering and returns a
  token in place of the card values.
- **Wallet top-up** (`WirexWalletCheckoutView`) hosts the Wirex checkout with Google Pay.

Use of the SDK requires an agreement with Wirex. See [LICENSE](LICENSE).

📖 **Integration guide:** https://wirexpay.github.io/baas-mobile-android-sdk/ — and a runnable example in [`example/`](example/).

## Requirements

- Android 6.0 (API 23) or later.
- Java 17 bytecode, and Kotlin 2.0 or later for a Kotlin host.

## Installation

The SDK is published to Maven Central as `com.wirexapp.baas:mobile-sdk`.

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("com.wirexapp.baas:mobile-sdk:0.3.13")
}
```

Its own dependencies are the Kotlin standard library, `kotlinx-coroutines-android` and
`androidx.webkit`.

## Environments

The SDK supports two environments, `production` and `test`, and the same two apply to every surface.
The host chooses one with `testMode`, or by name. The SDK holds the addresses, and no public API takes
a URL. An unknown name is an error: the SDK never falls back to another environment.

The card surfaces also need the chain ID of the partner's integration. It is passed when the
environment is created and sent with every request.

## Credentials

The SDK never issues credentials. The host implements `WirexCredentialProvider`, which supplies:

- the user's access token;
- an action token for each `WirexActionScope` the SDK asks for;
- the user's email and wallet address.

The SDK asks for a token when it needs one, so the host never has to push tokens in advance.

## Documentation

Every public type and member is documented. The sources and javadoc jars are published with each
version, so Android Studio shows the documentation for the SDK's API.
