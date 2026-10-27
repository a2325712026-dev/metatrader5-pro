package com.morris.mttpsl

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import com.morris.mttpsl.api.*
import com.morris.mttpsl.domain.BatchClose
import com.morris.mttpsl.domain.BatchResult
import com.morris.mttpsl.domain.BatchSummary
import com.morris.mttpsl.ui.MainViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BatchCloseLogicTest {
    private fun pos(id: String, sym: String, side: Side, vol: Double, profit: Double) =
        Position(id, sym, side, vol, 1.1, null, null, 1.1, profit)
    private val all = listOf(
        pos("1", "EURUSD", Side.BUY, 0.10, 5.0),
        pos("2", "EURUSD", Side.BUY, 0.20, -2.5),
        pos("3", "EURUSD", Side.SELL, 0.05, 1.25),
        pos("4", "GBPUSD", Side.BUY, 1.00, 100.0),
        pos("5", "XAUUSD", Side.SELL, 0.01, -3.0),
    )

    @Test fun selectsOnlyCurrentSymbolAndDirection() {
        assertEquals(listOf("1", "2"), BatchClose.select(all, "EURUSD", Side.BUY).map { it.id })
        assertEquals(listOf("3"), BatchClose.select(all, "EURUSD", Side.SELL).map { it.id })
        assertEquals(listOf("4"), BatchClose.select(all, "GBPUSD", Side.BUY).map { it.id })
        assertTrue(BatchClose.select(all, "GBPUSD", Side.SELL).isEmpty())
        assertTrue("symbol match is exact", BatchClose.select(all, "EURUS", Side.BUY).isEmpty())
        assertTrue(BatchClose.select(emptyList(), "EURUSD", Side.BUY).isEmpty())
    }

    @Test fun enabledOnlyWhenPositionsExistAndNotBusy() {
        assertTrue(BatchClose.enabled(all, "EURUSD", Side.BUY))
        assertTrue(BatchClose.enabled(all, "EURUSD", Side.SELL))
        assertFalse("no GBPUSD sell", BatchClose.enabled(all, "GBPUSD", Side.SELL))
        assertTrue(BatchClose.enabled(all, "GBPUSD", Side.BUY))
        assertFalse("other symbol has none", BatchClose.enabled(all, "USDJPY", Side.BUY))
        assertFalse("busy disables", BatchClose.enabled(all, "EURUSD", Side.BUY, busy = true))
        assertFalse("not loaded disables", BatchClose.enabled(all, "EURUSD", Side.BUY, loaded = false))
        assertFalse(BatchClose.enabled(emptyList(), "EURUSD", Side.BUY))
    }

    @Test fun summaryTotals() {
        val s = BatchClose.summary(BatchClose.select(all, "EURUSD", Side.BUY))
        assertEquals(2, s.count); assertEquals(0.30, s.totalLots, 1e-9); assertEquals(2.5, s.totalProfit, 1e-9)
        val e = BatchClose.summary(emptyList())
        assertTrue(e.isEmpty); assertEquals(0.0, e.totalLots, 0.0); assertEquals(0.0, e.totalProfit, 0.0)
        // 浮点：0.1 + 0.2 不会出现 0.30000000000000004
        assertEquals(0.3, BatchClose.summary(listOf(pos("a", "X", Side.BUY, 0.1, 0.1), pos("b", "X", Side.BUY, 0.2, 0.2))).totalLots, 0.0)
        assertEquals(0.3, BatchClose.summary(listOf(pos("a", "X", Side.BUY, 0.1, 0.1), pos("b", "X", Side.BUY, 0.2, 0.2))).totalProfit, 0.0)
    }

    @Test fun labelsAndDialogTextAreChinese() {
        val s = BatchSummary(3, 0.35, -12.5)
        assertEquals("平买 ×3", BatchClose.buttonTitle(Side.BUY, 3))
        assertEquals("平卖", BatchClose.buttonTitle(Side.SELL, 0))
        assertEquals("-12.50", BatchClose.buttonSub(s))
        assertEquals("+2.50", BatchClose.buttonSub(BatchSummary(2, 0.3, 2.5)))
        assertEquals("—", BatchClose.buttonSub(BatchSummary(0, 0.0, 0.0)))
        val t = BatchClose.dialogText("EURUSD", Side.BUY, s)
        assertTrue(t, t.contains("买入")); assertTrue(t.contains("3 笔")); assertTrue(t.contains("0.35 手")); assertTrue(t.contains("-12.50 USD"))
        assertTrue(BatchClose.dialogTitle("EURUSD", Side.SELL).contains("卖出"))
    }

    @Test fun resultMessages() {
        assertEquals("✅ 已平仓 3 笔 EURUSD 买入持仓", BatchClose.resultMessage("EURUSD", Side.BUY, BatchResult(3, 0)))
        assertEquals("⚠️ 已平仓 2 笔，失败 1 笔（market closed）", BatchClose.resultMessage("EURUSD", Side.BUY, BatchResult(2, 1, "market closed")))
        assertEquals("❌ 2 笔全部平仓失败：boom", BatchClose.resultMessage("EURUSD", Side.SELL, BatchResult(0, 2, "boom")))
        assertTrue(BatchResult(1, 0).allOk); assertFalse(BatchResult(1, 1).allOk)
    }
}

class ConfirmSettingTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun defaultIsOn() { assertTrue(AppConfig().confirmBatchClose) }

    @Test fun persistsAcrossRepositoryInstances() = runTest {
        val file = File(tmp.root, "config.preferences_pb")
        val ds1 = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val repo1 = ConfigRepository(ds1)
        assertTrue("default ON when nothing stored", repo1.config.first().confirmBatchClose)
        repo1.setConfirmBatchClose(false)
        assertFalse(repo1.config.first().confirmBatchClose)
        // 其它设置的保存（含开关值）不会丢失
        repo1.save(AppConfig(symbol = "GBPUSD", confirmBatchClose = false))
        val c = repo1.config.first()
        assertEquals("GBPUSD", c.symbol); assertFalse(c.confirmBatchClose)
        repo1.setConfirmBatchClose(true)
        assertTrue(repo1.config.first().confirmBatchClose)
        assertEquals("other fields untouched by toggle", "GBPUSD", repo1.config.first().symbol)
    }

    @Test fun savedValueSurvivesNewDataStoreOnSameFile() = runTest {
        val file = File(tmp.root, "persist.preferences_pb")
        val scope1 = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Unconfined)
        val repo1 = ConfigRepository(PreferenceDataStoreFactory.create(scope = scope1) { file })
        repo1.setConfirmBatchClose(false)
        scope1.cancel()
        val scope2 = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Unconfined)
        val repo2 = ConfigRepository(PreferenceDataStoreFactory.create(scope = scope2) { file })
        assertFalse("persisted to disk", repo2.config.first().confirmBatchClose)
        scope2.cancel()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class BatchCloseViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun demoWithPositions(): DemoTradingApi {
        val demo = DemoTradingApi(autoTick = false)
        demo.setBid("EURUSD", 1.10000)
        demo.setBid("GBPUSD", 1.30000)
        runBlocking { // 3 EURUSD BUY, 2 EURUSD SELL, 1 GBPUSD BUY
            demo.marketOrder("EURUSD", Side.BUY, 0.10, null, null)
            demo.marketOrder("EURUSD", Side.BUY, 0.20, null, null)
            demo.marketOrder("EURUSD", Side.BUY, 0.05, null, null)
            demo.marketOrder("EURUSD", Side.SELL, 0.03, null, null)
            demo.marketOrder("EURUSD", Side.SELL, 0.02, null, null)
            demo.marketOrder("GBPUSD", Side.BUY, 1.0, null, null)
        }
        return demo
    }
    private fun runBlocking(b: suspend () -> Unit) = kotlinx.coroutines.runBlocking { b() }

    private fun TestScope.vmFor(api: TradingApi, confirm: Boolean = true): MainViewModel {
        val vm = MainViewModel()
        vm.setConfirmBatchClose(confirm)
        vm.configure(false, "", "", "EURUSD", apiOverride = api)
        advanceTimeBy(1500)
        return vm
    }

    @Test fun confirmOnShowsDialogThenClosesOnlyCurrentSymbolBuys() = runTest(dispatcher) {
        val demo = demoWithPositions()
        val vm = vmFor(demo)
        assertEquals(6, vm.trade.value.positions.size)
        vm.requestBatchClose(Side.BUY)
        val req = vm.batchRequest.value
        assertNotNull("dialog requested, nothing closed yet", req)
        assertEquals(3, req!!.tickets.size); assertEquals("EURUSD", req.symbol)
        assertEquals(6, vm.trade.value.positions.size)
        vm.confirmBatchClose()
        assertTrue(vm.batchBusy.value)
        advanceTimeBy(500)
        assertFalse(vm.batchBusy.value)
        assertNull(vm.batchRequest.value)
        val left = vm.trade.value.positions
        assertEquals("positions refreshed after close", 3, left.size)
        assertTrue(left.none { it.symbol == "EURUSD" && it.side == Side.BUY })
        assertEquals(2, left.count { it.symbol == "EURUSD" && it.side == Side.SELL })
        assertEquals(1, left.count { it.symbol == "GBPUSD" })
        assertEquals("✅ 已平仓 3 笔 EURUSD 买入持仓", vm.message.value)
        vm.viewModelScope.cancel()
    }

    @Test fun confirmOffClosesImmediatelyWithoutDialog() = runTest(dispatcher) {
        val demo = demoWithPositions()
        val vm = vmFor(demo, confirm = false)
        vm.requestBatchClose(Side.SELL)
        assertNull("no dialog", vm.batchRequest.value)
        assertTrue(vm.batchBusy.value)
        advanceTimeBy(500)
        val left = vm.trade.value.positions
        assertEquals(4, left.size)
        assertTrue(left.none { it.symbol == "EURUSD" && it.side == Side.SELL })
        assertEquals("✅ 已平仓 2 笔 EURUSD 卖出持仓", vm.message.value)
        vm.viewModelScope.cancel()
    }

    @Test fun dismissCancelsAndDisabledSideIsNoop() = runTest(dispatcher) {
        val demo = demoWithPositions()
        val vm = vmFor(demo)
        vm.requestBatchClose(Side.BUY); vm.dismissBatchClose()
        assertNull(vm.batchRequest.value)
        advanceTimeBy(500)
        assertEquals(6, vm.trade.value.positions.size)
        // 切到没有 SELL 持仓的品种：点击无效，无弹窗
        vm.openChart("GBPUSD"); advanceTimeBy(200)
        vm.requestBatchClose(Side.SELL)
        assertNull(vm.batchRequest.value); assertFalse(vm.batchBusy.value)
        vm.requestBatchClose(Side.BUY)
        assertEquals(listOf(vm.trade.value.positions.single { it.symbol == "GBPUSD" }.id), vm.batchRequest.value!!.tickets)
        vm.viewModelScope.cancel()
    }

    @Test fun partialFailureReportsClosedAndFailedCounts() = runTest(dispatcher) {
        val demo = demoWithPositions()
        val failId = runBlocking2 { demo.positions().first { it.symbol == "EURUSD" && it.side == Side.BUY && it.volume == 0.20 }.id }
        val flaky = object : TradingApi by demo {
            override suspend fun closePosition(positionId: String): TradeResult =
                if (positionId == failId) TradeResult.Error("requote") else demo.closePosition(positionId)
        }
        val vm = vmFor(flaky, confirm = false)
        vm.requestBatchClose(Side.BUY)
        advanceTimeBy(500)
        assertEquals("⚠️ 已平仓 2 笔，失败 1 笔（requote）", vm.message.value)
        val left = vm.trade.value.positions.filter { it.symbol == "EURUSD" && it.side == Side.BUY }
        assertEquals(listOf(failId), left.map { it.id })
        assertFalse(vm.batchBusy.value)
        vm.viewModelScope.cancel()
    }

    @Test fun exceptionsCountAsFailuresAndAllFailMessage() = runTest(dispatcher) {
        val demo = demoWithPositions()
        val throwing = object : TradingApi by demo {
            override suspend fun closePosition(positionId: String): TradeResult = throw java.io.IOException("timeout")
        }
        val vm = vmFor(throwing, confirm = false)
        vm.requestBatchClose(Side.BUY)
        advanceTimeBy(500)
        assertEquals("❌ 3 笔全部平仓失败：timeout", vm.message.value)
        assertEquals(6, vm.trade.value.positions.size)
        assertFalse("button usable again for retry", vm.batchBusy.value)
        vm.viewModelScope.cancel()
    }

    @Test fun doubleTapDoesNotCloseTwice() = runTest(dispatcher) {
        val demo = demoWithPositions()
        val calls = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        val slow = object : TradingApi by demo {
            override suspend fun closePosition(positionId: String): TradeResult { calls += positionId; gate.await(); return demo.closePosition(positionId) }
        }
        val vm = vmFor(slow, confirm = false)
        vm.requestBatchClose(Side.BUY); vm.requestBatchClose(Side.BUY); vm.requestBatchClose(Side.SELL) // 连点
        advanceTimeBy(100)
        assertTrue(vm.batchBusy.value)
        assertEquals("only first tap acted, first ticket in flight", 1, calls.size)
        gate.complete(Unit)
        advanceTimeBy(500)
        assertEquals("each BUY ticket closed exactly once", 3, calls.size)
        assertEquals(calls.toSet().size, calls.size)
        assertEquals(3, vm.trade.value.positions.size)
        assertEquals("✅ 已平仓 3 笔 EURUSD 买入持仓", vm.message.value)
        vm.viewModelScope.cancel()
    }

    @Test fun ticketsClosedMeanwhileAreSkippedNotReportedAsFailures() = runTest(dispatcher) {
        val demo = demoWithPositions()
        val vm = vmFor(demo)
        vm.requestBatchClose(Side.BUY)
        val gone = vm.batchRequest.value!!.tickets.first()
        demo.closePosition(gone) // 对话框打开期间被 TP/SL 或别处平掉
        advanceTimeBy(1500)
        vm.confirmBatchClose(); advanceTimeBy(500)
        assertEquals("✅ 已平仓 2 笔 EURUSD 买入持仓", vm.message.value)
        vm.viewModelScope.cancel()
    }

    private fun <T> runBlocking2(b: suspend () -> T): T = kotlinx.coroutines.runBlocking { b() }
}
