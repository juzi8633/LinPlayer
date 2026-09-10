package xyz.linplayer.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.ToastKind
import xyz.linplayer.app.data.arr
import xyz.linplayer.app.data.long
import xyz.linplayer.app.data.obj
import xyz.linplayer.app.data.str
import xyz.linplayer.app.ui.components.Dim3
import xyz.linplayer.app.ui.components.LpButton
import xyz.linplayer.app.ui.components.LpField
import xyz.linplayer.app.ui.components.OptRow
import xyz.linplayer.app.ui.components.glass
import xyz.linplayer.app.ui.pages.args
import xyz.linplayer.app.ui.theme.Lp
import xyz.linplayer.app.ui.theme.R
import xyz.linplayer.app.ui.theme.Sp

/**
 * 弹幕搜索:关键词 → **按源分组的条目** → 条目下的集数 → 挂上。
 *
 * ## 为什么是居中大弹窗
 *
 * 播放页那个面板只有 236dp 宽、贴在右下角 —— 而搜索结果是**三层**
 * (源 / 作品 / 集),一列 236dp 摊不开。用户 2026-09-09 点名:
 * 「移动端需要做一个居中的弹窗」。PC 那边是贴在画面右侧的一条窗格,
 * 同一份内容两种版式,各自按屏幕的形状来。
 *
 * ## 多源怎么显示
 *
 * **各占一组、各报各的错。** 合成一个大列表的话,一个源 429 就把整页拖成空白,
 * 而用户看到的是「搜不到」—— 核心层的 `danmaku.search` 本来就是按源分组返回的
 * (每组自带 `error`),这里原样铺开。
 */
@Composable
fun DanmakuSearchDialog(itemId: String, title: String, onClose: () -> Unit) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var keyword by remember { mutableStateOf(title) }
    /* ☠ **默认词必须是剧名,不是这一集的名字。** 播放页传下来的 title 对剧集
       往往是「第 12 集」或者单集标题 —— 拿它去弹幕源搜是**永远搜不到**,
       而用户看到的只是「都没搜到」,会以为源坏了。
       条目详情里的 series_name 才是剧名;电影没有这个字段,name 就是片名。 */
    LaunchedEffect(itemId) {
        val d = runCatching { app.call("emby.itemDetail", args("item_id" to itemId)) }
            .getOrNull().obj() ?: return@LaunchedEffect
        (d.str("series_name") ?: d.str("name"))?.takeIf { it.isNotBlank() }?.let { keyword = it }
    }
    var groups by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    /** 展开了的作品:`源id|作品id` → 集表。null = 还在取。 */
    var opened by remember { mutableStateOf<Map<String, List<JsonObject>?>>(emptyMap()) }

    suspend fun search() {
        if (keyword.isBlank()) { status = "先输入片名。"; return }
        searching = true; groups = emptyList(); opened = emptyMap()
        status = "搜索中…"
        val r = runCatching { app.call("danmaku.search", args("keyword" to keyword.trim())) }
        searching = false
        r.onFailure { status = it.message ?: "搜索失败"; return }
        val gs = r.getOrNull().arr().mapNotNull { it.obj() }
        groups = gs
        val n = gs.sumOf { it["animes"].arr().size }
        status = when {
            gs.isEmpty() -> "一个可用的弹幕源都没有 —— 去设置里加一个。"
            n == 0 -> "都没搜到。换个写法试试(原名往往比中文名好使)。"
            else -> "$n 部"
        }
    }

    suspend fun expand(sourceId: String, anime: JsonObject) {
        val key = sourceId + "|" + (anime.str("anime_id") ?: return)
        if (opened.containsKey(key)) { opened = opened - key; return }
        // 搜索结果里往往已经带着集表,带了就不再往上游打一轮
        val inline = anime["episodes"].arr().mapNotNull { it.obj() }
        if (inline.isNotEmpty()) { opened = opened + (key to inline); return }
        opened = opened + (key to null)
        val eps = runCatching {
            app.call("danmaku.episodes",
                args("source_id" to sourceId, "anime_id" to (anime.str("anime_id") ?: "")))
        }.getOrNull().arr().mapNotNull { it.obj() }
        opened = opened + (key to eps)
    }

    suspend fun attach(sourceId: String, episodeId: String, label: String) {
        status = "正在取「$label」…"
        val items = runCatching {
            app.call("danmaku.load", args("source_id" to sourceId, "episode_id" to episodeId))
        }.onFailure { app.report(it); status = "取不到这一集的弹幕" }.getOrNull() ?: return
        runCatching {
            app.call("player.danmakuSet", JsonObject(mapOf("items" to items)))
            // 手动挂上就顺手把开关打开 —— 挂完不显示的话用户会以为没成功
            if (!DanmakuStyle.enabled.value) {
                app.call("player.setDanmakuEnabled", args("enabled" to true))
                DanmakuStyle.enabled.value = true
            }
        }.onFailure { app.report(it); return }
        // 灌完必须重取排版 —— 不取的话画面上还是上一份
        DanmakuStyle.reloadLayout(app)
        app.toast("挂上 " + (items as? JsonArray)?.size + " 条弹幕", ToastKind.Ok)
        onClose()
    }

    Box(Modifier.fillMaxSize()) {
        // 点框外关掉。scrim 压深一点:这一层是模态的,底下不接受任何操作
        Box(Modifier.fillMaxSize().background(Lp.colors.scrim.copy(alpha = .55f))
            .pointerInput(Unit) { detectTapGestures { onClose() } })
        Column(
            Modifier.align(Alignment.Center)
                .safeDrawingPadding()
                .padding(horizontal = Sp.x26, vertical = Sp.x26)
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .glass(R.md, solid = 1.8f)
                .padding(Sp.x12)
                // 吞掉落在弹窗里的点击 —— 不吞的话点搜索框也会把弹窗关掉
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("弹幕搜索", Modifier.weight(1f), color = Lp.colors.fg, fontSize = 15.sp)
                LpButton("关闭", onClick = onClose)
            }
            Spacer(Modifier.height(Sp.x10))
            LpField(keyword, { keyword = it }, "片名")
            Spacer(Modifier.height(Sp.x6))
            Row(verticalAlignment = Alignment.CenterVertically) {
                LpButton(if (searching) "搜索中…" else "搜索",
                    onClick = { scope.launch { search() } })
                Spacer(Modifier.width(Sp.x10))
                Dim3(status, Modifier.weight(1f), maxLines = 2)
            }
            Spacer(Modifier.height(Sp.x10))
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                groups.forEach { g ->
                    val sid = g.str("source_id") ?: ""
                    item("h-$sid") {
                        Dim3(g.str("source_name") ?: "弹幕源",
                            Modifier.padding(top = Sp.x6, bottom = Sp.x2))
                    }
                    val err = g.str("error")
                    if (err != null) {
                        // 这个源自己挂了,**别把别的源一起判死**
                        item("e-$sid") { Dim3("这个源报错了:$err", maxLines = 3) }
                    }
                    val animes = g["animes"].arr().mapNotNull { it.obj() }
                    if (err == null && animes.isEmpty()) {
                        item("n-$sid") { Dim3("这个源没搜到。") }
                    }
                    animes.forEach { an ->
                        val aid = an.str("anime_id") ?: ""
                        val key = "$sid|$aid"
                        item("a-$key") {
                            val year = an.long("year")?.let { " · $it" } ?: ""
                            val kind = an.str("type_description")?.let { " · $it" } ?: ""
                            OptRow((an.str("anime_title") ?: "作品") + year + kind,
                                { scope.launch { expand(sid, an) } },
                                selected = opened.containsKey(key))
                        }
                        if (opened.containsKey(key)) {
                            val eps = opened[key]
                            if (eps == null) item("l-$key") { Dim3("正在取集数…") }
                            else if (eps.isEmpty()) item("z-$key") { Dim3("这部作品下面没有集。") }
                            else eps.forEach { ep ->
                                val eid = ep.str("episode_id") ?: ""
                                item("p-$key-$eid") {
                                    val label = ep.str("episode_title") ?: eid
                                    OptRow(label, { scope.launch { attach(sid, eid, label) } },
                                        m = Modifier.padding(start = Sp.x20))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
