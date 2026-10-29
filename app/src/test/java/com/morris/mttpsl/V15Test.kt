package com.morris.mttpsl

import com.morris.mttpsl.api.*
import com.morris.mttpsl.domain.ProfitCalc
import com.morris.mttpsl.ui.ClosePlan
import com.morris.mttpsl.ui.levelToggleStyle
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException

class PartialCloseMathTest {
    private val pos = Position("101", "EURUSD", Side.BUY, 1.0, 1.1, null, null, 1.11, 100.0)

    @Test fun fullVolumeIsValid() { assertEquals(1.0 to null, ClosePlan.validate(pos, "1.00", 0.01, 0.01)) }
    @Test fun partialStepMultipleIsValid() { assertEquals(0.37, ClosePlan.validate(pos, "0.37", 0.01, 0.01).first!!, 1e-9) }
    @Test fun commaDecimalAccepted() { assertEquals(0.5, ClosePlan.validate(pos, "0,5", 0.01, 0.01).first!!, 1e-9) }
    @Test fun belowMinRejected() { assertNotNull(ClosePlan.validate(pos, "0.05", 0.1, 0.1).second) }
    @Test fun notStepMultipleRejected() { assertNotNull(ClosePlan.validate(pos, "0.015", 0.01, 0.01).second) }
    @Test fun aboveVolumeRejected() { assertNotNull(ClosePlan.validate(pos, "1.5", 0.01, 0.01).second) }
    @Test fun zeroAndGarbageRejected() {
        assertNotNull(ClosePlan.validate(pos, "0", 0.01, 0.01).second)
        assertNotNull(ClosePlan.validate(pos, "abc", 0.01, 0.01).second)
        assertNotNull(ClosePlan.validate(pos, "", 0.01, 0.01).second)
    }
    @Test fun remainderBelowMinRejected() { assertNotNull(ClosePlan.validate(pos, "0.95", 0.1, 0.05).second) }
    @Test fun stepButtonsClamp() {
        assertEquals("0.99", ClosePlan.step(pos, "1.00", -1, 0.01, 0.01))
        assertEquals("1.00", ClosePlan.step(pos, "1.00", +1, 0.01, 0.01))
        assertEquals("0.01", ClosePlan.step(pos, "0.01", -1, 0.01, 0.01))
        assertEquals("0.20", ClosePlan.step(pos, "0.1", +1, 0.1, 0.1))
    }
    @Test fun titleIsMt5Style() { assertEquals("平仓 #101 buy 1.00 EURUSD", ClosePlan.title(pos)) }
    @Test fun stepDecimals() { assertEquals(2, ClosePlan.stepDecimals(0.01)); assertEquals(1, ClosePlan.stepDecimals(0.1)); assertEquals(3, ClosePlan.stepDecimals(0.001)) }
    @Test fun closeBodyHasCleanVolume() { assertEquals(0.3, closeBody(0.1 + 0.2).getDouble("volume"), 0.0) }

    @Test fun demoPartialCloseKeepsRemainder() = runBlocking {
        val d = DemoTradingApi(autoTick = false)
        d.marketOrder("EURUSD", Side.BUY, 1.0, null, null)
        val id = d.positions().first().id
        assertTrue(d.closePosition(id, 0.4) is TradeResult.Ok)
        assertEquals(0.6, d.positions().first { it.id == id }.volume, 1e-9)
        assertTrue(d.closePosition(id, 0.6) is TradeResult.Ok)
        assertTrue(d.positions().none { it.id == id })
    }
}

class DragLabelMathTest {
    @Test fun pointsBuyAndSell() {
        assertEquals(7263L, ProfitCalc.points(Side.BUY, 1.10000, 1.17263, 0.00001))
        assertEquals(-120L, ProfitCalc.points(Side.BUY, 1.10000, 1.09880, 0.00001))
        assertEquals(120L, ProfitCalc.points(Side.SELL, 1.10000, 1.09880, 0.00001))
        assertEquals(0L, ProfitCalc.points(Side.BUY, 1.0, 2.0, 0.0))
    }
    @Test fun unitValueFromTickSpec() {
        // EURUSD：tick_value 1 USD / tick_size 0.00001 / 1 手 → 100000 USD 每价格单位
        assertEquals(100_000.0, ProfitCalc.unitValueFromSpec(1.0, 0.00001)!!, 1e-6)
        assertNull(ProfitCalc.unitValueFromSpec(0.0, 0.00001)); assertNull(ProfitCalc.unitValueFromSpec(1.0, 0.0))
    }
    @Test fun unitValueFromContract() {
        assertEquals(100.0, ProfitCalc.unitValueFromContract("XAUUSD", 100.0, 2400.0)!!, 1e-9)
        assertNull(ProfitCalc.unitValueFromContract("XAUUSD", 0.0, 2400.0))
    }
    @Test fun tpLabelMatchesMt5() {
        val k = ProfitCalc.unitValueFromSpec(1.0, 0.00001)
        val pr = ProfitCalc.profit("EURUSD", Side.BUY, 0.1, 1.10000, 1.17263, usdPerUnitLot = k)
        assertEquals(726.30, pr!!, 1e-9)
        assertEquals("TP, +726.30 USD, 7263 points", ProfitCalc.lineLabel("TP", pr, ProfitCalc.points(Side.BUY, 1.10000, 1.17263, 0.00001)))
    }
    @Test fun slLabelNegativeUsdUnsignedPoints() {
        val pr = ProfitCalc.profit("EURUSD", Side.SELL, 0.5, 1.10000, 1.10120, usdPerUnitLot = 100_000.0)
        assertEquals("SL, -60.00 USD, 120 points", ProfitCalc.lineLabel("SL", pr, ProfitCalc.points(Side.SELL, 1.10000, 1.10120, 0.00001)))
    }
    @Test fun goldLabel() {
        val pr = ProfitCalc.profit("XAUUSD", Side.BUY, 0.01, 2400.00, 2410.50, usdPerUnitLot = 100.0)
        assertEquals("TP, +10.50 USD, 1050 points", ProfitCalc.lineLabel("TP", pr, ProfitCalc.points(Side.BUY, 2400.00, 2410.50, 0.01)))
    }
    @Test fun nullProfitShowsDash() { assertEquals("— USD, 5 points", ProfitCalc.pnlPoints(null, -5)) }
    @Test fun tpSlButtonsGrayUntilSet() { assertFalse(levelToggleStyle(false).filled); assertTrue(levelToggleStyle(true).filled) }
}

class ConnectionStabilityTest {
    @Test fun transientClassification() {
        assertTrue(isTransientNetworkError(SocketTimeoutException("timeout")))
        assertTrue(isTransientNetworkError(ConnectException("Failed to connect")))
        assertTrue(isTransientNetworkError(java.io.IOException("wrapped", SocketTimeoutException("t"))))
        assertFalse(isTransientNetworkError(SSLHandshakeException("Trust anchor for certification path not found")))
        assertFalse(isTransientNetworkError(HttpStatusException(401, "bad key")))
    }
    @Test fun backoffDelays() { assertEquals(400L, retryDelayMs(1)); assertEquals(800L, retryDelayMs(2)); assertEquals(3000L, retryDelayMs(9)) }
    @Test fun gateNeedsThreeTransientFailures() {
        val g = TransientErrorGate()
        assertFalse(g.fail("q", true)); assertFalse(g.fail("q", true)); assertTrue(g.fail("q", true))
        g.ok("q"); assertFalse(g.fail("q", true))
        assertTrue("hard errors show immediately", g.fail("x", false))
    }
    @Test fun sameUrlAndFingerprintShareOneClient() {
        val fp = "AA".repeat(32)
        val a = BridgeHttp.client("https://1.2.3.4:8000", fp)
        val b = BridgeHttp.client("https://1.2.3.4:8000/", fp.chunked(2).joinToString(":"))
        assertSame(a, b)
        assertSame("connection pool shared with root", BridgeHttp.root.connectionPool, a.connectionPool)
        assertNotSame(a, BridgeHttp.client("https://1.2.3.4:8000", ""))
    }
    @Test fun bridgeClientUsesPinnedTrustManager() {
        val fp = "BB".repeat(32)
        val c = BridgeHttp.client("https://5.6.7.8:8000", fp)
        assertTrue(c.x509TrustManager is PinnedTrustManager)
    }
    @Test fun retryInterceptorRetriesGetOnTimeoutOnly() {
        val s = MockWebServer()
        s.enqueue(MockResponse().setBody("ok").setHeadersDelay(3, java.util.concurrent.TimeUnit.SECONDS))
        s.enqueue(MockResponse().setBody("ok2"))
        s.start()
        try {
            val sleeps = mutableListOf<Long>()
            val c = OkHttpClient.Builder().readTimeout(500, java.util.concurrent.TimeUnit.MILLISECONDS)
                .addInterceptor(RetryInterceptor(2) { sleeps += it }).build()
            val body = c.newCall(okhttp3.Request.Builder().url(s.url("/candles")).build()).execute().use { it.body!!.string() }
            assertEquals("ok2", body); assertEquals(listOf(400L), sleeps)
        } finally { s.shutdown() }
    }
    @Test fun retryInterceptorNeverRetriesPost() {
        val s = MockWebServer()
        s.enqueue(MockResponse().setBody("x").setHeadersDelay(3, java.util.concurrent.TimeUnit.SECONDS))
        s.start()
        try {
            val c = OkHttpClient.Builder().readTimeout(300, java.util.concurrent.TimeUnit.MILLISECONDS).addInterceptor(RetryInterceptor(2) { }).build()
            val body = okhttp3.RequestBody.create(null, "{}")
            try { c.newCall(okhttp3.Request.Builder().url(s.url("/order")).post(body).build()).execute(); fail() } catch (e: SocketTimeoutException) {}
            assertEquals(1, s.requestCount)
        } finally { s.shutdown() }
    }
}
