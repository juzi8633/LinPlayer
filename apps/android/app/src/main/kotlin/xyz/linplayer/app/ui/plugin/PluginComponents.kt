package xyz.linplayer.app.ui.plugin

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.Item
import xyz.linplayer.app.data.arr
import xyz.linplayer.app.data.bool
import xyz.linplayer.app.data.dbl
import xyz.linplayer.app.data.long
import xyz.linplayer.app.data.obj
import xyz.linplayer.app.data.str
import xyz.linplayer.app.tv.memo
import xyz.linplayer.app.ui.components.Body
import xyz.linplayer.app.ui.components.BtnKind
import xyz.linplayer.app.ui.components.Dim2
import xyz.linplayer.app.ui.components.Dim3
import xyz.linplayer.app.ui.components.H1
import xyz.linplayer.app.ui.components.H2
import xyz.linplayer.app.ui.components.MediaCard
import xyz.linplayer.app.ui.components.NetImage
import xyz.linplayer.app.ui.components.ToneChip
import xyz.linplayer.app.ui.components.rowHeight
import xyz.linplayer.app.ui.theme.Dim
import xyz.linplayer.app.ui.theme.LpIcons
import xyz.linplayer.app.ui.theme.Lp
import xyz.linplayer.app.ui.theme.R
import xyz.linplayer.app.ui.theme.Sp

/**
 * SPEC 7.4 的业务组件与几个重原语。放这里只为了 PluginRender.kt 不被撑爆:
 * 认领仍在那边的 when 里,这边只有画法。
 *
 * 业务组件一律**复用官方页那一份实现**(D21):海报卡是 [MediaCard],轨道高度是
 * [rowHeight],chip 是 [ToneChip] —— 主题改它们,插件里的跟着变。
 */

/** disabled 的视觉档:与 LpButton 的禁用态同一个值,两处分叉会被一眼看出来。 */
internal const val DisabledAlpha = 0.45f

internal fun UiNode.arrOf(k: String): List<JsonElement> = props[k].arr()

/**
 * 可点区域。TV 上**必须能吃焦点**,否则遥控器进不去这一块(整页没落点时方向键失灵)。
 *
 * `combinedClickable` 自带焦点与 DPAD_CENTER/回车,所以两端共用一份;TV 额外加焦点记忆与可见焦点环
 * —— 焦点看不见和聚不到在遥控器上是同一种坏。
 */
@Composable
internal fun Modifier.plugPress(
    id: Int, enabled: Boolean = true, onLongClick: (() -> Unit)? = null, onClick: () -> Unit,
): Modifier {
    val tv = LocalPluginTv.current
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val haptic = LocalHapticFeedback.current
    return this
        .then(if (tv) Modifier.memoFocus(id) else Modifier)
        .then(
            if (tv && focused) Modifier.border(2.dp, Lp.colors.acc, RoundedCornerShape(R.md))
            else Modifier
        )
        .combinedClickable(
            interactionSource = src, indication = null, enabled = enabled,
            // 长按必须震一下:没反馈的话用户按到一半就松手,以为这块不支持长按
            onLongClick = onLongClick?.let {
                { haptic.performHapticFeedback(HapticFeedbackType.LongPress); it() }
            },
            onClick = onClick,
        )
}

/**
 * PressProps 的 `onLongPress`(.d.ts `PressProps`)。没声明就给 null ——
 * 传个空 lambda 下去,控件会长按震一下然后什么都不做,比不支持更像坏了。
 */
internal fun longPressOf(n: UiNode, surface: String, app: AppState): (() -> Unit)? =
    n.fn("onLongPress")?.let { fn -> { fire(app, surface, fn) } }

/**
 * PressProps 的 `onFocus` / `onBlur`。挂在 [RenderNode] 给每个节点算的那条 modifier 上
 * —— 六个可点组件各写一遍的话,新增第七个的那次必然漏。
 *
 * 只在声明了回调时才入链:`onFocusChanged` 附着当帧就会回调一次(未聚焦),
 * 拿 [was] 挡住,否则整页每个节点开屏都白发一次 onBlur。
 */
@Composable
internal fun Modifier.plugFocusEvents(n: UiNode, surface: String, app: AppState): Modifier {
    val onFocus = n.fn("onFocus")
    val onBlur = n.fn("onBlur")
    if (onFocus == null && onBlur == null) return this
    val was = remember { mutableStateOf(false) }
    return this.onFocusChanged { st ->
        // hasFocus 一起算:[PosterCard] 这种把 m 挂在外层 Box 上的,焦点落在子节点身上,
        // 只看 isFocused 的话这一整类组件永远不发 onFocus
        val now = st.isFocused || st.hasFocus
        if (now == was.value) return@onFocusChanged
        was.value = now
        fire(app, surface, if (now) onFocus else onBlur)
    }
}

@Composable
private fun Modifier.memoFocus(id: Int): Modifier =
    this.memo("plug.$id", claimsInitialFocus(id))

/**
 * 一颗可选 chip。两端各用各的官方那颗:手机 [ToneChip],TV `TvButton`(自带焦点态与放大)。
 *
 * [index] 只用来分焦点记忆的 key,并决定谁吃这一块的初始焦点。
 */
@Composable
internal fun PlugChip(
    nodeId: Int, index: Int, label: String, on: Boolean, enabled: Boolean = true, onClick: () -> Unit,
) {
    if (LocalPluginTv.current) xyz.linplayer.app.tv.kit.TvButton(
        label, primary = on, enabled = enabled,
        modifier = Modifier.memo(
            "plug.$nodeId.$index", index == 0 && claimsInitialFocus(nodeId),
        ),
        onClick = onClick,
    ) else ToneChip(label, on, Modifier) { if (enabled) onClick() }
}

/** 插件的 MediaItem(.d.ts)→ 官方卡片吃的 [Item]。字段名不一样,只能逐个对。 */
internal fun mediaItem(o: JsonObject?, progress: Double = 0.0): Item = Item(
    id = o.str("id").orEmpty(),
    name = o.str("title").orEmpty(),
    type = when (o.str("kind")) {
        "series" -> "Series"; "episode" -> "Episode"; "season" -> "Season"; else -> "Movie"
    },
    // MediaCard 的进度条读的是 resume/runtime 的比值,所以把比值本身喂进去
    runtimeSecs = if (progress > 0) 1.0 else 0.0,
    resumeSecs = progress,
    year = o.long("year"),
    rating = o.arr("ratings").firstOrNull().obj().dbl("value"),
    played = o.obj("userData").bool("played"),
    unplayed = o.obj("userData").long("unplayedCount") ?: 0,
)

private fun JsonObject?.obj(k: String): JsonObject? = this?.get(k).obj()
private fun JsonObject?.arr(k: String): List<JsonElement> = this?.get(k).arr()

/** ImageRef 或裸地址都收:插件两种写法都合法(.d.ts 的 `string | ImageRef`)。 */
internal fun imageRef(e: JsonElement?): String? = when (e) {
    is JsonObject -> e.str("url")
    is JsonPrimitive -> e.content.takeIf { it.isNotBlank() }
    else -> null
}

/** 海报卡(D21:和官方页同一份)。 */
@Composable
internal fun PosterCard(
    id: Int, index: Int, m: Modifier, item: JsonObject?, shape: String?, onPress: () -> Unit,
    showRemarks: Boolean = true, progress: Double = 0.0, onLongPress: (() -> Unit)? = null,
) {
    val it0 = mediaItem(item, progress)
    val cover = imageRef(item?.get("poster") ?: item?.get("backdrop"))
    val remarks = item.str("remarks")?.takeIf { showRemarks && it.isNotBlank() }
    Box(m) {
        if (LocalPluginTv.current) xyz.linplayer.app.tv.kit.CardPoster(
            cover = { xyz.linplayer.app.tv.TvImage(cover, Modifier.fillMaxSize()) },
            title = it0.cardTitle, sub = remarks ?: it0.cardSub.orEmpty(),
            watched = it0.played, unplayed = it0.unplayed.toInt(),
            modifier = Modifier.memo(
                "plug.$id.$index", index == 0 && claimsInitialFocus(id),
            ),
            onLongClick = onLongPress,
            onClick = onPress,
        ) else {
            MediaCard(it0, cover, onPress, thumb = shape == "landscape", onLongPress = onLongPress)
            // 角标是**源给的一句话**(「更新至 12 集」),官方卡没有这一格,叠上去
            if (remarks != null) Text(
                remarks, Modifier.padding(Sp.x6).clip(RoundedCornerShape(R.sm))
                    .background(Lp.colors.acc).padding(horizontal = Sp.x6, vertical = Sp.x2),
                color = Lp.colors.accFg, fontSize = 11.sp, maxLines = 1,
            )
        }
    }
}

/** 一排海报(横滑)。轨道高度用官方那个常量 —— 不给固定高度的 LazyRow 嵌在列里滑不动。 */
@Composable
internal fun PosterRow(n: UiNode, m: Modifier, surface: String, app: AppState) {
    val list = n.arrOf("items")
    val thumb = n.str("shape") == "landscape"
    val state = androidx.compose.foundation.lazy.rememberLazyListState()
    Column(m.fillMaxWidth()) {
        n.str("title")?.let { H2(it, Modifier.padding(horizontal = Sp.x16, vertical = Sp.x8)) }
        LazyRow(
            Modifier.fillMaxWidth().height(rowHeight(thumb)),
            state,
            contentPadding = PaddingValues(horizontal = Sp.x16),
            horizontalArrangement = Arrangement.spacedBy(Sp.x10),
        ) {
            items(list.size) { i ->
                val o = list[i].obj()
                PosterCard(n.id, i, Modifier, o, n.str("shape"), {
                    fire(app, surface, n.fn("onItemPress"), o)
                })
            }
        }
    }
    EndReached({ state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }, list.size) {
        fire(app, surface, n.fn("onEndReached"))
    }
}

/**
 * 最后一项露头时要一次下一页。
 *
 * ☠ 只在「到底」这件事**从假变真**时发一次:每帧发的话插件那边会被连着要十几页。
 */
@Composable
private fun EndReached(lastVisible: () -> Int, total: Int, onEnd: () -> Unit) {
    LaunchedEffect(total) {
        androidx.compose.runtime.snapshotFlow { total > 0 && lastVisible() >= total - 1 }
            .collect { if (it) onEnd() }
    }
}

/**
 * 海报网格。
 *
 * ★ 故意**不用 LazyVerticalGrid**:插件页宿主本身是个 LazyColumn,高度无界的嵌套
 *   可滚动容器 Compose 是当场抛异常。项全部画出来,所以「到底」就是画完那一刻。
 */
@Composable
internal fun PosterGrid(n: UiNode, m: Modifier, surface: String, app: AppState) {
    val list = n.arrOf("items")
    val cols = (n.num("columns") ?: 3.0).toInt().coerceAtLeast(1)
    Column(m.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Sp.x12)) {
        list.chunked(cols).forEachIndexed { r, row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Sp.x10)) {
                row.forEachIndexed { c, e ->
                    val o = e.obj()
                    PosterCard(n.id, r * cols + c, Modifier, o, n.str("shape"), {
                        fire(app, surface, n.fn("onItemPress"), o)
                    })
                }
            }
        }
    }
    LaunchedEffect(list.size) {
        if (list.isNotEmpty()) fire(app, surface, n.fn("onEndReached"))
    }
}

/**
 * 选集格(.d.ts 的 `Episode{id,name,index}`)。
 *
 * 插件的集里**没有封面字段**,所以用不上官方详情页那张带剧照的 EpCard —— 画成格子按钮,
 * 当前集高亮。两端各用各的官方那颗(见 [PlugChip])。
 */
@Composable
internal fun EpisodeGrid(n: UiNode, m: Modifier, surface: String, app: AppState) {
    val raw = n.arrOf("episodes")
    val eps = if (n.bool("reversed")) raw.reversed() else raw
    val cur = n.str("current")
    Column(m.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Sp.x8)) {
        eps.chunked(4).forEachIndexed { r, row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Sp.x8)) {
                row.forEachIndexed { c, e ->
                    val o = e.obj()
                    val label = o.str("name")?.takeIf { it.isNotBlank() }
                        ?: o.long("index")?.let { "第 $it 集" } ?: "?"
                    PlugChip(n.id, r * 4 + c, label, o.str("id") == cur, !n.bool("disabled")) {
                        fire(app, surface, n.fn("onSelect"), o)
                    }
                }
            }
        }
    }
}

/** 顶部标签页 / 线路条。两者只差字段名,画法同一份。 */
@Composable
internal fun PlugTabs(n: UiNode, m: Modifier, surface: String, app: AppState, lines: Boolean) {
    val list = n.arrOf(if (lines) "lines" else "tabs")
    val cur = n.str("current")
    Row(
        m.horizontalScroll(rememberScrollState()).padding(horizontal = Sp.x16, vertical = Sp.x8),
        horizontalArrangement = Arrangement.spacedBy(Sp.x8),
    ) {
        list.forEachIndexed { i, e ->
            val o = e.obj()
            val id = o.str("id").orEmpty()
            PlugChip(n.id, i, o.str(if (lines) "name" else "title") ?: id, id == cur, !n.bool("disabled")) {
                fire(app, surface, n.fn("onChange"), id)
            }
        }
    }
}

/**
 * 筛选面板(.d.ts 的 `FilterDimension[]`)。
 *
 * 回调要的是**整张表**(`Record<string,string[]>`),不是这一次点的那一个 ——
 * 只回传增量的话插件那边得自己记上一次选了什么,那是把状态分成两份。
 */
@Composable
internal fun FilterPanel(n: UiNode, m: Modifier, surface: String, app: AppState) {
    val dims = n.arrOf("dimensions")
    val cur = n.props["value"] as? JsonObject
    Column(m.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Sp.x10)) {
        dims.forEachIndexed { di, de ->
            val d = de.obj()
            val key = d.str("key").orEmpty()
            val picked = cur?.get(key).arr().mapNotNull { (it as? JsonPrimitive)?.content }
            Dim3(d.str("name").orEmpty(), Modifier.padding(horizontal = Sp.x16))
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Sp.x16),
                horizontalArrangement = Arrangement.spacedBy(Sp.x8),
            ) {
                d?.get("options").arr().forEachIndexed { oi, oe ->
                    val o = oe.obj()
                    val v = o.str("value").orEmpty()
                    val on = v in picked
                    PlugChip(n.id, di * 100 + oi, o.str("name") ?: v, on, !n.bool("disabled")) {
                        val next = if (d.bool("multi")) {
                            if (on) picked - v else picked + v
                        } else listOf(v)
                        fire(app, surface, n.fn("onChange"), filterValue(cur, key, next))
                    }
                }
            }
        }
    }
}

private fun filterValue(cur: JsonObject?, key: String, next: List<String>): JsonObject {
    val m = cur?.toMutableMap() ?: mutableMapOf()
    m[key] = JsonArray(next.map { JsonPrimitive(it) })
    return JsonObject(m)
}

/** 各家评分并排(D435)。没有 max 的按 10 分制显示。 */
@Composable
internal fun RatingList(n: UiNode, m: Modifier) {
    Row(m.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Sp.x8)) {
        n.arrOf("ratings").forEach { e ->
            val o = e.obj()
            val v = o.dbl("value") ?: return@forEach
            Row(
                Modifier.clip(RoundedCornerShape(R.sm)).background(Lp.colors.s2)
                    .padding(horizontal = Sp.x10, vertical = Sp.x6),
                horizontalArrangement = Arrangement.spacedBy(Sp.x6),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Dim3(o.str("source").orEmpty())
                Text(
                    "$v", color = Lp.colors.acc, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                )
                o.dbl("max")?.let { Dim3("/ ${it.toInt()}") }
            }
        }
    }
}

/** 服务器卡。**不显示地址与用户名** —— ServerInfo 里压根没有这两样(.d.ts)。 */
@Composable
internal fun ServerCard(n: UiNode, m: Modifier, surface: String, app: AppState) {
    val s = n.props["server"] as? JsonObject
    val dis = n.bool("disabled")
    Row(
        m.fillMaxWidth().clip(RoundedCornerShape(R.md)).background(Lp.colors.s2)
            .plugPress(n.id, !dis, longPressOf(n, surface, app)) {
                fire(app, surface, n.fn("onPress"))
            }
            .padding(Sp.x12),
        horizontalArrangement = Arrangement.spacedBy(Sp.x12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Icon(
            LpIcons.server, null, Modifier.size(22.dp), tint = Lp.colors.fg2,
        )
        Column(Modifier.weight(1f)) {
            Body(s.str("name").orEmpty(), maxLines = 1)
            Dim3(if (s.str("type") == "emby") "Emby 服务器" else "数据源")
        }
    }
}

/** 详情头:背景图 + 标题 + 一行元信息 + 简介,`actions` 是子节点树,原样渲染。 */
@Composable
internal fun DetailHeader(n: UiNode, m: Modifier, surface: String, app: AppState) {
    val o = n.props["item"] as? JsonObject
    val meta = listOfNotNull(
        o.long("year")?.toString(),
        o?.get("genres").arr().mapNotNull { (it as? JsonPrimitive)?.content }
            .takeIf { it.isNotEmpty() }?.joinToString(" · "),
    ).joinToString(" · ")
    Column(m.fillMaxWidth()) {
        imageRef(o?.get("backdrop"))?.let {
            NetImage(it, null, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            Spacer(Modifier.height(Sp.x12))
        }
        H1(o.str("title").orEmpty(), Modifier.padding(horizontal = Sp.x16))
        if (meta.isNotEmpty()) Dim3(meta, Modifier.padding(horizontal = Sp.x16, vertical = Sp.x4))
        o.str("overview")?.takeIf { it.isNotBlank() }?.let {
            Dim2(it, Modifier.padding(Sp.x16), maxLines = 4)
        }
        if (n.children.isNotEmpty()) Row(
            Modifier.fillMaxWidth().padding(horizontal = Sp.x16, vertical = Sp.x8),
            horizontalArrangement = Arrangement.spacedBy(Sp.x8),
        ) { n.children.forEach { RenderNode(it, surface, app) } }
    }
}

/** 设置行。`trailing` 是子节点树,所以不能直接用 LpCell(它只收字符串和开关)。 */
@Composable
internal fun SettingsRow(n: UiNode, m: Modifier, surface: String, app: AppState) {
    val dis = n.bool("disabled")
    val fn = n.fn("onPress")
    val long = longPressOf(n, surface, app)
    Row(
        m.fillMaxWidth().heightIn(min = Dim.tap)
            // 只声明了 onLongPress 的行也得能按 —— 挂不挂可点区域看的是两个回调,不是 onPress 一个
            .then(
                if (fn != null || long != null)
                    Modifier.plugPress(n.id, !dis, long) { fire(app, surface, fn) }
                else Modifier
            )
            .padding(horizontal = Sp.x16, vertical = Sp.x10),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Body(n.str("title").orEmpty(), maxLines = 2)
            n.str("description")?.let { Dim3(it, Modifier.padding(top = Sp.x2), maxLines = 2) }
        }
        n.children.forEach { RenderNode(it, surface, app) }
    }
}

/** 空态:一句话 + 一个去处按钮,不配插画(D416)。 */
@Composable
internal fun EmptyState(n: UiNode, m: Modifier, surface: String, app: AppState) {
    val act = n.props["action"] as? JsonObject
    val fn = (act?.get("onPress") as? JsonObject)?.get("\$fn")
        ?.let { (it as? JsonPrimitive)?.content?.toIntOrNull() }
    xyz.linplayer.app.ui.components.EmptyState(
        title = n.str("text").orEmpty(),
        actionLabel = act.str("title"),
        onAction = if (fn != null) ({ fire(app, surface, fn) }) else null,
        m = m,
    )
}

/** 下拉选择。multi 时回传数组,单选回传字符串(.d.ts 的 `string | string[]`)。 */
@Composable
internal fun PlugSelect(n: UiNode, m: Modifier, surface: String, app: AppState) {
    val opts = n.arrOf("options")
    val multi = n.bool("multi")
    val dis = n.bool("disabled")
    val picked = when (val v = n.props["value"]) {
        is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.content }
        is JsonPrimitive -> listOf(v.content)
        else -> emptyList()
    }
    var open by remember { mutableStateOf(false) }
    val label = opts.mapNotNull { it.obj() }
        .filter { it.str("value") in picked }
        .mapNotNull { it.str("label") }
        .joinToString(" · ").ifEmpty { "请选择" }
    Box(m) {
        Row(
            Modifier.clip(RoundedCornerShape(R.sm)).background(Lp.colors.s2)
                .plugPress(n.id, !dis) { open = true }
                .padding(horizontal = Sp.x12, vertical = Sp.x8),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Sp.x6),
        ) {
            Text(label, color = Lp.colors.fg, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            androidx.compose.material3.Icon(LpIcons.chevD, null, Modifier.size(16.dp), tint = Lp.colors.fg3)
        }
        DropdownMenu(open, { open = false }) {
            opts.forEach { e ->
                val o = e.obj()
                val v = o.str("value").orEmpty()
                DropdownMenuItem(
                    text = { Text(o.str("label") ?: v, color = if (v in picked) Lp.colors.acc else Lp.colors.fg) },
                    onClick = {
                        val next = if (!multi) listOf(v) else if (v in picked) picked - v else picked + v
                        fire(app, surface, n.fn("onChange"), if (multi) next else next.firstOrNull().orEmpty())
                        if (!multi) open = false
                    },
                )
            }
        }
    }
}

/** 滑块。拖动中就回报 —— 合帧是核心层的事(D135),壳这边压着不发会让插件预览不动。 */
@Composable
internal fun PlugSlider(n: UiNode, m: Modifier, surface: String, app: AppState) {
    val min = (n.num("min") ?: 0.0).toFloat()
    val max = (n.num("max") ?: 1.0).toFloat().coerceAtLeast(min + 0.0001f)
    val step = n.num("step") ?: 0.0
    var v by remember(n.id) { mutableStateOf((n.num("value") ?: 0.0).toFloat()) }
    // JS 那边改了值要跟上;拖动中的本地值不能被它顶掉,所以只认「和上次下发的不一样」
    val pushed = (n.num("value") ?: 0.0).toFloat()
    LaunchedEffect(pushed) { v = pushed }
    Slider(
        value = v.coerceIn(min, max),
        onValueChange = { nv -> v = nv; fire(app, surface, n.fn("onChange"), nv) },
        modifier = m.then(
            if (LocalPluginTv.current)
                Modifier.memo("plug.${n.id}", claimsInitialFocus(n.id))
            else Modifier
        ),
        enabled = !n.bool("disabled"),
        valueRange = min..max,
        steps = if (step > 0) ((max - min) / step).toInt() - 1 else 0,
    )
}

/**
 * 图标:`name` 走官方那套稳定名(D211 D549),`src` 走网络图。
 *
 * 认不出来的名字画成一块空位而不是随便挑一个图标 —— 挑错的那个会被当成对的。
 */
@Composable
internal fun PlugIcon(n: UiNode, m: Modifier) {
    val size = (n.num("size") ?: 22.0).dp
    val src = n.str("src")
    if (!src.isNullOrBlank()) {
        NetImage(src, n.str("a11yLabel"), m.size(size), corner = R.none)
        return
    }
    val v = iconByName(n.str("name"))
    if (v == null) Box(m.size(size))
    else androidx.compose.material3.Icon(v, null, m.size(size), tint = Lp.colors.fg2)
}

/**
 * `Button.variant` 四档 → 官方那颗按钮的 [BtnKind](.d.ts 第 1229 行)。
 *
 * 不在这里按 variant 挑不同控件:四档差的只是底色和前景色,[LpButton] 本来就按 kind 分好了。
 * 认不出来的 variant 落回 primary —— 主按钮画成次按钮比画成一块空白容易被发现。
 */
internal fun variantOf(v: String?): BtnKind = when (v) {
    "secondary" -> BtnKind.Secondary
    "ghost" -> BtnKind.Ghost
    "danger" -> BtnKind.Danger
    else -> BtnKind.Primary
}

/**
 * `Badge.tone` 四档(.d.ts 第 1268 行)→ 底色与字色。
 *
 * 字色跟着底色一起给:ok / warn / accent 三档的底色在深浅两套主题里明暗正好相反,
 * 只换底色的话浅色主题下会变成白字白底。
 */
@Composable
internal fun badgeTone(tone: String?): Pair<Color, Color> = with(Lp.colors) {
    when (tone) {
        "neutral" -> s3 to fg
        "ok" -> ok to accFg
        "warn" -> warn to accFg
        else -> acc to accFg
    }
}

internal fun iconByName(name: String?): androidx.compose.ui.graphics.vector.ImageVector? = when (name) {
    "home" -> LpIcons.home; "search" -> LpIcons.search; "settings" -> LpIcons.settings
    "back" -> LpIcons.back; "play" -> LpIcons.play; "pause" -> LpIcons.pause
    "heart" -> LpIcons.heart; "check" -> LpIcons.check; "download" -> LpIcons.download
    "more" -> LpIcons.more; "close" -> LpIcons.close; "list" -> LpIcons.list
    "grid" -> LpIcons.grid; "filter" -> LpIcons.filter; "sort" -> LpIcons.sort
    "sub" -> LpIcons.sub; "audio" -> LpIcons.audio; "line" -> LpIcons.line
    "danmaku" -> LpIcons.danmaku; "lock" -> LpIcons.lock; "server" -> LpIcons.server
    "cloud" -> LpIcons.cloud; "plugin" -> LpIcons.plugin; "calendar" -> LpIcons.calendar
    "trophy" -> LpIcons.trophy; "folder" -> LpIcons.folder; "file" -> LpIcons.file
    "trash" -> LpIcons.trash; "plus" -> LpIcons.plus; "minus" -> LpIcons.minus
    "refresh" -> LpIcons.refresh; "star" -> LpIcons.star; "info" -> LpIcons.info
    "image" -> LpIcons.image; "globe" -> LpIcons.globe; "sync" -> LpIcons.sync
    "sparkle" -> LpIcons.sparkle; "qr" -> LpIcons.qr; "chevR" -> LpIcons.chevR
    "chevD" -> LpIcons.chevD
    else -> null
}

/**
 * Markdown 小子集:标题 #~###、粗体、无序列表、行内代码、链接、段落。
 *
 * 只到这一层,再多的排版让插件自己用组件拼 —— 引一个 Markdown 库换来的是几百 KB
 * 和一整套我们管不了的排版规则。链接只上色不跳转:渲染器手里没有「打开外链」那条通路,
 * 画成能点的样子而点了没反应比不画更糟。
 */
@Composable
internal fun PlugMarkdown(n: UiNode, m: Modifier) {
    val c = Lp.colors
    Column(m.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Sp.x4)) {
        n.str("source").orEmpty().split("\n").forEach { raw ->
            val line = raw.trim()
            when {
                line.isEmpty() -> Spacer(Modifier.height(Sp.x6))
                line.startsWith("### ") -> Text(mdInline(line.drop(4), c.acc, c.s2), color = c.fg,
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                line.startsWith("## ") -> Text(mdInline(line.drop(3), c.acc, c.s2), color = c.fg,
                    fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                line.startsWith("# ") -> Text(mdInline(line.drop(2), c.acc, c.s2), color = c.fg,
                    fontSize = 20.sp, fontWeight = FontWeight.Bold)
                line.startsWith("- ") || line.startsWith("* ") -> Row(
                    horizontalArrangement = Arrangement.spacedBy(Sp.x8),
                ) {
                    Text("·", color = c.fg3, fontSize = 14.sp)
                    Text(mdInline(line.drop(2), c.acc, c.s2), color = c.fg2, fontSize = 14.sp, lineHeight = 21.sp)
                }
                else -> Text(mdInline(line, c.acc, c.s2), color = c.fg2, fontSize = 14.sp, lineHeight = 21.sp)
            }
        }
    }
}

private val mdInlineRe = Regex("""\*\*(.+?)\*\*|`([^`]+)`|\[([^]]+)]\(([^)]*)\)""")

private fun mdInline(s: String, link: Color, codeBg: Color): androidx.compose.ui.text.AnnotatedString =
    androidx.compose.ui.text.buildAnnotatedString {
        var i = 0
        for (mm in mdInlineRe.findAll(s)) {
            append(s.substring(i, mm.range.first))
            val g = mm.groups
            when {
                g[1] != null -> withStyle(
                    androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.SemiBold)
                ) { append(g[1]!!.value) }
                g[2] != null -> withStyle(
                    androidx.compose.ui.text.SpanStyle(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        background = codeBg,
                    )
                ) { append(g[2]!!.value) }
                else -> withStyle(
                    androidx.compose.ui.text.SpanStyle(color = link)
                ) { append(g[3]!!.value) }
            }
            i = mm.range.last + 1
        }
        append(s.substring(i))
    }

/** 这一端接不上的东西(SPEC 7.4.1)。**不画成像是能用的样子** —— 那比不画更糟。 */
@Composable
internal fun Unavailable(what: String, why: String, m: Modifier) {
    Column(
        m.fillMaxWidth().clip(RoundedCornerShape(R.md)).background(Lp.colors.s2).padding(Sp.x12),
        verticalArrangement = Arrangement.spacedBy(Sp.x4),
    ) {
        Body("$what 在这台机器上不可用")
        Dim3(why, maxLines = 3)
    }
}

/**
 * 内嵌网页(D269)。`injectScript` 在 onPageFinished 注入,页面用
 * `LinPlayer.post(字符串)` 回传消息。
 *
 * ☠ `loadUrl` 只能在地址真的变了的时候调:放在 update 里无条件调,每次重组都重新加载,
 * 表现是页面一直在闪而且永远滚回顶部。
 */
@Composable
internal fun PlugWebView(n: UiNode, m: Modifier, surface: String, app: AppState) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    if (!xyz.linplayer.app.plugin.WebShell.available(ctx)) {
        Unavailable("网页组件", "系统没有可用的 WebView 组件", m)
        return
    }
    val src = n.str("src").orEmpty()
    val opts = n.props["options"] as? JsonObject
    val inject = n.str("injectScript") ?: opts.str("injectScript")
    val onMessage = androidx.compose.runtime.rememberUpdatedState(n.fn("onMessage"))
    val h = (n.style().numOf("height") ?: 240.0).dp
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { c ->
            android.webkit.WebView(c).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                opts.str("userAgent")?.let { settings.userAgentString = it }
                webViewClient = object : android.webkit.WebViewClient() {
                    override fun onPageFinished(view: android.webkit.WebView, url: String?) {
                        inject?.let { view.evaluateJavascript(it, null) }
                    }
                }
                addJavascriptInterface(WebBridge { d ->
                    fire(app, surface, onMessage.value, parseJson(d))
                }, "LinPlayer")
            }
        },
        modifier = m.fillMaxWidth().height(h),
        update = { w -> if (w.tag != src) { w.tag = src; if (src.isNotEmpty()) w.loadUrl(src) } },
    )
}

/** 具名类而不是匿名对象:`@JavascriptInterface` 的方法只有 JS 调,R8 得看得见它保谁。 */
internal class WebBridge(private val onPost: (String) -> Unit) {
    @android.webkit.JavascriptInterface
    fun post(data: String) = onPost(data)
}

private fun parseJson(s: String): JsonElement =
    runCatching { kotlinx.serialization.json.Json.parseToJsonElement(s) }.getOrElse { JsonPrimitive(s) }

/**
 * 内嵌播放器(D268 D276)。
 *
 * 全局只有一个 mpv 实例,把视频层定位到某一块区域这件事还是个未完成的 spike ——
 * 所以这里画的是占位,不是播放器。它必须是**认领过**的分支:画成「需要更新 LinPlayer」
 * 会让人以为是版本差,而这是这一端确实还没做。
 */
@Composable
internal fun PlugPlayer(n: UiNode, m: Modifier) {
    Unavailable(
        "内嵌播放器",
        "视频层跟随区域还没做;先用 nav 跳官方播放页",
        m.height((n.style().numOf("height") ?: 200.0).dp),
    )
}
