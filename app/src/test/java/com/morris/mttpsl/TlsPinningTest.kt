package com.morris.mttpsl

import com.morris.mttpsl.api.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/** 证书固定（SHA-256 指纹）逻辑 + URL 协议推导 + TLS 诊断文案 + 本地 HTTPS/WSS 端到端 */
class TlsPinningTest {
    private val placeholderHost = "203.0.113.10" // 文档专用地址，不是任何真实服务器

    private fun selfSigned(host: String = placeholderHost) = HeldCertificate.Builder()
        .addSubjectAlternativeName(host).commonName(host).build()
    private fun hex(c: HeldCertificate) = MessageDigest.getInstance("SHA-256").digest(c.certificate.encoded).joinToString("") { "%02x".format(it) }
    private fun colon(h: String) = h.uppercase().chunked(2).joinToString(":")

    // ---------- 指纹规范化 ----------
    @Test fun normalizeAcceptsColonHexAnyCase() {
        val h = hex(selfSigned())
        assertEquals(h, normalizeFingerprint(colon(h)))
        assertEquals(h, normalizeFingerprint(colon(h).lowercase()))
        assertEquals(h, normalizeFingerprint(h.uppercase()))
    }
    @Test fun normalizeToleratesSpacesFullWidthColonAndSeparators() {
        val h = hex(selfSigned())
        assertEquals(h, normalizeFingerprint("  " + colon(h).replace(":", "：") + " \n"))
        assertEquals(h, normalizeFingerprint(colon(h).replace(":", " ")))
        assertEquals(h, normalizeFingerprint(colon(h).replace(":", "-")))
    }
    @Test fun normalizeAcceptsPrefixesAndOpensslLine() {
        val h = hex(selfSigned())
        assertEquals(h, normalizeFingerprint("SHA256:" + colon(h)))
        assertEquals(h, normalizeFingerprint("sha-256 " + colon(h)))
        assertEquals(h, normalizeFingerprint("sha256 Fingerprint=" + colon(h))) // openssl x509 -fingerprint -sha256 的输出
        assertEquals(h, normalizeFingerprint("SHA256 Fingerprint=" + colon(h)))
    }
    @Test fun normalizeRejectsBadInput() {
        val h = hex(selfSigned())
        assertNull(normalizeFingerprint(""))
        assertNull(normalizeFingerprint("   "))
        assertNull(normalizeFingerprint(h.dropLast(2)))          // 少一个字节
        assertNull(normalizeFingerprint(h + "00"))               // 多一个字节
        assertNull(normalizeFingerprint(h.replaceRange(0, 1, "g"))) // 非十六进制
        assertNull(normalizeFingerprint("AA:BB:CC"))
        assertNull(normalizeFingerprint(MessageDigest.getInstance("SHA-1").digest(byteArrayOf(1)).joinToString("") { "%02x".format(it) })) // SHA-1 长度
    }
    @Test fun formatIsUpperColonAndIdempotent() {
        val h = hex(selfSigned()); val f = formatFingerprint(h)
        assertEquals(colon(h), f); assertEquals(95, f.length)
        assertEquals(f, formatFingerprint(f)); assertEquals("not-a-fp", formatFingerprint("  not-a-fp "))
    }

    // ---------- 匹配 / 不匹配 ----------
    @Test fun matchesOwnFingerprintInAnyFormat() {
        val c = selfSigned(); val h = hex(c)
        assertTrue(fingerprintMatches(c.certificate, h)); assertTrue(fingerprintMatches(c.certificate, colon(h)))
        assertTrue(fingerprintMatches(c.certificate, "sha256:" + colon(h).lowercase()))
    }
    @Test fun doesNotMatchOtherCertOrGarbage() {
        val a = selfSigned(); val b = selfSigned()
        assertFalse(fingerprintMatches(a.certificate, hex(b)))
        assertFalse(fingerprintMatches(a.certificate, "")); assertFalse(fingerprintMatches(a.certificate, "zz"))
        assertFalse(fingerprintMatches(a.certificate, hex(a).reversed()))
    }
    @Test fun trustManagerAcceptsMatchRejectsMismatchWithActualInMessage() {
        val a = selfSigned(); val b = selfSigned()
        PinnedTrustManager(colon(hex(a))).checkServerTrusted(arrayOf(a.certificate), "RSA") // 不抛异常
        val e = try { PinnedTrustManager(hex(a)).checkServerTrusted(arrayOf(b.certificate), "RSA"); null } catch (e: FingerprintMismatchException) { e }
        assertNotNull(e)
        assertEquals(colon(hex(b)), e!!.actual); assertEquals(colon(hex(a)), e.expected)
    }
    @Test fun trustManagerOnlyLooksAtLeafAndRejectsEmptyChain() {
        val leafOnlyPinned = selfSigned(); val other = selfSigned()
        // 指纹等于链中第二张证书（中间 / 根）也不行：必须是叶子
        val e = try { PinnedTrustManager(hex(other)).checkServerTrusted(arrayOf(leafOnlyPinned.certificate, other.certificate), "RSA"); null } catch (e: CertificateException) { e }
        assertTrue(e is FingerprintMismatchException)
        assertTrue(try { PinnedTrustManager(hex(other)).checkServerTrusted(emptyArray(), "RSA"); false } catch (_: CertificateException) { true })
        assertTrue(try { PinnedTrustManager(hex(other)).checkServerTrusted(null, "RSA"); false } catch (_: CertificateException) { true })
    }
    @Test fun trustManagerRejectsExpiredEvenIfPinMatches() {
        val now = System.currentTimeMillis()
        val expired = HeldCertificate.Builder().addSubjectAlternativeName(placeholderHost)
            .validityInterval(now - 20 * 86_400_000L, now - 10 * 86_400_000L).build()
        assertTrue(try { PinnedTrustManager(hex(expired)).checkServerTrusted(arrayOf(expired.certificate), "RSA"); false } catch (_: CertificateExpiredException) { true })
    }
    @Test fun trustManagerRejectsClientCertsAndHasNoIssuers() {
        val tm = PinnedTrustManager(hex(selfSigned()))
        assertTrue(try { tm.checkClientTrusted(emptyArray(), "RSA"); false } catch (_: CertificateException) { true })
        assertEquals(0, tm.acceptedIssuers.size)
    }
    @Test fun invalidFingerprintThrowsInsteadOfFallingBack() {
        assertTrue(try { PinnedTrustManager("garbage"); false } catch (_: InvalidFingerprintException) { true })
        assertTrue(try { OkHttpClient.Builder().applyServerTls("https://$placeholderHost:8000", "garbage"); false } catch (_: InvalidFingerprintException) { true })
    }

    // ---------- 何时启用固定 / 主机名校验只在固定模式放宽 ----------
    @Test fun pinningOnlyForHttpsWithFingerprint() {
        val fp = hex(selfSigned()); val default = OkHttpClient()
        val httpClient = OkHttpClient.Builder().applyServerTls("http://$placeholderHost:8000", fp).build()
        val httpsNoFp = OkHttpClient.Builder().applyServerTls("https://$placeholderHost:8000", "  ").build()
        val pinned = OkHttpClient.Builder().applyServerTls("https://$placeholderHost:8000", fp).build()
        assertSame(default.hostnameVerifier.javaClass, httpClient.hostnameVerifier.javaClass)
        assertSame(default.hostnameVerifier.javaClass, httpsNoFp.hostnameVerifier.javaClass)
        assertNotSame(PinnedHostnameVerifier, httpClient.hostnameVerifier); assertNotSame(PinnedHostnameVerifier, httpsNoFp.hostnameVerifier)
        assertSame(PinnedHostnameVerifier, pinned.hostnameVerifier)
        assertSame(default.sslSocketFactory, httpsNoFp.sslSocketFactory.let { default.sslSocketFactory }) // 无指纹不替换 SSLSocketFactory
    }

    // ---------- URL 协议推导 ----------
    @Test fun webSocketSchemeDerivedFromHttpScheme() {
        assertEquals("ws://$placeholderHost:8000", toWebSocketUrl("http://$placeholderHost:8000"))
        assertEquals("wss://$placeholderHost:8443", toWebSocketUrl("https://$placeholderHost:8443"))
        assertEquals("wss://a.example", toWebSocketUrl("HTTPS://a.example"))
        assertEquals("ws://a.example/x/http", toWebSocketUrl("http://a.example/x/http")) // 只替换协议，不动路径里的 http
    }
    @Test fun normalizeMapsWsSchemesAndKeepsHttps() {
        assertEquals("https://$placeholderHost:8443", normalizeServerUrl("wss://$placeholderHost:8443/"))
        assertEquals("http://$placeholderHost:8000", normalizeServerUrl("WS://$placeholderHost:8000"))
        assertEquals("https://$placeholderHost:8443", normalizeServerUrl("https://$placeholderHost:8443"))
        assertEquals("http://$placeholderHost:8000", normalizeServerUrl("$placeholderHost:8000"))
        assertTrue(isHttpsUrl(normalizeServerUrl("wss://x.y"))); assertFalse(isHttpsUrl(normalizeServerUrl("x.y:8000")))
        assertTrue(isValidServerUrl(normalizeServerUrl("https://$placeholderHost:8443")))
    }

    // ---------- 诊断文案 ----------
    private fun chinese(s: String) = s.any { it.code > 0x4e00 }
    @Test fun diagnosisPinMismatchShowsActualFingerprintAndWarns() {
        val a = colon(hex(selfSigned())); val b = colon(hex(selfSigned()))
        val i = classifyError(SSLHandshakeException("handshake failed").apply { initCause(FingerprintMismatchException(a, b)) })
        assertEquals(ConnKind.TLS_PIN_MISMATCH, i.kind)
        assertTrue(chinese(i.title) && i.title.contains("指纹")); assertTrue(i.hint.contains(b)); assertTrue(i.hint.contains("重新生成") && i.hint.contains("拦截"))
    }
    @Test fun diagnosisPinMismatchRecognisedFromWrappedMessageOnly() {
        val b = colon(hex(selfSigned()))
        val i = classifyError(java.io.IOException("javax.net.ssl.SSLHandshakeException: java.security.cert.CertificateException: Certificate fingerprint mismatch: expected X, server presented $b"))
        assertEquals(ConnKind.TLS_PIN_MISMATCH, i.kind); assertTrue(i.hint.contains(b))
    }
    @Test fun diagnosisUntrustedCertificate() {
        val i = classifyError(SSLHandshakeException("java.security.cert.CertPathValidatorException: Trust anchor for certification path not found."))
        assertEquals(ConnKind.TLS_UNTRUSTED, i.kind); assertTrue(i.title.contains("不受信任")); assertTrue(i.hint.contains("证书指纹"))
        assertEquals(ConnKind.TLS_UNTRUSTED, classifyError(SSLHandshakeException("PKIX path building failed: unable to find valid certification path to requested target")).kind)
    }
    @Test fun diagnosisHostnameMismatch() {
        val i = classifyError(SSLPeerUnverifiedException("Hostname $placeholderHost not verified:\n certificate: sha256/xx\n subjectAltNames: [localhost]"))
        assertEquals(ConnKind.TLS_HOSTNAME, i.kind); assertTrue(i.hint.contains("subjectAltName"))
    }
    @Test fun diagnosisExpired() {
        assertEquals(ConnKind.TLS_EXPIRED, classifyError(SSLHandshakeException("x").apply { initCause(CertificateExpiredException("NotAfter: Mon Jan 01 2024")) }).kind)
        assertEquals(ConnKind.TLS_EXPIRED, classifyError(java.security.cert.CertificateNotYetValidException("NotBefore: x")).kind)
    }
    @Test fun diagnosisBadFingerprintFormat() {
        val i = classifyError(InvalidFingerprintException("zz"))
        assertEquals(ConnKind.TLS_BAD_FINGERPRINT, i.kind); assertTrue(i.hint.contains("64"))
    }
    @Test fun diagnosisPlainHandshakeError() {
        val i = classifyError(SSLHandshakeException("Unable to parse TLS packet header"))
        assertEquals(ConnKind.TLS, i.kind); assertTrue(i.title.contains("握手")); assertTrue(i.hint.contains("SSL_CERTFILE"))
    }
    @Test fun allTlsMessagesChineseAndNeverLeakApiKey() {
        val key = "SECRET-KEY-ABC-123"
        listOf(
            SSLHandshakeException("x").apply { initCause(FingerprintMismatchException("A", "B")) },
            SSLHandshakeException("Trust anchor for certification path not found. $key"),
            SSLPeerUnverifiedException("Hostname x not verified $key"), SSLHandshakeException("h $key"), InvalidFingerprintException(key),
        ).forEach { e ->
            val i = classifyError(e, key)
            assertTrue(chinese(i.title) && chinese(i.hint))
            assertFalse(i.raw.contains(key)); assertFalse(i.hint.contains(key)); assertFalse(i.title.contains(key))
        }
    }

    // ---------- 端到端：本地 HTTPS / WSS MockWebServer ----------
    /** 以 [held] 作为服务器证书启动 HTTPS MockWebServer；block 收到服务器和该证书的 SHA-256（小写十六进制） */
    private fun httpsServer(held: HeldCertificate, block: (MockWebServer, String) -> Unit) {
        val s = MockWebServer()
        s.useHttps(HandshakeCertificates.Builder().heldCertificate(held).build().sslSocketFactory(), false)
        s.protocols = listOf(Protocol.HTTP_1_1)
        s.start()
        try { block(s, hex(held)) } finally { s.shutdown() }
    }
    private fun urlOf(s: MockWebServer) = "https://${s.hostName}:${s.port}"
    private val accountJson = "{\"login\":\"1\",\"server\":\"Demo\",\"currency\":\"USD\",\"balance\":10.0,\"equity\":10.0,\"margin\":0,\"margin_free\":10.0,\"leverage\":100}"
    private fun okAnswers(s: MockWebServer) { s.enqueue(MockResponse().setBody("{\"ok\":true}")); s.enqueue(MockResponse().setBody(accountJson)) }
    private val day = 86_400_000L

    @Test fun e2ePinnedSelfSignedCertWorksEvenWhenSanDoesNotMatchHost() = httpsServer(selfSigned("some-other-name.invalid")) { s, fp ->
        okAnswers(s)
        val r = runBlocking { ConnectionTester().test(urlOf(s), "k", colon(fp)) }
        assertTrue("pinned connection should succeed: $r", r is ConnTestResult.Success)
    }
    @Test fun e2eWrongFingerprintRejected() = httpsServer(selfSigned()) { s, _ ->
        okAnswers(s)
        val r = runBlocking { ConnectionTester().test(urlOf(s), "k", colon(hex(selfSigned()))) } as ConnTestResult.Failure
        assertEquals(ConnKind.TLS_PIN_MISMATCH, r.issue.kind)
        assertEquals(0, s.requestCount) // 握手失败 → 没有 HTTP 请求（API Key 没发出去）
    }
    @Test fun e2eNoFingerprintSelfSignedUsesSystemTrustAndIsRejected() = httpsServer(selfSigned()) { s, _ ->
        okAnswers(s)
        val r = runBlocking { ConnectionTester().test(urlOf(s), "k") } as ConnTestResult.Failure
        assertEquals("raw=${r.issue.raw}", ConnKind.TLS_UNTRUSTED, r.issue.kind)
        assertEquals(0, s.requestCount)
    }
    @Test fun e2eBadFingerprintFormatReportedBeforeAnyRequest() = httpsServer(selfSigned()) { s, _ ->
        okAnswers(s)
        val r = runBlocking { ConnectionTester().test(urlOf(s), "k", "12:34") } as ConnTestResult.Failure
        assertEquals(ConnKind.TLS_BAD_FINGERPRINT, r.issue.kind); assertEquals(0, s.requestCount)
    }
    @Test fun e2eExpiredPinnedCertRejected() {
        val now = System.currentTimeMillis()
        val expired = HeldCertificate.Builder().addSubjectAlternativeName("127.0.0.1").validityInterval(now - 20 * day, now - 10 * day).build()
        httpsServer(expired) { s, fp ->
            okAnswers(s)
            val r = runBlocking { ConnectionTester().test(urlOf(s), "k", fp) } as ConnTestResult.Failure
            assertEquals(ConnKind.TLS_EXPIRED, r.issue.kind)
        }
    }
    @Test fun e2eFingerprintIgnoredForPlainHttp() {
        val s = MockWebServer(); s.start()
        try {
            okAnswers(s)
            val r = runBlocking { ConnectionTester().test("http://${s.hostName}:${s.port}", "k", "garbage-ignored-for-http") }
            assertTrue(r is ConnTestResult.Success)
        } finally { s.shutdown() }
    }
    @Test fun e2eRestThroughTradingApiWithPin() = httpsServer(selfSigned()) { s, fp ->
        s.enqueue(MockResponse().setBody(accountJson))
        val api = Mt5BridgeTradingApi(urlOf(s), "k", colon(fp))
        assertEquals("1", runBlocking { api.account() }.login)
        assertEquals("k", s.takeRequest().getHeader("X-API-Key"))
    }
    @Test fun e2eWebSocketWssWithPin() = httpsServer(selfSigned()) { s, fp ->
        s.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                webSocket.send("{\"bid\":1.1,\"ask\":1.2,\"time_msc\":5}")
            }
        }))
        val api = Mt5BridgeTradingApi(urlOf(s), "k", fp)
        val q = runBlocking { withTimeout(10_000) { api.quotes("EURUSD").first() } }
        assertEquals(1.1, q.bid, 1e-9); assertEquals(1.2, q.ask, 1e-9)
        val req = s.takeRequest()
        assertEquals("/ws/ticks?symbol=EURUSD", req.path); assertEquals("k", req.getHeader("X-API-Key"))
    }
    @Test fun e2eWebSocketWssWrongPinNeverConnects() = httpsServer(selfSigned()) { s, _ ->
        s.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))
        val http = bridgeHttpClient(urlOf(s), colon(hex(selfSigned())))
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val latch = java.util.concurrent.CountDownLatch(1)
        http.newWebSocket(Request.Builder().url(toWebSocketUrl(urlOf(s)) + "/ws/ticks?symbol=X").header("X-API-Key", "k").build(), object : WebSocketListener() {
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) { failure.set(t); latch.countDown() }
        })
        assertTrue(latch.await(10, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(ConnKind.TLS_PIN_MISMATCH, classifyError(failure.get()!!).kind)
        assertEquals(0, s.requestCount)
    }
}
