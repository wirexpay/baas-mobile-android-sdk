package com.wirexapp.baas.example

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

/**
 * The plumbing every screen shares: rows of buttons, JSON fields, collapsible panels, and the
 * keyboard handling that took two attempts to get right.
 *
 * Extracted when the example grew from one screen to three. Built with plain views rather than
 * Compose so the integration is legible to any Android developer, and because the SDK's row IS a
 * `View` — a Compose host would wrap it in `AndroidView` and add a layer that hides what is being
 * demonstrated.
 */
object Ui {

    /**
     * The EXAMPLE's own backdrop for the selected mode.
     *
     * Not SDK behaviour — a real host already has a theme. It is here because `surfaceColor` defaults
     * to transparent, so a dark-mode row drawn on a light screen reads as the token having done
     * nothing, and the Appearance tab's whole job is to make a token change visible.
     *
     * Repainting rather than forcing `AppCompatDelegate.localNightMode`: that RECREATES the activity,
     * which drops whatever is typed into the collect form — the exact thing the tab is meant to
     * demonstrate a restyle does not do.
     */
    fun applyBackdrop(view: View, dark: Boolean) {
        view.setBackgroundColor(if (dark) 0xFF121212.toInt() else 0xFFFAFAFA.toInt())
        tint(view, if (dark) 0xFFE6E6E6.toInt() else 0xFF1A1A1A.toInt())
    }

    /**
     * Recolours the EXAMPLE's own text, stopping at the SDK's views.
     *
     * The stop matters: everything inside a Wirex view is styled by appearance TOKENS, and reaching in
     * to repaint it would be the example overriding the very thing it is demonstrating. Anything the
     * example drew itself is fair game.
     */
    private fun tint(view: View, color: Int) {
        if (view is com.wirexapp.baas.cards.collect.ui.WirexCollectForm) return
        if (view is com.wirexapp.baas.cards.reveal.ui.WirexRevealFieldView) return
        if (view is Button) return
        if (view is TextView) view.setTextColor(color)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) tint(view.getChildAt(index), color)
        }
    }

    fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

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

    /**
     * A header that shows and hides its body. Collapsed by default: this is operator plumbing, and
     * the point of a screen is the SDK's own views above it.
     */
    fun panel(context: Context, title: String, body: View): View {
        body.visibility = View.GONE
        val header = TextView(context).apply {
            text = "▸ $title"
            textSize = 14f
            setPadding(0, 24, 0, 8)
            setOnClickListener {
                val opening = body.visibility == View.GONE
                body.visibility = if (opening) View.VISIBLE else View.GONE
                text = "${if (opening) "▾" else "▸"} $title"
            }
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(header)
            addView(body, matchWidth())
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

    /**
     * Scrolls a focused field clear of the keyboard.
     *
     * `requestRectangleOnScreen` takes a rect in the view's OWN coordinates and bubbles up through
     * the parents, so the ScrollView handles it without this needing to know the hierarchy. The rect
     * is grown by a margin on both sides so the field does not land flush against the keyboard's top
     * edge.
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
     * `adjustResize` in the manifest is necessary but NOT sufficient: targetSdk 35 forces
     * edge-to-edge on Android 15+, so the system stops resizing the window for the IME and hands the
     * app an inset instead. Without this the keyboard covers the fields — and on the collect screen
     * that means typing a card number into a box you cannot see.
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
                // Only on the RISING edge. Insets are dispatched on every layout pass while the
                // keyboard is up, so scrolling on each one yanked the screen back to the focused
                // field every time a button elsewhere was tapped.
                if (ime.bottom > 0 && lastImeBottom == 0) currentFocus()?.let(::revealFocusedField)
                lastImeBottom = ime.bottom
                insets
            }
        }
    }
}
