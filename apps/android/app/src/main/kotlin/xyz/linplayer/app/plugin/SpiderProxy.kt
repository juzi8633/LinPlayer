package xyz.linplayer.app.plugin

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * TVBox 约定的本地代理(D353):jar 把地址写成 `http://127.0.0.1:9978/proxy?...`,
 * 请求到这里转给 jar 自带的 `com.github.catvod.spider.Proxy.proxy(Map)`,它回 `[状态码, 类型, 流(, 头)]`。
 * 跑在 `:spider` 进程里(jar 就在这儿);优先占 9978,占不到用动态端口 —— 写死 9978 的 jar 那时就会失败。
 * 只监听回环。
 */
object SpiderProxy {
    private const val PREFERRED = 9978
    private val loaders = CopyOnWriteArrayList<ClassLoader>()
    private val pool = Executors.newCachedThreadPool()
    @Volatile var port = 0; private set

    /** 最近加载的 jar 排前面:同时有几个 jar 时,多半是正在播的那个在代理。 */
    fun register(loader: ClassLoader) {
        loaders.remove(loader)
        loaders.add(0, loader)
    }

    @Synchronized fun start() {
        if (port != 0) return
        val loopback = InetAddress.getByName("127.0.0.1")
        val ss = runCatching { ServerSocket(PREFERRED, 50, loopback) }.getOrElse { ServerSocket(0, 50, loopback) }
        port = ss.localPort
        Thread({ while (true) { val s = runCatching { ss.accept() }.getOrNull() ?: break; pool.execute { serve(s) } } }, "spider-proxy").start()
    }

    private fun serve(sock: Socket) = sock.use { s ->
        val input = s.getInputStream().bufferedReader()
        val line = input.readLine() ?: return
        while (!input.readLine().isNullOrEmpty()) Unit // 请求头用不上
        val target = line.split(" ").getOrNull(1) ?: "/"
        val out = s.getOutputStream()
        if (!target.startsWith("/proxy")) return reply(out, 404, "text/plain", "只认 /proxy".byteInputStream())
        val params = target.substringAfter('?', "").split('&').filter { it.contains('=') }.associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }
        for (l in loaders) {
            val r = runCatching {
                l.loadClass("com.github.catvod.spider.Proxy").getMethod("proxy", Map::class.java).invoke(null, params) as? Array<*>
            }.onFailure { Log.w("LinPlayer.spider", "jar 代理出错", it) }.getOrNull() ?: continue
            @Suppress("UNCHECKED_CAST")
            return reply(out, (r.getOrNull(0) as? Number)?.toInt() ?: 200, r.getOrNull(1) as? String ?: "application/octet-stream",
                r.getOrNull(2) as? InputStream ?: ByteArray(0).inputStream(), r.getOrNull(3) as? Map<String, String>)
        }
        reply(out, 404, "text/plain", "没有 jar 认领这个代理请求".byteInputStream())
    }

    private fun reply(out: OutputStream, code: Int, mime: String, body: InputStream, headers: Map<String, String>? = null) {
        val bytes = body.use { it.readBytes() }
        val head = StringBuilder("HTTP/1.1 $code OK\r\nContent-Type: $mime\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n")
        headers?.forEach { (k, v) -> head.append("$k: $v\r\n") }
        out.write(head.append("\r\n").toString().toByteArray())
        out.write(bytes)
        out.flush()
    }
}
