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

/** 数据源首页(TV):继续观看 → 分类入口 → 推荐。源内搜索走搜索页(TV 上敲字慢,不在这里再放一个框)。 */
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

/** 插件页(TV,SPEC 14.7):已安装开关 + 市场一键装 + 仓库列表。复杂管理去手机或电脑上做。 */
@Composable
fun PluginsPageTv() {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var installed by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var market by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    LaunchedEffect(reload) { installed = loadInstalled(app) }
    LaunchedEffect(reload) { market = loadMarket(app) }
    LazyColumn(Modifier.contentArea(), contentPadding = PaddingValues(bottom = TvSp.x20)) {
        item { PageHead("插件", sub = "启停、安装都是重启应用后生效") }
        item { RowTitle("已安装") }
        if (installed.isEmpty()) item { TvText("还没有安装插件", tvType.meta, TvC.fg3) }
        items(installed, key = { "i:" + (it.str("id") ?: "") }) { p ->
            val id = p.str("id") ?: ""
            PanelItem(p.str("name") ?: id, sub = listOfNotNull(p.str("version"), p.str("author"), p.str("status")?.takeIf { it != "ok" }).joinToString(" · "),
                switch = p.bool("want"), modifier = Modifier.memo("plug.$id"), onClick = {
                    scope.launch { runCatching { app.call("plugin.setEnabled", args("id" to id, "enabled" to !p.bool("want"))) }.onFailure { app.report(it) }; reload++ }
                })
        }
        item { Spacer(Modifier.height(TvSp.x16)); RowTitle("市场") }
        if (market.isEmpty()) item { TvText("没有可装的插件(或市场没连上)", tvType.meta, TvC.fg3) }
        items(market, key = { "m:" + (it.str("id") ?: "") }) { e ->
            val v = e["best"].obj().str("version") ?: ""
            PanelItem(e.str("name") ?: "", sub = listOfNotNull(v, e.str("author"), if (e.bool("officialMark")) "官方" else "非官方来源").joinToString(" · "),
                chevron = true, onClick = {
                    scope.launch {
                        runCatching { app.call("plugin.installFromRepo", args("repo" to (e.str("repo") ?: ""), "id" to (e.str("id") ?: ""), "version" to v)) }
                            .onSuccess { app.toast("装好了,重启应用后生效", ToastKind.Ok); reload++ }.onFailure { app.report(it) }
                    }
                })
        }
    }
}
