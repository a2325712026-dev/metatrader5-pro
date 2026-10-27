package com.morris.mttpsl

import com.morris.mttpsl.api.*
import com.morris.mttpsl.ui.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** v11 闪退审计：崩溃日志、坏数据解析、WebSocket 重连、除零 / NaN / 越界防护 */
class CrashRobustnessTest {
    @get:Rule val tmp = TemporaryFolder()

    // ---------- 崩溃日志 ----------
    @Test fun crashLogWritesReadsAndKeepsLastEntry() {
        val d = tmp.newFolder("crash")
        assertNull(CrashLog.read(d))
        CrashLog.write(d, CrashLog.format("main", IllegalStateException("boom-1"), nowMs = 0L, version = "0.11 (11)"))
        CrashLog.write(d, CrashLog.format("DefaultDispatcher-worker-1", NullPointerException("boom-2"), nowMs = 3_600_000L))
        val all = CrashLog.read(d)!!
        assertTrue(all.contains("boom-1") && all.contains("boom-2"))
        val last = CrashLog.last(d)!!
        assertTrue(last.contains("boom-2")); assertFalse(last.contains("boom-1"))
        assertTrue("含堆栈", last.contains("at com.morris.mttpsl.CrashRobustnessTest"))
        assertTrue("时间按 UTC+8", last.contains("1970-01-01 09:00:00") || last.contains("1970-01-01 09:00"))
        assertTrue(last.contains("致命(闪退)"))
        CrashLog.clear(d); assertNull(CrashLog.read(d)); assertNull(CrashLog.last(d))
    }

    @Test fun crashLogIsBoundedAndNeverThrows() {
        val d = tmp.newFolder("crash2")
        repeat(40) { CrashLog.write(d, "x".repeat(5000) + it) }
        assertTrue(CrashLog.read(d)!!.length <= CrashLog.MAX_CHARS)
        assertTrue("保留最新的", CrashLog.read(d)!!.endsWith("39"))
        // 目录不可写（路径被一个文件占用）→ 返回 null 而不是抛出
        val f = tmp.newFile("blocker")
        assertNull(CrashLog.write(java.io.File(f, "sub"), "x"))
        assertNull(CrashLog.read(java.io.File(f, "sub")))
    }

    @Test fun crashLogRedactsSecrets() {
        val t = CrashLog.format("main", RuntimeException("failed X-API-Key: sk-12345 and password=hunter2 token=\"abc\" url=http://h/x?api_key=zzz"), 0L, false)
        assertFalse(t.contains("sk-12345")); assertFalse(t.contains("hunter2")); assertFalse(t.contains("abc\"")); assertFalse(t.contains("zzz"))
        assertTrue(t.contains("***")); assertTrue(t.contains("已捕获(未闪退)"))
    }

    // ---------- 解析：坏数据只丢该项 ----------
    private fun server(vararg bodies: String): MockWebServer = MockWebServer().apply {
        bodies.forEach { enqueue(MockResponse().setBody(it)) }; start()
    }
    private fun api(s: MockWebServer) = Mt5BridgeTradingApi(s.url("/").toString(), "test-key-not-real")

    @Test fun positionsWithMissingFieldsAreSkippedNotFatal() = runBlocking {
        val s = server("""[{"ticket":"1","symbol":"EURUSD","type":"BUY","volume":0.1,"price_open":1.1},
            {"ticket":"2","symbol":"EURUSD"},{"ticket":"3","symbol":"EURUSD","type":"SELL","volume":0,"price_open":1.1},
            {"ticket":"4","symbol":"EURUSD","type":"SELL","volume":0.2,"price_open":1.2,"tp":0,"sl":1.3}]""")
        val p = api(s).positions()
        assertEquals(listOf("1", "4"), p.map { it.id })
        assertNull(p[1].takeProfit); assertEquals(1.3, p[1].stopLoss!!, 1e-9)
        s.shutdown()
    }

    @Test fun ordersAndHistoryTolerateBadItems() = runBlocking {
        val s = server("""[{"ticket":"9","symbol":"EURUSD","type":"BUY_LIMIT","volume":0.1,"price":1.09},
            {"ticket":"8","symbol":"EURUSD","type":"WEIRD","volume":0.1,"price":1.09},
            {"ticket":"7","symbol":"EURUSD","type":"SELL_LIMIT","volume":0.1},
            {"ticket":"6","symbol":"EURUSD","type":"SELL_LIMIT","volume":0.1,"price":0}]""",
            """[{"ticket":"1","time":1700000000,"symbol":"EURUSD","type":"BUY","volume":0.1,"price":1.1,"profit":2.0},{"ticket":"2"}, 5]""")
        val a = api(s)
        assertEquals(listOf("9"), a.orders().map { it.ticket })
        assertEquals(listOf("1"), a.history(7).map { it.ticket })
        s.shutdown()
    }

    @Test fun candlesDropNaNZeroAndInvertedBarsAndAreSorted() = runBlocking {
        val s = server("""[{"time":120,"open":1.2,"high":1.3,"low":1.1,"close":1.25},
            {"time":60,"open":1.0,"high":1.1,"low":0.9,"close":1.05},
            {"time":180,"open":0,"high":0,"low":0,"close":0},
            {"time":240,"open":1.0,"high":0.5,"low":2.0,"close":1.0},
            {"time":300,"open":1.0,"high":1.1,"low":0.9},
            {"time":0,"open":1.0,"high":1.1,"low":0.9,"close":1}]""")
        val c = api(s).candles("EURUSD", "H1", 100)
        assertEquals(listOf(60_000L, 120_000L), c.map { it.time })
        s.shutdown()
    }

    @Test fun symbolSpecWithZeroPointIsRejectedWithMessage() = runBlocking {
        val s = server("""{"digits":5,"point":0,"trade_stops_level":0}""", """{"digits":5,"point":0.00001}""", """{"digits":99,"point":0.1}""")
        val a = api(s)
        assertTrue(runCatching { a.symbolSpec("EURUSD") }.exceptionOrNull() is IllegalStateException)
        assertEquals("缺 trade_stops_level 时按 0 处理", 0, a.symbolSpec("EURUSD").stopsLevelPoints)
        assertNotNull(runCatching { a.symbolSpec("EURUSD") }.exceptionOrNull())
        s.shutdown()
    }

    @Test fun quoteListSkipsZeroPriceAndMissingFields() = runBlocking {
        val s = server("""[{"symbol":"EURUSD","bid":1.1,"ask":1.1002,"digits":5,"point":0.00001,"spread":2,"change":0.1,"time_msc":1},
            {"symbol":"GBPUSD","bid":0,"ask":0},{"symbol":"USDJPY"}]""")
        assertEquals(listOf("EURUSD"), api(s).quoteList(listOf("EURUSD", "GBPUSD", "USDJPY")).map { it.symbol })
        s.shutdown()
    }

    @Test fun parseTickIgnoresGarbage() {
        assertNull(parseTick("EURUSD", "not json"))
        assertNull(parseTick("EURUSD", """{"bid":1.1}"""))
        assertNull(parseTick("EURUSD", """{"bid":0,"ask":0,"time_msc":1}"""))
        assertNull(parseTick("EURUSD", """{"bid":"x","ask":1}"""))
        assertEquals(1.1, parseTick("EURUSD", """{"bid":1.1,"ask":1.2,"time_msc":5}""")!!.bid, 1e-9)
        assertNotNull("缺 time_msc 也可用", parseTick("EURUSD", """{"bid":1.1,"ask":1.2}"""))
    }

    @Test fun backoffGrowsAndCaps() {
        assertEquals(2000L, nextBackoff(1000)); assertEquals(15_000L, nextBackoff(10_000)); assertEquals(15_000L, nextBackoff(15_000))
        assertEquals(1000L, nextBackoff(0))
    }

    // ---------- WebSocket 重连 ----------
    @Test fun webSocketReconnectsAfterServerClosesAndSkipsBadMessages() {
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("garbage")
                webSocket.send("""{"bid":1.1,"ask":1.1002,"time_msc":1}""")
                webSocket.close(1001, "bye") // 服务器主动断开
            }
        }))
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("""{"bid":1.2,"ask":1.2002,"time_msc":2}""")
            }
        }))
        server.start()
        try {
            val got = runBlocking { withTimeout(15_000) { api(server).quotes("EURUSD").take(2).toList() } }
            assertEquals(listOf(1.1, 1.2), got.map { it.bid })
            assertEquals("断线后重新连接", 2, server.requestCount)
        } finally { server.shutdown() }
    }

    // ---------- 数值防护 ----------
    @Test fun priceRangeDegenerateDoesNotDivideByZero() {
        val r = PriceRange(1.0, 1.0)
        assertEquals(50f, r.y(1.0, 100f), 0f); assertEquals(50f, r.y(Double.NaN, 100f), 0f)
        assertEquals(1.0, r.price(10f, 100f), 0.0)
        assertTrue(PriceRange(Double.NaN, Double.NaN).price(10f, 100f).isFinite())
        assertEquals(0f, PriceRange(2.0, 1.0).y(2.0, 100f), 1e-4f)
    }

    @Test fun autoFitSurvivesNaNEmptyAndOutOfRange() {
        val h = doubleArrayOf(1.2, Double.NaN, 1.3); val l = doubleArrayOf(1.0, Double.NaN, 1.1)
        val r = Viewport.autoFit(h, l, -5, 99, listOf(Double.NaN, Double.POSITIVE_INFINITY, 1.4), 0.0)
        assertEquals(1.0, r.lo, 0.1); assertTrue(r.hi >= 1.4); assertTrue(r.span > 0)
        val e = Viewport.autoFit(doubleArrayOf(), doubleArrayOf(), 0, 0, emptyList(), 0.0)
        assertTrue(e.span > 0)
        assertTrue(Viewport.autoFit(doubleArrayOf(1.0), doubleArrayOf(1.0), 0, 0, emptyList(), 0.0).span > 0)
    }

    @Test fun snapIsSafeForNaNAndZeroPoint() {
        assertTrue(OrderLines.snap(Double.NaN, SymbolSpec("X", 5, 0.00001, 0)).isNaN())
        assertEquals(1.5, OrderLines.snap(1.5, SymbolSpec("X", 5, 0.0, 0)), 0.0)
        assertEquals(Double.POSITIVE_INFINITY, OrderLines.snap(Double.POSITIVE_INFINITY, SymbolSpec("X", 5, 0.00001, 0)), 0.0)
        assertFalse(OrderLines.moved(1.0, Double.NaN, SymbolSpec("X", 5, 0.00001, 0)))
        assertEquals(1.23457, OrderLines.snap(1.234567, SymbolSpec("X", 5, 0.00001, 0)), 1e-12)
    }

    @Test fun viewportWithZeroCandlesOrTinyWidthIsSafe() {
        val v = Viewport()
        assertTrue(v.visible(0, 100f).isEmpty())
        assertNotNull(v.panX(10f, 0, 1f).zoom(1.5f, 0f, 0, 1f))
        assertTrue(v.visible(1, 1f).count() <= 1)
    }

    // ---------- 配置存储损坏 ----------
    @Test fun configFlowFallsBackWhenStoreThrowsOrHasUnknownPlatform() = runBlocking {
        val bad = object : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
            override val data = kotlinx.coroutines.flow.flow<androidx.datastore.preferences.core.Preferences> { throw java.io.IOException("corrupt") }
            override suspend fun updateData(transform: suspend (androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences) = throw java.io.IOException()
        }
        val c = ConfigRepository(bad).config.first()
        assertEquals(AppConfig(), c)
        val weird = object : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
            override val data = kotlinx.coroutines.flow.flowOf(androidx.datastore.preferences.core.preferencesOf(
                androidx.datastore.preferences.core.stringPreferencesKey("platform") to "MT9"))
            override suspend fun updateData(transform: suspend (androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences) = throw java.io.IOException()
        }
        assertEquals(Platform.MT5, ConfigRepository(weird).config.first().platform)
    }
}
