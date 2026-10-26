package com.wirexapp.baas.example

import com.wirexapp.baas.cards.core.appearance.WirexAppearance
import com.wirexapp.baas.cards.core.appearance.WirexAppearanceTokens
import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import com.wirexapp.baas.cards.core.appearance.WirexThemeMode

/** Outcome of merging a pasted document. */
sealed interface ConfigOutcome {
    data class Merged(val summary: String) : ConfigOutcome
    data class Rejected(val reason: String) : ConfigOutcome
}

/**
 * Everything the example needs at runtime that a rebuild would otherwise be required to change.
 *
 * Seeded from launch extras, then overridden from JSON pasted into the app. **Merge, not replace**: a
 * key absent from the pasted document keeps its previous value, so when one credential expires you
 * paste a document containing only that one. That is the whole reason this exists — `action_token`
 * lives 300 seconds, and rebuilding for each is not a workflow.
 *
 * SECURITY: in memory only. Nothing here reaches SharedPreferences, MMKV, a file or the keystore —
 * the SDK makes that promise about card values, and an example that persisted a bearer would teach
 * the opposite. Values die with the process.
 *
 * The same keys every platform's example holds.
 */
object LiveConfig {
    private val credentials = mutableMapOf<String, String>()
    /**
     * ONE token map, because that is the contract.
     *
     * The SDK is handed one appearance and resolves no theme. A host that wants a dark palette
     * supplies a different document — so holding two maps here and picking between them was this
     * example modelling a layering the SDK does not do, and it made a partial dark map look
     * workable when nothing fills its gaps.
     */
    private val appearanceTokens = mutableMapOf<String, Any>()

    private val lock = Any()

    private val KNOWN = listOf(
        "environment", "bearer", "email", "wallet", "cardId", "chainId", "actionToken", "cardholderName", "baasApiUrl",
        // Screen-capture protection (`FLAG_SECURE`). Only `"true"` disables it; unset / anything else keeps
        // it ON — the secure default. Set it from the Config tab or a seed link to let QA capture the
        // reveal / collect surfaces for a test.
        "disableScreenProtection",
        // Per-surface inputs a seed link may carry, so a QA device needs no typing at all: the 3DS tab's
        // orchestrator URL and the Wallet tab's checkout token / estimation id / provider. Each tab
        // prefills its own field from these; the field stays editable.
        "threeDsUrl", "checkoutToken", "estimationId", "walletProvider",
    )

    /**
     * Whether to pass `dangerouslyDisableScreenProtection = true` to the SDK. Default false (protection
     * ON); `disableScreenProtection=true` lets QA screenshot the surfaces. Never ship it on.
     */
    val isScreenProtectionDisabled: Boolean
        get() = credential("disableScreenProtection") == "true"

    /** Called once from the activity with whatever came in on the launch intent. */
    fun seed(values: Map<String, String>) = synchronized(lock) {
        credentials.putAll(values.filterValues { it.isNotEmpty() })
    }

    fun credential(key: String): String = synchronized(lock) { credentials[key] ?: "" }

    /**
     * Writes a single key — the per-field Config tab's edit. An EMPTY value CLEARS the key, matching
     * [applyCredentials]. `notify` is false while a field is mid-edit (every keystroke) and true when
     * it loses focus, so a screen rebuilds once on blur rather than on every character.
     */
    fun setCredential(key: String, value: String, notify: Boolean = true) {
        synchronized(lock) {
            if (value.isEmpty()) credentials.remove(key) else credentials[key] = value
        }
        if (notify) notifyCredentialsChanged()
    }

    /** The SDK environment NAME the Config tab selected, defaulting to `test`. A name, never a URL. */
    val environment: String
        get() = credential("environment").ifEmpty { "test" }

    /** `test` everywhere but the production name — mirrors `exampleConfig.ts`. */
    val isTestMode: Boolean
        get() = environment != "production"

    /**
     * Applies a `wirexqa://seed?d=<base64url(JSON)>` link — the deep link the example registers — from
     * the launch intent or from text pasted into the Config tab. Returns how many values were set, or
     * null when the text is not a seed link (other scheme or host, no `d`, undecodable). Any key is
     * applied, the bearer and action token included; the RN example names identity `userWallet` /
     * `userEmail`, which map to this example's `wallet` / `email`.
     */
    fun applySeedLink(link: String): Int? {
        val uri = runCatching { Uri.parse(link.trim()) }.getOrNull() ?: return null
        if (uri.scheme != "wirexqa" || uri.host != "seed") return null
        val d = uri.getQueryParameter("d")?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            val json = JSONObject(String(Base64.decode(d, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)))
            var applied = 0
            for (rawKey in json.keys()) {
                val value = json.optString(rawKey)
                if (value.isEmpty()) continue
                setCredential(SEED_ALIASES[rawKey] ?: rawKey, value, notify = false)
                applied++
            }
            notifyCredentialsChanged()
            applied
        } catch (_: Exception) {
            null
        }
    }

    private val SEED_ALIASES = mapOf("userWallet" to "wallet", "userEmail" to "email")

    /** Clears every credential / identity value back to empty (environment falls back to `test`). */
    fun resetCredentials() {
        synchronized(lock) { credentials.clear() }
        notifyCredentialsChanged()
    }

    fun applyCredentials(json: String): ConfigOutcome {
        val parsed = parse(json) ?: return ConfigOutcome.Rejected("not valid JSON")
        val applied = mutableListOf<String>()
        val cleared = mutableListOf<String>()
        val unknown = mutableListOf<String>()

        synchronized(lock) {
            for (key in parsed.keys()) {
                if (key !in KNOWN) { unknown += key; continue }
                val value = parsed.optString(key)
                // An EMPTY string clears the key — see LiveConfig.swift for why this is not the
                // same as omitting it.
                if (value.isEmpty()) {
                    credentials.remove(key)
                    cleared += key
                    continue
                }
                credentials[key] = value
                applied += key
            }
        }

        if (applied.isEmpty() && cleared.isEmpty() && unknown.isEmpty()) {
            return ConfigOutcome.Rejected("no recognised keys")
        }
        var summary = "merged: ${applied.sorted().joinToString(", ")}"
        if (cleared.isNotEmpty()) summary += " · CLEARED: ${cleared.sorted().joinToString(", ")}"
        // Named rather than ignored: a typo in a key would otherwise look like a successful paste.
        if (unknown.isNotEmpty()) summary += " · IGNORED unknown: ${unknown.sorted().joinToString(", ")}"
        return ConfigOutcome.Merged(summary)
    }

    /**
     * Per-key presence, and for JWTs whether they are still valid. The expiry check is the point: an
     * expired `action_token` and a missing one fail identically at the API, and knowing which is the
     * difference between pasting a new token and hunting a bug.
     */
    fun credentialsStatus(): String =
        listOf("bearer", "actionToken", "cardId", "chainId", "cardholderName", "email", "wallet").joinToString("\n") { key ->
            val value = credential(key)
            when {
                value.isEmpty() -> "$key: —"
                else -> {
                    val expiry = jwtExpirySeconds(value)
                    if (expiry == null) {
                        "$key: set"
                    } else {
                        val remaining = expiry - System.currentTimeMillis() / 1000
                        if (remaining > 0) "$key: valid ${remaining}s" else "$key: EXPIRED ${-remaining}s ago"
                    }
                }
            }
        }

    fun applyAppearance(json: String): ConfigOutcome {
        val parsed = parse(json) ?: return ConfigOutcome.Rejected("not valid JSON")
        synchronized(lock) {
            for (key in parsed.keys()) appearanceTokens[key] = parsed.get(key)
        }
        notifyAppearanceChanged()
        return ConfigOutcome.Merged("merged: ${parsed.keys().asSequence().sorted().joinToString(", ")}")
    }

    fun resetAppearance() {
        synchronized(lock) {
            appearanceTokens.clear()
        }
        notifyAppearanceChanged()
    }

    /**
     * The HOST picks; the SDK draws what it is given.
     *
     * One document, handed over as-is. Which document that is, is the host's decision — this example
     * swaps it when the theme changes, in `AppearanceScreen`, because that is where a host's design
     * system would do it. Noticing the device flipped belongs to the application, not to a card SDK.
     */
    fun appearance(context: android.content.Context): WirexAppearance {
        val dark = isDarkMode(context)
        val tokens = synchronized(lock) { appearanceTokens.toMap() }
        // Through the SDK's own reader rather than a copy of it. `WirexAppearanceTokens` is public in
        // core precisely so a host with a JSON token map does not write this mapping again, and so
        // that what a numerically-typed token holding a string means is decided in one place.
        return WirexAppearanceTokens.appearance(
            tokens = tokens,
            mode = if (dark) WirexThemeMode.DARK else WirexThemeMode.LIGHT,
        )
    }

    /** Replaces the map outright. What a preset button does — a merge would leave the last one on. */
    fun setAppearance(tokens: Map<String, Any>) {
        synchronized(lock) {
            appearanceTokens.clear()
            appearanceTokens.putAll(tokens)
        }
        notifyAppearanceChanged()
    }

    // ── Change broadcast ────────────────────────────────────────────────────────

    private val listeners = mutableListOf<() -> Unit>()

    /**
     * Notified whenever the appearance or the theme selection changes.
     *
     * The Appearance tab restyles BOTH surfaces, which is the invariant worth watching: a restyle
     * must never clear the collect form or unmask a reveal row.
     */
    fun onAppearanceChanged(listener: () -> Unit) = synchronized(lock) { listeners += listener }

    private val credentialsListeners = mutableListOf<() -> Unit>()

    /**
     * Notified whenever a credential / identity value changes, so a screen can rebuild. Separate from
     * [onAppearanceChanged]: a credential change may have to rebuild a session or a form, an
     * appearance change must never do either.
     */
    fun onCredentialsChanged(listener: () -> Unit) = synchronized(lock) { credentialsListeners += listener }

    fun notifyCredentialsChanged() {
        val snapshot = synchronized(lock) { credentialsListeners.toList() }
        snapshot.forEach { it() }
    }

    /** The host's light / dark / system choice, shared by every screen. */
    /**
     * Whether the DEVICE is dark. Nothing else decides.
     *
     * The example used to carry its own LIGHT/DARK/SYSTEM selection, and the Appearance tab had three
     * buttons for it. That put a theme vocabulary on screen the SDK does not have: it resolves no
     * theme and holds no palette — it draws the one complete document it is handed. A control the SDK
     * cannot honour teaches a partner the wrong contract, so the OS decides and the example swaps the
     * document.
     */
    fun isDarkMode(context: android.content.Context): Boolean =
        (context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    fun notifyAppearanceChanged() {
        val snapshot = synchronized(lock) { listeners.toList() }
        snapshot.forEach { it() }
    }

    private fun parse(json: String): JSONObject? =
        runCatching { JSONObject(json.trim()) }.getOrNull()

    /**
     * `exp` from an unverified JWT payload. The signature is NOT checked and must not be: this is a
     * convenience for the operator, and the server is the only thing entitled to trust a token.
     *
     * `android.util.Base64`, not `java.util.Base64` — the latter is API 26+ and this example builds
     * against the SDK's API 23 floor.
     */
    private fun jwtExpirySeconds(token: String): Long? {
        val segments = token.split('.')
        if (segments.size != 3) return null
        return runCatching {
            val decoded = android.util.Base64.decode(
                segments[1],
                android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP,
            )
            JSONObject(String(decoded)).optLong("exp").takeIf { it > 0 }
        }.getOrNull()
    }
}
