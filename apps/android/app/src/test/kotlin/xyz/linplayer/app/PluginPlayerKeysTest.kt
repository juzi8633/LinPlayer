package xyz.linplayer.app

import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import xyz.linplayer.app.plugin.PluginPlayer
import xyz.linplayer.app.ui.plugin.PlayerSurfaceInfo
import xyz.linplayer.app.ui.plugin.PluginKeys
import xyz.linplayer.app.ui.plugin.pluginPanelKind

/**
 * 按键名与面板名都是**公开契约**(plugin-sdk.d.ts 的 `PlayerKey` / `PlayerPanel`):
 * 翻错一个字插件那头收到的是一个它没见过的键,而两边都不报错。
 */
class PluginPlayerKeysTest {
    @Test fun 遥控键翻成SDK的PlayerKey() {
        val want = mapOf(
            Key.DirectionUp to "up", Key.DirectionDown to "down",
            Key.DirectionLeft to "left", Key.DirectionRight to "right",
            Key.DirectionCenter to "ok", Key.Enter to "ok", Key.NumPadEnter to "ok",
            Key.Back to "back", Key.Menu to "menu", Key.Info to "info",
            Key.ChannelUp to "channelUp", Key.ChannelDown to "channelDown",
            Key.MediaPlayPause to "playPause", Key.MediaStop to "stop",
            Key.Zero to "0", Key.Seven to "7", Key.Nine to "9", Key.NumPad3 to "3",
        )
        want.forEach { (k, name) -> assertEquals(name, PluginKeys.nameOf(k)) }
    }

    @Test fun 表外的键不问插件() {
        listOf(Key.A, Key.VolumeUp, Key.Escape).forEach { assertNull(PluginKeys.nameOf(it)) }
    }

    @Test fun 手机面板名对得上() {
        assertEquals("subtitle", PluginPlayer.phoneKind("subtitles", false))
        assertEquals("source", PluginPlayer.phoneKind("lines", false))
        assertEquals("quality", PluginPlayer.phoneKind("enhance", false))
        listOf("audio", "danmaku", "episodes", "more")
            .forEach { assertEquals(it, PluginPlayer.phoneKind(it, false)) }
    }

    @Test fun 这一端没有的面板抛unsupported而不是静默() {
        // Exo 内核下画面增强是空转的,开了等于给插件一颗没反应的按钮
        assertThrows(UnsupportedOperationException::class.java) { PluginPlayer.phoneKind("enhance", true) }
        assertThrows(UnsupportedOperationException::class.java) { PluginPlayer.phoneKind("speed", false) }
        assertThrows(UnsupportedOperationException::class.java) { PluginPlayer.phoneKind("nope", false) }
        listOf("speed", "enhance", "lines", "nope")
            .forEach { assertThrows(UnsupportedOperationException::class.java) { PluginPlayer.tvKind(it) } }
    }

    @Test fun TV面板名对得上() {
        assertEquals("sub", PluginPlayer.tvKind("subtitles"))
        listOf("audio", "danmaku", "episodes", "more")
            .forEach { assertEquals(it, PluginPlayer.tvKind(it)) }
    }

    /**
     * 挂载用的 kind 和闸门认的那张表必须对上 —— 对不上的表现正是这次要修的那条:
     * 插件声明了 `playerOverlays`,而 `player.onKey` 一次都不回调,两边都不报错。
     */
    @Test fun 覆盖层进按键闸门侧栏页不进() {
        assertTrue("overlay" in PluginKeys.playerKinds)
        // 侧栏页进了这张表,它开着时方向键会被闸门抢走,插件自己的面板反而按不动
        assertFalse("panel" in PluginKeys.playerKinds)
    }

    @Test fun 插件侧栏标签的面板名能查回那一行() {
        val rows = listOf(
            PlayerSurfaceInfo("linplayer/live", "guide", "节目单", "list", false),
            PlayerSurfaceInfo("linplayer/live", "notes", "剧情说明", "", true),
        )
        rows.forEach { s ->
            assertEquals(s, rows.firstOrNull { pluginPanelKind(it) == pluginPanelKind(s) })
        }
        assertEquals(2, rows.map { pluginPanelKind(it) }.toSet().size)
    }
}
