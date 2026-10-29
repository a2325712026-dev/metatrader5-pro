package com.morris.mttpsl

import com.morris.mttpsl.api.*
import com.morris.mttpsl.ui.MainViewModel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.After
import org.junit.Before
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.*
import androidx.lifecycle.viewModelScope

/** v0.13：只允许 HTTPS。http:// / ws:// 在任何入口都被拒绝，并且绝不发出明文请求。 */
class HttpsOnlyTest {
    @Test fun messageIsTheRequiredChineseText() {
        assertEquals("必须使用 HTTPS 地址", HTTPS_REQUIRED_MESSAGE)
        assertEquals(HTTPS_REQUIRED_MESSAGE, HttpsRequiredException("http://x").message)
    }

    @Test fun violationDetectionAfterNormalization() {
        assertNull("empty is not a violation (handled elsewhere)", httpsViolation(""))
        assertNull(httpsViolation(normalizeServerUrl("203.0.113.10:8000")))          // 没写协议 → https
        assertNull(httpsViolation(normalizeServerUrl("HTTPS://a.b")))
        assertNull(httpsViolation(normalizeServerUrl("wss://a.b:8443")))              // wss → https
        assertEquals(HTTPS_REQUIRED_MESSAGE, httpsViolation(normalizeServerUrl("http://203.0.113.10:8000")))
        assertEquals(HTTPS_REQUIRED_MESSAGE, httpsViolation(normalizeServerUrl("HTTP://a.b")))
        assertEquals(HTTPS_REQUIRED_MESSAGE, httpsViolation(normalizeServerUrl("ws://a.b:8000")))   // ws → http → 拒绝
        assertEquals(HTTPS_REQUIRED_MESSAGE, httpsViolation(normalizeServerUrl("ftp://a.b")))
    }

    @Test fun normalizeNeverSilentlyUpgradesHttp() {
        assertEquals("http://1.2.3.4:8000", normalizeServerUrl("http://1.2.3.4:8000"))  // 保留 http，交给校验拒绝（不悄悄改写用户的意图）
    }

    @Test fun requireHttpsThrows() {
        requireHttps("https://a.b")
        assertTrue(try { requireHttps("http://a.b"); false } catch (e: HttpsRequiredException) { e.message == HTTPS_REQUIRED_MESSAGE })
        assertTrue(try { requireHttps(""); false } catch (_: HttpsRequiredException) { true })
    }

    @Test fun clientCreationRejectsHttp() {
        assertTrue(try { bridgeHttpClient("http://203.0.113.10:8000", ""); false } catch (_: HttpsRequiredException) { true })
        assertTrue(try { bridgeHttpClient("ws://203.0.113.10:8000", ""); false } catch (_: HttpsRequiredException) { true })
        bridgeHttpClient("203.0.113.10:8000", "") // 默认 https，可以创建
    }

    @Test fun tradingApiRejectsHttpEvenWithInjectedClient() {
        assertTrue(try { Mt5BridgeTradingApi("http://203.0.113.10:8000", "k", http = OkHttpClient()); false } catch (_: HttpsRequiredException) { true })
        assertTrue(try { Mt5BridgeTradingApi("http://203.0.113.10:8000", "k"); false } catch (_: HttpsRequiredException) { true })
    }

    @Test fun testButtonRejectsHttpAndNeverConnects() {
        val s = MockWebServer(); s.enqueue(MockResponse().setBody("{}")); s.start()
        try {
            for (u in listOf("http://${s.hostName}:${s.port}", "ws://${s.hostName}:${s.port}")) {
                val r = runBlocking { ConnectionTester().test(u, "key") } as ConnTestResult.Failure
                assertEquals(ConnKind.HTTPS_REQUIRED, r.issue.kind)
                assertEquals(HTTPS_REQUIRED_MESSAGE, r.issue.title)
                assertTrue(r.issue.hint.contains("https://"))
            }
            assertEquals("no request ever reached the server", 0, s.requestCount)
        } finally { s.shutdown() }
    }

    @Test fun httpsOnlyClientBlocksCleartextRequestsAtOkHttpLevel() {
        val s = MockWebServer(); s.enqueue(MockResponse().setBody("x")); s.start()
        try {
            val client = OkHttpClient.Builder().httpsOnly().build()
            assertTrue("no CLEARTEXT connection spec", client.connectionSpecs.none { it == okhttp3.ConnectionSpec.CLEARTEXT })
            assertFalse(client.followSslRedirects)
            val e = try { client.newCall(Request.Builder().url("http://${s.hostName}:${s.port}/").build()).execute(); null } catch (e: Exception) { e }
            assertTrue("blocked: $e", e is HttpsRequiredException)
            assertEquals(0, s.requestCount)
        } finally { s.shutdown() }
    }

    @Test fun cleartextClassifierHintNoLongerPromisesHttpSupport() {
        val i = classifyError(java.net.UnknownServiceException("CLEARTEXT communication to 1.2.3.4 not permitted"))
        assertEquals(ConnKind.CLEARTEXT_BLOCKED, i.kind)
        assertFalse(i.hint.contains("已放开"))
    }

    @Test fun manifestDisallowsCleartext() {
        val f = listOf("app/src/main/AndroidManifest.xml", "src/main/AndroidManifest.xml").map { java.io.File(it) }.first { it.exists() }
        val text = f.readText()
        assertFalse(text.contains("usesCleartextTraffic=\"true\""))
        assertFalse(text.contains("cleartextTrafficPermitted=\"true\""))
        assertTrue(text.contains("usesCleartextTraffic=\"false\""))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class HttpsOnlyViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun savedHttpSettingsFallBackToDemoWithClearErrorInsteadOfConnecting() = runTest(dispatcher) {
        val vm = MainViewModel()
        vm.configure(true, "http://203.0.113.10:8000", "k", "EURUSD")
        assertTrue(vm.message.value!!.contains("必须使用 HTTPS 地址"))
        advanceTimeBy(2500)
        assertNull("running on demo data, not on a failing live api", vm.connIssue.value)
        vm.viewModelScope.cancel()
    }

    @Test fun demoModeStillWorksWithoutServer() = runTest(dispatcher) {
        val vm = MainViewModel()
        vm.configure(false, "", "", "EURUSD")           // 演示模式：不需要任何地址
        advanceTimeBy(2500)
        assertNull(vm.message.value); assertNull(vm.connIssue.value)
        assertTrue(DemoTradingApi(autoTick = false).account().balance > 0)
        vm.viewModelScope.cancel()
    }
}
