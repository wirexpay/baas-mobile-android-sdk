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
}
