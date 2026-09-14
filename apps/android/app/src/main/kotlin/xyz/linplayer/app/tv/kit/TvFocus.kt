package xyz.linplayer.app.tv.kit

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp

/*
 * 滚动跟随(UI_TV.md §4.3)。草稿和真页面用同一份,否则草稿图和真机长得不一样。
 */

/**
 * 横向行:焦点项左缘钉在行首。
 *
 * ★ **必须显式提供。** 真电视上 Lazy 列表的默认值是「焦点项停在 30% 处」,
 *   手机和截图环境里不是 —— 不显式给,草稿图和真机长得不一样。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProvideRowKeyline(lead: Dp, content: @Composable () -> Unit) {
    val px = with(LocalDensity.current) { lead.toPx() }
    val spec = remember(px) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float) = offset - px
        }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}

/**
 * 首页这种「一行一段」的纵向页:焦点项钉在距顶 [above] 处,上下一样(草稿 02)。
 * ★ 第一段要贴真顶:让整段去请求露出(不只是段里的按钮),滚动量被 0 夹住。见 HomePage 的 Hero。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProvideColumnKeyline(above: Dp, content: @Composable () -> Unit) {
    val a = with(LocalDensity.current) { above.toPx() }
    val spec = remember(a) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float) = offset - a
        }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}

/** 纵向页:往上多露出一段(行标题),往下只保证焦点项完整露出。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProvideColumnSpec(above: Dp, below: Dp, content: @Composable () -> Unit) {
    val d = LocalDensity.current
    val a = with(d) { above.toPx() }
    val b = with(d) { below.toPx() }
    val spec = remember(a, b) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = when {
                offset < a -> offset - a
                offset + size > containerSize - b -> offset + size - (containerSize - b)
                else -> 0f
            }
        }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}
