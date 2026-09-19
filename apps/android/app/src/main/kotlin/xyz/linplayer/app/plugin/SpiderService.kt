package xyz.linplayer.app.plugin

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import com.github.catvod.crawler.Spider
import dalvik.system.DexClassLoader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * `:spider` 子进程(D354):用系统 DexClassLoader 加载 TVBox jar,按 catvod 约定调 Spider(D349)。
 * jar 崩了只死这个进程,主进程的播放不断;主进程那边 [SpiderHost] 负责重连与重新 load。
 */
class SpiderService : Service() {
    companion object {
        const val LOAD = 1
        const val CALL = 2
        const val DISPOSE = 3
    }

    private val spiders = ConcurrentHashMap<String, Spider>()
    private val loaders = ConcurrentHashMap<String, ClassLoader>()
    private val inited = ConcurrentHashMap.newKeySet<String>()
    // 每次调用都可能阻塞在网络上:各走各的线程,一个慢源不挡别的源
    private val pool = Executors.newCachedThreadPool()
    private lateinit var messenger: Messenger

    override fun onCreate() {
        super.onCreate()
        val t = HandlerThread("spider-ipc").apply { start() }
        messenger = Messenger(Handler(t.looper) { m ->
            val id = m.data.getLong("id")
            val req = Json.parseToJsonElement(m.data.getString("req") ?: "{}") as JsonObject
            val what = m.what
            val reply = m.replyTo
            pool.execute {
                val out = Bundle().apply { putLong("id", id) }
                try {
                    when (what) {
                        LOAD -> { load(req); out.putString("data", "") }
                        CALL -> out.putString("data", call(req))
                        DISPOSE -> spiders.remove(s(req, "handle"))?.let { runCatching { it.destroy() } }
                    }
                } catch (e: NoSuchElementException) {
                    out.putString("err", e.message); out.putBoolean("noHandle", true)
                } catch (e: Throwable) {
                    val c = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
                    out.putString("err", c.javaClass.simpleName + ": " + (c.message ?: ""))
                }
                runCatching { reply?.send(Message.obtain().apply { data = out }) }
            }
            true
        })
    }

    override fun onBind(intent: Intent): IBinder = messenger.binder

    private fun s(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.contentOrNull ?: ""

    private fun load(req: JsonObject) {
        val handle = s(req, "handle")
        if (s(req, "kind") != "jar") throw UnsupportedOperationException("安卓端暂不支持 Python 源")
        val jar = fetchJar(s(req, "url"), s(req, "md5"))
        val loader = loaders.getOrPut(jar.absolutePath) {
            DexClassLoader(jar.absolutePath, codeCacheDir.absolutePath, null, javaClass.classLoader)
        }
        // jar 自带的 Init 要先调一次(它往里塞 Context、初始化自带的网络库)
        if (inited.add(jar.absolutePath)) runCatching {
            loader.loadClass("com.github.catvod.spider.Init").getMethod("init", Context::class.java).invoke(null, applicationContext)
        }
        val cls = "com.github.catvod.spider." + s(req, "api").removePrefix("csp_")
        val sp = loader.loadClass(cls).getDeclaredConstructor().newInstance() as Spider
        sp.init(applicationContext, s(req, "ext"))
        spiders[handle] = sp
    }

    /** jar 按 md5 缓存(D352):md5 对得上用本地;没给 md5 或对不上就重下,下载失败沿用旧的。 */
    private fun fetchJar(raw: String, md5: String): File {
        val url = raw.removePrefix("img+")
        val dir = File(cacheDir, "spider-jars").apply { mkdirs() }
        val f = File(dir, hex(MessageDigest.getInstance("SHA-1").digest(url.toByteArray())).take(16) + ".jar")
        if (f.exists() && md5.isNotEmpty() && md5.equals(hex(MessageDigest.getInstance("MD5").digest(f.readBytes())), true)) return f
        val tmp = File(dir, f.name + ".part")
        val ok = runCatching {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000; c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "okhttp/3.12.13")
            c.inputStream.use { i -> tmp.outputStream().use { i.copyTo(it) } }
            // Android 14 起动态加载的 dex 必须只读,否则拒绝加载
            if (f.exists()) { f.setWritable(true); f.delete() }
            tmp.renameTo(f) && f.setReadOnly()
        }.getOrDefault(false)
        if (!ok && !f.exists()) throw IllegalStateException("jar 下载失败:$url")
        return f
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private fun call(req: JsonObject): String {
        val sp = spiders[s(req, "handle")] ?: throw NoSuchElementException("没有这个 spider 句柄")
        val a = (req["args"] as? JsonArray) ?: JsonArray(emptyList())
        fun str(i: Int) = (a.getOrNull(i) as? JsonPrimitive)?.contentOrNull ?: ""
        fun bool(i: Int) = (a.getOrNull(i) as? JsonPrimitive)?.booleanOrNull ?: (str(i) == "true")
        fun list(i: Int): List<String> = (a.getOrNull(i) as? JsonArray)?.map { it.jsonPrimitive.content } ?: listOfNotNull(str(i).takeIf { it.isNotEmpty() })
        fun map(i: Int): HashMap<String, String> = HashMap((a.getOrNull(i) as? JsonObject)?.mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull ?: v.toString() } ?: emptyMap())
        val r: Any? = when (val m = s(req, "method")) {
            "homeContent" -> sp.homeContent(bool(0))
            "homeVideoContent" -> sp.homeVideoContent()
            "categoryContent" -> sp.categoryContent(str(0), str(1), bool(2), map(3))
            "detailContent" -> sp.detailContent(list(0))
            "searchContent" -> if (a.size >= 3) sp.searchContent(str(0), bool(1), str(2)) else sp.searchContent(str(0), bool(1))
            "playerContent" -> sp.playerContent(str(0), str(1), list(2))
            "liveContent" -> sp.liveContent(str(0))
            "isVideoFormat" -> sp.isVideoFormat(str(0)).toString()
            "manualVideoCheck" -> sp.manualVideoCheck().toString()
            "action" -> sp.action(str(0))
            else -> throw UnsupportedOperationException("不认识的 spider 方法 $m")
        }
        return r?.toString() ?: ""
    }
}
