package xyz.linplayer.app.tv

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.linplayer.app.core.CoreEvent
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.PageCache
import xyz.linplayer.app.data.UiPrefs

/**
 * 真页面逐张出图(UI_TV.md §11.3),和草稿同一张画布:`Television1080p`,1920×1080。
 *
 * `./gradlew :app:testDebugUnitTest -Proborazzi.test.record=true --tests '*TvPageShots*'`
 * → `app/build/tvpages/<草稿序号>-<名字>.png`。截图不进仓库;对照脚本把同序号的草稿拼在左边。
 *
 * ★ 只替掉核心层([FakeCore]),页面、焦点、面板、返回栈全是真代码;按键走 `performKeyInput`,和遥控器同一条分发路径。
 * ★ 草稿里的 03 / 04 / 05 / 36(组件表、系统态拼图)没有对应页面:它们画的就是 tv/kit 本身,
 *   由 [xyz.linplayer.app.TvDraftShots] 的逐像素回归守着(组件库挪进正式包前后 45 张图一致)。
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Television1080p, sdk = [36], application = Application::class)
class TvPageShots(private val name: String) {
    @get:Rule val rule = createComposeRule()

    class Case(
        val draft: Int, val name: String, val setup: FakeCore.() -> Unit = {},
        val waitMs: Long = 800, val after: (ComposeContentTestRule, FakeCore) -> Unit = { _, _ -> },
        /** 按完键到截图之间再等多久;预览游标 700ms 就提交,要拍它就不能等。 */
        val settle: Long = 600, val before: () -> Unit = {},
        val content: @Composable () -> Unit,
    )

    @OptIn(ExperimentalTestApi::class)
    @Test fun shot() {
        val c = cases.first { it.name == name }
        val core = FakeCore().loggedIn().apply(c.setup)
        PageCache.clear()
        UiPrefs.tvEpisodeView.value = "compact"
        FakeImages.install(ApplicationProvider.getApplicationContext())
        c.before()
        val app = AppState(core, CoroutineScope(SupervisorJob() + Dispatchers.Main))
        runBlocking { app.boot() }
        rule.mainClock.autoAdvance = false
        rule.setContent { TvFrame(app) { c.content() } }
        advance(rule, c.waitMs)
        c.after(rule, core)
        advance(rule, c.settle)
        rule.onRoot().captureRoboImage("build/tvpages/%02d-%s.png".format(c.draft, c.name))
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = cases.map { arrayOf<Any>(it.name) }
    }
}

internal fun advance(rule: ComposeContentTestRule, ms: Long) {
    var left = ms
    while (left > 0) { rule.mainClock.advanceTimeBy(50); left -= 50 }
}

@OptIn(ExperimentalTestApi::class)
internal fun press(rule: ComposeContentTestRule, vararg keys: Any) {
    // Key 是 value class,不许直接做 vararg;装箱传进来再拆
    keys.map { it as Key }.forEach { k ->
        rule.onRoot().performKeyInput { pressKey(k) }
        // 按键回调里写的状态要等 GlobalSnapshotManager 投递到主线程才生效;时钟手动推进时这一步不会自己发生
        Snapshot.sendApplyNotifications()
        advance(rule, 250)
    }
}

@Composable
internal fun Shell(route: TvRoute) {
    val nav = remember { TvNav().apply { if (route.rail >= 0 && route !is TvRoute.Lines && route !is TvRoute.LocalDir && route !is TvRoute.LocalPicker && !(route is TvRoute.Library && route.viewId != null)) rail(route) else push(route) } }
    TvShell(nav)
}

/** 播放页的状态事件:时间真的往前走 → 撤黑幕。 */
internal fun FakeCore.tick(pos: Double, eof: Boolean = false) {
    events.tryEmit(CoreEvent("player.status", buildJsonObject {
        put("position", pos); put("duration", 3660.0); put("paused", false); put("buffering", false); put("eof", eof)
    }))
}

private fun playing(rule: ComposeContentTestRule, core: FakeCore) {
    listOf(2170.0, 2171.0, 2172.0).forEach { core.tick(it); advance(rule, 100) }
}

private val playerRoute = TvRoute.Player("sh6", "幕府将军 · S1E06 妾之王国")

internal val cases = listOf(
    TvPageShots.Case(1, "home", content = { Shell(TvRoute.Home) }),
    // 往下只保证露出(§4.3),往上才对齐段顶:草稿画的是「从下面回到继续观看」那一刻
    TvPageShots.Case(2, "home-scrolled", after = { r, _ ->
        press(r, Key.DirectionDown, Key.DirectionDown, Key.DirectionDown, Key.DirectionUp, Key.DirectionUp, Key.DirectionRight)
    }) { Shell(TvRoute.Home) },
    TvPageShots.Case(6, "player-osd", { player() }, after = { r, c -> playing(r, c) }) { Shell(playerRoute) },
    TvPageShots.Case(7, "player-seek", { player() }, after = { r, c -> playing(r, c); press(r, Key.DirectionUp, Key.DirectionRight, Key.DirectionRight) }, settle = 300) { Shell(playerRoute) },
    TvPageShots.Case(8, "player-subtitles", { player() }, after = { r, c ->
        playing(r, c); press(r, Key.DirectionRight, Key.DirectionRight, Key.DirectionRight, Key.Enter)
    }) { Shell(playerRoute) },
    TvPageShots.Case(9, "player-more", { player() }, after = { r, c -> playing(r, c); press(r, Key.Menu) }) { Shell(playerRoute) },
    TvPageShots.Case(11, "player-nextup", { player() }, after = { r, c -> playing(r, c); c.tick(3660.0, eof = true); advance(r, 1200) }) { Shell(playerRoute) },
    TvPageShots.Case(12, "player-failed", { player(failPlay = true) }) { Shell(playerRoute) },
    TvPageShots.Case(13, "player-novideo", { player(noVideo = true) }, after = { r, c -> playing(r, c); advance(r, 6500) }) { Shell(playerRoute) },
    TvPageShots.Case(14, "library-picker", { library() }) { Shell(TvRoute.Library()) },
    TvPageShots.Case(15, "library-grid", { library() }) { Shell(TvRoute.Library("lib-0", "电影")) },
    TvPageShots.Case(16, "library-filter", { library() }, after = { r, _ -> press(r, Key.Enter) }) { Shell(TvRoute.Library("lib-0", "电影")) },
    TvPageShots.Case(17, "series-detail", { series() }) { Shell(TvRoute.Detail("s1", "Series")) },
    TvPageShots.Case(18, "movie-detail", { movie() }) { Shell(TvRoute.Detail("m1", "Movie")) },
    TvPageShots.Case(19, "episode-compact", { episode() }) { Shell(TvRoute.Episode("sh8")) },
    TvPageShots.Case(20, "episode-detailed", { episode() }, after = { r, _ -> press(r, Key.DirectionDown, Key.DirectionDown, Key.DirectionDown) }) {
        UiPrefs.tvEpisodeView.value = "detail"; Shell(TvRoute.Episode("sh8"))
    },
    TvPageShots.Case(21, "search", {
        ret("emby.aggregateSearch", arr(
            buildJsonObject { put("server_id", "http://emby-a.invalid"); put("server_name", "服务器 A")
                put("items", arr(item("q1", "幕府将军", "Series", year = 2024), item("q4", "幕府将军:幕后", year = 2024))) },
            buildJsonObject { put("server_id", "http://emby-b.invalid"); put("server_name", "服务器 B")
                put("items", arr(item("q2", "幕府将军", "Series", year = 2024), item("q3", "幕府将军(1980)", "Series", year = 1980))) },
            buildJsonObject { put("server_id", "http://emby-c.invalid"); put("server_name", "服务器 C"); put("items", arr(item("q5", "将军的女儿", year = 1999))) },
        ))
    }, waitMs = 1200) { PageCache.put("tv.search.q", "幕府"); PageCache.put("tv.search.agg", true); Shell(TvRoute.Search) },
    TvPageShots.Case(23, "onboarding") { OnboardingPage(embedded = false) },
    TvPageShots.Case(24, "servers", { servers() }) { Shell(TvRoute.Servers) },
    TvPageShots.Case(25, "servers-panel", { servers() }, after = { r, _ -> press(r, Key.DirectionRight, Key.Enter) }) { Shell(TvRoute.Servers) },
    TvPageShots.Case(26, "servers-reorder", { servers() }, after = { r, _ ->
        press(r, Key.DirectionRight, Key.Enter, Key.DirectionDown, Key.DirectionDown, Key.DirectionDown, Key.DirectionDown, Key.DirectionDown, Key.Enter)
    }) { Shell(TvRoute.Servers) },
    TvPageShots.Case(27, "lines", { servers() }, after = { r, _ -> press(r, Key.Enter) }) { Shell(TvRoute.Lines("http://emby-a.invalid", "服务器 A")) },
    TvPageShots.Case(28, "settings-playback", { settings() }) { PageCache.put("tv.settings.cat", 1); Shell(TvRoute.Settings) },
    TvPageShots.Case(29, "settings-general", { settings() }) { PageCache.put("tv.settings.cat", 0); Shell(TvRoute.Settings) },
    TvPageShots.Case(30, "discover-ranking", { discover() }) { Shell(TvRoute.Discover) },
    TvPageShots.Case(31, "discover-calendar", { discover() }) { PageCache.put("tv.discover.tab", 1); Shell(TvRoute.Discover) },
    TvPageShots.Case(32, "favorites", { favorites() }) { Shell(TvRoute.Favorites) },
    TvPageShots.Case(33, "downloads", { downloads() }, waitMs = 1200) { Shell(TvRoute.Downloads) },
    TvPageShots.Case(34, "local-picker", before = {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        org.robolectric.Shadows.shadowOf(ctx).grantPermissions(android.Manifest.permission.READ_MEDIA_VIDEO)
        // JVM 上没有存储卷:照草稿造三个,可用空间走 StatFs 的影子
        val sm = org.robolectric.Shadows.shadowOf(ctx.getSystemService(android.os.storage.StorageManager::class.java))
        sm.resetStorageVolumeList()
        listOf(Triple("内部存储", 32.0, 12.4), Triple("U 盘 · SanDisk", 64.0, 38.2), Triple("SD 卡", 128.0, 101.0)).forEachIndexed { i, (name, total, free) ->
            val dir = java.io.File("/storage/vol$i")
            sm.addStorageVolume(org.robolectric.shadows.StorageVolumeBuilder("vol$i", dir, name, android.os.UserHandle.getUserHandleForUid(0), "mounted")
                .setIsPrimary(i == 0).build())
            val blocks = { gb: Double -> (gb * (1L shl 30) / org.robolectric.shadows.ShadowStatFs.BLOCK_SIZE).toInt() }
            org.robolectric.shadows.ShadowStatFs.registerStats(dir.path, blocks(total), blocks(free), blocks(free))
        }
    }) { Shell(TvRoute.LocalPicker) },
    TvPageShots.Case(35, "local-browse", { local() }) {
        Shell(TvRoute.LocalDir("dir-2024", listOf(null to "U 盘 · SanDisk", "dir-movie" to "电影", "dir-2024" to "2024")))
    },
    TvPageShots.Case(37, "card-menu", {
        ret("emby.itemDetail", buildJsonObject { put("is_favorite", false) })
        ret("emby.permissions", buildJsonObject { put("can_download", true) })
        ret("emby.blockedList", arr())
    }, after = { r, _ ->
        press(r, Key.DirectionDown, Key.DirectionDown, Key.DirectionDown, Key.DirectionUp, Key.DirectionUp, Key.Menu)
    }) { Shell(TvRoute.Home) },
    TvPageShots.Case(38, "rail-expanded", after = { r, _ -> press(r, Key.DirectionLeft, Key.DirectionDown) }) { Shell(TvRoute.Home) },
)
