package xyz.linplayer.app.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.linplayer.app.data.Account
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.ToastKind
import xyz.linplayer.app.data.UiPrefs
import xyz.linplayer.app.data.arr
import xyz.linplayer.app.data.bool
import xyz.linplayer.app.data.dbl
import xyz.linplayer.app.data.keepState
import xyz.linplayer.app.data.long
import xyz.linplayer.app.data.obj
import xyz.linplayer.app.data.str
import xyz.linplayer.app.data.strList
import xyz.linplayer.app.tv.kit.PanelItem
import xyz.linplayer.app.tv.kit.Skel
import xyz.linplayer.app.tv.kit.TvC
import xyz.linplayer.app.tv.kit.TvSp
import xyz.linplayer.app.tv.kit.TvText
import xyz.linplayer.app.tv.kit.TvW
import xyz.linplayer.app.tv.kit.contentArea
import xyz.linplayer.app.tv.kit.tvType
import xyz.linplayer.app.ui.pages.args
import xyz.linplayer.app.ui.pages.fmtSize
import xyz.linplayer.app.ui.pages.jsonArrayOf
import xyz.linplayer.app.ui.player.DanmakuStyle
import xyz.linplayer.app.ui.player.SubStyle

private val Cats = listOf("通用", "播放", "跳过片头片尾", "字幕与音轨", "弹幕", "网络", "同步", "存储", "关于")

/** 语言 17 项【继承旧 TV】。核心层收 Emby 的三字母码;「自动」传 **null**,不许传空串。 */
private val Langs = listOf(
    "自动" to null, "中文" to "chi", "粤语" to "yue", "日语" to "jpn", "英语" to "eng", "韩语" to "kor", "法语" to "fre",
    "德语" to "ger", "西班牙语" to "spa", "葡萄牙语" to "por", "意大利语" to "ita", "俄语" to "rus", "泰语" to "tha",
    "越南语" to "vie", "印尼语" to "ind", "阿拉伯语" to "ara", "印地语" to "hin",
)

/**
 * 设置(UI_TV.md §7.13)。**左栏分类 + 右栏设置项,一层到底,不做二级页**:遥控器每深一层就多进出两次按键。
 *
 * ★ 左栏 OK 切分类,焦点**留在左栏**;右栏按分类整体重建(否则上一类最后的焦点被记到新一类同序号的行)。
 * ★ **只画核心层真有命令的项**,TV 不支持的项直接不渲染、不做灰显【用户定 2026-09-14】。
 * ★ 每组各自加载,没回来的组画骨架行;改完即生效,失败回滚 + Toast。
 */
@Composable
fun SettingsPage() {
    val t = tvType
    val overlay = rememberOverlay()
    var cat by keepState("tv.settings.cat") { 0 }
    Box(Modifier.fillMaxSize()) {
        Row(Modifier.contentArea()) {
            Column(Modifier.width(180.dp)) {
                TvText("设置", t.headline, TvC.fg, weight = TvW.semi)
                Spacer(Modifier.height(TvSp.x8))
                Cats.forEachIndexed { i, c ->
                    PanelItem(c, selected = i == cat, check = false, modifier = Modifier.memo("set.cat.$i", initial = i == cat), onClick = { cat = i })
                }
            }
            Spacer(Modifier.width(TvSp.x24))
            Box(Modifier.width(1.dp).fillMaxHeight().background(TvC.line))
            Spacer(Modifier.width(TvSp.x16))
            Box(Modifier.weight(1f)) {
                key(cat) {
                    LazyColumn(contentPadding = PaddingValues(bottom = TvSp.x24)) {
                        when (cat) {
                            0 -> item { GeneralGroup() }
                            1 -> item { PlaybackGroup(overlay) }
                            2 -> item { SkipGroup() }
                            3 -> item { SubAudioGroup(overlay) }
                            4 -> item { DanmakuGroup(overlay) }
                            5 -> item { NetworkGroup(overlay) }
                            6 -> item { SyncGroup(overlay) }
                            7 -> item { StorageGroup(overlay) }
                            else -> item { AboutGroup(overlay) }
                        }
                    }
                }
            }
        }
        OverlayHost(overlay)
    }
}

@Composable
private fun Group(title: String) {
    TvText(title, tvType.meta, TvC.fg3, Modifier.padding(top = TvSp.x12, bottom = TvSp.x6))
}

@Composable
private fun SkelRows(n: Int = 3) {
    Column { repeat(n) { Skel(Modifier.fillMaxWidth().height(tvType.rowH).padding(vertical = TvSp.x2)) } }
}

/** 开关行:**整行一个焦点**,OK 直接切换;失败回滚 + Toast。 */
private fun toggle(scope: CoroutineScope, app: AppState, get: () -> Boolean, set: (Boolean) -> Unit, send: suspend (Boolean) -> Unit) {
    val want = !get()
    set(want)
    scope.launch { runCatching { send(want) }.onFailure { set(!want); app.report(it) } }
}

/** 取值行的侧面板:**默认焦点在当前值**,选中即关面板。 */
private fun <T> Overlay.pick(title: String, options: List<Pair<String, T>>, current: T, onPick: (T) -> Unit) {
    open {
        TvSidePanel(title, { close() }) {
            options.forEach { (label, v) ->
                PanelItem(label, selected = v == current, focused = v == current, onClick = { onPick(v); close() })
            }
        }
    }
}

// ---------------------------------------------------------------- 通用

@Composable
private fun GeneralGroup() {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var cross by remember { mutableStateOf<Boolean?>(null) }
    var companion by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        launch { cross = runCatching { app.call("account.getCrossServerResume") }.getOrNull()?.let { (it as? JsonPrimitive)?.content == "true" } }
        launch { companion = runCatching { app.call("companion.status") }.getOrNull().obj()?.bool("enabled") }
    }
    Column {
        Group("播放行为")
        cross?.let { on ->
            PanelItem("跨服务器续播", sub = "同一部片在别的服务器上看过,也从那个位置接着播", switch = on, modifier = Modifier.memo("set.cross"), onClick = {
                toggle(scope, app, { cross == true }, { cross = it }) { app.call("account.setCrossServerResume", args("enabled" to it)) }
            })
        } ?: SkelRows(1)
        Group("手机遥控")
        companion?.let { on ->
            PanelItem("手机扫码遥控", sub = "电视在局域网里开一个小网页,手机浏览器打开就是遥控器", switch = on, modifier = Modifier.memo("set.companion"), onClick = {
                toggle(scope, app, { companion == true }, { companion = it }) { app.call("companion.setEnabled", args("enabled" to it)) }
            })
            if (on) CompanionBlock(140.dp, horizontal = true)
        } ?: SkelRows(1)
    }
}

// ---------------------------------------------------------------- 播放

@Composable
private fun PlaybackGroup(overlay: Overlay) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var prefs by remember { mutableStateOf<JsonObject?>(null) }
    var shader by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    LaunchedEffect(Unit) {
        launch { prefs = runCatching { app.call("player.getPlaybackPrefs") }.getOrNull().obj() }
        launch { shader = runCatching { app.call("player.shaderLevels") }.getOrNull().arr().mapNotNull { it.obj() } }
    }
    suspend fun send(key: String, v: Any) {
        prefs = app.call("player.setPlaybackPrefs", args(key to v)).obj()?.let { p ->
            JsonObject((prefs ?: JsonObject(emptyMap())) + (key to (p[key] ?: JsonPrimitive(v.toString()))))
        } ?: prefs
    }
    fun flip(key: String, on: Boolean, value: (Boolean) -> Any = { it }) {
        val old = prefs
        prefs = JsonObject((prefs ?: JsonObject(emptyMap())) + (key to JsonPrimitive(value(!on).toString()).let {
            if (value(!on) is Boolean) JsonPrimitive(!on) else it
        }))
        scope.launch { runCatching { app.call("player.setPlaybackPrefs", args(key to value(!on))) }.onFailure { prefs = old; app.report(it) } }
    }
    val p = prefs
    Column {
        if (p == null) { SkelRows(6); return@Column }
        Group("解码")
        val hw = p.str("hwdec") != "no"
        PanelItem("硬件解码", sub = "关掉后走软解,弱一点的盒子会卡", switch = hw, modifier = Modifier.memo("set.hwdec"),
            onClick = { flip("hwdec", hw) { if (it) "auto-safe" else "no" } })
        val dv = p.bool("dolby_auto_sw")
        PanelItem("杜比视界自动软解", switch = dv, modifier = Modifier.memo("set.dv"), onClick = { flip("dolby_auto_sw", dv) })
        Group("播放")
        val speed = p.dbl("default_speed") ?: 1.0
        PanelItem("默认倍速", value = "${speed}x", chevron = true, modifier = Modifier.memo("set.speed"), onClick = {
            overlay.pick("默认倍速", listOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0, 3.0).map { "${it}x" to it }, speed) { v ->
                scope.launch { runCatching { send("default_speed", v) }.onFailure { app.report(it) } }
            }
        })
        val levels = shader.filter { it.str("id") == "off" || it.bool("will_run") || !it.containsKey("will_run") }
        if (levels.isNotEmpty()) {
            val cur = levels.firstOrNull { it.bool("selected") }
            PanelItem("画面增强", value = cur.str("name") ?: "关", chevron = true, modifier = Modifier.memo("set.shader"), onClick = {
                overlay.pick("画面增强", levels.map { (it.str("name") ?: "") to (it.str("id") ?: "") }, cur.str("id") ?: "off") { id ->
                    scope.launch {
                        val r = runCatching { app.call("player.setShaderLevel", args("level" to id)) }.onFailure { app.report(it) }.getOrNull().obj()
                        if (r.bool("reverted")) app.toast(r.str("note") ?: "这档在这台机器上跑不起来", ToastKind.Error)
                        shader = runCatching { app.call("player.shaderLevels") }.getOrNull().arr().mapNotNull { it.obj() }
                    }
                }
            })
        }
        PanelItem("默认播放内核", value = if (UiPrefs.engine.value == "exo") "ExoPlayer" else "mpv", chevron = true, modifier = Modifier.memo("set.engine"), onClick = {
            overlay.pick("默认播放内核", listOf("mpv" to "mpv", "ExoPlayer" to "exo"), UiPrefs.engine.value) { UiPrefs.setEngine(ctx, it) }
        })
        val steps = listOf(5, 10, 15, 30)
        PanelItem("快进步长", value = "${UiPrefs.tvSeekStep.value} 秒", step = true, modifier = Modifier.memo("set.step"), onStep = { d ->
            val i = (steps.indexOf(UiPrefs.tvSeekStep.value).coerceAtLeast(0) + d).coerceIn(0, steps.lastIndex)
            UiPrefs.setTv(ctx, "tv_seek_step", steps[i])
        })
        PanelItem("长按倍速", value = "${UiPrefs.tvHoldSpeed.value}x", chevron = true, modifier = Modifier.memo("set.hold"), onClick = {
            overlay.pick("长按倍速", listOf(2.0, 3.0, 4.0).map { "${it}x" to it }, UiPrefs.tvHoldSpeed.value) { UiPrefs.setTv(ctx, "tv_hold_speed", it) }
        })
        PanelItem("自动播放下一集", sub = "关掉之后播完只出下一集卡,不倒计时", switch = UiPrefs.tvAutoNext.value, modifier = Modifier.memo("set.autonext"),
            onClick = { UiPrefs.setTv(ctx, "tv_auto_next", !UiPrefs.tvAutoNext.value) })
    }
}

@Composable
private fun SkipGroup() {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var prefs by remember { mutableStateOf<JsonObject?>(null) }
    LaunchedEffect(Unit) { prefs = runCatching { app.call("player.getPlaybackPrefs") }.getOrNull().obj() }
    val p = prefs
    Column {
        if (p == null) { SkelRows(4); return@Column }
        Group("跳过片头片尾")
        listOf(
            Triple("skip_intro", "跳过片头", null), Triple("skip_outro", "跳过片尾", null),
            Triple("skip_auto", "到点自动跳过", "开着就不出按钮,直接跳"),
            Triple("skip_use_online", "服务端没有章节时联网查询", null),
        ).forEach { (k, label, sub) ->
            val on = p.bool(k)
            PanelItem(label, sub = sub, switch = on, modifier = Modifier.memo("set.$k"), onClick = {
                val old = prefs
                prefs = JsonObject(p + (k to JsonPrimitive(!on)))
                scope.launch { runCatching { app.call("player.setPlaybackPrefs", args(k to !on)) }.onFailure { prefs = old; app.report(it) } }
            })
        }
    }
}

// ---------------------------------------------------------------- 字幕与音轨

@Composable
private fun SubAudioGroup(overlay: Overlay) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var prefs by remember { mutableStateOf<JsonObject?>(null) }
    LaunchedEffect(Unit) {
        prefs = runCatching { app.call("prefs.getPrefs") }.getOrNull().obj()
        if (!SubStyle.loaded.value) SubStyle.load(app)
    }
    val p = prefs
    fun save(key: String, v: JsonElement) {
        val old = prefs
        prefs = JsonObject((prefs ?: JsonObject(emptyMap())) + (key to v))
        scope.launch { runCatching { app.call("prefs.setPrefs", JsonObject(mapOf(key to v))) }.onFailure { prefs = old; app.report(it) } }
    }
    Column {
        if (p == null) { SkelRows(5); return@Column }
        Group("默认")
        val subOn = p.bool("sub_enabled")
        PanelItem("默认开启字幕", switch = subOn, modifier = Modifier.memo("set.subon"), onClick = { save("sub_enabled", JsonPrimitive(!subOn)) })
        listOf("sub_lang" to "字幕语言", "audio_lang" to "音轨语言").forEach { (k, label) ->
            val cur = p.str(k)?.takeIf { it.isNotBlank() }
            PanelItem(label, value = Langs.firstOrNull { it.second == cur }?.first ?: cur ?: "自动", chevron = true, modifier = Modifier.memo("set.$k"), onClick = {
                overlay.pick(label, Langs, cur) { v -> save(k, v?.let { JsonPrimitive(it) } ?: JsonNull) }
            })
        }
        Group("样式")
        PanelItem("字幕字号", value = "%.1fx".format(SubStyle.scale.doubleValue), step = true, modifier = Modifier.memo("set.subscale"), onStep = { d ->
            scope.launch { SubStyle.set(app, "scale", SubStyle.stepScale(SubStyle.scale.doubleValue, d > 0)) }
        })
        PanelItem("字幕位置", value = SubStyle.position.intValue.toString(), step = true, modifier = Modifier.memo("set.subpos"), onStep = { d ->
            scope.launch { SubStyle.set(app, "position", SubStyle.stepPos(SubStyle.position.intValue, d > 0)) }
        })
    }
}

// ---------------------------------------------------------------- 弹幕

@Composable
private fun DanmakuGroup(overlay: Overlay) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var words by remember { mutableStateOf<Pair<List<String>, List<String>>?>(null) }
    LaunchedEffect(Unit) {
        DanmakuStyle.load(app)
        val b = runCatching { app.call("danmaku.getBlockwords") }.getOrNull().obj()
        words = b.strList("words") to b.strList("users")
    }
    Column {
        if (!DanmakuStyle.loaded.value) { SkelRows(5); return@Column }
        Group("显示")
        PanelItem("默认显示弹幕", switch = DanmakuStyle.enabled.value, modifier = Modifier.memo("set.dm"), onClick = {
            toggle(scope, app, { DanmakuStyle.enabled.value }, { DanmakuStyle.enabled.value = it }) {
                app.call("player.setDanmakuEnabled", args("enabled" to it))
            }
        })
        PanelItem("显示区域", value = DanmakuStyle.areaLabel(DanmakuStyle.area.doubleValue), chevron = true, modifier = Modifier.memo("set.dmarea"), onClick = {
            overlay.pick("显示区域", listOf("四分之一屏" to 0.25, "半屏" to 0.5, "全屏" to 1.0), DanmakuStyle.area.doubleValue) { v ->
                scope.launch { DanmakuStyle.set(app, "area", v) }
            }
        })
        PanelItem("字号", value = "%.1fx".format(DanmakuStyle.scale.doubleValue), step = true, modifier = Modifier.memo("set.dmscale"), onStep = { d ->
            scope.launch { DanmakuStyle.set(app, "scale", DanmakuStyle.step(DanmakuStyle.scale.doubleValue, d > 0, 0.1, 0.1, 3.0)) }
        })
        PanelItem("不透明度", value = "%.0f%%".format(DanmakuStyle.opacity.doubleValue * 100), step = true, modifier = Modifier.memo("set.dmop"), onStep = { d ->
            scope.launch { DanmakuStyle.set(app, "opacity", DanmakuStyle.step(DanmakuStyle.opacity.doubleValue, d > 0, 0.1, 0.1, 1.0)) }
        })
        PanelItem("速度", value = "%.1fx".format(DanmakuStyle.speed.doubleValue), step = true, modifier = Modifier.memo("set.dmspeed"), onStep = { d ->
            scope.launch { DanmakuStyle.set(app, "speed", DanmakuStyle.step(DanmakuStyle.speed.doubleValue, d > 0, 0.1, 0.1, 3.0)) }
        })
        Group("屏蔽")
        val w = words
        if (w == null) SkelRows(1) else PanelItem("屏蔽词", value = "${w.first.size} 条", chevron = true, modifier = Modifier.memo("set.blockwords"), onClick = {
            overlay.open { BlockwordsPanel(w, { overlay.close() }) { words = it } }
        })
    }
}

/** 屏蔽词:列表 + 新增输入框,单条长按删除。长文本在手机遥控页上更好填(§9.2)。 */
@Composable
private fun BoxScope.BlockwordsPanel(init: Pair<List<String>, List<String>>, onClose: () -> Unit, onSaved: (Pair<List<String>, List<String>>) -> Unit) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf(init.first) }
    var draft by remember { mutableStateOf("") }
    fun save(next: List<String>) {
        val old = list
        list = next
        scope.launch {
            runCatching {
                app.call("danmaku.setBlockwords", JsonObject(mapOf("words" to jsonArrayOf(next), "users" to jsonArrayOf(init.second))))
            }.onSuccess { onSaved(next to init.second) }.onFailure { list = old; app.report(it) }
        }
    }
    TvSidePanel("屏蔽词", onClose) {
        Box(Modifier.padding(horizontal = TvSp.x6)) {
            TvTextField(draft, { draft = it }, "新增一个屏蔽词", 218.dp, Modifier.memo("bw.input"), onSubmit = { v ->
                if (v.isNotBlank() && v.trim() !in list) { save(list + v.trim()); draft = "" }
            })
        }
        PanelItem("添加", focused = true, onClick = { if (draft.isNotBlank()) { save((list + draft.trim()).distinct()); draft = "" } })
        if (list.isEmpty()) PanelItem("还没有屏蔽词", enabled = false)
        list.forEach { word -> PanelItem(word, sub = "长按删除", onLongClick = { save(list - word) }) }
    }
}

// ---------------------------------------------------------------- 网络

private val ProxyTypes = listOf("不使用" to "none", "HTTP" to "http", "HTTPS" to "https", "SOCKS5" to "socks5", "SOCKS4" to "socks4")

@Composable
private fun NetworkGroup(overlay: Overlay) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var proxy by remember { mutableStateOf<JsonObject?>(null) }
    var prefetch by remember { mutableStateOf<JsonObject?>(null) }
    var preload by remember { mutableStateOf<JsonObject?>(null) }
    var accounts by remember { mutableStateOf<List<Account>>(emptyList()) }
    LaunchedEffect(Unit) {
        launch { proxy = runCatching { app.call("prefs.getProxy") }.getOrNull().obj() }
        launch { prefetch = runCatching { app.call("prefs.getPrefetchSettings") }.getOrNull().obj() }
        launch { preload = runCatching { app.call("prefs.getPreloadSettings") }.getOrNull().obj() }
        launch { accounts = Account.list(runCatching { app.call("account.listAccounts") }.getOrNull()).filter { (it.kind ?: "emby") == "emby" } }
    }
    fun saveProxy(next: JsonObject) {
        val old = proxy
        proxy = next
        scope.launch { runCatching { app.call("prefs.setProxy", JsonObject(mapOf("config" to next))) }.onFailure { proxy = old; app.report(it) } }
    }
    Column {
        Group("代理")
        val px = proxy
        if (px == null) SkelRows(2) else {
            val type = px.str("type") ?: "none"
            PanelItem("代理类型", value = ProxyTypes.firstOrNull { it.second == type }?.first ?: type, chevron = true, modifier = Modifier.memo("set.pxtype"), onClick = {
                overlay.pick("代理类型", ProxyTypes, type) { v -> saveProxy(JsonObject(px + ("type" to JsonPrimitive(v)))) }
            })
            // 代理类型为「不使用」时本组其余项隐藏
            if (type != "none") {
                ProxyField("服务器地址", px.str("host") ?: "", "set.pxhost") { saveProxy(JsonObject(px + ("host" to JsonPrimitive(it.trim())))) }
                ProxyField("端口", px.long("port")?.takeIf { it > 0 }?.toString() ?: "", "set.pxport", number = true) {
                    it.trim().toIntOrNull()?.let { n -> saveProxy(JsonObject(px + ("port" to JsonPrimitive(n)))) }
                }
                ProxyField("用户名", px.str("username") ?: "", "set.pxuser") { saveProxy(JsonObject(px + ("username" to JsonPrimitive(it)))) }
                ProxyField("密码", px.str("password") ?: "", "set.pxpass", password = true) { saveProxy(JsonObject(px + ("password" to JsonPrimitive(it)))) }
                val media = px.bool("proxy_media")
                PanelItem("播放流也走代理", switch = media, modifier = Modifier.memo("set.pxmedia"),
                    onClick = { saveProxy(JsonObject(px + ("proxy_media" to JsonPrimitive(!media)))) })
            }
        }
        Group("多线程加载")
        val pf = prefetch
        if (pf == null) SkelRows(1) else {
            val on = pf.strList("servers")
            if (accounts.isEmpty()) PanelItem("还没有 Emby 服务器", enabled = false)
            accounts.forEach { a ->
                val enabled = a.id in on
                PanelItem(a.name, sub = "多线程预取这台服务器的播放流", switch = enabled, modifier = Modifier.memo("set.pf.${a.id}"), onClick = {
                    val next = if (enabled) on - a.id else on + a.id
                    val old = prefetch
                    prefetch = JsonObject(pf + ("servers" to jsonArrayOf(next)))
                    scope.launch {
                        runCatching { app.call("prefs.setPrefetchSettings", args("servers" to jsonArrayOf(next))) }
                            .onFailure { prefetch = old; app.report(it) }
                    }
                })
            }
        }
        Group("预加载")
        val pl = preload
        if (pl == null) SkelRows(2) else {
            val on = pl.bool("enabled")
            PanelItem("预加载", sub = "进详情页就先把片头拉下来,起播更快", switch = on, modifier = Modifier.memo("set.preload"), onClick = {
                val old = preload
                preload = JsonObject(pl + ("enabled" to JsonPrimitive(!on)))
                scope.launch { runCatching { app.call("prefs.setPreloadSettings", args("enabled" to !on)) }.onFailure { preload = old; app.report(it) } }
            })
            if (on) {
                val sizes = listOf(8L, 16L, 32L, 64L, 128L)
                val cur = pl.long("head_mb") ?: 16L
                PanelItem("头部大小", value = "$cur MB", step = true, modifier = Modifier.memo("set.preloadmb"), onStep = { d ->
                    val next = sizes[(sizes.indexOfFirst { it >= cur }.coerceAtLeast(0) + d).coerceIn(0, sizes.lastIndex)]
                    val old = preload
                    preload = JsonObject(pl + ("head_mb" to JsonPrimitive(next)))
                    scope.launch { runCatching { app.call("prefs.setPreloadSettings", args("head_mb" to next)) }.onFailure { preload = old; app.report(it) } }
                })
            }
        }
    }
}

/** 文本行:右侧一个 200dp 输入框(族 E),提交时机 = 完成键或失焦 —— 每字一次 setProxy 会把半截主机名落盘。 */
@Composable
private fun ProxyField(label: String, value: String, key: String, number: Boolean = false, password: Boolean = false, onSubmit: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    Row(Modifier.fillMaxWidth().height(tvType.rowH).padding(horizontal = TvSp.x16), verticalAlignment = Alignment.CenterVertically) {
        TvText(label, tvType.body, TvC.fg2, Modifier.weight(1f))
        TvTextField(text, { text = it }, "", 200.dp, Modifier.memo(key), number = number, password = password,
            onSubmit = { if (it != value) onSubmit(it) })
    }
}

// ---------------------------------------------------------------- 同步

@Composable
private fun SyncGroup(overlay: Overlay) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val t = tvType
    // null = 还没问到;不许画成「未连接」(否则每次进页闪一下)
    var trakt by remember { mutableStateOf<JsonElement?>(null) }
    var bangumi by remember { mutableStateOf<JsonElement?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var device by remember { mutableStateOf<JsonObject?>(null) }
    var bgmToken by remember { mutableStateOf("") }
    var bgmUrl by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(reload) {
        launch { trakt = runCatching { app.call("sync.traktAccount") }.getOrElse { JsonNull } }
        launch { bangumi = runCatching { app.call("sync.bangumiAccount") }.getOrElse { JsonNull } }
        launch { bgmUrl = runCatching { app.call("sync.bangumiAuthorizeUrl") }.getOrNull().let { (it as? JsonPrimitive)?.content ?: it.obj().str("url") } }
    }
    // Trakt 设备码轮询:**间隔听服务端的 interval**,自己拍更短会被限流(「码是对的但一直连不上」)
    LaunchedEffect(device) {
        val d = device ?: return@LaunchedEffect
        var interval = (d.long("interval") ?: 5L) * 1000
        while (true) {
            delay(interval)
            val r = runCatching { app.call("sync.traktPoll", args("device_code" to (d.str("device_code") ?: ""))) }.getOrNull().obj()
            when (r.str("state")) {
                "pending" -> Unit
                "slowDown" -> interval += 5000
                "authorized" -> { device = null; reload++; app.toast("Trakt 已连接", ToastKind.Ok); return@LaunchedEffect }
                "expired" -> { device = null; app.toast("设备码过期了,再点一次连接", ToastKind.Error); return@LaunchedEffect }
                "denied" -> { device = null; app.toast("在手机上被拒绝了", ToastKind.Error); return@LaunchedEffect }
                else -> { device = null; app.toast("Trakt 连接失败", ToastKind.Error); return@LaunchedEffect }
            }
        }
    }
    fun logout(name: String, cmd: String) {
        overlay.open {
            TvConfirm("断开 $name?", "断开后不再同步观看记录。之后可以重新连接。", "断开", onCancel = { overlay.close() }, onConfirm = {
                scope.launch { runCatching { app.call(cmd) }.onSuccess { overlay.close(); reload++ }.onFailure { app.report(it) } }
            })
        }
    }
    Column {
        Group("Trakt")
        val tr = trakt
        when {
            tr == null -> SkelRows(1)
            tr is JsonObject && tr.isNotEmpty() -> {
                PanelItem("Trakt", value = tr.str("username") ?: "已连接", enabled = false)
                PanelItem("断开 Trakt", danger = true, modifier = Modifier.memo("set.trakt.out"), onClick = { logout("Trakt", "sync.traktLogout") })
            }
            else -> {
                PanelItem("连接 Trakt", sub = "电视上出一个码,在手机上输入", chevron = true, modifier = Modifier.memo("set.trakt.in"), onClick = {
                    scope.launch {
                        runCatching { app.call("sync.traktDeviceCode") }.onSuccess { device = it.obj() }.onFailure { app.report(it) }
                    }
                })
                device?.let { d ->
                    Column(Modifier.padding(start = TvSp.x16, top = TvSp.x8, bottom = TvSp.x8)) {
                        TvText("在手机上打开 ${d.str("verification_url") ?: ""}", t.body, TvC.fg2, maxLines = 2)
                        Spacer(Modifier.height(TvSp.x6))
                        TvText((d.str("user_code") ?: "").toCharArray().joinToString(" "), t.numeral, TvC.acc, weight = TvW.bold)
                    }
                }
            }
        }
        Group("Bangumi")
        val bg = bangumi
        when {
            bg == null -> SkelRows(1)
            bg is JsonObject && bg.isNotEmpty() -> {
                PanelItem("Bangumi", value = bg.str("username") ?: "已连接", enabled = false)
                PanelItem("断开 Bangumi", danger = true, modifier = Modifier.memo("set.bgm.out"), onClick = { logout("Bangumi", "sync.bangumiLogout") })
            }
            else -> {
                // 主推 Access Token:授权码 30+ 字符区分大小写,遥控器敲一次好几分钟;令牌也可以用手机遥控页填过来
                Row(Modifier.fillMaxWidth().height(t.rowH).padding(horizontal = TvSp.x16), verticalAlignment = Alignment.CenterVertically) {
                    TvText("粘贴令牌", t.body, TvC.fg2, Modifier.weight(1f))
                    TvTextField(bgmToken, { bgmToken = it }, "Access Token", 200.dp, Modifier.memo("set.bgm.token"), onSubmit = { tok ->
                        if (tok.isBlank()) return@TvTextField
                        scope.launch {
                            runCatching { app.call("sync.bangumiLoginToken", args("token" to tok.trim())) }
                                .onSuccess { bgmToken = ""; reload++; app.toast("Bangumi 已连接", ToastKind.Ok) }
                                .onFailure { app.report(it) }
                        }
                    })
                }
                // 授权链接**常驻显示在行下**,不用 Toast:几十上百字符,3 秒没了抄不完
                bgmUrl?.takeIf { it.isNotBlank() }?.let {
                    TvText("在手机上打开 $it 生成令牌", t.meta, TvC.fg3, Modifier.padding(start = TvSp.x16), maxLines = 3)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 存储 / 关于

@Composable
private fun StorageGroup(overlay: Overlay) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var size by remember { mutableStateOf<Long?>(null) }
    var root by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        launch { size = runCatching { app.call("system.cacheSize") }.getOrNull().obj().long("bytes") }
        launch { root = runCatching { app.call("system.dataPaths") }.getOrNull().obj().str("root") }
    }
    Column {
        Group("缓存")
        val s = size
        if (s == null) SkelRows(1) else PanelItem("缓存", value = fmtSize(s) ?: "0 MB", chevron = true, modifier = Modifier.memo("set.cache"), onClick = {
            overlay.open {
                TvConfirm("清空缓存?", "图片和预取缓存会被删除,之后浏览时会重新下载。", "清空", onCancel = { overlay.close() }, onConfirm = {
                    scope.launch {
                        runCatching { app.call("system.clearCache") }.onSuccess { r ->
                            overlay.close()
                            size = r.obj().long("bytes") ?: 0
                            app.toast("已清空 ${fmtSize(r.obj().long("freed_bytes")) ?: ""}".trim(), ToastKind.Ok)
                        }.onFailure { app.report(it) }
                    }
                })
            }
        })
        Group("数据")
        root?.let { PanelItem("数据目录", sub = it, enabled = false) } ?: SkelRows(1)
    }
}

@Composable
private fun AboutGroup(overlay: Overlay) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var s by remember { mutableStateOf<JsonObject?>(null) }
    var checking by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { s = runCatching { app.call("prefs.getUpdateSettings") }.getOrNull().obj() }
    fun push(channel: String, auto: Boolean) {
        val old = s
        s = JsonObject((s ?: JsonObject(emptyMap())) + mapOf("channel" to JsonPrimitive(channel), "auto_check" to JsonPrimitive(auto)))
        scope.launch {
            runCatching { app.call("prefs.setUpdateSettings", args("channel" to channel, "auto_check" to auto, "proxy" to (old.str("proxy") ?: ""))) }
                .onFailure { s = old; app.report(it) }
        }
    }
    val cur = s
    Column {
        Group("版本")
        PanelItem("当前版本", value = cur.str("current_version") ?: xyz.linplayer.app.BuildConfig.VERSION_NAME, enabled = false)
        if (cur == null) { SkelRows(2); return@Column }
        val channel = cur.str("channel") ?: "stable"
        val auto = cur.bool("auto_check")
        Group("更新")
        PanelItem("更新渠道", value = if (channel == "prerelease") "预览版" else "正式版", chevron = true, modifier = Modifier.memo("set.channel"), onClick = {
            overlay.pick("更新渠道", listOf("正式版" to "stable", "预览版" to "prerelease"), channel) { push(it, auto) }
        })
        PanelItem("自动检查更新", sub = "默认关着 —— 这是个会自己联网的行为", switch = auto, modifier = Modifier.memo("set.autocheck"), onClick = { push(channel, !auto) })
        // 三种结果分开说【继承 PC A-5】
        PanelItem(if (checking) "正在检查…" else "立即检查更新", modifier = Modifier.memo("set.check"), onClick = {
            if (checking) return@PanelItem
            checking = true
            scope.launch {
                runCatching { app.call("system.checkUpdate") }.onSuccess { r ->
                    val o = r.obj()
                    if (o.bool("has_update")) o?.get("update").obj()?.let { u -> overlay.open { UpdateDialog(u) { overlay.close() } } }
                    else app.toast("已经是最新版本(${o.str("current") ?: ""})", ToastKind.Ok)
                }.onFailure { e -> app.toast("检查失败:" + ((e as? xyz.linplayer.app.core.CoreException)?.advice ?: e.message), ToastKind.Error) }
                checking = false
            }
        })
    }
}
