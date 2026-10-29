package com.morris.mttpsl.api

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException

/*
 * v15：所有请求（图表 K 线、报价列表、WebSocket 报价、账户 / 持仓 / 挂单轮询、交易、测试连接）共用【同一个】OkHttp 根客户端
 * （同一个连接池 / 调度器），按“地址 + 已保存的 TOFU 证书指纹”派生出唯一的配置客户端并缓存。
 *
 * v14 的问题（录屏里的 SSLHandshakeException “Trust anchor for certification path not found”）：
 * 连接横幅上的“重试”调用 configure() 时【没有传证书指纹】→ 新建的客户端退回系统信任库 → 自签名证书握手失败；
 * 而设置页“测试连接”自己带指纹建客户端，所以测试总是成功。v15 起重试会带上指纹，且同一地址 + 指纹只会有一个客户端。
 */
object BridgeHttp {
    /** 根客户端：共享连接池与线程池；各派生客户端只改 TLS / 超时 */
    val root: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).writeTimeout(20, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val cache = HashMap<String, OkHttpClient>()

    internal fun cacheKey(serverUrl: String, fingerprint: String): String =
        normalizeServerUrl(serverUrl).lowercase() + "|" + (normalizeFingerprint(fingerprint) ?: fingerprint.trim())

    /** 地址 + 指纹 → 共享客户端（含 HTTPS-only、证书固定、GET 超时重试）。非 https 抛 [HttpsRequiredException]，指纹格式错抛 [InvalidFingerprintException]。 */
    fun client(serverUrl: String, fingerprint: String): OkHttpClient = synchronized(cache) {
        val k = cacheKey(serverUrl, fingerprint)
        cache[k] ?: root.newBuilder()
            .applyServerTls(normalizeServerUrl(serverUrl), fingerprint)
            .addInterceptor(RetryInterceptor())
            .build().also { cache[k] = it }
    }

    /** 测试用：清空缓存 */
    internal fun clear() = synchronized(cache) { cache.clear() }
}

/** 是否是“暂时性”网络错误（超时 / 连接被拒 / 路由不可达）：值得自动重试，也不应该立刻弹横幅。证书错误绝不重试。 */
fun isTransientNetworkError(e: Throwable): Boolean {
    var t: Throwable? = e
    var depth = 0
    while (t != null && depth++ < 6) {
        if (t is SSLHandshakeException || t is java.security.cert.CertificateException) return false
        if (t is SocketTimeoutException || t is ConnectException || t is NoRouteToHostException) return true
        if (t is InterruptedIOException && t.message?.contains("timeout", ignoreCase = true) == true) return true
        if (t is IOException && (t.message?.contains("unexpected end of stream", true) == true || t.message?.contains("Connection reset", true) == true)) return true
        t = t.cause
    }
    return false
}

/** 第 n 次重试前等待：400ms, 800ms, 1600ms …（最长 3s） */
internal fun retryDelayMs(attempt: Int): Long = (400L shl (attempt - 1).coerceIn(0, 4)).coerceAtMost(3000L)

/**
 * 只对幂等的 GET 请求在暂时性错误时重试（最多 [maxRetries] 次，指数退避）；POST / DELETE（下单、平仓、改单）绝不自动重发，避免重复成交。
 */
class RetryInterceptor(private val maxRetries: Int = 2, private val sleep: (Long) -> Unit = { Thread.sleep(it) }) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        if (req.method != "GET") return chain.proceed(req)
        var attempt = 0
        while (true) {
            try { return chain.proceed(req) }
            catch (e: IOException) {
                if (attempt >= maxRetries || !isTransientNetworkError(e) || chain.call().isCanceled()) throw e
                attempt++
                sleep(retryDelayMs(attempt))
            }
        }
    }
}

/**
 * 横幅防抖：暂时性错误（超时 / 连接被拒）同一来源连续失败 [threshold] 次才显示；其他错误（证书、密钥、HTTPS）立即显示。成功一次即清零。
 */
class TransientErrorGate(private val threshold: Int = 3) {
    private val counts = HashMap<String, Int>()
    /** 记录一次失败，返回是否应该显示 */
    fun fail(src: String, transient: Boolean): Boolean {
        if (!transient) { counts[src] = threshold; return true }
        val n = (counts[src] ?: 0) + 1
        counts[src] = n
        return n >= threshold
    }
    fun ok(src: String) { counts.remove(src) }
    fun reset() = counts.clear()
}
