package xyz.linplayer.app.ui.pages

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.toRoute
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import xyz.linplayer.app.core.CoreException
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.Block
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.ToastKind
import xyz.linplayer.app.data.arr
import xyz.linplayer.app.data.block
import xyz.linplayer.app.data.bool
import xyz.linplayer.app.data.dbl
import xyz.linplayer.app.data.long
import xyz.linplayer.app.data.obj
import xyz.linplayer.app.data.str
import xyz.linplayer.app.data.strList
import xyz.linplayer.app.plugin.WebShell
import xyz.linplayer.app.ui.Route
import xyz.linplayer.app.ui.components.BlockBox
import xyz.linplayer.app.ui.components.Body
import xyz.linplayer.app.ui.components.BtnKind
import xyz.linplayer.app.ui.components.Dim2
import xyz.linplayer.app.ui.components.Dim3
import xyz.linplayer.app.ui.components.EmptyState
import xyz.linplayer.app.ui.components.H1
import xyz.linplayer.app.ui.components.H2
import xyz.linplayer.app.ui.components.LpButton
import xyz.linplayer.app.ui.components.LpDialog
import xyz.linplayer.app.ui.components.LpField
import xyz.linplayer.app.ui.components.LpIconButton
import xyz.linplayer.app.ui.components.LpScaffold
import xyz.linplayer.app.ui.components.NetImage
import xyz.linplayer.app.ui.components.OptRow
import xyz.linplayer.app.ui.components.ToneChip
import xyz.linplayer.app.ui.components.pressable
import xyz.linplayer.app.ui.components.rememberScrolled
import xyz.linplayer.app.ui.theme.Lp
import xyz.linplayer.app.ui.theme.LpIcons
import xyz.linplayer.app.ui.theme.R
import xyz.linplayer.app.ui.theme.Sp

// ---------------------------------------------------------------- 统一结构(MediaItem / MediaDetail,见 plugin-sdk.d.ts)

internal fun JsonObject?.img(k: String): String? = this?.get(k).obj().str("url")
internal fun JsonObject?.yearText(): String = (this.dbl("year") ?: 0.0).toInt().takeIf { it > 0 }?.toString() ?: ""

/** 插件错误的 kind / retryAfter / verifyUrl,在 CoreException.detail 的 JSON 里(D323)。 */
internal data class SrcErr(val kind: String, val retryAfter: Int, val verifyUrl: String, val advice: String)

internal fun srcErr(e: Throwable): SrcErr {
    val ce = e as? CoreException
    val d = ce?.detail?.let { runCatching { Json.parseToJsonElement(it).obj() }.getOrNull() }
    return SrcErr(d.str("kind") ?: "", (d.long("retryAfter") ?: 0L).toInt(), d.str("verifyUrl") ?: "",
        ce?.message?.takeIf { it.isNotEmpty() } ?: e.message ?: "出错了")
}

/** 数据源卡片。海报比例按分类声明(D336),缺海报是统一灰底(D341)。 */
@Composable
internal fun SourceCard(item: JsonObject, onOpen: () -> Unit, m: Modifier = Modifier, badge: String? = null,
                        progress: Double = -1.0, shape: String = "portrait") {
    val app = LocalApp.current
    val ratio = when (shape) { "landscape" -> 16f / 9f; "square" -> 1f; else -> 2f / 3f }
    Column(m.pressable(onOpen)) {
        Box(Modifier.fillMaxWidth().aspectRatio(ratio)) {
            NetImage(app.proxiedImage(item.img("poster"), 330), item.str("title"), Modifier.fillMaxSize())
            val tag = badge ?: item.str("remarks")
            if (!tag.isNullOrEmpty()) Text(tag, Modifier.align(Alignment.TopEnd).padding(Sp.x6).clip(RoundedCornerShape(R.sm))
                .background(Lp.colors.scrim).padding(horizontal = Sp.x6, vertical = Sp.x2), color = Lp.colors.fg, fontSize = 10.sp, maxLines = 1)
            if (progress in 0.0..1.0) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(progress.toFloat()).height(3.dp).background(Lp.colors.acc))
        }
        Body(item.str("title") ?: "", Modifier.padding(top = Sp.x6), maxLines = 1)
        item.yearText().takeIf { it.isNotEmpty() }?.let { Dim3(it) }
    }
}

/** 错误 + 动作:限流给倒计时、需要验证给[去验证]、站点挂了给[换源](D323)。 */
@Composable
internal fun SourceError(serverId: String, e: Throwable, retry: () -> Unit, switchSource: (() -> Unit)? = null) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val err = remember(e) { srcErr(e) }
    var left by remember(e) { mutableIntStateOf(if (err.kind == "rateLimited") maxOf(err.retryAfter, 5) else 0) }
    LaunchedEffect(e) { while (left > 0) { delay(1000); left-- } }
    Column(Modifier.fillMaxWidth().padding(Sp.x16), verticalArrangement = Arrangement.spacedBy(Sp.x10)) {
        Dim2(err.advice)
        Row(horizontalArrangement = Arrangement.spacedBy(Sp.x8)) {
            when (err.kind) {
                "rateLimited" -> LpButton(if (left > 0) "$left 秒后重试" else "重试", retry, enabled = left == 0, kind = BtnKind.Secondary)
                "needVerify" -> LpButton("去验证", {
                    val act = ctx as? Activity ?: return@LpButton
                    scope.launch { if (err.verifyUrl.isNotEmpty() && WebShell.verify(act, app, err.verifyUrl, serverId)) retry() }
                })
                else -> LpButton("重试", retry, kind = BtnKind.Secondary)
            }
            if (switchSource != null && err.kind in listOf("siteDown", "parseFailed", "notFound", "timeout", ""))
                LpButton("换源", switchSource, kind = BtnKind.Secondary)
        }
    }
}

private fun LazyGridScope.header(content: @Composable () -> Unit) =
    item(span = { GridItemSpan(maxLineSpan) }) { content() }

// ---------------------------------------------------------------- 首页

/** 数据源首页(D167 D344):继续观看 → 推荐 → 分类;没有 home 就只剩搜索框。源内搜索停顿 500ms 自动搜。 */
@Composable
fun SourceHomePage(nav: NavController, serverId: String, name: String) {
    val app = LocalApp.current
    val grid = rememberLazyGridState()
    var reload by remember { mutableIntStateOf(0) }
    var home by remember(serverId) { mutableStateOf<Result<JsonObject?>?>(null) }
    var cont by remember(serverId) { mutableStateOf<List<JsonObject>>(emptyList()) }
    var q by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<Result<List<JsonObject>>?>(null) }
    var searchGen by remember { mutableIntStateOf(0) }
    LaunchedEffect(serverId, reload) {
        home = null
        launch { cont = runCatching { app.call("source.continueWatching", args("server_id" to serverId)) }.getOrNull().arr().mapNotNull { it.obj() } }
        val caps = runCatching { app.call("source.caps", args("server_id" to serverId)) }.getOrNull().obj()
        home = if (caps.bool("home")) runCatching { app.call("source.home", args("server_id" to serverId)).obj() } else Result.success(null)
    }
    LaunchedEffect(q, searchGen) {
        if (q.isBlank()) { found = null; return@LaunchedEffect }
        delay(500)
        found = null
        found = runCatching { app.call("source.searchItems", args("server_id" to serverId, "keyword" to q.trim())).obj()?.get("items").arr().mapNotNull { it.obj() } }
    }
    val open: (JsonObject) -> Unit = { nav.navigate(Route.SourceDetail(it.str("source") ?: serverId, it.str("id") ?: "")) }
    LpScaffold(name, scrolled = rememberScrolled(grid), actions = {
        LpIconButton(LpIcons.search, "聚合搜索") { nav.navigate(Route.Search()) }
        LpIconButton(LpIcons.heart, "收藏") { nav.navigate(Route.SourceFavorites) }
    }) { pad ->
        LazyVerticalGrid(GridCells.Adaptive(108.dp), Modifier.fillMaxSize(), grid,
            contentPadding = PaddingValues(Sp.x16, Sp.x8, Sp.x16, pad.calculateBottomPadding()),
            horizontalArrangement = Arrangement.spacedBy(Sp.x10), verticalArrangement = Arrangement.spacedBy(Sp.x16)) {
            header { LpField(q, { q = it }, "在「$name」里搜索") }
            val f = found
            if (q.isNotBlank()) {
                when {
                    f == null -> header { Dim3("搜索中…") }
                    f.isFailure -> header { SourceError(serverId, f.exceptionOrNull()!!, { searchGen++ }) }
                    f.getOrNull()!!.isEmpty() -> header { Dim2("没有找到「$q」") }
                    else -> items(f.getOrNull()!!) { SourceCard(it, { open(it) }) }
                }
                return@LazyVerticalGrid
            }
            if (cont.isNotEmpty()) {
                header { H2("继续观看") }
                items(cont.take(12)) { c ->
                    val r = c["ref"].obj() ?: return@items
                    val dur = c.dbl("duration_secs") ?: 0.0
                    val pos = c.dbl("position_secs") ?: 0.0
                    SourceCard(r["item"].obj() ?: return@items, {
                        nav.navigate(Route.SourceDetail(serverId, r["item"].obj().str("id") ?: "", r.str("lineId"), r.str("episodeId"), pos))
                    }, badge = r.str("episodeName"), progress = if (dur > 0) pos / dur else -1.0)
                }
            }
            val h = home
            when {
                h == null -> header { Dim3("加载中…") }
                h.isFailure -> header { SourceError(serverId, h.exceptionOrNull()!!, { reload++ }) }
                h.getOrNull() == null -> if (cont.isEmpty()) header { Dim2("这个源没有首页,在上面的搜索框里找想看的。") }
                else -> {
                    val cats = h.getOrNull()!!["categories"].arr().mapNotNull { it.obj() }
                    val rec = h.getOrNull()!!["recommended"].arr().mapNotNull { it.obj() }
                    if (cats.isNotEmpty()) header {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Sp.x6)) {
                            cats.forEach { c -> ToneChip(c.str("name") ?: "", on = false) { nav.navigate(Route.SourceCategory(serverId, c.toString())) } }
                        }
                    }
                    if (rec.isNotEmpty()) { header { H2("推荐") }; items(rec) { SourceCard(it, { open(it) }) } }
                    else if (cats.isNotEmpty()) header { Dim3("点上面的分类看内容") }
                    if (cats.isEmpty() && rec.isEmpty() && cont.isEmpty()) header { Dim2("这个源的首页是空的,在上面的搜索框里找想看的。") }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 分类

/** 分类页:筛选按「源 + 分类」记住(D335),滚到底加载下一页(D166)。 */
@Composable
fun SourceCategoryPage(nav: NavController, entry: NavBackStackEntry) {
    val r = entry.toRoute<Route.SourceCategory>()
    val app = LocalApp.current
    val cat = remember(r.cat) { Json.parseToJsonElement(r.cat).obj() }
    val grid = rememberLazyGridState()
    val filters = remember(r.serverId, r.cat) { rememberedFilters.getOrPut(r.serverId + "#" + cat.str("id")) { mutableStateMapOf() } }
    val items = remember { mutableStateListOf<JsonObject>() }
    var next by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<Throwable?>(null) }
    var gen by remember { mutableIntStateOf(0) }
    suspend fun more(g: Int) {
        if (busy || done) return
        busy = true
        runCatching {
            app.call("source.category", j("server_id" to r.serverId, "category_id" to cat.str("id"),
                "filters" to filters.filterValues { it.isNotEmpty() }, "cursor" to next)).obj()
        }.onSuccess { page ->
            if (g != gen) return@onSuccess
            items.addAll(page?.get("items").arr().mapNotNull { it.obj() })
            next = page.str("next")?.takeIf { it.isNotEmpty() }
            done = next == null
        }.onFailure { if (g == gen) err = it }
        busy = false
    }
    LaunchedEffect(gen) { items.clear(); next = null; done = false; err = null; more(gen) }
    val atEnd by remember { derivedStateOf { (grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= grid.layoutInfo.totalItemsCount - 6 } }
    LaunchedEffect(Unit) { snapshotFlow { atEnd }.collect { if (it && items.isNotEmpty()) more(gen) } }
    LpScaffold(cat.str("name") ?: "分类", onBack = { nav.popBackStack() }, scrolled = rememberScrolled(grid)) { pad ->
        LazyVerticalGrid(GridCells.Adaptive(if (cat.str("shape") == "landscape") 160.dp else 108.dp), Modifier.fillMaxSize(), grid,
            contentPadding = PaddingValues(Sp.x16, Sp.x8, Sp.x16, pad.calculateBottomPadding()),
            horizontalArrangement = Arrangement.spacedBy(Sp.x10), verticalArrangement = Arrangement.spacedBy(Sp.x16)) {
            cat?.get("filters").arr().mapNotNull { it.obj() }.forEach { d ->
                val key = d.str("key") ?: return@forEach
                header {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Sp.x6),
                        verticalAlignment = Alignment.CenterVertically) {
                        Dim3(d.str("name") ?: "", Modifier.width(48.dp))
                        d["options"].arr().mapNotNull { it.obj() }.forEach { o ->
                            val v = o.str("value") ?: ""
                            val cur = filters[key] ?: emptyList()
                            ToneChip(o.str("name") ?: v, on = if (v.isEmpty()) cur.isEmpty() else v in cur) {
                                filters[key] = when {
                                    v.isEmpty() -> emptyList()
                                    d.bool("multi") -> if (v in cur) cur - v else cur + v
                                    else -> listOf(v)
                                }
                                gen++
                            }
                        }
                    }
                }
            }
            items(items) { SourceCard(it, { nav.navigate(Route.SourceDetail(it.str("source") ?: r.serverId, it.str("id") ?: "")) }, shape = cat.str("shape") ?: "portrait") }
            err?.let { e -> header { SourceError(r.serverId, e, { gen++ }) } }
            if (busy) header { Dim3("加载中…") }
            else if (done && items.isEmpty() && err == null) header { Dim2("这个分类下没有内容") }
        }
    }
}

private val rememberedFilters = HashMap<String, androidx.compose.runtime.snapshots.SnapshotStateMap<String, List<String>>>()

// ---------------------------------------------------------------- 详情

/** 数据源详情:线路 → 选集;只有一集时只有播放按钮(D463);收藏、换源(D232)。 */
@Composable
fun SourceDetailPage(nav: NavController, entry: NavBackStackEntry) {
    val r = entry.toRoute<Route.SourceDetail>()
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    var reload by remember { mutableIntStateOf(0) }
    var d by remember { mutableStateOf<Result<JsonObject>?>(null) }
    var line by remember { mutableStateOf("") }
    var fav by remember { mutableStateOf(false) }
    var switching by remember { mutableStateOf(false) }
    var autoDone by remember { mutableStateOf(false) }
    LaunchedEffect(reload) {
        d = runCatching { app.call("source.detail", args("server_id" to r.serverId, "item_id" to r.itemId)).obj()!! }
        fav = runCatching { app.call("source.isFavorite", args("server_id" to r.serverId, "item_id" to r.itemId)) }
            .getOrNull().let { (it as? JsonPrimitive)?.contentOrNull == "true" || it.obj().bool("favorite") }
        val detail = d?.getOrNull() ?: return@LaunchedEffect
        val lines = linesOf(detail)
        line = r.autoLine?.takeIf { l -> lines.any { it.str("id") == l } }
            ?: lines.firstOrNull { it.str("name") == detail.str("lastLine") }?.str("id") ?: lines.firstOrNull()?.str("id") ?: ""
    }
    fun play(detail: JsonObject, lineId: String, ep: JsonObject, pos: Double) {
        val src = j("server_id" to r.serverId, "item" to detail, "line_id" to lineId, "episode_id" to ep.str("id"), "resume_secs" to pos)
        nav.navigate(Route.Player(r.itemId, "${detail.str("title") ?: ""} ${ep.str("name") ?: ""}".trim(), src = src.toString()))
    }
    val detail = d?.getOrNull()
    // 继续观看 / 换源带进度进来:拿到线路表就接着那一集起播
    LaunchedEffect(detail, line) {
        if (detail == null || line.isEmpty() || autoDone || (r.autoEp == null && r.autoIndex == 0)) return@LaunchedEffect
        autoDone = true
        val eps = episodesOf(detail, line)
        val ep = eps.firstOrNull { it.str("id") == r.autoEp }
            ?: eps.firstOrNull { (it.long("index") ?: 0L).toInt() == r.autoIndex && r.autoIndex > 0 }
            ?: eps.singleOrNull()
        if (ep == null) app.toast(if (r.autoIndex > 0) "这个源没有第 ${r.autoIndex} 集,请手动选" else "上次看的那一集不在了,请手动选")
        else play(detail, line, ep, r.autoPos)
    }
    if (switching && detail != null) SwitchSourceDialog(nav, r.serverId, detail, line, 0, 0.0) { switching = false }
    LpScaffold(detail?.str("title") ?: "", onBack = { nav.popBackStack() }, scrolled = rememberScrolled(list)) { pad ->
        val res = d
        when {
            res == null -> Dim3("加载中…", Modifier.padding(Sp.x16))
            res.isFailure -> SourceError(r.serverId, res.exceptionOrNull()!!, { reload++ }) { switching = true }
            else -> LazyColumn(Modifier.fillMaxSize(), list, contentPadding = PaddingValues(bottom = pad.calculateBottomPadding())) {
                val x = res.getOrNull()!!
                item {
                    Row(Modifier.padding(Sp.x16), horizontalArrangement = Arrangement.spacedBy(Sp.x16)) {
                        NetImage(app.proxiedImage(x.img("poster"), 330), x.str("title"), Modifier.width(120.dp).aspectRatio(2f / 3f))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Sp.x6)) {
                            H1(x.str("title") ?: "")
                            Dim3(listOf(x.yearText(), x.strList("countries").joinToString(" "), x.strList("genres").joinToString(" "), x.str("remarks") ?: "")
                                .filter { it.isNotEmpty() }.joinToString(" · "), maxLines = 3)
                            Row(horizontalArrangement = Arrangement.spacedBy(Sp.x8)) {
                                LpButton(if (fav) "已收藏" else "收藏", {
                                    scope.launch {
                                        runCatching { app.call("source.setFavorite", j("server_id" to r.serverId, "item" to x, "favorite" to !fav)) }
                                            .onSuccess { fav = !fav }.onFailure { app.report(it) }
                                    }
                                }, kind = BtnKind.Secondary)
                                LpButton("换源", { switching = true }, kind = BtnKind.Secondary)
                            }
                        }
                    }
                    x.str("overview")?.takeIf { it.isNotEmpty() }?.let { Dim2(it, Modifier.padding(horizontal = Sp.x16), maxLines = 6) }
                }
                val lines = linesOf(x)
                if (lines.size > 1) item {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(Sp.x16), horizontalArrangement = Arrangement.spacedBy(Sp.x6)) {
                        lines.forEach { l -> ToneChip("${l.str("name")} · ${l["episodes"].arr().size}", on = l.str("id") == line) { line = l.str("id") ?: "" } }
                    }
                }
                val eps = episodesOf(x, line)
                if (eps.size == 1) item { LpButton("播放", { play(x, line, eps[0], 0.0) }, Modifier.padding(Sp.x16)) }
                else items(eps.chunked(4)) { row ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = Sp.x16, vertical = Sp.x4), horizontalArrangement = Arrangement.spacedBy(Sp.x6)) {
                        row.forEach { ep -> Box(Modifier.weight(1f)) { ToneChip(ep.str("name") ?: "", on = false, Modifier.fillMaxWidth()) { play(x, line, ep, 0.0) } } }
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                if (eps.isEmpty() && lines.isEmpty()) item { Dim2("这部片没有可播放的线路。", Modifier.padding(Sp.x16)) }
            }
        }
    }
}

/** 有季结构时先按季展开成线路(阶段 ① 的 TVBox 源都是线路结构)。 */
internal fun linesOf(d: JsonObject): List<JsonObject> =
    d["lines"].arr().mapNotNull { it.obj() } + d["seasons"].arr().flatMap { s -> s.obj()?.get("lines").arr().mapNotNull { it.obj() } }

internal fun episodesOf(d: JsonObject, lineId: String): List<JsonObject> =
    linesOf(d).firstOrNull { it.str("id") == lineId }?.get("episodes").arr().mapNotNull { it.obj() }

// ---------------------------------------------------------------- 换源(D232~D236,D464,D525)

/**
 * 换源三层:本源其它线路 → 其它数据源 → Emby 服务器。候选边找边出(partial);
 * 选了数据源的会记换源链路,全局历史里同一部片按它合并(D431)。
 */
@Composable
fun SwitchSourceDialog(nav: NavController, serverId: String, item: JsonObject, lineId: String, episodeIndex: Int, pos: Double, onClose: () -> Unit) {
    val app = LocalApp.current
    val cands = remember { mutableStateListOf<JsonObject>() }
    var done by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val all = runCatching {
            app.call("source.switchCandidates", j("server_id" to serverId, "item" to item, "line_id" to lineId,
                "episode_index" to episodeIndex, "title" to item.str("title"), "year" to (item.dbl("year") ?: 0.0))) { part ->
                part.arr().mapNotNull { it.obj() }.let { app.bg.launch { cands.addAll(it) } }
            }
        }.getOrNull().arr().mapNotNull { it.obj() }
        cands.clear(); cands.addAll(all); done = true
    }
    fun pick(c: JsonObject) {
        onClose()
        val target = c.str("server_id") ?: ""
        when ((c.long("layer") ?: 0L).toInt()) {
            1 -> {
                val src = j("server_id" to serverId, "item" to item, "line_id" to c.str("line_id"), "episode_id" to c.str("episode_id"), "resume_secs" to pos)
                nav.navigate(Route.Player(item.str("id") ?: "", item.str("title") ?: "", src = src.toString()))
            }
            3 -> {
                val e = c["emby_item"].obj()
                app.bg.launch {
                    runCatching { app.call("account.setActiveServer", args("server_id" to target)) }.onSuccess {
                        app.refreshSession(); nav.navigate(Route.Detail(e.str("id") ?: "", e.str("type_") ?: "Series"))
                    }.onFailure { app.report(it) }
                }
            }
            else -> {
                val it = c["item"].obj()
                app.bg.launch { runCatching { app.call("source.linkSwitch", args("from_server" to serverId, "from_item" to (item.str("id") ?: ""), "to_server" to target, "to_item" to (it.str("id") ?: ""))) } }
                nav.navigate(Route.SourceDetail(target, it.str("id") ?: "", autoPos = pos, autoIndex = episodeIndex))
            }
        }
    }
    LpDialog(onClose, "换源") {
        Column(verticalArrangement = Arrangement.spacedBy(Sp.x2)) {
            if (!done) Dim3("找其它源…")
            else if (cands.isEmpty()) Dim2("别的源里都没找到这部片。可以给更多源打开「允许聚合」。")
            LazyColumn(Modifier.height(360.dp)) {
                items(cands) { c ->
                    val layer = (c.long("layer") ?: 0L).toInt()
                    val tier = when ((c.long("tier") ?: 0L).toInt()) { 0 -> "同一部"; 1 -> "可能是"; else -> "不像" }
                    val (title, where) = when (layer) {
                        1 -> (c.str("line_name") ?: "") to "同一部 · 另一条线路"
                        3 -> (c["emby_item"].obj().str("name") ?: "") to "${c.str("server_name")} · $tier"
                        else -> "${c["item"].obj().str("title") ?: ""} ${c["item"].obj().yearText()}".trim() to "${c.str("server_name")} · $tier · ${(c.dbl("ms") ?: 0.0).toInt()}ms"
                    }
                    OptRow(title, { pick(c) }, sub = where)
                }
            }
        }
    }
}

/** 数据源起播失败:从路由带来的 `{server_id, item, line_id, episode_id}` 算出集序号,弹换源(D263)。 */
@Composable
internal fun SourcePlayFailed(nav: NavController, raw: String, pos: Double, onClose: () -> Unit) {
    val src = remember(raw) { Json.parseToJsonElement(raw).obj() } ?: return
    val item = src["item"].obj() ?: return
    val lineId = src.str("line_id") ?: ""
    val ep = episodesOf(item, lineId).firstOrNull { it.str("id") == src.str("episode_id") }
    SwitchSourceDialog(nav, src.str("server_id") ?: "", item, lineId, (ep.long("index") ?: 0L).toInt(), pos, onClose)
}

/** Emby 条目换到数据源(D525):按剧名 + 集序号找,Emby 服务器本身不在候选里。 */
@Composable
internal fun EmbySwitchSource(nav: NavController, itemId: String, onClose: () -> Unit) {
    val app = LocalApp.current
    var seed by remember { mutableStateOf<Triple<String, JsonObject, Int>?>(null) }
    LaunchedEffect(itemId) {
        runCatching { app.call("emby.itemDetail", args("item_id" to itemId)).obj()!! }.onSuccess { d ->
            val title = d.str("series_name")?.takeIf { it.isNotEmpty() } ?: d.str("name") ?: ""
            val item = j("id" to itemId, "title" to title, "year" to (d.dbl("year") ?: 0.0))
            seed = Triple(app.session.value?.server ?: "", item, (d.long("episode_no") ?: 0L).toInt())
        }.onFailure { app.report(it); onClose() }
    }
    seed?.let { (server, item, idx) -> SwitchSourceDialog(nav, server, item, "", idx, 0.0, onClose) }
}

// ---------------------------------------------------------------- 收藏 / 历史(D326 D430 D431 D333)

/** 全部数据源收藏,按来源分组;来源已删的仍显示但点不开。 */
@Composable
fun SourceFavoritesPage(nav: NavController) {
    val app = LocalApp.current
    val grid = rememberLazyGridState()
    var block by remember { mutableStateOf<Block<JsonElement>>(Block.Loading) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) { block = app.block("source.favorites") }
    LpScaffold("数据源收藏", onBack = { nav.popBackStack() }, scrolled = rememberScrolled(grid)) { pad ->
        BlockBox(block, { reload++ }) { v ->
            val groups = v.arr().mapNotNull { it.obj() }
            if (groups.isEmpty()) EmptyState("还没有收藏", "在数据源的详情页点「收藏」加进来。", LpIcons.heart)
            else LazyVerticalGrid(GridCells.Adaptive(108.dp), Modifier.fillMaxSize(), grid,
                contentPadding = PaddingValues(Sp.x16, Sp.x8, Sp.x16, pad.calculateBottomPadding()),
                horizontalArrangement = Arrangement.spacedBy(Sp.x10), verticalArrangement = Arrangement.spacedBy(Sp.x16)) {
                groups.forEach { g ->
                    val removed = g.bool("removed")
                    header { H2((g.str("server_name") ?: "") + if (removed) "(已移除)" else "") }
                    items(g["items"].arr().mapNotNull { it.obj() }) { it ->
                        SourceCard(it, {
                            if (removed) app.toast("这个来源已经移除了") else nav.navigate(Route.SourceDetail(g.str("server_id") ?: "", it.str("id") ?: ""))
                        })
                    }
                }
            }
        }
    }
}

/** 全局观看历史里数据源那部分,给 Emby 历史 / 首页用的横排。 */
@Composable
internal fun SourceHistoryRow(nav: NavController) {
    val app = LocalApp.current
    var rows by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    LaunchedEffect(Unit) { rows = runCatching { app.call("source.history") }.getOrNull().arr().mapNotNull { it.obj() } }
    if (rows.isEmpty()) return
    Column(Modifier.padding(vertical = Sp.x8)) {
        H2("数据源观看记录", Modifier.padding(horizontal = Sp.x16))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(Sp.x16), horizontalArrangement = Arrangement.spacedBy(Sp.x10)) {
            rows.take(30).forEach { c ->
                val r = c["ref"].obj() ?: return@forEach
                val dur = c.dbl("duration_secs") ?: 0.0
                val pos = c.dbl("position_secs") ?: 0.0
                val removed = c.bool("removed")
                SourceCard(r["item"].obj() ?: return@forEach, {
                    if (removed) app.toast("这个来源已经移除了")
                    else nav.navigate(Route.SourceDetail(c.str("server_id") ?: "", r["item"].obj().str("id") ?: "", r.str("lineId"), r.str("episodeId"), pos))
                }, Modifier.width(108.dp), badge = if (removed) "来源已移除" else r.str("episodeName"), progress = if (dur > 0) pos / dur else -1.0)
            }
        }
    }
}

// ---------------------------------------------------------------- 添加插件源(D131 D45 D346 D523)

/** 插件服务器类型的提交:插件给源草稿(或先给多仓让用户勾)→ 用户勾源 → 落账号表。返回是否加了。 */
@Composable
internal fun PluginSourceFlow(pluginId: String, typeId: String, form: Map<String, String>, onDone: (Boolean) -> Unit) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var repos by remember { mutableStateOf<List<JsonObject>?>(null) }
    var drafts by remember { mutableStateOf<List<JsonObject>?>(null) }
    val picked = remember { mutableStateMapOf<String, Boolean>() }
    LaunchedEffect(Unit) {
        noticeOnce(ctx, app, "subscription", "订阅内容由你自行添加,与 LinPlayer 官方无关。")
        runCatching { app.call("source.createSources", j("plugin_id" to pluginId, "type_id" to typeId, "form" to form)).obj() }
            .onSuccess { r ->
                val rp = r?.get("repos").arr().mapNotNull { it.obj() }
                if (rp.isNotEmpty()) { repos = rp; rp.forEach { picked[it.str("id") ?: ""] = true } } else setDrafts(r, picked) { drafts = it }
            }.onFailure { app.report(it); onDone(false) }
    }
    val rp = repos
    val ds = drafts
    when {
        ds != null -> PickDialog("选择要添加的源", ds.map { Triple(it.str("id") ?: "", it.str("name") ?: "", if (it.bool("exists")) "已添加" else it.str("unavailableReason") ?: "") }, picked,
            onCancel = { onDone(false) }) {
            scope.launch {
                val chosen = ds.filter { picked[it.str("id")] == true && !it.bool("exists") }
                if (chosen.isEmpty()) { onDone(false); return@launch }
                runCatching { app.call("source.addSources", j("plugin_id" to pluginId, "type_id" to typeId, "sources" to chosen)) }
                    .onSuccess { app.toast("已添加 ${chosen.size} 个源", ToastKind.Ok); onDone(true) }.onFailure { app.report(it); onDone(false) }
            }
        }
        rp != null -> PickDialog("选择要订阅的仓库", rp.map { Triple(it.str("id") ?: "", it.str("name") ?: "", "") }, picked, onCancel = { onDone(false) }) {
            val ids = rp.mapNotNull { it.str("id") }.filter { picked[it] == true }
            repos = null
            picked.clear()
            scope.launch {
                runCatching { app.call("source.createSources", j("plugin_id" to pluginId, "type_id" to typeId, "form" to form, "repos" to ids)).obj() }
                    .onSuccess { r -> setDrafts(r, picked) { drafts = it } }.onFailure { app.report(it); onDone(false) }
            }
        }
    }
}

private fun setDrafts(r: JsonObject?, picked: MutableMap<String, Boolean>, set: (List<JsonObject>) -> Unit) {
    val ds = r?.get("sources").arr().mapNotNull { it.obj() }
    ds.forEach { picked[it.str("id") ?: ""] = !it.bool("exists") && it.str("unavailableReason").isNullOrEmpty() }
    set(ds)
}

/** 勾选清单;第三项非空 = 不可选并写原因(已添加 / 本设备不可用)。 */
@Composable
private fun PickDialog(title: String, rows: List<Triple<String, String, String>>, picked: MutableMap<String, Boolean>, onCancel: () -> Unit, onOk: () -> Unit) {
    LpDialog(onCancel, title) {
        Column {
            LazyColumn(Modifier.height(360.dp)) {
                items(rows, key = { it.first }) { (id, name, why) ->
                    OptRow(if (why.isNotEmpty()) "$name($why)" else name, { if (why.isEmpty()) picked[id] = picked[id] != true }, selected = picked[id] == true)
                }
            }
            Spacer(Modifier.height(Sp.x8))
            Row(horizontalArrangement = Arrangement.spacedBy(Sp.x8)) {
                LpButton("取消", onCancel, kind = BtnKind.Secondary)
                LpButton("添加", onOk)
            }
        }
    }
}

/**
 * 真机自检直达:`-e lp_page 'tvbox:<插件目录>|<订阅地址>|play:<源名>:<关键词>:<线路>[:<第几集>]'`。
 * 开发版加载插件 → 订阅 → 全部源加进来 → 切到那个源 → 搜关键词 → 进详情并按线路起第 1 集。
 */
internal suspend fun selfCheckTvbox(app: AppState, nav: NavController, arg: String) {
    val (dir, cfg, then) = (arg.split("|") + listOf("", "", "")).take(3)
    try {
        app.call("plugin.devLoad", args("dir" to dir))
        val r = app.call("source.createSources", j("plugin_id" to "linplayer/tvbox", "type_id" to "subscription", "form" to mapOf("url" to cfg))).obj()
        val drafts = r?.get("sources").arr().mapNotNull { it.obj() }.filter { it.str("unavailableReason").isNullOrEmpty() }
        app.call("source.addSources", j("plugin_id" to "linplayer/tvbox", "type_id" to "subscription", "sources" to drafts))
        android.util.Log.i("LinPlayer", "[自检 tvbox] 加了 ${drafts.size} 个源")
        val p = then.removePrefix("play:").split(":")
        val src = drafts.firstOrNull { it.str("name") == p.getOrNull(0) } ?: drafts.first()
        val key = "plugin:linplayer/tvbox/" + src.str("id")
        app.call("account.setActiveServer", args("server_id" to key))
        app.refreshSession()
        if (!then.startsWith("play:")) return
        val found = app.call("source.searchItems", args("server_id" to key, "keyword" to (p.getOrNull(1) ?: ""))).obj()?.get("items").arr().firstOrNull().obj()
        android.util.Log.i("LinPlayer", "[自检 tvbox] 搜到 ${found.str("id")}")
        nav.navigate(Route.SourceDetail(key, found.str("id") ?: "", autoLine = p.getOrNull(2), autoIndex = p.getOrNull(3)?.toIntOrNull() ?: 1))
    } catch (e: Throwable) {
        android.util.Log.w("LinPlayer", "[自检 tvbox] 失败:" + e.message)
    }
}
