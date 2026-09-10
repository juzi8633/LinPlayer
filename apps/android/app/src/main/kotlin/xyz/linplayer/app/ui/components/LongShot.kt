package xyz.linplayer.app.ui.components

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import xyz.linplayer.app.core.Logs
import xyz.linplayer.app.ui.player.Shot

private const val TAG = "lp-longshot"

/** 一片切片:从当前这一帧的第 [srcTop] 行起取 [height] 行,贴到长图的第 [dstTop] 行。 */
class Slice(val srcTop: Int, val height: Int, val dstTop: Int)

/**
 * 长截屏的拼版。
 *
 * 第一帧整张收下;之后每滚动 d 像素,**只取这一帧最底下的 d 行** ——
 * 取整帧再叠的话重叠区会覆盖掉上一帧,一屏内容出现两次。
 *
 * ☠ 判据是**滚动真正消费掉的距离**,不是我们请求的距离。到底了请求 800 只走 120,
 * 按 800 贴会在长图里留一条 680 像素高的空白,而那看起来像「截图坏了」。
 */
fun longShotSlices(viewH: Int, deltas: List<Int>): List<Slice> {
    if (viewH <= 0) return emptyList()
    val out = mutableListOf(Slice(0, viewH, 0))
    var dst = viewH
    for (d in deltas) {
        if (d <= 0 || d > viewH) continue
        out.add(Slice(viewH - d, d, dst))
        dst += d
    }
    return out
}

/** 长图总高 = 一屏 + 每次真滚掉的距离。 */
fun longShotHeight(viewH: Int, deltas: List<Int>): Int =
    longShotSlices(viewH, deltas).sumOf { it.height }

/**
 * 长截屏【用户 2026-09-10:「方便截图演示」】。
 *
 * ★ **不指望系统那个「捕获更多」。** 它只在 Android 12+ 且 ROM 带那颗按钮时才有,
 *   出不出、截到哪一段全由系统 UI 说了算 —— 演示要的是「按一下拿到整页」。
 * ★ 自己滚 + 自己拼:每滚一屏画一帧,拼成一张长图,走 [Shot] 那条现成的写相册的路。
 */
object LongShot {

    /** 最多接多少屏。一屏 1080×2400 就是 10MB,接太多直接 OOM,而 OOM 不会告诉用户为什么。 */
    private const val MAX_SCREENS = 12

    /** 当前页那个能滚的东西。各页用 [LongShotTarget] 登记。
     *  用 MutableState 不用普通字段:按钮要**跟着换页出现和消失**,普通字段变了不重组。 */
    private val target = mutableStateOf<ScrollableState?>(null)

    /** 截取中。**底栏据此让开** —— 不让开的话每一片切片里都印着一条底栏。 */
    val capturing = mutableStateOf(false)

    /** 有没有可截的页。没有就别把按钮画出来 —— 摆着不生效的控件比没有更糟。 */
    fun ready(): Boolean = target.value != null

    fun bind(s: ScrollableState?) { target.value = s }

    /**
     * 截整页,返回给用户看的落点。失败返回 null。
     *
     * 必须在**主线程 + 有帧时钟**的协程里调(页面的 rememberCoroutineScope 就是)。
     */
    suspend fun take(activity: Activity): String? {
        val st = target.value ?: return null
        val root: View = activity.window?.decorView?.findViewById(android.R.id.content)
            ?: return null
        val w = root.width
        val h = root.height
        if (w <= 0 || h <= 0) return null

        capturing.value = true
        try {
            // 先回到顶:从半路开始截出来的长图缺开头,而用户不会注意到自己滚过
            frame(); st.scrollBy(-Int.MAX_VALUE.toFloat()); frame()

            val frames = mutableListOf<Bitmap>()
            val deltas = mutableListOf<Int>()
            frames.add(grab(root, w, h))
            repeat(MAX_SCREENS - 1) {
                val moved = st.scrollBy(h.toFloat()).toInt()
                if (moved <= 0) return@repeat
                frame()
                deltas.add(moved)
                frames.add(grab(root, w, h))
            }
            return runCatching { stitch(activity, frames, w, h, deltas) }
                .onFailure { Logs.e(TAG, "拼长图失败: " + it) }
                .getOrNull()
        } finally {
            capturing.value = false
        }
    }

    /** 等两帧:一帧让滚动落位,一帧让新内容画出来。少等一帧截到的是上一屏。 */
    private suspend fun frame() {
        withFrameNanos { }
        withFrameNanos { }
    }

    private fun grab(v: View, w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(b))
        return b
    }

    private fun stitch(
        activity: Activity, frames: List<Bitmap>, w: Int, h: Int, deltas: List<Int>,
    ): String {
        val slices = longShotSlices(h, deltas)
        val out = Bitmap.createBitmap(w, longShotHeight(h, deltas), Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        slices.forEachIndexed { i, s ->
            val src = frames.getOrNull(i) ?: return@forEachIndexed
            c.drawBitmap(src,
                Rect(0, s.srcTop, w, s.srcTop + s.height),
                Rect(0, s.dstTop, w, s.dstTop + s.height), null)
        }
        // 画完就放:一屏 10MB,十二屏攒着不放的话拼图那一刻正好是内存峰值
        frames.forEach { it.recycle() }
        val where = Shot.save(activity, out)
        out.recycle()
        return where
    }
}

/**
 * 把这一页的滚动容器登记给长截屏。**离开这一页就摘掉** ——
 * 不摘的话按钮会在一个已经不在屏上的列表上滚,截出来是一张不知道哪儿来的图。
 */
@Composable
fun LongShotTarget(state: ScrollableState) {
    DisposableEffect(state) {
        LongShot.bind(state)
        onDispose { LongShot.bind(null) }
    }
}

/** LocalContext 在对话框 / 主题包装下会是 ContextWrapper,直接强转拿不到 Activity。 */
private fun activityOf(c: android.content.Context?): Activity? = when (c) {
    is Activity -> c
    is android.content.ContextWrapper -> activityOf(c.baseContext)
    else -> null
}

/**
 * 那颗按钮。设置里开了、而且这一页真有东西可滚,才画。
 *
 * ★ 截取过程中自己也要藏起来 —— 不藏的话它会印在长图的每一片上。
 */
@Composable
fun LongShotButton(m: Modifier = Modifier) {
    val app = xyz.linplayer.app.data.LocalApp.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var busy by androidx.compose.runtime.remember { mutableStateOf(false) }
    if (!xyz.linplayer.app.data.UiPrefs.longShot.value) return
    if (!LongShot.ready() || LongShot.capturing.value) return
    val act = activityOf(ctx) ?: return
    Box(
        m.padding(
            end = xyz.linplayer.app.ui.theme.Sp.x16,
            bottom = LocalTabClearance.current + xyz.linplayer.app.ui.theme.Sp.x20,
        )
    ) {
        LpIconButton(xyz.linplayer.app.ui.theme.LpIcons.camera, "截长屏", size = 20) {
            if (busy) return@LpIconButton
            busy = true
            scope.launch {
                val where = LongShot.take(act)
                busy = false
                if (where == null) app.toast("这一页截不了长图")
                else app.toast("长图已存到 $where", xyz.linplayer.app.data.ToastKind.Ok)
            }
        }
    }
}
