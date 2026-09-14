package xyz.linplayer.app.tv

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import xyz.linplayer.app.data.Block
import xyz.linplayer.app.data.Item
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.ToastKind
import xyz.linplayer.app.data.UiPrefs
import xyz.linplayer.app.data.block
import xyz.linplayer.app.data.bool
import xyz.linplayer.app.data.dbl
import xyz.linplayer.app.data.long
import xyz.linplayer.app.data.obj
import xyz.linplayer.app.data.str
import xyz.linplayer.app.tv.kit.FullState
import xyz.linplayer.app.tv.kit.PanelItem
import xyz.linplayer.app.tv.kit.ProvideColumnSpec
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
import xyz.linplayer.app.tv.kit.tvType
import xyz.linplayer.app.ui.pages.args
import xyz.linplayer.app.ui.pages.fmtTime
import xyz.linplayer.app.ui.pages.map
import xyz.linplayer.app.ui.theme.LpIcons

/**
 * 集详情(UI_TV.md §7.6)。**版本 / 音轨 / 字幕的选择全部集中在这一页。**
 *
 * ★ 集数栏 OK = **本页换集,不入栈**:切了 8 集,按一次返回就回剧详情;上半屏淡入替换,焦点留在集数栏。
 */
@Composable
fun EpisodePage(r: TvRoute.Episode) {
    val app = LocalApp.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val overlay = rememberOverlay()
    val mem = LocalFocusMemory.current
    val ctx = LocalContext.current
    val t = tvType

    var epId by rememberSaveable { mutableStateOf(r.itemId) }
    var detail by remember(epId) { mutableStateOf<Block<JsonObject>>(Block.Loading) }
    var siblings by remember { mutableStateOf<List<Item>>(emptyList()) }
    var siblingsFor by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val pick = rememberPickState(epId)
    val fade = remember(epId) { Animatable(if (siblings.isEmpty()) 1f else 0f) }

    LaunchedEffect(epId, reload) {
        launch { fade.animateTo(1f, tween(200)) }
        launch { pick.loadPrefs(app) }
        val b = app.block("emby.itemDetail", args("item_id" to epId)).map { it.obj() ?: JsonObject(emptyMap()) }
        detail = b
        val d = b.valueOrNull ?: return@LaunchedEffect
        val parent = d.str("season_id") ?: d.str("series_id")
        if (parent != null && parent != siblingsFor) launch {
            siblings = Item.list(app.block("emby.seasonEpisodes", args("parent_id" to parent, "limit" to 200)).valueOrNull)
            siblingsFor = parent
        }
        val series = d.str("series_name")
        val s = d.long("season_no")
        val e = d.long("episode_no")
        // 跨服务器认定同一集靠「剧名 + 季 + 集号」;剧名只是写法不同(大小写 / 空格)时标「可能匹配」
        loadVersionCards(app, this, epId, series,
            match = { it.isEpisode && norm2(it.seriesName) == norm2(series) && it.seasonNo == s && it.episodeNo == e },
            matchExact = { it.seriesName == series }) { pick.cards = it }
    }

    val d = detail.valueOrNull
    Box(Modifier.fillMaxSize().background(TvC.bg)) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = fade.value }) {
            DetailBackdrop(d.str("series_id") ?: epId, d?.bool("has_backdrop") ?: (d.str("series_id") != null))
        }
        when (val b = detail) {
            is Block.Fail -> FullState(LpIcons.info, "没加载出来", detail = b.message, buttons = listOf("重试", "返回"),
                tone = TvC.bad, onButton = { if (it == 0) reload++ else nav.pop() })
            is Block.Loading -> if (siblings.isEmpty()) Column(Modifier.padding(horizontal = TvDim.safeH, vertical = TvDim.safeV)) {
                Skel(Modifier.width(200.dp).height(12.dp)); Spacer(Modifier.height(TvSp.x6))
                Skel(Modifier.width(360.dp).height(34.dp)); Spacer(Modifier.height(TvSp.x12))
                Skel(Modifier.width(560.dp).height(40.dp))
            }
            is Block.Ok -> Unit
        }
        if (d != null) ProvideColumnSpec(above = 64.dp, below = TvDim.safeV + TvDim.captionH) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = TvDim.safeV, bottom = TvDim.safeV)) {
                val resume = d.dbl("resume_secs") ?: 0.0
                val runtime = d.dbl("runtime_secs") ?: 0.0
                val series = d.str("series_name")
                val seasonNo = d.long("season_no")
                val epNo = d.long("episode_no")
                val epLabel = epNo?.let { "E%02d".format(it) }
                val title = listOfNotNull(series, if (seasonNo != null && epNo != null) "S${seasonNo}E$epNo" else null,
                    d.str("name")).joinToString(" · ")
                Column(Modifier.padding(horizontal = TvDim.safeH).graphicsLayer { alpha = fade.value }) {
                    TvText(listOfNotNull(series, seasonNo?.let { "第 $it 季" }).joinToString(" · "), t.meta, TvC.fg3)
                    Spacer(Modifier.height(TvSp.x2))
                    TvText(listOfNotNull(epLabel, d.str("name")).joinToString(" · "), t.display, TvC.fg, weight = TvW.bold, maxLines = 2)
                    Spacer(Modifier.height(TvSp.x2))
                    // 不重复规格:规格在下面的版本卡和选择条里
                    TvText(listOfNotNull(runtime.takeIf { it > 0 }?.let { "${(it / 60).toInt()} 分钟" },
                        if (resume > 0 && runtime > resume) remainText(runtime - resume) else null).joinToString(" · "), t.meta, TvC.fg2)
                    Spacer(Modifier.height(TvSp.x6))
                    OverviewBlock(d.str("overview").orEmpty(), 2, overlay, "ep.overview")
                    if (resume > 0 && runtime > 0) {
                        Spacer(Modifier.height(TvSp.x8))
                        Box(Modifier.width(470.dp).height(4.dp).clip(TvR.pill).background(TvC.surface3)) {
                            Box(Modifier.fillMaxHeight().fillMaxWidthFraction((resume / runtime).toFloat()).background(TvC.acc))
                        }
                    }
                    Spacer(Modifier.height(TvSp.x12))
                    Row(horizontalArrangement = Arrangement.spacedBy(TvSp.x12)) {
                        TvButton(if (resume > 0) "继续播放 ${fmtTime(resume)}" else "播放", LpIcons.play, primary = true,
                            modifier = Modifier.memo("ep.play", initial = true),
                            onClick = { scope.launch { pick.play(app, nav, epId, title, false) } })
                        if (resume > 0) TvButton("从头播放", LpIcons.refresh, modifier = Modifier.memo("ep.restart"),
                            onClick = { scope.launch { pick.play(app, nav, epId, title, true) } })
                        // 拿不到这一集的已看状态时整个按钮不画,不猜默认值(§7.6)
                        if (d.containsKey("played")) PlayedButton(app, scope, epId, d.bool("played"))
                        FavButton(app, scope, epId, d.bool("is_favorite"), "ep.fav")
                        TvButton("更多", LpIcons.more, modifier = Modifier.memo("ep.more"), onClick = {
                            scope.launch {
                                val dl = canDownload(app)
                                val blockId = d.str("series_id") ?: epId
                                val blockName = series ?: (d.str("name") ?: "")
                                overlay.openMore(title, app, nav, scope, blockId, blockName) { close ->
                                    if (dl) PanelItem("下载", focused = true, onClick = {
                                        scope.launch {
                                            runCatching { app.call("download.enqueue", args("item_id" to epId)) }
                                                .onSuccess { close(); app.toast("已加入下载队列", ToastKind.Ok) }.onFailure { app.report(it) }
                                        }
                                    })
                                }
                            }
                        })
                    }
                    Spacer(Modifier.height(TvSp.x12))
                    PickBar(pick, overlay, "ep")
                    VersionRow(pick.cards, pick.shownKey(), pick.picked?.key, onPick = { pick.pickCard(it) },
                        down = { mem.requesters["epbar.$epId"] })
                    Spacer(Modifier.height(TvSp.x16))
                    val detailView = UiPrefs.tvEpisodeView.value == "detail"
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RowTitle("选集", trailing = listOfNotNull(seasonNo?.let { "第 $it 季" },
                            siblings.size.takeIf { it > 0 }?.let { "共 $it 集" }).joinToString(" · "))
                        Spacer(Modifier.weight(1f))
                        // 视图选择**持久化**在本机:切一次详细下次进来还是详细
                        ScopeChips(listOf("紧凑", "详细"), if (detailView) 1 else 0,
                            itemModifier = { i -> Modifier.memo("ep.view.$i") },
                            onSelect = { i -> UiPrefs.setTv(ctx, "tv_episode_view", if (i == 1) "detail" else "compact") })
                    }
                    Spacer(Modifier.height(TvSp.x8))
                }
                if (UiPrefs.tvEpisodeView.value == "detail") {
                    EpisodeCards(siblings, currentId = epId, targetId = epId, keyPrefix = "epbar",
                        onOpen = { epId = it.id },
                        onMenu = { item -> overlay.openCardMenu(app, nav, scope, item) })
                } else CompactEpisodes(siblings, epId) { epId = it.id }
            }
        }
        OverlayHost(overlay)
    }
}

private fun norm2(s: String?) = s.orEmpty().lowercase().filter { it.isLetterOrDigit() }

private fun Modifier.fillMaxWidthFraction(f: Float) = this.fillMaxWidth(f.coerceIn(0f, 1f))

@Composable
private fun PlayedButton(app: xyz.linplayer.app.data.AppState, scope: kotlinx.coroutines.CoroutineScope, id: String, initial: Boolean) {
    var played by remember(id, initial) { mutableStateOf(initial) }
    TvButton(if (played) "已看" else "标记已看", LpIcons.check, modifier = Modifier.memo("ep.played"), onClick = {
        val want = !played
        played = want
        scope.launch {
            runCatching { app.call("emby.setPlayed", args("item_id" to id, "played" to want)) }
                .onFailure { played = !want; app.report(it) }
        }
    })
}

/**
 * 紧凑选集栏:集号 chip 52×36,一屏 14 集,**当前集恒居中**(第一集不强行居中)。
 * ★ 这一行的滚动跟随是「焦点项居中」,和别的横向行「钉行首」不同 —— 所以单独提供 spec。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CompactEpisodes(eps: List<Item>, currentId: String, onPick: (Item) -> Unit) {
    val cur = eps.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (cur - 6).coerceAtLeast(0))
    LaunchedEffect(currentId, eps.size) { if (eps.isNotEmpty()) state.animateScrollToItem((cur - 6).coerceAtLeast(0)) }
    val center = remember {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float) =
                offset + size / 2 - containerSize / 2
        }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides center) {
        LazyRow(
            state = state, contentPadding = PaddingValues(horizontal = TvDim.safeH),
            horizontalArrangement = Arrangement.spacedBy(TvSp.x8),
        ) {
            items(eps, key = { it.id }) { ep ->
                EpisodeChip("E${ep.episodeNo ?: "?"}", current = ep.id == currentId, watched = ep.played,
                    modifier = Modifier.memo("epbar.${ep.id}"), onClick = { onPick(ep) })
            }
        }
    }
}

/** 紧凑选集 chip。当前集 = 琥珀底;已看 = 灰字 + 短横;聚焦 = 白。 */
@Composable
private fun EpisodeChip(label: String, current: Boolean, watched: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val t = tvType
    Surface(
        onClick = onClick,
        modifier = modifier.size(52.dp, 36.dp),
        shape = ClickableSurfaceDefaults.shape(TvR.sm),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (current) TvC.acc else TvC.surface2,
            contentColor = when { current -> TvC.onAcc; watched -> TvC.fg3; else -> TvC.fg },
            focusedContainerColor = TvC.focus, focusedContentColor = TvC.onFocus,
        ),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(label, fontSize = t.body, fontWeight = TvW.semi)
            if (watched) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 5.dp).size(18.dp, 2.dp).clip(TvR.pill)
                .background(TvC.fg3))
        }
    }
}
