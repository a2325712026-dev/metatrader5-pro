package com.morris.mttpsl

import com.morris.mttpsl.ui.*
import com.morris.mttpsl.ui.ChartHit.Drag
import com.morris.mttpsl.ui.ChartHit.Tap
import org.junit.Assert.*
import org.junit.Test

/**
 * v11：持仓时挂单线必须能自由拖动。把 1dp 当 1px：触控区 24，持仓线点击半径 16，✕ 余量 6。
 * 持仓线 y=400，标签 120×18 → 标签矩形 x 4..136、y 378..400；✕ 方块 x 136..158、y 378..400。
 */
class ChartHitTest {
    private val plotW = 800f; private val plotH = 1000f
    private val none = FloatArray(4) { Float.NaN }
    private val posY = floatArrayOf(400f); private val posW = floatArrayOf(120f); private val posH = floatArrayOf(18f)

    private fun hit(x: Float, y: Float, orders: FloatArray = FloatArray(0), edit: FloatArray = none,
                    withPosition: Boolean = true): ChartHit.Result =
        if (withPosition) ChartHit.resolve(x, y, plotW, plotH, edit, orders, posY, posW, posH, 24f, 16f, 6f)
        else ChartHit.resolve(x, y, plotW, plotH, edit, orders, FloatArray(0), FloatArray(0), FloatArray(0), 24f, 16f, 6f)

    private fun edit(tp: Float = Float.NaN, sl: Float = Float.NaN, entry: Float = Float.NaN) =
        floatArrayOf(entry, Float.NaN, tp, sl)

    @Test fun touchZoneIs24dp() = assertEquals(24f, OrderLines.TOUCH_DP, 0f)

    // ---------- 旧 bug 的复现：下面这些在 v10 里挂单线都抓不到 ----------
    @Test fun pendingLineFarFromPositionIsGrabbableEvenWithPosition() {
        val r = hit(500f, 335f, orders = floatArrayOf(330f))
        assertEquals(Drag.Order(0), r.drag); assertNull("远离持仓线 → 不会触发平仓", r.tap)
    }

    @Test fun pendingLineNearPositionLineWinsDragWhenCloserToTouch() {
        // 挂单线 y=395，持仓线 y=400；触点 y=394（离挂单 1px、离持仓 6px）
        val r = hit(500f, 394f, orders = floatArrayOf(395f))
        assertEquals(Drag.Order(0), r.drag); assertNull(r.tap)
    }

    @Test fun tieBetweenPendingAndPositionLineGoesToPendingForDragButTapStillOpensClose() {
        // 触点 y=397.5：离挂单 y=395 与离持仓 y=400 同样近（2.5）
        val r = hit(500f, 397.5f, orders = floatArrayOf(395f))
        assertEquals("竖直拖动 → 挂单线", Drag.Order(0), r.drag)
        assertEquals("不动抬起 → 持仓线点击（平仓面板）", Tap.Position(0), r.tap)
    }

    @Test fun touchInsidePositionLabelStillGrabsNearbyPendingLine() {
        // v10：触点在标签内记距离 0，挂单线永远赢不了
        val r = hit(50f, 390f, orders = floatArrayOf(385f))
        assertEquals(Drag.Order(0), r.drag); assertEquals(Tap.Position(0), r.tap)
    }

    @Test fun touchOnCloseButtonWithPendingNearbyDragsPendingButTapsClose() {
        val r = hit(145f, 390f, orders = floatArrayOf(370f)) // 挂单线 20px 内
        assertEquals(Drag.Order(0), r.drag)
        assertEquals(Tap.Close(0), r.tap)
    }

    @Test fun closeButtonWithoutPendingIsPlainTapOnly() {
        val r = hit(145f, 390f)
        assertNull(r.drag); assertEquals(Tap.Close(0), r.tap)
    }

    @Test fun closeButtonPadExtendsHitArea() {
        assertEquals(Tap.Close(0), hit(162f, 390f).tap)  // 方块右缘 158 + 余量 6 = 164
        assertNotEquals(Tap.Close(0), hit(166f, 390f).tap)
    }

    // ---------- 编辑线（TP/SL）优先级：必须“严格更近”才抢 ----------
    @Test fun editLineStrictlyCloserWins() {
        val r = hit(500f, 303f, orders = floatArrayOf(310f), edit = edit(tp = 300f))
        assertEquals(Drag.Edit(LineId.TP), r.drag); assertNull(r.tap)
    }

    @Test fun tieBetweenEditLineAndPendingGoesToPending() {
        val r = hit(500f, 305f, orders = floatArrayOf(310f), edit = edit(tp = 300f))
        assertEquals(Drag.Order(0), r.drag)
    }

    @Test fun pendingCloserThanEditLineWins() {
        val r = hit(500f, 308f, orders = floatArrayOf(310f), edit = edit(sl = 300f))
        assertEquals(Drag.Order(0), r.drag)
    }

    @Test fun editLineAloneStillGrabbedWithin24() {
        assertEquals(Drag.Edit(LineId.SL), hit(500f, 324f, edit = edit(sl = 300f)).drag)
        assertNull(hit(500f, 325f, edit = edit(sl = 300f)).drag)
    }

    @Test fun editLineBeatsPendingOnlyWhenPendingIsInTouchZoneAndFartherByMoreThanHalfPixel() {
        // 编辑线 4.85px、挂单 5.15px：差 0.3px < 0.5px 视为平手 → 挂单线
        assertEquals(Drag.Order(0), hit(500f, 304.85f, orders = floatArrayOf(310f), edit = edit(tp = 300f)).drag)
        // 差 1px → 编辑线
        assertEquals(Drag.Edit(LineId.TP), hit(500f, 304f, orders = floatArrayOf(310f), edit = edit(tp = 300f)).drag)
    }

    @Test fun editLineBeatsPositionLineAndLabelTapButNotCloseButton() {
        // 触点在标签内，TP 编辑线贴着：抓编辑线，不开平仓面板
        val r = hit(50f, 390f, edit = edit(tp = 391f))
        assertEquals(Drag.Edit(LineId.TP), r.drag); assertNull(r.tap)
        // ✕ 仍然精确优先于编辑线（v10 行为）
        val c = hit(145f, 390f, edit = edit(tp = 391f))
        assertNull(c.drag); assertEquals(Tap.Close(0), c.tap)
    }

    // ---------- 触控区边界 ----------
    @Test fun pendingZoneBoundary() {
        assertEquals(Drag.Order(0), hit(500f, 224f, orders = floatArrayOf(200f), withPosition = false).drag)
        assertNull(hit(500f, 225f, orders = floatArrayOf(200f), withPosition = false).drag)
    }

    @Test fun nearestOfSeveralPendingLinesIsPicked() {
        val ys = floatArrayOf(100f, 140f, 180f)
        assertEquals(Drag.Order(1), hit(500f, 132f, orders = ys, withPosition = false).drag)
        assertEquals(Drag.Order(2), hit(500f, 165f, orders = ys, withPosition = false).drag)
    }

    @Test fun pendingLineOffScreenOrNaNIgnored() {
        assertNull(hit(500f, 10f, orders = floatArrayOf(-5f), withPosition = false).drag)
        assertNull(hit(500f, 10f, orders = floatArrayOf(Float.NaN), withPosition = false).drag)
        assertNull(hit(500f, 995f, orders = floatArrayOf(plotH + 5f), withPosition = false).drag)
    }

    // ---------- 平仓面板点击保持不变 ----------
    @Test fun positionLineTapWithinSixteen() {
        assertEquals(Tap.Position(0), hit(500f, 410f).tap)
        assertEquals(Tap.Position(0), hit(500f, 384f).tap)
        assertNull(hit(500f, 420f).tap)
        assertNull("无挂单时位置线不是拖动目标", hit(500f, 410f).drag)
    }

    @Test fun tapNearPendingButFartherFromPositionOpensNothing() {
        // 挂单线 y=410，触点 y=411（挂单 1px / 持仓 11px）→ 点击归挂单线 → 无面板
        val r = hit(500f, 411f, orders = floatArrayOf(410f))
        assertEquals(Drag.Order(0), r.drag); assertNull(r.tap)
    }

    @Test fun priceAxisAndOutOfPlotNeverHit() {
        assertEquals(ChartHit.Result(null, null), hit(plotW, 330f, orders = floatArrayOf(330f)))
        assertEquals(ChartHit.Result(null, null), hit(-1f, 330f, orders = floatArrayOf(330f)))
        assertEquals(ChartHit.Result(null, null), hit(10f, Float.NaN, orders = floatArrayOf(330f)))
    }

    @Test fun degenerateInputsDoNotThrow() {
        val r = ChartHit.resolve(10f, 10f, 800f, 1000f, floatArrayOf(Float.NaN, Float.NaN, Float.NaN, Float.NaN), FloatArray(0),
            floatArrayOf(Float.NaN), floatArrayOf(100f), floatArrayOf(18f), 24f, 16f, 6f)
        assertEquals(ChartHit.Result(null, null), r)
    }

    @Test fun verticalDragDetection() {
        assertTrue(OrderLines.isVerticalDrag(2f, 12f, 4f))
        assertFalse(OrderLines.isVerticalDrag(12f, 2f, 4f))
        assertFalse(OrderLines.isVerticalDrag(0f, 3f, 4f))
    }
}
