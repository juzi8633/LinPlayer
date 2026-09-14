package xyz.linplayer.app.ui.drafts.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import xyz.linplayer.app.tv.kit.TvDim
import xyz.linplayer.app.tv.kit.TvR
import xyz.linplayer.app.tv.kit.TvSp

/*
 * 草稿专用:不连网,封面一律色块;卡片按 seed 取色。真页面走 tv/kit 里吃封面 lambda 的那一版。
 */

/** 同一个 seed 恒定同一个色。 */
fun swatch(seed: Int): Brush {
    val hues = listOf(
        0xFF3B4A6B to 0xFF1C2233, 0xFF6B3B4A to 0xFF331C22, 0xFF3B6B4A to 0xFF1C3322,
        0xFF6B5A3B to 0xFF33291C, 0xFF5A3B6B to 0xFF291C33, 0xFF3B6B6B to 0xFF1C3333,
        0xFF6B6B3B to 0xFF33331C, 0xFF4A3B6B to 0xFF221C33,
    )
    val (a, b) = hues[seed.mod(hues.size)]
    return Brush.linearGradient(listOf(Color(a), Color(b)))
}

@Composable
fun Cover(seed: Int, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit = {}) {
    Box(modifier.fillMaxSize().background(swatch(seed)), content = content)
}

@Composable
fun CardWide(
    seed: Int, title: String, sub: String, progress: Float? = null, isNew: Boolean = false,
    focused: Boolean = false, fake: Boolean = false, w: Dp = TvDim.wideW, h: Dp = TvDim.wideH,
) = xyz.linplayer.app.tv.kit.CardWide({ Cover(seed) }, title, sub, progress, isNew, focused, fake, w, h)

@Composable
fun CardPoster(
    seed: Int, title: String, sub: String, watched: Boolean = false, unplayed: Int = 0,
    rank: Int = 0, focused: Boolean = false, fake: Boolean = false, w: Dp = TvDim.posterW, h: Dp = TvDim.posterH,
) = xyz.linplayer.app.tv.kit.CardPoster({ Cover(seed) }, title, sub, watched, unplayed, rank, focused, fake, w, h)

@Composable
fun CardEpisode(
    seed: Int, no: String, title: String, sub: String, progress: Float? = null,
    done: Boolean = false, current: Boolean = false, focused: Boolean = false, fake: Boolean = false,
) = xyz.linplayer.app.tv.kit.CardEpisode({ Cover(seed) }, no, title, sub, progress, done, current, focused, fake)

@Composable
fun CardLibrary(seed: Int, name: String, blocked: Boolean = false, focused: Boolean = false, fake: Boolean = false) =
    xyz.linplayer.app.tv.kit.CardLibrary({ Cover(seed) }, name, blocked, focused, fake)

/** 草稿标注:这一屏在演示什么。不属于设计本身。 */
@Composable
fun BoxScope.DraftNote(text: String) {
    Box(
        Modifier.align(Alignment.BottomEnd).padding(TvSp.x4).clip(TvR.sm)
            .background(Color(0xE6000000)).padding(horizontal = TvSp.x6, vertical = TvSp.x2),
    ) { Text(text, color = Color(0xFFFFD27A), fontSize = 9.sp) }
}
