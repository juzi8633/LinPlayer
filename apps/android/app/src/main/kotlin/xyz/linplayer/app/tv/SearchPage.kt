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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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

/** 一条结果。[serverId] 非空 = 来自聚合搜索,点开前要先切到那台服务器。 */
private data class Hit(val item: Item, val serverId: String?)

/**
 * 搜索(UI_TV.md §7.7)。**初始焦点 = 搜索框** → 进页即升起输入法;词和历史都在上半屏。
 *
 * ★ 400ms 防抖才发请求;历史只在「用户确认这个词」时记(完成键或点了结果),
 *   跟着防抖记会把「幕」「幕府」「幕府将」全记进去。
 * ★ 聚合结果按源分行(§7.7 / D258),同一部片多台都有就**并列显示不去重**:
 *   去重就得挑代表,而「哪台版本更好」正是用户点进去要看的。
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
    // 当前是插件数据源时没有 Emby 会话:只能聚合搜(含数据源,D258)
    val onSource = app.activeSource.collectAsStateWithLifecycle().value != null
    var aggregate by keepState("tv.search.agg") { false }
    if (onSource) aggregate = true
    /** 聚合只在提交(遥控器确认 / 离开输入框)时发:每停一下就撒给所有来源太重。 */
    var aggQ by remember { mutableStateOf("") }
    /** 聚合结果**一个来源一行**(D258 D262),谁先回来谁先出现 —— 核心层就是一行一行 partial 推的。 */
    val aggRows = remember { androidx.compose.runtime.mutableStateListOf<kotlinx.serialization.json.JsonObject>() }
    var aggBusy by remember { mutableStateOf(false) }
    var withEps by keepState("tv.search.eps") { false }
    var history by remember { mutableStateOf<List<String>>(emptyList()) }
    var servers by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<Block<List<Hit>>?>(null) }
    var retry by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        launch { history = runCatching { app.call("prefs.getPrefs") }.getOrNull().obj().strList("search_history") }
        launch { servers = Account.list(runCatching { app.call("account.listAccounts") }.getOrNull()).count { (it.kind ?: "emby") == "emby" } }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { listOf(q.trim(), aggregate, withEps, retry) }.distinctUntilChanged().debounce(400).collect { (typed, agg, eps, _) ->
            if (agg as Boolean) return@collect          // 聚合走下面那条按提交触发的路
            val text = typed as String
            if (text.isEmpty()) { result = null; return@collect }
            result = Block.Loading
            // ☠ `types` 必须是**数组**,传逗号串核心层读不到
            val types = if (eps as Boolean) listOf("Movie", "Series", "Episode") else listOf("Movie", "Series")
            result = app.block("emby.search", args("query" to text, "types" to jsonArrayOf(types), "limit" to 60))
                .map { v -> Item.list(v).map { Hit(it, null) } }
        }
    }
    LaunchedEffect(aggQ, aggregate, retry) {
        if (!aggregate || aggQ.isEmpty()) return@LaunchedEffect
        aggRows.clear()
        aggBusy = true
        // partial 回调在核心层的线程上:切回自己的 scope 再动 Compose 状态
        runCatching { app.call("source.aggregateSearch", args("query" to aggQ)) { p -> p.obj()?.let { scope.launch { aggRows.add(it) } } } }
            .onFailure { app.report(it) }
        aggBusy = false
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
                    icon = LpIcons.search, onSubmit = { remember(it); aggQ = it.trim() })
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
                    if (!onSource) ScopeChips(listOf("当前服务器", "聚合全部 · $servers"), if (aggregate) 1 else 0,
                        itemModifier = { i -> Modifier.memo("search.scope.$i") }, onSelect = { aggregate = it == 1 })
                    ScopeChips(listOf("包括分集"), if (withEps) 0 else -1,
                        itemModifier = { Modifier.memo("search.eps") }, onSelect = { withEps = !withEps })
                }
                Spacer(Modifier.height(TvSp.x12))
                if (aggregate) AggregateRows(q.trim(), aggQ, aggRows, aggBusy) { h -> open(h) }
                else SearchResults(q.trim(), result, overlay, { open(it) }, { retry++ })
            }
        }
        OverlayHost(overlay)
    }
}

/**
 * 聚合结果:**一个来源一行**(§7.7 / D258 D262)。行内横排,行头写来源名和条数。
 *
 * ★ 不压平成一张总表:压平就得给「哪台的版本更好」挑一个代表,而那正是用户按进去要看的;
 *   TV 上横排一行也正好是遥控器「上下选源、左右挑片」的走法。
 * ★ 跨服条目**不给卡片面板**:收藏 / 标已看写的是**当前**服务器,对着别的服的条目会写错地方。
 */
@Composable
private fun AggregateRows(
    q: String, submitted: String, rows: List<kotlinx.serialization.json.JsonObject>, busy: Boolean, onOpen: (Hit) -> Unit,
) {
    val nav = LocalNav.current
    val t = tvType
    if (submitted.isEmpty()) {
        TvText(if (q.isEmpty()) "输入片名,按确认键在所有来源里搜" else "按确认键开始搜 —— 每个来源各占一行,谁先回来谁先显示", t.body, TvC.fg3, maxLines = 2)
        return
    }
    if (rows.isEmpty()) {
        if (busy) Column(verticalArrangement = Arrangement.spacedBy(TvSp.x6)) { repeat(3) { Skel(Modifier.fillMaxWidth().height(96.dp)) } }
        else TvText("「$submitted」没搜到东西 —— 只包括打开了「允许聚合」的来源", t.body, TvC.fg2, maxLines = 2)
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(TvSp.x12), contentPadding = PaddingValues(bottom = TvSp.x12)) {
        items(rows, key = { it.str("server_id") ?: "" }) { g ->
            val sid = g.str("server_id") ?: ""
            val name = g.str("server_name") ?: sid
            val err = g.str("error")
            val plugin = g.str("kind") == "plugin"
            val embyItems = if (plugin) emptyList() else Item.list(g["emby_items"])
            val srcItems = if (plugin) g["items"].arr().mapNotNull { it.obj() } else emptyList()
            val n = embyItems.size + srcItems.size
            Column {
                // 半失败(一路 429、一路回空)不能吞成「没搜到」:错了的源自己占一行说话
                when {
                    err != null -> TvText("$name 没搜成:$err", t.meta, TvC.fg3, maxLines = 2)
                    n == 0 -> TvText("$name · 没有结果", t.meta, TvC.fg3)
                    else -> {
                        RowTitle(name, trailing = "$n 条")
                        Spacer(Modifier.height(TvSp.x8))
                        ProvideRowKeyline(TvSp.x12) {
                            LazyRow(contentPadding = PaddingValues(horizontal = TvSp.x12, vertical = TvSp.x6),
                                horizontalArrangement = Arrangement.spacedBy(TvSp.x12)) {
                                items(embyItems, key = { "$sid.${it.id}" }) { item ->
                                    ItemPoster(item, "search.agg.$sid.${item.id}", onMenu = {},
                                        onOpen = { onOpen(Hit(item, sid)) })
                                }
                                items(srcItems, key = { "$sid.${it.str("id")}" }) { it ->
                                    SourcePoster(it, "search.agg.$sid.${it.str("id")}") {
                                        nav.push(TvRoute.SourceDetail(it.str("source") ?: sid, it.str("id") ?: ""))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (busy) item("busy") { TvText("还有来源在搜…", t.meta, TvC.fg3) }
    }
}

@Composable
private fun SearchResults(
    q: String, result: Block<List<Hit>>?, overlay: Overlay,
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
                return
            }
            val rest = hits.filterNot { it.item.isEpisode }
            val eps = hits.filter { it.item.isEpisode }
            TvText("结果 · ${hits.size}", t.meta, TvC.fg3)
            Spacer(Modifier.height(TvSp.x6))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(TvSp.x6), contentPadding = PaddingValues(bottom = TvSp.x12)) {
                items(rest, key = { it.item.id }) { h ->
                    TvListRow(Modifier.memo("search.r.${h.item.id}"), onClick = { onOpen(h) },
                        onLongClick = { overlay.openCardMenu(app, nav, scope, h.item) }) {
                        Box(Modifier.size(38.dp, 57.dp).clip(TvR.sm)) { TvImage(app.imageUrl(h.item.id, "Primary", 120)) }
                        Spacer(Modifier.width(TvSp.x12))
                        Column(Modifier.weight(1f)) {
                            RowText(h.item.name, t.body)
                            RowText(listOfNotNull(h.item.year?.toString(),
                                if (h.item.isSeries) "剧集" else "电影").joinToString(" · "), t.meta, .7f)
                        }
                    }
                }
                if (eps.isNotEmpty()) item("eps") {
                    Column {
                        RowTitle("分集", trailing = "${eps.size} 项")
                        Spacer(Modifier.height(TvSp.x8))
                        ProvideRowKeyline(TvSp.x12) {
                            LazyRow(contentPadding = PaddingValues(horizontal = TvSp.x12, vertical = TvSp.x6),
                                horizontalArrangement = Arrangement.spacedBy(TvSp.x12)) {
                                items(eps, key = { it.item.id }) { h ->
                                    ItemWide(h.item, "search.e.${h.item.id}",
                                        { item -> overlay.openCardMenu(app, nav, scope, item) }, onOpen = { onOpen(h) })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
