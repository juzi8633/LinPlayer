package xyz.linplayer.app.tv

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.linplayer.app.core.CoreException

/* 各页的替身数据。片名、集名、数字照草稿(ui/drafts/tv)抄,出图和草稿对照时只看版式差异。 */

private val episodeNames = listOf("信号", "第三个坐标", "静默区", "谎言的代价", "回声", "折返点", "冰层之下", "第 1127 天", "原点", "寂静")
private val shogunNames = listOf("安针", "仆之二主", "明日之时", "八重之壁", "炉边闲话", "妾之王国", "今日之时", "大地之心", "红天", "冬之夏")

private fun people() = arr(*listOf("提莫西·查拉梅", "赞达亚", "丽贝卡·弗格森", "奥斯汀·巴特勒", "弗洛伦丝·皮尤", "哈维尔·巴登")
    .mapIndexed { i, n -> buildJsonObject { put("id", "p$i"); put("name", n); put("type_", "Actor") } }.toTypedArray())

private fun stream(type: String, codec: String, lang: String? = null, title: String? = null, w: Int? = null, h: Int? = null,
                   range: String? = null, layout: String? = null, index: Int = 0, external: Boolean = false) = buildJsonObject {
    put("index", index); put("type_", type); put("codec", codec); put("is_default", index <= 1); put("is_external", external)
    lang?.let { put("language", it) }; title?.let { put("title", it) }; w?.let { put("width", it) }; h?.let { put("height", it) }
    range?.let { put("video_range_type", it) }; layout?.let { put("channel_layout", it) }
}

private fun version(id: String, name: String, h: Int, codec: String, bitrate: Long, size: Long, range: String?, audio: String, subs: List<String>, preferred: Boolean,
                    subCodecs: List<String> = emptyList()) =
    buildJsonObject {
        put("id", id); put("name", name); put("preferred", preferred); put("container", "mkv"); put("bitrate", bitrate); put("size_bytes", size)
        put("streams", buildJsonArray {
            add(stream("Video", codec, w = h * 16 / 9, h = h, range = range, index = 0))
            add(stream("Audio", audio, lang = "jpn", layout = "7.1", index = 1))
            subs.forEachIndexed { i, l -> add(stream("Subtitle", subCodecs.getOrNull(i) ?: "ass", lang = l, title = if (l == "chi") "简体中文" else null, index = 2 + i)) }
        })
    }

fun FakeCore.library(): FakeCore {
    ret("emby.blockedList", arr(buildJsonObject { put("id", "lib-kids"); put("name", "儿童") }))
    on("emby.views") {
        arr(*listOf("电影", "剧集", "动漫", "纪录片", "华语剧集", "外语剧集", "演唱会", "儿童").mapIndexed { i, n ->
            buildJsonObject { put("id", if (n == "儿童") "lib-kids" else "lib-$i"); put("name", n) }
        }.toTypedArray())
    }
    val titles = listOf("沙丘 2" to 2024, "奥本海默" to 2023, "星际穿越" to 2014, "银翼杀手 2049" to 2017, "降临" to 2016, "火星救援" to 2015,
        "流浪地球 2" to 2023, "异形:夺命舰" to 2024, "瞬息全宇宙" to 2022, "信条" to 2020, "湮灭" to 2018, "月球" to 2009,
        "机械姬" to 2014, "她" to 2013, "盗梦空间" to 2010, "地心引力" to 2013, "阿凡达:水之道" to 2022, "头号玩家" to 2018)
    ret("emby.listItemsPage", buildJsonObject {
        put("items", arr(*titles.mapIndexed { i, (n, y) -> item("g$i", n, year = y, played = i % 5 == 3) }.toTypedArray()))
        put("total", 1284)
    })
    ret("emby.getFilters", buildJsonObject {
        put("genres", arr(JsonPrimitive("科幻"), JsonPrimitive("剧情"), JsonPrimitive("动作")))
        put("years", arr(JsonPrimitive(2024), JsonPrimitive(2023), JsonPrimitive(2022)))
    })
    return this
}

fun FakeCore.series(): FakeCore {
    ret("emby.itemDetail", buildJsonObject {
        put("id", "s1"); put("name", "寂静的星河"); put("type_", "Series"); put("year", 2024); put("rating", 8.6)
        put("overview", "一支勘探队在柯伊伯带外沿收到一段重复的信号。信号的内容,是他们自己三年后发出的求救。为了弄清那三年里发生了什么,他们必须先决定要不要回头。")
        put("genres", arr(JsonPrimitive("科幻"), JsonPrimitive("悬疑"))); put("has_backdrop", true); put("is_favorite", false)
        put("played", false); put("people", people())
    })
    ret("emby.seriesSeasons", arr(*(1..3).map { s ->
        buildJsonObject { put("id", "se$s"); put("name", "第 $s 季"); put("index_no", s); put("child_count", 12); put("unplayed", if (s == 1) 0 else 12) }
    }.toTypedArray()))
    ret("emby.seasonEpisodes", buildJsonObject {
        put("items", arr(*episodeNames.mapIndexed { i, n ->
            item("se2e${i + 1}", n, "Episode", series = "寂静的星河", season = 2, episode = i + 1, runtime = 2700.0,
                resume = if (i == 3) 1674.0 else 0.0, played = i < 3, seriesId = "s1")
        }.toTypedArray()))
        put("total", 10)
    })
    ret("emby.listResume", arr(item("se2e4", "谎言的代价", "Episode", series = "寂静的星河", season = 2, episode = 4, runtime = 2700.0, resume = 1674.0, seriesId = "s1")))
    ret("emby.similarItems", movieItems)
    return this
}

fun FakeCore.movie(): FakeCore {
    ret("emby.itemDetail", buildJsonObject {
        put("id", "m1"); put("name", "沙丘 2"); put("type_", "Movie"); put("year", 2024); put("rating", 8.6)
        put("runtime_secs", 9960); put("resume_secs", 1788)
        put("overview", "保罗·厄崔迪与契妮和弗雷曼人联手,向摧毁他家族的阴谋者复仇。在一生挚爱与已知宇宙的命运之间,他必须做出选择。")
        put("genres", arr(JsonPrimitive("科幻"), JsonPrimitive("冒险"))); put("has_backdrop", true); put("people", people())
    })
    on("emby.itemMedia") { a ->
        if ((a?.get("server_id") as? JsonPrimitive)?.content == "http://emby-b.invalid")
            arr(version("vb", "1080p", 1080, "h264", 12_400_000, 15_000_000_000, null, "ac3", listOf("chi"), false))
        else arr(version("va", "2160p", 2160, "hevc", 58_200_000, 71_000_000_000, "HDR10", "truehd", listOf("chi", "chi", "eng"), true))
    }
    ret("emby.aggregateVersions", arr(
        buildJsonObject { put("server_id", "http://emby-b.invalid"); put("server_name", "服务器 B"); put("item_id", "mb")
            put("confidence", "strong"); put("versions", arr(version("vb", "1080p", 1080, "h264", 12_400_000, 15_000_000_000, null, "ac3", listOf("chi"), false))) },
        buildJsonObject { put("server_id", "http://emby-c.invalid"); put("server_name", "服务器 C"); put("item_id", "mc")
            put("confidence", "strong"); put("versions", arr(version("va", "2160p", 2160, "hevc", 58_200_000, 71_000_000_000, "HDR10", "truehd", listOf("chi", "chi", "eng"), true))) },
    ))
    ret("emby.similarItems", movieItems)
    return this
}

fun FakeCore.episode(): FakeCore {
    ret("emby.itemDetail", buildJsonObject {
        put("id", "sh8"); put("name", "大地之心"); put("type_", "Episode"); put("series_name", "幕府将军"); put("series_id", "s2")
        put("season_id", "sh"); put("season_no", 1); put("episode_no", 8); put("runtime_secs", 3660); put("resume_secs", 2172)
        put("overview", "虎永的计划在大阪城内暴露。真理子必须在忠诚与信仰之间作出抉择,而布莱克索恩发现自己成了双方都想利用的棋子。")
        put("played", false); put("is_favorite", false)
    })
    ret("emby.seasonEpisodes", buildJsonObject {
        put("items", arr(*(1..24).map { n ->
            item("sh$n", shogunNames[n % shogunNames.size], "Episode", series = "幕府将军", season = 1, episode = n,
                runtime = 3660.0, resume = if (n == 8) 2172.0 else 0.0, played = n < 8, seriesId = "s2")
        }.toTypedArray()))
        put("total", 24)
    })
    on("emby.itemMedia") { a ->
        if ((a?.get("server_id") as? JsonPrimitive)?.content == "http://emby-b.invalid")
            arr(version("eb", "1080p", 1080, "h264", 8_100_000, 3_200_000_000, null, "aac", listOf("chi"), false))
        else arr(version("ea", "2160p", 2160, "hevc", 24_600_000, 8_400_000_000, "HDR10", "truehd", listOf("chi", "chi"), true))
    }
    ret("emby.aggregateVersions", arr(
        buildJsonObject { put("server_id", "http://emby-b.invalid"); put("server_name", "服务器 B"); put("item_id", "b8")
            put("confidence", "strong"); put("versions", arr(version("eb", "1080p", 1080, "h264", 8_100_000, 3_200_000_000, null, "aac", listOf("chi"), false))) },
        buildJsonObject { put("server_id", "http://emby-c.invalid"); put("server_name", "服务器 C"); put("item_id", "c8")
            put("confidence", "possible"); put("versions", arr(version("ea", "2160p", 2160, "hevc", 24_600_000, 8_400_000_000, "HDR10", "truehd", listOf("chi", "chi"), true))) },
    ))
    return this
}

fun FakeCore.player(failPlay: Boolean = false, noVideo: Boolean = false): FakeCore {
    ret("emby.itemDetail", buildJsonObject {
        put("id", "sh6"); put("name", "妾之王国"); put("type_", "Episode"); put("series_name", "幕府将军"); put("series_id", "s2")
        put("season_id", "sh"); put("season_no", 1); put("episode_no", 6)
    })
    ret("emby.seasonEpisodes", buildJsonObject {
        put("items", arr(*(1..10).map { n -> item("sh$n", shogunNames[n - 1], "Episode", series = "幕府将军", season = 1, episode = n, runtime = 3660.0) }.toTypedArray()))
        put("total", 10)
    })
    ret("emby.itemMedia", arr(
        version("ea", "2160p", 2160, "hevc", 24_600_000, 8_400_000_000, "HDR", "truehd", listOf("chi", "chi", "eng"), true, listOf("ass", "ass", "pgssub")),
        version("eb", "1080p", 1080, "h264", 8_100_000, 3_200_000_000, null, "aac", listOf("chi"), false),
    ))
    ret("player.shaderLevels", arr(buildJsonObject { put("id", "off"); put("name", "关"); put("selected", true) }))
    ret("player.thumbnail", buildJsonObject { put("available", true); put("jpeg", FakeImages.base64("thumb", android.graphics.Bitmap.CompressFormat.JPEG)) })
    on("player.play") { if (failPlay) throw CoreException("E_NETWORK", "服务端返回 500 · 转码进程未启动", true) else buildJsonObject { put("media_source_id", "ea"); put("resume_secs", 2172) } }
    ret("player.chapterInfo", buildJsonObject {
        put("chapters", arr(*listOf(220.0, 1130.0, 2010.0, 3400.0).map { s -> buildJsonObject { put("start_secs", s); put("name", "") } }.toTypedArray()))
        put("intro", buildJsonObject { put("start", 0.0); put("end", 220.0) }); put("skip_auto", false)
    })
    ret("player.status", buildJsonObject { put("buffered", 2640.0) })
    ret("player.tracks", arr(
        buildJsonObject { put("id", "1"); put("kind", "sub"); put("title", "简体中文"); put("lang", "chi"); put("selected", true); put("ff_index", 2) },
        buildJsonObject { put("id", "2"); put("kind", "sub"); put("title", "繁體中文"); put("lang", "chi"); put("ff_index", 3) },
        buildJsonObject { put("id", "3"); put("kind", "sub"); put("title", "English"); put("lang", "eng"); put("ff_index", 4) },
        buildJsonObject { put("id", "4"); put("kind", "sub"); put("title", "简日双语.ass"); put("external", true); put("ff_index", -1) },
        buildJsonObject { put("id", "1"); put("kind", "audio"); put("title", "日语 TrueHD 7.1"); put("lang", "jpn"); put("selected", true); put("ff_index", 1) },
    ))
    ret("player.mpvGet", JsonPrimitive(0.0))
    ret("player.getSubStyle", buildJsonObject { put("scale", 1.0); put("position", 100) })
    ret("player.getPlaybackPrefs", buildJsonObject { put("skip_intro", true); put("skip_outro", false) })
    ret("player.opts", buildJsonObject {
        put("current-vo", if (noVideo) "" else "gpu"); put("hwdec-current", "mediacodec"); put("dwidth", if (noVideo) "0" else "3840"); put("dheight", "2160")
    })
    return this
}

fun FakeCore.servers(): FakeCore {
    on("account.icon") { a -> buildJsonObject { put("data_uri", FakeImages.dataUri("icon:" + (a?.get("server_id") as? JsonPrimitive)?.content)) } }
    ret("account.probeAccounts", arr(
        buildJsonObject { put("server", "http://emby-a.invalid"); put("ok", true); put("ms", 80) },
        buildJsonObject { put("server", "http://emby-b.invalid"); put("ok", true); put("ms", 120) },
        buildJsonObject { put("server", "http://emby-d.invalid"); put("ok", false); put("error", "超时") },
    ))
    on("account.probeLine") { a ->
        val i = (a?.get("index") as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
        buildJsonObject { put("index", i); put("url", ""); if (i == 0) put("ms", 86) else if (i == 2) put("ms", 240) else put("ms", JsonNull) }
    }
    return this
}

fun FakeCore.settings(): FakeCore {
    ret("player.getPlaybackPrefs", buildJsonObject {
        put("hwdec", "auto-safe"); put("dolby_auto_sw", true); put("default_speed", 1.0); put("skip_intro", true); put("skip_outro", false)
    })
    ret("player.shaderLevels", arr(buildJsonObject { put("id", "off"); put("name", "关"); put("selected", true) }))
    ret("account.getCrossServerResume", JsonPrimitive(false))
    return this
}

fun FakeCore.favorites(): FakeCore {
    ret("emby.listFavorites", arr(
        item("f1", "谎言的代价", "Episode", series = "寂静的星河", season = 2, episode = 4),
        item("f2", "大地之心", "Episode", series = "幕府将军", season = 1, episode = 8),
        item("f3", "黑暗森林", "Episode", series = "三体", season = 1, episode = 23),
        item("f4", "第一集", "Episode", series = "繁花", season = 1, episode = 1),
        *(0 until showItems.size).map { showItems[it] }.toTypedArray(),
    ))
    return this
}

fun FakeCore.downloads(): FakeCore {
    ret("download.list", arr(
        buildJsonObject { put("id", "d1"); put("item_id", "se2e5"); put("title", "回声"); put("series_name", "寂静的星河"); put("season_number", 2); put("episode_number", 5)
            put("status", "downloading"); put("total_bytes", 6_227_702_579L); put("received_bytes", 2_576_980_377L); put("progress", 0.41) },
        buildJsonObject { put("id", "d2"); put("item_id", "sh9"); put("title", "红天"); put("series_name", "幕府将军"); put("season_number", 1); put("episode_number", 9)
            put("status", "failed"); put("error", "服务器拒绝了请求(403)"); put("total_bytes", 0); put("received_bytes", 0); put("progress", 0.0) },
        buildJsonObject { put("id", "d3"); put("item_id", "m1"); put("title", "沙丘 2"); put("status", "completed"); put("total_bytes", 16_320_875_724L); put("received_bytes", 16_320_875_724L); put("progress", 1.0) },
        buildJsonObject { put("id", "d4"); put("item_id", "n2"); put("title", "第一集"); put("series_name", "繁花"); put("season_number", 1); put("episode_number", 1)
            put("status", "completed"); put("total_bytes", 2_254_857_830L); put("received_bytes", 2_254_857_830L); put("progress", 1.0) },
    ))
    return this
}

fun FakeCore.local(): FakeCore {
    ret("source.listDir", arr(
        buildJsonObject { put("id", "dir-dune"); put("name", "沙丘 2 (2024)"); put("is_dir", true) },
        buildJsonObject { put("id", "f-poor"); put("name", "Poor.Things.2023.2160p.UHD.BluRay.REMUX.HDR.HEVC.Atmos-EXAMPLE.mkv"); put("is_video", true); put("size", 76_450_000_000L) },
        buildJsonObject { put("id", "f-fall"); put("name", "坠落的审判.2023.1080p.mkv"); put("is_video", true); put("size", 9_019_431_321L) },
        buildJsonObject { put("id", "f-nfo"); put("name", "movie.nfo"); put("is_video", false); put("size", 2048) },
        buildJsonObject { put("id", "f-pig"); put("name", "周处除三害.2024.1080p.mp4"); put("is_video", true); put("size", 4_187_593_113L) },
    ))
    return this
}
