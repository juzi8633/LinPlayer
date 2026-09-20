package xyz.linplayer.app.tv

import android.app.Activity
import android.graphics.BitmapFactory
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import xyz.linplayer.app.core.CoreException
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.Item
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.UiPrefs
import xyz.linplayer.app.data.arr
import xyz.linplayer.app.data.bool
import xyz.linplayer.app.data.dbl
import xyz.linplayer.app.data.long
import xyz.linplayer.app.data.obj
import xyz.linplayer.app.data.str
import xyz.linplayer.app.plugin.PluginPlayer
import xyz.linplayer.app.ui.pages.Version
import xyz.linplayer.app.ui.pages.args
import xyz.linplayer.app.ui.player.DanmakuLayer
import xyz.linplayer.app.ui.player.DanmakuStyle
import xyz.linplayer.app.ui.player.ExoSurface
import xyz.linplayer.app.ui.player.Libass
import xyz.linplayer.app.ui.player.VideoFit
import xyz.linplayer.app.ui.player.VideoSurface
import xyz.linplayer.app.ui.player.advancedNaturally
import xyz.linplayer.app.ui.player.failureDiag
import xyz.linplayer.app.ui.player.load
import xyz.linplayer.app.ui.player.loadEmbeddedFonts
import xyz.linplayer.app.ui.player.loadExternalAss
import xyz.linplayer.app.ui.player.rememberExoPlayer
import xyz.linplayer.app.ui.player.sampleNetSpeed
import xyz.linplayer.app.ui.player.startDanmakuFor
import xyz.linplayer.app.ui.plugin.PluginKeys
import xyz.linplayer.app.ui.plugin.rememberPluginKeyTaken

/** OSD 自动收起。= Media3 默认值,别自己发明【继承 PC A-23】。 */
private const val OsdHideMs = 5000L
/** 进度条游标松手多久才真的 seek(§8.3)。 */
private const val SeekCommitMs = 700L
/** 跳过片头按钮停留多久(§8.5)。 */
private const val SkipShowMs = 8000L

/** 播放页的全部可变状态。拆成一个对象是为了 OSD / 面板两个文件共用,不是为了复用。 */
internal class PlayerUi {
    var position by mutableDoubleStateOf(0.0)
    var duration by mutableDoubleStateOf(0.0)
    var paused by mutableStateOf(false)
    var buffering by mutableStateOf(true)
    /** ☠ 「时间真的往前走了」才撤黑幕,不是 `position > 0`。 */
    var everMoved by mutableStateOf(false)
    var buffered by mutableDoubleStateOf(0.0)
    var speed by mutableDoubleStateOf(1.0)
    var osd by mutableStateOf(true)
    var failed by mutableStateOf<String?>(null)
    var noVideo by mutableStateOf<String?>(null)
    var netSpeed by mutableStateOf("")
    var title by mutableStateOf("")
    var spec by mutableStateOf("")
    var chapters by mutableStateOf<List<Pair<Double, String>>>(emptyList())
    var intro by mutableStateOf<ClosedFloatingPointRange<Double>?>(null)
    var outro by mutableStateOf<ClosedFloatingPointRange<Double>?>(null)
    var skipAuto by mutableStateOf(false)
    var skipWhat by mutableStateOf<String?>(null)
    var preview by mutableStateOf<Double?>(null)
    var thumb by mutableStateOf<ImageBitmap?>(null)
    var hold by mutableStateOf(false)
    var flash by mutableStateOf<Boolean?>(null)
    var seasonId by mutableStateOf<String?>(null)
    var seriesId by mutableStateOf<String?>(null)
    var episodes by mutableStateOf<List<Item>>(emptyList())
    var nextCard by mutableStateOf(false)
    var countdown by mutableIntStateOf(5)
    var tracks by mutableStateOf<List<JsonObject>>(emptyList())
    var fit by mutableStateOf(VideoFit.Source)
    var versions by mutableStateOf<List<Version>>(emptyList())
    var mediaSourceId by mutableStateOf<String?>(null)
    val hasEpisodes get() = seasonId != null || seriesId != null

    fun next(cur: String) = episodes.indexOfFirst { it.id == cur }.let { i -> if (i >= 0) episodes.getOrNull(i + 1) else null }
    fun prev(cur: String) = episodes.indexOfFirst { it.id == cur }.let { i -> if (i > 0) episodes.getOrNull(i - 1) else null }
}

internal fun isOk(k: Key) = k == Key.DirectionCenter || k == Key.Enter || k == Key.NumPadEnter || k == Key.ButtonA

/**
 * 播放页(UI_TV.md §8)。
 *
 * ☠ **这一层和它上面的层不许有铺满的不透明底色**:SurfaceView 是在窗口上抠洞透出画面的,
 *   Compose 刷一块不透明背景 = 有声音、没画面、一条错都不报。黑底交给 Activity 的 windowBackground。
 * ☠ **根节点必须可聚焦并持有焦点**:OSD 收起时树上没有别的可聚焦件,不这么做「整屏黑着按什么都没反应」。
 * ★ 换集 / 换版本 / 换内核 / 换线路都在**这一页里**换目标,先等 stopPlayback 回来再起下一个 ——
 *   出栈再入栈的话,旧页的停播和新页的起播在核心层 worker 上并发,停播可能落在起播后面。
 */
@Composable
fun TvPlayerPage(r: TvRoute.Player) {
    val app = LocalApp.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val activity = LocalContext.current as? Activity
    val overlay = rememberOverlay()
    val mem = LocalFocusMemory.current
    val ui = remember { PlayerUi() }
    var target by remember { mutableStateOf(r) }
    var attempt by remember { mutableIntStateOf(0) }
    var autoRetried by remember(target) { mutableStateOf(false) }
    val engine = if (target.localEntry || target.download || target.src != null) "mpv" else target.engine ?: UiPrefs.engine.value
    var subLangPref by remember { mutableStateOf<String?>(null) }
    var subOffPref by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val p = runCatching { app.call("prefs.getPrefs") }.getOrNull().obj()
        subLangPref = p.str("sub_lang") ?: ""
        subOffPref = p?.get("sub_enabled")?.let { !p.bool("sub_enabled") } ?: false
    }
    val exo = rememberExoPlayer(engine == "exo", subLangPref)
    val root = remember { FocusRequester() }
    var leaving by remember { mutableStateOf(false) }
    val leave: () -> Unit = { if (!leaving) { leaving = true; nav.pop() } }

    suspend fun switchTo(next: TvRoute.Player) {
        runCatching { app.call("player.stopPlayback", args("pos" to ui.position)) }
        overlay.close()
        target = next
    }

    fun doSeek(t: Double) {
        val to = t.coerceIn(0.0, if (ui.duration > 0) ui.duration else Double.MAX_VALUE)
        if (exo != null) exo.seekTo((to * 1000).toLong())
        else scope.launch { runCatching { app.call("player.seek", args("pos" to to)) } }
    }
    fun doPause(want: Boolean) {
        if (exo != null) exo.playWhenReady = !want
        else scope.launch { runCatching { app.call("player.setPause", args("paused" to want)) } }
    }
    fun doSpeed(v: Double) {
        if (exo != null) exo.setPlaybackSpeed(v.toFloat())
        else scope.launch { runCatching { app.call("player.setSpeed", args("speed" to v)) } }
    }

    val ctl = PlayerCtl(::doSeek, ::doPause, ::doSpeed) { scope.launch { switchTo(it) } }

    // ---------------------------------------------------------------- 起播
    LaunchedEffect(target, attempt) {
        ui.everMoved = false; ui.buffering = true; ui.position = 0.0; ui.duration = 0.0
        ui.failed = null; ui.noVideo = null; ui.nextCard = false; ui.skipWhat = null; ui.osd = true
        ui.title = target.title
        Libass.reset()
        runCatching {
            when {
                target.download -> app.call("player.playLocal", args("id" to target.itemId))
                // 数据源:取流、解析、请求头都在核心层(source.playItem)
                target.src != null -> app.call("source.playItem", kotlinx.serialization.json.Json.parseToJsonElement(target.src!!) as kotlinx.serialization.json.JsonObject)
                target.localEntry -> app.call("source.play", args("entry_id" to target.itemId, "entry_name" to target.title,
                    "resume_secs" to (target.resumeAt ?: 0.0)))
                else -> {
                    // 没按过版本确认就不传 media_source_id(§11.2):否则核心层的版本正则整个被跳过
                    val a = buildMap<String, Any> {
                        put("item_id", target.itemId)
                        put("engine", engine)
                        target.versionId?.let { put("media_source_id", it) }
                        if (target.fromStart) put("from_start", true)
                        target.resumeAt?.let { put("resume_secs", it) }
                    }
                    val res = app.call("player.play", args(*a.toList().toTypedArray())).obj()
                    ui.mediaSourceId = res.str("media_source_id")
                    val url = res.str("play_url")
                    if (exo != null && url != null) {
                        exo.load(url, res.dbl("resume_secs") ?: 0.0)
                        if (!subOffPref) loadExternalAss(app, res?.get("external_subs"))
                        loadEmbeddedFonts(url)
                    }
                }
            }
        }.onFailure { e ->
            // 可重试的错(服务端 500、网络抖一下)先自己重来一次:多数时候第二次就起来了,不必让用户看失败页
            if (!autoRetried && (e as? CoreException)?.retryable == true) { autoRetried = true; attempt++; return@LaunchedEffect }
            // 第二行写核心层原话(§8.6),给人看的那句在第一行
            ui.failed = e.message ?: "起播失败"
        }
        runCatching { app.call("companion.setNowPlaying", args("title" to target.title)) }
        if (!target.localEntry && !target.download && target.src == null) launch { startDanmakuFor(app, target.itemId) }
    }

    // 剧集上下文、规格行、章节 / 片头片尾
    LaunchedEffect(target.itemId) {
        if (target.localEntry || target.download) return@LaunchedEffect
        val d = runCatching { app.call("emby.itemDetail", args("item_id" to target.itemId)) }.getOrNull().obj()
        ui.seasonId = d.str("season_id"); ui.seriesId = d.str("series_id")
        if (d.str("type_") == "Episode") {
            ui.title = listOfNotNull(d.str("series_name"),
                d.long("season_no")?.let { s -> d.long("episode_no")?.let { e -> "S${s}E%02d".format(e) } }, d.str("name")).joinToString(" · ")
            (ui.seasonId ?: ui.seriesId)?.let { p ->
                launch { ui.episodes = Item.list(runCatching { app.call("emby.seasonEpisodes", args("parent_id" to p, "limit" to 200)) }.getOrNull()) }
            }
        }
        ui.versions = Version.list(runCatching { app.call("emby.itemMedia", args("item_id" to target.itemId)) }.getOrNull())
        val v = ui.versions.firstOrNull { it.id == ui.mediaSourceId } ?: ui.versions.firstOrNull()
        ui.spec = listOfNotNull(v?.let(::resLabel)?.takeIf { it != "—" },
            v?.of("Audio")?.firstOrNull()?.let { a -> listOfNotNull(codecName(a.codec), a.layout).joinToString(" ") }).joinToString(" · ")
    }
    LaunchedEffect(target.itemId, ui.duration > 0) {
        if (ui.duration <= 0 || target.localEntry || target.download) return@LaunchedEffect
        val c = runCatching { app.call("player.chapterInfo", args("item_id" to target.itemId, "runtime_secs" to ui.duration)) }.getOrNull().obj()
        ui.chapters = c?.get("chapters").arr().mapNotNull { it.obj() }.mapNotNull { o -> o.dbl("start_secs")?.let { it to (o.str("name") ?: "") } }
        fun range(k: String) = c?.get(k).obj()?.let { o -> val s = o.dbl("start"); val e = o.dbl("end"); if (s != null && e != null && e > s) s..e else null }
        ui.intro = range("intro"); ui.outro = range("outro"); ui.skipAuto = c.bool("skip_auto")
    }

    // ---------------------------------------------------------------- 状态来源
    LaunchedEffect(engine, target) {
        if (exo != null) return@LaunchedEffect
        app.core.events.collect { ev ->
            if (ev.name != "player.status") return@collect
            val o = ev.data.obj()
            val p = o.dbl("position") ?: 0.0
            if (advancedNaturally(ui.position, p)) ui.everMoved = true
            ui.position = p
            o.dbl("duration")?.takeIf { it > 0 }?.let { ui.duration = it }
            ui.paused = o.bool("paused")
            ui.buffering = o.bool("buffering")
            // ☠ keep-open 下 END_FILE 永远不发,判播完读 eof;**只收尾一次**
            if (o.bool("eof") && !ui.nextCard && ui.failed == null) {
                if (ui.everMoved) onFinished(app, ui, target, scope) { leave() }
                else ui.failed = failureDiag(app)
            }
        }
    }
    LaunchedEffect(exo, target) {
        val e = exo ?: return@LaunchedEffect
        while (true) {
            val p = e.currentPosition / 1000.0
            if (advancedNaturally(ui.position, p)) ui.everMoved = true
            ui.position = p
            e.duration.takeIf { it > 0 }?.let { ui.duration = it / 1000.0 }
            ui.paused = !e.playWhenReady
            ui.buffering = e.playbackState == androidx.media3.common.Player.STATE_BUFFERING
            e.playerError?.let { err -> ui.failed = "ExoPlayer:" + err.errorCodeName + " " + (err.message ?: ""); return@LaunchedEffect }
            if (e.playbackState == androidx.media3.common.Player.STATE_ENDED && !ui.nextCard) {
                if (ui.everMoved) onFinished(app, ui, target, scope) { leave() } else ui.failed = "ExoPlayer 一帧都没放出来就结束了"
                return@LaunchedEffect
            }
            delay(250)
        }
    }
    // 黑幕兜底:4 秒且不在缓冲 → 撤;12 秒无条件撤(状态事件发不出来时 buffering 停在初值 true)
    LaunchedEffect(target) { delay(4000); if (!ui.buffering) ui.everMoved = true }
    LaunchedEffect(target) { delay(12_000); ui.everMoved = true }
    LaunchedEffect(Unit) { sampleNetSpeed { ui.netSpeed = it } }
    // 缓冲末端只在 OSD 亮着时要:事件里没有这个字段,命令版才有
    LaunchedEffect(ui.osd, exo) {
        while (ui.osd) {
            ui.buffered = if (exo != null) exo.bufferedPosition / 1000.0
            else runCatching { app.call("player.status") }.getOrNull().obj().dbl("buffered") ?: ui.buffered
            delay(1000)
        }
    }
    // 有声音没画面:时间在走,但 vo 没建 / 没解出帧 —— 用 player.opts 分诊,不编原因
    LaunchedEffect(ui.everMoved, target) {
        if (!ui.everMoved || exo != null) return@LaunchedEffect
        delay(6000)
        val o = runCatching { app.call("player.opts") }.getOrNull().obj() ?: return@LaunchedEffect
        if (o.str("current-vo").isNullOrBlank() || (o.str("dwidth")?.toIntOrNull() ?: 0) <= 0) ui.noVideo = failureDiag(app)
    }
    // 轨表**轮询到稳定**:每 700ms 一次,约 11 秒兜底;不在「第一次非空」时就停(音轨先出来、字幕永远进不了面板)
    LaunchedEffect(target) {
        if (exo != null) return@LaunchedEffect
        var stable = 0
        var applied = false
        repeat(16) {
            delay(700)
            val list = runCatching { app.call("player.tracks") }.getOrNull().arr().mapNotNull { it.obj() }
            if (list.size == ui.tracks.size && list.isNotEmpty()) stable++ else stable = 0
            ui.tracks = list
            if (stable >= 2 && !applied) {
                applied = true
                applyPickedTracks(app, target, list)
            }
        }
    }
    // 进度上报:播放中每 10s 一次
    LaunchedEffect(target) {
        if (target.localEntry || target.download) return@LaunchedEffect
        while (true) {
            delay(10_000)
            if (!ui.paused && ui.position > 0) runCatching {
                app.call("emby.reportProgress", args("pos" to ui.position, "paused" to ui.paused))
            }
        }
    }
    // 跳过片头 / 片尾:每片各跳一次;倒回去看片头不许被再踹走
    var skippedIntro by remember(target) { mutableStateOf(false) }
    var skippedOutro by remember(target) { mutableStateOf(false) }
    LaunchedEffect(ui.position.toLong(), ui.intro, ui.outro) {
        val p = ui.position
        val intro = ui.intro
        val outro = ui.outro
        when {
            intro != null && !skippedIntro && p >= intro.start && p < intro.endInclusive - 1 -> {
                skippedIntro = true
                if (ui.skipAuto) { doSeek(intro.endInclusive); app.toast("已跳过片头") }
                else { ui.skipWhat = "intro"; delay(SkipShowMs); if (ui.skipWhat == "intro") ui.skipWhat = null }
            }
            // 片尾只在后面还有内容时才跳【继承 PC A-18】:片尾就是片子最后一段时,跳过去等于直接播完
            outro != null && !skippedOutro && p >= outro.start && p < outro.endInclusive - 1 &&
                (outro.endInclusive < ui.duration - 2 || ui.next(target.itemId) != null) -> {
                skippedOutro = true
                if (ui.skipAuto) skipOutro(ui, target) { doSeek(it) }
                else { ui.skipWhat = "outro"; delay(SkipShowMs); if (ui.skipWhat == "outro") ui.skipWhat = null }
            }
        }
    }
    // 下一集卡的倒计时。「自动播放下一集」关着:照样出卡,但没有倒计时、不自动播
    LaunchedEffect(ui.nextCard) {
        if (!ui.nextCard || !UiPrefs.tvAutoNext.value) return@LaunchedEffect
        ui.countdown = 5
        while (ui.countdown > 0) { delay(1000); ui.countdown-- }
        ui.next(target.itemId)?.let { n -> switchTo(TvRoute.Player(n.id, cardTitleOf(n))) }
    }

    // ---------------------------------------------------------------- OSD 显隐与焦点
    var poke by remember { mutableIntStateOf(0) }
    LaunchedEffect(ui.osd, overlay.isOpen, ui.paused, poke) {
        if (!ui.osd || overlay.isOpen || ui.paused) return@LaunchedEffect
        delay(OsdHideMs)
        ui.osd = false
    }
    var fromPanel by remember { mutableStateOf(false) }
    LaunchedEffect(overlay.isOpen) { if (overlay.isOpen) fromPanel = true }
    LaunchedEffect(ui.osd, overlay.isOpen, ui.nextCard, ui.failed) {
        if (overlay.isOpen || ui.failed != null) return@LaunchedEffect
        fun want(k: String) = mem.requesters[k]?.let { fr -> runCatching { fr.requestFocus() }.getOrDefault(false) } ?: false
        repeat(8) {
            withFrameNanos { }
            // OSD **每次重新出现**焦点都回到播放/暂停;从面板回来时交给面板那一侧还给打开它的按钮
            // 下一集卡的焦点也在这里给:此刻记忆里是 osd.play,卡片自己的「初始焦点」不会生效
            val ok = if (ui.nextCard) want("next.card")
                else if (!ui.osd) runCatching { root.requestFocus() }.getOrDefault(false)
                else if (fromPanel) { fromPanel = false; true }
                else want("osd.play")
            if (ok) return@LaunchedEffect
        }
    }
    // 预览游标的缩略图:没有本地字节时核心层回 available=false,只显示时间
    LaunchedEffect(ui.preview?.let { (it / 5).toLong() }) {
        val p = ui.preview ?: run { ui.thumb = null; return@LaunchedEffect }
        if (exo != null) return@LaunchedEffect
        delay(250)
        val o = runCatching { app.call("player.thumbnail", args("position" to p)) }.getOrNull().obj()
        ui.thumb = if (o.bool("available")) o.str("jpeg")?.let { b64 ->
            runCatching {
                val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        } else null
    }

    DisposableEffect(ui.paused) {
        val w = activity?.window
        if (!ui.paused) w?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else w?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { w?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    // 主页键离开应用 → 暂停 + 落库 + 停止(TV 不做后台播放)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) { doPause(true); leave() } }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    DisposableEffect(Unit) {
        onDispose {
            Libass.reset()
            // ★ 收尾走 app.bg:页面的协程作用域正被取消,挂在它上面的上报一件都不跑。**落库失败也要离页**
            val pos = if (ui.duration > 0 && ui.position >= ui.duration - 2) ui.duration else ui.position
            app.bg.launch {
                runCatching { app.call("player.stopPlayback", args("pos" to pos)) }
                runCatching { app.call("companion.setNowPlaying", args("title" to "")) }
            }
        }
    }

    // ---------------------------------------------------------------- 返回键(§4.4 播放页那一列)
    // 初值不能是 0:开机两秒内 uptime 本身就小于 2000,第一下返回会被当成「再按一次」
    var backArmedAt by remember { mutableStateOf(Long.MIN_VALUE / 2) }
    BackHandler(enabled = ui.nextCard || ui.failed != null) { leave() }
    BackHandler(enabled = !ui.nextCard && ui.failed == null) {
        val now = android.os.SystemClock.uptimeMillis()
        when {
            now - backArmedAt < 2000 -> leave()
            ui.osd -> ui.osd = false
            else -> { ui.osd = true; backArmedAt = now; app.toast("再按一次返回退出播放") }
        }
    }

    /* 插件的 `player.openPanel` / `setOsdVisible`(D67 D162)。和手机端同一个 host 口子,
       翻的是 TV 自己那套面板名 —— 两端面板不是一套,合成一套只会两边都错。 */
    DisposableEffect(exo, target.itemId) {
        PluginPlayer.host = object : PluginPlayer.Host {
            override fun openPanel(which: String) {
                val kind = PluginPlayer.tvKind(which)
                ui.osd = true
                openPanel(kind, app, nav, scope, overlay, ui, target, engine, exo, ctl)
            }
            override fun setOsdVisible(visible: Boolean) { ui.osd = visible }
        }
        onDispose { PluginPlayer.host = null }
    }

    // ---------------------------------------------------------------- 按键
    var okDownAt by remember { mutableStateOf(0L) }
    var holdJob by remember { mutableStateOf<Job?>(null) }
    fun defaultKey(e: KeyEvent): Boolean {
        poke++
        val down = e.type == KeyEventType.KeyDown
        when (e.key) {
            Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> {
                if (down) {
                    val want = if (e.key == Key.MediaPlay) false else if (e.key == Key.MediaPause) true else !ui.paused
                    doPause(want)
                    // 播放/暂停媒体键**不唤出 OSD**,屏幕中央闪一次图标
                    if (!ui.osd) scope.launch { ui.flash = !want; delay(600); ui.flash = null }
                }
                return true
            }
            Key.MediaFastForward -> { if (down) doSeek(ui.position + UiPrefs.tvSeekStep.value); return true }
            Key.MediaRewind -> { if (down) doSeek(ui.position - UiPrefs.tvSeekStep.value); return true }
            Key.MediaNext -> { if (down) ui.next(target.itemId)?.let { n -> scope.launch { switchTo(TvRoute.Player(n.id, cardTitleOf(n))) } }; return true }
            Key.MediaPrevious -> { if (down) ui.prev(target.itemId)?.let { n -> scope.launch { switchTo(TvRoute.Player(n.id, cardTitleOf(n))) } }; return true }
            Key.MediaStop -> { if (down) leave(); return true }
        }
        if (overlay.isOpen || ui.nextCard || ui.failed != null) return false
        if (e.key == Key.Menu) {
            if (!down) { ui.osd = true; openPanel("more", app, nav, scope, overlay, ui, target, engine, exo, ctl) }
            return true
        }
        if (isOk(e.key)) {
            // 长按确认 = 临时倍速,松开恢复;短按在松手时才算
            if (down && e.nativeKeyEvent.repeatCount == 0) {
                okDownAt = e.nativeKeyEvent.eventTime
                holdJob = scope.launch {
                    delay(600)
                    ui.hold = true
                    doSpeed(UiPrefs.tvHoldSpeed.value)
                }
            }
            if (down) return !ui.osd || ui.hold
            holdJob?.cancel()
            if (ui.hold) { ui.hold = false; doSpeed(ui.speed); return true }
            if (!ui.osd) {
                // OSD 收起时按确认 = 跳过片头(此刻按确认最可能的意图)
                if (ui.skipWhat != null) skipNow(ui, target, ::doSeek) else ui.osd = true
                return true
            }
            return false
        }
        val dir = e.key == Key.DirectionUp || e.key == Key.DirectionDown || e.key == Key.DirectionLeft || e.key == Key.DirectionRight
        // OSD 收起时方向键**只唤醒**,不移动焦点:否则第一次按键就误触
        if (dir && !ui.osd) { if (down) ui.osd = true; return true }
        return false
    }

    /* 插件覆盖层开着时这一下键先归插件(D563)。没开着就一句都不问 —— 判断在
       PluginKeys.gate 里,遥控器上每按一下都要过它。 */
    val taken = rememberPluginKeyTaken()
    fun onKey(e: KeyEvent): Boolean = PluginKeys.gate(app, scope, taken, e, ::defaultKey)

    // 插件的两个挂载点(SPEC 9.5)。拉不到是空表,播放页照放
    val pluginOverlays = xyz.linplayer.app.ui.plugin.rememberPlayerSurfaces("overlay")
    val pluginPanels = xyz.linplayer.app.ui.plugin.rememberPlayerSurfaces("panel")

    Box(Modifier.fillMaxSize().onPreviewKeyEvent(::onKey).testTag("player.root").focusRequester(root).focusable()) {
        if (exo != null) ExoSurface(exo, subOff = subOffPref, fit = ui.fit, m = Modifier.fillMaxSize())
        else VideoSurface(app.core, Modifier.fillMaxSize())
        if (DanmakuStyle.enabled.value) DanmakuLayer(DanmakuStyle.layout.value, ui.position, ui.paused, ui.speed, Modifier.fillMaxSize())

        /* 插件覆盖层。摆在 OSD **之前**:盖住控制条等于把暂停和进度条一起弄没,
           接管控制条是 `osd[]` 贡献点的事。不声明 interactive 的那批连焦点都进不去。 */
        xyz.linplayer.app.ui.plugin.PlayerOverlays(pluginOverlays, interactive = false)
        xyz.linplayer.app.ui.plugin.PlayerOverlays(pluginOverlays, interactive = true)
        val failed = ui.failed
        if (failed != null) PlayerFailure(failed, retried = autoRetried, onRetry = { attempt++ },
            onSwitchEngine = if (target.localEntry || target.download) null else ({
                scope.launch { switchTo(target.copy(engine = if (engine == "exo") "mpv" else "exo")) }
            }),
            onLines = if (target.localEntry || target.download) null else ({
                scope.launch {
                    val id = xyz.linplayer.app.data.Account.list(runCatching { app.call("account.listAccounts") }.getOrNull())
                        .firstOrNull { it.isActive }?.id ?: return@launch
                    nav.push(TvRoute.Lines(id, ""))
                }
            }))
        else if (!ui.everMoved) Curtain(ui)
        if (failed == null) {
            if (ui.osd && !overlay.isOpen && !ui.nextCard) Osd(ui, target, engine, pluginPanels,
                onSeek = ::doSeek, onPause = { doPause(!ui.paused) },
                onPrev = { ui.prev(target.itemId)?.let { n -> scope.launch { switchTo(TvRoute.Player(n.id, cardTitleOf(n))) } } },
                onNext = { ui.next(target.itemId)?.let { n -> scope.launch { switchTo(TvRoute.Player(n.id, cardTitleOf(n))) } } },
                onPanel = { which ->
                    openPanel(which, app, nav, scope, overlay, ui, target, engine, exo, ctl,
                        pluginPanels.firstOrNull { xyz.linplayer.app.ui.plugin.pluginPanelKind(it) == which })
                },
                onSkip = { skipNow(ui, target, ::doSeek) },
            )
            if (ui.skipWhat != null && !ui.osd && !overlay.isOpen) SkipButton(ui) { skipNow(ui, target, ::doSeek) }
            ui.noVideo?.let { NoVideoCard(ui, it) }
            if (ui.nextCard) ui.next(target.itemId)?.let { n ->
                NextCard(n, if (UiPrefs.tvAutoNext.value) ui.countdown else null) {
                    scope.launch { switchTo(TvRoute.Player(n.id, cardTitleOf(n))) }
                }
            }
            Moments(ui)
        }
        OverlayHost(overlay)
    }
    LaunchedEffect(Unit) { runCatching { root.requestFocus() } }
}

/** 播完(`eof` 且时间走过):有下一集就出下一集卡,没有就退回去并 Toast。 */
private fun onFinished(app: AppState, ui: PlayerUi, target: TvRoute.Player, scope: kotlinx.coroutines.CoroutineScope, leave: () -> Unit) {
    if (ui.next(target.itemId) != null) { ui.osd = false; ui.nextCard = true }
    else { app.toast("播放完毕"); leave() }
}

private fun skipNow(ui: PlayerUi, target: TvRoute.Player, seek: (Double) -> Unit) {
    when (ui.skipWhat) {
        "intro" -> ui.intro?.let { seek(it.endInclusive) }
        "outro" -> skipOutro(ui, target, seek)
    }
    ui.skipWhat = null
}

private fun skipOutro(ui: PlayerUi, target: TvRoute.Player, seek: (Double) -> Unit) {
    val o = ui.outro ?: return
    if (o.endInclusive < ui.duration - 2) seek(o.endInclusive)
    else if (ui.next(target.itemId) != null) { ui.osd = false; ui.nextCard = true }
}

/**
 * 详情页选过的音轨 / 字幕:轨表稳定后按 **ff_index**(= Emby 的 MediaStream.Index)对上 mpv 的轨 id。
 * `picked` 为空时**什么都不设**,交给核心层的偏好。
 */
private suspend fun applyPickedTracks(app: AppState, target: TvRoute.Player, tracks: List<JsonObject>) {
    fun idOf(kind: String, index: Long?) = tracks.firstOrNull { it.str("kind") == kind && it.long("ff_index") == index }?.str("id")
    target.audioIndex?.let { idOf("audio", it) }?.let { runCatching { app.call("player.setTrack", args("kind" to "audio", "id" to it)) } }
    if (target.subOff) runCatching { app.call("player.setTrack", args("kind" to "sub", "id" to "")) }
    else target.subIndex?.let { idOf("sub", it) }?.let { runCatching { app.call("player.setTrack", args("kind" to "sub", "id" to it)) } }
}
