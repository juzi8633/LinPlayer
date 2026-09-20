package xyz.linplayer.app.plugin

import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import xyz.linplayer.app.data.arr
import xyz.linplayer.app.data.obj
import xyz.linplayer.app.data.str
import kotlin.coroutines.resume

/**
 * 插件的 `ui.confirm / prompt / select`(SPEC 7.10):等的是**人**,所以没有超时。
 *
 * ☠ 同一时刻只弹一个:两个插件同时问,后一个排队。叠着弹的话下面那个收不到按键,
 *   看起来像对话框卡死了。
 */
object PluginDialogs {
    class Req(val op: String, val args: JsonObject, private val done: (JsonElement) -> Unit) {
        private var replied = false
        fun reply(v: JsonElement) {
            if (replied) return
            replied = true
            done(v)
        }
    }

    val current = mutableStateOf<Req?>(null)
    private val gate = Mutex()

    suspend fun ask(op: String, args: JsonObject): JsonElement = gate.withLock {
        suspendCancellableCoroutine { k ->
            val req = Req(op, args) { v -> current.value = null; k.resume(v) }
            current.value = req
            // 插件被停用 / 页面走了都会取消这条协程 —— 对话框得跟着收掉,否则留一块点不动的遮罩
            k.invokeOnCancellation { if (current.value === req) current.value = null }
        }
    }

    /** 取消口径三样一致:确认 = false,输入与单选 = null。 */
    fun cancelValue(op: String): JsonElement =
        if (op == "ui.confirm") JsonPrimitive(false) else JsonNull

    fun options(args: JsonObject): List<Pair<String, String>> =
        args["options"].arr().mapNotNull { e ->
            e.obj()?.let { (it.str("value") ?: return@let null) to (it.str("label") ?: it.str("value") ?: "") }
                ?: runCatching { e.jsonPrimitive.content }.getOrNull()?.let { it to it }
        }
}
