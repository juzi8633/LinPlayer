package xyz.linplayer.app.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.toRoute
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import xyz.linplayer.app.data.Block
import xyz.linplayer.app.data.Item
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.Page
import xyz.linplayer.app.data.arr
import xyz.linplayer.app.data.block
import xyz.linplayer.app.data.obj
import xyz.linplayer.app.data.str
import xyz.linplayer.app.ui.Route
import xyz.linplayer.app.ui.components.Dim3
import xyz.linplayer.app.ui.components.EmptyState
import xyz.linplayer.app.ui.components.ErrorState
import xyz.linplayer.app.ui.components.H2
import xyz.linplayer.app.ui.components.LpField
import xyz.linplayer.app.ui.components.LpScaffold
import xyz.linplayer.app.ui.components.MediaCard
import xyz.linplayer.app.ui.components.LpRow
import xyz.linplayer.app.ui.components.Skeleton
import xyz.linplayer.app.ui.components.pressable
import xyz.linplayer.app.ui.theme.LpIcons
import xyz.linplayer.app.ui.theme.Lp
import xyz.linplayer.app.ui.theme.R
import xyz.linplayer.app.ui.theme.Sp

/**
 * 搜索(U1.7)。三个入口共用这一页:首页右上角 / 聚合页顶部 / 库内搜索(带 `viewId`)。
 *
 * 三条开关规则【用户定】:
 * 1. **「包括集」默认关。** 搜「凡人」应该先看到那部剧,不是被 200 集分集淹掉。
 * 2. **「包括集」一拨就重搜;「聚合」一拨不重搜。** 聚合一次打 N 台,来回拨两下就是 2N 个请求;
 *    而「包括集」只多打一次当前服,**不重搜才是坏的**。
 * 3. **库内搜索与聚合互斥。** 有搜索范围时聚合开关**整个不出现**。
 */
@OptIn(FlowPreview::class)
@Composable
fun SearchPage(nav: NavController, entry: NavBackStackEntry) {
    val route = entry.toRoute<Route.Search>()
    val app = LocalApp.current
    val scope = rememberCoroutineScope()

    // 带 q 进来的直接预填,不用用户再打一遍
    var q by remember { mutableStateOf(route.q.orEmpty()) }
    var includeEpisodes by remember { mutableStateOf(false) }
    // 当前是插件数据源:没有 Emby 会话,只能聚合搜(数据源一起,D258)
    val onSource = app.activeSource.collectAsState().value != null
    var aggregate by remember { mutableStateOf(onSource) }
    /** 聚合结果,一个来源一行,谁先回来谁先显示(source.aggregateSearch 的 partial)。 */
    val aggRows = remember { androidx.compose.runtime.mutableStateListOf<kotlinx.serialization.json.JsonObject>() }
    var aggRun by remember { mutableStateOf(0) }
    var aggBusy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Block<List<Item>>?>(null) }
    var history by remember { mutableStateOf<List<String>>(emptyList()) }
    val focus = remember { FocusRequester() }

    // 预填了词就别抢焦点弹键盘 —— 用户是来看结果的,不是来打字的
    LaunchedEffect(Unit) { if (route.q.isNullOrBlank()) focus.requestFocus() }

    // 防抖 250ms 后打**服务端**搜索。**不许**「拉全部库 → 全量拉条目 → 本地过滤」
    LaunchedEffect(Unit) {
        snapshotFlow { Triple(q.trim(), includeEpisodes, aggregate) }
            .debounce(250)
            .collect { (text, eps, agg) ->
                if (text.length < 1) { result = null; return@collect }
                result = Block.Loading
                if (agg && route.viewId == null) {
                    // 聚合**按按钮才发**(D258):每停一下就把请求撒给所有来源太重
                    result = null
                } else {
                    /* ☠ 库内搜索的库 id 参数叫 **parent_id**;`types` 要的是**数组**。
                       传 view_id = 核心层读不到,搜的是全站(而「在这个库里搜」的入口
                       还在);types 传逗号串 = strList 认不出来,当成「全都要」——
                       「包括集」那个开关于是点了没反应。两边都不报错。 */
                    val a = buildMap<String, Any> {
                        put("query", text)
                        put("types", jsonArrayOf(
                            if (eps) listOf("Series", "Movie", "Episode") else listOf("Series", "Movie")))
                        route.viewId?.let { put("parent_id", it) }
                    }
                    result = when (val r = app.block("emby.search", args(*a.toList().toTypedArray()))) {
                        is Block.Ok -> Block.Ok(Page.from(r.value).items)
                        is Block.Fail -> r
                        else -> Block.Loading
                    }
                }
            }
    }

    LaunchedEffect(aggRun) {
        if (aggRun == 0) return@LaunchedEffect
        aggRows.clear(); aggBusy = true
        runCatching {
            app.call("source.aggregateSearch", args("query" to q.trim())) { part ->
                part.obj()?.let { app.bg.launch { aggRows.add(it) } }
            }
        }.onFailure { app.report(it) }
        aggBusy = false
    }

    LpScaffold(onBack = { nav.popBackStack() }, scrolled = true, title = " ") { pad ->
        Column(Modifier.fillMaxSize().imePadding()) {
            LpField(q, { q = it }, if (route.viewId != null) "在这个库里搜" else "搜片名、剧名或演员",
                Modifier.padding(horizontal = Sp.x16).focusRequester(focus))

            Row(Modifier.padding(horizontal = Sp.x16, vertical = Sp.x8),
                horizontalArrangement = Arrangement.spacedBy(Sp.x8)) {
                Toggle("包括集", includeEpisodes) { includeEpisodes = it }
                // 库内搜索与聚合互斥:有搜索范围时这个开关**整个不出现**
                if (route.viewId == null && !onSource) Toggle("聚合(含数据源)", aggregate) { aggregate = it }
                if (aggregate && route.viewId == null) xyz.linplayer.app.ui.components.LpButton("搜索", { if (q.isNotBlank()) aggRun++ })
            }

            /* 插件的搜索快捷动作(D242)。摆在搜索框下面、结果上面 —— 结果列表本身不动。
               一条都没有时整行不画,不在搜索框下面留一条空白。 */
            val actions = xyz.linplayer.app.ui.plugin.rememberSearchActions(q)
            if (actions.isNotEmpty()) Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Sp.x16, vertical = Sp.x2),
                horizontalArrangement = Arrangement.spacedBy(Sp.x8),
            ) {
                actions.forEach { a ->
                    xyz.linplayer.app.ui.components.LpButton(a.str("title") ?: "", {
                        scope.launch { xyz.linplayer.app.ui.plugin.runSearchAction(app, a) }
                    })
                }
            }

            val r = result
            when {
                aggregate && route.viewId == null -> LazyColumn(Modifier.fillMaxSize(), contentPadding = pad) {
                    if (aggRun == 0) item("hint") {
                        EmptyState("在所有来源里搜", "输好关键词点「搜索」:每个来源各占一行,谁先回来谁先显示。", LpIcons.search)
                    }
                    items(aggRows.size) { i ->
                        val g = aggRows[i]
                        val name = g.str("server_name") ?: g.str("server_id") ?: ""
                        val sid = g.str("server_id") ?: ""
                        when {
                            g.str("error") != null -> Dim3("$name 没搜成:${g.str("error")}", Modifier.padding(Sp.x16), maxLines = 2)
                            g.str("kind") == "plugin" -> Column(Modifier.padding(vertical = Sp.x8)) {
                                xyz.linplayer.app.ui.components.H2("$name · ${g["items"].arr().size} 条", Modifier.padding(horizontal = Sp.x16))
                                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(Sp.x16), horizontalArrangement = Arrangement.spacedBy(Sp.x10)) {
                                    g["items"].arr().mapNotNull { it.obj() }.forEach { it ->
                                        SourceCard(it, { nav.navigate(Route.SourceDetail(it.str("source") ?: sid, it.str("id") ?: "")) }, Modifier.width(108.dp))
                                    }
                                }
                            }
                            // 跨服结果**不给长按菜单**:收藏 / 标已看是对当前活跃服务器写的
                            else -> LpRow(name, Item.list(g["emby_items"]), { app.imageUrl(it.id, "Primary", 330) },
                                { nav.navigate(Route.Detail(it.id, it.type)) }, menu = null)
                        }
                    }
                    if (aggBusy) item("busy") { Dim3("还有来源在搜…", Modifier.padding(Sp.x16)) }
                    else if (aggRun > 0 && aggRows.isEmpty()) item("none") { EmptyState("「${q.trim()}」没搜到东西", "只包括打开了「允许聚合」的来源。") }
                }

                r == null -> if (history.isEmpty()) EmptyState(
                    "搜片名、剧名或演员", "会搜当前服务器;打开「聚合跨服」可以一次搜所有已登录的服务器。",
                    LpIcons.search,
                ) else HistoryList(history) { q = it }

                r is Block.Loading -> Column(Modifier.padding(Sp.x16)) {
                    repeat(3) {
                        Skeleton(Modifier.fillMaxWidth().height(72.dp))
                        Spacer(Modifier.height(Sp.x10))
                    }
                }

                r is Block.Fail -> ErrorState(r.message)

                else -> {
                    val items = (r as Block.Ok).value
                    if (items.isEmpty()) EmptyState(
                        "「${q.trim()}」没搜到东西",
                        "检查一下有没有打错字,或者换个关键词 —— 有些片源用的是英文原名。",
                    ) else {
                        // 分集**单独一栏横版**;剧和影走网格
                        val eps = items.filter { it.isEpisode }
                        val rest = items.filterNot { it.isEpisode }
                        LazyColumn(Modifier.fillMaxSize(), contentPadding = pad) {
                            if (rest.isNotEmpty()) item("grid") {
                                LazyVerticalGridInline(rest) { picked ->
                                    // 历史只在**用户真的点开了某个结果**时才记 ——
                                    // 跟着防抖记会把「阿」「阿凡」「阿凡达」全记进去
                                    val t = q.trim()
                                    if (t.isNotEmpty()) history = (listOf(t) + history).distinct().take(8)
                                    nav.navigate(Route.Detail(picked.id, picked.type))
                                }
                            }
                            if (eps.isNotEmpty()) item("eps") {
                                LpRow("分集", eps, { app.imageUrl(it.id, "Primary", 220) },
                                    { nav.navigate(Route.Detail(it.id, "Episode")) }, thumb = true,
                                    menu = { cardActions(app, scope, it) })
                            }
                        }
                    }
                }
            }
        }
    }
}

private inline fun remember0(list: List<String>, set: (List<String>) -> Unit) = Unit

@Composable
private fun LazyVerticalGridInline(items: List<Item>, onOpen: (Item) -> Unit) {
    val app = LocalApp.current
    val rows = (items.size + 2) / 3
    Column(Modifier.fillMaxWidth().padding(horizontal = Sp.x16),
        verticalArrangement = Arrangement.spacedBy(Sp.x16)) {
        repeat(rows) { r ->
            Row(horizontalArrangement = Arrangement.spacedBy(Sp.x10)) {
                (0 until 3).forEach { cIdx ->
                    val i = r * 3 + cIdx
                    if (i < items.size) {
                        val it2 = items[i]
                        MediaCard(it2, app.imageUrl(it2.id, "Primary", 330), { onOpen(it2) },
                            Modifier.weight(1f), menu = null)
                    } else Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun HistoryList(history: List<String>, onPick: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(Sp.x16)) {
        H2("最近搜过")
        Spacer(Modifier.height(Sp.x8))
        history.forEach {
            Text(it, Modifier.fillMaxWidth().pressable({ onPick(it) }).padding(vertical = Sp.x12),
                color = Lp.colors.fg2, fontSize = 14.sp)
        }
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val c = Lp.colors
    Text(
        label,
        Modifier.clip(RoundedCornerShape(R.pill))
            .background(if (on) c.accDim else c.s2)
            .pressable({ onChange(!on) })
            .padding(horizontal = Sp.x12, vertical = Sp.x8),
        color = if (on) c.acc else c.fg2, fontSize = 12.sp,
    )
}
