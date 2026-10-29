package com.morris.mttpsl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.morris.mttpsl.AppConfig
import com.morris.mttpsl.CrashLog
import com.morris.mttpsl.Platform
import com.morris.mttpsl.api.ConnKind
import com.morris.mttpsl.api.ConnTestResult
import com.morris.mttpsl.api.ConnectionTester
import com.morris.mttpsl.api.httpsViolation
import com.morris.mttpsl.api.isHttpsUrl
import com.morris.mttpsl.api.normalizeFingerprint
import com.morris.mttpsl.api.normalizeServerUrl
import com.morris.mttpsl.api.summary
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * 设置页。所有输入都在 [SettingsDraft] 里（由 Activity 持有），所以切换标签 / 旋转 / 保存 / 记录证书都不会清空任何输入框。
 * 客户只需要填“服务器地址 + API Key”：第一次“测试连接”成功时会自动记录服务器的自签名证书（TOFU）。
 */
@Composable
fun SettingsScreen(
    draft: SettingsDraft,
    onSave: (AppConfig) -> Unit,
    onBack: () -> Unit,
    onConfirmBatchCloseChange: (Boolean) -> Unit = {},
    /** 把证书指纹立即写入已保存的配置（重置证书记录用，传空字符串 = 清除） */
    onPersistFingerprint: (String) -> Unit = {},
    /** 测试连接首次记录了证书：(测试用的地址, 指纹)；由外层决定是否写入已保存配置 */
    onLearned: (String, String) -> Unit = { _, _ -> },
) {
    var showAbout by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    if (showAbout) { AboutScreen(onBack = { showAbout = false }); return }
    val c = draft.c
    val scope = rememberCoroutineScope()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val urlNorm = normalizeServerUrl(c.serverUrl)
    val urlViolation = httpsViolation(urlNorm)
    Column(
        Modifier.fillMaxSize().background(Color(0xFF111418)).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("设置", color = Color.White, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { showAbout = true }) { Text("关于 / 支持项目") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("平台: ", color = Color.White)
            Platform.entries.forEach { p ->
                FilterChip(selected = c.platform == p, onClick = { draft.c = draft.c.copy(platform = p) },
                    label = { Text(p.name) }, modifier = Modifier.padding(end = 8.dp))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("演示模式（无需账户）", color = Color.White, modifier = Modifier.weight(1f))
            Switch(checked = c.demoMode, onCheckedChange = { draft.c = draft.c.copy(demoMode = it) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("一键平仓前确认", color = Color.White)
                Text("图表右上角“平买 / 平卖”按钮；关闭后点击将立即平仓，不再弹窗", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = c.confirmBatchClose, onCheckedChange = { draft.c = draft.c.copy(confirmBatchClose = it); onConfirmBatchCloseChange(it) })
        }
        // ---- 只需要这两项：服务器地址 + API Key ----
        Text("连接你的服务器", color = Color.White, style = MaterialTheme.typography.titleMedium)
        Text("填写服务器地址和 API Key 即可。自签名证书不需要任何设置：第一次“测试连接”成功时，App 会自动记住服务器的证书并从此固定它。", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(c.serverUrl, { draft.editServerUrl(it) }, label = { Text("MT5 桥接服务地址（https://IP:8000）") },
            placeholder = { Text("https://你的服务器IP:8000") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            isError = urlViolation != null,
            supportingText = { Text(urlViolation ?: "只支持 HTTPS；没写协议时按 https:// 处理") })
        OutlinedTextField(c.apiKey, { draft.c = draft.c.copy(apiKey = it) }, label = { Text("API Key") }, singleLine = true,
            visualTransformation = if (draft.showKey) VisualTransformation.None else PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
            supportingText = { Text(draft.keyHint ?: "必须和服务器 .env 里的 BRIDGE_API_KEY 完全一致") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { draft.generateKey() }) { Text("生成密钥") }
            OutlinedButton(enabled = c.apiKey.isNotBlank(), onClick = { clipboard.setText(AnnotatedString(c.apiKey.trim())); draft.keyHint = "已复制到剪贴板" }) { Text("复制密钥") }
            OutlinedButton(onClick = { draft.showKey = !draft.showKey }) { Text(if (draft.showKey) "隐藏" else "显示") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { draft.requestSave()?.let(onSave) }) { Text("保存") }
            OutlinedButton(onClick = onBack) { Text("返回") }
        }
        draft.saveError?.let { Text(it, color = Color(0xFFEF9A9A)) }
        Button(enabled = !draft.testing, onClick = {
            draft.testing = true; draft.testResult = null
            val url = c.serverUrl; val key = c.apiKey; val fp = c.certFingerprint
            scope.launch {
                val r = ConnectionTester().test(url, key, fp)
                if (r is ConnTestResult.Success && r.learnedFingerprint != null) {
                    val tested = normalizeServerUrl(url)
                    draft.onLearned(tested, r.learnedFingerprint)
                    onLearned(tested, r.learnedFingerprint)
                }
                draft.testResult = r; draft.testing = false
            }
        }) { Text(if (draft.testing) "测试中…" else "测试连接") }
        if (draft.testing) Text("正在请求 /health 和 /account …", color = Color.Gray)
        when (val r = draft.testResult) {
            is ConnTestResult.Success -> {
                Text(r.summary(), color = Color(0xFF81C784))
                if (r.learnedFingerprint != null) Text("🔒 已自动记录服务器证书（SHA-256 ${r.learnedFingerprint.take(23)}…），之后只信任这张证书。完整指纹见“高级”。", color = Color(0xFF81C784), fontSize = 12.sp)
                Text("/health: ${r.health}", color = Color.Gray, fontSize = 10.sp)
            }
            is ConnTestResult.Failure -> {
                Text("❌ ${r.issue.title}", color = Color(0xFFEF9A9A), style = MaterialTheme.typography.titleMedium)
                Text("失败步骤：${r.step}", color = Color(0xFFFFCDD2), fontSize = 12.sp)
                Text(r.issue.hint, color = Color(0xFFFFCDD2))
                if (r.issue.kind == ConnKind.TLS_PIN_MISMATCH)
                    OutlinedButton(onClick = { draft.resetPin(); onPersistFingerprint("") }) { Text("重置证书记录") }
                Text("技术详情（可截图）：\n${r.issue.raw}", color = Color.Gray, fontSize = 10.sp, lineHeight = 12.sp)
            }
            null -> {}
        }
        // ---- 高级（折叠）：已记录的证书指纹 + 导入连接码（都是可选的） ----
        val fpBad = c.certFingerprint.isNotBlank() && normalizeFingerprint(c.certFingerprint) == null
        TextButton(onClick = { draft.advanced = !draft.advanced }) {
            Text((if (draft.advanced) "▼ " else "▶ ") + "高级" + if (!draft.advanced && c.certFingerprint.isNotBlank()) "（已记录证书指纹）" else "")
        }
        if (draft.advanced) {
            OutlinedTextField(c.certFingerprint, { draft.c = draft.c.copy(certFingerprint = it) }, label = { Text("HTTPS 证书指纹 (SHA-256，已记录的证书)") },
                placeholder = { Text("留空即可：第一次测试连接成功后自动记录") }, isError = fpBad, singleLine = true, modifier = Modifier.fillMaxWidth(),
                supportingText = {
                    Text(when {
                        fpBad -> "格式不对：应为 64 位十六进制（可带冒号）"
                        c.certFingerprint.isNotBlank() && isHttpsUrl(urlNorm) -> "已启用证书固定：只信任指纹完全一致的服务器证书（指纹变了会拒绝连接）"
                        c.certFingerprint.isNotBlank() -> "指纹只对 https 地址生效"
                        else -> "尚未记录：自签名证书会在第一次测试连接成功时自动记录；正规证书（如 Let's Encrypt）不需要"
                    })
                })
            if (c.certFingerprint.isNotBlank())
                OutlinedButton(onClick = { draft.resetPin(); onPersistFingerprint("") }) { Text("重置证书记录") }
            draft.pinMsg?.let { Text(it, color = Color(0xFF81C784), fontSize = 12.sp) }
            Text("导入连接码（可选）", color = Color.White, style = MaterialTheme.typography.titleSmall)
            Text("如果服务器运行了 mt5-bridge.exe --setup，也可以粘贴它打印的连接码，一次填好地址、API Key 和证书指纹。连接码里含 API Key，请勿外传。输入框里的内容不会被自动清除。", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(draft.codeText, { draft.editCode(it) }, label = { Text("连接码（MT5PRO1:…）") }, maxLines = 3, modifier = Modifier.fillMaxWidth(),
                isError = draft.codeMsg?.startsWith("❌") == true,
                supportingText = draft.codeMsg?.let { m -> { Text(m, color = if (m.startsWith("❌")) Color(0xFFEF9A9A) else Color(0xFF81C784)) } })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { draft.pasteAndImport(clipboard.getText()?.text) }) { Text("从剪贴板粘贴并导入") }
                OutlinedButton(enabled = draft.codeText.isNotBlank(), onClick = { draft.importCode(draft.codeText) }) { Text("导入") }
            }
        }
        OutlinedTextField(c.symbol, { draft.c = draft.c.copy(symbol = it) }, label = { Text("品种") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("注意：这里不填写 MT5 登录名/密码。MT5 账户只配置在 Windows 服务器的 .env 中。", color = Color.Gray)
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
