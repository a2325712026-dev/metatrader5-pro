package com.morris.mttpsl.domain

import com.morris.mttpsl.api.Position
import com.morris.mttpsl.api.Side
import java.util.Locale

/**
 * 盈亏（账户货币 USD）计算，与 MT5 order_calc_profit 的外汇/贵金属公式一致：
 *   profit = (平仓价 - 开仓价) × 方向 × 手数 × 合约大小，再把报价货币换算成 USD。
 * 实盘时优先使用服务器持仓 profit 字段反推每价格单位的 USD 值（与 MT5 完全一致，不需修改服务器）。
 */
object ProfitCalc {
    fun contractSize(symbol: String): Double = when {
        symbol.startsWith("XAU") -> 100.0
        symbol.startsWith("XAG") -> 5000.0
        else -> 100_000.0
    }

    /** 报价货币 → USD 的汇率（1 单位报价货币 = ? USD）；price = 当前品种价格，quotes = 品种→中间价 */
    fun quoteToUsd(symbol: String, price: Double, quotes: Map<String, Double>): Double? {
        if (symbol.length < 6) return null
        val base = symbol.substring(0, 3); val quote = symbol.substring(3, 6)
        if (quote == "USD") return 1.0
        if (base == "USD") return if (price > 0) 1.0 / price else null
        quotes["${quote}USD"]?.takeIf { it > 0 }?.let { return it }
        quotes["USD$quote"]?.takeIf { it > 0 }?.let { return 1.0 / it }
        return null
    }

    /** 每 1 价格单位 × 1 手 的 USD 值 */
    fun usdPerUnitLot(symbol: String, price: Double, quotes: Map<String, Double> = emptyMap()): Double? =
        quoteToUsd(symbol, price, quotes)?.let { contractSize(symbol) * it }

    fun profit(symbol: String, side: Side, volume: Double, open: Double, close: Double,
               quotes: Map<String, Double> = emptyMap(), usdPerUnitLot: Double? = null): Double? {
        val diff = if (side == Side.BUY) close - open else open - close
        val k = usdPerUnitLot ?: usdPerUnitLot(symbol, close, quotes) ?: return null
        return Math.round(diff * volume * k * 100) / 100.0
    }

    /** 由服务器返回的持仓 profit 反推每价格单位每手 USD 值（MT5 真实值），无法反推时返回 null */
    fun calibrate(positions: List<Position>, symbol: String): Double? = positions
        .filter { it.symbol == symbol && it.volume > 0 && kotlin.math.abs(it.currentPrice - it.openPrice) > 1e-9 && it.profit != 0.0 }
        .map { val d = if (it.side == Side.BUY) it.currentPrice - it.openPrice else it.openPrice - it.currentPrice; it.profit / (d * it.volume) }
        .filter { it > 0 }
        .takeIf { it.isNotEmpty() }?.average()

    /** 由品种参数（MT5 trade_tick_value / trade_tick_size）得到每价格单位每手的账户货币值；参数缺失返回 null */
    fun unitValueFromSpec(tickValue: Double, tickSize: Double): Double? =
        if (tickValue.isFinite() && tickSize.isFinite() && tickValue > 0 && tickSize > 0) tickValue / tickSize else null

    /** 由合约大小推出（报价货币为账户货币时）：contractSize × quoteToUsd */
    fun unitValueFromContract(symbol: String, contractSize: Double, price: Double, quotes: Map<String, Double> = emptyMap()): Double? =
        if (contractSize > 0) quoteToUsd(symbol, price, quotes)?.let { contractSize * it } else null

    /** 有符号点数：(平仓价 − 开仓价) × 方向 / point */
    fun points(side: Side, open: Double, close: Double, point: Double): Long {
        if (point <= 0 || !point.isFinite()) return 0
        val diff = if (side == Side.BUY) close - open else open - close
        return Math.round(diff / point)
    }

    /** MT5 拖线标签的数值部分：“+726.30 USD, 7263 points”（点数为与开仓价的距离，不带符号；盈亏带符号） */
    fun pnlPoints(profit: Double?, points: Long, currency: String = "USD"): String =
        "${formatSigned(profit, currency)}, ${kotlin.math.abs(points)} points"

    /** MT5 拖线标签：“TP, +726.30 USD, 7263 points” / “SL, -12.00 USD, -120 points” */
    fun lineLabel(name: String, profit: Double?, points: Long, currency: String = "USD"): String =
        "$name, ${pnlPoints(profit, points, currency)}"

    /** MT5 风格：-1.23 USD */
    fun format(v: Double?, currency: String = "USD"): String =
        if (v == null) "— $currency" else String.format(Locale.US, "%.2f %s", v, currency)
    fun formatSigned(v: Double?, currency: String = "USD"): String =
        if (v == null) "— $currency" else String.format(Locale.US, "%+.2f %s", v, currency)
}
