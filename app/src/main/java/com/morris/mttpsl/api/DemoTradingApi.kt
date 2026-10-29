package com.morris.mttpsl.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 无需账号即可运行全部 UI 的模拟数据源（内存撮合：市价单、挂单触发、TP/SL 触发、历史）。
 *
 * 价格由独立的引擎协程推动（每 [tickMs] 毫秒所有品种走一步），每一步都检查挂单与 TP/SL，
 * 与界面是否在收集报价无关。进程内共享一个实例（[shared]），设置变更不会清空演示账户。
 */
class DemoTradingApi(autoTick: Boolean = true, seed: Int? = null, private val tickMs: Long = 250) : TradingApi {
    companion object {
        val shared: DemoTradingApi by lazy { DemoTradingApi() }
    }

    private data class Sym(val spec: SymbolSpec, var bid: Double, val spreadPts: Int, val open: Double, val vol: Double)

    private val rnd = seed?.let { Random(it) } ?: Random.Default
    private val syms = linkedMapOf(
        "EURUSD" to Sym(SymbolSpec("EURUSD", 5, 0.00001, 0), 1.12250, 8, 1.12400, 0.00006),
        "GBPUSD" to Sym(SymbolSpec("GBPUSD", 5, 0.00001, 0), 1.33210, 12, 1.33100, 0.00008),
        "USDJPY" to Sym(SymbolSpec("USDJPY", 3, 0.001, 0), 158.390, 10, 157.950, 0.010),
        "USDCHF" to Sym(SymbolSpec("USDCHF", 5, 0.00001, 0), 0.82430, 12, 0.82500, 0.00005),
        "AUDUSD" to Sym(SymbolSpec("AUDUSD", 5, 0.00001, 0), 0.69880, 10, 0.69500, 0.00005),
        "XAUUSD" to Sym(SymbolSpec("XAUUSD", 2, 0.01, 0), 2415.50, 25, 2400.00, 0.30),
    )
    private val lock = Any()
    private var nextTicket = 1000L
    private val positions = mutableListOf<Position>()
    private val orders = mutableListOf<PendingOrder>()
    private val deals = mutableListOf<Deal>()
    private val candleCache = HashMap<String, List<Candle>>()
    private var balance = 5000.0
    private val leverage = 100

    init {
        if (autoTick) {
            CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                while (isActive) {
                    // 后台线程里的未捕获异常会直接让 App 闪退 → 单步失败只记录，不影响后续
                    try { syms.keys.forEach { step(it) } } catch (e: Exception) { com.morris.mttpsl.CrashLog.record(e) }
                    delay(tickMs)
                }
            }
        }
    }

    private fun now() = System.currentTimeMillis() / 1000
    private fun sym(s: String) = syms[s] ?: syms.getValue("EURUSD")
    private fun ask(s: Sym) = s.bid + s.spreadPts * s.spec.point
    private fun r(v: Double, s: Sym) = Math.round(v / s.spec.point) * s.spec.point
    private fun contract(symbol: String) = if (symbol == "XAUUSD") 100.0 else 100_000.0

    private fun pnl(p: Position): Double {
        val s = sym(p.symbol)
        val cur = if (p.side == Side.BUY) s.bid else ask(s)
        val diff = if (p.side == Side.BUY) cur - p.openPrice else p.openPrice - cur
        return com.morris.mttpsl.domain.ProfitCalc.profit(p.symbol, p.side, p.volume, p.openPrice, cur,
            syms.mapValues { (it.value.bid + ask(it.value)) / 2 }) ?: 0.0
    }

    /** 随机走一步并执行撮合 */
    internal fun step(symbol: String): Quote = synchronized(lock) {
        val s = sym(symbol)
        s.bid = r(s.bid + rnd.nextDouble(-s.vol, s.vol), s)
        matchLocked(symbol)
    }

    /** 测试用：把 Bid 设到指定价并立即撮合 */
    internal fun setBid(symbol: String, bid: Double): Quote = synchronized(lock) {
        val s = sym(symbol)
        s.bid = r(bid, s)
        matchLocked(symbol)
    }

    private fun matchLocked(symbol: String): Quote {
        val s = sym(symbol)
        val a = ask(s)
        // 1) 挂单触发：Buy 以 Ask 成交，Sell 以 Bid 成交
        val toFill = mutableListOf<PendingOrder>()
        val toLimit = mutableListOf<PendingOrder>()
        val iter = orders.iterator()
        while (iter.hasNext()) {
            val o = iter.next()
            if (o.symbol != symbol) continue
            val hit = when (o.type) {
                PendingType.BUY_LIMIT -> a <= o.price
                PendingType.SELL_LIMIT -> s.bid >= o.price
                PendingType.BUY_STOP, PendingType.BUY_STOP_LIMIT -> a >= o.price
                PendingType.SELL_STOP, PendingType.SELL_STOP_LIMIT -> s.bid <= o.price
            }
            if (hit) { iter.remove(); if (o.type.isStopLimit) toLimit += o else toFill += o }
        }
        toLimit.forEach { o ->
            orders += o.copy(type = if (o.type.side == Side.BUY) PendingType.BUY_LIMIT else PendingType.SELL_LIMIT,
                price = o.stopLimit ?: o.price, stopLimit = null)
        }
        toFill.forEach { o -> openPosition(o.symbol, o.type.side, o.volume, o.price, o.stopLoss, o.takeProfit) }
        // 2) TP/SL：Buy 持仓以 Bid 平仓（Bid>=TP / Bid<=SL），Sell 持仓以 Ask 平仓（Ask<=TP / Ask>=SL）
        positions.filter { it.symbol == symbol }.forEach { p ->
            val cur = if (p.side == Side.BUY) s.bid else a
            val tpHit = p.takeProfit?.let { if (p.side == Side.BUY) cur >= it - 1e-9 else cur <= it + 1e-9 } ?: false
            val slHit = p.stopLoss?.let { if (p.side == Side.BUY) cur <= it + 1e-9 else cur >= it - 1e-9 } ?: false
            if (tpHit || slHit) closeLocked(p, if (tpHit) "tp" else "sl")
        }
        return Quote(symbol, s.bid, a, System.currentTimeMillis())
    }

    private fun openPosition(symbol: String, side: Side, vol: Double, price: Double, sl: Double?, tp: Double?): Position {
        val p = Position("${nextTicket++}", symbol, side, vol, price, tp, sl)
        positions += p
        deals += Deal("${nextTicket++}", p.id, now(), symbol, side, "IN", vol, price, 0.0, 0.0, 0.0)
        return p
    }

    private fun closeLocked(p: Position, reason: String = "") {
        val s = sym(p.symbol)
        val profit = pnl(p)
        positions.removeAll { it.id == p.id }
        balance += profit
        deals += Deal("${nextTicket++}", p.id, now(), p.symbol, if (p.side == Side.BUY) Side.SELL else Side.BUY, "OUT",
            p.volume, if (p.side == Side.BUY) s.bid else ask(s), profit, 0.0, 0.0)
    }

    override suspend fun symbolSpec(symbol: String) = sym(symbol).spec

    /** 每个 symbol|tf 一条升序历史；向左滚动时按需往前生成更多 */
    override suspend fun candles(symbol: String, timeframe: String, limit: Int, offset: Int): List<Candle> = synchronized(lock) {
        val key = "$symbol|$timeframe"
        val s = sym(symbol)
        val minutes = mapOf("M1" to 1, "M5" to 5, "M15" to 15, "M30" to 30, "H1" to 60, "H4" to 240, "D1" to 1440)[timeframe] ?: 1
        val stepSize = s.vol * 4 * sqrt(minutes.toDouble())
        var list = candleCache[key]
        if (list == null) {
            val t0 = now() / (minutes * 60) * (minutes * 60)
            list = listOf(Candle(t0 * 1000, s.bid, s.bid, s.bid, s.bid))
        } else {
            val last = list.last()
            list = list.dropLast(1) + last.copy(close = s.bid, high = maxOf(last.high, s.bid), low = minOf(last.low, s.bid))
        }
        val need = offset + limit
        if (list.size < need && list.size < 20000) {
            val g = Random((key.hashCode() * 31 + list.size).toLong())
            val older = ArrayList<Candle>()
            var c = list.first().open
            var t = list.first().time / 1000
            repeat(need - list.size) {
                t -= minutes * 60L
                val o = c - g.nextDouble(-stepSize, stepSize)
                val h = maxOf(o, c) + g.nextDouble(0.0, stepSize * 0.6)
                val l = minOf(o, c) - g.nextDouble(0.0, stepSize * 0.6)
                older += Candle(t * 1000, r(o, s), r(h, s), r(l, s), r(c, s))
                c = o
            }
            older.reverse()
            list = older + list
        }
        candleCache[key] = list
        val end = (list.size - offset).coerceAtLeast(0)
        list.subList((end - limit).coerceAtLeast(0), end).toList()
    }

    private fun currentQuote(symbol: String): Quote = synchronized(lock) {
        val s = sym(symbol); Quote(symbol, s.bid, ask(s), System.currentTimeMillis())
    }

    override fun quotes(symbol: String): Flow<Quote> = flow {
        while (true) { emit(currentQuote(symbol)); delay(tickMs) }
    }

    override suspend fun quoteList(symbols: List<String>): List<SymbolQuote> = symbols.mapNotNull { name ->
        if (!syms.containsKey(name)) return@mapNotNull null
        val q = currentQuote(name); val s = sym(name)
        SymbolQuote(name, q.bid, q.ask, s.spec.digits, s.spec.point, s.spreadPts, (q.bid - s.open) / s.open * 100, q.time)
    }

    private fun marginOf(symbol: String, volume: Double, price: Double): Double =
        volume * contract(symbol) * (if (symbol.startsWith("USD")) 1.0 else price) / leverage

    override suspend fun calcMargin(symbol: String, side: Side, volume: Double, price: Double?): Double? = synchronized(lock) {
        val s = sym(symbol)
        Math.round(marginOf(symbol, volume, price ?: (if (side == Side.BUY) ask(s) else s.bid)) * 100) / 100.0
    }

    override suspend fun account(): AccountInfo = synchronized(lock) {
        val floating = positions.sumOf { pnl(it) }
        val margin = positions.sumOf { marginOf(it.symbol, it.volume, sym(it.symbol).bid) }
        AccountInfo("DEMO", "演示模式", "USD", balance, balance + floating, margin, balance + floating - margin, leverage)
    }

    override suspend fun positions(): List<Position> = synchronized(lock) {
        positions.map { p ->
            val s = sym(p.symbol)
            p.copy(currentPrice = if (p.side == Side.BUY) s.bid else ask(s), profit = pnl(p))
        }
    }

    override suspend fun orders(): List<PendingOrder> = synchronized(lock) { orders.toList() }
    override suspend fun history(days: Int): List<Deal> = synchronized(lock) {
        val from = now() - days * 86400L
        deals.filter { it.time >= from }.sortedByDescending { it.time }
    }

    override suspend fun marketOrder(symbol: String, side: Side, volume: Double, sl: Double?, tp: Double?, comment: String): TradeResult {
        synchronized(lock) {
            val s = sym(symbol)
            openPosition(symbol, side, volume, if (side == Side.BUY) ask(s) else s.bid, sl, tp)
        }
        return TradeResult.Ok("TRADE_RETCODE_DONE")
    }

    override suspend fun modifyPosition(positionId: String, takeProfit: Double?, stopLoss: Double?): TradeResult {
        synchronized(lock) {
            val i = positions.indexOfFirst { it.id == positionId }
            if (i < 0) return TradeResult.Error("position not found")
            positions[i] = positions[i].copy(takeProfit = takeProfit, stopLoss = stopLoss)
        }
        return TradeResult.Ok("TRADE_RETCODE_DONE")
    }

    override suspend fun closePosition(positionId: String): TradeResult {
        synchronized(lock) {
            val p = positions.firstOrNull { it.id == positionId } ?: return TradeResult.Error("position not found")
            closeLocked(p)
        }
        return TradeResult.Ok("TRADE_RETCODE_DONE")
    }

    override suspend fun closePosition(positionId: String, volume: Double): TradeResult {
        synchronized(lock) {
            val i = positions.indexOfFirst { it.id == positionId }
            if (i < 0) return TradeResult.Error("position not found")
            val p = positions[i]
            if (volume >= p.volume - 1e-9) { closeLocked(p); return TradeResult.Ok("TRADE_RETCODE_DONE") }
            val part = p.copy(volume = volume, profit = p.profit * volume / p.volume)
            closeLocked(part)
            if (positions.none { it.id == positionId }) positions.add(i.coerceAtMost(positions.size), p.copy(volume = Math.round((p.volume - volume) * 1e8) / 1e8, profit = p.profit * (p.volume - volume) / p.volume))
            else positions[positions.indexOfFirst { it.id == positionId }] = p.copy(volume = Math.round((p.volume - volume) * 1e8) / 1e8)
        }
        return TradeResult.Ok("TRADE_RETCODE_DONE")
    }

    override suspend fun placePending(
        symbol: String, type: PendingType, volume: Double, price: Double, stopLimit: Double?,
        sl: Double?, tp: Double?, expiration: Expiration, comment: String,
    ): TradeResult {
        synchronized(lock) {
            orders += PendingOrder("${nextTicket++}", symbol, type, volume, price, stopLimit, tp, sl, expiration)
        }
        return TradeResult.Ok("TRADE_RETCODE_DONE")
    }

    override suspend fun modifyOrder(
        ticket: String, price: Double, stopLimit: Double?, sl: Double?, tp: Double?, expiration: Expiration,
    ): TradeResult {
        synchronized(lock) {
            val i = orders.indexOfFirst { it.ticket == ticket }
            if (i < 0) return TradeResult.Error("order not found")
            orders[i] = orders[i].copy(price = price, stopLimit = stopLimit, stopLoss = sl, takeProfit = tp, expiration = expiration)
        }
        return TradeResult.Ok("TRADE_RETCODE_DONE")
    }

    override suspend fun cancelOrder(ticket: String): TradeResult {
        synchronized(lock) { if (!orders.removeAll { it.ticket == ticket }) return TradeResult.Error("order not found") }
        return TradeResult.Ok("TRADE_RETCODE_DONE")
    }
}
