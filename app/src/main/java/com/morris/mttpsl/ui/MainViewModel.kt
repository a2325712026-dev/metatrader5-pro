package com.morris.mttpsl.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morris.mttpsl.api.*
import com.morris.mttpsl.domain.BatchClose
import com.morris.mttpsl.domain.BatchResult
import com.morris.mttpsl.domain.TpSlValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.max

enum class Tab(val label: String) { QUOTES("行情"), CHART("图表"), TRADE("交易"), HISTORY("历史"), SETTINGS("设置") }

/** 下单面板横向标签（与 MT5 一样水平排列；市价预备单为自定义功能，放在最前） */
enum class PanelTab(val label: String, val pending: PendingType? = null) {
    PREP("市价预备单"),
    MARKET("市场执行"),
    BUY_LIMIT("买入限价", PendingType.BUY_LIMIT),
    SELL_LIMIT("卖出限价", PendingType.SELL_LIMIT),
    BUY_STOP("买入止损", PendingType.BUY_STOP),
    SELL_STOP("卖出止损", PendingType.SELL_STOP),
    BUY_STOP_LIMIT("买入止损限价", PendingType.BUY_STOP_LIMIT),
    SELL_STOP_LIMIT("卖出止损限价", PendingType.SELL_STOP_LIMIT),
}

enum class TicketMode { NONE, PANEL, MODIFY_POSITION, MODIFY_ORDER }

object LineId { const val ENTRY = 0; const val STOP_LIMIT = 1; const val TP = 2; const val SL = 3 }

data class ChartState(
    val symbol: String = "EURUSD",
    val timeframe: String = "H1",
    val candles: List<Candle> = emptyList(),
    val quote: Quote? = null,
    val spec: SymbolSpec? = null,
    val mode: TicketMode = TicketMode.NONE,
    val tab: PanelTab = PanelTab.PREP,
    val expanded: Boolean = false,
    /** 用户手动点过“展开/收起”：之后切换订单类型不再重置，面板关闭时清除 */
    val expandedByUser: Boolean = false,
    val prepSide: Side = Side.BUY,
    val lotsText: String = "0.01",
    val entry: Double? = null,
    val stopLimit: Double? = null,
    val tp: Double? = null,
    val sl: Double? = null,
    val tpOn: Boolean = false,
    val slOn: Boolean = false,
    val expMode: ExpirationMode = ExpirationMode.GTC,
    val expHours: Int = 24,
    val comment: String = "",
    val margin: Double? = null,
    val editPosition: Position? = null,
    val editOrder: PendingOrder? = null,
    val busy: Boolean = false,
    /** 横向订单类型标签行上选中某类型后弹出的下单窗口（MT5 风格）是否打开 */
    val sheetOpen: Boolean = false,
) {
    /** 弹窗是否可见：修改模式总是可见；新建订单需先在标签行选择订单类型 */
    val sheetVisible get() = mode == TicketMode.MODIFY_POSITION || mode == TicketMode.MODIFY_ORDER || (mode == TicketMode.PANEL && sheetOpen)
    /** 计算预计盈亏用的开仓价 */
    val pnlEntry: Double?
        get() = when {
            isPending -> if (pendingType!!.isStopLimit) stopLimit else entry
            mode == TicketMode.MODIFY_POSITION -> editPosition?.openPrice
            else -> entryRef
        }
    val pnlVolume: Double?
        get() = when (mode) {
            TicketMode.MODIFY_POSITION -> editPosition?.volume
            TicketMode.MODIFY_ORDER -> editOrder?.volume
            else -> lots
        }
    /** 价格 price 处平仓的预计盈亏（USD） */
    fun pnlAt(price: Double, quotes: Map<String, Double>, usdPerUnitLot: Double? = null): Double? {
        val e = pnlEntry ?: return null; val v = pnlVolume ?: return null
        return com.morris.mttpsl.domain.ProfitCalc.profit(symbol, effectiveSide, v, e, price, quotes, usdPerUnitLot)
    }
    val pendingType: PendingType?
        get() = when (mode) {
            TicketMode.MODIFY_ORDER -> editOrder?.type
            TicketMode.PANEL -> tab.pending
            else -> null
        }
    val isPending get() = pendingType != null
    val isPrep get() = mode == TicketMode.PANEL && tab == PanelTab.PREP
    val isMarketExec get() = mode == TicketMode.PANEL && tab == PanelTab.MARKET
    val effectiveSide: Side
        get() = when {
            mode == TicketMode.MODIFY_POSITION -> editPosition?.side ?: Side.BUY
            pendingType != null -> pendingType!!.side
            else -> prepSide
        }
    val exitRef: Double? get() = quote?.let { if (effectiveSide == Side.BUY) it.bid else it.ask }
    val entryRef: Double? get() = quote?.let { if (effectiveSide == Side.BUY) it.ask else it.bid }
    val tpSent: Double? get() = if (tpOn) tp else null
    val slSent: Double? get() = if (slOn) sl else null
    val lots: Double? get() = lotsText.toDoubleOrNull()?.takeIf { it > 0 }
    /** 图上是否显示可拖动的线 */
    val linesActive get() = sheetVisible && !isMarketExec
    /** TP/SL 距离基准：挂单=开仓价（Stop Limit=限价），其它=平仓参考价 */
    val tpslBase: Double?
        get() = if (isPending) (if (pendingType!!.isStopLimit) stopLimit else entry) else exitRef

    fun expiration(): Expiration = when (expMode) {
        ExpirationMode.SPECIFIED -> Expiration(expMode, System.currentTimeMillis() / 1000 + expHours * 3600L)
        else -> Expiration(expMode)
    }

    fun validationError(): String? {
        val q = quote ?: return "等待报价…"
        val sp = spec ?: return "等待品种信息…"
        if (mode == TicketMode.PANEL && lots == null) return "交易量无效"
        if (isPrep && tpSent == null) return "市价预备单必须设置止盈：请点亮 TP 按钮"
        return if (isPending) TpSlValidator.validatePending(pendingType!!, q.bid, q.ask, entry, stopLimit, tpSent, slSent, sp)
        else TpSlValidator.validate(effectiveSide, q.bid, q.ask, tpSent, slSent, sp)
    }
}

data class TradeState(
    val account: AccountInfo? = null,
    val positions: List<Position> = emptyList(),
    val orders: List<PendingOrder> = emptyList(),
    val loaded: Boolean = false,
    val error: String? = null,
) {
    val floating: Double get() = account?.profit ?: positions.sumOf { it.profit }
}

data class HistoryState(val days: Int = 7, val deals: List<Deal> = emptyList(), val loading: Boolean = false)

/** 一键平仓确认对话框：记录弹窗时的品种、方向和 ticket 快照（确认时只平这些，且仍存在的持仓） */
data class BatchCloseRequest(val symbol: String, val side: Side, val tickets: List<String>)

data class Confirm(val title: String, val text: String, val confirmLabel: String = "确认", val action: suspend () -> TradeResult)

class MainViewModel : ViewModel() {
    val watchlist = listOf("EURUSD", "GBPUSD", "USDJPY", "USDCHF", "AUDUSD", "USDCAD", "XAUUSD")

    private var api: TradingApi = DemoTradingApi.shared
    private var apiKey: String? = null
    private val jobs = mutableListOf<Job>()
    private var chartJob: Job? = null
    private var marginJob: Job? = null

    private val _tab = MutableStateFlow(Tab.CHART); val tab: StateFlow<Tab> = _tab
    private val _quotes = MutableStateFlow<List<SymbolQuote>>(emptyList()); val quotes: StateFlow<List<SymbolQuote>> = _quotes
    private val _trade = MutableStateFlow(TradeState()); val trade: StateFlow<TradeState> = _trade
    private val _history = MutableStateFlow(HistoryState()); val history: StateFlow<HistoryState> = _history
    private val _chart = MutableStateFlow(ChartState()); val chart: StateFlow<ChartState> = _chart
    private val _confirm = MutableStateFlow<Confirm?>(null); val confirm: StateFlow<Confirm?> = _confirm
    private val _message = MutableStateFlow<String?>(null); val message: StateFlow<String?> = _message
    /** K 线上点击持仓线 / ✕ 后打开的平仓面板（持仓 ticket；持仓信息始终取自最新 trade.positions） */
    private val _closeId = MutableStateFlow<String?>(null); val closeId: StateFlow<String?> = _closeId
    private val _closeBusy = MutableStateFlow(false); val closeBusy: StateFlow<Boolean> = _closeBusy
    /** 一键平仓：是否需要确认（设置页开关，默认开）、待确认请求、执行中标志（防连点） */
    private val _confirmBatch = MutableStateFlow(true); val confirmBatchClose: StateFlow<Boolean> = _confirmBatch
    private val _batchRequest = MutableStateFlow<BatchCloseRequest?>(null); val batchRequest: StateFlow<BatchCloseRequest?> = _batchRequest
    private val _batchBusy = MutableStateFlow(false); val batchBusy: StateFlow<Boolean> = _batchBusy
    /** 图上拖动挂单线松手后，等待“确认修改 / 取消”的提案（null = 没有） */
    private val _orderMove = MutableStateFlow<OrderMoveProposal?>(null); val orderMove: StateFlow<OrderMoveProposal?> = _orderMove
    private val _orderMoveBusy = MutableStateFlow(false); val orderMoveBusy: StateFlow<Boolean> = _orderMoveBusy
    // 图上拖动【已开仓持仓】的 TP/SL 线：提案 → 确认 → modifyPosition
    private val _positionMove = MutableStateFlow<PositionMoveProposal?>(null); val positionMove: StateFlow<PositionMoveProposal?> = _positionMove
    private val _positionMoveBusy = MutableStateFlow(false); val positionMoveBusy: StateFlow<Boolean> = _positionMoveBusy
    /**
     * 所有协程都经由这里启动：兜底的 CoroutineExceptionHandler 会把意料之外的异常（含非 Exception 的 Error）
     * 记入崩溃日志并以提示条显示，而不是让整个 App 闪退。CancellationException 照常传播。
     */
    private val safeHandler = kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
        com.morris.mttpsl.CrashLog.record(e)
        _message.value = "❌ 内部错误：${e.javaClass.simpleName}: ${(e.message ?: "").take(120)}（详情见 设置 → 查看上次崩溃日志）"
    }
    private fun launchSafe(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) = viewModelScope.launch(safeHandler, block = block)
    private var lastError: String? = null
    /** 当前连接问题（中文原因 + 原始异常）；null = 正常。按来源记录，任一来源恢复不会清掉其它来源的问题 */
    private val _connIssue = MutableStateFlow<ConnIssue?>(null); val connIssue: StateFlow<ConnIssue?> = _connIssue
    private val issues = LinkedHashMap<String, ConnIssue>()
    private var liveArgs: LiveArgs? = null
    private data class LiveArgs(val url: String, val key: String, val symbol: String, val certFingerprint: String = "")
    private fun setIssue(src: String, issue: ConnIssue?) {
        if (issue == null) issues.remove(src) else issues[src] = issue
        val first = issues.values.firstOrNull()
        if (_connIssue.value != first) _connIssue.value = first
    }

    /**
     * 根据设置切换数据源。只有数据源真正变化时才重建（演示模式始终使用进程内共享的 DemoTradingApi，
     * 修改设置不会清空演示账户）。
     */
    fun configure(live: Boolean, serverUrlRaw: String, apiKeyValue: String, symbol: String, apiOverride: TradingApi? = null, certFingerprint: String = "") {
        val serverUrl = if (live) normalizeServerUrl(serverUrlRaw) else serverUrlRaw
        liveArgs = if (live && apiOverride == null) LiveArgs(serverUrl, apiKeyValue, symbol, certFingerprint) else null
        val key = if (apiOverride != null) "override" else if (live) "live|$serverUrl|$apiKeyValue|${normalizeFingerprint(certFingerprint) ?: certFingerprint.trim()}" else "demo"
        if (key == apiKey) return
        apiKey = key
        api = apiOverride ?: if (live) try { Mt5BridgeTradingApi(serverUrl, apiKeyValue, certFingerprint) } catch (e: Exception) {
            com.morris.mttpsl.CrashLog.record(e); toast(if (e is HttpsRequiredException) "❌ $HTTPS_REQUIRED_MESSAGE（不能用 http / ws），已回到演示模式。请在设置里改成 https:// 地址" else if (e is InvalidFingerprintException) "❌ HTTPS 证书指纹格式不对，已回到演示模式（请在设置里检查指纹）" else "❌ 服务器地址无效，已回到演示模式：${e.message?.take(80)}"); DemoTradingApi.shared
        } else DemoTradingApi.shared
        jobs.forEach { it.cancel() }; jobs.clear()
        _trade.value = TradeState(); _quotes.value = emptyList(); lastError = null; _closeId.value = null; _closeBusy.value = false
        _batchRequest.value = null; _orderMove.value = null; _orderMoveBusy.value = false
        issues.clear(); _connIssue.value = null; gate.reset()
        _positionMove.value = null; _positionMoveBusy.value = false
        jobs += launchSafe {
            while (isActive) {
                try { _quotes.value = api.quoteList(watchlist); ok("quotes") } catch (e: CancellationException) { throw e } catch (e: Exception) { reportError(e, "quotes") }
                delay(1000)
            }
        }
        jobs += launchSafe {
            while (isActive) { refreshTrade(); delay(if (live) 2000 else 1000) }
        }
        _chart.update { ChartState(timeframe = it.timeframe, lotsText = it.lotsText) }
        openChart(symbol, switchTab = false)
    }

    /** 暂时性错误（超时 / 连接被拒）连续 3 次才弹横幅 / 提示，避免网络抖动时刷屏 */
    private val gate = com.morris.mttpsl.api.TransientErrorGate()
    private fun ok(src: String) { gate.ok(src); setIssue(src, null) }

    private fun reportError(e: Exception, src: String = "other") {
        if (!gate.fail(src, com.morris.mttpsl.api.isTransientNetworkError(e))) return
        val issue = classifyError(e, liveArgs?.key)
        setIssue(src, issue)
        val m = "连接失败：${issue.title}（可在设置里点“测试连接”，或切回演示模式）"
        if (m != lastError) { lastError = m; _message.value = "❌ $m" }
    }

    /** 横幅上的“重试”：强制按当前设置重建连接并立即重新请求 */
    fun retryConnection() {
        val a = liveArgs ?: return
        lastError = null
        apiKey = null
        // v14 bug：这里没有传证书指纹 → 重建的客户端退回系统信任库，自签名证书报 “Trust anchor for certification path not found”
        configure(true, a.url, a.key, a.symbol, certFingerprint = a.certFingerprint)
    }

    /** 账户、持仓、挂单分别获取：一个接口失败不再导致整个交易页为空 */
    suspend fun refreshTrade() {
        val errors = mutableListOf<String>()
        var firstIssue: ConnIssue? = null
        var anyHard = false; var anyFail = false
        suspend fun <T> safe(name: String, block: suspend () -> T): T? = try { block() }
            catch (e: CancellationException) { throw e } catch (e: Exception) {
                anyFail = true
                if (!com.morris.mttpsl.api.isTransientNetworkError(e)) anyHard = true
                val iss = classifyError(e, liveArgs?.key)
                if (firstIssue == null) firstIssue = iss
                errors += "$name: ${iss.title}（${iss.raw.lineSequence().first().take(160)}）"; null
            }
        val a = safe("账户") { api.account() }
        val p = safe("持仓") { api.positions() }
        val o = safe("挂单") { api.orders() }
        val show = if (!anyFail) { gate.ok("trade"); false } else gate.fail("trade", !anyHard)
        if (!anyFail) setIssue("trade", null) else if (show) setIssue("trade", firstIssue)
        _trade.update {
            TradeState(a ?: it.account, p ?: it.positions, o ?: it.orders, loaded = it.loaded || p != null,
                // 暂时性错误未达阈值时不显示错误（保留上一次的数据）
                error = errors.takeIf { e -> e.isNotEmpty() && show }?.joinToString("\n"))
        }
        // 待确认的改价所针对的挂单已不存在（成交 / 撤销）→ 丢弃提案
        _orderMove.value?.let { m -> if (o != null && !_orderMoveBusy.value && o.none { it.ticket == m.ticket }) _orderMove.value = null }
        // 待确认的持仓 TP/SL 改价所针对的持仓已不存在（平仓 / 触发）→ 丢弃提案
        _positionMove.value?.let { m -> if (p != null && !_positionMoveBusy.value && p.none { it.id == m.id }) _positionMove.value = null }
    }

    fun selectTab(t: Tab) {
        _tab.value = t
        if (t == Tab.HISTORY) loadHistory(_history.value.days)
        if (t == Tab.TRADE) launchSafe { refreshTrade() }
    }

    fun clearMessage() { _message.value = null }
    private fun toast(m: String) { _message.value = m }

    // ---------------- 图表 ----------------
    fun openChart(symbol: String, timeframe: String = _chart.value.timeframe, switchTab: Boolean = true) {
        chartJob?.cancel()
        olderJob?.cancel(); noMoreHistory = false
        val same = symbol == _chart.value.symbol
        if (!same && !_orderMoveBusy.value) _orderMove.value = null
        if (!same && !_positionMoveBusy.value) _positionMove.value = null
        _chart.update {
            if (same) it.copy(timeframe = timeframe, candles = emptyList())
            else ChartState(symbol = symbol, timeframe = timeframe, lotsText = it.lotsText)
        }
        if (switchTab) _tab.value = Tab.CHART
        chartJob = launchSafe {
            try {
                // 首次加载失败（超时 / 连接被拒等）不再让图表永远空白：指数退避重试，成功后清除横幅
                var wait = 1000L
                while (true) {
                    try {
                        val spec = api.symbolSpec(symbol)
                        val cs = api.candles(symbol, timeframe, 120)
                        _chart.update { it.copy(spec = spec, candles = cs) }
                        ok("chart"); break
                    } catch (e: CancellationException) { throw e } catch (e: Exception) {
                        reportError(e, "chart"); delay(wait); wait = com.morris.mttpsl.api.nextBackoff(wait)
                    }
                }
                launch {
                    while (isActive) {
                        delay(3000)
                        try { val c = api.candles(symbol, timeframe, 120); _chart.update { it.copy(candles = mergeLatest(it.candles, c)) } }
                        catch (e: CancellationException) { throw e } catch (_: Exception) {}
                    }
                }
                api.quotes(symbol).collect { q -> _chart.update { it.copy(quote = q) } }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { reportError(e) }
        }
        refreshMargin()
    }

    private var olderJob: Job? = null
    private var noMoreHistory = false

    /** 图表滚动到左边缘附近时调用：从当前已加载数量处往前再取一批 */
    fun loadOlderCandles(batch: Int = 300) {
        if (olderJob?.isActive == true || noMoreHistory) return
        val st = _chart.value
        if (st.candles.isEmpty() || st.candles.size >= MAX_CANDLES) return
        val sym = st.symbol; val tf = st.timeframe
        olderJob = launchSafe {
            try {
                val older = api.candles(sym, tf, batch, st.candles.size)
                _chart.update { cur ->
                    if (cur.symbol != sym || cur.timeframe != tf) cur
                    else {
                        val merged = mergeOlder(older, cur.candles)
                        if (merged.size == cur.candles.size) noMoreHistory = true
                        cur.copy(candles = merged)
                    }
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) {}
        }
    }

    fun setTimeframe(tf: String) = openChart(_chart.value.symbol, tf, switchTab = false)

    fun changeSymbol(symbol: String) {
        val wasOpen = _chart.value.mode == TicketMode.PANEL
        val tab = _chart.value.tab
        openChart(symbol, switchTab = false)
        if (wasOpen) { _chart.update { it.copy(mode = TicketMode.PANEL, sheetOpen = true) }; pendingTabInit = tab }
    }
    private var pendingTabInit: PanelTab? = null

    private fun pip() = _chart.value.spec?.pip ?: 0.0001
    private fun round(v: Double) = _chart.value.spec?.let { Math.round(v / it.point) * it.point } ?: v
    private fun dir(side: Side) = if (side == Side.BUY) 1 else -1

    // ---------------- 下单面板 ----------------
    fun openPanel() = _chart.update {
        it.copy(mode = TicketMode.PANEL, sheetOpen = false, editOrder = null, editPosition = null, tpOn = false, slOn = false, entry = null, stopLimit = null)
    }

    /** 关闭下单弹窗，回到订单类型标签行（修改模式直接关闭） */
    fun closeSheet() {
        if (_chart.value.mode == TicketMode.PANEL) _chart.update { it.copy(sheetOpen = false, tpOn = false, slOn = false) } else closePanel()
    }

    fun closePanel() = _chart.update {
        it.copy(mode = TicketMode.NONE, sheetOpen = false, expanded = false, expandedByUser = false, entry = null, stopLimit = null, tp = null, sl = null, tpOn = false, slOn = false,
            editOrder = null, editPosition = null)
    }

    fun toggleExpanded() = _chart.update { it.copy(expanded = !it.expanded, expandedByUser = true) }

    fun selectPanelTab(tab: PanelTab) {
        val q = _chart.value.quote
        if (q == null) { _chart.update { it.copy(mode = TicketMode.PANEL, tab = tab, sheetOpen = true, expanded = if (it.expandedByUser) it.expanded else tab == PanelTab.MARKET) }; pendingTabInit = tab; return }
        pendingTabInit = null
        val p = pip()
        _chart.update { s ->
            val base = s.copy(mode = TicketMode.PANEL, tab = tab, sheetOpen = true, editOrder = null, editPosition = null, expMode = ExpirationMode.GTC)
            when (tab) {
                // 自定义功能：TP / SL 从【当前市价】拉出；收起面板以便在图上拖动
                PanelTab.PREP -> {
                    val m = if (s.prepSide == Side.BUY) q.bid else q.ask
                    base.copy(expanded = s.expandedByUser && s.expanded, entry = null, stopLimit = null, tp = m, sl = m, tpOn = true, slOn = true)
                }
                PanelTab.MARKET -> base.copy(expanded = if (s.expandedByUser) s.expanded else true, entry = null, stopLimit = null, tpOn = false, slOn = false)
                else -> {
                    val t = tab.pending!!
                    val e = round(when (t) {
                        PendingType.BUY_LIMIT -> q.ask - 20 * p
                        PendingType.SELL_LIMIT -> q.bid + 20 * p
                        PendingType.BUY_STOP, PendingType.BUY_STOP_LIMIT -> q.ask + 20 * p
                        PendingType.SELL_STOP, PendingType.SELL_STOP_LIMIT -> q.bid - 20 * p
                    })
                    val sl = if (t.isStopLimit) round(e - dir(t.side) * 10 * p) else null
                    base.copy(expanded = s.expandedByUser && s.expanded, entry = e, stopLimit = sl, tp = null, sl = null, tpOn = false, slOn = false)
                }
            }
        }
        refreshMargin()
    }

    /** 报价到达后补齐面板默认值 */
    private fun maybeInitTab() { pendingTabInit?.let { if (_chart.value.quote != null) selectPanelTab(it) } }

    /** 拖动结束才提交（不发送订单）；面板字段随之更新 */
    fun commitLines(values: Map<Int, Double>) {
        _chart.update { s ->
            var n = s
            values.forEach { (id, v) ->
                val r = round(v)
                n = when (id) {
                    LineId.ENTRY -> n.copy(entry = r)
                    LineId.STOP_LIMIT -> n.copy(stopLimit = r)
                    LineId.TP -> n.copy(tp = r)
                    else -> n.copy(sl = r)
                }
            }
            n
        }
        if (values.containsKey(LineId.ENTRY)) refreshMargin()
    }

    /** 面板步进按钮 → 更新价格（图上线同步移动） */
    fun stepLine(id: Int, steps: Int) {
        val d = steps * pip()
        _chart.update { s ->
            when (id) {
                LineId.ENTRY -> s.entry?.let { e ->
                    // 与拖动一致：开仓价移动时限价/TP/SL 跟随
                    s.copy(entry = round(e + d), stopLimit = s.stopLimit?.let { round(it + d) },
                        tp = s.tp?.let { round(it + d) }, sl = s.sl?.let { round(it + d) })
                } ?: s
                LineId.STOP_LIMIT -> s.copy(stopLimit = s.stopLimit?.let { round(it + d) })
                LineId.TP -> s.copy(tp = s.tp?.let { round(it + d) })
                else -> s.copy(sl = s.sl?.let { round(it + d) })
            }
        }
    }

    private fun defaultLevel(s: ChartState, tp: Boolean): Double? {
        if (s.isPrep) return s.exitRef // 自定义功能：从市价拉出
        val b = s.tpslBase ?: return null
        val sign = if (tp) 1 else -1
        return round(b + sign * dir(s.effectiveSide) * (if (tp) 20 else 15) * pip())
    }

    fun setTpOn(on: Boolean) = _chart.update { s ->
        s.copy(tpOn = on, tp = if (on) (s.tp ?: defaultLevel(s, true)) else s.tp)
    }
    fun setSlOn(on: Boolean) = _chart.update { s -> s.copy(slOn = on, sl = if (on) (s.sl ?: defaultLevel(s, false)) else s.sl) }

    fun setPrepSide(side: Side) = _chart.update { s ->
        if (s.prepSide == side) return@update s
        val m = s.exitRef
        fun flip(v: Double?) = if (v == null || m == null) v else round(2 * m - v)
        s.copy(prepSide = side, tp = flip(s.tp), sl = flip(s.sl))
    }.also { refreshMargin() }

    fun spawnLinesAtMarket() = _chart.update { s ->
        val m = s.exitRef ?: return@update s
        s.copy(tp = m, sl = m, tpOn = true, slOn = true)
    }

    fun setLotsText(t: String) { _chart.update { it.copy(lotsText = t.filter { c -> c.isDigit() || c == '.' }.take(8)) }; refreshMargin() }
    fun stepLots(dir: Int) = setLots((_chart.value.lots ?: 0.0) + dir * 0.01)
    fun setLots(v: Double) { _chart.update { it.copy(lotsText = String.format(Locale.US, "%.2f", max(0.01, v))) }; refreshMargin() }
    fun setExpMode(m: ExpirationMode) = _chart.update { it.copy(expMode = m) }
    fun stepExpHours(d: Int) = _chart.update { it.copy(expHours = (it.expHours + d).coerceIn(1, 24 * 30)) }
    fun setComment(c: String) = _chart.update { it.copy(comment = c.take(31)) }

    private fun refreshMargin() {
        marginJob?.cancel()
        marginJob = launchSafe {
            delay(250)
            val s = _chart.value
            val lots = s.lots ?: return@launchSafe
            val price = if (s.isPending) (s.stopLimit ?: s.entry) else null
            val m = try { api.calcMargin(s.symbol, s.effectiveSide, lots, price) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            _chart.update { it.copy(margin = m) }
        }
    }

    fun resetTicket() {
        val s = _chart.value
        when (s.mode) {
            TicketMode.MODIFY_POSITION -> s.editPosition?.let { editPosition(it) }
            TicketMode.MODIFY_ORDER -> s.editOrder?.let { editOrder(it) }
            TicketMode.PANEL -> selectPanelTab(s.tab)
            TicketMode.NONE -> Unit
        }
    }

    fun editPosition(p: Position) {
        if (p.symbol != _chart.value.symbol) openChart(p.symbol) else _tab.value = Tab.CHART
        _chart.update {
            it.copy(mode = TicketMode.MODIFY_POSITION, editPosition = p, editOrder = null, expanded = false, expandedByUser = false,
                tp = p.takeProfit, sl = p.stopLoss, tpOn = p.takeProfit != null, slOn = p.stopLoss != null, entry = null, stopLimit = null)
        }
    }

    fun editOrder(o: PendingOrder) {
        _orderMove.value = null
        if (o.symbol != _chart.value.symbol) openChart(o.symbol) else _tab.value = Tab.CHART
        _chart.update {
            it.copy(mode = TicketMode.MODIFY_ORDER, editOrder = o, editPosition = null, expanded = false, expandedByUser = false,
                entry = o.price, stopLimit = o.stopLimit, tp = o.takeProfit, sl = o.stopLoss,
                tpOn = o.takeProfit != null, slOn = o.stopLoss != null, expMode = o.expiration.mode,
                expHours = o.expiration.utcEpochSec?.let { e -> ((e - System.currentTimeMillis() / 1000) / 3600).toInt().coerceAtLeast(1) } ?: 24)
        }
    }

    private fun f(v: Double?): String { val d = _chart.value.spec?.digits ?: 5; return v?.let { "%.${d}f".format(it) } ?: "无" }

    /** “下单 …” / “修改” 按钮 */
    fun requestSubmit() {
        val s = _chart.value
        s.validationError()?.let { toast("❌ $it"); return }
        val exp = if (s.expMode == ExpirationMode.SPECIFIED) "${s.expHours} 小时后到期" else s.expMode.label
        val c: Confirm = when {
            s.isPrep -> Confirm("确认下单",
                "${if (s.prepSide == Side.BUY) "市价买入" else "市价卖出"} ${s.symbol} ${s.lotsText} 手\n入场: 市价 ≈ ${f(s.entryRef)}\n止盈 TP: ${f(s.tpSent)}\n止损 SL: ${f(s.slSent)}") {
                api.marketOrder(s.symbol, s.prepSide, s.lots!!, s.slSent, s.tpSent, s.comment)
            }
            s.mode == TicketMode.PANEL && s.isPending -> Confirm("确认挂单",
                "${s.tab.label} (${s.pendingType!!.label}) ${s.symbol} ${s.lotsText} 手\n${if (s.pendingType!!.isStopLimit) "触发价" else "价格"}: ${f(s.entry)}" +
                    (if (s.pendingType!!.isStopLimit) "\n限价: ${f(s.stopLimit)}" else "") +
                    "\n止盈 TP: ${f(s.tpSent)}\n止损 SL: ${f(s.slSent)}\n超期: $exp") {
                api.placePending(s.symbol, s.pendingType!!, s.lots!!, s.entry!!, s.stopLimit, s.slSent, s.tpSent, s.expiration(), s.comment)
            }
            s.mode == TicketMode.MODIFY_POSITION -> Confirm("确认修改",
                "持仓 #${s.editPosition?.id} ${s.symbol}\n止盈: ${f(s.editPosition?.takeProfit)} → ${f(s.tpSent)}\n止损: ${f(s.editPosition?.stopLoss)} → ${f(s.slSent)}") {
                api.modifyPosition(s.editPosition!!.id, s.tpSent, s.slSent)
            }
            s.mode == TicketMode.MODIFY_ORDER -> Confirm("确认修改挂单",
                "${s.pendingType?.label} #${s.editOrder?.ticket}\n价格: ${f(s.editOrder?.price)} → ${f(s.entry)}" +
                    (if (s.pendingType?.isStopLimit == true) "\n限价: ${f(s.editOrder?.stopLimit)} → ${f(s.stopLimit)}" else "") +
                    "\n止盈: ${f(s.tpSent)}\n止损: ${f(s.slSent)}\n超期: $exp") {
                api.modifyOrder(s.editOrder!!.ticket, s.entry!!, s.stopLimit, s.slSent, s.tpSent, s.expiration())
            }
            else -> return
        }
        _confirm.value = c.copy(action = { c.action().also { if (it is TradeResult.Ok) closePanel() } })
    }

    /** 市场执行：卖出 / 买入 按钮即时成交（与 MT5 一致） */
    fun marketExecute(side: Side) {
        val s = _chart.value
        val lots = s.lots ?: run { toast("❌ 交易量无效"); return }
        launchSafe {
            _chart.update { it.copy(busy = true) }
            val r = try { api.marketOrder(s.symbol, side, lots, null, null, s.comment) } catch (e: CancellationException) { throw e } catch (e: Exception) { TradeResult.Error(e.message ?: "error") }
            _chart.update { it.copy(busy = false) }
            report(r, "✅ ${if (side == Side.BUY) "买入" else "卖出"} ${s.symbol} $lots 手 已成交")
        }
    }

    // ---------------- 交易 ----------------
    fun requestClose(p: Position) {
        _confirm.value = Confirm("平仓", "${p.symbol} ${p.side.name.lowercase()} ${p.volume} #${p.id}\n浮动盈亏: %.2f USD".format(p.profit), "平仓") {
            api.closePosition(p.id).also { if (it is TradeResult.Ok && _chart.value.editPosition?.id == p.id) closePanel() }
        }
    }

    /** 图表：点击持仓线或其 ✕ → 打开平仓面板（不存在的 ticket 忽略） */
    fun openClosePanel(positionId: String) {
        if (_trade.value.positions.any { it.id == positionId }) _closeId.value = positionId
    }

    fun dismissClosePanel() { if (!_closeBusy.value) _closeId.value = null }

    /**
     * 面板“平仓”按钮：调用现有 close 接口并立即刷新持仓（线随之消失）。
     * volumeText 为面板的手数输入（v15 支持部分平仓）：按品种 volume_min / volume_step 校验，不合法时提示且不发送。
     */
    fun confirmClosePanel(volumeText: String? = null) {
        val id = _closeId.value ?: return
        val p = _trade.value.positions.firstOrNull { it.id == id } ?: run { _closeId.value = null; return }
        if (_closeBusy.value) return
        val (vmin, vstep) = closeVolumeLimits(p)
        val vol = if (volumeText == null) p.volume else {
            val (v, err) = ClosePlan.validate(p, volumeText, vmin, vstep)
            if (v == null) { toast("❌ ${err ?: "手数无效"}"); return }
            v
        }
        val full = ClosePlan.isFull(p, vol)
        _closeBusy.value = true
        launchSafe {
            val r = try { if (full) api.closePosition(p.id) else api.closePosition(p.id, vol) } catch (e: CancellationException) { throw e } catch (e: Exception) { TradeResult.Error(e.message ?: "error") }
            _closeBusy.value = false
            if (r is TradeResult.Ok) {
                _closeId.value = null
                if (_chart.value.editPosition?.id == p.id) closePanel()
            }
            report(r, if (full) "✅ 已平仓 #${p.id} ${p.symbol} ${PositionLines.lots(vol)} 手"
                else "✅ 已部分平仓 #${p.id} ${p.symbol} ${PositionLines.lots(vol)} / ${PositionLines.lots(p.volume)} 手")
            if (r is TradeResult.Ok) refreshTrade()
        }
    }

    /** 平仓手数限制（volume_min, volume_step）：持仓品种与当前图表品种相同时用服务器参数，否则用 0.01 / 0.01 */
    fun closeVolumeLimits(p: com.morris.mttpsl.api.Position): Pair<Double, Double> =
        _chart.value.spec?.takeIf { it.symbol == p.symbol }?.let { it.volumeMin to it.volumeStep } ?: (0.01 to 0.01)

    // ---------------- 一键平仓（当前品种的全部买入 / 卖出持仓） ----------------
    fun setConfirmBatchClose(on: Boolean) { _confirmBatch.value = on }

    /**
     * 点击右上角“平买 / 平卖”。无持仓或正在执行时忽略（防连点）；
     * 开启确认 → 弹窗；关闭确认 → 立即平仓。
     */
    fun requestBatchClose(side: Side) {
        if (_batchBusy.value || _batchRequest.value != null) return
        val symbol = _chart.value.symbol
        val tickets = BatchClose.select(_trade.value.positions, symbol, side).map { it.id }
        if (tickets.isEmpty()) return
        val req = BatchCloseRequest(symbol, side, tickets)
        if (_confirmBatch.value) _batchRequest.value = req else runBatchClose(req)
    }

    fun dismissBatchClose() { if (!_batchBusy.value) _batchRequest.value = null }

    fun confirmBatchClose() {
        val req = _batchRequest.value ?: return
        _batchRequest.value = null
        runBatchClose(req)
    }

    /** 逐个 ticket 调用现有 closePosition（服务端无批量接口）；统计成功/失败；最后立即刷新持仓 */
    private fun runBatchClose(req: BatchCloseRequest) {
        if (_batchBusy.value) return
        _batchBusy.value = true
        launchSafe {
            var closed = 0; var failed = 0; var firstError: String? = null
            try {
                // 对话框期间可能已被 TP/SL 平掉：只平仍然存在的 ticket，避免误报失败
                val live = _trade.value.positions.mapTo(HashSet()) { it.id }
                for (id in req.tickets.filter { it in live }) {
                    val r = try { api.closePosition(id) } catch (e: CancellationException) { throw e } catch (e: Exception) { TradeResult.Error(e.message ?: "error") }
                    if (r is TradeResult.Ok) {
                        closed++
                        if (_closeId.value == id) _closeId.value = null
                        if (_chart.value.editPosition?.id == id) closePanel()
                    } else { failed++; if (firstError == null) firstError = (r as TradeResult.Error).message }
                }
                toast(BatchClose.resultMessage(req.symbol, req.side, BatchResult(closed, failed, firstError)))
                refreshTrade() // 先刷新持仓再解除忙碌：按钮不会在旧数据上短暂可点
            } finally {
                _batchBusy.value = false
            }
        }
    }

    // ---------------- 图上直接拖动挂单线（改价）----------------
    /** 拖动中的实时校验（用最新报价）；null = 当前价位合法，可松手。找不到订单/报价时返回 null（不拦截）。 */
    fun checkOrderMove(ticket: String, kind: Int, price: Double): String? {
        val o = _trade.value.orders.firstOrNull { it.ticket == ticket } ?: return null
        val q = _chart.value.quote ?: return null
        val sp = _chart.value.spec ?: return null
        return OrderLines.validate(o, kind, OrderLines.snap(price, sp), q.bid, q.ask, sp)
    }

    /**
     * 松手：生成改价提案。价格没变 → 无事发生（线吸附回原位）；不合法 → 中文错误提示，不发请求（线吸附回原位）；
     * 合法 → 进入“确认修改 / 取消”步骤，此时才不会调用服务器。
     */
    fun proposeOrderMove(ticket: String, kind: Int, rawPrice: Double) {
        if (_orderMoveBusy.value) return
        val o = _trade.value.orders.firstOrNull { it.ticket == ticket } ?: run { _orderMove.value = null; return }
        val old = OrderLines.priceOf(o, kind) ?: run { _orderMove.value = null; return }
        val q = _chart.value.quote; val sp = _chart.value.spec
        if (q == null || sp == null) { _orderMove.value = null; toast("❌ 等待报价…"); return }
        val np = OrderLines.snap(rawPrice, sp)
        if (!OrderLines.moved(old, np, sp)) { _orderMove.value = null; return }
        val err = OrderLines.validate(o, kind, np, q.bid, q.ask, sp)
        if (err != null) { _orderMove.value = null; toast("❌ $err"); return }
        if (!_positionMoveBusy.value) _positionMove.value = null
        _orderMove.value = OrderMoveProposal(o, kind, old, np)
    }

    /** 取消：丢弃提案，线回到原价 */
    fun cancelOrderMove() { if (!_orderMoveBusy.value) _orderMove.value = null }

    /** 确认修改：调用现有 modifyOrder（服务端 POST /orders/{ticket}/modify），其余字段（TP/SL/限价/有效期）保持原值 */
    fun confirmOrderMove() {
        val p = _orderMove.value ?: return
        if (_orderMoveBusy.value) return
        val live = _trade.value.orders.firstOrNull { it.ticket == p.ticket }
        if (live == null) { _orderMove.value = null; toast("❌ 挂单 #${p.ticket} 已不存在（可能已成交或被撤销）"); return }
        val n = OrderLines.withPrice(live, p.kind, p.newPrice)
        // 发送前用最新报价再校验一次（报价可能在确认期间变化）
        val q = _chart.value.quote; val sp = _chart.value.spec
        if (q != null && sp != null) OrderLines.validate(live, p.kind, p.newPrice, q.bid, q.ask, sp)?.let {
            _orderMove.value = null; toast("❌ $it"); return
        }
        _orderMoveBusy.value = true
        launchSafe {
            val r = try { api.modifyOrder(n.ticket, n.price, n.stopLimit, n.stopLoss, n.takeProfit, n.expiration) }
                catch (e: CancellationException) { throw e } catch (e: Exception) { TradeResult.Error(e.message ?: "error") }
            try {
                val sp2 = _chart.value.spec
                report(r, "✅ 已修改挂单 #${p.ticket} ${OrderLines.kindName(p.kind, p.order)} ${OrderLines.fmt(p.oldPrice, sp2)} → ${OrderLines.fmt(p.newPrice, sp2)}")
            } finally {
                _orderMove.value = null; _orderMoveBusy.value = false
            }
        }
    }

    // ---------------- 图上直接拖动持仓的 TP / SL 线 ----------------
    /** 拖动中的实时校验（最新报价）；null = 合法。找不到持仓 / 报价时返回 null（不拦截）。 */
    fun checkPositionMove(id: String, kind: Int, price: Double): String? {
        val p = _trade.value.positions.firstOrNull { it.id == id } ?: return null
        val q = _chart.value.quote ?: return null
        val sp = _chart.value.spec ?: return null
        return PositionLineMoves.validate(p, kind, OrderLines.snap(price, sp), q.bid, q.ask, sp)
    }

    /** 松手：价格没变 → 无事发生（线吸附回原位）；不合法 → 中文错误提示，不发请求；合法 → 进入“确认修改 / 取消”（确认前不调用服务器） */
    fun proposePositionMove(id: String, kind: Int, rawPrice: Double) {
        if (_positionMoveBusy.value) return
        val p = _trade.value.positions.firstOrNull { it.id == id } ?: run { _positionMove.value = null; return }
        val old = PositionLineMoves.priceOf(p, kind) ?: run { _positionMove.value = null; return }
        val q = _chart.value.quote; val sp = _chart.value.spec
        if (q == null || sp == null) { _positionMove.value = null; toast("❌ 等待报价…"); return }
        val np = OrderLines.snap(rawPrice, sp)
        if (!OrderLines.moved(old, np, sp)) { _positionMove.value = null; return }
        val err = PositionLineMoves.validate(p, kind, np, q.bid, q.ask, sp)
        if (err != null) { _positionMove.value = null; toast("❌ $err"); return }
        if (!_orderMoveBusy.value) _orderMove.value = null // 同一时间只显示一个确认条
        _positionMove.value = PositionMoveProposal(p, kind, old, np)
    }

    fun cancelPositionMove() { if (!_positionMoveBusy.value) _positionMove.value = null }

    /** 确认修改：api.modifyPosition（服务端 POST /positions/{ticket}/modify），另一条 TP/SL 保持原值 */
    fun confirmPositionMove() {
        val m = _positionMove.value ?: return
        if (_positionMoveBusy.value) return
        val live = _trade.value.positions.firstOrNull { it.id == m.id }
        if (live == null) { _positionMove.value = null; toast("❌ 持仓 #${m.id} 已不存在（可能已平仓或触发止盈止损）"); return }
        val q = _chart.value.quote; val sp = _chart.value.spec
        if (q != null && sp != null) PositionLineMoves.validate(live, m.kind, m.newPrice, q.bid, q.ask, sp)?.let {
            _positionMove.value = null; toast("❌ $it"); return
        }
        val n = PositionLineMoves.withPrice(live, m.kind, m.newPrice)
        _positionMoveBusy.value = true
        launchSafe {
            val r = try { api.modifyPosition(n.id, n.takeProfit, n.stopLoss) }
                catch (e: CancellationException) { throw e } catch (e: Exception) { TradeResult.Error(e.message ?: "error") }
            try {
                val sp2 = _chart.value.spec
                report(r, "✅ 已修改持仓 #${m.id} ${PositionLineMoves.kindName(m.kind)} ${OrderLines.fmt(m.oldPrice, sp2)} → ${OrderLines.fmt(m.newPrice, sp2)}")
            } finally {
                _positionMove.value = null; _positionMoveBusy.value = false
            }
        }
    }

    fun requestCancel(o: PendingOrder) {
        _confirm.value = Confirm("删除挂单", "${o.type.label} ${o.symbol} ${o.volume} @ ${o.price}\n#${o.ticket}", "删除") {
            api.cancelOrder(o.ticket).also { if (it is TradeResult.Ok && _chart.value.editOrder?.ticket == o.ticket) closePanel() }
        }
    }

    fun dismissConfirm() { _confirm.value = null }

    fun runConfirm() {
        val c = _confirm.value ?: return
        _confirm.value = null
        launchSafe {
            _chart.update { it.copy(busy = true) }
            val r = try { c.action() } catch (e: CancellationException) { throw e } catch (e: Exception) { TradeResult.Error(e.message ?: "error") }
            _chart.update { it.copy(busy = false) }
            report(r, "✅ ${c.title.removePrefix("确认")}成功")
        }
    }

    /** 每次交易后立即刷新持仓/挂单 */
    private suspend fun report(r: TradeResult, ok: String) {
        when (r) {
            is TradeResult.Ok -> toast(ok)
            is TradeResult.Error -> toast("❌ ${r.message}")
        }
        refreshTrade()
    }

    // ---------------- 历史 ----------------
    fun loadHistory(days: Int) {
        _history.update { it.copy(days = days, loading = true) }
        launchSafe {
            try { val d = api.history(days); _history.update { it.copy(deals = d, loading = false) } }
            catch (e: CancellationException) { throw e } catch (e: Exception) { _history.update { it.copy(loading = false) }; reportError(e) }
        }
    }

    init {
        // 报价到达时补齐面板默认值
        launchSafe { _chart.collect { if (it.quote != null && pendingTabInit != null) maybeInitTab() } }
    }
}

const val MAX_CANDLES = 20000

/** 用最新一批替换尾部，保留更早的已加载历史（按时间去重） */
fun mergeLatest(old: List<Candle>, latest: List<Candle>): List<Candle> {
    if (latest.isEmpty()) return old
    val first = latest.first().time
    return old.filter { it.time < first } + latest
}

/** 把更早的一批接到前面（按时间去重） */
fun mergeOlder(older: List<Candle>, cur: List<Candle>): List<Candle> {
    if (cur.isEmpty()) return older
    val first = cur.first().time
    return older.filter { it.time < first } + cur
}
