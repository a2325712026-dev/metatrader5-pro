package com.morris.mttpsl.ui

import com.morris.mttpsl.api.PendingOrder
import com.morris.mttpsl.api.SymbolSpec
import com.morris.mttpsl.domain.TpSlValidator
import kotlin.math.abs

/** 图上一条挂单线：ticket + 线种类（[LineId]：ENTRY / STOP_LIMIT / TP / SL）+ 价格 */
/** [positionLine] = true：这是已开仓持仓的 TP/SL 线（ticket = 持仓 id），不是挂单线 */
data class OrderLineRef(val ticket: String, val kind: Int, val price: Double, val positionLine: Boolean = false)

/** 等待用户“确认修改”的挂单改价提案（松手后生成，确认/取消后清除） */
data class OrderMoveProposal(val order: PendingOrder, val kind: Int, val oldPrice: Double, val newPrice: Double) {
    val ticket get() = order.ticket
    /** 应用改价后的挂单（其余字段保持不变） */
    val moved: PendingOrder get() = OrderLines.withPrice(order, kind, newPrice)
}

/**
 * 图上挂单线（委托订单）拖动的纯逻辑：线的枚举、命中测试、与持仓线点击的优先级、
 * 价格取整、按订单类型的有效性校验、确认文案。不依赖 Compose，便于单元测试。
 */
object OrderLines {
    /** 挂单线的触控区半径（dp）——与 TP/SL 编辑线相同（≈24dp），持仓时也要能随手抓住 */
    const val TOUCH_DP = 24f

    fun kindName(kind: Int, o: PendingOrder? = null): String = when (kind) {
        LineId.ENTRY -> if (o?.type?.isStopLimit == true) "触发价" else "价格"
        LineId.STOP_LIMIT -> "限价"
        LineId.TP -> "止盈 TP"
        else -> "止损 SL"
    }

    /** 一张挂单在图上的全部线：开仓/触发价、（Stop Limit）限价、TP、SL */
    fun linesOf(o: PendingOrder): List<OrderLineRef> = buildList {
        add(OrderLineRef(o.ticket, LineId.ENTRY, o.price))
        if (o.type.isStopLimit) o.stopLimit?.let { add(OrderLineRef(o.ticket, LineId.STOP_LIMIT, it)) }
        o.takeProfit?.let { add(OrderLineRef(o.ticket, LineId.TP, it)) }
        o.stopLoss?.let { add(OrderLineRef(o.ticket, LineId.SL, it)) }
    }

    fun priceOf(o: PendingOrder, kind: Int): Double? = when (kind) {
        LineId.ENTRY -> o.price
        LineId.STOP_LIMIT -> o.stopLimit
        LineId.TP -> o.takeProfit
        else -> o.stopLoss
    }

    /** 只改一条线的价格；其余价格（含 TP/SL）原样保留 —— 移动开仓价不会平移 TP/SL */
    fun withPrice(o: PendingOrder, kind: Int, price: Double): PendingOrder = when (kind) {
        LineId.ENTRY -> o.copy(price = price)
        LineId.STOP_LIMIT -> o.copy(stopLimit = price)
        LineId.TP -> o.copy(takeProfit = price)
        else -> o.copy(stopLoss = price)
    }

    /** 取整到最小报价单位（point） */
    fun snap(price: Double, spec: SymbolSpec): Double {
        if (price.isNaN() || price.isInfinite() || !(spec.point > 0.0)) return price // 防 BigDecimal(NaN) 崩溃 / 除零
        val v = Math.round(price / spec.point) * spec.point
        return java.math.BigDecimal(v).setScale(spec.digits, java.math.RoundingMode.HALF_UP).toDouble()
    }

    /** 与旧价相比是否真的移动了（不足半个 point 视为没动 → 直接吸附回原位，不弹确认） */
    fun moved(old: Double, new: Double, spec: SymbolSpec) = !new.isNaN() && abs(new - old) >= spec.point / 2

    /**
     * 命中测试：返回离触点 y 最近且在 tol 内的线的下标（lineYs 与 refs 一一对应；不在绘图区 [0,plotH] 的线忽略）。
     * 距离相同（<0.5px）时取下标靠前者，结果稳定。
     */
    fun hit(lineYs: FloatArray, y: Float, plotH: Float, tol: Float): Int? {
        var best: Int? = null; var bestD = Float.MAX_VALUE
        for (i in lineYs.indices) {
            val ly = lineYs[i]
            if (ly.isNaN() || ly < 0f || ly > plotH) continue
            val d = abs(ly - y)
            if (d <= tol && d < bestD - 0.5f) { best = i; bestD = d }
        }
        return best
    }

    /**
     * 挂单线 与 持仓线（点击=平仓面板）冲突时的裁决。
     * orderDist = 触点到最近挂单线的距离（无则 null）；positionDist = 触点到命中的持仓线的距离
     * （在持仓标签 / ✕ 内记 0；未命中持仓线则 null）。持仓线更近或同样近 → 归持仓线（点击打开平仓面板，拖动不抓挂单线）。
     */
    fun orderWins(orderDist: Float?, positionDist: Float?): Boolean = when {
        orderDist == null -> false
        positionDist == null -> true
        else -> orderDist < positionDist
    }

    /** 拖动手势判定：位移超过 slop 且竖直分量占优 → 拖线；否则交给平移 */
    fun isVerticalDrag(dx: Float, dy: Float, slop: Float): Boolean =
        abs(dy) > slop && abs(dy) > abs(dx)

    /** 把拖动时的 y 换算成价格后的取值：夹在可视区内 */
    fun clampY(y: Float, h: Float) = y.coerceIn(0f, h)

    /**
     * 校验把 o 的第 kind 条线改到 newPrice 是否合法（用最新 bid/ask 与品种最小止损距离）。
     * 返回 null = 合法，否则为中文错误（不应发送请求）。
     * 规则复用 [TpSlValidator.validatePending]：Buy Limit < Ask、Sell Limit > Bid、Buy Stop > Ask、Sell Stop < Bid
     * （均需满足最小止损距离）、Stop Limit 的限价与触发价顺序、TP/SL 相对开仓价（Stop Limit 以限价为准）。
     */
    fun validate(o: PendingOrder, kind: Int, newPrice: Double, bid: Double, ask: Double, spec: SymbolSpec): String? {
        val n = withPrice(o, kind, newPrice)
        // 开仓/触发价：先对市价（方向 + 最小止损距离）再对 TP/SL；限价/TP/SL：只查与自身相关的关系，不被无关的旧状态拦住
        val err = if (kind == LineId.ENTRY)
            TpSlValidator.validatePending(n.type, bid, ask, n.price, n.stopLimit, n.takeProfit, n.stopLoss, spec)
        else TpSlValidator.validatePendingTpSl(n.type, n.price, n.stopLimit, n.takeProfit, n.stopLoss, spec)
        if (err == null) return null
        // 移动开仓价（/限价）后 TP/SL 价格保持不变而失效：明确告知，不发送
        return if ((kind == LineId.ENTRY || kind == LineId.STOP_LIMIT) && (err.startsWith("TP") || err.startsWith("SL")))
            "无法移动：$err（TP/SL 价格保持不变）" else err
    }

    fun fmt(v: Double?, spec: SymbolSpec?): String = v?.let { "%.${spec?.digits ?: 5}f".format(java.util.Locale.US, it) } ?: "无"

    /** 确认条文案：旧价 → 新价 */
    fun confirmText(p: OrderMoveProposal, spec: SymbolSpec?): String =
        "#${p.ticket} ${p.order.type.label}  ${kindName(p.kind, p.order)}  ${fmt(p.oldPrice, spec)} → ${fmt(p.newPrice, spec)}"
}
