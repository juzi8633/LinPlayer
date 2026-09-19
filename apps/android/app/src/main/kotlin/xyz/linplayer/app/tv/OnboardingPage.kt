package xyz.linplayer.app.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.linplayer.app.core.CoreException
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.ToastKind
import xyz.linplayer.app.data.bool
import xyz.linplayer.app.data.long
import xyz.linplayer.app.data.obj
import xyz.linplayer.app.data.str
import xyz.linplayer.app.data.arr
import xyz.linplayer.app.data.bool
import xyz.linplayer.app.tv.kit.ScopeChips
import xyz.linplayer.app.tv.kit.Skel
import xyz.linplayer.app.tv.kit.StatusDot
import xyz.linplayer.app.tv.kit.TvButton
import xyz.linplayer.app.tv.kit.TvC
import xyz.linplayer.app.tv.kit.TvDim
import xyz.linplayer.app.tv.kit.TvR
import xyz.linplayer.app.tv.kit.TvSp
import xyz.linplayer.app.tv.kit.TvText
import xyz.linplayer.app.tv.kit.TvW
import xyz.linplayer.app.tv.kit.contentArea
import xyz.linplayer.app.tv.kit.tvType
import xyz.linplayer.app.ui.pages.args
import xyz.linplayer.app.ui.pages.withScheme
import xyz.linplayer.app.ui.theme.LpIcons

/**
 * 首次启动 / 添加服务器(UI_TV.md §7.1)—— **同一页两种版式**【继承 PC A-45】:
 * 首次启动 = 全屏无轨;添加服务器 = 带轨、入栈。
 *
 * ★ **初始焦点在「连接」,不在输入框**【用户定 2026-07-21】:焦点落输入框输入法立刻升起盖住半屏,
 *   把扫码这条主路径挡了。
 * ⚠️ `source.formSchema` 不存在(MOBILE_BLOCKERS B1),源类型表照手机端写在这一处。
 */
@Composable
fun OnboardingPage(embedded: Boolean) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val t = tvType
    var kind by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf("") }
    var server by remember { mutableStateOf("https://") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    val nav = if (embedded) LocalNav.current else null
    // 插件提供的服务器类型(D131 D423):装了数据源插件才出现在类型片里
    var pluginForms by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    val pluginValues = remember { androidx.compose.runtime.mutableStateMapOf<String, String>() }
    LaunchedEffect(Unit) {
        pluginForms = runCatching { app.call("source.formSchema") }.getOrNull().arr().mapNotNull { it.obj() }.filter { it.str("kind") == "plugin" }
    }

    /* TV 上逐个勾几十个源太累:仓全订、源全加(不可用的除外),不要的在服务器页整组或单个删。
       订阅免责提示照样给(D523)。 */
    fun addPluginSources(f: JsonObject) {
        if (busy) return
        busy = true; hint = null
        scope.launch {
            try {
                app.toast("订阅内容由你自行添加,与 LinPlayer 官方无关。")
                val pid = f.str("plugin_id") ?: ""
                val tid = f.str("type_id") ?: ""
                var r = app.call("source.createSources", xyz.linplayer.app.ui.pages.j("plugin_id" to pid, "type_id" to tid, "form" to pluginValues.toMap())).obj()
                val repos = r?.get("repos").arr().mapNotNull { it.obj()?.str("id") }
                if (repos.isNotEmpty()) r = app.call("source.createSources", xyz.linplayer.app.ui.pages.j("plugin_id" to pid, "type_id" to tid, "form" to pluginValues.toMap(), "repos" to repos)).obj()
                val chosen = r?.get("sources").arr().mapNotNull { it.obj() }.filter { !it.bool("exists") && it.str("unavailableReason").isNullOrEmpty() }
                if (chosen.isEmpty()) { hint = "这份配置里没有可添加的源"; return@launch }
                app.call("source.addSources", xyz.linplayer.app.ui.pages.j("plugin_id" to pid, "type_id" to tid, "sources" to chosen))
                app.toast("已添加 ${chosen.size} 个源,不要的在服务器页删", ToastKind.Ok)
                app.refreshSession()
                nav?.pop()
            } catch (e: CoreException) {
                hint = e.advice
            } catch (e: Throwable) {
                hint = e.message ?: "添加失败"
            } finally { busy = false }
        }
    }

    if (picking) {
        // 首次启动页不在返回栈里,挑文件夹就地换内容;返回键回表单
        BackHandler { picking = false }
        LocalPickerPage(onDone = { picking = false })
        return
    }

    fun connect() {
        if (busy) return
        busy = true; hint = null
        scope.launch {
            try {
                val r = app.call("emby.login", JsonObject(mapOf(
                    "server" to JsonPrimitive(withScheme(server)), "username" to JsonPrimitive(user),
                    "password" to JsonPrimitive(pass), "device_id" to JsonPrimitive(app.deviceId()),
                ))).obj()
                // ★ emby.login 不收名字,登录成功后补一刀;**补名失败不许把「已经加成功」变成报错**
                val id = r.str("server")
                if (name.isNotBlank() && id != null) runCatching {
                    app.call("account.updateAccount", args("server_id" to id, "name" to name.trim()))
                }
                app.toast("已添加服务器", ToastKind.Ok)
                app.refreshSession()
                nav?.pop()
            } catch (e: CoreException) {
                hint = e.advice
            } catch (e: Throwable) {
                hint = e.message ?: "连接失败"
            } finally { busy = false }
        }
    }

    val body = @Composable {
        Column(Modifier.width(472.dp)) {
            Box(Modifier.size(40.dp).clip(TvR.lg).background(TvC.acc), contentAlignment = Alignment.Center) {
                Icon(LpIcons.play, null, Modifier.size(20.dp), tint = TvC.onAcc)
            }
            Spacer(Modifier.height(TvSp.x12))
            TvText(if (embedded) "添加服务器" else "欢迎使用 LinPlayer", t.display, TvC.fg, weight = TvW.bold)
            TvText("推荐用手机扫右边的码,不用在电视上打字", t.body, TvC.fg2)
            Spacer(Modifier.height(TvSp.x16))
            ScopeChips(listOf("Emby", "本机文件夹") + pluginForms.map { it.str("label") ?: "" }, kind,
                itemModifier = { i -> Modifier.memo("gate.kind.$i") }, onSelect = { kind = it; pluginValues.clear() })
            Spacer(Modifier.height(TvSp.x16))
            if (kind == 0) {
                // 名称排第一行【继承 PC A-46】:不填的话显示名回落成地址,到处都是真实地址
                Field("名称") { TvTextField(name, { name = it }, "可选,默认用服务器自己的名字", 384.dp, Modifier.memo("gate.name")) }
                Field("服务器地址") { TvTextField(server, { server = it }, "https://", 384.dp, Modifier.memo("gate.server")) }
                Field("用户名") { TvTextField(user, { user = it }, "", 384.dp, Modifier.memo("gate.user")) }
                Field("密码") { TvTextField(pass, { pass = it }, "", 384.dp, Modifier.memo("gate.pass"), password = true) }
                Spacer(Modifier.height(TvSp.x12))
                Row(horizontalArrangement = Arrangement.spacedBy(TvSp.x12)) {
                    TvButton(if (busy) "连接中…" else "连接", LpIcons.check, primary = true,
                        modifier = Modifier.memo("gate.connect", initial = true), onClick = { connect() })
                    // 「测试连接」只探测,不添加、不切会话【继承 PC A-47】
                    TvButton("测试连接", LpIcons.sync, modifier = Modifier.memo("gate.test"), onClick = {
                        scope.launch {
                            runCatching {
                                app.call("account.testConnection", JsonObject(mapOf(
                                    "server" to JsonPrimitive(withScheme(server)), "username" to JsonPrimitive(user),
                                    "password" to JsonPrimitive(pass), "device_id" to JsonPrimitive(app.deviceId()),
                                ))).obj()
                            }.onSuccess { d ->
                                app.toast("连上了:${d.str("name").orEmpty()} · 版本 ${d.str("version").orEmpty()}", ToastKind.Ok)
                            }.onFailure { e -> hint = (e as? CoreException)?.advice ?: e.message }
                        }
                    })
                }
            } else if (kind >= 2) {
                val f = pluginForms[kind - 2]
                TvText("由插件「${f.str("plugin_name") ?: ""}」提供。长文本用手机扫右边的码填更快", t.body, TvC.fg2, maxLines = 2)
                Spacer(Modifier.height(TvSp.x8))
                f["fields"].arr().mapNotNull { it.obj() }.forEach { fd ->
                    val key = fd.str("key") ?: return@forEach
                    Field(fd.str("label") ?: key) {
                        TvTextField(pluginValues[key] ?: "", { pluginValues[key] = it }, fd.str("placeholder") ?: "", 384.dp,
                            Modifier.memo("gate.p.$key"), password = fd.str("type") == "password")
                    }
                }
                Spacer(Modifier.height(TvSp.x12))
                TvButton(if (busy) "添加中…" else "添加", LpIcons.check, primary = true, modifier = Modifier.memo("gate.padd"),
                    onClick = { addPluginSources(f) })
            } else {
                TvText("选一个装着影片的文件夹,它会像一台服务器那样出现在服务器页", t.body, TvC.fg2, maxLines = 2)
                Spacer(Modifier.height(TvSp.x12))
                TvButton("选择文件夹", LpIcons.folder, primary = true, modifier = Modifier.memo("gate.folder"), onClick = {
                    if (nav != null) nav.push(TvRoute.LocalPicker) else picking = true
                })
            }
            hint?.let { Spacer(Modifier.height(TvSp.x8)); TvText(it, t.meta, TvC.bad, maxLines = 3) }
        }
        Spacer(Modifier.width(TvSp.x48))
        Column(Modifier.padding(top = TvSp.x24)) { CompanionBlock(200.dp) }
    }
    if (embedded) Row(Modifier.contentArea()) { body() }
    else Box(Modifier.fillMaxSize().background(TvC.bg)) {
        Row(Modifier.fillMaxSize().padding(horizontal = TvDim.safeH, vertical = TvDim.safeV)) { body() }
    }
}

@Composable
private fun Field(label: String, input: @Composable () -> Unit) {
    Row(Modifier.padding(vertical = TvSp.x4), verticalAlignment = Alignment.CenterVertically) {
        TvText(label, tvType.meta, TvC.fg3, Modifier.width(88.dp))
        input()
    }
}

/**
 * 手机遥控二维码块(§7.1 / §9)。三种「扫不了」分开说,下一步动作不同。
 * ☠ **不许自己编失败原因**:旧实现拿到 null 就写「未开启,或电视没连上局域网」,
 *   真因是配置默认值错了,用户插着网线看到只会认为程序在胡说。
 */
@Composable
fun CompanionBlock(qr: Dp, horizontal: Boolean = false) {
    val app = LocalApp.current
    val t = tvType
    var st by remember { mutableStateOf<JsonObject?>(null) }
    LaunchedEffect(Unit) {
        launch {
            app.core.events.collect { ev -> if (ev.name == "companion.status") ev.data.obj()?.let { st = it } }
        }
        while (true) {
            st = runCatching { app.call("companion.status") }.getOrNull().obj() ?: st
            delay(5000)
        }
    }
    val s = st
    when {
        s == null -> Skel(Modifier.size(qr), TvR.lg)
        !s.bool("enabled") -> TvText("手机遥控被关掉了 · 设置 → 通用里打开", t.body, TvC.fg2, maxLines = 2)
        !s.str("error").isNullOrBlank() -> Column {
            TvText("手机遥控没起来", t.title, TvC.fg, weight = TvW.semi)
            TvText(s.str("error") ?: "", t.meta, TvC.fg3, maxLines = 4)
        }
        s.str("url").isNullOrBlank() -> Column {
            TvText("电视没拿到局域网地址。服务在 ${s.long("port") ?: 0} 端口监听,可以在手机浏览器访问 http://电视的IP:${s.long("port") ?: 0}",
                t.body, TvC.fg2, maxLines = 4)
            s.str("ip_error")?.takeIf { it.isNotBlank() }?.let { TvText(it, t.meta, TvC.fg3, maxLines = 2) }
        }
        // 设置页里那一版:码在左、字在右,开在开关行下面(§7.13)
        horizontal -> Row(Modifier.padding(start = TvSp.x16, top = TvSp.x8), verticalAlignment = Alignment.CenterVertically) {
            QrImage(s.str("url") ?: "", qr)
            Spacer(Modifier.width(TvSp.x16))
            Column {
                TvText("用手机扫码", t.body, TvC.fg, weight = TvW.semi)
                TvText(s.str("url") ?: "", t.meta, TvC.fg3, mono = true)
                Spacer(Modifier.height(TvSp.x6))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val on = s.bool("connected")
                    StatusDot(if (on) TvC.acc else TvC.ok)
                    Spacer(Modifier.width(TvSp.x6))
                    TvText(if (on) "已连接 · 正在接收" else "服务在运行 · 关掉再打开会换一个新码", t.meta, TvC.fg2)
                }
            }
        }
        else -> Column {
            QrImage(s.str("url") ?: "", qr)
            Spacer(Modifier.height(TvSp.x16))
            TvText("用手机扫码添加", t.title, TvC.fg, weight = TvW.semi)
            Spacer(Modifier.height(TvSp.x4))
            TvText("手机和电视连同一个 Wi-Fi。扫码后在手机上填好提交,电视这边会自动进去 —— 之后这个网页还能当遥控器用。",
                t.body, TvC.fg2, maxLines = 3)
            Spacer(Modifier.height(TvSp.x6))
            // 地址明文:扫码器识别不了时可以手敲
            TvText(s.str("url") ?: "", t.meta, TvC.fg3, mono = true)
            Spacer(Modifier.height(TvSp.x8))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val on = s.bool("connected")
                StatusDot(if (on) TvC.acc else TvC.ok)
                Spacer(Modifier.width(TvSp.x6))
                TvText(if (on) "已连接 · 正在接收" else "等待手机连接…", t.meta, if (on) TvC.acc else TvC.ok)
            }
        }
    }
}
