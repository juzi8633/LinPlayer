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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import xyz.linplayer.app.data.Block
import xyz.linplayer.app.data.Item
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.Page
import xyz.linplayer.app.data.ToastKind
import xyz.linplayer.app.data.View
import xyz.linplayer.app.data.arr
import xyz.linplayer.app.data.block
import xyz.linplayer.app.data.keepState
import xyz.linplayer.app.data.obj
import xyz.linplayer.app.data.str
import xyz.linplayer.app.data.strList
import xyz.linplayer.app.tv.kit.CardLibrary
import xyz.linplayer.app.tv.kit.EntryChip
import xyz.linplayer.app.tv.kit.FullState
import xyz.linplayer.app.tv.kit.PageHead
import xyz.linplayer.app.tv.kit.PanelGroup
import xyz.linplayer.app.tv.kit.PanelItem
import xyz.linplayer.app.tv.kit.Skel
import xyz.linplayer.app.tv.kit.TvC
import xyz.linplayer.app.tv.kit.TvDim
import xyz.linplayer.app.tv.kit.TvSp
import xyz.linplayer.app.tv.kit.TvText
import xyz.linplayer.app.tv.kit.bleed
import xyz.linplayer.app.tv.kit.contentArea
import xyz.linplayer.app.tv.kit.tvType
import xyz.linplayer.app.ui.pages.args
import xyz.linplayer.app.ui.pages.jsonArrayOf
import xyz.linplayer.app.ui.pages.map
import xyz.linplayer.app.ui.theme.LpIcons

// ---------------------------------------------------------------- 选库(§7.3)

/**
 * ★ **已屏蔽的库也列出来**【继承 PC A-55】:这是唯一能找回并解除它的地方。按库 **id** 判。
 */
@Composable
fun LibraryPickerPage() {
    val app = LocalApp.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val overlay = rememberOverlay()
    val server = app.session.collectAsStateWithLifecycle().value?.server
    var views by keepState<Block<List<View>>>("tv.libs.$server") { Block.Loading }
    var blocked by keepState<Set<String>>("tv.libs.blocked.$server") { emptySet() }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(server, reload) {
        launch { views = app.block("emby.views", args("include_blocked" to true)).map { View.list(it) } }
        launch { blocked = runCatching { app.call("emby.blockedList") }.getOrNull().arr().mapNotNull { it.obj().str("id") }.toSet() }
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.contentArea()) {
            PageHead("媒体库", sub = "选择一个库")
            Spacer(Modifier.height(TvSp.x16))
            when (val v = views) {
                is Block.Loading -> Row(horizontalArrangement = Arrangement.spacedBy(TvSp.x32)) {
                    repeat(4) { Skel(Modifier.size(TvDim.libW, TvDim.libH)) }
                }
                is Block.Fail -> FullState(LpIcons.info, "没加载出来", detail = v.message, buttons = listOf("重试"),
                    tone = TvC.bad, onButton = { reload++ })
                is Block.Ok -> if (v.value.isEmpty()) FullState(LpIcons.grid, "这个账号下没有媒体库",
                    sub = "在服务器上建一个库,或者换一台服务器", buttons = listOf("去服务器页"),
                    onButton = { nav.rail(TvRoute.Servers) })
                else LazyVerticalGrid(
                    GridCells.Fixed(4), horizontalArrangement = Arrangement.spacedBy(TvSp.x32),
                    verticalArrangement = Arrangement.spacedBy(TvSp.x20), contentPadding = PaddingValues(12.dp),
                    modifier = Modifier.bleed(12.dp),
                ) {
                    itemsIndexed(v.value, key = { _, it -> it.id }) { i, lib ->
                        val isBlocked = lib.id in blocked
                        CardLibrary(
                            cover = { TvImage(app.imageUrl(lib.id, "Primary", 220), scale = ContentScale.Crop) },
                            name = lib.name, blocked = isBlocked, modifier = Modifier.memo("lib.${lib.id}", initial = i == 0),
                            onLongClick = {
                                overlay.open {
                                    TvSidePanel(lib.name, { overlay.close() }) {
                                        PanelItem(if (isBlocked) "解除屏蔽" else "屏蔽这个库", danger = !isBlocked,
                                            sub = if (isBlocked) null else "屏蔽后首页不再显示这个库的行,这里仍然列出来",
                                            focused = true, onClick = {
                                                scope.launch {
                                                    runCatching {
                                                        app.call("emby.setBlocked", args("id" to lib.id, "name" to lib.name, "blocked" to !isBlocked))
                                                    }.onSuccess {
                                                        overlay.close(); reload++
                                                        app.toast(if (isBlocked) "已解除屏蔽" else "已屏蔽「${lib.name}」", ToastKind.Ok)
                                                    }.onFailure { app.report(it) }
                                                }
                                            })
                                    }
                                }
                            },
                            onClick = { nav.push(TvRoute.Library(lib.id, lib.name)) },
                        )
                    }
                }
            }
        }
        OverlayHost(overlay)
    }
}

// ---------------------------------------------------------------- 库内网格(§7.3)

private const val LibPage = 60

/** 排序档。「更新时间」= DateLastContentAdded ≠「加入日期」DateCreated【用户定 2026-07-21】。 */
private val LibSorts = listOf(
    Triple("更新时间", "DateLastContentAdded", "Descending"),
    Triple("加入日期", "DateCreated", "Descending"),
    Triple("名称", "SortName", "Ascending"),
    Triple("名称 Z→A", "SortName", "Descending"),
    Triple("评分", "CommunityRating", "Descending"),
    Triple("上映日期", "PremiereDate", "Descending"),
    Triple("年份", "ProductionYear", "Descending"),
)

private val PlayedStates = listOf("全部" to null, "未看" to false, "已看" to true)

@Composable
fun LibraryGridPage(r: TvRoute.Library) {
    val app = LocalApp.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val overlay = rememberOverlay()
    val t = tvType
    val viewId = r.viewId ?: return
    val ck = "tv.lib.$viewId.${app.session.collectAsStateWithLifecycle().value?.server}"

    var sort by keepState("$ck.sort") { 0 }
    var genre by keepState<String?>("$ck.genre") { null }
    var year by keepState<Long?>("$ck.year") { null }
    var played by keepState("$ck.played") { 0 }
    var items by keepState<List<Item>>("$ck.items") { emptyList() }
    var total by keepState<Long?>("$ck.total") { null }
    var first by keepState<Block<Unit>>("$ck.first") { Block.Loading }
    var fetchedAs by keepState<String?>("$ck.as") { null }
    var moreFailed by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var facets by keepState<Block<Pair<List<String>, List<Long>>>>("$ck.facets") { Block.Loading }

    val filterKey = "$sort|$genre|$year|$played"
    val hasFilter = genre != null || year != null || played != 0

    /* ☠ `query` 是**嵌套对象**:平铺传 = 核心层读不到 = 每个库都是同一份全站列表且不报错。 */
    suspend fun fetch(offset: Int) {
        val (_, by, order) = LibSorts[sort]
        val q = buildMap<String, JsonElement> {
            put("start_index", JsonPrimitive(offset)); put("limit", JsonPrimitive(LibPage))
            put("sort_by", JsonPrimitive(by)); put("sort_order", JsonPrimitive(order))
            genre?.let { put("genres", jsonArrayOf(listOf(it))) }
            year?.let { put("years", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive(it)))) }
            PlayedStates[played].second?.let { put("played", JsonPrimitive(it)) }
        }
        val a = buildMap<String, Any> {
            put("parent_id", viewId)
            put("query", kotlinx.serialization.json.JsonObject(q))
        }
        when (val res = app.block("emby.listItemsPage", args(*a.toList().toTypedArray()))) {
            is Block.Ok -> {
                val p = Page.from(res.value)
                items = if (offset == 0) p.items else items + p.items
                total = p.total
                first = Block.Ok(Unit)
                moreFailed = false
            }
            is Block.Fail -> if (offset == 0) first = res else moreFailed = true
            else -> Unit
        }
    }

    LaunchedEffect(filterKey) {
        if (fetchedAs == filterKey && first is Block.Ok) return@LaunchedEffect
        fetchedAs = filterKey
        first = Block.Loading; items = emptyList(); total = null
        fetch(0)
    }
    LaunchedEffect(viewId) {
        if (facets is Block.Ok) return@LaunchedEffect
        facets = app.block("emby.getFilters", args("parent_id" to viewId)).map { e ->
            val o = e.obj()
            o.strList("genres") to o?.get("years").arr().mapNotNull { (it as? JsonPrimitive)?.content?.toLongOrNull() }
        }
    }
    val hasMore = total?.let { items.size < it } ?: false
    fun loadMore() {
        if (loadingMore || !hasMore || moreFailed) return
        loadingMore = true
        scope.launch { fetch(items.size); loadingMore = false }
    }

    val sortLabel = LibSorts[sort].let { it.first + if (it.third == "Descending" && it.second != "SortName") " ↓" else "" }
    val condition = listOfNotNull(sortLabel, genre ?: "全部类型", year?.toString() ?: "全部年份",
        PlayedStates[played].takeIf { played != 0 }?.first).joinToString(" · ")

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.contentArea()) {
            PageHead(r.title, count = total?.takeIf { first is Block.Ok }?.let { "%,d 项".format(it) })
            Spacer(Modifier.height(TvSp.x12))
            EntryChip("筛选与排序", LpIcons.filter, condition, modifier = Modifier.memo("lib.filter", initial = true), onClick = {
                overlay.open { FilterPanel(sort, genre, year, played, facets, { overlay.close() },
                    onSort = { sort = it }, onGenre = { genre = it }, onYear = { year = it }, onPlayed = { played = it }) }
            })
            Spacer(Modifier.height(TvSp.x16))
            when (val f = first) {
                is Block.Loading -> Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    repeat(6) { Skel(Modifier.size(124.dp, 186.dp)) }
                }
                is Block.Fail -> FullState(LpIcons.info, "没加载出来", detail = f.message, buttons = listOf("重试"), tone = TvC.bad,
                    onButton = { fetchedAs = null; scope.launch { first = Block.Loading; fetch(0) } })
                is Block.Ok -> if (items.isEmpty()) {
                    if (hasFilter) FullState(LpIcons.filter, "当前筛选下没有内容", buttons = listOf("清除筛选"),
                        onButton = { genre = null; year = null; played = 0 })
                    else FullState(LpIcons.grid, "这个库还没有内容", buttons = listOf("返回"), onButton = { nav.pop() })
                } else {
                    val grid = rememberLazyGridState()
                    LazyVerticalGrid(
                        GridCells.Fixed(6), state = grid, horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(TvSp.x20), contentPadding = PaddingValues(12.dp),
                        modifier = Modifier.bleed(12.dp),
                    ) {
                        itemsIndexed(items, key = { _, it -> it.id }) { i, it ->
                            // 焦点进入**倒数第二行**时预取下一页:遥控器焦点比滚轮快,等到最后一行用户会看见一段空白
                            Box(Modifier.onFocusChanged { f -> if (f.hasFocus && i >= items.size - 12) loadMore() }) {
                                ItemPoster(it, "lib.$viewId.${it.id}", { item ->
                                    overlay.openCardMenu(app, nav, scope, item) { tag ->
                                        if (tag == "blocked") items = items.filterNot { x -> x.id == item.id }
                                    }
                                }, w = 124.dp, h = 186.dp, sub = it.year?.toString().orEmpty())
                            }
                        }
                        if (moreFailed) item(span = { GridItemSpan(maxLineSpan) }) {
                            PanelItem("没加载出来 · 按确认重试", onClick = { moreFailed = false; loadMore() })
                        } else if (!hasMore) item(span = { GridItemSpan(maxLineSpan) }) {
                            TvText("已经到底了 · 共 ${items.size} 项", t.meta, TvC.fg3, Modifier.fillMaxWidth().padding(vertical = TvSp.x8))
                        }
                    }
                }
            }
        }
        OverlayHost(overlay)
    }
}

/** 筛选与排序面板。**选一项不关面板**(连着改几项是常态);默认焦点在当前排序档。 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.FilterPanel(
    sort: Int, genre: String?, year: Long?, played: Int,
    facets: Block<Pair<List<String>, List<Long>>>, onClose: () -> Unit,
    onSort: (Int) -> Unit, onGenre: (String?) -> Unit, onYear: (Long?) -> Unit, onPlayed: (Int) -> Unit,
) {
    // 面板里的选中态要跟着改动走,而 overlay 的内容 lambda 只捕获了打开那一刻的值
    var s by remember { mutableIntStateOf(sort) }
    var g by remember { mutableStateOf(genre) }
    var y by remember { mutableStateOf(year) }
    var p by remember { mutableIntStateOf(played) }
    TvSidePanel("筛选与排序", onClose) {
        PanelGroup("排序")
        LibSorts.forEachIndexed { i, (label) ->
            PanelItem(label, selected = i == s, focused = i == s, onClick = { s = i; onSort(i) })
        }
        val f = facets.valueOrNull
        PanelGroup("类型")
        if (f == null || f.first.isEmpty()) PanelItem("这台服务器没有提供类型列表", enabled = false)
        else {
            PanelItem("全部类型", selected = g == null, onClick = { g = null; onGenre(null) })
            f.first.forEach { name -> PanelItem(name, selected = g == name, onClick = { g = name; onGenre(name) }) }
        }
        PanelGroup("年份")
        if (f == null || f.second.isEmpty()) PanelItem("这台服务器没有提供年份列表", enabled = false)
        else {
            PanelItem("全部年份", selected = y == null, onClick = { y = null; onYear(null) })
            f.second.forEach { v -> PanelItem(v.toString(), selected = y == v, onClick = { y = v; onYear(v) }) }
        }
        PanelGroup("状态")
        PlayedStates.forEachIndexed { i, (label) -> PanelItem(label, selected = i == p, onClick = { p = i; onPlayed(i) }) }
    }
}
