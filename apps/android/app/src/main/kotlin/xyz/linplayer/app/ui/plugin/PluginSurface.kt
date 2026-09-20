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
    claimInitialFocus: Boolean = true,
    focusNs: String? = null,
) {
    val app = LocalApp.current
    val tree = remember(plugin, target) { SurfaceTree() }
    var surfaceId by remember(plugin, target) { mutableStateOf<String?>(null) }
    var state by remember(plugin, target) { mutableStateOf("loading") }
    var error by remember(plugin, target) { mutableStateOf("") }

    /* 盖在画面上的那几种 surface 要登记自己:播放页据此决定这一下按键要不要先问插件(D563)。
       判可见只能在壳这边 —— 「哪一层在最上面」核心层看不到。 */
    DisposableEffect(plugin, kind) {
        val onPlayer = kind in PluginKeys.playerKinds
        if (onPlayer) PluginKeys.playerOverlays.add(plugin)
        onDispose { if (onPlayer) PluginKeys.playerOverlays.remove(plugin) }
    }

    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(plugin, target) {
        /* 首帧预算的口径是 SPEC 7.12:**起点 nav.push,终点真正上屏**。
           起点取 PluginNavClock(导航那一刻起表),取不到才退回这里 ——
           DisposableEffect 跑在第一次组合之后,拿它当起点会把导航到组合那一段漏掉。 */
        val since = PluginNavClock.take()
        val job = app.bg.launch {
            runCatching {
                /* props 在**挂载那一刻**读一次:核心层没有「改 props」这条命令,
                   跟着重组重发只会把同一块反复重挂。所以调用方要么等数据齐了再挂,
                   要么自己换 key 重挂。 */
                val mountArgs = args("plugin" to plugin, "target" to target, "kind" to kind)
                app.call("plugin.ui.mount", JsonObject(
                    mountArgs + (props?.let { mapOf("props" to jsonOf(it)) } ?: emptyMap())))
            }.onSuccess { r ->
                // 首帧跟着 mount 的返回值来:等事件的话会漏掉它(见 core/plugin/ui.go 那条)
                tree.apply(r.obj()?.get("ops").arr())
                surfaceId = r.obj().str("surface")
                if (state == "loading") state = "ready"
                val warm = PluginNavClock.markLoaded(plugin)
                onFirstFrameDrawn(view) {
                    /* 冷热分开记(SPEC 7.12 的 300ms 写明「插件已加载的前提下」):
                       冷的那一次还要把插件装进运行时、现编 TS,和热路径不是一个量级。
                       合成一个数的话门槛只能按冷路径放宽,等于热路径根本没有门槛。 */
                    xyz.linplayer.app.core.Logs.w(
                        "插件UI",
                        "$plugin/$target 首帧上屏(${if (warm) "热" else "冷"}) " +
                            "${android.os.SystemClock.uptimeMillis() - since} ms,${tree.nodes.size} 个节点",
                    )
                }
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

    /* 这一块里第一个可交互元素吃初始焦点(TV);0 = 还没人认领。
       ☠ 官方页上的锚点块要传 claimInitialFocus=false:那一页的初始焦点归官方元素
       (详情页是播放键),插件块抢过去就成了「进页焦点落在一块插件里」。
       -1 是「谁都别认领」—— 节点 id 从 1 起,永远匹配不上。 */
    val firstFocus = remember(plugin, target) { mutableStateOf(if (claimInitialFocus) 0 else -1) }
    val focusKeys = remember(plugin, target) { androidx.compose.runtime.mutableStateMapOf<
        String, androidx.compose.ui.focus.FocusRequester>() }

    Box(modifier) {
        androidx.compose.runtime.CompositionLocalProvider(
            LocalPluginFirstFocus provides firstFocus,
            LocalPluginFocusKeys provides focusKeys,
            // 一页挂两块以上插件时必须给 [focusNs]:两边的节点 id 都从 1 起,
            // 不分开的话后挂的那块把先挂的 requester 覆盖掉,返回时焦点落到别人身上
            LocalPluginKeyNs provides (focusNs ?: ""),
        ) {
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
    // JsonObject/JsonArray 本身就是 Map/List,必须排在它们**前面**:
    // 落到下面那两条会把里层的 JsonElement 逐个 toString,JS 收到的是一串引号
    is JsonElement -> v
    is Map<*, *> -> JsonObject(v.entries.associate { (k, x) -> k.toString() to jsonOf(x) })
    is Iterable<*> -> JsonArray(v.map { jsonOf(it) })
    else -> JsonPrimitive(v.toString())
}

internal fun JsonObject?.dblOf(k: String): Double? = this.dbl(k)

/**
 * 首帧计时的起点(SPEC 7.12):导航到插件页那一刻。
 *
 * ☠ 不能拿 `DisposableEffect` 当起点 —— 它跑在第一次组合**之后**,
 * 导航 → 组合这一段(LpScaffold、LazyColumn、主题)全被漏掉,量出来必然偏小。
 */
object PluginNavClock {
    @Volatile private var mark = 0L

    /** 导航那一刻调一次。 */
    fun start() { mark = android.os.SystemClock.uptimeMillis() }

    /** 取走起点;没人起过表就以现在算(块级 surface 不经导航)。 */
    fun take(): Long {
        val m = mark
        mark = 0L
        return if (m == 0L) android.os.SystemClock.uptimeMillis() else m
    }

    private val loaded = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** 这个插件在本进程里挂过没有。返回 true = 热路径。 */
    fun markLoaded(plugin: String): Boolean = !loaded.add(plugin)
}

/**
 * 内容**真正画到屏幕上**之后回调一次。
 *
 * ☠ 终点不能打在「ops 进树」那一刻:那时候组合、布局、绘制都还没跑。
 * API 29 起用 `registerFrameCommitCallback` —— 它在这一帧提交给显示管线之后才回,
 * 是这台机器上能拿到的最接近「上屏」的信号;更老的机器退回下一次绘制回调。
 */
private fun onFirstFrameDrawn(view: android.view.View, done: () -> Unit) {
    view.post {
        val vto = view.viewTreeObserver
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            vto.registerFrameCommitCallback(object : Runnable {
                override fun run() = done()
            })
            view.invalidate()
            return@post
        }
        vto.addOnDrawListener(object : android.view.ViewTreeObserver.OnDrawListener {
            override fun onDraw() {
                view.post { runCatching { view.viewTreeObserver.removeOnDrawListener(this) } }
                done()
            }
        })
        view.invalidate()
    }
}
