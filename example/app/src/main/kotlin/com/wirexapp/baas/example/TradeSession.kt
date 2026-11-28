package com.wirexapp.baas.example

import com.wirexapp.baas.cards.core.logging.WirexLogLevel
import com.wirexapp.baas.cards.core.logging.WirexLogging
import com.wirexapp.baas.trade.WirexTrade
import com.wirexapp.baas.trade.WirexTradeSigner

/** The one [WirexTrade] the tab uses, made from the config. It is rebuilt when the environment, the chain or the
 *  key's address changes; the bearer is read on every request, so a new one needs no rebuild. */
class TradeSession {

    private var key: List<String?>? = null

    var trade: WirexTrade? = null
        private set

    /** Why there is no trade, or null when there is one. */
    var problem: String? = null
        private set

    /** Reads the config again. True when the trade was rebuilt, so the tab clears what the old one showed. */
    fun refresh(): Boolean {
        val environment = LiveConfig.environment
        val chainId = LiveConfig.credential("chainId")
        val signer = WalletLogin.signer()
        val missing = problems(environment, chainId, signer)
        val next = listOf(environment, chainId, signer?.address, missing)
        if (next == key) return false
        key = next
        trade = null
        problem = missing
        if (problem != null) return true
        try {
            trade = WirexTrade.create(
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

    internal companion object {
        /** What the config still lacks for a trade or a send, one line each, or null when nothing. */
        fun problems(environment: String, chainId: String, signer: TestSigner?): String? {
            val list = mutableListOf<String>()
            if (environment != "production" && environment != "test") list += "environment: production or test"
            if (chainId.isEmpty()) list += "chainId: 8453 for Base, 5042 for Arc"
            if (LiveConfig.credential("companyId").isEmpty() && LiveConfig.credential("bearer").isEmpty()) list += "companyId: needed for the login"
            if (signer == null) list += "ownerKey: a 32-byte hex private key"
            return list.takeIf { it.isNotEmpty() }?.joinToString("\n")
        }
    }
}

/** The example's [TestSigner] as the SDK takes a host signer; `WirexCrypto` takes the same interface. */
internal class HostSigner(private val signer: TestSigner) : WirexTradeSigner {
    override val address: String = signer.address

    override suspend fun signPersonal(hash: ByteArray): ByteArray = signer.signPersonal(hash)
}
