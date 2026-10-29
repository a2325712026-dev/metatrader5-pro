package com.morris.mttpsl.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.morris.mttpsl.BuildConfig

/** 关于 / 支持项目：纯展示，不弹窗、不打扰。 */
object SupportInfo {
    const val TELEGRAM_URL = "https://t.me/metatrader5pro_chat_group"
    const val DONATION_ADDRESS = "0xB5f7e22fa69b5D88eeCc238A767b582d18CFF2bF"
    val CHAINS = listOf("BSC / BEP20", "Ethereum / ERC20", "Base")
    const val NOTE_ZH = "MT5 加强版是我利用业余时间独立开发维护的开源项目，完全免费。服务器测试、新功能开发和持续更新都需要投入时间和成本。如果这个工具帮你省了时间或让交易更顺手，欢迎请我喝杯咖啡，你的支持是项目持续更新的动力。"
    const val NOTE_EN = "MT5 Pro is a free, open-source project I build and maintain in my spare time. Server testing, new features and ongoing updates take real time and cost. If it saves you time or makes your trading smoother, consider buying me a coffee. Your support keeps the project going."
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    Column(
        Modifier.fillMaxSize().background(Color(0xFF111418)).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("关于 / 支持项目", color = Color.White, style = MaterialTheme.typography.titleLarge)
        Text("MT5 加强版  v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", color = Color.Gray)
        Text(SupportInfo.NOTE_ZH, color = Color.White, style = MaterialTheme.typography.bodyMedium)
        Text("打赏地址（USDT / ETH 等）", color = Color.White, style = MaterialTheme.typography.titleSmall)
        SelectionContainer {
            Text(SupportInfo.DONATION_ADDRESS, color = Color(0xFF81C784), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        }
        Text("支持的链：" + SupportInfo.CHAINS.joinToString("、"), color = Color.Gray, fontSize = 12.sp)
        OutlinedButton(onClick = {
            clipboard.setText(AnnotatedString(SupportInfo.DONATION_ADDRESS))
            Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show()
        }) { Text("复制地址") }
        Text("电报交流群", color = Color.White, style = MaterialTheme.typography.titleSmall)
        Button(onClick = {
            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SupportInfo.TELEGRAM_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { Toast.makeText(ctx, SupportInfo.TELEGRAM_URL, Toast.LENGTH_LONG).show() }
        }) { Text("打开 Telegram 群") }
        Text(SupportInfo.TELEGRAM_URL, color = Color.Gray, fontSize = 12.sp)
        OutlinedButton(onClick = onBack) { Text("返回设置") }
    }
}
