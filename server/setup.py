"""一键配置（Python 源码版）：双击 setup.bat，或 `python setup.py [服务器公网IP]`。
等价于 `python main.py --setup <IP>`：自动生成 API Key + 自签名 HTTPS 证书，写入 .env，并打印手机 App 用的连接码。
可重复运行：已有的 API Key / 证书会保留。需要 `pip install cryptography`。
"""
import argparse
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from certgen import read_env_file, run_setup  # noqa: E402


def main() -> int:
    ap = argparse.ArgumentParser(description="一键配置 HTTPS + API Key，并打印连接码")
    ap.add_argument("ip", nargs="?", help="服务器公网 IP（或域名）；不填则交互输入")
    ap.add_argument("--port", type=int, default=None, help="监听端口（默认取 .env 的 PORT，否则 8000）")
    ap.add_argument("--force", action="store_true", help="覆盖已有证书（旧连接码失效）")
    ap.add_argument("--no-qr", action="store_true", help="不打印二维码")
    a = ap.parse_args()
    ip = (a.ip or "").strip()
    if not ip:
        ip = input("请输入这台服务器的公网 IP（或域名）: ").strip()
    port = a.port or int(read_env_file(os.path.join(HERE, ".env")).get("PORT") or 8000)
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
    return run_setup(ip, HERE, port, a.force, qr=not a.no_qr)


if __name__ == "__main__":
    code = main()
    if sys.stdin and sys.stdin.isatty():
        try:
            input("按回车键退出…")
        except Exception:
            pass
    sys.exit(code)
