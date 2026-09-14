package xyz.linplayer.app.ui.drafts.tv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import xyz.linplayer.app.ui.theme.LpIcons

/*
 * TV 草稿的组件(UI_TV.md §5)。页面只能用这些件拼。
 *
 * ★ 焦点和选中走两条通道(§4.1):焦点 = 白,选中 = 琥珀。
 *   旧 TV 两者都用描边,选中且聚焦的版本卡焦点环被吃掉 —— 每个件都要把四种组合画得分得开。
 *
 * 参数口径:`focused` = 进页时真的去要焦点(真机上就是初始焦点);
 * `fake` = 不要焦点、只**画成**聚焦的样子 —— 组件表要在一张图里同时摆出多个焦点态,而真焦点同一时刻只有一个。
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
)

@Composable
fun TvButton(
    label: String, icon: ImageVector? = null, primary: Boolean = false,
    focused: Boolean = false, fake: Boolean = false, modifier: Modifier = Modifier,
) {
    val t = tvType
    Surface(
        onClick = {},
        modifier = modifier.height(t.controlH).initialFocus(focused).fakeScale(fake, 1.04f),
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
) {
    Surface(
        onClick = {},
        modifier = Modifier.size(size).initialFocus(focused).fakeScale(fake, 1.08f),
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
fun EntryChip(label: String, icon: ImageVector, condition: String? = null, focused: Boolean = false, fake: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TvButton(label, icon, focused = focused, fake = fake)
        if (condition != null) {
            Spacer(Modifier.width(TvSp.x16))
            TvText(condition, tvType.meta, TvC.fg3)
        }
    }
}

/** PickBar 的 chip:按钮里带当前值 —— 三米外「字幕」两个字看不出现在是哪条轨。 */
@Composable
fun ValueChip(label: String, value: String, icon: ImageVector, focused: Boolean = false, fake: Boolean = false) {
    val t = tvType
    Surface(
        onClick = {},
        modifier = Modifier.height(t.controlH).initialFocus(focused).fakeScale(fake, 1.04f),
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
fun ScopeChips(items: List<String>, selected: Int, focusedIndex: Int = -1, fakeIndex: Int = -1) {
    val t = tvType
    Row(horizontalArrangement = Arrangement.spacedBy(TvSp.x8)) {
        items.forEachIndexed { i, s ->
            Surface(
                onClick = {},
                modifier = Modifier.height(t.controlH).initialFocus(i == focusedIndex).fakeScale(i == fakeIndex, 1.04f),
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

// ---------------------------------------------------------------- 族 A:卡片

// inset 为正 = 框画在封面**外面**(实测负值会画进封面里,压住图)
private val focusFrame = Border(BorderStroke(2.dp, TvC.focus), inset = 3.dp, shape = TvR.md)

/** 族 A:只有封面放大并描白框;标题在封面下 6dp,放大 1.06 刚好不压字(§4.1)。 */
@Composable
fun CoverSurface(
    w: Dp, h: Dp, focused: Boolean = false, selected: Boolean = false, fake: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val selectedFrame = Border(BorderStroke(1.5.dp, TvC.acc), shape = TvR.md)
    Surface(
        onClick = {},
        modifier = Modifier.size(w, h).initialFocus(focused).fakeScale(fake, 1.06f),
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
    ) {
        Box(Modifier.fillMaxSize()) {
            content()
            // 选中且聚焦:白框在外、琥珀框在内,两个都看得见
            if (selected && fake) Box(Modifier.fillMaxSize().border(1.5.dp, TvC.acc, TvR.md))
        }
    }
}

@Composable
fun Cover(seed: Int, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit = {}) {
    Box(modifier.fillMaxSize().background(swatch(seed)), content = content)
}

@Composable
fun ProgressLine(p: Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(3.dp).background(Color(0x8C000000))) {
        Box(Modifier.fillMaxWidth(p).fillMaxHeight().background(TvC.acc))
    }
}

@Composable
fun CardWide(
    seed: Int, title: String, sub: String, progress: Float? = null, isNew: Boolean = false,
    focused: Boolean = false, fake: Boolean = false, w: Dp = TvDim.wideW, h: Dp = TvDim.wideH,
) {
    val t = tvType
    Column(Modifier.width(w)) {
        CoverSurface(w, h, focused, fake = fake) {
            Cover(seed)
            if (progress != null) ProgressLine(progress, Modifier.align(Alignment.BottomStart))
            if (isNew) Badge("新", TvC.bad, Color.White, Modifier.align(Alignment.TopEnd).padding(TvSp.x6))
        }
        Spacer(Modifier.height(TvSp.x6))
        TvText(title, t.body, TvC.fg, weight = TvW.medium)
        TvText(sub, t.meta, TvC.fg2)
    }
}

@Composable
fun CardPoster(
    seed: Int, title: String, sub: String, watched: Boolean = false, unplayed: Int = 0,
    rank: Int = 0, focused: Boolean = false, fake: Boolean = false, w: Dp = TvDim.posterW, h: Dp = TvDim.posterH,
) {
    val t = tvType
    Column(Modifier.width(w)) {
        CoverSurface(w, h, focused, fake = fake) {
            Cover(seed)
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
        TvText(title, t.body, TvC.fg, weight = TvW.medium)
        TvText(sub, t.meta, TvC.fg2)
    }
}

@Composable
fun CardEpisode(
    seed: Int, no: String, title: String, sub: String, progress: Float? = null,
    done: Boolean = false, current: Boolean = false, focused: Boolean = false, fake: Boolean = false,
) {
    val t = tvType
    Column(Modifier.width(TvDim.epW)) {
        CoverSurface(TvDim.epW, TvDim.epH, focused, fake = fake) {
            Cover(seed)
            if (done) Box(Modifier.fillMaxSize().background(TvC.bg.copy(alpha = .3f)))
            Badge(no, Color(0xB8080C12), TvC.fg, Modifier.align(Alignment.TopStart).padding(TvSp.x6))
            when {
                done -> ProgressLine(1f, Modifier.align(Alignment.BottomStart))
                progress != null -> ProgressLine(progress, Modifier.align(Alignment.BottomStart))
            }
            if (progress != null && !done) Badge("继续", TvC.acc, TvC.onAcc, Modifier.align(Alignment.TopEnd).padding(TvSp.x6))
        }
        Spacer(Modifier.height(TvSp.x6))
        TvText(title, t.body, if (current) TvC.acc else if (done) TvC.fg2 else TvC.fg, weight = TvW.medium)
        TvText(sub, t.meta, TvC.fg2)
    }
}

@Composable
fun CardLibrary(seed: Int, name: String, blocked: Boolean = false, focused: Boolean = false, fake: Boolean = false) {
    val t = tvType
    Column(Modifier.width(TvDim.libW)) {
        CoverSurface(TvDim.libW, TvDim.libH, focused, fake = fake) {
            Cover(seed)
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
    focused: Boolean = false, fake: Boolean = false,
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
        Box(Modifier.size(TvDim.versionW, TvDim.versionH).clip(TvR.md).background(TvC.surface1).graphicsLayer { alpha = .5f }, content = body)
    } else {
        CoverSurface(TvDim.versionW, TvDim.versionH, focused, selected, fake, body)
    }
}

// ---------------------------------------------------------------- 族 D:进度条

@Composable
fun ProgressBar(
    played: Float, buffered: Float, focused: Boolean, width: Dp,
    chapters: List<Float> = emptyList(), intro: ClosedFloatingPointRange<Float>? = null,
    preview: Float? = null, previewLabel: String = "",
) {
    val barH = if (focused) 7.dp else 4.dp
    val knob = if (focused) 20.dp else 13.dp
    Box(Modifier.width(width).height(if (preview != null) 110.dp else 24.dp)) {
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
            // 预览游标:不立即 seek,松手 700ms 或按确认才发(§8.3)
            Column(
                Modifier.align(Alignment.TopStart).offset(x = (width * preview - 64.dp).coerceAtLeast(0.dp)),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.size(128.dp, 72.dp).clip(TvR.md).background(swatch(17)).border(2.dp, TvC.focus, TvR.md))
                Spacer(Modifier.height(TvSp.x4))
                Box(Modifier.clip(TvR.sm).background(TvC.surface2).padding(horizontal = TvSp.x6, vertical = TvSp.x2)) {
                    TvText(previewLabel, tvType.meta, TvC.fg, mono = true)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 导航轨(§3.1)

val railItems = listOf(
    "搜索" to LpIcons.search, "首页" to LpIcons.home, "媒体库" to LpIcons.grid,
    "收藏" to LpIcons.heart, "发现" to LpIcons.trophy, "下载" to LpIcons.download,
    "服务器" to LpIcons.server, "设置" to LpIcons.settings,
)

@Composable
fun Rail(current: Int, focused: Int = -1, fake: Int = -1) {
    val t = tvType
    Column(
        Modifier.width(TvDim.railW).fillMaxHeight().background(TvC.rail).padding(vertical = TvDim.safeV),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(28.dp).clip(TvR.md).background(TvC.acc), contentAlignment = Alignment.Center) {
            Icon(LpIcons.play, null, Modifier.size(14.dp), tint = TvC.onAcc)
        }
        Spacer(Modifier.height(TvSp.x12))
        railItems.forEachIndexed { i, (label, icon) ->
            if (i == 6) {
                Spacer(Modifier.weight(1f))
                Box(Modifier.width(48.dp).height(1.dp).background(TvC.line))
                Spacer(Modifier.height(TvSp.x4))
            }
            RailItem(label, icon, on = i == current, focused = i == focused, fake = i == fake)
        }
    }
}

@Composable
fun RailItem(label: String, icon: ImageVector, on: Boolean, focused: Boolean = false, fake: Boolean = false) {
    Surface(
        onClick = {},
        modifier = Modifier.padding(vertical = TvSp.x2).size(72.dp, 50.dp).initialFocus(focused),
        shape = ClickableSurfaceDefaults.shape(TvR.md),
        // 不放大:96dp 的轨里放大会撑破边界
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 0.97f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (fake) TvC.focus else if (on) TvC.accDim else Color.Transparent,
            contentColor = if (fake) TvC.onFocus else if (on) TvC.acc else TvC.fg3,
            focusedContainerColor = TvC.focus, focusedContentColor = TvC.onFocus,
        ),
    ) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(icon, null, Modifier.size(22.dp))
            Spacer(Modifier.height(TvSp.x2))
            Text(label, fontSize = tvType.meta, fontWeight = if (on) TvW.semi else FontWeight.Normal, maxLines = 1)
        }
    }
}

/** 带轨的页面外壳。内容区从 x=96 开始,左边 32dp 由各页自己让(横向行要留放大余量)。 */
@Composable
fun RailShell(current: Int, railFocus: Int = -1, content: @Composable BoxScope.() -> Unit) {
    Row(Modifier.fillMaxSize().background(TvC.bg)) {
        Rail(current, railFocus)
        Box(Modifier.weight(1f).fillMaxHeight(), content = content)
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
fun SidePanel(title: String, back: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val scroll = rememberScrollState()
    Column(
        Modifier.width(TvDim.panelW).heightIn(max = TvDim.panelMaxH)
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

@Composable
fun PanelItem(
    label: String, value: String? = null, selected: Boolean = false, focused: Boolean = false, fake: Boolean = false,
    danger: Boolean = false, chevron: Boolean = false, step: Boolean = false, enabled: Boolean = true,
    sub: String? = null, switch: Boolean? = null, check: Boolean = true,
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
    Surface(
        onClick = {},
        modifier = Modifier.padding(horizontal = TvSp.x6).fillMaxWidth().heightIn(min = t.rowH).initialFocus(focused),
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
) {
    val t = tvType
    Column(modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(64.dp).clip(TvR.lg).background(tone.copy(alpha = .16f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(32.dp), tint = tone)
        }
        Spacer(Modifier.height(TvSp.x16))
        TvText(title, t.title, TvC.fg, weight = TvW.semi)
        if (sub != null) { Spacer(Modifier.height(TvSp.x6)); TvText(sub, t.body, TvC.fg2) }
        if (detail != null) { Spacer(Modifier.height(TvSp.x4)); TvText(detail, t.meta, TvC.fg3) }
        Spacer(Modifier.height(TvSp.x20))
        Row(horizontalArrangement = Arrangement.spacedBy(TvSp.x12)) {
            buttons.forEachIndexed { i, b -> TvButton(b, focused = focusFirst && i == 0) }
        }
    }
}

@Composable
fun StatusDot(color: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).clip(TvR.pill).background(color))
}

/** 行标题。link = 可聚焦(媒体库行),聚焦时反色 + ›。 */
@Composable
fun RowTitle(text: String, link: Boolean = false, focused: Boolean = false, fake: Boolean = false, trailing: String? = null) {
    val t = tvType
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (link) {
            Surface(
                onClick = {},
                modifier = Modifier.initialFocus(focused),
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

/** 输入框(族 E)。草稿里只画外观 —— 真输入框用 BasicTextField 的 String 重载(§4.5)。 */
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

/** 草稿标注:这一屏在演示什么。不属于设计本身。 */
@Composable
fun BoxScope.DraftNote(text: String) {
    Box(
        Modifier.align(Alignment.BottomEnd).padding(TvSp.x4).clip(TvR.sm)
            .background(Color(0xE6000000)).padding(horizontal = TvSp.x6, vertical = TvSp.x2),
    ) { Text(text, color = Color(0xFFFFD27A), fontSize = 9.sp) }
}
