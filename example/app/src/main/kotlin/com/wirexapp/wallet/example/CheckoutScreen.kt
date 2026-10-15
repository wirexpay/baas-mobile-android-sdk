package com.wirexapp.wallet.example

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import com.wirexapp.wallet.topup.WirexWalletCheckoutView
import com.wirexapp.wallet.topup.WirexWalletConfig
import com.wirexapp.wallet.topup.WirexWalletLogLevel
import com.wirexapp.wallet.topup.WirexWalletLogging
import com.wirexapp.wallet.topup.generated.SpecWalletGooglePayButtonColor
import com.wirexapp.wallet.topup.model.WirexWalletBranding
import com.wirexapp.wallet.topup.model.WirexWalletButtons
import com.wirexapp.wallet.topup.model.WirexWalletCheckoutEvent
import com.wirexapp.wallet.topup.model.WirexWalletMoney
import com.wirexapp.wallet.topup.model.WirexWalletProviderConfig
import com.wirexapp.wallet.topup.model.WirexWalletQuote

/**
 * Mounts [WirexWalletCheckoutView] from a pasted provider config and an editable quote, and prints
 * every event it reports.
 *
 * Two things this screen is careful about, because they are the contract:
 * - the provider config goes in through [WirexWalletProviderConfig.fromJson] — the path that keeps
 *   the backend's key order — exactly as a host that received it as response text would;
 * - the Token event prints method, network, last4, display name and the payload's LENGTH. The
 *   payload itself never appears anywhere; a real host hands it to its execute call, verbatim.
 */
class CheckoutScreen(private val context: Context, private val appConfig: AppConfig) {

    private val log = EventLog(context)

    private lateinit var providerField: EditText
    private lateinit var estimationId: EditText
    private lateinit var sourceAmount: EditText
    private lateinit var sourceCurrency: EditText
    private lateinit var destinationAmount: EditText
    private lateinit var destinationCurrency: EditText
    private lateinit var feeAmount: EditText
    private lateinit var expiresIn: EditText
    private lateinit var debugToggle: CheckBox
    private lateinit var brandingToggle: CheckBox
    private lateinit var buttonsToggle: CheckBox
    private lateinit var checkoutContainer: FrameLayout

    private var view: WirexWalletCheckoutView? = null

    fun build(): View {
        val column = Ui.column(context)

        column.addView(Ui.heading(context, "Provider config (JSON)"), Ui.matchWidth())
        column.addView(
            Ui.caption(
                context,
                "Paste the topup.providers.<name> block your estimate names — the SDK passes it to the " +
                    "page VERBATIM, key order included. The sample is SHAPE only: field names and types, " +
                    "not real merchant values.",
            ),
            Ui.matchWidth(),
        )
        providerField = Ui.jsonField(context, "")
        column.addView(providerField, Ui.matchWidth())
        column.addView(
            Ui.buttonRow(
                context,
                "Sample (shape only)" to { providerField.setText(Samples.PROVIDER_CONFIG_SHAPE) },
            ),
            Ui.matchWidth(),
        )

        column.addView(Ui.heading(context, "Quote"), Ui.matchWidth())
        column.addView(
            Ui.caption(
                context,
                "Display only — the SDK neither prices nor checks it; the backend re-validates the " +
                    "estimation on execute. Blank destination or fee sends it absent.",
            ),
            Ui.matchWidth(),
        )
        estimationId = Ui.field(context, "example-1")
        sourceAmount = Ui.field(context, "105.50")
        sourceCurrency = Ui.field(context, "EUR")
        destinationAmount = Ui.field(context, "100.00")
        destinationCurrency = Ui.field(context, "EUR")
        feeAmount = Ui.field(context, "5.50")
        expiresIn = Ui.field(context, "900")
        column.addView(Ui.labelled(context, "estimationId", estimationId), Ui.matchWidth())
        column.addView(Ui.labelled(context, "source amount", sourceAmount), Ui.matchWidth())
        column.addView(Ui.labelled(context, "source currency", sourceCurrency), Ui.matchWidth())
        column.addView(Ui.labelled(context, "destination amount", destinationAmount), Ui.matchWidth())
        column.addView(Ui.labelled(context, "destination currency", destinationCurrency), Ui.matchWidth())
        column.addView(Ui.labelled(context, "fee (source currency)", feeAmount), Ui.matchWidth())
        column.addView(Ui.labelled(context, "expires in (seconds)", expiresIn), Ui.matchWidth())

        debugToggle = CheckBox(context).apply { text = "debug (arms wallet-debug + inspectable web view — never in production)"; textSize = 11f }
        brandingToggle = CheckBox(context).apply { text = "branding (partner processing disclosure)"; textSize = 11f }
        buttonsToggle = CheckBox(context).apply { text = "buttons (Google Pay preset: black)"; textSize = 11f }
        column.addView(debugToggle, Ui.matchWidth())
        column.addView(brandingToggle, Ui.matchWidth())
        column.addView(buttonsToggle, Ui.matchWidth())

        column.addView(
            Ui.buttonRow(
                context,
                "Mount checkout" to ::mount,
                "New quote (rearm)" to ::rearm,
                "Unmount" to ::unmount,
            ),
            Ui.matchWidth(),
        )

        column.addView(Ui.heading(context, "Checkout"), Ui.matchWidth())
        column.addView(
            Ui.caption(context, "The view is transparent and the host owns the box: its height follows NaturalHeight events."),
            Ui.matchWidth(),
        )
        checkoutContainer = FrameLayout(context)
        // DARK on purpose: the frame's text is styled for a dark host surface (the app renders it
        // over #141414, and the frame's own popup mode boots on the same colour) — on a light box
        // the review card reads as washed out.
        checkoutContainer.setBackgroundColor(0xFF141414.toInt())
        column.addView(
            checkoutContainer,
            android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(context, 400)),
        )

        column.addView(Ui.heading(context, "Events"), Ui.matchWidth())
        column.addView(Ui.buttonRow(context, "Clear log" to { log.clear() }), Ui.matchWidth())
        column.addView(log.view, Ui.matchWidth())

        return column
    }

    fun release() = unmountQuietly()

    // ── actions ─────────────────────────────────────────────────────────────────────

    private fun mount() {
        val environment = appConfig.environment
        if (environment == null) {
            log.append("cannot mount: ${appConfig.error}")
            return
        }
        val providerConfig = parseProviderConfig() ?: return
        val quote = buildQuote() ?: return

        unmountQuietly()

        val config = WirexWalletConfig(
            environment = environment,
            clientId = null,
            logging = WirexWalletLogging(WirexWalletLogLevel.INFO, ScreenLogSink(log)),
            cache = InMemoryAvailabilityCache,
        )
        val checkout = WirexWalletCheckoutView(
            context = context,
            config = config,
            quote = quote,
            providerConfig = providerConfig,
            buttons = if (buttonsToggle.isChecked) {
                WirexWalletButtons(googlePay = WirexWalletButtons.GooglePay(color = SpecWalletGooglePayButtonColor.BLACK))
            } else {
                null
            },
            branding = if (brandingToggle.isChecked) {
                WirexWalletBranding(processingDisclosurePartnerName = "Example Partner")
            } else {
                null
            },
            debug = debugToggle.isChecked,
        )
        checkout.onEvent = ::onEvent
        checkoutContainer.addView(
            checkout,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        view = checkout
        log.append("mounted — loading ${environment.name} checkout (debug=${debugToggle.isChecked})")
    }

    /** `estimationExpired` → the host re-estimates → `rearm`. Here the fields ARE the re-estimate. */
    private fun rearm() {
        val mounted = view
        if (mounted == null) {
            log.append("nothing mounted — press Mount checkout first")
            return
        }
        val providerConfig = parseProviderConfig() ?: return
        val quote = buildQuote() ?: return
        mounted.rearm(quote, providerConfig)
        log.append("rearm sent (estimationId=${quote.estimationId}) — the page re-renders and restarts the countdown")
    }

    private fun unmount() {
        if (view == null) {
            log.append("nothing mounted")
            return
        }
        unmountQuietly()
        log.append("destroyed — no events after this")
    }

    private fun unmountQuietly() {
        view?.destroy()
        view = null
        checkoutContainer.removeAllViews()
    }

    // ── inputs ──────────────────────────────────────────────────────────────────────

    private fun parseProviderConfig(): WirexWalletProviderConfig? {
        val text = providerField.text.toString().trim()
        if (text.isEmpty()) {
            log.append("provider config required — paste one or press Sample (shape only)")
            return null
        }
        return try {
            // fromJson keeps the source TEXT, so the frame sees the keys in the backend's order.
            WirexWalletProviderConfig.fromJson(text)
        } catch (e: IllegalArgumentException) {
            log.append("provider config rejected: ${e.message}")
            null
        }
    }

    private fun buildQuote(): WirexWalletQuote? {
        val id = estimationId.text.toString().trim()
        if (id.isEmpty()) {
            log.append("estimationId required")
            return null
        }
        val source = money(sourceAmount, sourceCurrency)
        if (source == null) {
            log.append("source amount + currency required")
            return null
        }
        val seconds = expiresIn.text.toString().trim().toLongOrNull()
        if (seconds == null) {
            log.append("expires in (seconds) must be a number")
            return null
        }
        return WirexWalletQuote(
            estimationId = id,
            sourceAmount = source,
            destinationAmount = money(destinationAmount, destinationCurrency),
            fee = money(feeAmount, sourceCurrency),
            // Epoch MILLISECONDS, by contract.
            expiresAtMs = System.currentTimeMillis() + seconds * 1000,
        )
    }

    private fun money(amount: EditText, currency: EditText): WirexWalletMoney? {
        val amountText = amount.text.toString().trim()
        val currencyText = currency.text.toString().trim()
        if (amountText.isEmpty() || currencyText.isEmpty()) return null
        // The string constructor: the amount reaches the frame as the host has it; the frame formats.
        return WirexWalletMoney(amountText, currencyText)
    }

    // ── events ──────────────────────────────────────────────────────────────────────

    private fun onEvent(event: WirexWalletCheckoutEvent) {
        when (event) {
            WirexWalletCheckoutEvent.Ready ->
                log.append("Ready — review rendered, buttons live")
            is WirexWalletCheckoutEvent.NaturalHeight -> {
                log.append("NaturalHeight ${event.height} dp — resizing the box")
                checkoutContainer.layoutParams = checkoutContainer.layoutParams.apply {
                    height = Ui.dp(context, event.height.toInt().coerceAtLeast(1))
                }
                checkoutContainer.requestLayout()
            }
            WirexWalletCheckoutEvent.SheetOpen ->
                log.append("SheetOpen — a platform payment sheet is presented")
            WirexWalletCheckoutEvent.Cancelled ->
                log.append("Cancelled — the user dismissed the sheet; a decision, not a fault")
            is WirexWalletCheckoutEvent.AttemptFailed ->
                log.append("AttemptFailed reason=${event.reason} (sanitized slug)")
            WirexWalletCheckoutEvent.EstimationExpired ->
                log.append("EstimationExpired — re-estimate and press New quote (rearm)")
            is WirexWalletCheckoutEvent.Token -> {
                // ONLY display metadata and the payload's length. The payload is the execute call's
                // wallet_payment_token and never appears in a log, here or anywhere.
                val token = event.token
                log.append(
                    "Token method=${token.method.wire} cardNetwork=${token.cardNetwork} " +
                        "panLast4=${token.panLast4} displayName=${token.displayName} " +
                        "payloadLength=${token.walletPaymentToken.length} — a real host calls execute now",
                )
            }
            // TOKEN MODE (the view was given `auth`): the page executes. The top-up token itself is never
            // printed — not here, not anywhere.
            WirexWalletCheckoutEvent.Executing ->
                log.append("Executing — token mode: the sheet closed, the page is calling execute")
            is WirexWalletCheckoutEvent.Executed -> {
                val result = event.result
                log.append(
                    "Executed id=${result.id} status=${result.status} method=${result.method.wire} " +
                        "cardNetwork=${result.cardNetwork} panLast4=${result.panLast4} " +
                        "— reconcile by status, as after your own execute",
                )
            }
            is WirexWalletCheckoutEvent.ExecuteFailed -> {
                val error = event.error
                log.append(
                    "ExecuteFailed reason=${error.reason} status=${error.statusCode} detail=${error.detail} " +
                        "— re-estimate, mint a fresh token and rearm",
                )
            }
            is WirexWalletCheckoutEvent.Failed -> {
                val error = event.error
                log.append(
                    "Failed kind=${error.kind} code=${error.code} status=${error.statusCode} " +
                        "detail=${error.detail} — dead end for this mount; press Mount checkout to retry",
                )
            }
            is WirexWalletCheckoutEvent.Diagnostic ->
                log.append("Diagnostic ${event.diagnostic.kind.wire} ${event.diagnostic.fields}")
            is WirexWalletCheckoutEvent.Debug ->
                log.append("[frame ${event.level}] ${event.message}")
            // Hosts keep an else arm: the event set grows with the frame in later SDK versions.
            // The SDK switches exhaustively; hosts must not.
            else ->
                log.append("unhandled event $event")
        }
    }
}
