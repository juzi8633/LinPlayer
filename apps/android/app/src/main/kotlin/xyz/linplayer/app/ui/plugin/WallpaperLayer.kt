package xyz.linplayer.app.ui.plugin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil3.compose.rememberAsyncImagePainter
import kotlinx.serialization.json.JsonObject
import xyz.linplayer.app.core.Logs
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.UiPrefs
import xyz.linplayer.app.data.obj
import xyz.linplayer.app.data.str

private const val TAG = "壁纸"

/** 模糊 1.0 对应的半径。再大只会把整屏糊成一块纯色,不是「更模糊」。 */
private val MaxBlur = 36.dp

/**
 * 模糊这一档在 Android 12 以下**做不到**:`Modifier.blur` 底下是 RenderEffect,
 * 低版本上它静默什么都不做 —— 所以设置页那一行也不画,不给一个调了没反应的滑块。
 */
val canBlurWallpaper: Boolean = android.os.Build.VERSION.SDK_INT >= 31

/**
 * 壁纸层(SPEC 11.5)。画在**所有页面内容的最底下**,
 * 不吃触摸也不抢焦点 —— 所以整层没有任何可交互的东西,也不加 focusable。
 */
@Composable
fun WallpaperLayer() {
    val app = LocalApp.current
    val core = app.core

    // `load: startup` 之外的壁纸插件从不主动 set,不问它一次就永远画不出来。
    // 问不到就退回主题自带的那张(D441:主题的是默认,壁纸插件覆盖它)
    LaunchedEffect(Unit) {
        val w = runCatching { app.call("plugin.initialWallpaper") }.getOrNull().obj()
        Wallpaper.set(w ?: xyz.linplayer.app.ui.theme.PluginTheme.wallpaper)
    }
    LaunchedEffect(Unit) {
        core.events.collect { if (it.name == "plugin.wallpaper") Wallpaper.set(it.data.obj()) }
    }

    val w = Wallpaper.content ?: return
    val blur = if (canBlurWallpaper) UiPrefs.wallBlur.value else 0f
    val dim = UiPrefs.wallDim.value

    /* 压暗做成**调淡**而不是另盖一层黑幕:黑幕是壁纸层里的第二个兄弟,
       而模糊只作用在第一个 —— 两者叠在一起时边缘会露出一圈没糊到的黑边。 */
    Box(
        Modifier.fillMaxSize()
            .let { if (blur > 0f) it.blur(MaxBlur * blur) else it }
            .alpha(1f - dim)
    ) {
        when (val kind = w.str("kind")) {
            "image" -> ImageWall(w)
            "canvas" -> PluginSurface(
                plugin = w.str("plugin").orEmpty(), target = w.str("block").orEmpty(),
                kind = "block", modifier = Modifier.fillMaxSize(), claimInitialFocus = false,
            )
            /* 播放页上**整层卸掉**,不只是暂停:两个非 overlay 的 SurfaceView 谁在上面
               是未定的,留着的表现是正片「有声音没画面」—— 而那一眼看着像播放坏了。 */
            "video" -> if (WallpaperGate.inPlayer) Unit else VideoWall(w)
            // 着色器壁纸这一端还没做:安卓上要另起一条 GL 通道,而那件事还没有地基
            "shader" -> Logs.w(TAG, "着色器壁纸安卓端还没做,这一张不画(${w.str("file")})")
            else -> Logs.w(TAG, "认不出来的壁纸类型:$kind")
        }
    }
}

@Composable
private fun ImageWall(w: JsonObject) {
    val app = LocalApp.current
    val src = imageRef(w)
    if (src.isEmpty()) return
    // 外网图走官方取图链路(代理 / 缓存 / 脱敏),包内的走本地数据通道
    val url = if (src.contains("://")) app.proxiedImage(src, 1080) ?: src
    else assetUrl(app.core, w.str("plugin").orEmpty(), src)
    Image(
        rememberAsyncImagePainter(url), null,
        Modifier.fillMaxSize().background(Color(0x00000000)),
        contentScale = ContentScale.Crop,
    )
}

/**
 * 三个暂停条件里的两个「环境」条件。进播放页那条由播放页自己报(见 [WallpaperGate])。
 *
 * 省电时**不恢复**:监听广播只能知道它变了,当前值还得现读 —— 冷启动时
 * 一条广播都还没来过,只听广播的话省电模式下第一次进来照样在放。
 */
@Composable
private fun WatchEnvironment() {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_STOP -> WallpaperGate.set(WallpaperGate.BACKGROUND, true)
                Lifecycle.Event.ON_START -> WallpaperGate.set(WallpaperGate.BACKGROUND, false)
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }
    DisposableEffect(ctx) {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val read = { WallpaperGate.set(WallpaperGate.POWER_SAVE, pm.isPowerSaveMode) }
        read()
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) = read()
        }
        ctx.registerReceiver(r, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        onDispose { runCatching { ctx.unregisterReceiver(r) } }
    }
}

/**
 * 视频壁纸(D443 D564)。**独立的 ExoPlayer 实例**,不碰播放页那一路 ——
 * mpv 与 ExoPlayer 本来就是并存的两套内核,壁纸占的是第二套里的另一个实例。
 *
 * ☠ 放不放由 [WallpaperGate] 说了算,三个条件是**与**;这里只负责照它执行。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun VideoWall(w: JsonObject) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val src = w.str("video").orEmpty().ifEmpty { return }
    val url = remember(src) { assetUrl(app.core, w.str("plugin").orEmpty(), src) }

    WatchEnvironment()

    val player = remember(url) {
        androidx.media3.exoplayer.ExoPlayer.Builder(ctx).build().apply {
            // 低分辨率(D443):多码率源挑小的。壁纸是一直在跑的东西,解码要让路给正片
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setMaxVideoSize(720, 405).build()
            volume = 0f
            repeatMode = androidx.media3.common.Player.REPEAT_MODE_ALL
            setMediaItem(androidx.media3.common.MediaItem.fromUri(url))
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }

    LaunchedEffect(player, WallpaperGate.mayPlay) {
        if (WallpaperGate.mayPlay) player.play()
        else {
            player.pause()
            Logs.w(TAG, "视频壁纸暂停:${WallpaperGate.pausedBy}")
        }
    }

    androidx.compose.ui.viewinterop.AndroidView(
        factory = {
            android.view.SurfaceView(it).apply {
                /* ☠ **必须垫在内容下面**。setZOrderMediaOverlay(true) 会把这一层
                   抬到窗口之上,表现是整个界面被壁纸盖住、一个按钮都点不到。 */
                setZOrderOnTop(false)
                setZOrderMediaOverlay(false)
                player.setVideoSurfaceView(this)
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}
