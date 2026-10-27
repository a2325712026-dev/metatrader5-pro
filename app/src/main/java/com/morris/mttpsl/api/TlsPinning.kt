package com.morris.mttpsl.api

import okhttp3.OkHttpClient
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/*
 * HTTPS 证书指纹（SHA-256）固定，用于“自签名证书 + 裸 IP”的服务器：
 *  - 地址是 https:// 且设置了指纹 → 只信任【叶子证书 SHA-256 与指纹完全一致】的服务器（不信任任何其他证书，包括系统 CA 签发的）；
 *    因为证书本身已被指纹唯一锁定，仅在这个模式下放宽主机名校验（自签名证书的 SAN 即使与地址不符也无所谓）。
 *  - 地址是 https:// 且没有指纹 → 完全使用系统信任库 + 标准主机名校验（Let's Encrypt 等正规证书）。
 *  - 地址是 http:// → 与以前一样，不涉及 TLS。
 * 任何情况下都不会“关闭校验”。
 */

/** 指纹与服务器证书不一致。[actual] 是服务器实际出示的证书指纹（冒号分隔大写）。 */
class FingerprintMismatchException(val expected: String, val actual: String) :
    CertificateException("Certificate fingerprint mismatch: expected $expected, server presented $actual")

/** 设置里填的指纹格式不对（不是 64 位十六进制）。 */
class InvalidFingerprintException(val input: String) :
    IOException("Invalid certificate fingerprint format (need 64 hex chars, SHA-256)")

private val HEX64 = Regex("^[0-9a-f]{64}$")

/**
 * 规范化指纹：去掉空白 / 冒号（含全角）/ 连字符 / 下划线 / 前缀 "sha256" "sha-256"（任意大小写），
 * 返回 64 位小写十六进制；格式不对返回 null。
 */
fun normalizeFingerprint(raw: String): String? {
    var t = raw.trim().lowercase()
    t = t.replace(Regex("^(sha-?256)\\s*[:=]?"), "")  // "SHA256 Fingerprint=" 的 openssl 格式也兼容（见下）
    t = t.replace(Regex("^\\s*fingerprint\\s*="), "")
    t = t.filter { !it.isWhitespace() && it != ':' && it != '：' && it != '-' && it != '_' }
    return t.takeIf { HEX64.matches(it) }
}

/** 显示用格式：AA:BB:…（大写，冒号分隔）；格式不对原样返回 */
fun formatFingerprint(raw: String): String {
    val n = normalizeFingerprint(raw) ?: return raw.trim()
    return n.uppercase().chunked(2).joinToString(":")
}

fun certFingerprint(cert: X509Certificate): String =
    MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }

/** 常量时间比较证书指纹与（已规范化或未规范化的）期望指纹 */
fun fingerprintMatches(cert: X509Certificate, expected: String): Boolean {
    val e = normalizeFingerprint(expected) ?: return false
    return MessageDigest.isEqual(certFingerprint(cert).toByteArray(), e.toByteArray())
}

/** 是否是 https:// 地址（已规范化后的 URL） */
fun isHttpsUrl(url: String): Boolean = url.startsWith("https://", ignoreCase = true)

/** 由 http(s) 地址得到 WebSocket 地址：http→ws，https→wss（其他原样返回） */
fun toWebSocketUrl(baseUrl: String): String = when {
    baseUrl.startsWith("https://", ignoreCase = true) -> "wss://" + baseUrl.substring(8)
    baseUrl.startsWith("http://", ignoreCase = true) -> "ws://" + baseUrl.substring(7)
    else -> baseUrl
}

/** 只接受叶子证书 SHA-256 等于 [expectedHex] 的服务器。 */
class PinnedTrustManager(expectedFingerprint: String) : X509TrustManager {
    private val expected: String = normalizeFingerprint(expectedFingerprint) ?: throw InvalidFingerprintException(expectedFingerprint)

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        if (chain.isNullOrEmpty()) throw CertificateException("Empty server certificate chain")
        val leaf = chain[0]
        val actual = certFingerprint(leaf)
        if (!MessageDigest.isEqual(actual.toByteArray(), expected.toByteArray()))
            throw FingerprintMismatchException(formatFingerprint(expected), formatFingerprint(actual))
        leaf.checkValidity() // 过期 / 尚未生效仍然拒绝（指纹对也一样）
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        throw CertificateException("Client certificates are not supported")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/** 固定模式下的主机名校验：证书已被指纹锁定，所以不再比对主机名。只在固定模式使用。 */
internal val PinnedHostnameVerifier = HostnameVerifier { _, _ -> true }

/**
 * 按地址 + 指纹配置 OkHttp 的 TLS：
 *  - 非 https 或 指纹为空（空白） → 不改动（http 明文 / 系统信任）
 *  - https 且有指纹 → 固定指纹；指纹格式错误抛 [InvalidFingerprintException]（不会悄悄退回系统信任或放行）
 */
fun OkHttpClient.Builder.applyServerTls(serverUrl: String, fingerprint: String): OkHttpClient.Builder {
    if (!isHttpsUrl(serverUrl) || fingerprint.isBlank()) return this
    val tm = PinnedTrustManager(fingerprint)
    val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), SecureRandom()) }
    return sslSocketFactory(ctx.socketFactory, tm).hostnameVerifier(PinnedHostnameVerifier)
}
