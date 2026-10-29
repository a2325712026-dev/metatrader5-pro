package com.morris.mttpsl.api

import java.io.IOException
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/*
 * TOFU（首次使用即信任）证书记录：
 *  - 没有设置指纹、且服务器出示的是系统不信任的证书（自签名）时，第一次“测试连接 / 连接”成功（API Key 有效）后，
 *    自动把该证书的 SHA-256 指纹记录进已保存的配置，之后一律固定这张证书。
 *  - 已经有指纹（不论是连接码导入、手填还是之前记录的）时【绝不】再学习：指纹变了就拒绝连接，只有用户手动“重置证书记录”才会清除。
 *  - 探测（[CertProbe]）只读取证书，不发送任何 HTTP 数据；API Key 只会发到“已固定该证书”的连接上，且只有 /account 成功才会记录。
 * 判断逻辑都是纯函数 / 可注入接口，见 TofuTest。
 */

/** 是否应该学习（记录）证书：只有“当前没有指纹”且“失败原因是证书不受信任（自签名）”才学习。已有指纹（哪怕格式不对）一律不学习。 */
fun shouldLearnPin(currentFingerprint: String, kind: ConnKind): Boolean =
    currentFingerprint.isBlank() && kind == ConnKind.TLS_UNTRUSTED

/** 测试成功后学到的指纹能否写入已保存的配置：已保存地址与测试地址相同，且已保存配置里还没有指纹（绝不覆盖已有指纹）。 */
fun shouldPersistLearnedPin(savedUrl: String, testedUrl: String, savedFingerprint: String): Boolean {
    if (savedFingerprint.isNotBlank()) return false
    val a = normalizeServerUrl(savedUrl); val b = normalizeServerUrl(testedUrl)
    return a.isNotEmpty() && a.equals(b, ignoreCase = true)
}

/** 读取服务器出示的叶子证书指纹（冒号分隔大写十六进制）。实现只能做 TLS 握手，不得发送任何应用数据。失败抛 [IOException]。 */
fun interface CertProbe {
    @Throws(IOException::class)
    fun fetch(host: String, port: Int): String
}

/** 真实探测：用“只记录不校验”的 TrustManager 做一次 TLS 握手并立刻关闭；不发送任何 HTTP 数据（因此不会泄露 API Key）。 */
object SocketCertProbe : CertProbe {
    override fun fetch(host: String, port: Int): String {
        var leaf: X509Certificate? = null
        val capture = object : X509TrustManager {
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) { leaf = chain?.firstOrNull() }
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw java.security.cert.CertificateException("no client certs")
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(capture), SecureRandom()) }
        (ctx.socketFactory.createSocket() as SSLSocket).use { s ->
            s.soTimeout = 8000
            s.connect(InetSocketAddress(host, port), 8000)
            s.startHandshake()
        }
        val c = leaf ?: throw IOException("服务器没有出示证书")
        return formatFingerprint(certFingerprint(c))
    }
}
