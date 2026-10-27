package com.morris.mttpsl

import com.morris.mttpsl.api.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DemoTradingApiTest {
    private fun api() = DemoTradingApi(autoTick = false, seed = 1)
    private fun DemoTradingApi.open(side: Side, tp: Double?, sl: Double?, bid: Double = 1.10000): Position = runBlocking {
        setBid("EURUSD", bid)
        val before = positions().map { it.id }.toSet()
        assertTrue(marketOrder("EURUSD", side, 0.01, sl, tp) is TradeResult.Ok)
        positions().first { it.id !in before }
    }
    private fun DemoTradingApi.isOpen(p: Position) = runBlocking { positions().any { it.id == p.id } }
    private fun DemoTradingApi.lastOut() = runBlocking { history(1).first { it.entry == "OUT" } }

    @Test fun buyTakeProfitClosesWhenBidReachesTp() {
        val a = api()
        val p = a.open(Side.BUY, tp = 1.10200, sl = 1.09850)
        assertEquals(1.10200, p.takeProfit!!, 1e-9); assertEquals(1.09850, p.stopLoss!!, 1e-9)
        a.setBid("EURUSD", 1.10199); assertTrue("not yet", a.isOpen(p))
        a.setBid("EURUSD", 1.10200); assertFalse("closed at TP", a.isOpen(p))
        assertTrue(a.lastOut().profit > 0)
    }

    @Test fun buyStopLossClosesWhenBidReachesSl() {
        val a = api()
        val p = a.open(Side.BUY, tp = 1.10200, sl = 1.09850)
        a.setBid("EURUSD", 1.09851); assertTrue(a.isOpen(p))
        a.setBid("EURUSD", 1.09850); assertFalse(a.isOpen(p))
        assertTrue(a.lastOut().profit < 0)
    }

    @Test fun sellTakeProfitUsesAsk() {
        val a = api() // EURUSD 点差 8 points
        val p = a.open(Side.SELL, tp = 1.09800, sl = 1.10200)
        a.setBid("EURUSD", 1.09795); assertTrue("ask=1.09803 > TP, still open", a.isOpen(p))
        a.setBid("EURUSD", 1.09792); assertFalse("ask=1.09800 <= TP, closed", a.isOpen(p))
        assertTrue(a.lastOut().profit > 0)
    }

    @Test fun sellStopLossUsesAsk() {
        val a = api()
        val p = a.open(Side.SELL, tp = 1.09800, sl = 1.10200)
        a.setBid("EURUSD", 1.10191); assertTrue(a.isOpen(p))
        a.setBid("EURUSD", 1.10192); assertFalse(a.isOpen(p))
    }

    @Test fun positionWithoutTpSlStaysOpen() {
        val a = api()
        val p = a.open(Side.BUY, tp = null, sl = null)
        a.setBid("EURUSD", 1.20000); a.setBid("EURUSD", 1.00000)
        assertTrue(a.isOpen(p))
    }

    @Test fun buyLimitTriggersAndCarriesTpSlThenTpCloses() = runBlocking {
        val a = api()
        a.setBid("EURUSD", 1.10000)
        a.placePending("EURUSD", PendingType.BUY_LIMIT, 0.01, 1.09800, null, 1.09600, 1.10100, Expiration())
        assertEquals(1, a.orders().size)
        a.setBid("EURUSD", 1.09795) // ask 1.09803 > 1.09800 → 未触发
        assertEquals(1, a.orders().size)
        a.setBid("EURUSD", 1.09792) // ask 1.09800 → 触发
        assertEquals(0, a.orders().size)
        val p = a.positions().last()
        assertEquals(Side.BUY, p.side); assertEquals(1.10100, p.takeProfit!!, 1e-9); assertEquals(1.09600, p.stopLoss!!, 1e-9)
        a.setBid("EURUSD", 1.10100)
        assertTrue(a.positions().none { it.id == p.id })
    }

    @Test fun sellStopLimitBecomesSellLimitThenFills() = runBlocking {
        val a = api()
        a.setBid("EURUSD", 1.10000)
        a.placePending("EURUSD", PendingType.SELL_STOP_LIMIT, 0.01, 1.09800, 1.09900, null, null, Expiration())
        a.setBid("EURUSD", 1.09800) // 触发价 → 转为 Sell Limit @1.09900
        val o = a.orders().single()
        assertEquals(PendingType.SELL_LIMIT, o.type); assertEquals(1.09900, o.price, 1e-9)
        a.setBid("EURUSD", 1.09900)
        assertTrue(a.orders().isEmpty()); assertEquals(Side.SELL, a.positions().last().side)
    }

    @Test fun autoTickEngineMovesPricesWithoutCollectors() = runBlocking {
        val a = DemoTradingApi(autoTick = true, tickMs = 10)
        val q1 = a.quoteList(listOf("EURUSD")).single().bid
        var moved = false
        repeat(100) { if (!moved) { Thread.sleep(10); moved = a.quoteList(listOf("EURUSD")).single().bid != q1 } }
        assertTrue("engine ticks on its own", moved)
    }
}
