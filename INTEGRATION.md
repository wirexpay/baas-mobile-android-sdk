# Wirex Mobile SDK for Android — Integration Guide

One Maven artifact, four sealed surfaces: card **Reveal**, card **Collect**, **3-D Secure** and **Wallet** top-up with Google Pay. This guide covers what each surface needs, where the credentials come from, how to style the views, and how to run the example app. Every snippet below is compiled against `com.wirexapp.baas:mobile-sdk` 0.3.15.

<!-- badges: Native Android · Kotlin | com.wirexapp.baas:mobile-sdk 0.3.15 · Maven Central | Reveal · Collect · 3-D Secure · Wallet -->

## 1. What the SDK does

The SDK owns **sealed UI surfaces** and nothing else. A revealed card number, a card the user is typing and a wallet payment token live inside a native view or a Wirex-hosted web frame and **never reach your app code**. The SDK never mints a credential, never stores one, and never moves money on its own.

Your app supplies two things:

- **Authentication.** A `WirexCredentialProvider` the SDK calls on demand: the user's bearer, a step-up action token when card data or PIN is read, and the user's identity.
- **Orchestration.** Your backend prices and executes money-moving calls through the Wirex BaaS API (top-up estimate and execute, the 3-D Secure URL, the wallet checkout token) and ships the wallet provider config. The SDK renders the result and reports events back.

| Surface | Credentials | What it does |
|---|---|---|
| Reveal | yes | Shows the cardholder their own PAN, expiry, CVV and PIN in sealed native views. |
| Collect | yes | Captures an external card in a sealed native form and tokenizes it at the acquirer. |
| 3-D Secure | **no** | Renders the issuer challenge for a card payment and reports the outcome. |
| Wallet | yes | Embeds the Wirex-hosted Google Pay checkout for a backend-priced top-up. |

:::diagram model Google Pay

## 2. Requirements and install

- **Android 6.0 (API 23) or later.** Java 17 bytecode; Kotlin 2.0 or later for a Kotlin host.
- `mavenCentral()` for the SDK and `google()` for its AndroidX dependency. Any Android project already has both.
- The SDK declares no permissions. `INTERNET` is your app's to request.

```kotlin
// build.gradle.kts (app)
dependencies {
    implementation("com.wirexapp.baas:mobile-sdk:0.3.15")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2") // to call the suspend APIs
}
```

The SDK's own dependencies are the Kotlin standard library, `kotlinx-coroutines-android` and `androidx.webkit`. Consumer ProGuard/R8 rules are shipped in the artifact, and the AAR merges the `<queries>` block Google Pay needs on Android 11 and later into your manifest.

Wirex assigns these values per integration. Everything else is your user's data.

| Value | What it is | Where it goes |
|---|---|---|
| `testMode` | `false` = production, `true` = the Wirex test environment. The SDK holds the hosts; no public API takes a URL. | `WirexReveal.create`, `WirexCollect.create`, `WirexThreeDS(testMode = …)`; the wallet takes the same choice by name: `WirexWalletEnvironment.resolve("test")` or `"production"`. |
| `chainId` | The chain your integration operates on, sent as `X-Chain-Id` on every authenticated call. Required for Reveal and Collect. | `chainId =` on `create(...)`. |
| `clientId` | **Optional, wallet only.** A partner id that selects your branding and configuration on the Wirex-hosted checkout pages. Wirex tells you if you have one; most integrations do not. It is not a credential and grants nothing. | `WirexWalletConfig(clientId = …)`; leave `null` unless Wirex gave you one. |

## 3. Credential provider

One object you implement. The SDK calls it when it needs a token, so you never push tokens in advance.

```kotlin
import com.wirexapp.baas.cards.core.WirexActionScope
import com.wirexapp.baas.cards.core.WirexCredentialProvider
import com.wirexapp.baas.cards.core.WirexDeclined

interface HostSession {                       // your own auth layer; shape is up to you
    suspend fun accessToken(): String
    suspend fun stepUpToken(purpose: String): String?   // null when the user backs out
    val email: String?
}

class AppCredentials(private val session: HostSession) : WirexCredentialProvider {
    override suspend fun accessToken(): String = session.accessToken()   // read on every request, never cached

    override suspend fun actionToken(scope: WirexActionScope): String {
        val purpose = when (scope) {
            WirexActionScope.CARD_DATA -> "card-data"
            WirexActionScope.PIN -> "pin"
        }
        return session.stepUpToken(purpose) ?: throw WirexDeclined()    // the user said no: not an error
    }

    override val userEmail: String? get() = session.email               // return ONE of email / wallet
    override val userWallet: String? get() = null
}
```

- **Exactly one identity.** Return either `userEmail` or `userWallet`; a `null` value is simply not sent. Sending both is a backend `400`.
- **The SDK sends the bearer as-is and does not retry a 401.** Check expiry in `accessToken()` and refresh before returning.
- `actionToken(scope)` is called once per scope per operation: `CARD_DATA` covers PAN, expiry and CVV; `PIN` covers the PIN. Throw `WirexDeclined()` when the user backs out; the SDK stops and reports it as declined.
- Values are read **per call**, so a refreshed token takes effect on the next SDK request.

## 4. Reveal {creds}

Shows the cardholder their own card details. Each field is a `WirexRevealFieldView` you add to your layout; the SDK draws the value inside it.

```kotlin
import android.app.Activity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import com.wirexapp.baas.cards.core.WirexCredentialProvider
import com.wirexapp.baas.cards.core.WirexDeclined
import com.wirexapp.baas.cards.reveal.WirexReveal
import com.wirexapp.baas.cards.reveal.WirexRevealField
import com.wirexapp.baas.cards.reveal.WirexRevealState
import com.wirexapp.baas.cards.reveal.WirexRevealStrings
import com.wirexapp.baas.cards.reveal.ui.WirexRevealFieldView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class RevealScreen(private val activity: Activity, credentials: WirexCredentialProvider) {
    private val scope = CoroutineScope(Dispatchers.Main)
    private val reveal: WirexReveal = WirexReveal.create(credentials = credentials, testMode = true, chainId = "<chain id>")
        ?: error("this build has no endpoints for that environment")

    fun show(container: LinearLayout, cardId: String) {
        val fields = listOf(WirexRevealField.PAN, WirexRevealField.EXPIRY, WirexRevealField.CVV, WirexRevealField.PIN)
        val session = reveal.session(cardId = cardId, fields = fields, cardholderName = "JANE DOE")
        val strings = WirexRevealStrings(fieldLabels = mapOf(WirexRevealField.PAN to "Card number"))

        for (field in fields) {
            val row = WirexRevealFieldView(activity)
            row.bind(session, field, strings = strings)
            container.addView(row, MATCH_PARENT, WRAP_CONTENT)
        }

        session.onChange = { snapshot ->                         // any thread: hop to main before touching views
            activity.runOnUiThread {
                when (val state = snapshot.state) {
                    WirexRevealState.Idle, WirexRevealState.Loading, WirexRevealState.Ready -> Unit
                    is WirexRevealState.Failed -> state.error.code
                }
            }
        }

        scope.launch {
            try { session.load() } catch (_: WirexDeclined) { /* the user declined the step-up */ }
        }
    }
}
```

- `fields` are `WirexRevealField` values: `CARDHOLDER_NAME`, `PAN`, `EXPIRY`, `CVV`, `PIN`. Omit `PIN` for a virtual card. `cardholderName` is supplied by you and never fetched.
- `PAN`, `CVV` and `PIN` start **masked**. `row.reveal()` unmasks, `row.mask()` masks again, `isMasked` reads the state. `reveal()` is refused (`onRevealRefused`) when screenshot protection is not active. Rows re-mask when detached (`masksWhenDetached`).
- Session state is `WirexRevealState`: `Idle`, `Loading`, `Ready`, `Failed(error)`. Per-field results are `snapshot.outcome(field)`, `snapshot.loaded` and `snapshot.failed`, so one failed field does not hide the others.
- `load()` is a `suspend` function and idempotent. Request failures are reported in the snapshot; it throws only `WirexDeclined`. Values are wiped 120 seconds after the first load; `session.clear()` wipes them earlier.
- `row.onCopy` fires when the user copies a copyable field. `row.release()` drops the observer when the view goes away.

> **Gotcha · sizing** A row draws its value at its natural height. Use `WRAP_CONTENT` or `row.naturalHeightPx(widthPx)`; a box shorter than the content clips the value.

> **Note · screenshots** The SDK sets `FLAG_SECURE` on the hosting window while a sealed view is attached and refuses to unmask without it. For automated UI tests only, pass `dangerouslyDisableScreenProtection = true` to `create(...)`; `WirexThreeDS(...)` takes the same switch for the 3-D Secure challenge. Never ship it on.

## 5. Collect {creds}

Captures a card the user is entering. The form is one view; your app receives a token, never the PAN or CVV.

:::flow Collect: user types the card | SDK tokenizes at the acquirer | your backend: estimate | execute → 3-D Secure URL

```kotlin
import android.app.Activity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import com.wirexapp.baas.cards.collect.WirexBillingDetails
import com.wirexapp.baas.cards.collect.WirexCollect
import com.wirexapp.baas.cards.collect.WirexCollectProfile
import com.wirexapp.baas.cards.collect.WirexTopUpTokenizeParams
import com.wirexapp.baas.cards.collect.ui.WirexCollectForm
import com.wirexapp.baas.cards.core.WirexCredentialProvider
import com.wirexapp.baas.cards.core.errors.WirexError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CollectScreen(private val activity: Activity, credentials: WirexCredentialProvider) {
    private val scope = CoroutineScope(Dispatchers.Main)
    private val collect: WirexCollect = WirexCollect.create(credentials = credentials, testMode = true, chainId = "<chain id>")
        ?: error("this build has no endpoints for that environment")
    private lateinit var form: WirexCollectForm

    fun mount(holder: FrameLayout) {
        form = collect.form(context = activity, profile = WirexCollectProfile.TOPUP)
        form.onChange = { state -> state.isComplete /* enable your Pay button on this */ }
        holder.addView(form, MATCH_PARENT, WRAP_CONTENT)
    }

    fun tokenize() {
        scope.launch {
            try {
                val token = form.tokenizeTopUp(WirexTopUpTokenizeParams(
                    billingDetails = WirexBillingDetails(line1 = "1 Main St", zipCode = "SW1A 1AA", city = "London", country = "GB"),
                    label = "My card",
                ))
                token.cardId; token.brand; token.bin; token.last4; token.expiryMonth; token.expiryYear
            } catch (error: WirexError) {
                error.code; error.retryable; error.reason
            }
        }
    }
}
```

- Profiles: `TOPUP` (fund a top-up with this card) and `OCT_DESTINATION` (register a payout card via `form.tokenizeOctDestination(WirexOctTokenizeParams(cardLabel, isSaved, isThirdParty))`, which returns `externalCardId`).
- `WirexCollectState` carries `isComplete`, `brand` (`VISA`, `MASTERCARD`, `UNKNOWN`) and per-field `fields` with `isValid` and `error` (`INCOMPLETE`, `INVALID`, `EXPIRED`).
- Billing details (`line1`, `zipCode`, `city`, `country`) are required by the tokenizer; `line2` and `state` are optional. The **card user must be enrolled at the acquirer**, otherwise tokenization fails.
- After tokenizing, **your backend** prices and executes the top-up with the `cardId`; a card payment may return a 3-D Secure URL for the next section.
- `form.errorLine` controls where validation messages render (`HIDDEN`, `RESERVED`, `ANIMATED`); `form.row(field)` returns a single sealed row if you lay fields out yourself; `form.naturalHeightPx(widthPx)` measures the form.

## 6. 3-D Secure {nocreds}

Presents the issuer challenge for a card payment. Your backend obtains the orchestrator URL from the top-up execute response; the SDK renders the flow and reports the outcome. No credentials are needed.

```kotlin
import android.app.Activity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import com.wirexapp.baas.cards.threeds.WirexThreeDS
import com.wirexapp.baas.cards.threeds.WirexThreeDSSession
import com.wirexapp.baas.cards.threeds.WirexThreeDSState
import com.wirexapp.baas.cards.threeds.ui.WirexThreeDSView

class ThreeDSScreen(private val activity: Activity) {
    private val threeDS = WirexThreeDS(testMode = true)
    private val challengeView = WirexThreeDSView(activity)
    private var session: WirexThreeDSSession? = null

    fun mount(root: FrameLayout) = root.addView(challengeView, MATCH_PARENT, MATCH_PARENT)   // full-size overlay

    fun start(orchestratorUrl: String) {
        val next = threeDS.session(orchestratorUrl)             // never throws; a refused origin settles at once
        session = next
        next.onChange = { snapshot ->
            if (snapshot.state == WirexThreeDSState.SETTLED) {
                val outcome = snapshot.outcome                   // status: COMPLETED · PENDING · FAILED · UNKNOWN
                outcome?.status; outcome?.error?.code
            }
        }
        challengeView.bind(next)
        next.start(activity)
    }

    fun release() {
        challengeView.unbind()
        session?.destroy()
    }
}
```

| State (`WirexThreeDSState`) | Outcome status (`WirexThreeDSStatus`) | Outcome error code |
|---|---|---|
| `IDLE` · `LOADING` · `CHALLENGE` · `SETTLED` | `COMPLETED` · `PENDING` · `FAILED` · `UNKNOWN` | `ORIGIN_REFUSED` · `DECLINED` · `UNAVAILABLE` |

- The view stays invisible until the challenge is shown, applies `FLAG_SECURE` while it is visible (unless `WirexThreeDS` was created with `dangerouslyDisableScreenProtection = true`), and includes a close control that calls `session.cancel()` (settles with `UNKNOWN`).
- `onChange` is invoked immediately with the current snapshot when set.

> **Note** `PENDING` means the payment is not settled *yet*; it is not an error. Reconcile the real result from your backend's status feed, not from the 3-D Secure status alone.

## 7. Wallet · Google Pay {creds|device}

Embeds the Wirex-hosted checkout for a top-up your backend has already priced. The frame renders the quote and the Google Pay button; the Pay sheet is native.

Three inputs, all from your backend and your app config:

| Input | What it is |
|---|---|
| `quote` | `estimationId` + `sourceAmount` from your backend's top-up estimate. Short TTL; a new estimate re-arms the checkout. |
| `providerConfig` | The wallet provider block from your app config, passed **verbatim** (`WirexWalletProviderConfig.fromJson(text)`). |
| `auth` | Token mode: a `checkoutToken` your backend minted for this top-up flow. Omit it for legacy mode. |

:::diagram wallet

```kotlin
import android.app.Activity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import com.wirexapp.wallet.topup.WirexWalletAvailabilityChecker
import com.wirexapp.wallet.topup.WirexWalletCheckoutView
import com.wirexapp.wallet.topup.WirexWalletConfig
import com.wirexapp.wallet.topup.WirexWalletEnvironment
import com.wirexapp.wallet.topup.model.WirexWalletCheckoutAuth
import com.wirexapp.wallet.topup.model.WirexWalletCheckoutEvent
import com.wirexapp.wallet.topup.model.WirexWalletMoney
import com.wirexapp.wallet.topup.model.WirexWalletProviderConfig
import com.wirexapp.wallet.topup.model.WirexWalletQuote

class WalletScreen(private val activity: Activity) {
    private val config = WirexWalletConfig(environment = WirexWalletEnvironment.resolve("test"))   // clientId optional
    private var checker: WirexWalletAvailabilityChecker? = null
    private var checkout: WirexWalletCheckoutView? = null

    // 1. Availability: decide whether to show a Pay button at all.
    fun checkAvailability(providerConfig: WirexWalletProviderConfig) {
        val fresh = WirexWalletAvailabilityChecker(activity, config, providerConfig)
        fresh.onChange = { availability -> availability.googlePay }
        fresh.start()
        checker = fresh
    }

    // 2. Checkout: arm it with a backend-priced quote and the provider block.
    fun mount(container: FrameLayout, providerConfigJson: String, checkoutToken: String) {
        val view = WirexWalletCheckoutView(
            context = activity,
            config = config,
            quote = WirexWalletQuote(estimationId = "<estimation id>", sourceAmount = WirexWalletMoney("10.00", "EUR")),
            providerConfig = WirexWalletProviderConfig.fromJson(providerConfigJson),
            auth = WirexWalletCheckoutAuth(checkoutToken = checkoutToken),
        )
        view.onEvent = ::onEvent
        container.addView(view, MATCH_PARENT, MATCH_PARENT)
        checkout = view
    }

    private fun onEvent(event: WirexWalletCheckoutEvent) {
        when (event) {
            WirexWalletCheckoutEvent.Ready, WirexWalletCheckoutEvent.SheetOpen,
            WirexWalletCheckoutEvent.Cancelled, WirexWalletCheckoutEvent.Executing -> Unit
            is WirexWalletCheckoutEvent.NaturalHeight -> event.height          // CSS px: size the container
            is WirexWalletCheckoutEvent.AttemptFailed -> event.reason
            WirexWalletCheckoutEvent.EstimationExpired -> Unit                  // re-price on your backend, then rearm(...)
            is WirexWalletCheckoutEvent.Token -> event.token.walletPaymentToken // legacy mode: your backend executes
            is WirexWalletCheckoutEvent.Executed -> event.result.status
            is WirexWalletCheckoutEvent.ExecuteFailed -> event.error.reason
            is WirexWalletCheckoutEvent.Failed -> event.error.kind
            is WirexWalletCheckoutEvent.Diagnostic, is WirexWalletCheckoutEvent.Debug -> Unit
        }
    }

    fun newQuote(quote: WirexWalletQuote, providerConfig: WirexWalletProviderConfig, checkoutToken: String) {
        checkout?.rearm(quote, providerConfig, auth = WirexWalletCheckoutAuth(checkoutToken = checkoutToken))
    }

    fun release() {
        checker?.stop()
        checkout?.destroy()
    }
}
```

- **Legacy vs token mode.** Without `auth`, the sheet tokenizes and the checkout emits `Token`; your backend executes the top-up with it. With a backend-minted `checkoutToken`, the frame executes itself and emits `Executing` → `Executed` or `ExecuteFailed`; nothing sensitive reaches app code. The mode is fixed when the view is created.
- `estimationId` and `checkoutToken` are two independent values from two backend calls; the id is not derived from the token.
- Events: `Ready`, `NaturalHeight`, `SheetOpen`, `Cancelled`, `AttemptFailed`, `EstimationExpired`, `Token`, `Executing`, `Executed`, `ExecuteFailed`, `Failed` (terminal), `Diagnostic`, `Debug` (only with `debug = true`).
- `WirexWalletMoney` takes the amount as a decimal string or a number. `WirexWalletQuote` also accepts optional `destinationAmount`, `fee`, `rate` and `expiresAtMs`.
- `providerConfig` is passed verbatim. Do not re-case keys or add defaults; an absent `google_pay` key means "do not offer Google Pay". `WirexWalletProviderConfigResolver.resolve(topup, provider)` picks the block out of your app config.
- `WirexWalletConfig(cache = …)` takes an optional key-value store (`WirexWalletAvailabilityCache`) so the availability answer survives relaunch. The SDK writes one key with two booleans.
- Standalone launch in a Chrome Custom Tab: `WirexWalletLaunch.buildUrl(config, WirexWalletLaunchParams(checkoutToken, quote, provider, returnUrl))` builds the URL your app opens; `WirexWalletReturnLink.matches(url, returnUrl)` and `parse(url)` read the result on return. The SDK opens no browser itself.

> **Gotcha · availability** Start a `WirexWalletAvailabilityChecker` with the real provider block before you show a Pay button; `onChange` reports the native Google Pay answer, and below the `min_webview_version` / `min_play_services_version` floors it answers unavailable without loading anything. Test on a device with Google Pay set up.

## 8. Appearance, strings and sizing

Every surface takes an appearance object and a strings object. The SDK ships no copy, so your app supplies every word the views draw.

```kotlin
import android.graphics.Color
import com.wirexapp.baas.cards.collect.WirexCollectField
import com.wirexapp.baas.cards.collect.WirexCollectFieldError
import com.wirexapp.baas.cards.collect.WirexCollectStrings
import com.wirexapp.baas.cards.collect.ui.WirexCollectForm
import com.wirexapp.baas.cards.core.appearance.WirexAppearance
import com.wirexapp.baas.cards.core.appearance.WirexThemeMode
import com.wirexapp.baas.cards.reveal.WirexRevealField
import com.wirexapp.baas.cards.reveal.WirexRevealStrings
import com.wirexapp.baas.cards.reveal.ui.WirexRevealFieldView
import com.wirexapp.wallet.topup.generated.SpecWalletGooglePayButtonColor
import com.wirexapp.wallet.topup.generated.SpecWalletGooglePayButtonType
import com.wirexapp.wallet.topup.model.WirexWalletAppearance
import com.wirexapp.wallet.topup.model.WirexWalletBranding
import com.wirexapp.wallet.topup.model.WirexWalletButtons
import com.wirexapp.wallet.topup.model.WirexWalletLogo

object Styling {
    val appearance = WirexAppearance(
        mode = WirexThemeMode.DARK,
        colorTextOverride = Color.WHITE,
        colorFocusOverride = Color.parseColor("#5753EA"),
        fontSizeBaseDp = 17f,
        borderRadiusDp = 12f,
    )
    val revealStrings = WirexRevealStrings(
        fieldLabels = mapOf(WirexRevealField.PAN to "Card number", WirexRevealField.CVV to "CVV"),
        maskedSegmentGlyph = "*", copy = "Copy", copied = "Copied",
    )
    val collectStrings = WirexCollectStrings(
        fieldLabels = mapOf(WirexCollectField.NUMBER to "Card number"),
        error = { field, reason ->
            when (reason) {
                WirexCollectFieldError.INCOMPLETE -> "${field.defaultLabel} is incomplete"
                WirexCollectFieldError.INVALID -> "Check the ${field.defaultLabel.lowercase()}"
                WirexCollectFieldError.EXPIRED -> "This card has expired"
            }
        },
    )
    val walletAppearance = WirexWalletAppearance(theme = WirexWalletAppearance.Theme.DARK, variables = mapOf("--wx-accent" to "#5753EA"))
    val walletButtons = WirexWalletButtons(googlePay = WirexWalletButtons.GooglePay(color = SpecWalletGooglePayButtonColor.BLACK, type = SpecWalletGooglePayButtonType.PAY))
    val walletBranding = WirexWalletBranding(logo = WirexWalletLogo.Hidden, processingDisclosurePartnerName = "Acme")

    fun apply(row: WirexRevealFieldView, form: WirexCollectForm) {
        row.setAppearance(appearance); row.setStrings(revealStrings)
        form.apply(appearance); form.apply(collectStrings)
    }
}
```

- `WirexAppearance` is a data class with every parameter defaulted: `mode` (`LIGHT` / `DARK`), ARGB colour overrides (`colorTextOverride`, `colorLabelOverride`, `colorPlaceholderOverride`, `colorErrorOverride`, `colorFocusOverride`, `colorBackgroundOverride`, `colorBorderOverride`, `surfaceColorOverride`), typography (`typeface`, `fontSizeBaseDp`, `fontSizeLabelDp`, `lineHeightBaseDp`, `lineHeightLabelDp`, `fontWeightBaseBold`, `fontWeightLabelBold`), geometry in dp (`borderRadiusDp`, `borderWidthDp`, `spacingUnitDp`, `paddingHorizontalDp`, `paddingVerticalDp`, `spacingLabelDp`, `spacingErrorDp`, `iconSizeDp`, `controlMinHeightDp`). The same object styles Reveal, Collect and 3-D Secure. `WirexAppearanceTokens.appearance(tokens, mode)` builds one from a token map with the web SDK's names.
- Strings: `WirexRevealStrings` (field labels, `maskedPlaceholder` for plain fields, `maskedSegmentGlyph` drawn in every PIN cell while masked, default `"*"`, copy texts), `WirexCollectStrings` (labels, placeholders, an `error` lambda per field and a `brandName` lambda; `fromJson` accepts the web SDK's JSON shape) and `WirexThreeDSStrings(title, close, loading)`. Icons are optional `Drawable`s: `WirexRevealIcons(copy, copied)`, `WirexCollectIcons(visa, mastercard)`, `WirexThreeDSIcons(close)`.
- Wallet: `WirexWalletAppearance(theme, variables)` passes CSS variables to the frame; `WirexWalletBranding(logo, processingDisclosurePartnerName, privacyUrl)`; `WirexWalletButtons` sets the Google Pay button `color` (`BLACK`, `WHITE`) and `type` (`PLAIN`, `PAY`, `BUY`); `strings` is a `Map<SpecWalletStringKey, String>` overriding the 11 labels of the checkout.
- **Sizing.** `WirexRevealFieldView.naturalHeightPx(widthPx)` and `WirexCollectForm.naturalHeightPx(widthPx)` measure the content; the wallet emits a `NaturalHeight` event. Use `WRAP_CONTENT` or apply those heights instead of hardcoding them.
- **Screenshot protection.** `FLAG_SECURE` is acquired on the hosting window automatically while a sealed view is attached. Pass the window explicitly with `secureWindow(window)` when the view lives in a `Dialog` or `PopupWindow` the SDK cannot resolve.

## 9. Logging, timeouts and errors

```kotlin
import com.wirexapp.baas.cards.core.WirexCredentialProvider
import com.wirexapp.baas.cards.core.WirexEnvironment
import com.wirexapp.baas.cards.core.WirexEnvironmentUnavailable
import com.wirexapp.baas.cards.core.errors.WirexError
import com.wirexapp.baas.cards.core.logging.WirexLogLevel
import com.wirexapp.baas.cards.core.logging.logcatLogging
import com.wirexapp.baas.cards.core.net.WirexTimeouts
import com.wirexapp.baas.cards.reveal.WirexReveal
import com.wirexapp.wallet.topup.WirexWalletLogLevel
import com.wirexapp.wallet.topup.WirexWalletLogging

object Diagnostics {
    fun configured(credentials: WirexCredentialProvider): WirexReveal? = WirexReveal.create(
        credentials = credentials, chainId = "<chain id>",
        logging = logcatLogging(WirexLogLevel.INFO),                    // default WirexLogging.DISABLED; WirexLogging.DEBUG for everything
        timeouts = WirexTimeouts(connectMillis = 10_000, readMillis = 20_000),
    )
    val walletLogging = com.wirexapp.wallet.topup.logcatLogging(WirexWalletLogLevel.INFO)   // separate types for the wallet
    val walletOff = WirexWalletLogging.DISABLED

    fun describe(error: WirexError): String = when (error.code) {
        WirexError.Code.UNAUTHORIZED -> "refresh the access token"
        WirexError.Code.ACTION_REQUIRED -> "obtain a fresh action token and retry"
        WirexError.Code.UPDATE_REQUIRED -> "ship a newer SDK build"
        WirexError.Code.INVALID_INPUT -> when (error.reason) {
            WirexError.Reason.CARD_ALREADY_LINKED -> "enter a different card"
            WirexError.Reason.CARD_LIMIT_REACHED -> "remove a card first"
            else -> "fix the highlighted field"
        }
        WirexError.Code.TEMPORARY -> if (error.retryable) "retry" else "give up"
        WirexError.Code.FAILED -> "terminal"
    }

    fun environment(name: String, chainId: String): WirexEnvironment? = try {
        WirexEnvironment.resolve(name, chainId)
    } catch (e: WirexEnvironmentUnavailable) {
        null   // UnknownName · NotBuilt · MalformedEndpoints · MissingChainId: show the reason, never fall back
    }
}
```

- Logging is off by default. The card surfaces take `WirexLogging(level, sink)` (presets `WirexLogging.DISABLED`, `WirexLogging.DEBUG`, `logcatLogging(level)`, tag `WirexappCardSdk`); the wallet takes a separate `WirexWalletLogging` with its own sink type and `logcatLogging` function (tag `WirexWallet`). Every logged value passes a redactor; tokens and card data are never handed to a sink. Sinks are called on arbitrary threads and must not block.
- `WirexTimeouts(connectMillis, readMillis)` default to 15 seconds each. A timed-out request fails `TEMPORARY` and is retryable.
- `WirexError.code`: `UNAUTHORIZED`, `ACTION_REQUIRED`, `UPDATE_REQUIRED`, `INVALID_INPUT`, `TEMPORARY`, `FAILED`. `retryable` says whether a retry can help. `reason` adds detail where the backend gives one: `ACTION_TOKEN_REJECTED`, `CARD_ALREADY_LINKED`, `CARD_LIMIT_REACHED`, `INVALID_CARD_ID`.
- `WirexEnvironmentUnavailable` is thrown by `WirexEnvironment.resolve(name, chainId)` and `WirexWalletEnvironment.resolve(name)` for an unknown name or a blank `chainId`; the `create(...)` factories return `null` instead.
- `WirexDeclined` is not an error state: the user said no, and nothing was sent.

## 10. Your backend

The SDK makes none of these calls. Your backend performs them with the partner credentials Wirex issued, and the app passes the results in:

- **Price a top-up** → `estimationId` and `sourceAmount` for the wallet quote. Short TTL; re-price to re-arm an expired checkout.
- **Execute a top-up** → settles it; a card payment returns the 3-D Secure orchestrator URL.
- **Mint a checkout token** (wallet token mode) → `checkoutToken`, scoped to the top-up flow.
- **Serve the wallet provider config** → the provider block the app passes to the wallet verbatim.

Authenticated calls carry `Authorization: Bearer`, `X-Chain-Id` and one identity header (`X-User-Email` or `X-User-Wallet`, never both). For exact paths and payloads see the Wirex BaaS API reference provided with your integration.

## 11. The example app

[`example/`](example/) is a Kotlin app that exercises all four surfaces against your own data; nothing is compiled in. Open it in Android Studio and run the `app` module; it resolves the SDK from Maven Central. The **Config** tab is a per-field editor for the environment, the integration parameters (chain id, identity, card id, cardholder name) and the secrets (bearer, step-up action token); values live in memory only, and every screen reads them live. The **Wallet** screen takes an estimation id and the provider block, and its **Fetch estimate** button prices a top-up through the BaaS API with your bearer, so you can get a real estimation id without standing up a backend. The **Appearance** tab restyles every sealed surface at once. The field list is in [`PARTNER-CONFIG.md`](PARTNER-CONFIG.md). The Pay sheet needs a device with Google Pay set up.

## 12. Patterns from a production integration

The SDK ships inside a production cardholder app built on the same BaaS API. The patterns below are not required by the API; they are what that integration settled on, with the app-specific parts removed.

### Credentials in practice

```kotlin
import com.wirexapp.baas.cards.core.WirexActionScope
import com.wirexapp.baas.cards.core.WirexCredentialProvider
import com.wirexapp.baas.cards.core.errors.WirexError

interface AuthSession {
    suspend fun accessToken(): Pair<String, Long>?          // token + expiry epoch ms
    suspend fun refreshAccessToken(): String?
    suspend fun signAction(scope: WirexActionScope): String
    val email: String?
}

class ProductionCredentials(private val auth: AuthSession) : WirexCredentialProvider {
    @Volatile private var offered: Pair<WirexActionScope, String>? = null

    /** Sign BEFORE the sheet opens and hand the token over one-shot: the user confirms once. */
    fun offerStepUpToken(scope: WirexActionScope, token: String) { offered = scope to token }
    fun revokeOfferedStepUpToken() { offered = null }

    override suspend fun accessToken(): String {
        // Expiry, not just presence: the SDK sends what it is given and never retries a 401.
        val current = auth.accessToken()
        if (current != null && current.second - System.currentTimeMillis() > 30_000) return current.first
        return auth.refreshAccessToken() ?: throw WirexError(WirexError.Code.UNAUTHORIZED, retryable = false)
    }

    override suspend fun actionToken(scope: WirexActionScope): String {
        offered?.takeIf { it.first == scope }?.let { offered = null; return it.second }
        return auth.signAction(scope)                              // fallback: sign now
    }

    override val userEmail: String? get() = auth.email
    override val userWallet: String? get() = null
}
```

- **Check the bearer's expiry, not just its presence.** Your own API client probably refreshes on a 401 and retries; the SDK does neither. Use the same expiry buffer your client uses so the two agree on "about to expire", refresh before returning, and only fall back to a still-valid token when the refresh fails.
- **Sign once.** The user confirms a card reveal with a step-up *before* the sheet opens. Mint the action token there, offer it to the provider one-shot, and revoke it if the flow is abandoned. Otherwise the SDK asks for a second confirmation for the same action.
- **Configure once per process**, cached behind a promise or a flag, and clear the cache on failure so a later attempt can retry. Derive `testMode` from your *runtime* environment if your app can switch backends. Log at `debug` in development builds and `warn` in release.
- **Tokens and identity are read per call**, so a sign-out and sign-in is picked up automatically; keep the provider reading live state rather than a captured copy.

### Reveal in practice

- **One session per open sheet, destroyed with it.** The session holds the retrieved values natively; leaving one alive after the sheet closes keeps a PAN in memory for nothing.
- **Unmask immediately when the user already confirmed.** After the step-up signature, call `reveal()` on the rows right after `load()` instead of asking for another tap.
- **Log which fields failed together with the snapshot's error code.** The row shows only *that* a field failed, never why.

### Collect in practice

```kotlin
import android.content.Context
import com.wirexapp.baas.cards.collect.WirexBillingDetails
import com.wirexapp.baas.cards.collect.WirexCollect
import com.wirexapp.baas.cards.collect.WirexCollectProfile
import com.wirexapp.baas.cards.collect.WirexTopUpTokenizeParams
import com.wirexapp.baas.cards.collect.ui.WirexCollectForm

/** The sealed form plus your own billing-address and card-label fields; submit only when both are ready. */
class NewCardScreen(context: Context, collect: WirexCollect) {
    val form: WirexCollectForm = collect.form(context = context, profile = WirexCollectProfile.TOPUP)
    var billing: WirexBillingDetails? = null                // filled by your own address screen
    private var isComplete = false

    init { form.onChange = { state -> isComplete = state.isComplete } }

    val canSubmit: Boolean get() = isComplete && billing != null

    suspend fun submit(): String =
        form.tokenizeTopUp(WirexTopUpTokenizeParams(billingDetails = requireNotNull(billing))).cardId

    fun release() = form.clear()
}
```

- The sealed rows replace only the card inputs. Keep your own billing-address entry and card label, enable submit on `isComplete` **and** a complete address, and release the form when the screen goes away.

### 3-D Secure in practice

```kotlin
import com.wirexapp.baas.cards.threeds.WirexThreeDSSession
import com.wirexapp.baas.cards.threeds.WirexThreeDSState
import com.wirexapp.baas.cards.threeds.WirexThreeDSStatus

/** Settle exactly once, and tell the three endings apart. */
class StepUpWatcher(
    private val onError: (String) -> Unit,
    private val onDismissed: () -> Unit,
    private val onComplete: (WirexThreeDSStatus) -> Unit,
    private val onChallengeVisible: (Boolean) -> Unit,
) {
    private var settled = false

    fun watch(session: WirexThreeDSSession) {
        session.onChange = { snapshot ->
            onChallengeVisible(snapshot.isChallengeVisible)            // hide your own header while true
            val outcome = snapshot.outcome
            if (snapshot.state == WirexThreeDSState.SETTLED && outcome != null && !settled) {
                settled = true
                val error = outcome.error
                when {
                    error != null -> onError(error.message ?: error.code.name)      // show it
                    outcome.status == WirexThreeDSStatus.UNKNOWN -> onDismissed()   // the user closed it: reconcile by backend status
                    else -> onComplete(outcome.status)                               // COMPLETED · PENDING · FAILED
                }
            }
        }
    }
}
```

- Start the session only after the view is mounted; a challenge can appear within a frame of the page asking for one.
- Three endings, not interchangeable: an outcome with an `error` is shown; `unknown` *without* an error means the user closed the challenge, which is a decision, not a failure, and the payment may still complete; anything else is a real status. Reconcile the first two against your backend.

### Wallet top-up in practice

```kotlin
import com.wirexapp.wallet.topup.WirexWalletCheckoutView
import com.wirexapp.wallet.topup.WirexWalletConfig
import com.wirexapp.wallet.topup.WirexWalletLaunch
import com.wirexapp.wallet.topup.generated.SpecWalletLaunchOutcome
import com.wirexapp.wallet.topup.model.WirexWalletCheckoutAuth
import com.wirexapp.wallet.topup.model.WirexWalletCheckoutEvent
import com.wirexapp.wallet.topup.model.WirexWalletLaunchParams
import com.wirexapp.wallet.topup.model.WirexWalletProviderConfig
import com.wirexapp.wallet.topup.model.WirexWalletQuote
import com.wirexapp.wallet.topup.model.WirexWalletReturnLink

sealed class TabOutcome {
    data class Executed(val outcome: SpecWalletLaunchOutcome) : TabOutcome()   // COMPLETED or PENDING
    data object Failed : TabOutcome()
    data object Unknown : TabOutcome()                                          // reconcile by backend status
}

object WalletArming {
    /** One token per armed quote. A failed mint is not fatal in-app: run legacy mode instead. */
    suspend fun auth(mint: suspend () -> String): WirexWalletCheckoutAuth? =
        runCatching { WirexWalletCheckoutAuth(checkoutToken = mint()) }.getOrNull()

    /** An expired estimate is re-priced on your backend, then the same view is re-armed. */
    fun onEvent(view: WirexWalletCheckoutView, event: WirexWalletCheckoutEvent,
                reprice: () -> Triple<WirexWalletQuote, WirexWalletProviderConfig, WirexWalletCheckoutAuth?>) {
        if (event == WirexWalletCheckoutEvent.EstimationExpired) {
            val (quote, providerConfig, auth) = reprice()
            view.rearm(quote, providerConfig, auth = auth)
        }
    }

    /** Custom Tab (token mode only): your app opens the URL in a Chrome Custom Tab and reads the return link. */
    fun launchUrl(config: WirexWalletConfig, auth: WirexWalletCheckoutAuth, quote: WirexWalletQuote, provider: String, returnUrl: String): String =
        WirexWalletLaunch.buildUrl(config, WirexWalletLaunchParams(checkoutToken = auth.checkoutToken, quote = quote, provider = provider, returnUrl = returnUrl))

    fun readReturn(url: String?, returnUrl: String, quote: WirexWalletQuote): TabOutcome {
        if (url == null || !WirexWalletReturnLink.matches(url, returnUrl)) return TabOutcome.Unknown   // tab closed without the link
        val back = WirexWalletReturnLink.parse(url)
        if (back.estimationId != null && back.estimationId != quote.estimationId) return TabOutcome.Unknown
        val outcome = back.outcome
        return when (outcome) {
            SpecWalletLaunchOutcome.COMPLETED, SpecWalletLaunchOutcome.PENDING -> TabOutcome.Executed(outcome)
            SpecWalletLaunchOutcome.FAILED -> TabOutcome.Failed
            else -> TabOutcome.Unknown                                                  // ALREADY_EXECUTED or no outcome
        }
    }
}
```

- **One `checkoutToken` per armed quote.** Mint it on your backend for the quote you are about to arm; a re-arm mints again. Never hand the user's session bearer to the frame.
- **A failed mint is not fatal in-app.** Run legacy mode instead: the frame tokenizes and your backend executes. Keep `debug` off outside development builds.
- **Custom Tab is the partner path for Google Pay on Android** when your own WebView cannot complete it. Use an **https App Link** as `returnUrl` (a custom scheme can be claimed by any installed app), open the URL in a Chrome Custom Tab, and treat every ending that is not `COMPLETED`, `PENDING` or `FAILED` as unknown: Android cannot close the tab for you, and a closed tab says nothing about the payment. A Custom Tab needs token mode; there is nobody to hand a wallet token to.

## 13. Troubleshooting

- **A revealed field is blank or will not unmask.** Screenshot protection is not active on this window (`onRevealRefused` fires); pass the window with `secureWindow(window)`.
- **`create(...)` returns `null`.** This build has no endpoints for that side, or `chainId` is blank. Call `WirexEnvironment.resolve(name, chainId)` to get the reason.
- **Reveal or Collect fails with `UNAUTHORIZED`.** The bearer is stale; `accessToken()` must refresh. The SDK never retries a 401.
- **Collect cannot tokenize.** The user is not enrolled at the acquirer, or billing details are missing.
- **The Pay button is unavailable.** The checker was not started, the provider block is empty, or the device is below the Google Pay floors.
- **3-D Secure ends in `PENDING`.** Not an error. Settle by your backend's status.
- **Both `userEmail` and `userWallet` are set.** The backend answers `400`. Return exactly one.
