package xyz.linplayer.app.ui.plugin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.serialization.json.JsonObject
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.arr
import xyz.linplayer.app.data.obj
import xyz.linplayer.app.data.str
import xyz.linplayer.app.ui.pages.args

/**
 * 搜索页上插件给的快捷动作(SPEC 6.2,D242):「在豆瓣查看」「导入这个订阅」这类。
 * 结果列表本身不动 —— 这是 D242 的原话。
 *
 * 两个壳共用取数,各画各的按钮:手机是 chip,TV 要可聚焦的那一套。
 * 核心层并发问所有插件、2 秒不回就当没给,所以这里不用再自己设超时。
 */
@Composable
fun rememberSearchActions(query: String): List<JsonObject> {
    val app = LocalApp.current
    var rows by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.isEmpty()) {
            rows = emptyList()
            return@LaunchedEffect
        }
        rows = runCatching { app.call("plugin.searchActions", args("q" to q)) }
            .getOrNull().arr().mapNotNull { it.obj() }
            // 标题或命令缺一个,这颗按钮就是点了没反应 —— 宁可不画
            .filter { !it.str("title").isNullOrEmpty() && !it.str("command").isNullOrEmpty() }
    }
    return rows
}

/** 点了就跑插件自己的命令。抛出来的错原样给用户看:插件写的是中文。 */
suspend fun runSearchAction(app: AppState, row: JsonObject) {
    val pid = row.str("plugin_id") ?: return
    val cmd = row.str("command") ?: return
    val a = buildMap<String, Any> {
        put("plugin_id", pid)
        put("command", cmd)
        // args 可有可无;给个空串的话核心层会当成一个真参数传给插件
        row["args"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.let { put("args", it) }
    }
    runCatching { app.call("plugin.runCommand", args(*a.toList().toTypedArray())) }
        .onFailure { app.report(it) }
}
