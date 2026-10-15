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

        root.addView(section("Environment"))
        root.addView(environmentRow())

        root.addView(section("Integration parameters"))
        root.addView(group(integration))

        root.addView(section("Secrets · memory only"))
        root.addView(group(secrets))

        root.addView(section("Credentials status"))
        status = TextView(activity).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(TEXT_DIM)
            text = LiveConfig.credentialsStatus()
        }
        root.addView(card(listOf(status, pill("Refresh status", TEAL, SURFACE_ALT, BORDER) { refreshStatus() })))

        root.addView(
            pill("Reset all", PINK, PINK and 0x22FFFFFF.toInt(), PINK) { resetAll() }.apply {
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(26)
                layoutParams = lp
            },
        )

        return Ui.scroller(activity, root) { activity.currentFocus }.apply { setBackgroundColor(BG) }
    }

    // ── Rows ──────────────────────────────────────────────────────────────────────

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
                    LiveConfig.setCredential("environment", name)
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
