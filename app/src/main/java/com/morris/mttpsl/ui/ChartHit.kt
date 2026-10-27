package com.morris.mttpsl.ui

import kotlin.math.abs

/**
 * 图上“按下”那一刻的统一命中裁决（纯逻辑，不依赖 Compose，便于单元测试）。
 *
 * 之前（≤v10）的做法是：先让持仓线 ✕ 抢触点；再让 TP/SL 编辑线在 24dp 内抢；挂单线只有 20dp，
 * 且“持仓线更近或同样近 / 触点落在持仓标签内（距离记 0）→ 归持仓线，不抓挂单线”。
 * 结果：持仓时挂单线只要靠近持仓线、持仓标签或编辑线，就根本拖不动（落到平移 / 长按十字线）。
 *
 * 新规则：
 *  1. 持仓线 / 标签 / ✕ 永远不是“拖动目标”，只是“点击目标”。竖直拖动只在 编辑线(TP/SL…) 与 挂单线 之间裁决：
 *     挂单线在 [OrderLines.TOUCH_DP]（≈24dp）内即可抓；编辑线只有“严格更近”才抢走，平手归挂单线。
 *  2. 点击（不动）：✕ 精确命中 → 平仓面板；持仓线 ±16dp / 标签内且 不比挂单线更远 → 平仓面板。
 *  3. 触点同时在 ✕/持仓线 与 挂单线的触控区内时，手势推迟到“松手或开始竖直拖动”才决定：
 *     没移动 → 点击；竖直拖动 → 挂单线（不会弹出任何面板）。
 */
object ChartHit {
    sealed interface Drag {
        /** 新建订单 / 修改中的 TP·SL 编辑线（[LineId]） */
        data class Edit(val id: Int) : Drag
        /** 已有挂单线，index 对应传入的 orderYs */
        data class Order(val index: Int) : Drag
    }

    sealed interface Tap {
        /** 持仓标签上的 ✕ ；index 对应传入的持仓数组 */
        data class Close(val index: Int) : Tap
        /** 持仓线或标签 */
        data class Position(val index: Int) : Tap
    }

    /** drag = 竖直拖动抓取的线（可空）；tap = 不动抬起时执行的点击（可空） */
    data class Result(val drag: Drag?, val tap: Tap?)

    /** 编辑线 vs 挂单线：编辑线必须“严格更近”（差 > 0.5px）才胜出；平手归挂单线 */
    fun editBeatsOrder(editDist: Float, orderDist: Float?) = orderDist == null || editDist < orderDist - 0.5f

    /**
     * @param editYs  4 条编辑线的 y（NaN = 不可见/不存在），下标 = [LineId]
     * @param orderYs 所有已有挂单线的 y
     * @param posYs/posTextWs/posTextHs 持仓线的 y 与标签文字尺寸
     * @param touchTol 编辑线 / 挂单线触控半径（px，≈24dp）
     * @param posTol 持仓线点击半径（px，≈16dp）；closePad ✕ 额外触控余量（px）
     */
    fun resolve(
        x: Float, y: Float, plotW: Float, plotH: Float,
        editYs: FloatArray, orderYs: FloatArray,
        posYs: FloatArray, posTextWs: FloatArray, posTextHs: FloatArray,
        touchTol: Float, posTol: Float, closePad: Float,
    ): Result {
        if (x < 0f || x >= plotW || y.isNaN()) return Result(null, null)
        // ✕ 精确命中
        var close: Int? = null
        for (i in posYs.indices) {
            val py = posYs[i]
            if (py.isNaN() || py < 0f || py > plotH) continue
            val lr = PositionLines.labelRect(posTextWs[i], posTextHs[i], py)
            if (PositionLines.hitCloseButton(lr, x, y, closePad)) { close = i; break }
        }
        // 挂单线（最近、容差内）
        val oi = OrderLines.hit(orderYs, y, plotH, touchTol)
        val orderDist = oi?.let { abs(orderYs[it] - y) }
        // 编辑线（最近、容差内）
        var ei = -1; var ed = Float.MAX_VALUE
        for (i in editYs.indices) {
            val ly = editYs[i]
            if (ly.isNaN()) continue
            val d = abs(ly - y)
            if (d <= touchTol && d < ed) { ei = i; ed = d }
        }
        val drag: Drag? = when {
            ei >= 0 && editBeatsOrder(ed, orderDist) -> Drag.Edit(ei)
            oi != null -> Drag.Order(oi)
            else -> null
        }
        // 持仓线 / 标签
        val ph = PositionLines.hitLineDist(posYs, posTextWs, posTextHs, x, y, plotW, plotH, posTol)
        val tap: Tap? = when {
            drag is Drag.Edit -> null // 编辑线抓取：整个手势归它（与旧行为一致），但 ✕ 仍精确优先
            close != null -> Tap.Close(close)
            ph != null && !OrderLines.orderWins(orderDist, ph.second) -> Tap.Position(ph.first)
            else -> null
        }
        if (close != null && drag is Drag.Edit) return Result(null, Tap.Close(close)) // ✕ 精确命中优先于编辑线（旧行为）
        return Result(drag, tap)
    }
}
