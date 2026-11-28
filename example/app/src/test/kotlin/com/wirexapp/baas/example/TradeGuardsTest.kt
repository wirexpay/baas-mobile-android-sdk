package com.wirexapp.baas.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigInteger

class TradeGuardsTest {

    private fun cents(value: Long) = BigInteger.valueOf(value)

    @Test fun buyWorthIsWhatItSpendsWithTheFee() {
        assertEquals(cents(1000), TradeGuards.worthCents(true, "9990000", 6, "1", 18, "10000"))
        assertEquals(cents(500), TradeGuards.worthCents(true, "5000000", 6, "1", 18, null))
    }

    @Test fun worthRoundsUp() {
        assertEquals(cents(1001), TradeGuards.worthCents(true, "10000001", 6, "1", 18, null))
    }

    @Test fun saleWorthIsTheUsdcItEstimates() {
        assertEquals(cents(750), TradeGuards.worthCents(false, "1", 18, "7500000", 6, null))
    }

    @Test fun unreadableWorthIsNull() {
        assertNull(TradeGuards.worthCents(true, "ten", 6, "1", 18, null))
        assertNull(TradeGuards.worthCents(true, "1", 6, "1", 18, "fee"))
        assertNull(TradeGuards.worthCents(false, "1", 18, "", 6, null))
    }

    @Test fun productionAllowsTenUsdAndRefusesMore() {
        assertNull(TradeGuards.refuseWorth("production", cents(1000)))
        assertEquals("refused: a production trade above 10 USD", TradeGuards.refuseWorth("production", cents(1001)))
    }

    @Test fun productionRefusesAnUnreadableWorth() {
        assertEquals("refused: the trade worth cannot be read", TradeGuards.refuseWorth("production", null))
    }

    @Test fun testHasNoCap() {
        assertNull(TradeGuards.refuseWorth("test", cents(1_000_000)))
        assertNull(TradeGuards.refuseWorth("test", null))
    }

    @Test fun usdText() {
        assertEquals(cents(1000), TradeGuards.usdTextCents(" 10 "))
        assertEquals(cents(1000), TradeGuards.usdTextCents("10.00"))
        assertEquals(cents(1001), TradeGuards.usdTextCents("10.001"))
        assertEquals(cents(250), TradeGuards.usdTextCents("2.5"))
        assertNull(TradeGuards.usdTextCents("0"))
        assertNull(TradeGuards.usdTextCents("-1"))
        assertNull(TradeGuards.usdTextCents("1e3"))
        assertNotNull(TradeGuards.refuseWorth("production", TradeGuards.usdTextCents("10.01")))
    }

    private val usdc = TradeGuards.BalanceRow("0x00000000000000000000000000000000000000aa", "USDC", 6)
    private val wusd = TradeGuards.BalanceRow("0x00000000000000000000000000000000000000bb", "WUSD", 18)
    private val weur = TradeGuards.BalanceRow("0x00000000000000000000000000000000000000cc", "WEUR", 6)

    @Test fun sendCapAtFaceValue() {
        assertNull(TradeGuards.refuseSend("production", "USDC", cents(1000)))
        assertEquals("refused: a production send above 10 USD", TradeGuards.refuseSend("production", "usdc", cents(1001)))
        assertNull(TradeGuards.refuseSend("production", "WUSD", cents(1000)))
        assertNotNull(TradeGuards.refuseSend("production", "USDT", cents(1001)))
    }

    @Test fun sendCapOnEuroAtOneTwentyFive() {
        assertNull(TradeGuards.refuseSend("production", "EURC", cents(800)))
        assertEquals("refused: a production send above 10 USD", TradeGuards.refuseSend("production", "WEUR", cents(801)))
    }

    @Test fun sendCapRefusesOtherTokensAndUnreadableWorth() {
        assertEquals(
            "refused: on production only USDC, USDT, WUSD, EURC and WEUR can be sent",
            TradeGuards.refuseSend("production", "ETH", cents(1)),
        )
        assertEquals("refused: on production only USDC, USDT, WUSD, EURC and WEUR can be sent", TradeGuards.refuseSend("production", "", cents(1)))
        assertEquals("refused: the send worth cannot be read", TradeGuards.refuseSend("production", "USDC", null))
        assertNull(TradeGuards.refuseSend("test", "ETH", null))
    }

    @Test fun unitsCentsRoundsUp() {
        assertEquals(cents(1000), TradeGuards.unitsCents("10000000", 6))
        assertEquals(cents(1), TradeGuards.unitsCents("1", 6))
        assertNull(TradeGuards.unitsCents("x", 6))
        assertNull(TradeGuards.unitsCents("1", null))
    }

    @Test fun leavesIsTheUnwrapWhenTheQuoteUnwraps() {
        val leaves = TradeGuards.sendLeaves(wusd.token.uppercase().replace("0X", "0x"), "10050000000000000000", usdc.token, "10050000", listOf(usdc, wusd))
        assertEquals("WUSD", leaves.symbol)
        assertEquals(cents(1005), leaves.cents)
        assertNotNull(TradeGuards.refuseSend("production", leaves.symbol, leaves.cents))
    }

    @Test fun leavesIsThePayTokenOtherwise() {
        val leaves = TradeGuards.sendLeaves(null, null, weur.token, "8000000", listOf(usdc, weur))
        assertEquals(TradeGuards.Leaves("WEUR", cents(800)), leaves)
    }

    @Test fun aPayingTokenWithNoRowIsRefused() {
        val leaves = TradeGuards.sendLeaves(wusd.token, "1", usdc.token, "1", listOf(usdc))
        assertEquals(TradeGuards.Leaves("", null), leaves)
        assertNotNull(TradeGuards.refuseSend("production", leaves.symbol, leaves.cents))
    }
}
