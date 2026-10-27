package com.wirexapp.baas.example

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.widget.SwitchCompat
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * The Config (debug) tab: a per-field form over [LiveConfig], so QA types every integration parameter
 * and credential straight into the running build — no rebuild, no source edit, no pasted JSON.
 *
 * Mirrors the React Native example's Config screen: the same field contract (PARTNER-CONFIG.md), the
 * same two tiers (non-secret integration parameters, memory-only secrets) and the same dark palette.
 * Every surface reads [LiveConfig] live, so a value typed here takes effect on the next call; a change
 * to a value a session captured (the card id) rebuilds that session through [LiveConfig.onCredentialsChanged].
 */
class ConfigScreen(private val activity: AppCompatActivity) {

    private val fields = mutableListOf<Pair<String, EditText>>()
    private val environmentChips = mutableListOf<TextView>()
    private lateinit var status: TextView

    private data class FieldSpec(val key: String, val label: String, val hint: String?, val secure: Boolean)

    private val integration = listOf(
        FieldSpec("chainId", "Chain ID", "X-Chain-Id — Wirex assigns per integration", false),
        FieldSpec("email", "User email", "X-User-Email", false),
        FieldSpec("wallet", "User wallet", "X-User-Wallet (EOA)", false),
        FieldSpec("cardId", "Card ID", "the card Reveal / Collect act on", false),
        FieldSpec("cardholderName", "Cardholder name", null, false),
        FieldSpec("baasApiUrl", "BaaS API URL", "for the Wallet “Fetch estimate” helper", false),
    )
    private val secrets = listOf(
        FieldSpec("bearer", "Bearer token", "getAccessToken — Wirex bearer for the user", true),
        FieldSpec("actionToken", "Step-up action token", "getActionToken — card data / PIN", true),
    )

    fun build(): View {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(40))
        }

        root.addView(title("Configuration"))
        root.addView(caption("Type a partner's parameters here; every screen reads them live. Secrets stay in memory only."))

        root.addView(section("Seed link"))
        root.addView(seedLinkCard())

        root.addView(section("Environment"))
        root.addView(environmentRow())

        root.addView(section("Integration parameters"))
        root.addView(group(integration))

        root.addView(section("Secrets · memory only"))
        root.addView(group(secrets))

        root.addView(section("Screen capture"))
        root.addView(screenCaptureCard())

        root.addView(section("Credentials status"))
        status = TextView(activity).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(TEXT_DIM)
            text = LiveConfig.credentialsStatus()
        }
        root.addView(card(listOf(status, pill("Refresh status", TEAL, SURFACE_ALT, BORDER) { DiagLog.tap("Refresh status"); refreshStatus() })))

        root.addView(
            pill("Reset all", PINK, PINK and 0x22FFFFFF.toInt(), PINK) { DiagLog.tap("Reset all"); resetAll() }.apply {
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(26)
                layoutParams = lp
            },
        )

        root.addView(section("Diagnostic log"))
        root.addView(diagnosticLogCard())

        // A seed deep link (or any other code path) can change a value while this tab is built. Pull
        // the new values into the fields and the status line, skipping the field being edited.
        LiveConfig.onCredentialsChanged { activity.runOnUiThread { syncFields() } }

        return Ui.scroller(activity, root) { activity.currentFocus }.apply { setBackgroundColor(BG) }
    }

    private fun syncFields() {
        for ((key, input) in fields) {
            if (input.hasFocus()) continue
            val value = LiveConfig.credential(key)
            if (input.text.toString() != value) input.setText(value)
        }
        syncEnvironmentChips()
        captureSwitch.isChecked = !LiveConfig.isScreenProtectionDisabled
        refreshStatus()
    }

    // ── Rows ──────────────────────────────────────────────────────────────────────

    /**
     * The same `wirexqa://seed?d=…` link the app opens as a deep link, pasted instead — for a device
     * where opening the link is awkward (a chat that strips the scheme, a locked-down browser).
     */
    private fun seedLinkCard(): View {
        val input = EditText(activity).apply {
            setTextColor(TEXT)
            setHintTextColor(TEXT_FAINT)
            textSize = 15f
            background = rounded(SURFACE_ALT, BORDER, 10)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = true
            hint = "wirexqa://seed?d=…"
            setOnFocusChangeListener { v, hasFocus -> if (hasFocus) Ui.revealFocusedField(v) }
        }
        val status = hint("Paste the link the QA kit prints (the same one the app opens as a deep link); Apply fills every field below.")
        val apply = pill("Apply", ON_ACCENT, ACCENT, ACCENT) {
            DiagLog.tap("Apply seed link")
            when (val outcome = LiveConfig.applySeedLink(input.text.toString(), source = "paste")) {
                is SeedOutcome.Applied -> {
                    status.text = "Applied ${outcome.count} value${if (outcome.count == 1) "" else "s"}"
                    status.setTextColor(TEAL)
                    input.setText("")
                }
                is SeedOutcome.Rejected -> {
                    status.text = "Not a wirexqa://seed link — ${outcome.reason}"
                    status.setTextColor(PINK)
                }
            }
            input.clearFocus()
            syncFields()
        }
        val applyRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(apply, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        return card(listOf(input, applyRow, status))
    }

    private fun environmentRow(): View {
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        for (name in listOf("test", "production")) {
            val chip = TextView(activity).apply {
                text = name
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(dp(20), dp(10), dp(20), dp(10))
                setOnClickListener {
                    DiagLog.tap("environment chip '$name'")
                    LiveConfig.setCredential("environment", name)
                    DiagLog.config("environment switched to $name")
                    syncEnvironmentChips()
                }
            }
            environmentChips += chip
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.rightMargin = dp(10)
            row.addView(chip, lp)
        }
        syncEnvironmentChips()
        return row
    }

    private fun syncEnvironmentChips() {
        val current = LiveConfig.environment
        for (chip in environmentChips) {
            val on = chip.text == current
            chip.background = rounded(if (on) ACCENT else SURFACE, if (on) ACCENT else BORDER, 999)
            chip.setTextColor(if (on) ON_ACCENT else TEXT_DIM)
        }
    }

    private lateinit var captureSwitch: SwitchCompat

    /**
     * DEV/TEST ONLY. Off = `dangerouslyDisableScreenProtection = true` on Reveal, Collect and 3DS, so QA can
     * screenshot the surfaces (they are black under FLAG_SECURE otherwise). A seed link can set the
     * same key (`disableScreenProtection`). Never ship a build with it off to cardholders.
     */
    private fun screenCaptureCard(): View {
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val texts = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("Screen-capture protection (FLAG_SECURE)"))
            addView(
                hint(
                    "On — card data is HIDDEN from screenshots / screen-recording on Reveal, Collect & 3DS (the secure default).\n" +
                        "Off — data is VISIBLE so QA can capture it for a test.",
                ),
            )
        }
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        captureSwitch = SwitchCompat(activity).apply {
            isChecked = !LiveConfig.isScreenProtectionDisabled
            setOnCheckedChangeListener { _, protect ->
                DiagLog.config("screen-capture protection ${if (protect) "on" else "off"}")
                LiveConfig.setCredential("disableScreenProtection", if (protect) "" else "true")
            }
        }
        row.addView(captureSwitch, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(12) })
        return card(listOf(row))
    }

    private fun group(specs: List<FieldSpec>): View {
        val views = mutableListOf<View>()
        for (spec in specs) {
            val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            column.addView(label(spec.label))
            spec.hint?.let { column.addView(hint(it)) }

            val input = EditText(activity).apply {
                setText(LiveConfig.credential(spec.key))
                setTextColor(TEXT)
                setHintTextColor(TEXT_FAINT)
                textSize = 15f
                background = rounded(SURFACE_ALT, BORDER, 10)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                inputType = if (spec.secure) {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                } else {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                }
                hint = if (spec.secure) "paste…" else ""
                // Mid-edit: keep the store live without posting, so screens do not rebuild per keystroke.
                addTextChangedListener(object : TextWatcher {
                    override fun afterTextChanged(s: Editable?) {
                        LiveConfig.setCredential(spec.key, s?.toString().orEmpty(), notify = false)
                    }
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                })
                // On blur: post the change, so a screen that captured the value (the card id) rebuilds.
                setOnFocusChangeListener { v, hasFocus ->
                    if (hasFocus) {
                        Ui.revealFocusedField(v)
                    } else {
                        LiveConfig.setCredential(spec.key, (v as EditText).text.toString(), notify = true)
                        refreshStatus()
                    }
                }
            }
            fields += spec.key to input
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(4)
            column.addView(input, lp)
            views += column
        }
        return card(views)
    }

    /**
     * Everything the example and the SDK reported this run, live, with a share sheet — so a bug
     * report carries what happened rather than a description of it. See [DiagLog] for what is
     * deliberately NOT in it.
     */
    private fun diagnosticLogCard(): View {
        val lines = TextView(activity).apply {
            typeface = Typeface.MONOSPACE
            textSize = 10f
            setTextColor(TEXT_DIM)
        }
        val scroll = ScrollView(activity).apply {
            background = rounded(SURFACE_ALT, BORDER, 10)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            addView(lines, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            // A scroller inside the tab's scroller: keep a drag that starts here, so the log can be
            // read back without the whole tab moving.
            setOnTouchListener { view, _ -> view.parent.requestDisallowInterceptTouchEvent(true); false }
        }
        val count = hint("")
        // Follows the newest line once the text has been laid out. Not `fullScroll`: that moves
        // focus, and the tab's scroller would follow it on launch.
        lines.addOnLayoutChangeListener { _, _, _, _, bottom, _, _, _, oldBottom ->
            if (bottom != oldBottom) scroll.scrollTo(0, bottom)
        }
        fun render() {
            lines.text = DiagLog.text().ifEmpty { "(empty)" }
            count.text = "${DiagLog.count()} lines"
        }
        DiagLog.onChange { activity.runOnUiThread { render() } }
        render()

        val buttons = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(10) }
            addView(pill("Share log", ON_ACCENT, ACCENT, ACCENT) { DiagLog.tap("Share log"); DiagLog.share(activity) }, lp)
            addView(pill("Copy", TEAL, SURFACE_ALT, BORDER) { DiagLog.tap("Copy log"); DiagLog.copy(activity) }, lp)
            addView(pill("Clear", PINK, SURFACE_ALT, BORDER) { DiagLog.clear(); DiagLog.app("log cleared") }, lp)
        }
        val card = card(
            listOf(
                hint(
                    "Everything the example and the SDK reported this run: taps, deep links, config changes, " +
                        "keyboard, SDK events. No secrets, no card data. Share it with a bug report.",
                ),
                scroll,
                buttons,
                count,
            ),
        )
        // The card's own layout params would override the fixed height set above; re-apply it.
        scroll.layoutParams = (scroll.layoutParams as LinearLayout.LayoutParams).apply { height = dp(220) }
        return card
    }

    // ── Actions ─────────────────────────────────────────────────────────────────

    private fun refreshStatus() {
        status.text = LiveConfig.credentialsStatus()
    }

    private fun resetAll() {
        LiveConfig.resetCredentials()
        fields.forEach { (_, input) -> input.setText("") }
        syncEnvironmentChips()
        refreshStatus()
    }

    // ── Styling (the React Native example's dark palette) ─────────────────────────

    private fun title(text: String) = TextView(activity).apply {
        this.text = text
        textSize = 24f
        setTypeface(null, Typeface.BOLD)
        setTextColor(TEXT)
    }

    private fun caption(text: String) = TextView(activity).apply {
        this.text = text
        textSize = 13f
        setTextColor(TEXT_DIM)
        setPadding(0, dp(6), 0, 0)
    }

    private fun section(text: String) = TextView(activity).apply {
        this.text = text.uppercase()
        textSize = 12f
        setTypeface(null, Typeface.BOLD)
        setTextColor(TEXT_FAINT)
        letterSpacing = 0.08f
        setPadding(0, dp(22), 0, dp(8))
    }

    private fun label(text: String) = TextView(activity).apply {
        this.text = text
        textSize = 14f
        setTypeface(null, Typeface.BOLD)
        setTextColor(TEXT)
    }

    private fun hint(text: String) = TextView(activity).apply {
        this.text = text
        textSize = 12f
        setTextColor(TEXT_FAINT)
    }

    private fun card(views: List<View>): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(SURFACE, BORDER, 20)
        setPadding(dp(14), dp(14), dp(14), dp(14))
        for ((index, view) in views.withIndex()) {
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            if (index > 0) lp.topMargin = dp(12)
            addView(view, lp)
        }
    }

    private fun pill(text: String, textColor: Int, fill: Int, stroke: Int, onTap: () -> Unit) = TextView(activity).apply {
        this.text = text
        textSize = 14f
        setTypeface(null, Typeface.BOLD)
        setTextColor(textColor)
        gravity = Gravity.CENTER
        setPadding(dp(18), dp(12), dp(18), dp(12))
        background = rounded(fill, stroke, 14)
        setOnClickListener { onTap() }
    }

    private fun rounded(fill: Int, stroke: Int, radiusDp: Int) = GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat()
        setColor(fill)
        setStroke(dp(1), stroke)
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    private companion object {
        const val BG = 0xFF0E0E1A.toInt()
        const val SURFACE = 0xFF17172A.toInt()
        const val SURFACE_ALT = 0xFF1F1F38.toInt()
        const val BORDER = 0xFF2A2A47.toInt()
        const val ACCENT = 0xFF5753EA.toInt()
        const val PINK = 0xFFFF407F.toInt()
        const val TEAL = 0xFF57C9F9.toInt()
        const val TEXT = 0xFFFFFFFF.toInt()
        const val TEXT_DIM = 0xFFA0A0C0.toInt()
        const val TEXT_FAINT = 0xFF6D6D8A.toInt()
        const val ON_ACCENT = 0xFFFFFFFF.toInt()
    }
}
