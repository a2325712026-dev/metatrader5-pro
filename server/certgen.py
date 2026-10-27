"""自签名 HTTPS 证书生成（供 main.py 的 --make-cert 与 make-cert.py 共用）。
Self-signed TLS certificate generator (shared by `main.py --make-cert` and `make-cert.py`).

依赖 / needs: `cryptography` (pip install cryptography)。
证书：RSA-2048 / SHA-256，有效期约 10 年，subjectAltName 含服务器 IP（或域名）。
不含任何密钥以外的机密；key.pem 是私钥，请勿外传。
"""
import datetime
import ipaddress
import os
import sys


def _fmt_fp(raw: bytes) -> str:
    return ":".join(f"{b:02X}" for b in raw)


def cert_fingerprint(cert_path: str) -> str:
    """读取 PEM 证书文件，返回 SHA-256 指纹（冒号分隔的大写十六进制，与 `openssl x509 -fingerprint -sha256` 一致）。"""
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes
    with open(cert_path, "rb") as f:
        cert = x509.load_pem_x509_certificate(f.read())
    return _fmt_fp(cert.fingerprint(hashes.SHA256()))


def make_cert(host: str, out_dir: str, days: int = 3650, force: bool = False) -> tuple[str, str, str]:
    """生成自签名证书，写入 out_dir/cert.pem 与 out_dir/key.pem，返回 (cert_path, key_path, 指纹)。
    host 可以是 IPv4/IPv6 地址（写入 SAN 的 IP 项）或域名（写入 SAN 的 DNS 项）。"""
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import rsa
    from cryptography.x509.oid import ExtendedKeyUsageOID, NameOID

    host = (host or "").strip()
    if not host:
        raise ValueError("请提供服务器 IP（或域名） / server IP or hostname required")
    try:
        san: x509.GeneralName = x509.IPAddress(ipaddress.ip_address(host))
    except ValueError:
        if not all(c.isalnum() or c in "-." for c in host) or host.startswith(("-", ".")):
            raise ValueError(f"无法识别的 IP / 域名: {host!r}")
        san = x509.DNSName(host)

    cert_path = os.path.join(out_dir, "cert.pem")
    key_path = os.path.join(out_dir, "key.pem")
    if not force and (os.path.exists(cert_path) or os.path.exists(key_path)):
        raise FileExistsError(f"{cert_path} 或 {key_path} 已存在；如需覆盖请加 --force（覆盖后 App 里的指纹要同步更新）")
    os.makedirs(out_dir, exist_ok=True)

    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, host[:64]),
                      x509.NameAttribute(NameOID.ORGANIZATION_NAME, "MT5 Bridge (self-signed)")])
    now = datetime.datetime.now(datetime.timezone.utc)
    cert = (x509.CertificateBuilder()
            .subject_name(name).issuer_name(name).public_key(key.public_key())
            .serial_number(x509.random_serial_number())
            .not_valid_before(now - datetime.timedelta(days=1))
            .not_valid_after(now + datetime.timedelta(days=days))
            .add_extension(x509.SubjectAlternativeName([san]), critical=False)
            .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
            .add_extension(x509.KeyUsage(digital_signature=True, key_encipherment=True, content_commitment=False,
                                         data_encipherment=False, key_agreement=False, key_cert_sign=False,
                                         crl_sign=False, encipher_only=False, decipher_only=False), critical=True)
            .add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.SERVER_AUTH]), critical=False)
            .sign(key, hashes.SHA256()))

    with open(key_path, "wb") as f:
        f.write(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.TraditionalOpenSSL,
                                  serialization.NoEncryption()))
    try:
        os.chmod(key_path, 0o600)  # Windows 上无实际效果
    except OSError:
        pass
    with open(cert_path, "wb") as f:
        f.write(cert.public_bytes(serialization.Encoding.PEM))
    return cert_path, key_path, _fmt_fp(cert.fingerprint(hashes.SHA256()))


def run_make_cert(host: str, out_dir: str, force: bool = False) -> int:
    """命令行入口：生成并打印指纹与后续步骤。"""
    try:
        cert_path, key_path, fp = make_cert(host, out_dir, force=force)
    except ImportError:
        print("[错误] 缺少 cryptography 包：请先执行 pip install cryptography", flush=True)
        return 2
    except (ValueError, FileExistsError) as e:
        print(f"[错误] {e}", flush=True)
        return 2
    print("=" * 60)
    print(" 已生成自签名证书（有效期约 10 年）")
    print(f"   证书 cert: {cert_path}")
    print(f"   私钥 key : {key_path}   （私钥不要外传）")
    print(f"   SAN      : {host}")
    print()
    print(" SHA-256 指纹（填入 App：设置 → HTTPS 证书指纹）:")
    print(f"   {fp}")
    print()
    print(" 下一步：在 .env 中加入（路径可相对 .env 所在目录）：")
    print("   SSL_CERTFILE=cert.pem")
    print("   SSL_KEYFILE=key.pem")
    print(" 然后重启桥接服务，App 地址改为 https://" + (f"[{host}]" if ":" in host else host) + ":端口")
    print("=" * 60)
    return 0


if __name__ == "__main__":
    sys.exit(run_make_cert(sys.argv[1] if len(sys.argv) > 1 else "", os.getcwd()))
