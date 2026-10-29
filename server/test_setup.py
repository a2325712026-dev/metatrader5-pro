"""--setup / 连接码 / 二维码 的测试（用真实的 main.py；MetaTrader5 用桩模块替代，Linux 上也能跑）。
运行：python -m unittest test_setup -v      依赖：fastapi uvicorn python-dotenv cryptography（见 requirements.txt）；对照用 openssl 命令（没有则跳过该项）。
"""
import base64
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import certgen  # noqa: E402
import miniqr  # noqa: E402

TEST_IP = "203.0.113.10"  # 文档专用地址


class SetupTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.stub = os.path.join(self.tmp, "stub"); os.makedirs(self.stub)
        with open(os.path.join(self.stub, "MetaTrader5.py"), "w") as f:  # 桩：initialize 失败，使服务启动后立刻退出
            f.write("def initialize(*a, **k):\n    return False\ndef last_error():\n    return (-1, 'stub')\n")
        self.app = os.path.join(self.tmp, "app"); os.makedirs(self.app)
        for n in ("main.py", "certgen.py", "miniqr.py", ".env.example"):
            shutil.copy(os.path.join(HERE, n), self.app)
        self.env = {k: v for k, v in os.environ.items() if not k.startswith(("BRIDGE_", "SSL_", "MT5_"))}
        self.env["PYTHONPATH"] = self.stub

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def run_main(self, *args, timeout=60):
        return subprocess.run([sys.executable, "main.py", *args], cwd=self.app, env=self.env, capture_output=True,
                              text=True, encoding="utf-8", timeout=timeout)

    def read_env(self):
        return certgen.read_env_file(os.path.join(self.app, ".env"))

    @staticmethod
    def find_code(text):
        m = re.search(r"MT5PRO1:[A-Za-z0-9_\-]+", text)
        return m.group(0) if m else None

    def openssl_fp(self):
        if not shutil.which("openssl"):
            self.skipTest("no openssl")
        out = subprocess.check_output(["openssl", "x509", "-in", os.path.join(self.app, "cert.pem"), "-noout", "-fingerprint", "-sha256"], text=True)
        return out.strip().split("=", 1)[1]

    def test_setup_from_scratch_roundtrip_and_openssl(self):
        r = self.run_main("--setup", TEST_IP, "--port", "8443")
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        env = self.read_env()
        self.assertGreaterEqual(len(env["BRIDGE_API_KEY"]), 40)
        self.assertEqual((env["SSL_CERTFILE"], env["SSL_KEYFILE"], env["HOST"], env["PORT"]), ("cert.pem", "key.pem", "0.0.0.0", "8443"))
        self.assertTrue(os.path.isfile(os.path.join(self.app, "cert.pem")) and os.path.isfile(os.path.join(self.app, "key.pem")))
        code = self.find_code(r.stdout)
        self.assertIsNotNone(code, r.stdout)
        obj = certgen.parse_connection_code(code)
        self.assertEqual(obj["u"], f"https://{TEST_IP}:8443")
        self.assertEqual(obj["k"], env["BRIDGE_API_KEY"])
        self.assertEqual(obj["f"], self.openssl_fp())          # 指纹与 openssl 一致
        self.assertEqual(obj["f"], certgen.cert_fingerprint(os.path.join(self.app, "cert.pem")))
        self.assertIn("SAN", subprocess.check_output(["openssl", "x509", "-in", os.path.join(self.app, "cert.pem"), "-noout", "-text"], text=True).replace("Subject Alternative Name", "SAN"))
        # 私钥不能出现在输出里
        key_pem = open(os.path.join(self.app, "key.pem")).read()
        body = "".join(key_pem.strip().splitlines()[1:-1])
        self.assertNotIn("PRIVATE KEY", r.stdout + r.stderr)
        self.assertNotIn(body[:40], r.stdout + r.stderr)
        self.assertIn("二维码", r.stdout)
        # 原始 JSON 键 / 字段
        raw = json.loads(base64.urlsafe_b64decode(code[len("MT5PRO1:"):] + "=="))
        self.assertEqual(set(raw), {"u", "k", "f"})

    def test_setup_is_idempotent_and_keeps_existing_key_and_cert(self):
        with open(os.path.join(self.app, ".env"), "w", encoding="utf-8") as f:
            f.write("# my config\nBRIDGE_API_KEY=my-existing-strong-key-1234567890\nMT5_LOGIN=12345\nPORT=9000\n")
        r1 = self.run_main("--setup", TEST_IP, "--no-qr")
        self.assertEqual(r1.returncode, 0, r1.stdout + r1.stderr)
        cert1 = open(os.path.join(self.app, "cert.pem"), "rb").read()
        r2 = self.run_main("--setup", TEST_IP, "--no-qr")
        self.assertEqual(r2.returncode, 0)
        self.assertEqual(cert1, open(os.path.join(self.app, "cert.pem"), "rb").read())
        env = self.read_env()
        self.assertEqual(env["BRIDGE_API_KEY"], "my-existing-strong-key-1234567890")
        self.assertEqual(env["MT5_LOGIN"], "12345")                 # 其它项保留
        self.assertEqual(env["PORT"], "9000")                        # 默认取 .env 的 PORT
        self.assertIn("# my config", open(os.path.join(self.app, ".env"), encoding="utf-8").read())
        self.assertEqual(self.find_code(r1.stdout), self.find_code(r2.stdout))
        self.assertNotIn("二维码", r1.stdout)
        self.assertEqual(certgen.parse_connection_code(self.find_code(r1.stdout))["u"], f"https://{TEST_IP}:9000")

    def test_placeholder_key_from_env_example_is_replaced(self):
        shutil.copy(os.path.join(self.app, ".env.example"), os.path.join(self.app, ".env"))
        self.assertTrue(self.read_env()["BRIDGE_API_KEY"].startswith("请"))
        self.assertEqual(self.run_main("--setup", TEST_IP, "--no-qr").returncode, 0)
        self.assertFalse(self.read_env()["BRIDGE_API_KEY"].startswith("请"))

    def test_bad_input_and_half_cert(self):
        r = self.run_main("--setup", "bad host!")
        self.assertNotEqual(r.returncode, 0); self.assertIn("[错误]", r.stdout)
        open(os.path.join(self.app, "cert.pem"), "w").write("x")
        r = self.run_main("--setup", TEST_IP)
        self.assertEqual(r.returncode, 2); self.assertIn("只存在一个", r.stdout)

    def test_normal_startup_prints_code_when_tls_on(self):
        self.assertEqual(self.run_main("--setup", TEST_IP, "--no-qr").returncode, 0)
        r = self.run_main()                                           # 桩 MT5 使服务启动失败退出；横幅已先打印
        code = self.find_code(r.stdout)
        self.assertIsNotNone(code, r.stdout + r.stderr)
        self.assertEqual(certgen.parse_connection_code(code)["k"], self.read_env()["BRIDGE_API_KEY"])
        self.assertIn("API Key", r.stdout.split(code)[1][:200])      # 紧跟保密提示
        self.assertIn("证书 SHA-256 指纹", r.stdout)

    def test_no_code_without_tls(self):
        with open(os.path.join(self.app, ".env"), "w") as f:
            f.write("BRIDGE_API_KEY=abcdefabcdefabcdefabcdefabcdef12\n")
        r = self.run_main()
        self.assertIsNone(self.find_code(r.stdout))

    def test_ipv6_url_is_bracketed(self):
        self.assertEqual(certgen.format_base_url("2001:db8::1", 8000), "https://[2001:db8::1]:8000")
        self.assertEqual(certgen.format_base_url("example.com", 443), "https://example.com:443")


class CodeAndQrTest(unittest.TestCase):
    def test_roundtrip_and_tolerance(self):
        fp = ":".join(["AB"] * 32)
        c = certgen.make_connection_code("https://h:1", "k-é", fp)
        self.assertTrue(c.startswith("MT5PRO1:") and "=" not in c)
        self.assertEqual(certgen.parse_connection_code(c), {"u": "https://h:1", "k": "k-é", "f": fp})
        self.assertEqual(certgen.parse_connection_code("  " + c[:30] + "\n" + c[30:] + " \n")["k"], "k-é")  # 换行 / 空白
        for bad in ("", "hello", "MT5PRO1:", "MT5PRO1:!!!", "MT5PRO1:" + base64.urlsafe_b64encode(b"[1]").decode()):
            with self.assertRaises(ValueError):
                certgen.parse_connection_code(bad)

    def test_api_key_strength(self):
        keys = {certgen.gen_api_key() for _ in range(50)}
        self.assertEqual(len(keys), 50)
        self.assertTrue(all(len(k) >= 43 and re.fullmatch(r"[A-Za-z0-9_\-]+", k) for k in keys))

    def test_qr_decodes_if_opencv_available(self):
        try:
            import cv2, numpy as np
        except ImportError:
            self.skipTest("opencv not installed")
        code = certgen.make_connection_code("https://203.0.113.10:8000", certgen.gen_api_key(), ":".join(["0A"] * 32))
        m = miniqr.make_qr(code); s = len(m)
        img = np.full(((s + 8) * 8, (s + 8) * 8), 255, np.uint8)
        for y in range(s):
            for x in range(s):
                if m[y][x]:
                    img[(y + 4) * 8:(y + 5) * 8, (x + 4) * 8:(x + 5) * 8] = 0
        self.assertEqual(cv2.QRCodeDetector().detectAndDecode(img)[0], code)


if __name__ == "__main__":
    unittest.main()
