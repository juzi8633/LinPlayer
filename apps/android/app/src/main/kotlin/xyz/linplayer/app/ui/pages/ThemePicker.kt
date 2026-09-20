package xyz.linplayer.app.ui.pages

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.ToastKind
import xyz.linplayer.app.data.UiPrefs
import xyz.linplayer.app.data.arr
import xyz.linplayer.app.data.obj
import xyz.linplayer.app.data.str
import xyz.linplayer.app.ui.components.Hairline
import xyz.linplayer.app.ui.components.LpCell
import xyz.linplayer.app.ui.components.LpDialog
import xyz.linplayer.app.ui.components.OptRow
import xyz.linplayer.app.ui.components.SliderRow
import xyz.linplayer.app.ui.plugin.Wallpaper

private const val OFFICIAL = "官方主题"

/** 这一端能选的主题。TV 的包只在 TV 上出现(D72),所以 platform 要按形态给。 */
@Composable
private fun platform(): String =
    if (xyz.linplayer.app.ui.plugin.LocalPluginTv.current) "android_tv" else "android"

/**
 * 选主题(SPEC 11.6)。**重启生效** —— token 在第一次组合之前就定下来了,
 * 当场换等于半屏新半屏旧。
 */
@Composable
fun ThemePickerCell() {
    val app = LocalApp.current
    val plat = platform()
    var list by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var current by remember { mutableStateOf("") }
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(plat) {
        list = runCatching { app.call("plugin.themes", args("platform" to plat)) }
            .getOrNull().let { it as? JsonArray }?.mapNotNull { e -> e.obj() }.orEmpty()
        current = runCatching { app.call("plugin.activeTheme", args("platform" to plat)) }
            .getOrNull().obj().str("plugin_id").orEmpty()
    }
    val name = list.firstOrNull { it.str("plugin_id") == current }.str("name") ?: OFFICIAL
    LpCell("界面主题", sub = "$name。换主题重启后生效", onClick = { open = true })
    if (!open) return
    LpDialog({ open = false }, "界面主题") {
        Column {
            OptRow(OFFICIAL, selected = current.isEmpty(), onClick = { pick(app, "") { current = "" }; open = false })
            list.forEach { t ->
                val id = t.str("plugin_id").orEmpty()
                OptRow(t.str("name") ?: id, selected = id == current,
                    onClick = { pick(app, id) { current = id }; open = false })
            }
        }
    }
}

private fun pick(app: xyz.linplayer.app.data.AppState, id: String, onOk: () -> Unit) {
    app.bg.launch {
        runCatching { app.call("plugin.setActiveTheme", args("id" to id)) }
            .onSuccess { onOk(); app.toast("重启后生效", ToastKind.Ok) }
            .onFailure { app.report(it) }
    }
}

/**
 * 选壁纸(SPEC 11.5)。**切换即时生效**,不重启。
 *
 * 模糊与压暗只在真有壁纸时才画:没壁纸时它们调了什么都不会变,
 * 而一个摆在那里不生效的滑块比没有更糟。
 */
@Composable
fun WallpaperPickerCell() {
    val app = LocalApp.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var list by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var current by remember { mutableStateOf("") }
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        list = runCatching { app.call("plugin.wallpapers") }
            .getOrNull().let { it as? JsonArray }?.mapNotNull { e -> e.obj() }.orEmpty()
        current = runCatching { app.call("plugin.activeWallpaper") }.getOrNull().obj().str("id").orEmpty()
    }
    val name = list.firstOrNull { it.str("plugin_id") == current }
        .let { it.str("title") ?: it.str("name") } ?: "无"
    LpCell("壁纸", sub = "$name。切换立刻生效", onClick = { open = true })

    if (Wallpaper.content != null) {
        if (xyz.linplayer.app.ui.plugin.canBlurWallpaper) {
        Hairline()
        SliderRow("壁纸模糊", UiPrefs.wallBlur.value, 0f..1f,
            { UiPrefs.setWall(ctx, it, UiPrefs.wallDim.value) },
            sub = "糊掉壁纸细节,保证正文读得清", fmt = { "${(it * 100).toInt()}%" })
        }
        Hairline()
        SliderRow("壁纸压暗", UiPrefs.wallDim.value, 0f..1f,
            { UiPrefs.setWall(ctx, UiPrefs.wallBlur.value, it) },
            fmt = { "${(it * 100).toInt()}%" })
    }
    if (!open) return
    LpDialog({ open = false }, "壁纸") {
        Column {
            OptRow("无", selected = current.isEmpty(), onClick = { setWall(app, "") { current = "" }; open = false })
            list.forEach { w ->
                val id = w.str("plugin_id").orEmpty()
                OptRow(w.str("title") ?: w.str("name") ?: id, selected = id == current,
                    onClick = { setWall(app, id) { current = id }; open = false })
            }
        }
    }
}

private fun setWall(app: xyz.linplayer.app.data.AppState, id: String, onOk: () -> Unit) {
    app.bg.launch {
        runCatching {
            app.call("plugin.setActiveWallpaper", args("id" to id))
            // 换完问一次:只实现 `wallpaper` 入口、从不调 set 的插件靠它才画得出来
            if (id.isEmpty()) Wallpaper.set(null)
            else Wallpaper.set(app.call("plugin.initialWallpaper").obj())
        }.onSuccess { onOk() }.onFailure { app.report(it) }
    }
}
