package xyz.linplayer.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import xyz.linplayer.app.core.Logs
import xyz.linplayer.app.data.arr
import xyz.linplayer.app.data.obj
import xyz.linplayer.app.data.str
import java.util.concurrent.TimeUnit

/**
 * 追剧日历开播提醒(插件 SPEC 18.1,D366):应用没开也要能提醒,所以走系统调度的周期任务。
 * 核心层记着提醒过哪些(`sync.calendarDue` 只回「新开播且没提醒过」的),重复跑不会重复通知。
 */
class CalendarWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val core = (applicationContext as LinPlayerApp).core
        val due = runCatching { core.callJson("sync.calendarDue") }.getOrElse {
            // 付费门没过(D551,核心层一处堵死):没解锁就没有提醒,这不是失败,别排重试。
            if ((it as? xyz.linplayer.app.core.CoreException)?.code == "E_PERMISSION") return Result.success()
            Logs.w("calendar", "开播提醒查询失败:" + it.message)
            return Result.retry()
        }.arr().mapNotNull { it.obj() }
        if (due.isEmpty()) return Result.success()
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "追剧开播提醒", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(applicationContext, 0,
            Intent(applicationContext, MainActivity::class.java).putExtra("lp_page", "calendar"), PendingIntent.FLAG_IMMUTABLE)
        due.take(5).forEachIndexed { i, e ->
            val title = e.str("title") ?: return@forEachIndexed
            val n = NotificationCompat.Builder(applicationContext, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle("「$title」开播了")
                .setContentText(e.str("subtitle") ?: "点开看追剧日历")
                .setContentIntent(open).setAutoCancel(true).build()
            runCatching { nm.notify(NOTIFY_BASE + i, n) } // 没给通知权限:系统直接丢,这里无事可做
        }
        return Result.success()
    }

    companion object {
        private const val CHANNEL = "calendar"
        private const val NOTIFY_BASE = 4200

        /** 每小时一次;KEEP = 已排上的不重排,冷启动反复调用无副作用。 */
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<CalendarWorker>(1, TimeUnit.HOURS).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("calendar-due", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
