package xyz.linplayer.app.ui.player

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.PixelCopy
import android.view.SurfaceView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import xyz.linplayer.app.core.Logs
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.UiPrefs
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

private const val TAG = "lp-shot"

/**
 * 播放页截屏【用户定 2026-09-07】。
 *
 * ☠ **不走核心层的 `player.screenshot`。** 那条命令是 mpv 的 `screenshot-to-file`:
 *   ExoPlayer 内核下 mpv 手里根本没有这一片(截出来是上一次 mpv 播的东西,或者直接失败);
 *   而且它写进的是应用私有目录,任何文件管理器都进不去 —— 点了、显示成功、拿不到图。
 * ☠ **`View.draw` 截不到画面。** `SurfaceView` 的内容不在 View 树里,
 *   它在自己的合成层上,View 树那一块是被 `PorterDuff.CLEAR` 抠出来的透明洞。
 *   唯一能读回它的是 `PixelCopy` —— 两个内核共用这一条路,因为两边都是 SurfaceView。
 */
object Shot {

    /** 当前在屏的那块视频面。两个内核各自在建 SurfaceView 时登记进来。 */
    @Volatile private var view: SurfaceView? = null

    fun bind(v: SurfaceView?) { view = v }

    /**
     * 截一张,叠上设置里选的东西,写进相册。返回给用户看的落点,失败返回 null。
     *
     * ★ **不弹窗**【用户定 2026-09-07:「截屏就截屏,出现弹窗干什么呢」】——
     *   调用方只发一条 toast。
     */
    suspend fun take(ctx: Context, app: AppState, itemId: String): String? {
        val v = view
        if (v == null || v.width <= 0 || v.height <= 0) {
            Logs.w(TAG, "没有可截的画面层(view=$v)")
            return null
        }
        val frame = grab(v) ?: run { Logs.w(TAG, "PixelCopy 没成功"); return null }
        val logo = if (UiPrefs.shotLogo.value) fetchLogo(app, itemId) else null
        return withContext(Dispatchers.IO) {
            runCatching {
                overlay(frame, logo)
                save(ctx, frame)
            }.onFailure { Logs.e(TAG, "存图失败: " + it) }.getOrNull()
        }
    }

    private suspend fun grab(v: SurfaceView): Bitmap? = suspendCancellableCoroutine { cont ->
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        runCatching {
            PixelCopy.request(v, b, { rc ->
                if (cont.isActive) cont.resume(if (rc == PixelCopy.SUCCESS) b else null)
            }, Handler(Looper.getMainLooper()))
        }.onFailure { if (cont.isActive) cont.resume(null) }
    }

    /**
     * 叠加层。
     *
     * ★ 字用**描边**不用底板:一块半透明黑板压在画面上比字本身还显眼
     *   —— 和播放页的 SRT 字幕是同一条口径。
     */
    private fun overlay(bmp: Bitmap, logo: Bitmap?) {
        val c = Canvas(bmp)
        val edge = minOf(bmp.width, bmp.height) * 0.045f
        if (logo != null) {
            val w = bmp.width * 0.22f
            val h = w * logo.height / logo.width.coerceAtLeast(1)
            val (x, y) = shotCorner(UiPrefs.shotLogoPos.value, bmp.width, bmp.height, w, h, edge)
            c.drawBitmap(logo, null, android.graphics.RectF(x, y, x + w, y + h), null)
        }
        if (UiPrefs.shotTime.value) {
            val text = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
            val size = minOf(bmp.width, bmp.height) * 0.038f
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; textSize = size
            }
            val line = Paint(fill).apply {
                color = Color.BLACK; style = Paint.Style.STROKE
                strokeWidth = size * 0.14f; strokeJoin = Paint.Join.ROUND
            }
            val w = fill.measureText(text)
            val h = -fill.fontMetrics.ascent
            val (x, y) = shotCorner(UiPrefs.shotTimePos.value, bmp.width, bmp.height, w, h, edge)
            // drawText 的 y 是基线,不是顶边 —— 按顶边给会把字画到框外面去
            c.drawText(text, x, y + h, line)
            c.drawText(text, x, y + h, fill)
        }
    }

    /**
     * 取条目艺术字(Emby 的 `Logo` 图)。
     *
     * ☠ 本地数据通道要 `X-LP-Token` 请求头 —— 不带就是 401,而 401 在这里长得和
     *   「这部片没有艺术字」一模一样。取不到就不叠,不报错。
     */
    private suspend fun fetchLogo(app: AppState, itemId: String): Bitmap? =
        withContext(Dispatchers.IO) {
            val u = app.imageUrl(itemId, "Logo", 240) ?: return@withContext null
            runCatching {
                val conn = URL(u).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 8000
                app.core.localToken.takeIf { it.isNotEmpty() }
                    ?.let { conn.setRequestProperty("X-LP-Token", it) }
                conn.inputStream.use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
        }

    /**
     * 写进相册。
     *
     * ☠ API 28 及以下往相册写要 `WRITE_EXTERNAL_STORAGE` —— 为一颗截图按钮去要
     *   全盘写权限不值(而且 Play 对播放器不放行)。那些机器落到应用自己的外部目录:
     *   `Android/data/<包名>/files/Pictures/shots`,和日志目录同一处,文件管理器进得去。
     */
    private fun save(ctx: Context, bmp: Bitmap): String {
        val name = "LinPlayer-" +
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".png"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cv = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/LinPlayer")
            }
            val uri = ctx.contentResolver
                .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
                ?: error("相册不收这张图")
            ctx.contentResolver.openOutputStream(uri)?.use {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
            } ?: error("相册的输出流打不开")
            Logs.d(TAG, "截屏落到相册 Pictures/LinPlayer/$name")
            return "相册 · Pictures/LinPlayer"
        }
        val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "shots")
        dir.mkdirs()
        val f = File(dir, name)
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Logs.d(TAG, "截屏落到 " + f.absolutePath)
        return f.absolutePath
    }
}

/**
 * 四角码 → 叠加块的左上角坐标。`tl/tr/bl/br`,见 [UiPrefs]。
 *
 * ☠ 抽成纯函数是为了能在 JVM 上钉:算错了**不报错**,只是水印跑到画面外面去,
 *   而那只有真机截一张出来才看得见 —— 右侧和底侧要减掉块自己的宽高,减漏一次就出界。
 */
internal fun shotCorner(
    pos: String, canvasW: Int, canvasH: Int, w: Float, h: Float, edge: Float,
): Pair<Float, Float> {
    val x = if (pos.endsWith("r")) canvasW - edge - w else edge
    val y = if (pos.startsWith("b")) canvasH - edge - h else edge
    return x to y
}
