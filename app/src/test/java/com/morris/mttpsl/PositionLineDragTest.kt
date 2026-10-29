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

/**
 * v0.14 修复：已开仓持仓的 TP/SL 线（图上的 "tp #id" / "sl #id"）无法拖动。
 * 根因：这两条线只是只读装饰线（StaticLine，没有 positionId），不在任何命中测试集合里
 * （[ChartHit] 只看 持仓开仓线 / 挂单线 / 新建·编辑订单时的编辑线），手指竖直滑动直接落到平移。
 * 现在它们作为可拖动线（[PositionLineMoves.refs]）参与命中，走与挂单线相同的“竖直拖动 → 确认条 → 修改”流程。
 */
class PositionLineHitTest {
    private val spec = SymbolSpec("EURUSD", 5, 0.00001, 10)
    private val pos = Position("59081463061", "EURUSD", Side.BUY, 0.20, 1.1000, 1.1050, 1.0950)
    private val tol = 24f * 3f // 24dp @ 3x 密度

    @Test fun refsContainTpAndSlOfEveryPositionAndOnlyThoseSet() {
        val refs = PositionLineMoves.refs(listOf(pos, pos.copy(id = "2", takeProfit = null), pos.copy(id = "3", takeProfit = null, stopLoss = null)))
        assertEquals(listOf("59081463061" to LineId.TP, "59081463061" to LineId.SL, "2" to LineId.SL), refs.map { it.ticket to it.kind })
        assertTrue(refs.all { it.positionLine })
        assertEquals(1.1050, refs[0].price, 0.0)
    }

    private fun resolve(touchY: Float, tpY: Float, posY: Float = 600f, x: Float = 500f): ChartHit.Result {
        val refs = PositionLineMoves.refs(listOf(pos.copy(takeProfit = 1.1050, stopLoss = null)))
        assertEquals(1, refs.size)
        return ChartHit.resolve(x, touchY, 1000f, 1500f, FloatArray(4) { Float.NaN }, floatArrayOf(tpY),
            floatArrayOf(posY), floatArrayOf(300f), floatArrayOf(36f), tol, 16f * 3f, 6f * 3f)
    }

    @Test fun verticalGrabWorksWithinTouchToleranceAndNotBeyond() {
        assertEquals(ChartHit.Drag.Order(0), resolve(touchY = 400f, tpY = 400f).drag)           // 正好在线上
        assertEquals(ChartHit.Drag.Order(0), resolve(touchY = 400f + 70f, tpY = 400f).drag)     // 24dp(72px) 内
        assertEquals(ChartHit.Drag.Order(0), resolve(touchY = 400f - 70f, tpY = 400f).drag)
        assertNull("too far from the line", resolve(touchY = 400f + 90f, tpY = 400f).drag)
    }

    @Test fun grabbingTheTpLineNeverTurnsIntoAClosePanelTap() {
        val r = resolve(touchY = 410f, tpY = 400f)
        assertEquals(ChartHit.Drag.Order(0), r.drag); assertNull(r.tap)
    }

    @Test fun grabbingWorksAnywhereAlongTheLineButNotOnThePriceAxis() {
        assertEquals(ChartHit.Drag.Order(0), resolve(400f, 400f, x = 20f).drag)
        assertEquals(ChartHit.Drag.Order(0), resolve(400f, 400f, x = 990f).drag)
        assertNull("x >= plotW is the price axis", resolve(400f, 400f, x = 1000f).drag)
    }

    @Test fun tpLineCloseToThePositionLineStillGrabbableWhenCloserToIt() {
        // 开仓线在 y=430，TP 线在 y=400：触点 y=395 离 TP 更近 → 拖动 TP；点击（不动）不会误开平仓面板，因为拖动判定由竖直位移决定
        val r = resolve(touchY = 395f, tpY = 400f, posY = 430f)
        assertEquals(ChartHit.Drag.Order(0), r.drag)
    }

    @Test fun verticalDragDoesNotPanButHorizontalDragDoes() {
        val slop = 8f
        assertTrue(OrderLines.isVerticalDrag(2f, 20f, slop * 0.5f))
        assertTrue(OrderLines.isVerticalDrag(-6f, -18f, slop * 0.5f))
        assertFalse("mostly horizontal -> pan", OrderLines.isVerticalDrag(30f, 8f, slop * 0.5f))
        assertFalse("below slop", OrderLines.isVerticalDrag(0f, 3f, slop * 0.5f))
    }

    @Test fun touchToleranceIsAboutTwentyFourDp() {
        assertTrue(OrderLines.TOUCH_DP in 24f..32f)
    }

    @Test fun withPriceChangesOnlyTheDraggedLine() {
        assertEquals(pos.copy(takeProfit = 1.11), PositionLineMoves.withPrice(pos, LineId.TP, 1.11))
        assertEquals(pos.copy(stopLoss = 1.09), PositionLineMoves.withPrice(pos, LineId.SL, 1.09))
        assertEquals(1.0950, PositionLineMoves.priceOf(pos, LineId.SL)!!, 0.0)
    }

    @Test fun validationFollowsBuyAndSellRules() {
        val bid = 1.1000; val ask = 1.10008
        assertNull(PositionLineMoves.validate(pos, LineId.TP, 1.1100, bid, ask, spec))
        assertNotNull("BUY TP below bid", PositionLineMoves.validate(pos, LineId.TP, 1.0990, bid, ask, spec))
        assertNull(PositionLineMoves.validate(pos, LineId.SL, 1.0900, bid, ask, spec))
        assertNotNull("BUY SL above bid", PositionLineMoves.validate(pos, LineId.SL, 1.1010, bid, ask, spec))
        val sell = pos.copy(side = Side.SELL, takeProfit = 1.0950, stopLoss = 1.1050)
        assertNull(PositionLineMoves.validate(sell, LineId.TP, 1.0900, bid, ask, spec))
        assertNotNull("SELL TP above ask", PositionLineMoves.validate(sell, LineId.TP, 1.1010, bid, ask, spec))
        assertNull(PositionLineMoves.validate(sell, LineId.SL, 1.1100, bid, ask, spec))
        assertNotNull("SELL SL below ask", PositionLineMoves.validate(sell, LineId.SL, 1.0990, bid, ask, spec))
        assertNotNull("inside min stop distance", PositionLineMoves.validate(pos, LineId.TP, 1.10005, bid, ask, spec))
    }

    @Test fun confirmTextShowsOldAndNewPrice() {
        val t = PositionLineMoves.confirmText(PositionMoveProposal(pos, LineId.TP, 1.1050, 1.1100), spec)
        assertEquals("#59081463061 buy 0.20  止盈 TP  1.10500 → 1.11000", t)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PositionLineDragViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private class Counting(val d: DemoTradingApi) : TradingApi by d {
        var modifyCalls = 0; var last: Triple<String, Double?, Double?>? = null
        override suspend fun modifyPosition(positionId: String, takeProfit: Double?, stopLoss: Double?): TradeResult {
            modifyCalls++; last = Triple(positionId, takeProfit, stopLoss); return d.modifyPosition(positionId, takeProfit, stopLoss)
        }
    }

    private suspend fun TestScope.setup(): Triple<MainViewModel, Counting, String> {
        val demo = DemoTradingApi(autoTick = false)
        demo.setBid("EURUSD", 1.10000)
        demo.marketOrder("EURUSD", Side.BUY, 0.20, 1.0950, 1.1050)
        val api = Counting(demo)
        val vm = MainViewModel()
        vm.configure(false, "", "", "EURUSD", apiOverride = api)
        advanceTimeBy(1500)
        return Triple(vm, api, vm.trade.value.positions.single().id)
    }

    @Test fun dragTpShowsConfirmThenModifiesKeepingSl() = runTest(dispatcher) {
        val (vm, api, id) = setup()
        vm.proposePositionMove(id, LineId.TP, 1.11004567)
        val m = vm.positionMove.value!!
        assertEquals(1.1050, m.oldPrice, 1e-9); assertEquals(1.11005, m.newPrice, 1e-9)
        assertEquals("nothing sent before confirm", 0, api.modifyCalls)
        assertEquals(1.1050, vm.trade.value.positions.single().takeProfit!!, 1e-9)
        vm.confirmPositionMove(); advanceTimeBy(1500)
        assertEquals(1, api.modifyCalls); assertEquals(Triple(id, 1.11005, 1.0950).first, api.last!!.first)
        val p = vm.trade.value.positions.single()
        assertEquals(1.11005, p.takeProfit!!, 1e-9); assertEquals("SL unchanged", 1.0950, p.stopLoss!!, 1e-9)
        assertNull(vm.positionMove.value)
        assertEquals("✅ 已修改持仓 #$id 止盈 TP 1.10500 → 1.11005", vm.message.value)
        vm.viewModelScope.cancel()
    }

    @Test fun dragSlWorksToo() = runTest(dispatcher) {
        val (vm, api, id) = setup()
        vm.proposePositionMove(id, LineId.SL, 1.0900); vm.confirmPositionMove(); advanceTimeBy(1500)
        val p = vm.trade.value.positions.single()
        assertEquals(1.09, p.stopLoss!!, 1e-9); assertEquals("TP unchanged", 1.1050, p.takeProfit!!, 1e-9); assertEquals(1, api.modifyCalls)
        vm.viewModelScope.cancel()
    }

    @Test fun cancelSnapsBackWithoutCallingServer() = runTest(dispatcher) {
        val (vm, api, id) = setup()
        vm.proposePositionMove(id, LineId.TP, 1.1100); assertNotNull(vm.positionMove.value)
        vm.cancelPositionMove(); advanceTimeBy(1500)
        assertNull(vm.positionMove.value); assertEquals(0, api.modifyCalls)
        assertEquals(1.1050, vm.trade.value.positions.single().takeProfit!!, 1e-9)
        vm.viewModelScope.cancel()
    }

    @Test fun invalidPriceShowsChineseErrorAndSendsNothing() = runTest(dispatcher) {
        val (vm, api, id) = setup()
        vm.proposePositionMove(id, LineId.TP, 1.0950) // BUY 的 TP 必须高于 Bid
        assertNull(vm.positionMove.value); assertTrue(vm.message.value!!.startsWith("❌"))
        assertNotNull(vm.checkPositionMove(id, LineId.TP, 1.0950)); assertNull(vm.checkPositionMove(id, LineId.TP, 1.12))
        assertEquals(0, api.modifyCalls)
        vm.viewModelScope.cancel()
    }

    @Test fun unchangedPriceOrUnknownPositionDoNothing() = runTest(dispatcher) {
        val (vm, api, id) = setup()
        vm.proposePositionMove(id, LineId.TP, 1.1050001); assertNull(vm.positionMove.value)
        vm.proposePositionMove("nope", LineId.TP, 1.12); assertNull(vm.positionMove.value)
        assertEquals(0, api.modifyCalls)
        vm.viewModelScope.cancel()
    }

    @Test fun proposalIsDroppedWhenThePositionDisappears() = runTest(dispatcher) {
        val (vm, api, id) = setup()
        vm.proposePositionMove(id, LineId.TP, 1.1100)
        api.d.closePosition(id); advanceTimeBy(1500)
        assertNull(vm.positionMove.value)
        vm.confirmPositionMove(); advanceTimeBy(500); assertEquals(0, api.modifyCalls)
        vm.viewModelScope.cancel()
    }
}
