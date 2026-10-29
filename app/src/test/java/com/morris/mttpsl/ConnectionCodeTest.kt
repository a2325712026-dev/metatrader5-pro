package com.morris.mttpsl

import com.morris.mttpsl.api.*
import org.junit.Assert.*
import org.junit.Test
import java.security.SecureRandom
import java.util.Base64

/** 连接码解析 / 生成、API Key 生成（纯函数） */
class ConnectionCodeTest {
    private val url = "https://203.0.113.10:8000"          // 文档专用地址，不是真实服务器
    private val key = "k3y_Example-Only-Not-Real_0123456789abcdefghij"
    private val fp = (0 until 32).joinToString(":") { "%02X".format(it * 7 % 256) }
    private fun ok(r: ConnectionCodeResult) = (r as? ConnectionCodeResult.Ok ?: fail("expected Ok but was $r") as Nothing).info
    private fun err(r: ConnectionCodeResult) = (r as? ConnectionCodeResult.Error ?: fail("expected Error but was $r") as Nothing).message
    private fun rawCode(json: String) = CONNECTION_CODE_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())

    @Test fun roundTrip() {
        val code = encodeConnectionCode(url, key, fp)
        assertTrue(code.startsWith("MT5PRO1:")); assertFalse(code.contains("=")); assertFalse(code.contains("+")); assertFalse(code.contains("/"))
        assertEquals(ConnectionInfo(url, key, fp), ok(parseConnectionCode(code)))
    }

    @Test fun parserIsTolerantOfPasteArtifacts() {
        val code = encodeConnectionCode(url, key, fp)
        val want = ConnectionInfo(url, key, fp)
        assertEquals(want, ok(parseConnectionCode("  \n$code\n  ")))
        assertEquals(want, ok(parseConnectionCode(code.chunked(40).joinToString("\n"))))          // 自动折行
        assertEquals(want, ok(parseConnectionCode(code.replace("MT5PRO1:", "mt5pro1：")))) // 小写 + 全角冒号
        assertEquals(want, ok(parseConnectionCode("$code=")))                                    // 多余填充
        val std = Base64.getEncoder().encodeToString("""{"u":"$url","k":"$key","f":"$fp"}""".toByteArray())
        assertEquals(want, ok(parseConnectionCode("MT5PRO1:$std")))                              // 标准 base64（带填充）
    }

    @Test fun fingerprintIsNormalizedAndOptional() {
        val lower = fp.lowercase().replace(":", "")
        assertEquals(fp, ok(parseConnectionCode(rawCode("""{"u":"$url","k":"$key","f":"$lower"}"""))).fingerprint)
        assertEquals("", ok(parseConnectionCode(rawCode("""{"u":"$url","k":"$key","f":""}"""))).fingerprint)
        assertEquals("", ok(parseConnectionCode(rawCode("""{"u":"$url","k":"$key"}"""))).fingerprint)
    }

    @Test fun addressWithoutSchemeBecomesHttpsAndSlashesTrimmed() {
        assertEquals("https://203.0.113.10:8000", ok(parseConnectionCode(rawCode("""{"u":"203.0.113.10:8000/","k":"$key","f":""}"""))).serverUrl)
    }

    @Test fun clearChineseErrorsForBadCodes() {
        assertTrue(err(parseConnectionCode("")).contains("空"))
        assertTrue(err(parseConnectionCode("   \n ")).contains("空"))
        assertTrue(err(parseConnectionCode("hello world")).contains("MT5PRO1:"))
        assertTrue(err(parseConnectionCode("https://203.0.113.10:8000")).contains("不是连接码"))
        assertTrue(err(parseConnectionCode("MT5PRO1:")).contains("不完整"))
        assertTrue(err(parseConnectionCode("MT5PRO1:A")).contains("损坏")) // 1 个字符的 base64 无效
        assertTrue(err(parseConnectionCode(rawCode("not json"))).contains("无法识别"))
        assertTrue(err(parseConnectionCode(rawCode("[1,2]"))).contains("无法识别"))
        val full = encodeConnectionCode(url, key, fp)
        assertTrue("truncated code", err(parseConnectionCode(full.dropLast(20))).let { it.contains("损坏") || it.contains("无法识别") })
        assertTrue(err(parseConnectionCode(rawCode("""{"k":"$key","f":""}"""))).contains("没有服务器地址"))
        assertTrue(err(parseConnectionCode(rawCode("""{"u":"$url","k":"","f":""}"""))).contains("没有 API Key"))
        assertTrue(err(parseConnectionCode(rawCode("""{"u":"$url","k":"   ","f":""}"""))).contains("没有 API Key"))
        assertTrue(err(parseConnectionCode(rawCode("""{"u":"$url","k":"$key","f":"12:34"}"""))).contains("指纹"))
        assertTrue(err(parseConnectionCode(rawCode("""{"u":"https://[bad","k":"$key","f":""}"""))).contains("格式不对"))
        assertTrue(listOf("", "x", "MT5PRO1:", "MT5PRO1:!!!").all { err(parseConnectionCode(it)).any { c -> c.code > 0x4e00 } })
    }

    @Test fun httpAddressInCodeIsRejectedWithHttpsMessage() {
        for (u in listOf("http://203.0.113.10:8000", "ws://203.0.113.10:8000", "HTTP://a.b")) {
            val m = err(parseConnectionCode(rawCode("""{"u":"$u","k":"$key","f":"$fp"}""")))
            assertTrue(u + " -> " + m, m.contains("必须使用 HTTPS 地址"))
        }
    }

    @Test fun parserNeverThrowsOnGarbage() {
        val rnd = java.util.Random(42)
        repeat(300) {
            val s = String(CharArray(rnd.nextInt(80)) { (rnd.nextInt(0x4fff) + 1).toChar() })
            parseConnectionCode(s); parseConnectionCode("MT5PRO1:$s")
        }
    }

    @Test fun apiKeyGeneratorIsStrongAndUrlSafe() {
        val keys = (1..200).map { generateApiKey() }
        assertEquals(200, keys.toSet().size)
        keys.forEach { k ->
            assertEquals(43, k.length)                                   // 32 字节 → 43 个 base64url 字符
            assertTrue(k, Regex("[A-Za-z0-9_-]{43}").matches(k))
            assertEquals(32, Base64.getUrlDecoder().decode(k).size)
        }
        assertEquals(generateApiKey(SecureRandom.getInstance("SHA1PRNG").apply { setSeed(1) }), generateApiKey(SecureRandom.getInstance("SHA1PRNG").apply { setSeed(1) })) // 注入随机源
    }

    @Test fun importedValuesAreUsableForLiveConfig() {
        val info = ok(parseConnectionCode(encodeConnectionCode(url, key, fp)))
        val c = AppConfig(demoMode = false, serverUrl = info.serverUrl, apiKey = info.apiKey, certFingerprint = info.fingerprint)
        assertTrue(c.canUseLive)
        assertNull(httpsViolation(normalizeServerUrl(c.serverUrl)))
        assertNotNull(normalizeFingerprint(c.certFingerprint))
        bridgeHttpClient(c.serverUrl, c.certFingerprint) // 不抛异常：地址 https、指纹有效
    }

    /** 与服务器端（certgen.py）格式的固定样例：保证两端编码一致 */
    @Test fun parsesCodeProducedByTheServerSideGenerator() {
        val json = """{"u":"https://203.0.113.10:8000","k":"AbC-_123","f":"${fp}"}"""
        val serverStyle = "MT5PRO1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray(Charsets.UTF_8))
        val i = ok(parseConnectionCode(serverStyle))
        assertEquals("https://203.0.113.10:8000", i.serverUrl); assertEquals("AbC-_123", i.apiKey); assertEquals(fp, i.fingerprint)
    }
}
