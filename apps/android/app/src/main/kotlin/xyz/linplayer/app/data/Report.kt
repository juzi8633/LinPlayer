package xyz.linplayer.app.data

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.linplayer.app.core.Logs
import java.io.File

/**
 * 崩溃报告 / 问题反馈:经核心层 `system.sendReport`(脱敏 + 自建代理)发到开发者的 Telegram。
 * 口径和 PC 端 `Report.cs` 一致:崩溃**自动发**,不让用户描述(用户 2026-09-18:「让用户描述没用」)。
 *
 * ★ 不能照抄 PC 的「运行中」标记:安卓进程在后台被系统回收是常态,那样会满屏误报。
 *   这里只认两种真信号 —— JVM 未捕获异常(当场写文件),和系统记下的原生崩溃 / ANR(Android 11+)。
 */
object Report {
    private const val PREFS = "report"
    private const val SEEN = "exit_seen_ts"

    private fun crashFile(ctx: Context) = File(ctx.filesDir, "last-crash.txt")

    /** 在 Application.onCreate 里装,排在 Sentry 之后:先落盘,再交给它和系统。 */
    fun arm(ctx: Context) {
        val file = crashFile(ctx)
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { file.writeText("线程 ${t.name}\n" + Log.getStackTraceString(e)) }
            prev?.uncaughtException(t, e)
        }
    }

    /** 上次的崩溃现场;没有返回 null。读过即清,同一次崩溃只发一回。 */
    fun takePending(ctx: Context): String? {
        val exit = lastAbnormalExit(ctx)
        val f = crashFile(ctx)
        if (f.exists()) return runCatching { f.readText() }.getOrNull().also { f.delete() }
        return exit
    }

    /**
     * 原生崩溃(libmpv / 核心层)和 ANR:JVM 的处理器接不到,只有系统记得。
     * 第一次跑时只记下时间戳不发 —— 否则装上新版就把历史上的旧崩溃全报一遍。
     */
    private fun lastAbnormalExit(ctx: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val am = ctx.getSystemService(ActivityManager::class.java) ?: return null
        val info = runCatching { am.getHistoricalProcessExitReasons(ctx.packageName, 0, 1) }
            .getOrNull()?.firstOrNull() ?: return null
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = prefs.getLong(SEEN, -1)
        prefs.edit().putLong(SEEN, info.timestamp).apply()
        if (seen < 0 || info.timestamp <= seen) return null
        val kind = when (info.reason) {
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "原生层崩溃"
            ApplicationExitInfo.REASON_ANR -> "ANR(界面无响应)"
            ApplicationExitInfo.REASON_CRASH -> "JVM 崩溃"
            else -> return null
        }
        // ANR 的 trace 是文本;原生崩溃的是二进制 tombstone,读了也看不懂,只留描述
        val trace = if (info.reason == ApplicationExitInfo.REASON_ANR)
            runCatching { info.traceInputStream?.bufferedReader()?.use { it.readText().take(64 shl 10) } }.getOrNull()
        else null
        return "$kind:${info.description ?: "(系统没给描述)"}\n" + (trace ?: "")
    }

    /** 启动后调一次:上次崩了就自动发出去。返回是否发了(给调用方决定要不要提示)。 */
    suspend fun sendPending(ctx: Context, app: AppState): Boolean {
        val crash = withContext(Dispatchers.IO) { takePending(ctx) } ?: return false
        return runCatching { send(app, "crash", crash) }
            .onFailure { Logs.w("report", "上次崩溃的报告没发出去: ${it.message}") }  // 不打扰用户,Sentry 也有一份
            .isSuccess
    }

    /**
     * 设置页「发送日志给开发者」(手机和 TV 共用)。
     * 发不出去(CF 在部分地区连不上)就把**脱敏后**的报告放进剪贴板,让用户自己转。
     */
    suspend fun feedback(ctx: Context, app: AppState) {
        val log = withContext(Dispatchers.IO) { Logs.dump() }
        runCatching { call(app, "feedback", "", log, dry = false) }
            .onSuccess { app.toast("已发给开发者,谢谢", ToastKind.Ok) }
            .onFailure { e ->
                val copied = runCatching {
                    val text = (call(app, "feedback", "", log, dry = true) as JsonPrimitive).content
                    ctx.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("LinPlayer 报告", text))
                }.isSuccess
                app.toast((e.message ?: "发送失败") + if (copied) "。报告已复制,可以自己发给开发者" else "", ToastKind.Error)
            }
    }

    suspend fun send(app: AppState, kind: String, crash: String = "") {
        // dump 里要起 logcat 子进程读输出,不能在主线程
        call(app, kind, crash, withContext(Dispatchers.IO) { Logs.dump() }, dry = false)
    }

    private suspend fun call(app: AppState, kind: String, crash: String, log: String, dry: Boolean) =
        app.core.callJson("system.sendReport", JsonObject(mapOf(
            "kind" to JsonPrimitive(kind),
            "crash" to JsonPrimitive(crash),
            "log" to JsonPrimitive(log),
            "dry" to JsonPrimitive(dry),
        )))
}
