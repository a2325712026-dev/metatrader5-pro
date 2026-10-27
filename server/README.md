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
- 服务默认只监听 127.0.0.1（`.env` 的 `HOST`，或命令行 `--host`）；让手机访问需要 `HOST=0.0.0.0`，**同时务必启用 HTTPS（见第 4.1 节）**，否则 API Key 与全部交易数据都是明文。
- 也可以用反向代理提供 HTTPS（有域名时 Caddy 最简单：`caddy reverse-proxy --from your.domain.com --to 127.0.0.1:8000`，自动证书），此时桥接服务保持 127.0.0.1、不设 SSL_*。
- Windows 防火墙只开放你实际使用的那一个端口，并在云厂商安全组里把来源限制为你自己的 IP（如可能）。
- 所有请求需带 `X-API-Key` 头；API Key 要足够长且随机。

### 4.1 启用 HTTPS（自签名证书 + 裸 IP，无需域名）/ Enable HTTPS (self-signed, bare IP)
桥接服务自带 TLS：`.env` 里设置 `SSL_CERTFILE` 和 `SSL_KEYFILE`（两项必须同时设置；都留空 = 仍是纯 http，行为不变；相对路径按 `.env` 所在目录解析）。App v0.12+ 支持 https，并可用“HTTPS 证书指纹 (SHA-256)”只信任这一张自签名证书（证书固定）。

**打包版 exe（无需安装 Python）— 在 Windows 服务器上：**
1. 先退出正在运行的 mt5-bridge。在 `mt5-bridge.exe` 所在文件夹（也就是 `.env` 所在文件夹）打开命令提示符 / PowerShell，运行（把 IP 换成服务器公网 IP，填你自己的，不要写进任何文档/代码）：
   ```
   .\mt5-bridge.exe --make-cert 203.0.113.10
   ```
   会在同目录生成 `cert.pem`（证书，有效期约 10 年，subjectAltName 含该 IP）和 `key.pem`（**私钥，不要外传**），并打印 **SHA-256 指纹**（形如 `AA:BB:…`，共 32 组）。把这一行复制保存好。已有证书时不会覆盖；确需重新生成请加 `--force`（之后 App 里的指纹必须同步更新）。
2. 编辑 `.env`，加入（或把已有的空项填上）：
   ```
   HOST=0.0.0.0
   PORT=8000
   SSL_CERTFILE=cert.pem
   SSL_KEYFILE=key.pem
   ```
3. 放行端口（管理员 PowerShell，端口与 `PORT` 一致）：
   ```
   New-NetFirewallRule -DisplayName "MT5 Bridge HTTPS" -Direction Inbound -LocalPort 8000 -Protocol TCP -Action Allow
   ```
   并在 **腾讯云控制台 → 云服务器 → 安全组 → 入站规则** 添加：协议 TCP、端口 8000（与 `PORT` 一致）、来源建议限制为你自己的公网 IP（不确定就先 0.0.0.0/0，之后再收紧）。Windows 防火墙和云安全组两处都要放行。
4. 重新启动 `mt5-bridge.exe`。窗口里应显示 `监听地址: https://0.0.0.0:8000`、`HTTPS: 已启用` 和 `证书 SHA-256 指纹: …`（与第 1 步打印的一致）。在服务器上自测：`curl.exe -k https://127.0.0.1:8000/health` 应返回 `{"ok":true}`。
5. 手机 App（v0.12+）→ 设置：服务器地址填 `https://服务器公网IP:8000`；**HTTPS 证书指纹 (SHA-256)** 粘贴第 1 步的指纹；API Key 不变；点“测试连接”。

**用 Python 运行（`run.bat` / NSSM）：** `.venv\Scripts\pip install -r requirements.txt`（含 `cryptography`），然后 `make-cert.bat 服务器公网IP`（或 `python make-cert.py 服务器公网IP [--out 目录] [--force]`），其余步骤相同。

**注意：**
- 启用 TLS 后这个端口只接受 https；旧版 App（v0.11 及以前）和 `http://` 地址将无法连接——请同时升级 App。
- 指纹只对 https 地址生效；留空 = 使用系统信任的证书。若你有域名和正规证书（如 Let’s Encrypt），把 `SSL_CERTFILE` / `SSL_KEYFILE` 指向 fullchain / privkey，App 里指纹留空即可。
- 如果 App 提示“HTTPS 证书指纹不匹配”，说明服务器出示的证书与设置里的不一致：确认是不是重新生成过证书；没改过却不匹配时不要继续使用（可能被中途拦截）。

**English summary:** run `mt5-bridge.exe --make-cert <server-ip>` (or `python make-cert.py <server-ip>`) next to `.env` → it writes `cert.pem` / `key.pem` (10-year self-signed cert with the IP as subjectAltName) and prints the SHA-256 fingerprint. Add `SSL_CERTFILE=cert.pem`, `SSL_KEYFILE=key.pem` (and `HOST=0.0.0.0`) to `.env`, open the port in Windows Firewall **and** the Tencent Cloud security group, restart, then in the app use `https://<ip>:<port>` and paste the fingerprint into “HTTPS 证书指纹 (SHA-256)”. With both variables unset the bridge stays plain http.

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
App 右上角“设置”→ 关闭演示模式 → 填写服务地址（如 https://服务器IP:8000 或 https://your.domain.com）和 API Key；自签名证书还要填“HTTPS 证书指纹 (SHA-256)” → 保存 → 测试连接。

## 7. 已验证（2026-10-19，Linux + WineHQ 11 + MT5 + Windows Python 3.11，MetaQuotes-Demo 模拟账户）
/health、/account、/symbol、/candles、/positions、WS 报价、市价买入 0.01 → 修改 TP/SL → 平仓，全部 retcode 10009 成功。
注意事项：
- MT5 时间为经纪商服务器时间（MetaQuotes-Demo ≈ UTC+3），服务已自动换算为 UTC（可用 `MT5_SERVER_UTC_OFFSET_HOURS` 固定）。
- 终端重启后若未保存密码会弹出登录框；服务启动时用 .env 中的账户登录即可。
- “Algo Trading” 按钮必须为绿色，否则下单返回 10027。
- 下单请求失败不会自动重发（避免重复成交）。
