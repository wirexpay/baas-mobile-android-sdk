package com.wirexapp.baas.example

import android.app.Activity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

/**
 * One place to restyle every field on both surfaces.
 *
 * Appearance is a screen of its own and applies to BOTH surfaces: both listen for
 * `LiveConfig.onAppearanceChanged`, so a preset or a pasted document lands everywhere at once. That
 * is also the invariant worth watching here — restyling must not clear what is typed into the collect
 * form, and must not unmask a reveal row.
 *
 * Presets are hardcoded on purpose. They are the fastest way to see the token vocabulary do something
 * visible, and each one is chosen to exercise a group of tokens rather than to look nice. The same
 * six the iOS example carries, token for token.
 */
class AppearanceScreen(private val activity: AppCompatActivity) {

    private lateinit var appearanceField: EditText

    /**
     * The preset whose document is currently in the field, so a theme change can re-write it.
     *
     * Null once someone types their own — a theme change must not overwrite what they just wrote.
     */
    private var activePreset: Preset? = null
    private lateinit var status: TextView

    private class Preset(
        val name: String,
        /** What it is here to demonstrate. Shown under the button, so the tab teaches the vocabulary. */
        val exercises: String,
        val light: Map<String, Any>,
        val dark: Map<String, Any>,
    )

    // Each variant is COMPLETE, because nothing merges them.
    //
    // The SDK takes one appearance and resolves no theme, so the document handed over in dark is the
    // whole document — not a patch on the light one. A partial dark map used to work by accident,
    // back when the SDK layered one over the other; under this contract it silently drops every
    // token it omits, and a preset that sets only colours would lose its geometry the moment the
    // theme flipped.
    //
    // Presets that set NO colours carry the same map twice on purpose: geometry does not change with
    // the theme, and duplicating it is how that stays true when someone edits one of them.
    private val presets = listOf(
        Preset(
            "SDK defaults",
            "every token unset — the mode's own defaults",
            emptyMap(),
            emptyMap(),
        ),
        Preset(
            "Boxed",
            "colorBackground, borderWidth, borderRadius, paddingValue*",
            mapOf(
                "surfaceColor" to "#FFFFFF",
                "colorBackground" to "#F1F5F9",
                "colorBorder" to "#CBD5E1",
                "borderWidth" to 1,
                "borderRadius" to 16,
                "paddingHorizontal" to 14,
                "paddingVertical" to 12,
                "paddingValueHorizontal" to 12,
                "paddingValueVertical" to 10,
                "colorText" to "#0F172A",
                "colorLabel" to "#64748B",
            ),
            mapOf(
                "surfaceColor" to "#0B1120",
                "colorBackground" to "#1E293B",
                "colorBorder" to "#334155",
                "borderWidth" to 1,
                "borderRadius" to 16,
                "paddingHorizontal" to 14,
                "paddingVertical" to 12,
                "paddingValueHorizontal" to 12,
                "paddingValueVertical" to 10,
                "colorText" to "#F8FAFC",
                "colorLabel" to "#94A3B8",
            ),
        ),
        Preset(
            "Right-aligned",
            "textAlignBase, textAlignLabel — the two the collect field ignored",
            mapOf(
                "textAlignBase" to "right",
                "textAlignLabel" to "right",
                "colorBackground" to "#F8FAFC",
                "paddingValueHorizontal" to 12,
                "borderRadius" to 12,
            ),
            mapOf(
                "textAlignBase" to "right",
                "textAlignLabel" to "right",
                "colorBackground" to "#111827",
                "paddingValueHorizontal" to 12,
                "borderRadius" to 12,
            ),
        ),
        Preset(
            "Large type",
            "fontSizeBase, lineHeightBase, controlMinHeight, iconSize",
            mapOf(
                "fontSizeBase" to 24,
                "lineHeightBase" to 34,
                "fontSizeLabel" to 15,
                "lineHeightLabel" to 20,
                "controlMinHeight" to 56,
                "iconSize" to 24,
                "spacingLabel" to 8,
                "spacingUnit" to 20,
            ),
            mapOf(
                "fontSizeBase" to 24,
                "lineHeightBase" to 34,
                "fontSizeLabel" to 15,
                "lineHeightLabel" to 20,
                "controlMinHeight" to 56,
                "iconSize" to 24,
                "spacingLabel" to 8,
                "spacingUnit" to 20,
            ),
        ),
        Preset(
            "Compact",
            "the same tokens the other way — a dense list",
            mapOf(
                "fontSizeBase" to 15,
                "lineHeightBase" to 20,
                "fontSizeLabel" to 11,
                "lineHeightLabel" to 14,
                "controlMinHeight" to 36,
                "iconSize" to 12,
                "spacingLabel" to 2,
                "spacingUnit" to 6,
                "paddingHorizontal" to 8,
                "paddingVertical" to 6,
            ),
            mapOf(
                "fontSizeBase" to 15,
                "lineHeightBase" to 20,
                "fontSizeLabel" to 11,
                "lineHeightLabel" to 14,
                "controlMinHeight" to 36,
                "iconSize" to 12,
                "spacingLabel" to 2,
                "spacingUnit" to 6,
                "paddingHorizontal" to 8,
                "paddingVertical" to 6,
            ),
        ),
        Preset(
            "High contrast",
            "colorFocus and colorError, which drive the caret and the error text",
            mapOf(
                "surfaceColor" to "#000000",
                "colorBackground" to "#000000",
                "colorText" to "#FFFFFF",
                "colorLabel" to "#FFD400",
                "colorPlaceholder" to "#7A7A7A",
                "colorFocus" to "#FFD400",
                "colorError" to "#FF453A",
                "colorBorder" to "#FFD400",
                "borderWidth" to 2,
                "borderRadius" to 4,
            ),
            mapOf(
                "surfaceColor" to "#000000",
                "colorBackground" to "#000000",
                "colorText" to "#FFFFFF",
                "colorLabel" to "#FFD400",
                "colorPlaceholder" to "#7A7A7A",
                "colorFocus" to "#FFD400",
                "colorError" to "#FF453A",
                "colorBorder" to "#FFD400",
                "borderWidth" to 2,
                "borderRadius" to 4,
            ),
        ),
    )

    fun build(): View {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 32)
        }

        root.addView(
            Ui.caption(
                activity,
                "One document, and the SDK draws exactly it. There is no theme to choose: the SDK " +
                    "resolves none and holds no palette. The device decides which document this " +
                    "example hands over, and the editor below shows the one in force.",
            ),
        )

        root.addView(Ui.heading(activity, "Presets"))
        root.addView(
            Ui.caption(
                activity,
                "Applied to BOTH surfaces at once. Watch that a restyle never clears the collect form " +
                    "or unmasks a reveal row — that is the invariant, not a detail.",
            ),
        )

        for (preset in presets) {
            root.addView(Ui.buttonRow(activity, preset.name to { applyPreset(preset) }))
            root.addView(Ui.caption(activity, preset.exercises))
        }

        root.addView(Ui.heading(activity, "Appearance (JSON)"))
        root.addView(
            Ui.caption(
                activity,
                "ONE document, which is what the SDK takes — it resolves no theme. Choosing a preset " +
                    "or a mode rewrites this field with the palette for what is showing; type here " +
                    "and it is yours until you pick another preset.",
            ),
        )
        appearanceField = Ui.jsonField(activity, "")
        root.addView(appearanceField, Ui.matchWidth())
        root.addView(
            Ui.buttonRow(
                activity,
                "Apply" to { applyAppearance() },
                "Reset" to { reset() },
            ),
        )

        status = Ui.mono(activity).apply {
            textSize = 10f
            setPadding(0, 16, 0, 0)
            text = "—"
        }
        root.addView(status)
        LiveConfig.onAppearanceChanged { Ui.applyBackdrop(root, LiveConfig.isDarkMode(activity)) }
        Ui.applyBackdrop(root, LiveConfig.isDarkMode(activity))
        return root
    }

    // ── Actions ─────────────────────────────────────────────────────────────────

    private fun applyPreset(preset: Preset) {
        activePreset = preset
        writeAndApply(preset)
        status.text = "applied '${preset.name}' — both surfaces restyled"
    }

    /** Puts the variant for the CURRENT theme into the field, and hands that document to the SDK. */
    private fun writeAndApply(preset: Preset) {
        val tokens = if (LiveConfig.isDarkMode(activity)) preset.dark else preset.light
        appearanceField.setText(pretty(tokens))
        LiveConfig.setAppearance(tokens)
    }

    private fun applyAppearance() {
        activePreset = null
        report(LiveConfig.applyAppearance(appearanceField.text.toString()))
    }

    private fun reset() {
        activePreset = null
        LiveConfig.resetAppearance()
        appearanceField.setText("")
        status.text = "reset to the SDK defaults"
    }

    private fun report(outcome: ConfigOutcome) {
        status.text = when (outcome) {
            is ConfigOutcome.Merged -> outcome.summary
            is ConfigOutcome.Rejected -> "rejected: ${outcome.reason}"
        }
    }

    private fun pretty(tokens: Map<String, Any>): String {
        if (tokens.isEmpty()) return ""
        return runCatching { JSONObject(tokens.toSortedMap() as Map<*, *>).toString(2) }
            .getOrDefault("")
    }

    private fun toast(message: String) =
        Toast.makeText(activity as Activity, message, Toast.LENGTH_SHORT).show()
}
