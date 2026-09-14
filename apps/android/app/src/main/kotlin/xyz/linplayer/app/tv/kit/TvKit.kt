package xyz.linplayer.app.tv.kit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.MarqueeAnimationMode
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import xyz.linplayer.app.ui.theme.LpIcons

/*
 * TV 组件(UI_TV.md §5)。页面只能用这些件拼;草稿(ui/drafts/tv)用的也是这一份。
 *
 * ★ 焦点和选中走两条通道(§4.1):焦点 = 白,选中 = 琥珀。
 * 参数口径:`focused` = 进页时真的去要焦点;`fake` = 不要焦点、只**画成**聚焦的样子
 * (组件表要在一张图里同时摆出多个焦点态,真焦点同一时刻只有一个)。
 */

// ---------------------------------------------------------------- 基础

@Composable
fun TvText(
    text: String, size: TextUnit, color: Color = TvC.fg, modifier: Modifier = Modifier,
    weight: FontWeight = FontWeight.Normal, maxLines: Int = 1, mono: Boolean = false,
) {
    Text(
        text, modifier, color = color, fontSize = size, fontWeight = weight,
        maxLines = maxLines, overflow = TextOverflow.Ellipsis,
        fontFamily = if (mono) FontFamily.Monospace else null,
    )
}

/**
 * 进页初始焦点。
 *
 * ☠ Lazy 列表里的项在第一帧还没挂上焦点树,这时 `requestFocus` 静默失败 ——
 *   表现是「初始焦点没落上」,截图里一个反白都没有。所以按帧重试,最多 10 帧。
 */
@Composable
fun Modifier.initialFocus(want: Boolean): Modifier {
    if (!want) return this
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(10) {
            withFrameNanos { }
            if (runCatching { fr.requestFocus(FocusDirection.Enter) }.getOrDefault(false)) return@LaunchedEffect
        }
    }
    return this.focusRequester(fr).testTag("initialFocus")
}

/**
 * 可滚动容器往左右各多出 [d],配合同样大小的 contentPadding 用。
 *
 * ☠ Lazy 容器一律裁剪到自己的边界,而焦点放大 1.06 + 外描白框要多占约 10dp ——
 *   不出血的话网格第一行 / 第一列的白框被切掉一截(草稿出图实测)。
 */
fun Modifier.bleed(d: Dp) = this.layout { m, c ->
    val px = d.roundToPx()
    val w = if (c.hasBoundedWidth) c.maxWidth + 2 * px else c.maxWidth
    val p = m.measure(c.copy(minWidth = w, maxWidth = w))
    layout(c.maxWidth, p.height) { p.place(-px, 0) }
}

private fun Modifier.fakeScale(fake: Boolean, s: Float) =
    if (fake) this.graphicsLayer { scaleX = s; scaleY = s } else this

/**
 * 菜单键 = 长按确认(§4.4)。
 *
 * ★ 两者必须等价:Google TV 原装遥控器、小米新款都没有菜单键 —— 只挂在菜单键上的能力,
 *   这些用户一辈子用不到。所以凡是传了长按的件,这里顺手把菜单键接上,不许页面各接各的。
 */
fun Modifier.menuKey(onLongClick: (() -> Unit)?): Modifier =
    if (onLongClick == null) this else this.onPreviewKeyEvent {
        if (it.key != Key.Menu) return@onPreviewKeyEvent false
        if (it.type == KeyEventType.KeyUp) onLongClick()
        true
    }

// ---------------------------------------------------------------- 族 B / C:实体控件

@Composable
private fun solidColors(primary: Boolean, selected: Boolean, fake: Boolean) = ClickableSurfaceDefaults.colors(
    containerColor = when {
        fake -> TvC.focus
        primary -> TvC.acc
        selected -> TvC.accDim
        else -> TvC.surface2
    },
    contentColor = when {
        fake -> TvC.onFocus
        primary -> TvC.onAcc
        selected -> TvC.acc
        else -> TvC.fg2
    },
    focusedContainerColor = TvC.focus,
    focusedContentColor = TvC.onFocus,
    pressedContainerColor = TvC.focus,
    pressedContentColor = TvC.onFocus,
    disabledContainerColor = TvC.surface2,
    disabledContentColor = TvC.fg3,
)

@Composable
fun TvButton(
    label: String, icon: ImageVector? = null, primary: Boolean = false,
    focused: Boolean = false, fake: Boolean = false, modifier: Modifier = Modifier,
    enabled: Boolean = true, onLongClick: (() -> Unit)? = null, onClick: () -> Unit = {},
) {
    val t = tvType
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        enabled = enabled,
        modifier = modifier.height(t.controlH).initialFocus(focused).fakeScale(fake, 1.04f).menuKey(onLongClick),
        shape = ClickableSurfaceDefaults.shape(TvR.sm),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f, pressedScale = 0.98f),
        colors = solidColors(primary, false, fake),
    ) {
        Row(Modifier.fillMaxHeight().padding(horizontal = TvSp.x16), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, Modifier.size(t.iconS))
                if (label.isNotEmpty()) Spacer(Modifier.width(TvSp.x8))
            }
            if (label.isNotEmpty()) Text(label, fontSize = t.body, fontWeight = TvW.semi, maxLines = 1)
        }
    }
}

/** 纯图标方钮 —— 只给播放页左组(通用约定可以不带字)。 */
@Composable
fun TvIconButton(
    icon: ImageVector, size: Dp, primary: Boolean = false, focused: Boolean = false, fake: Boolean = false,
    modifier: Modifier = Modifier, onClick: () -> Unit = {},
) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(size).initialFocus(focused).fakeScale(fake, 1.08f),
        shape = ClickableSurfaceDefaults.shape(TvR.sm),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f, pressedScale = 0.96f),
        colors = solidColors(primary, false, fake),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(size * 0.46f))
        }
    }
}

/** 入口 chip + 右侧不可聚焦的当前条件。☠ 不许恒为选中样式(旧实现聚焦时毫无变化)。 */
@Composable
fun EntryChip(
    label: String, icon: ImageVector, condition: String? = null, focused: Boolean = false, fake: Boolean = false,
    modifier: Modifier = Modifier, onClick: () -> Unit = {},
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TvButton(label, icon, focused = focused, fake = fake, modifier = modifier, onClick = onClick)
        if (condition != null) {
            Spacer(Modifier.width(TvSp.x16))
            TvText(condition, tvType.meta, TvC.fg3)
        }
    }
}

/** PickBar 的 chip:按钮里带当前值 —— 三米外「字幕」两个字看不出现在是哪条轨。 */
@Composable
fun ValueChip(
    label: String, value: String, icon: ImageVector, focused: Boolean = false, fake: Boolean = false,
    modifier: Modifier = Modifier, onClick: () -> Unit = {},
) {
    val t = tvType
    Surface(
        onClick = onClick,
        modifier = modifier.height(t.controlH).initialFocus(focused).fakeScale(fake, 1.04f),
        shape = ClickableSurfaceDefaults.shape(TvR.sm),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        colors = solidColors(false, false, fake),
    ) {
        Row(Modifier.fillMaxHeight().padding(horizontal = TvSp.x12), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(t.iconS))
            Spacer(Modifier.width(TvSp.x6))
            Text(label, fontSize = t.body, fontWeight = TvW.semi)
            Spacer(Modifier.width(TvSp.x8))
            Text(value, fontSize = t.meta, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 104.dp))
        }
    }
}

/** 单选 chip 行。选中 = 琥珀,聚焦 = 白。 */
@Composable
fun ScopeChips(
    items: List<String>, selected: Int, focusedIndex: Int = -1, fakeIndex: Int = -1,
    itemModifier: @Composable (Int) -> Modifier = { Modifier }, onSelect: (Int) -> Unit = {},
) {
    val t = tvType
    Row(horizontalArrangement = Arrangement.spacedBy(TvSp.x8)) {
        items.forEachIndexed { i, s ->
            Surface(
                onClick = { onSelect(i) },
                modifier = itemModifier(i).height(t.controlH).initialFocus(i == focusedIndex).fakeScale(i == fakeIndex, 1.04f),
                shape = ClickableSurfaceDefaults.shape(TvR.sm),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
                colors = solidColors(false, i == selected, i == fakeIndex),
            ) {
                Box(Modifier.fillMaxHeight().padding(horizontal = TvSp.x16), contentAlignment = Alignment.Center) {
                    Text(s, fontSize = t.body, fontWeight = if (i == selected) TvW.semi else TvW.medium, maxLines = 1)
                    // 选中的琥珀短横聚焦时也保留:反白之后,这是唯一能看出「焦点所在的就是选中那个」的记号
                    if (i == selected) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 5.dp)
                        .size(16.dp, 2.dp).clip(TvR.pill).background(TvC.acc))
                }
            }
        }
    }
}

/** 危险动作按钮:常态字是 bad 色,聚焦照样反白(焦点通道不许被危险色借走)。 */
@Composable
fun DangerButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(tvType.controlH),
        shape = ClickableSurfaceDefaults.shape(TvR.sm),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = TvC.surface3, contentColor = TvC.bad,
            focusedContainerColor = TvC.focus, focusedContentColor = TvC.bad,
        ),
    ) {
        Box(Modifier.fillMaxHeight().padding(horizontal = TvSp.x16), contentAlignment = Alignment.Center) {
            Text(label, fontSize = tvType.body, fontWeight = TvW.semi)
        }
    }
}

// ---------------------------------------------------------------- 族 A:卡片

// inset 为正 = 框画在封面**外面**(实测负值会画进封面里,压住图)
private val focusFrame = Border(BorderStroke(2.dp, TvC.focus), inset = 3.dp, shape = TvR.md)

/** 族 A:只有封面放大并描白框;标题在封面下 6dp,放大 1.06 刚好不压字(§4.1)。 */
@Composable
fun CoverSurface(
    w: Dp, h: Dp, focused: Boolean = false, selected: Boolean = false, fake: Boolean = false,
    modifier: Modifier = Modifier, interactionSource: MutableInteractionSource? = null,
    onLongClick: (() -> Unit)? = null, onClick: () -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    val selectedFrame = Border(BorderStroke(1.5.dp, TvC.acc), shape = TvR.md)
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.size(w, h).initialFocus(focused).fakeScale(fake, 1.06f).menuKey(onLongClick),
        shape = ClickableSurfaceDefaults.shape(TvR.md),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f, pressedScale = 0.98f),
        border = ClickableSurfaceDefaults.border(
            border = when {
                fake -> focusFrame
                selected -> selectedFrame
                else -> Border.None
            },
            focusedBorder = focusFrame,
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) TvC.accDim else TvC.surface1,
            focusedContainerColor = if (selected) TvC.accDim else TvC.surface1,
        ),
        interactionSource = interactionSource,
    ) {
        Box(Modifier.fillMaxSize()) {
            content()
            // 选中且聚焦:白框在外、琥珀框在内,两个都看得见
            if (selected && fake) Box(Modifier.fillMaxSize().border(1.5.dp, TvC.acc, TvR.md))
        }
    }
}

@Composable
fun ProgressLine(p: Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(3.dp).background(Color(0x8C000000))) {
        Box(Modifier.fillMaxWidth(p).fillMaxHeight().background(TvC.acc))
    }
}

/**
 * 卡片标题。聚焦时放不下就跑马灯【继承 PC A-25】:30dp/s,放得下不滚。
 * ★ 只在聚焦项上跑(§10):一屏几十张卡一起跑,盒子上每帧都在重画。
 */
@Composable
private fun CardTitle(text: String, focused: Boolean, color: Color) {
    val t = tvType
    if (!focused) TvText(text, t.body, color, weight = TvW.medium)
    else Text(
        text, Modifier.basicMarquee(Int.MAX_VALUE, MarqueeAnimationMode.Immediately, repeatDelayMillis = 1500, velocity = 30.dp),
        color = color, fontSize = t.body, fontWeight = TvW.medium, maxLines = 1, overflow = TextOverflow.Clip,
    )
}

@Composable
fun CardWide(
    cover: @Composable BoxScope.() -> Unit, title: String, sub: String, progress: Float? = null, isNew: Boolean = false,
    focused: Boolean = false, fake: Boolean = false, w: Dp = TvDim.wideW, h: Dp = TvDim.wideH,
    modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null, onClick: () -> Unit = {},
) {
    val t = tvType
    val src = remember { MutableInteractionSource() }
    val isFocused by src.collectIsFocusedAsState()
    Column(Modifier.width(w)) {
        CoverSurface(w, h, focused, fake = fake, modifier = modifier, interactionSource = src,
            onLongClick = onLongClick, onClick = onClick) {
            cover()
            if (progress != null) ProgressLine(progress, Modifier.align(Alignment.BottomStart))
            if (isNew) Badge("新", TvC.bad, Color.White, Modifier.align(Alignment.TopEnd).padding(TvSp.x6))
        }
        Spacer(Modifier.height(TvSp.x6))
        CardTitle(title, isFocused, TvC.fg)
        TvText(sub, t.meta, TvC.fg2)
    }
}

@Composable
fun CardPoster(
    cover: @Composable BoxScope.() -> Unit, title: String, sub: String, watched: Boolean = false, unplayed: Int = 0,
    rank: Int = 0, focused: Boolean = false, fake: Boolean = false, w: Dp = TvDim.posterW, h: Dp = TvDim.posterH,
    modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null, onClick: () -> Unit = {},
) {
    val t = tvType
    val src = remember { MutableInteractionSource() }
    val isFocused by src.collectIsFocusedAsState()
    Column(Modifier.width(w)) {
        CoverSurface(w, h, focused, fake = fake, modifier = modifier, interactionSource = src,
            onLongClick = onLongClick, onClick = onClick) {
            cover()
            when {
                // 有勾优先,否则显数字(PC 口径)
                watched -> Box(
                    Modifier.align(Alignment.TopEnd).padding(TvSp.x6).size(18.dp).clip(TvR.pill).background(TvC.ok),
                    contentAlignment = Alignment.Center,
                ) { Icon(LpIcons.check, null, Modifier.size(12.dp), tint = Color(0xFF062418)) }
                unplayed > 0 -> Badge("$unplayed", TvC.acc, TvC.onAcc, Modifier.align(Alignment.TopEnd).padding(TvSp.x6))
            }
            if (rank > 0) {
                val top = rank <= 3
                Badge("$rank", if (top) TvC.acc else TvC.fg.copy(alpha = .82f), if (top) TvC.onAcc else TvC.bg,
                    Modifier.align(Alignment.TopStart).padding(TvSp.x6))
            }
        }
        Spacer(Modifier.height(TvSp.x6))
        CardTitle(title, isFocused, TvC.fg)
        TvText(sub, t.meta, TvC.fg2)
    }
}

@Composable
fun CardEpisode(
    cover: @Composable BoxScope.() -> Unit, no: String, title: String, sub: String, progress: Float? = null,
    done: Boolean = false, current: Boolean = false, focused: Boolean = false, fake: Boolean = false,
    modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null, onClick: () -> Unit = {},
) {
    val t = tvType
    val src = remember { MutableInteractionSource() }
    val isFocused by src.collectIsFocusedAsState()
    Column(Modifier.width(TvDim.epW)) {
        CoverSurface(TvDim.epW, TvDim.epH, focused, fake = fake, modifier = modifier, interactionSource = src,
            onLongClick = onLongClick, onClick = onClick) {
            cover()
            if (done) Box(Modifier.fillMaxSize().background(TvC.bg.copy(alpha = .3f)))
            Badge(no, Color(0xB8080C12), TvC.fg, Modifier.align(Alignment.TopStart).padding(TvSp.x6))
            when {
                done -> ProgressLine(1f, Modifier.align(Alignment.BottomStart))
                progress != null -> ProgressLine(progress, Modifier.align(Alignment.BottomStart))
            }
            if (progress != null && !done) Badge("继续", TvC.acc, TvC.onAcc, Modifier.align(Alignment.TopEnd).padding(TvSp.x6))
        }
        Spacer(Modifier.height(TvSp.x6))
        CardTitle(title, isFocused, if (current) TvC.acc else if (done) TvC.fg2 else TvC.fg)
        TvText(sub, t.meta, TvC.fg2)
    }
}

@Composable
fun CardLibrary(
    cover: @Composable BoxScope.() -> Unit, name: String, blocked: Boolean = false, focused: Boolean = false,
    fake: Boolean = false, modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null, onClick: () -> Unit = {},
) {
    val t = tvType
    Column(Modifier.width(TvDim.libW)) {
        CoverSurface(TvDim.libW, TvDim.libH, focused, fake = fake, modifier = modifier,
            onLongClick = onLongClick, onClick = onClick) {
            cover()
            if (blocked) {
                Box(Modifier.fillMaxSize().background(Color(0xA6100E14)))
                Badge("已屏蔽", Color(0xE60A090D), TvC.fg, Modifier.align(Alignment.BottomStart).padding(TvSp.x6))
            }
        }
        Spacer(Modifier.height(TvSp.x6))
        TvText(name, t.body, if (blocked) TvC.fg3 else TvC.fg, weight = TvW.medium)
    }
}

@Composable
fun Badge(text: String, bg: Color, fg: Color, modifier: Modifier = Modifier) {
    Box(modifier.clip(TvR.sm).background(bg).padding(horizontal = TvSp.x6, vertical = 1.dp)) {
        Text(text, color = fg, fontSize = tvType.meta, fontWeight = TvW.bold, maxLines = 1)
    }
}

// ---------------------------------------------------------------- 版本卡

enum class Probe { OK, PROBING, ABSENT, OPAQUE }

@Composable
fun VersionCard(
    server: String, res: String = "", spec: String = "", tracks: String = "", current: Boolean = false,
    selected: Boolean = false, probe: Probe = Probe.OK, maybe: Boolean = false,
    focused: Boolean = false, fake: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit = {},
) {
    val t = tvType
    val body: @Composable BoxScope.() -> Unit = {
        Column(Modifier.fillMaxSize().padding(horizontal = TvSp.x12, vertical = TvSp.x8)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(14.dp).clip(TvR.sm).background(TvC.surface3))
                Spacer(Modifier.width(TvSp.x6))
                TvText(server, t.meta, TvC.fg2, Modifier.weight(1f))
                if (current) TvText("当前", t.meta, TvC.acc, weight = TvW.semi)
            }
            Spacer(Modifier.height(TvSp.x2))
            when (probe) {
                Probe.OK -> {
                    TvText(res, t.numeral, TvC.fg, weight = TvW.bold)
                    TvText(spec, t.meta, TvC.fg2)
                    TvText(tracks, t.meta, TvC.fg3)
                }
                Probe.PROBING -> {
                    Spacer(Modifier.height(TvSp.x6))
                    Skel(Modifier.width(96.dp).height(14.dp), color = TvC.surface3)
                    Spacer(Modifier.height(TvSp.x6))
                    Skel(Modifier.width(64.dp).height(10.dp), color = TvC.surface3)
                    Spacer(Modifier.height(TvSp.x6))
                    TvText("查询中…", t.meta, TvC.fg3)
                }
                Probe.ABSENT -> {
                    TvText("—", t.numeral, TvC.fg3, weight = TvW.bold)
                    TvText("此服务器没有这一集", t.meta, TvC.fg3)
                }
                Probe.OPAQUE -> {
                    TvText("—", t.numeral, TvC.fg3, weight = TvW.bold)
                    TvText("已找到,规格未知", t.meta, TvC.fg2)
                }
            }
            if (maybe) {
                Spacer(Modifier.weight(1f))
                TvText("可能匹配", t.meta, TvC.warn, weight = TvW.semi)
            }
        }
    }
    if (probe == Probe.ABSENT) {
        // 不可用来源保留但不可聚焦:消失会让人以为片源丢了,可聚焦又白按一次方向键
        Box(modifier.size(TvDim.versionW, TvDim.versionH).clip(TvR.md).background(TvC.surface1).graphicsLayer { alpha = .5f }, content = body)
    } else {
        CoverSurface(TvDim.versionW, TvDim.versionH, focused, selected, fake, modifier = modifier, onClick = onClick, content = body)
    }
}

// ---------------------------------------------------------------- 族 D:进度条

@Composable
fun ProgressBar(
    played: Float, buffered: Float, focused: Boolean, width: Dp,
    chapters: List<Float> = emptyList(), intro: ClosedFloatingPointRange<Float>? = null,
    preview: Float? = null, previewLabel: String = "",
    previewThumb: (@Composable BoxScope.() -> Unit)? = null,
) {
    val barH = if (focused) 7.dp else 4.dp
    val knob = if (focused) 20.dp else 13.dp
    val tall = preview != null && previewThumb != null
    Box(Modifier.width(width).height(if (tall) 110.dp else if (preview != null) 50.dp else 24.dp)) {
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(24.dp)) {
            // 片头区间画在轨道**上方**一条 2dp 细条:画在轨道里的话,播过去之后被琥珀色的已播段盖住就看不见了
            if (intro != null) Box(
                Modifier.align(Alignment.CenterStart).offset(x = width * intro.start, y = -(barH / 2 + 4.dp))
                    .width(width * (intro.endInclusive - intro.start)).height(2.dp).clip(TvR.pill).background(TvC.warn),
            )
            Box(Modifier.align(Alignment.CenterStart).fillMaxWidth().height(barH).clip(TvR.pill).background(Color(0xFF3A3642))) {
                // 缓冲段从播放头画到缓冲末端:demuxer 缓存只往前存,从 0 画是假的
                Box(Modifier.offset(x = width * played).width(width * (buffered - played).coerceAtLeast(0f))
                    .fillMaxHeight().background(Color(0xFF6A6474)))
                Box(Modifier.fillMaxWidth(played).fillMaxHeight().background(TvC.acc))
            }
            chapters.forEach { c ->
                Box(Modifier.align(Alignment.CenterStart).offset(x = width * c).width(2.dp).height(barH + 4.dp)
                    .background(TvC.fg.copy(alpha = .55f)))
            }
            Box(
                Modifier.align(Alignment.CenterStart).offset(x = width * played - knob / 2).size(knob)
                    .then(if (focused) Modifier.border(3.dp, TvC.bg, TvR.pill) else Modifier)
                    .clip(TvR.pill).background(TvC.fg),
            )
            if (preview != null) Box(
                Modifier.align(Alignment.CenterStart).offset(x = width * preview - 1.dp).width(2.dp).height(18.dp).background(TvC.focus),
            )
        }
        if (preview != null) {
            // 预览游标:不立即 seek,松手 700ms 或按确认才发(§8.3)。没有缩略图就只显示时间
            Column(
                Modifier.align(Alignment.TopStart).offset(x = (width * preview - 64.dp).coerceIn(0.dp, width - 128.dp)),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (previewThumb != null) {
                    Box(Modifier.size(128.dp, 72.dp).clip(TvR.md).background(TvC.surface1).border(2.dp, TvC.focus, TvR.md), content = previewThumb)
                    Spacer(Modifier.height(TvSp.x4))
                }
                Box(Modifier.clip(TvR.sm).background(TvC.surface2).padding(horizontal = TvSp.x6, vertical = TvSp.x2)) {
                    TvText(previewLabel, tvType.meta, TvC.fg, mono = true)
                }
            }
        }
    }
}
