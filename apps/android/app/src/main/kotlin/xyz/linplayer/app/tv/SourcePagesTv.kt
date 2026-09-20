package xyz.linplayer.app.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.ToastKind
import xyz.linplayer.app.data.arr
import xyz.linplayer.app.data.bool
import xyz.linplayer.app.data.dbl
import xyz.linplayer.app.data.long
import xyz.linplayer.app.data.obj
import xyz.linplayer.app.data.str
import xyz.linplayer.app.data.strList
import xyz.linplayer.app.tv.kit.CardPoster
import xyz.linplayer.app.tv.kit.FullState
import xyz.linplayer.app.tv.kit.PageHead
import xyz.linplayer.app.tv.kit.PanelItem
import xyz.linplayer.app.tv.kit.RowTitle
import xyz.linplayer.app.tv.kit.ScopeChips
import xyz.linplayer.app.tv.kit.TvButton
import xyz.linplayer.app.tv.kit.TvC
import xyz.linplayer.app.tv.kit.TvDim
import xyz.linplayer.app.tv.kit.TvSp
import xyz.linplayer.app.tv.kit.TvText
import xyz.linplayer.app.tv.kit.contentArea
import xyz.linplayer.app.tv.kit.tvType
import xyz.linplayer.app.ui.pages.args
import xyz.linplayer.app.ui.pages.episodesOf
import xyz.linplayer.app.ui.pages.img
import xyz.linplayer.app.ui.pages.j
import xyz.linplayer.app.ui.pages.linesOf
import xyz.linplayer.app.ui.pages.srcErr
import xyz.linplayer.app.ui.pages.yearText
import xyz.linplayer.app.ui.theme.LpIcons

/** 数据源卡片(TV 海报版)。 */
@Composable
internal fun SourcePoster(item: JsonObject, key: String, badge: String? = null, onClick: () -> Unit) {
    val app = LocalApp.current
    CardPoster(
        cover = { TvImage(app.proxiedImage(item.img("poster"), 330)) },
        title = item.str("title") ?: "", sub = listOfNotNull(item.yearText().ifEmpty { null }, badge ?: item.str("remarks")).joinToString(" · "),
        modifier = Modifier.memo(key), onClick = onClick,
    )
}

/** 数据源首页(TV):继续观看 → 分类入口 → 推荐。**没有源内搜索框**(D554):
 *  遥控器敲字慢,这件事交给搜索页 —— 聚合搜索按源分行,行头就写着来源名。 */
@Composable
fun SourceHomeTv(serverId: String, name: String) {
    val app = LocalApp.current
    val nav = LocalNav.current
    var reload by remember { mutableIntStateOf(0) }
    var home by remember(serverId) { mutableStateOf<Result<JsonObject?>?>(null) }
    var cont by remember(serverId) { mutableStateOf<List<JsonObject>>(emptyList()) }
    LaunchedEffect(serverId, reload) {
        launch { cont = runCatching { app.call("source.continueWatching", args("server_id" to serverId)) }.getOrNull().arr().mapNotNull { it.obj() } }
        val caps = runCatching { app.call("source.caps", args("server_id" to serverId)) }.getOrNull().obj()
        home = if (caps.bool("home")) runCatching { app.call("source.home", args("server_id" to serverId)).obj() } else Result.success(null)
    }
    val h = home
    if (h?.isFailure == true) {
        val e = srcErr(h.exceptionOrNull()!!)
        FullState(LpIcons.info, "没加载出来", detail = e.advice, buttons = listOf("重试"), tone = TvC.bad, onButton = { reload++ })
        return
    }
    LazyColumn(Modifier.contentArea(), verticalArrangement = Arrangement.spacedBy(TvSp.x16)) {
        item { PageHead(name) }
        if (cont.isNotEmpty()) item {
            RowTitle("继续观看")
            Spacer(Modifier.height(TvSp.x8))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(TvSp.x12)) {
                items(cont.take(12)) { c ->
                    val r = c["ref"].obj() ?: return@items
                    val it = r["item"].obj() ?: return@items
                    SourcePoster(it, "src.cont.${it.str("id")}", r.str("episodeName")) {
                        nav.push(TvRoute.SourceDetail(serverId, it.str("id") ?: "", r.str("lineId"), r.str("episodeId"), c.dbl("position_secs") ?: 0.0))
                    }
                }
            }
        }
        val x = h?.getOrNull()
        val cats = x?.get("categories").arr().mapNotNull { it.obj() }
        if (cats.isNotEmpty()) item {
            RowTitle("分类")
            Spacer(Modifier.height(TvSp.x8))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(TvSp.x8)) {
                items(cats) { c -> TvButton(c.str("name") ?: "", modifier = Modifier.memo("src.cat.${c.str("id")}")) { nav.push(TvRoute.SourceCategory(serverId, c.toString())) } }
            }
        }
        val rec = x?.get("recommended").arr().mapNotNull { it.obj() }
        if (rec.isNotEmpty()) item {
            RowTitle("推荐")
            Spacer(Modifier.height(TvSp.x8))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(TvSp.x12)) {
                items(rec) { it -> SourcePoster(it, "src.rec.${it.str("id")}") { nav.push(TvRoute.SourceDetail(it.str("source") ?: serverId, it.str("id") ?: "")) } }
            }
        }
        if (h != null && cats.isEmpty() && rec.isEmpty() && cont.isEmpty()) item {
            TvText("这个源没有首页,去「搜索」里找想看的。", tvType.body, TvC.fg2)
        }
    }
}

/** 分类页(TV):筛选维度一行一排按钮,滚到底翻页。 */
@Composable
fun SourceCategoryTv(r: TvRoute.SourceCategory) {
    val app = LocalApp.current
    val nav = LocalNav.current
    val cat = remember(r.cat) { Json.parseToJsonElement(r.cat).obj() }
    val grid = rememberLazyGridState()
    val items = remember { mutableStateListOf<JsonObject>() }
    val filters = remember { androidx.compose.runtime.mutableStateMapOf<String, List<String>>() }
    var next by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var gen by remember { mutableIntStateOf(0) }
    suspend fun more(g: Int) {
        if (busy || done) return
        busy = true
        runCatching {
            app.call("source.category", j("server_id" to r.serverId, "category_id" to cat.str("id"),
                "filters" to filters.filterValues { it.isNotEmpty() }, "cursor" to next)).obj()
        }.onSuccess { p ->
            if (g != gen) return@onSuccess
            items.addAll(p?.get("items").arr().mapNotNull { it.obj() })
            next = p.str("next")?.takeIf { it.isNotEmpty() }
            done = next == null
        }.onFailure { if (g == gen) err = srcErr(it).advice }
        busy = false
    }
    LaunchedEffect(gen) { items.clear(); next = null; done = false; err = null; more(gen) }
    val atEnd by remember { derivedStateOf { (grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= grid.layoutInfo.totalItemsCount - 8 } }
    LaunchedEffect(Unit) { snapshotFlow { atEnd }.collect { if (it && items.isNotEmpty()) more(gen) } }
    LazyVerticalGrid(GridCells.Adaptive(TvDim.posterW + 14.dp), Modifier.contentArea(), grid,
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(TvSp.x16)) {
        item(span = { GridItemSpan(maxLineSpan) }) { PageHead(cat.str("name") ?: "分类") }
        cat?.get("filters").arr().mapNotNull { it.obj() }.forEach { d ->
            val key = d.str("key") ?: return@forEach
            item(span = { GridItemSpan(maxLineSpan) }) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(TvSp.x6)) {
                    item { TvText(d.str("name") ?: "", tvType.meta, TvC.fg3, Modifier.width(56.dp)) }
                    items(d["options"].arr().mapNotNull { it.obj() }) { o ->
                        val v = o.str("value") ?: ""
                        val cur = filters[key] ?: emptyList()
                        TvButton(o.str("name") ?: v, primary = if (v.isEmpty()) cur.isEmpty() else v in cur) {
                            filters[key] = when { v.isEmpty() -> emptyList(); d.bool("multi") -> if (v in cur) cur - v else cur + v; else -> listOf(v) }
                            gen++
                        }
                    }
                }
            }
        }
        items(items) { it -> SourcePoster(it, "src.catitem.${it.str("id")}") { nav.push(TvRoute.SourceDetail(it.str("source") ?: r.serverId, it.str("id") ?: "")) } }
        err?.let { e -> item(span = { GridItemSpan(maxLineSpan) }) { TvText(e, tvType.body, TvC.fg2) } }
        if (done && items.isEmpty() && err == null) item(span = { GridItemSpan(maxLineSpan) }) { TvText("这个分类下没有内容", tvType.body, TvC.fg2) }
    }
}

/** 数据源详情(TV):线路一排、集一格一格;换源在侧面板里(D232)。 */
@Composable
fun SourceDetailTv(r: TvRoute.SourceDetail) {
    val app = LocalApp.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val overlay = rememberOverlay()
    var reload by remember { mutableIntStateOf(0) }
    var d by remember { mutableStateOf<Result<JsonObject>?>(null) }
    var line by remember { mutableStateOf("") }
    var autoDone by remember { mutableStateOf(false) }
    LaunchedEffect(reload) {
        d = runCatching { app.call("source.detail", args("server_id" to r.serverId, "item_id" to r.itemId)).obj()!! }
        val x = d?.getOrNull() ?: return@LaunchedEffect
        val lines = linesOf(x)
        line = r.autoLine?.takeIf { l -> lines.any { it.str("id") == l } }
            ?: lines.firstOrNull { it.str("name") == x.str("lastLine") }?.str("id") ?: lines.firstOrNull()?.str("id") ?: ""
    }
    fun play(x: JsonObject, lineId: String, ep: JsonObject, pos: Double) {
        val src = j("server_id" to r.serverId, "item" to x, "line_id" to lineId, "episode_id" to ep.str("id"), "resume_secs" to pos)
        nav.push(TvRoute.Player(r.itemId, "${x.str("title") ?: ""} ${ep.str("name") ?: ""}".trim(), src = src.toString()))
    }
    val x = d?.getOrNull()
    LaunchedEffect(x, line) {
        if (x == null || line.isEmpty() || autoDone || (r.autoEp == null && r.autoIndex == 0)) return@LaunchedEffect
        autoDone = true
        val eps = episodesOf(x, line)
        val ep = eps.firstOrNull { it.str("id") == r.autoEp } ?: eps.firstOrNull { r.autoIndex > 0 && (it.long("index") ?: 0L).toInt() == r.autoIndex } ?: eps.singleOrNull()
        if (ep == null) app.toast("上次看的那一集不在了,请手动选") else play(x, line, ep, r.autoPos)
    }
    Box(Modifier.fillMaxSize()) {
        val res = d
        when {
            res == null -> Unit
            res.isFailure -> FullState(LpIcons.info, "没加载出来", detail = srcErr(res.exceptionOrNull()!!).advice,
                buttons = listOf("重试", "换源"), tone = TvC.bad, onButton = { if (it == 0) reload++ else overlay.open { SwitchPanelTv(r.serverId, JsonObject(mapOf("id" to kotlinx.serialization.json.JsonPrimitive(r.itemId))), "", 0) { overlay.close() } } })
            x != null -> LazyColumn(Modifier.contentArea(), verticalArrangement = Arrangement.spacedBy(TvSp.x12)) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(TvSp.x20)) {
                        TvImage(app.proxiedImage(x.img("poster"), 330), Modifier.width(160.dp).height(240.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TvSp.x8)) {
                            PageHead(x.str("title") ?: "")
                            TvText(listOf(x.yearText(), x.strList("countries").joinToString(" "), x.strList("genres").joinToString(" "), x.str("remarks") ?: "")
                                .filter { it.isNotEmpty() }.joinToString(" · "), tvType.meta, TvC.fg2, maxLines = 2)
                            x.str("overview")?.let { TvText(it, tvType.body, TvC.fg2, maxLines = 4) }
                            Row(horizontalArrangement = Arrangement.spacedBy(TvSp.x8)) {
                                TvButton("收藏", LpIcons.heart) {
                                    scope.launch {
                                        runCatching { app.call("source.setFavorite", j("server_id" to r.serverId, "item" to x, "favorite" to true)) }
                                            .onSuccess { app.toast("已收藏", ToastKind.Ok) }.onFailure { app.report(it) }
                                    }
                                }
                                TvButton("换源") { overlay.open { SwitchPanelTv(r.serverId, x, line, 0) { overlay.close() } } }
                            }
                        }
                    }
                }
                val lines = linesOf(x)
                if (lines.size > 1) item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(TvSp.x8)) {
                        items(lines) { l -> TvButton("${l.str("name")} · ${l["episodes"].arr().size}", primary = l.str("id") == line) { line = l.str("id") ?: "" } }
                    }
                }
                val eps = episodesOf(x, line)
                items(eps.chunked(6)) { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(TvSp.x8)) {
                        row.forEach { ep -> TvButton(ep.str("name") ?: "", modifier = Modifier.weight(1f).memo("src.ep.${ep.str("id")}")) { play(x, line, ep, 0.0) } }
                        repeat(6 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        OverlayHost(overlay)
    }
}

/** 换源侧面板(TV):候选边找边出,选数据源的记换源链路(D431)。 */
@Composable
internal fun androidx.compose.foundation.layout.BoxScope.SwitchPanelTv(serverId: String, item: JsonObject, lineId: String, episodeIndex: Int, onClose: () -> Unit) {
    val app = LocalApp.current
    val nav = LocalNav.current
    val cands = remember { mutableStateListOf<JsonObject>() }
    var done by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val all = runCatching {
            app.call("source.switchCandidates", j("server_id" to serverId, "item" to item, "line_id" to lineId,
                "episode_index" to episodeIndex, "title" to item.str("title"), "year" to (item.dbl("year") ?: 0.0)))
        }.getOrNull().arr().mapNotNull { it.obj() }
        cands.clear(); cands.addAll(all); done = true
    }
    TvSidePanel("换源", onClose) {
        if (!done) TvText("找其它源…", tvType.meta, TvC.fg3)
        else if (cands.isEmpty()) TvText("别的源里都没找到这部片。", tvType.meta, TvC.fg3, maxLines = 3)
        cands.forEach { c ->
            val layer = (c.long("layer") ?: 0L).toInt()
            val label = when (layer) {
                1 -> c.str("line_name") ?: ""
                3 -> c["emby_item"].obj().str("name") ?: ""
                else -> c["item"].obj().str("title") ?: ""
            }
            PanelItem(label, sub = c.str("server_name") ?: "本源其它线路", chevron = true, onClick = {
                onClose()
                val target = c.str("server_id") ?: ""
                when (layer) {
                    1 -> nav.push(TvRoute.Player(item.str("id") ?: "", item.str("title") ?: "",
                        src = j("server_id" to serverId, "item" to item, "line_id" to c.str("line_id"), "episode_id" to c.str("episode_id")).toString()))
                    3 -> app.bg.launch {
                        runCatching { app.call("account.setActiveServer", args("server_id" to target)) }.onSuccess {
                            app.refreshSession(); val e = c["emby_item"].obj(); nav.push(TvRoute.Detail(e.str("id") ?: "", e.str("type_") ?: "Series"))
                        }.onFailure { app.report(it) }
                    }
                    else -> {
                        val it = c["item"].obj()
                        app.bg.launch { runCatching { app.call("source.linkSwitch", args("from_server" to serverId, "from_item" to (item.str("id") ?: ""), "to_server" to target, "to_item" to (it.str("id") ?: ""))) } }
                        nav.push(TvRoute.SourceDetail(target, it.str("id") ?: "", autoIndex = episodeIndex))
                    }
                }
            })
        }
    }
}

private suspend fun loadInstalled(app: xyz.linplayer.app.data.AppState) =
    runCatching { app.call("plugin.list") }.getOrNull().obj()?.get("plugins").arr().mapNotNull { it.obj() }

/** 市场里本机还没装、也能装的。 */
private suspend fun loadMarket(app: xyz.linplayer.app.data.AppState) =
    runCatching { app.call("plugin.market") }.getOrNull().obj()?.get("entries").arr().mapNotNull { it.obj() }
        .filter { it.str("installed").isNullOrEmpty() && !it.bool("needsUpgrade") }

/** 接管位的中文名(SPEC 14.4)。和手机端 `PluginPages.kt` 的 `slotName` 同一张表。 */
private fun slotNameTv(slot: String) = if (slot.startsWith("page:")) when (slot.removePrefix("page:")) {
    "home" -> "首页"; "detail" -> "详情页"; "ranking" -> "排行榜页"; "calendar" -> "追剧日历"
    else -> "页面 " + slot.removePrefix("page:")
} else slot

/**
 * 插件页(TV,SPEC 14.7):已安装 / 市场 / 接管位 / 仓库,和手机端同四格。
 *
 * ★ 仓库页不给「GitHub 加速前缀」那一栏:遥控器上敲一串 URL 前缀不是能用的交互,
 *   它在手机和电脑上设一次就同步过来了(同一份 `plugin.setGithubPrefix`)。
 */
@Composable
fun PluginsPageTv() {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val overlay = rememberOverlay()
    var tab by remember { mutableIntStateOf(0) }
    var reload by remember { mutableIntStateOf(0) }
    var installed by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var market by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var slots by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var repos by remember { mutableStateOf<JsonObject?>(null) }
    LaunchedEffect(reload) { installed = loadInstalled(app) }
    LaunchedEffect(reload) { market = loadMarket(app) }
    LaunchedEffect(reload) { slots = runCatching { app.call("plugin.takeovers") }.getOrNull().arr().mapNotNull { it.obj() } }
    LaunchedEffect(reload) { repos = runCatching { app.call("plugin.repos") }.getOrNull().obj() }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.contentArea()) {
            PageHead("插件", sub = "启停、安装、接管都是重启应用后生效")
            Spacer(Modifier.height(TvSp.x12))
            ScopeChips(listOf("已安装", "市场", "接管位", "仓库"), tab,
                itemModifier = { i -> Modifier.memo("plug.tab.$i", initial = i == 0) }, onSelect = { tab = it })
            Spacer(Modifier.height(TvSp.x12))
            LazyColumn(contentPadding = PaddingValues(bottom = TvSp.x20)) {
                when (tab) {
                    0 -> {
                        if (installed.isEmpty()) item { TvText("还没有安装插件", tvType.meta, TvC.fg3) }
                        items(installed, key = { "i:" + (it.str("id") ?: "") }) { p ->
                            val id = p.str("id") ?: ""
                            PanelItem(p.str("name") ?: id, sub = listOfNotNull(p.str("version"), p.str("author"), p.str("status")?.takeIf { it != "ok" }).joinToString(" · "),
                                switch = p.bool("want"), modifier = Modifier.memo("plug.$id"), onClick = {
                                    scope.launch { runCatching { app.call("plugin.setEnabled", args("id" to id, "enabled" to !p.bool("want"))) }.onFailure { app.report(it) }; reload++ }
                                })
                        }
                    }
                    1 -> {
                        if (market.isEmpty()) item { TvText("没有可装的插件(或市场没连上)", tvType.meta, TvC.fg3) }
                        items(market, key = { "m:" + (it.str("id") ?: "") }) { e ->
                            val v = e["best"].obj().str("version") ?: ""
                            PanelItem(e.str("name") ?: "", sub = listOfNotNull(v, e.str("author"), if (e.bool("officialMark")) "官方" else "非官方来源").joinToString(" · "),
                                chevron = true, modifier = Modifier.memo("plug.m.${e.str("id")}"), onClick = {
                                    scope.launch {
                                        runCatching { app.call("plugin.installFromRepo", args("repo" to (e.str("repo") ?: ""), "id" to (e.str("id") ?: ""), "version" to v)) }
                                            .onSuccess { app.toast("装好了,重启应用后生效", ToastKind.Ok); reload++ }.onFailure { app.report(it) }
                                    }
                                })
                        }
                    }
                    2 -> {
                        if (slots.isEmpty()) item { TvText("没有插件声明接管位 —— 这几页现在全是官方的", tvType.meta, TvC.fg3) }
                        items(slots, key = { "s:" + (it.str("slot") ?: "") }) { s ->
                            val slot = s.str("slot") ?: ""
                            val cur = s.str("current") ?: ""
                            fun pick(pid: String) = scope.launch {
                                runCatching { app.call("plugin.setTakeover", args("slot" to slot, "plugin_id" to pid)) }
                                    .onSuccess { app.toast("重启应用后生效", ToastKind.Ok); reload++ }.onFailure { app.report(it) }
                            }
                            Column {
                                RowTitle(slotNameTv(slot))
                                PanelItem("官方", selected = cur.isEmpty(), modifier = Modifier.memo("plug.t.$slot.official"), onClick = { pick("") })
                                s["candidates"].arr().mapNotNull { it.obj() }.forEach { c ->
                                    val pid = c.str("plugin_id") ?: ""
                                    PanelItem(c.str("name") ?: pid, selected = cur == pid,
                                        modifier = Modifier.memo("plug.t.$slot.$pid"), onClick = { pick(pid) })
                                }
                                Spacer(Modifier.height(TvSp.x12))
                            }
                        }
                    }
                    else -> {
                        val r = repos
                        items(r?.get("repos").arr().mapNotNull { it.obj() }, key = { "r:" + (it.str("url") ?: "") }) { repo ->
                            val u = repo.str("url") ?: ""
                            val official = repo.bool("official")
                            PanelItem(repo.str("name")?.takeIf { it.isNotEmpty() } ?: u, sub = u,
                                value = if (official) "官方" else null,
                                modifier = Modifier.memo("plug.r.$u"), onClick = {
                                    // 官方市场不能删,点它只说一声;第三方走确认框(删了要重新输一遍长地址)
                                    if (official) { app.toast("官方市场不能删除"); return@PanelItem }
                                    overlay.open {
                                        TvConfirm("删除这个仓库?", u, "删除", onCancel = { overlay.close() }, onConfirm = {
                                            scope.launch {
                                                runCatching { app.call("plugin.removeRepo", args("url" to u)) }
                                                    .onSuccess { overlay.close(); reload++ }.onFailure { app.report(it) }
                                            }
                                        })
                                    }
                                })
                        }
                        item {
                            PanelItem("添加第三方仓库", sub = "里面的插件由第三方开发,官方不对其内容负责",
                                chevron = true, modifier = Modifier.memo("plug.r.add"), onClick = {
                                    overlay.open { AddRepoPanel(overlay) { reload++ } }
                                })
                        }
                        item {
                            PanelItem("自动更新插件", switch = r.bool("auto_update"), modifier = Modifier.memo("plug.r.auto"), onClick = {
                                scope.launch {
                                    runCatching { app.call("plugin.setAutoUpdate", args("on" to !r.bool("auto_update"))) }
                                        .onSuccess { reload++ }.onFailure { app.report(it) }
                                }
                            })
                        }
                    }
                }
            }
        }
        OverlayHost(overlay)
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.AddRepoPanel(overlay: Overlay, onAdded: () -> Unit) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    TvSidePanel("添加第三方仓库", { overlay.close() }) {
        Box(Modifier.padding(horizontal = TvSp.x6)) {
            TvTextField(url, { url = it }, "index.json 所在目录或文件", 218.dp, Modifier.memo("plug.r.url", initial = true))
        }
        PanelItem("添加", onClick = {
            if (url.isBlank()) return@PanelItem
            scope.launch {
                runCatching { app.call("plugin.addRepo", args("url" to url.trim())) }
                    .onSuccess { overlay.close(); app.toast("已添加", ToastKind.Ok); onAdded() }.onFailure { app.report(it) }
            }
        })
    }
}

/**
 * 扩展组件页(SPEC 18.5 D380)。安卓上 jar 走系统 DexClassLoader、不用下组件;
 * Python / GeckoView / Whisper 还没有发行包 —— 如实列出「谁需要」,不摆点不动的下载键。
 */
@Composable
fun ExtensionsPageTv() {
    val app = LocalApp.current
    var users by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    LaunchedEffect(Unit) {
        users = runCatching { app.call("plugin.list") }.getOrNull().obj()?.get("plugins").arr().mapNotNull { it.obj() }
            .flatMap { p -> p.strList("components").map { it to (p.str("name") ?: "") } }
            .groupBy({ it.first }, { it.second })
    }
    val rows = listOf(
        Triple("jar-runtime", "TVBox jar 运行时", "安卓用系统自带的类加载器,不需要下载"),
        Triple("python", "Python 运行时", "还没有发行包"),
        Triple("geckoview", "GeckoView 内核", "系统 WebView 缺失或过旧时用;还没有发行包"),
        Triple("whisper", "Whisper 转写", "还没有发行包"),
    )
    LazyColumn(Modifier.contentArea(), contentPadding = PaddingValues(bottom = TvSp.x20)) {
        item { PageHead("扩展组件", sub = "插件用到时才会提示下载,安装插件时不强制下") }
        items(rows, key = { it.first }) { (id, name, state) ->
            // 四行都还没有可下的包,但行必须可聚焦 —— TV 上不可聚焦的列表滚不动,
            // 整页没有落点等于遥控器在这一页失灵。按下去如实说一句为什么下不了。
            PanelItem(name, sub = state + (users[id]?.let { " · 需要它的插件:" + it.joinToString("、") } ?: ""),
                modifier = Modifier.memo("ext.$id", initial = id == rows.first().first),
                onClick = { app.toast(state) })
        }
    }
}
