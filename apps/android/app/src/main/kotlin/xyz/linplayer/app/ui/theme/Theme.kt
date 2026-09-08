package xyz.linplayer.app.ui.theme

import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 设计 token(UI_MOBILE.md §1)。**刻度是枚举不是区间** ——
 * 要用第五种圆角,先改 UI_MOBILE.md §1.3 那张表,别就地写新值。
 */
@Immutable
data class LpColors(
    val bg: Color, val s1: Color, val s2: Color, val s3: Color,
    val line: Color, val line2: Color,
    val fg: Color, val fg2: Color, val fg3: Color,
    val acc: Color, val accDim: Color, val accFg: Color,
    val ok: Color, val warn: Color, val bad: Color,
    val scrim: Color,
    /** 叠在画面上的玻璃底。**写死色号必须带 alpha** —— 不透明的一律进上面那些 token */
    val chip: Color,
    val isDark: Boolean,
)

/*
 * 调色板照 docs 的手机端草稿(Draft 04「去边界」)。
 *
 * ★ **s1/s2/s3 和 line 全是半透明白**,不是不透明色号。
 *   草稿的核心手法是「分层替代描边」—— 卡片靠一层更亮的膜浮起来,
 *   不靠一圈 1px 边框框住。写成不透明色号的话,叠在取色底上就成了一块死灰补丁。
 */
private val Dark = LpColors(
    bg = Color(0xFF100E14),
    s1 = Color(0x0EFFFFFF), s2 = Color(0x1AFFFFFF), s3 = Color(0x26FFFFFF),
    line = Color(0x12FFFFFF), line2 = Color(0x24FFFFFF),
    fg = Color(0xFFF3EFF8), fg2 = Color(0xFF9B93AE), fg3 = Color(0xFF6E6880),
    // 强调色是**琥珀**不是蓝:草稿里评分、进度、选中态、主按钮渐变都吃它
    acc = Color(0xFFF5A524), accDim = Color(0x2EF5A524), accFg = Color(0xFF20160A),
    ok = Color(0xFF5CD6A0), warn = Color(0xFFF5A524), bad = Color(0xFFFF6B5E),
    scrim = Color(0xB3100E14), chip = Color(0x8C1A1720), isDark = true,
)

// 浅色:同一套语义翻个面。**琥珀在白底上要压暗**,#F5A524 放浅底上是看不清的
private val Light = LpColors(
    bg = Color(0xFFFAF7FC),
    s1 = Color(0x0B000000), s2 = Color(0x13000000), s3 = Color(0x1E000000),
    line = Color(0x0F000000), line2 = Color(0x1F000000),
    fg = Color(0xFF1A1622), fg2 = Color(0xFF6B6478), fg3 = Color(0xFF9A93A8),
    acc = Color(0xFF8A5A00), accDim = Color(0x1F8A5A00), accFg = Color(0xFFFFFBF2),
    ok = Color(0xFF1F7A55), warn = Color(0xFF8A5A00), bad = Color(0xFFC7554E),
    scrim = Color(0xB3FAF7FC), chip = Color(0xC7FFFFFF), isDark = false,
)

/** 间距刻度。**允许的值只有这些** */
object Sp {
    val x0 = 0.dp; val x2 = 2.dp; val x4 = 4.dp; val x6 = 6.dp; val x8 = 8.dp
    val x10 = 10.dp; val x12 = 12.dp; val x16 = 16.dp; val x20 = 20.dp
    val x26 = 26.dp; val x34 = 34.dp; val x48 = 48.dp
}

/** 圆角刻度。8=小件 · 12=卡片 · 18=弹窗面板 · 999=胶囊 */
object R {
    val none = 0.dp; val sm = 8.dp; val md = 12.dp; val lg = 18.dp; val pill = 999.dp
}

/** 固定尺寸。超过 48 的偏移不许写字面数字,抽成这里的具名常量 */
object Dim {
    val topBar = 52.dp
    val tabBar = 58.dp
    val tap = 48.dp
    val hairline = 1.dp
    /* 草稿里那几块「铺到屏幕顶」的图。它们不是间距,是**版面高度**,
       所以抽成具名常量 —— 改了 Hero 高度而没改让位高度的话,底下第一条轨会被压住。 */
    val coverLib = 212.dp    // 媒体库库头(草稿 02)
    val coverDetail = 236.dp // 剧/影详情页背景图(草稿 03)

    /** 底栏总高:三个 Tab + 手势条。**内容从它下面穿过去**,所以列表要按它留白。 */
    val tabClearance = 76.dp
}

/**
 * 首页 Hero 的高度【用户定 2026-09-07:「占首屏的比例有点低,可以做大一点」】。
 *
 * ☠ **写成屏高的比例,不写死 dp。** 原来是固定 392dp —— 在 5 寸小屏上那已经过了半屏,
 *   在 6.7 寸长屏上却只有三分之一。同一个数字在两台机器上是两种版面。
 * ★ 上下都夹一下:再小也得放得下艺术字,再大也不能把下面那条轨整个推出首屏。
 */
@Composable
fun heroHeight(): Dp {
    val h = LocalConfiguration.current.screenHeightDp
    return (h * 0.62f).dp.coerceIn(380.dp, 620.dp)
}

val LocalLpColors = staticCompositionLocalOf { Dark }

/**
 * 动效倍率。跟随系统的「移除动画」设置(UI_MOBILE.md §2.4)。
 * 每个动画都要乘它 —— 散着判会漏,所以挂在 CompositionLocal 上。
 */
val LocalMotionScale: ProvidableCompositionLocal<Float> = compositionLocalOf { 1f }

private fun lpTypography(f: FontFamily?) = Typography(
    displayLarge = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, fontFamily = f),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = f),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, fontFamily = f),
    bodyLarge = TextStyle(fontSize = 14.sp, lineHeight = 21.sp, fontFamily = f),
    bodyMedium = TextStyle(fontSize = 13.sp, fontFamily = f),
    labelLarge = TextStyle(fontSize = 12.sp, fontFamily = f),
    labelSmall = TextStyle(fontSize = 11.sp, fontFamily = f),
)

/**
 * 用户导入的界面字体。
 *
 * ☠ **文件不在就当没设过,不许抛。** 路径存在偏好里,而文件可能被清数据、换机恢复
 *   之后就没了 —— 那时整个应用不该起不来,只是回到默认字体。
 * ★ 用 `Typeface.createFromFile` 而不是 Compose 的 `Font(File)`:后者要 API 26,
 *   本应用 minSdk 是 24。
 */
@Composable
private fun userFontFamily(): FontFamily? {
    val path = xyz.linplayer.app.data.UiPrefs.uiFont.value
    return remember(path) {
        if (path.isBlank()) null
        else runCatching {
            val f = java.io.File(path)
            if (f.isFile) FontFamily(android.graphics.Typeface.createFromFile(f)) else null
        }.getOrNull()
    }
}

/**
 * 主题三态:跟随系统 / 强制深色 / 强制浅色。
 *
 * ★ **不用 Material You 动态取色。** 它会把整个界面染成用户壁纸的颜色,
 * 而本产品的底色是「影院沉浸」的一部分。这是主动放弃 M3 的默认能力(UI_MOBILE.md §1.1)。
 */
@Composable
fun LpTheme(
    darkOverride: Boolean? = null,
    content: @Composable () -> Unit,
) {
    val dark = darkOverride ?: isSystemInDarkTheme()
    val c = if (dark) Dark else Light
    val ctx = LocalContext.current
    val motion = remember(ctx) { animatorScale(ctx) }

    // M3 的 ColorScheme 仍然要给:M3 组件(Slider / Switch / Chip)读的是它。
    // 我们自己的组件读 LocalLpColors,两套值必须一致,否则同一屏上会出现两种蓝
    val scheme = if (dark) darkColorScheme(
        primary = c.acc, onPrimary = c.accFg, background = c.bg, onBackground = c.fg,
        surface = c.s1, onSurface = c.fg, surfaceVariant = c.s2, onSurfaceVariant = c.fg2,
        outline = c.line2, error = c.bad,
    ) else lightColorScheme(
        primary = c.acc, onPrimary = c.accFg, background = c.bg, onBackground = c.fg,
        surface = c.s1, onSurface = c.fg, surfaceVariant = c.s2, onSurfaceVariant = c.fg2,
        outline = c.line2, error = c.bad,
    )

    /* ☠ **光换 Typography 不够。** 全站大半的 `Text(…, fontSize = 13.sp)` 走的是
       `LocalTextStyle`(M3 不把 Typography 灌进去,它的默认值是 `TextStyle.Default`)——
       只改 Typography 的表现是「标题换了字体,正文一个字都没变」。两处一起给。 */
    val family = userFontFamily()
    val typo = remember(family) { lpTypography(family) }
    CompositionLocalProvider(LocalLpColors provides c, LocalMotionScale provides motion) {
        MaterialTheme(colorScheme = scheme, typography = typo) {
            CompositionLocalProvider(
                LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = family),
                content = content,
            )
        }
    }
}

/** 系统的动画时长倍率。开发者选项里关掉动画时是 0f。 */
private fun animatorScale(ctx: android.content.Context): Float = runCatching {
    Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
}.getOrDefault(1f)

/** 本项目自己的 token 入口。写 `Lp.colors.acc` 而不是 `MaterialTheme.colorScheme.primary`。 */
object Lp {
    val colors: LpColors
        @Composable get() = LocalLpColors.current
}

@Suppress("unused")
internal val sdkAtLeast31 = Build.VERSION.SDK_INT >= 31
