package xyz.linplayer.app.plugin

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.core.content.FileProvider
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive
import xyz.linplayer.app.core.Logs
import xyz.linplayer.app.data.obj
import xyz.linplayer.app.data.str
import java.io.File

/**
 * 插件的 `system.*`(SPEC 13,core/plugin/rt/system.go)。
 *
 * 目标安不安全核心层的 badAppTarget 已经判过,这里只管在安卓上把它发出去,
 * 以及把「这一端做不到」如实回上去 —— 电视上没有浏览器、没有分享面板是常态。
 */
object PluginSystem {

    val ops = setOf("system.openUrl", "system.openApp",
        "system.clipboardRead", "system.clipboardWrite", "system.share")

    fun handle(activity: Activity, op: String, args: JsonObject): JsonElement = when (op) {
        "system.openUrl" -> {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(args.str("url") ?: ""))
            if (i.resolveActivity(activity.packageManager) == null)
                throw UnsupportedOperationException("这台设备上没有能打开网页的应用(电视常见),链接没能打开")
            activity.startActivity(i)
            JsonNull
        }
        "system.openApp" -> JsonPrimitive(openApp(activity, args["target"]))
        "system.clipboardRead" -> JsonPrimitive(clipboardRead(activity))
        "system.clipboardWrite" -> {
            clipboard(activity).setPrimaryClip(ClipData.newPlainText("", args.str("text") ?: ""))
            JsonNull
        }
        "system.share" -> share(activity, args)
        else -> throw UnsupportedOperationException("安卓端不支持 $op")
    }

    /**
     * 深链打不开是**正常答案**不是错误:SDK 签名就是 `Promise<boolean>`。
     *
     * 不查 resolveActivity —— Android 11 起包可见性会把它过滤成 null,
     * 而 startActivity 本身不受过滤,所以只有真发一次才知道有没有人接。
     */
    private fun openApp(activity: Activity, target: JsonElement?): Boolean {
        val i = target.obj()?.let { intentOf(it) }
            ?: Intent(Intent.ACTION_VIEW, Uri.parse((target as? JsonPrimitive)?.content ?: ""))
        return try {
            activity.startActivity(i)
            true
        } catch (e: ActivityNotFoundException) {
            Logs.w("plugin", "openApp 没有 App 能接:${i.action}"); false
        } catch (e: SecurityException) {
            Logs.w("plugin", "openApp 被目标 App 拒绝:${e.message}"); false
        }
    }

    private fun intentOf(o: JsonObject): Intent {
        val i = Intent(o.str("action")?.takeIf { it.isNotEmpty() } ?: Intent.ACTION_VIEW)
        o.str("data")?.takeIf { it.isNotEmpty() }?.let { i.data = Uri.parse(it) }
        o.str("package")?.takeIf { it.isNotEmpty() }?.let { i.setPackage(it) }
        o["extras"].obj()?.forEach { (k, v) -> putExtra(i, k, v) }
        return i
    }

    /** JSON 只有三种标量,按原类型放 —— 全转字符串的话目标 App 的 getIntExtra 取不到。 */
    private fun putExtra(i: Intent, k: String, v: JsonElement) {
        val p = v as? JsonPrimitive ?: return
        if (p is JsonNull) return
        when {
            p.isString -> i.putExtra(k, p.content)
            p.booleanOrNull != null -> i.putExtra(k, p.booleanOrNull!!)
            p.longOrNull != null -> i.putExtra(k, p.longOrNull!!)
            else -> i.putExtra(k, p.content)
        }
    }

    /** Android 10 起只有前台窗口读得到剪贴板,拿不到时回空串并记一笔,不装作成功。 */
    private fun clipboardRead(activity: Activity): String {
        val clip = clipboard(activity).primaryClip?.takeIf { it.itemCount > 0 }
        val s = clip?.getItemAt(0)?.coerceToText(activity)?.toString()
        if (s == null) Logs.w("plugin", "剪贴板读不到内容(Android 10+ 只允许前台窗口读)")
        return s ?: ""
    }

    private fun clipboard(c: Context) =
        c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private fun share(activity: Activity, args: JsonObject): JsonElement {
        val text = listOfNotNull(args.str("text"), args.str("url"))
            .filter { it.isNotEmpty() }.joinToString("\n")
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
        args.str("title")?.takeIf { it.isNotEmpty() }?.let { send.putExtra(Intent.EXTRA_SUBJECT, it) }
        if (text.isNotEmpty()) send.putExtra(Intent.EXTRA_TEXT, text)
        args.str("image")?.takeIf { it.isNotEmpty() }?.let {
            send.setType("image/png").putExtra(Intent.EXTRA_STREAM, imageUri(activity, it))
        }
        if (send.resolveActivity(activity.packageManager) == null)
            throw UnsupportedOperationException("这台设备上没有能接收分享的应用(电视通常没有分享面板)")
        activity.startActivity(Intent.createChooser(send, args.str("title") ?: "分享")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        return JsonNull
    }

    /** file:// 交出去会当场 FileUriExposedException,必须过 FileProvider(res/xml/file_paths.xml)。 */
    private fun imageUri(activity: Activity, b64: String): Uri {
        val png = runCatching { Base64.decode(b64, Base64.DEFAULT) }
            .getOrElse { throw IllegalArgumentException("share 的 image 不是合法的 base64") }
        val f = File(activity.cacheDir, "plugin-share").apply { mkdirs() }.let { File(it, "share.png") }
        f.writeBytes(png)
        return FileProvider.getUriForFile(activity, activity.packageName + ".fileprovider", f)
    }
}
