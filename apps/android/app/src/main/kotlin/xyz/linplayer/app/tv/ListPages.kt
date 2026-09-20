package xyz.linplayer.app.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import xyz.linplayer.app.data.Block
import xyz.linplayer.app.data.Item
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.ToastKind
import xyz.linplayer.app.data.arr
import xyz.linplayer.app.data.block
import xyz.linplayer.app.data.bool
import xyz.linplayer.app.data.dbl
import xyz.linplayer.app.data.keepState
import xyz.linplayer.app.data.long
import xyz.linplayer.app.data.obj
import xyz.linplayer.app.data.str
import xyz.linplayer.app.tv.kit.CardPoster
import xyz.linplayer.app.tv.kit.EntryChip
import xyz.linplayer.app.tv.kit.FullState
import xyz.linplayer.app.tv.kit.PageHead
import xyz.linplayer.app.tv.kit.PanelGroup
import xyz.linplayer.app.tv.kit.PanelItem
import xyz.linplayer.app.tv.kit.RowTitle
import xyz.linplayer.app.tv.kit.ScopeChips
import xyz.linplayer.app.tv.kit.Skel
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
import xyz.linplayer.app.ui.pages.fmtSize
import xyz.linplayer.app.ui.pages.map
import xyz.linplayer.app.ui.theme.LpIcons

// ---------------------------------------------------------------- 收藏(§7.8)

private val FavBy = listOf("favorited" to "收藏时间", "name" to "名称", "updated" to "更新时间", "rating" to "评分")

/**
 * ☠ **排序在核心层做,不在 UI 层做**(SPEC §8.5):某 fork 在收藏上忽略 SortBy,
 *   核心层 `emby.listFavorites` 的 sort_by / sort_order 负责本地排,缺值一律沉底。
 * ★ 两组,**空组整组不画**:只收藏了剧的人看见空「分集」标题会以为收藏丢了。
 */
@Composable
fun FavoritesPage() {
    val app = LocalApp.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val overlay = rememberOverlay()
    val t = tvType
    val server = app.session.collectAsStateWithLifecycle().value?.server
    var by by keepState("tv.fav.by") { 0 }
    var asc by keepState("tv.fav.asc") { false }
    var block by keepState<Block<List<Item>>>("tv.fav.$server") { Block.Loading }
    // 数据源收藏按来源分组(D326 D333),和 Emby 收藏同页 —— 用户记的是「我收藏过」,不是「我在哪台收藏过」
    var srcFavs by remember { mutableStateOf<List<kotlinx.serialization.json.JsonObject>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(server, by, asc, reload) {
        val r = app.block("emby.listFavorites", args("sort_by" to FavBy[by].first,
            "sort_order" to if (asc) "asc" else "desc")).map { Item.list(it) }
        if (!(r is Block.Fail && block is Block.Ok)) block = r
    }
    LaunchedEffect(reload) {
        srcFavs = runCatching { app.call("source.favorites") }.getOrNull().arr().mapNotNull { it.obj() }
    }
    val cond = FavBy[by].second + if (by == 0) " ↓" else if (asc) " ↑" else " ↓"

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.contentArea()) {
            when (val b = block) {
                is Block.Loading -> {
                    PageHead("收藏")
                    Spacer(Modifier.height(TvSp.x16))
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) { repeat(6) { Skel(Modifier.size(124.dp, 186.dp)) } }
                }
                // 失败 FullState + 重试:旧实现失败时永远停在骨架上
                is Block.Fail -> FullState(LpIcons.info, "没加载出来", detail = b.message, buttons = listOf("重试"), tone = TvC.bad,
                    onButton = { reload++ })
                is Block.Ok -> if (b.value.isEmpty() && srcFavs.isEmpty()) FullState(LpIcons.heart, "还没有收藏", sub = "在详情页按收藏,之后会出现在这里",
                    buttons = listOf("去媒体库看看"), onButton = { nav.rail(TvRoute.Library()) })
                else {
                    val eps = b.value.filter { it.isEpisode }
                    val rest = b.value.filterNot { it.isEpisode }
                    val menu: (Item) -> Unit = { item ->
                        overlay.openCardMenu(app, nav, scope, item) { tag ->
                            if (tag == "unfav" || tag == "blocked") block = Block.Ok(b.value.filterNot { it.id == item.id })
                        }
                    }
                    val srcCount = srcFavs.sumOf { it["items"].arr().size }
                    PageHead("收藏", count = "${b.value.size + srcCount} 项")
                    Spacer(Modifier.height(TvSp.x8))
                    Row(horizontalArrangement = Arrangement.spacedBy(TvSp.x8)) {
                        EntryChip("排序", LpIcons.sort, cond, modifier = Modifier.memo("fav.sort"), onClick = {
                            overlay.open { FavSortPanel(by, asc, { overlay.close() }, { by = it }, { asc = it }) }
                        })
                        // 「观看历史」和「全部收藏」是一对(SPEC 8.7 D326):入口放这儿,不占导航轨的格子
                        EntryChip("观看历史", LpIcons.rewind, modifier = Modifier.memo("fav.history"),
                            onClick = { nav.push(TvRoute.History) })
                    }
                    Spacer(Modifier.height(TvSp.x6))
                    // 左右 bleed 12:卡片放大后的边有地方画,内容又和页标题对齐;顶上留 6 同理
                    LazyColumn(Modifier.bleed(12.dp), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = TvSp.x6, bottom = TvSp.x24)) {
                        if (eps.isNotEmpty()) {
                            item("eh") { Box(Modifier.padding(bottom = TvSp.x8)) { RowTitle("分集", trailing = "${eps.size} 项") } }
                            val rows = eps.chunked(4)
                            rows.forEachIndexed { ri, row ->
                                item("e$ri") {
                                    Row(Modifier.padding(bottom = if (ri < rows.lastIndex) TvSp.x12 else 0.dp), horizontalArrangement = Arrangement.spacedBy(TvSp.x16)) {
                                        row.forEachIndexed { ci, it ->
                                            ItemWide(it, "fav.${it.id}", menu, initial = ri == 0 && ci == 0, w = 192.dp, h = 108.dp)
                                        }
                                    }
                                }
                            }
                        }
                        if (rest.isNotEmpty()) {
                            item("ph") { Box(Modifier.padding(top = if (eps.isEmpty()) 0.dp else TvSp.x16, bottom = TvSp.x8)) { RowTitle("剧集与电影", trailing = "${rest.size} 项") } }
                            rest.chunked(6).forEachIndexed { ri, row ->
                                item("p$ri") {
                                    Row(Modifier.padding(bottom = TvSp.x12), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                        row.forEachIndexed { ci, it ->
                                            ItemPoster(it, "fav.${it.id}", menu, initial = eps.isEmpty() && ri == 0 && ci == 0,
                                                w = 124.dp, h = 186.dp, sub = it.year?.toString().orEmpty())
                                        }
                                    }
                                }
                            }
                        }
                        srcFavs.forEach { g ->
                            val sid = g.str("server_id") ?: ""
                            val removed = g.bool("removed")
                            val its = g["items"].arr().mapNotNull { it.obj() }
                            if (its.isEmpty()) return@forEach
                            item("sh:$sid") {
                                Box(Modifier.padding(top = TvSp.x16, bottom = TvSp.x8)) {
                                    RowTitle((g.str("server_name") ?: sid) + if (removed) "(已移除)" else "", trailing = "${its.size} 项")
                                }
                            }
                            its.chunked(6).forEachIndexed { ri, row ->
                                item("s:$sid:$ri") {
                                    Row(Modifier.padding(bottom = TvSp.x12), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                        row.forEach { it ->
                                            // 来源已移除的仍然显示但点不开(D333):藏起来用户会以为收藏丢了
                                            SourcePoster(it, "fav.s.$sid.${it.str("id")}") {
                                                if (removed) app.toast("这个来源已经移除了")
                                                else nav.push(TvRoute.SourceDetail(sid, it.str("id") ?: ""))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        item("end") { TvText("已经到底了 · 共 ${b.value.size + srcCount} 项", t.meta, TvC.fg3, Modifier.padding(vertical = TvSp.x12)) }
                    }
                }
            }
        }
        OverlayHost(overlay)
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.FavSortPanel(
    by: Int, asc: Boolean, onClose: () -> Unit, onBy: (Int) -> Unit, onAsc: (Boolean) -> Unit,
) {
    var b by remember { mutableIntStateOf(by) }
    var a by remember { mutableStateOf(asc) }
    TvSidePanel("排序", onClose) {
        PanelGroup("排序方式")
        FavBy.forEachIndexed { i, (_, label) ->
            PanelItem(label, selected = i == b, focused = i == b, onClick = { b = i; onBy(i) })
        }
        // 方向**只有非默认档才出现**:反转服务端原序得到的是一份没人要的倒序
        if (b != 0) {
            PanelGroup("方向")
            PanelItem("降序", selected = !a, onClick = { a = false; onAsc(false) })
            PanelItem("升序", selected = a, onClick = { a = true; onAsc(true) })
        }
    }
}

// ---------------------------------------------------------------- 下载(§7.10)

private data class Task(
    val id: String, val itemId: String, val title: String, val status: String, val error: String?,
    val total: Long, val got: Long, val progress: Float,
)

private fun tasksOf(e: kotlinx.serialization.json.JsonElement?): List<Task> = e.arr().mapNotNull {
    val o = it.obj() ?: return@mapNotNull null
    val series = o.str("series_name")
    val s = o.long("season_number")
    val ep = o.long("episode_number")
    val title = o.str("title") ?: ""
    Task(
        o.str("id") ?: return@mapNotNull null, o.str("item_id") ?: "",
        // 「寂静的星河 · S2E5 回声」:和卡片标题同一种写法
        if (series != null) series + " · " + listOfNotNull(if (s != null && ep != null) "S${s}E$ep" else null, title.takeIf { it.isNotBlank() })
            .joinToString(" ") else title,
        o.str("status") ?: "queued", o.str("error"),
        o.long("total_bytes") ?: 0, o.long("received_bytes") ?: 0, (o.dbl("progress") ?: 0.0).toFloat(),
    )
}

/**
 * ☠ 核心层**从不发** `download.progress` 事件(只在合并表里登记过),进度只能轮询 `download.list`。
 * ★ 一次拉取失败**不清空列表**:闪成空像下载没了。
 * ★ **不画剩余空间**:核心层没有这条命令,编一个数比不显示更糟(电视存储紧张,用户会照着它做决定)。
 */
@Composable
fun DownloadsPage() {
    val app = LocalApp.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val overlay = rememberOverlay()
    val t = tvType
    var tasks by remember { mutableStateOf<List<Task>?>(null) }
    var speeds by remember { mutableStateOf<Map<String, Double>>(emptyMap()) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(tick) {
        var last = emptyMap<String, Pair<Long, Long>>()
        while (true) {
            runCatching { app.call("download.list") }.getOrNull()?.let { e ->
                val now = System.nanoTime()
                val list = tasksOf(e)
                speeds = list.associate { tk ->
                    val prev = last[tk.id]
                    tk.id to if (prev == null) 0.0 else (tk.got - prev.first) * 1e9 / (now - prev.second).coerceAtLeast(1)
                }
                last = list.associate { it.id to (it.got to now) }
                tasks = list
            }
            delay(2000)
        }
    }
    val list = tasks
    val active = list.orEmpty().filter { it.status != "completed" }
    val done = list.orEmpty().filter { it.status == "completed" }

    fun rowAction(tk: Task) {
        scope.launch {
            when (tk.status) {
                "completed" -> nav.push(TvRoute.Player(tk.id, tk.title, download = true))
                "paused", "failed", "canceled" -> runCatching { app.call("download.resume", args("id" to tk.id)) }.onFailure { app.report(it) }
                else -> runCatching { app.call("download.pause", args("id" to tk.id)) }.onFailure { app.report(it) }
            }
            tick++
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.contentArea()) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.Bottom) {
                PageHead("下载", count = if (list == null) null else "${active.size} 进行中 · ${done.size} 已完成")
                Spacer(Modifier.weight(1f))
                TvButton("下载设置", LpIcons.settings, modifier = Modifier.memo("dl.settings", initial = list?.isEmpty() == false && false),
                    onClick = { overlay.open { DownloadSettings({ overlay.close() }) { tick++ } } })
            }
            if (done.isNotEmpty()) TvText("已下载 ${fmtSize(done.sumOf { it.total }) ?: "0 MB"}", t.meta, TvC.fg2)
            when {
                list == null -> Column(Modifier.padding(top = TvSp.x12), verticalArrangement = Arrangement.spacedBy(TvSp.x6)) {
                    repeat(3) { Skel(Modifier.fillMaxWidth().height(64.dp)) }
                }
                list.isEmpty() -> FullState(LpIcons.download, "下载队列是空的", sub = "详情页的「更多」里可以下载,下好的文件离线也能播",
                    buttons = listOf("去首页"), onButton = { nav.rail(TvRoute.Home) })
                else -> LazyColumn(contentPadding = PaddingValues(bottom = TvSp.x24)) {
                    if (active.isNotEmpty()) item("g1") { TvText("进行中", t.meta, TvC.fg3, Modifier.padding(top = TvSp.x12, bottom = TvSp.x6)) }
                    items(active, key = { it.id }) { tk ->
                        Box(Modifier.padding(vertical = 3.dp)) { DownloadRow(tk, speeds[tk.id] ?: 0.0, list.first() == tk, overlay, { rowAction(tk) }) { tick++ } }
                    }
                    if (done.isNotEmpty()) item("g2") { TvText("已完成", t.meta, TvC.fg3, Modifier.padding(top = TvSp.x12, bottom = TvSp.x6)) }
                    items(done, key = { it.id }) { tk ->
                        Box(Modifier.padding(vertical = 3.dp)) { DownloadRow(tk, 0.0, list.first() == tk, overlay, { rowAction(tk) }) { tick++ } }
                    }
                }
            }
        }
        OverlayHost(overlay)
    }
}

@Composable
private fun DownloadRow(tk: Task, speed: Double, initial: Boolean, overlay: Overlay, onClick: () -> Unit, onChanged: () -> Unit) {
    val app = LocalApp.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val t = tvType
    val failed = tk.status == "failed"
    val remainMin = if (speed > 0 && tk.total > tk.got) ((tk.total - tk.got) / speed / 60).toLong().coerceAtLeast(1) else null
    // 明细:剩余时间**只给到分钟** —— 秒级每两秒跳一次,三米外像页面在抖
    val detail = when (tk.status) {
        "downloading" -> listOfNotNull("${fmtSize(tk.got) ?: "0 MB"} / ${fmtSize(tk.total) ?: "未知大小"}",
            speed.takeIf { it > 0 }?.let { "%.1f MB/s".format(it / 1048576) }, remainMin?.let { "剩 $it 分钟" }).joinToString(" · ")
        "paused" -> "已暂停"
        "queued" -> "排队中"
        "failed" -> "下载失败 · ${tk.error ?: "原因未知"}"
        "canceled" -> "已取消"
        else -> fmtSize(tk.total) ?: ""
    }
    val (right, rightColor) = when (tk.status) {
        "completed" -> "可离线播放" to TvC.ok
        "failed" -> "重试" to TvC.bad
        "paused", "queued", "canceled" -> "${(tk.progress * 100).toInt()}%" to TvC.fg2
        else -> "${(tk.progress * 100).toInt()}%" to TvC.acc
    }
    TvListRow(Modifier.memo("dl.${tk.id}", initial = initial), onClick = onClick, onLongClick = {
        overlay.open {
            var confirm by remember { mutableStateOf(false) }
            if (confirm) TvConfirm("删除「${tk.title}」?", "任务和已下载的文件会一起删除,此操作无法撤销。", "删除",
                onCancel = { overlay.close() }, onConfirm = {
                    scope.launch {
                        runCatching { app.call("download.remove", args("id" to tk.id)) }
                            .onSuccess { overlay.close(); app.toast("已删除", ToastKind.Ok); onChanged() }
                            .onFailure { app.report(it) }
                    }
                })
            else TvSidePanel(tk.title, { overlay.close() }) {
                when (tk.status) {
                    "completed" -> PanelItem("播放", focused = true, onClick = { overlay.close(); nav.push(TvRoute.Player(tk.id, tk.title, download = true)) })
                    "paused", "failed", "canceled" -> PanelItem("继续", focused = true, onClick = { overlay.close(); onClick() })
                    else -> PanelItem("暂停", focused = true, onClick = { overlay.close(); onClick() })
                }
                PanelGroup("危险")
                PanelItem("删除任务", danger = true, onClick = { confirm = true })
            }
        }
    }) { focused ->
        // 失败:行首 3dp bad 竖条,**不弹窗打断**
        if (failed) { Box(Modifier.width(3.dp).height(40.dp).clip(TvR.pill).background(TvC.bad)); Spacer(Modifier.width(TvSp.x8)) }
        Box(Modifier.size(80.dp, 45.dp).clip(TvR.sm)) { TvImage(app.imageUrl(tk.itemId, "Primary", 120)) }
        Spacer(Modifier.width(TvSp.x12))
        Column(Modifier.weight(1f)) {
            RowText(tk.title, t.body)
            RowText(detail, t.meta, .7f)
            if (tk.status == "downloading" || tk.status == "paused") {
                Spacer(Modifier.height(TvSp.x4))
                Box(Modifier.fillMaxWidth(.9f).height(3.dp).clip(TvR.pill).background(TvC.surface3)) {
                    Box(Modifier.fillMaxWidth(tk.progress.coerceIn(0f, 1f)).fillMaxHeight().background(TvC.acc))
                }
            }
        }
        Spacer(Modifier.width(TvSp.x12))
        // 聚焦反白后状态色一律让位给 onFocus:琥珀字压在白底上对比度只有约 2:1
        Text(right, fontSize = t.title, color = if (focused) Color.Unspecified else rightColor, fontWeight = TvW.semi)
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.DownloadSettings(onClose: () -> Unit, onChanged: () -> Unit) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var threads by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        // 不传 threads = 只读回当前值(并发数只读不灌)
        threads = (runCatching { app.call("download.setThreads") }.getOrNull().obj().long("threads") ?: 2L).toInt()
    }
    TvSidePanel("下载设置", onClose) {
        PanelGroup("并发线程")
        (1..4).forEach { n ->
            PanelItem("$n", selected = n == threads, focused = n == threads, onClick = {
                val old = threads
                threads = n
                scope.launch {
                    runCatching { app.call("download.setThreads", args("threads" to n)) }.onFailure { threads = old; app.report(it) }
                }
            })
        }
        PanelGroup("记录")
        PanelItem("清除已完成记录", sub = "只清记录,不删文件", onClick = {
            scope.launch {
                runCatching { app.call("download.clearCompleted") }
                    .onSuccess { app.toast("已清除完成的记录(文件保留)", ToastKind.Ok); onChanged(); onClose() }
                    .onFailure { app.report(it) }
            }
        })
    }
}

// ---------------------------------------------------------------- 全局观看历史(SPEC 8.7 D430 D431)

/** ticks 是 100 纳秒单位 —— 除以 1e7 才是秒。写成 1e6 时长会大十倍且不报错。 */
private fun clock(secs: Double): String {
    val s = secs.toInt()
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/**
 * 全局观看历史。这份记录是**本地库**不是服务器的播放记录 —— 跨服续播就靠它,
 * 所以「只看当前服务器」和「全部」是两种看法,都要有。
 *
 * ★ 数据源那部分单开一排(`source.history`):它已经按换源链路合并过,
 *   来源被删的仍然显示但点不开(D333)—— 藏起来用户会以为记录丢了。
 */
@Composable
fun HistoryPageTv() {
    val app = LocalApp.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val t = tvType
    var onlyCurrent by keepState("tv.hist.only") { true }
    var recs by remember { mutableStateOf<List<kotlinx.serialization.json.JsonObject>?>(null) }
    var srcRows by remember { mutableStateOf<List<kotlinx.serialization.json.JsonObject>>(emptyList()) }
    LaunchedEffect(onlyCurrent) {
        recs = runCatching { app.call("emby.watchHistoryList", args("current_only" to onlyCurrent)) }
            .getOrNull().arr().mapNotNull { it.obj() }
            // 核心层不保证顺序,排序是展示层的事
            .sortedByDescending { it.long("last_played_at") ?: 0L }
    }
    LaunchedEffect(Unit) { srcRows = runCatching { app.call("source.history") }.getOrNull().arr().mapNotNull { it.obj() } }

    val list = recs
    Column(Modifier.contentArea()) {
        PageHead("观看历史", count = list?.let { "${it.size} 条" } ?: "")
        Spacer(Modifier.height(TvSp.x8))
        ScopeChips(listOf("当前服务器", "全部服务器"), if (onlyCurrent) 0 else 1,
            itemModifier = { i -> Modifier.memo("hist.scope.$i") }, onSelect = { onlyCurrent = it == 0 })
        Spacer(Modifier.height(TvSp.x12))
        if (list == null) {
            Column(verticalArrangement = Arrangement.spacedBy(TvSp.x6)) { repeat(5) { Skel(Modifier.fillMaxWidth().height(56.dp)) } }
            return@Column
        }
        if (list.isEmpty() && srcRows.isEmpty()) {
            FullState(LpIcons.rewind, "还没有观看记录", sub = "看过的片会记在本机,换服务器或重装之后还在", buttons = emptyList())
            return@Column
        }
        LazyColumn(Modifier.bleed(12.dp), verticalArrangement = Arrangement.spacedBy(TvSp.x6),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = TvSp.x24)) {
            if (srcRows.isNotEmpty()) {
                item("srch") { Box(Modifier.padding(bottom = TvSp.x8)) { RowTitle("数据源", trailing = "${srcRows.size} 项") } }
                item("src") {
                    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(TvSp.x12),
                        contentPadding = PaddingValues(vertical = TvSp.x6)) {
                        items(srcRows.take(30), key = { (it.str("server_id") ?: "") + "." + (it["ref"].obj()?.get("item").obj().str("id") ?: "") }) { c ->
                            val r = c["ref"].obj() ?: return@items
                            val it2 = r["item"].obj() ?: return@items
                            val removed = c.bool("removed")
                            val sid = c.str("server_id") ?: ""
                            val pos = c.dbl("position_secs") ?: 0.0
                            SourcePoster(it2, "hist.s.$sid.${it2.str("id")}",
                                badge = if (removed) "来源已移除" else r.str("episodeName")) {
                                if (removed) app.toast("这个来源已经移除了")
                                else nav.push(TvRoute.SourceDetail(sid, it2.str("id") ?: "", r.str("lineId"), r.str("episodeId"), pos))
                            }
                        }
                    }
                }
                if (list.isNotEmpty()) item("embyh") { Box(Modifier.padding(top = TvSp.x12, bottom = TvSp.x8)) { RowTitle("服务器", trailing = "${list.size} 条") } }
            }
            items(list.take(200), key = { it.str("record_id") ?: "" }) { rec ->
                val series = rec.str("series_title").orEmpty()
                val title = rec.str("title").orEmpty()
                val pos = (rec.long("last_position_ticks") ?: 0L) / 1e7
                val run = (rec.long("run_time_ticks") ?: 0L) / 1e7
                val right = if (rec.bool("played")) "已看完" else if (run > 0) "${clock(pos)} / ${clock(run)}" else clock(pos)
                val itemId = rec.str("last_emby_item_id")
                // scope_key 是 `server:user_id`,server 自带 https:// 甚至端口 —— 按**最后一个**冒号切
                val sid = (rec.str("scope_key") ?: "").substringBeforeLast(':', "")
                TvListRow(Modifier.memo("hist.${rec.str("record_id")}"), onClick = {
                    if (itemId.isNullOrEmpty()) { app.toast("这条记录没有对应的条目,换服务器后要先「扫描恢复」"); return@TvListRow }
                    scope.launch {
                        if (sid.isNotEmpty() && !switchServerIfNeeded(app, sid)) return@launch
                        nav.push(TvRoute.Detail(itemId, if (series.isEmpty()) "Movie" else "Episode"))
                    }
                }) {
                    Column(Modifier.weight(1f)) {
                        RowText(if (series.isEmpty()) title else "$series · $title", t.body)
                        val whenMs = rec.long("last_played_at") ?: 0L
                        // last_played_at 是**毫秒**(core/history/store.go 的 nowMs),当秒读日期会跳到五万年后
                        RowText(if (whenMs > 0) java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                            .format(java.util.Date(whenMs)) else "", t.meta, .7f)
                    }
                    Text(right, fontSize = t.meta, color = Color.Unspecified, fontWeight = TvW.semi)
                }
            }
        }
    }
}
