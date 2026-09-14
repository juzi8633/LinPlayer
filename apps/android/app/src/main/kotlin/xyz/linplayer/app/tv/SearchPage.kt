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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import xyz.linplayer.app.data.Account
import xyz.linplayer.app.data.Block
import xyz.linplayer.app.data.Item
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.arr
import xyz.linplayer.app.data.block
import xyz.linplayer.app.data.keepState
import xyz.linplayer.app.data.obj
import xyz.linplayer.app.data.str
import xyz.linplayer.app.data.strList
import xyz.linplayer.app.tv.kit.ProvideRowKeyline
import xyz.linplayer.app.tv.kit.RowTitle
import xyz.linplayer.app.tv.kit.ScopeChips
import xyz.linplayer.app.tv.kit.Skel
import xyz.linplayer.app.tv.kit.TvButton
import xyz.linplayer.app.tv.kit.TvC
import xyz.linplayer.app.tv.kit.TvR
import xyz.linplayer.app.tv.kit.TvSp
import xyz.linplayer.app.tv.kit.TvText
import xyz.linplayer.app.tv.kit.contentArea
import xyz.linplayer.app.tv.kit.tvType
import xyz.linplayer.app.ui.pages.args
import xyz.linplayer.app.ui.pages.jsonArrayOf
import xyz.linplayer.app.ui.pages.map
import xyz.linplayer.app.ui.theme.LpIcons

/** 一条结果。`server` 非空 = 来自聚合搜索,行尾标来源服务器。 */
private data class Hit(val item: Item, val serverId: String?, val serverName: String?)

/**
 * 搜索(UI_TV.md §7.7)。**初始焦点 = 搜索框** → 进页即升起输入法;词和历史都在上半屏。
 *
 * ★ 400ms 防抖才发请求;历史只在「用户确认这个词」时记(完成键或点了结果),
 *   跟着防抖记会把「幕」「幕府」「幕府将」全记进去。
 * ★ 聚合时同一部片多台都有,**并列显示不去重**:去重就得挑代表,而「哪台版本更好」正是用户点进去要看的。
 */
@OptIn(FlowPreview::class)
@Composable
fun SearchPage() {
    val app = LocalApp.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val overlay = rememberOverlay()
    val t = tvType

    var q by keepState("tv.search.q") { "" }
    var aggregate by keepState("tv.search.agg") { false }
    var withEps by keepState("tv.search.eps") { false }
    var history by remember { mutableStateOf<List<String>>(emptyList()) }
    var servers by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<Block<List<Hit>>?>(null) }
    var failed by remember { mutableStateOf<List<String>>(emptyList()) }
    var retry by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        launch { history = runCatching { app.call("prefs.getPrefs") }.getOrNull().obj().strList("search_history") }
        launch { servers = Account.list(runCatching { app.call("account.listAccounts") }.getOrNull()).count { (it.kind ?: "emby") == "emby" } }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { listOf(q.trim(), aggregate, withEps, retry) }.distinctUntilChanged().debounce(400).collect { (text, agg, eps) ->
            text as String
            if (text.isEmpty()) { result = null; return@collect }
            result = Block.Loading
            failed = emptyList()
            result = if (agg as Boolean) {
                app.block("emby.aggregateSearch", args("query" to text, "include_episodes" to eps)).map { v ->
                    val gs = v.arr().mapNotNull { it.obj() }
                    // 半失败(一路 429、一路回空)不能吞成「没搜到」
                    failed = gs.filter { it.str("error") != null }.map { it.str("server_name") ?: "服务器" }
                    // 按相关度排、同名挨着(§7.7 并列):按服务器分段排的话同一部片隔着几行,比不出哪台的版本好
                    gs.flatMap { g -> Item.list(g["items"]).map { Hit(it, g.str("server_id"), g.str("server_name")) } }
                        .sortedWith(compareBy<Hit>({ !it.item.name.startsWith(text) }, { it.item.name }))
                }
            } else {
                // ☠ `types` 必须是**数组**,传逗号串核心层读不到
                val types = if (eps as Boolean) listOf("Movie", "Series", "Episode") else listOf("Movie", "Series")
                app.block("emby.search", args("query" to text, "types" to jsonArrayOf(types), "limit" to 60))
                    .map { v -> Item.list(v).map { Hit(it, null, null) } }
            }
        }
    }

    fun remember(term: String) {
        val w = term.trim()
        if (w.isEmpty()) return
        scope.launch {
            history = runCatching { app.call("prefs.pushSearch", args("query" to w)) }.getOrNull().obj().strList("items")
                .ifEmpty { (listOf(w) + history).distinct() }
        }
    }

    fun open(h: Hit) {
        remember(q)
        scope.launch {
            // 结果来自别的服务器:先切服务器,**成功后**再进详情页
            if (h.serverId != null && !switchServerIfNeeded(app, h.serverId)) return@launch
            openItem(nav, h.item)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Row(Modifier.contentArea()) {
            Column(Modifier.width(300.dp)) {
                TvTextField(q, { q = it }, "搜索片名", 300.dp, Modifier.memo("search.input", initial = true),
                    icon = LpIcons.search, onSubmit = { remember(it) })
                Spacer(Modifier.height(TvSp.x16))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TvText("搜索历史", t.meta, TvC.fg3)
                    Spacer(Modifier.weight(1f))
                    // 「清空」是看得见的按钮,不藏进菜单键【用户定 2026-07-20】
                    if (history.isNotEmpty()) TvButton("清空", LpIcons.trash, modifier = Modifier.memo("search.clear"), onClick = {
                        scope.launch {
                            runCatching { app.call("prefs.setPrefs", args("search_history" to JsonArray(emptyList()))) }
                                .onSuccess { history = emptyList() }.onFailure { app.report(it) }
                        }
                    })
                }
                Spacer(Modifier.height(TvSp.x6))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(TvSp.x2)) {
                    items(history, key = { it }) { term ->
                        TvListRow(Modifier.memo("search.h.$term"), height = t.rowH, onClick = { q = term }) {
                            Icon(LpIcons.search, null, Modifier.size(t.iconS))
                            Spacer(Modifier.width(TvSp.x8))
                            RowText(term, t.body)
                        }
                    }
                }
            }
            Spacer(Modifier.width(TvSp.x24))
            Column(Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(TvSp.x8)) {
                    ScopeChips(listOf("当前服务器", "聚合全部 · $servers"), if (aggregate) 1 else 0,
                        itemModifier = { i -> Modifier.memo("search.scope.$i") }, onSelect = { aggregate = it == 1 })
                    ScopeChips(listOf("包括分集"), if (withEps) 0 else -1,
                        itemModifier = { Modifier.memo("search.eps") }, onSelect = { withEps = !withEps })
                }
                Spacer(Modifier.height(TvSp.x12))
                SearchResults(q.trim(), result, failed, aggregate, overlay, { open(it) }, { retry++ })
            }
        }
        OverlayHost(overlay)
    }
}

@Composable
private fun SearchResults(
    q: String, result: Block<List<Hit>>?, failed: List<String>, aggregate: Boolean, overlay: Overlay,
    onOpen: (Hit) -> Unit, onRetry: () -> Unit,
) {
    val app = LocalApp.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val t = tvType
    when (result) {
        null -> TvText("输入片名开始搜索", t.body, TvC.fg3)
        is Block.Loading -> Column(verticalArrangement = Arrangement.spacedBy(TvSp.x6)) {
            repeat(5) { Skel(Modifier.fillMaxWidth().height(64.dp)) }
        }
        // 搜挂了和搜不到是两回事
        is Block.Fail -> Column {
            TvText("搜索失败:${result.message}", t.body, TvC.fg2, maxLines = 3)
            Spacer(Modifier.height(TvSp.x12))
            TvButton("重试", LpIcons.refresh, modifier = Modifier.memo("search.retry"), onClick = onRetry)
        }
        is Block.Ok -> {
            val hits = result.value
            if (hits.isEmpty()) {
                TvText("没有找到「$q」", t.body, TvC.fg2)
                if (failed.isNotEmpty()) TvText("这些服务器没搜成:${failed.joinToString("、")}", t.meta, TvC.fg3, maxLines = 2)
                return
            }
            val rest = hits.filterNot { it.item.isEpisode }
            val eps = hits.filter { it.item.isEpisode }
            TvText("结果 · ${hits.size}", t.meta, TvC.fg3)
            Spacer(Modifier.height(TvSp.x6))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(TvSp.x6), contentPadding = PaddingValues(bottom = TvSp.x12)) {
                items(rest, key = { "${it.serverId}.${it.item.id}" }) { h ->
                    TvListRow(Modifier.memo("search.r.${h.serverId}.${h.item.id}"), onClick = { onOpen(h) },
                        // 跨服结果不给卡片面板:收藏 / 标已看是对**当前**服务器写的,对着别的服的条目会写错地方
                        onLongClick = if (h.serverId == null) ({ overlay.openCardMenu(app, nav, scope, h.item) }) else null) {
                        Box(Modifier.size(38.dp, 57.dp).clip(TvR.sm)) { TvImage(app.imageUrl(h.item.id, "Primary", 120)) }
                        Spacer(Modifier.width(TvSp.x12))
                        Column(Modifier.weight(1f)) {
                            RowText(h.item.name, t.body)
                            RowText(listOfNotNull(h.item.year?.toString(),
                                if (h.item.isSeries) "剧集" else "电影").joinToString(" · "), t.meta, .7f)
                        }
                        if (aggregate && h.serverName != null) RowText(h.serverName, t.meta, .7f)
                    }
                }
                if (eps.isNotEmpty()) item("eps") {
                    Column {
                        RowTitle("分集", trailing = "${eps.size} 项")
                        Spacer(Modifier.height(TvSp.x8))
                        ProvideRowKeyline(TvSp.x12) {
                            LazyRow(contentPadding = PaddingValues(horizontal = TvSp.x12, vertical = TvSp.x6),
                                horizontalArrangement = Arrangement.spacedBy(TvSp.x12)) {
                                items(eps, key = { "${it.serverId}.${it.item.id}" }) { h ->
                                    ItemWide(h.item, "search.e.${h.serverId}.${h.item.id}", { item ->
                                        if (h.serverId == null) overlay.openCardMenu(app, nav, scope, item)
                                    }, onOpen = { onOpen(h) })
                                }
                            }
                        }
                    }
                }
                if (failed.isNotEmpty()) item("failed") {
                    TvText("这些服务器没搜成:${failed.joinToString("、")}", t.meta, TvC.fg3, maxLines = 2)
                }
            }
        }
    }
}
