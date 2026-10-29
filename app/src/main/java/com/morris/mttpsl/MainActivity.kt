package com.morris.mttpsl

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import com.morris.mttpsl.ui.AppRoot
import com.morris.mttpsl.ui.MainViewModel
import com.morris.mttpsl.ui.SettingsDraft
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
            LaunchedEffect(c.canUseLive, c.serverUrl, c.apiKey, c.certFingerprint) {
                vm.configure(c.canUseLive, com.morris.mttpsl.api.normalizeServerUrl(c.serverUrl), c.apiKey, c.symbol, certFingerprint = c.certFingerprint)
            }
            // 首次连接的 TOFU：已保存的配置是 https 且没有指纹时，在后台试一次；自签名证书 + API Key 有效 → 自动记录证书指纹（保存后上面的 configure 会用指纹重新连接）。
            // 已有指纹时 shouldLearnPin 恒为 false，不会放宽固定；指纹变化只会被拒绝，不会被自动更新。
            LaunchedEffect(c.canUseLive, c.serverUrl, c.apiKey, c.certFingerprint) {
                if (!c.canUseLive || c.certFingerprint.isNotBlank()) return@LaunchedEffect
                val r = com.morris.mttpsl.api.ConnectionTester().test(c.serverUrl, c.apiKey, "")
                val learned = (r as? com.morris.mttpsl.api.ConnTestResult.Success)?.learnedFingerprint ?: return@LaunchedEffect
                if (com.morris.mttpsl.api.shouldPersistLearnedPin(c.serverUrl, c.serverUrl, c.certFingerprint)) repo.setCertFingerprint(learned)
            }
            // 设置页草稿由 Activity 持有（旋转屏幕也保留）：切换标签 / 保存 / 记录证书都不会清空输入框
            val draft = rememberSaveable(saver = SettingsDraft.Saver) { SettingsDraft(c) }
            LaunchedEffect(c.certFingerprint) { draft.syncSavedFingerprint(c.serverUrl, c.certFingerprint) }
            LaunchedEffect(c.confirmBatchClose) { vm.setConfirmBatchClose(c.confirmBatchClose) }
            val status = "${c.platform} · ${if (c.canUseLive) "已连接 MT5 桥接服务" else "演示模式"}"
            AppRoot(vm, status, onResetPin = { draft.resetPin(); scope.launch { repo.setCertFingerprint("") } }) {
                SettingsScreen(draft, onSave = { scope.launch { repo.save(it); vm.selectTab(Tab.CHART) } }, onBack = { vm.selectTab(Tab.CHART) },
                    onConfirmBatchCloseChange = { v -> vm.setConfirmBatchClose(v); scope.launch { repo.setConfirmBatchClose(v) } },
                    onPersistFingerprint = { fp -> scope.launch { repo.setCertFingerprint(fp) } },
                    onLearned = { url, fp -> if (com.morris.mttpsl.api.shouldPersistLearnedPin(c.serverUrl, url, c.certFingerprint)) scope.launch { repo.setCertFingerprint(fp) } })
            }
        }
    }
}
