package xyz.linplayer.app.ui.drafts.tv

import xyz.linplayer.app.tv.kit.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import xyz.linplayer.app.ui.theme.LpIcons

/* 其余页面草稿:搜索 / 首次启动 / 服务器 / 线路 / 设置 / 发现 / 收藏 / 下载 / 本机文件夹 / 系统态(UI_TV.md §7.1、§7.7–§7.14、§6)。 */

// ---------------------------------------------------------------- 共用小件

/** 整行一个焦点的列表行(族 B 行型:聚焦反白,不放大)。 */
@Composable
private fun ListRow(
    focused: Boolean = false, height: Dp = 64.dp, dim: Boolean = false,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Surface(
        onClick = {},
        modifier = Modifier.fillMaxWidth().heightIn(min = height).initialFocus(focused)
            .then(if (dim) Modifier.graphicsLayer { alpha = .72f } else Modifier),
        shape = ClickableSurfaceDefaults.shape(TvR.md),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 0.99f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = TvC.surface1, contentColor = TvC.fg,
            focusedContainerColor = TvC.focus, focusedContentColor = TvC.onFocus,
        ),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = height).padding(horizontal = TvSp.x12, vertical = TvSp.x8),
            verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

/** Surface 里的文字要吃 Surface 的 contentColor(聚焦时反色),所以这里不给颜色,只给透明度。 */
@Composable
private fun RowText(text: String, size: androidx.compose.ui.unit.TextUnit, alpha: Float = 1f, maxLines: Int = 1, mono: Boolean = false) {
    Text(
        text, fontSize = size, maxLines = maxLines, modifier = Modifier.graphicsLayer { this.alpha = alpha },
        fontFamily = if (mono) androidx.compose.ui.text.font.FontFamily.Monospace else null,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
    )
}

@Composable
private fun Group(title: String) {
    TvText(title, tvType.meta, TvC.fg3, Modifier.padding(top = TvSp.x12, bottom = TvSp.x6))
}

private fun Modifier.dashed(color: Color, radius: Dp = 14.dp) = this.drawBehind {
    drawRoundRect(
        color, style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
        cornerRadius = CornerRadius(radius.toPx()),
    )
}

/** 草稿里的二维码:只要看得出「这是个码」,不编码任何内容(也就不会带出任何地址)。 */
@Composable
private fun FakeQr(size: Dp) {
    Box(Modifier.size(size).clip(TvR.lg).background(Color.White).padding(size / 14)) {
        Canvas(Modifier.fillMaxSize()) {
            val n = 25
            val m = this.size.width / n
            var seed = 7
            for (y in 0 until n) for (x in 0 until n) {
                seed = (seed * 1103515245 + 12345) and 0x7fffffff
                val finder = (x < 7 && y < 7) || (x > 17 && y < 7) || (x < 7 && y > 17)
                if (!finder && seed % 3 == 0) drawRect(Color.Black, Offset(x * m, y * m), Size(m, m))
            }
            listOf(0 to 0, 18 to 0, 0 to 18).forEach { (fx, fy) ->
                drawRect(Color.Black, Offset(fx * m, fy * m), Size(7 * m, 7 * m))
                drawRect(Color.White, Offset((fx + 1) * m, (fy + 1) * m), Size(5 * m, 5 * m))
                drawRect(Color.Black, Offset((fx + 2) * m, (fy + 2) * m), Size(3 * m, 3 * m))
            }
        }
    }
}

// ---------------------------------------------------------------- 搜索(§7.7)

@Composable
fun DraftSearch(ime: Boolean) {
    val t = tvType
    RailShell(current = 0) {
        Row(Modifier.contentArea()) {
            Column(Modifier.width(300.dp)) {
                InputBox("幕府", "搜索片名", LpIcons.search, focused = true, width = 300.dp)
                Spacer(Modifier.height(TvSp.x16))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TvText("搜索历史", t.meta, TvC.fg3)
                    Spacer(Modifier.weight(1f))
                    TvButton("清空", LpIcons.trash)
                }
                Spacer(Modifier.height(TvSp.x6))
                listOf("幕府将军", "寂静的星河", "沙丘", "葬送的芙莉莲").forEach {
                    Box(Modifier.padding(vertical = 2.dp)) {
                        ListRow(height = t.rowH) {
                            Icon(LpIcons.search, null, Modifier.size(t.iconS))
                            Spacer(Modifier.width(TvSp.x8))
                            RowText(it, t.body)
                        }
                    }
                }
            }
            Spacer(Modifier.width(TvSp.x24))
            Column(Modifier.weight(1f)) {
                ScopeChips(listOf("当前服务器", "聚合全部 · 3", "包括分集"), selected = 1)
                Spacer(Modifier.height(TvSp.x12))
                TvText("结果 · 5", t.meta, TvC.fg3)
                Spacer(Modifier.height(TvSp.x6))
                searchResults.forEachIndexed { i, r ->
                    Box(Modifier.padding(vertical = 3.dp)) {
                        ListRow {
                            Box(Modifier.size(38.dp, 57.dp).clip(TvR.sm).background(swatch(200 + i)))
                            Spacer(Modifier.width(TvSp.x12))
                            Column(Modifier.weight(1f)) {
                                RowText(r[0], t.body)
                                RowText(r[1], t.meta, .7f)
                            }
                            RowText(r[2], t.meta, .7f)
                        }
                    }
                }
            }
        }
        if (ime) FakeIme()
        DraftNote(if (ime) "§4.5 输入法盖住下半屏:输入框和历史都在上半屏" else "§7.7 聚合时同一部片并列,不去重,行尾标来源")
    }
}

private val searchResults = listOf(
    listOf("幕府将军", "2024 · 剧集 · 2160p", "服务器 A"),
    listOf("幕府将军", "2024 · 剧集 · 1080p", "服务器 B"),
    listOf("幕府将军(1980)", "1980 · 剧集", "服务器 B"),
    listOf("幕府将军:幕后", "2024 · 电影 · 1080p", "服务器 A"),
    listOf("将军的女儿", "1999 · 电影", "服务器 C"),
)

@Composable
private fun BoxScope.FakeIme() {
    Column(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(250.dp).background(Color(0xFF26252B))
            .padding(horizontal = 120.dp, vertical = TvSp.x16),
        verticalArrangement = Arrangement.spacedBy(TvSp.x8),
    ) {
        TvText("系统输入法(示意)", tvType.meta, TvC.fg3)
        repeat(4) { r ->
            Row(horizontalArrangement = Arrangement.spacedBy(TvSp.x6)) {
                repeat(10 - r) { Box(Modifier.size(58.dp, 36.dp).clip(TvR.sm).background(Color(0xFF3A3940))) }
            }
        }
    }
}

// ---------------------------------------------------------------- 首次启动(§7.1)

@Composable
fun DraftOnboarding() {
    val t = tvType
    Box(Modifier.fillMaxSize().background(TvC.bg)) {
        Row(Modifier.fillMaxSize().padding(horizontal = TvDim.safeH, vertical = TvDim.safeV)) {
            Column(Modifier.width(472.dp)) {
                Box(Modifier.size(40.dp).clip(TvR.lg).background(TvC.acc), contentAlignment = Alignment.Center) {
                    Icon(LpIcons.play, null, Modifier.size(20.dp), tint = TvC.onAcc)
                }
                Spacer(Modifier.height(TvSp.x12))
                TvText("欢迎使用 LinPlayer", t.display, TvC.fg, weight = TvW.bold)
                TvText("推荐用手机扫右边的码,不用在电视上打字", t.body, TvC.fg2)
                Spacer(Modifier.height(TvSp.x16))
                ScopeChips(listOf("Emby", "本机文件夹"), selected = 0)
                Spacer(Modifier.height(TvSp.x16))
                Field("名称", "", "可选,默认用服务器自己的名字")
                Field("服务器地址", "https://", "")
                Field("用户名", "", "")
                Field("密码", "", "")
                Spacer(Modifier.height(TvSp.x12))
                Row(horizontalArrangement = Arrangement.spacedBy(TvSp.x12)) {
                    TvButton("连接", LpIcons.check, primary = true, focused = true)
                    TvButton("测试连接", LpIcons.sync)
                }
            }
            Spacer(Modifier.width(TvSp.x48))
            Column(Modifier.weight(1f).padding(top = TvSp.x24)) {
                FakeQr(200.dp)
                Spacer(Modifier.height(TvSp.x16))
                TvText("用手机扫码添加", t.title, TvC.fg, weight = TvW.semi)
                Spacer(Modifier.height(TvSp.x4))
                TvText("手机和电视连同一个 Wi-Fi。扫码后在手机上填好提交,电视这边会自动进去 —— 之后这个网页还能当遥控器用。",
                    t.body, TvC.fg2, maxLines = 3)
                Spacer(Modifier.height(TvSp.x6))
                TvText("http://电视的局域网地址/c/3f9a…", t.meta, TvC.fg3, mono = true)
                Spacer(Modifier.height(TvSp.x8))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(TvC.ok)
                    Spacer(Modifier.width(TvSp.x6))
                    TvText("等待手机连接…", t.meta, TvC.ok)
                }
            }
        }
        DraftNote("§7.1 初始焦点在「连接」不在输入框 · 二维码块整块不可聚焦")
    }
}

@Composable
private fun Field(label: String, text: String, placeholder: String) {
    Row(Modifier.padding(vertical = TvSp.x4), verticalAlignment = Alignment.CenterVertically) {
        TvText(label, tvType.meta, TvC.fg3, Modifier.width(88.dp))
        InputBox(text, placeholder, width = 384.dp, caret = false)
    }
}

// ---------------------------------------------------------------- 服务器(§7.11)

enum class ServersMode { NORMAL, PANEL, REORDER }

@Composable
fun DraftServers(mode: ServersMode) {
    val t = tvType
    RailShell(current = 5) {
        Column(Modifier.contentArea()) {
            if (mode == ServersMode.REORDER) {
                Row(
                    Modifier.fillMaxWidth().clip(TvR.md).background(TvC.accDim).border(1.5.dp, TvC.acc, TvR.md)
                        .padding(horizontal = TvSp.x16, vertical = TvSp.x12),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(LpIcons.sort, null, Modifier.size(t.iconS), tint = TvC.acc)
                    Spacer(Modifier.width(TvSp.x8))
                    TvText("排序模式 · 方向键移动位置,确认键放下,返回键取消", t.body, TvC.fg)
                }
            } else {
                PageHead("服务器", sub = "按确认键打开操作")
            }
            Spacer(Modifier.height(TvSp.x16))
            val cards = serverCards
            LazyVerticalGrid(
                GridCells.Fixed(3), horizontalArrangement = Arrangement.spacedBy(TvSp.x24),
                verticalArrangement = Arrangement.spacedBy(TvSp.x20), contentPadding = PaddingValues(12.dp), modifier = Modifier.bleed(12.dp),
            ) {
                itemsIndexed(cards) { i, c ->
                    ServerCard(
                        c, i, focused = mode == ServersMode.NORMAL && i == 0,
                        moving = mode == ServersMode.REORDER && i == 1,
                        reorder = mode == ServersMode.REORDER, total = cards.size,
                    )
                }
                if (mode != ServersMode.REORDER) item {
                    Box(Modifier.size(256.dp, 150.dp).dashed(TvC.line), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(LpIcons.plus, null, Modifier.size(28.dp), tint = TvC.acc)
                            Spacer(Modifier.height(TvSp.x6))
                            TvText("添加服务器", t.body, TvC.fg2)
                        }
                    }
                }
            }
        }
        if (mode == ServersMode.PANEL) PanelSlot {
            SidePanel("服务器 B") {
                PanelGroup("使用")
                PanelItem("切换到此服务器", focused = true)
                PanelItem("线路管理", value = "3 条", chevron = true)
                PanelGroup("编辑")
                PanelItem("修改名称")
                PanelItem("修改备注")
                PanelItem("重新登录")
                PanelItem("调整排序")
                PanelGroup("危险")
                PanelItem("删除此服务器", danger = true)
            }
        }
        DraftNote(when (mode) {
            ServersMode.NORMAL -> "§7.11 卡片只有图标 / 名称 / 备注 · 当前服务器用文字标注"
            ServersMode.PANEL -> "§7.11 卡片确认 → 操作面板(不需要长按)· 危险项单独一组放最后"
            ServersMode.REORDER -> "§7.11 同一页的第二种模式:卡片、栅格、间距都不变 · 返回键 = 还原"
        })
    }
}

private class ServerInfo(val name: String, val remark: String, val status: Color, val current: Boolean)

private val serverCards = listOf(
    ServerInfo("服务器 A", "客厅常用 · 4K 片源", TvC.ok, current = true),
    ServerInfo("服务器 B", "公司", TvC.ok, current = false),
    ServerInfo("服务器 C", "朋友的服务器", TvC.fg3, current = false),
    ServerInfo("旧 NAS", "只剩动漫", TvC.bad, current = false),
)

@Composable
private fun ServerCard(c: ServerInfo, index: Int, focused: Boolean, moving: Boolean, reorder: Boolean, total: Int) {
    val t = tvType
    val body: @Composable BoxScope.() -> Unit = {
        Column(Modifier.fillMaxSize().padding(TvSp.x12), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Box(Modifier.size(48.dp).clip(TvR.lg).background(swatch(300 + index)))
            Spacer(Modifier.height(TvSp.x8))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TvText(c.name, t.title, TvC.fg, weight = TvW.semi)
                Spacer(Modifier.width(TvSp.x6))
                StatusDot(c.status)
            }
            TvText(c.remark, t.meta, TvC.fg2)
            when {
                moving -> TvText("正在移动 · 位置 ${index + 1} / $total", t.meta, TvC.acc, weight = TvW.semi)
                c.current -> TvText("当前使用", t.meta, TvC.acc, weight = TvW.semi)
            }
        }
    }
    if (reorder) {
        Box(
            Modifier.size(256.dp, 150.dp).clip(TvR.lg).background(TvC.surface1)
                .dashed(if (moving) TvC.acc else TvC.fg3)
                .then(if (moving) Modifier else Modifier.graphicsLayer { alpha = .72f }),
            content = body,
        )
    } else {
        CoverSurface(256.dp, 150.dp, focused = focused, content = body)
    }
}

// ---------------------------------------------------------------- 线路管理(§7.12)

@Composable
fun DraftLines() {
    val t = tvType
    RailShell(current = 5) {
        Column(Modifier.contentArea()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TvText("线路管理", t.headline, TvC.fg, weight = TvW.semi)
                Spacer(Modifier.width(TvSp.x12))
                TvText("服务器 A", t.body, TvC.fg3)
                Spacer(Modifier.weight(1f))
                TvButton("全部测速", LpIcons.refresh, focused = true)
                Spacer(Modifier.width(TvSp.x8))
                TvButton("同步线路", LpIcons.sync)
            }
            TvText("选中的线路对该服务器的所有请求生效,包括图片和播放流", t.meta, TvC.fg2)
            Spacer(Modifier.height(TvSp.x12))
            LineRow("线路一", "https://media.example.net", TvC.ok, "86 ms", TvC.ok, current = true)
            LineRow("电信直连", "https://ct.example.net", TvC.warn, "测速中…", TvC.warn)
            LineRow("家里直连", "http://nas.local", TvC.fg3, "未测速", TvC.fg3)
            LineRow("线路 4", "https://backup.example.org", TvC.bad, "不可达", TvC.bad)
            Spacer(Modifier.height(TvSp.x6))
            Box(Modifier.fillMaxWidth().height(48.dp).dashed(TvC.line, 10.dp), contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(LpIcons.plus, null, Modifier.size(t.iconS), tint = TvC.acc)
                    Spacer(Modifier.width(TvSp.x6))
                    TvText("添加线路", t.body, TvC.fg2)
                }
            }
        }
        DraftNote("§7.12 这一页显示地址 · 当前用文字标注 · 不通写「不可达」不装成 0ms")
    }
}

@Composable
private fun LineRow(name: String, url: String, dot: Color, latency: String, latencyColor: Color, current: Boolean = false) {
    val t = tvType
    Box(Modifier.padding(vertical = 3.dp)) {
        ListRow {
            StatusDot(dot)
            Spacer(Modifier.width(TvSp.x12))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RowText(name, t.body)
                    if (current) {
                        Spacer(Modifier.width(TvSp.x8))
                        Text("当前使用", fontSize = t.meta, color = TvC.acc, fontWeight = TvW.semi)
                    }
                }
                RowText(url, t.meta, .65f, mono = true)
            }
            Text(latency, fontSize = t.body, color = latencyColor, fontWeight = TvW.semi)
        }
    }
}

// ---------------------------------------------------------------- 设置(§7.13)

@Composable
fun DraftSettings(companion: Boolean) {
    val t = tvType
    val cats = listOf("通用", "播放", "跳过片头片尾", "字幕与音轨", "弹幕", "网络", "同步", "存储", "关于")
    val sel = if (companion) 0 else 1
    RailShell(current = 6) {
        Row(Modifier.contentArea()) {
            Column(Modifier.width(180.dp)) {
                TvText("设置", t.headline, TvC.fg, weight = TvW.semi)
                Spacer(Modifier.height(TvSp.x8))
                cats.forEachIndexed { i, c -> PanelItem(c, selected = i == sel, focused = i == sel, check = false) }
            }
            Spacer(Modifier.width(TvSp.x24))
            Box(Modifier.width(1.dp).fillMaxHeight().background(TvC.line))
            Spacer(Modifier.width(TvSp.x16))
            Column(Modifier.weight(1f)) {
                if (companion) {
                    Group("播放行为")
                    PanelItem("跨服务器续播", sub = "同一部片在别的服务器上看过,也从那个位置接着播", switch = false)
                    Group("手机遥控")
                    PanelItem("手机扫码遥控", sub = "电视在局域网里开一个小网页,手机浏览器打开就是遥控器", switch = true)
                    Row(Modifier.padding(start = TvSp.x16, top = TvSp.x8), verticalAlignment = Alignment.CenterVertically) {
                        FakeQr(140.dp)
                        Spacer(Modifier.width(TvSp.x16))
                        Column {
                            TvText("用手机扫码", t.body, TvC.fg, weight = TvW.semi)
                            TvText("http://电视的局域网地址/c/3f9a…", t.meta, TvC.fg3, mono = true)
                            Spacer(Modifier.height(TvSp.x6))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                StatusDot(TvC.ok)
                                Spacer(Modifier.width(TvSp.x6))
                                TvText("服务在运行 · 关掉再打开会换一个新码", t.meta, TvC.fg2)
                            }
                        }
                    }
                } else {
                    Group("解码")
                    PanelItem("硬件解码", sub = "关掉后走软解,弱一点的盒子会卡", switch = true)
                    PanelItem("杜比视界自动软解", switch = true)
                    Group("播放")
                    PanelItem("默认倍速", value = "1.0x", chevron = true)
                    PanelItem("默认播放内核", value = "mpv", chevron = true)
                    PanelItem("快进步长", value = "10 秒", step = true)
                    PanelItem("长按倍速", value = "3.0x", chevron = true)
                    PanelItem("自动播放下一集", switch = true)
                }
            }
        }
        DraftNote(if (companion) "§7.13 / §9 二维码开在开关行下面,整块不可聚焦" else "§7.13 一层到底 · 开关整行一个焦点 · 取值进侧面板")
    }
}

// ---------------------------------------------------------------- 收藏(§7.8)

@Composable
fun DraftFavorites() {
    val t = tvType
    RailShell(current = 3) {
        Column(Modifier.contentArea()) {
            PageHead("收藏", count = "23 项")
            Spacer(Modifier.height(TvSp.x8))
            // 【用户定 2026-09-14】要排序;排序在核心层 emby.listFavorites 做,UI 只传参数
            EntryChip("排序", LpIcons.sort, "收藏时间 ↓")
            Spacer(Modifier.height(TvSp.x12))
            RowTitle("分集", trailing = "4 项")
            Spacer(Modifier.height(TvSp.x8))
            Row(horizontalArrangement = Arrangement.spacedBy(TvSp.x16)) {
                CardWide(501, "寂静的星河 · S2E4", "谎言的代价", focused = true, w = 192.dp, h = 108.dp)
                CardWide(502, "幕府将军 · S1E8", "大地之心", w = 192.dp, h = 108.dp)
                CardWide(503, "三体 · S1E23", "黑暗森林", w = 192.dp, h = 108.dp)
                CardWide(504, "繁花 · S1E1", "", w = 192.dp, h = 108.dp)
            }
            Spacer(Modifier.height(TvSp.x16))
            RowTitle("剧集与电影", trailing = "19 项")
            Spacer(Modifier.height(TvSp.x8))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                repeat(6) { i -> CardPoster(510 + i, shows[i].title, shows[i].sub, w = 124.dp, h = 186.dp) }
            }
        }
        DraftNote("§7.8 横竖卡不混在一个网格里 · 空组整组不画 · 排序在核心层做")
    }
}

// ---------------------------------------------------------------- 下载(§7.10)

@Composable
fun DraftDownloads() {
    val t = tvType
    RailShell(current = 4) {
        Column(Modifier.contentArea()) {
            Row(verticalAlignment = Alignment.Bottom) {
                PageHead("下载", count = "2 进行中 · 2 已完成")
                Spacer(Modifier.weight(1f))
                TvButton("下载设置", LpIcons.settings)
            }
            TvText("已下载 42.6 GB", t.meta, TvC.fg2)
            Group("进行中")
            DownloadRow(600, "寂静的星河 · S2E5 回声", "2.4 GB / 5.8 GB · 18.2 MB/s · 剩 3 分钟", .41f, "41%", TvC.acc, focused = true)
            DownloadRow(601, "幕府将军 · S1E9 红天", "下载失败 · 服务器拒绝了请求(403)", null, "重试", TvC.bad, failed = true)
            Group("已完成")
            DownloadRow(602, "沙丘 2", "15.2 GB", null, "可离线播放", TvC.ok)
            DownloadRow(603, "繁花 · S1E1", "2.1 GB", null, "可离线播放", TvC.ok)
        }
        DraftNote("§7.10 整行一个焦点,行内不放按钮 · 失败不弹窗 · 不画剩余空间(核心层没这条命令)")
    }
}

@Composable
private fun DownloadRow(
    seed: Int, title: String, detail: String, progress: Float?, right: String, rightColor: Color,
    focused: Boolean = false, failed: Boolean = false,
) {
    val t = tvType
    Box(Modifier.padding(vertical = 3.dp)) {
        ListRow(focused = focused) {
            if (failed) Box(Modifier.width(3.dp).height(40.dp).clip(TvR.pill).background(TvC.bad))
            if (failed) Spacer(Modifier.width(TvSp.x8))
            Box(Modifier.size(80.dp, 45.dp).clip(TvR.sm).background(swatch(seed)))
            Spacer(Modifier.width(TvSp.x12))
            Column(Modifier.weight(1f)) {
                RowText(title, t.body)
                RowText(detail, t.meta, .7f)
                if (progress != null) {
                    Spacer(Modifier.height(TvSp.x4))
                    Box(Modifier.fillMaxWidth(.9f).height(3.dp).clip(TvR.pill).background(TvC.surface3)) {
                        Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(TvC.acc))
                    }
                }
            }
            Spacer(Modifier.width(TvSp.x12))
            // 聚焦反白后状态色一律让位给 onFocus:琥珀字压在白底上对比度只有约 2:1
            Text(right, fontSize = t.title, color = if (focused) Color.Unspecified else rightColor, fontWeight = TvW.semi)
        }
    }
}

// ---------------------------------------------------------------- 本机文件夹(§7.14)

@Composable
fun DraftLocal(picker: Boolean) {
    val t = tvType
    RailShell(current = if (picker) 5 else 2) {
        Column(Modifier.contentArea()) {
            if (picker) {
                PageHead("选择文件夹", sub = "选好之后,这个文件夹会作为一个源出现在服务器页")
                Spacer(Modifier.height(TvSp.x12))
                LocalRow(LpIcons.folder, "内部存储", "可用 12.4 GB / 32 GB", focused = true)
                LocalRow(LpIcons.folder, "U 盘 · SanDisk", "可用 38.2 GB / 64 GB")
                LocalRow(LpIcons.folder, "SD 卡", "可用 101 GB / 128 GB")
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(TvSp.x6), verticalAlignment = Alignment.CenterVertically) {
                    TvButton("U 盘 · SanDisk", LpIcons.folder)
                    Icon(LpIcons.chevR, null, Modifier.size(14.dp), tint = TvC.fg3)
                    TvButton("电影")
                    Icon(LpIcons.chevR, null, Modifier.size(14.dp), tint = TvC.fg3)
                    TvButton("2024")
                }
                Spacer(Modifier.height(TvSp.x12))
                LocalRow(LpIcons.folder, "沙丘 2 (2024)", "3 项")
                LocalRow(LpIcons.file, "Poor.Things.2023.2160p.UHD.BluRay.REMUX.HDR.HEVC.Atmos-EXAMPLE.mkv", "71.2 GB · 2024-03-18", focused = true)
                LocalRow(LpIcons.file, "坠落的审判.2023.1080p.mkv", "8.4 GB · 2024-02-02")
                LocalRow(LpIcons.file, "周处除三害.2024.1080p.mp4", "3.9 GB · 2024-03-10")
            }
        }
        DraftNote(if (picker) "§7.14 第一层是存储卷(系统没有文件夹选择器)· 进这页时才要权限" else "§7.14 只列目录和视频 · 长文件名换行不截断 · 返回键回上一层")
    }
}

@Composable
private fun LocalRow(icon: ImageVector, name: String, meta: String, focused: Boolean = false) {
    val t = tvType
    Box(Modifier.padding(vertical = 3.dp)) {
        ListRow(focused = focused, height = 56.dp) {
            Icon(icon, null, Modifier.size(24.dp))
            Spacer(Modifier.width(TvSp.x12))
            Column(Modifier.weight(1f)) {
                RowText(name, t.body, maxLines = 2)
                RowText(meta, t.meta, .7f)
            }
        }
    }
}

// ---------------------------------------------------------------- 系统态 · 卡片操作面板(§6 / §5.4–§5.6)

@Composable
fun DraftStates() {
    val t = tvType
    Row(Modifier.fillMaxSize().background(TvC.bg)) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Quadrant("加载 · 静态骨架,不闪光") {
                Column(Modifier.padding(TvSp.x24)) {
                    Skel(Modifier.fillMaxWidth().height(110.dp), TvR.lg)
                    Spacer(Modifier.height(TvSp.x16))
                    Skel(Modifier.width(96.dp).height(14.dp))
                    Spacer(Modifier.height(TvSp.x8))
                    Row(horizontalArrangement = Arrangement.spacedBy(TvSp.x12)) {
                        repeat(4) { Skel(Modifier.size(88.dp, 50.dp)) }
                    }
                }
            }
            Quadrant("失败 · 一句给人看,一句给排查") {
                FullState(LpIcons.info, "没加载出来", sub = "连不上服务器 A", detail = "10 秒内没有响应 · 网络超时",
                    buttons = listOf("重试"), tone = TvC.bad, focusFirst = false)
            }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(TvC.line))
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Quadrant("空态 · 必带出路") {
                FullState(LpIcons.heart, "还没有收藏", sub = "在详情页按收藏,之后会出现在这里", buttons = listOf("去媒体库看看"), focusFirst = false)
            }
            Quadrant("确认对话框 · 只给不可逆操作,默认焦点在「取消」") {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(Modifier.width(360.dp).clip(TvR.lg).background(TvC.surface2).padding(TvSp.x20)) {
                        TvText("删除「服务器 A」?", t.title, TvC.fg, weight = TvW.semi)
                        Spacer(Modifier.height(TvSp.x6))
                        TvText("本机上这台服务器的登录信息会一并清除,此操作无法撤销。", t.body, TvC.fg2, maxLines = 2)
                        Spacer(Modifier.height(TvSp.x16))
                        Row(horizontalArrangement = Arrangement.spacedBy(TvSp.x12)) {
                            TvButton("取消", focused = true)
                            DangerButton("删除")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Quadrant(label: String, content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxWidth().height(270.dp)) {
        content()
        TvText(label, tvType.meta, TvC.acc, Modifier.padding(TvSp.x8))
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(1.dp).background(TvC.line))
    }
}

/** 危险动作按钮:常态字是 bad 色,聚焦照样反白(焦点通道不许被危险色借走)。 */
@Composable
private fun DangerButton(label: String) {
    Surface(
        onClick = {},
        modifier = Modifier.height(tvType.controlH),
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

@Composable
fun DraftCardMenu() {
    RailShell(current = 1) {
        Column(Modifier.contentArea()) {
            TvText("继续观看", tvType.title, TvC.fg, weight = TvW.semi)
            Spacer(Modifier.height(TvSp.x8))
            Row(horizontalArrangement = Arrangement.spacedBy(TvSp.x12)) {
                continueWatching.take(4).forEach { CardWide(it.seed, it.title, it.sub, it.progress) }
            }
        }
        PanelSlot {
            SidePanel("寂静的星河 · S2E4") {
                PanelItem("查看详情", focused = true)
                PanelItem("继续播放", value = "剩 18 分钟")
                PanelGroup("标记")
                PanelItem("收藏")
                PanelItem("标记已看")
                PanelGroup("其它")
                PanelItem("下载")
                PanelGroup("危险")
                PanelItem("屏蔽这部内容", danger = true, chevron = true)
            }
        }
        DraftNote("§5.6 长按确认 = 菜单键 · 所有出现卡片的页面都接这个面板")
    }
}
