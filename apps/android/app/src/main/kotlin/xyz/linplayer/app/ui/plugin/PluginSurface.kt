package xyz.linplayer.app.ui.plugin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.arr
import xyz.linplayer.app.data.dbl
import xyz.linplayer.app.data.long
import xyz.linplayer.app.data.obj
import xyz.linplayer.app.data.str
import xyz.linplayer.app.ui.pages.args

/**
 * 插件 UI 的节点树(SPEC 7.3)。
 *
 * ☠ Compose 是**声明式**的,而协议是一串命令式的 ops —— 中间必须有这棵树:
 * ops 改树,树是可观察状态,Compose 自己重组该重组的那一段。
 * 直接「收到 op 就动控件」在 Compose 里做不到,硬做出来的是每帧重建整页。
 */
internal class UiNode(val id: Int, val type: String) {
    val props = mutableStateMapOf<String, JsonElement>()
    val children = mutableStateListOf<UiNode>()
    var text by mutableStateOf("")
    var parent: UiNode? = null

    fun prop(name: String): JsonElement? = props[name]
    fun str(name: String): String? = (props[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
    fun num(name: String): Double? = (props[name] as? JsonPrimitive)?.content?.toDoubleOrNull()
    fun bool(name: String): Boolean = (props[name] as? JsonPrimitive)?.content == "true"
    fun style(): JsonObject? = props["style"] as? JsonObject

    /** `{"$fn":n}` → n;不是回调就返回 null。 */
    fun fn(name: String): Int? = (props[name] as? JsonObject)?.get("\$fn")?.let {
        (it as? JsonPrimitive)?.content?.toIntOrNull()
    }
}

/** 一个 surface 的全部状态:节点表 + 根。ops 只改这里。 */
internal class SurfaceTree {
    val nodes = mutableMapOf<Int, UiNode>()
    val root = UiNode(0, "#root")

    init {
        nodes[0] = root
    }

    fun apply(ops: List<JsonElement>) {
        for (e in ops) {
            val o = e.obj() ?: continue
            when (o.str("op")) {
                "root" -> Unit // 根一直都在,不必重建
                "create" -> {
                    val id = o.long("id")?.toInt() ?: continue
                    nodes[id] = UiNode(id, o.str("type") ?: "View")
                }
                "text" -> nodes[o.long("id")?.toInt()]?.text = o.str("value") ?: ""
                "props" -> {
                    val n = nodes[o.long("id")?.toInt()] ?: continue
                    (o["unset"] as? JsonArray)?.forEach { u ->
                        n.props.remove((u as? JsonPrimitive)?.content ?: "")
                    }
                    (o["set"] as? JsonObject)?.forEach { (k, v) -> n.props[k] = v }
                }
                "insert" -> {
                    val n = nodes[o.long("id")?.toInt()] ?: continue
                    val p = nodes[o.long("parent")?.toInt()] ?: continue
                    // 同一个 id 再次 insert 是**移动**:先从原处摘掉,不然会出现两份
                    n.parent?.children?.remove(n)
                    val before = o["before"]?.let { (it as? JsonPrimitive)?.content?.toIntOrNull() }
                    val at = before?.let { b -> p.children.indexOfFirst { it.id == b } }?.takeIf { it >= 0 }
                    if (at == null) p.children.add(n) else p.children.add(at, n)
                    n.parent = p
                }
                "remove" -> {
                    val n = nodes[o.long("id")?.toInt()] ?: continue
                    n.parent?.children?.remove(n)
                    n.parent = null
                    // 子树的节点表一起丢,否则一页开开关关几次之后这张表只增不减
                    drop(n)
                }
            }
        }
    }

    private fun drop(n: UiNode) {
        nodes.remove(n.id)
        n.children.forEach { drop(it) }
    }
}

/**
 * 挂一块插件 UI(SPEC 7.2)。进树时 mount、出树时 unmount。
 *
 * ★ 首帧之前画官方骨架屏(D271);插件那一块崩了只在这里显示「出错」,
 *   不带倒整页 —— 这就是错误边界在壳这一侧的样子(D136)。
 */
@Composable
fun PluginSurface(
    plugin: String,
    target: String,
    kind: String = "block",
    props: Map<String, Any?>? = null,
    modifier: Modifier = Modifier,
) {
    val app = LocalApp.current
    val tree = remember(plugin, target) { SurfaceTree() }
    var surfaceId by remember(plugin, target) { mutableStateOf<String?>(null) }
    var state by remember(plugin, target) { mutableStateOf("loading") }
    var error by remember(plugin, target) { mutableStateOf("") }

    DisposableEffect(plugin, target) {
        val job = app.bg.launch {
            runCatching {
                app.call("plugin.ui.mount", args("plugin" to plugin, "target" to target, "kind" to kind))
            }.onSuccess { r ->
                // 首帧跟着 mount 的返回值来:等事件的话会漏掉它(见 core/plugin/ui.go 那条)
                tree.apply(r.obj()?.get("ops").arr())
                surfaceId = r.obj().str("surface")
                if (state == "loading") state = "ready"
            }
                .onFailure { state = "error"; error = it.message ?: "挂不上" }
        }
        onDispose {
            job.cancel()
            val sid = surfaceId
            if (sid != null) app.bg.launch { runCatching { app.call("plugin.ui.unmount", args("surface" to sid)) } }
        }
    }

    // 帧与状态都从核心层的事件流来:和桌面收的是同一条流
    LaunchedEffect(surfaceId) {
        val sid = surfaceId ?: return@LaunchedEffect
        app.core.events.collectLatest { ev ->
            val o = ev.data as? JsonObject ?: return@collectLatest
            if (o.str("surface") != sid) return@collectLatest
            when (ev.name) {
                "plugin.ui" -> tree.apply(o["ops"].arr())
                "plugin.ui.surface" -> {
                    state = o.str("state") ?: state
                    error = o["error"].obj().str("message") ?: error
                }
            }
        }
    }

    Box(modifier) {
        when {
            state == "error" -> PluginError(error)
            state == "loading" && tree.root.children.isEmpty() -> PluginSkeleton()
            else -> RenderNode(tree.root, surfaceId ?: "", app)
        }
    }
}

@Composable
private fun PluginSkeleton() {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(3) { xyz.linplayer.app.ui.components.Skeleton(Modifier.fillMaxWidth().height(56.dp)) }
    }
}

@Composable
private fun PluginError(message: String) {
    xyz.linplayer.app.ui.components.ErrorState("这一块出错了:$message")
}

/** 事件回传:壳只认回调号,原样发回去。 */
internal fun fire(app: AppState, surface: String, fn: Int?, vararg values: Any?) {
    if (fn == null || surface.isEmpty()) return
    app.bg.launch {
        runCatching {
            app.call(
                "plugin.ui.event",
                JsonObject(
                    mapOf(
                        "surface" to JsonPrimitive(surface),
                        "fn" to JsonPrimitive(fn),
                        "args" to JsonArray(values.map { jsonOf(it) }),
                    )
                )
            )
        }
    }
}

private fun jsonOf(v: Any?): JsonElement = when (v) {
    null -> kotlinx.serialization.json.JsonNull
    is Boolean -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    else -> JsonPrimitive(v.toString())
}

internal fun JsonObject?.dblOf(k: String): Double? = this.dbl(k)
