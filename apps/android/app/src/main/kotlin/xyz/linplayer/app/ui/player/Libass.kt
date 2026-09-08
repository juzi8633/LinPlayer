package xyz.linplayer.app.ui.player

import android.graphics.Bitmap
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import xyz.linplayer.app.core.Logs
import xyz.linplayer.app.core.Native

/**
 * ExoPlayer 内核的**特效字幕**(U1.6b)。
 *
 * ## 为什么必须自己接 libass
 *
 * media3 的 `SsaParser` 把 ASS 折成 `Cue`:`\pos` `\move` `\fad`、卡拉OK、
 * 逐字变色、矢量绘图全部在那一步丢掉。压制组发的字幕十有八九靠这些 ——
 * 丢了之后画面上是「有字幕」,但不是那份字幕。
 *
 * ## 借的是哪份 libass
 *
 * **libmpv.so 自己导出的那份**(实测 191 个 `ass_*` 符号)。不另编第二份:
 * 那等于把包里已有的东西再编一遍,还多一份要跟着升级的依赖。
 * 桥在 `core/ffi/libass_android.go`。
 *
 * ## 事件从哪来,时间又从哪来
 *
 * media3 1.11 的字幕解析发生在**解封装阶段**(`TextRenderer` 已经没有
 * `SubtitleParser.Factory` 这个构造参数了),所以唯一的入口是
 * `DefaultMediaSourceFactory.setSubtitleParserFactory`。
 *
 * ☠☠ 但**解析器拿不到这条样本的播放时间**:`parse()` 收到的 `OutputOptions`
 * 是 `allCues()`,而 media3 重写过的 `Dialogue:` 行里开始时间**恒 0**
 * (`MatroskaExtractor.setSubtitleEndTime` 只回填结束那一格,起始那格一直是
 * `SSA_PREFIX` 里的 `0:00:00:00`)。真正的时间在样本上,由
 * `SubtitleTranscodingTrackOutput` 按 `timeUs + startTimeUs` 定位。
 *
 * 所以事件**搭 media3 的 cue 便车**回去(见 [assCarrier]),由 `ExoSubtitles`
 * 在播到那一刻收下、喂给 libass、再把它从可见 cue 里摘掉。
 * 顺带的好处:切轨不用重放 —— media3 自己会从当前位置重新派发。
 *
 * ## 开轨的时机不由我们定
 *
 * `onTracksChanged`(切轨请求)和 ASS 头(解封装)谁先到是不定的,
 * 两边都会触发开轨:[activateTrack] 记下要哪条,[header] 到了补开。
 */
@OptIn(UnstableApi::class)
object Libass {

    private const val TAG = "lp-libass-kt"

    private val lock = Any()
    /** 每条轨的 ASS 头。解封装时就到了,比切轨请求早 —— 所以先存下来。 */
    private val heads = LinkedHashMap<String, ByteArray?>()
    private var active: String? = null
    private var opened = false
    /** 想放的那条轨。数据还没到就先记下来,等第一批数据到了自己开 —— 见 [activateTrack]。 */
    private var wanted: String? = null
    private var wantedFonts = ""

    /** 下一帧强制重画。灌了字体 / 换了尺寸之后要用它 —— 见 [render]。 */
    private var pendingForce = false
    /* 开轨之后**真的喂进去几条、真的画出几帧**。各记一行就够 ——
       这条链上「开起来了但一个字没有」有四五种成因,不记这两个数只能一轮一轮试。 */
    private var fed = 0
    private var drawn = 0
    /** 这一部片已经灌过的字体名。附件字体常常在多条轨里重复,灌两遍是白花内存。 */
    private val fonts = HashSet<String>()
    /** 最近一次要的画布 / 片源尺寸。见 [setSize]。 */
    private var canvasW = 0
    private var canvasH = 0
    private var storeW = 0
    private var storeH = 0

    /** 这个构建的 libass 能不能用。取不到就整条路不走 —— 不是崩,是回落成普通字幕。 */
    val available: Boolean by lazy {
        val v = runCatching { Native.assVersion() }.getOrDefault(0)
        Logs.d(TAG, "libass 版本 0x%X".format(v))
        v > 0
    }

    fun isAss(f: Format?): Boolean = isAssMime(f?.sampleMimeType, f?.codecs)

    // ------------------------------------------------------------ 喂数据

    fun header(id: String, header: ByteArray?) = synchronized(lock) {
        heads[id] = header ?: heads[id]
        // 切轨请求先到、头后到:头到了自己补开
        if (!opened && id == wanted) openLocked(id, wantedFonts)
        Unit
    }

    /**
     * 一条事件。**时间由调用方给**,因为解析器那一侧根本拿不到 —— 见 [assCarrier]。
     *
     * libass 按 ReadOrder 去重,所以同一条重复喂(seek、cue 反复派发)不会画两遍。
     */
    fun chunk(body: ByteArray, startMs: Long, durMs: Long): Unit = synchronized(lock) {
        if (!opened) return
        Native.assChunk(body, startMs, durMs)
        if (fed++ == 0) Logs.d(TAG, "第一条事件进 libass @${startMs}ms ${body.size} 字节")
    }

    // ------------------------------------------------------------ 切轨

    /** 切到某条内封 ASS 轨。事件由 media3 按播放时间派发过来,这里只管开。 */
    fun activateTrack(id: String, fontsDir: String): Boolean = synchronized(lock) {
        if (!available) return false
        wanted = id
        wantedFonts = fontsDir
        if (active == id && opened) return true
        return openLocked(id, fontsDir)
    }

    private fun openLocked(id: String, fontsDir: String): Boolean {
        if (!heads.containsKey(id)) return false      // 头还没到,等 header() 来补开
        val h = heads[id]
        val rc = Native.assOpen(h, fontsDir)
        val styles = h?.let { String(it, Charsets.UTF_8).split("Style:").size - 1 } ?: 0
        Logs.d(TAG, "开轨 $id rc=$rc 头 ${h?.size ?: 0} 字节 样式 $styles 条")
        if (rc != 0) {
            // 开不起来是硬失败(建轨/建渲染器失败),重试只会每来一条字幕就再失败一次
            opened = false; active = null; wanted = null
            return false
        }
        fed = 0; drawn = 0
        active = id
        opened = true
        applySizeLocked()
        return true
    }

    /** 切到一份外挂 .ass / .ssa(整份文件)。 */
    fun activateFile(key: String, bytes: ByteArray, fontsDir: String): Boolean = synchronized(lock) {
        if (!available) return false
        /* ☠ 外挂 .ass 和内封 ASS **抢同一个渲染器**,到达顺序却是随机的:
           外挂要走一趟网络(几百毫秒),内封等解封装。上一版没有主次、谁后到谁赢,
           而且外挂那条还顺手把 `wanted` 清了 —— 内封再也开不回来。
           规矩:**选中的内封轨说了算,外挂只补空缺**。 */
        if (wanted != null) return false
        if (active == key && opened) return true
        if (Native.assOpenFile(bytes, fontsDir) != 0) {
            opened = false; active = null
            return false
        }
        active = key
        opened = true
        fed = 0; drawn = 0
        applySizeLocked()
        return true
    }

    fun deactivate() = synchronized(lock) {
        if (opened) Native.assClose()
        opened = false
        active = null
        wanted = null
    }

    /** 换一部片:连缓存一起清。留着就是把上一集的字幕喂给下一集。 */
    fun reset() = synchronized(lock) {
        if (opened) Native.assClose()
        opened = false
        active = null
        wanted = null
        heads.clear()
        // ★ 字体名单跟着清,但**已经灌进 libass 库里的字体不撤** ——
        //   撤要销毁整个 ASS_Library(字体目录重扫几十毫秒),而多留几份
        //   上一集的字体只是占点内存,libass 按名字查,不会画错。
        fonts.clear()
    }

    /**
     * 灌内嵌字体(MKV 附件)。
     *
     * ★ 灌完要**强制重画一帧**:libass 只在事件变化时才说「这一帧变了」,
     *   而换字体不算事件变化 —— 不强制的话画面上还是上一帧那份系统字体,
     *   直到下一句台词才换过来。
     */
    fun addFonts(list: List<MkvFonts.Font>): Int = synchronized(lock) {
        if (!available) return 0
        var n = 0
        for (f in list) {
            if (!fonts.add(f.name)) continue
            if (runCatching { Native.assAddFont(f.name, f.data) }.getOrDefault(-1) == 0) n++
        }
        if (n > 0) pendingForce = true
        return n
    }

    val isActive: Boolean get() = synchronized(lock) { opened }

    // ------------------------------------------------------------ 渲染

    /**
     * 画布尺寸。
     *
     * ☠ **记下来,开轨之后要再补一次。** `ass_set_frame_size` 没设过的话
     * libass 往一张 0×0 的画布上渲染 —— `ass_render_frame` 回空,
     * 表现是「libass 开起来了,一个字都没有」。而这一层的调用顺序**不由我们定**:
     * 画布是 Compose 布局完才有尺寸的,轨道是 ExoPlayer 解完封装才有的,
     * 外挂字幕更是起播之后才装。谁先谁后随机 —— 所以不能只在 setSize 这一侧发。
     */
    fun setSize(frameW: Int, frameH: Int, videoW: Int, videoH: Int) = synchronized(lock) {
        canvasW = frameW; canvasH = frameH; storeW = videoW; storeH = videoH
        applySizeLocked()
    }

    private fun applySizeLocked() {
        if (!opened || canvasW <= 0 || canvasH <= 0) return
        Native.assSetSize(canvasW, canvasH, storeW, storeH)
        pendingForce = true
    }

    /**
     * 这一帧的字幕内容变了没有。**不碰位图。**
     *
     * ★ 双缓冲要它:后备那张装的是上上帧,拿 `force=false` 去画的话 libass 会说
     *   「没变」而后备那张的内容是错的;拿 `force=true` 去画又等于每帧整块清屏重画,
     *   对白字幕那点省电全没了。先问一句、变了才画。
     */
    fun changed(posMs: Long): Boolean = synchronized(lock) {
        opened && (pendingForce || Native.assChanged(posMs))
    }

    /** -1 出错 / 0 和上一帧一样 / 1 位图已更新。 */
    fun render(bmp: Bitmap, posMs: Long, force: Boolean): Int = synchronized(lock) {
        if (!opened) return -1
        val f = force || pendingForce
        pendingForce = false
        val rc = Native.assRender(bmp, posMs, f)
        if (rc > 0 && drawn++ == 0) Logs.d(TAG, "libass 画出第一帧 @${posMs}ms")
        return rc
    }
}

/**
 * 拦下 ASS/SSA 的原始事件交给 libass,**自己一条 cue 都不输出**。
 *
 * ☠ 不输出是有意的:输出了的话 media3 会把它当普通字幕再画一遍,
 * 屏幕上就是两层字。别的格式(SRT / VTT / PGS)原样交给 media3 自己那套。
 */
@OptIn(UnstableApi::class)
class LibassParserFactory(private val fontsDir: String) : SubtitleParser.Factory {
    private val fallback = DefaultSubtitleParserFactory()

    // 支持面不扩大:我们只是把其中 ASS 那一类换个人画
    override fun supportsFormat(format: Format) = fallback.supportsFormat(format)

    override fun getCueReplacementBehavior(format: Format) =
        fallback.getCueReplacementBehavior(format)

    override fun create(format: Format): SubtitleParser =
        if (Libass.available && Libass.isAss(format))
            LibassParser(format.id ?: "ass", format)
        else fallback.create(format)
}

/**
 * ☠☠ **media3 给的不是标准 ASS 行。**
 *
 * `MatroskaExtractor` 把每个 SSA 样本重写成
 * `Dialogue: <Start>,<End>,` + 原始 Matroska 事件体,并配一条自定义的
 * `Format: Start, End, ReadOrder, Layer, Style, Name, MarginL, MarginR, MarginV, Effect, Text`
 * (常量原文来自 `MatroskaExtractor.SSA_PREFIX` / `SSA_DIALOGUE_FORMAT`,反编译核对过)。
 * 字段顺序和标准 ASS 的 `Layer,Start,End,Style,…` **不是一回事** ——
 * 直接丢给 `ass_process_data` 会把 Layer 当成 Style,样式全错而且不报错。
 *
 * 而切掉前两个字段之后剩下的正好是
 * `ReadOrder,Layer,Style,Name,MarginL,MarginR,MarginV,Effect,Text` ——
 * **就是 `ass_process_chunk` 要的那个口径**(libass 自己读掉 ReadOrder 和 Layer,
 * 再按 n_ignored=3 跳过 Format 里的 Layer/Start/End)。所以这里只做两件事:
 * 解出时间、把正文原样递过去。
 */
@OptIn(UnstableApi::class)
private class LibassParser(private val id: String, format: Format) : SubtitleParser {

    init {
        Libass.header(id, assHeaderOf(format.initializationData))
    }

    override fun parse(
        data: ByteArray, offset: Int, length: Int,
        outputOptions: SubtitleParser.OutputOptions,
        output: Consumer<CuesWithTiming>,
    ) {
        val ev = splitMedia3Dialogue(String(data, offset, length, Charsets.UTF_8)) ?: return
        /* 起点填 0 是**对的**:`SubtitleTranscodingTrackOutput` 会把它加到样本时间上,
           我们要的正是「这条样本自己的时刻」。真正的 ASS 事件到 [assPayload] 才落地。 */
        output.accept(CuesWithTiming(
            listOf(Cue.Builder().setText(assCarrier(ev.durMs, ev.body)).build()),
            0L,
            ev.durMs * 1000,
        ))
    }

    override fun getCueReplacementBehavior() = Format.CUE_REPLACEMENT_BEHAVIOR_MERGE

    override fun reset() { /* 事件按 ReadOrder 去重,seek 不必清空 —— 清了反而要重放 */ }
}

/**
 * 从 `initializationData` 里挑出**真正的 ASS 头**。
 *
 * ☠☠ media3 的 `MatroskaExtractor` 塞的是**两条**:`[0]` 是它自己拼的
 * `Format: Start, End, ReadOrder, …`(恒 90 字节),`[1]` 才是 MKV 的 CodecPrivate,
 * 也就是带 `[Script Info]`(PlayResX/PlayResY)和 `[V4+ Styles]` 的那份真头
 * (1.11.0 字节码:`ImmutableList.of(SSA_DIALOGUE_FORMAT, getCodecPrivate(codecId))`)。
 * 拿了 `[0]` 的表现是 libass **开得起来**(`rc=0`)却一个字都不画:样式表是空的,
 * 事件的 Style 索引全落在表外,渲染那一步整条跳过 —— 一句错都不报。
 * 真机日志里那句「头 90 字节」就是这条错的指纹。
 *
 * ★ 按内容认而不是按下标认:下标的顺序是 media3 的实现细节,认错了又是无声失败。
 */
internal fun assHeaderOf(data: List<ByteArray>): ByteArray? =
    data.lastOrNull { String(it, Charsets.UTF_8).contains("[Script Info]", true) }
        ?: data.lastOrNull()

/**
 * 一条切好的事件。`body` 已经是 `ass_process_chunk` 要的 Matroska 口径。
 *
 * **没有起始时间**,因为那一格里根本没有信息:见 [splitMedia3Dialogue]。
 */
data class AssEvent(val durMs: Long, val body: String)

/** 便车的记号。用控制字符是因为它**不可能**出现在真字幕文本里。 */
private const val MARK = "lp-ass"

/**
 * 把事件包成一条 cue 交回 media3,让它按**样本时间**派发。
 *
 * ☠☠ 这是「libass 开得起来、样式也对,就是一个字不出」的最后一层:
 * 解析器那一侧只知道时长、不知道这条什么时候播(media3 把开始时间写死成 0),
 * 上一版把那个 0 当成事件起点喂给 `ass_process_chunk` —— 于是**整片字幕全排在片头**,
 * 播到哪儿都是空的。真机日志里那句「第一条事件进 libass @0ms」就是指纹。
 */
internal fun assCarrier(durMs: Long, body: String): String = "$MARK$durMs$MARK$body"

/** 从 cue 文本里取回 `(时长, 正文)`。不是我们塞的就返回 null,照常当普通字幕画。 */
internal fun assPayload(text: CharSequence?): Pair<Long, String>? {
    val s = text?.toString() ?: return null
    if (!s.startsWith(MARK)) return null
    val end = s.indexOf(MARK, MARK.length)
    if (end < 0) return null
    val dur = s.substring(MARK.length, end).toLongOrNull() ?: return null
    return dur to s.substring(end + MARK.length)
}

/**
 * 把 media3 重写过的那行 `Dialogue:` 拆成 libass 要的两样东西。
 *
 * ☠☠ **第二格是「时长」,第一格是废的。** media3 拼的前缀是常量
 * `Dialogue: 0:00:00:00,0:00:00:00,`,之后只回填**第二格**、填的是 `blockDurationUs`
 * (1.11.0 字节码:`setSubtitleEndTime(codecId, blockDurationUs, data)` 往偏移 21 写)。
 * 第一格从头到尾是那个常量 0 —— 这条样本什么时候播,信息在样本上,不在这行里。
 * 上一版把它当成事件起点,于是整片字幕全排在片头(见 [assCarrier])。
 *
 * 抽成顶层函数不是为了好看,是**为了能在 JVM 上测** ——
 * 这一步错了不会报错,只会「字幕出来了但样式全丢」或者「时间全偏」,
 * 而那两种在真机上都得靠肉眼才看得出来。
 *
 * 拆不出来返回 null:宁可这一条不画,也不画一条错的。
 */
internal fun splitMedia3Dialogue(line: String): AssEvent? {
    val body = line.removePrefix("Dialogue: ")
    if (body.length == line.length) return null
    val c1 = body.indexOf(',')
    if (c1 < 0) return null
    val c2 = body.indexOf(',', c1 + 1)
    if (c2 < 0) return null
    val dur = assTimeMs(body.substring(c1 + 1, c2))
    if (dur < 0) return null
    return AssEvent(dur, body.substring(c2 + 1))
}

/**
 * `H:MM:SS:CC`(百分秒)。
 *
 * ☠ 这是 **media3 写进去的**格式(`MatroskaExtractor.SSA_TIMECODE_FORMAT`
 * = `%01d:%02d:%02d:%02d`),不是 ASS 自己那个 `H:MM:SS.CC` ——
 * 最后一段的分隔符是**冒号不是点**。按点去切的话四段变三段,整条丢掉。
 */
internal fun assTimeMs(s: String): Long {
    val p = s.trim().split(':')
    if (p.size != 4) return -1
    return try {
        p[0].toLong() * 3_600_000 + p[1].toLong() * 60_000 +
            p[2].toLong() * 1_000 + p[3].toLong() * 10
    } catch (_: NumberFormatException) {
        -1   // 解不出来就当这一条不存在:画错时间的字幕比没有更烦
    }
}

/**
 * 这条轨是不是 ASS/SSA。
 *
 * ☠☠ **不能只看 `sampleMimeType`。** 一旦挂了 `setSubtitleParserFactory`,
 * `SubtitleTranscodingTrackOutput.format()` 就会把交给下游的 Format 改写成
 * `sampleMimeType = application/x-media3-cues`,**把原来的 mime 挪进 `codecs`**
 * (media3-extractor 1.11.0 字节码:`setSampleMimeType("application/x-media3-cues")`
 * 紧跟着 `setCodecs(format.sampleMimeType)`)。
 *
 * 于是**轨道选择那一侧看到的永远不是 `text/x-ssa`** —— 上一版只比 sampleMimeType,
 * 这个判断恒 false:libass 一次都没被接上,而事件已经被我们的解析器吃掉了。
 * 表现就是用户报的「ASS 字幕直接消失,libass 完全没生效」,一句错都不报。
 *
 * 解析器工厂那一侧收到的是**改写前**的 Format(所以那边一直是对的),
 * 两侧共用这一个判据,不要各写一遍。
 */
internal fun isAssMime(sampleMime: String?, codecs: String?): Boolean =
    sampleMime == MimeTypes.TEXT_SSA || codecs == MimeTypes.TEXT_SSA
