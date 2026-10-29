package com.morris.mttpsl

import okhttp3.Protocol
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.security.MessageDigest

/** 测试辅助：v0.13 起 App 只允许 HTTPS，所以所有本地 MockWebServer 都用自签名 HTTPS + 指纹固定。 */
object TestHttps {
    /** 启动 HTTPS MockWebServer（证书 SAN = 127.0.0.1 / localhost）；返回 (服务器, 冒号分隔大写指纹) */
    fun start(held: HeldCertificate = HeldCertificate.Builder().addSubjectAlternativeName("127.0.0.1").addSubjectAlternativeName("localhost").commonName("localhost").build()): Pair<MockWebServer, String> {
        val s = MockWebServer()
        s.useHttps(HandshakeCertificates.Builder().heldCertificate(held).build().sslSocketFactory(), false)
        s.protocols = listOf(Protocol.HTTP_1_1)
        s.start()
        return s to fingerprintOf(held)
    }
    fun fingerprintOf(c: HeldCertificate): String =
        MessageDigest.getInstance("SHA-256").digest(c.certificate.encoded).joinToString(":") { "%02X".format(it) }
}
