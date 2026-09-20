package xyz.linplayer.app.ui.plugin

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import xyz.linplayer.app.core.Logs
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.arr
import xyz.linplayer.app.data.bool
import xyz.linplayer.app.data.obj
import xyz.linplayer.app.data.str
import xyz.linplayer.app.ui.pages.args

/** 播放页上的一块插件面儿(`plugin.playerSurfaces` 的一行)。 */
data class PlayerSurfaceInfo(
    val pluginId: String, val target: String,
    val title: String, val icon: String, val interactive: Boolean,
)

/**
 * 播放页的两个插件挂载点(SPEC 9.5,D65 D279 D300)。
 *
 * ☠ 这张表**只能问核心层**:`plugin.list` 回的 contributes 是中文名字符串,
 * 没有 id 也没有 interactive;而且「停用了插件覆盖层还在」这种错自己扫 manifest 必踩。
 */
@Composable
fun rememberPlayerSurfaces(kind: String): List<PlayerSurfaceInfo> {
    val app = LocalApp.current
    var rows by remember(kind) { mutableStateOf(emptyList<PlayerSurfaceInfo>()) }
    LaunchedEffect(kind) {
        rows = runCatching { app.call("plugin.playerSurfaces", args("kind" to kind)) }
            .onFailure { Logs.w("播放页", "插件${if (kind == "panel") "侧栏页" else "覆盖层"}表拉不到,这一场不挂:${it.message}") }
            .getOrNull().arr().mapNotNull { e ->
                val o = e.obj() ?: return@mapNotNull null
                PlayerSurfaceInfo(
                    o.str("plugin_id") ?: return@mapNotNull null,
                    o.str("id") ?: return@mapNotNull null,
                    o.str("title") ?: "", o.str("icon") ?: "", o.bool("interactive"),
                )
            }
    }
    return rows
}

/**
 * 铺满播放区的一批覆盖层。调用方按 [interactive] 分两处挂,靠**叠放次序**保证穿透:
 * 不拦的那批压在全屏手势层底下,命中测试根本到不了它们(Compose 没有 IsHitTestVisible)。
 */
@Composable
fun PlayerOverlays(rows: List<PlayerSurfaceInfo>, interactive: Boolean) {
    rows.forEach { s ->
        if (s.interactive != interactive) return@forEach
        PluginSurface(
            s.pluginId, s.target, "overlay",
            /* 遥控器焦点也要穿透:`canFocus = false` 只停掉这一层,子节点照样能被搜到;
               `onEnter` 取消才是整棵子树进不去。 */
            modifier = if (interactive) Modifier.fillMaxSize()
            else Modifier.fillMaxSize().focusProperties { onEnter = { cancelFocusChange() } }.focusGroup(),
            claimInitialFocus = false,
            focusNs = "${s.pluginId}/${s.target}.",
        )
    }
}

/** 侧边面板那一栏里,插件标签对应的面板名。 */
fun pluginPanelKind(s: PlayerSurfaceInfo): String = "plugin:${s.pluginId}|${s.target}"
