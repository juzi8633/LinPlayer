package xyz.linplayer.app.plugin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.linplayer.app.core.Logs
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.core.toJsonObject
import xyz.linplayer.app.ui.theme.LocalMotionScale
import xyz.linplayer.app.ui.theme.Lp

/**
 * 向核心层报运行环境(SPEC 7.7 20.4,D556 D558):主题明暗 + token 表 + 减少动态效果。
 *
 * 和 `plugin.setCapabilities` 分开报:能力是「这台机器能不能做某件事」一辈子不变,
 * 环境是「此刻长什么样」—— 用户切一次深浅色就变一次,合成一条的下场是切主题要重报能力。
 * token 值由**这一端**解出来:「acc 在浅色下是哪个色号」只有壳知道,核心层只转发。
 */
@Composable
fun ReportPluginEnv(app: AppState) {
    val c = Lp.colors
    val scale = LocalMotionScale.current
    LocalContext.current // 取过一次才保证在 Compose 树里调用
    /* 表在渲染器那边:解 `token:名字` 和报上去的是**同一张**(D556)。
       各写一份的话,改一个名字就有一处会悄悄失效,而失效的表现只是「颜色不对」。
       取色要在组合期做 —— tokenColor 是 @Composable,进不了 LaunchedEffect。 */
    val tokens = mutableMapOf<String, Any>()
    for (n in xyz.linplayer.app.ui.plugin.TOKEN_COLOR_NAMES) {
        xyz.linplayer.app.ui.plugin.tokenColor(n)?.let { tokens[n] = hex(it) }
    }
    for (n in xyz.linplayer.app.ui.plugin.TOKEN_NUMBER_NAMES) {
        xyz.linplayer.app.ui.plugin.tokenNumber(n)?.let { tokens[n] = it }
    }
    LaunchedEffect(c, scale) {
        withContext(Dispatchers.IO) {
            runCatching {
                app.core.callJson("plugin.setEnv", toJsonObject(mapOf(
                    "theme_mode" to if (c.isDark) "dark" else "light",
                    "theme_tokens" to tokens,
                    "reduced_motion" to (scale == 0f),
                )))
            }.onFailure { Logs.w("plugin", "报运行环境失败:" + it.message) }
        }
    }
}

/** `#rrggbbaa`。SPEC 20.4 定的就是这个写法 —— 安卓的 `#aarrggbb` 顺序是反的。 */
private fun hex(c: Color): String {
    val v = c.toArgb()
    return "#%02x%02x%02x%02x".format((v shr 16) and 0xFF, (v shr 8) and 0xFF, v and 0xFF, (v shr 24) and 0xFF)
}
