package xyz.linplayer.app.tv

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.Block
import xyz.linplayer.app.data.Item
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.arr
import xyz.linplayer.app.data.block
import xyz.linplayer.app.data.bool
import xyz.linplayer.app.data.dbl
import xyz.linplayer.app.data.keepState
import xyz.linplayer.app.data.long
import xyz.linplayer.app.data.obj
import xyz.linplayer.app.data.str
import xyz.linplayer.app.tv.kit.CardPoster
import xyz.linplayer.app.tv.kit.PanelItem
import xyz.linplayer.app.tv.kit.ScopeChips
import xyz.linplayer.app.tv.kit.Skel
import xyz.linplayer.app.tv.kit.TvC
import xyz.linplayer.app.tv.kit.TvDim
import xyz.linplayer.app.tv.kit.TvSp
import xyz.linplayer.app.tv.kit.TvText
import xyz.linplayer.app.tv.kit.TvW
import xyz.linplayer.app.tv.kit.bleed
import xyz.linplayer.app.tv.kit.contentArea
import xyz.linplayer.app.tv.kit.tvType
import xyz.linplayer.app.ui.pages.args
import xyz.linplayer.app.ui.pages.j
import xyz.linplayer.app.ui.pages.jsonArrayOf
import xyz.linplayer.app.ui.pages.map

private data class Entry(val title: String, val sub: String, val image: String?, val rank: Int,
                        /** `sync.calendarLibrary` 的命中:非空 = 这部剧已在当前 Emby 库里(D366)。 */
                        val hit: JsonObject? = null)

private val Weekdays = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

/**
 * 发现 = 排行榜 + 放送表(UI_TV.md §7.9)【用户定 2026-09-14】。
 * ★ **切 tab / 切分类时焦点留在左栏**,右栏内容淡入替换、**不抢焦点**。
 * ★ 榜单 / 放送条目**不是本地库的条目**:打开 = 在当前服务器按片名搜,命中进详情。
 */
@Composable
fun DiscoverPage() {
    val app = LocalApp.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val t = tvType
    var tab by keepState("tv.discover.tab") { 0 }
    var cats by keepState<Block<List<Pair<String, String>>>>("tv.rank.cats") { Block.Loading }
    var cat by keepState<Int>("tv.rank.cat") { 0 }
    var calSrc by keepState("tv.cal.src") { 0 }
    val today = java.time.LocalDate.now().dayOfWeek.value
    var day by keepState("tv.cal.day") { today }

    LaunchedEffect(Unit) {
        if (cats !is Block.Ok) cats = app.block("emby.rankingCategories").map { e ->
            e.arr().mapNotNull { it.obj() }.mapNotNull { o -> o.str("id")?.let { it to (o.str("label") ?: "榜单") } }
        }
    }

    fun open(e: Entry) {
        // 放送表已经对过库(D366):有命中就直奔那一条,不必再按片名搜一遍
        e.hit?.let { h ->
            if (h.bool("playable")) nav.push(TvRoute.Player(h.str("item_id") ?: "", e.title))
            else nav.push(TvRoute.Detail(h.str("series_id") ?: h.str("item_id") ?: "", "Series"))
            return
        }
        scope.launch {
            val hit = Item.list(runCatching {
                app.call("emby.search", args("query" to e.title, "types" to jsonArrayOf(listOf("Movie", "Series")), "limit" to 5))
            }.getOrNull()).firstOrNull()
            if (hit != null) openItem(nav, hit) else app.toast("「${e.title}」不在当前服务器的媒体库里")
        }
    }

    Row(Modifier.contentArea()) {
        Column(Modifier.width(180.dp)) {
            TvText("发现", t.headline, TvC.fg, weight = TvW.semi)
            Spacer(Modifier.height(TvSp.x8))
            ScopeChips(listOf("排行榜", "放送表"), tab, itemModifier = { i -> Modifier.memo("discover.tab.$i") }, onSelect = { tab = it })
            Spacer(Modifier.height(TvSp.x8))
            if (tab == 0) when (val c = cats) {
                is Block.Loading -> repeat(4) { Skel(Modifier.width(160.dp).height(32.dp)); Spacer(Modifier.height(TvSp.x6)) }
                is Block.Fail -> TvText("榜单分类没加载出来:${c.message}", t.meta, TvC.fg3, maxLines = 3)
                is Block.Ok -> c.value.forEachIndexed { i, (_, label) ->
                    PanelItem(label, selected = i == cat, check = false, modifier = Modifier.memo("rank.cat.$i", initial = i == cat),
                        onClick = { cat = i })
                }
            } else listOf("Bangumi 番剧", "Trakt 剧集").forEachIndexed { i, label ->
                PanelItem(label, selected = i == calSrc, check = false, modifier = Modifier.memo("cal.src.$i", initial = i == calSrc),
                    onClick = { calSrc = i })
            }
        }
        Spacer(Modifier.width(TvSp.x24))
        Box(Modifier.weight(1f)) {
            AnimatedContent(targetState = Triple(tab, cat, calSrc), transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
                label = "discover") { (tb, ct, src) ->
                if (tb == 0) RankingPane(app, cats.valueOrNull?.getOrNull(ct), cats) { open(it) }
                else CalendarPane(app, src, day, today, { day = it }) { open(it) }
            }
        }
    }
}

@Composable
private fun RankingPane(app: AppState, cat: Pair<String, String>?, cats: Block<List<Pair<String, String>>>, open: (Entry) -> Unit) {
    val t = tvType
    if (cats is Block.Ok && cats.value.isEmpty()) {
        // ☠ **不存在「排行榜开关」**,别写「去设置里打开」:用户照着找只会翻个空
        TvText("这个安装包没有内置榜单凭据,所以没有可用的榜单。", t.body, TvC.fg2, maxLines = 2)
        return
    }
    val c = cat ?: return
    var block by remember(c.first) { mutableStateOf<Block<List<Entry>>>(Block.Loading) }
    var source by remember(c.first) { mutableStateOf("") }
    LaunchedEffect(c.first) {
        block = app.block("emby.rankingFetch", args("category_id" to c.first)).map { e ->
            val rows = e.arr().mapNotNull { it.obj() }
            source = when (rows.firstOrNull().str("source")) { "tmdb" -> "TMDB"; "dandan" -> "弹弹play"; else -> "" }
            rows.mapIndexed { i, o ->
                // ☠ 条目**不是 Emby Item**;rating 为 null = 没人评过,不是 0 分 —— 回落副标题,不画「★ 0.0」
                Entry(o.str("title") ?: "", o.dbl("rating")?.let { "★ %.1f".format(it) } ?: o.str("subtitle").orEmpty(),
                    o.str("image_url"), (o.long("rank") ?: (i + 1).toLong()).toInt())
            }
        }
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TvText(c.second, t.title, TvC.fg, weight = TvW.semi)
            if (source.isNotBlank()) { Spacer(Modifier.width(TvSp.x8)); TvText("$source · 每日更新", t.meta, TvC.fg3) }
        }
        Spacer(Modifier.height(TvSp.x12))
        EntryGrid(block, rank = true, keyPrefix = "rank.${c.first}", open = open)
    }
}

/** 周归组:Bangumi 给 weekday(1=周一);Trakt 给 air_date,按本地时区算星期。 */
internal fun weekdayOf(o: JsonObject): Int? = o.long("weekday")?.toInt()?.takeIf { it in 1..7 }
    ?: (o.str("broadcast_at") ?: o.str("air_date"))?.let { iso ->
        runCatching { java.time.Instant.parse(iso).atZone(java.time.ZoneId.systemDefault()).dayOfWeek.value }.getOrNull()
            ?: runCatching { java.time.LocalDate.parse(iso.take(10)).dayOfWeek.value }.getOrNull()
    }

@Composable
private fun CalendarPane(app: AppState, src: Int, day: Int, today: Int, onDay: (Int) -> Unit, open: (Entry) -> Unit) {
    val t = tvType
    var all by remember(src) { mutableStateOf<Block<List<JsonObject>>>(Block.Loading) }
    var login by remember(src) { mutableStateOf<Boolean?>(null) }
    // 索引 → 媒体库命中(D366)。放送表先画出来再去对库:对得慢不该拖住整页
    var hits by remember(src) { mutableStateOf<Map<String, JsonObject>>(emptyMap()) }
    LaunchedEffect(src) {
        launch {
            login = runCatching { app.call(if (src == 0) "sync.bangumiAccount" else "sync.traktAccount") }.getOrNull()
                .let { it != null && it !is kotlinx.serialization.json.JsonNull && it.obj()?.isNotEmpty() == true }
        }
        all = app.block(if (src == 0) "sync.bangumiCalendar" else "sync.traktCalendar", args("only_mine" to false))
            .map { e -> e.arr().mapNotNull { it.obj() } }
        val rows = (all as? Block.Ok)?.value ?: return@LaunchedEffect
        val q = rows.mapIndexed { i, o ->
            buildMap<String, Any> {
                put("key", i.toString()); put("title", o.str("title") ?: "")
                o.long("tmdb_id")?.let { put("tmdb_id", it) }
                o.long("season")?.let { put("season", it) }
                o.long("episode")?.let { put("episode", it) }
            }
        }
        hits = runCatching { app.call("sync.calendarLibrary", j("entries" to q)).obj() }.getOrNull()
            ?.mapNotNull { (k, v) -> v.obj()?.let { k to it } }?.toMap().orEmpty()
    }
    val name = if (src == 0) "Bangumi" else "Trakt"
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TvText("放送表", t.title, TvC.fg, weight = TvW.semi)
            Spacer(Modifier.width(TvSp.x8))
            // 账号状态没问到时**不许画成「未登录」**,否则每次进页闪一下
            login?.let { TvText(if (it) "$name · 已登录" else if (src == 0) "$name · 未登录(显示通用放送表)" else "$name · 未连接", t.meta, TvC.fg3) }
        }
        Spacer(Modifier.height(TvSp.x8))
        // 七个日期 chip + 当天网格【用户定 2026-09-14,推翻旧稿七列】
        ScopeChips(Weekdays.mapIndexed { i, w -> if (i + 1 == today) "今天" else w }, day - 1,
            itemModifier = { i -> Modifier.memo("cal.day.$i") }, onSelect = { onDay(it + 1) })
        Spacer(Modifier.height(TvSp.x12))
        val block = all.map { list ->
            // 带着原始下标过滤:calendarLibrary 的 key 是全表的下标,按天筛完就对不上了
            list.withIndex().filter { weekdayOf(it.value) == day }.map { (i, o) ->
                // 取不到时刻就不写时刻,别编播出时间
                val hhmm = o.str("broadcast_at")?.let { iso ->
                    runCatching { java.time.Instant.parse(iso).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")) }.getOrNull()
                }
                val h = hits[i.toString()]
                val mark = h?.let { if (it.bool("playable")) "可播" else "已入库" }
                Entry(o.str("title") ?: "", listOfNotNull(o.str("subtitle"), hhmm, mark).joinToString(" · "), o.str("image_url"), 0, h)
            }
        }
        if (block is Block.Fail && src == 1) TvText("Trakt 没有连接 · 设置 → 同步里连接之后才有放送表", t.body, TvC.fg2)
        else EntryGrid(block, rank = false, keyPrefix = "cal.$src.$day", open = open)
    }
}

@Composable
private fun EntryGrid(block: Block<List<Entry>>, rank: Boolean, keyPrefix: String, open: (Entry) -> Unit) {
    val t = tvType
    when (block) {
        is Block.Loading -> Row(horizontalArrangement = Arrangement.spacedBy(15.dp)) { repeat(5) { Skel(Modifier.width(TvDim.posterW).height(TvDim.posterH)) } }
        is Block.Fail -> TvText("没加载出来:${block.message}", t.meta, TvC.fg3, maxLines = 3)
        is Block.Ok -> if (block.value.isEmpty()) TvText("这里暂时没有内容", t.body, TvC.fg3) else LazyVerticalGrid(
            GridCells.Fixed(5), horizontalArrangement = Arrangement.spacedBy(15.dp),
            verticalArrangement = Arrangement.spacedBy(TvSp.x16), contentPadding = PaddingValues(12.dp), modifier = Modifier.bleed(12.dp),
        ) {
            itemsIndexed(block.value, key = { i, e -> "$i.${e.title}" }) { i, e ->
                CardPoster(cover = { TvImage(e.image) }, title = e.title, sub = e.sub, rank = if (rank) e.rank else 0,
                    modifier = Modifier.memo("$keyPrefix.$i"), onClick = { open(e) })
            }
        }
    }
}
