package com.wirexapp.baas.example

import com.wirexapp.baas.trade.WirexTradeError

/** Display helpers of the Trade tab, the same text every platform's example writes. */
object TradeFormat {

    private val DIGITS = Regex("^[0-9]+$")

    private val HINTS = mapOf(
        "signer_mismatch" to "the key does not own this account",
        "investment_wallet_required" to "set up the account first",
        "quote_expired" to "get a new quote",
        "insufficient_funds" to "the main account cannot cover the buy",
        "order_not_ended" to "the order is still open: resume it from Open orders",
    )

    /** Base units as decimal text, trailing zeros dropped. Unreadable input shows as it came. */
    fun formatUnits(units: String?, decimals: Int): String {
        if (units == null) return "—"
        if (!DIGITS.matches(units) || decimals < 0) return units
        val padded = units.padStart(decimals + 1, '0')
        val whole = padded.substring(0, padded.length - decimals)
        val fraction = padded.substring(padded.length - decimals).trimEnd('0')
        return if (fraction.isEmpty()) whole else "$whole.$fraction"
    }

    /** `reason` first, then the fields that say where. It never carries an amount, an address or a hash. */
    fun describe(
        reason: String,
        step: String?,
        routeReason: String?,
        detailKey: String?,
        httpStatus: Int?,
        entryPointCode: String?,
    ): String {
        val parts = mutableListOf(reason)
        HINTS[reason]?.let { parts += "($it)" }
        step?.let { parts += "step $it" }
        routeReason?.let { parts += "route $it" + (detailKey?.let { key -> " / $key" } ?: "") }
        httpStatus?.let { parts += "HTTP $it" }
        entryPointCode?.takeIf { it.isNotEmpty() }?.let { parts += it }
        return parts.joinToString(" · ")
    }

    fun describe(error: Throwable): String = when (error) {
        is WirexTradeError -> describe(error.reason, error.step?.wire, error.routeReason, error.detailKey, error.httpStatus, error.entryPointCode)
        else -> "${error.javaClass.simpleName}: ${error.message ?: "(no message)"}"
    }
}
