package com.wirexapp.baas.example

import android.app.Activity
import android.graphics.Typeface
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.wirexapp.baas.cards.core.WirexEnvironment
import com.wirexapp.baas.cards.core.WirexEnvironmentUnavailable
import com.wirexapp.baas.cards.core.errors.WirexError
import com.wirexapp.baas.cards.reveal.WirexReveal
import com.wirexapp.baas.cards.reveal.WirexRevealField
import com.wirexapp.baas.cards.reveal.WirexRevealSession
import com.wirexapp.baas.cards.reveal.WirexRevealSnapshot
import com.wirexapp.baas.cards.reveal.WirexRevealStrings
import com.wirexapp.baas.cards.reveal.ui.WirexRevealFieldView
import kotlinx.coroutines.launch
import com.wirexapp.baas.cards.core.logging.WirexLogging

/**
 * Native Android integration of the reveal surface.
 *
 * What this screen is for:
 *
 *  - the composite rows the SDK draws (label, sealed value, its own copy button)
 *  - masked-by-default, and an explicit reveal
 *  - the partial-failure rule: when one field fails (say the CVV), its row shows a placeholder while
 *    the session stays ready, because the other fields did load
 *  - capture protection state, reported per row
 *
 * Theme and appearance moved to the Appearance tab, so one tap restyles this screen and the collect
 * form together — which is also the invariant worth watching: a restyle must never unmask a row.
 *
 * What it deliberately CANNOT do, and no amount of example code would change: read a value back.
 * There is no accessor and no copy method — the copy button is inside the SDK's own view.
 */
class RevealScreen(
    private val activity: androidx.appcompat.app.AppCompatActivity,
    private val credentials: HostCredentialProvider,
) {
    // Nullable: the SDK is (re)built lazily from the current environment + chain id, which are read at
    // CONFIGURE time — so a chain id typed on the Config tab after this screen first appeared has to
    // reconfigure, not just rebuild the session. Null while no environment is resolvable yet.
    private var reveal: WirexReveal? = null
    private var session: WirexRevealSession? = null

    private val rows = mutableMapOf<WirexRevealField, WirexRevealFieldView>()
    private lateinit var rowContainer: LinearLayout
    private lateinit var status: TextView

    private var masked = true

    /** What the live session was built from, so a re-apply can tell whether it is stale. */
    private var sessionIdentity = ""

    /** The environment + chain id the SDK was last configured with, so a change can reconfigure. */
    private var lastConfigKey = ""

    /** `cardholderName` is not sensitive and is not fetched — the host already has it. */
    private val fields = listOf(
        WirexRevealField.CARDHOLDER_NAME,
        WirexRevealField.PAN,
        WirexRevealField.EXPIRY,
        WirexRevealField.CVV,
        WirexRevealField.PIN,
    )

    /**
     * Every string the SDK draws comes from here.
     *
     * The SDK ships no locale packs on purpose: a card SDK must not invent English copy, least of all
     * copy a screen reader reads aloud. A real app would pull these from its own resources.
     */
    private val strings = WirexRevealStrings(
        fieldLabels = mapOf(
            WirexRevealField.CARDHOLDER_NAME to "Cardholder",
            WirexRevealField.PAN to "Card number",
            WirexRevealField.EXPIRY to "Expires",
            WirexRevealField.CVV to "CVV",
            WirexRevealField.PIN to "PIN",
        ),
        maskedPlaceholder = "••••",
        copy = "Copy",
        copied = "Copied",
        accessibilityShown = "shown",
        accessibilityHidden = "hidden",
        loadError = "Could not load card details",
    )

    fun build(): View {
        // The layout is built UNCONDITIONALLY — the SDK is configured lazily in
        // [configureAndCreateSession], which re-runs whenever a Config value changes. An example that
        // returned a dead "unavailable" screen when no chain id was set could never recover once the
        // chain id was typed on the Config tab, because the chain id is read at configure time.
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 32)
        }

        root.addView(Ui.heading(activity, "Reveal — the SDK draws, the host cannot read"))
        root.addView(
            Ui.caption(activity, "Values are fetched and drawn by the SDK. This app cannot read them."),
        )

        rowContainer = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        root.addView(rowContainer, Ui.matchWidth())

        status = TextView(activity).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(0, 24, 0, 24)
        }
        root.addView(status)

        root.addView(
            Ui.buttonRow(
                activity,
                "Load" to { load() },
                "Reveal" to { applyMask(false) },
                "Mask" to { applyMask(true) },
            ),
        )
        root.addView(
            Ui.buttonRow(
                activity,
                "Clear" to {
                    session?.clear()
                    session?.let { renderStatus(it.snapshot) }
                },
                "New session" to {
                    createSession()
                },
                "Decline next" to {
                    credentials.declineNextActionToken = true
                    toast("The next action-token will be declined")
                },
            ),
        )
        // Restyled from the Appearance tab, along with the collect form.
        LiveConfig.onAppearanceChanged {
            restyle()
            Ui.applyBackdrop(root, LiveConfig.isDarkMode(activity))
        }
        // A value typed on the Config tab: reconfigure the SDK (the chain id / environment are read at
        // configure time) and rebuild the session as needed. This is also what recovers the screen
        // from "unavailable" once the chain id is supplied.
        LiveConfig.onCredentialsChanged { configureAndCreateSession() }
        Ui.applyBackdrop(root, LiveConfig.isDarkMode(activity))

        configureAndCreateSession()
        return root
    }

    fun release() {
        rows.values.forEach { it.release() }
        session?.clear()
    }

    // ── Session ──────────────────────────────────────────────────────────────────

    // The environment name, resolved to the endpoints the SDK ships with. Two different failures
    // reach here and the message distinguishes them: a name outside the known set is this file's
    // mistake, and a known name the SDK has no endpoints for is a configuration issue. The chain id
    // is the host's own value (`X-Chain-Id`), supplied with the credentials; without one the SDK refuses.
    private fun environment(): WirexEnvironment =
        WirexEnvironment.resolve(LiveConfig.environment, chainId = LiveConfig.credential("chainId"))

    /**
     * (Re)configures the SDK for the current environment + chain id, then opens a session.
     *
     * The chain id and environment are read at CONFIGURE time, so a value typed on the Config tab must
     * reconfigure here — not merely rebuild the session. Recovers from "unavailable" once the chain id
     * is supplied, and avoids needless rebuilds when an unrelated credential (bearer, email) changes.
     */
    private fun configureAndCreateSession() {
        val resolved = try {
            environment()
        } catch (unavailable: WirexEnvironmentUnavailable) {
            reveal = null
            lastConfigKey = ""
            session = null
            rows.values.forEach { it.release() }
            rows.clear()
            rowContainer.removeAllViews()
            status.text = "unavailable — ${unavailable.message ?: "set the Chain ID on the Config tab, then it loads"}"
            return
        }
        val key = LiveConfig.environment + "|" + LiveConfig.credential("chainId")
        val reconfigured = reveal == null || key != lastConfigKey
        if (reconfigured) {
            reveal = WirexReveal(environment = resolved, credentials = credentials, logging = WirexLogging.DEBUG)
            lastConfigKey = key
        }
        val identity = LiveConfig.credential("cardId") + "|" + LiveConfig.credential("cardholderName")
        if (reconfigured || session == null || identity != sessionIdentity) {
            createSession()
        }
    }

    private fun createSession() {
        val sdk = reveal ?: return
        rows.values.forEach { it.release() }
        rows.clear()
        rowContainer.removeAllViews()

        // Host-supplied, never fetched: `contract/fields.json` gives this field `source: cardsList` and no
        // endpoint, and the web SDK takes it the same way.
        sessionIdentity =
            LiveConfig.credential("cardId") + "|" + LiveConfig.credential("cardholderName")
        val created = try {
            sdk.session(
                LiveConfig.credential("cardId"),
                fields,
                cardholderName = LiveConfig.credential("cardholderName"),
            )
        } catch (error: WirexError) {
            session = null
            status.text = "no session — ${error.code.name}. Paste a cardId in Credentials (JSON)."
            toast("cardId is required")
            return
        }
        created.onChange = { snapshot -> activity.runOnUiThread { renderStatus(snapshot) } }
        session = created

        val set = LiveConfig.appearance(activity)
        for (field in fields) {
            val row = WirexRevealFieldView(activity)
            row.bind(created, field, strings, set)
            row.onCopy = { copied ->
                // Only ever a boolean. The value is not in this callback and cannot be.
                toast(if (copied) "Copied ${field.wireName}" else "Copy refused")
            }
            row.onRevealRefused = {
                masked = true
                toast("Reveal refused — capture protection is not active")
                renderStatus(created.snapshot)
            }
            row.mask()
            rows[field] = row
            rowContainer.addView(row, Ui.matchWidth())
        }
        renderStatus(created.snapshot)
    }

    private fun load() {
        val active = session ?: return
        activity.lifecycleScope.launch {
            active.load()
            renderStatus(active.snapshot)
        }
    }

    private fun applyMask(next: Boolean) {
        masked = next
        rows.values.forEach { if (next) it.mask() else it.reveal() }
        session?.let { renderStatus(it.snapshot) }
    }

    /**
     * Applies the current appearance without rebinding.
     *
     * `setAppearance` is the SDK's restyle entry point and it does NOT touch mask state. Restyling
     * by re-`bind`ing resets each row to its field's `maskedByDefault`, so flipping the theme would
     * silently re-mask a revealed row.
     */
    private fun restyle() {
        val set = LiveConfig.appearance(activity)
        rows.values.forEach { it.setAppearance(set) }
        session?.let { renderStatus(it.snapshot) }
    }

    // ── Presentation ─────────────────────────────────────────────────────────────

    private fun renderStatus(snapshot: WirexRevealSnapshot) {
        val protection = rows.values.firstOrNull()?.isScreenshotProtectionActive
        status.text = buildString {
            append("state: ").append(snapshot.state)
            append("\nloaded: ").append(snapshot.loaded.map { it.wireName }.ifEmpty { listOf("—") })
            append("\nfailed: ").append(snapshot.failed.map { it.wireName }.ifEmpty { listOf("—") })
            append("\nmasked: ").append(masked)
            append("   capture-protected: ").append(protection ?: "unknown")
        }
    }

    private fun toast(message: String) =
        Toast.makeText(activity as Activity, message, Toast.LENGTH_SHORT).show()
}
