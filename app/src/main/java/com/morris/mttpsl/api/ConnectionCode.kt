package com.morris.mttpsl.api

import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64

/*
 * 连接码：服务器 `mt5-bridge.exe --setup <IP>` 打印的一串文本，手机 App“导入连接码”后一次填好 地址 + API Key + 证书指纹。
 * 格式：  MT5PRO1:<base64url(JSON {"u": "https://IP:PORT", "k": "<api key>", "f": "<sha256 指纹，冒号十六进制>"})>
 * 全部是纯函数，便于单元测试。连接码里含 API Key，不要写日志、不要显示在界面上。
 */

const val CONNECTION_CODE_PREFIX = "MT5PRO1:"

/** 解析出的连接信息 */
data class ConnectionInfo(val serverUrl: String, val apiKey: String, val fingerprint: String)

sealed class ConnectionCodeResult {
    data class Ok(val info: ConnectionInfo) : ConnectionCodeResult()
    /** [message] 是可直接显示给用户的中文原因 */
    data class Error(val message: String) : ConnectionCodeResult()
}

private val CODE_REGEX = Regex("MT5PRO1[:：]\\s*([A-Za-z0-9_\\-+/=\\s]*)", RegexOption.IGNORE_CASE)

/** 编码（测试用 / 将来可从 App 生成）。fingerprint 可为空。 */
fun encodeConnectionCode(serverUrl: String, apiKey: String, fingerprint: String): String {
    val json = JSONObject().put("u", serverUrl).put("k", apiKey).put("f", fingerprint).toString()
    return CONNECTION_CODE_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray(Charsets.UTF_8))
}

/**
 * 解析连接码。容忍：前后空白 / 换行（剪贴板自动折行）、全角冒号、前缀大小写、标准 base64（+ /）、缺少或多余的 = 填充。
 * 任何问题都返回带中文说明的 [ConnectionCodeResult.Error]，绝不抛异常。
 */
fun parseConnectionCode(raw: String): ConnectionCodeResult {
    val text = raw.trim()
    if (text.isEmpty()) return ConnectionCodeResult.Error("连接码是空的：请先复制服务器上显示的连接码，再粘贴")
    val m = CODE_REGEX.find(text)
        ?: return ConnectionCodeResult.Error("这不是连接码：应以 MT5PRO1: 开头。请复制服务器上 --setup 打印的整段连接码")
    val b64 = m.groupValues[1].filter { !it.isWhitespace() }.replace('+', '-').replace('/', '_').trimEnd('=')
    if (b64.isEmpty()) return ConnectionCodeResult.Error("连接码不完整：MT5PRO1: 后面没有内容，请重新复制整段连接码")
    val bytes = try { Base64.getUrlDecoder().decode(b64) } catch (_: IllegalArgumentException) {
        return ConnectionCodeResult.Error("连接码不完整或已损坏（复制时可能少了一部分），请重新复制整段连接码")
    }
    val o = try { JSONObject(String(bytes, Charsets.UTF_8)) } catch (_: Exception) {
        return ConnectionCodeResult.Error("连接码内容无法识别（可能被截断或不是本服务器生成的），请重新复制整段连接码")
    }
    val url = normalizeServerUrl(o.optString("u"))
    val key = o.optString("k").trim()
    val fpRaw = o.optString("f").trim()
    if (url.isEmpty()) return ConnectionCodeResult.Error("连接码里没有服务器地址，请在服务器上重新运行 --setup")
    httpsViolation(url)?.let { return ConnectionCodeResult.Error("连接码里的地址不是 HTTPS：$it") }
    if (!isValidServerUrl(url)) return ConnectionCodeResult.Error("连接码里的服务器地址格式不对，请在服务器上重新运行 --setup")
    if (key.isEmpty()) return ConnectionCodeResult.Error("连接码里没有 API Key，请在服务器上重新运行 --setup")
    if (fpRaw.isNotEmpty() && normalizeFingerprint(fpRaw) == null)
        return ConnectionCodeResult.Error("连接码里的证书指纹格式不对（应为 64 位十六进制），请在服务器上重新运行 --setup")
    return ConnectionCodeResult.Ok(ConnectionInfo(url, key, if (fpRaw.isEmpty()) "" else formatFingerprint(fpRaw)))
}

/** 生成强随机 API Key：32 字节（256 位）SecureRandom → base64url，43 个字符，只含 A-Z a-z 0-9 - _ */
fun generateApiKey(random: SecureRandom = SecureRandom()): String {
    val b = ByteArray(32).also { random.nextBytes(it) }
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
}
