package com.morris.mttpsl

import com.morris.mttpsl.api.Position
import com.morris.mttpsl.api.Side
import com.morris.mttpsl.domain.ProfitCalc
import com.morris.mttpsl.ui.ChartState
import com.morris.mttpsl.ui.PanelTab
import com.morris.mttpsl.ui.TicketMode
import com.morris.mttpsl.api.Quote
import org.junit.Assert.*
import org.junit.Test

class ProfitCalcTest {
    @Test fun eurusdBuy() = assertEquals(20.0, ProfitCalc.profit("EURUSD", Side.BUY, 0.1, 1.10000, 1.10200)!!, 1e-9)
    @Test fun eurusdSellLoss() = assertEquals(-1.23, ProfitCalc.profit("EURUSD", Side.SELL, 0.01, 1.10000, 1.10123)!!, 1e-9)
    @Test fun usdjpyConvertsByClosePrice() =
        assertEquals(6.62, ProfitCalc.profit("USDJPY", Side.BUY, 0.01, 150.000, 151.000)!!, 0.006)
    @Test fun xauusdContract100() = assertEquals(100.0, ProfitCalc.profit("XAUUSD", Side.BUY, 0.1, 2000.0, 2010.0)!!, 1e-9)
    @Test fun crossUsesQuotes() {
        // EURGBP：报价货币 GBP → GBPUSD 1.25
        assertEquals(125.0, ProfitCalc.profit("EURGBP", Side.BUY, 1.0, 0.85, 0.851, mapOf("GBPUSD" to 1.25))!!, 1e-6)
        // EURJPY：USDJPY 150 → 1/150
        assertEquals(100000 * 1.0 / 150, ProfitCalc.profit("EURJPY", Side.BUY, 1.0, 160.0, 161.0, mapOf("USDJPY" to 150.0))!!, 0.01)
        assertNull(ProfitCalc.profit("EURGBP", Side.BUY, 1.0, 0.85, 0.851))
    }
    @Test fun calibrateFromServerProfit() {
        val p = Position("1", "EURUSD", Side.BUY, 0.5, 1.1000, null, null, currentPrice = 1.1010, profit = 50.0)
        val k = ProfitCalc.calibrate(listOf(p), "EURUSD")!!
        assertEquals(100_000.0, k, 1e-6)
        assertEquals(-10.0, ProfitCalc.profit("EURUSD", Side.SELL, 0.1, 1.1000, 1.1010, usdPerUnitLot = k)!!, 1e-9)
        assertNull(ProfitCalc.calibrate(listOf(p), "GBPUSD"))
    }
    @Test fun formatLikeMt5() {
        assertEquals("-1.23 USD", ProfitCalc.format(-1.234))
        assertEquals("1.23 USD", ProfitCalc.format(1.23))
        assertEquals("— USD", ProfitCalc.format(null))
    }
    @Test fun chartStateExpectedPnlAtTpSl() {
        val s = ChartState(symbol = "EURUSD", mode = TicketMode.PANEL, tab = PanelTab.PREP, lotsText = "0.10",
            quote = Quote("EURUSD", 1.10000, 1.10010, 0))
        assertEquals(19.0, s.pnlAt(1.10200, emptyMap())!!, 1e-9)   // 买入入场 = ask
        assertEquals(-16.0, s.pnlAt(1.09850, emptyMap())!!, 1e-9)
    }
    @Test fun prepRequiresTpWithChineseHint() {
        val s = ChartState(symbol = "EURUSD", mode = TicketMode.PANEL, tab = PanelTab.PREP, sheetOpen = true,
            quote = Quote("EURUSD", 1.1, 1.1001, 0), spec = com.morris.mttpsl.api.SymbolSpec("EURUSD", 5, 0.00001, 0), tpOn = false)
        assertTrue(s.validationError()!!.contains("TP"))
    }
}
