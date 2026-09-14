package xyz.linplayer.app.tv

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.Surface
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.linplayer.app.core.CoreEvent
import xyz.linplayer.app.core.CoreException
import xyz.linplayer.app.core.CorePort

/**
 * JVM 上的核心层替身。**只替掉跨 FFI 的那一段**:页面、焦点、面板、返回栈全是真代码。
 * 字段形状照 scratchpad 里那份核心层返回形状整理(emby.go / account.go / transport.go 的 json 标签)。
 */
class FakeCore : CorePort {
    override val events = MutableSharedFlow<CoreEvent>(extraBufferCapacity = 64)
    // 非空才会拼出图片地址;scheme 是假的,由 [FakeImages] 接住
    override val localBaseUrl = "fake:"
    override val localToken = ""
    val calls = mutableListOf<Pair<String, JsonObject?>>()
    val handlers = HashMap<String, (JsonObject?) -> JsonElement>()

    fun on(cmd: String, f: (JsonObject?) -> JsonElement) { handlers[cmd] = f }
    fun ret(cmd: String, v: JsonElement) { handlers[cmd] = { v } }

    override suspend fun callJson(command: String, args: JsonObject?, onPartial: ((JsonElement) -> Unit)?): JsonElement {
        calls += command to args
        val h = handlers[command] ?: throw CoreException("E_UNSUPPORTED", "替身没有 $command", false)
        return h(args)
    }

    override fun setSurface(surface: Surface?, w: Int, h: Int) = 0
}

// ---------------------------------------------------------------- 图片:按条目 id 取色,和草稿同一套色块

object FakeImages {
    private val hues = listOf(
        0xFF3B4A6B to 0xFF1C2233, 0xFF6B3B4A to 0xFF331C22, 0xFF3B6B4A to 0xFF1C3322,
        0xFF6B5A3B to 0xFF33291C, 0xFF5A3B6B to 0xFF291C33, 0xFF3B6B6B to 0xFF1C3333,
        0xFF6B6B3B to 0xFF33331C, 0xFF4A3B6B to 0xFF221C33,
    )

    fun install(ctx: android.content.Context) {
        val loader = ImageLoader.Builder(ctx)
            .components { add(Factory()) }
            .coroutineContext(Dispatchers.Unconfined)
            .build()
        SingletonImageLoader.setUnsafe(loader)
    }

    private class Factory : Fetcher.Factory<coil3.Uri> {
        override fun create(data: coil3.Uri, options: Options, imageLoader: ImageLoader): Fetcher? =
            // 排行榜 / 放送表的封面是外站地址,不走 localBaseUrl:测试里一律画色块,不出网
            Fetcher { draw(data.toString()) }
    }

    private fun bitmap(key: String): Bitmap {
        val (a, b) = hues[key.hashCode().mod(hues.size)]
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawRect(0f, 0f, 64f, 64f, Paint().apply {
            shader = LinearGradient(0f, 0f, 64f, 64f, a.toInt(), b.toInt(), Shader.TileMode.CLAMP)
        })
        return bmp
    }

    private fun draw(key: String): FetchResult = ImageFetchResult(bitmap(key).asImage(), false, DataSource.MEMORY)

    /** `account.icon` / `player.thumbnail` 回的是 base64,不走 Coil。 */
    fun base64(key: String, format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG): String {
        val out = java.io.ByteArrayOutputStream()
        bitmap(key).compress(format, 90, out)
        return android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
    }

    fun dataUri(key: String) = "data:image/png;base64," + base64(key)
}

// ---------------------------------------------------------------- 夹具:和草稿同一批片名 / 数字

internal fun item(
    id: String, name: String, type: String = "Movie", year: Int? = null, rating: Double? = null,
    series: String? = null, season: Int? = null, episode: Int? = null, runtime: Double = 0.0, resume: Double = 0.0,
    played: Boolean = false, unplayed: Int = 0, seriesId: String? = null, genres: List<String> = emptyList(),
) = buildJsonObject {
    put("id", id); put("name", name); put("type_", type); put("runtime_secs", runtime); put("resume_secs", resume)
    put("played", played); put("unplayed_item_count", unplayed)
    year?.let { put("year", it) }; rating?.let { put("rating", it) }
    series?.let { put("series_name", it) }; seriesId?.let { put("series_id", it) }
    season?.let { put("season_no", it) }; episode?.let { put("episode_no", it) }
    put("genres", buildJsonArray { genres.forEach { add(JsonPrimitive(it)) } })
}

internal fun arr(vararg e: JsonElement) = buildJsonArray { e.forEach { add(it) } }
internal fun page(vararg e: JsonElement) = buildJsonObject { put("items", arr(*e)); put("total", e.size) }

internal val resumeItems = arr(
    item("r1", "谎言的代价", "Episode", series = "寂静的星河", season = 2, episode = 4, runtime = 2700.0, resume = 1620.0, seriesId = "s1"),
    item("r2", "妾之王国", "Episode", series = "幕府将军", season = 1, episode = 6, runtime = 3660.0, resume = 2220.0, seriesId = "s2"),
    item("r3", "沙丘:预言", "Movie", runtime = 5400.0, resume = 1080.0),
    item("r4", "旅程", "Episode", series = "葬送的芙莉莲", season = 1, episode = 17, runtime = 1440.0, resume = 900.0, seriesId = "s3"),
    item("r5", "黑暗森林", "Episode", series = "三体", season = 1, episode = 12, runtime = 2700.0, resume = 840.0, seriesId = "s4"),
    item("r6", "奥本海默", "Movie", runtime = 10800.0, resume = 1740.0),
)
internal val nextItems = arr(
    item("n1", "回声", "Episode", series = "寂静的星河", season = 2, episode = 5, seriesId = "s1"),
    item("n2", "第九集", "Episode", series = "繁花", season = 1, episode = 9),
    item("n3", "第四集", "Episode", series = "漫长的季节", season = 1, episode = 4),
    item("n4", "第二集", "Episode", series = "黑暗荣耀", season = 2, episode = 2),
    item("n5", "第七集", "Episode", series = "间谍过家家", season = 2, episode = 7),
)
internal val movieItems = arr(
    item("m1", "沙丘 2", year = 2024), item("m2", "奥本海默", year = 2023, played = true), item("m3", "坠落的审判", year = 2023),
    item("m4", "周处除三害", year = 2024), item("m5", "可怜的东西", year = 2023), item("m6", "年会不能停!", year = 2023),
    item("m7", "热辣滚烫", year = 2024), item("m8", "小丑", year = 2019, played = true),
)
internal val showItems = arr(
    item("s2", "幕府将军", "Series", year = 2024, unplayed = 4), item("s5", "繁花", "Series", year = 2023, unplayed = 21),
    item("s4", "三体", "Series", year = 2023), item("s6", "漫长的季节", "Series", year = 2023, played = true),
    item("s1", "寂静的星河", "Series", year = 2024, unplayed = 2), item("s7", "黑暗荣耀", "Series", year = 2022),
    item("s3", "葬送的芙莉莲", "Series", year = 2023, unplayed = 11),
)

internal fun account(id: String, name: String, remark: String, active: Boolean, lines: List<Pair<String, String>> = emptyList()) = buildJsonObject {
    put("server", id); put("name", name); put("remark", remark); put("active", active); put("user_name", "某人")
    put("source_kind", "emby"); put("active_line", 0)
    put("lines", buildJsonArray { lines.forEach { (n, u) -> add(buildJsonObject { put("name", n); put("url", u) }) } })
}

/** 一台「已登录」的替身:会话、账号表、首页五块。别的页各自在用例里补。 */
fun FakeCore.loggedIn(): FakeCore {
    ret("system.capabilities", buildJsonObject { put("platform", "android"); put("version", "1.0.0") })
    ret("emby.currentSession", buildJsonObject {
        put("server", "http://emby-a.invalid"); put("token", "t"); put("user_id", "u"); put("user_name", "某人")
    })
    ret("account.listAccounts", arr(
        account("http://emby-a.invalid", "服务器 A", "客厅常用 · 4K 片源", true, listOf(
            "线路一" to "https://media.example.net", "电信直连" to "https://ct.example.net",
            "家里直连" to "http://nas.local", "" to "https://backup.example.org")),
        account("http://emby-b.invalid", "服务器 B", "公司", false),
        account("http://emby-c.invalid", "服务器 C", "朋友的服务器", false),
        account("http://emby-d.invalid", "旧 NAS", "只剩动漫", false),
    ))
    ret("source.currentSource", buildJsonObject { })
    ret("emby.listRandom", arr(
        item("h1", "寂静的星河", "Series", year = 2024, rating = 8.6, genres = listOf("科幻", "悬疑")),
        item("h2", "幕府将军", "Series", year = 2024, rating = 8.9), item("h3", "沙丘 2", year = 2024),
        item("h4", "繁花", "Series", year = 2023), item("h5", "三体", "Series", year = 2023),
    ))
    ret("emby.listResume", resumeItems)
    ret("emby.listNextUp", nextItems)
    ret("emby.views", arr(
        buildJsonObject { put("id", "lib-movie"); put("name", "电影"); put("collection_type", "movies") },
        buildJsonObject { put("id", "lib-tv"); put("name", "剧集"); put("collection_type", "tvshows") },
    ))
    on("emby.listLatest") { a -> if (a?.get("parent_id")?.let { (it as JsonPrimitive).content } == "lib-movie") movieItems else showItems }
    ret("emby.listCollections", arr())
    ret("companion.status", buildJsonObject {
        put("enabled", true); put("running", true); put("url", "http://tv.invalid/c/3f9a1b2c4d5e"); put("port", 0)
        put("error", ""); put("ip_error", ""); put("connected", false)
    })
    ret("companion.start", JsonNull)
    ret("companion.setNowPlaying", JsonNull)
    ret("prefs.getPrefs", buildJsonObject { put("sub_enabled", true); put("search_history", arr(JsonPrimitive("幕府将军"), JsonPrimitive("寂静的星河"), JsonPrimitive("沙丘"), JsonPrimitive("葬送的芙莉莲"))) })
    return this
}
