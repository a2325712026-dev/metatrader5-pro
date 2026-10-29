# MT TP/SL 伴侣 App（原型）

## 目标
类 MetaTrader 的 Android 交易 App，核心新增功能：对已开仓位，**在当前市价位置生成 TP/SL 水平线**，上下拖动，松手即调用 `modifyPosition(tp, sl)` 修改订单（官方 MT App 只能从挂单线拖出）。

## 方案更新（当前）
已改为：用户自己的 Windows 云服务器 + 官方 MetaTrader5 Python 包 + FastAPI 桥接服务（见 `server/README.md`），不再使用 MetaApi，无第三方月费。App 中使用 `Mt5BridgeTradingApi`，默认 MT5 + 演示模式，服务器地址/API Key 在 App 设置中运行时填写。

## （旧）API 选择：MetaApi.cloud
- 同一套 API 支持 MT4 和 MT5，任意经纪商，无需成为券商：https://metaapi.cloud/docs/client/
- REST + WebSocket(socket.io)，支持实时报价/K线、持仓、下单、修改持仓 TP/SL（`POSITION_MODIFY`）：https://metaapi.cloud/docs/client/websocket/api/trade/
- 费用：常规订阅约 $30/月，7 天试用（仅一次），含 1 个 MT 账户：https://metaapi.cloud/ （另可能有按用量计费，以官网为准）
- 其他方案：MT5 Manager API 需要券商授权（个人不可用）；MetaTrader5 Python 包需在 Windows 上运行 MT5 终端（可自建 VPS 桥接）；自建 EA + 服务器桥接（免费但需自己维护）。

## 架构
Android (Kotlin + Jetpack Compose, Canvas 自绘 K 线) → **自建小后端**（保存 MetaApi token，转发请求）→ MetaApi → 经纪商 MT 服务器。
**严禁把 token 写进 APK。**

## 已完成
- `TradingApi` 接口 / `DemoTradingApi`（模拟数据，无需账号）/ `MetaApiTradingApi`（桩，走后端代理）
- K 线图 + 市价线 + “在市价生成 TP/SL 线”按钮 + 拖动 + 价格/距离(pips/points)标签 + 松手校验并修改
- 校验：BUY TP>现价、SL<现价；SELL 相反；满足最小止损距离 (stops level)
- 网页演示：`demo/index.html`，截图 `demo/screenshot.png`

## 构建
需要 JDK 17 + Android SDK (API 34)。用 Android Studio 打开本目录（会自动生成 Gradle Wrapper），或：
`gradle wrapper && ./gradlew assembleDebug`。原型尚未实际编译。

## 下一步
1. 确认 MT4/MT5、经纪商、模拟账户；2. 写后端代理；3. 接入 MetaApi 实时行情与持仓；4. 多仓位选择、市价单下单页、缩放/平移图表。


## 2026-10-19 迭代：MT5 风格界面
- 底部导航：报价 / 图表 / 交易 / 历史 / 设置（Material 图标，自绘图表，未使用任何 MetaQuotes 素材）
- 图表：黑底绿/红 K 线，周期 M1–D1，右侧价格刻度与价格标签，显示已有持仓/挂单线
- “+” 订单类型列表：市价预备单（拖动 TP/SL，自选 SL）、市价买入/卖出、Buy/Sell Limit、Buy/Sell Stop、Buy/Sell Stop Limit
- 挂单：可拖动开仓价线（TP/SL/限价随之平移）、限价线（Stop Limit）、TP/SL（SL 可关），按类型校验，有效期 GTC/当日/指定，确认对话框
- 交易页：余额/净值/预付款/可用预付款/预付款比例，持仓与挂单列表（修改/平仓/删除）
- 历史页：今天/7天/30天/90天成交与盈亏
- 演示模式支持以上全部功能（内存撮合，挂单到价触发）
- 网页演示：demo/index.html（截图 demo/screenshot-*.png）

## 2026-10-26 v9：图上直接拖动委托订单线
- 图表上已有挂单（Buy/Sell Limit、Buy/Sell Stop、Stop Limit）的开仓/触发价线、Stop Limit 限价线、以及挂单自带的 TP/SL 线都可直接上下拖动（触控区 ±20dp）；拖动时实时显示价格标签、轴上价签，TP/SL 标签显示预计盈亏（USD）。
- 松手后图表底部出现确认条“旧价 → 新价”（确认修改 / 取消），确认后才调用现有 `modifyOrder`（服务端 `POST /orders/{ticket}/modify`，服务端无改动）；取消 / 非法价格 → 线吸附回原价。
- 校验复用 `TpSlValidator`：Buy Limit < Ask、Sell Limit > Bid、Buy Stop > Ask、Sell Stop < Bid（含最小止损距离）；移动开仓价时 TP/SL 价格保持不变，若因此失效则提示中文错误且不发送。
- 手势优先级：✕ 平仓按钮 > TP/SL 编辑线 > 挂单线 > 平移/缩放；挂单线与持仓线冲突时更近者优先（点击持仓线仍弹平仓面板）；水平移动仍是平移。
- 逻辑在 `ui/OrderLines.kt`（纯函数，含测试 `OrderDragTest.kt`）；演示模式同样可用；网页演示 `demo/index.html` 已同步（`?view=v9confirm` / `v9invalid`）。

## 2026-10-27 v12：支持 HTTPS（自签名证书指纹固定）
- 服务端：`SSL_CERTFILE` / `SSL_KEYFILE`（`.env`）启用 TLS，未设置仍为 http；`mt5-bridge.exe --make-cert <IP>`（或 `server/make-cert.py <IP>`）生成约 10 年自签名证书（SAN 含该 IP）并打印 SHA-256 指纹。步骤见 `server/README.md` 4.1。
- App：地址可填 `https://…`（ws/wss 由 http/https 推导）；设置新增“HTTPS 证书指纹 (SHA-256)”。https + 指纹 → 只信任叶子证书 SHA-256 一致的服务器（REST 与 WebSocket 同一套，仅此模式放宽主机名校验）；https 无指纹 → 系统信任库 + 标准主机名校验；http 行为不变。永不关闭校验。
- 连接诊断新增中文提示：指纹不匹配 / 证书不受信任 / 证书与地址不匹配 / 证书过期 / 指纹格式错误 / 握手失败。逻辑在 `api/TlsPinning.kt`，测试 `TlsPinningTest.kt`（本地 HTTPS/WSS MockWebServer 端到端）。
- 版本 0.12（versionCode 12）。

## 2026-10-28 v13：只允许 HTTPS + 一键配置 / 连接码
- App：`http://` / `ws://` 地址一律拒绝，提示“必须使用 HTTPS 地址”（保存设置、测试连接、导入连接码、创建客户端、OkHttp 拦截器都检查；`normalizeServerUrl` 没写协议时默认 `https://`，不会悄悄把 http 改成 https）。清单 `usesCleartextTraffic="false"`；OkHttp 去掉 CLEARTEXT 连接规格并禁止 https→http 重定向。演示模式不受影响。旧版保存的 http 地址会提示错误并停在演示模式。
- 设置页：“导入连接码”（剪贴板粘贴 + 文本框）一次填好地址 / API Key / 指纹；“生成密钥”（256 位随机，需同时写入服务器 `.env`）；证书指纹放在可折叠“高级”里。解析 / 生成逻辑是纯函数：`api/ConnectionCode.kt`，测试 `ConnectionCodeTest.kt`、`HttpsOnlyTest.kt`。
- 连接码：`MT5PRO1:` + base64url(JSON `{u, k, f}`)；含 API Key，须保密。
- 服务端：`mt5-bridge.exe --setup <IP>`（或 `setup.bat` / `setup.py`）一步生成 API Key（为空才生成）+ 自签名证书（缺失才生成），写入 `.env`，打印连接码和控制台二维码（`miniqr.py`，纯 Python）；TLS 开启时正常启动也打印连接码。测试：`server/test_setup.py`（用真实 main.py + MetaTrader5 桩，连接码往返、指纹与 openssl 一致）。
- 没有编译期默认值：每位用户自己的服务器、自己的密钥；真实 IP / 指纹 / 密钥不进任何源码或文档。
- 版本 0.13（versionCode 13）。

## 2026-10-28 v14：首次连接自动记住证书（TOFU）+ 输入框永不自动清空 + 持仓 TP/SL 线可拖动
- 仍然只允许 HTTPS（`http://` / `ws://` 照旧拒绝）。
- 客户只需填**服务器地址 + API Key**。没有指纹且服务器出示自签名 / 不受信任证书时，第一次“测试连接”（以及首次真正连接）在 API Key 验证成功后，自动把该证书的 SHA-256 记入已保存配置并从此固定；设置“高级”里显示已记录的指纹。之后证书变了 → 拒绝连接，提示“服务器证书与已记录的不一致”，并给“重置证书记录”按钮（设置页、高级、顶部连接提示条都有）。已有指纹时绝不放宽固定；API Key 只会发给已固定的连接（先只握手读证书，不发任何请求）。逻辑在 `api/Tofu.kt`（`shouldLearnPin` / `CertProbe` 可注入）与 `ConnectionTester`，测试 `TofuTest.kt`。连接码导入和指纹输入框保留，但都是可选的（高级）。
- 设置页任何输入框都不会被程序自动清空：连接码导入成功后文本仍留在框里，出错时保留文本，错误一直显示到用户再次编辑；去掉了 `remember(initial)` 式的整页重置（以前保存任一开关会清掉所有未保存输入）。所有输入状态集中在 Activity 持有的 `SettingsDraft`（`ui/SettingsDraft.kt`），切换标签页 / 旋转 / 保存 / 记住证书都不会丢，测试 `SettingsDraftTest.kt`。
- 图表修复：已开仓持仓的 TP / SL 线（图上 “tp #id” / “sl #id”）上下拖动没反应。根因：这两条线只是只读装饰线（`StaticLine` 无 `positionId`），不在手势的命中集合里（命中集合只有 持仓开仓线 + 挂单线 + 新建/编辑订单时的编辑线），竖直滑动于是落到“平移”分支。现在它们和挂单线一样可拖（触点容差 24dp，竖直位移占优才算拖线，所以不会平移图表），松手后出现“修改持仓 止盈/止损 旧价 → 新价”确认条，确认才调用 `modifyPosition`（另一条 TP/SL 保持不变），取消则回到原价；价格按品种位数吸附，按买/卖方向和最小止损距离校验，不合法时线变红、松手提示中文错误且不发请求。逻辑：`PositionLineMoves`（`ui/PositionLines.kt`）、`MainViewModel.proposePositionMove / confirmPositionMove`；测试 `PositionLineDragTest.kt`。挂单线原本就可拖（走 `orderYs` 路径），未改动。
- 服务端代码未改动（v13 的 exe 包仍是当前服务端包）。版本 0.14（versionCode 14）。

## 2026-10-29 v15：部分平仓 + 拖动 SL/TP 实时盈亏标签 + MT5 风格按钮 + 连接稳定性
- 持仓支持部分平仓；服务端 `/close` 新增 `volume` 参数。
- 拖动 SL/TP 线时实时显示 USD / 点数；未启用的 SL/TP 按钮显示为灰色。
- 所有请求共用一个固定证书的 HTTP 客户端（BridgeHttp），带重试。
- 服务端：`/health` 返回 `version`（15），品种信息含 tick value / tick size，`--setup` 安装看门狗计划任务。版本 0.15（versionCode 15）。
