package com.morris.mttpsl.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import com.morris.mttpsl.AppConfig
import com.morris.mttpsl.Platform
import com.morris.mttpsl.api.*

/**
 * 设置页的“草稿”：所有输入框和提示都放在这里，而不是放在设置页自己的 remember 里。
 * 原因：设置页在切换底部标签、旋转屏幕、配置被保存 / 记录证书时会被销毁重建；草稿由 Activity 持有，所以
 *  **任何输入框的内容都不会被自动清空或重置**（连接码导入成功后连接码仍保留在框里，出错时文字也保留，错误提示一直显示到用户再次编辑）。
 * 不依赖 Compose UI，可以直接单元测试（SettingsDraftTest）。
 */
class SettingsDraft(initial: AppConfig) {
    var c by mutableStateOf(initial)
    var codeText by mutableStateOf("")
    /** 连接码导入结果：以 ❌ 开头 = 错误，以 ✅ 开头 = 成功；只在用户再次编辑连接码框时清除 */
    var codeMsg by mutableStateOf<String?>(null)
    var keyHint by mutableStateOf<String?>(null)
    var showKey by mutableStateOf(false)
    var advanced by mutableStateOf(false)
    var saveError by mutableStateOf<String?>(null)
    var pinMsg by mutableStateOf<String?>(null)
    /** 测试连接的状态与结果（不随屏幕旋转保存，但切换标签不会丢） */
    var testing by mutableStateOf(false)
    var testResult by mutableStateOf<ConnTestResult?>(null)

    /** 用户编辑连接码框：只更新文字并清除旧的导入提示（文字本身绝不被改动） */
    fun editCode(text: String) { codeText = text; codeMsg = null }

    /** 导入连接码。成功：填好 地址 / API Key / 指纹，连接码文字【保留】；失败：只显示错误，已填的内容和连接码文字都不动。返回是否成功。 */
    fun importCode(text: String): Boolean = when (val r = parseConnectionCode(text)) {
        is ConnectionCodeResult.Ok -> {
            c = c.copy(serverUrl = r.info.serverUrl, apiKey = r.info.apiKey, certFingerprint = r.info.fingerprint, demoMode = false)
            saveError = null
            codeMsg = "✅ 已导入：地址 ${r.info.serverUrl}，API Key 和证书指纹已填好。请点下面的“保存”，再点“测试连接”"
            true
        }
        is ConnectionCodeResult.Error -> { codeMsg = "❌ ${r.message}"; false }
    }

    /** “从剪贴板粘贴并导入”：剪贴板有内容才覆盖连接码框（并导入）；为空则只提示，框里原有文字保留。 */
    fun pasteAndImport(clipboardText: String?): Boolean {
        if (clipboardText.isNullOrBlank()) { codeMsg = "❌ 剪贴板是空的：请先复制服务器上的连接码"; return false }
        codeText = clipboardText
        return importCode(clipboardText)
    }

    fun editServerUrl(v: String) { c = c.copy(serverUrl = v); saveError = null }

    fun generateKey(key: String = generateApiKey()) {
        c = c.copy(apiKey = key); showKey = true
        keyHint = "已生成随机密钥：请点“复制密钥”，并把它填进服务器 .env 的 BRIDGE_API_KEY=（否则连不上）"
    }

    /** 点“保存”：地址不是 https → 返回 null 并显示错误（输入框保持原样）；否则返回规范化后的配置（草稿本身不改动，用户输入的文字不会被改写）。 */
    fun requestSave(): AppConfig? {
        val v = httpsViolation(normalizeServerUrl(c.serverUrl))
        if (v != null) { saveError = "❌ $v：请把服务器地址改成 https://…（已拒绝保存）"; return null }
        saveError = null
        return c.copy(serverUrl = normalizeServerUrl(c.serverUrl), apiKey = c.apiKey.trim(), certFingerprint = formatFingerprint(c.certFingerprint))
    }

    /** 测试连接首次记录了证书（TOFU）：只在草稿里的地址与测试地址一致且还没有指纹时写入，绝不覆盖已有指纹。 */
    fun onLearned(testedUrl: String, fingerprint: String) {
        if (shouldPersistLearnedPin(c.serverUrl, testedUrl, c.certFingerprint)) {
            c = c.copy(certFingerprint = fingerprint)
            pinMsg = "✅ 已自动记录该服务器的证书指纹，之后只信任这张证书"
        }
    }

    /** 用户明确点“重置证书记录” */
    fun resetPin() { c = c.copy(certFingerprint = ""); pinMsg = "已重置证书记录：下次测试连接成功后会重新记录服务器当前的证书" }

    /** 已保存配置里的指纹被外部改动（后台首次连接自动记录 / 横幅上的重置按钮）时，同步到草稿的指纹框；只改指纹这一项。 */
    fun syncSavedFingerprint(savedUrl: String, savedFp: String) {
        if (savedFp == c.certFingerprint) return
        if (savedFp.isBlank() || shouldPersistLearnedPin(c.serverUrl, savedUrl, c.certFingerprint)) c = c.copy(certFingerprint = savedFp)
    }

    companion object {
        val Saver = listSaver<SettingsDraft, Any?>(
            save = { d -> listOf(d.c.platform.name, d.c.demoMode, d.c.serverUrl, d.c.apiKey, d.c.symbol, d.c.confirmBatchClose, d.c.certFingerprint,
                d.codeText, d.codeMsg, d.keyHint, d.showKey, d.advanced, d.saveError, d.pinMsg) },
            restore = { l ->
                SettingsDraft(AppConfig(Platform.entries.firstOrNull { it.name == l[0] } ?: Platform.MT5, l[1] as Boolean, l[2] as String, l[3] as String,
                    l[4] as String, l[5] as Boolean, l[6] as String)).apply {
                    codeText = l[7] as String; codeMsg = l[8] as String?; keyHint = l[9] as String?; showKey = l[10] as Boolean
                    advanced = l[11] as Boolean; saveError = l[12] as String?; pinMsg = l[13] as String?
                }
            })
    }
}
