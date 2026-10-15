package com.wirexapp.baas.example

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.wirexapp.baas.cards.core.logging.WirexLogLevel
import com.wirexapp.baas.cards.core.logging.WirexLogSink

/**
 * Six tabs, the same set every platform has: **Config**, **Reveal**, **Collect**, **3DS**, **Wallet**, **Appearance**.
 *
 * A bottom button bar rather than `BottomNavigationView`: Material would be a dependency this example
 * does not otherwise need, and the point of the screen is the SDK's own views, not the chrome around
 * them.
 *
 * Each tab is built once and kept. Rebuilding on every switch would drop what the user has typed into
 * the collect form, and losing a half-entered card by tapping away and back is the worst possible
 * demonstration of a card-entry SDK.
 */
class MainActivity : AppCompatActivity() {

    private val credentials = HostCredentialProvider()

    private lateinit var container: FrameLayout
    private lateinit var reveal: RevealScreen
    private lateinit var threeDS: ThreeDSScreen
    private val tabs = mutableListOf<Pair<Button, View>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Credentials and identity are entered in the app (the "credentials" box), never passed as
        // intent extras — a bearer in an extra is visible in `logcat` and `dumpsys`.
        reveal = RevealScreen(this, credentials)
        threeDS = ThreeDSScreen(this, intent?.getStringExtra("threeDsUrl").orEmpty())
        setContentView(buildLayout())
    }

    override fun onDestroy() {
        reveal.release()
        threeDS.release()
        super.onDestroy()
    }

    private fun buildLayout(): View {
        container = FrameLayout(this)

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        // Config is first: a per-field editor over LiveConfig, the single store the surfaces read. It
        // returns its own keyboard-aware scroller, so it is added directly rather than wrapped again.
        addTab(bar, "Config", ConfigScreen(this).build())
        // Each screen wraps its own content in a scroller, so the keyboard handling belongs to the
        // screen that has fields rather than to the container.
        addTab(bar, "Reveal", Ui.scroller(this, reveal.build()) { currentFocus })
        addTab(bar, "Collect", Ui.scroller(this, CollectScreen(this, credentials).build()) { currentFocus })
        // Not in a scroller: the step-up view fills the tab.
        addTab(bar, "3DS", threeDS.build())
        // Wallet top-up (Apple Pay / Google Pay). Part of the same SDK artifact; it resolves its own
        // environment from assets/wirex-wallet-sdk.json.
        addTab(
            bar,
            "Wallet",
            Ui.scroller(
                this,
                com.wirexapp.wallet.example.CheckoutScreen(
                    this,
                    com.wirexapp.wallet.example.AppConfig.load(this),
                ).build(),
            ) { currentFocus },
        )
        addTab(bar, "Appearance", Ui.scroller(this, AppearanceScreen(this).build()) { currentFocus })
        // `-e screen config|collect|threeds|wallet|appearance` opens straight onto that tab, for scripted runs.
        select(listOf("config", "reveal", "collect", "threeds", "wallet", "appearance").indexOf(intent?.getStringExtra("screen")).coerceAtLeast(0))

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Edge to edge from Android 15: the system bars, the cutout and the keyboard pad the screen.
            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or
                        WindowInsetsCompat.Type.displayCutout() or
                        WindowInsetsCompat.Type.ime(),
                )
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                WindowInsetsCompat.CONSUMED
            }
            addView(
                container,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                ).apply { weight = 1f },
            )
            addView(bar, Ui.matchWidth())
        }
    }

    private fun addTab(bar: LinearLayout, title: String, content: View) {
        val index = tabs.size
        val button = Button(this).apply {
            text = title
            textSize = 12f
            setOnClickListener { select(index) }
        }
        bar.addView(
            button,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = 1f },
        )
        content.visibility = View.GONE
        container.addView(
            content,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        tabs += button to content
    }

    private fun select(index: Int) {
        for ((position, tab) in tabs.withIndex()) {
            val active = position == index
            tab.second.visibility = if (active) View.VISIBLE else View.GONE
            tab.first.alpha = if (active) 1f else 0.5f
        }
    }
}

/**
 * Sends the SDK's diagnostics to logcat.
 *
 * Worth having in the example precisely because of what is NOT in it: everything reaching a sink has
 * already been through the redactor, and the collect surface's own log lines carry a profile name and
 * a brand — never a field value. `adb logcat -s WirexSdk` while typing a card is the cheapest way to
 * see that for yourself.
 */
object ConsoleLogSink : WirexLogSink {
    override fun write(level: WirexLogLevel, event: String, fields: Map<String, String>) {
        val rendered = fields.entries.sortedBy { it.key }.joinToString(" ") { "${it.key}=${it.value}" }
        android.util.Log.println(
            when (level) {
                WirexLogLevel.ERROR -> android.util.Log.ERROR
                WirexLogLevel.WARN -> android.util.Log.WARN
                WirexLogLevel.INFO -> android.util.Log.INFO
                WirexLogLevel.DEBUG -> android.util.Log.DEBUG
            },
            "WirexSdk",
            "$event $rendered",
        )
    }
}
