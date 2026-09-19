package xyz.linplayer.app.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.linplayer.app.core.CoreException
import xyz.linplayer.app.data.Account
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.ToastKind
import xyz.linplayer.app.data.arr
import xyz.linplayer.app.data.bool
import xyz.linplayer.app.data.dbl
import xyz.linplayer.app.data.long
import xyz.linplayer.app.data.obj
import xyz.linplayer.app.data.str
import xyz.linplayer.app.tv.kit.CoverSurface
import xyz.linplayer.app.tv.kit.PageHead
import xyz.linplayer.app.tv.kit.PanelGroup
import xyz.linplayer.app.tv.kit.PanelItem
import xyz.linplayer.app.tv.kit.StatusDot
import xyz.linplayer.app.tv.kit.TvButton
import xyz.linplayer.app.tv.kit.TvC
import xyz.linplayer.app.tv.kit.TvR
import xyz.linplayer.app.tv.kit.TvSp
import xyz.linplayer.app.tv.kit.TvText
import xyz.linplayer.app.tv.kit.TvW
import xyz.linplayer.app.tv.kit.bleed
import xyz.linplayer.app.tv.kit.contentArea
import xyz.linplayer.app.tv.kit.tvType
import xyz.linplayer.app.ui.pages.args
import xyz.linplayer.app.ui.pages.rememberServerIcon
import xyz.linplayer.app.ui.theme.LpIcons

/** 虚线框。「添加」类入口的外观:它不是一个已有的东西。 */
internal fun Modifier.dashed(color: Color, radius: Dp = 14.dp) = this.drawBehind {
    drawRoundRect(color, style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
        cornerRadius = CornerRadius(radius.toPx()))
}

/** 虚线的「添加」入口,可聚焦。 */
@Composable
internal fun AddTile(label: String, w: Dp?, h: Dp, radius: Dp, modifier: Modifier, onClick: () -> Unit) {
    val t = tvType
    Surface(
        onClick = onClick,
        modifier = modifier.then(if (w != null) Modifier.size(w, h) else Modifier.fillMaxWidth().height(h)).dashed(TvC.line, radius),
        shape = ClickableSurfaceDefaults.shape(androidx.compose.foundation.shape.RoundedCornerShape(radius)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = if (w != null) 1.04f else 1f),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, contentColor = TvC.fg2,
            focusedContainerColor = TvC.focus, focusedContentColor = TvC.onFocus),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (w != null) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(LpIcons.plus, null, Modifier.size(28.dp), tint = TvC.acc)
                Spacer(Modifier.height(TvSp.x6))
                Text(label, fontSize = t.body)
            } else Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(LpIcons.plus, null, Modifier.size(t.iconS))
                Spacer(Modifier.width(TvSp.x6))
                Text(label, fontSize = t.body)
            }
        }
    }
}

// ---------------------------------------------------------------- 服务器(§7.11)

/**
 * ★ 卡片**只显示 图标 / 名称 / 备注**【用户定 2026-07-20】,当前服务器用文字标注(远看色差不可靠)。
 * ★ 排序模式是**同一页的第二种模式,不是新页面**:确认键之前一次都不动核心层,返回键 = 还原。
 */
@Composable
fun ServersPage() {
    val app = LocalApp.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val overlay = rememberOverlay()
    val mem = LocalFocusMemory.current
    val t = tvType
    val session by app.session.collectAsStateWithLifecycle()
    var raw by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var probe by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var reload by remember { mutableIntStateOf(0) }
    var order by remember { mutableStateOf<List<JsonObject>?>(null) }
    var moving by remember { mutableIntStateOf(-1) }
    var movingFrom by remember { mutableIntStateOf(-1) }
    val root = remember { FocusRequester() }

    LaunchedEffect(reload, session) {
        raw = runCatching { app.call("account.listAccounts") }.getOrNull().arr().mapNotNull { it.obj() }
        // 状态必须是真探测,进页跑一次;没探测(fg3)和失败(bad)不许同色
        launch {
            probe = runCatching { app.call("account.probeAccounts") }.getOrNull().arr().mapNotNull { it.obj() }
                .mapNotNull { o -> o.str("server")?.let { it to o.bool("ok") } }.toMap()
        }
    }
    // 插件数据源不进这张网格(一个订阅可能几十个源),按订阅分组画在下面(SPEC 8.7)
    val normal = raw.filter { it["plugin"] == null }
    val list = order ?: normal
    val reorder = order != null
    val openGroups = remember { androidx.compose.runtime.mutableStateListOf<String>() }

    if (reorder) BackHandler {
        order = null; moving = -1
        scope.launch { kotlinx.coroutines.delay(50); mem.restore() }
    }

    Box(Modifier.fillMaxSize().then(if (reorder) Modifier.focusRequester(root).onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent isOk(e.key) || e.key in listOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight)
        val cur = order ?: return@onPreviewKeyEvent false
        val d = when (e.key) { Key.DirectionLeft -> -1; Key.DirectionRight -> 1; Key.DirectionUp -> -3; Key.DirectionDown -> 3; else -> 0 }
        if (d != 0) {
            val to = (moving + d).coerceIn(0, cur.lastIndex)
            if (to != moving) { order = cur.toMutableList().apply { add(to, removeAt(moving)) }; moving = to }
            return@onPreviewKeyEvent true
        }
        if (isOk(e.key)) {
            // 网格里只有非插件的行:换算回账号表里的真实下标
            val from = movingFrom
            val to = raw.indexOf(normal.getOrNull(moving) ?: return@onPreviewKeyEvent true)
            order = null; moving = -1
            if (from != to) scope.launch {
                runCatching { app.call("account.reorderAccounts", args("from" to from, "to" to to)) }
                    .onSuccess { reload++ }.onFailure { app.report(it) }
            }
            return@onPreviewKeyEvent true
        }
        false
    }.focusable() else Modifier)) {
        Column(Modifier.contentArea()) {
            if (reorder) Row(
                Modifier.fillMaxWidth().clip(TvR.md).background(TvC.accDim).border(1.5.dp, TvC.acc, TvR.md)
                    .padding(horizontal = TvSp.x16, vertical = TvSp.x12),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(LpIcons.sort, null, Modifier.size(t.iconS), tint = TvC.acc)
                Spacer(Modifier.width(TvSp.x8))
                TvText("排序模式 · 方向键移动位置,确认键放下,返回键取消", t.body, TvC.fg)
            } else PageHead("服务器", sub = "按确认键打开操作")
            Spacer(Modifier.height(TvSp.x16))
            LazyVerticalGrid(
                GridCells.Fixed(3), horizontalArrangement = Arrangement.spacedBy(TvSp.x24),
                verticalArrangement = Arrangement.spacedBy(TvSp.x20), contentPadding = PaddingValues(12.dp), modifier = Modifier.bleed(12.dp),
            ) {
                itemsIndexed(list, key = { _, o -> o.str("server") ?: "" }) { i, o ->
                    val id = o.str("server") ?: ""
                    ServerCard(o, probe[id], i, list.size, reorder, moving == i,
                        Modifier.memo("srv.$id", initial = i == 0)) {
                        overlay.open { ServerPanel(o, raw.indexOf(o), overlay, onReorder = {
                            overlay.close()
                            movingFrom = raw.indexOf(o); moving = normal.indexOf(o); order = normal
                            scope.launch { kotlinx.coroutines.delay(50); runCatching { root.requestFocus() } }
                        }, onChanged = { reload++ }) }
                    }
                }
                // 「添加服务器」卡在排序模式下不画:它不参与排序,留着「位置 n / N」数不对
                if (!reorder) item(key = "add") {
                    AddTile("添加服务器", 256.dp, 150.dp, 14.dp, Modifier.memo("srv.add")) { nav.push(TvRoute.AddServer) }
                }
                if (!reorder) raw.filter { it["plugin"] != null }.groupBy { it["plugin"].obj().str("group") ?: "" }.forEach { (group, rows) ->
                    val p0 = rows.first()["plugin"].obj()
                    val gname = p0.str("group_name")?.takeIf { it.isNotEmpty() } ?: p0.str("plugin_id") ?: ""
                    val open = group in openGroups || rows.any { it.bool("active") }
                    item(key = "g:$group", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                        Row(horizontalArrangement = Arrangement.spacedBy(TvSp.x8), verticalAlignment = Alignment.CenterVertically) {
                            TvButton("${if (open) "▾" else "▸"} $gname(${rows.size})", modifier = Modifier.memo("srv.g.$group")) {
                                if (!openGroups.remove(group)) openGroups.add(group)
                            }
                            TvButton("管理", modifier = Modifier.memo("srv.gm.$group")) {
                                overlay.open { SourceGroupPanel(group, gname, p0.str("plugin_id") ?: "", { overlay.close() }) { reload++ } }
                            }
                        }
                    }
                    if (open) itemsIndexed(rows, key = { _, o -> o.str("server") ?: "" }) { _, o ->
                        val id = o.str("server") ?: ""
                        val why = o["plugin"].obj().str("unavailable").orEmpty()
                        ServerCard(o, if (o["plugin"].obj().bool("last_failed")) false else null, 0, 1, false, false, Modifier.memo("srv.$id")) {
                            if (why.isNotEmpty()) { app.toast(why); return@ServerCard }
                            scope.launch {
                                runCatching { app.call("account.setActiveServer", args("server_id" to id)) }
                                    .onSuccess { app.refreshSession(); reload++; app.toast("已切到「${o.str("name")}」") }.onFailure { app.report(it) }
                            }
                        }
                    }
                }
            }
        }
        OverlayHost(overlay)
    }
}

@Composable
private fun ServerCard(o: JsonObject, ok: Boolean?, index: Int, total: Int, reorder: Boolean, moving: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val t = tvType
    val id = o.str("server") ?: ""
    val local = o.str("source_kind") == "local"
    val icon = if (local) null else rememberServerIcon(id)
    val body: @Composable BoxScope.() -> Unit = {
        Column(Modifier.fillMaxSize().padding(TvSp.x12), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Box(Modifier.size(48.dp).clip(TvR.lg).background(if (icon == null) TvC.accDim else Color.Transparent),
                contentAlignment = Alignment.Center) {
                // 取不到图标画品牌色块,不画问号
                if (icon != null) Image(icon, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                else Icon(if (local) LpIcons.folder else LpIcons.server, null, Modifier.size(24.dp), tint = TvC.acc)
            }
            Spacer(Modifier.height(TvSp.x8))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TvText(o.str("name") ?: id, t.title, TvC.fg, Modifier.weight(1f, fill = false), weight = TvW.semi)
                Spacer(Modifier.width(TvSp.x6))
                StatusDot(when (ok) { true -> TvC.ok; false -> TvC.bad; null -> TvC.fg3 })
            }
            o.str("remark")?.takeIf { it.isNotBlank() }?.let { TvText(it, t.meta, TvC.fg2) }
            when {
                moving -> TvText("正在移动 · 位置 ${index + 1} / $total", t.meta, TvC.acc, weight = TvW.semi)
                o.bool("active") -> TvText("当前使用", t.meta, TvC.acc, weight = TvW.semi)
            }
        }
    }
    if (reorder) Box(
        Modifier.size(256.dp, 150.dp).clip(TvR.lg).background(TvC.surface1).dashed(if (moving) TvC.acc else TvC.fg3)
            .then(if (moving) Modifier else Modifier.graphicsLayer { alpha = .72f }),
        content = body,
    ) else CoverSurface(256.dp, 150.dp, modifier = modifier, onClick = onClick, content = body)
}

@Composable
private fun BoxScope.ServerPanel(o: JsonObject, index: Int, overlay: Overlay, onReorder: () -> Unit, onChanged: () -> Unit) {
    val app = LocalApp.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val id = o.str("server") ?: ""
    val name = o.str("name") ?: id
    val local = o.str("source_kind") == "local"
    var level by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var user by remember { mutableStateOf(o.str("user_name") ?: "") }
    val back = { level = "" }
    fun done(msg: String) { overlay.close(); app.toast(msg, ToastKind.Ok); onChanged() }
    when (level) {
        "name", "remark" -> TvSidePanel(if (level == "name") "修改名称" else "修改备注", { overlay.close() }, back = back) {
            Box(Modifier.padding(horizontal = TvSp.x6)) {
                TvTextField(text, { text = it }, if (level == "name") "名称" else "给自己看的备注", 218.dp, Modifier.memo("srv.edit", initial = true))
            }
            PanelItem("保存", onClick = {
                scope.launch {
                    runCatching { app.call("account.updateAccount", args("server_id" to id, level to text.trim())) }
                        .onSuccess { done("已保存") }.onFailure { app.report(it) }
                }
            })
        }
        // 重新登录走 emby.relogin,**不是 emby.login**【继承 PC A-53】:后者会凭空多出一台服务器
        "relogin" -> {
            var pass by remember { mutableStateOf("") }
            TvSidePanel("重新登录", { overlay.close() }, back = back) {
                Box(Modifier.padding(horizontal = TvSp.x6)) { TvTextField(user, { user = it }, "用户名", 218.dp, Modifier.memo("srv.user", initial = true)) }
                Spacer(Modifier.height(TvSp.x6))
                Box(Modifier.padding(horizontal = TvSp.x6)) { TvTextField(pass, { pass = it }, "密码", 218.dp, Modifier.memo("srv.pass"), password = true) }
                PanelItem("登录", onClick = {
                    scope.launch {
                        runCatching { app.call("emby.relogin", args("server_id" to id, "username" to user, "password" to pass)) }
                            .onSuccess { app.refreshSession(); done("已重新登录") }
                            .onFailure { app.report(it) }
                    }
                })
            }
        }
        "delete" -> TvConfirm("删除「$name」?", "本机上这台服务器的登录信息会一并清除,此操作无法撤销。已下载的文件不受影响。", "删除",
            onCancel = { overlay.close() }, onConfirm = {
                scope.launch {
                    runCatching { app.call("account.removeAccount", args("server_id" to id)) }
                        .onSuccess { overlay.close(); app.refreshSession(); onChanged() }
                        .onFailure { app.report(it) }
                }
            })
        else -> TvSidePanel(name, { overlay.close() }) {
            PanelGroup("使用")
            if (o.bool("active")) PanelItem("当前使用", enabled = false)
            else PanelItem("切换到此服务器", focused = true, onClick = {
                scope.launch { if (switchServerIfNeeded(app, id)) { overlay.close(); onChanged() } }
            })
            if (!local) PanelItem("线路管理", value = "${o["lines"].arr().size.coerceAtLeast(1)} 条", chevron = true,
                focused = o.bool("active"), onClick = { overlay.close(); nav.push(TvRoute.Lines(id, name)) })
            PanelGroup("编辑")
            PanelItem("修改名称", onClick = { text = name; level = "name" })
            PanelItem("修改备注", onClick = { text = o.str("remark") ?: ""; level = "remark" })
            if (!local) PanelItem("重新登录", onClick = { level = "relogin" })
            PanelItem("调整排序", onClick = onReorder)
            PanelGroup("危险")
            PanelItem("删除此服务器", danger = true, onClick = { level = "delete" })
        }
    }
}

// ---------------------------------------------------------------- 线路管理(§7.12)

private data class LineRowData(val index: Int, val name: String, val url: String, val remark: String?)

/**
 * ★ **这一页显示地址**【用户定 2026-09-06】:只写线路名的话,两条同名线路在界面上就是同一行。
 * ★「全部测速」逐条各发一个 probeLine,**谁先回填谁**;不用整表 probeLines(要等最慢那条 6s 超时一起回)。进页不自动测。
 */
@Composable
fun LinesPage(r: TvRoute.Lines) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val overlay = rememberOverlay()
    val t = tvType
    var acc by remember { mutableStateOf<JsonObject?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    // null = 未测;-1 = 测速中;-2 = 不可达;其余 = 毫秒
    var latency by remember { mutableStateOf<Map<Int, Long>>(emptyMap()) }
    LaunchedEffect(reload) {
        acc = runCatching { app.call("account.listAccounts") }.getOrNull().arr().mapNotNull { it.obj() }.firstOrNull { it.str("server") == r.serverId }
    }
    val a = acc
    val lines = a?.get("lines").arr().mapIndexedNotNull { i, e ->
        val o = e.obj() ?: return@mapIndexedNotNull null
        LineRowData(i, o.str("name")?.takeIf { it.isNotBlank() } ?: "线路 ${i + 1}", o.str("url") ?: "", o.str("remark"))
    }.ifEmpty { if (a != null) listOf(LineRowData(0, "主线路", r.serverId, null)) else emptyList() }
    val active = a.long("active_line")?.toInt() ?: 0

    fun probe(i: Int) {
        latency = latency + (i to -1L)
        scope.launch {
            val ms = runCatching { app.call("account.probeLine", args("server_id" to r.serverId, "index" to i)) }.getOrNull().obj().dbl("ms")
            latency = latency + (i to (ms?.toLong() ?: -2L))
        }
    }
    suspend fun saveLines(next: List<Pair<String, String>>) {
        app.call("account.setLines", JsonObject(mapOf(
            "server_id" to JsonPrimitive(r.serverId),
            "lines" to JsonArray(next.map { (n, u) -> JsonObject(mapOf("name" to JsonPrimitive(n), "url" to JsonPrimitive(u))) }),
        )))
    }
    val realLines = a?.get("lines").arr().isNotEmpty()

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.contentArea()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TvText("线路管理", t.headline, TvC.fg, weight = TvW.semi)
                Spacer(Modifier.width(TvSp.x12))
                TvText(r.name.ifBlank { a.str("name") ?: "" }, t.body, TvC.fg3)
                Spacer(Modifier.weight(1f))
                TvButton("全部测速", LpIcons.refresh, modifier = Modifier.memo("lines.probe", initial = true), onClick = { lines.forEach { probe(it.index) } })
                Spacer(Modifier.width(TvSp.x8))
                TvButton("同步线路", LpIcons.sync, modifier = Modifier.memo("lines.sync"), onClick = {
                    scope.launch {
                        runCatching { app.call("account.syncLines", args("server_id" to r.serverId)) }
                            .onSuccess { v -> reload++; app.toast("线路已同步 · 新增 ${v.obj().long("added") ?: 0} 条", ToastKind.Ok) }
                            // 不支持是**常态**:服主没部署同步服务,不弹红字
                            .onFailure { e ->
                                val code = (e as? CoreException)?.code
                                if (code == "E_NOTFOUND" || code == "E_UNSUPPORTED") app.toast("这台服务器没有提供线路同步") else app.report(e)
                            }
                    }
                })
            }
            TvText("选中的线路对该服务器的所有请求生效,包括图片和播放流", t.meta, TvC.fg2)
            Spacer(Modifier.height(TvSp.x12))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(TvSp.x6)) {
                itemsIndexed(lines, key = { _, l -> l.index }) { _, l ->
                    val ms = latency[l.index]
                    val (text, color) = when {
                        ms == null -> "未测速" to TvC.fg3
                        ms == -1L -> "测速中…" to TvC.warn
                        ms < 0 -> "不可达" to TvC.bad
                        else -> "$ms ms" to if (ms < 200) TvC.ok else TvC.fg2
                    }
                    TvListRow(Modifier.memo("line.${l.index}"), onClick = {
                        if (l.index == active) return@TvListRow
                        scope.launch {
                            runCatching { app.call("account.setActiveLine", args("server_id" to r.serverId, "index" to l.index)) }
                                // 切完刷新会话:图片 URL 是拿会话地址现拼的,不刷 = 切了跟没切一样
                                .onSuccess { app.refreshSession(); reload++; app.toast("已切换到 ${l.name}", ToastKind.Ok) }
                                .onFailure { app.report(it) }
                        }
                    }, onLongClick = {
                        overlay.open {
                            var level by remember { mutableStateOf("") }
                            var nameText by remember { mutableStateOf(l.name) }
                            when (level) {
                                "rename" -> TvSidePanel("修改名称", { overlay.close() }, back = { level = "" }) {
                                    Box(Modifier.padding(horizontal = TvSp.x6)) { TvTextField(nameText, { nameText = it }, "名称", 218.dp, Modifier.memo("line.name", initial = true)) }
                                    PanelItem("保存", onClick = {
                                        scope.launch {
                                            runCatching { saveLines(lines.map { if (it.index == l.index) nameText.trim() to it.url else it.name to it.url }) }
                                                .onSuccess { overlay.close(); reload++ }.onFailure { app.report(it) }
                                        }
                                    })
                                }
                                "delete" -> TvConfirm("删除「${l.name}」?", "删除后这条线路的地址不会保留。", "删除", onCancel = { overlay.close() }, onConfirm = {
                                    scope.launch {
                                        runCatching { saveLines(lines.filter { it.index != l.index }.map { it.name to it.url }) }
                                            .onSuccess { overlay.close(); reload++ }.onFailure { app.report(it) }
                                    }
                                })
                                else -> TvSidePanel(l.name, { overlay.close() }) {
                                    PanelItem("单独测速", focused = true, onClick = { overlay.close(); probe(l.index) })
                                    if (realLines) PanelItem("修改名称", onClick = { level = "rename" })
                                    if (realLines && lines.size > 1) {
                                        PanelGroup("危险")
                                        PanelItem("删除线路", danger = true, onClick = { level = "delete" })
                                    }
                                }
                            }
                        }
                    }) { focused ->
                        StatusDot(if (focused) TvC.onFocus else color.takeIf { ms != null && ms != -1L } ?: TvC.fg3)
                        Spacer(Modifier.width(TvSp.x12))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RowText(l.name, t.body)
                                if (l.index == active) {
                                    Spacer(Modifier.width(TvSp.x8))
                                    Text("当前使用", fontSize = t.meta, color = if (focused) Color.Unspecified else TvC.acc, fontWeight = TvW.semi)
                                }
                            }
                            RowText(l.url, t.meta, .65f, mono = true)
                        }
                        Text(text, fontSize = t.body, color = if (focused) Color.Unspecified else color, fontWeight = TvW.semi)
                    }
                }
                item("add") {
                    AddTile("添加线路", null, 48.dp, 10.dp, Modifier.padding(top = TvSp.x6).memo("line.add")) {
                        overlay.open {
                            var n by remember { mutableStateOf("") }
                            var u by remember { mutableStateOf("") }
                            TvSidePanel("添加线路", { overlay.close() }) {
                                Box(Modifier.padding(horizontal = TvSp.x6)) { TvTextField(n, { n = it }, "名称", 218.dp, Modifier.memo("line.new.name", initial = true)) }
                                Spacer(Modifier.height(TvSp.x6))
                                Box(Modifier.padding(horizontal = TvSp.x6)) { TvTextField(u, { u = it }, "https://", 218.dp, Modifier.memo("line.new.url")) }
                                PanelItem("添加", onClick = {
                                    if (u.isBlank()) return@PanelItem
                                    scope.launch {
                                        val base = if (realLines) lines.map { it.name to it.url } else emptyList()
                                        runCatching { saveLines(base + (n.trim().ifBlank { "线路 ${base.size + 1}" } to xyz.linplayer.app.ui.pages.withScheme(u))) }
                                            .onSuccess { overlay.close(); reload++ }.onFailure { app.report(it) }
                                    }
                                })
                            }
                        }
                    }
                }
            }
        }
        OverlayHost(overlay)
    }
}

/** 订阅分组的管理面板:检测这一组、插件声明的菜单(刷新订阅…)、删除整个订阅。 */
@Composable
private fun BoxScope.SourceGroupPanel(group: String, name: String, pluginId: String, onClose: () -> Unit, changed: () -> Unit) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var menus by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    LaunchedEffect(pluginId) { menus = runCatching { app.call("source.serverMenus", args("plugin_id" to pluginId)) }.getOrNull().arr().mapNotNull { it.obj() } }
    TvSidePanel(name, onClose) {
        PanelItem("检测这一组的源", sub = "打不开的会标红", onClick = {
            scope.launch {
                app.toast("正在检测…")
                runCatching { app.call("source.checkAll", args("group" to group)) }.onSuccess { r ->
                    val all = r.arr().mapNotNull { it.obj() }
                    val bad = all.count { !it.bool("ok") }
                    app.toast(if (bad == 0) "${all.size} 个源都能用" else "${all.size} 个源里 $bad 个打不开")
                    changed()
                }.onFailure { app.report(it) }
            }
        })
        menus.forEach { m ->
            PanelItem(m.str("title") ?: "", onClick = {
                scope.launch {
                    runCatching { app.call("source.runCommand", xyz.linplayer.app.ui.pages.j("plugin_id" to pluginId, "command" to m.str("command"), "args" to mapOf("group" to group))) }
                        .onSuccess { app.toast("完成"); changed() }.onFailure { app.report(it) }
                }
            })
        }
        PanelItem("删除整个订阅", sub = "收藏和观看记录保留", danger = true, onClick = {
            scope.launch {
                runCatching { app.call("source.removeGroup", args("group" to group)) }.onSuccess { onClose(); app.refreshSession(); changed() }.onFailure { app.report(it) }
            }
        })
    }
}
