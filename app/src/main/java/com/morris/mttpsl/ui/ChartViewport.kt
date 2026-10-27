package com.morris.mttpsl.ui

import kotlin.math.ceil
import kotlin.math.floor

data class PriceRange(val hi: Double, val lo: Double) {
    val span get() = hi - lo
    /** 区间退化（0 / 负 / NaN）时不除零：返回中线 */
    private val degenerate get() = !(span > 0.0) || span.isInfinite()
    fun y(p: Double, h: Float): Float = if (degenerate || p.isNaN()) h / 2f else ((hi - p) / span * h).toFloat()
    fun price(y: Float, h: Float): Double = if (degenerate || h <= 0f) (if (hi.isNaN()) 0.0 else hi) else hi - y / h * span
}

/**
 * MT5 式图表视口（纯数学，无 Compose 依赖，便于单元测试）。
 * scroll = 相对"最新 K 线贴右（留 RIGHT_PAD 根空白）"向历史方向滚动的根数；0 = 跟随最新价。
 * 用右侧为基准 → 往前插入更早历史时无需调整 scroll。
 * manualY=false 时价格轴自动适配可见 K 线；true 时使用 hi/lo（可自由上下移动/缩放）。
 */
data class Viewport(
    val barW: Float = 24f,
    val scroll: Float = 0f,
    val manualY: Boolean = false,
    val hi: Double = Double.NaN,
    val lo: Double = Double.NaN,
) {
    val following get() = scroll <= 0.5f

    /** 第 i 根（0 = 最早）中心 x，n = 总根数，w = 绘图区宽 */
    fun x(i: Int, n: Int, w: Float): Float = w - (n - 1 - i + RIGHT_PAD - scroll) * barW - barW / 2f

    /** x 处的（小数）K 线下标 */
    fun indexAt(x: Float, n: Int, w: Float): Float = (n - 1 + RIGHT_PAD - scroll) - (w - x - barW / 2f) / barW

    /** 可见下标区间（含两端，已裁剪），无可见 K 线时返回空 */
    fun visible(n: Int, w: Float): IntRange {
        if (n == 0) return IntRange.EMPTY
        val a = floor(indexAt(0f, n, w)).toInt().coerceAtLeast(0)
        val b = ceil(indexAt(w, n, w)).toInt().coerceAtMost(n - 1)
        return if (a > b) IntRange.EMPTY else a..b
    }

    fun clampScroll(n: Int, w: Float): Viewport {
        val minS = -(w / barW) * 0.5f              // 最多把最新 K 线拖到屏幕中间
        val maxS = (n - 1 + RIGHT_PAD - 2).toFloat().coerceAtLeast(minS) // 至少留几根可见
        return copy(scroll = scroll.coerceIn(minS, maxS))
    }

    /** 水平平移：手指向右 dx>0 → 看更早历史 */
    fun panX(dx: Float, n: Int, w: Float) = copy(scroll = scroll + dx / barW).clampScroll(n, w)

    /** 双指缩放：focusX 处的 K 线保持不动 */
    fun zoom(factor: Float, focusX: Float, n: Int, w: Float): Viewport {
        val idx = indexAt(focusX, n, w)
        val nb = (barW * factor).coerceIn(MIN_BAR, MAX_BAR)
        val ns = (focusX - w + nb / 2f) / nb + (n - 1 - idx + RIGHT_PAD)
        return copy(barW = nb, scroll = ns).clampScroll(n, w)
    }

    /** 当前生效的价格区间 */
    fun range(auto: PriceRange): PriceRange = if (manualY && !hi.isNaN()) PriceRange(hi, lo) else auto

    /** 价格轴拖动：factor>1 拉大区间（压缩），<1 放大，围绕中点；进入手动模式 */
    fun scaleY(factor: Float, current: PriceRange): Viewport {
        val mid = (current.hi + current.lo) / 2
        val half = (current.span / 2 * factor).coerceAtLeast(1e-9)
        return copy(manualY = true, hi = mid + half, lo = mid - half)
    }

    /** 手动模式下单指竖直移动：手指向下 dy>0 → 看更高的价格 */
    fun panY(dy: Float, h: Float, current: PriceRange): Viewport {
        if (!manualY) return this
        val d = dy / h * current.span
        return copy(hi = current.hi + d, lo = current.lo + d)
    }

    fun resetY() = copy(manualY = false, hi = Double.NaN, lo = Double.NaN)

    /** 新 K 线追加 added 根：跟随时保持贴右，回看时保持画面不动 */
    fun onAppended(added: Int) = if (following || added <= 0) this else copy(scroll = scroll + added)

    companion object {
        const val RIGHT_PAD = 3
        const val MIN_BAR = 3f
        const val MAX_BAR = 80f

        /** 可见 K 线 + 额外价格（如编辑中的线）自动适配，上下留 8% */
        fun autoFit(highs: DoubleArray, lows: DoubleArray, from: Int, to: Int, extra: List<Double>, minPad: Double): PriceRange {
            var hi = Double.NEGATIVE_INFINITY; var lo = Double.POSITIVE_INFINITY
            // 下标越界 / NaN / ±Inf 的价格一律忽略（坏数据不能让图表崩溃）
            for (i in maxOf(from, 0)..minOf(to, highs.lastIndex, lows.lastIndex)) {
                val a = highs[i]; val b = lows[i]
                if (a.isFinite() && a > hi) hi = a
                if (b.isFinite() && b < lo) lo = b
            }
            extra.forEach { if (it.isFinite()) { if (it > hi) hi = it; if (it < lo) lo = it } }
            if (hi == Double.NEGATIVE_INFINITY || lo == Double.POSITIVE_INFINITY) { hi = 1.0; lo = 0.0 }
            val pad = maxOf((hi - lo) * 0.08, if (minPad.isFinite() && minPad > 0) minPad else 1e-6)
            return PriceRange(hi + pad, lo - pad)
        }
    }
}
