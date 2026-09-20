package xyz.linplayer.app

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.linplayer.app.plugin.PluginNav
import xyz.linplayer.app.tv.TvRoute
import xyz.linplayer.app.tv.tvOfficialRoute
import xyz.linplayer.app.ui.Route

/**
 * 官方路由名是公开契约(插件 SPEC 20.3):改名算破坏性变更,所以拿表钉住。
 * 表里在安卓端没有对应页面的那几个必须回 null —— 静默不动的表现是「点了没反应」。
 */
class PluginNavTest {
    private val none: (String) -> String? = { null }

    @Test fun 手机认得表里有的路由() {
        listOf("home", "aggregate", "library", "detail", "search", "history", "favorites",
            "downloads", "ranking", "calendar", "servers", "player", "plugins", "settings",
            "settings.extensions", "server.add", "login", "settings.account",
            "settings.appearance", "settings.playback", "settings.danmaku",
            "settings.storage", "settings.about")
            .forEach { assertNotNull(it, xyz.linplayer.app.officialRoute(it, none)) }
    }

    @Test fun 手机没有的页面回null不是回首页() {
        listOf("person", "settings.network", "settings.developer", "aggregate.nope")
            .forEach { assertNull(it, xyz.linplayer.app.officialRoute(it, none)) }
    }

    @Test fun 参数进得去路由对象() {
        val p = mapOf("id" to "x7", "type" to "Movie", "title" to "片名")
        assertEquals(Route.Detail("x7", "Movie"), officialRoute("detail") { p[it] })
        assertEquals(TvRoute.Library("x7", "片名"), tvOfficialRoute("library") { p[it] })
    }

    @Test fun TV没有排行榜和追剧日历() {
        assertNull(tvOfficialRoute("ranking", none))
        assertNull(tvOfficialRoute("calendar", none))
        assertNotNull(tvOfficialRoute("settings.subtitle", none))
    }

    @Test fun 角标认入口名给不认识的报错() {
        PluginNav.host = object : PluginNav.Host {
            override fun push(route: String, params: JsonObject, replace: Boolean) = true
            override fun back() {}
        }
        PluginNav.handle("nav.setBadge", badge("downloads", "3"))
        assertEquals("3", PluginNav.badges["downloads"])
        // 0 是「摘掉」不是「画个 0」
        PluginNav.handle("nav.setBadge", badge("downloads", "0"))
        assertNull(PluginNav.badges["downloads"])
        val e = runCatching { PluginNav.handle("nav.setBadge", badge("player", "3")) }.exceptionOrNull()
        assertTrue(e is UnsupportedOperationException)
        PluginNav.host = null
    }

    private fun badge(target: String, text: String) =
        JsonObject(mapOf("target" to JsonPrimitive(target), "badge" to JsonPrimitive(text)))
}
