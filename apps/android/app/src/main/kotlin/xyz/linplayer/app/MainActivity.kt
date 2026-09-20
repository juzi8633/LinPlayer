package xyz.linplayer.app

import android.Manifest
import android.app.PictureInPictureParams
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.linplayer.app.data.AppState
import xyz.linplayer.app.ui.theme.LpTheme

/**
 * 单 Activity。**双形态分流靠 UiModeManager,不是两个 Activity**(U1.1)——
 * 两个 Activity 意味着两份深链、两份生命周期、两份 surface 交接。
 */
class MainActivity : ComponentActivity() {

    private lateinit var app: AppState

    /** 深链(U1.26)。冷启动走 `onCreate`、热启动走 `onNewIntent`,**两条路径都要收**。 */
    private val deepLink = MutableStateFlow<String?>(null)

    private val askNotification =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 拒了就没有通知栏控制,不拦流程 */ }

    private val askStorage =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 拒了就只是列不出文件,不拦流程 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 开屏必须在 super.onCreate 之前装(U1.17)。
        // ★ 不挂 setKeepOnScreenCondition:核心层已经在 Application 里起好了,
        //   把开屏拖到「首页有数据」等于做了一个假的加载页 —— 而契约是骨架先出。
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        app = AppState((application as LinPlayerApp).core, lifecycleScope)
        // 设备 id 必须**持久**:每次换一个会把服务器的设备列表刷满,续播会话也对不上
        runCatching {
            Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()?.let { app.setDeviceId(it) }

        // 上次崩了就自动把现场发给开发者,不问(用户说不清出了什么问题)
        lifecycleScope.launch {
            if (xyz.linplayer.app.data.Report.sendPending(this@MainActivity, app))
                app.toast("上次异常退出,已把报告发给开发者")
        }

        handleIntent(intent)
        // 自检直达:adb shell am start ... -e lp_login "<地址>|<用户>|<密码>"
        // 不能靠 input text —— 焦点落在哪儿不确定,实测一个字符都没进去(PC 端同一条教训)
        intent?.getStringExtra("lp_login")?.let { SelfCheck.login = it }
        intent?.getStringExtra("lp_page")?.let { SelfCheck.page = it }
        // 自检:先用开发版加载一个本地插件目录(不必先打包再安装)
        intent?.getStringExtra("lp_devplugin")?.let { SelfCheck.devPlugin = it }

        xyz.linplayer.app.data.UiPrefs.load(this)

        setContent {
            LpTheme(darkOverride = when (xyz.linplayer.app.data.UiPrefs.theme.value) {
                "dark" -> true; "light" -> false; else -> null
            }) {
                // TV 草稿画廊:`am start ... -e lp_page 'tvdrafts:<n>'`。不连网,不看登录态,手机上也能打开
                val tvDraft = SelfCheck.page?.takeIf { it.startsWith("tvdrafts") }
                when {
                    tvDraft != null -> xyz.linplayer.app.ui.drafts.tv.TvDraftGallery(
                        tvDraft.substringAfter(":", "0").toIntOrNull() ?: 0)
                    // `-e lp_page tv`:在不是电视的设备上强制走 TV 形态,给真机自检用
                    // `-e lp_page tv` 或 `tv:<页>`:在不是电视的设备上强制走 TV 形态
                    isTelevision() || SelfCheck.page?.startsWith("tv") == true -> xyz.linplayer.app.tv.TvRoot(app)
                    else -> PhoneRoot(app)
                }
            }
        }

        // 插件要壳做的事(WebView / jar spider),见 plugin/PluginShell
        xyz.linplayer.app.plugin.PluginShell.start(this, app, lifecycleScope)
        CalendarWorker.schedule(this)

        // 深链交给核心层解析,**UI 不自己解析 URL**(SPEC §8.5)
        lifecycleScope.launch {
            deepLink.collect { url ->
                if (url == null) return@collect
                runCatching {
                    app.core.callJson("account.parseDeepLink",
                        JsonObject(mapOf("url" to JsonPrimitive(url))))
                }.onSuccess { app.refreshSession() }.onFailure { app.report(it) }
                deepLink.value = null
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** 真机自检用的直达入口。发行包里它永远是空的 —— 只有 adb 传 extra 才会有值。 */
    object SelfCheck {
        @Volatile var login: String? = null
        @Volatile var page: String? = null

        /* 热启动再送一次 `-e lp_page` 时 +1。
           ☠ 没有它的话每次直达都得先 force-stop,于是**只量得到冷路径** ——
           首帧预算里「第二次进同一页」那半截永远没有数。 */
        val pageTick = androidx.compose.runtime.mutableIntStateOf(0)
        /** 开发版加载的本地插件目录(`-e lp_devplugin <路径>`)。 */
        @Volatile var devPlugin: String? = null
    }

    private fun handleIntent(i: Intent?) {
        if (i?.action == Intent.ACTION_VIEW) deepLink.value = i.dataString
        i?.getStringExtra("lp_page")?.let { SelfCheck.page = it; SelfCheck.pageTick.intValue++ }
    }

    /**
     * 通知权限(U1.27)。Android 13+ 要运行时申请,**拒了不拦流程** —— 只是没有通知栏控制。
     *
     * ★ **不在冷启动时问。** 用户还没做任何事就弹权限框是最招人烦的一种要法,
     *   而且这个权限只在**后台播放要挂通知栏**时才有意义(U1.21)。
     *   所以由播放页在起播时调它。
     */
    fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) return
        askNotification.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * 本机视频的读权限。**在用户要添加本机文件夹的那一刻要**,不在冷启动时要。
     *
     * ★ 权限名按版本分:Android 13 起是 `READ_MEDIA_VIDEO`,之前是
     *   `READ_EXTERNAL_STORAGE`(manifest 里两条都声明了,`maxSdkVersion=32`)。
     *   要错了的表现是**弹不出框**且 `checkSelfPermission` 恒 denied ——
     *   界面上只会是「这个文件夹是空的」,看着像路径填错了。
     * ★ 拒了不拦流程:用户可能只想连服务器。
     */
    fun askStoragePermission() {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_VIDEO
        else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) return
        askStorage.launch(perm)
    }

    /**
     * 画中画(U1.25)。Home 键进 PiP。
     * ★ 分屏 / 自由窗口下**不进 PiP**(那是两个窗口管理器在抢同一件事)。
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (isInMultiWindowMode) return
        if (!app.wantsPip) return
        runCatching {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
            )
        }
    }

    /** 双形态分流(SPEC §8.2):同一个 APK、同一个 Activity。 */
    private fun isTelevision(): Boolean {
        val ui = getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        return ui.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }
}
