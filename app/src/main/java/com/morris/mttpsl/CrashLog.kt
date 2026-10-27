package com.morris.mttpsl

import android.app.Application
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 崩溃（闪退）日志：未捕获异常的堆栈写入 App 私有目录 filesDir/crash/last_crash.txt（只保留最近几次），
 * 设置页“查看上次崩溃日志”可显示 / 复制 / 清除，便于下次定位问题。
 * 不记录任何凭据：API Key / 密码 / token 等字样后面的值会被抹掉（见 [redact]）。
 */
object CrashLog {
    const val FILE = "last_crash.txt"
    const val MAX_CHARS = 60_000
    private const val SEP = "\n========== 崩溃记录 ==========\n"

    /** 非致命异常（被捕获并上报的）也记到这里；install() 后可用 */
    @Volatile var dir: File? = null

    private val SECRET = Regex("""(?i)((?:x-)?api[-_ ]?key|password|passwd|pwd|token|secret|authorization)(["']?\s*[:=]\s*["']?)([^\s,;"'&]+)""")

    fun redact(text: String): String = SECRET.replace(text) { "${it.groupValues[1]}${it.groupValues[2]}***" }

    fun format(thread: String, t: Throwable, nowMs: Long = System.currentTimeMillis(), fatal: Boolean = true, version: String = ""): String {
        val sw = StringWriter(); t.printStackTrace(PrintWriter(sw))
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("Asia/Shanghai") }
        return redact("时间: ${fmt.format(Date(nowMs))}（UTC+8）  ${if (fatal) "致命(闪退)" else "已捕获(未闪退)"}  线程: $thread" +
            (if (version.isNotEmpty()) "  版本: $version" else "") + "\n$sw")
    }

    /** 追加一条记录；总长度超限时丢弃最旧的。返回写入的文件；任何 IO 错误都吞掉（崩溃处理器里不能再抛） */
    fun write(dir: File, entry: String): File? = try {
        dir.mkdirs()
        val f = File(dir, FILE)
        val old = if (f.exists()) f.readText() else ""
        var all = old + SEP + entry
        if (all.length > MAX_CHARS) all = all.takeLast(MAX_CHARS)
        f.writeText(all)
        f
    } catch (_: Throwable) { null }

    fun read(dir: File): String? = try {
        File(dir, FILE).takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
    } catch (_: Throwable) { null }

    /** 只取最后一条记录（“上次崩溃”） */
    fun last(dir: File): String? = read(dir)?.split(SEP.trim())?.map { it.trim() }?.lastOrNull { it.isNotEmpty() }

    fun clear(dir: File) { try { File(dir, FILE).delete() } catch (_: Throwable) {} }

    /** 记录一个被捕获的异常（不闪退）；未 install 时忽略 */
    fun record(t: Throwable, thread: String = Thread.currentThread().name) {
        val d = dir ?: return
        write(d, format(thread, t, fatal = false))
    }

    fun install(app: Application, version: String = "") {
        val d = File(app.filesDir, "crash"); dir = d
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { th, e ->
            write(d, format(th.name, e, version = version))
            prev?.uncaughtException(th, e)
        }
    }
}

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this, "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
    }
}
