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
@Composable
internal fun RenderNode(n: UiNode, surface: String, app: AppState) {
    val m = styleOf(n)
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
