package xyz.linplayer.app.ui.plugin

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.bool
import xyz.linplayer.app.data.obj
import xyz.linplayer.app.ui.pages.args

/**
 * 插件接管按键与返回键(D563 D85)。两条都是**壳先问、插件答**:
 * 哪一层在最上面只有壳知道,核心层不可能自己判。
 */
object PluginKeys {
    /**
     * 画面上挂着的插件覆盖层(插件 id,后挂的排在后面)。
     *
     * 空表 = 播放页一句都不问核心层。遥控器上每按一下都要过这个判断,
     * 多一次跨语言调用就是多一次发黏。
     */
    val playerOverlays = mutableStateListOf<String>()

    /**
     * 会盖在播放画面上的 surface kind。
     *
     * 侧栏页(`panel`)**故意不在表里**:它开着的时候方向键归 Compose 焦点,
     * 进了这张表就会被这里先抢走,插件自己的面板反而按不动。
     */
    val playerKinds = setOf("overlay", "osd")

    /** Compose 键 → SDK 的 `PlayerKey`。表里没有的键不问插件,按默认处理。 */
    fun nameOf(k: Key): String? = when (k) {
        Key.DirectionUp -> "up"
        Key.DirectionDown -> "down"
        Key.DirectionLeft -> "left"
        Key.DirectionRight -> "right"
        Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.ButtonA -> "ok"
        Key.Back -> "back"
        Key.Menu -> "menu"
        Key.Info -> "info"
        Key.ChannelUp -> "channelUp"
        Key.ChannelDown -> "channelDown"
        Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> "playPause"
        Key.MediaStop -> "stop"
        else -> digitOf(k)
    }

    private fun digitOf(k: Key): String? = (0..9).firstOrNull {
        k == digits[it] || k == numPad[it]
    }?.toString()

    private val digits = listOf(
        Key.Zero, Key.One, Key.Two, Key.Three, Key.Four,
        Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine,
    )
    private val numPad = listOf(
        Key.NumPad0, Key.NumPad1, Key.NumPad2, Key.NumPad3, Key.NumPad4,
        Key.NumPad5, Key.NumPad6, Key.NumPad7, Key.NumPad8, Key.NumPad9,
    )

    /**
     * 播放页的按键闸门。[fallback] 是壳自己的默认处理,[taken] 记「按下时被插件接走的键」。
     *
     * ☠ 主线程上不能同步等核心层。取舍:**没有可见覆盖层时根本不问**;有覆盖层时
     * 当场吃掉这一下,插件回「不接」再补做默认处理 —— 最晚晚 200ms(核心层的上限),
     * 且只发生在覆盖层开着的时候,那种场景下键本来就归插件。
     */
    fun gate(
        app: AppState,
        scope: CoroutineScope,
        taken: MutableSet<Key>,
        e: KeyEvent,
        fallback: (KeyEvent) -> Boolean,
    ): Boolean {
        if (playerOverlays.isEmpty()) return fallback(e)
        // 返回键不走这条路:播放页的返回是 BackHandler 那条四级链,
        // 这里吃掉之后补不回去(OnBackPressedDispatcher 不接受重放)。插件接返回用 nav.onBack
        if (e.key == Key.Back) return fallback(e)
        val name = nameOf(e.key) ?: return fallback(e)
        // 抬起键不再问一遍:合同里一次按键是一个事件,按下时谁接走抬起就归谁
        if (e.type == KeyEventType.KeyUp) return taken.remove(e.key) || fallback(e)
        val plugin = playerOverlays.last()
        scope.launch {
            val consumed = runCatching {
                app.call("plugin.playerKey", args(
                    "key" to name, "repeat" to (e.nativeKeyEvent.repeatCount > 0), "plugin" to plugin))
            }.getOrNull().obj().bool("consumed")
            if (consumed) taken.add(e.key) else fallback(e)
        }
        return true
    }
}

/** 播放页用:一次按键的「按下被谁接走」记在这里,按下与抬起要成对。 */
@Composable
fun rememberPluginKeyTaken(): MutableSet<Key> = remember { mutableSetOf() }

/**
 * 插件页的返回键(D85):插件 `nav.onBack` 回 true 就不退栈。
 *
 * 异步问没有可感知延迟:插件没接 `onBack` 时核心层立刻回 false(不进 JS 线程),
 * 接了也有 200ms 封顶。退栈走 [xyz.linplayer.app.plugin.PluginNav] 已经登记的那一条,
 * 两端各自的栈不在这里分叉。
 */
@Composable
fun PluginBackGate(plugin: String) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    BackHandler {
        scope.launch {
            val handled = runCatching { app.call("plugin.backRequest", args("plugin" to plugin)) }
                .getOrNull().obj().bool("handled")
            if (!handled) xyz.linplayer.app.plugin.PluginNav.host?.back()
        }
    }
}
