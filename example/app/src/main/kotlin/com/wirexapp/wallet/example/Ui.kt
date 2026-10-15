package com.wirexapp.wallet.example

import android.content.Context
import android.graphics.Rect
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.wirexapp.wallet.topup.WirexWalletLogLevel
import com.wirexapp.wallet.topup.WirexWalletLogSink
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The plumbing every screen shares. Built with plain views rather than Compose so the integration is
 * legible to any Android developer, and because the SDK's checkout surface IS a `View` — a Compose
 * host would wrap it in `AndroidView` and add a layer that hides what is being demonstrated.
 */
object Ui {

    fun column(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val pad = dp(context, 12)
        setPadding(pad, 0, pad, pad)
    }

    fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    fun heading(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 16f
        setTypeface(null, Typeface.BOLD)
        setPadding(0, 24, 0, 8)
    }

    fun caption(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 10f
        alpha = 0.7f
    }

    fun mono(context: Context): TextView = TextView(context).apply {
        textSize = 11f
        typeface = Typeface.MONOSPACE
        alpha = 0.85f
    }

    fun buttonRow(context: Context, vararg actions: Pair<String, () -> Unit>): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
            for ((label, action) in actions) {
                addView(Button(this.context).apply {
                    text = label
                    textSize = 11f
                    setOnClickListener { action() }
                })
            }
        }

    fun jsonField(context: Context, initial: String): EditText = EditText(context).apply {
        setText(initial)
        // The insets listener covers the keyboard OPENING. This covers moving between fields while
        // it is already open, when no insets pass fires at all.
        setOnFocusChangeListener { view, hasFocus -> if (hasFocus) revealFocusedField(view) }
        textSize = 11f
        typeface = Typeface.MONOSPACE
        minLines = 4
        gravity = Gravity.TOP
        inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    }

    fun field(context: Context, initial: String): EditText = EditText(context).apply {
        setText(initial)
        setOnFocusChangeListener { view, hasFocus -> if (hasFocus) revealFocusedField(view) }
        textSize = 12f
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    }

    /** A label beside a field, so the quote form reads as a form rather than a stack of boxes. */
    fun labelled(context: Context, label: String, view: View): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(context).apply {
            text = label
            textSize = 11f
            width = dp(context, 130)
        })
        addView(view, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = 1f })
    }

    /**
     * Scrolls a focused field clear of the keyboard. `requestRectangleOnScreen` takes a rect in the
     * view's OWN coordinates and bubbles up through the parents, so the ScrollView handles it without
     * this needing to know the hierarchy.
     */
    fun revealFocusedField(focused: View) {
        focused.post {
            val margin = (12 * focused.resources.displayMetrics.density).toInt()
            focused.requestRectangleOnScreen(
                Rect(0, -margin, focused.width, focused.height + margin),
                false,
            )
        }
    }

    /**
     * Wraps a screen's content in a scroller that handles the keyboard.
     *
     * `adjustResize` in the manifest is necessary but NOT sufficient: targetSdk 35+ forces
     * edge-to-edge, so the system stops resizing the window for the IME and hands the app an inset
     * instead. Without this the keyboard covers the provider-config field.
     */
    fun scroller(context: Context, content: View, currentFocus: () -> View?): ScrollView {
        var lastImeBottom = 0
        return ScrollView(context).apply {
            addView(content, matchWidth())
            clipToPadding = false
            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
                view.setPadding(
                    view.paddingLeft,
                    bars.top,
                    view.paddingRight,
                    // The IME inset already includes the navigation bar when the keyboard is up, so
                    // this is max() rather than a sum — adding them double-pads by the nav bar.
                    maxOf(bars.bottom, ime.bottom),
                )
                // Only on the RISING edge: insets are dispatched on every layout pass while the
                // keyboard is up, and scrolling on each one yanks the screen back to the field.
                if (ime.bottom > 0 && lastImeBottom == 0) currentFocus()?.let(::revealFocusedField)
                lastImeBottom = ime.bottom
                insets
            }
        }
    }
}

/**
 * A bounded, timestamped on-screen log. A growing mono TextView inside the screen's own scroller,
 * so everything the SDK reports is readable without adb.
 */
class EventLog(context: Context) {
    val view: TextView = Ui.mono(context).apply { text = "(empty)" }

    private val lines = ArrayDeque<String>()
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun append(line: String) {
        lines.addLast("${clock.format(Date())}  $line")
        while (lines.size > MAX_LINES) lines.removeFirst()
        view.text = lines.joinToString("\n")
    }

    fun clear() {
        lines.clear()
        view.text = "(empty)"
    }

    private companion object {
        const val MAX_LINES = 300
    }
}

/**
 * Routes the SDK's structured log into the screen's [EventLog]. Everything reaching a sink has
 * already been through the SDK's redactor — worth having in the example precisely because of what is
 * NOT in it: no token, no URL, no amount ever appears here.
 */
class ScreenLogSink(private val log: EventLog) : WirexWalletLogSink {
    override fun write(level: WirexWalletLogLevel, event: String, fields: Map<String, String>) {
        val rendered = fields.entries.sortedBy { it.key }.joinToString(" ") { "${it.key}=${it.value}" }
        log.append("[sdk ${level.name.lowercase(Locale.US)}] $event $rendered".trimEnd())
    }
}
