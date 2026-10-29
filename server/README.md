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
3. 编辑 `.env`：`BRIDGE_API_KEY` 可留空，之后运行 `python main.py --setup 服务器公网IP`（见 4.1）会自动生成并配好 HTTPS；也可自己填长随机串（可用 `python -c "import secrets;print(secrets.token_urlsafe(32))"` 生成）；MT5_LOGIN/MT5_PASSWORD 可留空（使用终端里已登录的账户），也可填写。**.env 不要提交、不要发给任何人。**
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

### 4.1 启用 HTTPS（一键配置 + 连接码，无需域名）/ One-step HTTPS setup
App v0.13 起**只支持 HTTPS**（`http://` 地址会被拒绝；v0.14 起首次连接自动记住证书）。每位用户在自己的服务器上运行一次 `--setup`，把打印出的**连接码**粘贴进 App 即可，不用手工处理证书和指纹：

1. **在服务器上运行**（IP 换成你服务器自己的公网 IP，不要写进任何文档 / 代码；下面的 `203.0.113.10` 只是示例）：
   - 打包版 exe：在 `mt5-bridge.exe` 所在文件夹（也是 `.env` 所在文件夹）运行 `.\mt5-bridge.exe --setup 203.0.113.10`，或双击文件夹里的 `setup.bat` 按提示输入 IP。
   - 源码版：`python main.py --setup 203.0.113.10`，或双击 `setup.bat`（`python setup.py [IP]`）。
   它会一步完成：① `.env` 里没有 `BRIDGE_API_KEY` 时生成强随机密钥（已有的保留；`.env.example` 里的占位文字会被替换）；② `cert.pem` / `key.pem` 不存在时为该 IP 生成约 10 年的自签名证书（已有的保留，`--force` 才重新生成）；③ 写入 / 更新 `.env`：`BRIDGE_API_KEY`、`SSL_CERTFILE=cert.pem`、`SSL_KEYFILE=key.pem`、`HOST=0.0.0.0`、`PORT`（`--port` 或 `.env` 里已有的，默认 8000），其他行与注释原样保留；④ 打印一条**连接码**（`MT5PRO1:…`）和控制台二维码（`--no-qr` 关闭）。**不会打印私钥。**可以重复运行，连接码不变。
2. **放行端口**（管理员 PowerShell，端口与 `PORT` 一致）：
   ```
   New-NetFirewallRule -DisplayName "MT5 Bridge HTTPS" -Direction Inbound -LocalPort 8000 -Protocol TCP -Action Allow
   ```
   并在云服务器安全组（如腾讯云：安全组 → 入站规则）添加 TCP 8000，来源建议限制为你自己的公网 IP。Windows 防火墙和云安全组两处都要放行。
3. **启动 / 重启 `mt5-bridge`**。窗口会显示 `监听地址: https://0.0.0.0:8000`、证书指纹，以及再次打印的连接码。服务器上自测：`curl.exe -k https://127.0.0.1:8000/health` 应返回 `{"ok":true}`。
4. **手机 App → 设置 → 填服务器地址和 API Key → 保存 → 测试连接。**只填这两项就够了：第一次测试连接成功时，App 会自动记住服务器的证书（无需手工填指纹）。也可以改用“导入连接码”一次填好（可选，在“高级”里）。

**连接码包含 API Key，等同于密码**：只发给你自己的手机，不要公开、不要放进群聊或截图。格式：`MT5PRO1:` + base64url(JSON `{"u": "https://IP:PORT", "k": "API Key", "f": "SHA-256 指纹"}`)。

**其他说明：**
- 仍可手动配置：在 App 里填 `https://IP:端口` 和 API Key，指纹放在“高级”里。`--make-cert <IP>`（或 `make-cert.py`）仍可单独使用，只生成证书并打印指纹。
- 启用 TLS 后该端口只接受 https。App 里的 `http://` 地址一律被拒绝（提示“必须使用 HTTPS 地址”）。
- 有域名和正规证书（如 Let’s Encrypt）时：把 `SSL_CERTFILE` / `SSL_KEYFILE` 指向 fullchain / privkey，App 里指纹留空（连接码里的指纹字段会是该证书的指纹，导入后可在“高级”里清空）。
- 如果 App 提示“服务器证书与已记录的不一致”，说明服务器出示的证书与 App 已记录的不同：确认是不是重新生成过证书（是的话，在 App 里点“重置证书记录”再测试连接即可重新记住）；没改过却不匹配时不要继续使用（可能被中途拦截）。
- 测试：`python -m unittest test_setup -v`（用真实 `main.py` 和 MetaTrader5 桩模块，验证连接码往返、指纹与 openssl 一致）。

**English summary:** run `mt5-bridge.exe --setup <server-ip>` (or `python main.py --setup <server-ip>` / double-click `setup.bat`) next to `.env`. It generates a strong `BRIDGE_API_KEY` (if empty), a self-signed certificate (if missing), updates `.env` (`SSL_CERTFILE`, `SSL_KEYFILE`, `HOST=0.0.0.0`, `PORT`) and prints one **connection code** (`MT5PRO1:` + base64url JSON `{u,k,f}`) plus a console QR code; the private key is never printed. Open the port in Windows Firewall and the cloud security group, start the bridge, then in the app use Settings → 导入连接码 and paste. The code contains the API key – keep it private. The app only accepts HTTPS.

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
App 右上角“设置”→ 关闭演示模式 → 填写服务地址（如 https://服务器IP:8000 或 https://your.domain.com）和 API Key → 保存 → 测试连接（自签名证书会在第一次测试成功时自动记住，指纹可选，在“高级”里查看 / 重置）。

## 7. 已验证（2026-10-19，Linux + WineHQ 11 + MT5 + Windows Python 3.11，MetaQuotes-Demo 模拟账户）
/health、/account、/symbol、/candles、/positions、WS 报价、市价买入 0.01 → 修改 TP/SL → 平仓，全部 retcode 10009 成功。
注意事项：
- MT5 时间为经纪商服务器时间（MetaQuotes-Demo ≈ UTC+3），服务已自动换算为 UTC（可用 `MT5_SERVER_UTC_OFFSET_HOURS` 固定）。
- 终端重启后若未保存密码会弹出登录框；服务启动时用 .env 中的账户登录即可。
- “Algo Trading” 按钮必须为绿色，否则下单返回 10027。
- 下单请求失败不会自动重发（避免重复成交）。
