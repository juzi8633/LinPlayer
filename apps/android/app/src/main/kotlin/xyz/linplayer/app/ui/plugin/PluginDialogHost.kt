package xyz.linplayer.app.ui.plugin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.JsonPrimitive
import xyz.linplayer.app.data.bool
import xyz.linplayer.app.data.str
import xyz.linplayer.app.plugin.PluginDialogs
import xyz.linplayer.app.ui.components.BtnKind
import xyz.linplayer.app.ui.components.LpButton
import xyz.linplayer.app.ui.components.LpCell
import xyz.linplayer.app.ui.components.LpDialog
import xyz.linplayer.app.ui.components.LpField
import xyz.linplayer.app.ui.theme.Lp
import xyz.linplayer.app.ui.theme.Sp

/**
 * 手机形态的插件对话框(SPEC 7.10)。挂在根上:插件可能在任何一页上问。
 *
 * ★ 点遮罩 / 返回键都算**取消** —— [PluginDialogs.cancelValue] 一处定口径,
 *   不然「点外面关掉」和「按取消」会给插件两种回值。
 */
@Composable
fun PhonePluginDialog() {
    val req by PluginDialogs.current
    val r = req ?: return
    val cancel = { r.reply(PluginDialogs.cancelValue(r.op)) }
    val title = r.args.str("title")
    val message = r.args.str("message")

    LpDialog(onDismiss = cancel, title = title) {
        if (!message.isNullOrEmpty()) {
            androidx.compose.material3.Text(message, color = Lp.colors.fg2, fontSize = 13.sp)
            Spacer(Modifier.height(Sp.x16))
        }
        when (r.op) {
            "ui.prompt" -> {
                var text by remember(r) { mutableStateOf(r.args.str("value") ?: "") }
                LpField(text, { text = it }, r.args.str("placeholder") ?: "",
                    Modifier.fillMaxWidth(), password = r.args.bool("secret"))
                Spacer(Modifier.height(Sp.x16))
                Buttons("确定", "取消", danger = false,
                    onOk = { r.reply(JsonPrimitive(text)) }, onCancel = cancel)
            }
            "ui.select" -> Column {
                // 选项多到超过半屏时自己滚:不滚的话取消键被顶到屏幕外,只剩点遮罩一条退路
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    PluginDialogs.options(r.args).forEach { (value, label) ->
                        LpCell(label) { r.reply(JsonPrimitive(value)) }
                    }
                }
                Spacer(Modifier.height(Sp.x12))
                LpButton("取消", cancel, Modifier.fillMaxWidth(), BtnKind.Ghost)
            }
            else -> Buttons(
                r.args.str("ok") ?: "确定", r.args.str("cancel") ?: "取消", r.args.bool("danger"),
                onOk = { r.reply(JsonPrimitive(true)) }, onCancel = { r.reply(JsonPrimitive(false)) })
        }
    }
}

@Composable
private fun Buttons(ok: String, cancel: String, danger: Boolean, onOk: () -> Unit, onCancel: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Sp.x12)) {
        LpButton(cancel, onCancel, Modifier.weight(1f), BtnKind.Secondary)
        LpButton(ok, onOk, Modifier.weight(1f), if (danger) BtnKind.Danger else BtnKind.Primary)
    }
}
