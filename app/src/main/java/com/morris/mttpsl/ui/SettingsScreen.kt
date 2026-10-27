package com.morris.mttpsl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.morris.mttpsl.AppConfig
import com.morris.mttpsl.CrashLog
import com.morris.mttpsl.Platform
import com.morris.mttpsl.api.ConnTestResult
import com.morris.mttpsl.api.ConnectionTester
import com.morris.mttpsl.api.normalizeServerUrl
import com.morris.mttpsl.api.summary
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(initial: AppConfig, onSave: (AppConfig) -> Unit, onBack: () -> Unit, onConfirmBatchCloseChange: (Boolean) -> Unit = {}) {
    var c by remember(initial) { mutableStateOf(initial) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ConnTestResult?>(null) }
    val scope = rememberCoroutineScope()
    Column(
        Modifier.fillMaxSize().background(Color(0xFF111418)).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("设置", color = Color.White, style = MaterialTheme.typography.titleLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("平台: ", color = Color.White)
            Platform.entries.forEach { p ->
                FilterChip(selected = c.platform == p, onClick = { c = c.copy(platform = p) },
                    label = { Text(p.name) }, modifier = Modifier.padding(end = 8.dp))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("演示模式（无需账户）", color = Color.White, modifier = Modifier.weight(1f))
            Switch(checked = c.demoMode, onCheckedChange = { c = c.copy(demoMode = it) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("一键平仓前确认", color = Color.White)
                Text("图表右上角“平买 / 平卖”按钮；关闭后点击将立即平仓，不再弹窗", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = c.confirmBatchClose, onCheckedChange = { c = c.copy(confirmBatchClose = it); onConfirmBatchCloseChange(it) })
        }
        OutlinedTextField(c.serverUrl, { c = c.copy(serverUrl = it) }, label = { Text("MT5 桥接服务地址（http://IP:8000 或 https://… 都可以）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(c.apiKey, { c = c.copy(apiKey = it) }, label = { Text("API Key") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(c.symbol, { c = c.copy(symbol = it) }, label = { Text("品种") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("注意：这里不填写 MT5 登录名/密码。MT5 账户只配置在 Windows 服务器的 .env 中。", color = Color.Gray)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSave(c.copy(serverUrl = normalizeServerUrl(c.serverUrl), apiKey = c.apiKey.trim())) }) { Text("保存") }
            OutlinedButton(onClick = onBack) { Text("返回") }
        }
        Button(enabled = !testing, onClick = {
            testing = true; result = null
            scope.launch { result = ConnectionTester().test(c.serverUrl, c.apiKey); testing = false }
        }) { Text(if (testing) "测试中…" else "测试连接") }
        if (testing) Text("正在请求 /health 和 /account …", color = Color.Gray)
        when (val r = result) {
            is ConnTestResult.Success -> {
                Text(r.summary(), color = Color(0xFF81C784))
                Text("/health: ${r.health}", color = Color.Gray, fontSize = 10.sp)
            }
            is ConnTestResult.Failure -> {
                Text("❌ ${r.issue.title}", color = Color(0xFFEF9A9A), style = MaterialTheme.typography.titleMedium)
                Text("失败步骤：${r.step}", color = Color(0xFFFFCDD2), fontSize = 12.sp)
                Text(r.issue.hint, color = Color(0xFFFFCDD2))
                Text("技术详情（可截图）：\n${r.issue.raw}", color = Color.Gray, fontSize = 10.sp, lineHeight = 12.sp)
            }
            null -> {}
        }
        CrashLogSection()
        if (!c.demoMode && (c.serverUrl.isBlank() || c.apiKey.isBlank()))
            Text("未填写完整，将继续使用演示模式", color = Color(0xFFFFD54F))
    }
}

/** “查看上次崩溃日志”：显示 App 私有目录里最近一次闪退 / 已捕获异常的堆栈；可复制、清除（日志不含 API Key / 密码） */
@Composable
private fun CrashLogSection() {
    var show by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf<String?>(null) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    OutlinedButton(onClick = { text = CrashLog.dir?.let { CrashLog.last(it) }; show = true }) { Text("查看上次崩溃日志") }
    if (show) AlertDialog(
        onDismissRequest = { show = false },
        title = { Text("上次崩溃日志") },
        text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                Text(text ?: "没有崩溃记录 👍", fontSize = 10.sp, lineHeight = 12.sp)
            }
        },
        confirmButton = {
            Row {
                if (text != null) TextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(text!!)) }) { Text("复制") }
                if (text != null) TextButton(onClick = { CrashLog.dir?.let { CrashLog.clear(it) }; text = null }) { Text("清除") }
                TextButton(onClick = { show = false }) { Text("关闭") }
            }
        },
    )
}
