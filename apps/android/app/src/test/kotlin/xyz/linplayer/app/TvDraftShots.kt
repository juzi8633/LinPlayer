package xyz.linplayer.app

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.linplayer.app.ui.drafts.tv.TvDraftFrame
import xyz.linplayer.app.ui.drafts.tv.TypeA
import xyz.linplayer.app.ui.drafts.tv.TypeB
import xyz.linplayer.app.ui.drafts.tv.tvDrafts

/**
 * TV 草稿逐张出图(UI_TV.md §14.3)。
 *
 * `./gradlew :app:testDebugUnitTest -Proborazzi.test.record=true --tests '*TvDraftShots*'`
 * → `app/build/tvdrafts/NN-B.png`。截图不进仓库。
 *
 * ★ application 换成空的 Application:清单里的 LinPlayerApp 一起来就加载 libmpv,
 *   JVM 上没有 .so,每张图都会死在 UnsatisfiedLinkError 上。
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Television1080p, sdk = [36], application = Application::class)
class TvDraftShots(private val index: Int, private val typeName: String) {
    @get:Rule val rule = createComposeRule()

    @Test fun shot() {
        val draft = tvDrafts[index]
        rule.setContent { TvDraftFrame(if (typeName == "A") TypeA else TypeB) { draft.content() } }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/tvdrafts/%02d-%s.png".format(index + 1, typeName))
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> = tvDrafts.flatMapIndexed { i, d ->
            listOfNotNull(arrayOf<Any>(i, "B"), if (d.alsoA) arrayOf<Any>(i, "A") else null)
        }
    }
}
