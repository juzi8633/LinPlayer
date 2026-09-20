package xyz.linplayer.app.ui.plugin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import xyz.linplayer.app.core.Logs
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.arr
import xyz.linplayer.app.data.obj
import xyz.linplayer.app.data.str

/**
 * 安卓这一端登记的锚点名(SPEC 20.3 那张表)。
 *
 * ☠ 这是**公开契约**,改名算破坏性变更;SPEC 19.5 的门禁拿这张表和文档对账,
 * 所以名字只能写在这里,不许散落成各页里的字符串字面量。
 */
object PluginAnchors {
    const val DETAIL_HEADER = "detail.header"
    const val DETAIL_TITLE = "detail.title"
    const val DETAIL_ACTIONS = "detail.actions"
    const val DETAIL_OVERVIEW = "detail.overview"
    const val DETAIL_RATINGS = "detail.ratings"
    const val DETAIL_SEASONS = "detail.seasons"
    const val DETAIL_EPISODES = "detail.episodes"
    const val DETAIL_CAST = "detail.cast"
    const val DETAIL_SIMILAR = "detail.similar"
    const val DETAIL_FOOTER = "detail.footer"

    /**
     * 详情页锚点。手机 `ui/pages/DetailPage.kt`、TV `tv/DetailPages.kt` 各画一遍。
     * SPEC 20.3 有、这里没有的只剩 `detail.lines` —— 线路是「播放选项」里的一行,不是一块。
     */
    val Detail = listOf(
        DETAIL_HEADER, DETAIL_TITLE, DETAIL_ACTIONS, DETAIL_OVERVIEW, DETAIL_RATINGS,
        DETAIL_SEASONS, DETAIL_EPISODES, DETAIL_CAST, DETAIL_SIMILAR, DETAIL_FOOTER,
    )

    /**
     * 官方设置页的分节锚点。粒度是**页**不是节:`settings.playback.general`
     * 这种带子节的名字按前缀归到 `settings.playback` 这一页的末尾。
     */
    val Settings = listOf(
        "settings", "settings.appearance", "settings.playback",
        "settings.subtitle", "settings.danmaku", "settings.storage", "settings.about",
    )

    /** 凭据页不开放(D407):这两个锚点上的分节一律不画,挡了要留日志。 */
    val CredentialDenied = listOf("settings.account", "settings.network")

    /** 门禁读的就是这一个。 */
    val All = Detail + Settings
}

/** 一条注入/接管登记。字段名照 `core/plugin/anchors.go` 的 `AnchorBlock`。 */
data class AnchorEntry(
    val pluginId: String, val name: String, val anchor: String,
    val mode: String, val block: String,
)

/**
 * 一页的锚点上下文:锚点表 + 要发给插件块的 props。
 *
 * props 是**当前条目的完整统一数据**(D160)—— 插件块拿它自画,不必再发一遍请求。
 */
class AnchorScope(
    val blocks: Map<String, List<AnchorEntry>> = emptyMap(),
    val props: Map<String, Any?> = emptyMap(),
)

val LocalAnchors = staticCompositionLocalOf { AnchorScope() }

/**
 * 拉一次 `plugin.anchors` 并按锚点分组。
 *
 * 只拉**一次**、不按锚点逐个问:一页七个锚点等于七次跨语言调用,而返回的是同一张表。
 */
@Composable
fun rememberAnchorScope(names: List<String>, props: Map<String, Any?>): AnchorScope {
    val app = LocalApp.current
    var rows by remember { mutableStateOf<List<AnchorEntry>>(emptyList()) }
    LaunchedEffect(Unit) {
        val r = runCatching { app.call("plugin.anchors") }.getOrNull().arr()
        rows = r.mapNotNull { e ->
            val o = e.obj() ?: return@mapNotNull null
            AnchorEntry(
                o.str("plugin_id") ?: return@mapNotNull null, o.str("name") ?: "",
                o.str("anchor") ?: return@mapNotNull null, o.str("mode") ?: "after",
                o.str("block") ?: return@mapNotNull null,
            )
        }.filter { it.anchor in names }
    }
    return remember(rows, props) { AnchorScope(rows.groupBy { it.anchor }, props) }
}

/**
 * 一个锚点位。[official] 是官方本来画的那一块。
 *
 * `before`/`after` 全生效按返回顺序排;`replace`/`hide` 是接管位,同一锚点只认第一个
 * —— 谁是第一个由核心层的安装顺序决定,冲突选择也在那边(D15)。
 */
@Composable
fun Anchored(anchor: String, modifier: Modifier = Modifier, official: @Composable () -> Unit) {
    val scope = LocalAnchors.current
    val rows = scope.blocks[anchor]
    if (rows.isNullOrEmpty()) { official(); return }
    val takeover = rows.firstOrNull { it.mode == "replace" || it.mode == "hide" }
    rows.filter { it.mode == "before" }.forEach { AnchorSurface(it, scope.props, modifier) }
    when {
        takeover == null -> official()
        takeover.mode == "replace" -> AnchorSurface(takeover, scope.props, modifier)
        else -> Unit // hide:官方那一块不画,也不放插件块
    }
    rows.filter { it.mode == "after" }.forEach { AnchorSurface(it, scope.props, modifier) }
}

/**
 * 锚点块挂的 surface。
 *
 * ☠ `claimInitialFocus = false`:这是官方页,初始焦点归官方元素(详情页是播放键)。
 * 不关掉的话插件块里第一个按钮会和播放键抢,而抢赢抢输取决于哪一帧先到 —— 静态截图上
 * 看不出来,表现是「有时候进详情页焦点莫名其妙落在一块插件上」。
 */
@Composable
private fun AnchorSurface(e: AnchorEntry, props: Map<String, Any?>, modifier: Modifier) {
    PluginSurface(
        e.pluginId, e.block, "block",
        props = props + mapOf("anchor" to e.anchor),
        modifier = modifier,
        claimInitialFocus = false,
        focusNs = "${e.pluginId}/${e.block}.",
    )
}

/** 详情页那份条目 JSON → 锚点块的 props。取不到就空着,插件自己兜底。 */
fun detailProps(item: JsonObject?): Map<String, Any?> =
    mapOf("item" to (item ?: JsonObject(emptyMap())) as JsonElement)

/** 凭据页(或它下面的子节)。 */
internal fun denied(anchor: String): Boolean =
    PluginAnchors.CredentialDenied.any { anchor == it || anchor.startsWith("$it.") }

internal fun logDenied(anchor: String, sections: Int) {
    if (sections > 0) Logs.w("插件锚点", "$anchor 是凭据页,挡掉 $sections 节插件设置(D407)")
}
