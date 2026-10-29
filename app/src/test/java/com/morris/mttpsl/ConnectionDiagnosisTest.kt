package com.morris.mttpsl

import com.morris.mttpsl.api.*
import com.morris.mttpsl.ui.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import okhttp3.Request
import okhttp3.mockwebserver.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.*
import javax.net.ssl.SSLHandshakeException

class ConnectionDiagnosisTest {
    // ---------- URL 规范化 ----------
    @Test fun normalizeAddsHttpsWhenNoScheme() {
        assertEquals("https://203.0.113.10:8000", normalizeServerUrl("203.0.113.10:8000"))
    }
    @Test fun normalizeTrimsSpacesAndTrailingSlashes() {
        assertEquals("http://203.0.113.10:8000", normalizeServerUrl("  http://203.0.113.10:8000//  "))
        assertEquals("https://example.com", normalizeServerUrl("https://example.com/"))
    }
    @Test fun normalizeKeepsHttpsAndLowercasesScheme() {
        assertEquals("https://a.b:8443", normalizeServerUrl("HTTPS://a.b:8443"))
    }
    @Test fun normalizeHandlesFullWidthColonAndInnerSpaces() {
        assertEquals("https://1.2.3.4:8000", normalizeServerUrl("1.2.3.4：8000 "))
        assertEquals("http://1.2.3.4:8000", normalizeServerUrl("http://1.2.3.4: 8000"))
    }
    @Test fun normalizeEmptyStaysEmpty() {
        assertEquals("", normalizeServerUrl("   ")); assertEquals("", normalizeServerUrl("/"))
    }
    @Test fun validUrlCheck() {
        assertTrue(isValidServerUrl(normalizeServerUrl("203.0.113.10:8000")))
        assertFalse(isValidServerUrl("ftp://x")); assertFalse(isValidServerUrl("http://"))
        assertFalse(isValidServerUrl(normalizeServerUrl("http://[bad")))
    }

    // ---------- 错误分类 ----------
    @Test fun cleartextBlocked() {
        val e = UnknownServiceException("CLEARTEXT communication to 203.0.113.10 not permitted by network security policy")
        assertEquals(ConnKind.CLEARTEXT_BLOCKED, classifyError(e).kind)
        assertTrue(classifyError(e).raw.contains("UnknownServiceException"))
    }
    @Test fun cleartextBlockedWrappedInCause() {
        val e = java.io.IOException("x", UnknownServiceException("CLEARTEXT communication to 1.2.3.4 not permitted"))
        assertEquals(ConnKind.CLEARTEXT_BLOCKED, classifyError(e).kind)
    }
    @Test fun timeout() {
        assertEquals(ConnKind.TIMEOUT, classifyError(SocketTimeoutException("failed to connect to /1.2.3.4 (port 8000) after 10000ms")).kind)
        assertEquals(ConnKind.TIMEOUT, classifyError(java.io.InterruptedIOException("timeout")).kind)
    }
    @Test fun refused() {
        assertEquals(ConnKind.REFUSED, classifyError(ConnectException("Failed to connect to /1.2.3.4:8000").apply { initCause(java.io.IOException("ECONNREFUSED (Connection refused)")) }).kind)
        assertEquals(ConnKind.REFUSED, classifyError(ConnectException("Connection refused")).kind)
    }
    @Test fun unreachable() {
        assertEquals(ConnKind.UNREACHABLE, classifyError(ConnectException("Network is unreachable")).kind)
        assertEquals(ConnKind.UNREACHABLE, classifyError(NoRouteToHostException("No route to host")).kind)
        assertEquals(ConnKind.UNREACHABLE, classifyError(ConnectException("something odd")).kind)
    }
    @Test fun dns() {
        assertEquals(ConnKind.DNS, classifyError(UnknownHostException("Unable to resolve host \"foo\": No address associated with hostname")).kind)
    }
    @Test fun urlFormat() {
        assertEquals(ConnKind.URL_FORMAT, classifyError(IllegalArgumentException("Expected URL scheme 'http' or 'https' but no scheme was found for abc")).kind)
        val real = try { Request.Builder().url("not a url"); null } catch (e: IllegalArgumentException) { e }
        assertEquals(ConnKind.URL_FORMAT, classifyError(real!!).kind)
    }
    @Test fun tls() {
        assertEquals(ConnKind.TLS, classifyError(SSLHandshakeException("Unable to parse TLS packet header")).kind)
    }
    @Test fun httpStatuses() {
        assertEquals(ConnKind.AUTH, classifyError(HttpStatusException(401, "{\"detail\":\"bad key\"}")).kind)
        assertEquals(ConnKind.AUTH, classifyHttp(403, "").kind)
        assertEquals(ConnKind.NOT_FOUND, classifyHttp(404, "").kind)
        val s = classifyHttp(502, "mt5 not initialized")
        assertEquals(ConnKind.SERVER_ERROR, s.kind); assertTrue(s.title.contains("502"))
        assertEquals(ConnKind.HTTP_OTHER, classifyHttp(418, "").kind)
    }
    @Test fun unknownFallback() {
        assertEquals(ConnKind.UNKNOWN, classifyError(RuntimeException("boom")).kind)
    }
    @Test fun rawNeverContainsApiKey() {
        val key = "SUPER-SECRET-KEY-123"
        val i = classifyError(java.io.IOException("failed with header X-API-Key: $key"), key)
        assertFalse(i.raw.contains(key)); assertFalse(i.hint.contains(key)); assertFalse(i.title.contains(key))
        assertFalse(classifyHttp(500, "echo $key", key).raw.contains(key))
    }
    @Test fun titlesAreChinese() {
        ConnKind.entries.forEach { k ->
            val i = when (k) {
                ConnKind.HTTPS_REQUIRED -> classifyError(HttpsRequiredException("http://x"))
                ConnKind.CLEARTEXT_BLOCKED -> classifyError(UnknownServiceException("CLEARTEXT x"))
                ConnKind.TIMEOUT -> classifyError(SocketTimeoutException("t"))
                ConnKind.REFUSED -> classifyError(ConnectException("Connection refused"))
                ConnKind.UNREACHABLE -> classifyError(ConnectException("Network is unreachable"))
                ConnKind.DNS -> classifyError(UnknownHostException("h"))
                ConnKind.URL_FORMAT -> classifyError(IllegalArgumentException("Invalid URL"))
                ConnKind.TLS -> classifyError(SSLHandshakeException("h"))
                ConnKind.TLS_PIN_MISMATCH -> classifyError(javax.net.ssl.SSLHandshakeException("x").apply { initCause(FingerprintMismatchException("AA", "BB")) })
                ConnKind.TLS_UNTRUSTED -> classifyError(SSLHandshakeException("Trust anchor for certification path not found."))
                ConnKind.TLS_HOSTNAME -> classifyError(javax.net.ssl.SSLPeerUnverifiedException("Hostname 1.2.3.4 not verified"))
                ConnKind.TLS_EXPIRED -> classifyError(SSLHandshakeException("x").apply { initCause(java.security.cert.CertificateExpiredException("NotAfter: x")) })
                ConnKind.TLS_BAD_FINGERPRINT -> classifyError(InvalidFingerprintException("zz"))
                ConnKind.AUTH -> classifyHttp(401, "")
                ConnKind.NOT_FOUND -> classifyHttp(404, "")
                ConnKind.SERVER_ERROR -> classifyHttp(500, "")
                ConnKind.HTTP_OTHER -> classifyHttp(400, "")
                ConnKind.UNKNOWN -> classifyError(RuntimeException())
            }
            assertEquals(k, i.kind)
            assertTrue("title for $k", i.title.any { it.code > 0x4e00 })
        }
    }

    // ---------- ConnectionTester（真实 OkHttp + 本地 MockWebServer）----------
    private var fp = "" // 当前测试 HTTPS 服务器的证书指纹（自签名 → 必须固定）
    private fun withServer(block: (MockWebServer) -> Unit) {
        val (s, f) = TestHttps.start(); fp = f; try { block(s) } finally { s.shutdown() }
    }
    private fun url(s: MockWebServer) = "${s.hostName}:${s.port}" // 故意不写协议：默认按 https:// 处理

    @Test fun testerSuccessShowsAccount() = withServer { s ->
        s.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = when (request.path) {
                "/health" -> MockResponse().setBody("{\"status\":\"ok\"}")
                "/account" -> if (request.getHeader("X-API-Key") == "k1")
                    MockResponse().setBody("{\"login\":\"123\",\"server\":\"Demo\",\"currency\":\"USD\",\"balance\":1000.5,\"equity\":1001.0,\"margin\":0,\"margin_free\":1000.5,\"leverage\":100}")
                else MockResponse().setResponseCode(401).setBody("bad key")
                else -> MockResponse().setResponseCode(404)
            }
        }
        val r = kotlinx.coroutines.runBlocking { ConnectionTester().test(url(s), " k1 ", fp) }
        assertTrue(r is ConnTestResult.Success)
        val sum = (r as ConnTestResult.Success).summary()
        assertTrue(sum.contains("123") && sum.contains("1000.50") && sum.contains("USD")); assertFalse(sum.contains("k1"))
    }
    @Test fun testerWrongKeyReports401() = withServer { s ->
        s.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                if (request.path == "/health") MockResponse().setBody("ok") else MockResponse().setResponseCode(401).setBody("nope")
        }
        val r = kotlinx.coroutines.runBlocking { ConnectionTester().test(url(s), "wrong", fp) } as ConnTestResult.Failure
        assertEquals(ConnKind.AUTH, r.issue.kind); assertTrue(r.step.contains("/account"))
    }
    @Test fun testerServerErrorKeepsCode() = withServer { s ->
        s.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(503).setBody("down")
        }
        val r = kotlinx.coroutines.runBlocking { ConnectionTester().test(url(s), "k", fp) } as ConnTestResult.Failure
        assertEquals(ConnKind.SERVER_ERROR, r.issue.kind); assertTrue(r.issue.title.contains("503")); assertTrue(r.step.contains("/health"))
    }
    @Test fun testerRefused() {
        val s = MockWebServer(); s.start(); val u = url(s); s.shutdown() // 端口已关闭
        val r = kotlinx.coroutines.runBlocking { ConnectionTester().test(u, "k") } as ConnTestResult.Failure
        assertEquals(ConnKind.REFUSED, r.issue.kind)
    }
    @Test fun testerBadUrlAndEmptyKey() {
        val r = kotlinx.coroutines.runBlocking { ConnectionTester().test("http://", "k") } as ConnTestResult.Failure
        assertEquals(ConnKind.URL_FORMAT, r.issue.kind)
        val r2 = kotlinx.coroutines.runBlocking { ConnectionTester().test("1.2.3.4:8000", "  ") } as ConnTestResult.Failure
        assertEquals(ConnKind.AUTH, r2.issue.kind)
    }
    @Test fun bridgeApiNormalisesUrlAndThrowsHttpStatus() = withServer { s ->
        s.enqueue(MockResponse().setResponseCode(401).setBody("bad"))
        val api = Mt5BridgeTradingApi("  ${url(s)}/ ", "k", fp)
        val e = try { kotlinx.coroutines.runBlocking { api.account() }; null } catch (e: HttpStatusException) { e }
        assertEquals(401, e!!.code)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionBannerViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private class FailingApi(val err: () -> Exception) : TradingApi by DemoTradingApi(autoTick = false) {
        override suspend fun quoteList(symbols: List<String>): List<SymbolQuote> = throw err()
        override suspend fun account(): AccountInfo = throw err()
        override suspend fun positions(): List<Position> = throw err()
        override suspend fun orders(): List<PendingOrder> = throw err()
        override suspend fun symbolSpec(symbol: String): SymbolSpec = throw err()
    }

    @Test fun failureShowsBannerWithChineseReasonAndRawException() = runTest(dispatcher) {
        val vm = MainViewModel()
        vm.configure(false, "", "", "EURUSD", apiOverride = FailingApi { UnknownServiceException("CLEARTEXT communication to 1.2.3.4 not permitted by network security policy") })
        advanceTimeBy(2500)
        val i = vm.connIssue.value
        assertNotNull("banner issue present", i)
        assertEquals(ConnKind.CLEARTEXT_BLOCKED, i!!.kind)
        assertTrue(i.raw.contains("UnknownServiceException"))
        assertTrue(vm.message.value!!.contains("连接失败"))
        assertTrue("trade tab error is classified", vm.trade.value.error!!.contains("明文"))
        vm.viewModelScope.cancel()
    }

    @Test fun healthyApiHasNoBanner() = runTest(dispatcher) {
        val vm = MainViewModel()
        vm.configure(false, "", "", "EURUSD", apiOverride = DemoTradingApi(autoTick = false))
        advanceTimeBy(2500)
        assertNull(vm.connIssue.value)
        vm.viewModelScope.cancel()
    }
}
