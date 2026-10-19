package com.wirexapp.wallet.example

import android.content.Context
import com.wirexapp.baas.example.LiveConfig
import com.wirexapp.baas.cards.core.WirexEnvironmentUnavailable
import com.wirexapp.wallet.topup.WirexWalletAvailabilityCache
import com.wirexapp.wallet.topup.WirexWalletEnvironment

/**
 * How this example chooses an environment: the NAME selected on the Config tab (`LiveConfig.environment`,
 * `test` / `production`), resolved by `WirexWalletEnvironment.resolve` into the frames origin the SDK
 * build carries on every use. Nothing here is a URL; there is nothing a URL could be passed to.
 *
 * Resolved lazily rather than once at launch, so flipping the environment chip after the app started
 * reaches the Wallet screens too. A failure is carried as [environment] == null with its reason rather
 * than a crash, so every screen can show WHY it is disabled. Never a fallback.
 */
class AppConfig private constructor() {

    val environmentName: String get() = LiveConfig.environment
    val environment: WirexWalletEnvironment? get() = resolve().first
    val error: String? get() = resolve().second

    private fun resolve(): Pair<WirexWalletEnvironment?, String?> {
        val name = environmentName
        return try {
            WirexWalletEnvironment.resolve(name) to null
        } catch (unknown: WirexEnvironmentUnavailable.UnknownName) {
            null to ("The Config tab names an environment this build does not have: \"$name\". " +
                "Known: ${unknown.known.joinToString(", ")}. The environment is a name, never a URL, " +
                "and there is no fallback.")
        } catch (notBuilt: WirexEnvironmentUnavailable.NotBuilt) {
            null to "This build has no configuration for \"$name\". Set the environment Wirex assigned (test / production)."
        } catch (unavailable: WirexEnvironmentUnavailable) {
            null to "\"$name\" cannot be used: ${unavailable.message}"
        }
    }

    companion object {
        @Suppress("UNUSED_PARAMETER")
        fun load(context: Context): AppConfig = AppConfig()
    }
}

/**
 * The example's storage adapter: a map, nothing more. A real host hands the SDK its preferences —
 * the SDK writes exactly one key holding the probe's two booleans. In-memory here so "Clear cache"
 * is honest and a reinstall-like state is one button away.
 */
object InMemoryAvailabilityCache : WirexWalletAvailabilityCache {
    private val values = mutableMapOf<String, String>()

    override fun read(key: String): String? = values[key]

    override fun write(key: String, value: String) {
        values[key] = value
    }

    fun clear() = values.clear()

    fun describe(): String = if (values.isEmpty()) "(empty)" else values.entries.joinToString { "${it.key}=${it.value}" }
}

/** Pasteable provider-config JSON. SHAPE only: field names and types, never real merchant values. */
object Samples {
    /** A sample provider block, in the shape the backend's `topup.providers.<name>` delivers. */
    val PROVIDER_CONFIG_SHAPE = """
        {
          "apple_pay": {
            "merchant_name": "Example Merchant",
            "merchant_country_code": "GB",
            "merchant_capabilities": ["supports3DS"],
            "supported_networks": ["visa", "masterCard"],
            "required_billing_contact_fields": [],
            "api_version": 3
          },
          "google_pay": {
            "allowed_auth_methods": ["CRYPTOGRAM_3DS"],
            "allowed_card_networks": ["VISA", "MASTERCARD"],
            "api_version": 2,
            "api_version_minor": 0
          }
        }
    """.trimIndent()

    /**
     * The same block with floors no device meets, so the availability checker's floor verdict —
     * `googlePay=false` published WITHOUT loading anything — is one button away.
     */
    val PROVIDER_CONFIG_UNMEETABLE_FLOORS = """
        {
          "apple_pay": {
            "merchant_name": "Example Merchant",
            "merchant_country_code": "GB",
            "merchant_capabilities": ["supports3DS"],
            "supported_networks": ["visa", "masterCard"],
            "required_billing_contact_fields": [],
            "api_version": 3
          },
          "google_pay": {
            "allowed_auth_methods": ["CRYPTOGRAM_3DS"],
            "allowed_card_networks": ["VISA", "MASTERCARD"],
            "api_version": 2,
            "api_version_minor": 0,
            "min_webview_version": 9999,
            "min_play_services_version": 9999
          }
        }
    """.trimIndent()
}
