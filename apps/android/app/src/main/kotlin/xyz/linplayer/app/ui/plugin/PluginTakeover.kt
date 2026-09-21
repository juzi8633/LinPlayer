package xyz.linplayer.app.ui.plugin

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import kotlinx.serialization.json.JsonObject
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.arr
import xyz.linplayer.app.data.obj
import xyz.linplayer.app.data.str

/**
 * 官方页被插件整页接管(SPEC 6.1,D15 D29 D586)。
 *
 * ☠ 这条路以前**只差最后一段**:插件页的「接管位」标签能列、能选,选完还弹
 *   「重启应用后生效」—— 而没有任何一个壳去问过谁接管了哪一页。表现是
 *   「选了接管,重启,一点变化没有」。摆着不生效的控件比没有更糟。
 *
 * 想回官方版:插件页「接管位」标签里选「官方」。
 */
object PluginTakeovers {
    /** 路由名 → 插件 id 与页面 id。null = 还没问过。 */
    private val map = mutableStateOf<Map<String, Pair<String, String>>?>(null)

    val ready: Boolean get() = map.value != null

    operator fun get(route: String): Pair<String, String>? = map.value?.get(route)

    /**
     * 问一次。重启生效(「接管位」标签改完弹的就是这句话),所以只在根组合里调。
     *
     * 取不到就当没人接管 —— 一次 IPC 抖动不该把整个界面换成空白。
     */
    suspend fun load(app: AppState) {
        val rows = runCatching { app.call("plugin.pageTakeovers") }.getOrNull().arr().mapNotNull { it.obj() }
        map.value = rows.mapNotNull { it.entry() }.toMap()
    }

    private fun JsonObject.entry(): Pair<String, Pair<String, String>>? {
        val target = str("target")?.takeIf { it.isNotEmpty() } ?: return null
        val pid = str("plugin_id")?.takeIf { it.isNotEmpty() } ?: return null
        val page = str("page")?.takeIf { it.isNotEmpty() } ?: return null
        return target to (pid to page)
    }
}

/** 根组合里挂一次:接管表要在第一页画出来之前到位。 */
@Composable
fun LoadTakeovers() {
    val app = LocalApp.current
    LaunchedEffect(Unit) { PluginTakeovers.load(app) }
}

/** 这一页被接管了就画插件那一版,否则画官方的。 */
@Composable
fun Takeover(route: String, official: @Composable () -> Unit) {
    val t = PluginTakeovers[route]
    if (t == null) {
        official()
        return
    }
    PluginSurface(t.first, t.second, "page", modifier = Modifier.fillMaxSize())
}
