package com.morris.mttpsl

import androidx.compose.runtime.saveable.SaverScope
import com.morris.mttpsl.api.*
import com.morris.mttpsl.ui.SettingsDraft
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** v0.14：设置页的输入框绝不自动清空 / 重置。 */
class SettingsDraftTest {
    private val url = "https://203.0.113.10:8000"                    // 文档专用地址
    private val key = "example-key-not-real-0123456789abcdefghijklmn"
    private val fp = (0 until 32).joinToString(":") { "%02X".format(it * 5 % 256) }
    private val code = encodeConnectionCode(url, key, fp)
    private fun draft() = SettingsDraft(AppConfig(demoMode = true, serverUrl = "", apiKey = "", symbol = "XAUUSD"))

    @Test fun successfulImportKeepsTheCodeTextAndFillsFields() {
        val d = draft(); d.editCode(code)
        assertTrue(d.importCode(d.codeText))
        assertEquals("code stays in the box", code, d.codeText)
        assertEquals(url, d.c.serverUrl); assertEquals(key, d.c.apiKey); assertEquals(fp, d.c.certFingerprint); assertFalse(d.c.demoMode)
        assertEquals("XAUUSD", d.c.symbol)
        assertTrue(d.codeMsg!!.startsWith("✅"))
        assertTrue(d.importCode(d.codeText)); assertEquals(code, d.codeText)          // 可重复导入，文字仍在
    }

    @Test fun errorKeepsTextAndFilledFieldsAndShowsUntilUserEdits() {
        val d = draft(); d.c = d.c.copy(serverUrl = "https://keep.me", apiKey = "keep-key")
        d.editCode("MT5PRO1:broken!!")
        assertFalse(d.importCode(d.codeText))
        assertEquals("MT5PRO1:broken!!", d.codeText); assertEquals("https://keep.me", d.c.serverUrl); assertEquals("keep-key", d.c.apiKey)
        val msg = d.codeMsg; assertTrue(msg!!.startsWith("❌"))
        // 其它操作（保存、生成密钥、重置指纹、同步）都不会清除错误提示
        d.requestSave(); d.generateKey("k2"); d.resetPin(); d.syncSavedFingerprint("https://keep.me", "")
        assertEquals(msg, d.codeMsg); assertEquals("MT5PRO1:broken!!", d.codeText)
        d.editCode("MT5PRO1:broken!!x")                                                // 用户再次编辑：文字按用户输入，旧提示才消失
        assertEquals("MT5PRO1:broken!!x", d.codeText); assertNull(d.codeMsg)
    }

    @Test fun httpCodeIsRejectedWithoutTouchingAnything() {
        val bad = encodeConnectionCode("http://203.0.113.10:8000", key, fp)
        val d = draft(); d.editCode(bad)
        assertFalse(d.importCode(bad)); assertEquals(bad, d.codeText); assertEquals("", d.c.serverUrl)
        assertTrue(d.codeMsg!!.contains("必须使用 HTTPS 地址"))
    }

    @Test fun pasteWithEmptyClipboardLeavesExistingTextAlone() {
        val d = draft(); d.editCode("whatever the user typed")
        assertFalse(d.pasteAndImport("")); assertFalse(d.pasteAndImport(null)); assertFalse(d.pasteAndImport("   \n"))
        assertEquals("whatever the user typed", d.codeText); assertTrue(d.codeMsg!!.contains("剪贴板"))
        assertTrue(d.pasteAndImport(code)); assertEquals(code, d.codeText)
    }

    @Test fun saveNeverRewritesWhatTheUserTyped() {
        val d = draft(); d.c = d.c.copy(serverUrl = "  203.0.113.10:8000/ ", apiKey = " key ", certFingerprint = fp.lowercase())
        val saved = d.requestSave()!!
        assertEquals("https://203.0.113.10:8000", saved.serverUrl); assertEquals("key", saved.apiKey); assertEquals(fp, saved.certFingerprint)
        assertEquals("  203.0.113.10:8000/ ", d.c.serverUrl); assertEquals(" key ", d.c.apiKey); assertEquals(fp.lowercase(), d.c.certFingerprint)
        d.c = d.c.copy(serverUrl = "http://203.0.113.10:8000")
        assertNull(d.requestSave()); assertEquals("http://203.0.113.10:8000", d.c.serverUrl)                  // http 被拒绝，输入保持原样
        assertTrue(d.saveError!!.contains("必须使用 HTTPS 地址"))
        d.editServerUrl("https://203.0.113.10:8000"); assertNull("error clears only when the user edits", d.saveError)
    }

    @Test fun learnedPinIsOnlyWrittenForSameServerAndNeverOverwrites() {
        val d = draft(); d.c = d.c.copy(serverUrl = url, apiKey = key)
        d.onLearned("https://other.example:8000", fp); assertEquals("", d.c.certFingerprint)
        d.onLearned(url, fp); assertEquals(fp, d.c.certFingerprint); assertEquals(url, d.c.serverUrl); assertEquals(key, d.c.apiKey)
        d.onLearned(url, "AA:" + fp.drop(3)); assertEquals("existing pin untouched", fp, d.c.certFingerprint)
    }

    @Test fun resetPinClearsOnlyTheFingerprintAndOnlyOnExplicitCall() {
        val d = draft(); d.importCode(code); d.editCode("typed")
        d.resetPin()
        assertEquals("", d.c.certFingerprint); assertEquals(url, d.c.serverUrl); assertEquals(key, d.c.apiKey); assertEquals("typed", d.codeText)
    }

    @Test fun savedFingerprintSyncOnlyTouchesTheFingerprint() {
        val d = draft(); d.c = d.c.copy(serverUrl = url, apiKey = "typed-but-unsaved")
        d.syncSavedFingerprint(url, fp)                                  // 后台首次连接记录了证书
        assertEquals(fp, d.c.certFingerprint); assertEquals("typed-but-unsaved", d.c.apiKey)
        d.syncSavedFingerprint(url, "")                                  // 横幅上“重置证书记录”
        assertEquals("", d.c.certFingerprint); assertEquals("typed-but-unsaved", d.c.apiKey)
        d.c = d.c.copy(certFingerprint = "AA".repeat(32))
        d.syncSavedFingerprint("https://other:1", fp); assertEquals("AA".repeat(32), d.c.certFingerprint) // 别的服务器 / 已有指纹：不覆盖
    }

    @Test fun draftSurvivesSaveRestoreRoundTrip() {
        val d = draft(); d.importCode(code); d.editCode(code + "\n"); d.importCode(d.codeText); d.advanced = true; d.showKey = true
        val scope = SaverScope { true }
        val saved = with(SettingsDraft.Saver) { scope.save(d)!! }
        val r = SettingsDraft.Saver.restore(saved)!!
        assertEquals(d.c, r.c); assertEquals(d.codeText, r.codeText); assertEquals(d.codeMsg, r.codeMsg); assertTrue(r.advanced); assertTrue(r.showKey)
    }

    @Test fun settingsSourceHasNoAutomaticFieldClearing() {
        val dir = listOf("src/main/java/com/morris/mttpsl/ui", "app/src/main/java/com/morris/mttpsl/ui").map { File(it) }.first { it.isDirectory }
        val screen = File(dir, "SettingsScreen.kt").readText(); val draft = File(dir, "SettingsDraft.kt").readText()
        assertFalse("no remember(initial) re-init (it wiped unsaved text whenever a setting was persisted)", screen.contains("remember(initial)"))
        assertFalse(screen.substringBefore("fun CrashLogSection").contains("remember {")) // 输入相关状态都在 SettingsDraft（由 Activity 持有）；崩溃日志弹窗除外
        for (src in listOf(screen, draft)) {
            assertFalse("codeText must never be blanked", Regex("codeText\\s*=\\s*\"\"").containsMatchIn(src))
            assertFalse(Regex("serverUrl\\s*=\\s*\"\"").containsMatchIn(src))
            assertFalse(Regex("apiKey\\s*=\\s*\"\"").containsMatchIn(src))
        }
    }
}
