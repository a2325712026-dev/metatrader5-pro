# MT5 Android Trader（MT TP/SL）

[English](README.md) | **简体中文**

**💬 电报交流群：** https://t.me/metatrader5pro_chat_group  
**☕ 打赏地址（BSC / BEP20）：** `0xB5f7e22fa69b5D88eeCc238A767b582d18CFF2bF`

一个用 Kotlin + Jetpack Compose 编写的 Android 应用，复刻 MetaTrader 5 手机端的界面与操作，并加入官方手机端没有的两个功能：

- **可拖动的 TP/SL 线**：在图表上上下拖动止盈 / 止损线（适用于已有持仓、已有挂单和新订单），松手后应用修改。
- **市价预备单（market-price pre-order）**：从**当前市价**位置拉出 TP/SL 线，拖动 TP（必填）和 SL（可选）到想要的位置，再发送带 TP/SL 的市价买入 / 卖出单。

App 并不直接连接 MetaTrader，而是连接一个运行在 Windows 上、与 MT5 终端同机的小型 **Python FastAPI 桥接服务**，该服务通过官方 [`MetaTrader5`](https://pypi.org/project/MetaTrader5/) Python 包与终端通信。

> **说明：**App 界面文字和服务端控制台提示目前为中文。App 内置**演示模式**（模拟数据，无需服务器和账户），可直接体验。

> ⚠️ 交易有风险。连接真实账户前请阅读下面的[安全](#安全)与[风险声明](#风险声明)，并**先使用模拟账户**。

## 更新内容（v0.13 – v0.15）

- **v0.15**：**部分平仓**（可选择平仓手数；服务端 `/close` 支持 `volume` 参数）；拖动 SL/TP 线时**实时显示 USD / 点数标签**；**MT5 风格按钮**——未启用的 SL/TP 按钮显示为灰色；**共享的固定证书 HTTP 客户端并带重试**，连接更稳定；服务端 `/health` 返回 `version`，品种信息包含 **tick value / tick size**，`--setup` 会安装**看门狗计划任务**自动重启桥接服务。
- **v0.14**：**首次使用即信任（TOFU）证书固定**——只需填服务器地址和 API Key；连接码**带或不带 `MT5PRO1:` 前缀**均可；输入框**永不自动清空**；已开仓持仓的 TP/SL 线**可拖动**并带确认条。
- **v0.13**：**只允许 HTTPS**——无明文连接；服务器端一步 `--setup` 配置。

详见 [CHANGELOG.md](CHANGELOG.md)。

## 截图

下列图片来自浏览器演示页 [`demo/index.html`](demo/index.html)（模拟数据，是界面的网页版复现），不是 Android 应用本身的截图。

| 市价预备单面板 | 拖动挂单线 | 拖动后的确认条 |
|---|---|---|
| ![panel](demo/v11-panel-expanded.png) | ![drag](demo/v11-pending-drag-with-position.png) | ![confirm](demo/v9-confirm.png) |

## 功能

App（`app/`）：

- 底部导航：**报价 / 图表 / 交易 / 历史 / 设置**。
- 自绘 K 线图（Compose Canvas），周期 M1–D1，价格刻度，平移与缩放；持仓和挂单以线的形式显示在图上。
- 已有持仓的 **TP/SL 线可拖动**（发送前按品种最小止损距离校验）。
- **市价预备单**：从市价拉出 TP/SL → 拖动 → 确认 → 发送带 TP/SL 的市价单。
- 订单类型：市价买入/卖出、Buy/Sell Limit、Buy/Sell Stop、Buy/Sell Stop Limit；有效期 GTC / 当日 / 指定时间。
- **已有挂单**的开仓/触发价线、Stop Limit 限价线、TP/SL 线都可在图上拖动；松手后出现“旧价 → 新价”确认条，确认后才会修改订单。
- 在图表上平仓（有确认对话框）；对当前品种、指定方向“一键全部平仓”（确认对话框可在设置中关闭）。注意：服务端只能整仓平仓（见[已知限制](#已知限制)）。
- 交易页：余额、净值、预付款、可用预付款、预付款比例；持仓与挂单列表（修改 / 平仓 / 删除）。
- 历史页：今天 / 7 天 / 30 天 / 90 天的成交与盈亏。
- 通过 WebSocket 获取实时报价；带连接诊断（能解释 URL 格式错误、明文 HTTP 被拒绝 / 被拦截、API Key 错误、超时、TLS 指纹不匹配 / 证书不受信任等常见问题）和“测试连接”按钮。
- **只允许 HTTPS**（v0.13 起）：`http://` / `ws://` 地址会被拒绝（提示“必须使用 HTTPS 地址”），App 绝不以明文连接。一步配置：在服务器上运行 `--setup`，把**连接码**粘贴进 App（见 [HTTPS](#https一键配置--连接码)）；v0.14 起 App 在第一次测试连接成功时**自动记住自签名证书**（首次使用即信任，TOFU）并从此固定——客户只需填服务器地址和 API Key。
- 图表：已开仓持仓的 TP / SL 线和挂单线都可以上下拖动（触点容差 24dp，竖直拖动不会平移图表）；松手后出现“修改持仓 … 旧价 → 新价”确认条，确认前不会发送任何请求（v0.14 修复了持仓 TP/SL 线拖不动的问题）。
- 崩溃日志保存在 App 私有目录（可在设置中查看 / 清除），其中的 API Key / 密码 / token 会被抹掉。
- **演示模式**（默认）：内存中的模拟行情与撮合，无需账户和服务器。
- 针对纯逻辑的单元测试（`app/src/test`）。

服务端（`server/main.py`，FastAPI）：

- 通过 `X-API-Key` 请求头认证（常量时间比较）；WebSocket 也接受 `?key=` 参数。
- 报价、K 线、品种信息、账户信息、持仓、挂单、预付款估算、历史成交。
- 带 TP/SL 的市价单、修改持仓 TP/SL（服务端二次校验）、平仓、挂单的创建 / 修改 / 删除。
- 服务器时间 → UTC 换算（自动测算，或用 `MT5_SERVER_UTC_OFFSET_HOURS` 固定）。
- 一键配置 HTTPS：`mt5-bridge.exe --setup <ip>`（或双击 `server/setup.bat` / `setup.py`）自动生成强随机 API Key 和自签名证书、写入 `.env`，并打印**连接码**（外加控制台二维码）。另有单独的 `--make-cert <ip>` / `server/make-cert.py`。
- 下单失败**不会**自动重发（避免重复成交）。

## 架构

```mermaid
flowchart LR
    A["Android 应用<br/>Kotlin + Jetpack Compose"] -- "HTTP(S) REST + WebSocket<br/>X-API-Key 请求头" --> B["桥接服务<br/>Python FastAPI（Windows）"]
    B -- "MetaTrader5 Python 包" --> C["MT5 终端<br/>（Windows，已登录）"]
    C --> D["券商 / MetaQuotes-Demo 服务器"]
```

MetaTrader5 Python 包只能在 Windows 上使用，且同一台机器上必须运行着已登录的 MT5 终端。

## 环境要求

**服务端（Windows）**

- Windows 10/11 或 Windows Server（64 位），已安装 MetaTrader 5 终端、已登录，并开启“算法交易 / Algo Trading”（按钮为绿色），否则下单会被拒绝。
- Python 3.11 / 3.12（64 位）；或使用 PyInstaller 打包版（目标机器无需 Python）。
- Python 依赖见 [`server/requirements.txt`](server/requirements.txt)（`MetaTrader5`、`fastapi`、`uvicorn[standard]`、`python-dotenv`、`cryptography`（仅生成证书时用到））。

**Android 应用**

- Android 8.0+（`minSdk 26`，`targetSdk 34`）。
- 自行编译需要：JDK 17、Android SDK 平台 34、Gradle 8.9（随附的 Gradle Wrapper 会自动下载）、Android Gradle 插件 8.5.2、Kotlin 2.0.20。

## 运行服务端

完整说明见 [`server/README.md`](server/README.md)。摘要：

```bat
cd server
python -m venv .venv
.venv\Scripts\pip install -r requirements.txt
copy .env.example .env
:: 按下表编辑 .env，然后：
run.bat
```

浏览器打开 `http://127.0.0.1:8000/health` 和 `http://127.0.0.1:8000/docs` 确认服务已运行。如需开机自启，请用 Windows 任务计划程序（“只在用户登录时运行”，因为 MT5 终端是桌面程序），详见 `server/README.md`。

### 配置（`server/.env`）

| 变量 | 含义 |
|---|---|
| `BRIDGE_API_KEY` | **必填。**App 必须在 `X-API-Key` 头中携带的长随机串。生成：`python -c "import secrets;print(secrets.token_urlsafe(32))"` |
| `MT5_LOGIN` | 可选。MT5 账号；留空则使用终端中已登录的账户。 |
| `MT5_PASSWORD` | 可选。`MT5_LOGIN` 对应的密码。 |
| `MT5_SERVER` | 与 `MT5_LOGIN` 搭配使用的交易服务器名（默认 `MetaQuotes-Demo`）。 |
| `MT5_PATH` | 可选。有多个终端时，指定 `terminal64.exe` 的完整路径。 |
| `HOST` | 监听地址（默认 `127.0.0.1`；命令行 `--host` 优先）。 |
| `PORT` | 监听端口（默认 `8000`）。 |
| `SSL_CERTFILE` / `SSL_KEYFILE` | 可选。PEM 证书和私钥；**两项都填**才启用 HTTPS，都留空则为纯 HTTP。见 [HTTPS](#https裸-ip-自签名证书或正规证书)。 |
| `DEVIATION` | 市价单允许的最大价格偏差（点，默认 `20`）。 |
| `MAGIC` | 订单的 Magic Number（默认 `20261019`）。 |
| `MT5_SERVER_UTC_OFFSET_HOURS` | 券商服务器时间相对 UTC 的小时数；留空 = 自动测算。 |

`.env` 从 `main.py` 所在目录读取（PyInstaller 版则从 exe 同目录读取）。

### 接口

除 `/health` 外都需要 `X-API-Key` 请求头。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/health` | 健康检查（无需认证） |
| GET | `/account` | 账户信息 |
| GET | `/symbol/{symbol}` | digits / point / 最小止损距离 / 手数限制 |
| GET | `/candles?symbol=EURUSD&timeframe=1m&count=200[&start=N]` | K 线；周期 `M1 M5 M15 M30 H1 H4 D1`（也可写 `1m`…`1d`）；`start` 为往回跳过的 K 线数，用于加载更早历史 |
| GET | `/positions` | 持仓 |
| POST | `/order` | 市价单 `{symbol, side: BUY/SELL, volume, sl, tp, comment}`（TP/SL 随单发送） |
| POST | `/positions/{ticket}/modify` | `{tp, sl}`（0 = 删除），服务端二次校验 |
| POST | `/positions/{ticket}/close` | 整仓平仓 |
| GET | `/quotes?symbols=EURUSD,GBPUSD` | 报价列表（bid/ask/点差/日涨跌%） |
| GET | `/orders` | 挂单列表 |
| GET | `/margin?symbol=EURUSD&side=BUY&volume=0.01` | 预估预付款 + 可用预付款 |
| POST | `/pending` | 创建挂单（`BUY_LIMIT`、`SELL_LIMIT`、`BUY_STOP`、`SELL_STOP`、`BUY_STOP_LIMIT`、`SELL_STOP_LIMIT`；有效期 `GTC`/`DAY`/`SPECIFIED`） |
| POST | `/orders/{ticket}/modify` | 修改挂单（price、stoplimit、sl、tp、有效期） |
| DELETE | `/orders/{ticket}` | 删除挂单 |
| GET | `/history?days=7` | 历史成交（UTC 时间） |
| WS | `/ws/ticks?symbol=EURUSD` | 实时报价（服务端每 200 ms 轮询 `symbol_info_tick`） |

### 构建 Windows 可执行文件（PyInstaller 文件夹版）

文件夹版（`mt5-bridge.exe` + `_internal/`）比单文件版启动更快，目标机器无需安装 Python。请在 Windows 上构建：

```bat
cd server
python -m venv .venv
.venv\Scripts\pip install -r requirements.txt pyinstaller
.venv\Scripts\pyinstaller --noconfirm mt5-bridge.spec
```

产物：`server\dist\mt5-bridge\`，其中有 `mt5-bridge.exe` 和 `_internal` 文件夹。**两者必须放在一起**，exe 不能单独使用。把 `.env.example` 复制到该文件夹并改名为 `.env`、填写后，双击 `mt5-bridge.exe`（或加参数 `--host 0.0.0.0 --port 8000`）运行。spec 中 `upx=True`；若未安装 UPX，PyInstaller 会直接跳过。PyInstaller 程序有时会被杀毒软件 / SmartScreen 误报；如果不信任别人提供的二进制文件，请自行构建。

## HTTPS（一键配置 + 连接码）

从 v0.13 起 App **只使用 HTTPS**，没有明文后备。每位用户运行**自己的**桥接服务器、设置**自己的** API Key；不需要手工处理证书或指纹。三步：

1. **在服务器上**（装有 MT5 终端的 Windows），在 `.env` / `mt5-bridge.exe` 所在文件夹运行：
   ```bat
   mt5-bridge.exe --setup 203.0.113.10
   :: 从源码运行：python main.py --setup 203.0.113.10   （或双击 setup.bat）
   ```
   `203.0.113.10` 是仅用于文档的示例地址，请换成你服务器自己的公网 IP。这一步会：`.env` 里没有 `BRIDGE_API_KEY` 时自动生成强随机密钥（已有的保留）；`cert.pem` / `key.pem` 不存在时为该 IP 生成 10 年自签名证书（已有的保留，`--force` 才重新生成）；写入 / 更新 `.env`（`BRIDGE_API_KEY`、`SSL_CERTFILE`、`SSL_KEYFILE`、`HOST=0.0.0.0`、`PORT`，其他行原样保留）；最后打印一条**连接码**和控制台二维码（`--no-qr` 可关闭）。**绝不会打印私钥**。可以重复运行，打印的连接码不变；TLS 开启时每次正常启动也会再打印一次。
2. **放行端口**（TCP，默认 8000）：Windows 防火墙和云厂商安全组都要放行，然后启动桥接服务。
3. **在 App 中**：设置 → 填服务器地址和 API Key → 保存 → 测试连接。这样就够了：第一次测试连接成功时，App 会自动记录服务器证书的 SHA-256 并固定（v0.14+）。可选捷径：**高级**里的**导入连接码** → 粘贴 → 一次填好地址、密钥和指纹；粘贴的文本会一直留在框里。

连接码格式：`MT5PRO1:` + base64url（JSON `{"u": "https://<ip>:<port>", "k": "<api key>", "f": "<SHA-256 指纹>"}`）。**连接码里包含 API Key，等同于密码**，只发给你自己的手机，不要公开发布，也不要放进截图。

仍然可以手动配置：填 `https://` 地址和 API Key，指纹（仅自签名证书需要）放在可折叠的 **高级** 里。“生成密钥”按钮会填入随机的 256 位 API Key，之后必须把同一个值也写进服务器 `.env` 的 `BRIDGE_API_KEY=`。使用正规证书（如用 `SSL_CERTFILE` / `SSL_KEYFILE` 配 Let's Encrypt，或 Caddy 等反向代理）时指纹留空。

App 对 TLS 的处理：

- `https://` **且有指纹**：只接受叶子证书 SHA-256 与指纹完全一致的服务器证书（证书固定，REST 与 WebSocket 都适用）。其他证书一律不信任（包括 CA 签发的），主机名校验**仅在此固定模式下**放宽（指纹已唯一确定证书）。已过期的证书仍会被拒绝。
- `https://` **无指纹**：使用系统信任库和标准主机名校验。若服务器出示自签名 / 不受信任的证书，第一次“测试连接”（以及首次真正连接）在 **API Key 验证成功**后，会自动把该证书的 SHA-256 记入已保存配置（在**高级**里可见）并从此固定（首次使用即信任）。读取证书只做握手；API Key 只会发给已固定的连接。已保存的指纹（哪怕格式有误或不同）绝不会被替换。
- 之后固定的证书变了：App 拒绝连接并提示“服务器证书与已记录的不一致”，给出**重置证书记录**按钮（设置页、高级、顶部连接提示条都有）；重置后下一次成功连接会重新记录。只有你自己重新生成过服务器证书时才应重置。
- 设置里的任何输入框都不会被自动清空（导入成功、出错、保存、切换标签页、旋转屏幕都一样）；错误会一直显示到你再次编辑。
- `http://` 和 `ws://`：**一律拒绝**，提示“必须使用 HTTPS 地址”——保存设置时拒绝、测试按钮、导入连接码、创建客户端时都会检查。不写协议时 App 按 `https://` 处理，`wss://` 视为 `https://`。OkHttp 也去掉了明文连接规格、不跟随 https→http 重定向，清单中 `usesCleartextTraffic="false"`。演示模式（不连服务器）不受影响。
- 任何情况下都不会关闭证书校验。连接诊断会用中文解释指纹不匹配、证书不受信任 / 已过期、主机名不匹配和握手失败。

**从 v0.12 及更早版本升级：**已保存的 `http://` 服务器地址将不再可用（App 会提示错误并停留在演示模式）——请在服务器上运行 `--setup` 并导入连接码。

### 给分发者

每位用户需要自己的服务器并各自运行一次 `--setup`；没有编译期默认值，服务器地址和密钥也绝不会编进 APK。不要把真实 IP、API Key、连接码、`.env`、`*.pem` 或指纹提交到仓库，也不要写进 README / `gradle.properties`。

## 构建与安装 Android 应用

### 从源码构建

需要：JDK 17、Android SDK 34（在 `local.properties` 中设置 `sdk.dir`，或设置 `ANDROID_HOME`）。

```bash
# local.properties（不要提交）：  sdk.dir=/path/to/Android/sdk
./gradlew assembleRelease        # -> app/build/outputs/apk/release/app-release.apk
./gradlew assembleDebug          # 调试版
./gradlew testDebugUnitTest      # 单元测试
```

Windows 下使用 `gradlew.bat`。release 构建使用 **debug** 签名配置（见 `app/build.gradle.kts`），因此不需要自备密钥库，但这样的 APK 不能上架 Google Play。如需自己的发布密钥，请自行修改 `signingConfig`，并且不要提交密钥库文件。

### 安装预编译 APK

从本仓库 GitHub **Releases** 页面下载 `mt-tpsl-v15.apk`（版本 0.15）并安装到手机。该 APK 使用 debug 密钥签名（不是应用商店密钥），需要允许浏览器 / 文件管理器**安装未知来源应用**。如果手机上有用不同密钥签名的旧版本，可能需要先卸载。和任何来自网络的 APK 一样，请只在信任来源时安装，或自行编译。

### 在 App 中配置

打开**设置**：

1. 关闭**演示模式**（默认开启）。
2. 最省事：只填服务器地址和 API Key——第一次测试连接成功时证书会被自动记住（v0.14+）。可选：**高级**里的**导入连接码**——在服务器上运行 `mt5-bridge.exe --setup <你的服务器IP>`，复制它打印的连接码，粘贴到 App。
3. 或手动填写：**服务器地址** `https://<服务器IP>:8000`（IP 或域名 + 端口；只支持 HTTPS，`http://` 会被拒绝；不写协议时按 `https://` 处理）和 **API Key**（服务端 `BRIDGE_API_KEY` 的值；“生成密钥”会生成随机密钥，之后必须同时写进服务器 `.env`）。
4. **高级**（默认折叠）：已记录的 **HTTPS 证书指纹 (SHA-256)**（可选，自动填入，可重置）和可选的连接码导入。
5. 保存，然后点“测试连接”。

App 把服务器地址和 API Key 保存在应用私有存储（Jetpack DataStore）中；不会保存你的 MT5 账号和密码——它们只存在于服务端的 `.env` 里。

## 安全

- **只允许 HTTPS（v0.13 起）。**App 拒绝 `http://` / `ws://` 地址，Android 清单中 `usesCleartextTraffic="false"`；桥接服务本身在启用 TLS（运行 `--setup`）之前仍是明文 HTTP，所以启用前不要把端口暴露出去——纯 HTTP 的桥接服务 App 根本连不上。
- **连接码里包含 API Key。**请当作密码对待：只复制到你自己的手机，不要公开发布。拿到连接码的人可以控制所连账户。
- **自签名证书：**首次使用即信任：第一次成功连接时看到的证书会被一直信任，所以第一次测试请在可信网络下进行（或自己从服务器控制台复制指纹 / 连接码粘贴进来，这样一开始就固定了）。`key.pem` 是私钥，不要分享或提交。
- 也可以在前面加一层 HTTPS 反向代理（如 Caddy：`caddy reverse-proxy --from your.domain.com --to 127.0.0.1:8000`），桥接服务保持监听 `127.0.0.1`；或通过 Tailscale / ZeroTier 等 VPN / 组网工具访问。
- **用 IP 白名单限制来源：**在 Windows 防火墙和云厂商安全组中只放行你自己的 IP。
- `BRIDGE_API_KEY` 使用足够长的随机串。任何拿到密钥且能访问该服务的人都能在所连账户上开仓和平仓。
- **绝不要提交 `.env`**（已加入 `.gitignore`，仓库中只应有 `.env.example`），也不要发给任何人。不要把 API Key 或 MT5 凭据写进 APK 或源码。
- **先用模拟账户**，把所有流程测试清楚后再考虑实盘。

## 风险声明

外汇、差价合约等杠杆产品的交易风险很高，可能损失超过初始资金。本软件**不构成投资建议**，属于实验性质，可能存在导致意外下单或亏损的缺陷。使用本软件下的任何交易均由你自行负责。软件按“**原样**”提供，不附带任何形式的担保（见 [LICENSE](LICENSE)）。

本项目为独立作品，**与 MetaQuotes Ltd. 无任何关联，也未获其认可或赞助**。MetaTrader 和 MT5 是 MetaQuotes Ltd. 的商标。项目未使用任何 MetaQuotes 素材，界面为自行绘制、仅在外观上类似 MT5。

## 已知限制

- 服务端**不支持部分平仓**：`/positions/{ticket}/close` 总是平掉全部手数，所以 App 只能整仓平仓。
- 目前只实现了 MT5（配置中虽有 `MT4` 枚举值，但没有 MT4 桥接）。
- 仅在有限的设备 / 模拟器上、针对 MetaQuotes-Demo 账户测试过（服务端按 `server/README.md` 在 Linux + Wine + Windows 版 Python 3.11 上验证）；未在所有 Android 版本、屏幕尺寸和券商上测试。交易规则特殊的品种可能表现不同。
- 桥接服务只对应一个 MT5 终端 / 账户，没有用户管理，API Key 是唯一的保护。MetaTrader5 Python 包不是线程安全的，因此调用通过锁串行化。
- 服务必须运行在交互式 Windows 会话中（MT5 终端是桌面程序）；作为 Session 0 的 Windows 服务运行可能失败。
- 界面文字仅有中文（暂无多语言）。
- 预编译 release APK 使用 debug 签名密钥。

## 项目结构

```
app/        Android 应用（Kotlin、Jetpack Compose）及单元测试
server/     FastAPI 桥接服务（main.py）、certgen.py（含 miniqr.py 二维码）/ setup.py / setup.bat（一键配置）、make-cert.py（自签名证书）、test_setup.py、.env.example、run.bat、mt5-bridge.spec（PyInstaller）
demo/       界面的浏览器演示（index.html）及截图
docs/       历史开发笔记（中文）
```

## 许可证

[MIT](LICENSE) © 2026 Morris Wing
