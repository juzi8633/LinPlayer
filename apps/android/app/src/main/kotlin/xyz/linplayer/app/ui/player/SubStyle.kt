package xyz.linplayer.app.ui.player

import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import xyz.linplayer.app.core.Native
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.bool
import xyz.linplayer.app.data.dbl
import xyz.linplayer.app.data.long
import xyz.linplayer.app.data.obj
import xyz.linplayer.app.ui.pages.args

/**
 * 字幕样式:大小 / 位置 / 描边 / 粗体 / 随窗口缩放(用户 2026-09-08 点名的五项)。
 *
 * ## 一份值,三个消费者
 *
 * 落库在核心层(`player.setSubStyle`,和 PC 端同一条命令、同一份配置),
 * 而**画字幕的不止一个**:
 *
 * | 内核 | 谁来画 | 认哪几项 |
 * |---|---|---|
 * | mpv | libmpv 自己 | 五项全认(核心层直接设 mpv 属性) |
 * | Exo · ASS 特效字幕 | [Libass] | 只有大小和位置([Native.assSetStyle]) |
 * | Exo · SRT/VTT 文本字幕 | Compose 的 `TextCue` | 大小 / 描边 / 粗体 |
 *
 * ☠ **描边和粗体对 ASS 不生效,这是设计不是缺陷。** ASS 字幕自带样式,
 *   盖掉它等于把压制组做的特效改成另一份字幕。面板上要把这句话写出来 ——
 *   不写的话用户会以为那两项坏了。
 *
 * ## 为什么「大小」不是 sub-font-size
 *
 * mpv 默认 `sub-ass-override=scale`,这个模式下 ASS **完全忽略** sub-font-size。
 * 而内封字幕(尤其番剧)绝大多数是 ASS。所以大小统一走 `sub-scale`
 * (实测记在 `core/player/subtitle.go` 的 `setSubScale` 上面)。
 */
object SubStyle {

    /** 字幕大小倍率(`sub-scale`)。 */
    val scale = mutableDoubleStateOf(1.0)

    /** 竖直位置 0(顶)..150(底,>100 是压进黑边)。 */
    val position = mutableIntStateOf(100)

    /** 描边粗细(像素)。 */
    val border = mutableDoubleStateOf(3.0)

    val bold = mutableStateOf(false)

    /** 字号跟窗口走(true)还是跟片源分辨率走(false)。 */
    val scaleByWindow = mutableStateOf(true)

    /** 已经从核心层读回来过没有。没读回来之前面板不该把默认值当成用户的选择写回去。 */
    val loaded = mutableStateOf(false)

    /**
     * 从核心层读回落库的那一份。
     *
     * ★ 哨兵(scale=0 / position<0 / border<0)= 「这一项用的是默认值」,
     *   原样跳过,不覆盖上面的初值。
     */
    suspend fun load(app: AppState) {
        val r = runCatching { app.call("player.getSubStyle") }.getOrNull().obj() ?: return
        r.dbl("scale")?.takeIf { it > 0 }?.let { scale.doubleValue = it }
        r.long("position")?.takeIf { it >= 0 }?.let { position.intValue = it.toInt() }
        r.dbl("border_size")?.takeIf { it >= 0 }?.let { border.doubleValue = it }
        bold.value = r.bool("bold")
        if (r.containsKey("scale_by_window")) scaleByWindow.value = r.bool("scale_by_window")
        loaded.value = true
        pushToLibass()
    }

    /**
     * 改一项:先落库(核心层顺手设 mpv 属性),再推给 libass。
     *
     * ☠ **两边都要推。** 只落库的话 Exo 内核下一点反应都没有 —— 而那正是
     *   安卓上最常用的那条路(ASS 特效字幕走的就是它)。
     */
    suspend fun set(app: AppState, key: String, value: Any) {
        when (key) {
            "scale" -> scale.doubleValue = value as Double
            "position" -> position.intValue = value as Int
            "border_size" -> border.doubleValue = value as Double
            "bold" -> bold.value = value as Boolean
            "scale_by_window" -> scaleByWindow.value = value as Boolean
        }
        runCatching {
            app.call("player.setSubStyle", when (value) {
                is Double -> args(key to value)
                is Int -> args(key to value.toDouble())
                is Boolean -> args(key to value)
                else -> args()
            })
        }.onFailure { app.report(it) }
        pushToLibass()
    }

    /* ---- 步进与换算。拆成纯函数是为了可测 —— 这三条规则的全部内容就是这几行。 ---- */

    /** 大小一档 0.1×,夹在 mpv 认的 0.2~4.0(超出去 mpv 只会静默拒绝)。 */
    fun stepScale(cur: Double, up: Boolean): Double =
        Math.round((cur + if (up) 0.1 else -0.1).coerceIn(0.2, 4.0) * 100) / 100.0

    /** 位置一档 5:1 个单位是画面高度的 1%,一格一格挪要按几十下。 */
    fun stepPos(cur: Int, up: Boolean): Int = (cur + if (up) 5 else -5).coerceIn(0, 150)

    fun stepBorder(cur: Double, up: Boolean): Double =
        Math.round((cur + if (up) 0.5 else -0.5).coerceIn(0.0, 10.0) * 10) / 10.0

    /**
     * 文本字幕离画面底边留多少(按**画面高度**的比例)。
     *
     * ☠ **口径是「100 = 底」**,和 mpv 的 sub-pos 一致。写成 `position / 100f` 的话
     * 是反的 —— 表现是「往下拖字幕往上跑」,而两端各错一次就正好互相抵消掉,
     * 谁也发现不了。
     * ★ >100(压进画面下面的黑边)这一层表达不了,夹到 0。
     */
    fun bottomPadFraction(position: Int): Float =
        ((100 - position).coerceIn(0, 100)) / 100f

    /** libass 只认大小和位置这两项 —— 见类注释那张表。 */
    private fun pushToLibass() {
        if (!Libass.available) return
        runCatching { Native.assSetStyle(scale.doubleValue, position.intValue) }
    }
}
