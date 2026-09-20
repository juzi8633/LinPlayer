package xyz.linplayer.app.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import xyz.linplayer.app.data.Block
import xyz.linplayer.app.data.Item
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.Page
import xyz.linplayer.app.data.ToastKind
import xyz.linplayer.app.data.arr
import xyz.linplayer.app.data.block
import xyz.linplayer.app.data.bool
import xyz.linplayer.app.data.dbl
import xyz.linplayer.app.data.long
import xyz.linplayer.app.data.obj
import xyz.linplayer.app.data.str
import xyz.linplayer.app.ui.Route
import xyz.linplayer.app.ui.components.BlockBox
import xyz.linplayer.app.ui.components.Body
import xyz.linplayer.app.ui.components.BtnKind
import xyz.linplayer.app.ui.components.Dim3
import xyz.linplayer.app.ui.components.EmptyState
import xyz.linplayer.app.ui.components.Hairline
import xyz.linplayer.app.ui.components.LpButton
import xyz.linplayer.app.ui.components.LpCell
import xyz.linplayer.app.ui.components.LpIconButton
import xyz.linplayer.app.ui.components.LpScaffold
import xyz.linplayer.app.ui.components.LpTag
import xyz.linplayer.app.ui.components.MediaCard
import xyz.linplayer.app.ui.components.NetImage
import xyz.linplayer.app.ui.components.Panel
import xyz.linplayer.app.ui.components.Skeleton
import xyz.linplayer.app.ui.components.StepperRow
import xyz.linplayer.app.ui.components.pressable
import xyz.linplayer.app.ui.components.rememberScrolled
import xyz.linplayer.app.ui.components.ToneChip
import xyz.linplayer.app.ui.theme.LpIcons
import xyz.linplayer.app.ui.theme.Lp
import xyz.linplayer.app.ui.theme.R
import xyz.linplayer.app.ui.theme.Sp
import androidx.compose.runtime.derivedStateOf
import androidx.navigation.toRoute

/**
 * 收藏(U1.9a)。**2026-09-12 起是底栏第三个 Tab**,所以没有返回键。
 *
 * 排序档位表在核心层(`emby.FavoriteSorts`),这里只按名字传过去 ——
 * 服务端那条路对某些 fork 是死的,排序是核心层本地做的。
 */
@Composable
fun FavoritesPage(nav: NavController) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val grid = rememberLazyGridState()
    var block by xyz.linplayer.app.data.keepState<Block<List<Item>>>("fav") { Block.Loading }
    var sort by xyz.linplayer.app.data.keepState("fav.sort") { FAV_SORTS[0] }
    /* 手里这份是按哪一档拉的。少了它,下面那道「已经有结果就别重拉」的闸
       会把换档位一起吞掉 —— 和媒体库那个坑同一个形状。 */
    var fetchedSort by xyz.linplayer.app.data.keepState<String?>("fav.as") { null }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(reload, sort) {
        // ☠ 判据必须带上档位,否则就是媒体库那个坑(2026-09-12「筛选了不刷新」)
        if (reload == 0 && block is Block.Ok && fetchedSort == sort) return@LaunchedEffect
        fetchedSort = sort
        block = when (val r = app.block("emby.listFavorites", args("sort" to sort))) {
            is Block.Ok -> Block.Ok(Page.from(r.value).items)
            is Block.Fail -> r
            else -> Block.Loading
        }
    }
    LaunchedEffect(Unit) { app.invalidate.collect { if (it == "library" || it == "all") reload++ } }

    LpScaffold("收藏", scrolled = rememberScrolled(grid), actions = {
        // 数据源的收藏单独一页(D326):它们不在 Emby 服务器上,排序档位也对不上
        // 「观看历史」和「全部收藏」是一对(SPEC 8.7 D326)
        xyz.linplayer.app.ui.components.LpIconButton(LpIcons.rewind, "观看历史") { nav.navigate(Route.History) }
        xyz.linplayer.app.ui.components.LpIconButton(LpIcons.plugin, "数据源收藏") { nav.navigate(Route.SourceFavorites) }
    }) { pad ->
        BlockBox(block, { reload++ }, skeleton = { GridSkel(pad) }) { items ->
            if (items.isEmpty()) EmptyState(
                "还没有收藏任何内容",
                "在任意封面上长按 → 收藏,或者在详情页点右上角那颗心。收藏会跟着服务器走。",
                LpIcons.heart,
            ) else LazyVerticalGrid(
                GridCells.Adaptive(112.dp), Modifier.fillMaxSize(), grid,
                contentPadding = PaddingValues(Sp.x16, Sp.x8, Sp.x16, pad.calculateBottomPadding()),
                horizontalArrangement = Arrangement.spacedBy(Sp.x10),
                verticalArrangement = Arrangement.spacedBy(Sp.x16),
            ) {
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                            .padding(bottom = Sp.x8),
                        horizontalArrangement = Arrangement.spacedBy(Sp.x6),
                    ) {
                        FAV_SORTS.forEach { s -> ToneChip(s, on = s == sort) { sort = s } }
                    }
                }
                items(items, key = { it.id }) {
                    MediaCard(it, app.imageUrl(it.id, "Primary", 330),
                        { nav.navigate(Route.Detail(it.id, it.type)) },
                        Modifier.fillMaxWidth(), menu = cardActions(app, scope, it))
                }
            }
        }
    }
}

/** 收藏排序档位。**必须和核心层 `emby.FavoriteSorts` 逐字一致** —— 对不上就静默落回第一档。 */
private val FAV_SORTS = listOf("更新时间", "名称", "评分", "年份")

/**
 * 下载(U1.12)。
 *
 * ★ **「清除已完成」只清记录,不删文件**;每条右边那个 ✕ 才是删文件。
 *   两个语义正相反,**别合并成一个命令**。
 * ★ 并发数**只读不灌**:核心层持久化,UI 读回来显示。
 */
@Composable
fun DownloadsPage(nav: NavController) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()

    data class Task(val id: String, val title: String, val state: String,
                    val progress: Float, val speed: String)

    var tasks by remember { mutableStateOf<List<Task>>(emptyList()) }
    var threads by remember { mutableStateOf(2.0) }
    var loaded by remember { mutableStateOf(false) }

    fun parse(e: kotlinx.serialization.json.JsonElement?): List<Task> = e.arr().mapNotNull {
        val o = it.obj() ?: return@mapNotNull null
        val total = o.long("total_bytes") ?: 0
        val done = o.long("bytes") ?: 0
        Task(
            o.str("id") ?: return@mapNotNull null,
            o.str("title") ?: o.str("name") ?: "下载任务",
            o.str("state") ?: "running",
            if (total > 0) (done.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f,
            o.long("speed")?.let { s -> "%.1f MB/s".format(s / 1024.0 / 1024.0) } ?: "",
        )
    }

    LaunchedEffect(Unit) {
        tasks = parse(runCatching { app.call("download.list") }.getOrNull())
        /* ☠ 线程数**不在 download.list 里** —— 它返回的是一个任务数组,
           原来那句 `r.obj().long("threads")` 拿数组当对象读,恒 null,
           于是这一格**永远显示 2**,用户设成 4 也看不出来。
           真出处是 `download.setThreads` 不传参数时的回读(桌面端一直是这么读的)。 */
        // 把响应**绑到一个变量**上再读:链式写法认不出接收者,字段名门禁只能
        // 回落到「本函数所有命令的并集」,而这个函数里正好也调 setThreads ——
        // 那样改回去读错命令它也不会红。绑了变量它就能精确归属。
        val cur = runCatching { app.call("download.setThreads") }.getOrNull().obj()
        threads = (cur.long("threads") ?: 2L).toDouble()
        loaded = true
        // 订阅进度事件而不是轮询:轮询是「每秒一次全表」,事件是「变了才来」
        app.core.events.collect { ev ->
            if (ev.name == "download.progress") {
                tasks = parse(runCatching { app.call("download.list") }.getOrNull())
            }
        }
    }

    LpScaffold("下载", onBack = { nav.popBackStack() }, scrolled = rememberScrolled(list),
        actions = {
            LpIconButton(LpIcons.trash, "清除已完成") {
                scope.launch {
                    runCatching { app.call("download.clearCompleted") }
                        .onSuccess { app.toast("已清除完成的记录(文件保留)", ToastKind.Ok) }
                        .onFailure { app.report(it) }
                }
            }
        }) { pad ->
        Column(Modifier.fillMaxSize()) {
            Panel(Modifier.padding(Sp.x16)) {
                StepperRow("同时下载", threads, 1.0, 4.0, 1.0, { v ->
                    threads = v
                    scope.launch {
                        runCatching { app.call("download.setThreads", args("threads" to v.toInt())) }
                            .onFailure { app.report(it) }
                    }
                }, sub = "线程越多不一定越快,看服务端给不给", fmt = { it.toInt().toString() })
            }
            if (!loaded) Skeleton(Modifier.fillMaxWidth().height(72.dp).padding(Sp.x16))
            else if (tasks.isEmpty()) EmptyState(
                "下载队列是空的",
                "详情页的长按菜单里可以整部或单集入队。下好的文件离线也能播。",
                LpIcons.download,
            ) else LazyColumn(Modifier.fillMaxSize(), list, contentPadding = pad) {
                items(tasks, key = { it.id }) { t ->
                    Panel(Modifier.padding(horizontal = Sp.x16, vertical = Sp.x6)) {
                        Column(Modifier.padding(Sp.x16)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Body(t.title, maxLines = 2)
                                    Dim3("${(t.progress * 100).toInt()}%  ${t.speed}",
                                        Modifier.padding(top = Sp.x2))
                                }
                                LpIconButton(
                                    if (t.state == "paused") LpIcons.play else LpIcons.pause,
                                    if (t.state == "paused") "继续" else "暂停",
                                ) {
                                    scope.launch {
                                        runCatching {
                                            app.call(
                                                if (t.state == "paused") "download.resume" else "download.pause",
                                                args("id" to t.id))
                                        }.onFailure { app.report(it) }
                                    }
                                }
                                LpIconButton(LpIcons.close, "删除任务与文件") {
                                    scope.launch {
                                        runCatching { app.call("download.remove", args("id" to t.id)) }
                                            .onFailure { app.report(it) }
                                    }
                                }
                            }
                            Spacer(Modifier.height(Sp.x8))
                            Box(Modifier.fillMaxWidth().height(3.dp)
                                .clip(RoundedCornerShape(R.pill)).background(Lp.colors.s3)) {
                                Box(Modifier.fillMaxWidth(t.progress).fillMaxSize()
                                    .background(Lp.colors.acc))
                            }
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun GridSkel(pad: PaddingValues) {
    LazyVerticalGrid(
        GridCells.Adaptive(112.dp), Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Sp.x16, Sp.x8, Sp.x16, pad.calculateBottomPadding()),
        horizontalArrangement = Arrangement.spacedBy(Sp.x10),
        verticalArrangement = Arrangement.spacedBy(Sp.x16),
    ) {
        items(List(12) { it }) {
            Column {
                Skeleton(Modifier.fillMaxWidth().height(168.dp))
                Spacer(Modifier.height(Sp.x6))
                Skeleton(Modifier.fillMaxWidth(0.8f).height(12.dp))
            }
        }
    }
}

/**
 * 「按某个类型 / 标签 / 工作室 列条目」
 * 【用户定 2026-09-12:「支持点击 标签 工作室 类型 的跳转」】。
 *
 * ★ 工作室走的是 `studio_ids` 不是 `studios`:实测(Emby 4.9.5)按名字筛被完全无视,
 *   返回全库 1673 条,头几条的工作室对不上;按 id 才精确命中。
 */
@Composable
fun FacetPage(nav: NavController, entry: androidx.navigation.NavBackStackEntry) {
    val route = entry.toRoute<Route.Facet>()
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val grid = rememberLazyGridState()
    var items by remember { mutableStateOf<List<Item>>(emptyList()) }
    var first by remember { mutableStateOf<Block<Unit>>(Block.Loading) }
    var total by remember { mutableStateOf<Long?>(null) }
    var loading by remember { mutableStateOf(false) }

    suspend fun fetch(offset: Int) {
        val q = buildMap<String, Any> {
            put("start_index", offset); put("limit", FACET_PAGE)
            put("sort_by", "SortName"); put("sort_order", "Ascending")
            put(facetParam(route.kind), jsonArrayOf(listOf(route.value)))
        }
        // parent_id 空 = 不限库:按标签找片本来就不该被「你现在在哪个库」框住
        val a = mapOf("parent_id" to "", "query" to args(*q.toList().toTypedArray()))
        when (val r = app.block("emby.listItemsPage", args(*a.toList().toTypedArray()))) {
            is Block.Ok -> {
                val p = Page.from(r.value)
                items = if (offset == 0) p.items else items + p.items
                total = p.total
                first = Block.Ok(Unit)
            }
            is Block.Fail -> if (offset == 0) first = r
            else -> Unit
        }
    }
    LaunchedEffect(route.kind, route.value) { fetch(0) }

    // 滚到底再拉下一页。闩防重入 —— 没有它的话一屏滚动能连发四五次同一页
    val needMore by remember {
        derivedStateOf {
            val last = grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            items.isNotEmpty() && last >= items.size - 8 &&
                (total == null || items.size < (total ?: 0))
        }
    }
    LaunchedEffect(needMore) {
        if (!needMore || loading) return@LaunchedEffect
        loading = true
        fetch(items.size)
        loading = false
    }

    val kindName = when (route.kind) { "tag" -> "标签"; "studio" -> "工作室"; else -> "类型" }
    LpScaffold(route.label, subtitle = kindName, onBack = { nav.popBackStack() },
        scrolled = rememberScrolled(grid)) { pad ->
        BlockBox(first, { scope.launch { fetch(0) } }, skeleton = { GridSkel(pad) }) {
            if (items.isEmpty()) EmptyState(
                "没有找到「${route.label}」下的内容",
                "这台服务器上可能没有刮到这一项,或者它只挂在被屏蔽的库里。",
                LpIcons.search,
            ) else LazyVerticalGrid(
                GridCells.Adaptive(112.dp), Modifier.fillMaxSize(), grid,
                contentPadding = PaddingValues(Sp.x16, Sp.x8, Sp.x16, pad.calculateBottomPadding()),
                horizontalArrangement = Arrangement.spacedBy(Sp.x10),
                verticalArrangement = Arrangement.spacedBy(Sp.x16),
            ) {
                items(items, key = { it.id }) {
                    MediaCard(it, app.imageUrl(it.id, "Primary", 330),
                        { nav.navigate(Route.Detail(it.id, it.type)) },
                        Modifier.fillMaxWidth(), menu = cardActions(app, scope, it))
                }
            }
        }
    }
}

/** 一页拉多少。和媒体库那页同一个数,别在这儿另起一档。 */
private const val FACET_PAGE = 120

/**
 * 落地页那一档要往 `emby.listItemsPage` 的 query 里放哪个参数名。
 *
 * ☠ 工作室是 **`studio_ids`** 不是 `studios`:实测(Emby 4.9.5)按名字筛被完全无视,
 * 返回全库 1673 条、头几条对不上;按 id 才精确命中。写成 `studios` 的表现是
 * 「点了工作室,出来的是一整个库」—— **一句错都不报**。
 */
internal fun facetParam(kind: String): String = when (kind) {
    "tag" -> "tags"
    "studio" -> "studio_ids"
    else -> "genres"
}

// ---------------------------------------------------------------- 全局观看历史(SPEC 8.7 D430 D431)

/** ticks 是 100 纳秒单位 —— 除以 1e7 才是秒。写成 1e6 时长会大十倍且不报错。 */
private fun clockOf(secs: Double): String {
    val s = secs.toInt()
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/**
 * 全局观看历史。这份记录是**本地库**不是服务器的播放记录 —— 跨服续播就靠它,
 * 所以「当前服务器」和「全部」是两种看法,都要有。
 *
 * ★ 数据源那部分走 [SourceHistoryRow]:它已经按换源链路合并过(D431),
 *   来源被删的仍然显示但点不开(D333)—— 藏起来用户会以为记录丢了。
 */
@Composable
fun HistoryPage(nav: NavController) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    var onlyCurrent by remember { mutableStateOf(true) }
    var recs by remember { mutableStateOf<List<JsonObject>?>(null) }
    LaunchedEffect(onlyCurrent) {
        recs = runCatching { app.call("emby.watchHistoryList", args("current_only" to onlyCurrent)) }
            .getOrNull().arr().mapNotNull { it.obj() }
            // 核心层不保证顺序,排序是展示层的事
            .sortedByDescending { it.long("last_played_at") ?: 0L }
    }

    LpScaffold("观看历史", onBack = { nav.popBackStack() }, scrolled = rememberScrolled(list)) { pad ->
        LazyColumn(Modifier.fillMaxSize(), list, contentPadding = PaddingValues(bottom = pad.calculateBottomPadding())) {
            item("scope") {
                Row(Modifier.padding(horizontal = Sp.x16, vertical = Sp.x8), horizontalArrangement = Arrangement.spacedBy(Sp.x6)) {
                    ToneChip("当前服务器", on = onlyCurrent) { onlyCurrent = true }
                    ToneChip("全部服务器", on = !onlyCurrent) { onlyCurrent = false }
                }
            }
            item("src") { SourceHistoryRow(nav) }
            val rows = recs
            if (rows == null) item("skel") {
                Column(Modifier.padding(Sp.x16)) {
                    repeat(4) { Skeleton(Modifier.fillMaxWidth().height(56.dp)); Spacer(Modifier.height(Sp.x10)) }
                }
            } else if (rows.isEmpty()) item("none") {
                EmptyState("还没有观看记录", "看过的片会记在本机,换服务器或重装之后还在。", LpIcons.rewind)
            } else {
                item("h") { xyz.linplayer.app.ui.components.H2("服务器", Modifier.padding(Sp.x16)) }
                items(rows.take(200), key = { it.str("record_id") ?: "" }) { rec ->
                    val series = rec.str("series_title").orEmpty()
                    val title = rec.str("title").orEmpty()
                    val pos = (rec.long("last_position_ticks") ?: 0L) / 1e7
                    val run = (rec.long("run_time_ticks") ?: 0L) / 1e7
                    val right = if (rec.bool("played")) "已看完" else if (run > 0) "${clockOf(pos)} / ${clockOf(run)}" else clockOf(pos)
                    val itemId = rec.str("last_emby_item_id")
                    // scope_key 是 `server:user_id`,server 自带 https:// 甚至端口 —— 按**最后一个**冒号切
                    val sid = (rec.str("scope_key") ?: "").substringBeforeLast(':', "")
                    // last_played_at 是**毫秒**(core/history/store.go 的 nowMs),当秒读日期会跳到五万年后
                    val whenMs = rec.long("last_played_at") ?: 0L
                    val whenText = if (whenMs > 0) java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date(whenMs)) else ""
                    LpCell(if (series.isEmpty()) title else "$series · $title", sub = whenText, value = right, onClick = {
                        if (itemId.isNullOrEmpty()) { app.toast("这条记录没有对应的条目,换服务器后要先在设置里「扫描恢复」"); return@LpCell }
                        scope.launch {
                            if (sid.isNotEmpty()) runCatching { app.call("account.setActiveServer", args("server_id" to sid)) }
                                .onSuccess { app.refreshSession() }.onFailure { app.report(it); return@launch }
                            nav.navigate(Route.Detail(itemId, if (series.isEmpty()) "Movie" else "Episode"))
                        }
                    })
                    Hairline()
                }
            }
        }
    }
}
