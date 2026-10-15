# Wirex Mobile SDK (Android) — integration context (for an AI assistant)

> Audience: an AI assistant helping a partner integrate the Wirex Mobile SDK on native Android (Kotlin).
> This file is the working context — read top to bottom; every fact is load-bearing. Dense on purpose:
> no marketing. The human-facing version is `INTEGRATION.html`. For exact backend request/response
> shapes, defer to the Wirex BaaS API reference — this file does not restate them.

---

## 1. Mental model (the one thing to get right)

The SDK owns **sealed UI surfaces** and nothing else. Secrets (a revealed PAN/CVV, a typed card number,
a wallet payment token) live inside a native view or a Wirex-hosted web frame and **never reach app
code**. The SDK **never mints a credential, never stores one**, and (except where noted) **never decides
a money movement**.

The integrator supplies two things:

1. **Authentication** — a `WirexCredentialProvider` the SDK calls on demand (bearer + step-up signature + identity).
2. **Orchestration** — the partner **backend** prices and executes money-moving work (top-up pricing/execution, the 3DS URL, the wallet checkout token) and ships the provider config.

| Surface | Needs credentials? | What it does |
|---|---|---|
| Reveal | yes | show the cardholder their own PAN/expiry/CVV/PIN in sealed native views |
| Collect | yes | capture an external card in a sealed native form and tokenize it at the acquirer |
| 3-D Secure | **no** | render the issuer challenge for a card payment; report the outcome |
| Wallet | yes | embed the Wirex-hosted Apple Pay / Google Pay checkout |

**One artifact.** `com.wirexapp.baas:mobile-sdk` carries all four surfaces, under
`com.wirexapp.baas.cards.{reveal,collect,threeds}` and `com.wirexapp.wallet.topup`.

---

## 2. Who integrates, and exactly what they must have

**The integrator is a partner** issuing Wirex-backed cards to their own users.

Wirex assigns (per integration):
- `environment` — a NAME (`test` / `production`), resolved via `WirexEnvironment.resolve(...)` (cards) and `WirexWalletEnvironment.resolve(...)` (wallet). Never a URL; the SDK holds the hosts.
- `chainId` — the `X-Chain-Id` on every authenticated call.
- `clientId` — optional. Not auth.

Partner owns / must build:
- **A bearer** for the signed-in user → `accessToken()`.
- **A wallet step-up signature** capability (user signs a confirmation message; the backend exchanges it for a short-lived action token) → `actionToken(scope)`.
- **The identity**: ONE of email or EOA wallet address → `userEmail` / `userWallet`.
- **A provisioned card** (card id) for Reveal/Collect.
- **Backend orchestration** for Wallet/Collect top-ups (price, execute, mint a checkout token, obtain the 3DS URL). Wirex BaaS API — see its reference.
- **Provider config** for Wallet: the wallet provider block from the partner's app config.

---

## 3. Install

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("com.wirexapp.baas:mobile-sdk:0.3.7")   // one artifact, all four surfaces
}
```
Add the Maven Central repository that serves it. minSdk 23+. The wallet web view needs INTERNET.

---

## 4. Credential provider (the one object the partner implements)

```kotlin
class AppCredentials : WirexCredentialProvider {
    override suspend fun accessToken(): String { /* partner auth → Wirex bearer */ }
    override suspend fun actionToken(scope: WirexActionScope): String { /* CARD_DATA / PIN → action token */ }
    override val userEmail: String?    // return ONE of email / wallet
    override val userWallet: String?   // the EOA address, if identifying by wallet
}
```

Rules the SDK enforces / expects:
- **Exactly one identity.** Both `userEmail` and `userWallet` is a backend `400`. Return one.
- **The SDK sends the bearer as-is and does not retry a 401.** `accessToken()` must check expiry and refresh.
- `actionToken(scope)` is the user's **wallet signature** over a confirmation action, exchanged by the backend for a short-lived, one-shot token. A user declining throws `WirexDeclined`.
- Read **per call** — a value refreshed mid-session takes effect on the next SDK call.

---

## 5. Surfaces — exact API

### 5.1 Reveal  (needs credentials)
```kotlin
import com.wirexapp.baas.cards.reveal.*
import com.wirexapp.baas.cards.reveal.ui.WirexRevealFieldView

val reveal = WirexReveal(environment = WirexEnvironment.resolve(name, chainId = chainId),
    credentials = credentials, logging = WirexLogging(WirexLogLevel.INFO, ConsoleLogSink))
val session = reveal.session(cardId = cardId, cardholderName = name,
    fields = listOf(WirexRevealField.CARDHOLDER_NAME, WirexRevealField.PAN,
                    WirexRevealField.EXPIRY, WirexRevealField.CVV, WirexRevealField.PIN))  // omit PIN for virtual
session.onChange { snapshot -> /* state: IDLE|LOADING|READY|ERROR */ }
session.load()
```
- Render one `WirexRevealFieldView` per field (a `View`). The value is drawn in a secure layer; capture protection masks it while the screen is recorded/cast.

### 5.2 Collect  (needs credentials)
```kotlin
import com.wirexapp.baas.cards.collect.*

val collect = WirexCollect(environment = WirexEnvironment.resolve(name, chainId = chainId),
    credentials = credentials, logging = WirexLogging(WirexLogLevel.INFO, ConsoleLogSink))
val form = collect.form(profile = WirexCollectProfile.TOPUP)   // a View; onChange reports validity + brand
val token = form.tokenizeTopUp(billingDetails = WirexBillingDetails(line1 = …, zipCode = …, city = …, country = …))
// token: brand, last4, expiryMonth, expiryYear — opaque; PAN/CVV never reach app code
```
- The **card user must be enrolled at the acquirer**; a non-enrolled user fails tokenization. The **partner backend** then funds the top-up (§6).

### 5.3 3-D Secure  (NO credentials)
```kotlin
import com.wirexapp.baas.cards.threeds.*
import com.wirexapp.baas.cards.threeds.ui.WirexThreeDSView

val threeDS = WirexThreeDS(testMode = isTestMode, logging = WirexLogging(WirexLogLevel.INFO, ConsoleLogSink))
val session = threeDS.session(url)      // url from the top-up execute response
val view = WirexThreeDSView(activity)         // keep mounted
session.onChange { s -> /* s.state == WirexThreeDSState.SETTLED */ }
session.start()
```
- State: `IDLE | LOADING | CHALLENGE | SETTLED`; status: `COMPLETED | PENDING | FAILED | UNKNOWN`.
- `PENDING` = **not settled yet**, not an error. Reconcile the real outcome from the backend status feed.

### 5.4 Wallet  (needs credentials; real device only for the Pay sheet)
```kotlin
import com.wirexapp.wallet.topup.*

val config = WirexWalletConfig(environment = WirexWalletEnvironment.resolve(name),
    logging = WirexWalletLogging(WirexWalletLogLevel.INFO, sink), cache = cache)

// availability (whether to show a Pay button):
val checker = WirexWalletAvailabilityChecker(context, config, providerConfig)
checker.onChange = { availability -> /* applePay / googlePay */ }
checker.start()

// checkout:
val checkout = WirexWalletCheckoutView(
    context,
    config = config,
    quote = WirexWalletQuote(estimationId = id, sourceAmount = WirexWalletMoney("10.00", "EUR")),
    providerConfig = WirexWalletProviderConfig.fromJson(providerBlockJson),   // VERBATIM
    auth = if (tokenMode) WirexWalletCheckoutAuth(checkoutToken) else null,   // token mode when non-null
    provider = providerName)
checkout.onEvent = { event -> /* Ready, NaturalHeight, SheetOpen, Cancelled, AttemptFailed,
    EstimationExpired, Token (legacy), Executing/Executed/ExecuteFailed (token mode), Failed, Diagnostic */ }
// checkout.rearm(quote, providerConfig) after EstimationExpired
```
- **Legacy** (no `auth`): the sheet tokenizes → `Token` event → the backend executes. **Token mode** (`auth`): the frame executes itself. Most emulators have no Google Pay → the probe reports unavailable (the real answer); the checkout still arms.

---

## 6. Backend orchestration (what the SDK does NOT do)

The partner backend owns these (Wirex BaaS API, partner credentials). The SDK consumes their results.

- **Price a top-up** → `estimationId` + fiat `sourceAmount`. Short TTL; re-price to re-arm.
- **Execute a top-up** → settles it; a card payment returns a **3DS orchestrator URL** for `threeDS.session(url)`.
- **Mint a checkout token** (token mode only) → the `checkoutToken`; scoped to the top-up flow, independent of a specific estimation.
- **Provider config** → the wallet provider block, served from your app config, passed to Wallet verbatim.

`estimationId` and `checkoutToken` are **two independent values**.

---

## 7. Invariants & gotchas

- **One identity.** email XOR wallet; both → 400.
- **Bearer expiry.** `accessToken()` must refresh; the SDK does not retry 401.
- **`estimationId` ≠ `checkoutToken`.**
- **Row sizing.** `WirexRevealFieldView` wraps to its content; let it size itself.
- **Wallet availability needs the checker running** with a real provider block; emulators without Google Pay report unavailable.
- **Collect needs an acquirer-enrolled user**, else tokenization fails.
- **3DS `PENDING` is not an error.** Settle by backend status.
- **`providerConfig` is passed verbatim.** Absent `apple_pay`/`google_pay` means "do not offer".
- **Unknown environment** throws `WirexEnvironmentUnavailable` — render the reason; never fall back.

---

## 8. The runnable example

This repo is a runnable native example covering all four surfaces. Credentials are entered
at runtime (pasted JSON on the Reveal/Collect screens, or launch extras for a scripted run). The field
contract matches the React Native / iOS / Flutter examples.

---

## 9. Quick decision rules

- "Which surface needs credentials?" → Reveal, Collect, Wallet yes; 3DS no.
- "Where does the `estimationId` come from?" → the partner backend prices the top-up; the SDK only consumes it. Not from any token.
- "Legacy or token mode for Wallet?" → token mode keeps the sheet payload off the device; legacy returns a token the backend executes. Both need a backend.
- "Why is the Pay button unavailable?" → checker not started, empty provider block, emulator without Google Pay.
- "Why is a revealed field blank/masked?" → capture protection (screen recorded/cast), or the row wasn't allowed to size itself.
- "Why does Collect fail to tokenize?" → the user is not enrolled at the acquirer.
