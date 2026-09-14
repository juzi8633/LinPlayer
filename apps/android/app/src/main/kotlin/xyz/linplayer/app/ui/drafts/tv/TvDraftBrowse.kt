package xyz.linplayer.app.ui.drafts.tv

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import xyz.linplayer.app.ui.theme.LpIcons

/* 媒体库 · 详情页族草稿(UI_TV.md §7.3–§7.6)。 */

// ---------------------------------------------------------------- 媒体库

@Composable
fun DraftLibraryPicker() {
    RailShell(current = 2) {
        Column(Modifier.contentArea()) {
            PageHead("媒体库", sub = "选择一个库")
            Spacer(Modifier.height(TvSp.x16))
            val libs = listOf("电影", "剧集", "动漫", "纪录片", "华语剧集", "外语剧集", "演唱会", "儿童")
            LazyVerticalGrid(
                GridCells.Fixed(4), horizontalArrangement = Arrangement.spacedBy(TvSp.x32),
                verticalArrangement = Arrangement.spacedBy(TvSp.x20), contentPadding = PaddingValues(12.dp), modifier = Modifier.bleed(12.dp),
            ) {
                itemsIndexed(libs) { i, n -> CardLibrary(70 + i, n, blocked = n == "儿童", focused = i == 0) }
            }
        }
        DraftNote("§7.3 已屏蔽的库也列出来 —— 这是唯一能解除它的地方")
    }
}

@Composable
fun DraftLibraryGrid(panel: Boolean) {
    RailShell(current = 2) {
        Column(Modifier.contentArea()) {
            PageHead("电影", count = "1,284 项")
            Spacer(Modifier.height(TvSp.x12))
            EntryChip("筛选与排序", LpIcons.filter, "更新时间 ↓ · 科幻 · 全部年份 · 未看", focused = !panel)
            Spacer(Modifier.height(TvSp.x16))
            LazyVerticalGrid(
                GridCells.Fixed(6), horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(TvSp.x20), contentPadding = PaddingValues(12.dp), modifier = Modifier.bleed(12.dp),
            ) {
                itemsIndexed(gridTitles) { i, (n, y) ->
                    CardPoster(80 + i, n, y, watched = i % 5 == 3, w = 124.dp, h = 186.dp)
                }
            }
        }
        if (panel) PanelSlot {
            SidePanel("筛选与排序") {
                PanelGroup("排序")
                PanelItem("更新时间", selected = true, focused = true)
                PanelItem("加入日期")
                PanelItem("名称")
                PanelItem("评分")
                PanelGroup("类型")
                PanelItem("全部类型")
                PanelItem("科幻", selected = true)
                PanelItem("剧情")
                PanelGroup("状态")
                PanelItem("全部")
                PanelItem("未看", selected = true)
            }
        }
        DraftNote(if (panel) "§7.3 选一项不关面板 · 默认焦点在当前生效值" else "§7.3 当前条件写在入口右边,不可聚焦 · 网格第一行按 ↑ 回到入口")
    }
}

private val gridTitles = listOf(
    "沙丘 2" to "2024", "奥本海默" to "2023", "星际穿越" to "2014", "银翼杀手 2049" to "2017", "降临" to "2016", "火星救援" to "2015",
    "流浪地球 2" to "2023", "异形:夺命舰" to "2024", "瞬息全宇宙" to "2022", "信条" to "2020", "湮灭" to "2018", "月球" to "2009",
    "机械姬" to "2014", "她" to "2013", "盗梦空间" to "2010", "地心引力" to "2013", "阿凡达:水之道" to "2022", "头号玩家" to "2018",
)

// ---------------------------------------------------------------- 详情页共用

/** 背景图铺满 + **均匀**遮罩(§2.2,【用户定】「不然看不清字真的很伤」),不渐变、不模糊。 */
@Composable
private fun Backdrop(seed: Int) {
    Box(Modifier.fillMaxSize().background(swatch(seed))) {
        Box(Modifier.align(Alignment.TopEnd).padding(top = 30.dp, end = 70.dp).size(420.dp, 260.dp).clip(TvR.lg).background(swatch(seed + 4)))
        Box(Modifier.align(Alignment.CenterEnd).padding(end = 240.dp).size(220.dp).clip(TvR.pill).background(swatch(seed + 2)))
        Box(Modifier.fillMaxSize().background(TvC.scrim))
    }
}

@Composable
private fun DetailHead(title: String, meta: String, tags: List<String> = emptyList(), overview: String, lines: Int) {
    val t = tvType
    TvText(title, t.display, TvC.fg, weight = TvW.bold)
    Spacer(Modifier.height(TvSp.x4))
    Row(verticalAlignment = Alignment.CenterVertically) {
        TvText("★ 8.6", t.meta, TvC.acc, weight = TvW.semi)
        Spacer(Modifier.width(TvSp.x8))
        TvText(meta, t.meta, TvC.fg2)
        tags.forEach {
            Spacer(Modifier.width(TvSp.x6))
            Badge(it, TvC.surface3, TvC.fg2)
        }
    }
    Spacer(Modifier.height(TvSp.x8))
    TvText(overview, t.body, TvC.fg2, Modifier.widthIn(max = 560.dp), maxLines = lines)
}

// ---------------------------------------------------------------- 剧详情

@Composable
fun DraftSeriesDetail() {
    Box(Modifier.fillMaxSize()) {
        Backdrop(3)
        Column(Modifier.fillMaxSize().padding(top = TvDim.safeV)) {
            Column(Modifier.padding(horizontal = TvDim.safeH)) {
                DetailHead(
                    "寂静的星河", "2024 · 3 季 · 36 集 · 科幻 / 悬疑",
                    overview = "一支勘探队在柯伊伯带外沿收到一段重复的信号。信号的内容,是他们自己三年后发出的求救。为了弄清那三年里发生了什么,他们必须先决定要不要回头。",
                    lines = 3,
                )
                Spacer(Modifier.height(TvSp.x16))
                Row(horizontalArrangement = Arrangement.spacedBy(TvSp.x12)) {
                    TvButton("继续 S2E4", LpIcons.play, primary = true, focused = true)
                    TvButton("从头播放", LpIcons.refresh)
                    TvButton("收藏", LpIcons.heart)
                    TvButton("更多", LpIcons.more)
                }
                Spacer(Modifier.height(TvSp.x16))
                // 默认选中主按钮目标集所在的季,不是第 1 季
                ScopeChips(listOf("第 1 季", "第 2 季", "第 3 季"), selected = 1)
                Spacer(Modifier.height(TvSp.x12))
            }
            ProvideRowKeyline(TvDim.safeH) {
                LazyRow(
                    contentPadding = PaddingValues(start = TvDim.safeH, end = TvDim.safeH, top = TvSp.x6),
                    horizontalArrangement = Arrangement.spacedBy(TvSp.x12),
                ) {
                    itemsIndexed(episodeNames) { i, n ->
                        CardEpisode(
                            90 + i, "E${i + 1}", "E${i + 1} · $n",
                            when { i < 3 -> "45 分钟 · 已看完"; i == 3 -> "45 分钟 · 剩 17 分钟"; else -> "45 分钟" },
                            progress = if (i == 3) .62f else null, done = i < 3,
                        )
                    }
                }
            }
            Spacer(Modifier.height(TvSp.x20))
            Box(Modifier.padding(start = TvSp.x24 + TvSp.x16)) { RowTitle("相似推荐") }
            Spacer(Modifier.height(TvSp.x8))
            Row(Modifier.padding(start = TvDim.safeH), horizontalArrangement = Arrangement.spacedBy(TvSp.x12)) {
                repeat(7) { i -> CardPoster(120 + i, movies[i].title, movies[i].sub) }
            }
        }
        DraftNote("§7.4 剧的层级不放版本/音轨/字幕 · 下面露出半行 = 还能往下走")
    }
}

private val episodeNames = listOf(
    "信号", "第三个坐标", "静默区", "谎言的代价", "回声", "折返点", "冰层之下", "第 1127 天", "原点", "寂静",
)

// ---------------------------------------------------------------- 电影详情

@Composable
fun DraftMovieDetail() {
    Box(Modifier.fillMaxSize()) {
        Backdrop(4)
        Column(Modifier.fillMaxSize().padding(horizontal = TvDim.safeH).padding(top = TvDim.safeV)) {
            DetailHead(
                "沙丘 2", "2024 · 166 分钟 · 科幻 / 冒险", tags = listOf("2160p", "HDR", "TrueHD 7.1", "已看 18%"),
                overview = "保罗·厄崔迪与契妮和弗雷曼人联手,向摧毁他家族的阴谋者复仇。在一生挚爱与已知宇宙的命运之间,他必须做出选择。",
                lines = 2,
            )
            Spacer(Modifier.height(TvSp.x16))
            Row(horizontalArrangement = Arrangement.spacedBy(TvSp.x12)) {
                TvButton("继续播放 29:48", LpIcons.play, primary = true, focused = true)
                TvButton("从头播放", LpIcons.refresh)
                TvButton("收藏", LpIcons.heart)
                TvButton("更多", LpIcons.more)
            }
            Spacer(Modifier.height(TvSp.x12))
            PickBar()
            Spacer(Modifier.height(TvSp.x20))
            RowTitle("版本", trailing = "来自 3 台服务器")
            Spacer(Modifier.height(TvSp.x8))
            Row(horizontalArrangement = Arrangement.spacedBy(TvSp.x12)) {
                VersionCard("服务器 A", "2160p HDR", "HEVC · 58.2 Mbps · 71 GB", "英 TrueHD · 简 / 繁 / 英", current = true, selected = true)
                VersionCard("服务器 B", "1080p", "H264 · 12.4 Mbps · 15 GB", "英 AC3 · 简")
                VersionCard("服务器 C", probe = Probe.PROBING)
                VersionCard("服务器 D", probe = Probe.ABSENT)
            }
            Spacer(Modifier.height(TvSp.x20))
            RowTitle("演职人员")
            Spacer(Modifier.height(TvSp.x8))
            Row(horizontalArrangement = Arrangement.spacedBy(TvSp.x20)) {
                listOf("提莫西·查拉梅", "赞达亚", "丽贝卡·弗格森", "奥斯汀·巴特勒", "弗洛伦丝·皮尤", "哈维尔·巴登").forEachIndexed { i, n ->
                    Column(Modifier.width(72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(56.dp).clip(TvR.pill).background(swatch(160 + i)))
                        Spacer(Modifier.height(TvSp.x4))
                        TvText(n, tvType.meta, TvC.fg2)
                    }
                }
            }
        }
        DraftNote("§7.5 PickBar 按钮里带当前值 · 版本卡与集详情页是同一个件")
    }
}

@Composable
private fun PickBar(focusVersion: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(TvSp.x8)) {
        ValueChip("版本", "2160p HDR", LpIcons.version, focused = focusVersion)
        ValueChip("音频", "日语 TrueHD", LpIcons.audio)
        ValueChip("字幕", "简体中文", LpIcons.sub)
        ValueChip("线路", "线路一", LpIcons.line)
    }
}

// ---------------------------------------------------------------- 集详情

@Composable
fun DraftEpisode(detailView: Boolean) {
    val t = tvType
    // 详细视图下集卡更高,焦点在集数栏时整页往上走一段
    val scroll = rememberScrollState(if (detailView) with(LocalDensity.current) { 150.dp.roundToPx() } else 0)
    Box(Modifier.fillMaxSize()) {
        Backdrop(5)
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(top = TvDim.safeV, bottom = TvDim.safeV)) {
            Column(Modifier.padding(horizontal = TvDim.safeH)) {
                TvText("幕府将军 · 第 1 季", t.meta, TvC.fg3)
                Spacer(Modifier.height(TvSp.x2))
                TvText("E08 · 大地之心", t.display, TvC.fg, weight = TvW.bold)
                Spacer(Modifier.height(TvSp.x2))
                TvText("61 分钟 · 剩 24 分钟 · 2024-04-09 播出", t.meta, TvC.fg2)
                Spacer(Modifier.height(TvSp.x6))
                TvText("虎永的计划在大阪城内暴露。真理子必须在忠诚与信仰之间作出抉择,而布莱克索恩发现自己成了双方都想利用的棋子。",
                    t.body, TvC.fg2, Modifier.widthIn(max = 560.dp), maxLines = 2)
                Spacer(Modifier.height(TvSp.x8))
                Box(Modifier.width(470.dp).height(4.dp).clip(TvR.pill).background(TvC.surface3)) {
                    Box(Modifier.fillMaxWidth(.6f).fillMaxHeight().background(TvC.acc))
                }
                Spacer(Modifier.height(TvSp.x12))
                Row(horizontalArrangement = Arrangement.spacedBy(TvSp.x12)) {
                    TvButton("继续播放 36:12", LpIcons.play, primary = true, focused = !detailView)
                    TvButton("从头播放", LpIcons.refresh)
                    TvButton("标记已看", LpIcons.check)
                    TvButton("收藏", LpIcons.heart)
                    TvButton("更多", LpIcons.more)
                }
                Spacer(Modifier.height(TvSp.x12))
                PickBar()
                Spacer(Modifier.height(TvSp.x16))
                RowTitle("版本", trailing = "来自 3 台服务器")
                Spacer(Modifier.height(TvSp.x6))
                Row(horizontalArrangement = Arrangement.spacedBy(TvSp.x12)) {
                    VersionCard("服务器 A", "2160p HDR", "HEVC · 24.6 Mbps · 8.4 GB", "日 TrueHD · 简 / 繁", current = true, selected = true)
                    VersionCard("服务器 B", "1080p", "H264 · 8.1 Mbps · 3.2 GB", "日 AAC · 简")
                    VersionCard("服务器 C", probe = Probe.OPAQUE, maybe = true)
                }
                Spacer(Modifier.height(TvSp.x16))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RowTitle("选集", trailing = "第 1 季 · 共 24 集")
                    Spacer(Modifier.weight(1f))
                    ScopeChips(listOf("紧凑", "详细"), selected = if (detailView) 1 else 0)
                }
                Spacer(Modifier.height(TvSp.x8))
            }
            if (detailView) {
                ProvideRowKeyline(TvDim.safeH) {
                    LazyRow(
                        contentPadding = PaddingValues(start = TvDim.safeH, end = TvDim.safeH, top = TvSp.x6),
                        horizontalArrangement = Arrangement.spacedBy(TvSp.x12),
                    ) {
                        itemsIndexed((5..14).toList()) { i, n ->
                            CardEpisode(
                                140 + n, "E$n", "E$n · ${shogunNames[n % shogunNames.size]}",
                                if (n < 8) "61 分钟 · 已看完" else if (n == 8) "61 分钟 · 剩 24 分钟" else "58 分钟",
                                progress = if (n == 8) .6f else null, done = n < 8, current = n == 8, focused = n == 8,
                            )
                        }
                    }
                }
            } else {
                // 紧凑:当前集恒居中(E8 在第 7 格)
                Row(Modifier.padding(horizontal = TvDim.safeH), horizontalArrangement = Arrangement.spacedBy(TvSp.x8)) {
                    (2..15).forEach { n -> EpisodeChip("E$n", current = n == 8, watched = n < 8) }
                }
            }
        }
        DraftNote(if (detailView) "§7.6 详细视图:顶部简介保留 · 版本行按 ↓ 落在当前集" else "§7.6 版本卡不抢焦点 · 显示的版本 ≠ 传下去的版本")
    }
}

private val shogunNames = listOf("安针", "仆之二主", "明日之时", "八重之壁", "炉边闲话", "妾之王国", "今日之时", "大地之心", "红天", "冬之夏")

/** 紧凑选集 chip。当前集 = 琥珀底;已看 = 灰字 + 短横;聚焦 = 白。 */
@Composable
private fun EpisodeChip(label: String, current: Boolean, watched: Boolean, focused: Boolean = false) {
    val t = tvType
    Surface(
        onClick = {},
        modifier = Modifier.size(52.dp, 36.dp).initialFocus(focused),
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
