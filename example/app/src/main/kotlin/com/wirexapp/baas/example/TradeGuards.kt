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

    private fun units(value: String?): BigInteger? = value?.takeIf { DIGITS.matches(it) }?.let(::BigInteger)

    private fun ceilCents(units: BigInteger, decimals: Int): BigInteger? {
        if (decimals < 0) return null
        val scale = BigInteger.TEN.pow(decimals)
        return (units * HUNDRED + scale - BigInteger.ONE) / scale
    }
}
