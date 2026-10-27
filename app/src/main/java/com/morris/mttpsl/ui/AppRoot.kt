package com.morris.mttpsl.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.CandlestickChart
import androidx.compose.material.icons.filled.History
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.delay

private fun Tab.icon(): ImageVector = when (this) {
    Tab.QUOTES -> Icons.AutoMirrored.Filled.FormatListBulleted
    Tab.CHART -> Icons.Filled.CandlestickChart
    Tab.TRADE -> Icons.Filled.SwapVert
    Tab.HISTORY -> Icons.Filled.History
    Tab.SETTINGS -> Icons.Filled.Settings
}

@Composable
fun AppRoot(vm: MainViewModel, status: String, settings: @Composable () -> Unit) {
    val tab by vm.tab.collectAsState()
    val confirm by vm.confirm.collectAsState()
    val message by vm.message.collectAsState()
    val trade by vm.trade.collectAsState()
    val issue by vm.connIssue.collectAsState()
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let { snack.currentSnackbarData?.dismiss(); snack.showSnackbar(it, withDismissAction = true); vm.clearMessage() }
    }
    MaterialTheme(colorScheme = darkColorScheme()) {
        Scaffold(
            containerColor = Color(0xFF0E1013),
            snackbarHost = { SnackbarHost(snack) },
            bottomBar = {
                NavigationBar(containerColor = Color(0xFF15181D)) {
                    Tab.entries.forEach { t ->
                        val label: @Composable () -> Unit = if (t == Tab.TRADE && trade.loaded && trade.positions.isNotEmpty()) {
                            {
                                // 与 MT5 一样在“交易”上显示浮动盈亏
                                val pl = trade.floating
                                Text("%.2f USD".format(pl), color = Color.White, fontSize = 12.sp,
                                    modifier = Modifier.background(if (pl >= 0) Color(0xFF1E5631) else Color(0xFF6B2326), RoundedCornerShape(8.dp))
                                        .padding(horizontal = 6.dp))
                            }
                        } else ({ Text(t.label) })
                        NavigationBarItem(selected = tab == t, onClick = { vm.selectTab(t) },
                            icon = { Icon(t.icon(), t.label) }, label = label)
                    }
                }
            },
        ) { pad ->
            Column(Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad)) {
              issue?.let { i -> if (tab != Tab.SETTINGS) ConnectionBanner(i, onRetry = vm::retryConnection, onSettings = { vm.selectTab(Tab.SETTINGS) }) }
              Box(Modifier.fillMaxWidth().weight(1f)) {
                when (tab) {
                    Tab.QUOTES -> QuotesScreen(vm)
                    Tab.CHART -> ChartScreen(vm)
                    Tab.TRADE -> TradeScreen(vm)
                    Tab.HISTORY -> HistoryScreen(vm)
                    Tab.SETTINGS -> settings()
                }
              }
            }
        }
        confirm?.let { c ->
            AlertDialog(
                onDismissRequest = vm::dismissConfirm,
                title = { Text(c.title) },
                text = { Text(c.text + "\n\n$status") },
                confirmButton = { Button(onClick = vm::runConfirm) { Text(c.confirmLabel) } },
                dismissButton = { TextButton(onClick = vm::dismissConfirm) { Text("取消") } },
            )
        }
    }
}

/** 连接失败时显示在每个页面顶部的红色横幅：中文原因 + 原始异常小字 + 重试 / 去设置 */
@Composable
fun ConnectionBanner(issue: com.morris.mttpsl.api.ConnIssue, onRetry: () -> Unit, onSettings: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Color(0xFF5A1D20)).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text("⚠ ${issue.title}", color = Color.White, fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        Text(issue.hint, color = Color(0xFFFFCDD2), fontSize = 12.sp)
        Text(issue.raw, color = Color(0xFFBDBDBD), fontSize = 9.sp, lineHeight = 11.sp, maxLines = 4)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRetry, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)) { Text("重试") }
            OutlinedButton(onClick = onSettings, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)) { Text("去设置 / 测试连接") }
        }
    }
}
