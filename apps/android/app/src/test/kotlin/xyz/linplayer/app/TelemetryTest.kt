package xyz.linplayer.app

import io.sentry.Hint
import io.sentry.SentryEvent
import io.sentry.SentryOptions
import io.sentry.protocol.SentryException
import org.junit.Assert.assertEquals
import org.junit.Test

class TelemetryTest {
    /** 走 configure 装上的那个 beforeSend,不是直接调纯函数 —— 漏挂时纯函数测试照样绿。 */
    @Test
    fun beforeSendStripsTokensFromExceptions() {
        val o = SentryOptions()
        Telemetry.configure(o, "https://k@o0.invalid/0", "0.0.0-test")
        val ev = SentryEvent().apply {
            exceptions = listOf(SentryException().apply { value = "GET /Items?api_key=SECRET123&x=1 失败" })
        }
        val out = o.beforeSend!!.execute(ev, Hint())!!
        assertEquals("GET /Items?api_key=<redacted>&x=1 失败", out.exceptions!![0].value)
        assertEquals("linplayer-android@0.0.0-test", o.release)
    }
}
