package xyz.linplayer.app.ui.plugin

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.JsonObject
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.tv.memo
import xyz.linplayer.app.ui.components.NetImage
import xyz.linplayer.app.ui.components.pressable
import xyz.linplayer.app.ui.theme.Lp

/**
 * 节点 → Compose(SPEC 7.4 组件表、7.5 样式子集)。
 *
 * 渲染器不认识组件语义,只认类型名:加一个组件在 [RenderNode] 的 when 里加一行,
 * 加一个样式属性在 [styleOf] 里加一行。业务在插件那边,不在这里。
 *
 * 未知组件画占位、未知属性直接忽略(D319):老宿主遇到新组件时用户看到的是
 * 「这一块需要更新 LinPlayer」,而不是整页崩掉或者一片空白。
 */
/**
 * TV 形态开关。
 *
 * ☠ TV 上**不可聚焦的元素等于不存在**:遥控器进不去,整页没有落点时方向键直接失灵
 * (TvNav.kt 顶上那条「最经典的 P0」)。所以交互组件在 TV 上换成 TV 套件的那一份,
 * 它们自带焦点态与放大。判据不是「长得像 TV」,是「遥控器点得到」。
 */
val LocalPluginTv = androidx.compose.runtime.staticCompositionLocalOf { false }

@Composable
internal fun RenderNode(n: UiNode, surface: String, app: AppState) {
    val m = styleOf(n)
    val tv = LocalPluginTv.current
    if (tv && renderTv(n, m, surface, app)) return
    when (n.type) {
        "#root", "View", "Column", "SettingsGroup" -> Stack(n, m, surface, app, row = n.style().dirRow())
        "Row", "ChipGroup" -> Stack(n, m, surface, app, row = true, wrapScroll = n.type == "ChipGroup")
        "Stack" -> Box(m) { n.children.forEach { RenderNode(it, surface, app) } }
        "ScrollView" -> Column(m.verticalScroll(rememberScrollState())) {
            n.children.forEach { RenderNode(it, surface, app) }
        }
        "Text" -> PluginText(n, m)
        "Image" -> NetImage(n.str("src").orEmpty(), null, m)
        "Divider" -> Box(m.fillMaxWidth().height(1.dp).background(Lp.colors.line))
        "Spinner" -> androidx.compose.material3.CircularProgressIndicator(m.size(22.dp))
        "Skeleton" -> xyz.linplayer.app.ui.components.Skeleton(m.fillMaxWidth().height(56.dp))
        "Button" -> xyz.linplayer.app.ui.components.LpButton(
            n.str("title") ?: n.str("label") ?: textOf(n),
            { fire(app, surface, n.fn("onPress")) },
            m,
            enabled = !n.bool("disabled"),
        )
        "Pressable" -> Box(m.pressable({ fire(app, surface, n.fn("onPress")) })) {
            n.children.forEach { RenderNode(it, surface, app) }
        }
        "TextInput" -> xyz.linplayer.app.ui.components.LpField(
            n.str("defaultValue").orEmpty(), { fire(app, surface, n.fn("onChangeText"), it) },
            n.str("placeholder").orEmpty(), m.fillMaxWidth(),
            password = n.bool("secret"),
        )
        "Switch", "Checkbox" -> {
            val on = n.bool("value") || n.bool("checked")
            androidx.compose.material3.Switch(on, { v -> fire(app, surface, n.fn("onChange") ?: n.fn("onToggle"), v) }, m)
        }
        "Chip" -> xyz.linplayer.app.ui.components.ToneChip(
            n.str("label") ?: textOf(n), on = n.bool("selected"), m,
        ) { fire(app, surface, n.fn("onPress")) }
        "Badge" -> Box(m.clip(RoundedCornerShape(999.dp)).background(Lp.colors.acc).padding(horizontal = 6.dp, vertical = 2.dp)) {
            Text(n.str("label") ?: textOf(n), color = Lp.colors.accFg, fontSize = 11.sp)
        }
        "VirtualList", "VirtualGrid" -> VirtualList(n, m, surface, app)
        "ProgressBar" -> androidx.compose.material3.LinearProgressIndicator(
            progress = { (n.num("value") ?: 0.0).toFloat() }, modifier = m.fillMaxWidth(),
        )
        else -> UnknownComponent(n.type, m)
    }
}

@Composable
private fun Stack(n: UiNode, m: Modifier, surface: String, app: AppState, row: Boolean, wrapScroll: Boolean = false) {
    val s = n.style()
    val gap = (s.numOf("gap") ?: 0.0).dp
    if (row) {
        var mm = if (wrapScroll) m.horizontalScroll(rememberScrollState()) else m
        // ☠ SpaceBetween 这几档**不撑满就等于没写**:Row 默认按内容收窄,
        //   标签和值会挨在一起,而且不报错(桌面那边 FlexPanel 自己算,没这个坑)
        if (s.strOf("justify") in listOf("between", "around", "evenly")) mm = mm.fillMaxWidth()
        Row(mm, horizontalArrangement = rowArrange(s, gap), verticalAlignment = crossAlignRow(s)) {
            n.children.forEach { c -> GrowBox(c) { RenderNode(c, surface, app) } }
        }
    } else {
        Column(m, verticalArrangement = colArrange(s, gap), horizontalAlignment = crossAlignCol(s)) {
            n.children.forEach { c -> RenderNode(c, surface, app) }
        }
    }
}

/** grow 在 Compose 里是 weight,而 weight 只能在 Row/Column 作用域里给。 */
@Composable
private fun RowScope.GrowBox(n: UiNode, content: @Composable () -> Unit) {
    val g = n.style().numOf("grow") ?: 0.0
    if (g > 0) Box(Modifier.weight(g.toFloat())) { content() } else content()
}

@Composable
private fun PluginText(n: UiNode, m: Modifier) {
    val s = n.style()
    Text(
        textOf(n),
        m,
        color = colorOf(s, "color") ?: Lp.colors.fg,
        fontSize = (s.numOf("fontSize") ?: 14.0).sp,
        fontWeight = if (s.strOf("fontWeight") in listOf("bold", "600", "700")) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = (s.numOf("maxLines") ?: Int.MAX_VALUE.toDouble()).toInt().coerceAtLeast(1),
        overflow = TextOverflow.Ellipsis,
        textAlign = when (s.strOf("textAlign")) {
            "center" -> TextAlign.Center; "end" -> TextAlign.End; else -> TextAlign.Start
        },
    )
}

/** 一个节点的文字 = 它自己的 text 加上所有 #text 子节点,按顺序拼。 */
private fun textOf(n: UiNode): String =
    n.text + n.children.filter { it.type == "#text" }.joinToString("") { it.text }

@Composable
private fun UnknownComponent(type: String, m: Modifier) {
    Box(m.clip(RoundedCornerShape(6.dp)).background(Lp.colors.s2).padding(horizontal = 10.dp, vertical = 6.dp)) {
        Text("这一块需要更新 LinPlayer($type)", color = Lp.colors.fg2, fontSize = 12.sp)
    }
}

// ---------------------------------------------------------------- 样式(SPEC 7.5)

internal fun JsonObject?.numOf(k: String): Double? =
    (this?.get(k) as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()

internal fun JsonObject?.strOf(k: String): String? =
    (this?.get(k) as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject?.dirRow(): Boolean = this.strOf("direction") == "row"

/** `token:名字` 走主题(D89),`#rrggbb` 直接解析;认不出来就不上色。 */
@Composable
private fun colorOf(s: JsonObject?, k: String): Color? {
    val v = s.strOf(k) ?: return null
    if (v.startsWith("token:")) return when (v.removePrefix("token:")) {
        "Accent", "acc" -> Lp.colors.acc
        "Ink", "fg" -> Lp.colors.fg
        "Ink2", "fg2" -> Lp.colors.fg2
        "Ink3", "fg3" -> Lp.colors.fg3
        "PanelAlt", "s2" -> Lp.colors.s2
        "Line", "line" -> Lp.colors.line
        else -> null
    }
    return runCatching { Color(android.graphics.Color.parseColor(v)) }.getOrNull()
}

@Composable
private fun styleOf(n: UiNode): Modifier {
    val s = n.style() ?: return Modifier
    var m: Modifier = Modifier
    s.numOf("width")?.let { m = m.width(it.dp) }
    s.numOf("height")?.let { m = m.height(it.dp) }
    s.numOf("radius")?.let { m = m.clip(RoundedCornerShape(it.dp)) }
    colorOf(s, "background")?.let { m = m.background(it) }
    // padding 要在 background 之后:反过来的话底色只铺内容那一块,留白处是透的
    edge(s, "padding")?.let { m = m.padding(it) }
    edge(s, "margin")?.let { m = m.padding(it) }
    s.numOf("opacity")?.let { m = m.alpha(it.toFloat()) }
    return m
}

/** padding / margin:给一个数是四边,给 *Top 这类单独覆盖那一边。 */
private fun edge(s: JsonObject?, prefix: String): PaddingValues? {
    val all = s.numOf(prefix)
    val t = s.numOf(prefix + "Top")
    val r = s.numOf(prefix + "Right")
    val b = s.numOf(prefix + "Bottom")
    val l = s.numOf(prefix + "Left")
    if (all == null && t == null && r == null && b == null && l == null) return null
    val base = all ?: 0.0
    return PaddingValues((l ?: base).dp, (t ?: base).dp, (r ?: base).dp, (b ?: base).dp)
}

private fun rowArrange(s: JsonObject?, gap: androidx.compose.ui.unit.Dp): Arrangement.Horizontal =
    when (s.strOf("justify")) {
        "center" -> Arrangement.Center
        "end" -> Arrangement.End
        "between" -> Arrangement.SpaceBetween
        "around" -> Arrangement.SpaceAround
        "evenly" -> Arrangement.SpaceEvenly
        else -> Arrangement.spacedBy(gap)
    }

private fun colArrange(s: JsonObject?, gap: androidx.compose.ui.unit.Dp): Arrangement.Vertical =
    when (s.strOf("justify")) {
        "center" -> Arrangement.Center
        "end" -> Arrangement.Bottom
        "between" -> Arrangement.SpaceBetween
        "around" -> Arrangement.SpaceAround
        "evenly" -> Arrangement.SpaceEvenly
        else -> Arrangement.spacedBy(gap)
    }

private fun crossAlignRow(s: JsonObject?): Alignment.Vertical = when (s.strOf("align")) {
    "center" -> Alignment.CenterVertically
    "end" -> Alignment.Bottom
    else -> Alignment.Top
}

private fun crossAlignCol(s: JsonObject?): Alignment.Horizontal = when (s.strOf("align")) {
    "center" -> Alignment.CenterHorizontally
    "end" -> Alignment.End
    else -> Alignment.Start
}


/**
 * 大列表(D134):壳只画**可见范围**,并把范围回报给 JS,JS 只渲染那一窗。
 *
 * ★ `firstIndex` 是 JS 那一窗的起点:children 的第 k 个对应第 `firstIndex + k` 项。
 *   壳照着它把 LazyColumn 的 item 下标对上 —— 对不上就是「滑着滑着内容错位」。
 * ☠ 回报范围要**多给一屏的余量**:正好按可见范围要的话,用户一滑就看见空白,
 *   因为 JS 渲染 + 帧合批 + 事件回传这一圈是有延迟的。
 */
@Composable
private fun VirtualList(n: UiNode, m: Modifier, surface: String, app: AppState) {
    val total = (n.num("itemCount") ?: 0.0).toInt()
    val first = (n.num("firstIndex") ?: 0.0).toInt()
    val state = androidx.compose.foundation.lazy.rememberLazyListState()
    val gap = (n.style().numOf("gap") ?: 0.0).dp

    // 可见范围变了就回报一次(带余量)。节流到「范围真的变了」那一刻,不是每帧
    val window = 12
    androidx.compose.runtime.LaunchedEffect(state, total) {
        androidx.compose.runtime.snapshotFlow {
            val info = state.layoutInfo.visibleItemsInfo
            if (info.isEmpty()) 0 to minOf(total, 24)
            else (info.first().index - window).coerceAtLeast(0) to (info.last().index + window + 1).coerceAtMost(total)
        }.collect { (from, to) ->
            fire(app, surface, n.fn("onRange"), mapOf("from" to from, "to" to to))
            if (to >= total && total > 0) fire(app, surface, n.fn("onEndReached"))
        }
    }

    androidx.compose.foundation.lazy.LazyColumn(m, state, verticalArrangement = Arrangement.spacedBy(gap)) {
        items(total, key = { it }) { i ->
            val k = i - first
            // 窗口外的项 JS 还没给:占位撑住高度,否则滚动条会在数据补上来时乱跳
            if (k in n.children.indices) RenderNode(n.children[k], surface, app)
            else Box(Modifier.fillMaxWidth().height(((n.num("itemHeight") ?: 56.0)).dp))
        }
    }
}


/**
 * TV 形态下的交互组件。返回 true = 这一类已经画过了,不用再走通用那条。
 *
 * 只覆盖**需要焦点**的那几种;纯展示的(Text / Image / Divider)两端共用一份。
 */
@Composable
private fun renderTv(n: UiNode, m: Modifier, surface: String, app: AppState): Boolean {
    val key = "plug.${n.id}"
    when (n.type) {
        "Button" -> xyz.linplayer.app.tv.kit.TvButton(
            n.str("title") ?: n.str("label") ?: textOfNode(n),
            modifier = m.memo(key),
        ) { fire(app, surface, n.fn("onPress")) }
        "Chip" -> xyz.linplayer.app.tv.kit.TvButton(
            n.str("label") ?: textOfNode(n),
            modifier = m.memo(key),
        ) { fire(app, surface, n.fn("onPress")) }
        "Pressable" -> xyz.linplayer.app.tv.kit.TvButton("", modifier = m.memo(key)) {
            fire(app, surface, n.fn("onPress"))
        }
        "Switch", "Checkbox" -> {
            /* ☠ 别映射成 PanelItem:那是**整行**的设置项,而插件常把开关摆在一行里,
               整行会把旁边的文字挤成一列竖字。这里要的只是「一个能聚焦、按了会翻」的东西。 */
            val on = n.bool("value") || n.bool("checked")
            xyz.linplayer.app.tv.kit.TvButton(
                listOfNotNull(n.str("label"), if (on) "开" else "关").joinToString(" "),
                modifier = m.memo(key),
            ) { fire(app, surface, n.fn("onChange") ?: n.fn("onToggle"), !on) }
        }
        else -> return false
    }
    return true
}

internal fun textOfNode(n: UiNode): String =
    n.text + n.children.filter { it.type == "#text" }.joinToString("") { it.text }
