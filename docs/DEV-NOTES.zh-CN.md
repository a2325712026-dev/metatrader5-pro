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
