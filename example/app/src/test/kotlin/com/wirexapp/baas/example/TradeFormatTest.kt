package com.wirexapp.baas.example

import org.junit.Assert.assertEquals
import org.junit.Test

class TradeFormatTest {

    @Test fun reasonFirstThenWhere() {
        assertEquals(
            "api_error · step submit · route ErrorGeneral / amount · HTTP 400 · AA22",
            TradeFormat.describe("api_error", "submit", "ErrorGeneral", "amount", 400, "AA22"),
        )
    }

    @Test fun signerMismatchCarriesItsHint() {
        assertEquals(
            "signer_mismatch · (the key does not own this account) · step setup",
            TradeFormat.describe("signer_mismatch", "setup", null, null, null, null),
        )
    }

    @Test fun routeWithoutDetailKey() {
        assertEquals("quote_expired · (get a new quote) · route quote_expired", TradeFormat.describe("quote_expired", null, "quote_expired", null, null, null))
    }

    @Test fun formatUnits() {
        assertEquals("10", TradeFormat.formatUnits("10000000", 6))
        assertEquals("0.000001", TradeFormat.formatUnits("1", 6))
        assertEquals("2.5", TradeFormat.formatUnits("2500000", 6))
        assertEquals("7", TradeFormat.formatUnits("7", 0))
        assertEquals("—", TradeFormat.formatUnits(null, 6))
        assertEquals("abc", TradeFormat.formatUnits("abc", 6))
    }

    @Test fun cryptoLineCarriesTheCryptoHint() {
        assertEquals(
            "recipient_is_account · (the recipient is the main account itself) · step request",
            TradeFormat.describeCrypto("recipient_is_account", "request", null, null, null, null),
        )
        assertEquals(
            "insufficient_funds · (the main account cannot cover the send) · HTTP 402",
            TradeFormat.describeCrypto("insufficient_funds", null, null, null, 402, null),
        )
    }

    @Test fun tradeKeepsItsOwnHint() {
        assertEquals("insufficient_funds · (the main account cannot cover the buy)", TradeFormat.describe("insufficient_funds", null, null, null, null, null))
    }
}
