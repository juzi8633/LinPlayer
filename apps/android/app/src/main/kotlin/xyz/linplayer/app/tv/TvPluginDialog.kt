package xyz.linplayer.app.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.serialization.json.JsonPrimitive
import xyz.linplayer.app.data.bool
import xyz.linplayer.app.data.str
import xyz.linplayer.app.plugin.PluginDialogs
import xyz.linplayer.app.tv.kit.PanelItem
import xyz.linplayer.app.tv.kit.TvButton
import xyz.linplayer.app.tv.kit.TvC
import xyz.linplayer.app.tv.kit.initialFocus
import xyz.linplayer.app.tv.kit.TvR
import xyz.linplayer.app.tv.kit.TvSp
import xyz.linplayer.app.tv.kit.TvText
import xyz.linplayer.app.tv.kit.TvW
import xyz.linplayer.app.tv.kit.tvType

/**
 * TV 上的插件对话框(SPEC 7.10 + 7.8)。
 *
 * ☠ 遥控器能不能按掉,截图上看不出来。所以用 [Dialog] 而不是在页面上叠一层 Box ——
 *   叠层的话方向键会走进背后那一页的控件里,而焦点一旦出去就再也回不来。
 *   独立窗口天然圈住焦点、天然吃掉返回键;剩下的只有**初始落点**([TvButton] 的 focused)。
 */
@Composable
fun TvPluginDialog() {
    val req by PluginDialogs.current
    val r = req ?: return
    val t = tvType
    val cancel = { r.reply(PluginDialogs.cancelValue(r.op)) }

    Dialog(onDismissRequest = cancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(TvC.scrim), contentAlignment = Alignment.Center) {
            Column(
                Modifier.width(560.dp).heightIn(max = 520.dp).clip(TvR.lg)
                    .background(TvC.surface2).padding(TvSp.x24).focusGroup(),
            ) {
                r.args.str("title")?.takeIf { it.isNotEmpty() }?.let {
                    TvText(it, t.title, TvC.fg, weight = TvW.semi)
                    Spacer(Modifier.height(TvSp.x8))
                }
                r.args.str("message")?.takeIf { it.isNotEmpty() }?.let {
                    TvText(it, t.body, TvC.fg2, maxLines = 6)
                    Spacer(Modifier.height(TvSp.x16))
                }
                when (r.op) {
                    "ui.prompt" -> {
                        var text by remember(r) { mutableStateOf(r.args.str("value") ?: "") }
                        // 初始焦点给输入框而不是确认键:进来就能打字,系统输入法跟着焦点起
                        TvTextField(text, { text = it }, r.args.str("placeholder") ?: "", 512.dp,
                            Modifier.initialFocus(true), password = r.args.bool("secret"))
                        Spacer(Modifier.height(TvSp.x16))
                        Buttons("确定", "取消", false, { r.reply(JsonPrimitive(text)) }, cancel, focusFirst = false)
                    }
                    "ui.select" -> {
                        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                            PluginDialogs.options(r.args).forEachIndexed { i, (value, label) ->
                                PanelItem(label, focused = i == 0) { r.reply(JsonPrimitive(value)) }
                            }
                        }
                        Spacer(Modifier.height(TvSp.x12))
                        TvButton("取消", onClick = cancel)
                    }
                    else -> Buttons(
                        r.args.str("ok") ?: "确定", r.args.str("cancel") ?: "取消", r.args.bool("danger"),
                        { r.reply(JsonPrimitive(true)) }, { r.reply(JsonPrimitive(false)) })
                }
            }
        }
    }
}

@Composable
private fun Buttons(
    ok: String, cancel: String, danger: Boolean,
    onOk: () -> Unit, onCancel: () -> Unit, focusFirst: Boolean = true,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(TvSp.x12)) {
        // 初始焦点落在确认键;危险操作(删除 / 清空)改落在取消键上,误按一下不会当场生效
        TvButton(ok, primary = true, focused = focusFirst && !danger, onClick = onOk)
        TvButton(cancel, focused = focusFirst && danger, onClick = onCancel)
    }
}
