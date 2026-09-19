package com.github.catvod.crawler;

import android.util.Log;

/** catvod 约定的日志入口,jar 里直接调它。只写 logcat,不进我们的日志文件(站点内容可能很长)。 */
public class SpiderDebug {
    public static void log(Throwable e) {
        Log.w("LinPlayer.spider", e);
    }

    public static void log(String msg) {
        Log.d("LinPlayer.spider", msg);
    }
}
