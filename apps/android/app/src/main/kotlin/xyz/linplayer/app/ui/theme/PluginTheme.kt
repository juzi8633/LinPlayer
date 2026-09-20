package xyz.linplayer.app.ui.theme

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import xyz.linplayer.app.core.CorePort
import xyz.linplayer.app.core.Logs
import xyz.linplayer.app.data.obj
import xyz.linplayer.app.data.str
import xyz.linplayer.app.tv.kit.TvC
import xyz.linplayer.app.tv.kit.TvSp
import xyz.linplayer.app.ui.pages.args

/**
 * 插件主题(SPEC 11.4,D215 D372)。手机 / TV 的主题是**数据不是代码** ——
 * Compose 运行时加载不了任意代码,所以这里把 JSON 解释成官方那套 token。
 *
 * ☠ 加载失败必须**报回核心层再用官方的**:主题坏了整个界面画不出来,
 *   那时候用户连「换回官方主题」的按钮都点不到。报过之后下次启动核心层直接给官方。
 */
object PluginTheme {
    private const val TAG = "主题"

    /** 真正叠上去的主题名。空 = 官方主题。 */
    var activeName = ""; private set

    /** 加载失败的那个主题名。取走即清:同一次启动只提示一次。 */
    private var failedName = ""

    internal fun takeFailed(): String? = failedName.ifEmpty { null }?.also { failedName = "" }

    /**
     * 主题自带的壁纸,补成和插件事件同一个形状(`plugin` + `kind`)——
     * 不补的话壁纸层要为「哪来的」分两条路走,而两条路只会分叉。
     * 壁纸插件选了就覆盖它(D441)。
     */
    var wallpaper: JsonObject? = null; private set

    /** 只给一种明暗的主题,系统切到另一种也保持它(D70)。null = 两种都给了。 */
    var forcedDark: Boolean? = null; private set

    private var numbers: Map<String, Double> = emptyMap()
    private var columns: Map<String, Int> = emptyMap()

    /** 数值 token 的主题值。没给就回 null,由官方刻度兜底。 */
    fun number(name: String): Double? = numbers[name]

    /** 海报网格列数。断点阈值和插件视口那套同一份(SPEC 7.7)。 */
    fun posterColumns(width: Int, official: Int): Int =
        columns[if (width < 600) "compact" else if (width < 1000) "medium" else "expanded"] ?: official

    /**
     * 启动时加载一次,**必须在第一次组合之前**:组合完再改 token,
     * 已经画出来的那一屏拿的还是官方值,表现是「换了主题要重启两次」。
     */
    fun loadAtStartup(core: CorePort, platform: String) {
        val t = ask(core, "plugin.activeTheme", args("platform" to platform)).obj() ?: return
        val id = t.str("plugin_id").orEmpty()
        val dir = t.str("dir").orEmpty()
        val rel = t.str("json").orEmpty()
        if (id.isEmpty() || dir.isEmpty()) return
        runCatching {
            if (rel.isEmpty()) error("这个主题没给手机 / TV 用的 theme.json")
            apply(json(java.io.File(dir, rel).readText()), platform, id)
            activeName = t.str("name") ?: id
            Logs.w(TAG, "已换上插件主题 $id")
        }.onFailure { e ->
            failedName = t.str("name") ?: id
            Logs.w(TAG, "$id 加载失败,已改用官方主题:$e")
            // 报一次核心层就记住了,下次启动不再自动用它(D372)
            ask(core, "plugin.themeFailed", args("id" to id, "detail" to e.toString()))
        }
    }

    /** 核心层卡住了宁可用官方主题开画,不把第一帧拖住。 */
    private fun ask(core: CorePort, cmd: String, a: JsonObject): JsonElement? = runBlocking {
        withTimeoutOrNull(1500) { runCatching { core.callJson(cmd, a) }.getOrNull() }
    }

    internal fun json(text: String): JsonObject =
        kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject

    /** 这一版解释得了的段落。其余的**跳过并记一行**,不算加载失败(主题坏了才回退)。 */
    private val UNDERSTOOD = setOf("\$schema", "schema", "modes", "tokens", "layout", "wallpaper")

    internal fun apply(j: JsonObject, platform: String, pluginId: String = "") {
        val modes = (j["modes"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
        if (modes.size == 1) forcedDark = modes[0] == "dark"

        val tk = j["tokens"].obj() ?: error("tokens 段缺失或不是对象")
        if (platform == "android_tv") applyTv(tokens(tk["dark"]))
        else {
            applyPhone(tokens(tk["dark"]), dark = true)
            applyPhone(tokens(tk["light"]), dark = false)
        }
        numbers = tokens(tk[if (forcedDark == false) "light" else "dark"])
            .mapNotNull { (k, v) -> v.toDoubleOrNull()?.let { k to it } }.toMap()
        applyRadius()
        applyLayout(j["layout"].obj(), platform)
        wallpaper = j["wallpaper"].obj()?.let { w ->
            val kind = if (w.str("video") != null) "video" else "image"
            JsonObject(w + mapOf(
                "plugin" to JsonPrimitive(pluginId), "kind" to JsonPrimitive(kind)))
        }

        j.keys.filterNot { it in UNDERSTOOD }.forEach {
            Logs.w(TAG, "主题的 $it 段这一端还没解释,跳过(components / motion / icons / fonts 都在这一批)")
        }
    }

    private fun tokens(e: JsonElement?): Map<String, String> =
        e.obj()?.mapNotNull { (k, v) -> ((v as? JsonPrimitive)?.content)?.let { k to it } }?.toMap().orEmpty()

    /**
     * `#RRGGBBAA` → Compose 的 ARGB。
     *
     * ☠ **alpha 在末尾**,和 `Color.parseColor` 的 `#AARRGGBB` 正好相反:
     *   按它那套解 `#0B0D12FF` 会得到一个 alpha=0x0B 的底色,表现是整屏透明发灰。
     */
    internal fun rgba(v: String): Color? {
        val h = v.removePrefix("#")
        val n = h.toLongOrNull(16) ?: return null
        return when (h.length) {
            8 -> Color(((n ushr 8) or ((n and 0xFF) shl 24)).toInt())
            6 -> Color(n.toInt() or (0xFF shl 24))
            else -> null
        }
    }

    private fun Map<String, String>.c(name: String, fallback: Color): Color =
        this[name]?.let { rgba(it) } ?: fallback

    private fun applyPhone(t: Map<String, String>, dark: Boolean) {
        if (t.isEmpty()) return
        val c = if (dark) DarkColors else LightColors
        val next = c.copy(
            bg = t.c("color.bg", c.bg), s1 = t.c("color.surface", c.s1), s2 = t.c("color.surfaceAlt", c.s2),
            line = t.c("color.line", c.line), line2 = t.c("color.lineStrong", c.line2),
            fg = t.c("color.ink", c.fg), fg2 = t.c("color.ink2", c.fg2), fg3 = t.c("color.ink3", c.fg3),
            acc = t.c("color.accent", c.acc), accDim = t.c("color.accentSoft", c.accDim),
            accFg = t.c("color.accentInk", c.accFg),
            ok = t.c("color.ok", c.ok), warn = t.c("color.warn", c.warn), bad = t.c("color.danger", c.bad),
        )
        if (dark) DarkColors = next else LightColors = next
    }

    private fun applyTv(t: Map<String, String>) {
        if (t.isEmpty()) return
        TvC.bg = t.c("color.bg", TvC.bg); TvC.rail = t.c("color.bg", TvC.rail)
        TvC.surface1 = t.c("color.surface", TvC.surface1)
        TvC.surface2 = t.c("color.surfaceAlt", TvC.surface2)
        TvC.line = t.c("color.line", TvC.line)
        TvC.fg = t.c("color.ink", TvC.fg); TvC.fg2 = t.c("color.ink2", TvC.fg2)
        TvC.fg3 = t.c("color.ink3", TvC.fg3)
        TvC.acc = t.c("color.accent", TvC.acc); TvC.accDim = t.c("color.accentSoft", TvC.accDim)
        TvC.onAcc = t.c("color.accentInk", TvC.onAcc)
        TvC.ok = t.c("color.ok", TvC.ok); TvC.warn = t.c("color.warn", TvC.warn)
        TvC.bad = t.c("color.danger", TvC.bad)
    }

    /** 圆角走同一把刻度(UI_MOBILE.md §1.3):主题只能挪这三档的值,不能加第四档。 */
    private fun applyRadius() {
        numbers["radius.small"]?.let { R.sm = it.dp() }
        numbers["radius.card"]?.let { R.md = it.dp() }
        numbers["radius.pill"]?.let { R.pill = it.dp() }
    }

    private fun Double.dp() = androidx.compose.ui.unit.Dp(toFloat())

    /**
     * 密度是**一把尺整体缩放**,不是逐个间距重定义 —— 逐个给的话主题作者
     * 漏掉一个就出现两种节奏混排,而那一眼看不出是漏了哪一个。
     */
    internal fun applyLayout(l: JsonObject?, platform: String) {
        if (l == null) return
        columns = l["posterColumns"].obj()
            ?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.content?.toIntOrNull()?.let { k to it } }
            ?.toMap().orEmpty()
        val k = when (l.str("density")) {
            "compact" -> 0.85f
            "spacious" -> 1.2f
            else -> return
        }
        if (platform == "android_tv") TvSp.scale(k) else Sp.scale(k)
    }
}

/**
 * 主题坏了的提示。**在根上弹不在加载处** —— 加载发生在第一帧之前,
 * 那时候还没有能显示 Toast 的地方。
 */
@androidx.compose.runtime.Composable
fun ThemeFailedToast() {
    val app = xyz.linplayer.app.data.LocalApp.current
    androidx.compose.runtime.LaunchedEffect(Unit) {
        PluginTheme.takeFailed()?.let {
            app.toast("$it 主题加载失败,已改用官方主题", xyz.linplayer.app.data.ToastKind.Error)
        }
    }
}
