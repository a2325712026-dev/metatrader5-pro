package com.morris.mttpsl

import androidx.lifecycle.viewModelScope
import com.morris.mttpsl.api.DemoTradingApi
import com.morris.mttpsl.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** v11：下单面板“展开 / 收起”双向切换且被记住；交易量不需要展开就能改 */
@OptIn(ExperimentalCoroutinesApi::class)
class PanelStateTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private suspend fun TestScope.vm(): MainViewModel {
        val demo = DemoTradingApi(autoTick = false); demo.setBid("EURUSD", 1.10000)
        val vm = MainViewModel()
        vm.configure(false, "", "", "EURUSD", apiOverride = demo)
        advanceTimeBy(1500)
        return vm
    }

    @Test fun toggleLabelsAreExplicitBothWays() {
        assertEquals("展开 ▼", PanelUi.toggleLabel(false))
        assertEquals("收起 ▲", PanelUi.toggleLabel(true))
    }

    @Test fun toggleWorksBothWaysRepeatedly() = runTest(dispatcher) {
        val vm = vm()
        vm.selectPanelTab(PanelTab.BUY_LIMIT); advanceTimeBy(100)
        assertFalse(vm.chart.value.expanded)
        vm.toggleExpanded(); assertTrue(vm.chart.value.expanded)
        vm.toggleExpanded(); assertFalse("展开后必须能再收起（v10 的 bug）", vm.chart.value.expanded)
        vm.toggleExpanded(); assertTrue(vm.chart.value.expanded)
        vm.viewModelScope.cancel()
    }

    @Test fun volumeEditableWithoutExpanding() = runTest(dispatcher) {
        val vm = vm()
        vm.selectPanelTab(PanelTab.BUY_LIMIT); advanceTimeBy(100)
        assertFalse("面板默认是收起的", vm.chart.value.expanded)
        vm.stepLots(1); assertEquals("0.02", vm.chart.value.lotsText)
        vm.stepLots(1); vm.stepLots(-1); assertEquals("0.02", vm.chart.value.lotsText)
        vm.setLotsText("0.35"); assertEquals(0.35, vm.chart.value.lots!!, 1e-9)
        vm.setLotsText("abc1.5x"); assertEquals("键盘输入只保留数字和小数点", "1.5", vm.chart.value.lotsText)
        for (i in 1..5) vm.stepLots(-1)
        vm.setLotsText("0.01"); vm.stepLots(-1); assertEquals("不会降到 0.01 以下", "0.01", vm.chart.value.lotsText)
        assertFalse(vm.chart.value.expanded)
        vm.viewModelScope.cancel()
    }

    @Test fun invalidVolumeTextIsRejectedByValidationNotCrash() = runTest(dispatcher) {
        val vm = vm()
        vm.selectPanelTab(PanelTab.BUY_LIMIT); advanceTimeBy(100)
        vm.setLotsText(""); assertNotNull(vm.chart.value.validationError())
        vm.setLotsText("."); assertNotNull(vm.chart.value.validationError())
        vm.setLotsText("1.2.3"); assertNotNull(vm.chart.value.validationError())
        vm.setLotsText("0"); assertNotNull(vm.chart.value.validationError())
        vm.stepLots(1) // 无效文本上步进也不崩
        assertEquals("0.01", vm.chart.value.lotsText)
        vm.viewModelScope.cancel()
    }

    @Test fun choiceIsRememberedWhileSwitchingOrderTypeAndResetOnClose() = runTest(dispatcher) {
        val vm = vm()
        vm.selectPanelTab(PanelTab.BUY_LIMIT); advanceTimeBy(100)
        vm.toggleExpanded(); assertTrue(vm.chart.value.expanded)
        vm.selectPanelTab(PanelTab.SELL_STOP); advanceTimeBy(100)
        assertTrue("切换订单类型后仍保持展开", vm.chart.value.expanded)
        vm.selectPanelTab(PanelTab.PREP); advanceTimeBy(100)
        assertTrue(vm.chart.value.expanded)
        vm.toggleExpanded(); assertFalse(vm.chart.value.expanded)
        vm.selectPanelTab(PanelTab.MARKET); advanceTimeBy(100)
        assertFalse("用户收起后，市价单也不再强制展开", vm.chart.value.expanded)
        vm.closePanel()
        assertFalse(vm.chart.value.expanded); assertFalse(vm.chart.value.expandedByUser)
        vm.selectPanelTab(PanelTab.MARKET); advanceTimeBy(100)
        assertTrue("没有用户选择时恢复各类型默认值（市价单默认展开）", vm.chart.value.expanded)
        vm.viewModelScope.cancel()
    }

    @Test fun panelFractionLeavesRoomForChart() {
        assertTrue(PANEL_MAX_FRACTION in 0.5f..0.8f)
    }
}
