package xyz.linplayer.app.ui.plugin

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import xyz.linplayer.app.data.LocalApp
import xyz.linplayer.app.data.arr
import xyz.linplayer.app.data.obj
import xyz.linplayer.app.data.str
import xyz.linplayer.app.ui.components.H2
import xyz.linplayer.app.ui.pages.SettingRow
import xyz.linplayer.app.ui.pages.args
import xyz.linplayer.app.ui.theme.Sp

/** 官方设置页里的一节。字段名照 `core/plugin/anchors.go` 的 `SettingsSectionInfo`。 */
data class PluginSettingsSection(
    val pluginId: String, val name: String, val anchor: String, val title: String,
    val settings: List<JsonObject>, val block: String,
)

/** 标题要标明来自哪个插件(SPEC 6.2 D289)。 */
internal fun PluginSettingsSection.heading(): String =
    (title.ifBlank { "插件设置" }) + " · 来自 " + name.ifBlank { pluginId }

/**
 * 某一页上的插件分节 + 它们的当前值。
 *
 * ☠ 凭据相关设置页不开放(D407):`settings.account` / `settings.network` 上的分节
 * 一律丢掉并留日志 —— 静悄悄地不画的话,插件作者会以为自己写错了配置。
 */
class SectionsOf(
    val sections: List<PluginSettingsSection> = emptyList(),
    val values: Map<String, JsonObject> = emptyMap(),
)

/**
 * 拉一次 `plugin.settingsSections`,挑出这一页的。
 *
 * 匹配到**页**这一级:`settings.playback.general` 这种带子节名的归到 `settings.playback`
 * 页末尾 —— 官方各面板没有把自己内部的节登记出来,按子节插会插到一个不存在的位置上。
 */
@Composable
fun rememberSettingsSections(page: String): SectionsOf {
    val app = LocalApp.current
    var out by remember(page) { mutableStateOf(SectionsOf()) }
    LaunchedEffect(page) {
        val all = runCatching { app.call("plugin.settingsSections") }.getOrNull().arr()
            .mapNotNull { e ->
                val o = e.obj() ?: return@mapNotNull null
                PluginSettingsSection(
                    o.str("plugin_id") ?: return@mapNotNull null, o.str("name") ?: "",
                    o.str("anchor") ?: return@mapNotNull null, o.str("title") ?: "",
                    o["settings"].arr().mapNotNull { it.obj() }, o.str("block") ?: "",
                )
            }
        all.filter { denied(it.anchor) }.groupBy { it.anchor }
            .forEach { (a, rows) -> logDenied(a, rows.size) }
        val mine = all.filter { !denied(it.anchor) && (it.anchor == page || it.anchor.startsWith("$page.")) }
        // 值只有 plugin.detail 给(核心层没有单独的 getSettings),每个插件问一次
        val vals = mine.map { it.pluginId }.distinct().mapNotNull { id ->
            val v = runCatching { app.call("plugin.detail", args("id" to id)) }.getOrNull()
                .obj()?.get("values").obj() ?: return@mapNotNull null
            id to v
        }.toMap()
        out = SectionsOf(mine, vals)
    }
    return out
}

/** 这一节里某个 key 的当前值;插件没存过就用声明里的默认值。 */
internal fun SectionsOf.valueOf(s: PluginSettingsSection, item: JsonObject): JsonElement? =
    values[s.pluginId]?.get(item.str("key") ?: "") ?: item["default"]

/** 一节自画区块。设置页的初始焦点归官方行,所以照样不许认领(见 AnchorSurface 那条)。 */
@Composable
internal fun SectionBlock(s: PluginSettingsSection, modifier: Modifier = Modifier) {
    PluginSurface(
        s.pluginId, s.block, "block", modifier = modifier,
        claimInitialFocus = false, focusNs = "${s.pluginId}/${s.block}.",
    )
}

/** 手机端:声明式设置项复用插件详情页那一份 [xyz.linplayer.app.ui.pages.SettingRow]。 */
@Composable
fun PhonePluginSections(page: String) {
    val app = LocalApp.current
    val data = rememberSettingsSections(page)
    data.sections.forEach { s ->
        Column {
            H2(s.heading(), Modifier.padding(Sp.x16))
            if (s.block.isNotEmpty()) SectionBlock(s, Modifier.padding(horizontal = Sp.x16))
            s.settings.forEach { item -> SettingRow(app, s.pluginId, item, data.valueOf(s, item)) }
        }
    }
}
