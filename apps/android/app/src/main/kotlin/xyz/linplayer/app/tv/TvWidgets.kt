package xyz.linplayer.app.tv

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import coil3.compose.AsyncImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import xyz.linplayer.app.tv.kit.DangerButton
import xyz.linplayer.app.tv.kit.PanelSlot
import xyz.linplayer.app.tv.kit.SidePanel
import xyz.linplayer.app.tv.kit.TvButton
import xyz.linplayer.app.tv.kit.TvC
import xyz.linplayer.app.tv.kit.TvR
import xyz.linplayer.app.tv.kit.TvSp
import xyz.linplayer.app.tv.kit.TvText
import xyz.linplayer.app.tv.kit.TvW
import xyz.linplayer.app.tv.kit.tvType

/** 封面。没到 / 失败都是同一个 `surface1` 色块,**不写「加载失败」**(§5.2)。 */
@Composable
fun TvImage(url: String?, modifier: Modifier = Modifier, scale: ContentScale = ContentScale.Crop) {
    Box(modifier.fillMaxSize().background(TvC.surface1)) {
        if (!url.isNullOrEmpty()) AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = scale)
    }
}

/**
 * 输入框(族 E,§4.5)。**焦点框就是输入框**:焦点到达即拉起输入法,上下键照常换字段。
 *
 * ★ 用 `BasicTextField(value: String)` 这个旧重载:它自带「遥控器方向键直接移出输入框」;
 *   `TextFieldState` 那个新重载没有这层拦截,↑↓ 会被当成光标移到行首行尾吃掉。
 * ★ 提交时机 = 输入法「完成」或失焦,不是每个字;离页时正在输入的内容也要提交。
 */
@Composable
fun TvTextField(
    value: String, onValueChange: (String) -> Unit, placeholder: String, width: Dp,
    modifier: Modifier = Modifier, icon: ImageVector? = null, password: Boolean = false,
    number: Boolean = false, onSubmit: (String) -> Unit = {},
) {
    val t = tvType
    val kb = LocalSoftwareKeyboardController.current
    var focused by remember { mutableStateOf(false) }
    val latest by rememberUpdatedState(value)
    val submit by rememberUpdatedState(onSubmit)
    DisposableEffect(Unit) { onDispose { if (focused) submit(latest) } }
    BasicTextField(
        value = value, onValueChange = onValueChange, singleLine = true,
        modifier = modifier.width(width).height(t.controlH).onFocusChanged { f ->
            if (f.isFocused && !focused) kb?.show()
            if (!f.isFocused && focused) submit(latest)
            focused = f.isFocused
        },
        textStyle = TextStyle(color = TvC.fg, fontSize = t.body),
        cursorBrush = SolidColor(TvC.acc),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (number) KeyboardType.Number else if (password) KeyboardType.Password else KeyboardType.Text,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { submit(latest); kb?.hide() }),
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxSize().clip(TvR.sm).background(if (focused) TvC.surface2 else TvC.surface3)
                    .then(if (focused) Modifier.border(2.dp, TvC.focus, TvR.sm) else Modifier)
                    .padding(horizontal = TvSp.x12),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != null) { Icon(icon, null, Modifier.size(t.iconS), tint = TvC.fg3); Spacer(Modifier.width(TvSp.x8)) }
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) TvText(placeholder, t.body, TvC.fg3)
                    inner()
                }
            }
        },
    )
}

/**
 * 页面上的弹出层:侧面板或确认对话框,同一时刻最多一个。
 *
 * ★ 关掉之后**先让它从树上摘掉,再**把焦点还给打开它的元素 —— 在面板还在的时候要焦点,
 *   会撞上面板自己圈焦点的那道闸。
 */
class Overlay {
    var content by mutableStateOf<(@Composable BoxScope.() -> Unit)?>(null)
    fun open(c: @Composable BoxScope.() -> Unit) { content = c }
    fun close() { content = null }
    val isOpen: Boolean get() = content != null
}

@Composable
fun rememberOverlay(): Overlay {
    val o = remember { Overlay() }
    val mem = LocalFocusMemory.current
    var was by remember { mutableStateOf(false) }
    LaunchedEffect(o.isOpen) {
        if (!o.isOpen && was) repeat(6) {
            withFrameNanos { }
            if (mem.restore()) return@repeat
        }
        was = o.isOpen
    }
    return o
}

@Composable
fun BoxScope.OverlayHost(o: Overlay) {
    o.content?.invoke(this)
}

/**
 * 侧面板(§5.3)。圈住焦点、返回键关面板;[back] 非空 = 二级内容,返回键先回上一级。
 * ★ 默认焦点落在当前生效值上:内容里给那一项传 `focused = true`;没有就落第一项。
 */
@Composable
fun BoxScope.TvSidePanel(
    title: String, onClose: () -> Unit, back: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler { if (back != null) back() else onClose() }
    val fr = remember(title) { FocusRequester() }
    var inside by remember { mutableStateOf(false) }
    LaunchedEffect(title) {
        repeat(8) {
            withFrameNanos { }
            if (inside) return@LaunchedEffect
            runCatching { fr.requestFocus() }
        }
    }
    PanelSlot {
        SidePanel(
            title, back = back != null,
            modifier = Modifier.focusRequester(fr).onFocusChanged { inside = it.hasFocus }
                .focusProperties { onExit = { cancelFocusChange() } }.focusGroup(),
            content = content,
        )
    }
}

/** 确认对话框(§5.4)。只给不可逆操作;「取消」在左且默认聚焦。 */
@Composable
fun BoxScope.TvConfirm(title: String, text: String, confirm: String, onCancel: () -> Unit, onConfirm: () -> Unit) {
    BackHandler(onBack = onCancel)
    val t = tvType
    Column(
        Modifier.align(Alignment.Center).width(360.dp).clip(TvR.lg).background(TvC.surface2).padding(TvSp.x20)
            .focusProperties { onExit = { cancelFocusChange() } }.focusGroup(),
    ) {
        TvText(title, t.title, TvC.fg, weight = TvW.semi, maxLines = 2)
        Spacer(Modifier.height(TvSp.x6))
        TvText(text, t.body, TvC.fg2, maxLines = 4)
        Spacer(Modifier.height(TvSp.x16))
        Row(horizontalArrangement = Arrangement.spacedBy(TvSp.x12)) {
            TvButton("取消", focused = true, onClick = onCancel)
            DangerButton(confirm, onClick = onConfirm)
        }
    }
}

/** 二维码。整块不可聚焦(§7.1:上面没有要按的东西)。 */
@Composable
fun QrImage(text: String, size: Dp, modifier: Modifier = Modifier) {
    val bmp = remember(text) {
        runCatching {
            val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 1))
            val b = Bitmap.createBitmap(m.width, m.height, Bitmap.Config.ARGB_8888)
            for (y in 0 until m.height) for (x in 0 until m.width) {
                b.setPixel(x, y, if (m.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
            b.asImageBitmap()
        }.getOrNull()
    }
    Box(modifier.size(size).clip(TvR.lg).background(androidx.compose.ui.graphics.Color.White).padding(size / 20)) {
        if (bmp != null) Image(bmp, null, Modifier.fillMaxSize(), filterQuality = FilterQuality.None)
    }
}
