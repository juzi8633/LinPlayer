package xyz.linplayer.app.data

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/**
 * **纯呈现的、只属于这一台设备的**偏好。
 *
 * 规矩是「一切持久化归核心层」(`SPEC.md` §8.5),这里是一个有边界的例外:
 * 深浅色覆盖既没有核心层命令能存(`prefs.setPrefs` 只认
 * `audio_lang` / `sub_lang` / `sub_enabled`),也**不该**跨设备同步 ——
 * 手机上想强制深色不代表电视上也要。它和「我在哪一页、滚到哪」是同一类东西。
 *
 * ☠ **这里只放这一类。** 任何有核心层消费点的东西都不许进来:
 * 放进来的那一刻,它就变成一个「设了但核心层不知道」的开关 ——
 * 而那正是本仓库最难查的一类 bug。
 */
object UiPrefs {
    private const val FILE = "lp_ui"
    private const val K_THEME = "theme"
    private const val K_ENGINE = "engine"
    private const val K_FONT = "ui_font"
    const val K_SHOT_TIME = "shot_time"
    const val K_SHOT_LOGO = "shot_logo"
    const val K_SHOT_TIME_POS = "shot_time_pos"
    const val K_SHOT_LOGO_POS = "shot_logo_pos"

    /** `system` / `dark` / `light`。 */
    val theme = mutableStateOf("system")

    /**
     * 界面字体文件的绝对路径(用户导入的 .ttf/.otf,已复制进应用私有目录)。空 = 系统默认。
     *
     * ★ 它进这里而不是核心层:字体得在**第一帧之前**就拿得到。走核心层是一次异步调用,
     *   表现是每次冷启动先用默认字体画一屏、再整页换字 —— 那比不给这个功能还难看。
     * ★ 存的是**我们自己复制的那一份**,不是用户选中的原始 Uri:
     *   SAF 的 Uri 重启后可能就没权限了,而字体是每次冷启动都要读的东西。
     */
    val uiFont = mutableStateOf("")

    /**
     * 播放键**短按**用哪个内核:`mpv` / `exo`。长按用另一个。
     *
     * ★ 它进这里是因为**「哪个内核能在这台机器上出画面」是设备属性,不是账号属性**
     *   —— 手机上 mpv 出「有声音没画面」不代表电视上也会。跨设备同步它反而害人。
     * ★ 值只是**发给 `player.play` 的一个参数**,核心层不存它,所以不违反
     *   「一切持久化归核心层」:这里没有第二个真相。
     */
    val engine = mutableStateOf("mpv")

    /**
     * 截屏叠加【用户定 2026-09-07】:要不要压上系统时间 / 条目艺术字,各自摆在哪一角。
     *
     * ★ 它们进这里的理由和主题一样:**核心层没有消费点**。截屏整条路
     *   (PixelCopy → 叠字 → 写相册)都在 Kotlin 这一侧,核心层不知道也不需要知道。
     * ★ 位置用四角的字母码 `tl/tr/bl/br` —— 存中文标签的话改一次文案就把用户的设置弄丢了。
     */
    val shotTime = mutableStateOf(true)
    val shotLogo = mutableStateOf(false)
    val shotTimePos = mutableStateOf("br")
    val shotLogoPos = mutableStateOf("tl")

    fun load(ctx: Context) {
        val sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        theme.value = sp.getString(K_THEME, "system") ?: "system"
        engine.value = sp.getString(K_ENGINE, "mpv") ?: "mpv"
        uiFont.value = sp.getString(K_FONT, "") ?: ""
        shotTime.value = sp.getBoolean(K_SHOT_TIME, true)
        shotLogo.value = sp.getBoolean(K_SHOT_LOGO, false)
        shotTimePos.value = sp.getString(K_SHOT_TIME_POS, "br") ?: "br"
        shotLogoPos.value = sp.getString(K_SHOT_LOGO_POS, "tl") ?: "tl"
    }

    fun setShotFlag(ctx: Context, key: String, v: Boolean) {
        (if (key == K_SHOT_TIME) shotTime else shotLogo).value = v
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(key, v).apply()
    }

    fun setShotPos(ctx: Context, key: String, v: String) {
        (if (key == K_SHOT_TIME_POS) shotTimePos else shotLogoPos).value = v
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(key, v).apply()
    }

    fun setTheme(ctx: Context, v: String) {
        theme.value = v
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(K_THEME, v).apply()
    }

    /**
     * 长按播放键用的内核 —— 短按那个的**另一个**。
     *
     * ★ 只有两个内核,所以「调换位置」就是把短按那个换掉,不需要第二个开关 ——
     *   两个各自能选的话会出现「短按长按都是 mpv」,那时长按等于坏了。
     */
    fun otherEngine(of: String = engine.value): String = if (of == "exo") "mpv" else "exo"

    fun setFont(ctx: Context, path: String) {
        uiFont.value = path
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(K_FONT, path).apply()
    }

    fun setEngine(ctx: Context, v: String) {
        engine.value = v
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(K_ENGINE, v).apply()
    }
}
