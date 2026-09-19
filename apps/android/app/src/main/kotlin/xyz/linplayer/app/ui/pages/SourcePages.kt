package xyz.linplayer.app.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import xyz.linplayer.app.data.Block
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.ToastKind
import xyz.linplayer.app.data.arr
import xyz.linplayer.app.data.bool
import xyz.linplayer.app.data.long
import xyz.linplayer.app.data.obj
import xyz.linplayer.app.data.str
import xyz.linplayer.app.ui.Route
import xyz.linplayer.app.ui.components.Body
import xyz.linplayer.app.ui.components.BtnKind
import xyz.linplayer.app.ui.components.Dim3
import xyz.linplayer.app.ui.components.EmptyState
import xyz.linplayer.app.ui.components.ErrorState
import xyz.linplayer.app.ui.components.Hairline
import xyz.linplayer.app.ui.components.LpButton
import xyz.linplayer.app.ui.components.LpDialog
import xyz.linplayer.app.ui.components.LpField
import xyz.linplayer.app.ui.components.LpIconButton
import xyz.linplayer.app.ui.components.LpScaffold
import xyz.linplayer.app.ui.components.OptRow
import xyz.linplayer.app.ui.components.Skeleton
import xyz.linplayer.app.ui.components.pressable
import xyz.linplayer.app.ui.components.rememberScrolled
import xyz.linplayer.app.ui.theme.Lp
import xyz.linplayer.app.ui.theme.LpIcons
import xyz.linplayer.app.ui.theme.Sp

/**
 * 文件浏览(U1.10)。
 *
 * ★ 列表行不是网格 —— **文件名比缩略图重要**。
 * ★ `source.listDir` 是**流式**的,边列边出。
 * ★ 起播必须走宿主统一的起播入口(导航到播放页),**本页不许自己 `player.play`**。
 *   教训:曾经绕开统一入口自己起播,结果「有声音、没画面、还关不掉」。
 *
 * ☠☠ **这一页没有「根目录」这个概念**【用户定 2026-09-06】。
 *   上一版一进来就往 `source.listDir` 发一条空 `dir_id`,面包屑写死「根目录」——
 *   而当前活跃账号是 Emby 时核心层根本没有文件源,回的是
 *   「当前没有已登录的文件源」,界面上就成了那句莫名其妙的「根目录浏览失败」。
 *   用户既没选过文件夹,也没被问过要选哪个。
 *   现在的口径:**先让用户添加一个本机文件夹**,添加完那个文件夹**就是**起点,
 *   面包屑第一格写它的名字。
 */
@Composable
fun BrowsePage(nav: NavController) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    val ctx = LocalContext.current

    data class Entry(val id: String, val name: String, val isDir: Boolean,
                     val size: Long?)

    /** 当前生效的文件源(null = 还没添加,或者当前活跃的是 Emby)。 */
    var source by remember { mutableStateOf<JsonObject?>(null) }
    /** 账号表里已有的本机文件夹。有但没生效时给一颗「切过去」。 */
    var localSources by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var stack by remember { mutableStateOf<List<Pair<String?, String>>>(emptyList()) }
    var entries by remember { mutableStateOf<List<Entry>>(emptyList()) }
    var state by remember { mutableStateOf<Block<Unit>>(Block.Loading) }
    var adding by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }

    // 谁是当前文件源 + 账号表里还有哪些本机文件夹
    LaunchedEffect(reload) {
        source = runCatching { app.call("source.currentSource") }.getOrNull().obj()
        localSources = runCatching { app.call("account.listAccounts") }.getOrNull().arr()
            .mapNotNull { e ->
                val o = e.obj() ?: return@mapNotNull null
                // 字段名是 source_kind。原来还并了一句 `o.str("kind")`,
                // 而核心层从来不发 kind —— 那半句恒真,是个假的保险
                if (o.str("source_kind") != "local") return@mapNotNull null
                val id = o.str("server") ?: return@mapNotNull null
                id to (o.str("name")?.takeIf { it.isNotBlank() } ?: folderName(id))
            }
        // 起点就是那个文件夹本身,不叫「根目录」
        val cur = source
        stack = if (cur == null) emptyList()
        else listOf(null to (cur.str("server_name")?.takeIf { it.isNotBlank() }
            ?: folderName(cur.str("server_id") ?: "")))
    }

    LaunchedEffect(stack, reload) {
        if (stack.isEmpty()) { state = Block.Ok(Unit); return@LaunchedEffect }
        state = Block.Loading; entries = emptyList()
        val dir = stack.last().first
        /* ☠ 这里原来还挂着一个 `onPartial`(「边列边出」),而**核心层这条命令
           一次 partial 都不发** —— 那段回调从上线起没跑过一次,靠的一直是下面
           这条兜底。留着它只会让人以为大目录是增量出的。真要做增量,
           得先让 core/sourcecmd 用 bus.Partial 发,再把它加回来。 */
        val r = runCatching {
            app.call("source.listDir", dir?.let { args("dir_id" to it) })
        }
        state = r.fold({ v ->
            entries = v.arr().mapNotNull { e ->
                val o = e.obj() ?: return@mapNotNull null
                Entry(o.str("id") ?: return@mapNotNull null, o.str("name") ?: "",
                    o.bool("is_dir"), o.long("size"))
            }
            Block.Ok(Unit)
        }, { e ->
            val ce = e as? xyz.linplayer.app.core.CoreException
            Block.Fail(ce?.code ?: "E_INTERNAL", ce?.advice ?: (e.message ?: "这个文件夹打不开"))
        })
    }

    /** 添加一个本机文件夹:核心层把它当成一个源(`kind=local`),之后走和别的源一样的链路。 */
    val addFolder: (String) -> Unit = { raw ->
        val path = raw.trim()
        scope.launch {
            runCatching {
                app.call("source.login", args("kind" to "local", "base_url" to path))
            }.onSuccess {
                adding = false
                app.refreshSession()
                reload++
                app.toast("已添加「" + folderName(path) + "」", ToastKind.Ok)
            }.onFailure { app.report(it) }
        }
    }

    LpScaffold(
        stack.lastOrNull()?.second ?: "本机文件夹", subtitle = "文件浏览",
        onBack = { if (stack.size > 1) stack = stack.dropLast(1) else nav.popBackStack() },
        scrolled = rememberScrolled(list),
        actions = {
            if (stack.isNotEmpty()) LpIconButton(LpIcons.plus, "添加文件夹") { adding = true }
        },
    ) { pad ->
        val st = state
        when {
            // 还没有文件源:这不是错误,是「还没开始」
            stack.isEmpty() -> Column(
                Modifier.fillMaxSize().padding(pad).padding(horizontal = Sp.x16),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                EmptyState(
                    "还没有添加本机文件夹",
                    "选一个装着影片的文件夹,它会像一台服务器那样出现在列表里,之后就能直接浏览和播放。",
                    LpIcons.folder, "添加文件夹", onAction = { adding = true },
                )
                /* 已经添加过、只是当前活跃的是 Emby:给一颗切过去的键。
                   不给的话用户得自己绕回服务器页,而他刚点的就是「文件浏览」。 */
                if (localSources.isNotEmpty()) {
                    Spacer(Modifier.height(Sp.x20))
                    Dim3("已添加的文件夹(点一下切过去)")
                    Spacer(Modifier.height(Sp.x6))
                    localSources.forEach { entry ->
                        OptRow(entry.second, sub = entry.first, onClick = {
                            scope.launch {
                                runCatching {
                                    app.call("account.setActiveServer", args("server_id" to entry.first))
                                }.onSuccess { app.refreshSession(); reload++ }
                                    .onFailure { app.report(it) }
                            }
                        })
                    }
                }
            }

            st is Block.Loading -> Column(Modifier.padding(pad).padding(Sp.x16)) {
                repeat(6) { Skeleton(Modifier.fillMaxWidth().height(48.dp)); Spacer(Modifier.height(Sp.x8)) }
            }

            st is Block.Fail -> if (!st.isSilent) ErrorState(st.message, { reload++ })

            entries.isEmpty() -> EmptyState(
                // 空目录就说**空目录**,不要说「加载失败」
                "这个文件夹是空的", "里面没有影片,也没有子文件夹。", LpIcons.folder,
            )

            else -> LazyColumn(Modifier.fillMaxSize(), list, contentPadding = pad) {
                items(entries, key = { it.id }) { e ->
                    Row(Modifier.fillMaxWidth().pressable({
                        if (e.isDir) stack = stack + (e.id to e.name)
                        else nav.navigate(Route.Player(e.id, e.name))
                    }).padding(horizontal = Sp.x16, vertical = Sp.x12),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (e.isDir) LpIcons.folder else LpIcons.file, null,
                            Modifier.size(20.dp), tint = Lp.colors.fg2)
                        Spacer(Modifier.padding(horizontal = Sp.x6))
                        Column(Modifier.weight(1f)) {
                            Body(e.name, maxLines = 2)
                            // 修改时间**核心层不发**(source.Entry 里没有这个字段),
                            // 原来那一格是恒空的 —— 摆着不生效的东西比没有更糟
                            e.size?.let {
                                Dim3("%.1f MB".format(it / 1024.0 / 1024.0),
                                    Modifier.padding(top = Sp.x2))
                            }
                        }
                    }
                    Hairline(Modifier.padding(start = Sp.x48))
                }
            }
        }
    }

    if (adding) AddFolderDialog(ctx, onDismiss = { adding = false }, onPick = addFolder)
}

/** 路径的最后一段。整条路径当标题太长,而用户认的就是文件夹名。 */
internal fun folderName(path: String): String =
    path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')
        .ifBlank { path.ifBlank { "本机文件夹" } }

/**
 * 添加本机文件夹。
 *
 * ☠ **要的是真实路径,不是 SAF 的 `content://`** —— mpv 的 `protocol-list` 里没有
 *   content 这个 scheme,给它等于给一个它不认识的地址。所以这里是路径输入框
 *   加几个常用位置的快捷键,不是系统文件夹选择器。
 * ★ 读权限在**这一刻**要,不在冷启动时要:没授权的表现是「这个文件夹是空的」,
 *   而那句话会让人以为路径填错了。
 */
@Composable
private fun AddFolderDialog(
    ctx: android.content.Context,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    var path by remember { mutableStateOf("") }
    val activity = ctx as? xyz.linplayer.app.MainActivity
    LaunchedEffect(Unit) { activity?.askStoragePermission() }

    LpDialog(onDismiss, "添加本机文件夹") {
        Dim3("填一个装着影片的文件夹路径。添加之后它会像一台服务器那样出现在列表里。")
        Spacer(Modifier.height(Sp.x12))
        LpField(path, { path = it }, "/storage/emulated/0/Movies", label = "文件夹路径")
        val common = remember { commonFolders() }
        if (common.isNotEmpty()) {
            Spacer(Modifier.height(Sp.x10))
            Dim3("常用位置")
            Spacer(Modifier.height(Sp.x6))
            common.forEach { p -> OptRow(p, onClick = { path = p }, selected = path == p) }
        }
        Spacer(Modifier.height(Sp.x16))
        Row(horizontalArrangement = Arrangement.spacedBy(Sp.x10)) {
            LpButton("取消", onDismiss, kind = BtnKind.Secondary)
            LpButton("添加", { if (path.isNotBlank()) onPick(path) })
        }
    }
}

/** 常用位置。**只列真的存在的** —— 列一个不存在的路径,点了只会得到一句「打不开」。 */
private fun commonFolders(): List<String> {
    val ext = android.os.Environment.getExternalStorageDirectory().absolutePath
    return listOf(ext, ext + "/Movies", ext + "/Download", ext + "/DCIM", ext + "/Videos")
        .distinct().filter { java.io.File(it).isDirectory }
}
