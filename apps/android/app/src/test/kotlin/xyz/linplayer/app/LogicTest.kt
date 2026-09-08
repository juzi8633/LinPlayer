package xyz.linplayer.app

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.linplayer.app.data.Capabilities
import xyz.linplayer.app.data.Item
import xyz.linplayer.app.data.Page
import xyz.linplayer.app.data.Session
import xyz.linplayer.app.data.UiPrefs
import xyz.linplayer.app.ui.pages.Stream
import xyz.linplayer.app.ui.pages.Version
import xyz.linplayer.app.ui.pages.defaultVersion
import xyz.linplayer.app.ui.pages.fmtTime
import xyz.linplayer.app.ui.pages.artLogoSize
import xyz.linplayer.app.ui.pages.CORNER_LABELS
import xyz.linplayer.app.ui.pages.cornerCode
import xyz.linplayer.app.ui.pages.cornerLabel
import xyz.linplayer.app.ui.pages.withScheme
import xyz.linplayer.app.ui.player.VideoFit
import xyz.linplayer.app.ui.player.assCarrier
import xyz.linplayer.app.ui.player.assHeaderOf
import xyz.linplayer.app.ui.player.assPayload
import xyz.linplayer.app.ui.player.assTimeMs
import xyz.linplayer.app.ui.player.isAssMime
import xyz.linplayer.app.ui.player.shotCorner
import xyz.linplayer.app.ui.player.DragAxis
import xyz.linplayer.app.ui.player.cueRect
import xyz.linplayer.app.ui.player.fmtSpeed
import xyz.linplayer.app.ui.player.lockAxis
import xyz.linplayer.app.ui.player.videoRect
import xyz.linplayer.app.ui.player.splitMedia3Dialogue
import xyz.linplayer.app.ui.theme.pickTone
import xyz.linplayer.app.ui.theme.rgbToHsv

/**
 * UI 层的纯逻辑。**跑在 JVM 上,不需要设备。**
 *
 * 只测那几条「错了不会报错、只会静默给出一个错事实」的:
 * 版本回落、地址补全、剧集标题、能力集判定。别的交给真渲染自检。
 */
class LogicTest {

    /**
     * ☠ **不许自己回落 `versions[0]`。**
     *
     * 核心层没标 `preferred` 就是「让核心层自己决定」,UI 传 null 而不是替它选一个。
     * 旧实现回落第一版 → 版本筛选正则**从上线起一次都没生效过,还一声不吭**。
     */
    @Test fun `没有 preferred 时不许回落第一版`() {
        val vs = listOf(
            Version("a", "1080p", preferred = false),
            Version("b", "4K", preferred = false),
        )
        assertNull("没有 preferred 就该是 null,不是 versions[0]", defaultVersion(vs))
    }

    @Test fun `有 preferred 时选它而不是第一个`() {
        val vs = listOf(
            Version("a", "1080p", preferred = false),
            Version("b", "4K", preferred = true),
        )
        assertEquals("b", defaultVersion(vs)?.id)
    }

    /**
     * 地址补全。**默认补 http 不是 https** —— 补错协议只会连不上,
     * 而补 https 到一台只有 http 的服务器上,报的是看不懂的 TLS 错。
     */
    @Test fun `地址没写协议时补 http`() {
        assertEquals("http://example.test:8096", withScheme("example.test:8096"))
        assertEquals("https://a.test", withScheme("https://a.test"))
        assertEquals("http://a.test", withScheme("  http://a.test  "))
        assertEquals("", withScheme("   "))
    }

    /** 剧集**恒带剧名** —— Emby 的 Episode.Name 只是「第 35 集」,单看无意义。 */
    @Test fun `剧集卡片标题用剧名副标题用季集`() {
        val ep = item("""{"id":"e1","name":"第 35 集","type_":"Episode",
            "series_name":"某部剧","season_no":2,"episode_no":35}""")!!
        assertEquals("某部剧", ep.cardTitle)
        assertEquals("S2E35", ep.cardSub)
    }

    @Test fun `电影卡片副标题是年份`() {
        val m = item("""{"id":"m1","name":"某部电影","type_":"Movie","year":2019}""")!!
        assertEquals("某部电影", m.cardTitle)
        assertEquals("2019", m.cardSub)
    }

    /** 核心层加字段**不该把界面打红** —— 未知键必须被忽略。 */
    @Test fun `多出来的字段不影响解析`() {
        val it = item("""{"id":"x","name":"n","type_":"Movie","将来才有的字段":123}""")
        assertEquals("n", it?.name)
    }

    /** 少字段也只是那一项没有,不是整条丢掉。 */
    @Test fun `缺字段时给默认值而不是丢掉整条`() {
        val it = item("""{"id":"x"}""")!!
        assertEquals("", it.name)
        assertEquals(0.0, it.runtimeSecs, 0.0)
    }

    @Test fun `没有 id 的条目直接丢掉`() {
        assertNull(item("""{"name":"没有 id"}"""))
    }

    /** 进度条只在有进度时出现,而且不许超过 1。 */
    @Test fun `观看进度按比例且封顶`() {
        assertEquals(0f, item("""{"id":"a","runtime_secs":100}""")!!.progress, 1e-6f)
        assertEquals(0.5f, item("""{"id":"a","runtime_secs":100,"resume_secs":50}""")!!.progress, 1e-6f)
        assertEquals(1f, item("""{"id":"a","runtime_secs":100,"resume_secs":999}""")!!.progress, 1e-6f)
    }

    /** `unsupported` 里的命令,入口在启动时就不画(UI_MOBILE §6.3)。 */
    @Test fun `能力集判定`() {
        val c = Capabilities.from(Json.parseToJsonElement(
            """{"platform":"android","version":"1.0","unsupported":["system.pickFile"],
               "features":{"pip":true,"filePicker":false}}"""))
        assertTrue(c.supports("emby.views"))
        assertTrue(!c.supports("system.pickFile"))
        assertTrue(c.feature("pip"))
        assertTrue(!c.feature("filePicker"))
    }

    /**
     * ☠ 会话字段名**就是线上字段名**(小写下划线)。
     * 写成驼峰发出去核心层当作没传,报「缺少 server 或 user_id」——
     * 两边都不报编译错,只在运行时现形。
     */
    @Test fun `会话按线上字段名解析`() {
        val s = Session.from(Json.parseToJsonElement(
            """{"server":"http://a.test","token":"t","user_id":"u","user_name":"n"}"""))!!
        assertEquals("http://a.test", s.server)
        assertEquals("t", s.token)
        assertEquals("u", s.userId)
    }

    @Test fun `分页既吃裸数组也吃带 total 的对象`() {
        val bare = Page.from(Json.parseToJsonElement("""[{"id":"a"},{"id":"b"}]"""))
        assertEquals(2, bare.items.size)
        assertNull(bare.total)
        val paged = Page.from(Json.parseToJsonElement("""{"items":[{"id":"a"}],"total":42}"""))
        assertEquals(42L, paged.total)
    }

    @Test fun `时间格式一小时以上才带小时位`() {
        assertEquals("12:34", fmtTime(754.0))
        assertEquals("1:00:05", fmtTime(3605.0))
    }

    /* ───────────────── 封面取色 ───────────────── */

    /**
     * ☠ **色相要按圆周平均,不能按算术平均。**
     *
     * 红色跨 0°/360°:一半像素在 355°、一半在 5°,算术平均得 180° —— 那是**青色**,
     * 正好是红的补色。详情页整页会染成青的,而代码看起来完全正常。
     */
    @Test
    fun `取色_红色跨零度不会平均成青色`() {
        val px = IntArray(200) { if (it % 2 == 0) 0xC81020 else 0xC82010 }
        val hsv = rgbToHsv2(pickTone(px)!!)
        assertTrue("色相应落在红区(±30°),实得 ${hsv[0]}", hsv[0] < 30f || hsv[0] > 330f)
    }

    /** 灰度图取不出色 —— **返回 null 让调用方回落主题色**,不要挑出一个噪点色。 */
    @Test
    fun `取色_灰度图返回null`() {
        assertNull(pickTone(IntArray(200) { 0x3A3A3A }))
        assertNull(pickTone(IntArray(200) { 0x000000 }))
    }

    /**
     * 出来的色必须**能当底色**:太亮压不住白字,太灰等于没取色。
     * 输入是一张刺眼的荧光绿 —— clamp 没生效的话这一条会红。
     */
    @Test
    fun `取色_钳到能当底色的明度和饱和度`() {
        val hsv = rgbToHsv2(pickTone(IntArray(200) { 0x00FF00 })!!)
        assertTrue("饱和度 ${hsv[1]} 该在 .42~.80", hsv[1] in 0.42f..0.80f)
        assertTrue("明度 ${hsv[2]} 该在 .42~.68", hsv[2] in 0.42f..0.68f)
    }

    private fun rgbToHsv2(rgb: Int) =
        rgbToHsv((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)

    private fun item(json: String): Item? = Item.from(Json.parseToJsonElement(json))

    /**
     * ☠☠ **`LpCell` 的 `onClick` 必须是最后一个形参。**
     *
     * 它排在 `onSwitch` 前面的时候,`LpCell("外观", icon = X) { nav.navigate(...) }`
     * 这种写法会把尾随 lambda 绑到 `onSwitch`(Kotlin 绑最后一个形参),
     * 而一个不声明参数的 lambda 完全可以当成 `(Boolean) -> Unit` —— **所以它编译得过**。
     * 后果:`onClick` 恒 null,整张设置列表一条都点不进去,一个警告都没有。
     *
     * 这条只能靠反射钉:它是**签名的形状**,不是运行时行为,任何调用都测不出来。
     * Compose 编译器会在末尾追加 Composer / 两个 int,所以只比较两个 lambda 的先后。
     */
    @Test
    fun `LpCell 的 onClick 必须排在 onSwitch 后面`() {
        val m = Class.forName("xyz.linplayer.app.ui.components.BaseKt")
            .declaredMethods.first { it.name == "LpCell" }
        val types = m.parameterTypes.map { it.name }
        val onSwitch = types.indexOf("kotlin.jvm.functions.Function1")
        val onClick = types.indexOf("kotlin.jvm.functions.Function0")
        assertTrue("签名里该有 onSwitch:(Boolean)->Unit,实得 $types", onSwitch >= 0)
        assertTrue("签名里该有 onClick:()->Unit,实得 $types", onClick >= 0)
        assertTrue(
            "onClick 必须排在 onSwitch 之后,否则尾随 lambda 会静默绑错(实得 " +
                "onSwitch=$onSwitch onClick=$onClick)",
            onClick > onSwitch,
        )
    }

    /**
     * ☠☠ `emby.seasonEpisodes` 回的是 **`{items,total}`**,不是裸数组。
     *
     * 详情页和播放页的选集面板两处都写成 `Item.list(...)`,而 `arr()` 对 JsonObject
     * 返回空表 —— 于是「这一季一集都没有」和「解析形状挑错了」长得一模一样,
     * 一条日志都没有。用户报的「集数没显示出来」就是这一条。
     *
     * 判据:两种形状都要解得出来。
     */
    @Test fun `Item·list 要同时吃裸数组和 items 包裹`() {
        val bare = Json.parseToJsonElement("""[{"id":"e1","name":"第 1 集","type_":"Episode"}]""")
        val wrapped = Json.parseToJsonElement(
            """{"items":[{"id":"e1","name":"第 1 集","type_":"Episode"}],"total":1}""")
        assertEquals("裸数组解不出来", 1, Item.list(bare).size)
        assertEquals("{items,total} 解不出来 —— 选集面板会恒空", 1, Item.list(wrapped).size)
        assertEquals("e1", Item.list(wrapped).first().id)
    }

    // ---------------------------------------------------------------- libass

    /**
     * ☠☠ media3 给的 `Dialogue:` 行**不是标准 ASS 行**。
     *
     * `MatroskaExtractor` 把 SSA 样本重写成
     * `Dialogue: <Start>,<End>,` + 原始 Matroska 事件体,配的 Format 是
     * `Start, End, ReadOrder, Layer, Style, …`(反编译 SSA_PREFIX /
     * SSA_DIALOGUE_FORMAT 核对过),字段顺序和标准 ASS 的
     * `Layer,Start,End,Style,…` 不是一回事。
     *
     * 而切掉前两段之后剩下的正好是 `ass_process_chunk` 要的那串。
     * 切错的表现是「字幕出来了但样式全丢」—— 编译绿、不报错、只能靠肉眼。
     */
    @Test fun `media3 的 Dialogue 行要切成 libass 的 chunk 口径`() {
        // 原始字符串:ASS 满是反斜杠,转义写法一眼看不出对不对。
        // 第一格恒是 media3 前缀里那个常量 0,第二格是**时长**不是结束时刻。
        val line = """Dialogue: 0:00:00:00,0:00:02:66,7,0,OP-CN,,0,0,0,,{\pos(640,80)\fad(300,300)}风吹过的夏天"""
        val ev = splitMedia3Dialogue(line)
        assertNotNull("这一行就是 media3 的真实形状,拆不出来等于整条路不通", ev)
        ev!!
        // 0:00:02:66 = 2 秒 66 百分秒
        assertEquals("时长算错了 —— 最后一段是**百分秒**不是毫秒", 2_660L, ev.durMs)
        assertEquals(
            "正文必须是 ReadOrder 打头的 Matroska 口径,前两段时间要切掉",
            """7,0,OP-CN,,0,0,0,,{\pos(640,80)\fad(300,300)}风吹过的夏天""",
            ev.body,
        )
    }

    /** 时间戳的分隔符是**冒号**不是点:按点切会四段变三段,整条丢掉。 */
    @Test fun `ASS 时间戳按冒号切四段`() {
        assertEquals(3_723_450L, assTimeMs("1:02:03:45"))
        assertEquals(0L, assTimeMs("0:00:00:00"))
        assertEquals("段数不对要判失败,不能猜", -1L, assTimeMs("00:00:01.500"))
        assertEquals("非数字要判失败", -1L, assTimeMs("a:b:c:d"))
    }

    /** 不是那个形状就返回 null:宁可这一条不画,也不画一条错的。 */
    @Test fun `不认识的行一律判失败`() {
        assertNull(splitMedia3Dialogue("Comment: 0:00:01:00,0:00:02:00,0,0,Default,,0,0,0,,x"))
        assertNull(splitMedia3Dialogue("Dialogue: 0:00:01:00"))
        assertNull(splitMedia3Dialogue(""))
    }

    // ------------------------------------------------------------ Exo 内核的字幕与画面

    /**
     * ☠ 这一条钉的是**用户报的「ASS 字幕直接消失」**。
     *
     * 挂了 `setSubtitleParserFactory` 之后,`SubtitleTranscodingTrackOutput.format()`
     * 会把交给轨道选择那一侧的 Format 改写成 `application/x-media3-cues`,
     * 原 mime 挪进 `codecs`(media3-extractor 1.11.0 字节码核对)。
     * 只比 sampleMimeType 的话这个判断恒 false —— libass 一次都接不上,
     * 而事件已经被我们的解析器吃掉了,画面上一个字都没有,一句错都不报。
     */
    @Test fun `改写过的轨道格式也要认出是 ASS`() {
        assertTrue("解析器工厂那一侧看到的是改写前的",
            isAssMime("text/x-ssa", null))
        assertTrue("轨道选择那一侧看到的是改写后的",
            isAssMime("application/x-media3-cues", "text/x-ssa"))
        assertTrue("别的格式被改写后不许认成 ASS",
            !isAssMime("application/x-media3-cues", "application/x-subrip"))
        assertTrue("PGS 更不能认成 ASS", !isAssMime("application/pgs", null))
    }

    /**
     * 画面比例。上一版交给 `Modifier.aspectRatio`,对不对只有真机肉眼看得出来,
     * 用户为「画面被拉伸」报了两轮 —— 所以算式必须在这里能跑。
     */
    @Test fun `画面比例四档各算各的`() {
        // 宽屏手机横过来放 16:9:高度贴边,左右留黑
        val src = videoRect(2400, 1080, 16f / 9f, VideoFit.Source)
        assertEquals(1080, src.height)
        assertEquals(1920, src.width)
        // 竖屏放 16:9:宽度贴边,上下留黑
        val por = videoRect(1080, 2400, 16f / 9f, VideoFit.Source)
        assertEquals(1080, por.width)
        assertEquals(608, por.height)
        // 自适应 = 铺满裁切:两边都不小于容器
        val cov = videoRect(2400, 1080, 16f / 9f, VideoFit.Cover)
        assertTrue("铺满档必须盖住整个容器,宽=${cov.width} 高=${cov.height}",
            cov.width >= 2400 && cov.height >= 1080)
        // 强制档不看片源比例
        assertEquals(1440, videoRect(2400, 1080, 16f / 9f, VideoFit.R4x3).width)
        // 首帧之前比例还不知道:先铺满,别算出一块 0
        val unknown = videoRect(2400, 1080, 0f, VideoFit.Source)
        assertEquals(2400, unknown.width)
        assertEquals(1080, unknown.height)
    }

    /**
     * Hero 艺术字占的位置 = 它画出来的位置。
     *
     * 差出来的那一块是**框里的空白**,表现是「艺术字和下面的标签离得很远」——
     * 只有真机上肉眼看得见,所以在这里钉死。
     */
    @Test
    fun `艺术字的框不许比图大`() {
        // 宽长条:宽度先顶到上限,高度就该跟着缩,而不是留着 92 不动
        val (w1, h1) = artLogoSize(1200f, 200f, 320f, 92f)
        assertEquals(320f, w1, 0.01f)
        assertEquals(53.33f, h1, 0.05f)
        // 竖高的:高度顶到上限,宽度按比例
        val (w2, h2) = artLogoSize(200f, 400f, 320f, 92f)
        assertEquals(92f, h2, 0.01f)
        assertEquals(46f, w2, 0.01f)
        // 两种情况下框和图必须同一个比例(= 没有空白)
        assertEquals(1200f / 200f, w1 / h1, 0.01f)
        assertEquals(200f / 400f, w2 / h2, 0.01f)
        // 图还没解出来:给个中庸值,不许回 0(回 0 的表现是标题闪一下才出来)
        val (w3, h3) = artLogoSize(0f, 0f, 320f, 92f)
        assertTrue("未知尺寸也得有个占位,拿到的是 ${w3}x${h3}", w3 > 0f && h3 > 0f)
    }

    /* ───────────────── 截屏 ───────────────── */

    /**
     * 截屏水印的落点。
     *
     * ☠ 右侧和底侧要**减掉块自己的宽高**,减漏一次水印就整块跑到画面外面 ——
     * 而那不报错,只有真机截一张出来才看得见。
     */
    @Test fun `截屏水印四个角都落在画面里`() {
        val (w, h, edge) = Triple(200f, 60f, 40f)
        val cw = 1920; val ch = 1080
        for (pos in listOf("tl", "tr", "bl", "br")) {
            val (x, y) = shotCorner(pos, cw, ch, w, h, edge)
            assertTrue("$pos 的左上角出界了:($x, $y)", x >= 0f && y >= 0f)
            assertTrue("$pos 的右下角出界了:(${x + w}, ${y + h})",
                x + w <= cw && y + h <= ch)
        }
        // 四个角必须真的是四个不同的角,不是同一个
        assertEquals(edge, shotCorner("tl", cw, ch, w, h, edge).first, 0.01f)
        assertEquals(cw - edge - w, shotCorner("tr", cw, ch, w, h, edge).first, 0.01f)
        assertEquals(ch - edge - h, shotCorner("bl", cw, ch, w, h, edge).second, 0.01f)
    }

    /**
     * 截屏设置里的四个角:**存的是字母码,显示的是中文**。
     *
     * ☠ 两个方向必须互为反函数。错开一格的表现是「选了右下,下次进设置显示左上」——
     * 而两个 `when` 各自看都完全正常,一句错都不报。
     */
    @Test fun `截屏位置的码和中文标签要能来回走`() {
        CORNER_LABELS.forEach { label ->
            assertEquals("『$label』走一圈变了样", label, cornerLabel(cornerCode(label)))
        }
        listOf("tl", "tr", "bl", "br").forEach { code ->
            assertEquals("『$code』走一圈变了样", code, cornerCode(cornerLabel(code)))
        }
    }

    /**
     * 字幕行的两行字【用户定 2026-09-07】:第一行轨道名,第二行「语言 / 格式」。
     *
     * ☠ 轨道名优先 `title`。`display_title` 是 Emby 拼的「语言 + 格式」,
     * 拿它当名字的表现是整张表全是格式标签,用户分不出哪条是哪条 —— 报过两次。
     */
    @Test fun `字幕第一行是轨道名第二行是语言和格式`() {
        val named = sub(title = "简体中文特效", display = "Chinese - ASS", lang = "chi", codec = "ass")
        assertEquals("简体中文特效", named.label)
        assertEquals("中文 / ASS", named.langAndCodec)

        // 没有轨道名才轮到 display_title,再没有才自己拼
        assertEquals("English - PGS", sub(display = "English - PGS", lang = "eng", codec = "pgs").label)
        assertEquals("英语 SRT", sub(lang = "eng", codec = "srt").label)
    }

    /**
     * PGS 的比例是相对**字幕平面**(原盘那一帧)的,不是相对画面的。
     * 2.35:1 的片子重编码掉黑边之后两者不等比,拿画面框当平面用会把字竖着压扁 ——
     * 屏幕上看着就是「字幕向两边拉伸」。判据就一条:**位图长宽比不能变**。
     */
    @Test fun `图形字幕按自己的平面还原不被压扁`() {
        // 画面 3840×1632 铺在 2460×1080 的屏上 = 2460×1046;字幕平面还是 1920×1080
        val r = cueRect(
            2460f, 1046f, 1600, 120,
            size = 1600f / 1920f, bmpFrac = 120f / 1080f,
            position = 160f / 1920f, line = 900f / 1080f, pad = 24f,
        )
        assertEquals(1600f / 120f, r.width / r.height, 0.05f)
        assertEquals(2050f, r.width, 1f)          // 位图按画面的缩放比 2460/1920 放大
        assertEquals(0f, r.left - 205f, 1f)

        // 平面和画面同比例(1920×1080 的片子)时算式必须退化成原样
        val same = cueRect(
            1920f, 1080f, 1600, 120,
            size = 1600f / 1920f, bmpFrac = 120f / 1080f,
            position = 160f / 1920f, line = 900f / 1080f,
        )
        assertEquals(1600f, same.width, 1f)
        assertEquals(120f, same.height, 1f)
        assertEquals(900f, same.top, 1f)
    }

    /**
     * media3 的 MatroskaExtractor 给两条 initializationData:`[0]` 是它自己拼的
     * `Format: …`(恒 90 字节),`[1]` 才是带样式表的真 ASS 头。拿错了 libass
     * 照样 `rc=0`、一个字不画 —— 真机日志里那句「头 90 字节」就是指纹。
     */
    @Test fun `ASS 头要挑带样式表的那一条不是 media3 拼的 Format 行`() {
        val fmt = ("Format: Start, End, ReadOrder, Layer, Style, Name, " +
            "MarginL, MarginR, MarginV, Effect, Text").toByteArray()
        val real = ("[Script Info]\nPlayResX: 1920\n" +
            "[V4+ Styles]\nStyle: Default,思源黑体,60\n").toByteArray()
        assertEquals(90, fmt.size)
        assertEquals(String(real), String(assHeaderOf(listOf(fmt, real))!!))
        // 只给一条(别的解封装器)时原样用它
        assertEquals(String(real), String(assHeaderOf(listOf(real))!!))
        assertNull(assHeaderOf(emptyList()))
    }

    /**
     * ASS 事件搭 media3 的 cue 便车回来,时间到了才落地 —— 因为解析那一侧
     * 拿不到样本时间(media3 把 `Dialogue:` 的开始时间写死成 0)。
     * 包和拆必须严丝合缝,漏一点就是「一条字幕都不出」或者「屏幕上冒出乱码」。
     */
    @Test fun `ASS 事件搭便车回来时时长和正文都还在`() {
        val body = "0,0,默认,,0,0,0,,{\\pos(960,1000)}你好，世界"
        val (dur, got) = assPayload(assCarrier(4200, body))!!
        assertEquals(4200L, dur)
        assertEquals(body, got)

        // 普通字幕不能被当成便车:那会让真字幕凭空消失
        assertNull(assPayload("普通字幕"))
        assertNull(assPayload(null))
    }

    private fun sub(
        title: String? = null, display: String? = null,
        lang: String? = null, codec: String = "",
    ) = Stream(
        index = 0, type = "Subtitle", codec = codec, profile = null,
        title = title, display = display, lang = lang,
        width = null, height = null, bitrate = null, channels = null,
        layout = null, fps = null, range = null, isDefault = false, isExternal = false,
    )

    /**
     * ☠ 方向要**攒够再定**,而且定了不许改。
     *
     * 上一版的判据是「这一帧 |dx| 和 |dy| 谁大」—— 手指稍斜,两个分量在每一帧上
     * 互相超过,一次左右滑里就夹着几十帧被判成上下:用户报的
     * 「左右滑顺带把上下也滑了」就是它。这条钉住三件事:没攒够不定、
     * 斜着划不定、主方向必须明显压过副方向。
     */
    @Test fun `手势方向要攒够才定并且斜划不算`() {
        val slop = 28f
        assertNull("没攒够就定方向 = 一碰就窜", lockAxis(10f, 4f, slop))
        assertNull("没攒够就定方向 = 一碰就窜", lockAxis(3f, 20f, slop))
        assertEquals(DragAxis.H, lockAxis(60f, 12f, slop))
        assertEquals(DragAxis.H, lockAxis(-60f, 12f, slop))
        assertEquals(DragAxis.V, lockAxis(10f, 40f, slop))
        // 45° 斜划:两边都够长,但谁也不占优 —— 什么都不该触发
        assertNull("斜着划必须什么都不做,不能随机落到一边", lockAxis(50f, 48f, slop))
    }

    /** 网速读数。取不到就**返回空串**(界面上那一格整个不画),不摆一个恒为 0 的数。 */
    @Test fun `网速读数按量级换单位取不到就空`() {
        val sec = 1_000_000_000L
        assertEquals("2.0 MB/s", fmtSpeed(2L * 1024 * 1024, sec))
        assertEquals("500 KB/s", fmtSpeed(512_000, sec))
        assertEquals("0 KB/s", fmtSpeed(100, sec))
        assertEquals("", fmtSpeed(1024, 0))
        // 计数器回绕 / 换网卡时差值会是负数 —— 那时宁可不画
        assertEquals("", fmtSpeed(-1, sec))
    }

    /**
     * 长按播放键用**另一个**内核。
     *
     * ☠ 两个方向必须互为反函数。同向的表现是「短按长按都是 mpv」——
     * 那时长按等于坏了,而界面上一点看不出来。
     */
    @Test fun `长按播放键换的是另一个内核`() {
        assertEquals("exo", UiPrefs.otherEngine("mpv"))
        assertEquals("mpv", UiPrefs.otherEngine("exo"))
        // 存了个不认识的值时保守回到 exo(短按那边的 when 也把非 exo 当 mpv)
        assertEquals("exo", UiPrefs.otherEngine("垃圾值"))
    }
}
