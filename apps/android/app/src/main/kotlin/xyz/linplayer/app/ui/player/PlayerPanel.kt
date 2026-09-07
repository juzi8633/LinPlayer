package xyz.linplayer.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import kotlinx.coroutines.launch
import xyz.linplayer.app.data.Item
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.arr
import xyz.linplayer.app.data.bool
import xyz.linplayer.app.data.dbl
import xyz.linplayer.app.data.long
import xyz.linplayer.app.data.strList
import xyz.linplayer.app.data.obj
import xyz.linplayer.app.data.str
import xyz.linplayer.app.ui.components.Dim3
import xyz.linplayer.app.ui.components.glass
import xyz.linplayer.app.ui.components.OptRow
import xyz.linplayer.app.ui.pages.args
import xyz.linplayer.app.ui.theme.Lp
import xyz.linplayer.app.ui.theme.R
import xyz.linplayer.app.ui.theme.Sp

/** 让开底排控件和进度条的高度。改了 OSD 底排的高度就要改它,否则面板压在进度条上。 */
private val OsdClearance = 92.dp

/**
 * 播放器面板。**从侧边推入,只占屏宽 42%**,不做通栏 sheet(UI_MOBILE.md §8.1 收纳手法 4)。
 *
 * ★ 「源」把**版本 + 线路合成一个入口**:有的服务器十几个版本、三十几条线路,
 *   解法不是「给个滚动条」,是分组。
 * ★ 线路是**三态**:未探(转圈)/ 探过不通(显示「—」,**不装成 0 ms**)/ 毫秒数。
 */
@Composable
fun PlayerPanel(
    kind: String,
    itemId: String,
    exo: androidx.media3.exoplayer.ExoPlayer? = null,
    fit: VideoFit = VideoFit.Source,
    onOpen: (String) -> Unit = {},
    onFit: (VideoFit) -> Unit = {},
    onClose: () -> Unit,
) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    val c = Lp.colors

    var options by remember { mutableStateOf<List<Triple<String, String?, String>>>(emptyList()) }
    var current by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(kind, itemId) {
        loading = true; options = emptyList()
        when (kind) {
            "audio", "subtitle" -> if (exo != null) {
                /* ☠☠ **Exo 内核不走 `player.tracks`。** 那条命令问的是 mpv,
                   而 Exo 那条路上 mpv 手里根本没有这一片 —— 表现是音轨/字幕面板
                   恒空、点了也没反应,而**一句错都不报**。轨道表要问 ExoPlayer 自己。 */
                options = exoTracks(exo, kind)
                current = exoCurrent(exo, kind)
            } else {
                // ☠ `player.tracks` 返回**裸数组**,轨道类型的字段名是 `kind` 不是 `type`。
                //    两处都错的表现是音轨/字幕面板恒空,而且一句错都不报。
                val t = runCatching { app.call("player.tracks") }.getOrNull()
                val want = if (kind == "audio") "audio" else "sub"
                options = t.arr().mapNotNull {
                    val o = it.obj() ?: return@mapNotNull null
                    if (o.str("kind") != want) return@mapNotNull null
                    Triple(
                        o.str("id") ?: return@mapNotNull null,
                        o.str("lang"),
                        o.str("title") ?: o.str("lang") ?: "轨道",
                    )
                }
                current = t.arr().firstOrNull { it.obj().bool("selected") }.obj().str("id")
            }
            "source" -> {
                val m = runCatching { app.call("emby.itemMedia", args("item_id" to itemId)) }
                    .getOrNull()
                options = m.arr().mapNotNull {
                    val o = it.obj() ?: return@mapNotNull null
                    Triple(o.str("id") ?: return@mapNotNull null,
                        if (o.bool("preferred")) "推荐" else null, o.str("name") ?: "版本")
                }
            }
            "episodes" -> {
                val d = runCatching { app.call("emby.itemDetail", args("item_id" to itemId)) }
                    .getOrNull().obj()
                // ★ 季的主键叫 season_id(核心层这次补上的);Emby 的 ParentId 不在详情里
                val season = d.str("season_id")
                if (season != null) {
                    // 播放中**只拉一屏 40 条**
                    options = Item.list(runCatching {
                        app.call("emby.seasonEpisodes", args("parent_id" to season, "limit" to 40))
                    }.getOrNull()).map {
                        Triple(it.id, "${(it.runtimeSecs / 60).toInt()} 分钟",
                            "S${it.seasonNo ?: 1}E${it.episodeNo ?: 1} ${it.name}")
                    }
                    current = itemId
                }
            }
            "quality" -> {
                options = runCatching { app.call("player.shaderLevels") }.getOrNull().arr()
                    .mapNotNull {
                        val o = it.obj() ?: return@mapNotNull null
                        Triple(o.str("id") ?: return@mapNotNull null, o.str("group"),
                            o.str("name") ?: "档位")
                    }
            }
            "danmaku" -> {
                options = listOf(
                    Triple("on", null, "打开弹幕"),
                    Triple("off", null, "关闭弹幕"),
                )
                // 回显当前状态:不回显的话开关看起来永远是「两个都没选」
                current = if (runCatching { app.call("prefs.getPrefs") }
                        .getOrNull().obj().bool("danmaku_enabled")) "on" else "off"
            }
            /* 画面比例。★ 档位表由 [VideoFit] 一处定 —— 面板里再抄一遍的话,
               加一档就得改两处,而漏掉的那处不会报错,只是少一个选项。
               ☠ **只写档位名,不挂说明也不挂「片源未知」**【用户定 2026-09-07:
                 「我只是切换比例而已,这个提示对这个选项没有用处」】。
                 那行字本来是给我自己看的自检 —— 自检该进日志(`lp-exo` 已经在打了),
                 不该摆在用户每次切比例都要读一遍的地方。 */
            "ratio" -> {
                options = VideoFit.entries.map { Triple(it.name, null, it.label) }
                current = fit.name
            }
            /* 「更多」是**跳板**,不是设置项:选一条就换一个面板。
               ☠ 上一版把它当设置项走 `pick`,而 pick 的 when 里根本没有 "more"
                  这一支 —— 落到 `else -> Unit`,表现是「更多里点什么都没反应」。 */
            "more" -> {
                options = listOf(
                    Triple("ratio", null, "画面比例"),
                    Triple("source", null, "版本与线路"),
                    Triple("audio", null, "音轨"),
                    Triple("quality", null, "画质"),
                    Triple("danmaku", null, "弹幕"),
                )
            }
        }
        loading = false
    }

    val title = when (kind) {
        "source" -> "版本与线路"; "audio" -> "音轨"; "subtitle" -> "字幕"
        "episodes" -> "选集"; "quality" -> "画质"; "danmaku" -> "弹幕"
        "ratio" -> "画面比例"; else -> "更多"
    }

    Box(Modifier.fillMaxSize()) {
        /* scrim。★ OSD 在它之上(PlayerPage 的绘制顺序保证),面板开关期间上下栏一动不动。
           ★ **只压到能看出「后面那层不接受点击」的程度**(0.18):这一层的职责是
             接住面板外的点击,不是把画面调暗 —— 用户明说了面板挡画面挡得厉害。 */
        Box(Modifier.fillMaxSize().background(c.scrim.copy(alpha = .18f))
            .pointerInput(Unit) { detectTapClose(onClose) })
        /* ☠☠ **面板要小**【用户定 2026-09-07:「各个按钮的弹窗都太大了」】。
           上一版是**通高、占屏宽 46%** 的一整条侧栏 —— 切一次比例(四个选项)
           要让出半块屏幕、盖住整条进度条。现在它按内容收:贴着右下角,
           宽度固定一列,高度到多少算多少、封顶到半屏。
           ★ 贴**右下角**而不是右中:那儿离拇指最近,也不压住上面的画面主体。
           ★ 玻璃底和全站其它浮层同一块,不再自己调一套渐变。 */
        Column(
            Modifier.align(Alignment.BottomEnd)
                .safeDrawingPadding()
                .padding(end = Sp.x12, bottom = OsdClearance)
                .width(236.dp)
                .heightIn(max = 320.dp)
                .glass(R.md, solid = 1.6f)
                .padding(horizontal = Sp.x12, vertical = Sp.x10),
        ) {
            Dim3(title)
            Spacer(Modifier.height(Sp.x6))
            when {
                loading -> Dim3("正在取…")
                options.isEmpty() -> Dim3("这里没有可选项", maxLines = 2)
                else -> LazyColumn(Modifier.fillMaxWidth(), list) {
                    items(options, key = { it.first }) { (id, badge, label) ->
                        OptRow(label, {
                            if (kind == "more") onOpen(id)
                            else if (kind == "ratio") { onFit(VideoFit.of(id)); onClose() }
                            else {
                                scope.launch { pick(app, kind, id, itemId, exo) }
                                onClose()
                            }
                        }, selected = id == current, badge = badge)
                    }
                }
            }
        }
    }

    // 打开面板要**滚动到当前项**
    LaunchedEffect(options, current) {
        val i = options.indexOfFirst { it.first == current }
        if (i >= 0) list.scrollToItem(i)
    }
}

private suspend fun pick(
    app: xyz.linplayer.app.data.AppState, kind: String, id: String, itemId: String,
    exo: androidx.media3.exoplayer.ExoPlayer? = null,
) {
    if (exo != null && (kind == "audio" || kind == "subtitle")) {
        runCatching { exoPick(exo, kind, id) }.onFailure { app.report(it) }
        return
    }
    runCatching {
        when (kind) {
            "audio" -> app.call("player.setTrack", args("kind" to "audio", "id" to id))
            "subtitle" -> app.call("player.setTrack", args("kind" to "sub", "id" to id))
            "source" -> app.call("player.play", args("item_id" to itemId, "media_source_id" to id))
            "episodes" -> app.call("player.play", args("item_id" to id))
            "quality" -> app.call("player.setShaderLevel", args("level" to id))
            /* 弹幕开关走 player.setDanmakuEnabled(2026-09-06 新增)。
               ☠ **不是** danmaku.setDanmakuConfig —— 那条收的是**弹幕源清单**,
                 传 enabled 进去核心层只会报「缺少 sources」。
               打开时顺手匹配一次本片的弹幕并灌进渲染层:开关只管开关,
               取哪一集是 danmaku.* 的事,两件事不合成一条命令。 */
            "danmaku" -> {
                app.call("player.setDanmakuEnabled", args("enabled" to (id == "on")))
                if (id == "on") loadDanmakuFor(app, itemId) else
                    app.call("player.danmakuSet", argsEmptyItems())
            }
            else -> Unit
        }
    }.onFailure { app.report(it) }
}

/**
 * 匹配本片弹幕并灌进播放器。
 *
 * ★ 匹配不上**不弹错**:九成片子本来就没有弹幕,弹一次错等于骂用户一次。
 *   `danmaku.autoLoad` 分不够时返回 null,那是正常结果不是失败。
 */
private suspend fun loadDanmakuFor(app: xyz.linplayer.app.data.AppState, itemId: String) {
    val d = runCatching { app.call("emby.itemDetail", args("item_id" to itemId)) }
        .getOrNull().obj() ?: return
    // ★ autoLoad 收的是**嵌套的 input 对象**(MatchInput),不是平铺参数。
    //   平铺传过去核心层只会报「缺少 input」。
    val title = d.str("series_name") ?: d.str("name") ?: return
    val input = buildMap<String, kotlinx.serialization.json.JsonElement> {
        put("title", kotlinx.serialization.json.JsonPrimitive(title))
        d.long("episode_no")?.let {
            put("episode_no", kotlinx.serialization.json.JsonPrimitive(it))
        }
        d.long("season_no")?.let {
            put("season_no", kotlinx.serialization.json.JsonPrimitive(it))
        }
        put("genres", kotlinx.serialization.json.JsonArray(
            d.strList("genres").map { kotlinx.serialization.json.JsonPrimitive(it) }))
    }
    val items = runCatching {
        app.call("danmaku.autoLoad", kotlinx.serialization.json.JsonObject(
            mapOf("input" to kotlinx.serialization.json.JsonObject(input))))
    }.getOrNull()
    if (items == null || items is kotlinx.serialization.json.JsonNull) {
        app.toast("这一集没匹配到弹幕")
        return
    }
    runCatching {
        app.call("player.danmakuSet",
            kotlinx.serialization.json.JsonObject(mapOf("items" to items)))
    }.onFailure { app.report(it) }
}

private fun argsEmptyItems() = kotlinx.serialization.json.JsonObject(
    mapOf("items" to kotlinx.serialization.json.JsonArray(emptyList())))

private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectTapClose(onClose: () -> Unit) {
    detectTapGestures { onClose() }
}

// ---------------------------------------------------------------- Exo 的轨道表

/** 关字幕那一项的 id。用一个不可能和 `Format.id` 撞的串。 */
private const val EXO_TRACK_OFF = "lp:off"

/**
 * ExoPlayer 的音轨 / 字幕轨。
 *
 * ★ id 用 `groupIndex:trackIndex` 而不是 `Format.id`:后者**允许为 null**,
 *   而且同一个文件里可以重复 —— 拿它当主键的表现是「选了第二条,生效的是第一条」。
 * ★ 字幕多给一项「关闭字幕」:没有它的话字幕一旦打开就再也关不掉。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun exoTracks(
    exo: androidx.media3.exoplayer.ExoPlayer, kind: String,
): List<Triple<String, String?, String>> {
    val want = if (kind == "audio") androidx.media3.common.C.TRACK_TYPE_AUDIO
    else androidx.media3.common.C.TRACK_TYPE_TEXT
    val out = ArrayList<Triple<String, String?, String>>()
    if (want == androidx.media3.common.C.TRACK_TYPE_TEXT) {
        out.add(Triple(EXO_TRACK_OFF, null, "关闭字幕"))
    }
    exo.currentTracks.groups.forEachIndexed { gi, g ->
        if (g.type != want) return@forEachIndexed
        for (ti in 0 until g.length) {
            val f = g.getTrackFormat(ti)
            val name = listOfNotNull(
                f.label,
                f.language,
                // ASS 标出来:用户才知道这条是带特效的那一条
                if (Libass.isAss(f)) "特效" else null,
            ).joinToString(" · ").ifBlank { "轨道 ${gi + 1}-${ti + 1}" }
            out.add(Triple("$gi:$ti", f.language, name))
        }
    }
    return out
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun exoCurrent(exo: androidx.media3.exoplayer.ExoPlayer, kind: String): String? {
    val want = if (kind == "audio") androidx.media3.common.C.TRACK_TYPE_AUDIO
    else androidx.media3.common.C.TRACK_TYPE_TEXT
    exo.currentTracks.groups.forEachIndexed { gi, g ->
        if (g.type != want) return@forEachIndexed
        for (ti in 0 until g.length) if (g.isTrackSelected(ti)) return "$gi:$ti"
    }
    return if (want == androidx.media3.common.C.TRACK_TYPE_TEXT) EXO_TRACK_OFF else null
}

/**
 * 切轨。
 *
 * ☠ 关字幕要**同时**关掉 libass:只 disable ExoPlayer 的文本轨,
 * libass 手里那条 track 还在,画面上的特效字幕纹丝不动。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun exoPick(exo: androidx.media3.exoplayer.ExoPlayer, kind: String, id: String) {
    val text = androidx.media3.common.C.TRACK_TYPE_TEXT
    if (id == EXO_TRACK_OFF) {
        Libass.deactivate()
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(text)
            .setTrackTypeDisabled(text, true)
            .build()
        return
    }
    val gi = id.substringBefore(':').toIntOrNull() ?: return
    val ti = id.substringAfter(':').toIntOrNull() ?: return
    val g = exo.currentTracks.groups.getOrNull(gi) ?: return
    if (ti !in 0 until g.length) return
    val type = g.type
    exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
        .setTrackTypeDisabled(type, false)
        .setOverrideForType(androidx.media3.common.TrackSelectionOverride(g.mediaTrackGroup, ti))
        .build()
    // 选的是 ASS 就把 libass 接上,不是就撤掉 —— `onTracksChanged` 里那条同源判断
    if (type == text && !Libass.isAss(g.getTrackFormat(ti))) Libass.deactivate()
}
