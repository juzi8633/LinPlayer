package xyz.linplayer.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.delay
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.ToastKind
import xyz.linplayer.app.ui.Route
import xyz.linplayer.app.ui.components.LpRowSkeleton
import xyz.linplayer.app.ui.components.LongShotButton
import xyz.linplayer.app.ui.components.LpTabBar
import xyz.linplayer.app.ui.components.Skeleton
import xyz.linplayer.app.ui.pages.AggregatePage
import xyz.linplayer.app.ui.pages.CalendarPage
import xyz.linplayer.app.ui.pages.DetailPage
import xyz.linplayer.app.ui.pages.DownloadsPage
import xyz.linplayer.app.ui.pages.FacetPage
import xyz.linplayer.app.ui.pages.FavoritesPage
import xyz.linplayer.app.ui.pages.GatePage
import xyz.linplayer.app.ui.pages.HomePage
import xyz.linplayer.app.ui.pages.LibraryPage
import xyz.linplayer.app.ui.pages.LinesPage
import xyz.linplayer.app.ui.pages.BrowsePage
import xyz.linplayer.app.ui.pages.PluginsPage
import xyz.linplayer.app.ui.pages.RankingPage
import xyz.linplayer.app.ui.pages.SearchPage
import xyz.linplayer.app.ui.pages.ServersPage
import xyz.linplayer.app.ui.pages.SettingsPage
import xyz.linplayer.app.ui.pages.SettingsSubPage
import xyz.linplayer.app.ui.player.PlayerPage
import xyz.linplayer.app.data.str
import xyz.linplayer.app.ui.switchTab
import xyz.linplayer.app.ui.theme.LpEasing
import xyz.linplayer.app.ui.theme.Lp
import xyz.linplayer.app.ui.theme.R
import xyz.linplayer.app.ui.theme.Sp
import xyz.linplayer.app.ui.theme.T

/**
 * 手机形态的根。启动时序(SPEC §8.0)的第 4~6 步在这里。
 *
 * ☠ **第 5 步没判完之前画骨架,不画闸口也不画首页** ——
 * 先画闸口再跳走会闪一下「请登录」,那比多等 80ms 难看得多。
 */
@Composable
fun PhoneRoot(app: AppState) {
    androidx.compose.runtime.CompositionLocalProvider(LocalApp provides app) {
        val loggedIn by app.loggedIn.collectAsStateWithLifecycle()
        LaunchedEffect(Unit) { app.boot() }

        Box(Modifier.fillMaxSize().background(Lp.colors.bg)) {
            // 草稿画廊:`am start ... -e lp_page 'drafts:<n>'`。
            // ★ 放在登录判定**之前** —— 草稿不连网,不该被「还没登录」挡住
            val draft = MainActivity.SelfCheck.page?.takeIf { it.startsWith("drafts") }
            when {
                draft != null -> xyz.linplayer.app.ui.drafts.DraftGallery(
                    draft.substringAfter(":", "0").toIntOrNull() ?: 0)
                else -> when (loggedIn) {
                null -> BootSkeleton()
                false -> GatePage(onDone = { app.refreshSession() })
                true -> MainShell()
            }
            }
            xyz.linplayer.app.ui.plugin.PhonePluginDialog()
            ToastHost()
        }
    }
}

/** 第 1 步的骨架:**不是转圈,是页面轮廓**(SPEC §8.0)。 */
@Composable
private fun BootSkeleton() {
    Column(Modifier.fillMaxSize().padding(top = 80.dp)) {
        Skeleton(Modifier.fillMaxWidth().height(220.dp).padding(horizontal = Sp.x16), R.md)
        LpRowSkeleton()
        LpRowSkeleton()
    }
}

@Composable
private fun MainShell() {
    val app = LocalApp.current
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    // 「启动时自动检查更新」。放在登录之后:没进门就先弹更新是打扰
    var newVersion by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<kotlinx.serialization.json.JsonObject?>(null)
    }
    LaunchedEffect(Unit) { newVersion = xyz.linplayer.app.ui.pages.autoCheckUpdate(app) }
    xyz.linplayer.app.ui.pages.UpdateFlow(newVersion) { newVersion = null }

    /* 插件的 nav.* 接到这个栈上(SPEC 7.9)。绑在 MainShell 而不是 PhoneRoot:
       闸口页还没有栈,这时插件跳页无处可去,报「界面还没起来」比静默丢掉清楚。 */
    androidx.compose.runtime.DisposableEffect(nav) {
        xyz.linplayer.app.plugin.PluginNav.host = object : xyz.linplayer.app.plugin.PluginNav.Host {
            override fun push(route: String, params: kotlinx.serialization.json.JsonObject, replace: Boolean): Boolean {
                val r = officialRoute(route) { k -> params.str(k) } ?: return false
                if (replace) nav.popBackStack()
                nav.navigate(r)
                return true
            }
            override fun back() { nav.popBackStack() }
        }
        onDispose { xyz.linplayer.app.plugin.PluginNav.host = null }
    }

    val route = entry?.destination?.route.orEmpty()
    val tab = when {
        route.endsWith("Home") -> 0
        route.endsWith("Aggregate") -> 1
        route.endsWith("Favorites") -> 2
        else -> -1
    }

    /* 真机自检直达:`am start ... -e lp_page <名字>`。
       ★ 不能靠 input tap 走到目标页 —— 坐标随字号 / 数据变,而且中间任何一步
         没点中,后面全错位;截图看起来还像是「那一页做坏了」。 */
    LaunchedEffect(Unit) {
        MainActivity.SelfCheck.devPlugin?.let { dir ->
            MainActivity.SelfCheck.devPlugin = null
            runCatching { app.call("plugin.devLoad", xyz.linplayer.app.ui.pages.args("dir" to dir)) }.onFailure { app.report(it) }
        }
        val p = MainActivity.SelfCheck.page ?: return@LaunchedEffect
        MainActivity.SelfCheck.page = null
        if (p.startsWith("tvbox:")) { xyz.linplayer.app.ui.pages.selfCheckTvbox(app, nav, p.removePrefix("tvbox:")); return@LaunchedEffect }
        val parts = p.split(":")
        when (parts[0]) {
            "favoritesTab" -> nav.switchTab(Route.Favorites)
            "srcfav" -> nav.navigate(Route.SourceFavorites)
            "browse" -> nav.navigate(Route.Browse)
            "addServer" -> nav.navigate(Route.AddServer)
            "extensions" -> nav.navigate(Route.Extensions)
            "settingsSub" -> nav.navigate(Route.SettingsSub(parts.getOrElse(1) { "about" }))
            // pluginpage:<插件id>/<页面id> —— 插件 id 自带一个「/」,页面 id 取最后一段
            "pluginpage" -> {
                val raw = p.removePrefix("pluginpage:")
                val at = raw.lastIndexOf('/')
                if (at > 0) nav.navigate(Route.PluginPage(raw.substring(0, at), raw.substring(at + 1), "插件页"))
            }
            // 其余走插件那张官方路由表:自检的第 2 / 3 段按位置喂进去,省得同一份映射写两遍
            else -> officialRoute(parts[0]) { k ->
                when (k) {
                    "id", "tab" -> parts.getOrNull(1)
                    "title", "type" -> parts.getOrNull(2)
                    else -> null
                }
            }?.let { nav.navigate(it) }
        }
    }

    /* ☠ 底栏从「Column 里占一格」改成「Box 上叠一层」。
       占一格的话内容区被切短,底栏上面永远是一条硬边;叠一层之后轨道从它下面穿过去,
       滚动时是渐渐化掉 —— 这是草稿 01 第 4 条要的效果。
       代价是每个列表都得自己留白,所以底栏高度走 LocalTabClearance 下发。 */
    androidx.compose.runtime.CompositionLocalProvider(
        xyz.linplayer.app.ui.components.LocalTabClearance provides
            if (tab >= 0) xyz.linplayer.app.ui.theme.Dim.tabClearance else 0.dp
    ) {
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            NavHost(
                navController = nav,
                startDestination = Route.Home,
                // 页面转场:新页从右滑入 + 淡入。**Tab 之间只淡入不位移**(平级没有方向)
                enterTransition = {
                    slideInHorizontally(tweenI(T.T7, LpEasing.emphasized)) { it / 8 } +
                        fadeIn(tween(T.T7))
                },
                exitTransition = { fadeOut(tween(T.T4)) },
                popEnterTransition = { fadeIn(tween(T.T4)) },
                popExitTransition = {
                    slideOutHorizontally(tweenI(T.T7, LpEasing.emphasized)) { it / 6 } +
                        fadeOut(tween(T.T5))
                },
            ) {
                // 当前是插件数据源:首页换成数据源首页(D167),Emby 那一套它一样都没有
                composable<Route.Home> {
                    val src by app.activeSource.collectAsStateWithLifecycle()
                    src?.let { xyz.linplayer.app.ui.pages.SourceHomePage(nav, it.id, it.name) } ?: HomePage(nav)
                }
                composable<Route.Aggregate> { AggregatePage(nav) }
                composable<Route.Servers> { ServersPage(nav) }
                composable<Route.Library> { LibraryPage(nav, it) }
                composable<Route.Detail> { DetailPage(nav, it) }
                composable<Route.Search> { SearchPage(nav, it) }
                composable<Route.Favorites> {
                    val src by app.activeSource.collectAsStateWithLifecycle()
                    if (src != null) xyz.linplayer.app.ui.pages.SourceFavoritesPage(nav) else FavoritesPage(nav)
                }
                composable<Route.History> { xyz.linplayer.app.ui.pages.HistoryPage(nav) }
                composable<Route.Facet> { FacetPage(nav, it) }
                composable<Route.Lines> { LinesPage(nav, it) }
                composable<Route.Browse> { BrowsePage(nav) }
                composable<Route.Downloads> { DownloadsPage(nav) }
                composable<Route.Plugins> { PluginsPage(nav, it) }
                composable<Route.PluginDetail> { xyz.linplayer.app.ui.pages.PluginDetailPage(nav, it) }
                composable<Route.PluginPage> { xyz.linplayer.app.ui.pages.PluginHostPage(nav, it) }
                composable<Route.Extensions> { xyz.linplayer.app.ui.pages.ExtensionsPage(nav) }
                composable<Route.SourceCategory> { xyz.linplayer.app.ui.pages.SourceCategoryPage(nav, it) }
                composable<Route.SourceDetail> { xyz.linplayer.app.ui.pages.SourceDetailPage(nav, it) }
                composable<Route.SourceFavorites> { xyz.linplayer.app.ui.pages.SourceFavoritesPage(nav) }
                composable<Route.Ranking> { RankingPage(nav) }
                composable<Route.Calendar> { CalendarPage(nav) }
                composable<Route.Settings> { SettingsPage(nav) }
                composable<Route.SettingsSub> { SettingsSubPage(nav, it) }
                /* ☠ 加完服务器**必须重取一次会话**。只 popBackStack 的话:
                   `emby.login` 已经在核心层把活动服务器换成了新加的这台,
                   而 UI 手里那份 session 还是老的 —— 于是「当前是哪台」这件事
                   界面和核心层各说各话,退出重进(boot 一次)才对得上
                   (用户 2026-09-12:「显示目前服务器是新添加的结果还是原来的服务器,
                   退出重进才正常」)。 */
                composable<Route.AddServer> {
                    GatePage(onDone = { app.refreshSession(); nav.popBackStack() }, embedded = true)
                }
                composable<Route.Player> { PlayerPage(nav, it) }
            }
        }
        /* 播放页是全屏页,没有底栏。
           ☠ 截长屏时底栏必须让开:长图是一片片切出来拼的,底栏留着的话
             它会**在每一片里各印一条**,一路排下来像出了什么故障。 */
        if (tab >= 0 && !xyz.linplayer.app.ui.components.LongShot.capturing.value) {
            Box(Modifier.align(Alignment.BottomCenter)) {
                LpTabBar(tab) {
                    nav.switchTab(when (it) { 0 -> Route.Home; 1 -> Route.Aggregate; else -> Route.Favorites })
                }
            }
        }
        LongShotButton(Modifier.align(Alignment.BottomEnd))
    }
    }
}

private fun tween(d: Int, easing: androidx.compose.animation.core.Easing = LpEasing.standard) =
    androidx.compose.animation.core.tween<Float>(d, easing = easing)

private fun tweenI(d: Int, easing: androidx.compose.animation.core.Easing = LpEasing.standard) =
    androidx.compose.animation.core.tween<androidx.compose.ui.unit.IntOffset>(d, easing = easing)

/**
 * Toast。**位置:全站中部偏下**【用户定,三端统一】。
 *
 * ★ 不用系统 `android.widget.Toast`:位置不可控、Android 12+ 强制加图标和应用名、
 *   播放页全屏下会被系统栏顶位置(UI_MOBILE.md §6.1)。
 */
@Composable
private fun ToastHost() {
    val app = LocalApp.current
    val c = Lp.colors
    var cur by remember { mutableStateOf<xyz.linplayer.app.data.Toast?>(null) }

    LaunchedEffect(Unit) {
        app.toasts.collect {
            cur = it
            delay(if (it.kind == ToastKind.Error) 5000 else 3000)
            cur = null
        }
    }

    Box(Modifier.fillMaxSize().padding(bottom = 128.dp), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = cur != null,
            enter = slideInVertically(tweenI(T.T5, LpEasing.emphasizedDecelerate)) { it / 3 } + fadeIn(tween(T.T5)),
            exit = fadeOut(tween(T.T4)),
        ) {
            val t = cur
            Text(
                t?.text.orEmpty(),
                Modifier.padding(horizontal = Sp.x26)
                    .clip(RoundedCornerShape(R.sm))
                    .background(if (t?.kind == ToastKind.Error) c.bad else c.s3)
                    .padding(horizontal = Sp.x16, vertical = Sp.x12),
                color = if (t?.kind == ToastKind.Error) c.accFg else c.fg,
                fontSize = 13.sp,
            )
        }
    }
}

/**
 * 官方路由名(插件 SPEC 20.3)→ 手机路由对象。**这是那张表在手机端唯一的一份**,
 * 插件的 `nav.push` 和真机自检直达都从这里出。认不出来回 null,调用方负责报错。
 *
 * 凭据页(`server.add` `login`)**能跳但不能接管**(D407):跳的是添加服务器那一版。
 */
internal fun officialRoute(route: String, p: (String) -> String?): Any? = when (route) {
    "home" -> Route.Home
    "aggregate" -> Route.Aggregate
    "library" -> Route.Library(p("id") ?: p("viewId") ?: "", p("title") ?: "媒体库")
    "detail" -> Route.Detail(p("id") ?: p("itemId") ?: "", p("type") ?: "Series")
    "search" -> Route.Search(p("viewId"), p("q"))
    "history" -> Route.History
    "favorites" -> Route.Favorites
    "downloads" -> Route.Downloads
    "ranking" -> Route.Ranking
    "calendar" -> Route.Calendar
    "servers" -> Route.Servers
    "player" -> Route.Player(p("id") ?: p("itemId") ?: "", p("title") ?: "播放")
    "plugins" -> Route.Plugins(p("tab")?.toIntOrNull() ?: 0)
    "settings" -> Route.Settings
    "settings.extensions" -> Route.Extensions
    "server.add", "login" -> Route.AddServer
    // 手机上「账号」就是服务器列表(一台服务器一个账号),没有独立的账号页
    "settings.account" -> Route.Servers
    // 设置二级页在手机上按功能分组,组名和锚点名一一对应(见 SettingsSubPage 的 anchor)
    "settings.appearance" -> Route.SettingsSub("appearance")
    "settings.playback" -> Route.SettingsSub("player")
    "settings.danmaku" -> Route.SettingsSub("danmaku")
    "settings.storage" -> Route.SettingsSub("storage")
    "settings.about" -> Route.SettingsSub("about")
    else -> null
}
