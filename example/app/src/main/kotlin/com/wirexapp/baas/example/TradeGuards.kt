package com.wirexapp.baas.example

import java.math.BigInteger

/** The example's own money guard: on `production` it refuses a trade worth more than 10 USD. Worth is in cents,
 *  rounded up, so a value a fraction above the limit is refused too. */
object TradeGuards {

    val PRODUCTION_LIMIT_CENTS: BigInteger = BigInteger.valueOf(1000)

    private val HUNDRED = BigInteger.valueOf(100)
    private val DIGITS = Regex("^[0-9]+$")
    private val USD_TEXT = Regex("^([0-9]+)(?:\\.([0-9]+))?$")

    /** A buy is worth the USDC it spends with Circle's fee; a sale, the USDC it estimates. Null when unreadable. */
    fun worthCents(
        isBuy: Boolean,
        amountIn: String,
        tokenInDecimals: Int,
        estimatedAmountOut: String,
        tokenOutDecimals: Int,
        transferFee: String?,
    ): BigInteger? {
        if (isBuy) {
            val spent = units(amountIn) ?: return null
            val fee = if (transferFee == null) BigInteger.ZERO else units(transferFee) ?: return null
            return ceilCents(spent + fee, tokenInDecimals)
        }
        return ceilCents(units(estimatedAmountOut) ?: return null, tokenOutDecimals)
    }

    /** A buy's USD amount text in cents, rounded up. Null when it is not a positive decimal. */
    fun usdTextCents(text: String): BigInteger? {
        val match = USD_TEXT.matchEntire(text.trim()) ?: return null
        val fraction = match.groupValues[2]
        val cents = BigInteger(match.groupValues[1]) * HUNDRED + BigInteger((fraction + "00").substring(0, 2))
        val rest = if (fraction.length > 2 && fraction.substring(2).any { it in '1'..'9' }) BigInteger.ONE else BigInteger.ZERO
        return (cents + rest).takeIf { it.signum() > 0 }
    }

    /** Why the example refuses a trade of this worth, or null when it may run. */
    fun refuseWorth(environment: String, cents: BigInteger?): String? {
        // SECURITY: an unreadable worth on production is refused, never let through.
        if (environment != "production") return null
        if (cents == null) return "refused: the trade worth cannot be read"
        if (cents > PRODUCTION_LIMIT_CENTS) return "refused: a production trade above 10 USD"
        return null
    }

    /** USD value per unit of each token a production send may move: EUR overstated, so the cap caps at 8. */
    private val SEND_RATES: Map<String, Pair<BigInteger, BigInteger>> = mapOf(
        "USDC" to (BigInteger.ONE to BigInteger.ONE),
        "USDT" to (BigInteger.ONE to BigInteger.ONE),
        "WUSD" to (BigInteger.ONE to BigInteger.ONE),
        "EURC" to (BigInteger.valueOf(5) to BigInteger.valueOf(4)),
        "WEUR" to (BigInteger.valueOf(5) to BigInteger.valueOf(4)),
    )

    /** One balance row as the cap reads it: the token's address, symbol and decimals. */
    data class BalanceRow(val token: String, val symbol: String, val decimals: Int)

    /** What leaves, as a symbol and its cents, or no cents when it cannot be read. */
    data class Leaves(val symbol: String, val cents: BigInteger?)

    /** Base units in cents of the token, rounded up. Null when unreadable. */
    fun unitsCents(units: String?, decimals: Int?): BigInteger? {
        if (decimals == null) return null
        return ceilCents(units(units) ?: return null, decimals)
    }

    /** Why the example refuses a send of [cents] of [symbol], or null when it may run. */
    fun refuseSend(environment: String, symbol: String, cents: BigInteger?): String? {
        if (environment != "production") return null
        // SECURITY: on production only the listed stablecoins count toward the cap; any other token is refused.
        val (num, den) = SEND_RATES[symbol.trim().uppercase()] ?: return "refused: on production only USDC, USDT, WUSD, EURC and WEUR can be sent"
        if (cents == null) return "refused: the send worth cannot be read"
        if ((cents * num + den - BigInteger.ONE) / den > PRODUCTION_LIMIT_CENTS) return "refused: a production send above 10 USD"
        return null
    }

    /** What leaves on every rail: the unwrapped token when the quote unwraps, else [payUnits] of [payToken]. */
    fun sendLeaves(unwrapToken: String?, unwrapUnits: String?, payToken: String, payUnits: String, rows: List<BalanceRow>): Leaves {
        val token = if (unwrapToken != null) unwrapToken else payToken
        val units = if (unwrapToken != null) unwrapUnits else payUnits
        val row = rows.firstOrNull { it.token.equals(token, ignoreCase = true) }
        // SECURITY: a paying token with no balance row is refused, as its symbol and decimals cannot be read.
        return Leaves(row?.symbol.orEmpty(), row?.let { unitsCents(units, it.decimals) })
    }

    private fun units(value: String?): BigInteger? = value?.takeIf { DIGITS.matches(it) }?.let(::BigInteger)

    private fun ceilCents(units: BigInteger, decimals: Int): BigInteger? {
        if (decimals < 0) return null
        val scale = BigInteger.TEN.pow(decimals)
        return (units * HUNDRED + scale - BigInteger.ONE) / scale
    }
}
