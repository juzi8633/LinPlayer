package xyz.linplayer.app.plugin

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import xyz.linplayer.app.data.obj
import xyz.linplayer.app.data.str

/**
 * 插件的 `nav.*`(SPEC 7.9):导航栈、页面选项、入口角标都是平台的东西,核心层只转发。
 *
 * 路由名是公开契约(SPEC 20.3),两端各自把它翻成自己的路由对象 —— 手机用
 * Navigation Compose 的 [xyz.linplayer.app.ui.Route],TV 用自管栈的 TvRoute。
 */
object PluginNav {
    /** 哪一端活着由根组合登记。两端不会同时在树上。 */
    interface Host {
        /** 认识这个路由名就跳并回 true。 */
        fun push(route: String, params: JsonObject, replace: Boolean): Boolean
        fun back()
    }

    @Volatile var host: Host? = null

    /** 入口角标(D158):键是官方路由名,值是角标文字;空串 = 摘掉。 */
    val badges = mutableStateMapOf<String, String>()

    /** 当前插件页的页面选项(D219)。插件页离开时由页面自己清掉,所以不用记谁设的。 */
    val pageOptions = mutableStateOf<JsonObject?>(null)

    fun handle(op: String, args: JsonObject): JsonElement {
        val h = host ?: throw UnsupportedOperationException("界面还没起来,$op 暂时做不了")
        when (op) {
            "nav.push", "nav.replace" -> {
                val route = args.str("route") ?: ""
                if (!h.push(route, args["params"].obj() ?: JsonObject(emptyMap()), op == "nav.replace"))
                    throw UnsupportedOperationException("安卓端没有「$route」这个页面")
            }
            "nav.back" -> h.back()
            "nav.setPageOptions" -> pageOptions.value = args["options"].obj()
            "nav.setBadge" -> {
                val target = args.str("target") ?: ""
                if (target !in badgeTargets) throw UnsupportedOperationException("「$target」不是能加角标的入口")
                val text = args["badge"].let { if (it == null || it is JsonNull) "" else badgeText(it) }
                if (text.isEmpty()) badges.remove(target) else badges[target] = text
            }
        }
        return JsonNull
    }

    /** 底栏三格 + TV 导航轨八格 —— 安卓上没有侧栏,这些就是「入口」。 */
    private val badgeTargets = setOf(
        "home", "aggregate", "favorites", "search", "library", "downloads", "servers", "settings")

    /** `badge` 可以是数字、字符串或 `{text}`;数字 0 当「摘掉」。 */
    private fun badgeText(v: JsonElement): String {
        v.obj()?.let { return it.str("text") ?: "" }
        val s = runCatching { (v as kotlinx.serialization.json.JsonPrimitive).content }.getOrDefault("")
        return if (s == "0" || s == "false" || s == "null") "" else s
    }
}
