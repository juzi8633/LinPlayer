package xyz.linplayer.app.tv

import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import xyz.linplayer.app.data.Block
import xyz.linplayer.app.data.Item
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.View
import xyz.linplayer.app.data.arr
import xyz.linplayer.app.data.block
import xyz.linplayer.app.data.keepState
import xyz.linplayer.app.data.obj
import xyz.linplayer.app.data.str
import xyz.linplayer.app.tv.kit.FullState
import xyz.linplayer.app.tv.kit.InlineError
import xyz.linplayer.app.tv.kit.ProvideColumnKeyline
import xyz.linplayer.app.tv.kit.ProvideRowKeyline
import xyz.linplayer.app.tv.kit.RowTitle
import xyz.linplayer.app.tv.kit.Skel
import xyz.linplayer.app.tv.kit.TvButton
import xyz.linplayer.app.tv.kit.TvC
import xyz.linplayer.app.tv.kit.TvDim
import xyz.linplayer.app.tv.kit.TvR
import xyz.linplayer.app.tv.kit.TvSp
import xyz.linplayer.app.tv.kit.TvText
import xyz.linplayer.app.tv.kit.TvW
import xyz.linplayer.app.tv.kit.contentArea
import xyz.linplayer.app.tv.kit.tvType
import xyz.linplayer.app.ui.pages.args
import xyz.linplayer.app.ui.pages.map
import xyz.linplayer.app.ui.theme.LpIcons

/** Hero 自动轮播间隔。8 秒不是 12 秒:12 秒在电视上「看着像坏了」(§7.2)。 */
private const val HeroIntervalMs = 8000L

/**
 * 首页(UI_TV.md §7.2)。
 *
 * ☠ 五块**各自并发、各自骨架、各自出错**,不设屏障 —— 等齐再画 = 机顶盒上整屏黑到像死机。
 * ★ 数据留在 PageCache 里:返回首页不许整屏重新骨架化;Hero 的随机推荐尤其不能重拉
 *   (每次返回都重新下载 5 张大图)。
 */
@Composable
fun HomePage() {
    val app = LocalApp.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val overlay = rememberOverlay()

    if (LocalIsLocalSource.current) {
        Box(Modifier.contentArea()) {
            FullState(LpIcons.folder, "本机文件夹没有首页推荐", buttons = listOf("打开文件夹"),
                onButton = { nav.rail(TvRoute.Library()) })
        }
        return
    }

    val server = app.session.collectAsStateWithLifecycle().value?.server
    val ck = "tv.home.$server"
    var hero by keepState<Block<List<Item>>>("$ck.hero") { Block.Loading }
    var resume by keepState<Block<List<Item>>>("$ck.resume") { Block.Loading }
    var nextUp by keepState<Block<List<Item>>>("$ck.next") { Block.Loading }
    var views by keepState<Block<List<View>>>("$ck.views") { Block.Loading }
    var latest by keepState<Map<String, Block<List<Item>>>>("$ck.latest") { emptyMap() }
    var collections by keepState<Block<List<Item>>>("$ck.coll") { Block.Loading }
    var reload by remember { mutableIntStateOf(0) }
    /* 插件声明的首页栏目(SPEC 6.1,D303)。桌面和手机 2026-09-21 接通,TV 这一端当时没做
       —— 官方那几行是按 Emby 的 Item 写的,插件给的是数据源形状的 JSON,得另走一条。 */
    var pluginSections by keepState<List<JsonObject>>("tv.home.plugins") { emptyList() }
    LaunchedEffect(reload) {
        pluginSections = runCatching { app.call("plugin.homeSections") }.getOrNull().arr().mapNotNull { it.obj() }
            .filter { !it.str("id").isNullOrEmpty() && !it.str("plugin_id").isNullOrEmpty() }
    }

    /* 回来时后台静默刷新:结果回来才替换,**一次拉取失败不把手里已有的冲掉**。 */
    fun <T> keep(old: Block<T>, new: Block<T>) = if (new is Block.Fail && old is Block.Ok) old else new
    LaunchedEffect(server, reload) {
        if (hero !is Block.Ok || reload > 0) launch { hero = keep(hero, app.block("emby.listRandom", args("limit" to 5)).map { Item.list(it) }) }
        launch { resume = keep(resume, app.block("emby.listResume", args("limit" to 20)).map { Item.list(it) }) }
        launch { nextUp = keep(nextUp, app.block("emby.listNextUp", args("limit" to 20)).map { Item.list(it) }) }
        launch { collections = keep(collections, app.block("emby.listCollections").map { Item.list(it) }) }
        launch {
            val v = app.block("emby.views").map { View.list(it) }
            views = keep(views, v)
            v.valueOrNull?.forEach { view ->
                launch {
                    val r = app.block("emby.listLatest", args("parent_id" to view.id, "limit" to 20)).map { Item.list(it) }
                    latest = latest + (view.id to keep(latest[view.id] ?: Block.Loading, r))
                }
            }
        }
    }

    val menu: (Item) -> Unit = { item -> overlay.openCardMenu(app, nav, scope, item) { if (it == "blocked") reload++ } }
    val allEmpty = listOf(hero, resume, nextUp, collections).all { it is Block.Ok && it.value.isEmpty() } &&
        views.let { it is Block.Ok && it.value.isEmpty() } &&
        // 官方内容全空但插件有栏目时**不能**换成空状态页:那一屏正是插件栏目最该出现的时候
        pluginSections.isEmpty()

    Box(Modifier.fillMaxSize()) {
        if (allEmpty) {
            Box(Modifier.contentArea()) {
                FullState(LpIcons.server, "这台服务器上还没有内容", buttons = listOf("去服务器页"),
                    onButton = { nav.rail(TvRoute.Servers) })
            }
        } else {
            val list = rememberLazyListState()
            // 焦点卡钉在距顶 64dp = 安全区 27 + 行标题 29 + 间距 8:行标题跟着露出
            ProvideColumnKeyline(above = 64.dp) {
                LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(top = TvDim.safeV, bottom = 64.dp)) {
                    item("hero") {
                        Box(Modifier.padding(start = TvSp.x32, end = TvDim.safeH)) {
                            Hero(hero) { openItem(nav, it) }
                        }
                    }
                    itemRow("resume", "继续观看", resume) { it ->
                        items(it, key = { i -> i.id }) { i -> ItemWide(i, "home.resume.${i.id}", menu) }
                    }
                    itemRow("next", "接下来看", nextUp) { it ->
                        items(it, key = { i -> i.id }) { i -> ItemWide(i, "home.next.${i.id}", menu, showProgress = false) }
                    }
                    views.valueOrNull.orEmpty().forEach { v ->
                        itemRow("view.${v.id}", v.name, latest[v.id] ?: Block.Loading, link = {
                            nav.push(TvRoute.Library(v.id, v.name))
                        }) { it ->
                            items(it, key = { i -> i.id }) { i -> ItemPoster(i, "home.v.${v.id}.${i.id}", menu) }
                        }
                    }
                    itemRow("coll", "合集", collections) { it ->
                        items(it, key = { i -> i.id }) { i -> ItemPoster(i, "home.coll.${i.id}", menu) }
                    }
                    // 插件栏目排在官方栏目**后面**(D156:新装的追加到末尾)
                    pluginSections.forEach { pluginRow(it) }
                }
            }
        }
        OverlayHost(overlay)
    }
}

/** 插件的一条首页栏目(D303)。空的 / 拿不到的**整行不画**,不在首页上留一行空标题。 */
private fun LazyListScope.pluginRow(sec: JsonObject) {
    val pid = sec.str("plugin_id") ?: return
    val sid = sec.str("id") ?: return
    val title = sec.str("title")?.takeIf { it.isNotEmpty() } ?: sid
    item("ps:$pid:$sid") {
        Column(Modifier.padding(top = TvSp.x20)) {
            Box(Modifier.padding(start = TvSp.x24)) { RowTitle(title) }
            Spacer(Modifier.height(TvSp.x8))
            if (sec.str("kind") == "custom") PluginBlock(pid, sec.str("block")?.takeIf { it.isNotEmpty() } ?: sid)
            else PluginItems(pid, sid, sec.str("shape") ?: "portrait")
        }
    }
}

/** 插件自画的那一块。可聚焦那套控件由 `TvFrame` 整个壳统一给(`LocalPluginTv`),这里不用再套。 */
@Composable
private fun PluginBlock(pid: String, block: String) {
    Box(Modifier.padding(start = TvSp.x32, end = TvDim.safeH)) {
        xyz.linplayer.app.ui.plugin.PluginSurface(pid, block, "block", modifier = Modifier.fillMaxWidth())
    }
}

/** 插件只给条目时,用数据源那套海报卡画 —— 主题、焦点轨道自动跟随官方行。 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun PluginItems(pid: String, sid: String, shape: String) {
    val app = LocalApp.current
    val nav = LocalNav.current
    val rail = LocalRailFocus.current
    var got by remember(pid, sid) { mutableStateOf<List<JsonObject>?>(null) }
    LaunchedEffect(pid, sid) {
        got = runCatching { app.call("plugin.homeItems", args("plugin_id" to pid, "id" to sid)) }
            .getOrNull().arr().mapNotNull { it.obj() }
    }
    val list = got ?: return
    if (list.isEmpty()) return
    ProvideRowKeyline(TvSp.x32) {
        LazyRow(
            // 行首再按 ← 进轨(§4.4),和官方行同一条规矩
            modifier = Modifier.focusProperties {
                exit = { d -> if (d == FocusDirection.Left) rail ?: FocusRequester.Default else FocusRequester.Default }
            },
            contentPadding = PaddingValues(start = TvSp.x32, end = TvDim.safeH, top = TvSp.x6, bottom = TvSp.x2),
            horizontalArrangement = Arrangement.spacedBy(TvSp.x12),
        ) {
            items(list, key = { it.str("id") ?: "" }) { x ->
                SourcePoster(x, "home.ps.$pid.$sid.${x.str("id")}") {
                    // 条目带来源(D282),按它回到对应数据源的详情页
                    nav.push(TvRoute.SourceDetail(x.str("source") ?: "", x.str("id") ?: ""))
                }
            }
        }
    }
}

/**
 * 一行内容。**空就整段不画,连标题一起**(§6.2);失败只在这一行画 InlineError,其它行照常。
 * ★ 媒体库行的标题**可聚焦**并常驻 ›;「继续观看 / 接下来看」没有库可进,标题不可聚焦。
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
private fun LazyListScope.itemRow(
    key: String, title: String, block: Block<List<Item>>, link: (() -> Unit)? = null,
    cards: LazyListScope.(List<Item>) -> Unit,
) {
    if (block is Block.Ok && block.value.isEmpty()) return
    if (block is Block.Fail && block.isSilent) return
    item(key) {
        val rail = LocalRailFocus.current
        Column(Modifier.padding(top = TvSp.x20)) {
            Box(Modifier.padding(start = TvSp.x24)) {
                RowTitle(title, link = link != null, modifier = Modifier.memo("home.title.$key"), onClick = { link?.invoke() })
            }
            Spacer(Modifier.height(TvSp.x8))
            when (block) {
                is Block.Loading -> Row(Modifier.padding(start = TvSp.x32), horizontalArrangement = Arrangement.spacedBy(TvSp.x12)) {
                    repeat(5) { Skel(Modifier.size(TvDim.wideW, TvDim.wideH)) }
                }
                is Block.Fail -> InlineError(block.message, Modifier.padding(start = TvSp.x32))
                is Block.Ok -> ProvideRowKeyline(TvSp.x32) {
                    LazyRow(
                        // 行首再按 ← 进轨(§4.4)。不拐的话,焦点搜索先在整页里找,别的行里左缩 8dp 的行标题会被当成「左边」
                        modifier = Modifier.focusProperties {
                            exit = { d -> if (d == FocusDirection.Left) rail ?: FocusRequester.Default else FocusRequester.Default }
                        },
                        contentPadding = PaddingValues(start = TvSp.x32, end = TvDim.safeH, top = TvSp.x6, bottom = TvSp.x2),
                        horizontalArrangement = Arrangement.spacedBy(TvSp.x12),
                    ) { cards(block.value) }
                }
            }
        }
    }
}

@Composable
private fun Hero(block: Block<List<Item>>, onDetail: (Item) -> Unit) {
    when (block) {
        is Block.Loading -> Skel(Modifier.fillMaxWidth().height(TvDim.heroH), TvR.lg)
        // Hero 拿不到:整块换成 surface1 底 + InlineError,下面的行照常(§7.2)
        is Block.Fail -> if (!block.isSilent) Box(
            Modifier.fillMaxWidth().height(TvDim.heroH).clip(TvR.lg).background(TvC.surface1).padding(TvSp.x24),
        ) { InlineError(block.message, Modifier.align(Alignment.BottomStart)) }
        is Block.Ok -> if (block.value.isNotEmpty()) HeroCarousel(block.value, onDetail)
    }
}

/**
 * ★ 5 张背景图**全部预先挂着**只切透明度:只挂当前那张的话换图时现拉现解码,中间闪一下黑。
 * ☠ 轮播在「失去焦点」时必须恢复 —— 旧实现只在获得焦点时停,首页一进来焦点就在按钮上,
 *   轮播从第一秒起永久停摆。
 * ★ **文字上什么都不加**【用户定 2026-07-20】:不加渐变、不加底、不加描边。
 */
@Composable
private fun HeroCarousel(items: List<Item>, onDetail: (Item) -> Unit) {
    val app = LocalApp.current
    val t = tvType
    var idx by rememberSaveable { mutableIntStateOf(0) }
    var paused by remember { mutableStateOf(false) }
    val n = items.size
    val cur = items[idx.coerceIn(0, n - 1)]
    LaunchedEffect(paused, n) {
        if (paused || n < 2) return@LaunchedEffect
        while (true) { delay(HeroIntervalMs); idx = (idx + 1) % n }
    }
    val scope = rememberCoroutineScope()
    val whole = remember { BringIntoViewRequester() }
    // 整块 Hero 请求露出:只让按钮自己露出的话,从下面回来停在按钮顶端,上面的封面在视野外(§4.3)
    Box(Modifier.testTag("home.heroBox").fillMaxWidth().height(TvDim.heroH).bringIntoViewRequester(whole).clip(TvR.lg).background(TvC.surface1)
        .onFocusChanged { paused = it.hasFocus; if (it.hasFocus) scope.launch { whole.bringIntoView() } }) {
        items.forEachIndexed { i, it ->
            val a by animateFloatAsState(if (i == idx) 1f else 0f, tween(500), label = "hero$i")
            TvImage(app.imageUrl(it.id, "Backdrop", 720), Modifier.graphicsLayer { alpha = a })
        }
        Column(Modifier.align(Alignment.BottomStart).padding(TvSp.x24).widthIn(max = 480.dp)) {
            TvText(cur.name, t.display, TvC.fg, weight = TvW.bold, maxLines = 2)
            Spacer(Modifier.height(TvSp.x4))
            Row(verticalAlignment = Alignment.CenterVertically) {
                cur.rating?.takeIf { it > 0 }?.let {
                    TvText("★ %.1f".format(it), t.meta, TvC.acc, weight = TvW.semi)
                    Spacer(Modifier.width(TvSp.x8))
                }
                TvText(listOfNotNull(
                    cur.year?.toString(), cur.genres.take(3).joinToString(" · ").takeIf { it.isNotEmpty() },
                    cur.runtimeSecs.takeIf { !cur.isSeries && it > 0 }?.let { "${(it / 60).toInt()} 分钟" },
                ).joinToString(" · "), t.meta, TvC.fg2)
            }
            Spacer(Modifier.height(TvSp.x12))
            Row(horizontalArrangement = Arrangement.spacedBy(TvSp.x12)) {
                // Hero 不直接起播【用户定 2026-09-14】:随机推荐的片子多半要先看一眼简介
                TvButton("详情", LpIcons.info, primary = true, modifier = Modifier.memo("home.hero", initial = true),
                    onClick = { onDetail(cur) })
                // 焦点在最后一个按钮上再按 → = 换下一部:Hero 右边没有别的东西,方向键在那是死键
                if (n > 1) TvButton("换一部", LpIcons.refresh, onClick = { idx = (idx + 1) % n },
                    modifier = Modifier.memo("home.heroNext").onPreviewKeyEvent {
                        if (it.key != Key.DirectionRight) return@onPreviewKeyEvent false
                        if (it.type == KeyEventType.KeyDown) idx = (idx + 1) % n
                        true
                    })
            }
        }
        if (n > 1) Row(Modifier.align(Alignment.BottomEnd).padding(TvSp.x24), horizontalArrangement = Arrangement.spacedBy(TvSp.x6),
            verticalAlignment = Alignment.CenterVertically) {
            repeat(n) { i ->
                if (i == idx) Box(Modifier.size(16.dp, 6.dp).clip(TvR.pill).background(TvC.acc))
                else Box(Modifier.size(6.dp).clip(TvR.pill).background(TvC.fg.copy(alpha = .3f)))
            }
        }
    }
}
