"""MT5 桥接服务（Windows）：FastAPI + 官方 MetaTrader5 Python 包。
凭据只从环境变量 / .env 读取，不写日志。"""
import asyncio, hmac, os, sys, threading, time
from contextlib import asynccontextmanager
from datetime import datetime, timedelta
from typing import Literal, Optional

from dotenv import load_dotenv
from fastapi import Depends, FastAPI, Header, HTTPException, WebSocket, WebSocketDisconnect
from pydantic import BaseModel

try:
    import MetaTrader5 as mt5  # 仅 Windows 可用
except ImportError:  # 方便在非 Windows 上做语法检查
    mt5 = None

# .env 放在 exe（或 main.py）旁边；打包后不在 bundle 内读取
BASE_DIR = os.path.dirname(sys.executable) if getattr(sys, "frozen", False) else os.path.dirname(os.path.abspath(__file__))
ENV_PATH = os.path.join(BASE_DIR, ".env")
load_dotenv(ENV_PATH)
if os.name == "nt":  # 让中文在任何代码页的 Windows 控制台都能正常显示
    try:
        import ctypes
        ctypes.windll.kernel32.SetConsoleOutputCP(65001)
    except Exception:
        pass
for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
API_KEY = os.environ.get("BRIDGE_API_KEY", "")
DEVIATION = int(os.environ.get("DEVIATION", "20"))
MAGIC = int(os.environ.get("MAGIC", "20261019"))
LOCK = threading.Lock()  # MetaTrader5 包非线程安全

TIMEFRAMES = {
    "M1": "TIMEFRAME_M1", "M5": "TIMEFRAME_M5", "M15": "TIMEFRAME_M15", "M30": "TIMEFRAME_M30",
    "H1": "TIMEFRAME_H1", "H4": "TIMEFRAME_H4", "D1": "TIMEFRAME_D1",
    "1m": "TIMEFRAME_M1", "5m": "TIMEFRAME_M5", "15m": "TIMEFRAME_M15", "30m": "TIMEFRAME_M30",
    "1h": "TIMEFRAME_H1", "4h": "TIMEFRAME_H4", "1d": "TIMEFRAME_D1",
}


def mt5_init() -> None:
    if mt5 is None:
        raise RuntimeError("MetaTrader5 package not available (Windows only)")
    kw = {}
    if os.environ.get("MT5_PATH"):
        kw["path"] = os.environ["MT5_PATH"]
    if os.environ.get("MT5_LOGIN"):
        kw.update(login=int(os.environ["MT5_LOGIN"]), password=os.environ.get("MT5_PASSWORD", ""),
                  server=os.environ.get("MT5_SERVER", "MetaQuotes-Demo"))
    if not mt5.initialize(**kw):
        raise RuntimeError(f"mt5.initialize failed: {mt5.last_error()}")


@asynccontextmanager
async def lifespan(_):
    if not API_KEY:
        raise RuntimeError("BRIDGE_API_KEY not set")
    mt5_init()
    try:
        a = mt5.account_info(); t = mt5.terminal_info()
        if a:
            kind = {0: "模拟", 1: "竞赛", 2: "真实"}.get(getattr(a, "trade_mode", -1), "?")
            print(f"[MT5] 已连接账户 {a.login}（{kind}） 服务器 {a.server}  余额 {a.balance:.2f} {a.currency}  杠杆 1:{a.leverage}", flush=True)
        if t is not None and not t.trade_allowed:
            print("[MT5] 警告：终端未开启「算法交易 / Algo Trading」，下单会被拒绝", flush=True)
    except Exception as e:
        print(f"[MT5] 读取账户信息失败: {e}", flush=True)
    yield
    mt5.shutdown()


app = FastAPI(title="MT5 Bridge", lifespan=lifespan)


def check_key(key: str | None) -> None:
    if not key or not hmac.compare_digest(key, API_KEY):
        raise HTTPException(401, "invalid api key")


def auth(x_api_key: str | None = Header(default=None)):
    check_key(x_api_key)


def call(fn, *a, **kw):
    # 注意：MetaTrader5 的 C 函数在传入 kwargs（即使是空 dict）时会拒绝位置参数
    # （last_error = -2 "Unnamed arguments not allowed"），因此分开调用。
    def invoke():
        return fn(*a, **kw) if kw else fn(*a)
    with LOCK:
        r = invoke()
        if r is None and fn not in (mt5.order_send, mt5.order_calc_margin, mt5.history_deals_get, mt5.orders_get, mt5.positions_get):  # 断线时重连一次（下单失败不自动重发，避免重复成交）
            mt5_init()
            r = invoke()
    return r


_offset = {"sec": None}


def server_offset() -> int:
    """MT5 的 K 线/报价时间是【经纪商服务器时间】（MetaQuotes-Demo 为 UTC+2/+3），不是 UTC。
    返回 服务器时间 - UTC 的秒数。可用 MT5_SERVER_UTC_OFFSET_HOURS 固定；否则在报价新鲜时自动测算并缓存。"""
    env = os.environ.get("MT5_SERVER_UTC_OFFSET_HOURS")
    if env:
        return int(float(env) * 3600)
    t = call(mt5.symbol_info_tick, "EURUSD")
    if t is not None:
        d = t.time - time.time()
        h = round(d / 1800) * 1800
        if abs(d - h) < 120:  # 报价足够新（休市时不更新缓存）
            _offset["sec"] = int(h)
    return _offset["sec"] or 0


def ensure_symbol(symbol: str):
    info = call(mt5.symbol_info, symbol)
    if info is None:
        raise HTTPException(404, f"unknown symbol {symbol}")
    if not info.visible:
        call(mt5.symbol_select, symbol, True)
    return info


def result_dict(r):
    if r is None:
        return {"ok": False, "retcode": None, "comment": str(mt5.last_error())}
    return {"ok": r.retcode in (mt5.TRADE_RETCODE_DONE, mt5.TRADE_RETCODE_PLACED, mt5.TRADE_RETCODE_DONE_PARTIAL), "retcode": r.retcode, "comment": r.comment,
            "order": r.order, "deal": r.deal, "price": r.price}


def get_position(ticket: int):
    ps = call(mt5.positions_get, ticket=ticket)
    if not ps:
        raise HTTPException(404, f"position {ticket} not found")
    return ps[0]


def filling_mode(info):
    # symbol_info.filling_mode 位掩码：1=FOK, 2=IOC
    if info.filling_mode & 1:
        return mt5.ORDER_FILLING_FOK
    if info.filling_mode & 2:
        return mt5.ORDER_FILLING_IOC
    return mt5.ORDER_FILLING_RETURN


VERSION = 16
TELEGRAM_URL = "https://t.me/metatrader5pro_chat_group"
DONATION_ADDRESS = "0xB5f7e22fa69b5D88eeCc238A767b582d18CFF2bF"
DONATION_CHAINS = ["BSC / BEP20", "Ethereum / ERC20", "Base"]
NOTE_ZH = "MT5 加强版是我利用业余时间独立开发维护的开源项目，完全免费。服务器测试、新功能开发和持续更新都需要投入时间和成本。如果这个工具帮你省了时间或让交易更顺手，欢迎请我喝杯咖啡，你的支持是项目持续更新的动力。"
NOTE_EN = "MT5 Pro is a free, open-source project I build and maintain in my spare time. Server testing, new features and ongoing updates take real time and cost. If it saves you time or makes your trading smoother, consider buying me a coffee. Your support keeps the project going."


def about_info():
    """公开信息（无鉴权、不含任何密钥）"""
    return {"name": "MT5 加强版 / MT5 Pro", "version": VERSION, "telegram": TELEGRAM_URL,
            "donation": {"address": DONATION_ADDRESS, "chains": DONATION_CHAINS},
            "note_zh": NOTE_ZH, "note_en": NOTE_EN}


def print_support_banner():
    print("-" * 56, flush=True)
    print(f" 电报交流群 / Telegram: {TELEGRAM_URL}", flush=True)
    print(f" 打赏地址 / Donate: {DONATION_ADDRESS}", flush=True)
    print(f" 支持的链 / Chains: {', '.join(DONATION_CHAINS)}", flush=True)
    print(" 免费开源，欢迎请我喝杯咖啡 / Free & open source - buy me a coffee if it helps", flush=True)


@app.get("/health")
def health():
    return {"ok": True, "version": VERSION, "partial_close": True, "about": "/about"}


@app.get("/about")
def about():
    return about_info()


@app.get("/account", dependencies=[Depends(auth)])
def account():
    a = call(mt5.account_info)
    d = a._asdict()
    return {k: d[k] for k in ("login", "server", "currency", "balance", "equity", "margin",
                              "margin_free", "leverage", "trade_mode", "company")}


@app.get("/symbol/{symbol}", dependencies=[Depends(auth)])
def symbol(symbol: str):
    i = ensure_symbol(symbol)
    return {"symbol": i.name, "digits": i.digits, "point": i.point,
            "trade_stops_level": i.trade_stops_level, "trade_freeze_level": i.trade_freeze_level,
            "volume_min": i.volume_min, "volume_step": i.volume_step, "volume_max": i.volume_max,
            "trade_contract_size": i.trade_contract_size, "trade_tick_value": i.trade_tick_value,
            "trade_tick_value_profit": getattr(i, "trade_tick_value_profit", i.trade_tick_value),
            "trade_tick_value_loss": getattr(i, "trade_tick_value_loss", i.trade_tick_value),
            "trade_tick_size": i.trade_tick_size, "currency_profit": i.currency_profit}


@app.get("/candles", dependencies=[Depends(auth)])
def candles(symbol: str, timeframe: str = "1m", count: int = 200, start: int = 0):
    """start = 从最新一根往回跳过的 K 线数（0 = 含当前 K 线），用于图表向左滚动时加载更早历史"""
    ensure_symbol(symbol)
    tf = getattr(mt5, TIMEFRAMES.get(timeframe, "TIMEFRAME_M1"))
    rates = call(mt5.copy_rates_from_pos, symbol, tf, max(start, 0), min(max(count, 1), 5000))
    if rates is None and start > 0:
        return []  # 超出终端可用历史
    if rates is None:
        raise HTTPException(500, str(mt5.last_error()))
    off = server_offset()
    return [{"time": int(r["time"]) - off, "open": float(r["open"]), "high": float(r["high"]),
             "low": float(r["low"]), "close": float(r["close"]), "volume": int(r["tick_volume"])}
            for r in rates]


@app.get("/positions", dependencies=[Depends(auth)])
def positions(symbol: str | None = None):
    ps = call(mt5.positions_get, symbol=symbol) if symbol else call(mt5.positions_get)
    out = []
    for p in ps or []:
        dg = ensure_symbol(p.symbol).digits
        out.append({"ticket": str(p.ticket), "symbol": p.symbol,
                    "type": "BUY" if p.type == mt5.POSITION_TYPE_BUY else "SELL",
                    "volume": p.volume, "price_open": round(p.price_open, dg),
                    "price_current": round(p.price_current, dg), "sl": round(p.sl, dg),
                    "tp": round(p.tp, dg), "profit": p.profit})
    return out


class OrderIn(BaseModel):
    symbol: str
    side: Literal["BUY", "SELL"]
    volume: float
    sl: float = 0.0
    tp: float = 0.0
    comment: str = ""


def clean_comment(c: str) -> str:
    # MT5 注释最长 31 个字符；只保留可打印 ASCII，避免经纪商拒单
    c = "".join(ch for ch in (c or "") if 32 <= ord(ch) < 127).strip()
    return (c or "tpsl-app")[:31]


@app.post("/order", dependencies=[Depends(auth)])
def order(o: OrderIn):
    info = ensure_symbol(o.symbol)
    tick = call(mt5.symbol_info_tick, o.symbol)
    buy = o.side == "BUY"
    req = {"action": mt5.TRADE_ACTION_DEAL, "symbol": o.symbol, "volume": o.volume,
           "type": mt5.ORDER_TYPE_BUY if buy else mt5.ORDER_TYPE_SELL,
           "price": tick.ask if buy else tick.bid, "sl": round(o.sl, info.digits), "tp": round(o.tp, info.digits),
           "deviation": DEVIATION, "magic": MAGIC, "comment": clean_comment(o.comment),
           "type_time": mt5.ORDER_TIME_GTC, "type_filling": filling_mode(info)}
    return result_dict(call(mt5.order_send, req))


class ModifyIn(BaseModel):
    tp: float = 0.0  # 0 = 删除
    sl: float = 0.0


@app.post("/positions/{ticket}/modify", dependencies=[Depends(auth)])
def modify(ticket: int, m: ModifyIn):
    p = get_position(ticket)
    info = ensure_symbol(p.symbol)
    tick = call(mt5.symbol_info_tick, p.symbol)
    # 服务端再次校验（BUY 以 bid 平仓，SELL 以 ask 平仓）
    ref = tick.bid if p.type == mt5.POSITION_TYPE_BUY else tick.ask
    dist = max(info.trade_stops_level, info.trade_freeze_level) * info.point
    sgn = 1 if p.type == mt5.POSITION_TYPE_BUY else -1
    if m.tp and sgn * (m.tp - ref) < dist:
        return {"ok": False, "retcode": None, "comment": "TP too close / wrong side"}
    if m.sl and sgn * (ref - m.sl) < dist:
        return {"ok": False, "retcode": None, "comment": "SL too close / wrong side"}
    req = {"action": mt5.TRADE_ACTION_SLTP, "position": ticket, "symbol": p.symbol,
           "sl": round(m.sl, info.digits), "tp": round(m.tp, info.digits), "magic": MAGIC}
    return result_dict(call(mt5.order_send, req))


class CloseIn(BaseModel):
    volume: float = 0.0  # 0 / 省略 = 全部平仓


def close_volume(requested: float, position_volume: float, vmin: float, vstep: float) -> float:
    """部分平仓手数校验（与 MT5 一致）：0 = 全部；必须 >= volume_min、为 volume_step 的整数倍、<= 持仓手数；
    剩余手数若不为 0 也必须 >= volume_min。不合法抛 ValueError。"""
    if requested is None or requested <= 0 or abs(requested - position_volume) < 1e-9:
        return position_volume
    if requested > position_volume + 1e-9:
        raise ValueError(f"volume {requested} > position volume {position_volume}")
    if vmin > 0 and requested < vmin - 1e-9:
        raise ValueError(f"volume {requested} < volume_min {vmin}")
    if vstep > 0:
        n = round(requested / vstep)
        if abs(n * vstep - requested) > 1e-7:
            raise ValueError(f"volume {requested} is not a multiple of volume_step {vstep}")
        requested = round(n * vstep, 8)
    rest = position_volume - requested
    if rest > 1e-9 and vmin > 0 and rest < vmin - 1e-9:
        raise ValueError(f"remaining volume {rest:g} < volume_min {vmin}")
    return requested


@app.post("/positions/{ticket}/close", dependencies=[Depends(auth)])
def close(ticket: int, c: Optional[CloseIn] = None):
    p = get_position(ticket)
    info = ensure_symbol(p.symbol)
    try:
        vol = close_volume(c.volume if c else 0.0, p.volume, info.volume_min, info.volume_step)
    except ValueError as e:
        return {"ok": False, "retcode": None, "comment": str(e)}
    tick = call(mt5.symbol_info_tick, p.symbol)
    buy = p.type == mt5.POSITION_TYPE_BUY
    req = {"action": mt5.TRADE_ACTION_DEAL, "position": ticket, "symbol": p.symbol, "volume": vol,
           "type": mt5.ORDER_TYPE_SELL if buy else mt5.ORDER_TYPE_BUY,
           "price": tick.bid if buy else tick.ask, "deviation": DEVIATION, "magic": MAGIC,
           "comment": "tpsl-app close", "type_time": mt5.ORDER_TIME_GTC, "type_filling": filling_mode(info)}
    r = result_dict(call(mt5.order_send, req))
    r["volume"] = vol
    return r


@app.websocket("/ws/ticks")
async def ws_ticks(ws: WebSocket, symbol: str):
    # 认证：X-API-Key 头（App 使用）或 ?key= 参数
    try:
        check_key(ws.headers.get("x-api-key") or ws.query_params.get("key"))
    except HTTPException:
        await ws.close(code=1008)
        return
    await ws.accept()
    last = None
    try:
        ensure_symbol(symbol)
        off_ms = (await asyncio.to_thread(server_offset)) * 1000
        while True:
            t = await asyncio.to_thread(call, mt5.symbol_info_tick, symbol)
            if t is not None and t.time_msc != last:
                last = t.time_msc
                await ws.send_json({"symbol": symbol, "bid": t.bid, "ask": t.ask, "time_msc": t.time_msc - off_ms})
            await asyncio.sleep(0.2)
    except (WebSocketDisconnect, RuntimeError):
        pass


# ---------------- 报价列表 ----------------
@app.get("/quotes", dependencies=[Depends(auth)])
def quotes(symbols: str = "EURUSD,GBPUSD,USDJPY,USDCHF,AUDUSD,USDCAD,XAUUSD"):
    out = []
    off = server_offset()
    for s in [x.strip() for x in symbols.split(",") if x.strip()]:
        info = call(mt5.symbol_info, s)
        if info is None:
            continue
        if not info.visible:
            call(mt5.symbol_select, s, True)
        t = call(mt5.symbol_info_tick, s)
        if t is None:
            continue
        if t.time_msc == 0:
            continue  # 该品种暂无报价（例如 Demo 服务器未提供）
        out.append({"symbol": s, "bid": round(t.bid, info.digits), "ask": round(t.ask, info.digits), "digits": info.digits, "point": info.point,
                    "spread": info.spread, "change": round(getattr(info, "price_change", 0.0), 2),
                    "time_msc": t.time_msc - off * 1000})
    return out


@app.get("/margin", dependencies=[Depends(auth)])
def margin(symbol: str, side: Literal["BUY", "SELL"], volume: float, price: Optional[float] = None):
    ensure_symbol(symbol)
    t = call(mt5.symbol_info_tick, symbol)
    buy = side == "BUY"
    p = price or (t.ask if buy else t.bid)
    m = call(mt5.order_calc_margin, mt5.ORDER_TYPE_BUY if buy else mt5.ORDER_TYPE_SELL, symbol, volume, p)
    a = call(mt5.account_info)
    return {"margin": m, "currency": a.currency if a else "", "margin_free": a.margin_free if a else None}


# ---------------- 挂单 ----------------
PENDING_TYPES = {
    "BUY_LIMIT": "ORDER_TYPE_BUY_LIMIT", "SELL_LIMIT": "ORDER_TYPE_SELL_LIMIT",
    "BUY_STOP": "ORDER_TYPE_BUY_STOP", "SELL_STOP": "ORDER_TYPE_SELL_STOP",
    "BUY_STOP_LIMIT": "ORDER_TYPE_BUY_STOP_LIMIT", "SELL_STOP_LIMIT": "ORDER_TYPE_SELL_STOP_LIMIT",
}


def type_name(t: int) -> str:
    for k, v in PENDING_TYPES.items():
        if getattr(mt5, v) == t:
            return k
    return {mt5.ORDER_TYPE_BUY: "BUY", mt5.ORDER_TYPE_SELL: "SELL"}.get(t, str(t))


def time_fields(mode: str, expiration: Optional[int]) -> dict:
    """expiration 为 UTC 秒；MT5 需要服务器时间"""
    if mode == "DAY":
        return {"type_time": mt5.ORDER_TIME_DAY}
    if mode == "SPECIFIED":
        if not expiration:
            raise HTTPException(400, "expiration required")
        return {"type_time": mt5.ORDER_TIME_SPECIFIED, "expiration": int(expiration) + server_offset()}
    return {"type_time": mt5.ORDER_TIME_GTC}


def check_pending(kind: str, price: float, stoplimit: float, bid: float, ask: float, dist: float) -> Optional[str]:
    if kind == "BUY_LIMIT" and not price <= ask - dist: return "Buy Limit 价格必须低于 Ask"
    if kind == "SELL_LIMIT" and not price >= bid + dist: return "Sell Limit 价格必须高于 Bid"
    if kind in ("BUY_STOP", "BUY_STOP_LIMIT") and not price >= ask + dist: return "Buy Stop 价格必须高于 Ask"
    if kind in ("SELL_STOP", "SELL_STOP_LIMIT") and not price <= bid - dist: return "Sell Stop 价格必须低于 Bid"
    if kind == "BUY_STOP_LIMIT" and not (0 < stoplimit < price): return "Buy Stop Limit 的限价必须低于触发价"
    if kind == "SELL_STOP_LIMIT" and not stoplimit > price: return "Sell Stop Limit 的限价必须高于触发价"
    return None


def order_dict(o) -> dict:
    dg = ensure_symbol(o.symbol).digits
    off = server_offset()
    return {"ticket": str(o.ticket), "symbol": o.symbol, "type": type_name(o.type),
            "volume": o.volume_current, "price": round(o.price_open, dg),
            "stoplimit": round(o.price_stoplimit, dg), "sl": round(o.sl, dg), "tp": round(o.tp, dg),
            "time_setup": o.time_setup - off,
            "expiration": (o.time_expiration - off) if (o.time_expiration and o.type_time == mt5.ORDER_TIME_SPECIFIED) else 0,
            "type_time": {mt5.ORDER_TIME_GTC: "GTC", mt5.ORDER_TIME_DAY: "DAY",
                          mt5.ORDER_TIME_SPECIFIED: "SPECIFIED"}.get(o.type_time, "GTC")}


@app.get("/orders", dependencies=[Depends(auth)])
def orders():
    return [order_dict(o) for o in (call(mt5.orders_get) or [])]


class PendingIn(BaseModel):
    symbol: str
    type: Literal["BUY_LIMIT", "SELL_LIMIT", "BUY_STOP", "SELL_STOP", "BUY_STOP_LIMIT", "SELL_STOP_LIMIT"]
    volume: float
    price: float
    stoplimit: float = 0.0
    sl: float = 0.0
    tp: float = 0.0
    expiration_mode: Literal["GTC", "DAY", "SPECIFIED"] = "GTC"
    expiration: Optional[int] = None
    comment: str = ""


@app.post("/pending", dependencies=[Depends(auth)])
def pending(o: PendingIn):
    info = ensure_symbol(o.symbol)
    t = call(mt5.symbol_info_tick, o.symbol)
    err = check_pending(o.type, o.price, o.stoplimit, t.bid, t.ask, info.trade_stops_level * info.point)
    if err:
        return {"ok": False, "retcode": None, "comment": err}
    req = {"action": mt5.TRADE_ACTION_PENDING, "symbol": o.symbol, "volume": o.volume,
           "type": getattr(mt5, PENDING_TYPES[o.type]), "price": round(o.price, info.digits),
           "sl": round(o.sl, info.digits), "tp": round(o.tp, info.digits), "deviation": DEVIATION,
           "magic": MAGIC, "comment": clean_comment(o.comment), "type_filling": mt5.ORDER_FILLING_RETURN,
           **time_fields(o.expiration_mode, o.expiration)}
    if o.type.endswith("STOP_LIMIT"):
        req["stoplimit"] = round(o.stoplimit, info.digits)
    return result_dict(call(mt5.order_send, req))


class OrderModifyIn(BaseModel):
    price: float
    stoplimit: float = 0.0
    sl: float = 0.0
    tp: float = 0.0
    expiration_mode: Literal["GTC", "DAY", "SPECIFIED"] = "GTC"
    expiration: Optional[int] = None


def get_order(ticket: int):
    os_ = call(mt5.orders_get, ticket=ticket)
    if not os_:
        raise HTTPException(404, f"order {ticket} not found")
    return os_[0]


@app.post("/orders/{ticket}/modify", dependencies=[Depends(auth)])
def modify_order(ticket: int, m: OrderModifyIn):
    o = get_order(ticket)
    info = ensure_symbol(o.symbol)
    t = call(mt5.symbol_info_tick, o.symbol)
    kind = type_name(o.type)
    err = check_pending(kind, m.price, m.stoplimit, t.bid, t.ask, info.trade_stops_level * info.point)
    if err:
        return {"ok": False, "retcode": None, "comment": err}
    req = {"action": mt5.TRADE_ACTION_MODIFY, "order": ticket, "symbol": o.symbol,
           "price": round(m.price, info.digits), "sl": round(m.sl, info.digits), "tp": round(m.tp, info.digits),
           **time_fields(m.expiration_mode, m.expiration)}
    if kind.endswith("STOP_LIMIT"):
        req["stoplimit"] = round(m.stoplimit, info.digits)
    return result_dict(call(mt5.order_send, req))


@app.delete("/orders/{ticket}", dependencies=[Depends(auth)])
def delete_order(ticket: int):
    get_order(ticket)
    return result_dict(call(mt5.order_send, {"action": mt5.TRADE_ACTION_REMOVE, "order": ticket}))


# ---------------- 历史成交 ----------------
@app.get("/history", dependencies=[Depends(auth)])
def history(days: int = 7):
    off = server_offset()
    now_srv = datetime.utcfromtimestamp(time.time() + off)
    deals = call(mt5.history_deals_get, now_srv - timedelta(days=max(1, min(days, 365))), now_srv + timedelta(days=1))
    out = []
    for d in deals or []:
        if d.type not in (mt5.DEAL_TYPE_BUY, mt5.DEAL_TYPE_SELL):
            continue  # 跳过入金等余额操作
        out.append({"ticket": str(d.ticket), "order": str(d.order), "position_id": str(d.position_id),
                    "time": d.time - off, "symbol": d.symbol,
                    "type": "BUY" if d.type == mt5.DEAL_TYPE_BUY else "SELL",
                    "entry": {mt5.DEAL_ENTRY_IN: "IN", mt5.DEAL_ENTRY_OUT: "OUT"}.get(d.entry, str(d.entry)),
                    "volume": d.volume, "price": d.price, "profit": d.profit,
                    "commission": d.commission, "swap": d.swap, "comment": d.comment})
    out.sort(key=lambda x: x["time"], reverse=True)
    return out


def resolve_tls():
    """读取 SSL_CERTFILE / SSL_KEYFILE（相对路径按 .env 所在目录解析）。都没设置 → 返回 (None, None)，保持纯 http。
    只设置了其中一个，或文件不存在 → 抛出 RuntimeError（不会悄悄退回 http）。"""
    cert = os.environ.get("SSL_CERTFILE", "").strip()
    key = os.environ.get("SSL_KEYFILE", "").strip()
    if not cert and not key:
        return None, None
    if not cert or not key:
        raise RuntimeError("SSL_CERTFILE 和 SSL_KEYFILE 必须同时设置（只设置了其中一个）")
    cert, key = (p if os.path.isabs(p) else os.path.join(BASE_DIR, p) for p in (cert, key))
    for label, p in (("SSL_CERTFILE", cert), ("SSL_KEYFILE", key)):
        if not os.path.isfile(p):
            raise RuntimeError(f"{label} 指向的文件不存在: {p}（先运行 mt5-bridge.exe --setup <服务器IP> 生成）")
    return cert, key


def cli():
    import argparse
    import uvicorn
    p = argparse.ArgumentParser(description="MT5 Bridge（FastAPI + MetaTrader5）")
    p.add_argument("--host", default=os.environ.get("HOST", "127.0.0.1"))
    p.add_argument("--port", type=int, default=int(os.environ.get("PORT", "8000")))
    p.add_argument("--make-cert", metavar="IP", help="生成自签名 HTTPS 证书（cert.pem / key.pem，写到 .env 旁边）并打印 SHA-256 指纹，然后退出")
    p.add_argument("--force", action="store_true", help="与 --make-cert / --setup 合用：覆盖已有的 cert.pem / key.pem（旧连接码和指纹随之失效）")
    p.add_argument("--setup", metavar="IP", help="一键配置：自动生成 API Key（.env 里为空才生成）和自签名证书（缺失才生成），写入 .env，并打印手机 App 用的“连接码”，然后退出")
    p.add_argument("--no-qr", action="store_true", help="与 --setup 合用：不打印二维码")
    args = p.parse_args()
    if args.setup:
        from certgen import run_setup  # 延迟导入：只有用到时才需要 cryptography
        return run_setup(args.setup, BASE_DIR, args.port, args.force, qr=not args.no_qr)
    if args.make_cert:
        from certgen import run_make_cert  # 延迟导入：只有用到时才需要 cryptography
        return run_make_cert(args.make_cert, BASE_DIR, args.force)
    ssl_cert, ssl_key = resolve_tls()
    scheme = "https" if ssl_cert else "http"
    print("=" * 56, flush=True)
    print(f" MT5 Bridge 服务 v{VERSION}", flush=True)
    print(f" 配置文件: {ENV_PATH} {'(已找到)' if os.path.exists(ENV_PATH) else '(未找到！请把 .env.example 复制为 .env 并填写)'}", flush=True)
    print(f" 监听地址: {scheme}://{args.host}:{args.port}   (健康检查 /health)", flush=True)
    if ssl_cert:
        print(f" HTTPS: 已启用（证书 {ssl_cert}）", flush=True)
        try:
            from certgen import cert_fingerprint
            fp = cert_fingerprint(ssl_cert)
            print(f" 证书 SHA-256 指纹: {fp}", flush=True)
            if API_KEY:
                from certgen import cert_hosts, format_base_url, make_connection_code, print_connection_code
                hosts = cert_hosts(ssl_cert)
                if hosts:
                    print("-" * 56, flush=True)
                    print_connection_code(make_connection_code(format_base_url(hosts[0], args.port), API_KEY, fp))
                    print("-" * 56, flush=True)
        except Exception:
            pass  # 没有 cryptography / 证书不是 PEM 单证书：只是不显示指纹 / 连接码，不影响启动
    elif args.host not in ("127.0.0.1", "localhost"):
        print(" 注意：已对外监听但未启用 HTTPS（API Key 明文传输）。建议：运行 mt5-bridge.exe --setup <服务器IP> 一键启用 HTTPS 并生成手机连接码", flush=True)
    if args.host not in ("127.0.0.1", "localhost"):
        print(" 注意：已对外监听，请务必设置强 API Key", flush=True)
    print_support_banner()
    print(" 按 Ctrl+C 停止", flush=True)
    print("=" * 56, flush=True)
    if not API_KEY:
        print("[错误] 未设置 BRIDGE_API_KEY（运行 mt5-bridge.exe --setup <服务器IP> 自动生成，或在 .env 中填写）", flush=True)
        return 2
    kw = {"ssl_certfile": ssl_cert, "ssl_keyfile": ssl_key} if ssl_cert else {}
    uvicorn.run(app, host=args.host, port=args.port, log_level="info", **kw)
    return 0


if __name__ == "__main__":
    try:
        code = cli()
    except Exception as e:  # 双击运行时让窗口停留，便于看错误
        print(f"[错误] {e}", flush=True)
        code = 1
    if code and getattr(sys, "frozen", False) and sys.stdin and sys.stdin.isatty():
        try:
            input("按回车键退出…")
        except Exception:
            pass
    sys.exit(code)
