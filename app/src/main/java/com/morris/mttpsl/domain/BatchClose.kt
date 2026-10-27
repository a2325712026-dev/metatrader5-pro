package com.morris.mttpsl.domain

import com.morris.mttpsl.api.Position
import com.morris.mttpsl.api.Side
import java.util.Locale

/** 一键平仓的汇总：笔数、总手数、总浮动盈亏（USD，含等价账户货币）。 */
data class BatchSummary(val count: Int, val totalLots: Double, val totalProfit: Double) {
    val isEmpty get() = count == 0
}

/** 批量平仓的结果：成功 / 失败笔数，以及第一条失败原因（用于提示）。 */
data class BatchResult(val closed: Int, val failed: Int, val firstError: String? = null) {
    val total get() = closed + failed
    val allOk get() = failed == 0 && closed > 0
}

/**
 * 图表右上角“一键平仓”按钮的纯逻辑（不依赖 Compose / Android，便于单元测试）。
 * 服务端没有批量接口，所以按 ticket 逐笔调用现有的 closePosition。
 */
object BatchClose {
    /** 当前品种、指定方向的全部持仓（品种严格相等，方向相同） */
    fun select(positions: List<Position>, symbol: String, side: Side): List<Position> =
        positions.filter { it.symbol == symbol && it.side == side }

    fun summary(selected: List<Position>): BatchSummary {
        // 手数按 0.01 精度累加，避免 0.1+0.2 的浮点误差；盈亏按分累加
        val lots = Math.round(selected.sumOf { it.volume } * 100) / 100.0
        val pl = Math.round(selected.sumOf { it.profit } * 100) / 100.0
        return BatchSummary(selected.size, lots, pl)
    }

    /** 按钮可用：该品种该方向有持仓，且当前没有正在执行的批量平仓，且持仓列表已加载 */
    fun enabled(positions: List<Position>, symbol: String, side: Side, busy: Boolean = false, loaded: Boolean = true): Boolean =
        loaded && !busy && positions.any { it.symbol == symbol && it.side == side }

    fun sideLabel(side: Side) = if (side == Side.BUY) "买入" else "卖出"

    /** 按钮第一行文字：“平买 ×3” / 无持仓时“平买” */
    fun buttonTitle(side: Side, count: Int) =
        (if (side == Side.BUY) "平买" else "平卖") + if (count > 0) " ×$count" else ""

    /** 按钮第二行：浮动盈亏（带符号）；无持仓为“—” */
    fun buttonSub(s: BatchSummary) =
        if (s.isEmpty) "—" else String.format(Locale.US, "%+.2f", s.totalProfit)

    fun lots(v: Double) = String.format(Locale.US, "%.2f", v)

    fun dialogTitle(symbol: String, side: Side) = "一键平仓 ${symbol} ${sideLabel(side)}"

    /** 确认对话框正文（中文）：方向、笔数、总手数、总盈亏（USD） */
    fun dialogText(symbol: String, side: Side, s: BatchSummary, currency: String = "USD"): String =
        "即将平掉 $symbol 的全部${sideLabel(side)}持仓：\n" +
            "方向：${sideLabel(side)}（${side.name.lowercase()}）\n" +
            "持仓数量：${s.count} 笔\n" +
            "总手数：${lots(s.totalLots)} 手\n" +
            "总浮动盈亏：${ProfitCalc.formatSigned(s.totalProfit, currency)}"

    /** 结果提示（snackbar） */
    fun resultMessage(symbol: String, side: Side, r: BatchResult): String = when {
        r.total == 0 -> "没有可平的${sideLabel(side)}持仓"
        r.failed == 0 -> "✅ 已平仓 ${r.closed} 笔 $symbol ${sideLabel(side)}持仓"
        r.closed == 0 -> "❌ ${r.failed} 笔全部平仓失败" + (r.firstError?.let { "：$it" } ?: "")
        else -> "⚠️ 已平仓 ${r.closed} 笔，失败 ${r.failed} 笔" + (r.firstError?.let { "（$it）" } ?: "")
    }
}
