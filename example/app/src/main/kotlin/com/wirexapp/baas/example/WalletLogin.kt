package com.wirexapp.baas.example

import com.wirexapp.baas.cards.core.WirexEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Why the example has no bearer. The message never carries a value. */
class LoginException(message: String) : Exception(message)

/**
 * The bearer for the Trade tab: a pasted `bearer` wins, otherwise a wallet-signature login with `ownerKey`.
 * The minted token stays in memory until a minute before it expires.
 */
object WalletLogin {

    private const val TEMPLATE = "I, owner of account %s confirm that I request access token for company %s on chain %s at %d"

    private data class Minted(val scope: List<String>, val token: String, val expiresAt: Long)

    private val mutex = Mutex()
    private var minted: Minted? = null

    /** The signer `ownerKey` makes, or null when it is empty or not a key. */
    fun signer(): TestSigner? = LiveConfig.credential("ownerKey").takeIf { it.isNotEmpty() }?.let(TestSigner::fromHex)

    /** The exact login message the backend rebuilds and checks. */
    fun message(owner: String, companyId: String, chainId: String, nonce: Long): String =
        String.format(java.util.Locale.ROOT, TEMPLATE, owner, companyId, chainId, nonce)

    suspend fun bearer(): String {
        LiveConfig.credential("bearer").takeIf { it.isNotEmpty() }?.let { return it }
        return mutex.withLock { minted() }
    }

    fun forget() {
        minted = null
    }

    /** When the minted bearer expires, Unix seconds, or null with none or a pasted one. */
    fun expiresAt(): Long? = minted?.expiresAt?.takeIf { it > 0 && LiveConfig.credential("bearer").isEmpty() }

    private suspend fun minted(): String {
        if (LiveConfig.credential("ownerKey").isEmpty()) throw LoginException("no bearer and no ownerKey: set one on the Config tab")
        val signer = signer() ?: throw LoginException("ownerKey is not a 32-byte secp256k1 key")
        val companyId = LiveConfig.credential("companyId").ifEmpty { throw LoginException("companyId is required for the login") }
        val chainId = LiveConfig.credential("chainId").ifEmpty { throw LoginException("chainId is required for the login") }
        val scope = listOf(LiveConfig.environment, chainId, companyId, signer.address)
        val now = System.currentTimeMillis() / 1000
        minted?.takeIf { it.scope == scope && it.expiresAt - 60 > now }?.let { return it.token }

        val nonce = now
        val signature = TestSigner.toHex(signer.signText(message(signer.address, companyId, chainId, nonce)))
        val body = JSONObject()
            .put("owner_address", signer.address)
            .put("message_signature", signature)
            .put("nonce", nonce)
            .toString()
        val reply = post(loginUrl(chainId), body, companyId, chainId)
        val token = reply.optString("access_token").ifEmpty { throw LoginException("login answered no access_token") }
        minted = Minted(scope, token, reply.optLong("expires_at"))
        DiagLog.config("login: bearer minted from ownerKey (${token.length} chars)")
        return token
    }

    private fun loginUrl(chainId: String): String {
        val base = LiveConfig.credential("baasApiUrl").ifEmpty {
            WirexEnvironment.named(LiveConfig.environment, chainId)?.apiBaseUrl
                ?: throw LoginException("this build has no API host for ${LiveConfig.environment}: set baasApiUrl")
        }
        if (!base.startsWith("https://")) throw LoginException("the API host must be https")
        return base.trimEnd('/') + "/v1/user/authorize"
    }

    private suspend fun post(url: String, body: String, companyId: String, chainId: String): JSONObject = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("X-Company-Id", companyId)
            connection.setRequestProperty("X-Chain-Id", chainId)
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull() ?: JSONObject()
            if (status !in 200..299) {
                // The reason name only; the reply's message can quote the request.
                val reason = json.optString("error_reason").ifEmpty { "no reason" }
                throw LoginException("login refused: HTTP $status, $reason")
            }
            json
        } finally {
            connection.disconnect()
        }
    }
}
