package com.wirexapp.baas.example

import com.wirexapp.baas.cards.core.logging.WirexLogLevel
import com.wirexapp.baas.cards.core.logging.WirexLogging
import com.wirexapp.baas.crypto.WirexCrypto
import com.wirexapp.baas.crypto.WirexSendQuote

/** The one [WirexCrypto] the Crypto tab uses, made from the config as [TradeSession] makes its trade, with the same
 *  signer and bearer. It is rebuilt when the environment, the chain, the key's address or the config's gaps change. */
class CryptoSession {

    private var key: List<String?>? = null

    var crypto: WirexCrypto? = null
        private set

    /** Why there is no crypto surface, or null when there is one. */
    var problem: String? = null
        private set

    /** Reads the config again. True when the surface was rebuilt, so the tab clears what the old one showed. */
    fun refresh(): Boolean {
        val environment = LiveConfig.environment
        val chainId = LiveConfig.credential("chainId")
        val signer = WalletLogin.signer()
        val missing = TradeSession.problems(environment, chainId, signer)
        val next = listOf(environment, chainId, signer?.address, missing)
        if (next == key) return false
        key = next
        crypto = null
        problem = missing
        if (problem != null) return true
        try {
            crypto = WirexCrypto.create(
                environment = environment,
                chainId = chainId,
                tokenProvider = { WalletLogin.bearer() },
                signer = HostSigner(requireNotNull(signer)),
                logging = WirexLogging(WirexLogLevel.INFO, ConsoleLogSink),
            )
        } catch (failure: Exception) {
            problem = TradeFormat.describe(failure)
        }
        return true
    }
}

/** The quotes this tab made, in memory only, so a pending row can find its own. A row carries no `sendId`, so the
 *  match is the quote's `requestHash` against the row's `hash`, in lower case. */
class HeldQuotes {

    private val quotes = mutableListOf<WirexSendQuote>()

    /** Holds [quote], replacing one with the same `sendId`. */
    fun hold(quote: WirexSendQuote) {
        quotes.removeAll { it.sendId == quote.sendId }
        quotes += quote
    }

    fun clear() = quotes.clear()

    /** The held quote whose `requestHash` is [hash], or null. */
    fun forHash(hash: String): WirexSendQuote? = quotes.firstOrNull { it.requestHash.lowercase() == hash.lowercase() }
}
