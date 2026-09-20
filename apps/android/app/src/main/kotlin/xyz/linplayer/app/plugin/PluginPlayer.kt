package xyz.linplayer.app.plugin

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import xyz.linplayer.app.data.bool
import xyz.linplayer.app.data.str

/**
 * 插件的 `player.openPanel` / `player.setOsdVisible`(D67 D162)。
 *
 * 官方子面板和 OSD 都是**当前那个播放页的局部状态**,所以和 [PluginNav] 一样:
 * 页面在树上时登记自己,离页时摘掉。
 */
object PluginPlayer {
    interface Host {
        /** 打开这个官方面板;这一端没有就抛 [UnsupportedOperationException] 说清原因。 */
        fun openPanel(which: String)
        fun setOsdVisible(visible: Boolean)
    }

    @Volatile var host: Host? = null

    val ops = setOf("player.openPanel", "player.setOsdVisible")

    fun handle(op: String, args: JsonObject): JsonElement {
        val h = host ?: throw UnsupportedOperationException("现在不在播放页,$op 做不了")
        when (op) {
            "player.openPanel" -> h.openPanel(args.str("panel") ?: "")
            "player.setOsdVisible" -> h.setOsdVisible(args.bool("visible"))
        }
        return JsonNull
    }

    /**
     * SDK 的 `PlayerPanel` → 手机端面板名。
     *
     * 两端面板不是一套,所以各翻各的 —— 合成一张表的下场是两边都对不上。
     */
    fun phoneKind(panel: String, hasExo: Boolean): String = when (panel) {
        "subtitles" -> "subtitle"
        "audio", "danmaku", "episodes", "more" -> panel
        "lines" -> "source"
        // 画面增强是 mpv 的 glsl-shaders;Exo 那条路上开了也是空转,不如说清楚
        "enhance" -> if (hasExo) no("当前是 Exo 内核,没有画面增强这一档") else "quality"
        "speed" -> no("手机端倍速是控制栏上的步进按钮,没有单独的面板")
        else -> no("安卓手机端没有「$panel」这个面板")
    }

    /** SDK 的 `PlayerPanel` → TV 端面板名。 */
    fun tvKind(panel: String): String = when (panel) {
        "subtitles" -> "sub"
        "audio", "danmaku", "episodes", "more" -> panel
        // 这三项在 TV 上没有独立侧栏,直接开会开出一个空面板
        "speed", "enhance", "lines" -> no("TV 端「$panel」在「更多」面板里,请用 openPanel('more')")
        else -> no("安卓 TV 端没有「$panel」这个面板")
    }

    private fun no(why: String): Nothing = throw UnsupportedOperationException(why)
}
