package com.morris.mttpsl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.morris.mttpsl.api.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Bg = Color(0xFF0E1013)
private val RowBg = Color(0xFF15181D)
private val Divider = Color(0xFF23272E)
private val Muted = Color(0xFF8A9099)
private val Up = Color(0xFF2E7DFF)
private val Down = Color(0xFFFF4D4F)
private fun pnlColor(v: Double) = if (v >= 0) Up else Down

@Composable
private fun SectionHeader(text: String) {
    Text(text, color = Muted, fontSize = 13.sp, modifier = Modifier.fillMaxWidth().background(Bg).padding(horizontal = 14.dp, vertical = 8.dp))
}

// ---------------- 报价 ----------------
@Composable
fun QuotesScreen(vm: MainViewModel) {
    val quotes by vm.quotes.collectAsState()
    var actionFor by remember { mutableStateOf<SymbolQuote?>(null) }
    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text("行情", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)) {
            Text("品种", color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1.2f))
            Text("Bid", color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text("Ask", color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
        }
        LazyColumn {
            items(quotes, key = { it.symbol }) { q ->
                QuoteRow(q) { actionFor = q }
                HorizontalDivider(color = Divider)
            }
        }
        if (quotes.isEmpty()) Text("加载中…", color = Muted, modifier = Modifier.padding(16.dp))
    }
    actionFor?.let { q ->
        AlertDialog(
            onDismissRequest = { actionFor = null },
            title = { Text(q.symbol) },
            text = { Text("Bid ${"%.${q.digits}f".format(q.bid)}   Ask ${"%.${q.digits}f".format(q.ask)}") },
            confirmButton = { Button(onClick = { actionFor = null; vm.openChart(q.symbol); vm.openPanel() }) { Text("交易") } },
            dismissButton = { TextButton(onClick = { actionFor = null; vm.openChart(q.symbol) }) { Text("图表") } },
        )
    }
}

@Composable
private fun QuoteRow(q: SymbolQuote, onClick: () -> Unit) {
    // 与上一次报价比较决定颜色（涨蓝跌红，类似 MT5）
    var prevBid by remember(q.symbol) { mutableDoubleStateOf(q.bid) }
    val color = when { q.bid > prevBid -> Up; q.bid < prevBid -> Down; else -> Color.White }
    SideEffect { prevBid = q.bid }
    Row(Modifier.fillMaxWidth().background(RowBg).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1.2f)) {
            Text(q.symbol, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text("%+.2f%%".format(q.changePct), color = if (q.changePct >= 0) Up else Down, fontSize = 11.sp)
            Text("点差 ${q.spreadPoints}", color = Muted, fontSize = 11.sp)
        }
        BigPrice(q.bid, q.digits, color, Modifier.weight(1f))
        BigPrice(q.ask, q.digits, color, Modifier.weight(1f))
    }
}

/** MT5 风格：最后两位大号显示 */
@Composable
private fun BigPrice(v: Double, digits: Int, color: Color, modifier: Modifier) {
    val s = "%.${digits}f".format(v)
    val cut = (s.length - if (digits >= 3) 3 else 2).coerceAtLeast(0)
    val big = if (digits >= 3) s.substring(cut, s.length - 1) else s.substring(cut)
    val tail = if (digits >= 3) s.takeLast(1) else ""
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        Text(s.substring(0, cut), color = color, fontSize = 14.sp)
        Text(big, color = color, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(tail, color = color, fontSize = 12.sp)
    }
}

// ---------------- 交易 ----------------
@Composable
fun TradeScreen(vm: MainViewModel) {
    val t by vm.trade.collectAsState()
    var posAction by remember { mutableStateOf<Position?>(null) }
    var ordAction by remember { mutableStateOf<PendingOrder?>(null) }
    LazyColumn(Modifier.fillMaxSize().background(Bg)) {
        item {
            val a = t.account
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                Text(if (a != null) "%.2f %s".format(a.profit, a.currency) else "—",
                    color = pnlColor(a?.profit ?: 0.0), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                AccountRow("余额", a?.balance); AccountRow("净值", a?.equity); AccountRow("预付款", a?.margin)
                AccountRow("可用预付款", a?.freeMargin)
                AccountRow("预付款比例 (%)", a?.marginLevel)
                if (a != null) Text("${a.login} · ${a.server} · 1:${a.leverage}", color = Muted, fontSize = 11.sp)
            }
        }
        t.error?.let { e ->
            item { Text("⚠ 部分数据获取失败（已保留上次数据）：\n$e", color = Color(0xFFFFD54F), fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().background(Color(0xFF3A2E12)).padding(10.dp)) }
        }
        item { SectionHeader("持仓 (${t.positions.size})") }
        if (t.positions.isEmpty()) item {
            Text(if (t.loaded) "无持仓" else "加载中…", color = Muted, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
        }
        items(t.positions, key = { "p" + it.id }) { p ->
            Row(Modifier.fillMaxWidth().background(RowBg).clickable { posAction = p }.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row {
                        Text(p.symbol, color = Color.White, fontWeight = FontWeight.Bold)
                        Text("  ${p.side.name.lowercase()} ${p.volume}", color = if (p.side == Side.BUY) Up else Down)
                    }
                    Text("${p.openPrice} → ${p.currentPrice}", color = Muted, fontSize = 12.sp)
                    Text("TP ${p.takeProfit ?: "-"}   SL ${p.stopLoss ?: "-"}", color = Muted, fontSize = 11.sp)
                }
                Text("%.2f USD".format(p.profit), color = pnlColor(p.profit), fontWeight = FontWeight.Bold)
            }
            HorizontalDivider(color = Divider)
        }
        item { SectionHeader("挂单 (${t.orders.size})") }
        if (t.orders.isEmpty()) item {
            Text(if (t.loaded) "无挂单" else "加载中…", color = Muted, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
        }
        items(t.orders, key = { "o" + it.ticket }) { o ->
            Row(Modifier.fillMaxWidth().background(RowBg).clickable { ordAction = o }.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Column(Modifier.weight(1f)) {
                    Row {
                        Text(o.symbol, color = Color.White, fontWeight = FontWeight.Bold)
                        Text("  ${o.type.label.lowercase()} ${o.volume}", color = if (o.type.side == Side.BUY) Up else Down)
                    }
                    Text("@ ${o.price}" + (o.stopLimit?.let { "  限价 $it" } ?: ""), color = Muted, fontSize = 12.sp)
                    Text("TP ${o.takeProfit ?: "-"}   SL ${o.stopLoss ?: "-"}   ${o.expiration.mode.name}", color = Muted, fontSize = 11.sp)
                }
                Text("#${o.ticket}", color = Muted, fontSize = 11.sp)
            }
            HorizontalDivider(color = Divider)
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
    posAction?.let { p ->
        AlertDialog(
            onDismissRequest = { posAction = null },
            title = { Text("${p.symbol} ${p.side} ${p.volume}") },
            text = { Text("#${p.id}\n开仓 ${p.openPrice}  现价 ${p.currentPrice}\n盈亏 %.2f USD".format(p.profit)) },
            confirmButton = { Button(onClick = { posAction = null; vm.editPosition(p) }) { Text("修改 TP/SL") } },
            dismissButton = { TextButton(onClick = { posAction = null; vm.requestClose(p) }) { Text("平仓", color = Down) } },
        )
    }
    ordAction?.let { o ->
        AlertDialog(
            onDismissRequest = { ordAction = null },
            title = { Text("${o.symbol} ${o.type.label} ${o.volume}") },
            text = { Text("#${o.ticket}\n价格 ${o.price}" + (o.stopLimit?.let { "\n限价 $it" } ?: "")) },
            confirmButton = { Button(onClick = { ordAction = null; vm.editOrder(o) }) { Text("修改") } },
            dismissButton = { TextButton(onClick = { ordAction = null; vm.requestCancel(o) }) { Text("删除", color = Down) } },
        )
    }
}

@Composable
private fun AccountRow(label: String, v: Double?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, color = Muted, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(v?.let { "%.2f".format(it) } ?: "—", color = Color.White, fontSize = 14.sp)
    }
}

// ---------------- 历史 ----------------
@Composable
fun HistoryScreen(vm: MainViewModel) {
    val h by vm.history.collectAsState()
    val fmt = remember { SimpleDateFormat("yyyy.MM.dd HH:mm:ss", Locale.US) } // 设备本地时区
    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(1 to "今天", 7 to "7天", 30 to "30天", 90 to "90天").forEach { (d, l) ->
                FilterChip(selected = h.days == d, onClick = { vm.loadHistory(d) }, label = { Text(l) })
            }
        }
        val closed = h.deals.filter { it.entry == "OUT" }
        val profit = closed.sumOf { it.profit + it.commission + it.swap }
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
            Text("成交 ${h.deals.size} 笔 · 平仓 ${closed.size} 笔", color = Muted, modifier = Modifier.weight(1f))
            Text("盈亏 %.2f USD".format(profit), color = pnlColor(profit), fontWeight = FontWeight.Bold)
        }
        if (h.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn {
            items(h.deals, key = { it.ticket }) { d ->
                Row(Modifier.fillMaxWidth().background(RowBg).padding(horizontal = 14.dp, vertical = 9.dp)) {
                    Column(Modifier.weight(1f)) {
                        Row {
                            Text(d.symbol, color = Color.White, fontWeight = FontWeight.Bold)
                            Text("  ${d.side.name.lowercase()} ${d.volume} ${if (d.entry == "IN") "开仓" else "平仓"}",
                                color = if (d.side == Side.BUY) Up else Down)
                        }
                        Text("${fmt.format(Date(d.time * 1000))}  @ ${d.price}", color = Muted, fontSize = 11.sp)
                    }
                    if (d.entry == "OUT") Text("%.2f USD".format(d.profit), color = pnlColor(d.profit), fontWeight = FontWeight.Bold)
                }
                HorizontalDivider(color = Divider)
            }
        }
        if (!h.loading && h.deals.isEmpty()) Text("暂无成交", color = Muted, modifier = Modifier.padding(16.dp))
    }
}
