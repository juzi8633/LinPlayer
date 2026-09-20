package xyz.linplayer.app.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag

/**
 * TV 的路由(UI_TV.md §3.3)。[rail] = 导航轨上高亮哪一项;-1 = 全屏页,不画轨。
 */
sealed interface TvRoute {
    val rail: Int

    data object Search : TvRoute { override val rail = 0 }
    data object Home : TvRoute { override val rail = 1 }
    /** 没带库 id = 选库;当前源是本机文件夹时这一项进的是文件夹浏览(§7.14)。 */
    data class Library(val viewId: String? = null, val title: String = "") : TvRoute { override val rail = 2 }
    data object Favorites : TvRoute { override val rail = 3 }
    data object Discover : TvRoute { override val rail = 4 }
    data object Downloads : TvRoute { override val rail = 5 }
    data object Servers : TvRoute { override val rail = 6 }
    data object Settings : TvRoute { override val rail = 7 }

    // ☠ 下面这些是**下钻,必须入栈**:线路管理、带库 id 的媒体库当平级处理的话,按一下返回就退出应用
    data class Lines(val serverId: String, val name: String) : TvRoute { override val rail = 6 }
    data object AddServer : TvRoute { override val rail = 6 }
    data object LocalPicker : TvRoute { override val rail = 6 }
    data class LocalDir(val dirId: String, val trail: List<Pair<String?, String>>) : TvRoute { override val rail = 2 }
    data class Detail(val itemId: String, val type: String) : TvRoute { override val rail = -1 }
    data class Episode(val itemId: String) : TvRoute { override val rail = -1 }
    data class Player(
        val itemId: String, val title: String,
        /** 只有用户**真按过确认**才有值(§7.6 picked);为空就不传,交给核心层的版本正则。 */
        val versionId: String? = null,
        val audioIndex: Long? = null, val subIndex: Long? = null, val subOff: Boolean = false,
        val engine: String? = null, val fromStart: Boolean = false,
        /** 本机文件夹里的视频走 `source.play`,不走 `player.play`。 */
        val localEntry: Boolean = false,
        /** 下载完成的任务走 `player.playLocal`(itemId 是任务 id)。 */
        val download: Boolean = false,
        /** 换内核 / 换线路 / 换版本重播时接着当前位置,不回到服务端记的进度。 */
        val resumeAt: Double? = null,
        /** 数据源播放:`{server_id, item, line_id, episode_id}` 的 JSON 原文,走 source.playItem。 */
        val src: String? = null,
    ) : TvRoute { override val rail = -1 }
    // 数据源(插件 SPEC 8.7)与插件页(14.7)
    data class SourceCategory(val serverId: String, val cat: String) : TvRoute { override val rail = 1 }
    data class SourceDetail(
        val serverId: String, val itemId: String,
        val autoLine: String? = null, val autoEp: String? = null, val autoPos: Double = 0.0, val autoIndex: Int = 0,
    ) : TvRoute { override val rail = -1 }
    data object Plugins : TvRoute { override val rail = 7 }
    data object Extensions : TvRoute { override val rail = 7 }
    /** 插件自己画的页面(SPEC 7.2 的 page surface)。 */
    data class PluginPage(val id: String, val page: String, val title: String) : TvRoute { override val rail = 7 }
    /** 全局观看历史(SPEC 8.7)。导航轨的八格是定死的(§3.1),所以它从收藏页下钻。 */
    data object History : TvRoute { override val rail = 3 }
}

/** 轨上第 i 项对应的平级页。 */
fun railRoute(i: Int): TvRoute = when (i) {
    0 -> TvRoute.Search; 1 -> TvRoute.Home; 2 -> TvRoute.Library(); 3 -> TvRoute.Favorites
    4 -> TvRoute.Discover; 5 -> TvRoute.Downloads; 6 -> TvRoute.Servers; else -> TvRoute.Settings
}

/**
 * 返回栈。自己管而不用 Navigation Compose:轨上切页要「重置为 [首页, 目标页]」且首页那一格的
 * 焦点记忆、滚动位置都得留着 —— 这是一张列表的三行操作,不值得绕进 NavController 的 popUpTo 语义。
 */
class TvNav {
    class Entry(val id: Long, val route: TvRoute) {
        val memory = FocusMemory()
    }

    private var seq = 0L
    val stack = mutableStateListOf(Entry(seq++, TvRoute.Home))
    val top: Entry get() = stack.last()

    /** 出栈的页要把它的 SaveableState 一起清掉,否则回到同名页会拿到上一次的滚动位置。 */
    var onRemoved: (Long) -> Unit = {}

    fun push(r: TvRoute) { stack.add(Entry(seq++, r)) }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        onRemoved(stack.removeAt(stack.lastIndex).id)
        return true
    }

    /** 轨上的页互为平级:栈重置为 [首页, 目标页];首页那一格原样保留。 */
    fun rail(r: TvRoute) {
        while (stack.size > 1) onRemoved(stack.removeAt(stack.lastIndex).id)
        if (r != TvRoute.Home) push(r)
    }

    /** 集详情页里换集不入栈(§3.3):切了 8 集,按一次返回就回剧详情。 */
    fun replaceTop(r: TvRoute) {
        onRemoved(top.id)
        stack[stack.lastIndex] = Entry(seq++, r)
    }
}

val LocalNav = staticCompositionLocalOf<TvNav> { error("TvNav 还没提供") }

/** 导航轨这个焦点组;没有轨的页面(详情、播放)为空。 */
val LocalRailFocus = staticCompositionLocalOf<FocusRequester?> { null }

/**
 * 一页的焦点记忆(§4.2):从下一级返回 / 面板关闭时,焦点回到打开它的那个元素。
 *
 * ☠ 面板一卸载焦点在树上没有落点 = **遥控器整个失灵**,TV 最经典的 P0。
 * ★ 记的是**键**不是 FocusRequester:页面被销毁重建之后,旧的 requester 已经不在树上。
 */
class FocusMemory {
    var key: String? = null
    internal val requesters = HashMap<String, FocusRequester>()

    fun restore(): Boolean {
        val fr = key?.let { requesters[it] } ?: return false
        return runCatching { fr.requestFocus() }.getOrDefault(false)
    }
}

val LocalFocusMemory = staticCompositionLocalOf { FocusMemory() }

/**
 * 给一个可聚焦元素挂上记忆。[initial] = 这一页的初始焦点(§7 各页规定的那个元素)。
 *
 * ★ 只在**这个元素第一次组合时**要焦点:异步回来的内容不许抢焦点(§4.2)——
 *   用户已经把焦点挪到别处时 [FocusMemory.key] 非空,初始焦点就不再生效。
 * ☠ Lazy 列表的项第一帧还没挂上焦点树,`requestFocus` 静默失败,所以按帧重试。
 */
@Composable
fun Modifier.memo(key: String, initial: Boolean = false): Modifier {
    val mem = LocalFocusMemory.current
    val fr = remember(key) { FocusRequester() }
    DisposableEffect(key) {
        mem.requesters[key] = fr
        onDispose { if (mem.requesters[key] === fr) mem.requesters.remove(key) }
    }
    LaunchedEffect(key) {
        if (mem.key != key && !(initial && mem.key == null)) return@LaunchedEffect
        repeat(10) {
            withFrameNanos { }
            if (runCatching { fr.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }
    return this.testTag(key).focusRequester(fr).onFocusChanged { if (it.isFocused) mem.key = key }
}

/**
 * 官方路由名(插件 SPEC 20.3)→ TV 路由对象。TV 只有八格轨,表里有几页这里就没有:
 * 排行榜 / 追剧日历 / 演员页在 TV 上不存在,回 null 让调用方报错,别静默不动。
 */
fun tvOfficialRoute(route: String, p: (String) -> String?): TvRoute? = when (route) {
    "home" -> TvRoute.Home
    "search" -> TvRoute.Search
    "library" -> TvRoute.Library(p("id") ?: p("viewId"), p("title") ?: "")
    "detail" -> TvRoute.Detail(p("id") ?: p("itemId") ?: "", p("type") ?: "Series")
    "favorites" -> TvRoute.Favorites
    "aggregate" -> TvRoute.Discover
    "downloads" -> TvRoute.Downloads
    "servers" -> TvRoute.Servers
    "history" -> TvRoute.History
    "player" -> TvRoute.Player(p("id") ?: p("itemId") ?: "", p("title") ?: "播放")
    "plugins" -> TvRoute.Plugins
    "settings.extensions" -> TvRoute.Extensions
    // TV 的设置是一页多节,没有二级页;凭据页就是添加服务器那一版(D407 能跳不能接管)
    "settings", "settings.playback", "settings.danmaku", "settings.subtitle",
    "settings.appearance", "settings.shortcuts", "settings.storage", "settings.about" -> TvRoute.Settings
    "server.add", "login", "settings.account" -> TvRoute.AddServer
    else -> null
}
