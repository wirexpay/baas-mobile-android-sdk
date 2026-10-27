package com.wirexapp.wallet.example

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.browser.customtabs.CustomTabsIntent
import com.wirexapp.baas.example.DiagLog
import com.wirexapp.baas.example.LiveConfig
import com.wirexapp.wallet.topup.WirexWalletConfig
import com.wirexapp.wallet.topup.WirexWalletLaunch
import com.wirexapp.wallet.topup.model.WirexWalletLaunchParams
import com.wirexapp.wallet.topup.model.WirexWalletMoney
import com.wirexapp.wallet.topup.model.WirexWalletQuote
import com.wirexapp.wallet.topup.model.WirexWalletReturnLink

/**
 * Wallet top-up the way a partner ships it: the checkout opens TOP-LEVEL in a **Chrome Custom Tab**,
 * never a WebView. Google Pay validates the hosting app's package against the merchant, and only a
 * top-level tab satisfies that — a WebView cannot complete the sheet.
 *
 * The SDK builds the URL ([WirexWalletLaunch.buildUrl]); opening the tab is the host's. The top-up
 * token rides in the URL fragment, so it is handed to the tab and to nothing else — never logged. The
 * checkout runs in token mode, shows its own result, and offers a "Return to the app" link: the
 * `returnUrl`, an https App Link this app catches (see the manifest intent-filter and
 * [com.wirexapp.baas.example.MainActivity.onNewIntent]), with `estimation_id` and `outcome` appended —
 * read with [WirexWalletReturnLink].
 */
class LaunchScreen(private val activity: Activity, private val appConfig: AppConfig) {
    private val context: Context = activity

    private lateinit var checkoutToken: EditText
    private lateinit var estimationId: EditText
    private lateinit var provider: EditText
    private lateinit var sourceAmount: EditText
    private lateinit var sourceCurrency: EditText
    private lateinit var destinationAmount: EditText
    private lateinit var destinationCurrency: EditText
    private lateinit var feeAmount: EditText
    private lateinit var returnUrlField: EditText
    private var log: TextView? = null

    /** The returnUrl last armed by a launch, matched against the App Link the tab resolves on. */
    var returnUrl: String = DEFAULT_RETURN_URL
        private set

    private fun syncFromConfig() {
        for ((field, key) in listOf(checkoutToken to "checkoutToken", estimationId to "estimationId", provider to "walletProvider")) {
            val seeded = LiveConfig.credential(key)
            if (seeded.isNotEmpty() && field.text.toString() != seeded) field.setText(seeded)
        }
    }

    fun build(): View {
        val column = Ui.column(context)

        column.addView(Ui.heading(context, "Wallet — Custom Tab launch"), Ui.matchWidth())
        column.addView(
            TextView(context).apply {
                text = "The checkout opens top-level in a Chrome Custom Tab (not a WebView). Paste the " +
                    "top-up token your backend minted (POST /cards/topup/token) and the estimation; " +
                    "pay in the tab, then tap \"Return to the app\"."
            },
            Ui.matchWidth(),
        )

        checkoutToken = Ui.field(context, "")
        estimationId = Ui.field(context, "")
        provider = Ui.field(context, "nuvei")
        sourceAmount = Ui.field(context, "105.50")
        sourceCurrency = Ui.field(context, "EUR")
        destinationAmount = Ui.field(context, "100.00")
        destinationCurrency = Ui.field(context, "EUR")
        feeAmount = Ui.field(context, "5.50")
        returnUrlField = Ui.field(context, DEFAULT_RETURN_URL)
        // A seed link (deep link or the Config tab's field) may carry the checkout token, estimation id
        // and provider: prefill those fields, so a QA device needs no typing. They stay editable.
        syncFromConfig()
        LiveConfig.onCredentialsChanged { activity.runOnUiThread { syncFromConfig() } }

        column.addView(Ui.labelled(context, "checkoutToken", checkoutToken), Ui.matchWidth())
        column.addView(Ui.labelled(context, "estimationId", estimationId), Ui.matchWidth())
        column.addView(Ui.labelled(context, "provider", provider), Ui.matchWidth())
        column.addView(Ui.labelled(context, "source amount", sourceAmount), Ui.matchWidth())
        column.addView(Ui.labelled(context, "source currency", sourceCurrency), Ui.matchWidth())
        column.addView(Ui.labelled(context, "destination amount", destinationAmount), Ui.matchWidth())
        column.addView(Ui.labelled(context, "destination currency", destinationCurrency), Ui.matchWidth())
        column.addView(Ui.labelled(context, "fee (source currency)", feeAmount), Ui.matchWidth())
        column.addView(Ui.labelled(context, "returnUrl (https App Link)", returnUrlField), Ui.matchWidth())

        column.addView(
            Ui.buttonRow(context, "Launch in Custom Tab" to { DiagLog.tap("Wallet: Launch in Custom Tab"); launch() }),
            Ui.matchWidth(),
        )

        column.addView(Ui.heading(context, "Events"), Ui.matchWidth())
        log = Ui.mono(context)
        column.addView(log, Ui.matchWidth())

        return column
    }

    private fun launch() {
        val token = checkoutToken.text.toString().trim()
        if (token.isEmpty()) {
            logLine("checkoutToken required — mint one with POST /api/v1/cards/topup/token")
            return
        }
        val environment = appConfig.environment
        if (environment == null) {
            logLine("environment unavailable: ${appConfig.error}")
            return
        }
        val source = money(sourceAmount, sourceCurrency)
        if (source == null) {
            logLine("source amount + currency required")
            return
        }
        val quote = WirexWalletQuote(
            estimationId = estimationId.text.toString().trim(),
            sourceAmount = source,
            destinationAmount = money(destinationAmount, destinationCurrency),
            fee = money(feeAmount, sourceCurrency),
        )
        returnUrl = returnUrlField.text.toString().trim()
        val params = WirexWalletLaunchParams(
            checkoutToken = token,
            quote = quote,
            provider = provider.text.toString().trim(),
            returnUrl = returnUrl.ifEmpty { null },
        )
        val url = WirexWalletLaunch.buildUrl(WirexWalletConfig(environment), params)
        // The fragment carries the token — log only the part before it.
        logLine("opening Custom Tab: ${url.substringBefore('#')}#<redacted>")
        CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url))
    }

    /** Called by MainActivity when an App Link arrives, so the result screen reflects the return. */
    fun onReturn(url: String) {
        if (!WirexWalletReturnLink.matches(url, returnUrl)) {
            logLine("return link ignored (not ours): $url")
            return
        }
        val link = WirexWalletReturnLink.parse(url)
        logLine("RETURN — estimationId=${link.estimationId} outcome=${link.outcome}")
        logLine("(UX signal only — reconcile the top-up by the estimation's status on your backend)")
    }

    private fun money(amount: EditText, currency: EditText): WirexWalletMoney? {
        val a = amount.text.toString().trim()
        val c = currency.text.toString().trim()
        if (a.isEmpty() || c.isEmpty()) return null
        return WirexWalletMoney(a, c)
    }

    private fun logLine(line: String) {
        DiagLog.sdk("wallet $line")
        activity.runOnUiThread { log?.let { it.text = "${it.text}\n$line".trim() } }
    }

    companion object {
        // Demo App Link. A real host uses its own verified domain (assetlinks.json) so the tap returns
        // straight to the app; the manifest intent-filter here declares this host.
        const val DEFAULT_RETURN_URL = "https://wirex-sdk-example.wirexapp.tech/wallet-return"
    }
}
