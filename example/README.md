# Wirex Mobile SDK — Android example

A native Android app (Kotlin, View-based) that exercises **every surface** of the SDK — card
**Reveal**, card **Collect**, the **3-D Secure** step-up, **Wallet** top-up (Apple Pay / Google
Pay) and **Trade**. One Maven artifact, `com.wirexapp.baas:mobile-sdk`, carries every surface.

## Install & run

```sh
./gradlew :app:installDebug      # build + install on a running device/emulator
# or open the folder in Android Studio and Run
```

The app depends on the published coordinate `com.wirexapp.baas:mobile-sdk:0.3.15`
(`app/build.gradle.kts`), resolved from Maven Central.

## Configure at runtime

Nothing is compiled in. The **Config** tab is a per-field editor over `LiveConfig`, the single store
every surface reads live — type a value and the next call picks it up, no rebuild. It holds:

- the **environment** (`test` / `production`), a name the SDK maps onto the hosts it ships with;
- integration parameters — chain id, company id, user email, user wallet, card id, cardholder name;
- secrets (bearer, step-up action token, the Trade tab's owner key), held in memory only and never
  persisted or logged.

The Wallet surface takes the same environment name from the Config tab.

### Where the values come from

Three sources fill the config, and a higher one wins:

| Order | Source | Notes |
|---|---|---|
| 1, highest | Launch extras, `adb shell am start -e <key> <value>` | **Debug builds only**: a release build ignores them. A field set this way is read-only on the Config tab until the app restarts |
| 2 | The Config tab, typed or pasted | `ownerKey` stays in memory only |
| 3 | A `wirexqa://seed?d=…` link | Refuses a link that carries `ownerKey`. A value typed on the Config tab now wins over the link's |

`Reset all` clears what the Config tab and seed links set. Launch extras stay until the app restarts.
Clearing a typed field brings back the seed link's value, if a link set one.

## The Trade tab

The tab runs a stock or coin trade against a real environment. It needs `environment`, `chainId`,
`companyId` and `ownerKey`, a test user's key exported from the company's web app.

- The tab signs with `ownerKey` in `TestSigner.kt`, the one file that holds a key. It is for testing
  only: a real host asks the user's own wallet to sign.
- The bearer comes from the key, through `POST /api/v1/user/authorize` in wallet-signature mode. A
  pasted `bearer` wins over the login. The login goes to the SDK's API host for the environment, or
  to `baasApiUrl` when that is set.
- On `production` the tab refuses a trade worth more than 10 USD.

`tools/trade-launch.sh` opens a debug build on the Trade tab with the values prefilled. It reads
`WIREX_ENVIRONMENT`, `WIREX_CHAIN_ID`, `WIREX_COMPANY_ID`, `WIREX_OWNER_KEY` and, optionally,
`WIREX_BEARER` and `WIREX_BAAS_API_URL` from the shell, or from `example/trade.local.env`:

```sh
./gradlew :app:installDebug
tools/trade-launch.sh
```

### Before the SDK release: build against the SDK sources

Until the SDK version with `Trade` is published, this branch builds only against a local checkout of the SDK
sources, through a dev-only Gradle property. Without it the build resolves the published artifact, which has no
`Trade` yet.

```sh
./gradlew -PwirexSdkSource=<path to the baas-android-sdk checkout> :app:installDebug
```

A checkout of the SDK sources has an empty endpoint table, so `WirexTrade.create` refuses every environment. Before
a run, the tester fills the table in that checkout, and empties it again afterwards so that nothing is committed:

```sh
cd <path to the baas-android-sdk checkout>
node tools/inject-endpoints.mjs          # before the build
node tools/inject-endpoints.mjs --empty  # after the run
```

`trade.local.env` is gitignored, and the script prints key names only and refuses to run on CI.
Nothing reads it at build time, so no build, a CI build included, carries a key.

## The surfaces

| Tab | What it shows |
|---|---|
| **Config** | a per-field editor for the environment, integration parameters and secrets, shared by every surface |
| **Reveal** | the cardholder's own PAN / expiry / CVV / PIN in sealed native views |
| **Collect** | a sealed form that tokenizes an external funding card at the acquirer |
| **3DS** | paste a top-up orchestrator URL → the issuer challenge, and the outcome |
| **Wallet** | the hosted Apple Pay / Google Pay checkout (paste a provider block, build a quote — legacy tokenise flow) |
| **Appearance** | restyle every sealed surface at once — presets or a pasted token document |
| **Trade** | Account, Catalog, Quote, Trade and Open orders: a stock or coin trade end to end |

The field contract is the same across the React Native / iOS / Flutter examples — see
`../PARTNER-CONFIG.md`.

> Most emulators have no Google Pay, so the Wallet availability probe answers unavailable there — the
> real answer; a Pay sheet needs a device with Google Pay set up. The checkout still arms.
