package xyz.linplayer.app.ui.pages

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.linplayer.app.core.Logs
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.toRoute
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.ToastKind
import xyz.linplayer.app.data.arr
import xyz.linplayer.app.data.strList
import xyz.linplayer.app.data.bool
import xyz.linplayer.app.data.long
import xyz.linplayer.app.data.obj
import xyz.linplayer.app.data.str
import xyz.linplayer.app.ui.Route
import xyz.linplayer.app.ui.components.Dim3
import xyz.linplayer.app.ui.components.EmptyState
import xyz.linplayer.app.ui.components.Hairline
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import xyz.linplayer.app.ui.components.LpButton
import xyz.linplayer.app.ui.components.LpField
import xyz.linplayer.app.ui.components.LpCell
import xyz.linplayer.app.ui.components.LpScaffold
import xyz.linplayer.app.ui.components.Panel
import xyz.linplayer.app.ui.components.SegRow
import xyz.linplayer.app.ui.components.StepperRow
import xyz.linplayer.app.ui.components.rememberScrolled
import xyz.linplayer.app.ui.theme.LpIcons
import xyz.linplayer.app.ui.theme.Lp
import xyz.linplayer.app.ui.theme.Sp

/**
 * 设置(U1.15)。**一级列表 + 二级页**(手机没有主从两栏的宽度)。
 *
 * ★ 交互口径:**改完即生效、零保存按钮、越界让核心层拒绝、失败回滚**(UI_MOBILE.md §6.2)。
 * ★ 手机端比 PC 少一项:**快捷键**(没有键盘)。多的没有。
 * ★ **`unsupported` 里的命令,对应入口在启动时就不画** —— 不要等点了才 E_UNSUPPORTED。
 * ☠ **只列已经做好的**【用户定 2026-09-06】。原来还有弹幕 / 预加载 / 代理 /
 *   跨服续播 / Trakt·Bangumi 五条,点进去是一张「把核心层返回的键原样列出来」的表 ——
 *   开关拨了不落库、数值不能改。那不是「做了一半」,那是**一个装成功能的入口**:
 *   用户点进去、拨一下、以为设上了。宁可不列。要做的时候各自补一个真面板再挂回来。
 */
@Composable
fun SettingsPage(nav: NavController) {
    val list = rememberLazyListState()

    LpScaffold("设置", onBack = { nav.popBackStack() }, scrolled = rememberScrolled(list)) { pad ->
        LazyColumn(Modifier.fillMaxSize(), list, contentPadding = pad) {
            item("g1") { GroupLabel("通用") }
            item("p1") {
                Panel(Modifier.padding(horizontal = Sp.x16)) {
                    LpCell("外观", icon = LpIcons.image) { nav.navigate(Route.SettingsSub("appearance")) }
                    Hairline()
                    LpCell("播放器", icon = LpIcons.play) { nav.navigate(Route.SettingsSub("player")) }
                    Hairline()
                    LpCell("mpv 配置", icon = LpIcons.file) { nav.navigate(Route.SettingsSub("mpvconf")) }
                    Hairline()
                    // 弹幕源和屏蔽词跟内核无关,Exo 下也照样能加(播放页那个入口才分内核)
                    LpCell("弹幕", icon = LpIcons.danmaku) { nav.navigate(Route.SettingsSub("danmaku")) }
                    Hairline()
                    LpCell("截屏", icon = LpIcons.camera) { nav.navigate(Route.SettingsSub("shot")) }
                }
            }
            item("g2") { GroupLabel("网络") }
            item("p2") {
                Panel(Modifier.padding(horizontal = Sp.x16)) {
                    LpCell("多线程加载", icon = LpIcons.cloud) { nav.navigate(Route.SettingsSub("prefetch")) }
                }
            }
            item("g4") { GroupLabel("其它") }
            item("p4") {
                Panel(Modifier.padding(horizontal = Sp.x16)) {
                    // 「已屏蔽的内容」是**隐藏类功能的集中解除列表** ——
                    // 没有它的话屏蔽了就再也解除不了
                    LpCell("已屏蔽的内容", icon = LpIcons.lock) {
                        nav.navigate(Route.SettingsSub("blocked"))
                    }
                    Hairline()
                    // 和 PC 端读写同一份文件格式(docs/backup-format.md)
                    LpCell("备份与还原", icon = LpIcons.folder) {
                        nav.navigate(Route.SettingsSub("backup"))
                    }
                    Hairline()
                    LpCell("插件", icon = LpIcons.plugin) { nav.navigate(Route.Plugins) }
                    Hairline()
                    LpCell("文件浏览", icon = LpIcons.folder) { nav.navigate(Route.Browse) }
                    Hairline()
                    LpCell("存储与数据目录", icon = LpIcons.file) { nav.navigate(Route.SettingsSub("storage")) }
                    Hairline()
                    LpCell("关于", icon = LpIcons.info) { nav.navigate(Route.SettingsSub("about")) }
                }
            }
            item("tail") { Spacer(Modifier.height(Sp.x34)) }
        }
    }
}

@Composable
private fun GroupLabel(t: String) =
    Text(t, Modifier.padding(start = Sp.x26, top = Sp.x20, bottom = Sp.x8),
        color = Lp.colors.fg3, fontSize = 12.sp)

/** 设置二级页。各面板**进入时各自拉自己的配置**;同一面板里的多个请求**必须并发**。 */
@Composable
fun SettingsSubPage(nav: NavController, entry: NavBackStackEntry) {
    val route = entry.toRoute<Route.SettingsSub>()
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()

    val title = when (route.group) {
        "appearance" -> "外观"; "player" -> "播放器"; "shot" -> "截屏"
        "mpvconf" -> "mpv 配置"; "danmaku" -> "弹幕"
        "backup" -> "备份与还原"
        "prefetch" -> "多线程加载"
        "blocked" -> "已屏蔽的内容"; "storage" -> "存储与数据目录"; else -> "关于"
    }

    LpScaffold(title, subtitle = "设置", onBack = { nav.popBackStack() },
        scrolled = rememberScrolled(list)) { pad ->
        LazyColumn(Modifier.fillMaxSize(), list, contentPadding = pad) {
            item("body") {
                when (route.group) {
                    "appearance" -> AppearancePanel()
                    "player" -> PlayerPrefsPanel()
                    "shot" -> ShotPanel()
                    "mpvconf" -> MpvConfPanel()
                    "danmaku" -> DanmakuSettingsPanel()
                    "backup" -> BackupPanel()
                    "prefetch" -> PrefetchPanel()
                    "blocked" -> BlockedPanel()
                    "storage" -> StoragePanel()
                    else -> AboutPanel()
                }
            }
            item("tail") { Spacer(Modifier.height(Sp.x34)) }
        }
    }
}

/**
 * 外观。
 *
 * ★ 主题走 [UiPrefs](本机 SharedPreferences),**不走核心层** ——
 *   `prefs.setPrefs` 只认 `audio_lang` / `sub_lang` / `sub_enabled`,
 *   根本没有 theme 这一项。原来往它塞 `theme` 的写法是**一个永远不生效的开关**:
 *   核心层照常返回成功,配置里什么都没变。这类「设了没反应」是本仓库最难查的一种。
 *   而且深浅色本来就不该跨设备同步 —— 手机上强制深色不代表电视上也要。
 */
@Composable
private fun AppearancePanel() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val theme = when (xyz.linplayer.app.data.UiPrefs.theme.value) {
        "dark" -> "深色"; "light" -> "浅色"; else -> "跟随系统"
    }
    val app = LocalApp.current
    val font = xyz.linplayer.app.data.UiPrefs.uiFont.value
    /* 字体导入【用户定 2026-09-08】。
       ★ 文件类型过滤放到最宽,不按字体 MIME 筛:实测各家文件管理器给 .ttf 的
         MIME 五花八门(application/octet-stream 最常见),按字体类型筛的表现是
         「文件选择器里一个字体都看不见」—— 一个打不开的入口。
         是不是真字体由复制完那次 createFromFile 判。 */
    val pick = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val path = importFont(ctx, uri)
        if (path == null) app.toast("这个文件不是能用的字体", ToastKind.Error)
        else {
            xyz.linplayer.app.data.UiPrefs.setFont(ctx, path)
            app.toast("字体已换", ToastKind.Ok)
        }
    }
    Panel(Modifier.padding(Sp.x16)) {
        SegRow("主题", listOf("跟随系统", "深色", "浅色"), theme, { v ->
            xyz.linplayer.app.data.UiPrefs.setTheme(ctx, when (v) {
                "深色" -> "dark"; "浅色" -> "light"; else -> "system"
            })
        }, sub = "深浅两套都调过。跟随系统时晚上自动变暗;这一项只影响这台设备")
        Hairline()
        LpCell(
            "界面字体",
            sub = if (font.isBlank()) "系统默认。选一个 .ttf / .otf 换掉全局字体"
            else "已换成 " + font.substringAfterLast('/'),
            onClick = { pick.launch(arrayOf("*/*")) },
        )
        // ★ 没换过就不画「恢复默认」—— 一个点了什么都不会发生的按钮
        if (font.isNotBlank()) {
            Hairline()
            LpCell("恢复默认字体", arrow = false, onClick = {
                xyz.linplayer.app.data.UiPrefs.setFont(ctx, "")
                clearFonts(ctx)
            })
        }
    }
}

/**
 * 把选中的字体复制进应用私有目录并返回落点。不是字体就返回 null。
 *
 * ☠ **必须复制一份。** SAF 给的 Uri 重启之后多半就没权限了,而字体是每次冷启动
 *   第一帧就要读的东西 —— 存 Uri 的表现是「今天好好的,明天开机字体没了」。
 * ☠ 文件名带时间戳:覆盖同一个路径的话,偏好里那个字符串没变,
 *   界面上那层 `remember(path)` 不会重算 —— 换了字体却一点变化都没有。
 * ★ 复制完当场 `createFromFile` 验一次。不验的话用户选了张图片进来,
 *   得到的是「设置显示已换、界面还是老样子」。
 */
private fun importFont(ctx: android.content.Context, uri: android.net.Uri): String? = runCatching {
    clearFonts(ctx)
    val dst = java.io.File(ctx.filesDir, "ui-font-" + System.currentTimeMillis() + ".ttf")
    ctx.contentResolver.openInputStream(uri)!!.use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
    if (android.graphics.Typeface.createFromFile(dst) == null) {
        dst.delete()
        return null
    }
    dst.absolutePath
}.getOrNull()

/** 旧字体不留 —— 每换一次留一份的话,私有目录里会攒一堆几十 MB 的中文字体。 */
private fun clearFonts(ctx: android.content.Context) {
    ctx.filesDir.listFiles { f -> f.name.startsWith("ui-font-") }?.forEach { it.delete() }
}

@Composable
private fun PlayerPrefsPanel() {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var prefs by remember { mutableStateOf<JsonObject?>(null) }
    LaunchedEffect(Unit) {
        prefs = runCatching { app.call("player.getPlaybackPrefs") }.getOrNull().obj()
    }
    /* ☠☠ **改完必须把新值写回本地这份 `prefs`。** 开关是**受控**控件 ——
       它显示的永远是 `prefs` 里的那个值。上一版只发命令不回填,于是每个开关
       拨过去又弹回来,用户看到的就是「三个按钮点不开」(2026-09-07 原话)。
       乐观更新 + 失败回滚,和多线程加载那一页同一套写法。 */
    fun flip(key: String, v: Boolean) {
        val before = prefs
        prefs = patch(before, key, JsonPrimitive(v))
        scope.launch {
            runCatching { app.call("player.setPlaybackPrefs", args(key to v)) }
                .onSuccess { r -> r.obj()?.let { prefs = it } }
                .onFailure { prefs = before; app.report(it) }
        }
    }
    fun pick(key: String, v: String) {
        val before = prefs
        prefs = patch(before, key, JsonPrimitive(v))
        scope.launch {
            runCatching { app.call("player.setPlaybackPrefs", args(key to v)) }
                .onSuccess { r -> r.obj()?.let { prefs = it } }
                .onFailure { prefs = before; app.report(it) }
        }
    }

    val ctx = androidx.compose.ui.platform.LocalContext.current
    val short = if (xyz.linplayer.app.data.UiPrefs.engine.value == "exo") "ExoPlayer" else "mpv"
    val long = if (short == "mpv") "ExoPlayer" else "mpv"
    Panel(Modifier.padding(Sp.x16)) {
        /* 播放键【用户定 2026-09-08:「短按 MPV、长按 EXO,允许调换位置」】。
           ★ 只给**短按**一个开关,长按恒是另一个 —— 两个各自能选的话会出现
             「短按长按都是 mpv」,那时长按就是坏的,而界面上看不出来。
           ★ 内核跟着**这一次起播**走,不改全局:长按试一次不该把设置也改掉。
           ★ 播放中不换内核 —— 当场换等于拆掉解码器再重建,seek 位置、上报会话、
             Surface 三样全要重来,为一个一年按一次的开关背这套复杂度不值。 */
        SegRow("播放键短按", listOf("mpv", "ExoPlayer"), short, { v ->
            xyz.linplayer.app.data.UiPrefs.setEngine(ctx, if (v == "ExoPlayer") "exo" else "mpv")
        }, sub = "长按播放键用另一个内核(现在是 " + long + ")。" +
            "mpv 认的格式多、字幕全;ExoPlayer 走安卓自带解码,更省电也更稳")
        Hairline()
        /* ★ 这一栏只放**核心层真的读**的那几项。上一版的「后台播放」「播完自动下一集」
           在核心层里连字段都没有:拨了返回成功、配置一个字没变,而且下次进来还是关着。
           不生效的选项直接删,不摆在界面上(用户 2026-09-04 的口径)。 */
        SegRow("硬件解码", listOf("自动", "关闭"),
            if (prefs.str("hwdec") == "no") "关闭" else "自动",
            { v -> pick("hwdec", if (v == "关闭") "no" else "auto-safe") },
            sub = "关掉更费电,但少数机型的花屏、绿屏只能靠它")
        Hairline()
        LpCell("杜比视界自动软解", sub = "DoVi 片源走硬解常见偏色,自动切软解画面才是对的",
            switch = prefs.bool("dolby_auto_sw"), onSwitch = { v -> flip("dolby_auto_sw", v) })
        Hairline()
        LpCell("跳过片头", switch = prefs.bool("skip_intro"),
            onSwitch = { v -> flip("skip_intro", v) })
        Hairline()
        LpCell("跳过片尾", switch = prefs.bool("skip_outro"),
            onSwitch = { v -> flip("skip_outro", v) })
        Hairline()
        LpCell("到了就自己跳", sub = "关着的话只弹一个「跳过」按钮,由你点",
            switch = prefs.bool("skip_auto"), onSwitch = { v -> flip("skip_auto", v) })
    }
}

/** 把一个键就地换掉,别的原样留着。乐观更新要的就是这一步。 */
private fun patch(o: JsonObject?, key: String, v: JsonPrimitive): JsonObject =
    JsonObject((o ?: JsonObject(emptyMap())).toMutableMap().apply { put(key, v) })

/**
 * mpv 配置【用户定 2026-09-08:「允许导入用户自己的 mpv.conf」】。
 *
 * ★ **只对 mpv 内核有效**,这句话必须写在界面上 —— 用 ExoPlayer 的人导入完
 *   什么都不会发生,不说清就是一个「设了没反应」的入口。
 * ★ 里面几行会被我们摘掉(vo / wid / config-dir 那几个:它们决定画面往哪儿画,
 *   放过去就是一片黑还不报错)。核心层会把摘掉的行写进日志。
 */
/**
 * 备份与还原(用户 2026-09-08)。
 *
 * ★ 和 PC 端**读写同一份文件**:格式、加密、合并规则全在核心层
 * (`prefs.backupExport` / `backupImport`),两端只负责挑文件。
 * 各写一份的话「互通」这件事就没有验收点了。
 * ★ 文件也能交给第三方播放器 —— 容器就是 Richasy/Rodel 的 CommonConfig,
 * 说明在 `docs/backup-format.md`。
 *
 * ☠ **导出必须让用户自己挑位置。** 写进应用私有目录再弹一句「已导出」的话,
 * 那个目录任何文件管理器都进不去 —— 用户看见成功提示、然后什么也拿不到
 * (导出日志那一栏踩过这一跤)。
 */
@Composable
private fun BackupPanel() {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var withAccounts by remember { mutableStateOf(true) }
    var withSettings by remember { mutableStateOf(true) }
    var note by remember { mutableStateOf<String?>(null) }

    val save = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val r = app.call("prefs.backupExport", args(
                    "accounts" to withAccounts, "settings" to withSettings)).obj()
                val text = r.str("content") ?: error("核心层没有给出内容")
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                }
                note = "已导出 ${r.long("bytes") ?: 0} 字节。" + (r.str("warning") ?: "")
            }.onSuccess { app.toast("备份已导出", ToastKind.Ok) }.onFailure { app.report(it) }
        }
    }

    val open = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                /* 备份文件几十 KB 量级,整份读进来没问题;但还是要设上限 ——
                   用户选错一个几百 MB 的文件时,不该是 OOM 崩掉。 */
                val text = withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)?.use {
                        String(it.readBytes().let { b ->
                            if (b.size > 4 * 1024 * 1024) error("这个文件太大,不像备份文件") else b
                        })
                    }
                } ?: error("读不出这个文件")
                // 先看清楚再写:还原是不可逆的,选错一个文件只能一台一台删回去
                val pv = app.call("prefs.backupPreview", args("content" to text)).obj()
                val r = app.call("prefs.backupImport", args(
                    "content" to text,
                    "accounts" to withAccounts, "settings" to withSettings)).obj()
                note = "这份备份来自 ${pv.str("from") ?: "?"};还原 ${r.long("imported") ?: 0} 台," +
                    "现在共 ${r.long("total") ?: 0} 台。" +
                    if (r.bool("settings_restored")) "软件设置已还原,重启后全部生效。"
                    else "这份备份里没有软件设置。"
            }.onSuccess { app.toast("已还原", ToastKind.Ok) }.onFailure { app.report(it) }
        }
    }

    Panel(Modifier.padding(Sp.x16)) {
        LpCell("包含服务器地址与账号密码", value = if (withAccounts) "是" else "否",
            onClick = { withAccounts = !withAccounts })
        Hairline()
        LpCell("包含软件设置", value = if (withSettings) "是" else "否",
            onClick = { withSettings = !withSettings })
        Hairline()
        LpCell("导出到文件", sub = "选个位置存下来,PC 端能直接读", onClick = {
            save.launch("LinPlayer-备份-" + System.currentTimeMillis() + ".lpbak")
        })
        Hairline()
        LpCell("从文件还原", sub = "合并:这台机器上原有的服务器保留", onClick = {
            open.launch(arrayOf("*/*"))
        })
        Hairline()
        // ☠ 这句必须显眼:备份文件里带着 token 和密码,加密只是混淆级(密钥随文件走)
        LpCell("勾了账号的备份文件里有你所有服务器的登录凭据,只做了混淆,别公开分享。",
            arrow = false)
        note?.let { Hairline(); LpCell(it, arrow = false) }
    }
}

/**
 * 弹幕源与屏蔽词。
 *
 * ★ 这一整组以前**一个入口都没有**:核心层十六条 `danmaku.*` 里只有 `autoLoad`
 *   被调过 —— 加源、看官方源为什么不可用、屏蔽词,三件事在两端都没有界面。
 * ★ 屏蔽词落在核心层而不是各端各存一份:它要跟着「备份与还原」走
 *   (`docs/backup-format.md` 里 prefs 是整块透传的),而且自动加载和手动搜索
 *   必须用同一份。
 */
@Composable
private fun DanmakuSettingsPanel() {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var official by remember { mutableStateOf<JsonObject?>(null) }
    var sources by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var words by remember { mutableStateOf("") }
    var userCount by remember { mutableStateOf(0) }
    var newName by remember { mutableStateOf("") }
    var newUrl by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(reload) {
        // 三个请求互不依赖 —— 串起来的话这一页要等三个往返才画得出来
        official = runCatching { app.call("danmaku.getOfficialDanmaku") }.getOrNull().obj()
        sources = runCatching { app.call("danmaku.getDanmakuConfig") }.getOrNull().arr()
            .mapNotNull { it.obj() }.filter { !it.bool("official") }
        val bw = runCatching { app.call("danmaku.getBlockwords") }.getOrNull().obj()
        words = bw?.strList("words").orEmpty().joinToString("\n")
        userCount = bw?.strList("users").orEmpty().size
    }

    suspend fun saveSources(list: List<JsonObject>) {
        runCatching {
            app.call("danmaku.setDanmakuConfig",
                JsonObject(mapOf("sources" to kotlinx.serialization.json.JsonArray(list))))
        }.onFailure { app.report(it) }
        reload++
    }

    val importXml = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val xml = withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)?.use {
                        val b = it.readBytes()
                        if (b.size > 8 * 1024 * 1024) error("这个文件太大,不像屏蔽表") else String(b)
                    }
                } ?: error("读不出这个文件")
                // 合并落库由核心层做 —— 两端各写一份合并逻辑,漏掉去重的那边会越导越长
                val r = app.call("danmaku.importBlocklist", args("xml" to xml)).obj()
                note = "现在共 ${r.long("total_words") ?: 0} 个词、" +
                    "${r.long("total_users") ?: 0} 个用户" +
                    "(这份文件里跳过 ${r.long("skipped_count") ?: 0} 条没启用的)。"
                reload++
            }.onFailure { app.report(it) }
        }
    }

    Column {
        Panel(Modifier.padding(Sp.x16)) {
            // ★ 「没有」和「坏了」是两件事,核心层把原因写清楚了,原样转给用户
            LpCell(
                if (official.bool("enabled")) "官方源可用"
                else "官方源不可用",
                sub = if (official.bool("enabled")) official.str("name")
                else official.str("reason") ?: "正在查…",
                arrow = false,
            )
        }
        GroupLabel("自建源")
        Panel(Modifier.padding(horizontal = Sp.x16)) {
            if (sources.isEmpty()) LpCell("还没有自建源。只用官方源也能看。", arrow = false)
            sources.forEachIndexed { i, src ->
                if (i > 0) Hairline()
                LpCell(src.str("name") ?: "(没名字)", sub = src.str("api_url"),
                    value = "删除", arrow = false, onClick = {
                        scope.launch { saveSources(sources.filterIndexed { j, _ -> j != i }) }
                    })
            }
        }
        Column(Modifier.padding(Sp.x16)) {
            LpField(newName, { newName = it }, "名称(可留空)")
            Spacer(Modifier.height(Sp.x6))
            LpField(newUrl, { newUrl = it }, "接口地址,粘贴整条即可")
            Spacer(Modifier.height(Sp.x6))
            // ★ 鉴权方式**不让用户选** —— 他也不知道什么是 pathToken。
            //   核心层从地址里推,推错了也比给一个四选一的下拉框强:那个框选错了
            //   同样不报错,只是搜不到。
            LpButton("添加", onClick = {
                if (newUrl.isBlank()) return@LpButton
                scope.launch {
                    saveSources(sources + JsonObject(mapOf(
                        "id" to JsonPrimitive("u" + System.currentTimeMillis()),
                        "name" to JsonPrimitive(newName.ifBlank { "自建源" }),
                        "api_url" to JsonPrimitive(newUrl.trim()),
                    )))
                    newName = ""; newUrl = ""
                }
            })
        }
        GroupLabel("屏蔽词")
        Column(Modifier.padding(Sp.x16)) {
            Dim3("一行一个。命中的弹幕直接不上屏,自动加载和手动搜索都算。", maxLines = 2)
            Spacer(Modifier.height(Sp.x6))
            LpField(words, { words = it }, "一行一个词", lines = 5)
            Spacer(Modifier.height(Sp.x6))
            Row {
                LpButton("保存屏蔽词", onClick = {
                    scope.launch {
                        val ws = words.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                        runCatching {
                            app.call("danmaku.setBlockwords", JsonObject(mapOf(
                                "words" to kotlinx.serialization.json.JsonArray(ws.map { JsonPrimitive(it) }))))
                        }.onSuccess { note = "已保存 ${ws.size} 个屏蔽词。下一次加载弹幕时生效。" }
                            .onFailure { app.report(it) }
                    }
                })
                Spacer(Modifier.width(Sp.x10))
                LpButton("导入弹弹Play 屏蔽表", kind = xyz.linplayer.app.ui.components.BtnKind.Secondary,
                    onClick = { importXml.launch(arrayOf("*/*")) })
            }
        }
        Panel(Modifier.padding(Sp.x16)) {
            LpCell("屏蔽用户 $userCount 个", sub = "只能从弹弹Play 的屏蔽表导入", arrow = false)
            note?.let { Hairline(); LpCell(it, arrow = false) }
        }
    }
}

@Composable
private fun MpvConfPanel() {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var conf by remember { mutableStateOf<JsonObject?>(null) }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(reload) {
        conf = runCatching { app.call("player.getMpvConf") }.getOrNull().obj()
    }
    val text = conf.str("text").orEmpty()
    val active = conf.bool("active")

    suspend fun push(body: String) {
        runCatching { app.call("player.setMpvConf", args("text" to body)) }
            .onSuccess { r -> conf = r.obj() }
            .onFailure { app.report(it) }
    }

    val pick = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val body = withContext(Dispatchers.IO) {
                runCatching {
                    ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                }.getOrNull()
            }
            // 256KB 封顶:mpv.conf 再长也到不了,到了多半是选错了文件
            if (body == null || body.size > (256 shl 10)) {
                app.toast("这个文件读不了,或者根本不是配置文件", ToastKind.Error)
                return@launch
            }
            push(String(body, Charsets.UTF_8))
            app.toast("已导入,下次起播生效", ToastKind.Ok)
        }
    }

    Panel(Modifier.padding(Sp.x16)) {
        LpCell(
            "当前配置",
            sub = if (active) "已导入 · " + text.lineSequence().count() + " 行" else "没有导入过",
            arrow = false,
        )
        Hairline()
        LpCell("导入 mpv.conf", sub = "选一个文本文件;换成新的会整份覆盖",
            onClick = { pick.launch(arrayOf("*/*")) })
        if (active) {
            Hairline()
            LpCell("清除", sub = "删掉配置,回到出厂状态", arrow = false, onClick = {
                scope.launch { push("") }
            })
        }
        Hairline()
        LpCell("只对 mpv 内核有效", sub = "ExoPlayer 走安卓自带解码,不读这份配置;" +
            "改动要退出当前播放再进才生效", arrow = false)
    }
    if (active && text.isNotBlank()) Panel(Modifier.padding(horizontal = Sp.x16)) {
        Text(text.lineSequence().take(20).joinToString("\n"),
            Modifier.padding(Sp.x12), color = Lp.colors.fg2, fontSize = 12.sp)
    }
}

/**
 * 截屏【用户定 2026-09-07】。
 *
 * ★ 这四项都存在 [UiPrefs](本机 SharedPreferences),**不走核心层** ——
 *   截屏整条路(PixelCopy 读回当前帧 → 叠字 → 写相册)都在 Kotlin 这一侧,
 *   核心层没有消费点。往 `prefs.setPrefs` 里塞它们只会得到一个永远不生效的开关,
 *   而那是本仓库最难查的一类 bug(外观页那一条就是这么栽的)。
 * ★ 位置只给四个角,不给自由坐标:一个能拖的水印会被拖到画面正中间。
 */
@Composable
private fun ShotPanel() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val p = xyz.linplayer.app.data.UiPrefs
    Panel(Modifier.padding(Sp.x16)) {
        LpCell("截屏保存到", sub = "相册的 Pictures/LinPlayer;安卓 9 及以下落到应用目录",
            arrow = false)
        Hairline()
        LpCell("叠加系统时间", switch = p.shotTime.value,
            onSwitch = { v -> p.setShotFlag(ctx, xyz.linplayer.app.data.UiPrefs.K_SHOT_TIME, v) })
        if (p.shotTime.value) SegRow("时间的位置", CORNER_LABELS, cornerLabel(p.shotTimePos.value),
            { v -> p.setShotPos(ctx, xyz.linplayer.app.data.UiPrefs.K_SHOT_TIME_POS, cornerCode(v)) })
        Hairline()
        LpCell("叠加条目艺术字", sub = "这部片的片名艺术字(Emby 的 Logo 图),没有就不叠",
            switch = p.shotLogo.value,
            onSwitch = { v -> p.setShotFlag(ctx, xyz.linplayer.app.data.UiPrefs.K_SHOT_LOGO, v) })
        if (p.shotLogo.value) SegRow("艺术字的位置", CORNER_LABELS, cornerLabel(p.shotLogoPos.value),
            { v -> p.setShotPos(ctx, xyz.linplayer.app.data.UiPrefs.K_SHOT_LOGO_POS, cornerCode(v)) })
    }
}

/**
 * 四个角。**存的是字母码,显示的是中文** —— 存中文的话改一次文案就把用户已有的设置弄丢了。
 *
 * ☠ 两个方向必须**互为反函数**。错开一格的表现是:用户选「右下」,存进去的是别的角,
 *   下次进设置页显示回「左上」—— 而这两个 `when` 各自看都完全正常,一句错都不报。
 *   `LogicTest` 拿这四个标签来回走一遍钉住它。
 */
internal val CORNER_LABELS = listOf("左上", "右上", "左下", "右下")

internal fun cornerLabel(code: String) = when (code) {
    "tr" -> "右上"; "bl" -> "左下"; "br" -> "右下"; else -> "左上"
}

internal fun cornerCode(label: String) = when (label) {
    "右上" -> "tr"; "左下" -> "bl"; "右下" -> "br"; else -> "tl"
}

/**
 * 多线程加载。
 *
 * ★ 它**不是一个全局开关**:核心层存的是一张「对哪几台服务器开」的清单
 *   (`settings.servers`)。所以这里的开关 = 把**当前服务器**放进 / 移出那张表。
 *   原来传的 `enabled` 核心层根本不读 —— 又一个永远不生效的开关。
 * ★ 线程数下限是 **2**:核心层对 <2 或 >4 直接回 `E_INVALID`
 *   (它故意不静默夹紧 —— 夹紧会让用户以为设了 8 生效了)。
 */
@Composable
private fun PrefetchPanel() {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val session by app.session.collectAsStateWithLifecycle()
    var servers by remember { mutableStateOf<List<String>>(emptyList()) }
    var threads by remember { mutableStateOf(2.0) }
    var cacheBytes by remember { mutableStateOf(0L) }

    suspend fun push(newServers: List<String>, newThreads: Int) {
        val payload = JsonObject(mapOf("settings" to JsonObject(mapOf(
            "servers" to kotlinx.serialization.json.JsonArray(
                newServers.map { kotlinx.serialization.json.JsonPrimitive(it) }),
            "threads" to kotlinx.serialization.json.JsonPrimitive(newThreads),
            "cache_bytes" to kotlinx.serialization.json.JsonPrimitive(cacheBytes),
        ))))
        app.call("prefs.setPrefetchSettings", payload)
    }

    LaunchedEffect(Unit) {
        val o = runCatching { app.call("prefs.getPrefetchSettings") }.getOrNull().obj()
        servers = o.strList("servers")
        threads = (o.long("threads") ?: 2L).toDouble()
        cacheBytes = o.long("cache_bytes") ?: 0L
    }

    val cur = session?.server
    val on = cur != null && cur in servers
    Panel(Modifier.padding(Sp.x16)) {
        LpCell("对这台服务器开启", sub = "开着不一定更快 —— 收益看服务端给不给多连接",
            switch = on, onSwitch = { v ->
                val srv = cur ?: return@LpCell
                val before = servers
                servers = if (v) servers + srv else servers - srv   // 乐观更新
                scope.launch {
                    runCatching { push(servers, threads.toInt()) }
                        .onFailure { servers = before; app.report(it) }   // ☠ 失败必须回滚
                }
            })
        Hairline()
        StepperRow("并发连接数", threads, 2.0, 4.0, 1.0, { v ->
            val before = threads
            threads = v
            scope.launch {
                runCatching { push(servers, v.toInt()) }
                    .onFailure { threads = before; app.report(it) }
            }
        }, sub = "只支持 2~4;超出核心层会拒绝并回滚", fmt = { it.toInt().toString() })
    }
}

@Composable
private fun BlockedPanel() {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(reload) {
        items = runCatching { app.call("emby.blockedList") }.getOrNull().arr().mapNotNull {
            val o = it.obj() ?: return@mapNotNull null
            (o.str("id") ?: return@mapNotNull null) to (o.str("name") ?: "(没有名字)")
        }
        loaded = true
    }
    if (loaded && items.isEmpty()) EmptyState("没有屏蔽过任何东西", "在封面上长按可以屏蔽一个条目或整个库。")
    else Panel(Modifier.padding(Sp.x16)) {
        items.forEachIndexed { i, (id, name) ->
            if (i > 0) Hairline()
            LpCell(name, value = "解除", arrow = false, onClick = {
                scope.launch {
                    runCatching {
                        app.call("emby.setBlocked",
                            args("id" to id, "name" to name, "blocked" to false))
                    }.onSuccess { reload++ }.onFailure { app.report(it) }
                }
            })
        }
    }
}

@Composable
private fun StoragePanel() {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var paths by remember { mutableStateOf<String?>(null) }
    var size by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        // 同一面板里的多个请求**必须并发**:串行 await 会把后端本身的卡放大 N 倍
        launch { paths = runCatching { app.call("system.dataPaths") }.getOrNull().obj().str("dataRoot") }
        launch {
            size = runCatching { app.call("system.cacheSize") }.getOrNull().obj()
                .long("bytes")?.let { "%.1f MB".format(it / 1024.0 / 1024.0) }
        }
    }
    /* 导出日志。**必须让用户自己挑位置** —— 上一版写进应用私有目录然后弹一句
       「已导出到数据目录」,而那个目录任何文件管理器都进不去:
       用户点了、看见成功提示、然后什么也拿不到。那不是导出,是安慰剂。 */
    val ctx = LocalContext.current
    val save = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val diag = runCatching { app.call("system.exportDiagnostics") }.getOrNull()
                val text = "== 诊断 ==\n" + (diag?.toString() ?: "取不到") + "\n\n" + Logs.dump()
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                }
            }.onSuccess { app.toast("日志已导出", ToastKind.Ok) }.onFailure { app.report(it) }
        }
    }

    Panel(Modifier.padding(Sp.x16)) {
        // 安卓的数据根是应用私有目录:**展示但不可点开** ——
        // 没有文件管理器能进去,给一个打不开的按钮比不给更糟
        LpCell("数据目录", sub = paths ?: "读取中…", arrow = false)
        Hairline()
        // 这个目录**是**能进去的(Android/data/<包名>/files/logs),所以照实写出来
        LpCell("日志目录", sub = Logs.dirPath.ifEmpty { "未初始化" }, arrow = false)
        Hairline()
        LpCell("导出日志", sub = "选个位置存下来,连 logcat 一起", onClick = {
            save.launch("linplayer-" + System.currentTimeMillis() + ".log")
        })
        Hairline()
        LpCell("缓存占用", value = size ?: "…", arrow = false)
        Hairline()
        LpCell("清理缓存", onClick = {
            scope.launch {
                runCatching { app.call("system.clearCache") }
                    .onSuccess { app.toast("缓存已清理", ToastKind.Ok); size = "0.0 MB" }
                    .onFailure { app.report(it) }
            }
        })
    }
}

@Composable
private fun AboutPanel() {
    val app = LocalApp.current
    val caps by app.caps.collectAsStateWithLifecycle()
    var update by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        update = runCatching { app.call("system.checkUpdate") }.getOrNull()
            .obj().str("version")
    }
    Panel(Modifier.padding(Sp.x16)) {
        LpCell("版本", value = caps.version, arrow = false)
        Hairline()
        // 安卓端**不做应用内更新**:安装权限对一个第三方播放器是过重的要求,
        // 而且各厂商 ROM 拦法各不相同。只提示,跳发布页
        LpCell("检查更新", value = update?.let { "有新版 $it" } ?: "已是最新", arrow = false)
    }
}
