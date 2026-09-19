package xyz.linplayer.app.tv.kit

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import xyz.linplayer.app.ui.theme.LpIcons

// ---------------------------------------------------------------- 导航轨(§3.1)

val railItems = listOf(
    "搜索" to LpIcons.search, "首页" to LpIcons.home, "媒体库" to LpIcons.grid,
    "收藏" to LpIcons.heart, "下载" to LpIcons.download,
    "服务器" to LpIcons.server, "设置" to LpIcons.settings,
)

/**
 * §3.1【用户定 2026-09-14】收起只剩图标;焦点一进轨就展开成 200dp,**盖在内容上面**,内容区不动。
 * previewExpanded 只给草稿出图用:截图没法先把焦点放进轨里再截。
 *
 * ★ 焦点进轨落在**当前页那一项**,不是上次停过的项;轨项获得焦点不切页,按确认才切。
 */
@Composable
fun Rail(
    current: Int, focused: Int = -1, previewExpanded: Boolean = false,
    modifier: Modifier = Modifier, onSelect: (Int) -> Unit = {},
) {
    var hasFocus by remember { mutableStateOf(false) }
    val expanded = previewExpanded || hasFocus
    val w by animateDpAsState(if (expanded) TvDim.railExpandedW else TvDim.railW, tween(180), label = "rail")
    val reqs = remember { List(railItems.size) { FocusRequester() } }
    Box(
        modifier.width(w).fillMaxHeight()
            .then(if (expanded) Modifier.shadow(8.dp) else Modifier)
            .background(TvC.rail)
            .onFocusChanged { hasFocus = it.hasFocus }
            // 调用方指定了焦点项(草稿)就不拐:那一项自己 requestFocus 进组时,会被拐回当前页那一项
            .focusProperties { if (focused < 0) onEnter = { reqs[current.coerceIn(0, reqs.lastIndex)].requestFocus() } }
            .focusGroup(),
    ) {
        Column(Modifier.fillMaxSize().padding(vertical = TvDim.safeV, horizontal = TvSp.x8)) {
            Row(Modifier.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(28.dp).clip(TvR.md).background(TvC.acc), contentAlignment = Alignment.Center) {
                    Icon(LpIcons.play, null, Modifier.size(14.dp), tint = TvC.onAcc)
                }
                if (expanded) {
                    Spacer(Modifier.width(TvSp.x12))
                    TvText("LinPlayer", tvType.title, TvC.fg, weight = TvW.semi)
                }
            }
            Spacer(Modifier.height(TvSp.x16))
            railItems.forEachIndexed { i, (label, icon) ->
                if (i == 6) {
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.fillMaxWidth().padding(horizontal = TvSp.x8).height(1.dp).background(TvC.line))
                    Spacer(Modifier.height(TvSp.x6))
                }
                RailItem(label, icon, on = i == current, expanded = expanded, focused = i == focused,
                    modifier = Modifier.focusRequester(reqs[i]), onClick = { onSelect(i) })
            }
        }
        Box(Modifier.align(Alignment.CenterEnd).width(1.dp).fillMaxHeight().background(TvC.line))
    }
}

/** 轨项。收起 48×44 只有图标;展开 184×44 图标 + 文字。**不放大**:轨里放大会撑破边界。 */
@Composable
fun RailItem(
    label: String, icon: ImageVector, on: Boolean, expanded: Boolean, focused: Boolean = false, fake: Boolean = false,
    modifier: Modifier = Modifier, onClick: () -> Unit = {},
) {
    Surface(
        onClick = onClick,
        modifier = modifier.padding(vertical = TvSp.x2).size(if (expanded) 184.dp else 48.dp, 44.dp).initialFocus(focused),
        shape = ClickableSurfaceDefaults.shape(TvR.md),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 0.97f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (fake) TvC.focus else if (on) TvC.accDim else Color.Transparent,
            contentColor = when {
                fake -> TvC.onFocus
                on -> TvC.acc
                expanded -> TvC.fg2
                else -> TvC.fg3
            },
            focusedContainerColor = TvC.focus, focusedContentColor = TvC.onFocus,
        ),
    ) {
        Row(Modifier.fillMaxSize().padding(start = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(22.dp))
            if (expanded) {
                Spacer(Modifier.width(TvSp.x12))
                Text(label, fontSize = tvType.body, fontWeight = if (on) TvW.semi else TvW.medium, maxLines = 1)
            }
        }
    }
}

/** 带轨的页面外壳(草稿用)。轨**叠在内容上面**;内容区从 x=64 开始,左边 32dp 由各页自己让。 */
@Composable
fun RailShell(current: Int, railFocus: Int = -1, railExpanded: Boolean = false, content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(TvC.bg)) {
        Box(Modifier.fillMaxSize().padding(start = TvDim.railW), content = content)
        Rail(current, railFocus, railExpanded)
    }
}

/** 带轨页面的标准内容区:左 32、右 48、上下 27。 */
fun Modifier.contentArea() = this.fillMaxSize().padding(start = TvSp.x32, end = TvDim.safeH, top = TvDim.safeV, bottom = TvDim.safeV)

@Composable
fun PageHead(title: String, count: String? = null, sub: String? = null) {
    val t = tvType
    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            TvText(title, t.headline, TvC.fg, weight = TvW.semi)
            if (count != null) {
                Spacer(Modifier.width(TvSp.x12))
                TvText(count, t.meta, TvC.fg3, Modifier.padding(bottom = 3.dp))
            }
        }
        if (sub != null) TvText(sub, t.body, TvC.fg2)
    }
}

// ---------------------------------------------------------------- 侧面板(§5.3)

@Composable
fun SidePanel(title: String, back: Boolean = false, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val scroll = rememberScrollState()
    Column(
        modifier.width(TvDim.panelW).heightIn(max = TvDim.panelMaxH)
            .shadow(12.dp, TvR.lg).clip(TvR.lg).background(TvC.surface2).padding(vertical = TvSp.x12),
    ) {
        Row(Modifier.padding(horizontal = TvSp.x16).padding(bottom = TvSp.x6), verticalAlignment = Alignment.CenterVertically) {
            if (back) {
                Icon(LpIcons.back, null, Modifier.size(16.dp), tint = TvC.fg3)
                Spacer(Modifier.width(TvSp.x4))
            }
            TvText(title, tvType.title, TvC.fg, weight = TvW.semi)
        }
        Column(Modifier.weight(1f, fill = false).verticalScroll(scroll), content = content)
        // 内容超出时底部一个 ▾:不画的话最后一项被切掉一半,看着像面板画坏了
        if (scroll.canScrollForward) Box(Modifier.fillMaxWidth().padding(top = TvSp.x2), contentAlignment = Alignment.Center) {
            Icon(LpIcons.chevD, null, Modifier.size(16.dp), tint = TvC.fg3)
        }
    }
}

/** 分组标题,不可聚焦。 */
@Composable
fun PanelGroup(title: String) {
    TvText(title, tvType.meta, TvC.fg3, Modifier.padding(start = TvSp.x16, top = TvSp.x8, bottom = TvSp.x2))
}

/**
 * 面板项 / 设置行。
 * ★ 步进项([onStep] 非空):聚焦后 ←/→ 调值,确认无动作;调值时焦点不移出面板。
 */
@Composable
fun PanelItem(
    label: String, value: String? = null, selected: Boolean = false, focused: Boolean = false, fake: Boolean = false,
    danger: Boolean = false, chevron: Boolean = false, step: Boolean = false, enabled: Boolean = true,
    sub: String? = null, switch: Boolean? = null, check: Boolean = true,
    modifier: Modifier = Modifier, onStep: ((Int) -> Unit)? = null, onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit = {},
) {
    val t = tvType
    if (!enabled) {
        Row(Modifier.fillMaxWidth().heightIn(min = t.rowH).padding(horizontal = TvSp.x16, vertical = TvSp.x6),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                TvText(label, t.body, TvC.fg3, maxLines = 2)
                if (sub != null) TvText(sub, t.meta, TvC.fg3, maxLines = 3)
            }
            if (value != null) TvText(value, t.meta, TvC.fg3)
        }
        return
    }
    val stepKeys = if (onStep == null) Modifier else Modifier.onPreviewKeyEvent {
        val d = when (it.key) { Key.DirectionLeft -> -1; Key.DirectionRight -> 1; else -> 0 }
        if (d == 0) return@onPreviewKeyEvent false
        if (it.type == KeyEventType.KeyDown) onStep(d)
        true
    }
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.padding(horizontal = TvSp.x6).fillMaxWidth().heightIn(min = t.rowH).initialFocus(focused)
            .then(stepKeys).menuKey(onLongClick),
        shape = ClickableSurfaceDefaults.shape(TvR.md),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 0.98f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (fake) TvC.focus else Color.Transparent,
            contentColor = when {
                fake -> TvC.onFocus
                danger -> TvC.bad
                selected -> TvC.fg
                else -> TvC.fg2
            },
            focusedContainerColor = TvC.focus, focusedContentColor = TvC.onFocus,
        ),
    ) {
        Box(Modifier.fillMaxWidth().heightIn(min = t.rowH)) {
            if (selected) Box(Modifier.align(Alignment.CenterStart).width(3.dp).height(t.rowH * 0.45f).clip(TvR.pill).background(TvC.acc))
            Row(Modifier.fillMaxWidth().heightIn(min = t.rowH).padding(horizontal = TvSp.x12, vertical = TvSp.x6),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(label, fontSize = t.body, fontWeight = if (selected) TvW.semi else TvW.medium, maxLines = 3)
                    if (sub != null) Text(sub, fontSize = t.meta, maxLines = 3, modifier = Modifier.graphicsLayer { alpha = .75f })
                }
                if (step) Text("−", fontSize = t.title, modifier = Modifier.padding(horizontal = TvSp.x6))
                if (value != null) Text(value, fontSize = t.meta, maxLines = 1)
                if (step) Text("+", fontSize = t.title, modifier = Modifier.padding(start = TvSp.x6))
                if (switch != null) Toggle(switch)
                // 导航性质的列表(设置分类、发现分类)只要竖条不要勾:勾读作「已勾选」,而那里是「在看这一类」
                if (selected && check) Icon(LpIcons.check, null, Modifier.padding(start = TvSp.x6).size(16.dp))
                if (chevron) Icon(LpIcons.chevR, null, Modifier.padding(start = TvSp.x4).size(14.dp))
            }
        }
    }
}

@Composable
fun Toggle(on: Boolean) {
    Box(Modifier.padding(start = TvSp.x8).size(32.dp, 18.dp).clip(TvR.pill).background(if (on) TvC.acc else TvC.surface3)) {
        Box(Modifier.align(if (on) Alignment.CenterEnd else Alignment.CenterStart).padding(2.dp).size(14.dp).clip(TvR.pill)
            .background(if (on) TvC.onAcc else TvC.fg3))
    }
}

/** 面板靠右、垂直居中、右边距 48,无遮罩。 */
@Composable
fun BoxScope.PanelSlot(content: @Composable () -> Unit) {
    Box(Modifier.align(Alignment.CenterEnd).padding(end = TvDim.safeH)) { content() }
}

// ---------------------------------------------------------------- 反馈

/** 静态色块,不做闪光:弱机上每帧重画一整屏。 */
@Composable
fun Skel(modifier: Modifier, shape: Shape = TvR.md, color: Color = TvC.surface1) {
    Box(modifier.clip(shape).background(color))
}

@Composable
fun BoxScope.TvToast(text: String, dot: Color = TvC.ok) {
    Row(
        Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp)
            .shadow(8.dp, TvR.md).clip(TvR.md).background(TvC.surface2)
            .padding(horizontal = TvSp.x20, vertical = TvSp.x12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(TvR.pill).background(dot))
        Spacer(Modifier.width(TvSp.x8))
        TvText(text, tvType.body, TvC.fg)
    }
}

@Composable
fun FullState(
    icon: ImageVector, title: String, sub: String? = null, detail: String? = null,
    buttons: List<String>, tone: Color = TvC.fg3, focusFirst: Boolean = true, modifier: Modifier = Modifier,
    onButton: (Int) -> Unit = {},
) {
    val t = tvType
    Column(modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(64.dp).clip(TvR.lg).background(tone.copy(alpha = .16f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(32.dp), tint = tone)
        }
        Spacer(Modifier.height(TvSp.x16))
        TvText(title, t.title, TvC.fg, weight = TvW.semi)
        if (sub != null) { Spacer(Modifier.height(TvSp.x6)); TvText(sub, t.body, TvC.fg2, maxLines = 3) }
        if (detail != null) { Spacer(Modifier.height(TvSp.x4)); TvText(detail, t.meta, TvC.fg3, maxLines = 3) }
        Spacer(Modifier.height(TvSp.x20))
        Row(horizontalArrangement = Arrangement.spacedBy(TvSp.x12)) {
            buttons.forEachIndexed { i, b -> TvButton(b, focused = focusFirst && i == 0, onClick = { onButton(i) }) }
        }
    }
}

/** 行内错误(§5.5):一句 `meta fg3`,不打断其它块。 */
@Composable
fun InlineError(message: String, modifier: Modifier = Modifier) {
    TvText("没加载出来:$message", tvType.meta, TvC.fg3, modifier, maxLines = 2)
}

@Composable
fun StatusDot(color: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).clip(TvR.pill).background(color))
}

/** 行标题。link = 可聚焦(媒体库行),聚焦时反色 + ›。 */
@Composable
fun RowTitle(
    text: String, link: Boolean = false, focused: Boolean = false, fake: Boolean = false, trailing: String? = null,
    modifier: Modifier = Modifier, onClick: () -> Unit = {},
) {
    val t = tvType
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (link) {
            Surface(
                onClick = onClick,
                modifier = modifier.initialFocus(focused),
                shape = ClickableSurfaceDefaults.shape(TvR.sm),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = if (fake) TvC.focus else Color.Transparent,
                    contentColor = if (fake) TvC.onFocus else TvC.fg,
                    focusedContainerColor = TvC.focus, focusedContentColor = TvC.onFocus,
                ),
            ) {
                Row(Modifier.padding(horizontal = TvSp.x8, vertical = TvSp.x2), verticalAlignment = Alignment.CenterVertically) {
                    Text(text, fontSize = t.title, fontWeight = TvW.semi)
                    Icon(LpIcons.chevR, null, Modifier.padding(start = TvSp.x4).size(14.dp))
                }
            }
        } else {
            TvText(text, t.title, TvC.fg, Modifier.padding(horizontal = TvSp.x8, vertical = TvSp.x2), weight = TvW.semi)
        }
        if (trailing != null) TvText(trailing, t.meta, TvC.fg3)
    }
}

/** 输入框外观(族 E)。草稿与真输入框 [TvTextField] 共用这一份画法。 */
@Composable
fun InputBox(text: String, placeholder: String = "", icon: ImageVector? = null, focused: Boolean = false, width: Dp, caret: Boolean = focused) {
    val t = tvType
    Row(
        Modifier.width(width).height(t.controlH).clip(TvR.sm)
            .background(if (focused) TvC.surface2 else TvC.surface3)
            .then(if (focused) Modifier.border(2.dp, TvC.focus, TvR.sm) else Modifier)
            .padding(horizontal = TvSp.x12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(t.iconS), tint = TvC.fg3); Spacer(Modifier.width(TvSp.x8)) }
        if (text.isEmpty()) TvText(placeholder, t.body, TvC.fg3) else TvText(text, t.body, TvC.fg)
        if (caret) Box(Modifier.padding(start = 1.dp).width(2.dp).height(t.body.value.dp + 4.dp).background(TvC.acc))
    }
}
