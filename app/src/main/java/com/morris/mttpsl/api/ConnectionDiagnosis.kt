package com.morris.mttpsl.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.UnknownServiceException
import java.util.concurrent.TimeUnit

/** 服务端返回非 2xx 时抛出（消息格式与旧版一致："HTTP 401: body"） */
class HttpStatusException(val code: Int, val body: String) : RuntimeException("HTTP $code: $body")

enum class ConnKind {
    HTTPS_REQUIRED, CLEARTEXT_BLOCKED, TIMEOUT, REFUSED, UNREACHABLE, DNS, URL_FORMAT,
    TLS, TLS_PIN_MISMATCH, TLS_UNTRUSTED, TLS_HOSTNAME, TLS_EXPIRED, TLS_BAD_FINGERPRINT, AUTH, NOT_FOUND, SERVER_ERROR, HTTP_OTHER, UNKNOWN
}

/**
 * 一次连接失败的“人话”说明。
 * [raw] 是原始异常类名 + 消息（已去掉 API Key），用于小字显示 / 截图。
 */
data class ConnIssue(val kind: ConnKind, val title: String, val hint: String, val raw: String)

/** 只允许 HTTPS 的统一错误文案（设置页、测试连接、创建客户端都用它） */
const val HTTPS_REQUIRED_MESSAGE = "必须使用 HTTPS 地址"

/** 地址不是 https:// 时抛出：App 绝不以明文（http / ws）连接服务器。 */
class HttpsRequiredException(val url: String) : java.io.IOException(HTTPS_REQUIRED_MESSAGE)

/** 地址非空且不是 https:// → 返回错误文案，否则 null（空地址不在这里判断） */
fun httpsViolation(url: String): String? = if (url.isNotBlank() && !isHttpsUrl(url)) HTTPS_REQUIRED_MESSAGE else null

/** 地址不是 https:// 时抛 [HttpsRequiredException] */
fun requireHttps(url: String) { if (!isHttpsUrl(url)) throw HttpsRequiredException(url) }

/**
 * 规范化用户输入的服务器地址：去空格/全角冒号/末尾斜杠；没写协议则默认 https://；ws:// → http://，wss:// → https://。
 * 注意：这里【不会】把 http 升级成 https（那会悄悄改变用户的意图），而是保留 http:// 让后面的 [httpsViolation] / [requireHttps] 拒绝它。
 */
fun normalizeServerUrl(raw: String): String {
    val t = raw.filter { !it.isWhitespace() }.replace('：', ':').replace('／', '/')
    if (t.isEmpty()) return ""
    val withScheme = if (t.contains("://")) t else "https://$t"
    val i = withScheme.indexOf("://")
    val rest = withScheme.substring(i + 3).trimEnd('/')
    if (rest.isEmpty()) return ""
    val scheme = when (val sc = withScheme.substring(0, i).lowercase()) { "ws" -> "http"; "wss" -> "https"; else -> sc }
    return "$scheme://$rest"
}

/** 地址是否可被 OkHttp 解析（仅 http/https） */
fun isValidServerUrl(url: String): Boolean = url.toHttpUrlOrNull() != null

private fun redact(s: String, apiKey: String?): String {
    val k = apiKey?.trim().orEmpty()
    return if (k.length >= 4) s.replace(k, "***") else s
}

/** 异常 + 原因链的完整描述（类名: 消息），去掉 API Key，限长 */
fun describeException(e: Throwable, apiKey: String? = null): String {
    val parts = generateSequence(e) { it.cause?.takeIf { c -> c !== it } }.take(4)
        .map { t -> if (t.message.isNullOrEmpty()) t.javaClass.name else "${t.javaClass.name}: ${t.message}" }
    return redact(parts.joinToString("\n  ← "), apiKey).take(600)
}

fun classifyHttp(code: Int, body: String, apiKey: String? = null): ConnIssue {
    val raw = redact("HTTP $code: ${body.take(200)}", apiKey)
    return when {
        code == 401 || code == 403 -> ConnIssue(ConnKind.AUTH, "API Key 不对（HTTP $code）",
            "服务器收到了请求但拒绝了密钥。请到设置里重新粘贴服务器 .env 中的 API Key（注意不要多复制空格）。", raw)
        code == 404 -> ConnIssue(ConnKind.NOT_FOUND, "服务器地址不对（HTTP 404）",
            "连上了某个服务，但没有这个接口。请确认端口是 8000，且地址不要带多余的路径。", raw)
        code in 500..599 -> ConnIssue(ConnKind.SERVER_ERROR, "服务器内部错误（HTTP $code）",
            "网络是通的，但服务器处理出错（常见原因：服务器上的 MT5 终端没有登录或没打开）。请检查 Windows 服务器。", raw)
        else -> ConnIssue(ConnKind.HTTP_OTHER, "服务器返回异常（HTTP $code）", "网络是通的，但服务器返回了意外的状态码。", raw)
    }
}

/** 把任意异常翻译成中文原因 */
fun classifyError(e: Throwable, apiKey: String? = null): ConnIssue {
    if (e is HttpStatusException) return classifyHttp(e.code, e.body, apiKey)
    val chain = generateSequence(e) { it.cause?.takeIf { c -> c !== it } }.take(6).toList()
    val text = chain.joinToString(" | ") { "${it.javaClass.name} ${it.message ?: ""}" }.lowercase()
    val raw = describeException(e, apiKey)
    fun has(vararg k: String) = k.any { text.contains(it.lowercase()) }
    val rawText = chain.joinToString(" | ") { it.message ?: "" }
    return when {
        chain.any { it is HttpsRequiredException } ->
            ConnIssue(ConnKind.HTTPS_REQUIRED, HTTPS_REQUIRED_MESSAGE,
                "这个 App 只允许 https:// 连接，不会以明文连接服务器。请把地址改成 https://IP:端口；服务器端运行 mt5-bridge.exe --setup <服务器IP> 即可一键启用 HTTPS，并得到可直接导入的连接码。", raw)
        chain.any { it is InvalidFingerprintException } ->
            ConnIssue(ConnKind.TLS_BAD_FINGERPRINT, "HTTPS 证书指纹格式不对",
                "“HTTPS 证书指纹 (SHA-256)”应是 64 位十六进制（可带冒号，如 AA:BB:CC:…，共 32 组）。最省事的做法是用“导入连接码”自动填写；也可以复制服务器上 mt5-bridge.exe --setup 打印的指纹；使用正规证书（如 Let's Encrypt）时把这一栏清空。", raw)
        chain.any { it is FingerprintMismatchException } || has("fingerprint mismatch") -> {
            val actual = chain.firstNotNullOfOrNull { (it as? FingerprintMismatchException)?.actual }
                ?: Regex("presented ([0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){31})").find(rawText)?.groupValues?.get(1)
            ConnIssue(ConnKind.TLS_PIN_MISMATCH, "服务器证书与已记录的不一致（HTTPS 证书指纹不匹配）",
                "服务器出示的证书与已记录（固定）的证书不一致" + (actual?.let { "（服务器实际指纹：$it）" } ?: "") +
                    "。如果你确认服务器重新生成过证书（例如重新运行了 --setup --force），请点“重置证书记录”重新信任；如果没有改过证书，请不要继续使用这个连接——可能有人在中途拦截。已拒绝连接，没有发送 API Key。", raw)
        }
        chain.any { it is java.security.cert.CertificateExpiredException || it is java.security.cert.CertificateNotYetValidException } ||
            has("certificateexpired", "certificate expired", "not yet valid", "notafter", "notbefore") ->
            ConnIssue(ConnKind.TLS_EXPIRED, "HTTPS 证书已过期或尚未生效",
                "证书的有效期不包含当前时间。请先检查手机的日期和时间是否正确（开启自动时间）；若确实过期，请在服务器上用 --setup <服务器IP> --force 重新生成证书，并重新导入连接码。", raw)
        chain.any { it is javax.net.ssl.SSLPeerUnverifiedException } || has("not verified", "no subject alternative") ->
            ConnIssue(ConnKind.TLS_HOSTNAME, "证书与服务器地址不匹配",
                "证书里没有包含你填写的地址（用 IP 访问时，证书必须把这个 IP 写进 subjectAltName）。请在服务器上用 --setup <服务器IP> --force 重新生成并导入新的连接码；或在设置里填写“HTTPS 证书指纹”（固定指纹模式不比对主机名）。", raw)
        chain.any { it is java.security.cert.CertPathValidatorException || it is java.security.cert.CertificateException } ||
            has("trust anchor", "unable to find valid certification path", "certpathvalidator", "self signed", "self-signed", "chain validation failed") ->
            ConnIssue(ConnKind.TLS_UNTRUSTED, "服务器证书不受信任",
                "手机不信任这个 https 证书（自签名证书都会这样）。请在设置里“导入连接码”（服务器运行 mt5-bridge.exe --setup 时会打印），或在“高级”里填写 HTTPS 证书指纹；如果用的是正规证书（如 Let's Encrypt），请检查证书是否过期或证书链是否完整。", raw)
        chain.any { it is UnknownServiceException } && has("cleartext") || has("cleartext communication") ->
            ConnIssue(ConnKind.CLEARTEXT_BLOCKED, "系统禁止了 http 明文连接",
                "手机系统拦截了 http:// 明文连接。本 App 只允许 https://，请把服务器地址改成 https://IP:端口。", raw)
        chain.any { it is IllegalArgumentException } && has("expected url scheme", "unexpected url", "invalid url", "unexpected port", "host", "url") ->
            ConnIssue(ConnKind.URL_FORMAT, "服务器地址格式不对",
                "请填写形如 https://203.0.113.10:8000 的地址（IP 或域名 + 端口，不要有中文或空格）。", raw)
        chain.any { it is java.net.UnknownHostException } || has("unable to resolve host", "no address associated") ->
            ConnIssue(ConnKind.DNS, "域名解析失败（DNS）",
                "手机找不到这个域名。请检查地址有没有写错，或手机是否已联网；直接用 IP 地址可以避开 DNS。", raw)
        chain.any { it is java.net.SocketTimeoutException } || has("timeout", "timed out") ->
            ConnIssue(ConnKind.TIMEOUT, "连接超时",
                "手机发出了请求但服务器没有回应。常见原因：云服务器防火墙/安全组没有放行 8000 端口、服务器没开机、服务没启动。", raw)
        has("econnrefused", "connection refused") ->
            ConnIssue(ConnKind.REFUSED, "连接被拒绝",
                "服务器主机能访问，但 8000 端口没有程序在监听。请确认桥接服务已启动，且端口号正确。", raw)
        chain.any { it is java.net.NoRouteToHostException } || has("enetunreach", "network is unreachable", "no route to host", "ehostunreach") ->
            ConnIssue(ConnKind.UNREACHABLE, "网络不可达",
                "手机当前无法到达这个地址。请检查手机是否联网（WiFi/流量）、是否开了会拦截的代理/VPN，以及服务器 IP 是否正确。", raw)
        chain.any { it is javax.net.ssl.SSLException } || has("ssl", "tls", "handshake", "certificate", "trust anchor") ->
            ConnIssue(ConnKind.TLS, "https 握手失败",
                "安全连接没能建立。最常见原因：服务器其实没有启用 https（请在服务器上运行 mt5-bridge.exe --setup <服务器IP>，它会自动设置 .env 里的 SSL_CERTFILE / SSL_KEYFILE，然后重启服务）；其次是端口填错、证书/私钥不匹配，或中间有设备拦截了 TLS。", raw)
        chain.any { it is java.net.ConnectException } ->
            ConnIssue(ConnKind.UNREACHABLE, "无法连接到服务器",
                "手机连不上这个地址。请检查网络、服务器 IP 和端口，以及云服务器防火墙。", raw)
        else -> ConnIssue(ConnKind.UNKNOWN, "连接失败（未知原因）", "请把下面的小字截图发给开发者。", raw)
    }
}

/** “测试连接”结果 */
sealed class ConnTestResult {
    /** [learnedFingerprint] 非空 = 这次测试首次记录了服务器的自签名证书指纹（TOFU），调用方应把它保存进配置 */
    data class Success(val health: String, val account: AccountInfo, val learnedFingerprint: String? = null) : ConnTestResult()
    data class Failure(val step: String, val issue: ConnIssue) : ConnTestResult()
}

/** 设置页“测试连接”：先 GET /health（不带 Key），再 GET /account（带 Key） */
class ConnectionTester(
    /** 测试用注入的客户端；为空时每次测试按“地址 + 证书指纹”新建（https 且有指纹 → 固定指纹）。注入客户端时不做证书学习。 */
    private val http: OkHttpClient? = null,
    /** 首次使用即信任（TOFU）时读取服务器证书指纹的探测器（可注入，便于测试） */
    private val probe: CertProbe = SocketCertProbe,
) {
    private fun newClient(base: String, fingerprint: String): OkHttpClient =
        // 与图表 / 轮询共用同一个连接池和 TLS 配置（同一地址 + 指纹 → 同一个客户端），只是测试时超时更短
        BridgeHttp.client(base, fingerprint).newBuilder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()

    suspend fun test(rawUrl: String, apiKey: String, certFingerprint: String = ""): ConnTestResult = withContext(Dispatchers.IO) {
        val base = normalizeServerUrl(rawUrl)
        val key = apiKey.trim()
        if (base.isEmpty() || !isValidServerUrl(base)) {
            return@withContext ConnTestResult.Failure("检查地址",
                classifyError(IllegalArgumentException("Invalid URL: ${base.ifEmpty { "(empty)" }}"), key))
        }
        if (!isHttpsUrl(base)) return@withContext ConnTestResult.Failure("检查地址", classifyError(HttpsRequiredException(base), key))
        if (key.isEmpty()) return@withContext ConnTestResult.Failure("检查 API Key",
            ConnIssue(ConnKind.AUTH, "还没有填写 API Key", "请先在下面填写 API Key 再测试。", "API Key is empty"))
        var client = http ?: try { newClient(base, certFingerprint) } catch (e: InvalidFingerprintException) {
            return@withContext ConnTestResult.Failure("检查证书指纹", classifyError(e, key))
        }
        var learned: String? = null
        val health = try { get(client, "$base/health", null) } catch (e: Exception) {
            val issue = classifyError(e, key)
            // TOFU：没有指纹 + 自签名（不受信任）证书 → 读取证书指纹，固定后重试。已有指纹时 shouldLearnPin 恒为 false，绝不放宽。
            if (http != null || !shouldLearnPin(certFingerprint, issue.kind)) return@withContext ConnTestResult.Failure("第 1 步 /health", issue)
            val u = base.toHttpUrlOrNull() ?: return@withContext ConnTestResult.Failure("第 1 步 /health", issue)
            val fp = try { probe.fetch(u.host, u.port) } catch (pe: Exception) {
                return@withContext ConnTestResult.Failure("第 1 步 /health", issue)
            }
            val pinned = try { newClient(base, fp) } catch (ie: InvalidFingerprintException) {
                return@withContext ConnTestResult.Failure("第 1 步 /health", issue)
            }
            val h = try { get(pinned, "$base/health", null) } catch (e2: Exception) {
                return@withContext ConnTestResult.Failure("第 1 步 /health（记录证书后重试）", classifyError(e2, key))
            }
            client = pinned; learned = fp
            h
        }
        val acc = try { JSONObject(get(client, "$base/account", key)) } catch (e: Exception) {
            return@withContext ConnTestResult.Failure("第 2 步 /account", classifyError(e, key)) // 密钥无效等：不记录证书
        }
        try {
            ConnTestResult.Success(health.take(120), AccountInfo(acc.optString("login"), acc.optString("server"), acc.optString("currency"),
                acc.optDouble("balance"), acc.optDouble("equity"), acc.optDouble("margin"), acc.optDouble("margin_free"), acc.optInt("leverage")), learned)
        } catch (e: Exception) { ConnTestResult.Failure("解析 /account", classifyError(e, key)) }
    }

    private fun get(http: OkHttpClient, url: String, apiKey: String?): String {
        val b = Request.Builder().url(url)
        if (apiKey != null) b.header("X-API-Key", apiKey)
        http.newCall(b.get().build()).execute().use { r ->
            val body = r.body?.string() ?: ""
            if (!r.isSuccessful) throw HttpStatusException(r.code, body.take(300))
            return body
        }
    }
}

/** 测试成功时显示的中文文字 */
fun ConnTestResult.Success.summary(): String =
    "✅ 连接成功\n账户 ${account.login} · ${account.server}\n余额 %.2f %s · 净值 %.2f".format(account.balance, account.currency, account.equity)
