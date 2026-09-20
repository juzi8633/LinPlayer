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
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
 * 详情页注入位(SPEC 6.2 D155 D159)在 TV 上的实装。
 *
 * ☠ 「装了插件设了没反应」和「块画出来了但遥控器进不去」在**静态截图上一模一样**,
 * 所以这里全是按键驱动的断言:插件块要进得去,官方原有的焦点顺序不许被它打断。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Television1080p, sdk = [36], application = Application::class)
class PluginAnchorTest {
    @get:Rule val rule = createComposeRule()

    @Before fun boot() {
        PageCache.clear()
        FakeImages.install(ApplicationProvider.getApplicationContext())
    }

    /** 一个按钮的块,和真插件发出来的 ops 形状一样。 */
    private fun oneButton(title: String) = buildJsonArray {
        add(buildJsonObject { put("op", "create"); put("id", 1); put("type", "Button") })
        add(buildJsonObject {
            put("op", "props"); put("id", 1)
            put("set", buildJsonObject {
                put("title", title)
                put("onPress", buildJsonObject { put("\$fn", 9) })
            })
        })
        add(buildJsonObject { put("op", "insert"); put("parent", 0); put("id", 1) })
    }

    private fun anchor(a: String, mode: String) = buildJsonObject {
        put("plugin_id", "alice/demo"); put("name", "示例插件")
        put("anchor", a); put("mode", mode); put("block", "b-$a")
    }

    private fun open(vararg anchors: JsonObject): FakeCore {
        val core = FakeCore().loggedIn().movie()
        core.ret("plugin.anchors", JsonArray(anchors.toList()))
        core.on("plugin.ui.mount") {
            JsonObject(mapOf(
                "surface" to JsonPrimitive("s1"),
                "ops" to oneButton("插件按钮"),
            ))
        }
        core.ret("plugin.ui.unmount", JsonObject(emptyMap()))
        core.ret("plugin.ui.event", JsonObject(emptyMap()))
        core.ret("plugin.ui.viewport", JsonObject(emptyMap()))
        val app = AppState(core, CoroutineScope(SupervisorJob() + Dispatchers.Main))
        runBlocking { app.boot() }
        val nav = TvNav().apply { push(TvRoute.Detail("m1", "Movie")) }
        rule.mainClock.autoAdvance = false
        rule.setContent { TvFrame(app) { TvShell(nav) } }
        advance(rule, 1500)
        return core
    }

    @Test fun 插进动作排后面的块遥控器进得去() {
        open(anchor("detail.actions", "after"))
        rule.onNode(hasText("插件按钮")).assertExists()
        // 初始焦点仍归播放键:插件块不许把进页焦点抢走
        rule.onNode(hasTestTag("detail.play")).assertIsFocused()
        press(rule, Key.DirectionDown)
        rule.onNode(hasText("插件按钮")).assertIsFocused()
    }

    @Test fun hide_模式下官方那一块不画也不放插件块() {
        open(anchor("detail.overview", "hide"))
        rule.onNode(hasText("保罗·厄崔迪与契妮和弗雷曼人联手", substring = true)).assertDoesNotExist()
        rule.onNode(hasText("插件按钮")).assertDoesNotExist()
    }

    @Test fun replace_模式下插件块顶掉官方那一块() {
        open(anchor("detail.overview", "replace"))
        rule.onNode(hasText("保罗·厄崔迪与契妮和弗雷曼人联手", substring = true)).assertDoesNotExist()
        rule.onNode(hasText("插件按钮")).assertExists()
    }
}
