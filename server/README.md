# MT5 桥接服务（Windows 云服务器）

App ⇄ (HTTPS) ⇄ 本服务 (FastAPI) ⇄ MetaTrader5 Python 包 ⇄ 本机 MT5 终端 ⇄ MetaQuotes-Demo

## 1. 安装 MT5 终端
1. 从 https://www.metatrader5.com 下载安装 MT5（64 位）。
2. 打开终端 → 文件 → 登录交易账户，服务器选 **MetaQuotes-Demo**，登录你的模拟账户。
3. 工具 → 选项 → 智能交易：勾选 **允许算法交易**；工具栏 "Algo Trading" 按钮保持绿色。
4. 保持终端运行（服务会连接本机终端）。

## 2. 安装 Python 与依赖
1. 安装 Python 3.11/3.12（64 位，勾选 Add to PATH）。
2. 在本目录执行：
```
python -m venv .venv
.venv\Scripts\pip install -r requirements.txt
copy .env.example .env
```
3. 编辑 `.env`：`BRIDGE_API_KEY` 填长随机串（可用 `python -c "import secrets;print(secrets.token_urlsafe(32))"` 生成）；MT5_LOGIN/MT5_PASSWORD 可留空（使用终端里已登录的账户），也可填写。**.env 不要提交、不要发给任何人。**
4. 测试：`run.bat`，浏览器打开 http://127.0.0.1:8000/health 和 /docs。

## 3. 作为服务运行
**方式 A：NSSM**（https://nssm.cc）
```
nssm install MT5Bridge "C:\path\server\.venv\Scripts\python.exe" "C:\path\server\main.py"
nssm set MT5Bridge AppDirectory "C:\path\server"
nssm start MT5Bridge
```
注意：MT5 终端是桌面程序，Windows 服务在 Session 0 运行时可能无法启动/连接终端。若出现 `initialize failed`，请改用方式 B。

**方式 B：任务计划程序（推荐）**：创建任务 → 触发器“用户登录时” → 操作：启动 `run.bat` → 勾选“只在用户登录时运行”。配合 Windows 自动登录，MT5 终端也加入启动项。

## 4. 网络与安全
- 服务默认只监听 127.0.0.1。推荐用反向代理提供 HTTPS（Caddy 最简单：`caddy reverse-proxy --from your.domain.com --to 127.0.0.1:8000`，自动证书）。
- Windows 防火墙仅开放 443（`New-NetFirewallRule -DisplayName "HTTPS" -Direction Inbound -LocalPort 443 -Protocol TCP -Action Allow`），并在云厂商安全组中限制来源 IP（如可能）。
- 不要直接把 8000 端口以 HTTP 暴露到公网（API Key 会明文传输）。
- 所有请求需带 `X-API-Key` 头。

## 5. 接口
| 方法 | 路径 | 说明 |
|---|---|---|
| GET | /health | 健康检查（无需认证） |
| GET | /account | 账户信息 |
| GET | /symbol/{symbol} | digits / point / trade_stops_level 等 |
| GET | /candles?symbol=EURUSD&timeframe=1m&count=200 | copy_rates_from_pos，timeframe 可用 M1/M5/M15/M30/H1/H4/D1 |（可选 `start`：往回跳过的 K 线数，用于加载更早历史）
| GET | /positions | 持仓 |
| POST | /order | {symbol, side: BUY/SELL, volume, sl, tp, comment} → TRADE_ACTION_DEAL（tp/sl 随单发送，由 MT5 服务器端触发） |
| POST | /positions/{ticket}/modify | {tp, sl}（0=删除）→ TRADE_ACTION_SLTP，服务端二次校验 |
| POST | /positions/{ticket}/close | 平仓 |
| GET | /quotes?symbols=EURUSD,GBPUSD | 报价列表（bid/ask/点差/日涨跌%） |
| GET | /orders | 挂单列表 |
| GET | /margin?symbol=EURUSD&side=BUY&volume=0.01 | 预估预付款（order_calc_margin）+ 可用预付款 |
| POST | /pending | {symbol, type: BUY_LIMIT/SELL_LIMIT/BUY_STOP/SELL_STOP/BUY_STOP_LIMIT/SELL_STOP_LIMIT, volume, price, stoplimit, sl, tp, expiration_mode: GTC/DAY/SPECIFIED, expiration(UTC 秒)} |
| POST | /orders/{ticket}/modify | {price, stoplimit, sl, tp, expiration_mode, expiration} |
| DELETE | /orders/{ticket} | 删除挂单 |
| GET | /history?days=7 | 历史成交（history_deals_get，时间为 UTC） |
| WS | /ws/ticks?symbol=EURUSD | 实时报价（symbol_info_tick 轮询 200ms） |

## 6. App 设置
App 右上角“设置”→ 关闭演示模式 → 填写服务地址（如 https://your.domain.com）和 API Key → 保存。

## 7. 已验证（2026-10-19，Linux + WineHQ 11 + MT5 + Windows Python 3.11，MetaQuotes-Demo 模拟账户）
/health、/account、/symbol、/candles、/positions、WS 报价、市价买入 0.01 → 修改 TP/SL → 平仓，全部 retcode 10009 成功。
注意事项：
- MT5 时间为经纪商服务器时间（MetaQuotes-Demo ≈ UTC+3），服务已自动换算为 UTC（可用 `MT5_SERVER_UTC_OFFSET_HOURS` 固定）。
- 终端重启后若未保存密码会弹出登录框；服务启动时用 .env 中的账户登录即可。
- “Algo Trading” 按钮必须为绿色，否则下单返回 10027。
- 下单请求失败不会自动重发（避免重复成交）。
