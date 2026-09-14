package xyz.linplayer.app.ui.drafts.tv

import xyz.linplayer.app.tv.kit.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import xyz.linplayer.app.ui.theme.LpIcons

/*
 * 播放页草稿(UI_TV.md §8)。
 * ★ 真机上视频在 SurfaceView 里,截图工具截不到;草稿用色块代替画面,只评 OSD 版式。
 */

@Composable
private fun FakeVideo(seed: Int = 7) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.fillMaxSize().background(swatch(seed)))
        Box(Modifier.align(Alignment.CenterEnd).padding(end = 120.dp).size(300.dp, 220.dp).clip(TvR.lg).background(swatch(seed + 3)))
        Box(Modifier.align(Alignment.BottomStart).padding(start = 80.dp, bottom = 90.dp).size(360.dp, 140.dp).clip(TvR.lg).background(swatch(seed + 5)))
    }
}

@Composable
private fun TimeChip(text: String) {
    Box(Modifier.clip(TvR.sm).background(TvC.surface2).padding(horizontal = TvSp.x6, vertical = TvSp.x2)) {
        TvText(text, tvType.meta, TvC.fg, mono = true)
    }
}

/** 顶栏:标题栏本身全透明,只有标题块自带不透明底(§2.2)。 */
@Composable
private fun BoxScope.OsdTop(skip: Boolean, skipFocused: Boolean = false) {
    val t = tvType
    Column(
        Modifier.align(Alignment.TopStart).padding(start = TvDim.safeH, top = TvDim.safeV)
            .clip(TvR.md).background(TvC.surface2).padding(horizontal = TvSp.x12, vertical = TvSp.x8),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TvText("幕府将军 · S1E06 妾之王国", t.body, TvC.fg, weight = TvW.semi)
            Spacer(Modifier.width(TvSp.x8))
            TvText("2.1 MB/s", t.meta, TvC.fg2, mono = true)
        }
        TvText("2160p HDR · TrueHD 7.1 · 简体中文", t.meta, TvC.fg2)
    }
    if (skip) Box(Modifier.align(Alignment.TopEnd).padding(end = TvDim.safeH, top = TvDim.safeV)) {
        TvButton("跳过片头", LpIcons.skipNext, focused = skipFocused)
    }
}

@Composable
private fun BoxScope.OsdBottom(playFocused: Boolean, barFocused: Boolean = false, preview: Float? = null) {
    Column(
        Modifier.align(Alignment.BottomStart).fillMaxWidth()
            .padding(start = TvDim.safeH, end = TvDim.safeH, bottom = TvDim.safeV),
    ) {
        ProgressBar(
            played = .6f, buffered = .72f, focused = barFocused, width = 864.dp,
            chapters = listOf(.06f, .31f, .55f, .93f), intro = 0f..0.06f,
            preview = preview, previewLabel = "41:30",
            previewThumb = { Box(Modifier.fillMaxSize().background(swatch(17))) },
        )
        Spacer(Modifier.height(TvSp.x4))
        Row {
            TimeChip("36:12")
            Spacer(Modifier.weight(1f))
            TimeChip("-24:48")
        }
        Spacer(Modifier.height(TvSp.x12))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(TvSp.x8), verticalAlignment = Alignment.CenterVertically) {
                TvIconButton(LpIcons.skipPrev, 36.dp)
                TvIconButton(LpIcons.rewind, 36.dp)
                TvIconButton(LpIcons.pause, 44.dp, primary = true, focused = playFocused)
                TvIconButton(LpIcons.forward, 36.dp)
                TvIconButton(LpIcons.skipNext, 36.dp)
            }
            // 右组和播控拉开距离,防误触
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(TvSp.x8)) {
                TvButton("字幕", LpIcons.sub)
                TvButton("音轨", LpIcons.audio)
                TvButton("弹幕", LpIcons.danmaku)
                TvButton("选集", LpIcons.list)
                TvButton("更多", LpIcons.more)
            }
        }
    }
}

@Composable
fun DraftPlayerOsd() {
    Box(Modifier.fillMaxSize()) {
        FakeVideo()
        OsdTop(skip = false)
        OsdBottom(playFocused = true)
        DraftNote("§8.2 进度条上方黄色细条 = 片头区间 · 白色刻度 = 章节")
    }
}

@Composable
fun DraftPlayerSeek() {
    Box(Modifier.fillMaxSize()) {
        FakeVideo()
        OsdTop(skip = false)
        OsdBottom(playFocused = false, barFocused = true, preview = .69f)
        DraftNote("§8.3 ←/→ 只移预览游标,松手 700ms 或按确认才 seek")
    }
}

@Composable
fun DraftPlayerSubtitles() {
    Box(Modifier.fillMaxSize()) {
        FakeVideo()
        PanelSlot {
            SidePanel("字幕") {
                PanelGroup("轨道")
                PanelItem("简体中文(内封)", selected = true, focused = true)
                PanelItem("繁體中文(内封)")
                PanelItem("English(内封)", sub = "PGS 图形字幕")
                PanelItem("简日双语.ass", sub = "外挂")
                PanelItem("关闭字幕")
                PanelGroup("样式")
                PanelItem("字号", value = "1.0x", chevron = true)
                PanelItem("延迟", value = "0.0s", step = true)
            }
        }
        DraftNote("§8.4 面板开着 OSD 收起 · 无遮罩 · 默认焦点在当前生效的轨")
    }
}

@Composable
fun DraftPlayerMore() {
    Box(Modifier.fillMaxSize()) {
        FakeVideo(9)
        PanelSlot {
            SidePanel("更多") {
                PanelGroup("画面")
                PanelItem("画面比例", value = "自动", chevron = true)
                PanelItem("画面增强", value = "关", chevron = true, focused = true)
                PanelItem("版本", value = "2160p HDR", chevron = true)
                PanelItem("线路", value = "线路一", chevron = true)
                PanelGroup("播放")
                PanelItem("倍速", value = "1.0x", chevron = true)
                PanelItem("跳过片头", switch = true)
                PanelItem("跳过片尾", switch = false)
                PanelGroup("工具")
                PanelItem("换内核重播", value = "mpv → ExoPlayer")
            }
        }
        DraftNote("§8.4 带 › 的项确认后面板内容整体替换,不叠第二层")
    }
}

@Composable
fun DraftPlayerMoments() {
    val t = tvType
    Box(Modifier.fillMaxSize()) {
        FakeVideo(12)
        Row(
            Modifier.align(Alignment.TopCenter).padding(top = TvDim.safeV).clip(TvR.pill).background(TvC.surface2)
                .padding(horizontal = TvSp.x16, vertical = TvSp.x6),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvText("3.0x", t.title, TvC.acc, weight = TvW.bold)
            Spacer(Modifier.width(TvSp.x6))
            Icon(LpIcons.forward, null, Modifier.size(t.iconS), tint = TvC.acc)
            Spacer(Modifier.width(TvSp.x8))
            TvText("松开恢复", t.meta, TvC.fg2)
        }
        Box(Modifier.align(Alignment.TopEnd).padding(end = TvDim.safeH, top = TvDim.safeV)) {
            TvButton("跳过片头", LpIcons.skipNext, focused = true)
        }
        Box(Modifier.align(Alignment.Center).size(64.dp).clip(TvR.pill).background(TvC.surface2), contentAlignment = Alignment.Center) {
            Icon(LpIcons.pause, null, Modifier.size(28.dp), tint = TvC.fg)
        }
        TvToast("再按一次返回退出播放", dot = TvC.warn)
        DraftNote("§4.4 / §8.5 四个瞬时提示放在同一张图里,实际不会同时出现")
    }
}

@Composable
fun DraftPlayerNextUp() {
    Box(Modifier.fillMaxSize()) {
        FakeVideo(14)
        Box(Modifier.align(Alignment.BottomEnd).padding(end = TvDim.safeH, bottom = TvDim.safeV)) {
            Surface(
                onClick = {},
                modifier = Modifier.initialFocus(true),
                shape = ClickableSurfaceDefaults.shape(TvR.lg),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = TvC.surface2, contentColor = TvC.fg,
                    focusedContainerColor = TvC.focus, focusedContentColor = TvC.onFocus,
                ),
            ) {
                Row(Modifier.padding(TvSp.x12), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(128.dp, 72.dp).clip(TvR.md).background(swatch(28)), contentAlignment = Alignment.Center) {
                        val acc = TvC.acc
                        Box(
                            Modifier.size(34.dp).drawBehind {
                                drawCircle(Color(0x99000000))
                                drawArc(acc, -90f, 360f * .6f, false, style = Stroke(3.dp.toPx()))
                            },
                            contentAlignment = Alignment.Center,
                        ) { Text("3", color = Color.White, fontSize = tvType.body, fontWeight = TvW.bold) }
                    }
                    Spacer(Modifier.width(TvSp.x12))
                    Column(Modifier.width(188.dp)) {
                        Text("下一集", fontSize = tvType.meta)
                        Text("E07 · 天之御者", fontSize = tvType.body, fontWeight = TvW.semi, maxLines = 2)
                        Spacer(Modifier.height(TvSp.x2))
                        Text("确认立即播放 · 返回取消", fontSize = tvType.meta)
                    }
                }
            }
        }
        DraftNote("§8.5 播完且有下一集:5 秒倒计时,焦点落在卡上")
    }
}

@Composable
fun DraftPlayerFailed() {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        FullState(
            LpIcons.info, "无法播放这个文件",
            sub = "已自动重试 1 次仍然失败", detail = "服务端返回 500 · 转码进程未启动",
            buttons = listOf("重试", "换内核重播", "线路管理"), tone = TvC.bad,
        )
        DraftNote("§8.6 第一行给人看,第二行给排查用 · 换线路不在这里做")
    }
}

@Composable
fun DraftPlayerNoVideo() {
    val t = tvType
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color.Black))
        Column(
            Modifier.align(Alignment.Center).widthIn(max = 520.dp).clip(TvR.lg).background(TvC.surface2)
                .border(2.dp, TvC.bad, TvR.lg).padding(horizontal = TvSp.x24, vertical = TvSp.x20),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TvText("只有声音,没有画面", t.title, TvC.fg, weight = TvW.semi)
            Spacer(Modifier.height(TvSp.x8))
            TvText("视频输出没有建起来(vo 为空,hwdec = mediacodec)", t.body, TvC.fg2, maxLines = 2)
            Spacer(Modifier.height(TvSp.x6))
            TvText("按返回键退出播放。把这段话报给开发者。", t.meta, TvC.fg3)
        }
        OsdTop(skip = false)
        DraftNote("§8.6 故障牌不随 OSD 收起 —— 它是故障说明,不是控件")
    }
}
