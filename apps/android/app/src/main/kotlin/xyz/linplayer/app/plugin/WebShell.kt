package xyz.linplayer.app.plugin

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Bitmap
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import xyz.linplayer.app.core.toJsonObject
import xyz.linplayer.app.data.arr
import xyz.linplayer.app.data.obj
import xyz.linplayer.app.data.str

/**
 * 插件用的 WebView(SPEC 5.8,D59 D62 D496 D497)。全部挂在 MainActivity 顶上的一层 FrameLayout 里:
 * 平时平移到屏幕外(不挡触摸),要用户动手时挪回来并加一条「完成」栏 —— 同一个 WebView,
 * 已经点开的播放器、过了一半的验证不会因为换窗口丢掉。
 */
@SuppressLint("SetJavaScriptEnabled")
object WebShell {
    private var host: FrameLayout? = null
    // 隐藏 WebView 全局最多 3 个,超出排队(排队时间计入调用超时,D497)
    private val slots = Semaphore(3)
    private val defaultMedia = Regex("""\.(m3u8|mp4|flv)(\?|$)|/m3u8\b""", RegexOption.IGNORE_CASE)

    fun available(ctx: Context): Boolean = runCatching { android.webkit.WebSettings.getDefaultUserAgent(ctx); true }.getOrDefault(false)

    fun attach(activity: Activity) {
        val f = FrameLayout(activity)
        activity.addContentView(f, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        host = f
    }

    private class Page(val web: WebView, val frame: FrameLayout)

    private fun newPage(activity: Activity, ua: String?): Page {
        val web = WebView(activity)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        if (!ua.isNullOrEmpty()) web.settings.userAgentString = ua
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        val frame = FrameLayout(activity)
        frame.setBackgroundColor(Color.BLACK)
        frame.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val h = host ?: error("WebView 容器还没挂上")
        h.addView(frame, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.translationX = 100000f // 屏幕外:照常加载执行,但不挡界面触摸
        return Page(web, frame)
    }

    /** 挪回屏幕并加一条标题 + 「完成」;点完成回调 onDone。 */
    private fun show(p: Page, title: String, onDone: () -> Unit) {
        val bar = LinearLayout(p.frame.context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.argb(235, 20, 24, 32))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 48, 24, 12)
            addView(TextView(context).apply { text = title; setTextColor(Color.WHITE) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(context).apply { text = "完成"; setOnClickListener { onDone() } })
        }
        p.frame.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        p.frame.translationX = 0f
    }

    private fun close(p: Page) {
        host?.removeView(p.frame)
        p.web.stopLoading()
        p.web.destroy()
    }

    private fun strs(o: JsonObject?, k: String) = o?.get(k).arr().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    private fun ms(o: JsonObject?, k: String, def: Long) = o?.get(k)?.jsonPrimitive?.longOrNull?.takeIf { it > 0 } ?: def

    /** 嗅探:拦截请求抓视频地址;超时仍没抓到且允许时,亮出来让用户点播放 / 过验证。 */
    suspend fun sniff(activity: Activity, args: JsonObject): JsonElement = slots.withPermit {
        val o = args["opts"].obj()
        val match = strs(o, "match").map { Regex(it, RegexOption.IGNORE_CASE) }.ifEmpty { listOf(defaultMedia) }
        val block = strs(o?.get("block").obj(), "urlPatterns").map { Regex(it, RegexOption.IGNORE_CASE) }
        val headers = o?.get("headers").obj()?.mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull ?: "" } ?: emptyMap()
        val found = CompletableDeferred<Pair<String, Map<String, String>>>()
        val page = withContext(Dispatchers.Main) {
            newPage(activity, o.str("userAgent")).also { p ->
                p.web.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                        val u = req.url.toString()
                        if (block.any { it.containsMatchIn(u) }) return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), null)
                        if (match.any { it.containsMatchIn(u) }) {
                            val h = req.requestHeaders.filterKeys { it.equals("Referer", true) || it.equals("User-Agent", true) || it.equals("Origin", true) }.toMutableMap()
                            CookieManager.getInstance().getCookie(u)?.let { h["Cookie"] = it }
                            found.complete(u to h)
                        }
                        return null
                    }
                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                        o.str("injectScript")?.takeIf { it.isNotEmpty() }?.let { view.evaluateJavascript(it, null) }
                    }
                }
                p.web.loadUrl(args.str("url") ?: "", headers)
            }
        }
        try {
            coroutineScope {
                val visible = o?.get("allowVisible")?.jsonPrimitive?.booleanOrNull != false
                launch {
                    delay(ms(o, "visibleAfter", 15_000))
                    if (visible && !found.isCompleted) withContext(Dispatchers.Main) { show(page, "请完成验证或点一下播放") { } }
                }
                val r = withTimeoutOrNull(ms(o, "timeout", 60_000)) { found.await() } ?: throw ShellTimeout("网页嗅探超时,没抓到视频地址")
                coroutineContext.cancelChildren()
                toJsonObject(mapOf("url" to r.first, "headers" to r.second))
            }
        } finally {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) { close(page) }
        }
    }

    /** 加载页面后执行一段 JS 取值(D62);waitFor 是等到出现的 CSS 选择器。 */
    suspend fun evaluate(activity: Activity, args: JsonObject): JsonElement = slots.withPermit {
        val o = args["opts"].obj()
        val loaded = CompletableDeferred<Unit>()
        val page = withContext(Dispatchers.Main) {
            newPage(activity, o.str("userAgent")).also { p ->
                p.web.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) { loaded.complete(Unit) }
                }
                p.web.loadUrl(args.str("url") ?: "")
            }
        }
        try {
            val deadline = System.currentTimeMillis() + ms(o, "timeout", 30_000)
            withTimeoutOrNull(deadline - System.currentTimeMillis()) { loaded.await() } ?: throw ShellTimeout("页面加载超时")
            o.str("waitFor")?.takeIf { it.isNotEmpty() }?.let { sel ->
                while (js(page, "!!document.querySelector(${Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(sel))})") != "true") {
                    if (System.currentTimeMillis() > deadline) throw ShellTimeout("等不到页面上的元素:$sel")
                    delay(300)
                }
            }
            val raw = js(page, args.str("script") ?: "null")
            runCatching { Json.parseToJsonElement(raw) }.getOrElse { JsonPrimitive(raw) }
        } finally {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) { close(page) }
        }
    }

    private suspend fun js(p: Page, script: String): String {
        val r = CompletableDeferred<String>()
        withContext(Dispatchers.Main) { p.web.evaluateJavascript(script) { r.complete(it ?: "null") } }
        return r.await()
    }

    /** 整页可见 WebView(过盾、登录):用户点完成或条件满足时返回 Cookie 与 localStorage。 */
    suspend fun open(activity: Activity, args: JsonObject): JsonElement {
        val o = args["opts"].obj()
        val until = o?.get("until").obj()
        val urlRe = until.str("urlMatches")?.takeIf { it.isNotEmpty() }?.let { Regex(it) }
        val cookieName = until.str("cookieName")?.takeIf { it.isNotEmpty() }
        val done = CompletableDeferred<Unit>()
        val page = withContext(Dispatchers.Main) {
            newPage(activity, o.str("userAgent")).also { p ->
                p.web.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        val u = url ?: return
                        if (urlRe?.containsMatchIn(u) == true) done.complete(Unit)
                        if (cookieName != null && CookieManager.getInstance().getCookie(u)?.split(";")?.any { it.trim().startsWith("$cookieName=") } == true) done.complete(Unit)
                    }
                }
                p.web.loadUrl(args.str("url") ?: "")
                show(p, o.str("title")?.takeIf { it.isNotEmpty() } ?: "完成验证后点「完成」") { done.complete(Unit) }
            }
        }
        try {
            done.await()
            return withContext(Dispatchers.Main) {
                val finalUrl = page.web.url ?: args.str("url") ?: ""
                val cookies = parseCookies(CookieManager.getInstance().getCookie(finalUrl))
                val ls = js(page, "JSON.stringify(localStorage)")
                val inner = runCatching { Json.parseToJsonElement(Json.parseToJsonElement(ls).jsonPrimitive.content) }.getOrDefault(JsonObject(emptyMap()))
                toJsonObject(mapOf("cookies" to cookies, "localStorage" to inner, "finalUrl" to finalUrl))
            }
        } finally {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) { close(page) }
        }
    }

    private fun parseCookies(raw: String?): Map<String, String> =
        raw.orEmpty().split(";").mapNotNull { kv -> kv.trim().split("=", limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()

    /** [去验证](D323):整页 WebView 过盾,Cookie 进该源的罐子(罐子名 = 数据源开放键)。 */
    suspend fun verify(activity: Activity, app: xyz.linplayer.app.data.AppState, url: String, serverId: String): Boolean {
        val r = open(activity, toJsonObject(mapOf("url" to url, "opts" to mapOf("title" to "完成验证后点「完成」")))).obj()
        val cookies = r?.get("cookies").obj() ?: return false
        if (cookies.isEmpty()) return false
        val pluginId = serverId.removePrefix("plugin:").substringBeforeLast('/')
        return runCatching {
            app.core.callJson("plugin.setCookies", toJsonObject(mapOf("plugin_id" to pluginId, "jar" to serverId, "url" to r.str("finalUrl"), "cookies" to cookies)))
        }.isSuccess
    }
}

private fun kotlin.coroutines.CoroutineContext.cancelChildren() = this[kotlinx.coroutines.Job]?.children?.forEach { it.cancel() }
