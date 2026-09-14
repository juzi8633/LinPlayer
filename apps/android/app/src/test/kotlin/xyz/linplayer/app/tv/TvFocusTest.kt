package xyz.linplayer.app.tv

import org.junit.Assert.assertFalse
import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.PageCache

/**
 * 焦点路径断言(UI_TV.md §11.3)。☠ 焦点问题在静态截图上**完全看不出来** —— 出图之外必须有按键驱动的断言。
 * 按键走 `performKeyInput`,返回走 OnBackPressedDispatcher —— 和遥控器落到的是同一组处理。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Television1080p, sdk = [36], application = Application::class)
class TvFocusTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var core: FakeCore
    private lateinit var nav: TvNav
    private lateinit var back: OnBackPressedDispatcher

    @Before fun boot() {
        PageCache.clear()
        FakeImages.install(ApplicationProvider.getApplicationContext())
    }

    private fun mount(route: TvRoute = TvRoute.Home, setup: FakeCore.() -> Unit = {}) {
        core = FakeCore().loggedIn().apply(setup)
        val app = AppState(core, CoroutineScope(SupervisorJob() + Dispatchers.Main))
        runBlocking { app.boot() }
        nav = TvNav().apply { if (route == TvRoute.Home) Unit else push(route) }
        rule.mainClock.autoAdvance = false
        rule.setContent {
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            TvFrame(app) { TvShell(nav) }
        }
        advance(rule, 800)
    }

    private fun focused(tag: String) = rule.onNode(hasTestTag(tag)).assertIsFocused()
    private fun pressBack() { rule.runOnUiThread { back.onBackPressed() }; advance(rule, 300) }

    @Test fun 首页初始焦点在Hero详情() {
        mount()
        focused("home.hero")
    }

    @Test fun 左键进轨落在当前页右键回到离开时的元素() {
        mount()
        press(rule, Key.DirectionDown, Key.DirectionRight)
        focused("home.resume.r2")
        // 从行首才进得了轨:先回到第一张
        press(rule, Key.DirectionLeft, Key.DirectionLeft)
        // 焦点进轨后展开,文字出来,且落在「首页」这一项(当前页),不是上次停过的项
        rule.onNode(hasText("LinPlayer")).assertExists()
        rule.onNode(hasText("首页")).assertExists()
        press(rule, Key.DirectionRight)
        focused("home.resume.r1")
    }

    @Test fun 从下面回到首页Hero整块露出() {
        mount()
        // 往下走到半露的那一行才会真的滚;全在屏幕里的项请求露出是空操作,测不出东西
        press(rule, Key.DirectionDown, Key.DirectionDown, Key.DirectionDown, Key.DirectionUp, Key.DirectionUp, Key.DirectionUp)
        advance(rule, 600)
        focused("home.hero")
        // getBoundsInRoot 会裁到屏幕里,滚出去的部分量不出来;要看没裁过的位置
        val top = rule.onNode(hasTestTag("home.heroBox")).fetchSemanticsNode().positionInRoot.y
        assertTrue("Hero 上沿被滚出屏幕:$top px", top >= 0f)
    }

    @Test fun 面板关闭焦点回到打开它的卡片且返回键不连退两层() {
        mount()
        press(rule, Key.DirectionDown, Key.Menu)
        rule.onNode(hasText("查看详情")).assertExists()
        pressBack()
        rule.onNode(hasText("查看详情")).assertDoesNotExist()
        focused("home.resume.r1")
        assertEquals("面板开着按返回只该关面板,页面不许一起退", 1, nav.stack.size)
    }

    @Test fun 从下一级返回焦点回到点进去的那张卡() {
        mount()
        press(rule, Key.DirectionDown, Key.DirectionRight)
        focused("home.resume.r2")
        press(rule, Key.Enter)
        assertTrue("OK 应该进集详情", nav.top.route is TvRoute.Episode)
        pressBack()
        assertEquals(1, nav.stack.size)
        focused("home.resume.r2")
    }

    @Test fun 播放页OSD收起后方向键只唤醒不移动焦点() {
        mount(TvRoute.Player("sh6", "幕府将军 · S1E06 妾之王国")) { player() }
        listOf(2170.0, 2171.0, 2172.0).forEach { core.tick(it); advance(rule, 100) }
        focused("osd.play")
        advance(rule, 5600)
        rule.onNode(hasText("字幕")).assertDoesNotExist()
        focused("player.root")
        press(rule, Key.DirectionRight)
        rule.onNode(hasText("字幕")).assertExists()
        // 唤醒那一下不许把焦点挪到快进上:焦点回到播放/暂停
        focused("osd.play")
    }

    @Test fun 播完出下一集卡焦点落在卡上() {
        mount(TvRoute.Player("sh6", "幕府将军 · S1E06 妾之王国")) { player() }
        listOf(2170.0, 2171.0, 2172.0).forEach { core.tick(it); advance(rule, 100) }
        core.tick(3660.0, eof = true)
        advance(rule, 1200)
        focused("next.card")
    }

    @Test fun 没按过版本确认起播不传版本() {
        mount(TvRoute.Detail("m1", "Movie")) { movie() }
        advance(rule, 600)
        assertTrue("版本卡要先出来,否则这条断言是空转", rule.onAllNodes(hasText("2160p HDR10", substring = true)).fetchSemanticsNodes().isNotEmpty())
        focused("detail.play")
        press(rule, Key.Enter)
        assertTrue(nav.top.route is TvRoute.Player)
        advance(rule, 500)
        val a = core.calls.last { it.first == "player.play" }.second
        assertEquals("m1", (a?.get("item_id") as? kotlinx.serialization.json.JsonPrimitive)?.content)
        assertFalse("没按过确认却传了 media_source_id:核心层的版本正则会被整个跳过", a!!.containsKey("media_source_id"))
    }

    @Test fun 播放页OSD收起时菜单键和媒体键都有反应() {
        mount(TvRoute.Player("sh6", "幕府将军 · S1E06 妾之王国")) { player() }
        listOf(2170.0, 2171.0, 2172.0).forEach { core.tick(it); advance(rule, 100) }
        advance(rule, 5600)
        rule.onNode(hasText("字幕")).assertDoesNotExist()
        press(rule, Key.MediaPlayPause)
        assertTrue("播放/暂停媒体键没发到核心层", core.calls.any { it.first == "player.setPause" })
        rule.onNode(hasText("字幕")).assertDoesNotExist()
        press(rule, Key.Menu)
        rule.onNode(hasText("画面比例")).assertExists()
    }

    @Test fun 播放页返回键先收OSD再按两次才退出() {
        mount(TvRoute.Player("sh6", "幕府将军 · S1E06 妾之王国")) { player() }
        listOf(2170.0, 2171.0, 2172.0).forEach { core.tick(it); advance(rule, 100) }
        pressBack()
        assertEquals("OSD 显示时返回 = 收起 OSD,不退页", 2, nav.stack.size)
        pressBack()
        assertEquals("第一次返回只唤出 OSD 并提示", 2, nav.stack.size)
        pressBack()
        assertEquals("2 秒内再按才退出", 1, nav.stack.size)
    }
}
