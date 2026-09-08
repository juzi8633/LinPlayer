package xyz.linplayer.app.ui.player

import android.graphics.Bitmap
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.delay
import xyz.linplayer.app.BuildConfig
import xyz.linplayer.app.core.Logs

/**
 * libass 找字体的目录。
 *
 * ☠ 这份 libmpv 里**一个 fontconfig 符号都没有**(实测 `Fc*` 为 0),
 * 所以字体只能走 libass 的目录提供者。不给的话它一个字体都找不到,
 * **文本字幕整段不显示**而且不报错 —— 和 mpv 那条路的 `sub-fonts-dir` 是同一件事。
 */
private const val FONTS_DIR = "/system/fonts"

private const val TAG_EXO = "lp-exo"

/**
 * 第二个播放内核:ExoPlayer(U1.6b)。
 *
 * ★ **它和 mpv 是并列关系,不是替代**【用户定 2026-09-06】。两者各有死角:
 *   libmpv 在部分机型上出「有声音没画面」(vo 绑不上 Surface),
 *   ExoPlayer 认不了一部分容器/字幕但走的是平台 MediaCodec、稳。给用户一个开关。
 *
 * ☠ **分叉点只有「谁去解码渲染」这一处。** 取哪条流、从第几秒开始、
 *   版本正则、跨服续播、start/stop 上报 —— 全部照旧走核心层的 `player.play`
 *   (带 `engine=exo`,它算完一切、只是不 loadfile,把地址回给这里)。
 *   在 UI 里自己拼地址是**明令禁止**的:反代只在 `/emby/` 下处理 Range,
 *   拼错的表现是「跳到没缓冲的位置就卡死」,而且查不出来。
 */
@OptIn(UnstableApi::class)
@Composable
fun rememberExoPlayer(enabled: Boolean, preferredSubLang: String?): ExoPlayer? {
    val ctx = LocalContext.current
    if (!enabled) return null
    val player = remember {
        /* ★ UA 必须是 `LinPlayer/<版本>`(UA 三分口径)。不设的话 ExoPlayer 发的是
           `media3/x.y.z` —— 服务端的设备列表、会话归属、按 UA 做的分流全会对不上,
           而**播放本身照常能播**,所以这类错不会自己冒出来。 */
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("LinPlayer/" + BuildConfig.VERSION_NAME)
            .setAllowCrossProtocolRedirects(true)   // Emby 直传常常 302 到 CDN
        /* ☠☠ **特效字幕的唯一入口就是这一行。**
           media3 1.11 的字幕解析发生在解封装阶段(`TextRenderer` 已经没有
           `SubtitleParser.Factory` 构造参数了),所以想拿到 ASS 的原始事件,
           只能在 MediaSource 这一层换掉解析器。换晚了 / 换错层的表现是
           「libass 初始化成功,但一条事件都没进去」—— 而那看着就像 libass 没接上。 */
        val sources = DefaultMediaSourceFactory(http)
            .setSubtitleParserFactory(LibassParserFactory(FONTS_DIR))
        ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(sources)
            .build()
    }
    /* ☠☠ **不设这两项 = 一条字幕都不会被选中。**
       DefaultTrackSelector 默认只在「语言命中偏好」或「系统开了无障碍字幕」时才启用
       文本轨,而 `preferredTextLanguage` 是空的 —— 于是内封字幕明明在,
       `onCues` 一次都不回调,界面上就是「完全没有字幕」。用户报的正是这一条。
       语言偏好走播放偏好里的 `sub_lang`;它是空的时候
       `selectUndeterminedTextLanguage` 兜住「没标语言」的那些轨,
       两条都命中不了的由 [pickSubtitleTrack] 在轨道表到手之后再补一次。 */
    DisposableEffect(player, preferredSubLang) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setPreferredTextLanguage(preferredSubLang?.takeIf { it.isNotBlank() })
            .setSelectUndeterminedTextLanguage(true)
            .build()
        onDispose { }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    return player
}

/**
 * 兜底选一条字幕轨。
 *
 * 语言偏好没命中时(片源标的是 `chi` 而偏好写的是 `zh`,或者干脆一条都没标),
 * DefaultTrackSelector 会**一条都不选**。这里在轨道表到手之后补一次:
 * 有文本轨、又一条都没选中 → 选第一条。
 *
 * ★ 用户显式关了字幕(`sub_lang == ""`)时**不补** —— 那是他自己关的。
 */
@OptIn(UnstableApi::class)
private fun pickSubtitleTrack(player: ExoPlayer, tracks: Tracks, subOff: Boolean) {
    if (subOff) return
    val texts = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
    if (texts.isEmpty() || texts.any { it.isSelected }) return
    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        .setOverrideForType(TrackSelectionOverride(texts.first().mediaTrackGroup, 0))
        .build()
}

/** 把一条地址交给 ExoPlayer,并从 [startSecs] 起播。 */
fun ExoPlayer.load(url: String, startSecs: Double) {
    setMediaItem(MediaItem.fromUri(url))
    prepare()
    if (startSecs > 0) seekTo((startSecs * 1000).toLong())
    playWhenReady = true
}

/**
 * 画面比例档位【用户定 2026-09-07】。
 *
 * ★ 档位是**枚举不是数值**:给一个自由输入的比例框,用户只会输出一堆变形的画面。
 */
enum class VideoFit(val label: String, val hint: String, val mpvRatio: String) {
    Source("原始", "按片源比例,完整显示", "-1"),
    Cover("自适应", "铺满屏幕,超出的裁掉", "cover"),
    R4x3("4:3", "强制 4:3", "1.3333"),
    R16x9("16:9", "强制 16:9", "1.7778");

    companion object {
        fun of(id: String?): VideoFit = entries.firstOrNull { it.name == id } ?: Source
    }
}

/**
 * 画面在容器里占的那块矩形(像素,居中放)。
 *
 * ☠ **这一步必须是能在 JVM 上跑的纯函数。** 上一版把它交给
 * `Modifier.aspectRatio`,而那条链错没错**只有真机上肉眼看得出来** ——
 * 用户为「画面被拉伸」报了两轮。算式摆在这里,`ExoEngineTest` 直接钉。
 *
 * ★ contain(留黑边)和 cover(裁掉多余)只差一个比较方向,不写成两份。
 */
internal fun videoRect(boxW: Int, boxH: Int, videoAr: Float, fit: VideoFit): IntSize {
    if (boxW <= 0 || boxH <= 0) return IntSize.Zero
    val ar = when (fit) {
        VideoFit.R4x3 -> 4f / 3f
        VideoFit.R16x9 -> 16f / 9f
        else -> videoAr
    }
    // 比例还不知道(首帧之前)就先铺满:这期间画面本来就还没有
    if (ar <= 0f || !ar.isFinite()) return IntSize(boxW, boxH)
    val boxAr = boxW.toFloat() / boxH
    val heightBound = if (fit == VideoFit.Cover) boxAr < ar else boxAr > ar
    return if (heightBound) IntSize(Math.round(boxH * ar), boxH)
    else IntSize(boxW, Math.round(boxW / ar))
}

/**
 * ExoPlayer 的视频层。
 *
 * ★ 和 mpv 那条一样用 `SurfaceView` 而不是 `TextureView`:独立合成层,
 *   Compose 的 OSD 天然画在它上面,零 overdraw。
 * ★ **解绑写在 onDispose 里**:不解的话换内核 / 退出播放页之后,
 *   ExoPlayer 还攥着一个已经没了的 Surface。
 *
 * ☠☠ **画面比例必须自己算。** 裸 `SurfaceView` 把画面拉满整个 View,
 *   不管片源是什么比例。尺寸由 [videoRect] 一处算出,视频层和两个字幕层
 *   **共用同一个 `box`** —— 各写一遍的两处早晚会漂,而漂了的表现是
 *   「字幕比画面宽一截」,不报错。
 * ★ 必须乘 `pixelWidthHeightRatio`:非方形像素的片源(DVD 源、部分 1080i)
 *   光看 width/height 会算出一个瘦长的画面,而这在编译期看不出来。
 * ★ Cover 档的矩形**比容器大**,所以用 `requiredSize` 而不是 `size` ——
 *   后者会被父约束夹回去,那一档就退化成和「原始」一模一样。
 */
@Composable
fun ExoSurface(
    player: ExoPlayer,
    subOff: Boolean,
    fit: VideoFit,
    /** 起播前就知道的片源比例(Emby 的 Video 流宽高)。0 = 不知道。见 [ratioOf]。 */
    hintAr: Float = 0f,
    m: Modifier = Modifier,
) {
    var ratio by remember(player) { mutableFloatStateOf(0f) }
    var videoW by remember(player) { mutableIntStateOf(0) }
    var videoH by remember(player) { mutableIntStateOf(0) }

    /* 取一次当前的画面尺寸。`ratio` 是 0 就等于「不知道比例」,而不知道比例时
       [videoRect] 只能铺满 —— 那正是用户看到的「画面被拉伸」。

       ☠☠ **拿不到就别写**。media3 在换轨、渲染器重建、媒体项切换时会发一次
       `VideoSize.UNKNOWN`(0×0),而上一版是无条件赋值 —— 于是刚从轨道表里
       算出来的正确比例被一个 0 抹掉,下面那条轮询又已经退出了,再没有人纠正它。
       表现就是「怎么调都还是拉伸的」,而且只在某些片子上出现(用户报了三轮)。 */
    val take = { v: VideoSize ->
        val par = if (v.pixelWidthHeightRatio > 0f) v.pixelWidthHeightRatio else 1f
        if (v.width > 0 && v.height > 0) {
            val r = v.width * par / v.height
            if (r != ratio) Logs.d(TAG_EXO, "片源尺寸 ${v.width}×${v.height} par=$par 比例=$r")
            ratio = r
            videoW = v.width; videoH = v.height
        }
    }

    DisposableEffect(player, subOff) {
        // 监听器只管「以后」,而这次注册可能已经晚了(subOff 一变就重挂一次)
        take(player.videoSize)
        val l = object : Player.Listener {
            override fun onVideoSizeChanged(size: VideoSize) { take(size) }

            override fun onTracksChanged(tracks: Tracks) {
                /* ☠ 轨道表比 `videoSize` **早到**:解封装完就有了,而 videoSize 要等首帧
                   (硬解冷启动实测好几秒)。这期间 ratio 是 0,而 0 的含义是「不知道比例」,
                   [videoRect] 只能铺满 —— 那正是用户三轮都在报的「画面被拉伸」。 */
                if (ratio <= 0f) videoOf(tracks)?.let { (w, h, r) ->
                    ratio = r; videoW = w; videoH = h
                }
                pickSubtitleTrack(player, tracks, subOff)
                syncLibassTrack(tracks, subOff)
            }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }

    /* ☠ **不能只靠 onVideoSizeChanged。** 它一部片只发一两次,而这一层的监听器
       会随 `subOff` 重挂、随进程被杀重建 —— 错过那一次就永远是 0。
       ★ 这条轮询**不退出**:比例被清掉(换片、换轨)之后还得有人把它捡回来,
         「拿到就停」的写法只兜得住第一次。没在等的时候一秒问一次,代价可以忽略。 */
    LaunchedEffect(player) {
        while (true) {
            delay(if (ratio <= 0f) 300 else 1000)
            take(player.videoSize)
            if (ratio <= 0f) videoOf(player.currentTracks)?.let { (w, h, r) ->
                ratio = r; videoW = w; videoH = h
            }
        }
    }

    /* 最后一道:详情页那边早就从 `emby.itemMedia` 拿到过宽高(起播方向就是照它定的),
       所以片源比例其实**在按下播放之前**就已知。播放器自己还没说话时先用它。 */
    val ar = if (ratio > 0f) ratio else hintAr
    BoxWithConstraints(m.clipToBounds(), contentAlignment = Alignment.Center) {
        val r = videoRect(constraints.maxWidth, constraints.maxHeight, ar, fit)
        LaunchedEffect(r, fit) {
            Logs.d(TAG_EXO, "画面 ${fit.name} 容器 ${constraints.maxWidth}×${constraints.maxHeight}" +
                " → 画到 ${r.width}×${r.height}(比例 $ar 播放器给的 $ratio 元数据给的 $hintAr)")
        }
        val box = with(LocalDensity.current) {
            Modifier.requiredSize(r.width.toDp(), r.height.toDp())
        }
        AndroidView(
            modifier = box,
            // Shot.bind:截屏从这块面上 PixelCopy 读回当前帧(View.draw 只能拿到一个透明洞)
            factory = { ctx ->
                SurfaceView(ctx).also { player.setVideoSurfaceView(it); Shot.bind(it) }
            },
        )
        LibassLayer(player, box, videoW, videoH)
        ExoSubtitles(player, box)
    }
    DisposableEffect(player) { onDispose { Shot.bind(null); player.clearVideoSurface() } }
}

/**
 * 从轨道表里读画面尺寸和比例:`(宽, 高, 比例)`,读不出来返回 null。
 *
 * `Format.width/height` 在解封装阶段就填好了,比 `videoSize`(要等首帧)早得多。
 * 非方形像素要乘 `pixelWidthHeightRatio` —— 不乘的话 DVD 源和部分 1080i 会算出一个瘦长的画面。
 * 读不出来返回 null,**不猜一个 16:9**。
 *
 * ★ 宽高也要一起给出去:libass 的 storage size 靠它(见 [LibassLayer])。
 *   只回比例的话特效字幕按 PlayResX 定位时会整体错位,而那看着像「字幕没对准」。
 */
@OptIn(UnstableApi::class)
private fun videoOf(tracks: Tracks): Triple<Int, Int, Float>? {
    val f = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
        .flatMap { g -> (0 until g.length).map { g.getTrackFormat(it) } }
        .firstOrNull { it.width > 0 && it.height > 0 } ?: return null
    val par = if (f.pixelWidthHeightRatio > 0f) f.pixelWidthHeightRatio else 1f
    return Triple(f.width, f.height, f.width * par / f.height)
}

/**
 * 选中的那条文本轨是 ASS 就交给 libass,否则关掉它。
 *
 * ★ 判据是「**选中的**那一条」,不是「有没有 ASS 轨」:片子里常常几条轨并存,
 *   用户选了简体 SRT 却弹出繁体 ASS 特效,那比没有特效更糟。
 * ★ 内封没有 ASS 时回落到**外挂** ASS(压制组单独发的那种,特效字幕的大头)。
 */
@OptIn(UnstableApi::class)
private fun syncLibassTrack(tracks: Tracks, subOff: Boolean) {
    if (!Libass.available || subOff) {
        Logs.d(TAG_EXO, "libass 不走这条路: available=${Libass.available} subOff=$subOff")
        Libass.deactivate(); return
    }
    val sel = tracks.groups
        .filter { it.type == C.TRACK_TYPE_TEXT && it.isSelected }
        .flatMap { g -> (0 until g.length).filter { g.isTrackSelected(it) }.map { g.getTrackFormat(it) } }
        .firstOrNull()
    Logs.d(TAG_EXO, "选中字幕轨 id=${sel?.id} mime=${sel?.sampleMimeType}" +
        " codecs=${sel?.codecs} 判为 ASS=${Libass.isAss(sel)}")
    if (sel != null && Libass.isAss(sel)) {
        val ok = Libass.activateTrack(sel.id ?: "ass", FONTS_DIR)
        Logs.d(TAG_EXO, "交给 libass: $ok")
        if (ok) return
    }
    // 选中的不是 ASS:内封这条路到此为止。外挂那条由 PlayerPage 在起播时装
    if (sel != null && !Libass.isAss(sel)) Libass.deactivate()
}

/**
 * libass 的画布。
 *
 * ☠ **只在 libass 说「这一帧变了」时才重画。** 对白字幕一秒才变几次,
 *   每帧无脑清屏 + 重画是纯烧电;而卡拉OK 那种逐帧变的,它自然每帧都返回 1。
 * ★ 位图交给 native 直接 `AndroidBitmap_lockPixels` 写 —— 不走 JNI 拷贝。
 *   `unlockPixels` 会 bump 位图的 generation id,Skia 的纹理缓存据此失效;
 *   这里另外还读一次 `frame` 状态,双保险(少一层就是「字幕停在第一句不动」)。
 */
@Composable
private fun LibassLayer(player: ExoPlayer, m: Modifier, videoW: Int, videoH: Int) {
    if (!Libass.available) return
    var size by remember { mutableStateOf(IntSize.Zero) }
    var frame by remember { mutableIntStateOf(0) }
    val bmp = remember(size, videoW, videoH) {
        if (size.width <= 0 || size.height <= 0) null
        else Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888).also {
            Logs.d(TAG_EXO, "libass 画布 ${size.width}×${size.height} 片源 ${videoW}×${videoH}")
            Libass.setSize(size.width, size.height, videoW, videoH)
        }
    }

    LaunchedEffect(bmp, player) {
        val b = bmp ?: return@LaunchedEffect
        var force = true      // 位图刚建出来,第一帧必须画
        var last = -1L
        while (true) {
            val pos = player.currentPosition
            // seek / 换片之后要强制重画:libass 的 detect_change 只比「上一次渲染」
            if (kotlin.math.abs(pos - last) > 1000) force = true
            last = pos
            if (Libass.render(b, pos, force) > 0) frame++
            force = false
            delay(33)         // 30Hz。卡拉OK 够用,对白字幕靠 detect_change 直接跳过
        }
    }

    Canvas(m.onSizeChanged { size = it }) {
        val b = bmp ?: return@Canvas
        @Suppress("UNUSED_EXPRESSION") frame    // 制造重绘依赖
        drawIntoCanvas { it.nativeCanvas.drawBitmap(b, 0f, 0f, null) }
    }
}

/**
 * ExoPlayer 的字幕层(**libass 管不到的那些**)。
 *
 * 分工:ASS/SSA 走 libass([LibassLayer]),这里画剩下的 ——
 * SRT / VTT / TTML 这类文本,以及 **PGS / VobSub / DVBSub 这类图形字幕**。
 * media3 1.11 自带 `PgsParser`,蓝光原盘扒出来的 `.sup` 轨直接就能解
 * (`MatroskaExtractor` 认 `S_HDMV/PGS`,反编译核对过常量表)。
 *
 * ☠☠ **图形字幕自带坐标,不能拉满宽度。**
 *   `PgsParser` 给的 `Cue` 里 `position` / `line` / `size` / `bitmapHeight`
 *   都是**相对视频画面的比例**。上一版无视它们、一律 `fillMaxWidth` ——
 *   表现是字幕被横向拉成一条、还盖在画面正中间。
 * ☠ 因此这一层的坐标系**必须和画面严格重合**:它和 [LibassLayer]、SurfaceView
 *   共用同一个 `fit`。铺满全屏的话上下黑边会被算进比例,字幕整体偏。
 */
@OptIn(UnstableApi::class)
@Composable
fun ExoSubtitles(player: ExoPlayer, m: Modifier = Modifier) {
    var cues by remember(player) { mutableStateOf<List<Cue>>(emptyList()) }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onCues(cueGroup: CueGroup) { cues = cueGroup.cues }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }
    if (cues.isEmpty()) return

    /* 图形字幕平面的真实尺寸,从 cue 自己反推(见 [cueRect])。
       平面和画面不等比正是「字幕被拉伸」的判据 —— 上一轮日志里没有这个数,
       只能靠算式反推是哪一头错了,白花一轮。 */
    val plane = cues.firstOrNull { it.bitmap != null }?.let { c ->
        val b = c.bitmap ?: return@let null
        if (c.size == Cue.DIMEN_UNSET || c.bitmapHeight == Cue.DIMEN_UNSET) null
        else "${Math.round(b.width / c.size)}×${Math.round(b.height / c.bitmapHeight)}"
    }
    LaunchedEffect(plane) { plane?.let { Logs.d(TAG_EXO, "图形字幕平面 $it") } }

    BoxWithConstraints(m) {
        val texts = cues.filter { it.bitmap == null }
        cues.forEach { cue -> cue.bitmap?.let { BitmapCue(cue, it, maxWidth, maxHeight) } }
        if (texts.isNotEmpty()) Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            texts.forEach { cue ->
                cue.text?.toString()?.takeIf { it.isNotBlank() }?.let { TextCue(it) }
            }
        }
    }
}

/**
 * 一句文本字幕。
 *
 * ☠ **不要黑底。** 上一版给每句话垫一块 55% 的黑板 —— 白字确实看得清了,
 *   代价是画面下方常年被切掉一条,亮场景里那块板比字还显眼(用户原话:
 *   「播放 SRT 字幕会带黑色遮罩背景」)。
 *   正解是**描边**:黑边先画一遍、白字再压上去。字外面一个像素都不占,
 *   压在雪地上和压在夜景上一样清楚 —— 这也是 libass 那条路的默认做法。
 */
@Composable
private fun TextCue(t: String) {
    val base = TextStyle(
        fontSize = 18.sp, lineHeight = 24.sp,
        fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
    )
    Box(contentAlignment = Alignment.Center) {
        Text(t, style = base.copy(
            color = Color.Black,
            // Round 的接头:方角在笔画拐弯处会支出一个小尖角
            drawStyle = Stroke(width = 6f, join = StrokeJoin.Round, cap = StrokeCap.Round),
        ))
        Text(t, style = base.copy(color = Color.White))
    }
}

/**
 * 一张图形字幕在画面框里的落点(像素)。**未给的值传 `NaN`。**
 *
 * ☠☠ **PGS 的比例是相对「字幕平面」的,不是相对画面的。** `PgsParser` 交出来的
 *   `position/line/size/bitmapHeight` 都是拿平面宽高除出来的,而平面是**原盘那一帧**
 *   (蓝光一律 1920×1080)。2.35:1 的片子重编码成 3840×1632 之后画面不带黑边了、
 *   平面还带着 —— 把画面框当平面用,竖向就被压掉 1046/1383,而横向没压,
 *   看上去正是「字幕向两边拉伸」(用户报了两轮)。
 * ★ 平面比例从 cue 自己反推:平面宽 = 位图宽 / `size`,平面高 = 位图高 / `bitmapHeight`。
 *   平面和画面同比例时(绝大多数片子)整段算式退化成原样。
 * ★ 平面按**宽度**贴齐画面:PGS 平面横向总是铺满原帧,多出来的是上下那两条黑边。
 */
internal fun cueRect(
    boxW: Float, boxH: Float, bmpW: Int, bmpH: Int,
    size: Float, bmpFrac: Float, position: Float, line: Float, pad: Float = 0f,
): Rect {
    val on = { v: Float -> !v.isNaN() }
    val planeH =
        if (on(size) && on(bmpFrac) && size > 0f && bmpFrac > 0f && bmpW > 0)
            boxW * bmpH * size / (bmpFrac * bmpW)
        else boxH
    val w = (if (on(size)) boxW * size else boxW).coerceAtMost(boxW)
    val h = (if (on(bmpFrac)) planeH * bmpFrac
    else w * bmpH / bmpW.coerceAtLeast(1)).coerceAtMost(boxH)
    val x = if (on(position)) boxW * position else (boxW - w) / 2f
    /* ☠ **落点要夹回画面里。** 平面比画面高的时候底部那条字会算到画面外面 ——
       原样贴的表现是双语字幕只剩上面一行(用户原话:「直接看不到下面的英语了」)。 */
    val y = if (on(line)) (boxH - planeH) / 2f + planeH * line else boxH - h - pad
    val cx = x.coerceIn(0f, (boxW - w).coerceAtLeast(0f))
    val cy = y.coerceIn(0f, (boxH - h).coerceAtLeast(0f))
    return Rect(cx, cy, cx + w, cy + h)
}

/**
 * 一张图形字幕。
 *
 * `DIMEN_UNSET` 是 `Float.MIN_VALUE`,**不是 0 也不是 -1** —— 拿 `<= 0` 判会把它
 * 当成合法的 0,字幕就贴到左上角去了。这里统一换成 `NaN` 交给 [cueRect]。
 */
@OptIn(UnstableApi::class)
@Composable
private fun BitmapCue(cue: Cue, bmp: android.graphics.Bitmap, boxW: Dp, boxH: Dp) {
    val on = { v: Float -> if (v == Cue.DIMEN_UNSET) Float.NaN else v }
    with(LocalDensity.current) {
        val r = cueRect(
            boxW.toPx(), boxH.toPx(), bmp.width, bmp.height,
            on(cue.size), on(cue.bitmapHeight), on(cue.position),
            if (cue.lineType == Cue.LINE_TYPE_FRACTION) on(cue.line) else Float.NaN,
            24.dp.toPx(),
        )
        Image(
            bmp.asImageBitmap(), null,
            Modifier.offset(r.left.toDp(), r.top.toDp()).size(r.width.toDp(), r.height.toDp()),
            contentScale = ContentScale.FillBounds,
        )
    }
}
