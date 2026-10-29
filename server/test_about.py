"""v16: /about 公开信息与启动横幅的测试（MetaTrader5 用桩模块替代）。运行：python -m unittest test_about -v"""
import io, os, sys, types, unittest, contextlib

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.modules.setdefault("MetaTrader5", types.ModuleType("MetaTrader5"))
import main  # noqa: E402

ADDR = "0xB5f7e22fa69b5D88eeCc238A767b582d18CFF2bF"
TG = "https://t.me/metatrader5pro_chat_group"


class AboutTest(unittest.TestCase):
    def test_constants_exact(self):
        self.assertEqual(main.DONATION_ADDRESS, ADDR)
        self.assertEqual(main.TELEGRAM_URL, TG)
        self.assertEqual(main.VERSION, 16)

    def test_health_and_about(self):
        self.assertEqual(main.health()["version"], 16)
        a = main.about()
        self.assertEqual(a["donation"]["address"], ADDR)
        self.assertEqual(a["telegram"], TG)
        self.assertIn("Base", a["donation"]["chains"])
        blob = repr(a).lower()
        for bad in ("api_key", "bridge_api_key", "pem", "password"):
            self.assertNotIn(bad, blob)

    def test_about_no_auth(self):
        from fastapi.testclient import TestClient
        r = TestClient(main.app).get("/about")
        self.assertEqual(r.status_code, 200)
        self.assertEqual(r.json()["donation"]["address"], ADDR)

    def test_banner(self):
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            main.print_support_banner()
        out = buf.getvalue()
        self.assertIn(ADDR, out); self.assertIn(TG, out); self.assertIn("BEP20", out)


if __name__ == "__main__":
    unittest.main()
