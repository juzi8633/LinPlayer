package xyz.linplayer.app.ui.drafts.tv

import xyz.linplayer.app.tv.kit.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import androidx.activity.compose.BackHandler

/*
 * TV 草稿画廊(UI_TV.md §14)。
 *
 * 真机:`am start ... -e lp_page tvdrafts:<n>` 直达第 n 张;返回键回目录;
 * **菜单键在 A / B 两档字阶之间切换** —— §1.2 的字号只能在三米外拍板。
 * 出图:`src/test/.../TvDraftShots.kt` 用同一份 [tvDrafts] 表逐张渲染。
 */

class TvDraft(val title: String, val batch: Int, val alsoA: Boolean = false, val content: @Composable () -> Unit)

val tvDrafts: List<TvDraft> = listOf(
    TvDraft("首页 · 焦点在 Hero「详情」", 1, alsoA = true) { DraftHome(scrolled = false) },
    TvDraft("首页 · 往下走到继续观看第 2 张", 1) { DraftHome(scrolled = true) },
    TvDraft("组件表 ① · 实体控件的焦点 / 选中", 1, alsoA = true) { DraftComponentsSolid() },
    TvDraft("组件表 ② · 卡片与版本卡", 1) { DraftComponentsCards() },
    TvDraft("组件表 ③ · 轨项 / 设置行 / 探测态 / 角标", 1) { DraftComponentsMore() },
    TvDraft("播放页 · OSD,焦点在播放", 2, alsoA = true) { DraftPlayerOsd() },
    TvDraft("播放页 · 进度条预览游标", 2) { DraftPlayerSeek() },
    TvDraft("播放页 · 字幕面板", 2) { DraftPlayerSubtitles() },
    TvDraft("播放页 · 更多面板", 2) { DraftPlayerMore() },
    TvDraft("播放页 · 倍速 / 跳过片头 / 暂停 / 再按一次退出", 2) { DraftPlayerMoments() },
    TvDraft("播放页 · 下一集", 2) { DraftPlayerNextUp() },
    TvDraft("播放页 · 起播失败", 2) { DraftPlayerFailed() },
    TvDraft("播放页 · 有声音没画面", 2) { DraftPlayerNoVideo() },
    TvDraft("媒体库 · 选库", 3) { DraftLibraryPicker() },
    TvDraft("媒体库 · 网格", 3) { DraftLibraryGrid(panel = false) },
    TvDraft("媒体库 · 筛选与排序面板", 3) { DraftLibraryGrid(panel = true) },
    TvDraft("剧详情", 3, alsoA = true) { DraftSeriesDetail() },
    TvDraft("电影详情", 3) { DraftMovieDetail() },
    TvDraft("集详情 · 紧凑选集", 4, alsoA = true) { DraftEpisode(detailView = false) },
    TvDraft("集详情 · 详细选集", 4) { DraftEpisode(detailView = true) },
    TvDraft("搜索", 5, alsoA = true) { DraftSearch(ime = false) },
    TvDraft("搜索 · 输入法升起", 5) { DraftSearch(ime = true) },
    TvDraft("首次启动 / 添加服务器", 5) { DraftOnboarding() },
    TvDraft("服务器", 6) { DraftServers(ServersMode.NORMAL) },
    TvDraft("服务器 · 操作面板", 6) { DraftServers(ServersMode.PANEL) },
    TvDraft("服务器 · 排序模式", 6) { DraftServers(ServersMode.REORDER) },
    TvDraft("线路管理", 6) { DraftLines() },
    TvDraft("设置 · 播放", 6, alsoA = true) { DraftSettings(companion = false) },
    TvDraft("设置 · 通用(手机遥控)", 6) { DraftSettings(companion = true) },
    TvDraft("收藏", 7) { DraftFavorites() },
    TvDraft("下载", 7) { DraftDownloads() },
    TvDraft("本机文件夹 · 挑选", 7) { DraftLocal(picker = true) },
    TvDraft("本机文件夹 · 浏览", 7) { DraftLocal(picker = false) },
    TvDraft("系统态:加载 / 空 / 失败 / 确认", 7) { DraftStates() },
    TvDraft("卡片操作面板(长按确认)", 7) { DraftCardMenu() },
    TvDraft("导航轨 · 焦点进轨后展开", 1) { DraftHome(scrolled = false, railFocus = 2) },
)

/** 草稿画布:强制深色 + 字阶档。 */
@Composable
fun TvDraftFrame(type: TvType, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(background = TvC.bg, surface = TvC.surface2, onSurface = TvC.fg)) {
        CompositionLocalProvider(LocalTvType provides type) {
            Box(Modifier.fillMaxSize().background(TvC.bg)) { content() }
        }
    }
}

/**
 * 横向行:焦点项左缘钉在行首(§4.3)。
 *
 * ★ **必须显式提供。** 真电视上 Lazy 列表的默认值是「焦点项停在 30% 处」,
 *   手机和截图环境里不是 —— 不显式给,草稿图和真机长得不一样。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProvideRowKeyline(lead: Dp, content: @Composable () -> Unit) {
    val px = with(LocalDensity.current) { lead.toPx() }
    val spec = remember(px) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float) = offset - px
        }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}

/** 纵向页:往上多露出一段(行标题),往下只保证焦点项完整露出(§4.3)。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProvideColumnSpec(above: Dp, below: Dp, content: @Composable () -> Unit) {
    val d = LocalDensity.current
    val a = with(d) { above.toPx() }
    val b = with(d) { below.toPx() }
    val spec = remember(a, b) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = when {
                offset < a -> offset - a
                offset + size > containerSize - b -> offset + size - (containerSize - b)
                else -> 0f
            }
        }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}

@Composable
fun TvDraftGallery(start: Int = 0) {
    var page by remember { mutableIntStateOf(start) }
    var typeB by remember { mutableStateOf(true) }
    val type = if (typeB) TypeB else TypeA
    Box(
        Modifier.fillMaxSize().onPreviewKeyEvent {
            if (it.key == Key.Menu && it.type == KeyEventType.KeyUp) { typeB = !typeB; true } else false
        },
    ) {
        TvDraftFrame(type) {
            if (page in 1..tvDrafts.size) {
                BackHandler { page = 0 }
                tvDrafts[page - 1].content()
            } else {
                DraftIndex(type) { page = it }
            }
        }
    }
}

@Composable
private fun DraftIndex(type: TvType, open: (Int) -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = TvDim.safeH, vertical = TvDim.safeV)) {
        TvText("TV 草稿画廊", type.display, TvC.fg, weight = TvW.bold)
        TvText("真 Compose 渲染 · 当前 ${type.name} · 菜单键切换字阶 · 返回键回目录", type.meta, TvC.fg3)
        Spacer(Modifier.height(TvSp.x16))
        LazyVerticalGrid(
            GridCells.Fixed(3), horizontalArrangement = Arrangement.spacedBy(TvSp.x12),
            verticalArrangement = Arrangement.spacedBy(TvSp.x8), contentPadding = PaddingValues(TvSp.x6),
        ) {
            itemsIndexed(tvDrafts) { i, d ->
                Surface(
                    onClick = { open(i + 1) },
                    modifier = Modifier.initialFocus(i == 0),
                    shape = ClickableSurfaceDefaults.shape(TvR.md),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = TvC.surface1, contentColor = TvC.fg2,
                        focusedContainerColor = TvC.focus, focusedContentColor = TvC.onFocus,
                    ),
                ) {
                    Row(Modifier.padding(TvSp.x12), verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}", fontSize = type.title, fontWeight = TvW.bold, modifier = Modifier.width(28.dp))
                        Column {
                            Text(d.title, fontSize = type.body, maxLines = 2)
                            Text("第 ${d.batch} 批", fontSize = type.meta)
                        }
                    }
                }
            }
        }
    }
}
