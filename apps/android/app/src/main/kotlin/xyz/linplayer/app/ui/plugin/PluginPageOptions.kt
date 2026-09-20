package xyz.linplayer.app.ui.plugin

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import xyz.linplayer.app.data.bool
import xyz.linplayer.app.data.str
import xyz.linplayer.app.plugin.PluginNav

/** 插件页面选项(SPEC 7.9 D219)里壳要画出来的那两项;其余在这里当场生效。 */
data class PluginPageOptions(val title: String, val immersive: Boolean, val edgeToEdge: Boolean)

/**
 * 应用当前插件页的页面选项,并在**离开页面时全部复原**。
 *
 * ★ 复原靠 `DisposableEffect` 的 onDispose,不靠插件自己调一次「关掉」——
 *   插件崩在半路时它没机会调,而屏幕会一直亮着、一直锁着横屏。
 */
@Composable
fun pluginPageOptions(defaultTitle: String): PluginPageOptions {
    val opts by PluginNav.pageOptions
    val activity = LocalContext.current as? Activity

    DisposableEffect(Unit) { onDispose { PluginNav.pageOptions.value = null } }

    val keepAwake = opts.bool("keepAwake")
    DisposableEffect(keepAwake) {
        val w = activity?.window
        if (keepAwake) w?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { if (keepAwake) w?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    val orientation = opts.str("orientation")
    DisposableEffect(orientation) {
        activity?.requestedOrientation = when (orientation) {
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    val immersive = opts.bool("immersive")
    DisposableEffect(immersive) {
        // 隐藏的是整组 systemBars:只藏状态栏的话手势条还在,沉浸页底下留一条黑边
        val w = activity?.window
        val ctl = w?.let { WindowInsetsControllerCompat(it, it.decorView) }
        if (immersive) {
            ctl?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctl?.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { if (immersive) ctl?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    return PluginPageOptions(opts.str("title")?.takeIf { it.isNotEmpty() } ?: defaultTitle,
        immersive, opts.bool("edgeToEdge"))
}
