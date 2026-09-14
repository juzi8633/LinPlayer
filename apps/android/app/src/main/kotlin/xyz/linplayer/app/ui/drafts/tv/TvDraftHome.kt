package xyz.linplayer.app.ui.drafts.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import xyz.linplayer.app.ui.theme.LpIcons

// ---------------------------------------------------------------- 首页(UI_TV.md §7.2)

@Composable
fun DraftHome(scrolled: Boolean) {
    RailShell(current = 1) {
        val list = rememberLazyListState(initialFirstVisibleItemIndex = if (scrolled) 2 else 0)
        // 往上多露 64dp = 安全区 27 + 行标题 29 + 间距 8:只露出焦点卡的话,行标题被顶出屏幕上沿
        ProvideColumnSpec(above = 64.dp, below = TvDim.safeV) {
            LazyColumn(
                Modifier.fillMaxSize(), state = list,
                contentPadding = PaddingValues(top = TvDim.safeV, bottom = 64.dp),
            ) {
                item { Box(Modifier.padding(start = TvSp.x32, end = TvDim.safeH)) { Hero(focusPlay = !scrolled) } }
                item {
                    HomeSection("继续观看") {
                        items(continueWatching.withIndex().toList()) { (i, c) ->
                            CardWide(c.seed, c.title, c.sub, progress = c.progress, focused = scrolled && i == 1)
                        }
                    }
                }
                item {
                    HomeSection("接下来看") {
                        items(nextUp) { c -> CardWide(c.seed, c.title, c.sub, isNew = c.progress == 0f) }
                    }
                }
                item {
                    HomeSection("电影", link = true) {
                        items(movies) { p -> CardPoster(p.seed, p.title, p.sub, watched = p.watched) }
                    }
                }
                item {
                    HomeSection("剧集", link = true) {
                        items(shows) { p -> CardPoster(p.seed, p.title, p.sub, unplayed = p.unplayed) }
                    }
                }
            }
        }
        DraftNote(if (scrolled) "§4.3 往下:焦点行对齐段顶,行标题一起露出" else "§7.2 Hero 文字上不加任何底 · 焦点在「播放」")
    }
}

@Composable
private fun Hero(focusPlay: Boolean) {
    val t = tvType
    Box(Modifier.fillMaxWidth().height(TvDim.heroH).clip(TvR.lg).background(swatch(3))) {
        // 草稿里的「剧照」:几块暗色几何,只为了让字压在一张有明暗的图上而不是纯色上
        Box(Modifier.align(Alignment.TopEnd).padding(top = 20.dp, end = 60.dp).size(260.dp, 180.dp).clip(TvR.lg).background(swatch(11)))
        Box(Modifier.align(Alignment.TopEnd).padding(top = 70.dp, end = 180.dp).size(140.dp).clip(TvR.pill).background(swatch(5)))
        Column(Modifier.align(Alignment.BottomStart).padding(TvSp.x24).widthIn(max = 480.dp)) {
            TvText("寂静的星河", t.display, TvC.fg, weight = TvW.bold, maxLines = 2)
            Spacer(Modifier.height(TvSp.x4))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TvText("★ 8.6", t.meta, TvC.acc, weight = TvW.semi)
                Spacer(Modifier.width(TvSp.x8))
                TvText("2024 · 科幻 · 悬疑 · 3 季", t.meta, TvC.fg2)
            }
            Spacer(Modifier.height(TvSp.x12))
            Row(horizontalArrangement = Arrangement.spacedBy(TvSp.x12)) {
                TvButton("播放", LpIcons.play, primary = true, focused = focusPlay)
                TvButton("详情", LpIcons.info)
                TvButton("换一部", LpIcons.refresh)
            }
        }
        Row(Modifier.align(Alignment.BottomEnd).padding(TvSp.x24), horizontalArrangement = Arrangement.spacedBy(TvSp.x6),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(16.dp, 6.dp).clip(TvR.pill).background(TvC.acc))
            repeat(4) { Box(Modifier.size(6.dp).clip(TvR.pill).background(TvC.fg.copy(alpha = .3f))) }
        }
    }
}

@Composable
private fun HomeSection(
    title: String, link: Boolean = false,
    rowContent: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    Column(Modifier.padding(top = TvSp.x20)) {
        Box(Modifier.padding(start = TvSp.x24)) { RowTitle(title, link = link) }
        Spacer(Modifier.height(TvSp.x8))
        ProvideRowKeyline(TvSp.x32) {
            LazyRow(
                contentPadding = PaddingValues(start = TvSp.x32, end = TvDim.safeH, top = TvSp.x6, bottom = TvSp.x2),
                horizontalArrangement = Arrangement.spacedBy(TvSp.x12),
                content = rowContent,
            )
        }
    }
}

class WideItem(val seed: Int, val title: String, val sub: String, val progress: Float)
class PosterItem(val seed: Int, val title: String, val sub: String, val watched: Boolean = false, val unplayed: Int = 0)

val continueWatching = listOf(
    WideItem(21, "寂静的星河 · S2E4", "剩 18 分钟", .62f),
    WideItem(22, "幕府将军 · S1E6", "剩 24 分钟", .41f),
    WideItem(23, "沙丘:预言", "剩 1 小时 12 分", .23f),
    WideItem(24, "葬送的芙莉莲 · S1E17", "剩 9 分钟", .7f),
    WideItem(25, "三体 · S1E12", "剩 31 分钟", .15f),
    WideItem(26, "奥本海默", "剩 2 小时 31 分", .08f),
)
val nextUp = listOf(
    WideItem(31, "寂静的星河 · S2E5", "下一集", 0f),
    WideItem(32, "繁花 · S1E9", "下一集", .1f),
    WideItem(33, "漫长的季节 · S1E4", "下一集", .1f),
    WideItem(34, "黑暗荣耀 · S2E2", "下一集", .1f),
    WideItem(35, "间谍过家家 · S2E7", "下一集", .1f),
)
val movies = listOf(
    PosterItem(40, "沙丘 2", "2024 · 2160p"), PosterItem(41, "奥本海默", "2023 · 2160p", watched = true),
    PosterItem(42, "坠落的审判", "2023 · 1080p"), PosterItem(43, "周处除三害", "2024 · 1080p"),
    PosterItem(44, "可怜的东西", "2023 · 2160p"), PosterItem(45, "年会不能停!", "2023 · 1080p"),
    PosterItem(46, "热辣滚烫", "2024 · 1080p"), PosterItem(47, "小丑", "2019 · 2160p", watched = true),
)
val shows = listOf(
    PosterItem(50, "幕府将军", "2024 · 10 集", unplayed = 4), PosterItem(51, "繁花", "2023 · 30 集", unplayed = 21),
    PosterItem(52, "三体", "2023 · 30 集"), PosterItem(53, "漫长的季节", "2023 · 12 集", watched = true),
    PosterItem(54, "寂静的星河", "2024 · 36 集", unplayed = 2), PosterItem(55, "黑暗荣耀", "2022 · 16 集"),
    PosterItem(56, "葬送的芙莉莲", "2023 · 28 集", unplayed = 11),
)

// ---------------------------------------------------------------- 组件表(§4.1 / §5)

private val stateCols = listOf("常态", "聚焦", "选中", "选中 + 聚焦")

@Composable
private fun MatrixHead(title: String, sub: String) {
    val t = tvType
    TvText(title, t.headline, TvC.fg, weight = TvW.semi)
    TvText(sub, t.meta, TvC.fg3)
    Spacer(Modifier.height(TvSp.x12))
    Row {
        Spacer(Modifier.width(96.dp))
        stateCols.forEach { TvText(it, t.meta, TvC.fg3, Modifier.width(196.dp)) }
    }
    Spacer(Modifier.height(TvSp.x6))
}

@Composable
private fun MatrixRow(label: String, cellW: Dp = 196.dp, cells: List<(@Composable () -> Unit)?>) {
    Row(Modifier.padding(vertical = TvSp.x8), verticalAlignment = Alignment.CenterVertically) {
        TvText(label, tvType.body, TvC.fg2, Modifier.width(96.dp))
        cells.forEach { c ->
            Box(Modifier.width(cellW), contentAlignment = Alignment.CenterStart) {
                if (c != null) c() else TvText("—", tvType.meta, TvC.fg3)
            }
        }
    }
}

@Composable
fun DraftComponentsSolid() {
    Box(Modifier.fillMaxSize().padding(horizontal = TvDim.safeH, vertical = TvDim.safeV)) {
        Column {
            MatrixHead("组件表 ①", "焦点 = 白(反色填充),选中 = 琥珀。两条通道互不借用 —— 旧 TV 选中和焦点都用描边,焦点环被吃掉")
            MatrixRow("按钮", cells = listOf(
                { TvButton("收藏", LpIcons.heart) }, { TvButton("收藏", LpIcons.heart, fake = true) }, null, null,
            ))
            MatrixRow("主按钮", cells = listOf(
                { TvButton("继续 S2E4", LpIcons.play, primary = true) },
                { TvButton("继续 S2E4", LpIcons.play, primary = true, fake = true) }, null, null,
            ))
            MatrixRow("单选 chip", cells = listOf(
                { ScopeChips(listOf("第 2 季"), selected = -1) }, { ScopeChips(listOf("第 2 季"), selected = -1, fakeIndex = 0) },
                { ScopeChips(listOf("第 1 季"), selected = 0) }, { ScopeChips(listOf("第 1 季"), selected = 0, fakeIndex = 0) },
            ))
            MatrixRow("取值 chip", cells = listOf(
                { ValueChip("字幕", "简体中文", LpIcons.sub) }, { ValueChip("字幕", "简体中文", LpIcons.sub, fake = true) }, null, null,
            ))
            MatrixRow("面板项", cells = listOf(
                { Box(Modifier.width(180.dp)) { PanelItem("English(内封)") } },
                { Box(Modifier.width(180.dp)) { PanelItem("English(内封)", fake = true) } },
                { Box(Modifier.width(180.dp)) { PanelItem("简体中文(内封)", selected = true) } },
                { Box(Modifier.width(180.dp)) { PanelItem("简体中文(内封)", selected = true, fake = true) } },
            ))
            MatrixRow("输入框", cells = listOf(
                { InputBox("", "搜索片名", LpIcons.search, width = 180.dp) },
                { InputBox("幕府", "", LpIcons.search, focused = true, width = 180.dp) }, null, null,
            ))
            MatrixRow("进度条", cells = listOf(
                { ProgressBar(.42f, .58f, focused = false, width = 180.dp, chapters = listOf(.2f, .7f)) },
                { ProgressBar(.42f, .58f, focused = true, width = 180.dp, chapters = listOf(.2f, .7f)) }, null, null,
            ))
        }
        DraftNote("§4.1 族 B / C / D / E")
    }
}

@Composable
private fun Captioned(caption: String, content: @Composable () -> Unit) {
    Column {
        TvText(caption, tvType.meta, TvC.fg3)
        Spacer(Modifier.height(TvSp.x8))
        content()
    }
}

@Composable
fun DraftComponentsCards() {
    val t = tvType
    Box(Modifier.fillMaxSize().padding(horizontal = TvDim.safeH, vertical = TvDim.safeV)) {
        Column {
            TvText("组件表 ②", t.headline, TvC.fg, weight = TvW.semi)
            TvText("卡片只放大封面(1.06)并在封面外描白框,标题在封面下 6dp 不被压住;版本卡选中 = 琥珀底 + 琥珀框", t.meta, TvC.fg3)
            Spacer(Modifier.height(TvSp.x16))
            Row(horizontalArrangement = Arrangement.spacedBy(TvSp.x32)) {
                Captioned("海报 · 常态") { CardPoster(60, "幕府将军", "2024 · 10 集", unplayed = 4) }
                Captioned("海报 · 聚焦") { CardPoster(60, "幕府将军", "2024 · 10 集", unplayed = 4, fake = true) }
                Captioned("横卡 · 常态") { CardWide(61, "寂静的星河 · S2E4", "剩 18 分钟", progress = .62f) }
                Captioned("横卡 · 聚焦") { CardWide(61, "寂静的星河 · S2E4", "剩 18 分钟", progress = .62f, fake = true) }
                Captioned("分集卡 · 看了一半") { CardEpisode(62, "E4", "E4 · 谎言的代价", "45 分钟 · 剩 12 分钟", progress = .7f) }
            }
            Spacer(Modifier.height(TvSp.x20))
            Row(horizontalArrangement = Arrangement.spacedBy(TvSp.x12)) {
                Captioned("版本卡 · 常态") { VersionCard("服务器 B", "1080p", "H264 · 8.1 Mbps · 3.2 GB", "日 AAC · 简") }
                Captioned("聚焦") { VersionCard("服务器 B", "1080p", "H264 · 8.1 Mbps · 3.2 GB", "日 AAC · 简", fake = true) }
                Captioned("选中") { VersionCard("服务器 A", "2160p HDR", "HEVC · 24.6 Mbps · 8.4 GB", "日 TrueHD · 简 / 繁", current = true, selected = true) }
                Captioned("选中 + 聚焦(白框在外,琥珀框在内)") { VersionCard("服务器 A", "2160p HDR", "HEVC · 24.6 Mbps · 8.4 GB", "日 TrueHD · 简 / 繁", current = true, selected = true, fake = true) }
            }
        }
        DraftNote("§4.1 族 A · §5.2 卡片 · §7.6 版本卡")
    }
}

@Composable
fun DraftComponentsMore() {
    val t = tvType
    Box(Modifier.fillMaxSize().padding(horizontal = TvDim.safeH, vertical = TvDim.safeV)) {
        Column {
            TvText("组件表 ③", t.headline, TvC.fg, weight = TvW.semi)
            TvText("导航轨项不放大(96dp 的轨里放大会撑破边界);不可用的版本卡保留但不可聚焦", t.meta, TvC.fg3)
            Spacer(Modifier.height(TvSp.x16))
            Row(horizontalArrangement = Arrangement.spacedBy(TvSp.x24)) {
                Captioned("轨项 · 常态") { RailItem("媒体库", LpIcons.grid, on = false) }
                Captioned("当前页") { RailItem("首页", LpIcons.home, on = true) }
                Captioned("聚焦") { RailItem("媒体库", LpIcons.grid, on = false, fake = true) }
                Captioned("当前页 + 聚焦") { RailItem("首页", LpIcons.home, on = true, fake = true) }
                Captioned("行标题 · 常态") { RowTitle("电影", link = true) }
                Captioned("行标题 · 聚焦") { RowTitle("电影", link = true, fake = true) }
            }
            Spacer(Modifier.height(TvSp.x20))
            Row(horizontalArrangement = Arrangement.spacedBy(TvSp.x24)) {
                Captioned("开关行 · 常态") { Box(Modifier.width(220.dp)) { PanelItem("跳过片头", switch = true) } }
                Captioned("开关行 · 聚焦(整行是一个焦点)") { Box(Modifier.width(220.dp)) { PanelItem("跳过片尾", switch = false, fake = true) } }
                Captioned("取值行 · 聚焦") { Box(Modifier.width(220.dp)) { PanelItem("字幕语言", value = "中文", chevron = true, fake = true) } }
            }
            Spacer(Modifier.height(TvSp.x20))
            Row(horizontalArrangement = Arrangement.spacedBy(TvSp.x12)) {
                Captioned("版本卡 · 查询中") { VersionCard("服务器 C", probe = Probe.PROBING) }
                Captioned("已找到,规格未知 · 可能匹配") { VersionCard("服务器 D", probe = Probe.OPAQUE, maybe = true) }
                Captioned("没有这一集(不可聚焦)") { VersionCard("服务器 E", probe = Probe.ABSENT) }
                Captioned("角标") {
                    Row(horizontalArrangement = Arrangement.spacedBy(TvSp.x6), verticalAlignment = Alignment.CenterVertically) {
                        Badge("新", TvC.bad, androidx.compose.ui.graphics.Color.White)
                        Badge("继续", TvC.acc, TvC.onAcc)
                        Badge("12", TvC.acc, TvC.onAcc)
                        Badge("1", TvC.acc, TvC.onAcc)
                        Badge("7", TvC.fg.copy(alpha = .82f), TvC.bg)
                    }
                }
            }
        }
        DraftNote("§3.1 轨项 · §7.13 设置行型 · §7.6 探测四态")
    }
}
