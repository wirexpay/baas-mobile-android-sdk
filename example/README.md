# Wirex Mobile SDK — Android example

A native Android app (Kotlin, View-based) that exercises **every surface** of the SDK — card
**Reveal**, card **Collect**, the **3-D Secure** step-up and **Wallet** top-up (Apple Pay / Google
Pay). One Maven artifact, `com.wirexapp.baas:mobile-sdk`, carries all four surfaces.

## Install & run

```sh
./gradlew :app:installDebug      # build + install on a running device/emulator
# or open the folder in Android Studio and Run
```

The app depends on the published coordinate `com.wirexapp.baas:mobile-sdk:0.3.7`
(`app/build.gradle.kts`), resolved from Maven Central.

## The environment

- Card surfaces: `WirexSdkConfig.ENVIRONMENT`
  (`app/src/main/kotlin/com/wirexapp/baas/cards/config/WirexSdkConfig.kt`).
- Wallet surface: `app/src/main/assets/wirex-wallet-sdk.json`.

Both name an environment (`test` / `production`) — a name, never a URL; the SDK maps it onto the hosts
it holds. Set them to the environment Wirex assigned.

## Enter credentials at runtime

Nothing is compiled in. Credentials live in `LiveConfig`, filled at runtime:

**Paste JSON** on the Reveal or Collect screen (the credentials box) —
`{"bearer":"…","email":"…","cardId":"…","chainId":"…","actionToken":"…","cardholderName":"…"}`.
Credentials are never passed as intent extras — a bearer in an extra is visible in `logcat` and `dumpsys`.

## The surfaces

| Tab | What it shows |
|---|---|
| **Reveal** | the cardholder's own PAN / expiry / CVV / PIN in sealed native views |
| **Collect** | a sealed form that tokenizes an external funding card at the acquirer |
| **3DS** | paste a top-up orchestrator URL → the issuer challenge, and the outcome |
| **Wallet** | the hosted Apple Pay / Google Pay checkout (paste a provider block, build a quote — legacy tokenise flow) |

The field contract is the same across the React Native / iOS / Flutter examples — see
`../PARTNER-CONFIG.md`.

> Most emulators have no Google Pay, so the Wallet availability probe answers unavailable there — the
> real answer; a Pay sheet needs a device with Google Pay set up. The checkout still arms.
