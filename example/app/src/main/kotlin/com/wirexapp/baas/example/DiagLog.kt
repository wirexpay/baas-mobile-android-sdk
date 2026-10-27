package com.wirexapp.baas.example

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import com.wirexapp.baas.cards.core.WirexSdkInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The example's diagnostic log: one process-wide, in-memory ring of timestamped lines, shown at the
 * bottom of the Config tab and shared from there with a bug report.
 *
 * Everything the example and the SDK report this run lands here — taps, deep links, config changes,
 * keyboard, SDK events, errors — so a tester's "it did nothing" comes with what actually happened.
 * The same tags on every platform's example: `app`, `link`, `config`, `tap`, `kbd`, `sdk`, `err`.
 *
 * SECURITY: no secret and no card data is ever written here. Callers log key NAMES and value
 * LENGTHS, never values; [redact] is a second line behind that, for anything that slips through in
 * an SDK field or an error message. The share sheet sends this text to whatever app the tester
 * picks, so what is not in it cannot leak.
 */
object DiagLog {
    private val lines = ArrayDeque<String>()
    private val lock = Any()
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val listeners = mutableListOf<() -> Unit>()
    private var lastBody: String? = null

    /** Set once from the activity; the share header reads it. */
    private var header: String = ""

    fun app(message: String) = append("app", message)
    fun link(message: String) = append("link", message)
    fun config(message: String) = append("config", message)
    fun tap(message: String) = append("tap", message)
    fun kbd(message: String) = append("kbd", message)
    fun sdk(message: String) = append("sdk", message)
    fun err(message: String) = append("err", message)

    /** `[err] <Class>: <message>` for any caught exception. */
    fun err(error: Throwable) = err("${error.javaClass.simpleName}: ${error.message ?: "(no message)"}")

    fun append(tag: String, message: String) {
        val body = "[$tag] ${redact(message)}"
        val snapshot: List<() -> Unit>
        synchronized(lock) {
            // The same event twice in a row (a form reporting its state per field) is one line.
            if (lastBody == body) return
            lastBody = body
            lines.addLast("${clock.format(Date())} $body")
            while (lines.size > MAX_LINES) lines.removeFirst()
            snapshot = listeners.toList()
        }
        snapshot.forEach { it() }
    }

    fun clear() {
        val snapshot: List<() -> Unit>
        synchronized(lock) {
            lines.clear()
            lastBody = null
            snapshot = listeners.toList()
        }
        snapshot.forEach { it() }
    }

    /** Called after every change, on the thread that logged. Subscribers post to the UI thread. */
    fun onChange(listener: () -> Unit) = synchronized(lock) { listeners += listener }

    fun text(): String = synchronized(lock) { lines.joinToString("\n") }

    fun count(): Int = synchronized(lock) { lines.size }

    /** Describes the build and the device once, at launch; the first line of every share. */
    fun start(context: Context) {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val metrics = context.resources.displayMetrics
        header = listOf(
            "app: ${context.packageName} ${info.versionName} (${info.longVersionCodeCompat()})",
            "os: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "device: ${Build.MANUFACTURER} ${Build.MODEL}",
            "sdk: ${WirexSdkInfo.PLATFORM}/${WirexSdkInfo.VERSION}",
            "screen: ${metrics.widthPixels}x${metrics.heightPixels} @${metrics.density}x",
            "locale: ${Locale.getDefault().toLanguageTag()}",
        ).joinToString("\n")
        app("launch\n$header")
    }

    /** Header + every line, dated: what "Share log" and "Copy" hand over. */
    fun export(): String = buildString {
        append("Wirex Mobile SDK example — diagnostic log\n")
        append("exported: ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())).append('\n')
        append(header).append('\n')
        append("lines: ").append(count()).append("\n\n")
        append(text())
    }

    /** The system share sheet, text/plain, so the log reaches a chat or a ticket in one tap. */
    fun share(context: Context) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Wirex SDK example — diagnostic log")
            putExtra(Intent.EXTRA_TEXT, export())
        }
        context.startActivity(Intent.createChooser(send, "Share diagnostic log"))
    }

    fun copy(context: Context) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Wirex SDK diagnostic log", export()))
        Toast.makeText(context, "Log copied", Toast.LENGTH_SHORT).show()
    }

    /** Host only, no query, no fragment: a 3DS or checkout URL carries its session token after them. */
    fun hostOf(url: String): String =
        runCatching { android.net.Uri.parse(url.trim()) }.getOrNull()?.let { uri ->
            if (uri.scheme == null) "(no scheme)" else "${uri.scheme}://${uri.host ?: ""}"
        } ?: "(unparseable)"

    /** `0x1a2b…9f8e` — enough to tell two addresses apart, not enough to be one. */
    fun maskAddress(address: String): String =
        if (address.length > 10) "${address.take(6)}…${address.takeLast(4)}" else address

    // SECURITY: the last line of defence, not the first. Callers must not log values; this catches a
    // bearer, a card number or a wallet address that arrives inside a message anyway (an SDK field,
    // an exception text) before the line is kept or shared.
    private fun redact(message: String): String = message
        .replace(JWT, "<jwt>")
        .replace(ADDRESS) { maskAddress(it.value) }
        .replace(DIGITS, "<digits>")

    private val JWT = Regex("""eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]+\.?[A-Za-z0-9_-]*""")
    private val ADDRESS = Regex("""0x[0-9a-fA-F]{40}""")
    private val DIGITS = Regex("""(?<!\d)\d(?:[ -]?\d){12,18}(?!\d)""")

    private fun android.content.pm.PackageInfo.longVersionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else @Suppress("DEPRECATION") versionCode.toLong()

    private const val MAX_LINES = 2000
}
