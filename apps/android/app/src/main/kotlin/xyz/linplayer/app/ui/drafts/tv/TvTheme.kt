package xyz.linplayer.app.ui.drafts.tv

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * TV 草稿的设计 token(docs/go-migration/UI_TV.md §1–§2)。
 * 数字全部是 960×540dp 画布上的值 —— 旧 TV 稿的 1920 CSS px 要除以 2。
 */

/** §2.1。TV 上**没有半透明 surface**:压在封面和视频上的东西要么全透明,要么不透明块。 */
object TvC {
    val bg = Color(0xFF100E14)
    val rail = Color(0xFF0A090D)
    val surface1 = Color(0xFF18161D)
    val surface2 = Color(0xFF211E28)
    val surface3 = Color(0xFF2C2834)
    val line = Color(0xFF2E2A36)
    val fg = Color(0xFFF3EFF8)
    val fg2 = Color(0xFF9B93AE)
    val fg3 = Color(0xFF6E6880)
    val focus = fg
    val onFocus = Color(0xFF16131B)
    val acc = Color(0xFFF5A524)
    val accDim = Color(0x2EF5A524)
    val onAcc = Color(0xFF20160A)
    val ok = Color(0xFF5CD6A0)
    // 与 acc 分开:「可能匹配」黄标和「当前」琥珀字会出现在同一张版本卡上
    val warn = Color(0xFFF5C04A)
    val bad = Color(0xFFFF6B5E)
    val scrim = Color(0x9E100E14)
}

/** §1.2 字阶两档。控件高度跟着字阶走,封面尺寸不跟。 */
@Immutable
data class TvType(
    val name: String,
    val display: TextUnit, val headline: TextUnit, val title: TextUnit,
    val body: TextUnit, val meta: TextUnit, val numeral: TextUnit,
    val controlH: Dp, val rowH: Dp, val iconS: Dp,
)

val TypeB = TvType("B 档 · 推荐", 26.sp, 22.sp, 16.sp, 14.sp, 12.sp, 20.sp, 40.dp, 48.dp, 20.dp)
val TypeA = TvType("A 档 · 旧稿换算", 18.sp, 20.sp, 13.sp, 9.sp, 7.5.sp, 15.sp, 26.dp, 32.dp, 11.dp)

val LocalTvType = staticCompositionLocalOf { TypeB }

object TvW {
    val bold = FontWeight.Bold
    val semi = FontWeight.SemiBold
    val medium = FontWeight.Medium
}

/** §2.4 间距。只有这些值。 */
object TvSp {
    val x2 = 2.dp; val x4 = 4.dp; val x6 = 6.dp; val x8 = 8.dp; val x12 = 12.dp
    val x16 = 16.dp; val x20 = 20.dp; val x24 = 24.dp; val x32 = 32.dp; val x48 = 48.dp
}

/** §2.3 圆角。 */
object TvR {
    val sm = RoundedCornerShape(6.dp)
    val md = RoundedCornerShape(10.dp)
    val lg = RoundedCornerShape(14.dp)
    val pill = RoundedCornerShape(999.dp)
}

/** 版面尺寸。大于 48 的数都是「版面高度 / 让位」,不是间距节奏,所以具名。 */
object TvDim {
    val safeH = 48.dp
    val safeV = 27.dp
    val railW = 96.dp
    /** 内容区左边线 = 轨 96 + 32 */
    val contentStart = 128.dp
    val contentW = 784.dp
    val heroH = 243.dp
    val wideW = 160.dp; val wideH = 90.dp
    val posterW = 110.dp; val posterH = 165.dp
    val epW = 142.dp; val epH = 80.dp
    val libW = 172.dp; val libH = 97.dp
    val versionW = 190.dp; val versionH = 106.dp
    val panelW = 230.dp; val panelMaxH = 486.dp
}

/** 草稿不连网:封面一律色块。同一个 seed 恒定同一个色。 */
fun swatch(seed: Int): Brush {
    val hues = listOf(
        0xFF3B4A6B to 0xFF1C2233, 0xFF6B3B4A to 0xFF331C22, 0xFF3B6B4A to 0xFF1C3322,
        0xFF6B5A3B to 0xFF33291C, 0xFF5A3B6B to 0xFF291C33, 0xFF3B6B6B to 0xFF1C3333,
        0xFF6B6B3B to 0xFF33331C, 0xFF4A3B6B to 0xFF221C33,
    )
    val (a, b) = hues[seed.mod(hues.size)]
    return Brush.linearGradient(listOf(Color(a), Color(b)))
}

val tvType: TvType
    @Composable get() = LocalTvType.current
