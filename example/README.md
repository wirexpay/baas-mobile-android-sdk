# Wirex Mobile SDK — Android example

A native Android app (Kotlin, View-based) that exercises **every surface** of the SDK — card
**Reveal**, card **Collect**, the **3-D Secure** step-up and **Wallet** top-up (Apple Pay / Google
Pay). One Maven artifact, `com.wirexapp.baas:mobile-sdk`, carries all four surfaces.

## Install & run

```sh
./gradlew :app:installDebug      # build + install on a running device/emulator
# or open the folder in Android Studio and Run
```

The app depends on the published coordinate `com.wirexapp.baas:mobile-sdk:0.3.13`
(`app/build.gradle.kts`), resolved from Maven Central.

## Configure at runtime

Nothing is compiled in. The **Config** tab is a per-field editor over `LiveConfig`, the single store
every surface reads live — type a value and the next call picks it up, no rebuild. It holds:

- the **environment** (`test` / `production`), a name the SDK maps onto the hosts it ships with;
- integration parameters — chain id, user email, user wallet, card id, cardholder name;
- secrets (bearer, step-up action token), held in memory only and never persisted or logged.

Credentials are never passed as intent extras — a bearer in an extra is visible in `logcat` and
`dumpsys`. The Wallet surface takes the same environment name
from the Config tab.

## The surfaces

| Tab | What it shows |
|---|---|
| **Config** | a per-field editor for the environment, integration parameters and secrets, shared by every surface |
| **Reveal** | the cardholder's own PAN / expiry / CVV / PIN in sealed native views |
| **Collect** | a sealed form that tokenizes an external funding card at the acquirer |
| **3DS** | paste a top-up orchestrator URL → the issuer challenge, and the outcome |
| **Wallet** | the hosted Apple Pay / Google Pay checkout (paste a provider block, build a quote — legacy tokenise flow) |
| **Appearance** | restyle every sealed surface at once — presets or a pasted token document |

The field contract is the same across the React Native / iOS / Flutter examples — see
`../PARTNER-CONFIG.md`.

> Most emulators have no Google Pay, so the Wallet availability probe answers unavailable there — the
> real answer; a Pay sheet needs a device with Google Pay set up. The checkout still arms.
