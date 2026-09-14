package xyz.linplayer.app.tv

import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import xyz.linplayer.app.tv.kit.TvC
import xyz.linplayer.app.tv.kit.TvR
import xyz.linplayer.app.tv.kit.TvSp
import xyz.linplayer.app.tv.kit.menuKey
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.Item
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.PageCache
import xyz.linplayer.app.data.ToastKind
import xyz.linplayer.app.data.arr
import xyz.linplayer.app.data.bool
import xyz.linplayer.app.data.obj
import xyz.linplayer.app.data.str
import xyz.linplayer.app.tv.kit.CardPoster
import xyz.linplayer.app.tv.kit.CardWide
import xyz.linplayer.app.tv.kit.PanelGroup
import xyz.linplayer.app.tv.kit.PanelItem
import xyz.linplayer.app.tv.kit.TvDim
import xyz.linplayer.app.ui.pages.Stream
import xyz.linplayer.app.ui.pages.Version
import xyz.linplayer.app.ui.pages.args
import xyz.linplayer.app.ui.pages.fmtRate
import xyz.linplayer.app.ui.pages.fmtSize
import xyz.linplayer.app.ui.pages.langCn

/* 页面之间共用的卡片口径、卡片操作面板、规格文案。 */

/** 分集卡 OK → 集详情页,不是剧详情页【用户定 2026-08-02】;其余进详情。 */
fun openItem(nav: TvNav, it: Item) {
    nav.push(if (it.isEpisode) TvRoute.Episode(it.id) else TvRoute.Detail(it.id, it.type.ifEmpty { "Movie" }))
}

/** 卡片标题口径【继承 PC】:分集恒带剧名「剧名 · S1E5」,缺季号回落集名。 */
fun cardTitleOf(it: Item): String = when {
    it.isEpisode && it.seriesName != null && it.seasonNo != null && it.episodeNo != null ->
        "${it.seriesName} · S${it.seasonNo}E${it.episodeNo}"
    it.isEpisode && it.seriesName != null -> "${it.seriesName} · ${it.name}"
    else -> it.name
}

/** 「剩 18 分钟」/「剩 1 小时 12 分」。**只给到分钟**:秒级每两秒跳一次,三米外像页面在抖。 */
fun remainText(secs: Double): String {
    val m = (secs / 60).toLong().coerceAtLeast(1)
    return if (m >= 60) "剩 ${m / 60} 小时 ${m % 60} 分" else "剩 $m 分钟"
}

fun wideSubOf(it: Item): String = when {
    it.resumeSecs > 0 && it.runtimeSecs > it.resumeSecs -> remainText(it.runtimeSecs - it.resumeSecs)
    it.isEpisode -> it.name
    else -> it.year?.toString().orEmpty()
}

fun posterSubOf(it: Item): String = listOfNotNull(
    it.year?.toString(),
    it.rating?.takeIf { r -> r > 0 }?.let { r -> "★ %.1f".format(r) },
).joinToString(" · ")

@Composable
fun ItemWide(
    item: Item, key: String, onMenu: (Item) -> Unit, showProgress: Boolean = true, initial: Boolean = false,
    w: Dp = TvDim.wideW, h: Dp = TvDim.wideH, onOpen: ((Item) -> Unit)? = null,
) {
    val app = LocalApp.current
    val nav = LocalNav.current
    CardWide(
        cover = { TvImage(app.imageUrl(item.id, "Primary", 220)) },
        title = cardTitleOf(item), sub = wideSubOf(item),
        progress = if (showProgress && item.progress > 0f) item.progress else null,
        w = w, h = h, modifier = Modifier.memo(key, initial),
        onLongClick = { onMenu(item) }, onClick = { onOpen?.invoke(item) ?: openItem(nav, item) },
    )
}

@Composable
fun ItemPoster(
    item: Item, key: String, onMenu: (Item) -> Unit, initial: Boolean = false, rank: Int = 0,
    w: Dp = TvDim.posterW, h: Dp = TvDim.posterH, sub: String = posterSubOf(item),
    onOpen: ((Item) -> Unit)? = null,
) {
    val app = LocalApp.current
    val nav = LocalNav.current
    CardPoster(
        cover = { TvImage(app.imageUrl(item.id, "Primary", 330)) },
        title = item.name, sub = sub, watched = item.played, unplayed = item.unplayed.toInt(), rank = rank,
        w = w, h = h, modifier = Modifier.memo(key, initial),
        onLongClick = { onMenu(item) }, onClick = { onOpen?.invoke(item) ?: openItem(nav, item) },
    )
}

/** `emby.permissions.can_download`。按会话缓存:每开一次卡片面板问一次服务器不值得。 */
suspend fun canDownload(app: AppState): Boolean {
    val k = "tv.perm." + app.session.value?.server
    PageCache.get<Boolean>(k)?.let { return it }
    val v = runCatching { app.call("emby.permissions") }.getOrNull().obj().bool("can_download")
    PageCache.put(k, v)
    return v
}

/**
 * 卡片操作面板(§5.6)。**所有出现卡片的页面都接这一个**:
 * ☠ 旧实现只有媒体库页接了长按屏蔽,「同一张卡在不同页面行为不一样」。
 *
 * [onChanged] 收到的是做了什么:`fav` / `unfav` / `played` / `unplayed` / `blocked` —— 页面按需移除或刷新。
 */
fun Overlay.openCardMenu(app: AppState, nav: TvNav, scope: CoroutineScope, item: Item, onChanged: (String) -> Unit = {}) {
    open { CardMenu(app, nav, scope, item, onClose = { close() }, onChanged = onChanged) }
}

@Composable
private fun BoxScope.CardMenu(
    app: AppState, nav: TvNav, scope: CoroutineScope, item: Item,
    onClose: () -> Unit, onChanged: (String) -> Unit,
) {
    var level by remember { mutableStateOf(0) }
    var fav by remember { mutableStateOf<Boolean?>(null) }
    var played by remember { mutableStateOf(item.played) }
    var dl by remember { mutableStateOf(false) }
    // 分集的屏蔽记**剧名**:观看记录跨服务器,核心层只认名字(§7.6)
    val blockId = if (item.isEpisode) item.seriesId ?: item.id else item.id
    val blockName = if (item.isEpisode) item.seriesName ?: item.name else item.name
    var blocked by remember { mutableStateOf(false) }
    LaunchedEffect(item.id) {
        launch { fav = runCatching { app.call("emby.itemDetail", args("item_id" to item.id)) }.getOrNull().obj()?.bool("is_favorite") }
        launch { dl = canDownload(app) }
        launch {
            blocked = runCatching { app.call("emby.blockedList") }.getOrNull().arr()
                .any { it.obj().str("id") == blockId || it.obj().str("name") == blockName }
        }
    }
    fun act(cmd: String, a: kotlinx.serialization.json.JsonObject, ok: String, tag: String, after: () -> Unit = {}) {
        scope.launch {
            runCatching { app.call(cmd, a) }
                .onSuccess { after(); app.toast(ok, ToastKind.Ok); onChanged(tag) }
                .onFailure { app.report(it) }
        }
    }
    TvSidePanel(cardTitleOf(item), onClose, back = if (level == 1) ({ level = 0 }) else null) {
        if (level == 1) {
            PanelItem(if (blocked) "解除屏蔽" else "屏蔽这部内容",
                sub = if (blocked) "它会重新出现在首页、媒体库和搜索里。"
                else "屏蔽后它不会再出现在首页、媒体库和搜索里。分集按剧名屏蔽整部剧。",
                enabled = false)
            PanelItem(if (blocked) "确认解除" else "确认屏蔽", danger = !blocked, focused = true, onClick = {
                act("emby.setBlocked", args("id" to blockId, "name" to blockName, "blocked" to !blocked),
                    if (blocked) "已解除屏蔽" else "已屏蔽「$blockName」", if (blocked) "unblocked" else "blocked") { onClose() }
            })
            return@TvSidePanel
        }
        PanelItem("查看详情", focused = true, onClick = { onClose(); openItem(nav, item) })
        if (item.isEpisode || item.type == "Movie") PanelItem(
            if (item.resumeSecs > 0) "继续播放" else "播放",
            value = if (item.resumeSecs > 0 && item.runtimeSecs > item.resumeSecs) remainText(item.runtimeSecs - item.resumeSecs) else null,
            onClick = { onClose(); nav.push(TvRoute.Player(item.id, cardTitleOf(item))) },
        )
        PanelGroup("标记")
        fav?.let { f ->
            PanelItem(if (f) "取消收藏" else "收藏", onClick = {
                act("emby.setFavorite", args("item_id" to item.id, "fav" to !f), if (f) "已取消收藏" else "已加入收藏",
                    if (f) "unfav" else "fav") { fav = !f }
            })
        }
        PanelItem(if (played) "标记未看" else "标记已看", onClick = {
            act("emby.setPlayed", args("item_id" to item.id, "played" to !played), if (played) "已标记未看" else "已标记已看",
                if (played) "unplayed" else "played") { played = !played }
        })
        // can_download 为假时**不出现**:按下去才说没权限,等于摆一个不生效的项
        if (dl) {
            PanelGroup("其它")
            PanelItem("下载", onClick = {
                act("download.enqueue", args("item_id" to item.id), "已加入下载队列", "download")
            })
        }
        PanelGroup("危险")
        PanelItem(if (blocked) "解除屏蔽" else "屏蔽这部内容", danger = true, chevron = true, onClick = { level = 1 })
    }
}

/**
 * 整行一个焦点的列表行(族 B 行型:聚焦反白,不放大)。
 * ★ 行内**不放按钮**:一行三个小按钮让左右键变成噩梦(§7.10)。行内文字吃 Surface 的 contentColor,
 *   聚焦反白时状态色一律让位给 onFocus —— 琥珀字压在白底上对比度只有约 2:1。
 */
@Composable
fun TvListRow(
    modifier: Modifier = Modifier, height: Dp = 64.dp, dim: Boolean = false,
    onLongClick: (() -> Unit)? = null, onClick: () -> Unit = {},
    content: @Composable androidx.compose.foundation.layout.RowScope.(focused: Boolean) -> Unit,
) {
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    androidx.tv.material3.Surface(
        onClick = onClick, onLongClick = onLongClick, interactionSource = src,
        modifier = modifier.fillMaxWidth().heightIn(min = height).menuKey(onLongClick)
            .then(if (dim) Modifier.graphicsLayer { alpha = .72f } else Modifier),
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(TvR.md),
        scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 0.99f),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = TvC.surface1, contentColor = TvC.fg,
            focusedContainerColor = TvC.focus, focusedContentColor = TvC.onFocus,
        ),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = height).padding(horizontal = TvSp.x12, vertical = TvSp.x8),
            verticalAlignment = Alignment.CenterVertically) { content(focused) }
    }
}

/** 行内文字:颜色跟 Surface 走(聚焦反色),只给透明度。 */
@Composable
fun RowText(text: String, size: androidx.compose.ui.unit.TextUnit, alpha: Float = 1f, maxLines: Int = 1, mono: Boolean = false) {
    androidx.tv.material3.Text(
        text, fontSize = size, maxLines = maxLines, modifier = Modifier.graphicsLayer { this.alpha = alpha },
        fontFamily = if (mono) androidx.compose.ui.text.font.FontFamily.Monospace else null,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
    )
}

// ---------------------------------------------------------------- 版本规格文案(§7.6)

/** 分辨率档:「2160p HDR10」。SDR 不写。 */
/** 编码名按惯用写法:`truehd` 全大写成「TRUEHD」,没人这么叫。 */
internal fun codecName(c: String?): String? {
    val k = c?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    return when (k) {
        "truehd" -> "TrueHD"; "eac3" -> "E-AC3"; "dca" -> "DTS"; "opus" -> "Opus"; "h265" -> "HEVC"; "avc" -> "H264"
        "subrip" -> "SRT"; "pgssub", "hdmv_pgs_subtitle" -> "PGS"
        else -> k.uppercase()
    }
}

internal fun resLabel(v: Version): String {
    val s = v.of("Video").firstOrNull() ?: return "—"
    val h = s.height ?: return "—"
    val w = s.width ?: 0
    val p = when {
        h >= 2000 || w >= 3800 -> "2160p"
        h >= 1000 || w >= 1900 -> "1080p"
        h >= 700 || w >= 1270 -> "720p"
        else -> "${h}p"
    }
    val range = s.range?.takeIf { it.isNotBlank() && !it.equals("SDR", true) }
    return if (range != null) "$p $range" else p
}

/** 「HEVC · 24.6 Mbps · 8.4 GB」。**码率必须给**【用户定 2026-07-20】:4 Mbps 的 2160p 不如 15 Mbps 的 1080p。 */
internal fun specLabel(v: Version): String = listOfNotNull(
    codecName(v.of("Video").firstOrNull()?.codec),
    fmtRate(v.bitrate ?: v.of("Video").firstOrNull()?.bitrate),
    fmtSize(v.sizeBytes),
).joinToString(" · ")

private fun langShort(code: String?): String? = langCn(code)?.let { if (it.endsWith("语")) it.dropLast(1) else it.take(2) }

/** 前 2 条音轨 · 前 4 种字幕语言。 */
internal fun tracksLabel(v: Version): String {
    val audio = v.of("Audio").take(2).joinToString(" / ") { a ->
        listOfNotNull(langShort(a.lang), codecName(a.codec)).joinToString(" ")
    }
    val subs = v.of("Subtitle").mapNotNull { langShort(it.lang) }.distinct().take(4).joinToString(" / ")
    return listOf(audio, subs).filter { it.isNotBlank() }.joinToString(" · ")
}

internal fun streamLabel(s: Stream): String = listOfNotNull(
    s.title ?: s.display ?: langCn(s.lang), codecName(s.codec),
    if (s.isExternal) "外挂" else null,
).distinct().joinToString(" · ").ifBlank { "轨道 ${s.index}" }
