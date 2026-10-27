package com.morris.mttpsl.ui

import com.morris.mttpsl.api.Position
import com.morris.mttpsl.domain.ProfitCalc
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
 * 平仓方案。MT5 桥接服务 POST /positions/{ticket}/close 只接受 ticket、总是平掉整个持仓
 * （server/main.py 不接受 volume），因此只提供全部平仓；若将来服务端支持，把 PARTIAL_SUPPORTED 置 true
 * 并在 TradingApi.closePosition 传 volume 即可。
 */
object ClosePlan {
    const val PARTIAL_SUPPORTED = false

    /** 实际平仓手数：不支持部分平仓时恒为全部手数 */
    fun volumeToClose(p: Position, requestedText: String? = null, partialSupported: Boolean = PARTIAL_SUPPORTED): Double {
        if (!partialSupported) return p.volume
        val v = requestedText?.trim()?.toDoubleOrNull() ?: return p.volume
        val r = Math.round(v * 100) / 100.0
        return if (r <= 0.0 || r >= p.volume) p.volume else r
    }

    fun isFull(p: Position, volume: Double) = volume >= p.volume - 1e-9

    fun summary(p: Position, currency: String): String =
        "${p.symbol}  ${p.side.name.lowercase()}  ${PositionLines.lots(p.volume)} 手\n#${p.id}\n" +
            "开仓价 ${p.openPrice}   现价 ${p.currentPrice}\n浮动盈亏 ${ProfitCalc.format(p.profit, currency)}"
}
