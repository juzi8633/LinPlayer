package xyz.linplayer.app.ui.pages

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.toRoute
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import xyz.linplayer.app.core.toJsonObject
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.Block
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.ToastKind
import xyz.linplayer.app.data.arr
import xyz.linplayer.app.data.block
import xyz.linplayer.app.data.bool
import xyz.linplayer.app.data.dbl
import xyz.linplayer.app.data.obj
import xyz.linplayer.app.data.str
import xyz.linplayer.app.data.strList
import xyz.linplayer.app.ui.Route
import xyz.linplayer.app.ui.components.BlockBox
import xyz.linplayer.app.ui.components.Body
import xyz.linplayer.app.ui.components.BtnKind
import xyz.linplayer.app.ui.components.Dim2
import xyz.linplayer.app.ui.components.Dim3
import xyz.linplayer.app.ui.components.EmptyState
import xyz.linplayer.app.ui.components.H2
import xyz.linplayer.app.ui.components.Hairline
import xyz.linplayer.app.ui.components.LpButton
import xyz.linplayer.app.ui.components.LpCell
import xyz.linplayer.app.ui.components.LpDialog
import xyz.linplayer.app.ui.components.LpField
import xyz.linplayer.app.ui.components.LpScaffold
import xyz.linplayer.app.ui.components.LpTag
import xyz.linplayer.app.ui.components.OptRow
import xyz.linplayer.app.ui.components.ToneChip
import xyz.linplayer.app.ui.components.rememberScrolled
import xyz.linplayer.app.ui.theme.LpIcons
import xyz.linplayer.app.ui.theme.Sp

/** 带嵌套结构的参数(数组 / 对象)。`args` 只收一层标量,插件命令常要传整块 JSON。 */
internal fun j(vararg pairs: Pair<String, Any?>): JsonObject = toJsonObject(mapOf(*pairs))

/**
 * 插件页(SPEC 14.4):已安装 / 市场 / 接管位 / 仓库。这一页永远官方,不可接管(D29)。
 * 启停、装卸都是重启生效:离开本页时有改动就提示一次(D170)。
 */
@Composable
fun PluginsPage(nav: NavController, entry: NavBackStackEntry) {
    val app = LocalApp.current
    var tab by remember { mutableIntStateOf(entry.toRoute<Route.Plugins>().tab) }
    var reload by remember { mutableIntStateOf(0) }
    val list = rememberLazyListState()
    DisposableEffect(Unit) {
        onDispose {
            app.bg.launch {
                val pending = runCatching { app.call("plugin.pendingRestart") }.getOrNull()
                if ((pending as? JsonPrimitive)?.booleanOrNull == true) app.toast("插件有改动,重启应用后生效")
            }
        }
    }
    LpScaffold("插件", onBack = { nav.popBackStack() }, scrolled = rememberScrolled(list)) { pad ->
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Sp.x16, vertical = Sp.x8),
                horizontalArrangement = Arrangement.spacedBy(Sp.x6),
            ) {
                listOf("已安装", "市场", "接管位", "仓库").forEachIndexed { i, s -> ToneChip(s, on = i == tab) { tab = i } }
            }
            val bottom = PaddingValues(bottom = pad.calculateBottomPadding())
            when (tab) {
                0 -> InstalledTab(nav, list, bottom, reload) { reload++ }
                1 -> MarketTab(list, bottom, reload) { reload++ }
                2 -> TakeoverTab(list, bottom)
                else -> RepoTab(list, bottom, reload) { reload++ }
            }
        }
    }
}

@Composable
private fun InstalledTab(nav: NavController, list: androidx.compose.foundation.lazy.LazyListState, pad: PaddingValues, reload: Int, again: () -> Unit) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var block by remember { mutableStateOf<Block<JsonObject>>(Block.Loading) }
    var confirm by remember { mutableStateOf<Pair<String, JsonObject>?>(null) }
    LaunchedEffect(reload) { block = app.block("plugin.list", args("with_updates" to true)).map { it.obj() ?: JsonObject(emptyMap()) } }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val path = copyToCache(ctx, uri, "install.lpplugin") ?: run { app.toast("读不到这个文件", ToastKind.Error); return@launch }
            runCatching { app.call("plugin.inspect", args("path" to path)).obj()!! }
                .onSuccess { confirm = path to it }.onFailure { app.report(it) }
        }
    }
    confirm?.let { (path, ins) ->
        InstallConfirm(ins, onCancel = { confirm = null }) {
            confirm = null
            scope.launch {
                runCatching { app.call("plugin.installFile", args("path" to path)) }
                    .onSuccess { app.toast("装好了,重启应用后生效", ToastKind.Ok); again() }.onFailure { app.report(it) }
            }
        }
    }
    BlockBox(block, again) { r ->
        val plugins = r["plugins"].arr().mapNotNull { it.obj() }
        LazyColumn(Modifier.fillMaxSize(), list, contentPadding = pad) {
            if (r.bool("safe_mode")) item {
                Dim2("安全模式:上次启动连续崩溃,插件已全部关闭。" + (r.str("safe_suspect")?.takeIf { it.isNotEmpty() }?.let { "最可疑的是 $it。" } ?: ""),
                    Modifier.padding(Sp.x16))
            }
            if (r.bool("pending_restart")) item { Dim2("有改动,重启应用后生效。", Modifier.padding(horizontal = Sp.x16, vertical = Sp.x8)) }
            item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(Sp.x16), horizontalArrangement = Arrangement.spacedBy(Sp.x8)) {
                    LpButton("从文件安装", { pick.launch(arrayOf("*/*")) })
                    val ups = plugins.filter { !it.str("update").isNullOrEmpty() }.mapNotNull { it.str("id") }
                    if (ups.isNotEmpty()) LpButton("全部更新(${ups.size})", {
                        scope.launch {
                            runCatching { app.call("plugin.updateAll", j("ids" to ups)) }
                                .onSuccess { app.toast("更新完了,重启应用后生效", ToastKind.Ok); again() }.onFailure { app.report(it) }
                        }
                    }, kind = BtnKind.Secondary)
                    if (r.bool("disabled_all")) LpButton("恢复之前的启用状态", { scope.launch { runCatching { app.call("plugin.restoreAll") }; again() } }, kind = BtnKind.Secondary)
                    else if (plugins.any { it.bool("want") }) LpButton("一键全部禁用", { scope.launch { runCatching { app.call("plugin.disableAll") }; again() } }, kind = BtnKind.Secondary)
                }
            }
            if (plugins.isEmpty()) item { EmptyState("还没有安装插件", "去「市场」找一个,或者从文件安装。", LpIcons.plugin) }
            items(plugins, key = { it.str("id") ?: "" }) { p ->
                val id = p.str("id") ?: ""
                LpCell(
                    p.str("name") ?: id,
                    sub = listOfNotNull(p.str("version"), p.str("author"), statusText(p)).joinToString(" · "),
                    switch = p.bool("want"),
                    onSwitch = { on -> scope.launch { runCatching { app.call("plugin.setEnabled", args("id" to id, "enabled" to on)) }.onFailure { app.report(it) }; again() } },
                    onClick = { nav.navigate(Route.PluginDetail(id)) },
                )
                Hairline()
            }
        }
    }
}

private fun statusText(p: JsonObject): String? = when (p.str("status")) {
    "autoDisabled" -> "已自动禁用:" + (p.str("autoDisabled") ?: "")
    "update" -> "可更新到 " + p.str("update")
    "pendingRestart" -> if (p.bool("uninstall")) "重启后卸载" else "待重启"
    "dev" -> "开发版"
    else -> null
}

/** 安装确认(SPEC 14.5):贡献点清单 + 非官方来源 + 扩展组件 + 局域网 + 大小。 */
@Composable
private fun InstallConfirm(ins: JsonObject, onCancel: () -> Unit, onOk: () -> Unit) {
    LpDialog(onCancel, "安装 ${ins.str("name") ?: ""} ${ins.str("version") ?: ""}") {
        Column(verticalArrangement = Arrangement.spacedBy(Sp.x6)) {
            ins.str("incompatible")?.takeIf { it.isNotEmpty() }?.let { Body("装不了:$it"); LpButton("知道了", onCancel); return@Column }
            ins.str("installed")?.takeIf { it.isNotEmpty() }?.let { Dim2(if (ins.bool("otherSource")) "已从别的来源装过 $it,会被替换" else "覆盖已装的 $it") }
            if (ins.bool("unofficial")) Dim2("非官方来源:内容由第三方提供,官方不对其负责。")
            ins.strList("contributes").takeIf { it.isNotEmpty() }?.let { Dim2("它会:" + it.joinToString("、")) }
            ins.strList("components").takeIf { it.isNotEmpty() }?.let { Dim2("需要扩展组件:" + it.joinToString("、")) }
            if (ins.bool("lan")) Dim2("会访问你的局域网设备")
            Spacer(Modifier.height(Sp.x8))
            Row(horizontalArrangement = Arrangement.spacedBy(Sp.x8)) {
                LpButton("取消", onCancel, kind = BtnKind.Secondary)
                LpButton("安装", onOk)
            }
        }
    }
}

@Composable
private fun MarketTab(list: androidx.compose.foundation.lazy.LazyListState, pad: PaddingValues, reload: Int, again: () -> Unit) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var block by remember { mutableStateOf<Block<JsonObject>>(Block.Loading) }
    var q by remember { mutableStateOf("") }
    var official by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<JsonObject?>(null) }
    LaunchedEffect(reload) {
        noticeOnce(ctx, app, "market", "插件由第三方开发维护,官方不对插件内容负责。")
        block = app.block("plugin.market").map { it.obj() ?: JsonObject(emptyMap()) }
    }
    detail?.let { x -> MarketDetail(x, onClose = { detail = null }) { v ->
        detail = null
        scope.launch {
            runCatching { app.call("plugin.installFromRepo", args("repo" to (x.str("repo") ?: ""), "id" to (x.str("id") ?: ""), "version" to v)) }
                .onSuccess { app.toast("装好了,重启应用后生效", ToastKind.Ok); again() }.onFailure { app.report(it) }
        }
    } }
    BlockBox(block, again) { m ->
        val all = m["entries"].arr().mapNotNull { it.obj() }
        val shown = all.filter { e ->
            (q.isBlank() || listOf("name", "description", "author").any { e.str(it)?.contains(q, true) == true }) &&
                (!official || e.bool("officialMark"))
        }.sortedByDescending { it.dbl("downloads") ?: 0.0 }
        LazyColumn(Modifier.fillMaxSize(), list, contentPadding = pad) {
            item {
                Column(Modifier.padding(horizontal = Sp.x16), verticalArrangement = Arrangement.spacedBy(Sp.x8)) {
                    LpField(q, { q = it }, "搜索名称、描述、作者")
                    Row(horizontalArrangement = Arrangement.spacedBy(Sp.x6)) {
                        ToneChip("只看官方", on = official) { official = !official }
                        ToneChip("刷新", on = false) { scope.launch { runCatching { app.call("plugin.market", args("refresh" to true)) }; again() } }
                    }
                    m["stale"].obj()?.forEach { (repo, days) -> Dim3("$repo 拉取失败,用的是 ${days.jsonPrimitive.content} 天前的缓存", maxLines = 2) }
                    m["errors"].obj()?.forEach { (repo, why) -> Dim3("$repo:${why.jsonPrimitive.content}", maxLines = 2) }
                }
            }
            if (shown.isEmpty()) item { EmptyState(if (q.isBlank()) "市场里还没有插件" else "没找到「$q」", "", LpIcons.search) }
            items(shown, key = { it.str("id") ?: "" }) { e ->
                val bestV = bestVersion(e)
                val tag = when {
                    e.bool("needsUpgrade") -> "需要升级 LinPlayer"
                    e.str("installed")?.isNotEmpty() == true && e.str("installed") == bestV -> "已安装"
                    e.str("installed")?.isNotEmpty() == true -> "可更新"
                    else -> null
                }
                LpCell(e.str("name") ?: "", sub = listOfNotNull(bestV, e.str("author"), e.str("repoName"),
                    if (e.bool("officialMark")) "官方" else null, if (e.bool("paid")) "含付费功能" else null, tag).joinToString(" · "),
                    onClick = { detail = e })
                Hairline()
            }
        }
    }
}

/** 本机能装的最高版本(D450),在条目的 best 子对象里。 */
private fun bestVersion(e: JsonObject): String? = e["best"].obj().str("version")

@Composable
private fun MarketDetail(x: JsonObject, onClose: () -> Unit, onInstall: (String) -> Unit) {
    val best = x["best"].obj()
    LpDialog(onClose, x.str("name")) {
        Column(verticalArrangement = Arrangement.spacedBy(Sp.x6)) {
            Dim2(x.str("description") ?: "")
            x.strList("contributes").takeIf { it.isNotEmpty() }?.let { Dim2("贡献点:" + it.joinToString("、")) }
            if (!x.bool("officialMark")) Dim2("非官方来源:内容由第三方提供,官方不对其负责。")
            best?.let { Dim3("最低 LinPlayer ${it.str("minAppVersion") ?: ""} · 包 ${((it.dbl("size") ?: 0.0) / 1024).toInt()} KB", maxLines = 2) }
            best.str("changelog")?.takeIf { it.isNotEmpty() }?.let { Dim3("更新内容:$it", maxLines = 6) }
            Spacer(Modifier.height(Sp.x8))
            Row(horizontalArrangement = Arrangement.spacedBy(Sp.x8)) {
                LpButton("关闭", onClose, kind = BtnKind.Secondary)
                val v = best.str("version")
                when {
                    x.bool("needsUpgrade") -> LpButton("需要升级 LinPlayer", {}, enabled = false)
                    v == null -> Unit
                    x.str("installed") == v -> LpButton("已安装", {}, enabled = false)
                    else -> LpButton(if (x.str("installed").isNullOrEmpty()) "安装" else "更新", { onInstall(v) })
                }
            }
        }
    }
}

@Composable
private fun TakeoverTab(list: androidx.compose.foundation.lazy.LazyListState, pad: PaddingValues) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var block by remember { mutableStateOf<Block<JsonElement>>(Block.Loading) }
    LaunchedEffect(reload) { block = app.block("plugin.takeovers") }
    BlockBox(block, { reload++ }) { r ->
        val slots = r.arr().mapNotNull { it.obj() }
        LazyColumn(Modifier.fillMaxSize(), list, contentPadding = pad) {
            if (slots.isEmpty()) item { EmptyState("没有插件声明接管位", "全部是官方的。", LpIcons.plugin) }
            items(slots, key = { it.str("slot") ?: "" }) { s ->
                val slot = s.str("slot") ?: ""
                Column(Modifier.padding(Sp.x16)) {
                    H2(slotName(slot))
                    val cur = s.str("current") ?: ""
                    OptRow("官方", { scope.launch { runCatching { app.call("plugin.setTakeover", args("slot" to slot, "plugin_id" to "")) }; app.toast("重启应用后生效"); reload++ } }, selected = cur.isEmpty())
                    s["candidates"].arr().mapNotNull { it.obj() }.forEach { c ->
                        val pid = c.str("plugin_id") ?: ""
                        OptRow(c.str("name") ?: pid, { scope.launch { runCatching { app.call("plugin.setTakeover", args("slot" to slot, "plugin_id" to pid)) }; app.toast("重启应用后生效"); reload++ } }, selected = cur == pid)
                    }
                }
            }
        }
    }
}

private fun slotName(slot: String) = if (slot.startsWith("page:")) when (slot.removePrefix("page:")) {
    "home" -> "首页"; "detail" -> "详情页"; "ranking" -> "排行榜页"; "calendar" -> "追剧日历"; else -> "页面 " + slot.removePrefix("page:")
} else slot

@Composable
private fun RepoTab(list: androidx.compose.foundation.lazy.LazyListState, pad: PaddingValues, reload: Int, again: () -> Unit) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var block by remember { mutableStateOf<Block<JsonObject>>(Block.Loading) }
    var url by remember { mutableStateOf("") }
    var askAdd by remember { mutableStateOf(false) }
    LaunchedEffect(reload) { block = app.block("plugin.repos").map { it.obj() ?: JsonObject(emptyMap()) } }
    BlockBox(block, again) { r ->
        var prefix by remember(r) { mutableStateOf(r.str("github_prefix") ?: "") }
        LazyColumn(Modifier.fillMaxSize(), list, contentPadding = pad) {
            items(r["repos"].arr().mapNotNull { it.obj() }, key = { it.str("url") ?: "" }) { repo ->
                val u = repo.str("url") ?: ""
                LpCell(repo.str("name")?.takeIf { it.isNotEmpty() } ?: u, sub = u, arrow = false,
                    onClick = if (repo.bool("official")) null else ({ scope.launch { runCatching { app.call("plugin.removeRepo", args("url" to u)) }; app.toast("已删除"); again() } }))
                Hairline()
            }
            item {
                Column(Modifier.padding(Sp.x16), verticalArrangement = Arrangement.spacedBy(Sp.x8)) {
                    Dim3("点第三方仓库那一行即删除;官方市场不能删。", maxLines = 2)
                    LpField(url, { url = it }, "仓库地址(index.json 所在目录或文件)")
                    LpButton("添加", { if (url.isNotBlank()) askAdd = true })
                    Spacer(Modifier.height(Sp.x16))
                    LpField(prefix, { prefix = it }, "GitHub 加速前缀(可留空)", label = "下载")
                    LpButton("保存前缀", { scope.launch { runCatching { app.call("plugin.setGithubPrefix", args("prefix" to prefix.trim())) }; app.toast("已保存") } }, kind = BtnKind.Secondary)
                }
                LpCell("自动更新插件", switch = r.bool("auto_update"), onSwitch = { on -> scope.launch { runCatching { app.call("plugin.setAutoUpdate", args("on" to on)) }; again() } })
            }
        }
    }
    if (askAdd) LpDialog({ askAdd = false }, "添加第三方仓库") {
        Column(verticalArrangement = Arrangement.spacedBy(Sp.x8)) {
            Dim2("这个仓库不是官方维护的,里面的插件由第三方开发,官方不对其内容负责。")
            Row(horizontalArrangement = Arrangement.spacedBy(Sp.x8)) {
                LpButton("取消", { askAdd = false }, kind = BtnKind.Secondary)
                LpButton("添加", {
                    askAdd = false
                    scope.launch { runCatching { app.call("plugin.addRepo", args("url" to url.trim())) }.onSuccess { url = ""; again() }.onFailure { app.report(it) } }
                })
            }
        }
    }
}

/** 已装插件详情(SPEC 14.4 末段):设置、贡献点、占用、版本操作、卸载、错误详情。 */
@Composable
fun PluginDetailPage(nav: NavController, entry: NavBackStackEntry) {
    val id = entry.toRoute<Route.PluginDetail>().id
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val list = rememberLazyListState()
    var reload by remember { mutableIntStateOf(0) }
    var block by remember { mutableStateOf<Block<JsonObject>>(Block.Loading) }
    LaunchedEffect(reload) { block = app.block("plugin.detail", args("id" to id)).map { it.obj() ?: JsonObject(emptyMap()) } }
    fun act(cmd: String, vararg a: Pair<String, Any>, done: String? = null) = scope.launch {
        runCatching { app.call(cmd, args("id" to id, *a)) }.onSuccess { done?.let { app.toast(it) }; reload++ }.onFailure { app.report(it) }
    }
    val info = (block as? Block.Ok)?.value?.get("info").obj()
    LpScaffold(info.str("name") ?: id, subtitle = info.str("version"), onBack = { nav.popBackStack() }, scrolled = rememberScrolled(list)) { pad ->
        BlockBox(block, { reload++ }) { d ->
            val vals = d["values"].obj()
            LazyColumn(Modifier.fillMaxSize(), list, contentPadding = PaddingValues(bottom = pad.calculateBottomPadding())) {
                (info.dbl("loadMs") ?: 0.0).takeIf { it > 0 }?.let { item { Dim3("启动耗时 ${it.toInt()} ms" + if (it > 500) "(偏慢)" else "", Modifier.padding(Sp.x16)) } }
                val settings = d["settings"].arr().mapNotNull { it.obj() }
                if (settings.isNotEmpty()) item { H2("设置", Modifier.padding(Sp.x16)) }
                items(settings, key = { it.str("key") ?: "" }) { s -> SettingRow(app, id, s, vals?.get(s.str("key") ?: "") ?: s["default"]) }
                item {
                    d.strList("contributes").takeIf { it.isNotEmpty() }?.let {
                        H2("它做了什么", Modifier.padding(Sp.x16)); Dim2(it.joinToString("、"), Modifier.padding(horizontal = Sp.x16))
                    }
                    val u = d["usage"].obj()
                    H2("占用", Modifier.padding(Sp.x16))
                    Dim2("数据 ${size((u.dbl("kv") ?: 0.0) + (u.dbl("data") ?: 0.0))} · 缓存 ${size(u.dbl("cache") ?: 0.0)}", Modifier.padding(horizontal = Sp.x16))
                    LpCell("清缓存", onClick = { act("plugin.clearData", "cache_only" to true, done = "已清缓存") })
                    LpCell("清数据", sub = "插件保存的设置、登录状态都会清掉", onClick = { act("plugin.clearData", "cache_only" to false, done = "已清数据") })
                    H2("版本与管理", Modifier.padding(Sp.x16))
                    info.str("prev")?.takeIf { it.isNotEmpty() }?.let { p -> LpCell("回退到 $p", onClick = { act("plugin.rollback", done = "重启应用后生效") }) }
                    info.str("update")?.takeIf { it.isNotEmpty() }?.let { v -> LpCell("跳过 $v", onClick = { act("plugin.skipVersion", "version" to v) }) }
                    val locked = info.bool("locked")
                    LpCell(if (locked) "解锁自动更新" else "锁定当前版本", onClick = { act("plugin.setLocked", "locked" to !locked) })
                    LpCell("复制错误详情", sub = "只在本机内存里,不会自动上传", onClick = {
                        scope.launch {
                            runCatching { app.call("plugin.errorDetail", args("id" to id)) }.onSuccess {
                                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("LinPlayer 插件错误详情", it.toString()))
                                app.toast("已复制")
                            }.onFailure { app.report(it) }
                        }
                    })
                    if (info.bool("uninstall")) LpCell("撤销卸载", onClick = { act("plugin.cancelUninstall") })
                    else LpCell("卸载", sub = "重启后卸载,插件数据保留", onClick = { act("plugin.uninstall", "delete_data" to false, done = "重启应用后卸载") })
                }
            }
        }
    }
}

/** manifest 设置项的一行。password 值存密钥区(核心层管),这里只当普通文本框画。 */
@Composable
private fun SettingRow(app: AppState, id: String, s: JsonObject, cur: JsonElement?) {
    val scope = rememberCoroutineScope()
    val key = s.str("key") ?: return
    val title = s.str("title")?.takeIf { it.isNotEmpty() } ?: key
    fun save(v: Any?) = scope.launch {
        runCatching { app.call("plugin.setSetting", j("id" to id, "key" to key, "value" to v)) }.onFailure { app.report(it) }
    }
    when (s.str("type")) {
        "toggle" -> LpCell(title, sub = s.str("description"), switch = (cur as? JsonPrimitive)?.booleanOrNull == true, onSwitch = { save(it) })
        "select" -> {
            val opts = s["options"].arr().mapNotNull { it.obj() }
            Column(Modifier.padding(horizontal = Sp.x16)) {
                Body(title)
                opts.forEach { o ->
                    val v = o.str("value") ?: ""
                    var picked by remember { mutableStateOf((cur as? JsonPrimitive)?.content == v) }
                    OptRow(o.str("label") ?: v, { save(v); picked = true }, selected = picked)
                }
            }
        }
        "button" -> LpCell(title, sub = s.str("description"), onClick = {
            scope.launch {
                runCatching { app.call("source.runCommand", args("plugin_id" to id, "command" to (s.str("action") ?: ""))) }
                    .onSuccess { app.toast("完成") }.onFailure { app.report(it) }
            }
        })
        "group" -> H2(title, Modifier.padding(Sp.x16))
        "text", "password", "number", "slider" -> {
            var v by remember { mutableStateOf((cur as? JsonPrimitive)?.content ?: "") }
            Column(Modifier.padding(horizontal = Sp.x16, vertical = Sp.x6)) {
                LpField(v, { v = it }, s.str("description") ?: "", label = title, password = s.str("type") == "password",
                    lines = if (s.bool("multiline")) 4 else 1)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    LpButton("保存", { save(if (s.str("type") in listOf("number", "slider")) v.toDoubleOrNull() ?: 0.0 else v) }, kind = BtnKind.Secondary)
                }
            }
        }
        // multiselect 这类复杂项阶段 ① 不画:画出来点了不生效比没有更糟
        else -> Unit
    }
}

private fun size(b: Double) = if (b < 1024 * 1024) "${(b / 1024).toInt()} KB" else "%.1f MB".format(b / 1024 / 1024)

/**
 * 扩展组件页(SPEC 18.5)。安卓上 jar 走系统 DexClassLoader、不用下组件;
 * Python 与 GeckoView 还没有发行包,如实列出「谁需要」,不摆点不动的下载键。
 */
@Composable
fun ExtensionsPage(nav: NavController) {
    val app = LocalApp.current
    var users by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    LaunchedEffect(Unit) {
        val r = runCatching { app.call("plugin.list") }.getOrNull().obj()
        users = r?.get("plugins").arr().mapNotNull { it.obj() }.flatMap { p -> p.strList("components").map { it to (p.str("name") ?: "") } }
            .groupBy({ it.first }, { it.second })
    }
    LpScaffold("扩展组件", onBack = { nav.popBackStack() }) { pad ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = pad.calculateBottomPadding())) {
            item { Dim2("插件用到时才会提示下载,安装插件时不强制下。", Modifier.padding(Sp.x16)) }
            val rows = listOf(
                Triple("jar-runtime", "TVBox jar 运行时", "安卓用系统自带的类加载器,不需要下载"),
                Triple("python", "Python 运行时", "还没有发行包"),
                Triple("geckoview", "GeckoView 内核", "系统 WebView 缺失或过旧时用;还没有发行包"),
                Triple("whisper", "Whisper 转写", "还没有发行包"),
            )
            items(rows, key = { it.first }) { (id, name, state) ->
                LpCell(name, sub = state + (users[id]?.let { " · 需要它的插件:" + it.joinToString("、") } ?: ""), arrow = false)
                Hairline()
            }
        }
    }
}

/** 一次性免责提示(SPEC 14.6),记在应用私有目录的标记文件里。 */
internal suspend fun noticeOnce(ctx: Context, app: AppState, key: String, text: String) {
    val f = java.io.File(ctx.filesDir, "notice-$key")
    if (f.exists()) return
    app.toast(text)
    runCatching { f.writeText("") }
}

/** SAF 选的文件拷进缓存目录:核心层只认真实路径。 */
internal fun copyToCache(ctx: Context, uri: Uri, name: String): String? = runCatching {
    val out = java.io.File(ctx.cacheDir, name)
    ctx.contentResolver.openInputStream(uri)!!.use { i -> out.outputStream().use { i.copyTo(it) } }
    out.absolutePath
}.getOrNull()
