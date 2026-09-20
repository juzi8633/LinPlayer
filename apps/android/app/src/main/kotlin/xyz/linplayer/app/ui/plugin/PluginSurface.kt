package xyz.linplayer.app.ui.plugin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.systemBars
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
                "canvas" -> nodes[o.long("id")?.toInt()]?.let { n ->
                    n.props["cmds"] = o["cmds"] ?: JsonArray(emptyList())
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
        // 首帧预算(D543 的 300ms)量的是这里到第一批 ops 进树
        val since = android.os.SystemClock.uptimeMillis()
        val job = app.bg.launch {
            runCatching {
                app.call("plugin.ui.mount", args("plugin" to plugin, "target" to target, "kind" to kind))
            }.onSuccess { r ->
                // 首帧跟着 mount 的返回值来:等事件的话会漏掉它(见 core/plugin/ui.go 那条)
                tree.apply(r.obj()?.get("ops").arr())
                xyz.linplayer.app.core.Logs.d(
                    "插件UI",
                    "$plugin/$target 首帧 ${android.os.SystemClock.uptimeMillis() - since} ms,${tree.nodes.size} 个节点",
                )
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

    /* 视口 / 断点 / 安全区(SPEC 7.7 D217 D425 D426)。
       ★ 断点阈值与官方页同一套:算两遍迟早分叉。
       ☠ insets 要真取系统的:写死 0 的表现是界面在有刘海、有手势条的机器上
       被切掉一圈,而开发机上一切正常,不报错。 */
    val density = androidx.compose.ui.platform.LocalDensity.current
    val cfg = androidx.compose.ui.platform.LocalConfiguration.current
    val bars = androidx.compose.foundation.layout.WindowInsets.systemBars.asPaddingValues()
    val tv = LocalPluginTv.current
    val ld = androidx.compose.ui.unit.LayoutDirection.Ltr
    LaunchedEffect(surfaceId, cfg.screenWidthDp, cfg.screenHeightDp, bars) {
        val sid = surfaceId ?: return@LaunchedEffect
        val w = cfg.screenWidthDp
        runCatching {
            app.call("plugin.ui.viewport", JsonObject(mapOf(
                "surface" to JsonPrimitive(sid),
                "width" to JsonPrimitive(w),
                "height" to JsonPrimitive(cfg.screenHeightDp),
                "breakpoint" to JsonPrimitive(if (w < 600) "compact" else if (w < 1000) "medium" else "expanded"),
                "formFactor" to JsonPrimitive(if (tv) "tv" else "phone"),
                "insets" to JsonObject(mapOf(
                    "top" to JsonPrimitive(bars.calculateTopPadding().value),
                    "right" to JsonPrimitive(bars.calculateRightPadding(ld).value),
                    "bottom" to JsonPrimitive(bars.calculateBottomPadding().value),
                    "left" to JsonPrimitive(bars.calculateLeftPadding(ld).value),
                )),
            )))
        }
    }
    // density 只是让上面那段在缩放变化时也重算一次
    @Suppress("UNUSED_EXPRESSION") density

    // 这一块里第一个可交互元素吃初始焦点(TV);0 = 还没人认领
    val firstFocus = remember(plugin, target) { mutableStateOf(0) }

    Box(modifier) {
        androidx.compose.runtime.CompositionLocalProvider(LocalPluginFirstFocus provides firstFocus) {
        when {
            state == "error" -> PluginError(error)
            state == "loading" && tree.root.children.isEmpty() -> PluginSkeleton()
            else -> RenderNode(tree.root, surfaceId ?: "", app)
        }
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

/* ☠ Map / List 必须**递归转**,不能落到 toString()。
   落到 toString 的表现是 JS 那边收到一个字符串 "{from=0, to=24}",
   `r.from` 读出 undefined,`undefined|0` 悄悄变成 0 —— 于是虚拟列表的窗口
   被设成 0..0,一千项全部消失,界面空白且**不报错**。2026-09-20 在 TV 上撞到。 */
internal fun jsonOf(v: Any?): JsonElement = when (v) {
    null -> kotlinx.serialization.json.JsonNull
    is Boolean -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    is String -> JsonPrimitive(v)
    is Map<*, *> -> JsonObject(v.entries.associate { (k, x) -> k.toString() to jsonOf(x) })
    is Iterable<*> -> JsonArray(v.map { jsonOf(it) })
    else -> JsonPrimitive(v.toString())
}

internal fun JsonObject?.dblOf(k: String): Double? = this.dbl(k)
