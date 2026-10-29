package com.morris.mttpsl

import androidx.lifecycle.viewModelScope
import com.morris.mttpsl.api.*
import com.morris.mttpsl.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PositionLinesTest {
    private val pos = Position("58956001", "EURUSD", Side.BUY, 0.1, 1.1, null, null, 1.1005, 5.0)

    @Test fun labelHasTicketDirectionLotsAndUsdPnl() {
        assertEquals("#58956001 buy 0.10  5.00 USD", PositionLines.label(pos, "USD"))
        assertEquals("#7 sell 1.25  -3.50 USD", PositionLines.label(pos.copy(id = "7", side = Side.SELL, volume = 1.25, profit = -3.5), "USD"))
    }

    @Test fun closeButtonSitsRightOfLabelAndHitsWithPad() {
        val lab = PositionLines.labelRect(100f, 30f, 400f)
        assertEquals(FRect(4f, 366f, 116f, 400f), lab)
        val btn = PositionLines.closeButtonRect(lab)
        assertEquals(116f, btn.left, 0f); assertEquals(34f, btn.width, 0f)
        assertTrue(PositionLines.hitCloseButton(lab, 130f, 380f, 0f))
        assertFalse("label body is not the button", PositionLines.hitCloseButton(lab, 60f, 380f, 0f))
        assertFalse(PositionLines.hitCloseButton(lab, 170f, 380f, 0f))
        assertTrue("pad widens target", PositionLines.hitCloseButton(lab, 154f, 380f, 6f))
    }

    private fun hit(x: Float, y: Float, lineYs: FloatArray) =
        PositionLines.hitLine(lineYs, FloatArray(lineYs.size) { 100f }, FloatArray(lineYs.size) { 30f }, x, y, 800f, 1000f, 40f)

    @Test fun lineHitUsesToleranceAndPicksNearest() {
        val ys = floatArrayOf(300f, 330f)
        assertEquals(0, hit(500f, 290f, ys))
        assertEquals(1, hit(500f, 345f, ys))
        assertEquals("nearest of overlapping", 1, hit(500f, 320f, ys))
        assertNull(hit(500f, 200f, ys))
        assertNull("price axis ignored", hit(805f, 300f, ys))
        assertNull(hit(500f, 300f, floatArrayOf()))
    }

    @Test fun labelAreaCountsAsHitEvenFarFromTolerance() {
        // 标签在线上方 34px，容差 10 时线本身够不到，但点在标签里仍命中
        assertEquals(0, PositionLines.hitLine(floatArrayOf(300f), floatArrayOf(100f), floatArrayOf(30f), 50f, 275f, 800f, 1000f, 10f))
    }

    @Test fun offscreenLinesNeverHit() {
        assertNull(hit(500f, 995f, floatArrayOf(1200f)))
        assertNull(hit(500f, 5f, floatArrayOf(-50f)))
    }

    @Test fun serverHasNoPartialCloseSoAlwaysFullVolume() {
        // v15：服务器支持部分平仓；显式关闭时仍恒为全部手数
        assertTrue(ClosePlan.PARTIAL_SUPPORTED)
        assertEquals(0.1, ClosePlan.volumeToClose(pos, "0.03", partialSupported = false), 1e-9)
        assertEquals(0.1, ClosePlan.volumeToClose(pos), 1e-9)
    }

    @Test fun partialVolumeRulesWhenServerSupportsIt() {
        assertEquals(0.03, ClosePlan.volumeToClose(pos, "0.03", partialSupported = true), 1e-9)
        assertEquals(0.1, ClosePlan.volumeToClose(pos, "5", partialSupported = true), 1e-9)
        assertEquals(0.1, ClosePlan.volumeToClose(pos, "0", partialSupported = true), 1e-9)
        assertEquals(0.1, ClosePlan.volumeToClose(pos, "abc", partialSupported = true), 1e-9)
        assertTrue(ClosePlan.isFull(pos, 0.1)); assertFalse(ClosePlan.isFull(pos, 0.05))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ClosePanelViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    /** 图表点击持仓线 → 面板 → “平仓” → 持仓与线消失（演示模式），并且不影响其他持仓 */
    @Test fun tapLineOpensPanelAndConfirmClosesPositionInDemo() = runTest(dispatcher) {
        val demo = DemoTradingApi(autoTick = false)
        demo.setBid("EURUSD", 1.10000)
        demo.marketOrder("EURUSD", Side.BUY, 0.05, null, null)
        demo.marketOrder("EURUSD", Side.SELL, 0.02, null, null)
        val vm = MainViewModel()
        vm.configure(false, "", "", "EURUSD", apiOverride = demo)
        advanceTimeBy(1500)
        val all = vm.trade.value.positions
        assertEquals(2, all.size)
        val target = all.first()

        vm.openClosePanel("does-not-exist"); assertNull(vm.closeId.value)
        vm.openClosePanel(target.id)
        assertEquals(target.id, vm.closeId.value)
        vm.confirmClosePanel("0.01") // v15：部分平仓 0.01 手，持仓剩 0.04 手
        assertTrue(vm.closeBusy.value)
        advanceTimeBy(500)
        assertFalse(vm.closeBusy.value)
        assertNull("panel closed", vm.closeId.value)
        val after = vm.trade.value.positions
        assertEquals(2, after.size)
        assertEquals("remainder stays open", 0.04, after.first { it.id == target.id }.volume, 1e-9)
        assertEquals("closed in history with partial volume", 0.01,
            demo.history(1).first { it.entry == "OUT" && it.positionId == target.id }.volume, 1e-9)
        assertEquals("✅ 已部分平仓 #${target.id} EURUSD 0.01 / 0.05 手", vm.message.value)
        // 再全部平掉剩余手数
        vm.openClosePanel(target.id); vm.confirmClosePanel("0.04")
        advanceTimeBy(500)
        assertTrue("line source removed", vm.trade.value.positions.none { it.id == target.id })
        assertEquals(1, vm.trade.value.positions.size)
        assertEquals("✅ 已平仓 #${target.id} EURUSD 0.04 手", vm.message.value)
        vm.viewModelScope.cancel()
    }

    @Test fun invalidPartialVolumeIsRejectedWithoutSending() = runTest(dispatcher) {
        val demo = DemoTradingApi(autoTick = false)
        demo.setBid("EURUSD", 1.10000)
        demo.marketOrder("EURUSD", Side.BUY, 0.05, null, null)
        val vm = MainViewModel()
        vm.configure(false, "", "", "EURUSD", apiOverride = demo)
        advanceTimeBy(1500)
        val id = vm.trade.value.positions.single().id
        vm.openClosePanel(id); vm.confirmClosePanel("0.015")
        assertFalse(vm.closeBusy.value)
        assertTrue(vm.message.value!!.startsWith("❌"))
        assertEquals(0.05, demo.positions().single().volume, 1e-9)
        vm.viewModelScope.cancel()
    }

    @Test fun dismissAndDoubleConfirmAreSafe() = runTest(dispatcher) {
        val demo = DemoTradingApi(autoTick = false)
        demo.setBid("EURUSD", 1.10000)
        demo.marketOrder("EURUSD", Side.BUY, 0.01, null, null)
        val vm = MainViewModel()
        vm.configure(false, "", "", "EURUSD", apiOverride = demo)
        advanceTimeBy(1500)
        val id = vm.trade.value.positions.single().id
        vm.openClosePanel(id); vm.dismissClosePanel()
        assertNull(vm.closeId.value)
        vm.openClosePanel(id)
        vm.confirmClosePanel(); vm.confirmClosePanel() // 第二次被忙碌标志挡住
        advanceTimeBy(500)
        assertTrue(vm.trade.value.positions.isEmpty())
        assertEquals("only one close succeeded, no error toast", "✅ 已平仓 #$id EURUSD 0.01 手", vm.message.value)
        vm.viewModelScope.cancel()
    }

    @Test fun failedCloseKeepsPanelAndReportsError() = runTest(dispatcher) {
        val demo = DemoTradingApi(autoTick = false)
        demo.setBid("EURUSD", 1.10000)
        demo.marketOrder("EURUSD", Side.BUY, 0.01, null, null)
        val failing = object : TradingApi by demo {
            override suspend fun closePosition(positionId: String): TradeResult = TradeResult.Error("market closed")
        }
        val vm = MainViewModel()
        vm.configure(false, "", "", "EURUSD", apiOverride = failing)
        advanceTimeBy(1500)
        val id = vm.trade.value.positions.single().id
        vm.openClosePanel(id); vm.confirmClosePanel()
        advanceTimeBy(500)
        assertEquals(id, vm.closeId.value)
        assertFalse(vm.closeBusy.value)
        assertEquals("❌ market closed", vm.message.value)
        assertEquals(1, vm.trade.value.positions.size)
        vm.viewModelScope.cancel()
    }

    /** 底部/列表平仓（requestClose → runConfirm）仍然可用 */
    @Test fun listCloseStillWorks() = runTest(dispatcher) {
        val demo = DemoTradingApi(autoTick = false)
        demo.setBid("EURUSD", 1.10000)
        demo.marketOrder("EURUSD", Side.SELL, 0.03, null, null)
        val vm = MainViewModel()
        vm.configure(false, "", "", "EURUSD", apiOverride = demo)
        advanceTimeBy(1500)
        val p = vm.trade.value.positions.single()
        vm.requestClose(p)
        assertEquals("平仓", vm.confirm.value?.confirmLabel)
        vm.runConfirm()
        advanceTimeBy(500)
        assertTrue(vm.trade.value.positions.isEmpty())
        vm.viewModelScope.cancel()
    }
}
