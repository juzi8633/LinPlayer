package xyz.linplayer.app.plugin

import android.app.Activity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive
import xyz.linplayer.app.core.Logs
import xyz.linplayer.app.core.toJsonObject
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.obj
import xyz.linplayer.app.data.str
import java.util.concurrent.ConcurrentHashMap

/**
 * 插件宿主要壳执行的事(core/plugin/rt/shell.go):WebView 嗅探 / 取值 / 可见页,TVBox jar spider。
 * 核心层发 `plugin.shellRequest {id, op, args}`,这里做完回 `plugin.shellResult`;
 * `plugin.shellCancel` 撤掉还在跑的那件(核心层那边已经超时了)。
 */
object PluginShell {
    private val running = ConcurrentHashMap<Long, Job>()

    fun start(activity: Activity, app: AppState, scope: CoroutineScope) {
        WebShell.attach(activity)
        scope.launch {
            runCatching {
                app.core.callJson("plugin.setCapabilities", toJsonObject(mapOf(
                    "webview" to WebShell.available(activity), "spider_jar" to true, "spider_py" to false,
                    // ☠ 这一位是 nav / ui 对话框的闸门(rt/navui.go):报 true 而下面 when 里没接,
                    //   插件会等满 60 秒再收到一个看起来像参数写错的超时
                    "shell" to true)))
            }.onFailure { Logs.w("plugin", "报壳能力失败:" + it.message) }
        }
        scope.launch {
            app.core.events.collect { ev ->
                val d = ev.data.obj()
                when (ev.name) {
                    "plugin.shellRequest" -> handle(activity, app, scope, d ?: return@collect)
                    "plugin.shellCancel" -> d?.get("id")?.jsonPrimitive?.longOrNull?.let { running.remove(it)?.cancel() }
                    "plugin.toast" -> d.str("text")?.let { app.toast(it) }
                    "plugin.autoDisabled" -> app.toast("「${d.str("name")?.takeIf { it.isNotEmpty() } ?: d.str("id")}」连续出错,已自动禁用", xyz.linplayer.app.data.ToastKind.Error)
                    "plugin.memoryKilled" -> app.toast("「${d.str("name")?.takeIf { it.isNotEmpty() } ?: d.str("id")}」插件内存占用过高,已暂停", xyz.linplayer.app.data.ToastKind.Error)
                    "plugin.safeMode" -> app.toast("上次启动连续崩溃,已关闭全部插件" + (d.str("suspect")?.takeIf { it.isNotEmpty() }?.let { ",最可疑:$it" } ?: ""), xyz.linplayer.app.data.ToastKind.Error)
                    "plugin.legacyRemoved" -> app.toast("插件系统已重做,旧插件已移除")
                }
            }
        }
    }

    private fun handle(activity: Activity, app: AppState, scope: CoroutineScope, req: JsonObject) {
        val id = req["id"]?.jsonPrimitive?.longOrNull ?: return
        val op = req.str("op") ?: ""
        val args = req["args"].obj() ?: JsonObject(emptyMap())
        running[id] = scope.launch {
            val reply: Map<String, Any?> = try {
                // 对话框等的是**人**:给它 120 秒的话,用户去倒杯水回来插件已经收到「超时」了
                val data: JsonElement = if (op in dialogOps) dispatch(activity, app, op, args)
                else withTimeout(120_000) {
                    when (op) {
                        "webview.sniff" -> WebShell.sniff(activity, args)
                        "webview.evaluate" -> WebShell.evaluate(activity, args)
                        "webview.open" -> WebShell.open(activity, args)
                        "spider.load" -> SpiderHost.load(activity, args)
                        "spider.call" -> SpiderHost.call(activity, args)
                        "spider.dispose" -> { SpiderHost.dispose(args); JsonNull }
                        else -> dispatch(activity, app, op, args)
                    }
                }
                mapOf("id" to id, "ok" to true, "data" to data)
            } catch (e: kotlinx.coroutines.CancellationException) {
                if (e is kotlinx.coroutines.TimeoutCancellationException) errorOf(id, "timeout", "壳执行超时") else return@launch
            } catch (e: ShellTimeout) {
                errorOf(id, "timeout", e.message ?: "超时")
            } catch (e: UnsupportedOperationException) {
                errorOf(id, "unsupported", e.message ?: "不支持")
            } catch (e: Throwable) {
                errorOf(id, if (e is SpiderCrashed) "siteDown" else "internal", e.message ?: e.toString())
            } finally {
                running.remove(id)
            }
            if (reply["ok"] != true) Logs.w("plugin", "壳请求 $op 失败:${reply["error"]}")
            runCatching { app.core.callJson("plugin.shellResult", toJsonObject(reply)) }
        }
    }

    private val dialogOps = setOf("ui.confirm", "ui.prompt", "ui.select")

    /** 导航、页面选项、角标、通知 —— 走这里,和上面那几条一样回 `plugin.shellResult`。 */
    private suspend fun dispatch(activity: Activity, app: AppState, op: String, args: JsonObject): JsonElement =
        when (op) {
            "nav.push", "nav.replace", "nav.back", "nav.setPageOptions", "nav.setBadge" -> PluginNav.handle(op, args)
            in dialogOps -> PluginDialogs.ask(op, args)
            "ui.notify" -> PluginNotify.show(activity, app, args)
            else -> throw UnsupportedOperationException("安卓端不支持 $op")
        }

    private fun errorOf(id: Long, kind: String, msg: String) =
        mapOf("id" to id, "ok" to false, "error" to mapOf("kind" to kind, "message" to msg))

    internal fun str(v: String?): JsonElement = if (v == null) JsonNull else JsonPrimitive(v)
}

/** 壳这边判定的超时(嗅探没抓到、页面加载不完)。和协程取消分开:它要回给插件,取消不用。 */
class ShellTimeout(msg: String) : Exception(msg)

/** spider 子进程崩了 / 连不上:按「站点挂了」回给插件,计入该源的错误数(D355)。 */
class SpiderCrashed(msg: String) : Exception(msg)
