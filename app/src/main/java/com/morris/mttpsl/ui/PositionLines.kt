package com.morris.mttpsl.ui

import com.morris.mttpsl.api.Position
import com.morris.mttpsl.api.SymbolSpec
import com.morris.mttpsl.domain.ProfitCalc
import com.morris.mttpsl.domain.TpSlValidator
import kotlin.math.abs

/** 图上持仓线的纯逻辑（命中测试、标签、平仓方案），不依赖 Compose，便于单元测试。 */
data class FRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom
    fun inflate(d: Float) = FRect(left - d, top - d, right + d, bottom + d)
    val width get() = right - left
    val height get() = bottom - top
}

object PositionLines {
    /** 标签：#ticket 方向 手数 盈亏(USD)，与 MT5 一致 */
    fun label(p: Position, currency: String): String =
        "#${p.id} ${p.side.name.lowercase()} ${lots(p.volume)}  ${ProfitCalc.format(p.profit, currency)}"

    fun lots(v: Double): String = String.format(java.util.Locale.US, "%.2f", v)

    /** 标签矩形：线上方，从 x=4 起（与绘制一致） */
    fun labelRect(textW: Float, textH: Float, lineY: Float) =
        FRect(4f, lineY - textH - 4f, 4f + textW + 12f, lineY)

    /** ✕ 按钮：紧贴标签右侧的方块，高度与标签相同 */
    fun closeButtonRect(label: FRect) = FRect(label.right, label.top, label.right + label.height, label.bottom)

    /** 触点是否在 ✕ 按钮上（hitPad 为额外触控余量） */
    fun hitCloseButton(label: FRect, x: Float, y: Float, hitPad: Float): Boolean =
        closeButtonRect(label).inflate(hitPad).contains(x, y)

    /**
     * 点击是否命中持仓线：在线 ±tol 内或在标签（含 ✕）内；多条时取最近的。
     * lineYs[i] 为第 i 条线的 y，textWs/textHs 为标签文字尺寸；返回索引，未命中返回 null。
     * x 在价格轴（>= plotW）时不命中。
     */
    fun hitLine(
        lineYs: FloatArray, textWs: FloatArray, textHs: FloatArray,
        x: Float, y: Float, plotW: Float, plotH: Float, tol: Float,
    ): Int? = hitLineDist(lineYs, textWs, textHs, x, y, plotW, plotH, tol)?.first

    /** 同 [hitLine]，另返回触点到该线的“有效距离”：落在标签（含 ✕）内记 0，否则为 |线y−触点y|；供与挂单线的优先级裁决 */
    fun hitLineDist(
        lineYs: FloatArray, textWs: FloatArray, textHs: FloatArray,
        x: Float, y: Float, plotW: Float, plotH: Float, tol: Float,
    ): Pair<Int, Float>? {
        if (x < 0f || x >= plotW || y < 0f || y > plotH) return null
        var best: Int? = null; var bestD = Float.MAX_VALUE; var bestEff = 0f
        for (i in lineYs.indices) {
            val ly = lineYs[i]
            if (ly < 0f || ly > plotH) continue
            val lab = labelRect(textWs[i], textHs[i], ly)
            val inLabel = lab.contains(x, y) || closeButtonRect(lab).contains(x, y)
            val d = abs(ly - y)
            if ((d <= tol || inLabel) && d < bestD) { best = i; bestD = d; bestEff = if (inLabel) 0f else d }
        }
        return best?.let { it to bestEff }
    }
}

/**
 * 平仓方案（v15 起支持部分平仓，与 MT5 一致）：服务器 POST /positions/{ticket}/close {"volume": v}
 * → order_send(TRADE_ACTION_DEAL, 反向, position = ticket, volume = v)。
 * 手数必须 ≥ volume_min、为 volume_step 的整数倍、≤ 持仓手数；部分平仓后剩余手数也必须 ≥ volume_min。
 */
object ClosePlan {
    const val PARTIAL_SUPPORTED = true

    /** 实际平仓手数（宽松版，无品种参数时使用）：无效输入 / 超出 → 全部手数 */
    fun volumeToClose(p: Position, requestedText: String? = null, partialSupported: Boolean = PARTIAL_SUPPORTED): Double {
        if (!partialSupported) return p.volume
        val v = requestedText?.trim()?.replace(',', '.')?.toDoubleOrNull() ?: return p.volume
        val r = Math.round(v * 100) / 100.0
        return if (r <= 0.0 || r >= p.volume) p.volume else r
    }

    fun isFull(p: Position, volume: Double) = volume >= p.volume - 1e-9

    /** 小数位数（由 step 推出，如 0.01 → 2） */
    fun stepDecimals(step: Double): Int =
        java.math.BigDecimal(step.toString()).stripTrailingZeros().scale().coerceIn(0, 8)

    fun fmtVolume(v: Double, step: Double = 0.01): String =
        String.format(java.util.Locale.US, "%.${stepDecimals(step).coerceAtLeast(2)}f", v)

    /** 严格校验（与服务器 close_volume 一致）。返回 (手数, null) 或 (null, 错误文字)。 */
    fun validate(p: Position, text: String, vmin: Double, vstep: Double): Pair<Double?, String?> {
        val v = text.trim().replace(',', '.').toDoubleOrNull() ?: return null to "请输入平仓手数"
        if (!v.isFinite() || v <= 0.0) return null to "手数必须大于 0"
        if (v > p.volume + 1e-9) return null to "不能超过持仓手数 ${fmtVolume(p.volume, vstep)}"
        if (abs(v - p.volume) < 1e-9) return p.volume to null
        if (vmin > 0 && v < vmin - 1e-9) return null to "最小手数 ${fmtVolume(vmin, vstep)}"
        var r = v
        if (vstep > 0) {
            val n = Math.round(v / vstep)
            if (abs(n * vstep - v) > 1e-7) return null to "手数必须是 ${fmtVolume(vstep, vstep)} 的整数倍"
            r = java.math.BigDecimal(n * vstep).setScale(8, java.math.RoundingMode.HALF_UP).toDouble()
        }
        val rest = p.volume - r
        if (rest > 1e-9 && vmin > 0 && rest < vmin - 1e-9) return null to "剩余手数不能小于最小手数 ${fmtVolume(vmin, vstep)}"
        return r to null
    }

    /** “−/+” 步进按钮：按 step 增减并夹在 [vmin, 持仓手数] 内；无法解析时从全部手数开始 */
    fun step(p: Position, text: String, delta: Int, vmin: Double, vstep: Double): String {
        val cur = text.trim().replace(',', '.').toDoubleOrNull() ?: p.volume
        val st = if (vstep > 0) vstep else 0.01
        val n = Math.round(cur / st) + delta
        val v = (n * st).coerceIn(if (vmin > 0) vmin else st, p.volume)
        return fmtVolume(v, st)
    }

    /** MT5 风格标题：“平仓 #ticket buy 0.10 EURUSD” */
    fun title(p: Position): String = "平仓 #${p.id} ${p.side.name.lowercase()} ${PositionLines.lots(p.volume)} ${p.symbol}"

    fun summary(p: Position, currency: String): String =
        "${p.symbol}  ${p.side.name.lowercase()}  ${PositionLines.lots(p.volume)} 手\n#${p.id}\n" +
            "开仓价 ${p.openPrice}   现价 ${p.currentPrice}\n浮动盈亏 ${ProfitCalc.format(p.profit, currency)}"
}

/** 等待用户“确认修改”的持仓 TP/SL 改价提案（松手后生成） */
data class PositionMoveProposal(val position: Position, val kind: Int, val oldPrice: Double, val newPrice: Double) {
    val id get() = position.id
    val moved: Position get() = PositionLineMoves.withPrice(position, kind, newPrice)
}

/**
 * 已开仓持仓的 TP / SL 线可以直接在图上上下拖动（纯逻辑：线的枚举、改价、校验、确认文案）。
 *
 * v0.14 之前这两条线（图上的 "tp #id" / "sl #id"）只是【只读装饰线】：它们不在任何命中测试集合里
 * （命中集合只含 持仓开仓线 + 挂单线 + 新建/编辑订单时的 TP/SL 编辑线），所以手指在上面竖直滑动 = 落到平移，线纹丝不动。
 */
object PositionLineMoves {
    /** 所有持仓的 TP/SL 线（只含已设置的），[OrderLineRef.positionLine] = true */
    fun refs(positions: List<Position>): List<OrderLineRef> = positions.flatMap { p ->
        listOfNotNull(
            p.takeProfit?.let { OrderLineRef(p.id, LineId.TP, it, positionLine = true) },
            p.stopLoss?.let { OrderLineRef(p.id, LineId.SL, it, positionLine = true) },
        )
    }

    fun priceOf(p: Position, kind: Int): Double? = if (kind == LineId.TP) p.takeProfit else p.stopLoss

    /** 只改一条线；另一条（TP/SL）原样保留 */
    fun withPrice(p: Position, kind: Int, price: Double): Position =
        if (kind == LineId.TP) p.copy(takeProfit = price) else p.copy(stopLoss = price)

    fun kindName(kind: Int) = if (kind == LineId.TP) "止盈 TP" else "止损 SL"

    /** 校验：BUY 的 TP > Bid + 最小距离、SL < Bid - 最小距离；SELL 相反。只检查被拖动的那一条，null = 合法 */
    fun validate(p: Position, kind: Int, newPrice: Double, bid: Double, ask: Double, spec: SymbolSpec): String? =
        TpSlValidator.validate(p.side, bid, ask, if (kind == LineId.TP) newPrice else null, if (kind == LineId.SL) newPrice else null, spec)

    fun confirmText(m: PositionMoveProposal, spec: SymbolSpec?): String =
        "#${m.id} ${m.position.side.name.lowercase()} ${PositionLines.lots(m.position.volume)}  ${kindName(m.kind)}  ${OrderLines.fmt(m.oldPrice, spec)} → ${OrderLines.fmt(m.newPrice, spec)}"
}
