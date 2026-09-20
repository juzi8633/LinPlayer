using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.Json;
using System.Threading.Tasks;
using Avalonia;
using Avalonia.Controls;
using LinPlayer.Core;
using LinPlayer.Desktop.Core;

namespace LinPlayer.Desktop.Views;

/// <summary>
/// 桌面壳实现了哪些锚点(SPEC 20.3)。
///
/// <para>锚点名是公开契约,改名算破坏性变更 —— 所以集中登记在这里一处,
/// 而不是散在各页的字符串字面量里:门禁(SPEC 19.5)要拿它和 SPEC 的表对账,
/// 散着的话对不出「少实现了哪个」。</para>
/// </summary>
internal static class Anchors
{
    /// <summary>详情页。顺序照 SPEC 20.3 的表,便于人眼对账。</summary>
    public static readonly string[] Detail =
    [
        "detail.header", "detail.title", "detail.actions", "detail.overview",
        "detail.ratings", "detail.episodes", "detail.cast", "detail.similar", "detail.footer",
    ];

    /// <summary>设置页:SPEC 写作 <c>settings.playback.*</c>,即按前缀落到桌面的大节。</summary>
    public static readonly (string Pattern, string Group)[] SettingsGroups =
    [
        ("settings.appearance.*", "常规"),
        ("settings.playback.*", "播放"),
        ("settings.danmaku.*", "弹幕与字幕"),
        ("settings.subtitle.*", "弹幕与字幕"),
    ];

    /// <summary>凭据相关设置页不开放(D407):这些锚点上的分节一律不画。</summary>
    public static readonly string[] Denied = ["settings.account", "settings.network"];

    public static readonly string[] All = [.. Detail, .. SettingsGroups.Select(g => g.Pattern)];
}

/// <summary>
/// 一次 <c>plugin.anchors</c> 的结果,按锚点分好组。
///
/// <para>进页时拉一次就够:锚点表只随装/停用插件变,而那两件事都要重启。</para>
/// </summary>
internal sealed class PluginAnchorSet
{
    public static readonly PluginAnchorSet Empty = new(null, []);

    private readonly CoreClient? _core;
    private readonly Dictionary<string, List<JsonElement>> _by;

    private PluginAnchorSet(CoreClient? core, Dictionary<string, List<JsonElement>> by)
    {
        _core = core;
        _by = by;
    }

    /// <summary>拉不到就返回空集 —— 插件是增量,拉不到只该少几块,不该让官方页打不开。</summary>
    public static async Task<PluginAnchorSet> Load(CoreClient core)
    {
        try
        {
            var by = new Dictionary<string, List<JsonElement>>();
            foreach (var b in Mi.ArrOf(await core.PluginAnchors(new { })))
            {
                var a = Mi.Str(b, "anchor");
                if (a.Length == 0) continue;
                (by.TryGetValue(a, out var l) ? l : by[a] = []).Add(b);
            }
            return new PluginAnchorSet(core, by);
        }
        catch (Exception e)
        {
            Log.W("锚点", "拉插件锚点失败,这一页只画官方内容:" + e.Message);
            return Empty;
        }
    }

    /// <summary>
    /// 官方那一块外面包上插件块。返回 null = 这一块整个不画。
    ///
    /// <para><paramref name="official"/> 给 null 表示这是纯注入位(页尾之类),
    /// 官方本来就没内容,只有插件来了才长出东西。</para>
    /// </summary>
    public Control? Wrap(string anchor, Control? official, object? props)
    {
        // 清单和调用处对不上就只有这一行会说话 —— 门禁查的是清单,查不出「代码里写错了一个字」
        if (!Anchors.All.Contains(anchor)) Log.W("锚点", $"{anchor} 不在 Anchors 清单里,插件按 SPEC 20.3 的名字是找不到它的");
        if (_core is null || !_by.TryGetValue(anchor, out var list)) return official;

        var center = official;
        // 接管位:同一锚点只生效一个(SPEC 6.1 D159),取第一个 —— 安装顺序就是优先顺序
        if (list.FirstOrDefault(x => Mi.Str(x, "mode") is "replace" or "hide") is { ValueKind: JsonValueKind.Object } take)
        {
            var mode = Mi.Str(take, "mode");
            if (mode == "hide") return null;
            center = Surface(take, props);
        }

        var before = list.Where(x => Mi.Str(x, "mode") == "before").Select(x => Surface(x, props)).OfType<Control>().ToList();
        var after = list.Where(x => Mi.Str(x, "mode") == "after").Select(x => Surface(x, props)).OfType<Control>().ToList();
        if (before.Count == 0 && after.Count == 0) return center;

        var box = new StackPanel { Spacing = 14 };
        foreach (var c in before) box.Children.Add(c);
        if (center is not null) box.Children.Add(center);
        foreach (var c in after) box.Children.Add(c);
        return box;
    }

    /// <summary>
    /// 挂一块插件 UI。构造期抛了只丢这一块。
    ///
    /// <para>surface 自己有错误边界,但那是挂上之后的事 —— 构造这一步抛出来的话,
    /// 异常会一路冒到官方页的渲染里,表现是「装了个插件,详情页整页打不开」。</para>
    /// </summary>
    private Control? Surface(JsonElement b, object? props)
    {
        var plugin = Mi.Str(b, "plugin_id");
        var block = Mi.Str(b, "block");
        if (plugin.Length == 0 || block.Length == 0) return null;
        try { return new PluginSurface(_core!, plugin, block, "block", props); }
        catch (Exception e)
        {
            Log.W("锚点", $"{plugin} 的 {block} 挂不上,这一块跳过:{e.Message}");
            return null;
        }
    }
}

/// <summary>
/// 官方设置页里的插件分节(SPEC 6.2,D286 D289 D407)。
/// </summary>
internal static class PluginSettingsSections
{
    /// <summary>把分节挂到对应大节末尾。<paramref name="groups"/> 的键是桌面的大节名。</summary>
    public static void Fill(CoreClient core, JsonElement list, Dictionary<string, StackPanel> groups)
    {
        foreach (var s in Mi.ArrOf(list))
        {
            var anchor = Mi.Str(s, "anchor");
            var who = Mi.Str(s, "name") is { Length: > 0 } n ? n : Mi.Str(s, "plugin_id");
            if (Anchors.Denied.Any(d => anchor == d || anchor.StartsWith(d + ".", StringComparison.Ordinal)))
            {
                // D407:凭据页不开放。挡了要留一句,否则插件作者只看到「我的分节没出现」
                Log.W("设置", $"{who} 的分节挂在凭据页锚点 {anchor} 上,不画(D407)");
                continue;
            }
            var group = Anchors.SettingsGroups
                .FirstOrDefault(g => anchor == g.Pattern[..^2] || anchor.StartsWith(g.Pattern[..^1], StringComparison.Ordinal)).Group;
            if (group is null || !groups.TryGetValue(group, out var host))
            {
                Log.W("设置", $"{who} 的分节锚点 {anchor} 桌面没有这一节,不画");
                continue;
            }
            if (Card(core, s, who) is { } card) host.Children.Add(card);
        }
    }

    private static Control? Card(CoreClient core, JsonElement s, string who)
    {
        var body = Body(core, s);
        if (body is null) return null;
        var title = Mi.Str(s, "title") is { Length: > 0 } t ? t : who;
        return new Border
        {
            Classes = { "card" }, Padding = new Thickness(18),
            Child = new StackPanel
            {
                Spacing = 10,
                Children =
                {
                    new TextBlock { Text = title, Classes = { "h2" } },
                    // SPEC 6.2:标题标明来自哪个插件 —— 不写的话用户会当成官方设置去找出处
                    new TextBlock { Text = "来自插件 " + who, FontSize = 12, Foreground = Tok.Of("Ink3") },
                    body,
                },
            },
        };
    }

    /// <summary>声明式设置项和自画区块二选一,都没有就整节不画。</summary>
    private static Control? Body(CoreClient core, JsonElement s)
    {
        var id = Mi.Str(s, "plugin_id");
        var items = Mi.Arr(s, "settings");
        if (items.Count > 0)
        {
            var form = new StackPanel { Spacing = 10 };
            _ = FillRows(core, id, items, form);
            return form;
        }
        var block = Mi.Str(s, "block");
        if (block.Length == 0 || id.Length == 0) return null;
        try { return new PluginSurface(core, id, block, "block"); }
        catch (Exception e)
        {
            Log.W("设置", $"{id} 的分节区块 {block} 挂不上:{e.Message}");
            return null;
        }
    }

    /// <summary>
    /// 当前值跟着 <c>plugin.detail</c> 一起来(它的 <c>values</c>),没有单独的取值命令。
    ///
    /// <para>异步补:设置页那五个大节不该为了一个插件分节一起晚出来。</para>
    /// </summary>
    private static async Task FillRows(CoreClient core, string id, List<JsonElement> items, StackPanel form)
    {
        JsonElement vals = default;
        try { vals = (await core.PluginDetail(new { id })).TryGetProperty("values", out var v) ? v : default; }
        catch (Exception e) { Log.W("设置", $"{id} 的当前值没读到,先画默认值:{e.Message}"); }
        Avalonia.Threading.Dispatcher.UIThread.Post(() =>
        {
            foreach (var it in items)
                if (PluginDetailPage.SettingRow(core, id, it, vals) is { } row) form.Children.Add(row);
        });
    }
}

/// <summary>播放页上的一块插件面儿:覆盖层或侧栏页(<c>plugin.playerSurfaces</c> 的一行)。</summary>
internal sealed record PlayerSurfaceInfo(string Plugin, string Target, string Title, string Icon, bool Interactive);

/// <summary>
/// 播放页的两个插件挂载点(SPEC 9.5,D65 D279 D300)。
///
/// <para>这张表在核心层一处算好(启用中的插件 + manifest 的 playerOverlays / playerPanels),
/// 壳只管挂 —— 自己扫 manifest 的话「停用了插件覆盖层还在」这种错只有用户会发现。</para>
/// </summary>
internal static class PlayerSurfaces
{
    /// <summary><paramref name="kind"/> 传 <c>overlay</c> 或 <c>panel</c>。拉不到返回空表:插件是增量,播放页照放。</summary>
    public static async Task<List<PlayerSurfaceInfo>> Load(CoreClient core, string kind)
    {
        try
        {
            return [.. Mi.ArrOf(await core.PluginPlayerSurfaces(new { kind }))
                .Select(x => new PlayerSurfaceInfo(
                    Mi.Str(x, "plugin_id"), Mi.Str(x, "id"), Mi.Str(x, "title"), Mi.Str(x, "icon"),
                    x.TryGetProperty("interactive", out var i) && i.ValueKind == JsonValueKind.True))
                .Where(s => s.Plugin.Length > 0 && s.Target.Length > 0)];
        }
        catch (Exception e)
        {
            Log.W("播放页", $"插件{(kind == "panel" ? "侧栏页" : "覆盖层")}表拉不到,这一场不挂:{e.Message}");
            return [];
        }
    }

    /// <summary>
    /// 一层覆盖层。<b>点击穿透是默认</b>:没声明 <c>interactive</c> 的层既不吃鼠标也不进 Tab 焦点圈,
    /// 否则双击全屏、拖进度条会被一层看不见的东西吞掉,而用户只会觉得「播放器坏了」。
    /// </summary>
    public static Control? Overlay(CoreClient core, PlayerSurfaceInfo s)
    {
        try
        {
            var v = new PluginSurface(core, s.Plugin, s.Target, "overlay");
            v.IsHitTestVisible = s.Interactive;
            if (!s.Interactive)
                Avalonia.Input.KeyboardNavigation.SetTabNavigation(v, Avalonia.Input.KeyboardNavigationMode.None);
            return v;
        }
        catch (Exception e)
        {
            Log.W("播放页", $"{s.Plugin} 的覆盖层 {s.Target} 挂不上,这一层跳过:{e.Message}");
            return null;
        }
    }
}
