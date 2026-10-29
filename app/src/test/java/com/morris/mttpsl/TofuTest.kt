package com.morris.mttpsl

import com.morris.mttpsl.api.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.QueueDispatcher
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** v0.14：首次使用即信任（TOFU）证书记录——只在没有指纹时学习；已有指纹绝不放宽。 */
class TofuTest {
    private val accountJson = "{\"login\":\"1\",\"server\":\"Demo\",\"currency\":\"USD\",\"balance\":10.0,\"equity\":10.0,\"margin\":0,\"margin_free\":10.0,\"leverage\":100}"
    private val fpOfOther = (0 until 32).joinToString(":") { "%02X".format(it) }
    private fun url(s: MockWebServer) = "https://${s.hostName}:${s.port}"
    private fun serve(held: HeldCertificate? = null, block: (MockWebServer, String) -> Unit) {
        val (s, fp) = if (held == null) TestHttps.start() else TestHttps.start(held)
        try { block(s, fp) } finally { s.shutdown() }
    }
    private fun answers(s: MockWebServer) { s.enqueue(MockResponse().setBody("{\"ok\":true}")); s.enqueue(MockResponse().setBody(accountJson)) }
    private class CountingProbe(val result: () -> String) : CertProbe {
        var calls = 0
        override fun fetch(host: String, port: Int): String { calls++; return result() }
    }

    // ---------- 纯函数 ----------
    @Test fun learnOnlyWhenNoFingerprintAndCertUntrusted() {
        assertTrue(shouldLearnPin("", ConnKind.TLS_UNTRUSTED))
        assertTrue(shouldLearnPin("   ", ConnKind.TLS_UNTRUSTED))
        for (k in ConnKind.entries) if (k != ConnKind.TLS_UNTRUSTED) assertFalse("never learn on $k", shouldLearnPin("", k))
    }
    @Test fun neverLearnWhenAFingerprintIsAlreadySet() {
        for (k in ConnKind.entries) {
            assertFalse(shouldLearnPin(fpOfOther, k))
            assertFalse(shouldLearnPin("garbage", k))          // 格式错误的指纹也不能被悄悄替换
            assertFalse(shouldLearnPin("0".repeat(64), k))
        }
    }
    @Test fun persistGuard() {
        assertTrue(shouldPersistLearnedPin("https://1.2.3.4:8000", "https://1.2.3.4:8000", ""))
        assertTrue(shouldPersistLearnedPin("1.2.3.4:8000/", "https://1.2.3.4:8000", " "))          // 规范化后相同
        assertFalse("never overwrite a saved pin", shouldPersistLearnedPin("https://1.2.3.4:8000", "https://1.2.3.4:8000", fpOfOther))
        assertFalse("different server", shouldPersistLearnedPin("https://1.2.3.4:8000", "https://5.6.7.8:8000", ""))
        assertFalse("different port", shouldPersistLearnedPin("https://1.2.3.4:8000", "https://1.2.3.4:8001", ""))
        assertFalse("nothing saved yet", shouldPersistLearnedPin("", "https://1.2.3.4:8000", ""))
    }

    // ---------- 端到端（真实 OkHttp + 本地自签名 HTTPS） ----------
    @Test fun firstSuccessfulTestRecordsFingerprintAndSendsKeyOnlyAfterPinning() = serve { s, fp ->
        answers(s)
        val r = runBlocking { ConnectionTester().test(url(s), " the-key ") } as ConnTestResult.Success
        assertEquals(fp, r.learnedFingerprint)
        val health = s.takeRequest(); val account = s.takeRequest()
        assertEquals("/health", health.path); assertNull("no key on /health", health.getHeader("X-API-Key"))
        assertEquals("/account", account.path); assertEquals("the-key", account.getHeader("X-API-Key"))
        assertEquals("only the two pinned requests reached the server (probe sends no HTTP)", 2, s.requestCount)
    }

    @Test fun learnedFingerprintThenPinsSubsequentTestsAndConnections() = serve { s, fp ->
        answers(s)
        val learned = (runBlocking { ConnectionTester().test(url(s), "k") } as ConnTestResult.Success).learnedFingerprint!!
        answers(s)
        val again = runBlocking { ConnectionTester().test(url(s), "k", learned) } as ConnTestResult.Success
        assertNull("already pinned: nothing new to learn", again.learnedFingerprint)
        s.enqueue(MockResponse().setBody(accountJson))
        assertEquals("1", runBlocking { Mt5BridgeTradingApi(url(s), "k", learned).account() }.login)
        assertEquals(fp, learned)
    }

    @Test fun changedCertificateIsRefusedNotRelearned() {
        val probe = CountingProbe { fail("probe must not run when a fingerprint is set"); "" }
        val old = TestHttps.fingerprintOf(HeldCertificate.Builder().addSubjectAlternativeName("127.0.0.1").commonName("old").build())
        serve { s, newFp ->
            answers(s)
            val r = runBlocking { ConnectionTester(probe = probe).test(url(s), "k", old) } as ConnTestResult.Failure
            assertEquals(ConnKind.TLS_PIN_MISMATCH, r.issue.kind)
            assertTrue(r.issue.hint.contains("重置证书记录")); assertTrue(r.issue.hint.contains(newFp))
            assertEquals("no HTTP request, so no API key sent", 0, s.requestCount)
            assertEquals(0, probe.calls)
        }
    }

    @Test fun malformedFingerprintIsReportedAndNeverReplacedByLearning() = serve { s, _ ->
        val probe = CountingProbe { fail("probe must not run"); "" }
        answers(s)
        val r = runBlocking { ConnectionTester(probe = probe).test(url(s), "k", "12:34") } as ConnTestResult.Failure
        assertEquals(ConnKind.TLS_BAD_FINGERPRINT, r.issue.kind); assertEquals(0, probe.calls); assertEquals(0, s.requestCount)
    }

    @Test fun wrongApiKeyDoesNotRecordAnything() = serve { s, _ ->
        s.enqueue(MockResponse().setBody("{\"ok\":true}")); s.enqueue(MockResponse().setResponseCode(401).setBody("bad key"))
        val r = runBlocking { ConnectionTester().test(url(s), "wrong") }
        assertTrue(r is ConnTestResult.Failure); assertEquals(ConnKind.AUTH, (r as ConnTestResult.Failure).issue.kind)
    }

    @Test fun probeFailureKeepsTheOriginalUntrustedDiagnosis() = serve { s, _ ->
        answers(s)
        val r = runBlocking { ConnectionTester(probe = { _, _ -> throw IOException("probe boom") }).test(url(s), "k") } as ConnTestResult.Failure
        assertEquals(ConnKind.TLS_UNTRUSTED, r.issue.kind); assertEquals(0, s.requestCount)
    }

    @Test fun certificateSwappedBetweenProbeAndRequestIsRejected() = serve { s, _ ->
        // 模拟中间人：探测看到的指纹与随后真正握手的证书不一致 → 固定握手失败，API Key 没有发出去
        answers(s)
        val probe = CountingProbe { fpOfOther }
        val r = runBlocking { ConnectionTester(probe = probe).test(url(s), "k") } as ConnTestResult.Failure
        assertEquals(1, probe.calls)
        assertEquals(ConnKind.TLS_PIN_MISMATCH, r.issue.kind); assertEquals(0, s.requestCount)
    }

    @Test fun expiredSelfSignedCertIsNotRecorded() {
        val now = System.currentTimeMillis(); val day = 86_400_000L
        val expired = HeldCertificate.Builder().addSubjectAlternativeName("127.0.0.1").validityInterval(now - 20 * day, now - 10 * day).build()
        serve(expired) { s, _ ->
            answers(s)
            val r = runBlocking { ConnectionTester().test(url(s), "k") } as ConnTestResult.Failure
            assertEquals(ConnKind.TLS_EXPIRED, r.issue.kind); assertEquals(0, s.requestCount)
        }
    }

    @Test fun plainHttpStillRefusedBeforeAnyProbe() {
        val probe = CountingProbe { fail("no probe for http"); "" }
        val r = runBlocking { ConnectionTester(probe = probe).test("http://127.0.0.1:9", "k") } as ConnTestResult.Failure
        assertEquals(ConnKind.HTTPS_REQUIRED, r.issue.kind); assertEquals(0, probe.calls)
    }

    @Test fun socketProbeReadsTheLeafFingerprintWithoutSendingHttp() = serve { s, fp ->
        assertEquals(fp, SocketCertProbe.fetch(s.hostName, s.port))
        Thread.sleep(200)
        assertEquals("handshake only: the probe never sends an HTTP request", 0, s.requestCount)
    }

    @Test fun socketProbeFailsCleanlyWhenNothingListens() {
        val s = MockWebServer(); s.start(); val port = s.port; s.shutdown()
        assertTrue(try { SocketCertProbe.fetch("127.0.0.1", port); false } catch (_: IOException) { true })
    }
}
