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
import com.wirexapp.baas.trade.WirexTrade
import com.wirexapp.baas.trade.WirexTradeAccount
import com.wirexapp.baas.trade.WirexTradeCatalogItem
import com.wirexapp.baas.trade.WirexTradeMarket
import com.wirexapp.baas.trade.WirexTradeOpenOrder
import com.wirexapp.baas.trade.WirexTradeOrder
import com.wirexapp.baas.trade.WirexTradeQuote
import com.wirexapp.baas.trade.WirexTradeSide
import com.wirexapp.baas.trade.WirexTradeStepEvent
import kotlinx.coroutines.launch
import java.math.BigInteger
import java.text.DateFormat
import java.util.Date

/**
 * The Trade tab: a stock or coin trade end to end, with the panels, buttons and wording of every platform's example.
 * It trades against a real environment; on `production` it refuses a trade worth more than 10 USD.
 */
class TradeScreen(private val activity: AppCompatActivity) {

    private val session = TradeSession()
    private var busy: String? = null

    private var market = WirexTradeMarket.STOCK
    private var side = WirexTradeSide.BUY
    private var account: WirexTradeAccount? = null
    private var catalog: List<WirexTradeCatalogItem>? = null
    private var quote: WirexTradeQuote? = null
    private val steps = mutableListOf<WirexTradeStepEvent>()
    private var order: WirexTradeOrder? = null
    private var openOrders: List<WirexTradeOpenOrder>? = null

    private val banner = TextView(activity).apply { textSize = 12f; setTypeface(null, Typeface.BOLD) }
    private val status = Ui.mono(activity)
    private val problems = Ui.mono(activity)
    private val engineProblem = errorText()
    private val signInButton = button("Sign in") { signIn() }

    private val readAccountButton = button("Read account") { readAccount() }
    private val setUpAccountButton = button("Set up account") { setUpAccount() }
    private val accountError = errorText()
    private val accountTable = TableLayout(activity)
    private val holdingsTable = TableLayout(activity)

    private val loadCatalogButton = button("Load catalog") { loadCatalog() }
    private val catalogError = errorText()
    private val catalogItems = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }

    private val marketButtons = mutableListOf<Pair<WirexTradeMarket, Button>>()
    private val sideButtons = mutableListOf<Pair<WirexTradeSide, Button>>()
    private val symbol = field("from the catalog", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)
    private val amount = field("1.00", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
    private val amountLabel = Ui.caption(activity, "")
    private val buyRefusal = errorText()
    private val getQuoteButton = button("Get quote") { getQuote() }
    private val quoteError = errorText()
    private val quoteTable = TableLayout(activity)

    private val quoteRefusal = errorText()
    private val executeButton = button("Execute") { execute() }
    private val tradeError = errorText()
    private val stepsTable = TableLayout(activity)
    private val orderText = Ui.mono(activity)

    private val loadOpenOrdersButton = button("Load open orders") { loadOpenOrders() }
    private val ordersError = errorText()
    private val ordersTable = TableLayout(activity)
    private val resumeButtons = mutableListOf<Pair<WirexTradeOpenOrder, Button>>()

    fun build(): View {
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        fun add(view: View) = column.addView(view, Ui.matchWidth())

        add(banner)
        add(status)
        add(problems)
        add(row(signInButton))
        add(engineProblem)

        add(Ui.heading(activity, "Account"))
        add(Ui.caption(activity, "The main account's chain, both addresses, and whether the investment account is ready."))
        add(row(readAccountButton, setUpAccountButton))
        add(accountError)
        add(accountTable)
        add(holdingsTable)

        add(Ui.heading(activity, "Catalog"))
        add(Ui.caption(activity, "Stock and coin symbols. A company with its own tokens lists no coins."))
        add(row(loadCatalogButton))
        add(catalogError)
        add(HorizontalScrollView(activity).apply { addView(catalogItems) })

        add(Ui.heading(activity, "Quote"))
        add(Ui.caption(activity, "Market, side, symbol and amount go in. Amounts, fee and expiry come back."))
        add(choice(WirexTradeMarket.entries.map { it to it.wire }, marketButtons) { market = it; render() })
        add(choice(WirexTradeSide.entries.map { it to it.wire }, sideButtons) { side = it; render() })
        add(Ui.caption(activity, "symbol"))
        add(symbol)
        add(amountLabel)
        add(amount)
        add(buyRefusal)
        add(row(getQuoteButton))
        add(quoteError)
        add(quoteTable)

        add(Ui.heading(activity, "Trade"))
        add(Ui.caption(activity, "Every step and its status, until the order ends."))
        add(quoteRefusal)
        add(row(executeButton))
        add(tradeError)
        add(HorizontalScrollView(activity).apply { addView(stepsTable) })
        add(orderText)

        add(Ui.heading(activity, "Open orders"))
        add(Ui.caption(activity, "A trade carries on after the tester kills the app."))
        add(row(loadOpenOrdersButton))
        add(ordersError)
        add(HorizontalScrollView(activity).apply { addView(ordersTable) })

        listOf(symbol, amount).forEach { input ->
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

    /** A new trade for a new config clears everything the old one showed. */
    private fun refreshSession() {
        if (session.refresh()) {
            account = null; catalog = null; quote = null; order = null; openOrders = null
            steps.clear()
            listOf(accountError, catalogError, quoteError, tradeError, ordersError).forEach { it.text = "" }
            renderAccount(); renderCatalog(); renderQuote(); renderSteps(); renderOrders()
        }
        render()
    }

    /** Runs one engine call at a time; a failure shows its reason in [error], never a guess. */
    private fun run(label: String, error: TextView, call: suspend (WirexTrade) -> Unit) {
        val trade = session.trade ?: return
        if (busy != null) return
        busy = label
        error.text = ""
        DiagLog.tap("Trade: $label")
        render()
        activity.lifecycleScope.launch {
            try {
                call(trade)
                DiagLog.app("trade: $label done")
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                val text = "$label: ${TradeFormat.describe(failure)}"
                error.text = text
                DiagLog.err(text)
            } finally {
                busy = null
                render()
            }
        }
    }

    private val onStep: (WirexTradeStepEvent) -> Unit = { event ->
        activity.runOnUiThread {
            steps += event
            renderSteps()
            DiagLog.app("trade step ${event.step.wire} ${event.state.wire}${event.status?.let { " $it" } ?: ""}")
        }
    }

    private fun signIn() {
        DiagLog.tap("Trade: Sign in")
        engineProblem.text = ""
        busy = "Sign in"
        render()
        activity.lifecycleScope.launch {
            try {
                WalletLogin.bearer()
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                engineProblem.text = "Sign in: ${failure.message ?: failure.javaClass.simpleName}"
            } finally {
                busy = null
                render()
            }
        }
    }

    private fun readAccount() = run("Read account", accountError) { account = it.account(); renderAccount() }

    private fun setUpAccount() = run("Set up account", accountError) {
        steps.clear(); renderSteps()
        account = it.setUpAccount(onStep)
        renderAccount()
    }

    private fun loadCatalog() = run("Load catalog", catalogError) { catalog = it.catalog(); renderCatalog() }

    private fun getQuote() {
        typedRefusal()?.let { buyRefusal.text = it; return }
        run("Get quote", quoteError) {
            quote = null; renderQuote()
            quote = it.quote(market, side, symbol.text.toString().trim(), amount.text.toString().trim())
            renderQuote()
        }
    }

    private fun execute() {
        val current = quote ?: return
        // SECURITY: the 10 USD cap is checked again at the tap, on the quote execute is handed.
        refusalOf(current)?.let { tradeError.text = "Execute $it"; return }
        run("Execute", tradeError) {
            steps.clear(); order = null; renderSteps()
            order = it.execute(current, onStep)
            renderSteps()
        }
    }

    private fun loadOpenOrders() = run("Load open orders", ordersError) { openOrders = it.openOrders(); renderOrders() }

    private fun resume(open: WirexTradeOpenOrder) {
        // SECURITY: a resume can still fund an order, so the same cap applies to the order's own terms.
        TradeGuards.refuseWorth(LiveConfig.environment, worthOf(open))?.let { ordersError.text = "Resume $it"; return }
        val known = quote?.takeIf { it.orderId == open.orderId }
        run("Resume", ordersError) {
            steps.clear(); order = null; renderSteps()
            order = it.resume(open.orderId, known, onStep)
            renderSteps()
        }
    }

    // ── The cap ──────────────────────────────────────────────────────────────────

    /** SECURITY: on production a buy's typed USD amount above 10 USD is refused before any quote. */
    private fun typedRefusal(): String? {
        if (side != WirexTradeSide.BUY || amount.text.isBlank()) return null
        return TradeGuards.refuseWorth(LiveConfig.environment, TradeGuards.usdTextCents(amount.text.toString()))
    }

    // SECURITY: the quote's own worth, which disables Execute when it is refused.
    private fun refusalOf(subject: WirexTradeQuote): String? = TradeGuards.refuseWorth(LiveConfig.environment, worthOf(subject))

    private fun worthOf(q: WirexTradeQuote): BigInteger? = TradeGuards.worthCents(
        q.side == WirexTradeSide.BUY, q.amountIn, q.tokenInDecimals, q.estimatedAmountOut, q.tokenOutDecimals, q.transferFee,
    )

    private fun worthOf(o: WirexTradeOrder): BigInteger? = TradeGuards.worthCents(
        o.side == WirexTradeSide.BUY, o.amountIn, o.tokenInDecimals, o.estimatedAmountOut, o.tokenOutDecimals, null,
    )

    // ── Rendering ────────────────────────────────────────────────────────────────

    private fun render() {
        val environment = LiveConfig.environment
        banner.text = if (environment == "production") {
            "production — real money. Every trade spends the test user's own funds. The tab refuses a trade worth more than 10 USD."
        } else {
            "test. The catalog reads, a buy quote answers investment_wallet_required, and the set-up cannot run."
        }
        banner.setTextColor(if (environment == "production") PINK else TEAL)

        val chainId = LiveConfig.credential("chainId")
        val api = LiveConfig.credential("baasApiUrl").ifEmpty { WirexEnvironment.named(environment, chainId)?.apiBaseUrl ?: "—" }
        val pasted = LiveConfig.credential("bearer").isNotEmpty()
        val expires = WalletLogin.expiresAt()
        val bearer = if (pasted) "pasted bearer" else if (expires != null) "login" else "none yet"
        status.text = buildString {
            append("owner: ${WalletLogin.signer()?.address ?: "—"}\napi: $api\nbearer: $bearer")
            expires?.let { append(", valid until ${DateFormat.getTimeInstance().format(Date(it * 1000))}") }
            busy?.let { append("\n$it…") }
        }
        problems.text = session.problem.orEmpty()
        problems.visibility = if (session.problem == null) View.GONE else View.VISIBLE

        marketButtons.forEach { (value, button) -> button.alpha = if (value == market) 1f else 0.5f }
        sideButtons.forEach { (value, button) -> button.alpha = if (value == side) 1f else 0.5f }
        amountLabel.text = "amount (${if (side == WirexTradeSide.BUY) "USD" else "token"})"

        val buyRefused = typedRefusal()
        buyRefusal.text = buyRefused.orEmpty()
        val quoteRefused = quote?.let(::refusalOf)
        quoteRefusal.text = quoteRefused.orEmpty()

        val can = session.trade != null && busy == null
        signInButton.isEnabled = session.problem == null && busy == null
        listOf(readAccountButton, setUpAccountButton, loadCatalogButton, loadOpenOrdersButton).forEach { it.isEnabled = can }
        catalogItems.children().forEach { it.isEnabled = can }
        getQuoteButton.isEnabled = can && symbol.text.isNotBlank() && amount.text.isNotBlank() && buyRefused == null
        executeButton.isEnabled = can && quote != null && quoteRefused == null
        resumeButtons.forEach { (open, button) -> button.isEnabled = can && open.nextStep != null }
    }

    private fun renderAccount() {
        accountTable.removeAllViews()
        holdingsTable.removeAllViews()
        val a = account ?: return
        table(
            accountTable,
            null,
            listOf(
                listOf("chainId", a.chainId.toString()),
                listOf("mainAccount", a.mainAccount),
                listOf("investmentAccount", a.investmentAccount ?: "—"),
                listOf("isSetUp", a.isSetUp.toString()),
                listOf("cash", a.cash?.let { "${TradeFormat.formatUnits(it.units, it.decimals)} USDC" } ?: "—"),
            ),
        )
        if (a.holdings.isNotEmpty()) {
            table(
                holdingsTable,
                listOf("market", "symbol", "units", "shares", "value"),
                a.holdings.map {
                    listOf(
                        it.market.wire,
                        it.symbol,
                        TradeFormat.formatUnits(it.units, it.decimals),
                        it.sharesShown ?: "—",
                        it.valueCents?.let { cents -> "${TradeFormat.formatUnits(cents, 2)} USD" } ?: "—",
                    )
                },
            )
        }
    }

    private fun renderCatalog() {
        catalogItems.removeAllViews()
        val items = catalog ?: return
        if (items.isEmpty()) {
            catalogItems.addView(Ui.caption(activity, "nothing to trade"))
            return
        }
        // A symbol fills the quote's market and symbol.
        for (item in items) {
            catalogItems.addView(button("${item.symbol} · ${item.market.wire}") {
                market = item.market
                symbol.setText(item.symbol)
                render()
            })
        }
        render()
    }

    private fun renderQuote() {
        quoteTable.removeAllViews()
        val q = quote ?: return render()
        val shareDecimals = if (q.side == WirexTradeSide.BUY) q.tokenOutDecimals else q.tokenInDecimals
        val rows = mutableListOf(
            listOf("orderId", q.orderId),
            listOf("trade", "${q.side.wire} ${q.symbol} (${q.market.wire}, ${q.executionMethod})"),
            listOf("amountIn", TradeFormat.formatUnits(q.amountIn, q.tokenInDecimals)),
            listOf("estimatedAmountOut", TradeFormat.formatUnits(q.estimatedAmountOut, q.tokenOutDecimals)),
            listOf("minimumAmountOut", TradeFormat.formatUnits(q.minimumAmountOut, q.tokenOutDecimals)),
        )
        q.estimatedShares?.let {
            rows += listOf("shares", "${TradeFormat.formatUnits(it, shareDecimals)} (at least ${TradeFormat.formatUnits(q.minimumShares, shareDecimals)})")
        }
        rows += listOf("feeAmount", q.feeAmount)
        q.transferFee?.let { rows += listOf("transferFee", "${TradeFormat.formatUnits(it, 6)} USDC") }
        rows += listOf("worth", worthOf(q)?.let { "${TradeFormat.formatUnits(it.toString(), 2)} USD" } ?: "—")
        rows += listOf("signBy", q.signBy)
        rows += listOf("validUntil", q.validUntil)
        table(quoteTable, null, rows)
        render()
    }

    private fun renderSteps() {
        stepsTable.removeAllViews()
        if (steps.isNotEmpty()) {
            table(
                stepsTable,
                listOf("step", "state", "status", "reason", "transaction"),
                steps.map { listOf(it.step.wire, it.state.wire, it.status.orEmpty(), it.reason.orEmpty(), it.transactionHash.orEmpty()) },
            )
        }
        orderText.text = order?.let { o ->
            buildString {
                append(o.status)
                o.statusReason?.let { append(" · $it") }
                append(" · out ${TradeFormat.formatUnits(o.amountOut, o.tokenOutDecimals)}")
                o.transactionHash?.let { append(" · $it") }
            }
        }.orEmpty()
        orderText.setTextColor(if (order?.status == "Completed") TEAL else PINK)
    }

    private fun renderOrders() {
        ordersTable.removeAllViews()
        resumeButtons.clear()
        val list = openOrders ?: return render()
        if (list.isEmpty()) {
            ordersTable.addView(Ui.caption(activity, "no open orders"))
            return render()
        }
        ordersTable.addView(tableRow(listOf("order", "trade", "status", "next step", ""), header = true))
        for (open in list) {
            val row = tableRow(listOf(open.orderId, "${open.side.wire} ${open.market.wire}", open.status, open.nextStep?.wire ?: "—"), header = false)
            val resume = button("Resume") { resume(open) }
            resumeButtons += open to resume
            row.addView(resume)
            ordersTable.addView(row)
        }
        render()
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

    private fun <T> choice(options: List<Pair<T, String>>, into: MutableList<Pair<T, Button>>, onPick: (T) -> Unit): View =
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

    private fun LinearLayout.children(): List<View> = (0 until childCount).map(::getChildAt)

    private companion object {
        const val PINK = 0xFFFF407F.toInt()
        const val TEAL = 0xFF57C9F9.toInt()
    }
}
