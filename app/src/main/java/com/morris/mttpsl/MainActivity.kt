package com.morris.mttpsl

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import com.morris.mttpsl.ui.AppRoot
import com.morris.mttpsl.ui.MainViewModel
import com.morris.mttpsl.ui.SettingsScreen
import com.morris.mttpsl.ui.Tab
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 边到边：让 Compose 的 imePadding 能拿到键盘高度（配合 manifest 的 adjustResize）
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        val repo = ConfigRepository(applicationContext)
        setContent {
            // 等待设置加载完成再创建数据源（之前用默认设置先建一个、加载后再建一个 → 两套独立的演示账户）
            val cfg by repo.config.collectAsState(initial = null)
            val vm: MainViewModel = viewModel() // 单一 ViewModel；数据源在 configure() 中按需切换
            val scope = rememberCoroutineScope()
            val c = cfg
            if (c == null) {
                Box(Modifier.fillMaxSize().background(Color.Black))
                return@setContent
            }
            LaunchedEffect(c.canUseLive, c.serverUrl, c.apiKey) {
                vm.configure(c.canUseLive, com.morris.mttpsl.api.normalizeServerUrl(c.serverUrl), c.apiKey, c.symbol)
            }
            LaunchedEffect(c.confirmBatchClose) { vm.setConfirmBatchClose(c.confirmBatchClose) }
            val status = "${c.platform} · ${if (c.canUseLive) "已连接 MT5 桥接服务" else "演示模式"}"
            AppRoot(vm, status) {
                SettingsScreen(c, onSave = { scope.launch { repo.save(it); vm.selectTab(Tab.CHART) } }, onBack = { vm.selectTab(Tab.CHART) },
                    onConfirmBatchCloseChange = { v -> vm.setConfirmBatchClose(v); scope.launch { repo.setConfirmBatchClose(v) } })
            }
        }
    }
}
