package xyz.linplayer.app.plugin

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import xyz.linplayer.app.MainActivity
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.data.str

/**
 * 插件的 `ui.notify`(SPEC 12.5,D195)。配额与折叠在核心层(D547),这里只管发。
 *
 * ☠ Android 13+ 没给权限时系统**直接丢掉**通知,不报错 —— 插件会以为发出去了。
 *   所以先申请,仍然没开就降级成 Toast:用户至少看得见这条消息。
 */
object PluginNotify {
    private const val CHANNEL = "plugin"
    private var seq = 4300

    fun show(activity: Activity, app: AppState, args: JsonObject): JsonElement {
        val title = args.str("title") ?: ""
        val body = args.str("body") ?: ""
        if (!NotificationManagerCompat.from(activity).areNotificationsEnabled()) {
            (activity as? MainActivity)?.askNotificationPermission()
            app.toast(listOf(title, body).filter { it.isNotEmpty() }.joinToString(" · "))
            return JsonNull
        }
        val nm = activity.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "插件通知", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(activity, seq,
            Intent(activity, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(activity, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title).setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open).setAutoCancel(true).build()
        nm.notify(seq++, n)
        return JsonNull
    }
}
