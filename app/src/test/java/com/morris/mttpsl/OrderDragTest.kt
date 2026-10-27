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

/** 图上拖动挂单线：命中测试 / 与持仓线的优先级 / 各类型价格有效性 / 确认与吸附回原位 */
class OrderLinesLogicTest {
    private val spec = SymbolSpec("EURUSD", 5, 0.00001, 0)
    private val specStops = SymbolSpec("EURUSD", 5, 0.00001, 100) // 最小止损距离 100 点 = 0.001
    private val bid = 1.10000
    private val ask = 1.10010

    private fun order(type: PendingType, price: Double, sl: Double? = null, tp: Double? = null, stopLimit: Double? = null) =
        PendingOrder("9001", "EURUSD", type, 0.1, price, stopLimit, tp, sl)

    // ---------- 命中测试 ----------
    @Test fun hitPicksNearestLineWithinTolerance() {
        val ys = floatArrayOf(100f, 140f, 400f)
        assertEquals(0, OrderLines.hit(ys, 110f, 1000f, 20f))
        assertEquals(1, OrderLines.hit(ys, 125f, 1000f, 20f))   // 离 140 更近
        assertEquals(1, OrderLines.hit(ys, 150f, 1000f, 20f))
        assertNull("远离所有线", OrderLines.hit(ys, 250f, 1000f, 20f))
        assertEquals(2, OrderLines.hit(ys, 419f, 1000f, 20f))
        assertNull(OrderLines.hit(ys, 421f, 1000f, 20f))
    }

    @Test fun hitIgnoresOffscreenLinesAndIsStableOnTies() {
        assertNull("线在可视区外不可抓", OrderLines.hit(floatArrayOf(-30f, 1200f), 5f, 1000f, 40f))
        assertEquals("同距离取靠前", 0, OrderLines.hit(floatArrayOf(100f, 100f), 100f, 1000f, 20f))
        assertNull(OrderLines.hit(floatArrayOf(), 10f, 100f, 20f))
    }

    @Test fun dragIsVerticalDominantElsePan() {
        assertTrue(OrderLines.isVerticalDrag(2f, 12f, 8f))
        assertFalse("水平位移占优 → 平移", OrderLines.isVerticalDrag(15f, 9f, 4f))
        assertFalse("竖直位移未过 slop", OrderLines.isVerticalDrag(0f, 3f, 8f))
        assertTrue(OrderLines.isVerticalDrag(-3f, -20f, 8f))
    }

    // ---------- 与持仓线 / TP·SL 编辑线的优先级 ----------
    @Test fun positionLineWinsWhenCloserOrEqualOrInsideLabel() {
        assertTrue("无持仓冲突 → 挂单线", OrderLines.orderWins(10f, null))
        assertFalse("持仓线更近", OrderLines.orderWins(12f, 5f))
        assertFalse("同样近 → 持仓线（点击平仓面板）", OrderLines.orderWins(8f, 8f))
        assertTrue("挂单线更近", OrderLines.orderWins(3f, 9f))
        assertFalse("触点在持仓标签内（距离 0）", OrderLines.orderWins(4f, 0f))
        assertFalse("没有挂单线", OrderLines.orderWins(null, 0f))
    }

    @Test fun positionHitDistanceIsZeroInsideLabelElseLineDistance() {
        val ws = floatArrayOf(100f); val hs = floatArrayOf(30f)
        val inLabel = PositionLines.hitLineDist(floatArrayOf(400f), ws, hs, 50f, 380f, 800f, 1000f, 16f)
        assertEquals(0 to 0f, inLabel)
        val nearLine = PositionLines.hitLineDist(floatArrayOf(400f), ws, hs, 500f, 410f, 800f, 1000f, 16f)
        assertEquals(0, nearLine!!.first); assertEquals(10f, nearLine.second, 0.01f)
        assertNull(PositionLines.hitLineDist(floatArrayOf(400f), ws, hs, 500f, 440f, 800f, 1000f, 16f))
        assertEquals("旧接口行为不变", 0, PositionLines.hitLine(floatArrayOf(400f), ws, hs, 500f, 410f, 800f, 1000f, 16f))
    }

    // ---------- 线的枚举与改价 ----------
    @Test fun linesOfListsEntryTpSlAndStopLimit() {
        val o = order(PendingType.BUY_STOP_LIMIT, 1.11, sl = 1.09, tp = 1.13, stopLimit = 1.105)
        assertEquals(listOf(LineId.ENTRY, LineId.STOP_LIMIT, LineId.TP, LineId.SL), OrderLines.linesOf(o).map { it.kind })
        assertEquals(listOf(LineId.ENTRY), OrderLines.linesOf(order(PendingType.BUY_LIMIT, 1.09)).map { it.kind })
    }

    @Test fun movingEntryKeepsTpSlUnchanged() {
        val o = order(PendingType.BUY_LIMIT, 1.09, sl = 1.08, tp = 1.12)
        val m = OrderLines.withPrice(o, LineId.ENTRY, 1.095)
        assertEquals(1.095, m.price, 0.0); assertEquals(1.08, m.stopLoss!!, 0.0); assertEquals(1.12, m.takeProfit!!, 0.0)
        assertEquals(1.10, OrderLines.withPrice(o, LineId.TP, 1.10).takeProfit!!, 0.0)
        assertEquals(1.09, OrderLines.withPrice(o, LineId.TP, 1.10).price, 0.0)
    }

    @Test fun snapRoundsToPointAndIgnoresSubPointMoves() {
        assertEquals(1.09123, OrderLines.snap(1.091234567, spec), 1e-12)
        assertEquals(158.391, OrderLines.snap(158.39127, SymbolSpec("USDJPY", 3, 0.001, 0)), 1e-12)
        assertEquals(2415.5, OrderLines.snap(2415.4962, SymbolSpec("XAUUSD", 2, 0.01, 0)), 1e-12)
        assertFalse(OrderLines.moved(1.09, 1.090004, spec))
        assertTrue(OrderLines.moved(1.09, 1.09001, spec))
    }

    // ---------- 各挂单类型的价格有效性 ----------
    @Test fun buyLimitMustBeBelowAsk() {
        val o = order(PendingType.BUY_LIMIT, 1.09)
        assertNull(OrderLines.validate(o, LineId.ENTRY, 1.0900, bid, ask, spec))
        assertNull(OrderLines.validate(o, LineId.ENTRY, 1.10009, bid, ask, spec))
        assertNotNull(OrderLines.validate(o, LineId.ENTRY, ask, bid, ask, spec))
        val err = OrderLines.validate(o, LineId.ENTRY, 1.105, bid, ask, spec)!!
        assertTrue(err, err.contains("Buy Limit") && err.contains("低于"))
    }

    @Test fun sellLimitMustBeAboveBid() {
        val o = order(PendingType.SELL_LIMIT, 1.11)
        assertNull(OrderLines.validate(o, LineId.ENTRY, 1.1050, bid, ask, spec))
        assertNotNull(OrderLines.validate(o, LineId.ENTRY, bid, bid, ask, spec))
        assertTrue(OrderLines.validate(o, LineId.ENTRY, 1.095, bid, ask, spec)!!.contains("高于"))
    }

    @Test fun buyStopMustBeAboveAsk() {
        val o = order(PendingType.BUY_STOP, 1.11)
        assertNull(OrderLines.validate(o, LineId.ENTRY, 1.1200, bid, ask, spec))
        assertNotNull(OrderLines.validate(o, LineId.ENTRY, ask, bid, ask, spec))
        assertTrue(OrderLines.validate(o, LineId.ENTRY, 1.09, bid, ask, spec)!!.contains("高于"))
    }

    @Test fun sellStopMustBeBelowBid() {
        val o = order(PendingType.SELL_STOP, 1.09)
        assertNull(OrderLines.validate(o, LineId.ENTRY, 1.0800, bid, ask, spec))
        assertNotNull(OrderLines.validate(o, LineId.ENTRY, bid, bid, ask, spec))
        assertTrue(OrderLines.validate(o, LineId.ENTRY, 1.12, bid, ask, spec)!!.contains("低于"))
    }

    @Test fun stopLimitTriggerAndLimitOrdering() {
        val buy = order(PendingType.BUY_STOP_LIMIT, 1.11, stopLimit = 1.105)
        assertNull(OrderLines.validate(buy, LineId.ENTRY, 1.115, bid, ask, spec))
        assertNotNull("触发价必须高于 Ask", OrderLines.validate(buy, LineId.ENTRY, 1.095, bid, ask, spec))
        assertNotNull("触发价移到限价之下 → 限价必须低于触发价", OrderLines.validate(buy, LineId.ENTRY, 1.104, bid, ask, spec))
        assertNull(OrderLines.validate(buy, LineId.STOP_LIMIT, 1.1, bid, ask, spec))
        assertNotNull("限价不得高于触发价", OrderLines.validate(buy, LineId.STOP_LIMIT, 1.112, bid, ask, spec))
        val sell = order(PendingType.SELL_STOP_LIMIT, 1.09, stopLimit = 1.095)
        assertNull(OrderLines.validate(sell, LineId.ENTRY, 1.085, bid, ask, spec))
        assertNotNull(OrderLines.validate(sell, LineId.STOP_LIMIT, 1.088, bid, ask, spec))
    }

    @Test fun minStopDistanceIsRespected() {
        val bl = order(PendingType.BUY_LIMIT, 1.09)
        assertNull(OrderLines.validate(bl, LineId.ENTRY, 1.0990, bid, ask, specStops))      // ask-0.001 = 1.0991
        assertNotNull(OrderLines.validate(bl, LineId.ENTRY, 1.1000, bid, ask, specStops))
        val ss = order(PendingType.SELL_STOP, 1.09)
        assertNull(OrderLines.validate(ss, LineId.ENTRY, 1.0989, bid, ask, specStops))
        assertNotNull(OrderLines.validate(ss, LineId.ENTRY, 1.0995, bid, ask, specStops))
        // TP 离开仓价至少 100 点
        val withTp = order(PendingType.BUY_LIMIT, 1.09, tp = 1.0950)
        assertNotNull(OrderLines.validate(withTp, LineId.TP, 1.0905, bid, ask, specStops))
        assertNull(OrderLines.validate(withTp, LineId.TP, 1.0915, bid, ask, specStops))
    }

    @Test fun movingEntryThatInvalidatesExistingTpSlIsRejectedWithChineseError() {
        // Buy Limit 1.09，TP 1.095，SL 1.085；把入场价拖到 1.097 → TP 不再高于入场价
        val o = order(PendingType.BUY_LIMIT, 1.09, sl = 1.085, tp = 1.095)
        val err = OrderLines.validate(o, LineId.ENTRY, 1.097, bid, ask, spec)!!
        assertTrue(err, err.startsWith("无法移动") && err.contains("TP") && err.contains("保持不变"))
        // 拖到 1.083 → SL 不再低于入场价
        val err2 = OrderLines.validate(o, LineId.ENTRY, 1.083, bid, ask, spec)!!
        assertTrue(err2, err2.contains("SL"))
        // 仍在 TP 与 SL 之间 → 合法，TP/SL 不动
        assertNull(OrderLines.validate(o, LineId.ENTRY, 1.092, bid, ask, spec))
    }

    @Test fun draggingTpSlOfPendingOrderIsValidatedAgainstEntry() {
        val sell = order(PendingType.SELL_LIMIT, 1.11, sl = 1.12, tp = 1.10)
        assertNull(OrderLines.validate(sell, LineId.TP, 1.095, bid, ask, spec))
        assertTrue(OrderLines.validate(sell, LineId.TP, 1.115, bid, ask, spec)!!.contains("TP 必须低于"))
        assertNull(OrderLines.validate(sell, LineId.SL, 1.13, bid, ask, spec))
        assertTrue(OrderLines.validate(sell, LineId.SL, 1.105, bid, ask, spec)!!.contains("SL 必须高于"))
        // Stop Limit 的 TP/SL 以限价为基准
        val sl = order(PendingType.BUY_STOP_LIMIT, 1.11, stopLimit = 1.105, tp = 1.12)
        assertNull(OrderLines.validate(sl, LineId.TP, 1.108, bid, ask, spec))
        assertNotNull(OrderLines.validate(sl, LineId.TP, 1.104, bid, ask, spec))
    }

    @Test fun confirmTextShowsOldArrowNew() {
        val o = order(PendingType.BUY_LIMIT, 1.09)
        val t = OrderLines.confirmText(OrderMoveProposal(o, LineId.ENTRY, 1.09, 1.0875), spec)
        assertEquals("#9001 Buy Limit  价格  1.09000 → 1.08750", t)
        assertTrue(OrderLines.confirmText(OrderMoveProposal(o.copy(takeProfit = 1.1), LineId.TP, 1.1, 1.105), spec).contains("止盈 TP"))
    }
}

/** ViewModel 层：松手 → 校验 → 确认条 → 才调用 modifyOrder；取消 / 非法 → 不发请求、线回到原价（演示模式） */
@OptIn(ExperimentalCoroutinesApi::class)
class OrderDragViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private class Counting(val d: DemoTradingApi) : TradingApi by d {
        var modifyCalls = 0
        var last: List<Any?> = emptyList()
        override suspend fun modifyOrder(ticket: String, price: Double, stopLimit: Double?, sl: Double?, tp: Double?, expiration: Expiration): TradeResult {
            modifyCalls++; last = listOf(ticket, price, stopLimit, sl, tp, expiration)
            return d.modifyOrder(ticket, price, stopLimit, sl, tp, expiration)
        }
    }

    private suspend fun TestScope.setup(): Triple<MainViewModel, Counting, String> {
        val demo = DemoTradingApi(autoTick = false)
        demo.setBid("EURUSD", 1.10000)
        val api = Counting(demo)
        val vm = MainViewModel()
        vm.configure(false, "", "", "EURUSD", apiOverride = api)
        advanceTimeBy(1500)
        demo.placePending("EURUSD", PendingType.BUY_LIMIT, 0.1, 1.09000, null, 1.08000, 1.12000, Expiration())
        advanceTimeBy(1500)
        val t = vm.trade.value.orders.single().ticket
        return Triple(vm, api, t)
    }

    @Test fun validDragShowsConfirmThenModifiesKeepingTpSl() = runTest(dispatcher) {
        val (vm, api, t) = setup()
        vm.proposeOrderMove(t, LineId.ENTRY, 1.08751234)
        val p = vm.orderMove.value!!
        assertEquals(1.09, p.oldPrice, 1e-9); assertEquals(1.08751, p.newPrice, 1e-9)
        assertEquals("确认前不调用服务器", 0, api.modifyCalls)
        assertEquals(1.09, vm.trade.value.orders.single().price, 1e-9)
        vm.confirmOrderMove()
        advanceTimeBy(1500)
        assertEquals(1, api.modifyCalls)
        val o = vm.trade.value.orders.single()
        assertEquals(1.08751, o.price, 1e-9)
        assertEquals("SL 价格不变", 1.08, o.stopLoss!!, 1e-9)
        assertEquals("TP 价格不变", 1.12, o.takeProfit!!, 1e-9)
        assertNull(vm.orderMove.value)
        vm.viewModelScope.cancel()
    }

    @Test fun cancelSnapsBackWithoutCallingServer() = runTest(dispatcher) {
        val (vm, api, t) = setup()
        vm.proposeOrderMove(t, LineId.ENTRY, 1.0850)
        assertNotNull(vm.orderMove.value)
        vm.cancelOrderMove()
        advanceTimeBy(1500)
        assertNull(vm.orderMove.value)
        assertEquals(0, api.modifyCalls)
        assertEquals(1.09, vm.trade.value.orders.single().price, 1e-9)
        vm.viewModelScope.cancel()
    }

    @Test fun invalidPriceShowsChineseErrorAndSendsNothing() = runTest(dispatcher) {
        val (vm, api, t) = setup()
        vm.proposeOrderMove(t, LineId.ENTRY, 1.1050) // Buy Limit 必须低于 Ask 1.10008
        assertNull(vm.orderMove.value)
        assertTrue(vm.message.value!!.startsWith("❌") && vm.message.value!!.contains("Buy Limit"))
        assertEquals(0, api.modifyCalls)
        vm.proposeOrderMove(t, LineId.ENTRY, 1.0750) // SL 1.08 > 入场价 → 无效
        assertNull(vm.orderMove.value)
        assertTrue(vm.message.value!!.contains("SL"))
        assertEquals(0, api.modifyCalls)
        assertEquals(1.09, vm.trade.value.orders.single().price, 1e-9)
        vm.viewModelScope.cancel()
    }

    @Test fun unchangedPriceOrUnknownTicketIsIgnored() = runTest(dispatcher) {
        val (vm, api, t) = setup()
        vm.proposeOrderMove(t, LineId.ENTRY, 1.090002)
        assertNull(vm.orderMove.value)
        vm.proposeOrderMove("nope", LineId.ENTRY, 1.08)
        assertNull(vm.orderMove.value)
        assertEquals(0, api.modifyCalls)
        vm.viewModelScope.cancel()
    }

    @Test fun draggingTpOfPendingOrderCommitsOnlyTp() = runTest(dispatcher) {
        val (vm, api, t) = setup()
        assertNull("合法 TP 拖动无错误", vm.checkOrderMove(t, LineId.TP, 1.115))
        assertNotNull("TP 拖到入场价之下无效", vm.checkOrderMove(t, LineId.TP, 1.085))
        vm.proposeOrderMove(t, LineId.TP, 1.115)
        vm.confirmOrderMove()
        advanceTimeBy(1500)
        val o = vm.trade.value.orders.single()
        assertEquals(1.115, o.takeProfit!!, 1e-9); assertEquals(1.09, o.price, 1e-9); assertEquals(1.08, o.stopLoss!!, 1e-9)
        vm.viewModelScope.cancel()
    }

    @Test fun proposalDroppedWhenOrderFilledMeanwhile() = runTest(dispatcher) {
        val demo = DemoTradingApi(autoTick = false)
        demo.setBid("EURUSD", 1.10000)
        val vm = MainViewModel()
        vm.configure(false, "", "", "EURUSD", apiOverride = demo)
        advanceTimeBy(1500)
        demo.placePending("EURUSD", PendingType.BUY_LIMIT, 0.1, 1.09, null, null, null, Expiration())
        advanceTimeBy(1500)
        val t = vm.trade.value.orders.single().ticket
        vm.proposeOrderMove(t, LineId.ENTRY, 1.085)
        assertNotNull(vm.orderMove.value)
        demo.setBid("EURUSD", 1.0890) // 触发成交
        advanceTimeBy(1500)
        assertTrue(vm.trade.value.orders.isEmpty())
        assertNull("订单已成交 → 确认条消失", vm.orderMove.value)
        vm.viewModelScope.cancel()
    }
}
