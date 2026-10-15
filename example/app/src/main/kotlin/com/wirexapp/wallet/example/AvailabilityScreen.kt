package com.wirexapp.wallet.example

import android.content.Context
import android.view.View
import android.widget.EditText
import android.widget.TextView
import com.wirexapp.wallet.topup.WirexWalletAvailabilityChecker
import com.wirexapp.wallet.topup.WirexWalletConfig
import com.wirexapp.wallet.topup.WirexWalletLogLevel
import com.wirexapp.wallet.topup.WirexWalletLogging
import com.wirexapp.wallet.topup.model.WirexWalletAvailability
import com.wirexapp.wallet.topup.model.WirexWalletProviderConfig

/**
 * "Can THIS device tokenize" — [WirexWalletAvailabilityChecker] runs the real probe document in an
 * off-screen web view with the checkout's exact configuration and answers two booleans.
 *
 * This screen is also where the config's WebView / Play Services floors become visible: paste a
 * provider config whose `google_pay` block carries `min_webview_version` / `min_play_services_version`
 * above the device and the checker publishes `googlePay=false` WITHOUT loading anything — watch for
 * `probe_below_floor` in the log.
 */
class AvailabilityScreen(private val context: Context, private val appConfig: AppConfig) {

    private val log = EventLog(context)
    private var checker: WirexWalletAvailabilityChecker? = null
    private lateinit var status: TextView
    private lateinit var providerField: EditText

    fun build(): View {
        val column = Ui.column(context)

        column.addView(Ui.heading(context, "Availability"), Ui.matchWidth())
        column.addView(
            Ui.caption(
                context,
                "One fresh probe per process, by contract — after it settles, Run probe replays the " +
                    "settled answer. The cached answer (two booleans, nothing else) lives in the " +
                    "example's in-memory adapter.",
            ),
            Ui.matchWidth(),
        )
        status = Ui.mono(context)
        column.addView(status, Ui.matchWidth())

        column.addView(Ui.heading(context, "Provider config (optional)"), Ui.matchWidth())
        column.addView(
            Ui.caption(
                context,
                "The DEFAULT provider's block, pre-quote. Blank = none. \"Unmeetable floors\" adds " +
                    "google_pay.min_webview_version / min_play_services_version no device meets, so the " +
                    "floor verdict is visible. Samples are SHAPE only — field names and types, not real values.",
            ),
            Ui.matchWidth(),
        )
        providerField = Ui.jsonField(context, "")
        column.addView(providerField, Ui.matchWidth())
        column.addView(
            Ui.buttonRow(
                context,
                "Sample (shape only)" to { providerField.setText(Samples.PROVIDER_CONFIG_SHAPE) },
                "Unmeetable floors" to { providerField.setText(Samples.PROVIDER_CONFIG_UNMEETABLE_FLOORS) },
            ),
            Ui.matchWidth(),
        )

        column.addView(
            Ui.buttonRow(
                context,
                "Run probe" to ::runProbe,
                "Clear cache" to ::clearCache,
                "Clear log" to { log.clear() },
            ),
            Ui.matchWidth(),
        )

        column.addView(Ui.heading(context, "Log"), Ui.matchWidth())
        column.addView(log.view, Ui.matchWidth())

        renderStatus(null)
        return column
    }

    fun release() {
        checker?.stop()
        checker = null
    }

    private fun runProbe() {
        val environment = appConfig.environment
        if (environment == null) {
            log.append("cannot probe: ${appConfig.error}")
            return
        }

        // Blank is valid — "no block" — while a paste that does not parse aborts the run.
        val text = providerField.text.toString().trim()
        val providerConfig: WirexWalletProviderConfig?
        if (text.isEmpty()) {
            providerConfig = null
            log.append("no provider config — probing without a block")
        } else {
            providerConfig = try {
                WirexWalletProviderConfig.fromJson(text)
            } catch (e: IllegalArgumentException) {
                log.append("provider config rejected: ${e.message}")
                return
            }
        }

        // A fresh checker per run, the previous one stopped first — `stop()` abandons a probe in
        // flight without publishing anything.
        checker?.stop()
        val config = WirexWalletConfig(
            environment = environment,
            clientId = null,
            logging = WirexWalletLogging(WirexWalletLogLevel.INFO, ScreenLogSink(log)),
            cache = InMemoryAvailabilityCache,
        )
        val fresh = WirexWalletAvailabilityChecker(context, config, providerConfig)
        checker = fresh

        // `current` is instant: this process's settled answer, else the cached real answer, else null.
        log.append("current (before start) = ${describe(fresh.current)}")
        renderStatus(fresh.current)

        fresh.onChange = { availability ->
            log.append("onChange applePay=${availability.applePay} googlePay=${availability.googlePay}")
            renderStatus(availability)
        }
        fresh.onDiagnostic = { diagnostic ->
            log.append("diagnostic ${diagnostic.kind.wire} ${diagnostic.fields}")
        }
        log.append("start() — probing ${environment.name} (${environment.framesOrigin})")
        fresh.start()
    }

    private fun clearCache() {
        InMemoryAvailabilityCache.clear()
        log.append("cache cleared (note: this process's probe answer stays settled until restart)")
        renderStatus(checker?.current)
    }

    private fun renderStatus(availability: WirexWalletAvailability?) {
        status.text = "current: ${describe(availability)}\ncache:   ${InMemoryAvailabilityCache.describe()}"
    }

    private fun describe(availability: WirexWalletAvailability?): String =
        availability?.let { "applePay=${it.applePay} googlePay=${it.googlePay}" } ?: "null (no answer yet)"
}
