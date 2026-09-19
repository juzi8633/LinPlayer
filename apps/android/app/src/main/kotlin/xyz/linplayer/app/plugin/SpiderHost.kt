package xyz.linplayer.app.plugin

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.linplayer.app.core.toJsonObject
import xyz.linplayer.app.data.str
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * jar spider 的主进程一侧(D354):所有 jar 跑在 `:spider` 子进程里,这里用 Messenger 跨进程调。
 *
 * 句柄由**这一侧**分配并记住 load 参数:子进程崩了重启后,旧句柄在那边不认识,
 * 这里自动用原参数重新 load 一次再调 —— 插件那边手里的句柄一直有效,不用感知崩溃。
 */
object SpiderHost {
    private val seq = AtomicLong(0)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<Bundle>>()
    private val loads = ConcurrentHashMap<String, JsonObject>()
    @Volatile private var remote: Messenger? = null
    private var connecting: CompletableDeferred<Messenger>? = null

    private val replies = Messenger(Handler(Looper.getMainLooper()) { m ->
        pending.remove(m.data.getLong("id"))?.complete(m.data)
        true
    })

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val m = Messenger(binder)
            remote = m
            binder.linkToDeath({ crashed() }, 0)
            connecting?.complete(m)
        }
        override fun onServiceDisconnected(name: ComponentName) = crashed()
    }

    /** 子进程没了:手上等着的全部按「站点挂了」失败,下一次调用会重新拉起子进程。 */
    private fun crashed() {
        remote = null
        connecting = null
        pending.keys.toList().forEach { k ->
            pending.remove(k)?.completeExceptionally(SpiderCrashed("jar 源进程崩溃,已重启,请重试"))
        }
    }

    private suspend fun messenger(ctx: Context): Messenger {
        remote?.let { return it }
        val d = synchronized(this) {
            connecting ?: CompletableDeferred<Messenger>().also {
                connecting = it
                ctx.applicationContext.bindService(Intent(ctx, SpiderService::class.java), conn, Context.BIND_AUTO_CREATE)
            }
        }
        return d.await()
    }

    private suspend fun send(ctx: Context, what: Int, payload: JsonObject): Bundle {
        val id = seq.incrementAndGet()
        val d = CompletableDeferred<Bundle>()
        pending[id] = d
        val msg = Message.obtain(null, what).apply {
            data = Bundle().apply { putLong("id", id); putString("req", payload.toString()) }
            replyTo = replies
        }
        try {
            messenger(ctx).send(msg)
            return d.await()
        } finally {
            pending.remove(id)
        }
    }

    private fun Bundle.orThrow(): String {
        getString("err")?.let { if (getBoolean("noHandle")) throw NoHandle() else throw IllegalStateException(it) }
        return getString("data") ?: ""
    }

    private class NoHandle : Exception()

    suspend fun load(ctx: Context, args: JsonObject): JsonElement {
        val handle = "h" + seq.incrementAndGet()
        val req = toJsonObject(args + ("handle" to JsonPrimitive(handle)))
        send(ctx, SpiderService.LOAD, req).orThrow()
        loads[handle] = req
        return toJsonObject(mapOf("handle" to handle))
    }

    suspend fun call(ctx: Context, args: JsonObject): JsonElement {
        val handle = args.str("handle") ?: throw IllegalArgumentException("缺少 handle")
        val out = try {
            send(ctx, SpiderService.CALL, args).orThrow()
        } catch (e: NoHandle) {
            // 子进程重启过:用原参数重新 load 一次再调
            val req = loads[handle] ?: throw IllegalStateException("这个 spider 已经释放了")
            send(ctx, SpiderService.LOAD, req).orThrow()
            send(ctx, SpiderService.CALL, args).orThrow()
        }
        return JsonPrimitive(out)
    }

    fun dispose(args: JsonObject) {
        val handle = args.str("handle") ?: return
        loads.remove(handle)
        remote?.send(Message.obtain(null, SpiderService.DISPOSE).apply {
            data = Bundle().apply { putLong("id", 0); putString("req", args.toString()) }
        })
    }

    internal fun parse(s: String): JsonObject = Json.parseToJsonElement(s) as JsonObject
}
