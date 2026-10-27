package com.morris.mttpsl

import androidx.lifecycle.viewModelScope
import com.morris.mttpsl.api.DemoTradingApi
import com.morris.mttpsl.api.Side
import com.morris.mttpsl.ui.LineId
import com.morris.mttpsl.ui.MainViewModel
import com.morris.mttpsl.ui.PanelTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PrepFlowViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    /** 市价预备单：拖动 TP/SL → 确认 → 持仓带 TP/SL 并显示在交易状态 → 价格到 TP 后平仓 */
    @Test fun marketPrepOrderCarriesTpSlAndClosesAtTp() = runTest(dispatcher) {
        val demo = DemoTradingApi(autoTick = false)
        demo.setBid("EURUSD", 1.10000)
        val vm = MainViewModel()
        vm.configure(false, "", "", "EURUSD", apiOverride = demo)
        advanceTimeBy(1500)
        val initial = vm.trade.value.positions.size
        assertTrue("trade state loaded", vm.trade.value.loaded)

        vm.selectPanelTab(PanelTab.PREP)
        advanceTimeBy(100)
        val s0 = vm.chart.value
        assertEquals("TP spawned at market", 1.10000, s0.tp!!, 1e-9)
        vm.commitLines(mapOf(LineId.TP to 1.10200, LineId.SL to 1.09850)) // 模拟松手
        assertNull(vm.chart.value.validationError())
        vm.requestSubmit()
        assertNotNull("confirm dialog", vm.confirm.value)
        vm.runConfirm()
        advanceTimeBy(500)

        val pos = vm.trade.value.positions
        assertEquals(initial + 1, pos.size)
        val p = pos.last()
        assertEquals(Side.BUY, p.side)
        assertEquals(1.10200, p.takeProfit!!, 1e-9)
        assertEquals(1.09850, p.stopLoss!!, 1e-9)

        demo.setBid("EURUSD", 1.10200)
        advanceTimeBy(1500) // 轮询刷新
        assertTrue("closed at TP", vm.trade.value.positions.none { it.id == p.id })
        vm.viewModelScope.cancel()
    }
}
