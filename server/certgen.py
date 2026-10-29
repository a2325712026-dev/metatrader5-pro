"""自签名 HTTPS 证书生成（供 main.py 的 --make-cert 与 make-cert.py 共用）。
Self-signed TLS certificate generator (shared by `main.py --make-cert` and `make-cert.py`).

依赖 / needs: `cryptography` (pip install cryptography)。
证书：RSA-2048 / SHA-256，有效期约 10 年，subjectAltName 含服务器 IP（或域名）。
不含任何密钥以外的机密；key.pem 是私钥，请勿外传。
"""
import base64
import datetime
import ipaddress
import json
import os
import re
import secrets
import sys

CODE_PREFIX = "MT5PRO1:"


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


# ---------------- 一键配置（--setup）与连接码 ----------------
def gen_api_key() -> str:
    """生成强随机 API Key（256 位，URL 安全字符）。"""
    return secrets.token_urlsafe(32)


def _b64url(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")


def make_connection_code(url: str, api_key: str, fingerprint: str) -> str:
    """连接码 = "MT5PRO1:" + base64url(JSON {u: 地址, k: API Key, f: SHA-256 指纹})（无填充）。含 API Key，须保密。"""
    payload = json.dumps({"u": url, "k": api_key, "f": fingerprint}, separators=(",", ":"), ensure_ascii=False)
    return CODE_PREFIX + _b64url(payload.encode("utf-8"))


def parse_connection_code(code: str) -> dict:
    """解析连接码，返回 {"u","k","f"}；格式不对抛 ValueError。"""
    m = re.search(r"MT5PRO1[:：]\s*([A-Za-z0-9_\-+/=\s]*)", code or "")
    if not m:
        raise ValueError("不是有效的连接码（应以 MT5PRO1: 开头）")
    body = re.sub(r"\s+", "", m.group(1)).replace("+", "-").replace("/", "_").rstrip("=")
    try:
        obj = json.loads(base64.urlsafe_b64decode(body + "=" * (-len(body) % 4)).decode("utf-8"))
        if not isinstance(obj, dict) or not all(isinstance(obj.get(k), str) for k in ("u", "k", "f")):
            raise ValueError
    except Exception:
        raise ValueError("连接码内容损坏或不完整") from None
    return obj


_PLACEHOLDER_KEY = re.compile(r"^(请|changeme|your[-_ ]?key|xxx)", re.IGNORECASE)


def read_env_file(path: str) -> dict:
    """读取 .env 的 KEY=VALUE（忽略注释 / 空行；去掉成对引号）。文件不存在返回空 dict。"""
    out = {}
    if not os.path.isfile(path):
        return out
    with open(path, encoding="utf-8-sig") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            v = v.strip()
            if len(v) >= 2 and v[0] == v[-1] and v[0] in "\"'":
                v = v[1:-1]
            out[k.strip()] = v
    return out


def update_env_file(path: str, updates: dict) -> None:
    """就地更新 .env：已有的键改值（保留注释和其它行），没有的键追加到末尾。文件不存在则新建。"""
    lines = []
    if os.path.isfile(path):
        with open(path, encoding="utf-8-sig") as f:
            lines = f.read().splitlines()
    remaining = dict(updates)
    for i, line in enumerate(lines):
        t = line.strip()
        if not t or t.startswith("#") or "=" not in t:
            continue
        k = t.split("=", 1)[0].strip()
        if k in remaining:
            lines[i] = f"{k}={remaining.pop(k)}"
    for k, v in remaining.items():
        lines.append(f"{k}={v}")
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")
    try:
        os.chmod(path, 0o600)
    except OSError:
        pass


def cert_hosts(cert_path: str) -> list:
    """证书 subjectAltName 里的 IP / 域名（字符串列表）。"""
    from cryptography import x509
    with open(cert_path, "rb") as f:
        cert = x509.load_pem_x509_certificate(f.read())
    try:
        san = cert.extensions.get_extension_for_class(x509.SubjectAlternativeName).value
    except x509.ExtensionNotFound:
        return []
    return [str(i) for i in san.get_values_for_type(x509.IPAddress)] + list(san.get_values_for_type(x509.DNSName))


def format_base_url(host: str, port) -> str:
    host = host.strip()
    return f"https://[{host}]:{port}" if ":" in host and not host.startswith("[") else f"https://{host}:{port}"


def print_connection_code(code: str, qr: bool = False) -> None:
    print(" 连接码（手机 App：设置 → 导入连接码 → 粘贴）：")
    print()
    print(code)
    print()
    print(" 注意：连接码里包含 API Key，等同于密码，只发给你自己的手机，不要公开、不要贴到聊天群或截图外传。")
    if qr:
        try:
            from miniqr import make_qr, qr_to_console
            print()
            print(" 二维码（用另一台手机扫描后复制文字；深色背景的窗口里才能正确显示）：")
            print(qr_to_console(make_qr(code)))
        except Exception as e:  # 二维码只是辅助，失败不影响配置
            print(f" （二维码生成失败，已跳过：{e}）")


def run_setup(host: str, base_dir: str, port: int = 8000, force: bool = False, qr: bool = True) -> int:
    """一键配置：API Key（为空才生成）+ 自签名证书（缺失才生成）+ 更新 .env + 打印连接码。
    不会打印私钥。可重复运行（幂等）：已有的 API Key / 证书原样保留。"""
    host = (host or "").strip()
    if not host:
        print("[错误] 请提供服务器公网 IP（或域名），例如：--setup 203.0.113.10", flush=True)
        return 2
    env_path = os.path.join(base_dir, ".env")
    env = read_env_file(env_path)

    api_key = env.get("BRIDGE_API_KEY", "").strip()
    new_key = not api_key or bool(_PLACEHOLDER_KEY.match(api_key))
    if new_key:
        api_key = gen_api_key()

    cert_rel = env.get("SSL_CERTFILE", "").strip() or "cert.pem"
    key_rel = env.get("SSL_KEYFILE", "").strip() or "key.pem"
    cert_path, key_path = (p if os.path.isabs(p) else os.path.join(base_dir, p) for p in (cert_rel, key_rel))
    try:
        have_cert, have_key = os.path.isfile(cert_path), os.path.isfile(key_path)
        generated = False
        if force or not (have_cert or have_key):
            if (cert_rel, key_rel) != ("cert.pem", "key.pem") and not force:
                print(f"[错误] .env 中指定的证书不存在：{cert_path}", flush=True)
                return 2
            cert_path, key_path, fp = make_cert(host, base_dir, force=True)
            cert_rel, key_rel = "cert.pem", "key.pem"
            generated = True
        elif have_cert != have_key:
            print(f"[错误] 证书文件只存在一个（{cert_path} / {key_path}）。请删除残留文件后重新运行，或加 --force 重新生成。", flush=True)
            return 2
        else:
            fp = cert_fingerprint(cert_path)
    except ImportError:
        print("[错误] 缺少 cryptography 包：请先执行 pip install cryptography", flush=True)
        return 2
    except (ValueError, FileExistsError, OSError) as e:
        print(f"[错误] {e}", flush=True)
        return 2

    update_env_file(env_path, {"BRIDGE_API_KEY": api_key, "SSL_CERTFILE": cert_rel, "SSL_KEYFILE": key_rel,
                               "HOST": "0.0.0.0", "PORT": str(port)})
    url = format_base_url(host, port)
    code = make_connection_code(url, api_key, fp)

    print("=" * 60)
    print(" 一键配置完成")
    print(f"   配置文件 : {env_path}")
    print(f"   API Key  : {'已自动生成并写入 .env' if new_key else '沿用 .env 里已有的'}")
    print(f"   HTTPS 证书: {'已生成（自签名，约 10 年）' if generated else '沿用已有证书'}  {cert_path}")
    print(f"   监听     : 0.0.0.0:{port}（HTTPS）")
    try:
        sans = cert_hosts(cert_path)
        if sans and host.strip("[]") not in sans:
            print(f" [警告] 证书里没有 {host}（证书适用于：{', '.join(sans)}）。App 用指纹固定证书仍可连接；要让证书匹配新地址请加 --force 重新生成（旧连接码将失效）。")
    except Exception:
        pass
    print()
    print_connection_code(code, qr=qr)
    print()
    print(" 接下来：1) 放行防火墙 / 云安全组的该端口（TCP " + str(port) + "）  2) 启动 mt5-bridge  3) 手机 App 粘贴上面的连接码")
    print("=" * 60)
    return 0


if __name__ == "__main__":
    sys.exit(run_make_cert(sys.argv[1] if len(sys.argv) > 1 else "", os.getcwd()))
