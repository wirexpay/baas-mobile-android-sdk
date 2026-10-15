package com.wirexapp.baas.example

import com.wirexapp.baas.cards.core.WirexCredentialProvider
import com.wirexapp.baas.cards.core.WirexActionScope
import com.wirexapp.baas.cards.core.WirexDeclined
import com.wirexapp.baas.cards.core.errors.WirexError

/**
 * What a partner application has to implement.
 *
 * This is the entire integration contract for authentication: the SDK never manages a user session,
 * so it asks the host for a token whenever it needs one. A real app returns the token it already
 * holds and runs whatever action-token it uses — biometrics, a PIN sheet, an MFA prompt.
 *
 * Two things worth copying:
 *
 *  - `actionToken` is called once per SCOPE per reveal, not per HTTP request, matching the web
 *    SDK's model. PAN + expiry + CVV asks once; adding PIN asks again.
 *  - A user declining is signalled by throwing `WirexDeclined`, which the SDK provides for exactly
 *    this. A bare `CancellationException` means something else: the coroutine is being torn down.
 *    The latter means the coroutine itself is being torn down and is propagated rather than
 *    reported; getting this wrong makes a declined prompt look like a crash.
 */
class HostCredentialProvider : WirexCredentialProvider {

    // Read through LiveConfig, so a value pasted into the app takes effect without a rebuild. Must
    // match the token's user: the backend receives these as `X-User-Email` / `X-User-Wallet`
    // alongside the bearer, so a mismatch fails for a reason unrelated to the token.
    override val userEmail: String? get() = LiveConfig.credential("email").ifEmpty { null }
    override val userWallet: String? get() = LiveConfig.credential("wallet").ifEmpty { null }

    /** Set from the UI to exercise the decline path. */
    @Volatile
    var declineNextActionToken: Boolean = false

    override suspend fun accessToken(): String = LiveConfig.credential("bearer")

    override suspend fun actionToken(scope: WirexActionScope): String {
        if (declineNextActionToken) {
            declineNextActionToken = false
            throw WirexDeclined()
        }
        // `scope` distinguishes the PIN so a stronger prompt could be demanded; the backend has one
        // `GetCardDetails` action type today, so both scopes mint the same way.
        @Suppress("UNUSED_EXPRESSION") scope

        // The token the host holds. A real application returns whatever its own flow produced; this
        // one returns what was pasted or passed at launch.
        //
        // What this deliberately does NOT show is how to obtain one. That is the host's flow against
        // the host's backend, and an example that picked one would be teaching a choice the SDK does
        // not make.
        val supplied = LiveConfig.credential("actionToken")
        if (supplied.isEmpty()) {
            // No token, reported as the SDK's "the user must do something" rather than as a crash.
            // A host whose own flow failed reports the same thing.
            throw WirexError(WirexError.Code.ACTION_REQUIRED, retryable = true)
        }
        return supplied
    }
}
