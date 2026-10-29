package com.morris.mttpsl.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.morris.mttpsl.api.ExpirationMode
import com.morris.mttpsl.api.Side
import com.morris.mttpsl.domain.BatchClose
import com.morris.mttpsl.domain.ProfitCalc
import kotlin.math.abs

val Timeframes = listOf("M1", "M5", "M15", "M30", "H1", "H4", "D1")
private val TopBg = Color(0xFF000000)
private val PanelBg = Color(0xFF1E2228)
private val FieldBorder = Color(0xFF3A3F47)
private val Accent = Color(0xFF4C8DF6)
val BuyBlue = Color(0xFF2E7DF6)
val SellRed = Color(0xFFE5484D)
private val Muted = Color(0xFF8A9099)
/** 下单面板最多占可用高度的比例；超出部分在面板内滚动，“下单/修改”按钮固定在底部 */
const val PANEL_MAX_FRACTION = 0.72f

@Composable
fun ChartScreen(vm: MainViewModel) {
    val s by vm.chart.collectAsState()
    val trade by vm.trade.collectAsState()
    val live = remember { LiveLines() }
    val quoteList by vm.quotes.collectAsState()
    val mids = remember(quoteList) { quoteList.associate { it.symbol to (it.bid + it.ask) / 2 } }
    // 每价格单位每手的账户货币值：优先用服务器品种参数（tick_value / tick_size，与 MT5 一致），其次用持仓 profit 反推，最后按合约大小公式
    val calib = remember(trade.positions, s.symbol, s.spec) {
        s.spec?.let { ProfitCalc.unitValueFromSpec(it.tickValue, it.tickSize) } ?: ProfitCalc.calibrate(trade.positions, s.symbol)
            ?: s.spec?.let { sp -> ProfitCalc.unitValueFromContract(s.symbol, sp.contractSize, s.quote?.bid ?: 0.0, mids) }
    }
    val cur = trade.account?.currency ?: "USD"
    val pnl = PnlCtx(s, mids, calib, cur)
    // imePadding：键盘弹出时整页上移/收缩，输入框与“下单”按钮不被遮挡；panelMaxH：面板最多占 72% 可用高度，其余可滚动
    BoxWithConstraints(Modifier.fillMaxSize().background(TopBg).imePadding()) {
    val panelMaxH = maxHeight * PANEL_MAX_FRACTION
    Column(Modifier.fillMaxSize()) {
        ChartTopBar(s, trade, vm)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val spec = s.spec
            if (spec != null && s.candles.isNotEmpty()) {
                val pt = s.pendingType
                val lines = remember(s.linesActive, s.entry, s.stopLimit, s.tp, s.sl, s.tpOn, s.slOn, pt) {
                    listOf(
                        DragLine(LineId.ENTRY, s.entry, s.linesActive && pt != null, ChartColors.Entry,
                            pt?.let { if (it.isStopLimit) "${it.label} 触发" else it.label } ?: "开仓"),
                        DragLine(LineId.STOP_LIMIT, s.stopLimit, s.linesActive && pt?.isStopLimit == true, ChartColors.StopLimit, "限价"),
                        DragLine(LineId.TP, s.tp, s.linesActive && s.tpOn, ChartColors.Tp, "TP"),
                        DragLine(LineId.SL, s.sl, s.linesActive && s.slOn, ChartColors.Sl, "SL"),
                    )
                }
                val q = s.quote
                val statics = remember(trade.positions, s.symbol, s.mode, s.editPosition, cur, q, calib) {
                    buildList {
                        trade.positions.filter { it.symbol == s.symbol && it.id != s.editPosition?.id }.forEach { p ->
                            // 持仓线上的当前盈亏：随实时报价更新（平仓价 = BUY 用 Bid、SELL 用 Ask），无法计算时用服务器 profit
                            val livePl = q?.let { qq -> ProfitCalc.profit(p.symbol, p.side, p.volume, p.openPrice,
                                if (p.side == Side.BUY) qq.bid else qq.ask, mids, calib) } ?: p.profit
                            add(StaticLine(p.openPrice, if (p.side == Side.BUY) BuyBlue else SellRed,
                                PositionLines.label(p.copy(profit = livePl), cur), positionId = p.id))
                            if (s.mode == TicketMode.NONE) {
                                p.takeProfit?.let { add(StaticLine(it, ChartColors.Tp, "tp #${p.id}", posTicket = p.id, kind = LineId.TP)) }
                                p.stopLoss?.let { add(StaticLine(it, ChartColors.Sl, "sl #${p.id}", posTicket = p.id, kind = LineId.SL)) }
                            }
                        }
                    }
                }
                val chartOrders = remember(trade.orders, s.symbol, s.editOrder) {
                    trade.orders.filter { it.symbol == s.symbol && it.ticket != s.editOrder?.ticket }
                }
                val orderMove by vm.orderMove.collectAsState()
                val positionMove by vm.positionMove.collectAsState()
                TradingChart(
                    candles = s.candles, spec = spec, bid = s.quote?.bid, ask = s.quote?.ask, lines = lines,
                    side = s.effectiveSide, pendingMode = s.isPending, staticLines = statics,
                    onCommit = vm::commitLines, live = live,
                    viewportKey = s.symbol + "|" + s.timeframe, onNeedHistory = vm::loadOlderCandles,
                    pnlLabel = { price, liveEntry -> pnl.label(price, liveEntry, spec.point) },
                    onPositionTap = vm::openClosePanel,
                    orders = chartOrders, orderMove = orderMove?.takeIf { it.order.symbol == s.symbol },
                    onOrderMove = vm::proposeOrderMove, orderCheck = vm::checkOrderMove,
                    orderPnl = { side, vol, open, close -> ProfitCalc.pnlPoints(ProfitCalc.profit(s.symbol, side, vol, open, close, mids, calib), ProfitCalc.points(side, open, close, spec.point), cur) },
                    positionPnl = { id, price -> trade.positions.firstOrNull { it.id == id }?.let { p ->
                        ProfitCalc.pnlPoints(ProfitCalc.profit(p.symbol, p.side, p.volume, p.openPrice, price, mids, calib), ProfitCalc.points(p.side, p.openPrice, price, spec.point), cur) } },
                    positionMove = positionMove?.takeIf { it.position.symbol == s.symbol },
                    onPositionLineMove = vm::proposePositionMove, positionCheck = vm::checkPositionMove,
                )
                OrderMoveBar(vm, s, cur, mids, calib, Modifier.align(Alignment.BottomCenter))
                PositionMoveBar(vm, s, cur, mids, calib, Modifier.align(Alignment.BottomCenter))
            } else {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        }
        if (s.sheetVisible) OrderPanel(s, trade, live, vm, pnl, panelMaxH)
        else if (s.mode == TicketMode.PANEL) TypeStrip(s, vm)
    }
    }
    ClosePositionDialog(vm, trade, cur)
    BatchCloseDialog(vm, trade, cur)
}

/**
 * K 线上点击持仓线 / ✕ 弹出的平仓面板（MT5 风格）：标题 “平仓 #ticket buy 0.10 EURUSD”，手数可编辑
 * （默认全部手数，−/+ 按 volume_step 步进，按 volume_min / volume_step 校验），“平仓”确认按钮。
 */
@Composable
private fun ClosePositionDialog(vm: MainViewModel, trade: TradeState, cur: String) {
    val id by vm.closeId.collectAsState()
    val busy by vm.closeBusy.collectAsState()
    val p = id?.let { i -> trade.positions.firstOrNull { it.id == i } }
    // 持仓已不存在（已被平掉 / TP·SL 触发）→ 自动关闭面板
    LaunchedEffect(id, p == null, trade.loaded) { if (id != null && p == null && trade.loaded) vm.dismissClosePanel() }
    if (p == null) return
    val (vmin, vstep) = vm.closeVolumeLimits(p)
    // 只按 ticket 记忆：持仓刷新（手数/盈亏变化）不会清空用户输入
    var volText by remember(p.id) { mutableStateOf(ClosePlan.fmtVolume(p.volume, vstep)) }
    val (vol, err) = ClosePlan.validate(p, volText, vmin, vstep)
    val full = vol != null && ClosePlan.isFull(p, vol)
    AlertDialog(
        onDismissRequest = vm::dismissClosePanel,
        containerColor = PanelBg,
        title = { Text(ClosePlan.title(p), color = Color.White, fontSize = 18.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row {
                    Text(p.symbol, color = Accent, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("  ${p.side.name.lowercase()} ${PositionLines.lots(p.volume)}", color = if (p.side == Side.BUY) BuyBlue else SellRed,
                        fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
                Text("开仓价 ${p.openPrice}    现价 ${p.currentPrice}", color = Muted, fontSize = 13.sp)
                val pl = if (vol != null && !full) p.profit * vol / p.volume else p.profit
                Text("${if (full || vol == null) "浮动盈亏" else "本次平仓盈亏"} ${ProfitCalc.formatSigned(pl, cur)}", fontWeight = FontWeight.Bold, fontSize = 16.sp,
                    color = if (pl >= 0) BuyBlue else SellRed)
                Row(Modifier.fillMaxWidth().border(1.dp, if (err != null) SellRed else FieldBorder, RoundedCornerShape(8.dp)).padding(start = 6.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { volText = ClosePlan.step(p, volText, -1, vmin, vstep) }, enabled = !busy) { Text("−", color = Accent, fontSize = 20.sp) }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("平仓手数", color = Muted, fontSize = 11.sp)
                        BasicTextField(value = volText, onValueChange = { volText = it }, singleLine = true, enabled = !busy,
                            textStyle = TextStyle(color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp, textAlign = TextAlign.Center),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), cursorBrush = SolidColor(Accent),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                    }
                    TextButton(onClick = { volText = ClosePlan.step(p, volText, +1, vmin, vstep) }, enabled = !busy) { Text("+", color = Accent, fontSize = 20.sp) }
                }
                Text(err ?: if (full) "全部平仓" else "部分平仓：剩余 ${ClosePlan.fmtVolume(p.volume - (vol ?: 0.0), vstep)} 手",
                    color = if (err != null) SellRed else Muted, fontSize = 12.sp)
                Text("最小 ${ClosePlan.fmtVolume(vmin, vstep)} · 步长 ${ClosePlan.fmtVolume(vstep, vstep)}", color = Muted, fontSize = 11.sp)
            }
        },
        confirmButton = {
            Button(onClick = { vm.confirmClosePanel(volText) }, enabled = !busy && vol != null, shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SellRed), modifier = Modifier.height(46.dp)) {
                Text(if (busy) "平仓中…" else if (full) "平仓" else "部分平仓", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = vm::dismissClosePanel, enabled = !busy) { Text("取消", color = Muted) } },
    )
}

/**
 * 图上拖动挂单线松手后的确认条（图表底部）：旧价 → 新价，“确认修改” / “取消”。
 * 确认前不会调用服务器；取消后线吸附回原价。移动开仓价时同时显示 TP/SL 的预计盈亏（TP/SL 价格不变）。
 */
@Composable
private fun OrderMoveBar(vm: MainViewModel, s: ChartState, cur: String, mids: Map<String, Double>, calib: Double?, modifier: Modifier) {
    val p by vm.orderMove.collectAsState()
    val busy by vm.orderMoveBusy.collectAsState()
    val m = p?.takeIf { it.order.symbol == s.symbol } ?: return
    val o = m.moved
    val base = if (o.type.isStopLimit) (o.stopLimit ?: o.price) else o.price
    fun usd(price: Double?) = price?.let { ProfitCalc.format(ProfitCalc.profit(o.symbol, o.type.side, o.volume, base, it, mids, calib), cur) }
    Column(modifier.fillMaxWidth().padding(8.dp).background(Color(0xF0252A31), RoundedCornerShape(12.dp))
        .border(1.dp, ChartColors.Entry, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text("修改挂单", color = Muted, fontSize = 11.sp)
        Text(OrderLines.confirmText(m, s.spec), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        val tp = usd(o.takeProfit); val sl = usd(o.stopLoss)
        if (tp != null || sl != null) Text(
            listOfNotNull(tp?.let { "TP ${OrderLines.fmt(o.takeProfit, s.spec)} → $it" }, sl?.let { "SL ${OrderLines.fmt(o.stopLoss, s.spec)} → $it" }).joinToString("    "),
            color = Muted, fontSize = 11.sp)
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = vm::cancelOrderMove, enabled = !busy, modifier = Modifier.weight(1f).height(40.dp), shape = RoundedCornerShape(8.dp)) {
                Text("取消", color = Muted)
            }
            Button(onClick = vm::confirmOrderMove, enabled = !busy, modifier = Modifier.weight(1f).height(40.dp), shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                Text(if (busy) "修改中…" else "确认修改", fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** 图上拖动持仓 TP/SL 线松手后的确认条：旧价 → 新价 + 预计盈亏；确认前不调用服务器，取消后线回到原价 */
@Composable
private fun PositionMoveBar(vm: MainViewModel, s: ChartState, cur: String, mids: Map<String, Double>, calib: Double?, modifier: Modifier) {
    val p by vm.positionMove.collectAsState()
    val busy by vm.positionMoveBusy.collectAsState()
    val m = p?.takeIf { it.position.symbol == s.symbol } ?: return
    val pos = m.position
    val pnl = ProfitCalc.format(ProfitCalc.profit(pos.symbol, pos.side, pos.volume, pos.openPrice, m.newPrice, mids, calib), cur)
    Column(modifier.fillMaxWidth().padding(8.dp).background(Color(0xF0252A31), RoundedCornerShape(12.dp))
        .border(1.dp, if (m.kind == LineId.TP) ChartColors.Tp else ChartColors.Sl, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text("修改持仓 ${PositionLineMoves.kindName(m.kind)}", color = Muted, fontSize = 11.sp)
        Text(PositionLineMoves.confirmText(m, s.spec), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text("触及该价预计盈亏 $pnl", color = Muted, fontSize = 11.sp)
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = vm::cancelPositionMove, enabled = !busy, modifier = Modifier.weight(1f).height(40.dp), shape = RoundedCornerShape(8.dp)) {
                Text("取消", color = Muted)
            }
            Button(onClick = vm::confirmPositionMove, enabled = !busy, modifier = Modifier.weight(1f).height(40.dp), shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                Text(if (busy) "修改中…" else "确认修改", fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** 预计盈亏上下文（USD） */
class PnlCtx(val s: ChartState, val mids: Map<String, Double>, val calib: Double?, val currency: String) {
    fun at(price: Double, liveEntry: Double? = null): Double? {
        val st = if (liveEntry != null) s.copy(entry = liveEntry, stopLimit = if (s.pendingType?.isStopLimit == true) liveEntry else s.stopLimit) else s
        return st.pnlAt(price, mids, calib)
    }
    /** MT5 拖线标签数值：“+726.30 USD, 7263 points” */
    fun label(price: Double, liveEntry: Double?, point: Double): String {
        val st = if (liveEntry != null) s.copy(entry = liveEntry, stopLimit = if (s.pendingType?.isStopLimit == true) liveEntry else s.stopLimit) else s
        val e = st.pnlEntry
        val pts = if (e != null) ProfitCalc.points(st.effectiveSide, e, price, point) else 0L
        return ProfitCalc.pnlPoints(at(price, liveEntry), pts, currency)
    }
}

/** 椭圆交易按钮打开的横向订单类型标签行；点选后弹出该类型的下单窗口 */
@Composable
private fun TypeStrip(s: ChartState, vm: MainViewModel) {
    Column(Modifier.fillMaxWidth().background(PanelBg).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("选择订单类型", color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.Close, "关闭", tint = Muted, modifier = Modifier.size(24.dp).clickable { vm.closePanel() })
        }
        TabRow(s, vm)
    }
}

@Composable
private fun ChartTopBar(s: ChartState, trade: TradeState, vm: MainViewModel) {
    var tfMenu by remember { mutableStateOf(false) }
    val batchBusy by vm.batchBusy.collectAsState()
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(s.symbol, color = Accent, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(s.timeframe, color = Color.White, fontSize = 12.sp)
        }
        // 右上角一键平仓：卖出（红）在左、买入（蓝）在右，与右侧椭圆交易按钮一致
        BatchCloseButton(Side.SELL, BatchClose.select(trade.positions, s.symbol, Side.SELL),
            BatchClose.enabled(trade.positions, s.symbol, Side.SELL, batchBusy, trade.loaded)) { vm.requestBatchClose(Side.SELL) }
        Spacer(Modifier.width(4.dp))
        BatchCloseButton(Side.BUY, BatchClose.select(trade.positions, s.symbol, Side.BUY),
            BatchClose.enabled(trade.positions, s.symbol, Side.BUY, batchBusy, trade.loaded)) { vm.requestBatchClose(Side.BUY) }
        Spacer(Modifier.width(2.dp))
        Box {
            TextButton(onClick = { tfMenu = true }) { Text(s.timeframe, color = Color.White, fontWeight = FontWeight.Bold) }
            DropdownMenu(expanded = tfMenu, onDismissRequest = { tfMenu = false }) {
                Timeframes.forEach { tf -> DropdownMenuItem(text = { Text(tf) }, onClick = { tfMenu = false; vm.setTimeframe(tf) }) }
            }
        }
        Spacer(Modifier.width(8.dp))
        TradeOvalButton(onClick = { if (s.mode == TicketMode.PANEL) vm.closePanel() else vm.openPanel() })
    }
}

/** MT5 风格小标签：红=平卖、蓝=平买；第一行笔数，第二行浮动盈亏；无持仓时灰色且不可点 */
@Composable
fun BatchCloseButton(side: Side, selected: List<com.morris.mttpsl.api.Position>, enabled: Boolean, onClick: () -> Unit) {
    val sum = BatchClose.summary(selected)
    val bg = if (!enabled) Color(0xFF2A2F37) else if (side == Side.BUY) BuyBlue else SellRed
    val fg = if (enabled) Color.White else Color(0xFF5C626B)
    Column(
        Modifier.size(width = 54.dp, height = 34.dp).clip(RoundedCornerShape(6.dp)).background(bg).clickable(enabled = enabled, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Text(BatchClose.buttonTitle(side, sum.count), color = fg, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(BatchClose.buttonSub(sum), color = fg, fontSize = 10.sp, maxLines = 1)
    }
}

/** 一键平仓确认对话框：方向、笔数、总手数、总盈亏；数字取自弹窗时的 ticket 快照中仍存在的持仓 */
@Composable
private fun BatchCloseDialog(vm: MainViewModel, trade: TradeState, cur: String) {
    val req by vm.batchRequest.collectAsState()
    val r = req ?: return
    val items = trade.positions.filter { it.id in r.tickets && it.symbol == r.symbol && it.side == r.side }
    // 持仓已全部消失（如被 TP/SL 平掉）→ 自动关闭对话框
    LaunchedEffect(items.isEmpty(), trade.loaded) { if (items.isEmpty() && trade.loaded) vm.dismissBatchClose() }
    val sum = BatchClose.summary(items)
    AlertDialog(
        onDismissRequest = vm::dismissBatchClose,
        containerColor = PanelBg,
        title = { Text(BatchClose.dialogTitle(r.symbol, r.side), color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(BatchClose.dialogText(r.symbol, r.side, sum, cur), color = Color.White, fontSize = 14.sp)
                Text("服务器无批量接口，将逐笔平仓；如有失败会提示成功/失败笔数。", color = Muted, fontSize = 12.sp)
            }
        },
        confirmButton = {
            Button(onClick = vm::confirmBatchClose, enabled = items.isNotEmpty(), shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SellRed), modifier = Modifier.height(46.dp)) {
                Text("全部平仓", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = vm::dismissBatchClose) { Text("取消", color = Muted) } },
    )
}

/** 自绘的椭圆双色交易按钮（左红=卖，右蓝=买），不使用任何 MetaQuotes 素材 */
@Composable
fun TradeOvalButton(onClick: () -> Unit) {
    Canvas(Modifier.size(width = 52.dp, height = 30.dp).clip(RoundedCornerShape(15.dp)).clickable(onClick = onClick)) {
        val r = CornerRadius(size.height / 2, size.height / 2)
        clipRect(right = size.width / 2) { drawRoundRect(SellRed, cornerRadius = r) }
        clipRect(left = size.width / 2) { drawRoundRect(BuyBlue, cornerRadius = r) }
        drawLine(Color.White, Offset(size.width / 2, 5f), Offset(size.width / 2, size.height - 5f), 2f)
        val cy = size.height / 2; val t = size.height * 0.18f
        val down = Path().apply { moveTo(size.width * 0.25f - t, cy - t / 1.5f); lineTo(size.width * 0.25f + t, cy - t / 1.5f); lineTo(size.width * 0.25f, cy + t); close() }
        val up = Path().apply { moveTo(size.width * 0.75f - t, cy + t / 1.5f); lineTo(size.width * 0.75f + t, cy + t / 1.5f); lineTo(size.width * 0.75f, cy - t); close() }
        drawPath(down, Color.White); drawPath(up, Color.White)
    }
}

@Composable
private fun OrderPanel(s: ChartState, trade: TradeState, live: LiveLines, vm: MainViewModel, pnl: PnlCtx, maxH: androidx.compose.ui.unit.Dp) {
    val d = s.spec?.digits ?: 5
    fun f(v: Double?) = v?.let { "%.${d}f".format(it) } ?: "—"
    Column(
        Modifier.fillMaxWidth().heightIn(max = maxH).padding(horizontal = 6.dp).border(1.dp, Color(0xFF3A3F47), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)).background(PanelBg)
            .padding(horizontal = 14.dp).padding(bottom = 10.dp)
    ) {
        // 展开 / 收起开关：点击整行在两个状态间来回切换（状态由 ViewModel 记住，面板打开期间保持）
        ExpandToggleRow(s.expanded) { vm.toggleExpanded() }
        // 中间部分可滚动（小屏 / 键盘弹出时），底部的提示与“下单”按钮固定、始终可点
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PanelHeader(s, vm)
            when (s.mode) {
                TicketMode.PANEL -> Text("下单 · ${s.tab.label}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                TicketMode.MODIFY_POSITION -> Text("修改持仓 #${s.editPosition?.id}  ${s.editPosition?.side?.name?.lowercase()} ${s.editPosition?.volume}", color = Color.White)
                TicketMode.MODIFY_ORDER -> Text("修改挂单 #${s.editOrder?.ticket}  ${s.pendingType?.label} ${s.editOrder?.volume}", color = Color.White)
                TicketMode.NONE -> Unit
            }
            // 交易量：无论展开还是收起都直接可改（−/+ 步进 + 键盘输入）
            if (s.mode == TicketMode.PANEL) VolumeRow(s.lotsText, vm::setLotsText, { vm.stepLots(-1) }, { vm.stepLots(1) })
            if (s.expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { ExpandedFields(s, trade, live, vm, ::f, pnl) }
            } else {
                CompactSummary(s, live, vm, ::f, pnl)
            }
        }
        s.validationError()?.let { if (s.quote != null && (!s.isMarketExec)) Text("⚠ $it", color = Color(0xFFFFD54F), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
        Spacer(Modifier.height(8.dp))
        SubmitRow(s, vm)
    }
}

/** 面板顶部的拖动把手 + 明确的“收起 ▲ / 展开 ▼”切换（整行可点，高度 ≥ 40dp） */
@Composable
private fun ExpandToggleRow(expanded: Boolean, onToggle: () -> Unit) {
    Box(Modifier.fillMaxWidth().heightIn(min = 40.dp).clickable(onClick = onToggle), contentAlignment = Alignment.Center) {
        Box(Modifier.size(36.dp, 4.dp).background(Color(0xFF555B63), RoundedCornerShape(2.dp)))
        Text(PanelUi.toggleLabel(expanded), color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.CenterEnd).padding(start = 12.dp))
    }
}

/** 交易量：− [可直接键盘输入] + 手；数字键盘（含小数点） */
@Composable
private fun VolumeRow(text: String, onText: (String) -> Unit, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth().border(1.dp, FieldBorder, RoundedCornerShape(8.dp)).padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("交易量", color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Box(Modifier.size(44.dp).clickable(onClick = onMinus), contentAlignment = Alignment.Center) { Text("−", color = Accent, fontSize = 24.sp, fontWeight = FontWeight.Bold) }
        BasicTextField(value = text, onValueChange = onText, singleLine = true,
            textStyle = TextStyle(color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp, textAlign = TextAlign.Center),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = androidx.compose.ui.text.input.ImeAction.Done),
            cursorBrush = SolidColor(Accent),
            modifier = Modifier.width(76.dp).padding(vertical = 12.dp))
        Box(Modifier.size(44.dp).clickable(onClick = onPlus), contentAlignment = Alignment.Center) { Text("+", color = Accent, fontSize = 24.sp, fontWeight = FontWeight.Bold) }
        Text("手", color = Color.White, modifier = Modifier.padding(start = 2.dp, end = 10.dp))
    }
}

@Composable
private fun PanelHeader(s: ChartState, vm: MainViewModel) {
    val d = s.spec?.digits ?: 5
    var menu by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box {
            Row(Modifier.clickable { menu = true }, verticalAlignment = Alignment.CenterVertically) {
                Text(s.symbol, color = Accent, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Icon(Icons.Filled.UnfoldMore, "选择品种", tint = Accent)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                vm.watchlist.forEach { sym -> DropdownMenuItem(text = { Text(sym) }, onClick = { menu = false; vm.changeSymbol(sym) }) }
            }
        }
        Spacer(Modifier.weight(1f))
        Text(s.quote?.let { "%.${d}f".format(it.bid) } ?: "—", color = Accent, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(Modifier.width(12.dp))
        Text(s.quote?.let { "%.${d}f".format(it.ask) } ?: "—", color = Accent, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        IconButton(onClick = vm::closeSheet) { Icon(Icons.Filled.Close, "关闭", tint = Muted) }
    }
}

@Composable
private fun TabRow(s: ChartState, vm: MainViewModel) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        PanelTab.entries.forEach { t ->
            val sel = t == s.tab
            Text(t.label, color = if (sel) Accent else Color(0xFFC8CCD2), fontSize = 15.sp,
                fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.clickable { vm.selectPanelTab(t) }.padding(vertical = 4.dp))
        }
    }
}

/** MT5 风格输入框：左侧标签，右侧数值 + 上下步进 */
@Composable
private fun StepField(label: String, value: String, valueColor: Color = Color.White, onUp: () -> Unit, onDown: () -> Unit, onRemove: (() -> Unit)? = null, sub: String? = null) {
    Row(Modifier.fillMaxWidth().border(1.dp, FieldBorder, RoundedCornerShape(8.dp)).padding(start = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 15.sp)
        Text(sub?.let { "  预计 $it" } ?: "", color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(value, color = valueColor, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Column {
            Icon(Icons.Filled.KeyboardArrowUp, "增加", tint = Muted, modifier = Modifier.size(width = 40.dp, height = 26.dp).clickable(onClick = onUp))
            Icon(Icons.Filled.KeyboardArrowDown, "减少", tint = Muted, modifier = Modifier.size(width = 40.dp, height = 26.dp).clickable(onClick = onDown))
        }
        if (onRemove != null) Icon(Icons.Filled.Close, "移除", tint = Muted, modifier = Modifier.padding(end = 6.dp).size(28.dp).clickable(onClick = onRemove))
    }
}

/** 显示实时价格：拖动时只重组这一个 Text */
@Composable
private fun LiveValue(live: LiveLines, id: Int, committed: Double?, f: (Double?) -> String, pnl: PnlCtx?, color: Color) {
    val v = live.value(id, committed)
    Column {
        Text(f(v), color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        if (pnl != null && v != null) {
            val e = if (pnl.s.isPending) live.value(if (pnl.s.pendingType!!.isStopLimit) LineId.STOP_LIMIT else LineId.ENTRY,
                if (pnl.s.pendingType!!.isStopLimit) pnl.s.stopLimit else pnl.s.entry) else null
            val p = pnl.at(v, e)
            Text(ProfitCalc.format(p, pnl.currency), color = pnlTint(p), fontSize = 11.sp)
        }
    }
}

private fun pnlTint(v: Double?) = when { v == null -> Muted; v >= 0 -> Color(0xFF4C8DF6); else -> SellRed }

/** MT5 风格 TP/SL 开关按钮：点亮=启用（显示线与输入框），再点熄灭=移除 */
@Composable
fun LevelToggle(text: String, on: Boolean, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    // MT5 风格：未设置 = 灰色（无填充、灰边、灰字，表示未启用）；已设置 = 彩色填充（TP 绿 / SL 红）
    val st = levelToggleStyle(on)
    Box(modifier.height(36.dp).background(if (st.filled) color else Color.Transparent, RoundedCornerShape(18.dp))
        .border(1.dp, if (st.filled) color else InactiveGray.copy(alpha = 0.6f), RoundedCornerShape(18.dp)).clickable(onClick = onClick)
        .padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
        Text((if (on) "● " else "○ ") + text, color = if (st.filled) Color.White else InactiveGray, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, fontSize = 14.sp)
    }
}

private val InactiveGray = Color(0xFF8A9099)

/** TP/SL 按钮外观（纯逻辑，便于测试）：只有已设置 / 启用时才彩色填充 */
data class LevelToggleStyle(val filled: Boolean)
fun levelToggleStyle(on: Boolean) = LevelToggleStyle(filled = on)

@Composable
private fun TpSlToggles(s: ChartState, vm: MainViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        LevelToggle("TP 止盈", s.tpOn, ChartColors.Tp) { vm.setTpOn(!s.tpOn) }
        LevelToggle("SL 止损", s.slOn, ChartColors.Sl) { vm.setSlOn(!s.slOn) }
        if (s.isPrep && !s.tpOn) Text("需点亮 TP", color = Color(0xFFFFD54F), fontSize = 12.sp)
    }
}

@Composable
private fun ExpandedFields(s: ChartState, trade: TradeState, live: LiveLines, vm: MainViewModel, f: (Double?) -> String, pnl: PnlCtx) {
    if (s.mode == TicketMode.PANEL) {
        Slider(value = ((s.lots ?: 0.01).toFloat()).coerceIn(0.01f, 1f), onValueChange = { vm.setLots(Math.round(it * 100) / 100.0) },
            valueRange = 0.01f..1f, colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent))
        Row {
            Text("预付款 ", color = Accent, fontSize = 12.sp)
            Text("≈ ${s.margin?.let { "%.2f".format(it) } ?: "—"} ${trade.account?.currency ?: ""}", color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text("可用: ${trade.account?.freeMargin?.let { "%.2f".format(it) } ?: "—"} ${trade.account?.currency ?: ""}", color = Color.White, fontSize = 12.sp)
        }
    }
    if (s.isPrep) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SideToggle("买入", s.prepSide == Side.BUY, BuyBlue, Modifier.weight(1f)) { vm.setPrepSide(Side.BUY) }
            SideToggle("卖出", s.prepSide == Side.SELL, SellRed, Modifier.weight(1f)) { vm.setPrepSide(Side.SELL) }
        }
        Text("入场: 市价 ${f(s.entryRef)}（TP/SL 从市价线拉出）", color = Muted, fontSize = 12.sp)
    }
    if (s.isPending) {
        val pt = s.pendingType!!
        StepField(if (pt.isStopLimit) "触发价" else "价格", f(live.value(LineId.ENTRY, s.entry)), ChartColors.Entry,
            onUp = { vm.stepLine(LineId.ENTRY, 1) }, onDown = { vm.stepLine(LineId.ENTRY, -1) })
        if (pt.isStopLimit) StepField("限价", f(live.value(LineId.STOP_LIMIT, s.stopLimit)), ChartColors.StopLimit,
            onUp = { vm.stepLine(LineId.STOP_LIMIT, 1) }, onDown = { vm.stepLine(LineId.STOP_LIMIT, -1) })
    }
    if (!s.isMarketExec) {
        TpSlToggles(s, vm)
        if (s.tpOn) StepField("止盈", f(live.value(LineId.TP, s.tp)), ChartColors.Tp,
            onUp = { vm.stepLine(LineId.TP, 1) }, onDown = { vm.stepLine(LineId.TP, -1) },
            sub = s.tp?.let { ProfitCalc.format(pnl.at(it), pnl.currency) })
        if (s.slOn) StepField("止损", f(live.value(LineId.SL, s.sl)), ChartColors.Sl,
            onUp = { vm.stepLine(LineId.SL, 1) }, onDown = { vm.stepLine(LineId.SL, -1) },
            sub = s.sl?.let { ProfitCalc.format(pnl.at(it), pnl.currency) })
    } else {
        Text("如需在图上拖动设置 TP/SL，请使用 “市价预备单”", color = Muted, fontSize = 12.sp)
    }
    if (s.isPending) ExpiryRow(s, vm)
    if (s.mode == TicketMode.PANEL) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("评论: ", color = Color.White, fontSize = 14.sp)
            BasicTextField(value = s.comment, onValueChange = vm::setComment, singleLine = true,
                textStyle = TextStyle(color = Accent, fontSize = 14.sp), cursorBrush = SolidColor(Accent),
                modifier = Modifier.weight(1f), decorationBox = { inner ->
                    if (s.comment.isEmpty()) Text("添加注释", color = Accent, fontSize = 14.sp)
                    inner()
                })
        }
    }
}

@Composable
private fun ExpiryRow(s: ChartState, vm: MainViewModel) {
    var menu by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("超期: ", color = Color.White, fontSize = 14.sp)
        Box {
            Text(when (s.expMode) { ExpirationMode.GTC -> "直到取消"; ExpirationMode.DAY -> "今天"; ExpirationMode.SPECIFIED -> "指定时间" } + " ⌄",
                color = Accent, fontSize = 14.sp, modifier = Modifier.clickable { menu = true })
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("直到取消") }, onClick = { menu = false; vm.setExpMode(ExpirationMode.GTC) })
                DropdownMenuItem(text = { Text("今天") }, onClick = { menu = false; vm.setExpMode(ExpirationMode.DAY) })
                DropdownMenuItem(text = { Text("指定时间") }, onClick = { menu = false; vm.setExpMode(ExpirationMode.SPECIFIED) })
            }
        }
        if (s.expMode == ExpirationMode.SPECIFIED) {
            TextButton(onClick = { vm.stepExpHours(-1) }) { Text("−") }
            Text("${s.expHours} 小时后", color = Color.White, fontSize = 13.sp)
            TextButton(onClick = { vm.stepExpHours(1) }) { Text("+") }
        }
    }
}

@Composable
private fun AddLevelButton(text: String, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(46.dp).background(Color(0xFF26364F), RoundedCornerShape(8.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center) { Text(text, color = Accent, fontSize = 15.sp) }
}

@Composable
private fun SideToggle(text: String, selected: Boolean, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.height(38.dp).background(if (selected) color else Color.Transparent, RoundedCornerShape(8.dp))
        .border(1.dp, color, RoundedCornerShape(8.dp)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, color = if (selected) Color.White else color, fontWeight = FontWeight.Bold)
    }
}

/** 收起模式：图表大部分可见；一行显示实时价格，拖动线时同步更新 */
@Composable
private fun CompactSummary(s: ChartState, live: LiveLines, vm: MainViewModel, f: (Double?) -> String, pnl: PnlCtx) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (s.isPrep) {
                SideToggle("买入", s.prepSide == Side.BUY, BuyBlue, Modifier.width(64.dp)) { vm.setPrepSide(Side.BUY) }
                SideToggle("卖出", s.prepSide == Side.SELL, SellRed, Modifier.width(64.dp)) { vm.setPrepSide(Side.SELL) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (s.isPending) Column { Text(if (s.pendingType!!.isStopLimit) "触发" else "价格", color = Muted, fontSize = 11.sp)
                LiveValue(live, LineId.ENTRY, s.entry, f, null, ChartColors.Entry) }
            if (s.pendingType?.isStopLimit == true) Column { Text("限价", color = Muted, fontSize = 11.sp)
                LiveValue(live, LineId.STOP_LIMIT, s.stopLimit, f, null, ChartColors.StopLimit) }
            if (s.tpOn) LiveValue(live, LineId.TP, s.tp, f, pnl, ChartColors.Tp)
            if (s.slOn) LiveValue(live, LineId.SL, s.sl, f, pnl, ChartColors.Sl)
        }
        if (!s.isMarketExec) TpSlToggles(s, vm)
    }
}

@Composable
private fun SubmitRow(s: ChartState, vm: MainViewModel) {
    val d = s.spec?.digits ?: 5
    when {
        s.isMarketExec -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { vm.marketExecute(Side.SELL) }, enabled = !s.busy, modifier = Modifier.weight(1f).height(58.dp),
                shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.buttonColors(containerColor = SellRed)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("卖出", fontSize = 16.sp); Text(s.quote?.let { "%.${d}f".format(it.bid) } ?: "—", fontSize = 13.sp)
                }
            }
            Button(onClick = { vm.marketExecute(Side.BUY) }, enabled = !s.busy, modifier = Modifier.weight(1f).height(58.dp),
                shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.buttonColors(containerColor = BuyBlue)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("买入", fontSize = 16.sp); Text(s.quote?.let { "%.${d}f".format(it.ask) } ?: "—", fontSize = 13.sp)
                }
            }
        }
        else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (s.mode == TicketMode.MODIFY_POSITION) {
                OutlinedButton(onClick = { s.editPosition?.let(vm::requestClose) }, shape = RoundedCornerShape(8.dp)) { Text("平仓", color = SellRed) }
            }
            if (s.mode == TicketMode.MODIFY_ORDER) {
                OutlinedButton(onClick = { s.editOrder?.let(vm::requestCancel) }, shape = RoundedCornerShape(8.dp)) { Text("删除", color = SellRed) }
            }
            OutlinedButton(onClick = vm::resetTicket, shape = RoundedCornerShape(8.dp)) { Text("重置") }
            val label = when {
                s.busy -> "提交中…"
                s.isPrep -> "下单 ${if (s.prepSide == Side.BUY) "买入" else "卖出"}（市价预备单）"
                s.mode == TicketMode.PANEL -> "下单 ${s.tab.label}"
                else -> "修改"
            }
            val ok = s.validationError() == null
            Button(onClick = vm::requestSubmit, enabled = !s.busy, modifier = Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (!ok) Color(0xFF4A4F57) else if (s.effectiveSide == Side.BUY) BuyBlue else SellRed)) {
                Text(label, fontSize = 15.sp, maxLines = 1)
            }
        }
    }
}
