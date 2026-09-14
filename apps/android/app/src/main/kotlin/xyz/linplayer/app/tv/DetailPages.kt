package xyz.linplayer.app.tv

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import xyz.linplayer.app.data.Account
import xyz.linplayer.app.data.AppState
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
import xyz.linplayer.app.data.strList
import xyz.linplayer.app.tv.kit.Badge
import xyz.linplayer.app.tv.kit.CardEpisode
import xyz.linplayer.app.tv.kit.FullState
import xyz.linplayer.app.tv.kit.PanelGroup
import xyz.linplayer.app.tv.kit.PanelItem
import xyz.linplayer.app.tv.kit.Probe
import xyz.linplayer.app.tv.kit.ProvideColumnSpec
import xyz.linplayer.app.tv.kit.ProvideRowKeyline
import xyz.linplayer.app.tv.kit.RowTitle
import xyz.linplayer.app.tv.kit.ScopeChips
import xyz.linplayer.app.tv.kit.Skel
import xyz.linplayer.app.tv.kit.TvButton
import xyz.linplayer.app.tv.kit.TvC
import xyz.linplayer.app.tv.kit.TvDim
import xyz.linplayer.app.tv.kit.TvR
import xyz.linplayer.app.tv.kit.TvSp
import xyz.linplayer.app.tv.kit.TvText
import xyz.linplayer.app.tv.kit.TvW
import xyz.linplayer.app.tv.kit.ValueChip
import xyz.linplayer.app.tv.kit.VersionCard
import xyz.linplayer.app.tv.kit.tvType
import xyz.linplayer.app.ui.pages.Version
import xyz.linplayer.app.ui.pages.args
import xyz.linplayer.app.ui.pages.defaultVersion
import xyz.linplayer.app.ui.pages.fmtTime
import xyz.linplayer.app.ui.pages.langCn
import xyz.linplayer.app.ui.pages.map
import xyz.linplayer.app.ui.theme.LpIcons

// ---------------------------------------------------------------- 共用块

internal data class Season(val id: String, val name: String, val index: Long?, val childCount: Long, val unplayed: Long)

internal fun seasonsOf(e: JsonElement?): List<Season> = e.arr().mapNotNull {
    val o = it.obj() ?: return@mapNotNull null
    Season(o.str("id") ?: return@mapNotNull null, o.str("name") ?: "", o.long("index_no"),
        o.long("child_count") ?: 0, o.long("unplayed") ?: 0)
}

/** 背景:Backdrop 那张,没有才退回封面;**均匀** scrim,不渐变、不模糊(`Modifier.blur` 要 API 31)。 */
@Composable
fun BoxScope.DetailBackdrop(id: String, hasBackdrop: Boolean) {
    val app = LocalApp.current
    TvImage(app.imageUrl(id, if (hasBackdrop) "Backdrop" else "Primary", 1080))
    Box(Modifier.fillMaxSize().background(TvC.scrim))
}

/**
 * 族 F 展示块(§4.1):聚焦只画 1dp `fg3` 框、外扩 8dp,**不放大**。
 * ★ 存在的理由:滚动是焦点驱动的,最后一个可聚焦项下面的展示区块永远滚不到,等于不存在。
 */
@Composable
fun FBlock(modifier: Modifier = Modifier, onClick: () -> Unit = {}, content: @Composable () -> Unit) {
    Surface(
        onClick = onClick, modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(TvR.md),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent, contentColor = TvC.fg,
            focusedContainerColor = Color.Transparent, focusedContentColor = TvC.fg, pressedContainerColor = Color.Transparent,
        ),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(1.dp, TvC.fg3), inset = 8.dp, shape = TvR.md)),
    ) { content() }
}

@Composable
fun OverviewBlock(text: String, lines: Int, overlay: Overlay, key: String) {
    if (text.isBlank()) return
    FBlock(Modifier.widthIn(max = 560.dp).memo(key), onClick = {
        overlay.open {
            TvSidePanel("简介", { overlay.close() }) {
                FBlock(Modifier.padding(horizontal = TvSp.x16)) { TvText(text, tvType.body, TvC.fg2, maxLines = 40) }
            }
        }
    }) { TvText(text, tvType.body, TvC.fg2, maxLines = lines) }
}

internal data class Person(val id: String, val name: String)

/** 演职人员:**整块一个族 F 焦点** —— 没有人物页,头像能聚焦 = 按下去没反应。空就不画。 */
@Composable
internal fun PeopleBlock(people: List<Person>, key: String) {
    if (people.isEmpty()) return
    val app = LocalApp.current
    Spacer(Modifier.height(TvSp.x20))
    RowTitle("演职人员")
    Spacer(Modifier.height(TvSp.x8))
    FBlock(Modifier.memo(key)) {
        Row(horizontalArrangement = Arrangement.spacedBy(TvSp.x20)) {
            people.take(9).forEach { p ->
                Column(Modifier.width(72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(56.dp).clip(TvR.pill)) { TvImage(app.imageUrl(p.id, "Primary", 120)) }
                    Spacer(Modifier.height(TvSp.x4))
                    TvText(p.name, tvType.meta, TvC.fg2)
                }
            }
        }
    }
}

/** 媒体信息(族 F,4 列键值)。缺的整项不画,SDR 不画。 */
@Composable
internal fun MediaInfoBlock(v: Version, key: String) {
    val video = v.of("Video").firstOrNull()
    val rows = listOfNotNull(
        video?.let { s -> s.width?.let { w -> s.height?.let { h -> "分辨率" to "$w×$h" } } },
        video?.codec?.uppercase()?.takeIf { it.isNotBlank() }?.let { "编码" to it },
        xyz.linplayer.app.ui.pages.fmtRate(v.bitrate ?: video?.bitrate)?.let { "码率" to it },
        xyz.linplayer.app.ui.pages.fmtSize(v.sizeBytes)?.let { "体积" to it },
        video?.fps?.takeIf { it > 0 }?.let { "帧率" to "%.3f".format(it) },
        video?.range?.takeIf { !it.equals("SDR", true) }?.let { "动态范围" to it },
        v.container?.uppercase()?.let { "封装" to it },
    )
    if (rows.isEmpty()) return
    val t = tvType
    Spacer(Modifier.height(TvSp.x20))
    RowTitle("媒体信息")
    Spacer(Modifier.height(TvSp.x8))
    FBlock(Modifier.memo(key)) {
        Column(Modifier.width(640.dp)) {
            rows.chunked(4).forEach { line ->
                Row {
                    line.forEach { (k, value) ->
                        Column(Modifier.width(160.dp).padding(bottom = TvSp.x6)) {
                            TvText(k, t.meta, TvC.fg3)
                            TvText(value, t.body, TvC.fg)
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 版本聚合(§7.6,电影也用)

/** 一张版本卡。`version == null` = 还在查 / 没有 / 规格未知。 */
internal data class VCard(
    val serverId: String, val serverName: String, val itemId: String?, val version: Version?,
    val probe: Probe, val current: Boolean, val maybe: Boolean = false,
) {
    val key get() = "$serverId|${version?.id ?: itemId ?: "-"}"
}

private fun VCard.height(): Long = version?.of("Video")?.firstOrNull()?.height ?: 0
private fun VCard.rate(): Long = version?.bitrate ?: version?.of("Video")?.firstOrNull()?.bitrate ?: 0

/** 排序:当前服务器 → 分辨率降序 → 码率降序;查询中 / 规格未知在有规格的后面,确认没有的垫底。 */
internal fun sortCards(cards: List<VCard>): List<VCard> = cards.sortedWith(
    compareBy<VCard>({ when (it.probe) { Probe.OK -> 0; Probe.PROBING, Probe.OPAQUE -> 1; Probe.ABSENT -> 2 } },
        { if (it.current) 0 else 1 }, { -it.height() }, { -it.rate() }),
)

private fun norm(s: String?) = s.orEmpty().lowercase().filter { it.isLetterOrDigit() }

/**
 * 当前服务器的版本立即出,**其它服务器回来一个填一个**(等齐再画 = 进页白屏两秒)。
 * 别台服务器靠 `emby.itemMedia {server_id}` 拿真规格(sessionFrom 里 server_id 压过当前会话);
 * 拿不到就如实写「规格未知」,**不许编分辨率**【用户定】。
 */
internal suspend fun loadVersionCards(
    app: AppState, scope: CoroutineScope, itemId: String, query: String?, match: (Item) -> Boolean,
    matchExact: (Item) -> Boolean, onCards: (List<VCard>) -> Unit,
) {
    val accounts = Account.list(runCatching { app.call("account.listAccounts") }.getOrNull())
    val current = accounts.firstOrNull { it.isActive }
    val others = accounts.filter { !it.isActive && (it.kind ?: "emby") == "emby" }
    var cards = listOf<VCard>()
    fun emit() = onCards(sortCards(cards))
    val mine = xyz.linplayer.app.ui.pages.Version.list(app.block("emby.itemMedia", args("item_id" to itemId)).valueOrNull)
    cards = mine.map { VCard(current?.id ?: "", current?.name ?: "", itemId, it, Probe.OK, current = true) }
    cards = cards + others.map { VCard(it.id, it.name, null, null, Probe.PROBING, false) }
    emit()
    if (others.isEmpty() || query.isNullOrBlank()) return
    val groups = runCatching {
        app.call("emby.aggregateSearch", args("query" to query, "include_episodes" to true))
    }.getOrNull().arr().mapNotNull { it.obj() }
    others.forEach { acc ->
        scope.launch {
            val g = groups.firstOrNull { it.str("server_id") == acc.id }
            val hit = Item.list(g?.get("items")).firstOrNull(match)
            val replaced: List<VCard> = if (hit == null) listOf(VCard(acc.id, acc.name, null, null, Probe.ABSENT, false))
            else {
                val maybe = !matchExact(hit)
                val vs = Version.list(runCatching {
                    app.call("emby.itemMedia", args("item_id" to hit.id, "server_id" to acc.id))
                }.getOrNull())
                if (vs.isEmpty()) listOf(VCard(acc.id, acc.name, hit.id, null, Probe.OPAQUE, false, maybe))
                else vs.map { VCard(acc.id, acc.name, hit.id, it, Probe.OK, false, maybe) }
            }
            cards = cards.filterNot { it.serverId == acc.id } + replaced
            emit()
        }
    }
}

/** 版本卡一行。卡片 ≤ 1 张时整行不画。 */
@Composable
internal fun VersionRow(
    cards: List<VCard>, shownKey: String?, pickedKey: String?, onPick: (VCard) -> Unit,
    down: () -> androidx.compose.ui.focus.FocusRequester?,
    above: androidx.compose.ui.unit.Dp = TvSp.x16, gap: androidx.compose.ui.unit.Dp = TvSp.x6,
) {
    if (cards.size <= 1) return
    val servers = cards.map { it.serverId }.distinct().size
    Spacer(Modifier.height(above))
    RowTitle("版本", trailing = if (servers > 1) "来自 $servers 台服务器" else null)
    Spacer(Modifier.height(gap))
    ProvideRowKeyline(TvDim.safeH) {
        // 上下不留内边距:LazyRow 只裁主轴,交叉轴留有 15dp,选中框画得出来
        LazyRow(
            contentPadding = PaddingValues(end = TvDim.safeH),
            horizontalArrangement = Arrangement.spacedBy(TvSp.x12),
        ) {
            items(cards, key = { it.key }) { c ->
                VersionCard(
                    server = c.serverName, res = c.version?.let(::resLabel) ?: "", spec = c.version?.let(::specLabel) ?: "",
                    tracks = c.version?.let(::tracksLabel) ?: "", current = c.current,
                    selected = c.key == (pickedKey ?: shownKey), probe = c.probe, maybe = c.maybe,
                    // 版本行按 ↓ **显式**落到集数栏的当前集:旧实现靠几何碰运气,焦点被吸到右上角小按钮上
                    modifier = Modifier.memo("ver.${c.key}").focusProperties { down()?.let { d -> this.down = d } },
                    onClick = { onPick(c) },
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 剧 / 电影 / 合集详情(§7.4 / §7.5)

@Composable
fun DetailPage(r: TvRoute.Detail) {
    val app = LocalApp.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val overlay = rememberOverlay()
    val t = tvType
    val server = app.session.collectAsStateWithLifecycle().value?.server
    val ck = "tv.detail.${r.itemId}.$server"

    var detail by keepState<Block<JsonObject>>("$ck.d") { Block.Loading }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(r.itemId, reload) {
        detail = app.block("emby.itemDetail", args("item_id" to r.itemId)).map { it.obj() ?: JsonObject(emptyMap()) }
    }
    val d = detail.valueOrNull
    val type = d.str("type_") ?: r.type

    Box(Modifier.fillMaxSize().background(TvC.bg)) {
        DetailBackdrop(r.itemId, d?.bool("has_backdrop") ?: true)
        when (val b = detail) {
            is Block.Fail -> FullState(LpIcons.info, "没加载出来", detail = b.message, buttons = listOf("重试", "返回"),
                tone = TvC.bad, onButton = { if (it == 0) reload++ else nav.pop() })
            is Block.Loading -> Column(Modifier.padding(horizontal = TvDim.safeH, vertical = TvDim.safeV)) {
                Skel(Modifier.width(320.dp).height(34.dp)); Spacer(Modifier.height(TvSp.x12))
                Skel(Modifier.width(420.dp).height(14.dp)); Spacer(Modifier.height(TvSp.x8))
                Skel(Modifier.width(560.dp).height(60.dp))
            }
            is Block.Ok -> ProvideColumnSpec(above = 64.dp, below = TvDim.safeV + TvDim.captionH) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = TvDim.safeV, bottom = TvDim.safeV)) {
                    when (type) {
                        "Series", "Season" -> SeriesBody(r.itemId, b.value, overlay, scope)
                        "BoxSet" -> BoxSetBody(r.itemId, b.value, overlay, scope)
                        else -> MovieBody(r.itemId, b.value, overlay, scope)
                    }
                }
            }
        }
        OverlayHost(overlay)
    }
}

@Composable
private fun DetailHead(d: JsonObject, meta: String, tags: List<String> = emptyList(), overlay: Overlay, lines: Int) {
    val t = tvType
    TvText(d.str("name") ?: "", t.display, TvC.fg, weight = TvW.bold, maxLines = 2)
    Spacer(Modifier.height(TvSp.x4))
    Row(verticalAlignment = Alignment.CenterVertically) {
        d.dbl("rating")?.takeIf { it > 0 }?.let {
            TvText("★ %.1f".format(it), t.meta, TvC.acc, weight = TvW.semi)
            Spacer(Modifier.width(TvSp.x8))
        }
        TvText(meta, t.meta, TvC.fg2)
        tags.forEach { Spacer(Modifier.width(TvSp.x6)); Badge(it, TvC.surface3, TvC.fg2) }
    }
    Spacer(Modifier.height(TvSp.x8))
    OverviewBlock(d.str("overview").orEmpty(), lines, overlay, "detail.overview")
}

/** 收藏开关。**乐观更新,失败回滚**(§6.1:一次动作失败 → Toast 原因 + UI 回滚)。 */
@Composable
internal fun FavButton(app: AppState, scope: CoroutineScope, itemId: String, initial: Boolean, key: String) {
    var fav by remember(itemId, initial) { mutableStateOf(initial) }
    TvButton(if (fav) "已收藏" else "收藏", if (fav) LpIcons.heartOn else LpIcons.heart, modifier = Modifier.memo(key), onClick = {
        val want = !fav
        fav = want
        scope.launch {
            runCatching { app.call("emby.setFavorite", args("item_id" to itemId, "fav" to want)) }
                .onFailure { fav = !want; app.report(it) }
        }
    })
}

/** 「更多」里的屏蔽:二级内容说明后果再确认,成功后离开这一页。 */
internal fun Overlay.openMore(
    title: String, app: AppState, nav: TvNav, scope: CoroutineScope, blockId: String, blockName: String,
    items: @Composable (close: () -> Unit) -> Unit,
) {
    open {
        var level by remember { mutableIntStateOf(0) }
        TvSidePanel(title, { close() }, back = if (level == 1) ({ level = 0 }) else null) {
            if (level == 1) {
                PanelItem("屏蔽「$blockName」", sub = "屏蔽后它不会再出现在首页、媒体库和搜索里。", enabled = false)
                PanelItem("确认屏蔽", danger = true, focused = true, onClick = {
                    scope.launch {
                        runCatching { app.call("emby.setBlocked", args("id" to blockId, "name" to blockName, "blocked" to true)) }
                            .onSuccess { close(); app.toast("已屏蔽「$blockName」", ToastKind.Ok); nav.pop() }
                            .onFailure { app.report(it) }
                    }
                })
                return@TvSidePanel
            }
            items { close() }
            PanelGroup("危险")
            PanelItem("屏蔽", danger = true, chevron = true, onClick = { level = 1 })
        }
    }
}

@Composable
private fun SeriesBody(id: String, d: JsonObject, overlay: Overlay, scope: CoroutineScope) {
    val app = LocalApp.current
    val nav = LocalNav.current
    val ck = "tv.series.$id.${app.session.value?.server}"
    var seasons by keepState<List<Season>?>("$ck.seasons") { null }
    var season by keepState<Season?>("$ck.season") { null }
    var episodes by keepState<Block<List<Item>>>("$ck.eps") { Block.Loading }
    var target by keepState<Item?>("$ck.target") { null }
    var similar by keepState<List<Item>>("$ck.sim") { emptyList() }
    var dl by remember { mutableStateOf(false) }

    suspend fun loadEpisodes(parent: String): List<Item> {
        val b = app.block("emby.seasonEpisodes", args("parent_id" to parent, "limit" to 200)).map { Item.list(it) }
        episodes = b
        return b.valueOrNull.orEmpty()
    }
    LaunchedEffect(id) {
        launch { similar = Item.list(app.block("emby.similarItems", args("item_id" to id, "limit" to 20)).valueOrNull) }
        launch { dl = canDownload(app) }
        if (seasons != null) return@LaunchedEffect
        val ss = seasonsOf(app.block("emby.seriesSeasons", args("series_id" to id)).valueOrNull)
        val resumed = Item.list(app.block("emby.listResume", args("limit" to 50)).valueOrNull).firstOrNull { it.seriesId == id }
        // 默认选中主按钮**目标集所在的季**【用户定 2026-09-14】:看了一半的 → 第一个没看完的季 → 第一季
        val s = ss.firstOrNull { resumed != null && it.index == resumed.seasonNo }
            ?: ss.firstOrNull { it.unplayed > 0 } ?: ss.firstOrNull()
        seasons = ss
        season = s
        // ☠ 季列表可能为空(有些剧集直接挂剧下)→ 拿 series id 当 parent;不回落 =「点进去一集都没有」且不报错
        val eps = loadEpisodes(s?.id ?: id)
        target = eps.firstOrNull { it.id == resumed?.id } ?: eps.firstOrNull { it.resumeSecs > 0 }
            ?: eps.firstOrNull { !it.played } ?: eps.firstOrNull()
    }

    val tg = target
    val seasonCount = seasons?.size ?: 0
    val epCount = seasons?.sumOf { it.childCount }?.takeIf { it > 0 }
    val meta = listOfNotNull(d.long("year")?.toString(), seasonCount.takeIf { it > 0 }?.let { "$it 季" },
        epCount?.let { "$it 集" }, d.strList("genres").take(3).joinToString(" / ").takeIf { it.isNotEmpty() }).joinToString(" · ")
    val epTag = tg?.let { "S${it.seasonNo ?: 1}E${it.episodeNo ?: 1}" }

    Column(Modifier.padding(horizontal = TvDim.safeH)) {
        DetailHead(d, meta, overlay = overlay, lines = 3)
        Spacer(Modifier.height(TvSp.x16))
        Row(horizontalArrangement = Arrangement.spacedBy(TvSp.x12)) {
            // 主按钮上写明是哪一集:那是用户按播放时唯一想知道的事
            TvButton(when {
                tg == null -> "播放"
                tg.resumeSecs > 0 -> "继续 $epTag"
                else -> "播放 $epTag"
            }, LpIcons.play, primary = true, enabled = tg != null, modifier = Modifier.memo("detail.play", initial = true), onClick = {
                tg?.let { nav.push(TvRoute.Player(it.id, cardTitleOf(it))) }
            })
            // 「继续」和「从头」必须并列显式给出:PC 上藏在右键里,TV 没有右键
            if (tg != null && tg.resumeSecs > 0) TvButton("从头播放", LpIcons.refresh, modifier = Modifier.memo("detail.restart"),
                onClick = { nav.push(TvRoute.Player(tg.id, cardTitleOf(tg), fromStart = true)) })
            FavButton(app, scope, id, d.bool("is_favorite"), "detail.fav")
            TvButton("更多", LpIcons.more, modifier = Modifier.memo("detail.more"), onClick = {
                val played = d.bool("played")
                overlay.openMore(d.str("name") ?: "", app, nav, scope, id, d.str("name") ?: "") { close ->
                    PanelItem(if (played) "标记整部未看" else "标记整部已看", focused = true, onClick = {
                        scope.launch {
                            runCatching { app.call("emby.setPlayed", args("item_id" to id, "played" to !played)) }
                                .onSuccess { close(); app.toast(if (played) "已标记未看" else "已标记已看", ToastKind.Ok) }
                                .onFailure { app.report(it) }
                        }
                    })
                    val s = season
                    if (dl) PanelItem("下载本季", sub = s?.name, onClick = {
                        scope.launch {
                            runCatching {
                                app.call("download.enqueueSeason", args(*listOfNotNull("parent_id" to id,
                                    s?.index?.let { "season" to it }).toTypedArray()))
                            }.onSuccess { close(); app.toast("已加入下载队列", ToastKind.Ok) }.onFailure { app.report(it) }
                        }
                    })
                }
            })
        }
        val ss = seasons.orEmpty()
        if (ss.size > 1) {
            Spacer(Modifier.height(TvSp.x16))
            // 切季**只换分集行**,上半屏不动(整页重画会丢焦点)
            ScopeChips(ss.map { it.name }, ss.indexOfFirst { it.id == season?.id },
                itemModifier = { i -> Modifier.memo("season.${ss[i].id}") }, onSelect = { i ->
                    season = ss[i]
                    scope.launch { loadEpisodes(ss[i].id) }
                })
        }
        Spacer(Modifier.height(TvSp.x12))
    }
    when (val e = episodes) {
        is Block.Loading -> Row(Modifier.padding(start = TvDim.safeH), horizontalArrangement = Arrangement.spacedBy(TvSp.x12)) {
            repeat(5) { Skel(Modifier.size(TvDim.epW, TvDim.epH)) }
        }
        is Block.Fail -> xyz.linplayer.app.tv.kit.InlineError(e.message, Modifier.padding(start = TvDim.safeH))
        is Block.Ok -> EpisodeCards(e.value, currentId = null, targetId = tg?.id, keyPrefix = "detail.ep",
            onOpen = { nav.push(TvRoute.Episode(it.id)) },
            onMenu = { item -> overlay.openCardMenu(app, nav, scope, item) })
    }
    SimilarRow(similar, overlay, scope)
    Column(Modifier.padding(horizontal = TvDim.safeH)) {
        PeopleBlock(peopleOf(d), "detail.people")
    }
}

internal fun peopleOf(d: JsonObject?): List<Person> = d?.get("people").arr().mapNotNull {
    val p = it.obj() ?: return@mapNotNull null
    Person(p.str("id") ?: return@mapNotNull null, p.str("name") ?: "")
}

/**
 * 分集卡一行。第一次从上面按 ↓ 进来,**焦点落在目标集**,不是 E1(§7.4)。
 * ★ 已看完的卡 alpha 0.72 + 满进度条;看了一半右上「继续」角标。
 */
@Composable
internal fun EpisodeCards(
    eps: List<Item>, currentId: String?, targetId: String?, keyPrefix: String,
    onOpen: (Item) -> Unit, onMenu: (Item) -> Unit,
) {
    val app = LocalApp.current
    val mem = LocalFocusMemory.current
    val state = rememberLazyListState()
    // 目标集不在第一屏(一屏放得下 5 张)就先滚过去:没挂上的项没有 FocusRequester,↓ 会落回 E1
    LaunchedEffect(targetId, eps.size) {
        val i = eps.indexOfFirst { it.id == targetId }
        if (i >= 5) state.scrollToItem(i)
    }
    ProvideRowKeyline(TvDim.safeH) {
        LazyRow(
            state = state,
            modifier = Modifier.focusProperties {
                onEnter = {
                    val remembered = mem.key?.takeIf { it.startsWith("$keyPrefix.") }
                    (remembered ?: targetId?.let { "$keyPrefix.$it" })?.let { k -> mem.requesters[k]?.requestFocus() }
                }
            },
            contentPadding = PaddingValues(start = TvDim.safeH, end = TvDim.safeH, top = TvSp.x6),
            horizontalArrangement = Arrangement.spacedBy(TvSp.x12),
        ) {
            items(eps, key = { it.id }) { ep ->
                val no = "E${ep.episodeNo ?: "?"}"
                run {
                    CardEpisode(
                        cover = { TvImage(app.imageUrl(ep.id, "Primary", 220)) },
                        no = no, title = "$no · ${ep.name}",
                        sub = listOfNotNull(ep.runtimeSecs.takeIf { it > 0 }?.let { "${(it / 60).toInt()} 分钟" },
                            when {
                                ep.played -> "已看完"
                                ep.resumeSecs > 0 && ep.runtimeSecs > ep.resumeSecs -> remainText(ep.runtimeSecs - ep.resumeSecs)
                                else -> null
                            }).joinToString(" · "),
                        progress = if (ep.resumeSecs > 0 && !ep.played) ep.progress else null,
                        done = ep.played, current = ep.id == currentId,
                        modifier = Modifier.memo("$keyPrefix.${ep.id}"),
                        onLongClick = { onMenu(ep) }, onClick = { onOpen(ep) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun SimilarRow(similar: List<Item>, overlay: Overlay, scope: CoroutineScope) {
    if (similar.isEmpty()) return
    val app = LocalApp.current
    val nav = LocalNav.current
    Spacer(Modifier.height(TvSp.x20))
    Box(Modifier.padding(start = TvDim.safeH - TvSp.x8)) { RowTitle("相似推荐") }
    Spacer(Modifier.height(TvSp.x8))
    ProvideRowKeyline(TvDim.safeH) {
        LazyRow(
            contentPadding = PaddingValues(start = TvDim.safeH, end = TvDim.safeH),
            horizontalArrangement = Arrangement.spacedBy(TvSp.x12),
        ) {
            items(similar, key = { it.id }) { it ->
                ItemPoster(it, "detail.sim.${it.id}", { item -> overlay.openCardMenu(app, nav, scope, item) })
            }
        }
    }
}

@Composable
private fun MovieBody(id: String, d: JsonObject, overlay: Overlay, scope: CoroutineScope) {
    val app = LocalApp.current
    val nav = LocalNav.current
    var similar by remember { mutableStateOf<List<Item>>(emptyList()) }
    val pick = rememberPickState(id)
    LaunchedEffect(id) {
        launch { similar = Item.list(app.block("emby.similarItems", args("item_id" to id, "limit" to 20)).valueOrNull) }
        launch { pick.loadPrefs(app) }
        launch {
            val name = d.str("name")
            val year = d.long("year")
            loadVersionCards(app, this, id, name,
                match = { it.type == "Movie" && norm(it.name) == norm(name) && (year == null || it.year == null || it.year == year) },
                matchExact = { it.name == name && it.year == year }) { pick.cards = it }
        }
    }
    val resume = d.dbl("resume_secs") ?: 0.0
    val runtime = d.dbl("runtime_secs") ?: 0.0
    val shown = pick.shownVersion()
    val tags = listOfNotNull(
        // 分辨率和动态范围各占一个徽标:挤成「2160p HDR10」一眼读不出是两件事
        *shown?.let(::resLabel)?.takeIf { it != "—" }?.split(" ", limit = 2)?.toTypedArray().orEmpty(),
        shown?.of("Audio")?.firstOrNull()?.let { a -> listOfNotNull(codecName(a.codec), a.layout).joinToString(" ") },
        if (resume > 0 && runtime > 0) "已看 ${Math.round(resume * 100 / runtime)}%" else null,
    )
    val meta = listOfNotNull(d.long("year")?.toString(), runtime.takeIf { it > 0 }?.let { "${(it / 60).toInt()} 分钟" },
        d.strList("genres").take(3).joinToString(" / ").takeIf { it.isNotEmpty() }).joinToString(" · ")
    val title = d.str("name") ?: ""
    Column(Modifier.padding(horizontal = TvDim.safeH)) {
        DetailHead(d, meta, tags, overlay, lines = 2)
        Spacer(Modifier.height(TvSp.x16))
        Row(horizontalArrangement = Arrangement.spacedBy(TvSp.x12)) {
            TvButton(if (resume > 0) "继续播放 ${fmtTime(resume)}" else "播放", LpIcons.play, primary = true,
                modifier = Modifier.memo("detail.play", initial = true), onClick = { scope.launch { pick.play(app, nav, id, title, false) } })
            if (resume > 0) TvButton("从头播放", LpIcons.refresh, modifier = Modifier.memo("detail.restart"),
                onClick = { scope.launch { pick.play(app, nav, id, title, true) } })
            FavButton(app, scope, id, d.bool("is_favorite"), "detail.fav")
            TvButton("更多", LpIcons.more, modifier = Modifier.memo("detail.more"), onClick = {
                val played = d.bool("played")
                scope.launch {
                    val dl = canDownload(app)
                    overlay.openMore(title, app, nav, scope, id, title) { close ->
                        PanelItem(if (played) "标记未看" else "标记已看", focused = true, onClick = {
                            scope.launch {
                                runCatching { app.call("emby.setPlayed", args("item_id" to id, "played" to !played)) }
                                    .onSuccess { close(); app.toast(if (played) "已标记未看" else "已标记已看", ToastKind.Ok) }
                                    .onFailure { app.report(it) }
                            }
                        })
                        if (dl) PanelItem("下载", onClick = {
                            scope.launch {
                                runCatching { app.call("download.enqueue", args("item_id" to id)) }
                                    .onSuccess { close(); app.toast("已加入下载队列", ToastKind.Ok) }.onFailure { app.report(it) }
                            }
                        })
                    }
                }
            })
        }
        Spacer(Modifier.height(TvSp.x12))
        PickBar(pick, overlay, "detail")
        VersionRow(pick.cards, pick.shownKey(), pick.picked?.key, onPick = { pick.pickCard(it) }, down = { null }, above = TvSp.x20, gap = TvSp.x8)
        PeopleBlock(peopleOf(d), "detail.people")
        shown?.let { MediaInfoBlock(it, "detail.media") }
    }
    SimilarRow(similar, overlay, scope)
}

@Composable
private fun BoxSetBody(id: String, d: JsonObject, overlay: Overlay, scope: CoroutineScope) {
    val app = LocalApp.current
    val nav = LocalNav.current
    var groups by remember { mutableStateOf<List<Pair<String, List<Item>>>?>(null) }
    LaunchedEffect(id) {
        val o = app.block("emby.collectionItems", args("item_id" to id)).valueOrNull.obj()
        groups = listOf("影片" to Item.list(o?.get("movies")), "剧集" to Item.list(o?.get("series")), "其它" to Item.list(o?.get("others")))
    }
    Column(Modifier.padding(horizontal = TvDim.safeH)) {
        DetailHead(d, "合集", overlay = overlay, lines = 3)
        if (groups?.all { it.second.isEmpty() } == true) {
            Spacer(Modifier.height(TvSp.x16))
            TvText("这个合集里没有内容", tvType.body, TvC.fg3)
        }
    }
    groups.orEmpty().filter { it.second.isNotEmpty() }.forEachIndexed { gi, (label, list) ->
        Spacer(Modifier.height(TvSp.x20))
        Box(Modifier.padding(start = TvDim.safeH - TvSp.x8)) { RowTitle("$label · ${list.size}") }
        Spacer(Modifier.height(TvSp.x8))
        ProvideRowKeyline(TvDim.safeH) {
            LazyRow(contentPadding = PaddingValues(start = TvDim.safeH, end = TvDim.safeH, top = TvSp.x6, bottom = TvSp.x2),
                horizontalArrangement = Arrangement.spacedBy(TvSp.x12)) {
                items(list, key = { it.id }) { it ->
                    ItemPoster(it, "box.${it.id}", { item -> overlay.openCardMenu(app, nav, scope, item) },
                        initial = gi == 0 && it == list.first())
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 选择状态(§7.6)

/**
 * ☠ **显示的和传下去的是两个值**:旧实现第一张版本卡一出来就写进「用户的选择」,
 * 于是起播**永远**传了一个 media_source_id,核心层的版本筛选正则整个被跳过。
 */
internal class PickState(val itemId: String) {
    var cards by mutableStateOf<List<VCard>>(emptyList())
    var picked by mutableStateOf<VCard?>(null)
    var audio by mutableStateOf<Long?>(null)
    var sub by mutableStateOf<Long?>(null)
    var subOff by mutableStateOf(false)
    var audioLang by mutableStateOf<String?>(null)
    var subLang by mutableStateOf<String?>(null)
    var subEnabled by mutableStateOf(true)
    var lineLabel by mutableStateOf<String?>(null)
    var serverId by mutableStateOf<String?>(null)

    /** shown = 用户选的 ?: preferred ?: 第一条(只在当前服务器里回落)。 */
    fun shownCard(): VCard? = picked ?: cards.filter { it.current }.let { cur ->
        cur.firstOrNull { it.version != null && it.version == defaultVersion(cur.mapNotNull { c -> c.version }) } ?: cur.firstOrNull()
    }
    fun shownKey() = shownCard()?.key
    fun shownVersion() = shownCard()?.version

    fun pickCard(c: VCard) {
        if (c.probe != Probe.OK && c.probe != Probe.OPAQUE) return
        picked = c
        // 换版本 = 换文件,已选的音轨字幕清空
        audio = null; sub = null; subOff = false
    }

    suspend fun loadPrefs(app: AppState) {
        val p = runCatching { app.call("prefs.getPrefs") }.getOrNull().obj()
        audioLang = p.str("audio_lang"); subLang = p.str("sub_lang"); subEnabled = p?.bool("sub_enabled") ?: true
        val accs = runCatching { app.call("account.listAccounts") }.getOrNull().arr().mapNotNull { it.obj() }
        val active = accs.firstOrNull { it.bool("active") }
        serverId = active.str("server")
        val idx = active.long("active_line")?.toInt() ?: 0
        val lines = active?.get("lines").arr()
        lineLabel = lines.getOrNull(idx).obj()?.str("name")?.takeIf { it.isNotBlank() }
            ?: if (lines.isEmpty()) "主线路" else "线路 ${idx + 1}"
    }

    /** 起播。`picked` 为空就不传版本;选的是别台服务器的卡就先切过去,**成功后**再起播。 */
    suspend fun play(app: AppState, nav: TvNav, localItemId: String, title: String, fromStart: Boolean) {
        val p = picked
        var target = localItemId
        if (p != null && !p.current) {
            if (!switchServerIfNeeded(app, p.serverId)) return
            target = p.itemId ?: return
        }
        nav.push(TvRoute.Player(target, title, versionId = p?.version?.id, audioIndex = audio, subIndex = sub,
            subOff = subOff, fromStart = fromStart))
    }
}

@Composable
internal fun rememberPickState(itemId: String) = remember(itemId) { PickState(itemId) }

/**
 * 选择条(§7.5):四个 chip,**按钮里带当前值**。没有可选项时 chip 照样画,面板里如实说「没有」。
 */
@Composable
internal fun PickBar(pick: PickState, overlay: Overlay, keyPrefix: String) {
    val nav = LocalNav.current
    val v = pick.shownVersion()
    val audios = v?.of("Audio").orEmpty()
    val subs = v?.of("Subtitle").orEmpty()
    val audioShown = audios.firstOrNull { it.index == pick.audio }
        ?: audios.firstOrNull { it.lang == pick.audioLang } ?: audios.firstOrNull { it.isDefault } ?: audios.firstOrNull()
    val subShown = if (pick.subOff || (pick.sub == null && !pick.subEnabled)) null
        else subs.firstOrNull { it.index == pick.sub } ?: subs.firstOrNull { it.lang == pick.subLang }
            ?: subs.firstOrNull { it.isDefault } ?: subs.firstOrNull()
    Row(horizontalArrangement = Arrangement.spacedBy(TvSp.x8)) {
        ValueChip("版本", v?.let(::resLabel) ?: "—", LpIcons.version, modifier = Modifier.memo("$keyPrefix.pick.ver"), onClick = {
            overlay.open {
                TvSidePanel("版本", { overlay.close() }) {
                    val cur = pick.cards.filter { it.version != null }
                    if (cur.isEmpty()) PanelItem("没有可选的版本", enabled = false)
                    cur.forEach { c ->
                        PanelItem(c.version?.name ?: "版本", value = resLabel(c.version!!), sub = if (c.current) specLabel(c.version) else "${c.serverName} · ${specLabel(c.version)}",
                            selected = c.key == pick.shownKey(), focused = c.key == pick.shownKey(),
                            onClick = { pick.pickCard(c); overlay.close() })
                    }
                }
            }
        })
        ValueChip("音频", audioShown?.let { listOfNotNull(langCn(it.lang), codecName(it.codec)).joinToString(" ") } ?: "无",
            LpIcons.audio, modifier = Modifier.memo("$keyPrefix.pick.audio"), onClick = {
                overlay.open {
                    TvSidePanel("音频", { overlay.close() }) {
                        if (audios.isEmpty()) PanelItem("该版本没有音轨信息", enabled = false)
                        audios.forEach { s ->
                            PanelItem(streamLabel(s), selected = s == audioShown, focused = s == audioShown,
                                onClick = { pick.audio = s.index; overlay.close() })
                        }
                    }
                }
            })
        ValueChip("字幕", subShown?.let { it.title ?: it.display ?: langCn(it.lang) ?: streamLabel(it) } ?: if (subs.isEmpty()) "无" else "关闭",
            LpIcons.sub, modifier = Modifier.memo("$keyPrefix.pick.sub"), onClick = {
                overlay.open {
                    TvSidePanel("字幕", { overlay.close() }) {
                        if (subs.isEmpty()) PanelItem("该版本没有字幕", enabled = false)
                        subs.forEach { s ->
                            PanelItem(streamLabel(s), selected = s == subShown, focused = s == subShown,
                                onClick = { pick.sub = s.index; pick.subOff = false; overlay.close() })
                        }
                        if (subs.isNotEmpty()) PanelItem("关闭字幕", selected = subShown == null, focused = subShown == null,
                            onClick = { pick.subOff = true; pick.sub = null; overlay.close() })
                    }
                }
            })
        ValueChip("线路", pick.lineLabel ?: "…", LpIcons.line, modifier = Modifier.memo("$keyPrefix.pick.line"), onClick = {
            // 换线路不在播放页做,也不在这里做:进线路管理(§8.6【用户定 2026-07-20】)
            pick.serverId?.let { nav.push(TvRoute.Lines(it, "")) }
        })
    }
}
