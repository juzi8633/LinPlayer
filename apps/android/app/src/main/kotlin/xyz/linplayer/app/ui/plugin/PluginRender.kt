package xyz.linplayer.app.ui.plugin

import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.JsonObject
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.tv.memo
import xyz.linplayer.app.ui.components.BtnKind
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

/** 插件没给高度时,可滚动容器兜的那一档。要别的自己写 `style.height`。 */
private val ScrollFallbackH = 360.dp

/**
 * 这一块 UI 里**第一个**可交互元素的节点 id。
 *
 * ☠ 进插件页时焦点必须有落点,否则遥控器整页失灵 —— 而这件事在静态截图上
 * 完全看不出来(按钮画得好好的,只是按不到)。谁先渲染谁认领,认领过就不再变:
 * 重渲染时焦点不许被抢回第一个元素上(用户可能已经挪走了)。
 */
/** key → FocusRequester:`nextFocusUp` 这几条指的是 key,移焦时要按 key 查回来(SPEC 7.8)。 */
internal val LocalPluginFocusKeys =
    androidx.compose.runtime.staticCompositionLocalOf<
        androidx.compose.runtime.snapshots.SnapshotStateMap<String, androidx.compose.ui.focus.FocusRequester>
        > { androidx.compose.runtime.mutableStateMapOf() }

internal val LocalPluginFirstFocus =
    androidx.compose.runtime.staticCompositionLocalOf<androidx.compose.runtime.MutableState<Int>?> { null }

/** 焦点记忆键的前缀,每个 surface 一份(见 PluginSurface 那条)。 */
internal val LocalPluginKeyNs = androidx.compose.runtime.staticCompositionLocalOf { "" }

/** 插件节点的焦点记忆键。拼前缀是为了让同一页上的两块插件不撞键。 */
@Composable
internal fun plugKey(vararg parts: Any): String =
    LocalPluginKeyNs.current + "plug." + parts.joinToString(".")

/** 这个节点要不要吃初始焦点。 */
@Composable
internal fun claimsInitialFocus(id: Int): Boolean {
    val slot = LocalPluginFirstFocus.current ?: return false
    if (slot.value == 0) slot.value = id
    return slot.value == id
}

@Composable
internal fun RenderNode(n: UiNode, surface: String, app: AppState) {
    // PressProps 的 onFocus/onBlur 在这一处接完:TV 上移焦是主交互,漏一个组件就是一块哑区
    val m = styleOf(n).a11y(n).focusOf(n).plugFocusEvents(n, surface, app)
    val tv = LocalPluginTv.current
    if (tv && renderTv(n, m, surface, app)) return
    when (n.type) {
        "#root", "View", "Column", "SettingsGroup" -> Stack(n, m, surface, app, row = n.style().dirRow())
        "Row", "ChipGroup" -> Stack(n, m, surface, app, row = true, wrapScroll = n.type == "ChipGroup")
        "Stack" -> Box(m) { n.children.forEach { RenderNode(it, surface, app) } }
        /* ☠ 和 VirtualList 同一条:高度无界的可滚动容器 Compose 是**当场抛异常**而不是画不出来,
           而插件页宿主本身就是个 LazyColumn —— 插件不写 style.height 就必崩。 */
        "ScrollView" -> if (n.bool("horizontal")) {
            Row(m.horizontalScroll(rememberScrollState())) {
                n.children.forEach { RenderNode(it, surface, app) }
            }
        } else {
            val bounded = if (n.style().numOf("height") != null) m else m.height(ScrollFallbackH)
            Column(bounded.verticalScroll(rememberScrollState())) {
                n.children.forEach { RenderNode(it, surface, app) }
            }
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
            kind = variantOf(n.str("variant")),
            enabled = !n.bool("disabled"),
            // 图标走官方稳定名那张表(D211 D549),和 Icon 组件同一份 —— 名字对不上就只剩文字
            icon = iconByName(n.str("icon")),
            onLongClick = longPressOf(n, surface, app),
        )
        /* disabled 是**所有**可交互组件的属性(.d.ts 的 BaseProps 一层),不是 Button 专有。
           只压暗不断回调等于「看着点不了、其实点得动」—— 那比没有禁用更糟。 */
        "Pressable" -> Box(
            m.pressable(
                { fire(app, surface, n.fn("onPress")) },
                longPressOf(n, surface, app),
                enabled = !n.bool("disabled"),
            )
        ) { n.children.forEach { RenderNode(it, surface, app) } }
        "TextInput" -> PlugTextInput(n, m, surface, app)
        "Switch", "Checkbox" -> {
            val on = n.bool("value") || n.bool("checked")
            androidx.compose.material3.Switch(
                on, { v -> fire(app, surface, n.fn("onChange") ?: n.fn("onToggle"), v) }, m,
                enabled = !n.bool("disabled"),
            )
        }
        "Chip" -> xyz.linplayer.app.ui.components.ToneChip(
            n.str("label") ?: textOf(n), on = n.bool("selected"), m,
            onLongClick = longPressOf(n, surface, app),
        ) { if (!n.bool("disabled")) fire(app, surface, n.fn("onPress")) }
        "Badge" -> {
            val (bg, fg) = badgeTone(n.str("tone"))
            Box(m.clip(RoundedCornerShape(999.dp)).background(bg).padding(horizontal = 6.dp, vertical = 2.dp)) {
                Text(n.str("text") ?: n.str("label") ?: textOf(n), color = fg, fontSize = 11.sp)
            }
        }
        "Canvas" -> PluginCanvas(n, m)
        "VirtualList", "VirtualGrid" -> VirtualList(n, m, surface, app)
        "ProgressBar" -> androidx.compose.material3.LinearProgressIndicator(
            progress = { (n.num("value") ?: 0.0).toFloat() }, modifier = m.fillMaxWidth(),
        )
        "PosterCard" -> PosterCard(
            n.id, 0, m, n.props["item"] as? JsonObject, n.str("shape"),
            { if (!n.bool("disabled")) fire(app, surface, n.fn("onPress")) },
            showRemarks = n.bool("showRemarks"), progress = n.num("progress") ?: 0.0,
            onLongPress = longPressOf(n, surface, app),
        )
        "PosterRow" -> PosterRow(n, m, surface, app)
        "PosterGrid" -> PosterGrid(n, m, surface, app)
        "EpisodeGrid" -> EpisodeGrid(n, m, surface, app)
        "Tabs" -> PlugTabs(n, m, surface, app, lines = false)
        "LineTabs" -> PlugTabs(n, m, surface, app, lines = true)
        "FilterPanel" -> FilterPanel(n, m, surface, app)
        "RatingList" -> RatingList(n, m)
        "ServerCard" -> ServerCard(n, m, surface, app)
        "DetailHeader" -> DetailHeader(n, m, surface, app)
        "SettingsRow" -> SettingsRow(n, m, surface, app)
        "EmptyState" -> EmptyState(n, m, surface, app)
        "Select" -> PlugSelect(n, m, surface, app)
        "Slider" -> PlugSlider(n, m, surface, app)
        "Icon" -> PlugIcon(n, m)
        "Markdown" -> PlugMarkdown(n, m)
        "WebView" -> PlugWebView(n, m, surface, app)
        /* 视频层跟随区域还是未完成的 spike(D268),所以这里画的是「不可用」。
           但它必须**认领**这个类型:落进 UnknownComponent 的意思是「老宿主遇到新组件」,
           和「这一端确实还没做」是两回事,而两者在截图上长得一模一样。 */
        "Player" -> PlugPlayer(n, m)
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
            n.children.forEach { c -> GrowBox(c) { RenderNode(c, surface, app) } }
        }
    }
}

/**
 * grow 在 Compose 里是 weight,而 weight 只能在 Row/Column 作用域里给 ——
 * 所以两个作用域各要一份。**少写一份的表现是那个方向上 grow 静默失效**,不报错。
 */
@Composable
private fun RowScope.GrowBox(n: UiNode, content: @Composable () -> Unit) {
    val g = n.style().numOf("grow") ?: 0.0
    if (g > 0) Box(Modifier.weight(g.toFloat())) { content() } else content()
}

@Composable
private fun ColumnScope.GrowBox(n: UiNode, content: @Composable () -> Unit) {
    val g = n.style().numOf("grow") ?: 0.0
    if (g > 0) Box(Modifier.weight(g.toFloat())) { content() } else content()
}

@Composable
private fun PluginText(n: UiNode, m: Modifier) {
    // selectable:长按选中 + 复制。整棵树包一层 SelectionContainer 不行 ——
    // 那样没声明的文字也跟着能选,长按手势还会被它吃掉,卡片的长按菜单就打不开了
    if (n.bool("selectable")) {
        androidx.compose.foundation.text.selection.SelectionContainer { PlainText(n, m) }
        return
    }
    PlainText(n, m)
}

@Composable
private fun PlainText(n: UiNode, m: Modifier) {
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

/* 未知组件是**版本差**,不是错误,所以画占位而不是崩(D319)。
   但它必须留下一条日志:三端里只要有一端把某个组件降级成占位,
   「三端示例页长得一样」这句话就不成立了,而占位块小得不容易在截图上发现。 */
@Composable
private fun UnknownComponent(type: String, m: Modifier) {
    androidx.compose.runtime.LaunchedEffect(type) {
        xyz.linplayer.app.core.Logs.w("插件UI", "未知组件 $type —— 这一端把它降级成了占位")
    }
    Box(m.clip(RoundedCornerShape(6.dp)).background(Lp.colors.s2).padding(horizontal = 10.dp, vertical = 6.dp)) {
        Text("这一块需要更新 LinPlayer($type)", color = Lp.colors.fg2, fontSize = 12.sp)
    }
}

// ---------------------------------------------------------------- 样式(SPEC 7.5)

/** 取一个长度值。数字直接用,`token:名字` 查 SPEC 20.4 那张表(D556)。 */
internal fun JsonObject?.numOf(k: String): Double? {
    val p = this?.get(k) as? kotlinx.serialization.json.JsonPrimitive ?: return null
    p.content.toDoubleOrNull()?.let { return it }
    if (p.isString && p.content.startsWith("token:")) return tokenNumber(p.content.removePrefix("token:"))
    return null
}

internal fun JsonObject?.strOf(k: String): String? =
    (this?.get(k) as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject?.dirRow(): Boolean = this.strOf("direction") == "row"

/**
 * `token:名字` 走主题(D89 D556),`#rrggbb` 直接解析;认不出来就不上色。
 *
 * 名字是 **SPEC 20.4 的那一套**(`color.accent`),和 `plugin.setEnv` 报上去的
 * 是同一张表 —— 两处各写一份的话,改一个名字就有一处会悄悄失效,
 * 而失效的表现是「那段文字用了默认色」,没人会发现。
 */
@Composable
internal fun tokenColor(name: String): Color? = when (name) {
    "color.bg" -> Lp.colors.bg
    "color.surface" -> Lp.colors.s1
    "color.surfaceAlt", "PanelAlt", "s2" -> Lp.colors.s2
    "color.ink", "Ink", "fg" -> Lp.colors.fg
    "color.ink2", "Ink2", "fg2" -> Lp.colors.fg2
    "color.ink3", "Ink3", "fg3" -> Lp.colors.fg3
    "color.line", "Line", "line" -> Lp.colors.line
    "color.lineStrong" -> Lp.colors.line2
    "color.accent", "Accent", "acc" -> Lp.colors.acc
    "color.accentInk" -> Lp.colors.accFg
    "color.accentSoft" -> Lp.colors.accDim
    "color.ok" -> Lp.colors.ok
    "color.warn" -> Lp.colors.warn
    "color.danger" -> Lp.colors.bad
    else -> null
}

/** SPEC 20.4 的 token 名。解 `token:名字` 和报给核心层用的是**同一张**表(D556)。 */
internal val TOKEN_COLOR_NAMES = listOf(
    "color.bg", "color.surface", "color.surfaceAlt",
    "color.ink", "color.ink2", "color.ink3",
    "color.line", "color.lineStrong",
    "color.accent", "color.accentInk", "color.accentSoft",
    "color.ok", "color.warn", "color.danger",
)

internal val TOKEN_NUMBER_NAMES = listOf(
    "radius.small", "radius.card", "radius.pill",
    "space.xs", "space.sm", "space.md", "space.lg", "space.xl",
    "font.size.body", "font.size.title", "font.size.h1",
    "motion.duration.fast", "motion.duration.normal",
)

/** 数值 token(`radius: 'token:radius.card'`,SPEC 7.5 的原例)。刻度见 UI_MOBILE.md §1.3。 */
internal fun tokenNumber(name: String): Double? =
    xyz.linplayer.app.ui.theme.PluginTheme.number(name) ?: when (name) {
    "radius.small" -> 6.0; "radius.card" -> 10.0; "radius.pill" -> 999.0
    "space.xs" -> 2.0; "space.sm" -> 6.0; "space.md" -> 10.0; "space.lg" -> 14.0; "space.xl" -> 18.0
    "font.size.body" -> 14.0; "font.size.title" -> 18.0; "font.size.h1" -> 26.0
    "motion.duration.fast" -> 120.0; "motion.duration.normal" -> 220.0
    else -> null
}

@Composable
private fun colorOf(s: JsonObject?, k: String): Color? {
    val v = s.strOf(k) ?: return null
    if (v.startsWith("token:")) return tokenColor(v.removePrefix("token:"))
    return runCatching { Color(android.graphics.Color.parseColor(v)) }.getOrNull()
}

@Composable
private fun styleOf(n: UiNode): Modifier {
    val s = n.style()
    val dim = n.bool("disabled")
    if (s == null) return if (dim) Modifier.alpha(DisabledAlpha) else Modifier
    val a = motionOf(s)
    var m: Modifier = Modifier
    (a.width ?: s.numOf("width")?.toFloat())?.let { m = m.width(it.dp) }
    (a.height ?: s.numOf("height")?.toFloat())?.let { m = m.height(it.dp) }
    /* 透明度与变换合并成**一层** graphicsLayer,并且排在 background 之前:
       排在后面的话 alpha 只罩住内容,底色仍然是实的 —— 半透明的块看上去像没生效。 */
    if (a.layered) m = m.graphicsLayer {
        alpha = a.opacity
        translationX = a.tx.dp.toPx(); translationY = a.ty.dp.toPx()
        scaleX = a.scale; scaleY = a.scale
        rotationZ = a.rotate
    }
    s.numOf("radius")?.let { m = m.clip(RoundedCornerShape(it.dp)) }
    colorOf(s, "background")?.let { m = m.background(it) }
    // padding 要在 background 之后:反过来的话底色只铺内容那一块,留白处是透的
    edge(s, "padding")?.let { m = m.padding(it) }
    edge(s, "margin")?.let { m = m.padding(it) }
    // disabled 对**所有**可交互组件生效,不只是 Button:压暗在这里做一次
    if (dim) m = m.alpha(DisabledAlpha)
    return m
}

/** 动效跑到这一帧的值(SPEC 7.6)。 */
private class Motion(
    val opacity: Float, val tx: Float, val ty: Float, val scale: Float, val rotate: Float,
    val width: Float?, val height: Float?, val layered: Boolean,
)

private fun JsonObject?.fl(k: String, d: Float): Float = (this.numOf(k) ?: d.toDouble()).toFloat()

private fun easingOf(name: String?): androidx.compose.animation.core.Easing = when (name) {
    "decelerate" -> xyz.linplayer.app.ui.theme.LpEasing.standardDecelerate
    "accelerate" -> xyz.linplayer.app.ui.theme.LpEasing.standardAccelerate
    "linear" -> androidx.compose.animation.core.LinearEasing
    else -> xyz.linplayer.app.ui.theme.LpEasing.standard
}

/**
 * `transition`:属性一变原生端自己插值,不过 JS 桥(SPEC 7.6)。
 * `animation`:关键帧按等距停靠点插值。
 *
 * ☠ D428 —— 系统开了「减少动态效果」(动画时长倍率 0)时两者**直接跳到终态**:
 * transition 的时长乘 0,关键帧直接取最后一帧。不是「跑快一点」,是不跑。
 */
@Composable
private fun motionOf(s: JsonObject?): Motion {
    val ms = xyz.linplayer.app.ui.theme.LocalMotionScale.current
    val base = Motion(
        s.fl("opacity", 1f), s.fl("translateX", 0f), s.fl("translateY", 0f),
        s.fl("scale", 1f), s.fl("rotate", 0f),
        s.numOf("width")?.toFloat(), s.numOf("height")?.toFloat(),
        layered = s.numOf("opacity") != null || s.numOf("translateX") != null ||
            s.numOf("translateY") != null || s.numOf("scale") != null || s.numOf("rotate") != null,
    )
    (s?.get("animation") as? JsonObject)?.let { return keyframed(it, base, ms) }
    val tr = s?.get("transition") as? JsonObject ?: return base
    val props = (tr["props"] as? kotlinx.serialization.json.JsonArray)
        .orEmpty().mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.toSet()
    val spec = androidx.compose.animation.core.tween<Float>(
        ((tr.numOf("duration") ?: 0.0) * ms).toInt().coerceAtLeast(0),
        ((tr.numOf("delay") ?: 0.0) * ms).toInt().coerceAtLeast(0),
        easingOf(tr.strOf("easing")),
    )
    val op by androidx.compose.animation.core.animateFloatAsState(base.opacity, spec, label = "op")
    val tx by androidx.compose.animation.core.animateFloatAsState(base.tx, spec, label = "tx")
    val ty by androidx.compose.animation.core.animateFloatAsState(base.ty, spec, label = "ty")
    val sc by androidx.compose.animation.core.animateFloatAsState(base.scale, spec, label = "sc")
    val ro by androidx.compose.animation.core.animateFloatAsState(base.rotate, spec, label = "ro")
    val w by androidx.compose.animation.core.animateFloatAsState(base.width ?: 0f, spec, label = "w")
    val h by androidx.compose.animation.core.animateFloatAsState(base.height ?: 0f, spec, label = "h")
    fun <T> pick(k: String, animated: T, still: T) = if (k in props) animated else still
    return Motion(
        pick("opacity", op, base.opacity), pick("translateX", tx, base.tx),
        pick("translateY", ty, base.ty), pick("scale", sc, base.scale),
        pick("rotate", ro, base.rotate),
        base.width?.let { pick("width", w, it) }, base.height?.let { pick("height", h, it) },
        layered = true,
    )
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

    /* ☠ 插件没给高度时必须兜一个:可滚动容器嵌在可滚动容器里、高度无界,
       Compose 是**当场抛异常**而不是画不出来。插件页宿主本身就是个 LazyColumn,
       所以这条一定会被踩到 —— 兜 360dp,插件想要别的自己写 style.height。 */
    val bounded = if (n.style().numOf("height") != null) m else m.height(ScrollFallbackH)
    val tv = LocalPluginTv.current
    androidx.compose.foundation.lazy.LazyColumn(bounded, state, verticalArrangement = Arrangement.spacedBy(gap)) {
        items(total, key = { it }) { i ->
            val k = i - first
            // 窗口外的项 JS 还没给:占位撑住高度,否则滚动条会在数据补上来时乱跳
            val c = n.children.getOrNull(k)
            when {
                c == null -> Box(Modifier.fillMaxWidth().height(((n.num("itemHeight") ?: 56.0)).dp))
                tv && !hasInteractive(c) -> TvListRow(n.id, i) { RenderNode(c, surface, app) }
                else -> RenderNode(c, surface, app)
            }
        }
    }
}

/* ☠ 纯展示的项在 TV 上**等于不存在**:整页没有落点,遥控器连进都进不去,
   一千项一行也滚不动 —— 而截图里列表画得整整齐齐。2026-09-20 量帧率时撞到:
   80 次下键只产生 10 帧,焦点全程停在侧边栏。项里自带交互元素的交给它自己,
   不要套两层焦点。 */
@Composable
private fun TvListRow(listId: Int, index: Int, content: @Composable () -> Unit) {
    val src = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val on by src.collectIsFocusedAsState()
    Box(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (on) Color.White.copy(alpha = 0.10f) else Color.Transparent)
            .memo(plugKey("list", listId, index), index == 0 && claimsInitialFocus(listId))
            .focusable(interactionSource = src)
    ) { content() }
}

private val interactiveTypes = listOf("Button", "Chip", "Pressable", "Switch", "Checkbox", "TextInput")

/** 子树里有没有能吃焦点的东西。 */
private fun hasInteractive(n: UiNode): Boolean =
    n.type in interactiveTypes || n.children.any { hasInteractive(it) }


/**
 * TV 形态下的交互组件。返回 true = 这一类已经画过了,不用再走通用那条。
 *
 * 只覆盖**需要焦点**的那几种;纯展示的(Text / Image / Divider)两端共用一份。
 *
 * 业务组件(PosterCard / Tabs / Select…)不在这里:它们自己就分两端画 —— TV 上落到
 * `CardPoster` / `TvButton` / [plugPress],焦点与记忆都在那一份里。再在这里抄一遍
 * 等于同一个组件两处实现,改一处的那次就分叉了。
 */
@Composable
private fun renderTv(n: UiNode, m: Modifier, surface: String, app: AppState): Boolean {
    val key = plugKey(n.id)
    val interactive = n.type in interactiveTypes
    val initial = interactive && claimsInitialFocus(n.id)
    val enabled = !n.bool("disabled")
    val long = longPressOf(n, surface, app)
    when (n.type) {
        /* variant 在 TV 上只分「主 / 不主」:那边的焦点态本身就是最强的视觉区分,
           再按四档配四套底色,聚焦框一压上去谁是谁都看不出来。 */
        "Button" -> xyz.linplayer.app.tv.kit.TvButton(
            n.str("title") ?: n.str("label") ?: textOfNode(n),
            icon = iconByName(n.str("icon")),
            primary = variantOf(n.str("variant")) == BtnKind.Primary,
            modifier = m.memo(key, initial), enabled = enabled, onLongClick = long,
        ) { fire(app, surface, n.fn("onPress")) }
        "Chip" -> xyz.linplayer.app.tv.kit.TvButton(
            n.str("label") ?: textOfNode(n),
            modifier = m.memo(key, initial), enabled = enabled, onLongClick = long,
        ) { fire(app, surface, n.fn("onPress")) }
        "Pressable" -> xyz.linplayer.app.tv.kit.TvButton(
            "", modifier = m.memo(key, initial), enabled = enabled, onLongClick = long,
        ) { fire(app, surface, n.fn("onPress")) }
        "Switch", "Checkbox" -> {
            /* ☠ 别映射成 PanelItem:那是**整行**的设置项,而插件常把开关摆在一行里,
               整行会把旁边的文字挤成一列竖字。这里要的只是「一个能聚焦、按了会翻」的东西。 */
            val on = n.bool("value") || n.bool("checked")
            xyz.linplayer.app.tv.kit.TvButton(
                listOfNotNull(n.str("label"), if (on) "开" else "关").joinToString(" "),
                modifier = m.memo(key, initial), enabled = enabled,
            ) { fire(app, surface, n.fn("onChange") ?: n.fn("onToggle"), !on) }
        }
        else -> return false
    }
    return true
}

internal fun textOfNode(n: UiNode): String =
    n.text + n.children.filter { it.type == "#text" }.joinToString("") { it.text }


/**
 * 插件 Canvas(D19 D104 D105):把一帧的指令流照着画出来。
 *
 * ☠ 只认**指令名**,不认语义:加一个方法就在下面的 when 里加一行。
 * 认不出来的指令直接跳过 —— 老宿主遇到新 SDK 时少画一笔,而不是整块崩掉(D319)。
 */
@Composable
private fun PluginCanvas(n: UiNode, m: Modifier) {
    val cmds = n.props["cmds"] as? kotlinx.serialization.json.JsonArray ?: return
    val fallback = Lp.colors.fg
    androidx.compose.foundation.Canvas(m.fillMaxWidth().height((n.style().numOf("height") ?: 120.0).dp)) {
        /* ☠ 指令里的坐标是**设备无关像素**(SPEC 7.5),而 DrawScope 用的是物理像素 ——
           不缩放的话同一段指令在高密度屏上画出来只有桌面的三分之一大,而且不报错。 */
        scale(density, pivot = androidx.compose.ui.geometry.Offset.Zero) {
        var fill = fallback
        var stroke = fallback
        var lineWidth = 1f
        var alpha = 1f
        var path = mutableListOf<androidx.compose.ui.geometry.Offset>()
        for (e in cmds) {
            val a = e as? kotlinx.serialization.json.JsonArray ?: continue
            if (a.isEmpty()) continue
            fun s(i: Int) = (a.getOrNull(i) as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
            fun f(i: Int) = (a.getOrNull(i) as? kotlinx.serialization.json.JsonPrimitive)?.content?.toFloatOrNull() ?: 0f
            when (s(0)) {
                "set" -> when (s(1)) {
                    "fillStyle" -> fill = parseColor(s(2)) ?: fill
                    "strokeStyle" -> stroke = parseColor(s(2)) ?: stroke
                    "lineWidth" -> lineWidth = f(2)
                    "globalAlpha" -> alpha = f(2)
                }
                "fillRect" -> drawRect(fill.copy(alpha = fill.alpha * alpha),
                    androidx.compose.ui.geometry.Offset(f(1), f(2)),
                    androidx.compose.ui.geometry.Size(f(3), f(4)))
                "strokeRect" -> drawRect(stroke.copy(alpha = stroke.alpha * alpha),
                    androidx.compose.ui.geometry.Offset(f(1), f(2)),
                    androidx.compose.ui.geometry.Size(f(3), f(4)),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(lineWidth))
                "beginPath" -> path = mutableListOf()
                "moveTo" -> { path = mutableListOf(androidx.compose.ui.geometry.Offset(f(1), f(2))) }
                "lineTo" -> path.add(androidx.compose.ui.geometry.Offset(f(1), f(2)))
                "stroke" -> for (i in 1 until path.size) {
                    drawLine(stroke.copy(alpha = stroke.alpha * alpha), path[i - 1], path[i], lineWidth)
                }
                // fillText 走 nativeCanvas:Compose 的 DrawScope 自己不画文字
                "fillText" -> drawContext.canvas.nativeCanvas.drawText(
                    s(1), f(2), f(3),
                    android.graphics.Paint().apply {
                        color = android.graphics.Color.argb(
                            (fill.alpha * alpha * 255).toInt(),
                            (fill.red * 255).toInt(), (fill.green * 255).toInt(), (fill.blue * 255).toInt())
                        textSize = 14f
                        isAntiAlias = true
                    },
                )
                // 认不出来的跳过
            }
        }
        }
    }
}

private fun parseColor(v: String): Color? = runCatching {
    if (v.startsWith("token:")) null else Color(android.graphics.Color.parseColor(v))
}.getOrNull()

/**
 * 关键帧动画。停靠点**等距**:n 帧的第 i 帧落在 i/(n-1)。
 *
 * 减少动态效果时直接停在最后一帧 —— 关键帧动画的「终态」就是它。
 */
@Composable
private fun keyframed(an: JsonObject, base: Motion, ms: Float): Motion {
    val kf = (an["keyframes"] as? kotlinx.serialization.json.JsonArray)
        .orEmpty().mapNotNull { it as? JsonObject }
    if (kf.size < 2 || ms == 0f) return frameAt(kf.lastOrNull(), kf.lastOrNull(), 0f, base)
    val dur = ((an.numOf("duration") ?: 0.0) * ms).toInt().coerceAtLeast(1)
    val iter = an.numOf("iterations")?.toInt()?.takeIf { it > 0 }
    val p = if (iter == null) {
        androidx.compose.animation.core.rememberInfiniteTransition(label = "kf").animateFloat(
            0f, 1f,
            androidx.compose.animation.core.infiniteRepeatable(
                androidx.compose.animation.core.tween(dur, easing = androidx.compose.animation.core.LinearEasing)
            ),
            label = "kf",
        ).value
    } else {
        val a = androidx.compose.runtime.remember(dur, iter) {
            androidx.compose.animation.core.Animatable(0f)
        }
        androidx.compose.runtime.LaunchedEffect(a) {
            a.animateTo(
                iter.toFloat(),
                androidx.compose.animation.core.tween(dur * iter, easing = androidx.compose.animation.core.LinearEasing),
            )
        }
        if (a.value >= iter) 1f else a.value % 1f
    }
    val pos = p.coerceIn(0f, 1f) * (kf.size - 1)
    val i = pos.toInt().coerceIn(0, kf.size - 2)
    return frameAt(kf[i], kf[i + 1], easingOf(an.strOf("easing")).transform(pos - i), base)
}

/** 两帧之间插值;关键帧里没写的属性回落到节点自己的静态值。 */
private fun frameAt(from: JsonObject?, to: JsonObject?, f: Float, base: Motion): Motion {
    fun v(k: String, d: Float): Float {
        val a = from.numOf(k)?.toFloat() ?: d
        val b = to.numOf(k)?.toFloat() ?: d
        return a + (b - a) * f
    }
    return Motion(
        v("opacity", base.opacity), v("translateX", base.tx), v("translateY", base.ty),
        v("scale", base.scale), v("rotate", base.rotate),
        base.width?.let { v("width", it) }, base.height?.let { v("height", it) },
        layered = true,
    )
}

/** D445:所有组件都能带 `a11yLabel`;文字类没给就用自身文字。 */
@Composable
private fun Modifier.a11y(n: UiNode): Modifier {
    val label = n.str("a11yLabel") ?: defaultLabel(n) ?: return this
    if (label.isEmpty()) return this
    return this.semantics { contentDescription = label }
}

private fun defaultLabel(n: UiNode): String? = when (n.type) {
    "Text" -> textOfNode(n)
    "Markdown" -> n.str("source")
    "Button" -> n.str("title") ?: n.str("label")
    "Chip" -> n.str("label")
    "Badge" -> n.str("text") ?: n.str("label")
    "EmptyState" -> n.str("text")
    "SettingsRow" -> n.str("title")
    "Icon" -> n.str("name")
    else -> null
}

/**
 * SPEC 7.8 的 FocusProps。
 *
 * `nextFocusUp` 这几条指向的是**别的节点的 key**,所以要一张 key → FocusRequester 的表;
 * 查表放在 `focusProperties` 的 lambda 里 —— 它在移焦那一刻才执行,那时目标才一定已经注册过。
 */
@Composable
private fun Modifier.focusOf(n: UiNode): Modifier {
    val key = n.str("key")
    val group = n.str("focusGroup")
    val can = (n.prop("focusable") as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBooleanStrictOrNull()
    val auto = n.bool("autoFocus")
    val up = n.str("nextFocusUp"); val down = n.str("nextFocusDown")
    val left = n.str("nextFocusLeft"); val right = n.str("nextFocusRight")
    val any = key != null || group != null || can != null || auto ||
        up != null || down != null || left != null || right != null
    if (!any) return this

    val keys = LocalPluginFocusKeys.current
    val fr = androidx.compose.runtime.remember(n.id) { androidx.compose.ui.focus.FocusRequester() }
    if (key != null) androidx.compose.runtime.DisposableEffect(key) {
        keys[key] = fr
        onDispose { if (keys[key] === fr) keys.remove(key) }
    }
    if (auto) androidx.compose.runtime.LaunchedEffect(n.id) {
        // 头几帧里目标还没进树,requestFocus 会失败 —— 重试到进树为止
        repeat(10) {
            androidx.compose.runtime.withFrameNanos { }
            if (runCatching { fr.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }
    var m = this.focusProperties {
        if (can == false) canFocus = false
        up?.let { k -> this.up = keys[k] ?: androidx.compose.ui.focus.FocusRequester.Default }
        down?.let { k -> this.down = keys[k] ?: androidx.compose.ui.focus.FocusRequester.Default }
        left?.let { k -> this.left = keys[k] ?: androidx.compose.ui.focus.FocusRequester.Default }
        right?.let { k -> this.right = keys[k] ?: androidx.compose.ui.focus.FocusRequester.Default }
    }.focusRequester(fr)
    if (group != null) m = m.focusGroup()
    if (can == true) m = m.focusable()
    return m
}
