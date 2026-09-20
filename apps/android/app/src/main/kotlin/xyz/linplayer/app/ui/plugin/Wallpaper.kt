package xyz.linplayer.app.ui.plugin

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.json.JsonObject
import xyz.linplayer.app.core.Logs
import xyz.linplayer.app.data.str

/**
 * 视频壁纸的放行闸(D443)。
 *
 * ☠ 三个条件是**与**:任一要求暂停就暂停,三条都允许才放。
 *   写成「最后一个事件说了算」的话,「息屏回来 + 省电模式开着」会把它放起来 ——
 *   而用户开省电模式正是为了别让后台有东西在解码。所以记的是**理由的集合**,
 *   不是一个布尔。
 */
object WallpaperGate {
    const val PLAYER = "player"
    const val BACKGROUND = "background"
    const val POWER_SAVE = "powerSave"

    private var reasons by mutableStateOf(emptySet<String>())

    fun set(reason: String, pause: Boolean) {
        reasons = if (pause) reasons + reason else reasons - reason
    }

    val mayPlay: Boolean get() = reasons.isEmpty()

    /** 在播放页。视频壁纸这时要**整层卸掉**而不只是暂停,见 [WallpaperLayer]。 */
    val inPlayer: Boolean get() = PLAYER in reasons

    /** 日志里要写清是被谁拦的 —— 只说「暂停了」查不出是三条里的哪一条。 */
    val pausedBy: String get() = reasons.sorted().joinToString("+")

    internal fun reset() { reasons = emptySet() }
}

/**
 * 当前壁纸内容(SPEC 11.5)。进程级:`load: startup` 的壁纸插件在核心层起来那一刻
 * 就 set 完了,等第一次组合再订就已经错过 —— 事件不重发,内容也不落盘。
 */
object Wallpaper {
    private const val TAG = "壁纸"

    var content by mutableStateOf<JsonObject?>(null)
        private set

    fun set(o: JsonObject?) {
        content = o
        if (o != null) Logs.w(TAG, "换上 ${o["plugin"]} 的 ${o["kind"]} 壁纸")
    }
}

/** 包内资源走本地数据通道(SPEC 4.1 的 `/p/`),不自己开文件。 */
internal fun assetUrl(core: xyz.linplayer.app.core.CorePort, plugin: String, rel: String): String {
    if (rel.contains("://")) return rel
    val b = core.localBaseUrl
    return if (b.isEmpty()) rel else "$b/p/${core.localToken}/$plugin/${rel.trimStart('/')}"
}

/** `image` 既可能是字符串,也可能是 `ImageRef` 对象。 */
internal fun imageRef(d: JsonObject?): String {
    val v = d?.get("image") ?: return ""
    (v as? kotlinx.serialization.json.JsonPrimitive)?.let { if (it.isString) return it.content }
    return (v as? JsonObject).str("url") ?: ""
}

/** 有壁纸时页面底色让开 —— 不让的话壁纸永远被第一层不透明底盖住。 */
@androidx.compose.runtime.Composable
fun pageBg(official: androidx.compose.ui.graphics.Color): androidx.compose.ui.graphics.Color =
    if (Wallpaper.content == null) official else androidx.compose.ui.graphics.Color.Transparent
