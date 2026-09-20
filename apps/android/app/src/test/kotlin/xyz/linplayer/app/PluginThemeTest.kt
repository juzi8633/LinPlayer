package xyz.linplayer.app

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.linplayer.app.ui.theme.DarkColors
import xyz.linplayer.app.ui.theme.PluginTheme

/**
 * 主题 JSON 的解释(SPEC 11.4)。色号那条是判据:
 * `#RRGGBBAA` 按 `#AARRGGBB` 解会得到一个几乎全透明的底色,而那在编译期看不出来。
 */
class PluginThemeTest {
    @Test fun 色号是RRGGBBAA不是AARRGGBB() {
        assertEquals(Color(0xFF0B0D12), PluginTheme.rgba("#0B0D12FF"))
        assertEquals(Color(0x807C9CFF), PluginTheme.rgba("#7C9CFF80"))
        assertEquals(Color(0xFF7C9CFF), PluginTheme.rgba("#7C9CFF"))
    }

    @Test fun token与列数真落到官方刻度上() {
        PluginTheme.apply(PluginTheme.json("""
            {"schema":1,"modes":["dark","light"],
             "tokens":{"dark":{"color.bg":"#0B0D12FF","color.accent":"#7C9CFFFF","radius.card":10}},
             "layout":{"posterColumns":{"compact":4,"expanded":6}},
             "components":{"PosterCard":{"default":{"radius":"token:radius.card"}}}}
        """.trimIndent()), "android")
        assertEquals(Color(0xFF0B0D12), DarkColors.bg)
        assertEquals(Color(0xFF7C9CFF), DarkColors.acc)
        assertEquals(10.0, PluginTheme.number("radius.card")!!, 0.0)
        assertEquals(4, PluginTheme.posterColumns(411, 3))
        assertEquals(6, PluginTheme.posterColumns(1280, 3))
        // 没给 medium 就用官方的,不许自己插值
        assertEquals(3, PluginTheme.posterColumns(800, 3))
    }
}
