package com.wirexapp.baas.example

import fr.acinq.secp256k1.Secp256k1
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TestSignerTest {

    // An obviously fake key: the scalar 1, whose address every Ethereum library documents.
    private val one = "0x" + "0".repeat(63) + "1"

    @Test fun keccakOfNothing() {
        assertEquals("0xc5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470", TestSigner.toHex(TestSigner.keccak(ByteArray(0))))
    }

    @Test fun addressOfKeyOne() {
        assertEquals("0x7E5F4552091A69125d5DfCb7b8C2659029395Bdf", TestSigner.fromHex(one)!!.address)
    }

    @Test fun eip55Vector() {
        val raw = ByteArray(20) { "5aaeb6053f3e94c9b9a09f33669435e7ef1beaed".substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        assertEquals("0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed", TestSigner.checksum(raw))
    }

    @Test fun refusesWhatIsNotAKey() {
        assertNull(TestSigner.fromHex("0x" + "0".repeat(64)))
        assertNull(TestSigner.fromHex("0x1234"))
        assertNull(TestSigner.fromHex("not hex"))
    }

    @Test fun personalSignRecoversTheSigner() {
        val signer = TestSigner.fromHex(one)!!
        val hash = ByteArray(32) { it.toByte() }
        val signature = signer.signPersonal(hash)
        assertEquals(65, signature.size)
        val v = signature[64].toInt()
        assertTrue(v == 27 || v == 28)
        val prefix = "\u0019Ethereum Signed Message:\n32".toByteArray()
        val recovered = Secp256k1.ecdsaRecover(signature.copyOfRange(0, 64), TestSigner.keccak(prefix + hash), v - 27)
        assertArrayEquals(Secp256k1.pubkeyCreate(ByteArray(32).also { it[31] = 1 }), recovered)
    }

    @Test fun loginMessageIsTheBackendTemplate() {
        assertEquals(
            "I, owner of account 0x7E5F4552091A69125d5DfCb7b8C2659029395Bdf confirm that I request access token for company 00000000-0000-0000-0000-000000000000 on chain 8453 at 1700000000",
            WalletLogin.message("0x7E5F4552091A69125d5DfCb7b8C2659029395Bdf", "00000000-0000-0000-0000-000000000000", "8453", 1_700_000_000),
        )
    }
}
