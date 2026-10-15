package com.wirexapp.baas.cards.config

/**
 * The SDK environment this example talks to.
 *
 * A name, never a URL — the SDK maps it onto the hosts it holds. Set it to the environment Wirex
 * assigned to your integration (`test` or `production`). The card surfaces resolve it through
 * `WirexEnvironment.resolve(...)`; the wallet surface reads its own name from
 * `assets/wirex-wallet-sdk.json`.
 */
object WirexSdkConfig {
    const val ENVIRONMENT: String = "test"
}
