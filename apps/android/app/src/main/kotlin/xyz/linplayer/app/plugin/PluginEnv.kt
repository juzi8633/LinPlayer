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
    LaunchedEffect(c, scale) {
        val tokens = mutableMapOf<String, Any>(
            "color.bg" to hex(c.bg), "color.surface" to hex(c.s1), "color.surfaceAlt" to hex(c.s2),
            "color.ink" to hex(c.fg), "color.ink2" to hex(c.fg2), "color.ink3" to hex(c.fg3),
            "color.line" to hex(c.line), "color.lineStrong" to hex(c.line2),
            "color.accent" to hex(c.acc), "color.accentInk" to hex(c.accFg), "color.accentSoft" to hex(c.accDim),
            "color.ok" to hex(c.ok), "color.warn" to hex(c.warn), "color.danger" to hex(c.bad),
        )
        tokens.putAll(NumberTokens)
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

/** 刻度是枚举不是区间(UI_MOBILE.md §1.3):这里是那把尺子对外的那一份。 */
private val NumberTokens = mapOf<String, Any>(
    "radius.small" to 6, "radius.card" to 10, "radius.pill" to 999,
    "space.xs" to 2, "space.sm" to 6, "space.md" to 10, "space.lg" to 14, "space.xl" to 18,
    "font.size.body" to 14, "font.size.title" to 18, "font.size.h1" to 26,
    "motion.duration.fast" to 120, "motion.duration.normal" to 220,
)
