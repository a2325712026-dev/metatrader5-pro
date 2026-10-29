package com.morris.mttpsl

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

enum class Platform { MT5, MT4 }

/** 运行时配置；不保存任何 MT 登录名/密码。默认：MT5 + 演示模式 */
data class AppConfig(
    val platform: Platform = Platform.MT5,
    val demoMode: Boolean = true,
    val serverUrl: String = "",
    val apiKey: String = "",
    val symbol: String = "EURUSD",
    /** 图表右上角“一键平仓”是否弹出确认对话框（默认开启） */
    val confirmBatchClose: Boolean = true,
    /** HTTPS 证书指纹（SHA-256，冒号分隔）；仅 https 地址使用，空 = 使用系统信任的证书 */
    val certFingerprint: String = "",
) {
    val canUseLive get() = !demoMode && serverUrl.isNotBlank() && apiKey.isNotBlank()
}

private val Context.store by preferencesDataStore("config")

class ConfigRepository(private val ds: DataStore<Preferences>) {
    constructor(ctx: Context) : this(ctx.store)

    private object K {
        val platform = stringPreferencesKey("platform"); val demo = stringPreferencesKey("demo")
        val url = stringPreferencesKey("url");
        val token = stringPreferencesKey("token"); val sym = stringPreferencesKey("sym")
        val confirmBatch = booleanPreferencesKey("confirm_batch_close")
        val certFp = stringPreferencesKey("cert_fp")
    }
    // 存储损坏 / 读取失败 / 未知枚举值都回退到默认配置，而不是让收集 Flow 的界面崩溃
    val config: Flow<AppConfig> = ds.data.catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }.map { p ->
        AppConfig(
            platform = p[K.platform]?.let { n -> Platform.entries.firstOrNull { it.name == n } } ?: Platform.MT5,
            demoMode = p[K.demo]?.toBoolean() ?: true,
            serverUrl = p[K.url] ?: "", apiKey = p[K.token] ?: "", symbol = p[K.sym] ?: "EURUSD",
            confirmBatchClose = p[K.confirmBatch] ?: true,
            certFingerprint = p[K.certFp] ?: "",
        )
    }
    suspend fun save(c: AppConfig) = ds.edit { p ->
        p[K.platform] = c.platform.name; p[K.demo] = c.demoMode.toString()
        p[K.url] = com.morris.mttpsl.api.normalizeServerUrl(c.serverUrl); p[K.token] = c.apiKey.trim(); p[K.sym] = c.symbol.trim().ifEmpty { "EURUSD" }
        p[K.confirmBatch] = c.confirmBatchClose
        p[K.certFp] = com.morris.mttpsl.api.formatFingerprint(c.certFingerprint)
    }

    /** 只更新证书指纹（TOFU 自动记录 / “重置证书记录”）：立即持久化，不动其他设置 */
    suspend fun setCertFingerprint(fp: String) { ds.edit { it[K.certFp] = com.morris.mttpsl.api.formatFingerprint(fp) } }

    /** 设置页开关：立即持久化，不必等到点“保存” */
    suspend fun setConfirmBatchClose(on: Boolean) { ds.edit { it[K.confirmBatch] = on } }
}
