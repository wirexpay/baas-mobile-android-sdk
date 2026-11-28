package com.wirexapp.baas.example

import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.wirexapp.baas.cards.core.WirexEnvironment
import com.wirexapp.baas.crypto.WirexCrypto
import com.wirexapp.baas.crypto.WirexCryptoBalance
import com.wirexapp.baas.crypto.WirexCryptoStepEvent
import com.wirexapp.baas.crypto.WirexDepositAddress
import com.wirexapp.baas.crypto.WirexDeliveryState
import com.wirexapp.baas.crypto.WirexPendingSend
import com.wirexapp.baas.crypto.WirexSendQuote
import com.wirexapp.baas.crypto.WirexSendRail
import com.wirexapp.baas.crypto.WirexSendResult
import com.wirexapp.baas.crypto.WirexSendStatus
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * The Crypto tab: a send from the main account and a deposit address, with the panels, buttons and wording of every
 * platform's example. It sends against a real environment; on `production` it refuses a send worth more than 10 USD.
 */
class CryptoScreen(private val activity: AppCompatActivity) {

    private val session = CryptoSession()
    private val held = HeldQuotes()
    private var busy: String? = null

    private var network = "BASE"
    private var provider: WirexSendRail? = null
    private var family = "EVM"
    private var balances: List<WirexCryptoBalance>? = null
    private var quote: WirexSendQuote? = null
    private val steps = mutableListOf<WirexCryptoStepEvent>()
    private var result: WirexSendResult? = null
    private var status: WirexSendStatus? = null
    private var pending: List<WirexPendingSend>? = null
    private var deposit: WirexDepositAddress? = null

    private val banner = TextView(activity).apply { textSize = 12f; setTypeface(null, Typeface.BOLD) }
    private val sessionLine = Ui.mono(activity)
    private val problems = Ui.mono(activity)
    private val errorLine = errorText()
    private val signInButton = button("Sign in") { signIn() }

    private val readBalancesButton = button("Read balances") { readBalances() }
    private val balancesTable = TableLayout(activity)

    private val networkButtons = mutableListOf<Pair<String, Button>>()
    private val providerButtons = mutableListOf<Pair<WirexSendRail?, Button>>()
    private val token = field("USDC", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS).apply { setText("USDC") }
    private val recipient = field("the address on that network", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
    private val amount = field("1.00", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
    private val amountRefusal = errorText()
    private val getQuoteButton = button("Get quote") { getQuote() }
    private val quoteTable = TableLayout(activity)
    private val sendButton = button("Send") { send() }
    private val checkDeliveryButton = button("Check delivery") { checkDelivery() }
    private val stepsTable = TableLayout(activity)
    private val resultLine = Ui.mono(activity)
    private val statusLine = Ui.mono(activity)

    private val loadPendingButton = button("Load pending") { loadPending() }
    private val pendingTable = TableLayout(activity)
    private val resumeButtons = mutableListOf<Pair<WirexPendingSend, Button>>()

    private val familyButtons = mutableListOf<Pair<String, Button>>()
    private val getDepositButton = button("Get deposit address") { getDeposit() }
    private val depositLine = Ui.mono(activity)
    private val depositTable = TableLayout(activity)

    fun build(): View {
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        fun add(view: View) = column.addView(view, Ui.matchWidth())

        add(banner)
        add(sessionLine)
        add(problems)
        add(row(signInButton))
        add(errorLine)

        add(Ui.heading(activity, "Balances"))
        add(row(readBalancesButton))
        add(HorizontalScrollView(activity).apply { addView(balancesTable) })

        add(Ui.heading(activity, "Send"))
        add(Ui.caption(activity, "network"))
        add(HorizontalScrollView(activity).apply { addView(choice(NETWORKS.map { it to it }, networkButtons) { network = it; render() }) })
        add(Ui.caption(activity, "token"))
        add(token)
        add(Ui.caption(activity, "recipient"))
        add(recipient)
        add(Ui.caption(activity, "amount (what arrives)"))
        add(amount)
        add(Ui.caption(activity, "provider (optional)"))
        add(choice(listOf<Pair<WirexSendRail?, String>>(null to "— any —") + PROVIDERS.map { it to it.wire }, providerButtons) { provider = it; render() })
        add(amountRefusal)
        add(row(getQuoteButton))
        add(quoteTable)
        add(row(sendButton, checkDeliveryButton))
        add(HorizontalScrollView(activity).apply { addView(stepsTable) })
        add(resultLine)
        add(statusLine)

        add(Ui.heading(activity, "Pending"))
        add(row(loadPendingButton))
        add(HorizontalScrollView(activity).apply { addView(pendingTable) })

        add(Ui.heading(activity, "Deposit"))
        add(Ui.caption(activity, "family"))
        add(choice(FAMILIES.map { it to it }, familyButtons) { family = it; render() })
        add(row(getDepositButton))
        add(depositLine)
        add(HorizontalScrollView(activity).apply { addView(depositTable) })

        listOf(token, recipient, amount).forEach { input ->
            input.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) = render()
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        LiveConfig.onCredentialsChanged { activity.runOnUiThread { refreshSession() } }
        refreshSession()
        return column
    }

    // ── The engine ───────────────────────────────────────────────────────────────

    /** A new surface for a new config clears everything the old one showed, its held quotes included. */
    private fun refreshSession() {
        if (session.refresh()) {
            balances = null; quote = null; result = null; status = null; pending = null; deposit = null
            held.clear()
            steps.clear()
            errorLine.text = ""
            renderBalances(); renderQuote(); renderSend(); renderPending(); renderDeposit()
        }
        render()
    }

    /** Runs one engine call at a time; a failure shows its reason, never a guess. */
    private fun run(label: String, call: suspend (WirexCrypto) -> Unit) {
        val crypto = session.crypto ?: return
        if (busy != null) return
        busy = label
        errorLine.text = ""
        DiagLog.tap("Crypto: $label")
        render()
        activity.lifecycleScope.launch {
            try {
                call(crypto)
                DiagLog.app("crypto: $label done")
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                val text = "$label: ${if (failure is CapRefused) failure.message else TradeFormat.describe(failure)}"
                errorLine.text = text
                DiagLog.err(text)
            } finally {
                busy = null
                render()
            }
        }
    }

    private val onStep: (WirexCryptoStepEvent) -> Unit = { event ->
        activity.runOnUiThread {
            steps += event
            renderSend()
            DiagLog.app("crypto step ${event.step.wire} ${event.state.wire}")
        }
    }

    private fun signIn() {
        DiagLog.tap("Crypto: Sign in")
        errorLine.text = ""
        busy = "Sign in"
        render()
        activity.lifecycleScope.launch {
            try {
                WalletLogin.bearer()
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                errorLine.text = "Sign in: ${failure.message ?: failure.javaClass.simpleName}"
            } finally {
                busy = null
                render()
            }
        }
    }

    private fun readBalances() = run("Read balances") { balances = it.balances(); renderBalances() }

    private fun getQuote() {
        typedRefusal()?.let { errorLine.text = "Get quote $it"; return }
        run("Get quote") {
            quote = null; renderQuote()
            val made = it.quoteSend(recipient.text.toString(), network, token.text.toString().trim(), amount.text.toString().trim(), provider)
            quote = made
            held.hold(made)
            renderQuote()
            renderPending()
        }
    }

    private fun send() {
        val current = quote ?: return
        run("Send") {
            // SECURITY: the 10 USD cap is checked again at the tap, on what the quote sends leaves.
            capRefusal(it, current)?.let { refusal -> throw CapRefused(refusal) }
            steps.clear(); result = null; status = null; renderSend()
            result = it.send(current, onStep)
            renderSend()
        }
    }

    private fun checkDelivery() {
        val current = result ?: return
        run("Check delivery") { status = it.sendStatus(current); renderSend() }
    }

    private fun loadPending() = run("Load pending") { pending = it.pendingSends(); renderPending() }

    private fun resume(row: WirexPendingSend) {
        val quoteForRow = held.forHash(row.hash) ?: return
        run("Resume") {
            // SECURITY: a resume still sends the execute, so the same cap applies to the held quote.
            capRefusal(it, quoteForRow)?.let { refusal -> throw CapRefused(refusal) }
            steps.clear(); result = null; status = null; renderSend()
            result = it.resumeSend(quoteForRow.sendId, quoteForRow, onStep)
            renderSend()
        }
    }

    private fun getDeposit() = run("Get deposit address") {
        deposit = null; renderDeposit()
        deposit = it.depositAddress(family)
        renderDeposit()
    }

    // ── The cap ──────────────────────────────────────────────────────────────────

    /** A cap refusal, shown as it is written rather than as an engine reason. */
    private class CapRefused(message: String) : Exception(message)

    /** SECURITY: on production the typed amount of the typed token is capped before any quote. */
    private fun typedRefusal(): String? {
        if (amount.text.isBlank()) return null
        return TradeGuards.refuseSend(LiveConfig.environment, token.text.toString(), TradeGuards.usdTextCents(amount.text.toString()))
    }

    /** The cap on what [subject] leaves, read against fresh balances, so its symbol and decimals are the account's own. */
    private suspend fun capRefusal(crypto: WirexCrypto, subject: WirexSendQuote): String? {
        val rows = crypto.balances()
        balances = rows
        renderBalances()
        val leaves = TradeGuards.sendLeaves(
            subject.unwrap?.token, subject.unwrap?.units, subject.payToken, subject.payUnits,
            rows.map { TradeGuards.BalanceRow(it.token, it.symbol, it.decimals) },
        )
        return TradeGuards.refuseSend(LiveConfig.environment, leaves.symbol, leaves.cents)
    }

    // ── Rendering ────────────────────────────────────────────────────────────────

    private fun render() {
        val environment = LiveConfig.environment
        banner.text = if (environment == "production") {
            "production — real money. Every send spends the test user's own funds. The tab refuses a send worth more than 10 USD."
        } else {
            "test. Crypto sends only from Base 8453 or Arc 5042. On any other chain, a member that touches the chain refuses with invalid_input."
        }
        banner.setTextColor(if (environment == "production") PINK else TEAL)

        val chainId = LiveConfig.credential("chainId")
        val api = LiveConfig.credential("baasApiUrl").ifEmpty { WirexEnvironment.named(environment, chainId)?.apiBaseUrl ?: "—" }
        val expires = WalletLogin.expiresAt()
        val bearer = if (LiveConfig.credential("bearer").isNotEmpty()) "pasted bearer" else if (expires != null) "login" else "none yet"
        sessionLine.text = buildString {
            append("owner: ${WalletLogin.signer()?.address ?: "—"}\napi: $api\nbearer: $bearer")
            expires?.let { append(", valid until ${DateFormat.getTimeInstance().format(Date(it * 1000))}") }
            busy?.let { append("\n$it…") }
        }
        problems.text = session.problem.orEmpty()
        problems.visibility = if (session.problem == null) View.GONE else View.VISIBLE

        networkButtons.forEach { (value, button) -> button.alpha = if (value == network) 1f else 0.5f }
        providerButtons.forEach { (value, button) -> button.alpha = if (value == provider) 1f else 0.5f }
        familyButtons.forEach { (value, button) -> button.alpha = if (value == family) 1f else 0.5f }

        val refused = typedRefusal()
        amountRefusal.text = refused.orEmpty()

        val can = session.crypto != null && busy == null
        signInButton.isEnabled = session.problem == null && busy == null
        listOf(readBalancesButton, loadPendingButton, getDepositButton).forEach { it.isEnabled = can }
        getQuoteButton.isEnabled = can && recipient.text.isNotBlank() && amount.text.isNotBlank() && refused == null
        sendButton.isEnabled = can && quote != null
        checkDeliveryButton.visibility = if (result != null && result?.rail != WirexSendRail.TRANSFER) View.VISIBLE else View.GONE
        checkDeliveryButton.isEnabled = can
        resumeButtons.forEach { (row, button) -> button.isEnabled = can && held.forHash(row.hash) != null }
    }

    private fun renderBalances() {
        balancesTable.removeAllViews()
        val rows = balances ?: return
        if (rows.isEmpty()) {
            balancesTable.addView(Ui.caption(activity, "no balances"))
            return
        }
        table(balancesTable, listOf("symbol", "amount", "token"), rows.map { listOf(it.symbol, TradeFormat.formatUnits(it.units, it.decimals), it.token) })
    }

    /** Every send to another network leaves and arrives in USDC or USDT, at 6 decimals; the own chain's at its token's. */
    private fun quoteDecimals(q: WirexSendQuote): Int? =
        if (q.rail != WirexSendRail.TRANSFER) SEND_DECIMALS else balances?.firstOrNull { it.token.equals(q.payToken, ignoreCase = true) }?.decimals

    private fun shown(units: String, decimals: Int?): String = if (decimals == null) "$units (base units)" else TradeFormat.formatUnits(units, decimals)

    private fun renderQuote() {
        quoteTable.removeAllViews()
        val q = quote ?: return render()
        val decimals = quoteDecimals(q)
        table(
            quoteTable,
            null,
            listOf(
                listOf("sendId", q.sendId),
                listOf("rail", q.rail.wire),
                listOf("to", "${q.to} on ${q.network}"),
                listOf("arrives", "${shown(q.amount, decimals)} ${q.token}"),
                listOf("fee", shown(q.fee, decimals)),
                listOf("leaves", shown(q.payUnits, decimals) + if (q.unwrap != null) " (unwrapped first)" else ""),
                listOf("validUntil", q.validUntil.toLongOrNull()?.let { DateFormat.getTimeInstance().format(Date(it * 1000)) } ?: q.validUntil),
            ),
        )
        render()
    }

    private fun renderSend() {
        stepsTable.removeAllViews()
        if (steps.isNotEmpty()) {
            table(
                stepsTable,
                listOf("step", "state", "reason", "transaction"),
                steps.map { listOf(it.step.wire, it.state.wire, it.reason.orEmpty(), it.transactionHash.orEmpty()) },
            )
        }
        resultLine.text = result?.let { r ->
            "sent by ${r.rail.wire}" + (r.transactionHash?.let { " · $it" } ?: " · the execute ran before")
        }.orEmpty()
        resultLine.setTextColor(TEAL)
        statusLine.text = status?.let { s ->
            buildString {
                append(s.state.wire)
                s.vendorState?.let { append(" · $it") }
                s.destinationTxHash?.let { append(" · $it") }
                s.refund?.let { append(" · refund ${it.amount.orEmpty()} ${it.token.orEmpty()} ${it.network.orEmpty()}") }
            }
        }.orEmpty()
        statusLine.setTextColor(
            when (status?.state) {
                WirexDeliveryState.DELIVERED -> TEAL
                WirexDeliveryState.NOT_DELIVERED -> PINK
                else -> 0xFFA0A0C0.toInt()
            },
        )
        render()
    }

    private fun renderPending() {
        pendingTable.removeAllViews()
        resumeButtons.clear()
        val rows = pending ?: return render()
        if (rows.isEmpty()) {
            pendingTable.addView(Ui.caption(activity, "no pending sends"))
            return render()
        }
        pendingTable.addView(tableRow(listOf("amount", "to", "due", "valid until", ""), header = true))
        for (pendingRow in rows) {
            val row = tableRow(
                listOf(TradeFormat.formatUnits(pendingRow.units, pendingRow.decimals), pendingRow.destination, pendingRow.validAfter, pendingRow.validBefore),
                header = false,
            )
            val resume = button("Resume") { resume(pendingRow) }
            resumeButtons += pendingRow to resume
            row.addView(resume)
            if (held.forHash(pendingRow.hash) == null) row.addView(Ui.caption(activity, "no quote held for this send"))
            pendingTable.addView(row)
        }
        render()
    }

    private fun renderDeposit() {
        depositTable.removeAllViews()
        val d = deposit
        depositLine.text = d?.let { "${it.family} · ${it.address}" }.orEmpty()
        if (d == null) return
        table(
            depositTable,
            listOf("network", "token", "min USD", "max USD"),
            d.networks.flatMap { entry -> entry.tokens.map { listOf(entry.network, it.symbol, it.minUsd, it.maxUsd) } },
        )
    }

    // ── Views ────────────────────────────────────────────────────────────────────

    private fun table(into: TableLayout, header: List<String>?, rows: List<List<String>>) {
        header?.let { into.addView(tableRow(it, header = true)) }
        rows.forEach { into.addView(tableRow(it, header = false)) }
    }

    private fun tableRow(cells: List<String>, header: Boolean): TableRow = TableRow(activity).apply {
        for (cell in cells) {
            addView(Ui.mono(activity).apply {
                text = cell
                setPadding(0, 4, 24, 4)
                if (header) setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            })
        }
    }

    private fun <T> choice(options: List<Pair<T, String>>, into: MutableList<Pair<T, Button>>, onPick: (T) -> Unit): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            for ((value, label) in options) {
                val button = button(label) { onPick(value) }
                into += value to button
                addView(button)
            }
        }

    private fun row(vararg buttons: Button): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        buttons.forEach { addView(it) }
    }

    private fun button(label: String, onTap: () -> Unit) = Button(activity).apply {
        text = label
        textSize = 11f
        isAllCaps = false
        setOnClickListener { onTap() }
    }

    private fun field(hint: String, type: Int): EditText = EditText(activity).apply {
        this.hint = hint
        inputType = type
        isSingleLine = true
        textSize = 14f
        setOnFocusChangeListener { view, hasFocus -> if (hasFocus) Ui.revealFocusedField(view) }
    }

    private fun errorText() = Ui.mono(activity).apply { setTextColor(PINK) }

    private companion object {
        const val PINK = 0xFFFF407F.toInt()
        const val TEAL = 0xFF57C9F9.toInt()
        const val SEND_DECIMALS = 6
        val NETWORKS = listOf("ARC", "BASE", "ETHEREUM", "ARBITRUM", "OPTIMISM", "AVALANCHE", "MATIC_POS", "BINANCE", "TRON", "SOLANA")
        val PROVIDERS = listOf(WirexSendRail.CCTP, WirexSendRail.RHINO, WirexSendRail.ACROSS)
        val FAMILIES = listOf("EVM", "TRX", "SOL", "XLM", "BTC")
    }
}
