package com.morris.mttpsl.domain

import com.morris.mttpsl.api.PendingType
import com.morris.mttpsl.api.Side
import com.morris.mttpsl.api.SymbolSpec

object TpSlValidator {
    private const val EPS = 1e-9

    /**
     * 持仓/市价单：BUY 以 Bid 平仓：TP > bid + minDist，SL < bid - minDist
     * SELL 以 Ask 平仓：TP < ask - minDist，SL > ask + minDist
     */
    fun validate(side: Side, bid: Double, ask: Double, tp: Double?, sl: Double?, spec: SymbolSpec): String? {
        val minDist = spec.stopsLevelPoints * spec.point
        val ref = if (side == Side.BUY) bid else ask
        return relative(side, ref, tp, sl, minDist, "现价", spec)
    }

    /** 挂单：开仓价相对 Bid/Ask 的方向 + TP/SL 相对开仓价（Stop Limit 以限价为准） */
    fun validatePending(
        type: PendingType, bid: Double, ask: Double, price: Double?, stopLimit: Double?,
        tp: Double?, sl: Double?, spec: SymbolSpec,
    ): String? {
        if (price == null) return "请设置开仓价"
        val d = spec.stopsLevelPoints * spec.point
        val f = "%.${spec.digits}f"
        when (type) {
            PendingType.BUY_LIMIT -> if (price > ask - d - EPS) return "Buy Limit 价格必须低于 Ask ${f.format(ask)}"
            PendingType.SELL_LIMIT -> if (price < bid + d + EPS) return "Sell Limit 价格必须高于 Bid ${f.format(bid)}"
            PendingType.BUY_STOP, PendingType.BUY_STOP_LIMIT ->
                if (price < ask + d + EPS) return "${type.label} 触发价必须高于 Ask ${f.format(ask)}"
            PendingType.SELL_STOP, PendingType.SELL_STOP_LIMIT ->
                if (price > bid - d - EPS) return "${type.label} 触发价必须低于 Bid ${f.format(bid)}"
        }
        return validatePendingTpSl(type, price, stopLimit, tp, sl, spec)
    }

    /** 挂单中与市价无关的部分：Stop Limit 的限价/触发价顺序 + TP/SL 相对开仓价（Stop Limit 以限价为准） */
    fun validatePendingTpSl(type: PendingType, price: Double, stopLimit: Double?, tp: Double?, sl: Double?, spec: SymbolSpec): String? {
        val d = spec.stopsLevelPoints * spec.point
        if (type.isStopLimit) {
            if (stopLimit == null) return "Stop Limit 需要设置限价"
            if (type == PendingType.BUY_STOP_LIMIT && stopLimit >= price) return "Buy Stop Limit 的限价必须低于触发价"
            if (type == PendingType.SELL_STOP_LIMIT && stopLimit <= price) return "Sell Stop Limit 的限价必须高于触发价"
        }
        val base = if (type.isStopLimit) stopLimit!! else price
        return relative(type.side, base, tp, sl, d, "开仓价", spec)
    }

    private fun relative(side: Side, ref: Double, tp: Double?, sl: Double?, d: Double, refName: String, spec: SymbolSpec): String? {
        val pts = spec.stopsLevelPoints
        val extra = if (pts > 0) "至少 $pts 点" else ""
        if (side == Side.BUY) {
            if (tp != null && tp < ref + d + EPS) return "TP 必须高于$refName$extra"
            if (sl != null && sl > ref - d - EPS) return "SL 必须低于$refName$extra"
        } else {
            if (tp != null && tp > ref - d - EPS) return "TP 必须低于$refName$extra"
            if (sl != null && sl < ref + d + EPS) return "SL 必须高于$refName$extra"
        }
        return null
    }

    fun pips(a: Double, b: Double, spec: SymbolSpec) = (a - b) / spec.pip
    fun points(a: Double, b: Double, spec: SymbolSpec) = Math.round((a - b) / spec.point)
}
