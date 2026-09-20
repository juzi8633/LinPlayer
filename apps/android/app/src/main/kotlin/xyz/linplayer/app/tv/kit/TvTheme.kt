package xyz.linplayer.app.tv.kit

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
    var bg = Color(0xFF100E14)
    var rail = Color(0xFF0A090D)
    var surface1 = Color(0xFF18161D)
    var surface2 = Color(0xFF211E28)
    var surface3 = Color(0xFF2C2834)
    var line = Color(0xFF2E2A36)
    var fg = Color(0xFFF3EFF8)
    var fg2 = Color(0xFF9B93AE)
    var fg3 = Color(0xFF6E6880)
    var focus = fg
    var onFocus = Color(0xFF16131B)
    var acc = Color(0xFFF5A524)
    var accDim = Color(0x2EF5A524)
    var onAcc = Color(0xFF20160A)
    var ok = Color(0xFF5CD6A0)
    // 与 acc 分开:「可能匹配」黄标和「当前」琥珀字会出现在同一张版本卡上
    var warn = Color(0xFFF5C04A)
    var bad = Color(0xFFFF6B5E)
    var scrim = Color(0x9E100E14)
}

/** §1.2 字阶两档。控件高度跟着字阶走,封面尺寸不跟。 */
@Immutable
data class TvType(
    val name: String,
    val display: TextUnit, val headline: TextUnit, val title: TextUnit,
    val body: TextUnit, val meta: TextUnit, val numeral: TextUnit,
    val controlH: Dp, val rowH: Dp, val iconS: Dp,
)

val TypeB = TvType("B 档 · 采用", 26.sp, 22.sp, 16.sp, 14.sp, 12.sp, 20.sp, 40.dp, 48.dp, 20.dp)
val TypeA = TvType("A 档 · 旧稿换算", 18.sp, 20.sp, 13.sp, 9.sp, 7.5.sp, 15.sp, 26.dp, 32.dp, 11.dp)

val LocalTvType = staticCompositionLocalOf { TypeB }

object TvW {
    val bold = FontWeight.Bold
    val semi = FontWeight.SemiBold
    val medium = FontWeight.Medium
}

/** §2.4 间距。只有这些值。 */
object TvSp {
    var x2 = 2.dp; var x4 = 4.dp; var x6 = 6.dp; var x8 = 8.dp; var x12 = 12.dp
    var x16 = 16.dp; var x20 = 20.dp; var x24 = 24.dp; var x32 = 32.dp; var x48 = 48.dp

    /** 主题密度(`layout.density`)。整把尺一起缩放,启动时调一次。 */
    fun scale(k: Float) {
        x2 *= k; x4 *= k; x6 *= k; x8 *= k; x12 *= k
        x16 *= k; x20 *= k; x24 *= k; x32 *= k; x48 *= k
    }
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
    /** 导航轨收起 64 / 展开 200(盖在内容上,内容区不动) */
    val railW = 64.dp
    val railExpandedW = 200.dp
    /** 内容区左边线 = 轨 64 + 32 */
    val contentStart = 96.dp
    val contentW = 816.dp
    val heroH = 243.dp
    val wideW = 160.dp; val wideH = 90.dp
    val posterW = 110.dp; val posterH = 165.dp
    val epW = 142.dp; val epH = 80.dp
    val libW = 180.dp; val libH = 101.dp
    val versionW = 190.dp; val versionH = 106.dp
    val panelW = 230.dp; val panelMaxH = 486.dp
    /** 卡片封面下标题 + 副标题两行的高度。焦点落在封面上,往下「完整露出」要把这两行算进去。 */
    val captionH = 40.dp
}

val tvType: TvType
    @Composable get() = LocalTvType.current
