package com.morris.mttpsl

import com.morris.mttpsl.api.Candle
import com.morris.mttpsl.api.DemoTradingApi
import com.morris.mttpsl.ui.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ViewportTest {
    private val n = 500
    private val w = 1000f
    private val eps = 1e-3f

    @Test fun latestCandleSitsRightWithPad() {
        val v = Viewport(barW = 20f)
        assertEquals(w - Viewport.RIGHT_PAD * 20f - 10f, v.x(n - 1, n, w), eps)
        assertTrue(v.following)
        assertEquals(n - 1, v.visible(n, w).last)
    }

    @Test fun indexAtIsInverseOfX() {
        val v = Viewport(barW = 17f, scroll = 42.5f)
        for (i in listOf(0, 100, 399, 480)) assertEquals(i.toFloat(), v.indexAt(v.x(i, n, w), n, w), eps)
    }

    @Test fun panRightShowsOlderAndClamps() {
        val v0 = Viewport(barW = 20f)
        val v1 = v0.panX(200f, n, w)
        assertEquals(10f, v1.scroll, eps)
        assertEquals(v0.visible(n, w).first - 10, v1.visible(n, w).first)
        assertFalse(v1.following)
        // 不能滚过最早 K 线，也不能把最新 K 线拖过屏幕中线太多
        assertTrue(v0.panX(1e7f, n, w).visible(n, w).contains(0))
        assertEquals(-(w / 20f) * 0.5f, v0.panX(-1e7f, n, w).scroll, eps)
    }

    @Test fun zoomKeepsFocusCandleFixed() {
        val v = Viewport(barW = 20f, scroll = 30f)
        val focus = 400f
        val idx = v.indexAt(focus, n, w)
        val z = v.zoom(2f, focus, n, w)
        assertEquals(40f, z.barW, eps)
        assertEquals(idx, z.indexAt(focus, n, w), 1e-2f)
        assertEquals(Viewport.MAX_BAR, v.zoom(100f, focus, n, w).barW, eps)
        assertEquals(Viewport.MIN_BAR, v.zoom(0.001f, focus, n, w).barW, eps)
        assertTrue(z.visible(n, w).count() < v.visible(n, w).count())
    }

    @Test fun priceYRoundTrip() {
        val r = PriceRange(1.1300, 1.1200)
        val h = 800f
        assertEquals(0f, r.y(1.1300, h), eps)
        assertEquals(h, r.y(1.1200, h), eps)
        assertEquals(400f, r.y(1.1250, h), eps)
        assertEquals(1.12345, r.price(r.y(1.12345, h), h), 1e-7)
    }

    @Test fun autoFitThenManualScaleAndPanY() {
        val highs = doubleArrayOf(1.10, 1.12, 1.30)
        val lows = doubleArrayOf(1.09, 1.11, 1.29)
        val auto = Viewport.autoFit(highs, lows, 0, 1, emptyList(), 0.0001) // 只看可见的前两根
        assertTrue(auto.hi > 1.12 && auto.hi < 1.13 && auto.lo < 1.09)
        val v = Viewport()
        assertEquals(auto, v.range(auto))
        val s = v.scaleY(2f, auto)
        assertTrue(s.manualY)
        val r = s.range(auto)
        assertEquals(auto.span * 2, r.span, 1e-9)
        assertEquals((auto.hi + auto.lo) / 2, (r.hi + r.lo) / 2, 1e-9)
        // 手动模式：手指下移半屏 → 区间整体上移半个跨度
        val p = s.panY(400f, 800f, r).range(auto)
        assertEquals(r.hi + r.span / 2, p.hi, 1e-9)
        // 自动模式下竖直拖动无效
        assertEquals(v, v.panY(400f, 800f, auto))
        assertEquals(auto, s.resetY().range(auto))
    }

    @Test fun linePriceStableAcrossPanAndZoom() {
        // 线存的是价格；平移/缩放只改变映射，换算回来价格不变
        val r = PriceRange(1.13, 1.12)
        val v = Viewport().scaleY(1.5f, r).panY(123f, 800f, r.let { Viewport().scaleY(1.5f, it).range(it) })
        val rr = v.range(r)
        val tp = 1.12555
        assertEquals(tp, rr.price(rr.y(tp, 800f), 800f), 1e-9)
    }

    @Test fun appendKeepsViewWhenScrolledBack() {
        assertEquals(0f, Viewport().onAppended(1).scroll, eps)
        assertEquals(11f, Viewport(scroll = 10f).onAppended(1).scroll, eps)
    }

    @Test fun mergeHistory() {
        fun c(t: Long) = Candle(t, 1.0, 1.0, 1.0, 1.0)
        val cur = (10L..20L).map(::c)
        assertEquals((0L..20L).toList(), mergeOlder((0L..12L).map(::c), cur).map { it.time })
        assertEquals((10L..22L).toList(), mergeLatest(cur, (18L..22L).map(::c)).map { it.time })
    }

    @Test fun demoOffsetReturnsContiguousOlderCandles() = runTest {
        val api = DemoTradingApi(autoTick = false, seed = 1)
        val latest = api.candles("EURUSD", "H1", 120)
        val older = api.candles("EURUSD", "H1", 200, 120)
        assertEquals(120, latest.size); assertEquals(200, older.size)
        assertEquals(latest.first().time - 3600_000L, older.last().time)
        assertTrue(older.zipWithNext().all { (a, b) -> b.time - a.time == 3600_000L })
    }
}
