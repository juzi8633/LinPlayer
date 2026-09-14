package xyz.linplayer.app.ui.player

/**
 * 「时间真的往前走了」:两次状态之间往前走了一点,但没大到像一次跳转。
 *
 * ☠ 续播时第一次状态就从 0 跳到续播点。拿「比上一次大」判的话起播即放行:
 *   黑幕提前撤,一帧没出就 eof 的起播失败被当成「播完」静默退出。
 * ★ 上限 2 秒:状态 4 Hz,4 倍速一拍最多走 1 秒,留一倍余量。
 */
internal fun advancedNaturally(prev: Double, now: Double): Boolean = now - prev in 0.05..2.0
