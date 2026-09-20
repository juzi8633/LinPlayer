package xyz.linplayer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.linplayer.app.ui.plugin.WallpaperGate

/**
 * 视频壁纸的三个暂停条件是**与**(D443)。
 *
 * ☠ 判据在第三条:息屏回来(ON_START)之后,省电模式还开着就**不许恢复**。
 *   写成「最后一个事件说了算」的话这一条会变绿灯 —— 而用户开省电模式
 *   正是为了别让后台有东西在解码。
 */
class WallpaperGateTest {
    @Before fun clean() = WallpaperGate.reset()

    @Test fun 三条都允许才放() {
        assertTrue(WallpaperGate.mayPlay)
        WallpaperGate.set(WallpaperGate.PLAYER, true)
        assertFalse(WallpaperGate.mayPlay)
        WallpaperGate.set(WallpaperGate.PLAYER, false)
        assertTrue(WallpaperGate.mayPlay)
    }

    @Test fun 息屏回来但省电模式还开着不许放() {
        WallpaperGate.set(WallpaperGate.POWER_SAVE, true)
        WallpaperGate.set(WallpaperGate.BACKGROUND, true)
        WallpaperGate.set(WallpaperGate.BACKGROUND, false)   // ON_START
        assertFalse("省电模式还开着就不能恢复", WallpaperGate.mayPlay)
        assertEquals("powerSave", WallpaperGate.pausedBy)
    }

    @Test fun 退出播放页但还在后台不许放() {
        WallpaperGate.set(WallpaperGate.BACKGROUND, true)
        WallpaperGate.set(WallpaperGate.PLAYER, true)
        WallpaperGate.set(WallpaperGate.PLAYER, false)       // player.end
        assertFalse(WallpaperGate.mayPlay)
        assertEquals("background", WallpaperGate.pausedBy)
    }

    @Test fun 三条全解除才回到放() {
        listOf(WallpaperGate.PLAYER, WallpaperGate.BACKGROUND, WallpaperGate.POWER_SAVE)
            .forEach { WallpaperGate.set(it, true) }
        assertEquals("background+player+powerSave", WallpaperGate.pausedBy)
        listOf(WallpaperGate.PLAYER, WallpaperGate.BACKGROUND).forEach { WallpaperGate.set(it, false) }
        assertFalse(WallpaperGate.mayPlay)
        WallpaperGate.set(WallpaperGate.POWER_SAVE, false)
        assertTrue(WallpaperGate.mayPlay)
    }
}
