package xyz.linplayer.app

import android.app.Activity
import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import xyz.linplayer.app.plugin.PluginSystem

/**
 * `system.*` 的三条会静默出错的路:对象 target 的字段没进 Intent(目标 App 收到空意图)、
 * 没人接时抛异常而不是回 false(插件那头变成 reject)、这端做不到却不吭声。
 */
@RunWith(RobolectricTestRunner::class)
// 用裸 Application:LinPlayerApp 一起来就 System.loadLibrary("mpv"),JVM 上没有这个库
@Config(sdk = [36], application = Application::class)
class PluginSystemTest {
    private fun activity(): Activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    private fun call(a: Activity, op: String, args: JsonObject) = PluginSystem.handle(a, op, args)

    @Test fun 对象target的每个字段都进了Intent() {
        val a = activity()
        val target = buildJsonObject {
            put("action", "android.intent.action.SENDTO")
            put("data", "lptest://x/y")
            put("package", "com.example.nothing")
            put("extras", buildJsonObject { put("s", "文"); put("n", 7); put("b", true) })
        }
        val ok = call(a, "system.openApp", buildJsonObject { put("target", target) })
        assertEquals(JsonPrimitive(true), ok)
        val i = shadowOf(a).nextStartedActivity!!
        assertEquals("android.intent.action.SENDTO", i.action)
        assertEquals("lptest://x/y", i.data.toString())
        assertEquals("com.example.nothing", i.`package`)
        assertEquals("文", i.getStringExtra("s"))
        assertEquals(7L, i.getLongExtra("n", 0))
        assertTrue(i.getBooleanExtra("b", false))
    }

    @Test fun 字符串target组成ACTION_VIEW() {
        val a = activity()
        call(a, "system.openApp", buildJsonObject { put("target", "lptest://open") })
        val i = shadowOf(a).nextStartedActivity!!
        assertEquals(Intent.ACTION_VIEW, i.action)
        assertEquals("lptest://open", i.data.toString())
    }

    @Test fun 没有App能接时回false而不是抛() {
        val a = activity()
        // 让 startActivity 走真实的「没人接」路径:默认 Robolectric 什么都接
        shadowOf(RuntimeEnvironment.getApplication()).checkActivities(true)
        val ok = call(a, "system.openApp", buildJsonObject { put("target", "lptest://nobody") })
        assertEquals(JsonPrimitive(false), ok)
    }

    @Test fun 剪贴板写进去读得回来() {
        val a = activity()
        call(a, "system.clipboardWrite", buildJsonObject { put("text", "剪贴板内容") })
        assertEquals(JsonPrimitive("剪贴板内容"), call(a, "system.clipboardRead", JsonObject(emptyMap())))
        // 空剪贴板不许假装成功:回空串
        (a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).clearPrimaryClip()
        assertEquals(JsonPrimitive(""), call(a, "system.clipboardRead", JsonObject(emptyMap())))
    }

    @Test fun 这端没有分享面板时说清楚是哪一端() {
        val a = activity()
        val e = runCatching { call(a, "system.share", buildJsonObject { put("text", "x") }) }.exceptionOrNull()
        assertTrue("$e", e is UnsupportedOperationException)
        assertTrue("$e", e!!.message!!.contains("设备"))
        assertFalse("不许静默:必须有话给用户看", e.message.isNullOrEmpty())
    }
}
