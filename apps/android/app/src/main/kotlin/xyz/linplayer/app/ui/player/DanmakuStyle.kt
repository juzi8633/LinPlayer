package xyz.linplayer.app.ui.player

import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.bool
import xyz.linplayer.app.data.dbl
import xyz.linplayer.app.data.long
import xyz.linplayer.app.data.obj
import xyz.linplayer.app.ui.pages.args

/**
 * 弹幕显示设置(用户 2026-09-09 点名的九项)。
 *
 * ## 分工
 *
 * **排版在核心层**(`core/player/danmakustyle.go`:合并、分轨、排不下的丢掉),
 * **绘制在 View 层**([DanmakuLayer])。这个对象是中间那一层:读回落库的值、
 * 把改动发过去、拿回排好版的那一份交给绘制层。
 *
 * ★ 设置一改就要**重新取一次排版** —— 缩放、速度、范围、行数、合并这五项
 *   一改都会换一套分轨结果。不重取的表现是读数变了而画面没变。
 */
object DanmakuStyle {

    /** 滚动弹幕占画面高度:0.25 / 0.5 / 1.0。 */
    val area = mutableDoubleStateOf(1.0)

    /** 字号倍率 0.1..3.0。行高跟着一起缩。 */
    val scale = mutableDoubleStateOf(1.0)

    /** 不透明度 0.1..1.0。 */
    val opacity = mutableDoubleStateOf(1.0)

    /** 滚动速度倍率 0.1..3.0。 */
    val speed = mutableDoubleStateOf(1.0)

    /** 置顶 / 置底最多几行。**0 是合法值** = 这一类不显示。 */
    val topLines = mutableIntStateOf(10)
    val bottomLines = mutableIntStateOf(10)

    val merge = mutableStateOf(false)
    val bold = mutableStateOf(false)
    val heatmap = mutableStateOf(false)

    /** 弹幕总开关。和上面九项分开落库(`prefs.danmaku_enabled`)。 */
    val enabled = mutableStateOf(false)

    val loaded = mutableStateOf(false)

    /** 排好版的那一份。null = 还没灌语料。 */
    val layout = mutableStateOf<DmLayout?>(null)

    /** 重新取一次排版。灌完语料、改完设置都要调。 */
    suspend fun reloadLayout(app: AppState) {
        layout.value = parseDmLayout(
            runCatching { app.call("player.danmakuLayout") }.getOrNull().obj())
    }

    suspend fun load(app: AppState) {
        val r = runCatching { app.call("player.getDanmakuStyle") }.getOrNull().obj() ?: return
        r.dbl("area")?.let { area.doubleValue = it }
        r.dbl("scale")?.let { scale.doubleValue = it }
        r.dbl("opacity")?.let { opacity.doubleValue = it }
        r.dbl("speed")?.let { speed.doubleValue = it }
        r.long("top_lines")?.let { topLines.intValue = it.toInt() }
        r.long("bottom_lines")?.let { bottomLines.intValue = it.toInt() }
        merge.value = r.bool("merge")
        bold.value = r.bool("bold")
        heatmap.value = r.bool("heatmap")
        enabled.value = runCatching { app.call("prefs.getPrefs") }
            .getOrNull().obj().bool("danmaku_enabled")
        loaded.value = true
        reloadLayout(app)
    }

    /**
     * 改一项。
     *
     * ★ **照回来的值刷读数,不照发出去的值** —— 核心层会钳区间,
     *   照发出去的值刷的话面板上会显示一个核心层根本没接受的数,
     *   而画面上的弹幕是另一个样子。
     */
    suspend fun set(app: AppState, key: String, value: Any) {
        val r = runCatching {
            app.call("player.setDanmakuStyle", when (value) {
                is Double -> args(key to value)
                is Int -> args(key to value.toDouble())
                is Boolean -> args(key to value)
                else -> args()
            })
        }.onFailure { app.report(it) }.getOrNull().obj() ?: return
        r.dbl("area")?.let { area.doubleValue = it }
        r.dbl("scale")?.let { scale.doubleValue = it }
        r.dbl("opacity")?.let { opacity.doubleValue = it }
        r.dbl("speed")?.let { speed.doubleValue = it }
        r.long("top_lines")?.let { topLines.intValue = it.toInt() }
        r.long("bottom_lines")?.let { bottomLines.intValue = it.toInt() }
        merge.value = r.bool("merge")
        bold.value = r.bool("bold")
        heatmap.value = r.bool("heatmap")
        reloadLayout(app)
    }

    /* ---- 纯函数。拆出来是为了可测 —— 这两条规则的全部内容就是这几行。 ---- */

    /** 范围是**三档枚举**不是连续值:点一下换下一档,四分之一 → 半 → 全 → 四分之一。 */
    fun nextArea(cur: Double): Double = when {
        cur <= 0.3 -> 0.5
        cur <= 0.6 -> 1.0
        else -> 0.25
    }

    /**
     * 步进并**收掉浮点误差**:0.1 一直加会攒出 1.7000000000000002,
     * 而那个数原样发给核心层、原样显示回来,读数上就是「1.7000000000000002×」。
     */
    fun step(cur: Double, up: Boolean, by: Double, min: Double, max: Double): Double =
        Math.round((cur + if (up) by else -by).coerceIn(min, max) * 100) / 100.0

    fun areaLabel(cur: Double): String = when {
        cur <= 0.3 -> "四分之一屏"
        cur <= 0.6 -> "半屏"
        else -> "全屏"
    }
}
