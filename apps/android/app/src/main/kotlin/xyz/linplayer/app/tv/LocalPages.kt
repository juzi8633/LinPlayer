package xyz.linplayer.app.tv

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Icon
import kotlinx.coroutines.launch
import xyz.linplayer.app.MainActivity
import xyz.linplayer.app.core.CoreException
import xyz.linplayer.app.data.Block
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.ToastKind
import xyz.linplayer.app.data.arr
import xyz.linplayer.app.data.bool
import xyz.linplayer.app.data.long
import xyz.linplayer.app.data.obj
import xyz.linplayer.app.data.str
import xyz.linplayer.app.tv.kit.FullState
import xyz.linplayer.app.tv.kit.PageHead
import xyz.linplayer.app.tv.kit.PanelGroup
import xyz.linplayer.app.tv.kit.PanelItem
import xyz.linplayer.app.tv.kit.Skel
import xyz.linplayer.app.tv.kit.TvButton
import xyz.linplayer.app.tv.kit.TvC
import xyz.linplayer.app.tv.kit.TvSp
import xyz.linplayer.app.tv.kit.contentArea
import xyz.linplayer.app.tv.kit.tvType
import xyz.linplayer.app.ui.pages.args
import xyz.linplayer.app.ui.pages.fmtSize
import xyz.linplayer.app.ui.pages.folderName
import xyz.linplayer.app.ui.theme.LpIcons

private fun storagePermission() =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE

private fun granted(ctx: Context) = ContextCompat.checkSelfPermission(ctx, storagePermission()) == PackageManager.PERMISSION_GRANTED

/** 存储卷:「内部存储」+ 每个 U 盘 / SD 卡。取不到路径的卷不列(列出来点了只会得到一句打不开)。 */
private data class Vol(val label: String, val path: String, val meta: String)

private fun volumes(ctx: Context): List<Vol> {
    val sm = ctx.getSystemService(Context.STORAGE_SERVICE) as StorageManager
    return sm.storageVolumes.mapNotNull { v ->
        val dir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) v.directory
            else runCatching { v.javaClass.getMethod("getPathFile").invoke(v) as java.io.File }.getOrNull()
        dir ?: return@mapNotNull null
        val stat = runCatching { StatFs(dir.path) }.getOrNull()
        val meta = stat?.let { "可用 ${fmtSize(it.availableBytes) ?: "0 MB"} / ${fmtSize(it.totalBytes) ?: "?"}" } ?: ""
        Vol(if (v.isPrimary) "内部存储" else v.getDescription(ctx), dir.path, meta)
    }
}

/**
 * 挑文件夹(UI_TV.md §7.14)。第一层 = 存储卷列表;**这一层归 UI**:Android TV 上大多数设备没有系统文件夹选择器。
 * ★ 权限在**进这一页的那一刻**才要;拒绝 → FullState + 去系统设置。
 * ★ 选定 → `source.login {kind:"local"}` → 回服务器页,新卡片出现。
 */
@Composable
fun LocalPickerPage(onDone: (() -> Unit)? = null) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val t = tvType
    val nav = if (onDone == null) LocalNav.current else null
    var ok by remember { mutableStateOf(granted(ctx)) }
    var asked by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) ok = granted(ctx) }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(Unit) { if (!ok) { (ctx as? MainActivity)?.askStoragePermission(); asked = true } }
    var stack by remember { mutableStateOf<List<String>>(emptyList()) }
    if (stack.isNotEmpty()) BackHandler { stack = stack.dropLast(1) }

    Column(Modifier.contentArea()) {
        PageHead("选择文件夹", sub = "选好之后,这个文件夹会作为一个源出现在服务器页")
        Spacer(Modifier.height(TvSp.x12))
        when {
            !ok && asked -> FullState(LpIcons.folder, "没有读取视频的权限", sub = "允许之后才能列出存储里的文件夹",
                buttons = listOf("去系统设置"), onButton = {
                    runCatching {
                        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                })
            stack.isEmpty() -> {
                val vols = remember { volumes(ctx) }
                if (vols.isEmpty()) FullState(LpIcons.folder, "没找到存储卷", buttons = listOf("返回"), onButton = { onDone?.invoke() ?: nav?.pop() })
                LazyColumn(verticalArrangement = Arrangement.spacedBy(TvSp.x6)) {
                    itemsIndexed(vols) { i, v ->
                        LocalRow(true, v.label, v.meta, Modifier.memo("pick.vol.$i", initial = i == 0)) { stack = listOf(v.path) }
                    }
                }
            }
            else -> {
                val dir = stack.last()
                val subs = remember(dir) {
                    java.io.File(dir).listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.sortedBy { it.name.lowercase() }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TvButton("选择这个文件夹", LpIcons.check, primary = true, modifier = Modifier.memo("pick.choose.$dir", initial = true), onClick = {
                        scope.launch {
                            runCatching { app.call("source.login", args("kind" to "local", "base_url" to dir)) }
                                .onSuccess {
                                    app.toast("已添加「${folderName(dir)}」", ToastKind.Ok)
                                    app.refreshSession()
                                    if (onDone != null) onDone() else nav?.rail(TvRoute.Servers)
                                }.onFailure { app.report(it) }
                        }
                    })
                    Spacer(Modifier.width(TvSp.x16))
                    RowText(dir, t.meta, .6f, mono = true)
                }
                Spacer(Modifier.height(TvSp.x12))
                when {
                    subs == null -> FullState(LpIcons.folder, "找不到这个文件夹", sub = "U 盘可能被拔掉了", buttons = listOf("返回上一层"),
                        onButton = { stack = stack.dropLast(1) })
                    subs.isEmpty() -> RowText("这里没有子文件夹", t.body, .6f)
                    else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(TvSp.x6)) {
                        items(subs, key = { it.path }) { f -> LocalRow(true, f.name, "", Modifier.memo("pick.${f.path}")) { stack = stack + f.path } }
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalRow(dir: Boolean, name: String, meta: String, modifier: Modifier, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val t = tvType
    TvListRow(modifier, height = 56.dp, onClick = onClick, onLongClick = onLongClick) {
        Icon(if (dir) LpIcons.folder else LpIcons.file, null, Modifier.size(24.dp))
        Spacer(Modifier.width(TvSp.x12))
        Column(Modifier.weight(1f)) {
            // 长文件名换行,不截断
            RowText(name, t.body, maxLines = 3)
            if (meta.isNotBlank()) RowText(meta, t.meta, .7f)
        }
    }
}

private data class LEntry(val id: String, val name: String, val isDir: Boolean, val size: Long?)

/**
 * 浏览本机文件夹(§7.14)。**只列目录和视频**(`is_video`);子目录入栈,返回键回上一层。
 * ★ 核心层对传回来的路径做了越狱闸:**UI 不许自己拼路径绕过 `source.listDir`**。
 */
@Composable
fun LocalBrowsePage(r: TvRoute.LocalDir?) {
    val app = LocalApp.current
    val nav = LocalNav.current
    val overlay = rememberOverlay()
    val t = tvType
    var rootName by remember { mutableStateOf("本机文件夹") }
    var block by remember { mutableStateOf<Block<List<LEntry>>>(Block.Loading) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(r?.dirId, reload) {
        if (r == null) rootName = runCatching { app.call("source.currentSource") }.getOrNull().obj().str("server_name")?.takeIf { it.isNotBlank() } ?: rootName
        block = Block.Loading
        block = runCatching { app.call("source.listDir", r?.let { args("dir_id" to it.dirId) }) }.fold({ v ->
            Block.Ok(v.arr().mapNotNull { it.obj() }.filter { it.bool("is_dir") || it.bool("is_video") }.mapNotNull { o ->
                LEntry(o.str("id") ?: return@mapNotNull null, o.str("name") ?: "", o.bool("is_dir"), o.long("size"))
            })
        }, { e -> Block.Fail((e as? CoreException)?.code ?: "E_INTERNAL", (e as? CoreException)?.advice ?: (e.message ?: "打不开")) })
    }
    val trail: List<Pair<String?, String>> = r?.trail ?: listOf(null to rootName)
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.contentArea()) {
            // 面包屑每段可聚焦,OK 跳到那一层
            LazyRow(horizontalArrangement = Arrangement.spacedBy(TvSp.x6), verticalAlignment = Alignment.CenterVertically,
                contentPadding = PaddingValues(vertical = TvSp.x4)) {
                itemsIndexed(trail) { i, (_, name) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (i > 0) { Icon(LpIcons.chevR, null, Modifier.size(14.dp), tint = TvC.fg3); Spacer(Modifier.width(TvSp.x6)) }
                        TvButton(name, if (i == 0) LpIcons.folder else null, modifier = Modifier.memo("crumb.$i"), onClick = {
                            repeat(trail.lastIndex - i) { nav.pop() }
                        })
                    }
                }
            }
            Spacer(Modifier.height(TvSp.x12))
            when (val b = block) {
                is Block.Loading -> Column(verticalArrangement = Arrangement.spacedBy(TvSp.x6)) { repeat(5) { Skel(Modifier.fillMaxWidth().height(56.dp)) } }
                is Block.Fail -> FullState(LpIcons.folder, "找不到这个文件夹", sub = "U 盘可能被拔掉了", detail = b.message,
                    buttons = listOf("重试"), onButton = { reload++ })
                is Block.Ok -> if (b.value.isEmpty()) FullState(LpIcons.folder, "这个文件夹是空的", sub = "里面没有影片,也没有子文件夹",
                    buttons = listOf("返回"), onButton = { if (!nav.pop()) nav.rail(TvRoute.Servers) })
                else LazyColumn(verticalArrangement = Arrangement.spacedBy(TvSp.x6), contentPadding = PaddingValues(bottom = TvSp.x24)) {
                    itemsIndexed(b.value, key = { _, e -> e.id }) { i, e ->
                        LocalRow(e.isDir, e.name, if (e.isDir) "" else fmtSize(e.size).orEmpty(), Modifier.memo("local.${e.id}", initial = i == 0),
                            onLongClick = if (e.isDir) null else ({
                                overlay.open {
                                    TvSidePanel(e.name, { overlay.close() }) {
                                        PanelItem("播放", focused = true, onClick = { overlay.close(); nav.push(TvRoute.Player(e.id, e.name, localEntry = true)) })
                                        PanelItem("从头播放", onClick = { overlay.close(); nav.push(TvRoute.Player(e.id, e.name, localEntry = true, resumeAt = 0.0)) })
                                    }
                                }
                            })) {
                            if (e.isDir) nav.push(TvRoute.LocalDir(e.id, trail + (e.id to e.name)))
                            else nav.push(TvRoute.Player(e.id, e.name, localEntry = true))
                        }
                    }
                    item("end") { RowText("已经到底了 · 共 ${b.value.size} 项", t.meta, .5f) }
                }
            }
        }
        OverlayHost(overlay)
    }
}
