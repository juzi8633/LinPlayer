package xyz.linplayer.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.linplayer.app.ui.player.advancedNaturally

/**
 * ☠ 续播时第一次状态就从 0 跳到续播点。把那一下当成「在播了」,
 * 黑幕提前撤掉,而起播失败(一帧没出就 eof)会被当成「播完」静默退出。
 */
class PlayerProgressTest {
    @Test fun 续播点那一跳不算在播() {
        assertFalse("0 → 1234 秒是一次跳转,不是播放", advancedNaturally(0.0, 1234.5))
    }

    @Test fun 正常一拍算在播() {
        assertTrue(advancedNaturally(1234.5, 1234.75))
        // 4 Hz 状态 × 4 倍速,一拍最多走 1 秒
        assertTrue(advancedNaturally(10.0, 11.0))
    }

    @Test fun 原地不动和往回跳都不算() {
        assertFalse(advancedNaturally(50.0, 50.0))
        assertFalse(advancedNaturally(50.0, 12.0))
    }
}
