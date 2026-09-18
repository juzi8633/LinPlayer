package xyz.linplayer.app

import android.content.Context
import io.sentry.SentryEvent
import io.sentry.SentryOptions
import io.sentry.android.core.SentryAndroid
import io.sentry.protocol.Message

/**
 * 崩溃上报(Sentry)。口径与 PC 端 `Telemetry.cs` 一致:只报崩溃 + 匿名活跃人数,
 * 不采 PII、不开性能追踪;出站前抹掉 URL 里的 api_key/token(Emby 请求 URL 就带 token)。
 */
object Telemetry {
    fun init(ctx: Context) {
        // 本地构建没注 DSN = 不启用,开发机的崩溃不灌进线上
        if (BuildConfig.SENTRY_DSN.isEmpty()) return
        SentryAndroid.init(ctx) { configure(it, BuildConfig.SENTRY_DSN, BuildConfig.VERSION_NAME) }
    }

    fun configure(o: SentryOptions, dsn: String, version: String) {
        o.dsn = dsn
        // 前缀和 PC 端区分:两端进同一个项目,活跃人数徽章要一起数
        o.release = "linplayer-android@$version"
        o.isSendDefaultPii = false
        o.tracesSampleRate = null
        o.isEnableAutoSessionTracking = true
        o.beforeSend = SentryOptions.BeforeSendCallback { ev, _ -> scrub(ev) }
    }

    private fun scrub(ev: SentryEvent): SentryEvent {
        ev.message?.let { m -> ev.message = Message().apply { message = scrub(m.message); formatted = scrub(m.formatted) } }
        ev.exceptions?.forEach { it.value = scrub(it.value) }
        return ev
    }

    private val secretQuery =
        Regex("""(?i)\b(api_key|apikey|x-emby-token|token|access_token|pw|password|sign|authorization)=[^&\s"'<>]+""")

    fun scrub(s: String?): String? = s?.let { secretQuery.replace(it, "$1=<redacted>") }
}
