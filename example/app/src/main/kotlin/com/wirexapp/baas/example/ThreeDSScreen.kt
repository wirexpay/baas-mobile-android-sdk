package com.wirexapp.baas.example

import android.app.Activity
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.wirexapp.baas.cards.core.logging.WirexLogLevel
import com.wirexapp.baas.cards.core.logging.WirexLogging
import com.wirexapp.baas.cards.threeds.WirexThreeDS
import com.wirexapp.baas.cards.threeds.WirexThreeDSSession
import com.wirexapp.baas.cards.threeds.WirexThreeDSSnapshot
import com.wirexapp.baas.cards.threeds.WirexThreeDSState
import com.wirexapp.baas.cards.threeds.ui.WirexThreeDSView

/**
 * The 3DS step-up, end to end, with the orchestrator URL pasted in.
 *
 * A real host takes `three_ds_state.url` from its own `POST /v1/cards/topup/execute` reply. This
 * shows the SDK's part: the origin refusal, the states, the challenge, and the outcome.
 *
 * A URL passed as the `threeDsUrl` launch extra fills the field and starts once, for scripted runs.
 */
class ThreeDSScreen(private val activity: Activity, private val launchUrl: String) {

    private val threeDS = WirexThreeDS(testMode = true, logging = WirexLogging(WirexLogLevel.INFO, ConsoleLogSink))

    private lateinit var url: EditText
    private lateinit var start: Button
    private lateinit var cancel: Button
    private val status = Ui.mono(activity)

    /** The SDK's view, at FULL SIZE for the session's whole life. Never collapsed or faded. */
    private val challengeView = WirexThreeDSView(activity)

    private var session: WirexThreeDSSession? = null

    fun build(): View {
        url = EditText(activity).apply {
            hint = "three_ds_state.url"
            setText(launchUrl)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
        }
        start = Button(activity).apply {
            text = "Start step-up"
            setOnClickListener { start() }
        }
        cancel = Button(activity).apply {
            text = "Cancel"
            isEnabled = false
            setOnClickListener { session?.cancel() }
        }
        status.text = "no session"

        val buttons = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(start)
            addView(cancel)
        }
        val controls = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            addView(url, Ui.matchWidth())
            addView(buttons, Ui.matchWidth())
            addView(status, Ui.matchWidth())
        }

        restyle()
        LiveConfig.onAppearanceChanged { activity.runOnUiThread { restyle() } }

        val match = ViewGroup.LayoutParams.MATCH_PARENT
        return FrameLayout(activity).apply {
            addView(controls, FrameLayout.LayoutParams(match, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(challengeView, FrameLayout.LayoutParams(match, match))
            if (launchUrl.isNotEmpty()) post { start() }
        }
    }

    fun release() {
        session?.destroy()
        session = null
    }

    private fun restyle() {
        challengeView.appearance = LiveConfig.appearance(activity)
    }

    private fun start() {
        session?.destroy()
        val next = threeDS.session(orchestratorUrl = url.text.toString())
        session = next
        next.onChange = ::render
        challengeView.bind(next)
        next.start(activity)
    }

    private fun render(snapshot: WirexThreeDSSnapshot) {
        val settled = snapshot.state == WirexThreeDSState.SETTLED
        // `cancel()` does nothing once the session has settled, so the button is disabled then.
        cancel.isEnabled = !settled
        start.text = if (settled) "Start another" else "Start step-up"

        val lines = mutableListOf("state: ${snapshot.state.wireName}")
        snapshot.outcome?.let { outcome ->
            lines += "status: ${outcome.status.wireStatus}"
            val error = outcome.error
            if (error != null) {
                lines += "code: ${error.code.wireCode}"
                lines += "retryable: ${error.retryable}"
                error.message?.let { lines += "message: $it" }
            } else {
                // A dismissal carries no error.
                lines += "error: none"
            }
        }
        status.text = lines.joinToString("\n")
    }
}
