package xyz.linplayer.app.tv

import android.app.Application
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
 * 插件页在 TV 上的焦点(SPEC 7.8,D31 D424;D543 的「焦点」那条)。
 *
 * ☠ **TV 上不可聚焦的元素等于不存在**:遥控器进不去,整页没有落点时方向键直接失灵
 * (TvNav.kt 顶上那条「最经典的 P0」)。而这件事在**静态截图上完全看不出来** ——
 * 截图里按钮画得好好的,只是按不到。所以必须有按键驱动的断言。
 *
 * 这里不起真运行时:ops 直接喂给渲染器,测的是「同样一串 ops,TV 上能不能用遥控器走」。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Television1080p, sdk = [36], application = Application::class)
class PluginUiFocusTest {
    @get:Rule val rule = createComposeRule()

    @Before fun boot() {
        PageCache.clear()
        FakeImages.install(ApplicationProvider.getApplicationContext())
    }

    /** 一串 ops:根下一个 Column,里面两个 Button。和真插件发出来的形状一样。 */
    private fun twoButtons(): List<JsonObject> {
        fun op(vararg p: Pair<String, Any>) = JsonObject(p.associate { (k, v) ->
            k to when (v) {
                is Int -> JsonPrimitive(v)
                is String -> JsonPrimitive(v)
                else -> JsonPrimitive(v.toString())
            }
        })
        return listOf(
            op("op" to "create", "id" to 1, "type" to "Column"),
            op("op" to "insert", "parent" to 0, "id" to 1),
            op("op" to "create", "id" to 2, "type" to "Button"),
            JsonObject(mapOf(
                "op" to JsonPrimitive("props"), "id" to JsonPrimitive(2),
                "set" to JsonObject(mapOf(
                    "title" to JsonPrimitive("第一个"),
                    "onPress" to JsonObject(mapOf("\$fn" to JsonPrimitive(11))),
                )),
            )),
            op("op" to "insert", "parent" to 1, "id" to 2),
            op("op" to "create", "id" to 3, "type" to "Button"),
            JsonObject(mapOf(
                "op" to JsonPrimitive("props"), "id" to JsonPrimitive(3),
                "set" to JsonObject(mapOf(
                    "title" to JsonPrimitive("第二个"),
                    "onPress" to JsonObject(mapOf("\$fn" to JsonPrimitive(12))),
                )),
            )),
            op("op" to "insert", "parent" to 1, "id" to 3),
        )
    }

    private fun mountPluginPage(): FakeCore {
        val core = FakeCore().loggedIn()
        core.on("plugin.ui.mount") {
            JsonObject(mapOf(
                "surface" to JsonPrimitive("s1"),
                "frame" to JsonPrimitive(1),
                "ops" to JsonArray(twoButtons()),
            ))
        }
        core.ret("plugin.ui.unmount", JsonObject(emptyMap()))
        core.ret("plugin.ui.event", JsonObject(emptyMap()))
        core.ret("plugin.ui.viewport", JsonObject(emptyMap()))
        val app = AppState(core, CoroutineScope(SupervisorJob() + Dispatchers.Main))
        runBlocking { app.boot() }
        val nav = TvNav().apply { push(TvRoute.PluginPage("alice/demo", "p", "插件页")) }
        rule.mainClock.autoAdvance = false
        rule.setContent { TvFrame(app) { TvShell(nav) } }
        advance(rule, 1200)
        return core
    }

    @Test fun 进插件页焦点自动落在第一个可交互元素上() {
        mountPluginPage()
        // 先确认真画出来了 —— 画不出来的话下面那条断言失败的原因会指错地方
        rule.onNode(hasText("第一个")).assertExists()
        rule.onNode(hasText("第二个")).assertExists()
        // ☠ 没有落点时遥控器整页失灵,而截图上按钮画得好好的
        rule.onNode(hasTestTag("plug.2")).assertIsFocused()
    }

    @Test fun 方向键能在插件画的按钮之间走() {
        mountPluginPage()
        press(rule, Key.DirectionDown)
        rule.onNode(hasTestTag("plug.3")).assertIsFocused()
        press(rule, Key.DirectionUp)
        rule.onNode(hasTestTag("plug.2")).assertIsFocused()
    }

    /*
     * 「三端示例页一致」的**真判据**(D543):不是像素相同 —— 三端主题、密度、字体本来就不同 ——
     * 而是**没有哪一端把组件悄悄降级成占位**。
     *
     * ☠ 占位块只有一行小字,截图上一眼扫过去发现不了,而它正好破掉「三端长得一样」这句话。
     * 这里把示例页用到的组件挨个喂一遍,任何一个渲染成占位都算红。
     */
    @Test fun 示例页用到的组件一个都不许降级成占位() {
        val types = listOf(
            "Badge", "Button", "Canvas", "Checkbox", "Chip", "ChipGroup", "Column",
            "DetailHeader", "Divider", "EmptyState", "EpisodeGrid", "FilterPanel", "Icon",
            "Image", "LineTabs", "Markdown", "Player", "PosterCard", "PosterGrid",
            "PosterRow", "Pressable", "ProgressBar", "RatingList", "Row", "ScrollView",
            "Select", "ServerCard", "SettingsGroup", "SettingsRow", "Skeleton", "Slider",
            "Spinner", "Stack", "Switch", "Tabs", "Text", "TextInput", "View",
            "VirtualGrid", "VirtualList", "WebView",
        )
        val core = FakeCore().loggedIn()
        core.on("plugin.ui.mount") {
            val ops = mutableListOf<JsonObject>()
            ops += JsonObject(mapOf(
                "op" to JsonPrimitive("create"), "id" to JsonPrimitive(1), "type" to JsonPrimitive("Column")))
            ops += JsonObject(mapOf(
                "op" to JsonPrimitive("insert"), "parent" to JsonPrimitive(0), "id" to JsonPrimitive(1)))
            types.forEachIndexed { i, t ->
                val id = 10 + i
                ops += JsonObject(mapOf(
                    "op" to JsonPrimitive("create"), "id" to JsonPrimitive(id), "type" to JsonPrimitive(t)))
                ops += JsonObject(mapOf(
                    "op" to JsonPrimitive("insert"), "parent" to JsonPrimitive(1), "id" to JsonPrimitive(id)))
            }
            JsonObject(mapOf(
                "surface" to JsonPrimitive("s1"), "frame" to JsonPrimitive(1),
                "ops" to JsonArray(ops),
            ))
        }
        core.ret("plugin.ui.unmount", JsonObject(emptyMap()))
        core.ret("plugin.ui.event", JsonObject(emptyMap()))
        core.ret("plugin.ui.viewport", JsonObject(emptyMap()))
        val app = AppState(core, CoroutineScope(SupervisorJob() + Dispatchers.Main))
        runBlocking { app.boot() }
        val nav = TvNav().apply { push(TvRoute.PluginPage("alice/demo", "p", "插件页")) }
        rule.mainClock.autoAdvance = false
        rule.setContent { TvFrame(app) { TvShell(nav) } }
        advance(rule, 1200)

        // 占位块上写着「需要更新 LinPlayer」—— 一个都不该有
        rule.onAllNodes(hasText("需要更新 LinPlayer", substring = true)).fetchSemanticsNodes().let { bad ->
            if (bad.isNotEmpty()) {
                throw AssertionError("有 ${bad.size} 个组件被降级成占位 —— 这一端和别的端画的不是同一页")
            }
        }
    }

    /*
     * 纯展示的虚拟列表在 TV 上也必须能走。
     *
     * ☠ 2026-09-20 在模拟器上量一千项帧率时撞到:80 次下键只产生 10 帧 ——
     * 项全是 Text,没有一个能吃焦点,焦点全程停在侧边栏,列表一行都没滚。
     * 帧率数字当时「看起来只是量不准」,真因是这一页遥控器**根本进不去**。
     */
    @Test fun 纯文本的虚拟列表也能用遥控器滚() {
        val core = FakeCore().loggedIn()
        core.on("plugin.ui.mount") {
            val ops = mutableListOf<JsonObject>()
            ops += JsonObject(mapOf(
                "op" to JsonPrimitive("create"), "id" to JsonPrimitive(1), "type" to JsonPrimitive("VirtualList")))
            ops += JsonObject(mapOf(
                "op" to JsonPrimitive("props"), "id" to JsonPrimitive(1),
                "set" to JsonObject(mapOf(
                    "itemCount" to JsonPrimitive(1000),
                    "firstIndex" to JsonPrimitive(0),
                    "itemHeight" to JsonPrimitive(56),
                    "style" to JsonObject(mapOf("height" to JsonPrimitive(600))),
                ))))
            ops += JsonObject(mapOf(
                "op" to JsonPrimitive("insert"), "parent" to JsonPrimitive(0), "id" to JsonPrimitive(1)))
            repeat(24) { i ->
                val id = 100 + i
                ops += JsonObject(mapOf(
                    "op" to JsonPrimitive("create"), "id" to JsonPrimitive(id), "type" to JsonPrimitive("Text")))
                ops += JsonObject(mapOf(
                    "op" to JsonPrimitive("text"), "id" to JsonPrimitive(id), "value" to JsonPrimitive("第 $i 项")))
                ops += JsonObject(mapOf(
                    "op" to JsonPrimitive("insert"), "parent" to JsonPrimitive(1), "id" to JsonPrimitive(id)))
            }
            JsonObject(mapOf(
                "surface" to JsonPrimitive("s1"), "frame" to JsonPrimitive(1), "ops" to JsonArray(ops)))
        }
        core.ret("plugin.ui.unmount", JsonObject(emptyMap()))
        core.ret("plugin.ui.event", JsonObject(emptyMap()))
        core.ret("plugin.ui.viewport", JsonObject(emptyMap()))
        val app = AppState(core, CoroutineScope(SupervisorJob() + Dispatchers.Main))
        runBlocking { app.boot() }
        val nav = TvNav().apply { push(TvRoute.PluginPage("alice/demo", "p", "插件页")) }
        rule.mainClock.autoAdvance = false
        rule.setContent { TvFrame(app) { TvShell(nav) } }
        advance(rule, 1200)

        rule.onNode(hasText("第 0 项")).assertExists()
        rule.onNode(hasTestTag("plug.list.1.0")).assertIsFocused()
        press(rule, Key.DirectionDown)
        rule.onNode(hasTestTag("plug.list.1.1")).assertIsFocused()
    }

    @Test fun 按下确认把回调号原样发回核心层() {
        val core = mountPluginPage()
        press(rule, Key.Enter)
        advance(rule, 300)
        val ev = core.calls.filter { it.first == "plugin.ui.event" }
        if (ev.isEmpty()) throw AssertionError("按了确认却没发 plugin.ui.event —— 按钮是死的")
        val fn = (ev.last().second?.get("fn") as? JsonPrimitive)?.content
        if (fn != "11") throw AssertionError("发回去的回调号是 $fn,应该是第一个按钮的 11")
    }
}
