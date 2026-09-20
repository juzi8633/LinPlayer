package xyz.linplayer.app.tv

import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import android.app.Activity
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.ToastKind
import xyz.linplayer.app.data.long
import xyz.linplayer.app.data.obj
import xyz.linplayer.app.data.str
import xyz.linplayer.app.tv.kit.LocalTvType
import xyz.linplayer.app.tv.kit.Rail
import xyz.linplayer.app.tv.kit.Skel
import xyz.linplayer.app.tv.kit.TvButton
import xyz.linplayer.app.tv.kit.TvC
import xyz.linplayer.app.tv.kit.TvDim
import xyz.linplayer.app.tv.kit.TvR
import xyz.linplayer.app.tv.kit.TvSp
import xyz.linplayer.app.tv.kit.TvText
import xyz.linplayer.app.tv.kit.TvToast
import xyz.linplayer.app.tv.kit.TvW
import xyz.linplayer.app.tv.kit.TypeB
import xyz.linplayer.app.tv.kit.tvType
import xyz.linplayer.app.ui.pages.args
import xyz.linplayer.app.ui.pages.autoCheckUpdate
import xyz.linplayer.app.ui.pages.runUpdate

/** 当前活跃的是不是本机文件夹源。首页和「媒体库」轨项在它下面长得完全不一样(§7.14)。 */
val LocalIsLocalSource = staticCompositionLocalOf { false }

/**
 * TV 根(UI_TV.md §7.0)。和手机根共用同一个 [AppState],自己再 provide 一次。
 *
 * `loggedIn == null` → 首页骨架(带轨);`false` → 首次启动页(不画轨);`true` → 外壳。
 */
@Composable
fun TvRoot(app: AppState) {
    val base = LocalViewConfiguration.current
    // 长按 600ms:Android 默认 500,遥控器确认键行程长、沙发上按得慢,500 会误触(§4.4)
    val tvConfig = remember(base) {
        object : ViewConfiguration by base { override val longPressTimeoutMillis: Long = 600L }
    }
    TvFrame(app, tvConfig) {
        val loggedIn by app.loggedIn.collectAsStateWithLifecycle()
        val nav = remember { TvNav() }
        LaunchedEffect(Unit) { app.boot() }
        // 手机遥控开机即起(§9.3 默认开)。手机形态从不调这条,所以手机上不监听
        LaunchedEffect(Unit) { runCatching { app.call("companion.start") } }
        CompanionBridge(nav)
        when (loggedIn) {
            null -> TvShell(nav, booting = true)
            false -> OnboardingPage(embedded = false)
            true -> TvShell(nav)
        }
    }
}

/** 主题 + 字阶 + 深色底 + Toast。拆出来是给 JVM 出图 / 焦点测试直接挂真页面用的。 */
@Composable
fun TvFrame(app: AppState, config: ViewConfiguration = LocalViewConfiguration.current, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalApp provides app, LocalTvType provides TypeB, LocalViewConfiguration provides config) {
        MaterialTheme(colorScheme = darkColorScheme(background = TvC.bg, surface = TvC.surface2, onSurface = TvC.fg)) {
            Box(Modifier.fillMaxSize().background(TvC.bg)) {
                content()
                TvToastHost()
            }
        }
    }
}

/**
 * 外壳:导航轨 + 当前页 + 返回键第 3 / 4 级(§3.4)。
 *
 * ☠ **所有返回都从 BackHandler 走**:面板、页内模式的 BackHandler 组合得更晚,优先级更高 ——
 *   页面自己不许再监听 KEYCODE_BACK,否则就是旧实现那种「面板没关、页面先退了」。
 */
@Composable
fun TvShell(nav: TvNav, booting: Boolean = false) {
    val app = LocalApp.current
    val activity = LocalContext.current as? Activity
    val holder = rememberSaveableStateHolder()
    nav.onRemoved = { holder.removeState(it) }
    var exitArmedAt by remember { mutableStateOf(Long.MIN_VALUE / 2) }
    BackHandler {
        if (nav.pop()) return@BackHandler
        val now = SystemClock.uptimeMillis()
        // 第 4 级不用确认对话框【用户定 2026-09-14】:「再按一次」只多一次按键,不打断画面
        if (now - exitArmedAt < 2000) activity?.finish()
        else { exitArmedAt = now; app.toast("再按一次返回退出") }
    }

    val session by app.session.collectAsStateWithLifecycle()
    var isLocal by remember { mutableStateOf(false) }
    LaunchedEffect(session) {
        isLocal = runCatching { app.call("source.currentSource") }.getOrNull().obj().str("source_kind") == "local"
    }

    val top = nav.top
    val rail = top.route.rail
    val railReq = remember { FocusRequester() }
    Box(Modifier.fillMaxSize().background(TvC.bg)) {
        Box(
            Modifier.fillMaxSize().padding(start = if (rail >= 0) TvDim.railW else 0.dp)
                // 轨上按 → 回内容区:焦点落回离开时的那个元素(§3.1)
                .focusProperties { onEnter = { top.memory.restore() } }
                .focusGroup(),
        ) {
            holder.SaveableStateProvider(top.id) {
                CompositionLocalProvider(
                    LocalFocusMemory provides top.memory, LocalNav provides nav, LocalIsLocalSource provides isLocal,
                    LocalRailFocus provides railReq.takeIf { rail >= 0 },
                ) {
                    if (booting) HomeSkeleton() else TvPage(top.route)
                }
            }
        }
        if (rail >= 0) Rail(current = rail, modifier = Modifier.focusRequester(railReq), onSelect = { i -> nav.rail(railRoute(i)) })
    }
    if (!booting) UpdateCheck()
}

@Composable
private fun TvPage(r: TvRoute) {
    when (r) {
        TvRoute.Home -> {
            // 当前是插件数据源:首页换成数据源首页(D167)
            val src by LocalApp.current.activeSource.collectAsStateWithLifecycle()
            src?.let { SourceHomeTv(it.id, it.name) } ?: HomePage()
        }
        TvRoute.Search -> SearchPage()
        is TvRoute.Library -> if (r.viewId == null && LocalIsLocalSource.current) LocalBrowsePage(null)
            else if (r.viewId == null) LibraryPickerPage() else LibraryGridPage(r)
        TvRoute.Favorites -> FavoritesPage()
        TvRoute.Discover -> DiscoverPage()
        TvRoute.Downloads -> DownloadsPage()
        TvRoute.Servers -> ServersPage()
        TvRoute.Settings -> SettingsPage()
        is TvRoute.Lines -> LinesPage(r)
        TvRoute.AddServer -> OnboardingPage(embedded = true)
        TvRoute.LocalPicker -> LocalPickerPage()
        is TvRoute.LocalDir -> LocalBrowsePage(r)
        is TvRoute.Detail -> DetailPage(r)
        is TvRoute.Episode -> EpisodePage(r)
        is TvRoute.Player -> TvPlayerPage(r)
        is TvRoute.SourceCategory -> SourceCategoryTv(r)
        is TvRoute.SourceDetail -> SourceDetailTv(r)
        TvRoute.Plugins -> PluginsPageTv()
        TvRoute.Extensions -> ExtensionsPageTv()
        is TvRoute.PluginPage -> PluginHostPageTv(r)
        TvRoute.History -> HistoryPageTv()
    }
}

/** 冷启动(会话判定没完成):画首页骨架,**不画转圈、不先画登录页再跳走**(§6.3)。 */
@Composable
fun HomeSkeleton() {
    Column(Modifier.fillMaxSize().padding(start = TvSp.x32, end = TvDim.safeH, top = TvDim.safeV)) {
        Skel(Modifier.fillMaxWidth().height(TvDim.heroH), TvR.lg)
        repeat(2) {
            Spacer(Modifier.height(TvSp.x20))
            Skel(Modifier.width(96.dp).height(16.dp))
            Spacer(Modifier.height(TvSp.x8))
            Row(horizontalArrangement = Arrangement.spacedBy(TvSp.x12)) {
                repeat(5) { Skel(Modifier.size(TvDim.wideW, TvDim.wideH)) }
            }
        }
    }
}

/** Toast(§5.5):底部居中,3 秒,**不抢焦点**,连续两条排队不叠放。 */
@Composable
private fun TvToastHost() {
    val app = LocalApp.current
    var cur by remember { mutableStateOf<xyz.linplayer.app.data.Toast?>(null) }
    LaunchedEffect(Unit) {
        app.toasts.collect {
            cur = it
            delay(3000)
            cur = null
        }
    }
    val t = cur ?: return
    Box(Modifier.fillMaxSize()) {
        TvToast(t.text, when (t.kind) { ToastKind.Ok -> TvC.ok; ToastKind.Error -> TvC.bad; ToastKind.Info -> TvC.warn })
    }
}

/**
 * 手机遥控的电视这一侧(§9.4)。
 *
 * ★ 按键注入走**和真遥控器同一个入口**:方向 / 确认 / 菜单合成真 KeyEvent 交给 Activity,
 *   返回走 OnBackPressedDispatcher(和遥控器返回键落到的是同一组 BackHandler)。不另写一套。
 */
@Composable
private fun CompanionBridge(nav: TvNav) {
    val app = LocalApp.current
    val activity = LocalContext.current as? ComponentActivity
    LaunchedEffect(Unit) {
        app.core.events.collect { ev ->
            val d = ev.data.obj()
            when (ev.name) {
                "companion.key" -> when (val k = d.str("key")) {
                    "back" -> activity?.onBackPressedDispatcher?.onBackPressed()
                    "home" -> nav.rail(TvRoute.Home)
                    else -> keyCodeOf(k)?.let { code ->
                        val t = SystemClock.uptimeMillis()
                        activity?.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
                        activity?.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
                    }
                }
                // ☠ 手机上加 / 删 / 切服务器:电视要立刻换屏。旧版漏发这个广播,电视停在「添加服务器」那一屏
                "account.status" -> app.refreshSession()
                "companion.open" -> openFromPhone(app, nav, d)
            }
        }
    }
}

private fun keyCodeOf(k: String?): Int? = when (k) {
    "up" -> KeyEvent.KEYCODE_DPAD_UP; "down" -> KeyEvent.KEYCODE_DPAD_DOWN
    "left" -> KeyEvent.KEYCODE_DPAD_LEFT; "right" -> KeyEvent.KEYCODE_DPAD_RIGHT
    "ok" -> KeyEvent.KEYCODE_DPAD_CENTER; "menu" -> KeyEvent.KEYCODE_MENU
    "playpause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
    else -> null
}

/** 事件带 `server_id` 且不是当前服务器 → 先切服务器,**成功后**再进详情页。 */
private suspend fun openFromPhone(app: AppState, nav: TvNav, d: JsonObject?) {
    val id = d.str("item_id") ?: return
    val server = d.str("server_id")
    if (!server.isNullOrBlank() && !switchServerIfNeeded(app, server)) return
    nav.push(if (d.str("type") == "Episode") TvRoute.Episode(id) else TvRoute.Detail(id, d.str("type") ?: "Movie"))
}

/** 「启动时自动检查更新」。关着就一次都不查(那是个会自己联网的行为,默认关)。 */
@Composable
private fun UpdateCheck() {
    val app = LocalApp.current
    var offer by remember { mutableStateOf<JsonObject?>(null) }
    LaunchedEffect(Unit) { offer = autoCheckUpdate(app) }
    offer?.let { u -> Box(Modifier.fillMaxSize()) { UpdateDialog(u) { offer = null } } }
}

/** 有新版之后:先给说明让人决定,再给进度。下载跑在 app.bg 上 —— 页面死了进度也不能断。 */
@Composable
fun UpdateDialog(u: JsonObject, onClose: () -> Unit) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val t = tvType
    var prog by remember(u) { mutableStateOf<JsonObject?>(null) }
    var busy by remember(u) { mutableStateOf(false) }
    BackHandler { if (!busy) onClose() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(420.dp).clip(TvR.lg).background(TvC.surface2).padding(TvSp.x20)
                .focusProperties { onExit = { cancelFocusChange() } }.focusGroup(),
        ) {
            TvText("新版本 " + (u.str("version") ?: ""), t.title, TvC.fg, weight = TvW.semi)
            Spacer(Modifier.height(TvSp.x8))
            Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                TvText(u.str("notes")?.takeIf { it.isNotBlank() } ?: "这一版没有更新说明。", t.body, TvC.fg2, maxLines = 12)
            }
            Spacer(Modifier.height(TvSp.x12))
            val got = prog.long("downloaded") ?: 0L
            val total = prog.long("total") ?: 0L
            if (busy) TvText(if (total > 0) "%.1f MB / %.1f MB".format(got / 1048576.0, total / 1048576.0)
                else "正在下载…", t.meta, TvC.fg2)
            Spacer(Modifier.height(TvSp.x16))
            Row(horizontalArrangement = Arrangement.spacedBy(TvSp.x12)) {
                TvButton("取消", focused = true, onClick = {
                    if (busy) app.bg.launch { runCatching { app.call("system.cancelUpdate") } } else onClose()
                })
                if (!busy) TvButton("下载并安装", primary = true, onClick = {
                    busy = true
                    app.bg.launch { runUpdate(app, ctx) { prog = it }; onClose() }
                })
            }
        }
    }
}

/**
 * 需要时切到另一台服务器。**成功后**才返回 true —— 不先切服就进详情页 =
 * 拿当前服的 token 去问不存在的 id =「点进去是空白页」(§7.7)。
 */
suspend fun switchServerIfNeeded(app: AppState, serverId: String): Boolean {
    val accounts = xyz.linplayer.app.data.Account.list(runCatching { app.call("account.listAccounts") }.getOrNull())
    val target = accounts.firstOrNull { it.id == serverId } ?: return false
    if (target.isActive) return true
    return runCatching { app.call("account.setActiveServer", args("server_id" to serverId)) }
        .onSuccess { app.refreshSession(); app.toast("已切换到 ${target.name}", ToastKind.Ok) }
        .onFailure { app.report(it) }.isSuccess
}
