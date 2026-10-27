"""生成自签名 HTTPS 证书 / Generate a self-signed HTTPS certificate for the MT5 bridge.

用法 / Usage:
    python make-cert.py <服务器IP或域名> [--out 目录] [--force]
    例 e.g.  python make-cert.py 203.0.113.10

输出 cert.pem / key.pem（默认写到本脚本所在目录），并打印 SHA-256 指纹（冒号分隔）。
需要 `pip install cryptography`。已打包的 exe 用户请直接运行：mt5-bridge.exe --make-cert <IP>
"""
import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from certgen import run_make_cert  # noqa: E402


def main() -> int:
    p = argparse.ArgumentParser(description="生成自签名证书（有效期约 10 年，SAN 含服务器 IP）")
    p.add_argument("host", help="服务器公网 IP（或域名）")
    p.add_argument("--out", default=os.path.dirname(os.path.abspath(__file__)), help="输出目录（默认：本脚本所在目录）")
    p.add_argument("--force", action="store_true", help="覆盖已存在的 cert.pem / key.pem")
    a = p.parse_args()
    return run_make_cert(a.host, a.out, a.force)


if __name__ == "__main__":
    sys.exit(main())
