package com.wirexapp.baas.example

import android.app.Activity
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.wirexapp.baas.cards.collect.WirexBillingDetails
import com.wirexapp.baas.cards.collect.WirexCollect
import com.wirexapp.baas.cards.collect.WirexCollectField
import com.wirexapp.baas.cards.collect.WirexCollectFieldError
import com.wirexapp.baas.cards.collect.WirexCollectProfile
import com.wirexapp.baas.cards.collect.WirexCollectState
import com.wirexapp.baas.cards.collect.WirexOctTokenizeParams
import com.wirexapp.baas.cards.collect.WirexTopUpTokenizeParams
import com.wirexapp.baas.cards.collect.WirexCollectStrings
import com.wirexapp.baas.cards.collect.ui.WirexCollectForm
import com.wirexapp.baas.cards.core.surface.WirexErrorLine
import com.wirexapp.baas.cards.config.WirexSdkConfig
import com.wirexapp.baas.cards.core.WirexEnvironment
import com.wirexapp.baas.cards.core.WirexEnvironmentUnavailable
import com.wirexapp.baas.cards.core.errors.WirexError
import com.wirexapp.baas.cards.core.logging.WirexLogLevel
import com.wirexapp.baas.cards.core.logging.WirexLogging
import kotlinx.coroutines.launch

/**
 * The card-ENTRY screen: the mirror of [RevealScreen], and the same screen the iOS example shows.
 *
 * What it is here to demonstrate, in order of importance:
 *
 * 1. **The SDK hands the host no way to read what the user typed.** No API on the form or the field
 *    returns a value, and the field's own class is internal. The boundary is the public API, not the
 *    widget tree.
 * 2. **Live state without content.** Every keystroke reports validity, touched-ness and the scheme.
 *    That is all a real Continue button needs.
 * 3. **Tokenisation.** The entered card goes straight to the card service and what comes back is an
 *    opaque id plus BIN and last four — out of PCI scope by construction.
 *
 * Credentials come from [LiveConfig], shared with the reveal screen: paste a bearer once and both
 * screens use it.
 */
class CollectScreen(
    private val activity: AppCompatActivity,
    private val credentials: HostCredentialProvider,
) {
    private var collect: WirexCollect? = null
    private var form: WirexCollectForm? = null
    private var profile: WirexCollectProfile = WirexCollectProfile.TOPUP

    /** Which of the three error-line policies the form is using. Switchable, to see the difference. */
    private var errorLine: WirexErrorLine = WirexErrorLine.ANIMATED

    /**
     * Every string the form draws, read from the JSON document below.
     *
     * A partner integrating this has its own translation store, so the SDK takes a document rather
     * than a table of Kotlin constants — the same shape the appearance panel takes, and the same
     * reader the React Native and Flutter bindings will call.
     */
    private var strings: WirexCollectStrings = WirexCollectStrings.fromJson(DEFAULT_STRINGS_JSON)
        ?: WirexCollectStrings()

    private lateinit var stringsField: EditText

    private lateinit var formHolder: FrameLayout

    /** Where the two rows this screen lays out itself go — see [placeExpiryAndCvvSideBySide]. */
    private lateinit var sideBySideHolder: FrameLayout
    private lateinit var stateLabel: TextView
    private lateinit var resultLabel: TextView
    private lateinit var tokenizeButton: android.widget.Button
    private lateinit var profileLabel: TextView

    fun build(): View {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 32)
        }

        root.addView(Ui.heading(activity, "Collect — the user types, the host cannot read"))
        root.addView(
            Ui.caption(
                activity,
                "Every row below is the SAME component reveal draws. Only the value area differs: " +
                    "reveal fills it and seals it, collect leaves it empty and takes keystrokes.",
            ),
        )

        profileLabel = Ui.mono(activity)
        root.addView(
            Ui.buttonRow(
                activity,
                "topup" to { setProfile(WirexCollectProfile.TOPUP) },
                "oct-destination" to { setProfile(WirexCollectProfile.OCT_DESTINATION) },
            ),
        )
        root.addView(profileLabel)

        root.addView(
            Ui.buttonRow(
                activity,
                "err: animated" to { setErrorLine(WirexErrorLine.ANIMATED) },
                "reserved" to { setErrorLine(WirexErrorLine.RESERVED) },
                "hidden" to { setErrorLine(WirexErrorLine.HIDDEN) },
            ),
        )
        root.addView(
            Ui.caption(
                activity,
                "ANIMATED keeps the form compact and slides a message in — everything below it moves. " +
                    "RESERVED keeps a line's height always, so nothing ever moves and the form is " +
                    "taller. HIDDEN draws none, for a host rendering its own from onChange.",
            ),
        )

        formHolder = FrameLayout(activity)
        root.addView(formHolder, Ui.matchWidth())
        sideBySideHolder = FrameLayout(activity)
        root.addView(sideBySideHolder, Ui.matchWidth())

        stateLabel = Ui.mono(activity).apply { setPadding(0, 24, 0, 8) }
        root.addView(stateLabel)

        tokenizeButton = android.widget.Button(activity).apply {
            text = "Tokenize"
            textSize = 11f
            setOnClickListener { tokenize() }
        }
        root.addView(
            LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(tokenizeButton)
                addView(
                    android.widget.Button(activity).apply {
                        text = "Clear"
                        textSize = 11f
                        setOnClickListener {
                            form?.clear()
                            resultLabel.text = "—"
                        }
                    },
                )
            },
        )

        resultLabel = Ui.mono(activity).apply {
            setPadding(0, 16, 0, 0)
            text = "—"
        }
        root.addView(resultLabel)
        root.addView(buildTestCardsPanel())
        // LAST, below everything. It is a tall editor for a thing most runs never touch, and
        // anywhere higher it pushes the form — the actual subject of the screen — off the first
        // screenful.
        root.addView(buildStringsPanel())

        // A restyle, not a rebuild. Rebuilding would drop what the user has typed, which is the one
        // thing an appearance change must never do.
        LiveConfig.onAppearanceChanged {
            form?.apply(LiveConfig.appearance(activity))
            Ui.applyBackdrop(root, LiveConfig.isDarkMode(activity))
        }
        Ui.applyBackdrop(root, LiveConfig.isDarkMode(activity))

        rebuildForm()
        return root
    }

    // ── The SDK ─────────────────────────────────────────────────────────────────

    private fun rebuildForm() {
        // A fresh instance per rebuild, because the environment name and the credentials can change
        // between rebuilds. The same reason the reveal screen rebuilds its session on Apply.
        val environment = try {
            WirexEnvironment.resolve(WirexSdkConfig.ENVIRONMENT, chainId = LiveConfig.credential("chainId"))
        } catch (unavailable: WirexEnvironmentUnavailable) {
            // Shown rather than swallowed: the two reasons are fixed in different places, and the
            // message already distinguishes them.
            resultLabel.text = unavailable.message
            return
        }
        val collect = WirexCollect(
            environment = environment,
            credentials = credentials,
            logging = WirexLogging(WirexLogLevel.INFO, ConsoleLogSink),
        )
        this.collect = collect

        formHolder.removeAllViews()
        val built = collect.form(
            context = activity,
            profile = profile,
            appearance = LiveConfig.appearance(activity),
            // Every string the form draws, supplied by the host. The SDK ships no locale packs, so
            // this is the integration a partner actually performs — the defaults exist only so a
            // form that supplies nothing still renders.
            strings = strings,
        )
        built.errorLine = errorLine
        built.onChange = { state -> render(state) }
        // Moving between fields while the keyboard is already up fires no inset pass, so this is the
        // only thing that tells the screen to scroll the new field into view.
        built.onFocusedFieldChanged = { _ ->
            activity.currentFocus?.let(Ui::revealFocusedField)
        }
        formHolder.addView(
            built,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        form = built
        placeExpiryAndCvvSideBySide(built)
        profileLabel.text = "profile: ${profile.wireName} — fields: " +
            profile.fields.joinToString(", ") { it.wireName }
        render(built.state)
    }

    /**
     * Per-row placement: the host decides where a field goes.
     *
     * `row(field)` hands back the live view the form draws for that field, and moving it into the
     * host's own layout is supported — the form goes on owning it, so typing, validation and
     * `onChange` are unaffected. Here expiry and CVV are put on one line, which is the layout a card
     * form usually wants and which the SDK deliberately does not decide.
     *
     * The form itself stays in the hierarchy: it is what puts `FLAG_SECURE` on the window when it
     * attaches, and it keeps every row it handed out in its model — a field left unplaced is still a
     * field the user has to fill before the form reports complete.
     */
    private fun placeExpiryAndCvvSideBySide(built: WirexCollectForm) {
        // `oct-destination` has no CVV: the accessor answers null for a field the profile lacks, and
        // there is nothing to pair.
        val expiry = built.row(WirexCollectField.EXPIRY) ?: return
        val cvv = built.row(WirexCollectField.CVV) ?: return

        (expiry.parent as? ViewGroup)?.removeView(expiry)
        (cvv.parent as? ViewGroup)?.removeView(cvv)

        val pair = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(expiry, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(cvv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        sideBySideHolder.removeAllViews()
        sideBySideHolder.addView(pair, Ui.matchWidth())
    }

    /** Switches the policy on the LIVE form — no rebuild, so what is typed survives. */
    private fun setErrorLine(next: WirexErrorLine) {
        errorLine = next
        form?.errorLine = next
        toast("error line: ${next.name.lowercase()}")
    }

    /**
     * The wording document, editable live.
     *
     * Rebuilds the form, because labels and placeholders are read when a row is built — which also
     * clears what is typed. That is honest: a host changing its LOCALE mid-form is not a case worth
     * pretending about, and the SDK's own restyle path (the Appearance tab) is the one that must
     * preserve input.
     */
    private fun buildStringsPanel(): View {
        stringsField = Ui.jsonField(activity, DEFAULT_STRINGS_JSON)
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                Ui.caption(
                    activity,
                    "labels · placeholders · errors[field|\"*\"][incomplete|invalid|expired]. " +
                        "A null suppresses that message; an absent key falls through to the SDK's " +
                        "own English. Shape and precedence are fixed by the SDK.",
                ),
            )
            addView(stringsField, Ui.matchWidth())
            addView(
                Ui.buttonRow(
                    activity,
                    "Apply strings" to {
                        val parsed = WirexCollectStrings.fromJson(stringsField.text.toString())
                        if (parsed == null) {
                            toast("strings: not valid JSON")
                        } else {
                            strings = parsed
                            rebuildForm()
                            toast("strings applied — the form was rebuilt")
                        }
                    },
                    "Reset" to {
                        stringsField.setText(DEFAULT_STRINGS_JSON)
                        strings = WirexCollectStrings.fromJson(DEFAULT_STRINGS_JSON)
                            ?: WirexCollectStrings()
                        rebuildForm()
                    },
                ),
            )
        }
        return Ui.panel(activity, "Strings (JSON)", body)
    }

    private fun setProfile(next: WirexCollectProfile) {
        if (profile == next) return
        profile = next
        resultLabel.text = "—"
        rebuildForm()
    }

    private fun render(state: WirexCollectState) {
        val perField = state.fields.joinToString("\n") { field ->
            val mark = if (field.isValid) "ok" else if (field.isEmpty) "empty" else "invalid"
            val touched = if (field.isTouched) "" else " (untouched)"
            "${field.field.wireName}: $mark$touched"
        }
        stateLabel.text = buildString {
            append("complete: ").append(state.isComplete)
            append("   capture-protected: ").append(form?.isScreenshotProtectionActive ?: "unknown")
            append("\nbrand: ").append(state.brand.wireValue)
            append("\n").append(perField)
        }
        val enabled = state.isComplete && form?.isSubmitting != true
        tokenizeButton.isEnabled = enabled
        tokenizeButton.alpha = if (enabled) 1f else 0.4f
    }

    // ── Actions ─────────────────────────────────────────────────────────────────

    private fun tokenize() {
        val active = form ?: return
        resultLabel.text = "submitting…"
        tokenizeButton.isEnabled = false

        activity.lifecycleScope.launch {
            try {
                when (profile) {
                    WirexCollectProfile.TOPUP -> {
                        // A real host collects these in its own UI: a billing address is not card data
                        // and is outside the sealed surface on purpose.
                        val token = active.tokenizeTopUp(
                            WirexTopUpTokenizeParams(
                                billingDetails = WirexBillingDetails(
                                    line1 = "1 Example Street",
                                    zipCode = "SW1A 1AA",
                                    city = "London",
                                    country = "GB",
                                ),
                            ),
                        )
                        resultLabel.text = buildString {
                            append("linked\ncardId: ").append(token.cardId)
                            append("\n").append(token.brand.wireValue)
                            append(" •••• ").append(token.last4)
                            append("  bin ").append(token.bin)
                            append("\nexpiry: ").append(token.expiryMonth).append("/").append(token.expiryYear)
                        }
                    }
                    WirexCollectProfile.OCT_DESTINATION -> {
                        val token = active.tokenizeOctDestination(
                            WirexOctTokenizeParams(
                                cardLabel = "Example recipient",
                                isSaved = false,
                                isThirdParty = true,
                            ),
                        )
                        resultLabel.text = buildString {
                            append("registered\nexternalCardId: ").append(token.externalCardId)
                            append("\n").append(token.brand.wireValue)
                            append(" •••• ").append(token.last4)
                            append("  bin ").append(token.bin)
                        }
                    }
                }
            } catch (error: WirexError) {
                // The WHOLE error, not `code` and `retryable` picked out of it.
                //
                // `detail` is opt-in internal — a host cannot branch on it, deliberately — but the
                // exception message carries it, and that is the difference between "failed" and "the
                // service rejected the request" (`BAD_REQUEST`) or "the connection died"
                // (`TRANSPORT`). Printing two fields threw away the one that says which, and a
                // screen that reports a failure without saying what kind sends whoever is
                // integrating to a packet capture.
                resultLabel.text = "failed: ${error.message}"
            } catch (error: Throwable) {
                resultLabel.text = "failed"
            }
            render(active.state)
        }
    }


    /**
     * Test cards, so the screen is usable without hunting for a valid number.
     *
     * Pasted rather than typed by the app: there is no API to set a field's value, which is the point.
     * A long-press paste into the number field is how these are used.
     *
     * SEVERAL of them, and the caption says why: the card service links a number to an account ONCE.
     * A second attempt with the same number is a 400 — `detail: BAD_REQUEST` — and reads exactly like
     * a broken integration. With one number on the panel, the first person to run the example twice
     * concludes the SDK is broken; `4242 4242 4242 4242` in particular is the number everyone
     * reaches for, so on any account that has seen a demo it is already spent.
     */
    private fun buildTestCardsPanel(): View {
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                Ui.caption(
                    activity,
                    "Copy one, then long-press the card number field and paste. Pasting goes through " +
                        "the same filter a keystroke does — separators are dropped and an over-long " +
                        "paste is truncated.",
                ),
            )
            for (number in TEST_CARD_NUMBERS) {
                addView(
                    EditText(activity).apply {
                        setText(number)
                        textSize = 12f
                        inputType =
                            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    },
                    Ui.matchWidth(),
                )
            }
            addView(
                Ui.caption(
                    activity,
                    "Visa and Mastercard test numbers. Expiry: any future MM/YY. CVV: any 3 digits.\n\n" +
                        "ONE LINK PER NUMBER PER ACCOUNT. Tokenising a number this account has " +
                        "already linked fails with detail BAD_REQUEST — the service says the card " +
                        "belongs to another account, and the SDK does not pass backend text through. " +
                        "Move to the next number rather than debugging the failure.",
                ),
            )
        }
        return Ui.panel(activity, "Test cards", body)
    }

    private fun toast(message: String) =
        Toast.makeText(activity as Activity, message, Toast.LENGTH_SHORT).show()

    private companion object {
        /**
         * Enough numbers that running the example a few times does not exhaust them.
         *
         * All standard scheme test numbers, so they pass the Luhn check the number field runs and
         * detect as the brand shown beside the field.
         */
        private val TEST_CARD_NUMBERS = listOf(
            "4242 4242 4242 4242",
            "4111 1111 1111 1111",
            "4000 0566 5566 5556",
            "5555 5555 5555 4444",
        )

        /**
         * Deliberately NOT the SDK's own wording, so the screen proves the hook is live rather than
         * that it exists. The wildcard row is the shape most hosts want: one sentence per reason.
         */
        private val DEFAULT_STRINGS_JSON = """
            {
              "labels": {
                "number": "Card number", "expiry": "Expires", "cvv": "CVV", "name": "Cardholder"
              },
              "errors": {
                "*": {
                  "incomplete": "Finish this field — it is incomplete",
                  "invalid": "That does not look right",
                  "expired": "That card has expired"
                },
                "number": { "invalid": "Check the card number and try again" }
              }
            }
        """.trimIndent()
    }
}
