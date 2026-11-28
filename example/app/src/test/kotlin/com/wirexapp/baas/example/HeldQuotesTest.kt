package com.wirexapp.baas.example

import com.wirexapp.baas.crypto.WirexSendQuote
import com.wirexapp.baas.crypto.WirexSendRail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeldQuotesTest {

    // Obviously fake values: a zero-filled send id and made-up hashes.
    private fun quote(sendId: String, requestHash: String) = WirexSendQuote(
        sendId = sendId, chainId = 8453, network = "BASE", rail = WirexSendRail.TRANSFER, token = "USDC",
        to = "0x000000000000000000000000000000000000dEaD", amount = "1000000", fee = "0", arrives = "1000000",
        payToken = "0x00000000000000000000000000000000000000aa", payUnits = "1000000", unwrap = null,
        validUntil = "1700000000", requestHash = requestHash,
    )

    @Test fun matchesARowByRequestHashInLowerCase() {
        val held = HeldQuotes()
        held.hold(quote("00000000-0000-4000-8000-000000000001", "0xABCDEF"))
        assertEquals("00000000-0000-4000-8000-000000000001", held.forHash("0xabcdef")?.sendId)
    }

    @Test fun anotherHashFindsNothing() {
        val held = HeldQuotes()
        held.hold(quote("00000000-0000-4000-8000-000000000001", "0xabcdef"))
        assertNull(held.forHash("0x123456"))
    }

    @Test fun aNewQuoteForTheSameSendReplacesTheOld() {
        val held = HeldQuotes()
        held.hold(quote("00000000-0000-4000-8000-000000000001", "0xaaaa"))
        held.hold(quote("00000000-0000-4000-8000-000000000001", "0xbbbb"))
        assertNull(held.forHash("0xaaaa"))
        assertEquals("00000000-0000-4000-8000-000000000001", held.forHash("0xBBBB")?.sendId)
        held.clear()
        assertNull(held.forHash("0xbbbb"))
    }
}
