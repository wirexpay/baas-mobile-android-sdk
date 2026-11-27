package com.wirexapp.baas.example

import fr.acinq.secp256k1.Secp256k1
import org.kotlincrypto.hash.sha3.Keccak256

/**
 * FOR TESTING ONLY. Signs with a test user's exported key, held in memory, so a tester can run a trade.
 * A real host never holds a user's key: it asks the user's own wallet to sign. Never put a real user's key here.
 */
class TestSigner private constructor(private val key: ByteArray) {

    /** The owner's address, EIP-55. */
    val address: String = checksum(keccak(Secp256k1.pubkeyCreate(key).copyOfRange(1, 65)).copyOfRange(12, 32))

    /** EIP-191 `personal_sign` over [message]'s bytes, as `r ‖ s ‖ v` with v 27 or 28. */
    fun signText(message: String): ByteArray = signPersonal(message.toByteArray(Charsets.UTF_8))

    /** EIP-191 over the raw bytes of [data], such as a 32-byte UserOp hash. */
    fun signPersonal(data: ByteArray): ByteArray {
        val prefix = "\u0019Ethereum Signed Message:\n${data.size}".toByteArray(Charsets.UTF_8)
        val digest = keccak(prefix + data)
        val compact = Secp256k1.sign(digest, key)
        val publicKey = Secp256k1.pubkeyCreate(key)
        // The library answers 64 bytes; the recovery id is the one that gives back our own key.
        val recovery = (0..1).firstOrNull { id ->
            runCatching { Secp256k1.ecdsaRecover(compact, digest, id) }.getOrNull()?.contentEquals(publicKey) == true
        } ?: error("recovery id not found")
        return compact + byteArrayOf((27 + recovery).toByte())
    }

    companion object {
        /** The signer for a 32-byte hex key, `0x` optional, or null when it is not a valid secp256k1 key. */
        fun fromHex(text: String): TestSigner? {
            val bytes = hexToBytes(text.trim()) ?: return null
            if (bytes.size != 32 || !Secp256k1.secKeyVerify(bytes)) return null
            return TestSigner(bytes)
        }

        fun keccak(data: ByteArray): ByteArray = Keccak256().digest(data)

        fun toHex(bytes: ByteArray): String = "0x" + bytes.joinToString("") { "%02x".format(it) }

        /** EIP-55 mixed case for 20 address bytes. */
        fun checksum(address: ByteArray): String {
            val lower = toHex(address).removePrefix("0x")
            val hash = toHex(keccak(lower.toByteArray(Charsets.US_ASCII))).removePrefix("0x")
            return "0x" + lower.mapIndexed { i, c -> if (c.isLetter() && hash[i].digitToInt(16) >= 8) c.uppercaseChar() else c }.joinToString("")
        }

        private fun hexToBytes(text: String): ByteArray? {
            val hex = text.removePrefix("0x").removePrefix("0X")
            if (hex.isEmpty() || hex.length % 2 != 0 || hex.any { it.digitToIntOrNull(16) == null }) return null
            return ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }
    }
}
