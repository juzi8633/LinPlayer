using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Text.Json;
using System.Threading.Tasks;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.ApplicationLifetimes;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Platform.Storage;
using Avalonia.Threading;
using LinPlayer.Core;
using LinPlayer.Desktop.Core;

namespace LinPlayer.Desktop.Views;

/// <summary>
/// 插件页(SPEC 14.4):已安装 / 市场 / 接管位 / 仓库。这一页永远官方,不可接管(D29)。
/// 启停、装卸都是重启生效:离开本页时有改动就问一次要不要立即重启(D170)。
/// </summary>
public sealed class PluginPage : PageBase
{
    private static readonly string[] Tabs = ["已安装", "市场", "接管位", "仓库"];
    private readonly CoreClient _core;
    private readonly ContentControl _body = new();
    private readonly WrapPanel _tabs = new() { ItemSpacing = 6, LineSpacing = 6 };
    private readonly StackPanel _banner = new() { Spacing = 10 };
    private int _tab;
    private string _query = "";

    public PluginPage(CoreClient core, int tab = 0)
    {
        _core = core;
        for (var i = 0; i < Tabs.Length; i++)
        {
            var n = i;
            var b = new Button { Classes = { "chip" }, Content = Tabs[i] };
            b.Click += (_, _) => Switch(n);
            _tabs.Children.Add(b);
        }
        Content = Scrolled(new StackPanel
        {
            Spacing = 14, MaxWidth = 920, HorizontalAlignment = HorizontalAlignment.Left,
            Children = { H1("插件"), _banner, _tabs, _body },
        });
        DetachedFromVisualTree += (_, _) => _ = AskRestart(core);
        Switch(tab);
    }

    private void Switch(int tab)
    {
        _tab = tab;
        for (var i = 0; i < _tabs.Children.Count; i++) _tabs.Children[i].Classes.Set("on", i == tab);
        _body.Content = Skeleton.Detail();
        _ = Run(tab switch { 0 => Installed, 1 => Market, 2 => Takeovers, _ => Repos });
    }

    private async Task Run(Func<Task<Control>> build)
    {
        var tab = _tab;
        Control c;
        try { c = await build(); }
        catch (Exception e) { c = Dim(LibraryPage.Advice(e)); }
        if (tab == _tab) _body.Content = c;
    }

    private void Reload() => Switch(_tab);

    // ---------------------------------------------------------------- 已安装

    private async Task<Control> Installed()
    {
        var r = await _core.PluginList(new { with_updates = true });
        _banner.Children.Clear();
        if (Mi.Str(r, "safe_banner") is { Length: > 0 } || B(r, "safe_mode"))
            _banner.Children.Add(Banner("安全模式:上次启动连续崩溃,插件已全部关闭。" +
                (Mi.Str(r, "safe_suspect") is { Length: > 0 } s ? $"最可疑的是 {s}。" : "") + "在下面逐个打开排查。"));
        if (B(r, "pending_restart")) _banner.Children.Add(Banner("有改动要重启后才生效。", "立即重启", Restart));

        var list = new StackPanel { Spacing = 10 };
        var plugins = Mi.Arr(r, "plugins");
        var tools = new WrapPanel { ItemSpacing = 10, LineSpacing = 10 };
        tools.Children.Add(Btn("从文件安装", InstallFromFile, primary: true));
        tools.Children.Add(Btn("加载开发目录", DevLoad));
        var updatable = plugins.Where(p => Mi.Str(p, "update").Length > 0).Select(p => Mi.Str(p, "id")).ToArray();
        if (updatable.Length > 0) tools.Children.Add(Btn($"全部更新({updatable.Length})", () => UpdateAll(updatable)));
        if (B(r, "disabled_all")) tools.Children.Add(Btn("恢复之前的启用状态", async () => { await _core.PluginRestoreAll(new { }); Reload(); }));
        else if (plugins.Any(p => B(p, "want")))
            tools.Children.Add(Btn("一键全部禁用", async () => { await _core.PluginDisableAll(new { }); Reload(); }));
        list.Children.Add(tools);

        if (plugins.Count == 0)
        {
            list.Children.Add(new StackPanel
            {
                Spacing = 10, Margin = new Thickness(0, 26),
                Children = { Dim("还没有安装插件"), Btn("去市场", () => { Switch(1); return Task.CompletedTask; }) },
            });
            return list;
        }
        foreach (var p in plugins) list.Children.Add(InstalledRow(p));
        return list;
    }

    private Control InstalledRow(JsonElement p)
    {
        var id = Mi.Str(p, "id");
        var on = new CheckBox { IsChecked = B(p, "want"), VerticalAlignment = VerticalAlignment.Center };
        ToolTip.SetTip(on, "启用(重启后生效)");
        on.IsCheckedChanged += async (_, _) =>
        {
            try { await _core.PluginSetEnabled(new { id, enabled = on.IsChecked == true }); Reload(); }
            catch (Exception e) { Toast.Error(LibraryPage.Advice(e)); }
        };
        var title = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 6 };
        title.Children.Add(new TextBlock { Text = Mi.Str(p, "name"), FontWeight = FontWeight.SemiBold });
        title.Children.Add(Tag(Mi.Str(p, "version")));
        if (B(p, "official")) title.Children.Add(Tag("官方", "Accent"));
        if (B(p, "dev")) title.Children.Add(Tag("开发版", "Warn"));
        var (st, tone) = Status(p);
        if (st.Length > 0) title.Children.Add(Tag(st, tone));
        var sub = $"{Mi.Str(p, "author")} · {SourceName(Mi.Str(p, "source"))}";
        var detail = Btn("详情", () => { Nav.Push(new PluginDetailPage(_core, id)); return Task.CompletedTask; });
        var grid = new Grid { ColumnDefinitions = new ColumnDefinitions("Auto,*,Auto"), ColumnSpacing = 14 };
        grid.Children.Add(on);
        var mid = new StackPanel { Spacing = 2, Children = { title, Dim(sub) } };
        if (Mi.Str(p, "description") is { Length: > 0 } d) mid.Children.Add(Dim(d));
        Grid.SetColumn(mid, 1);
        grid.Children.Add(mid);
        Grid.SetColumn(detail, 2);
        detail.VerticalAlignment = VerticalAlignment.Center;
        grid.Children.Add(detail);
        return new Border { Classes = { "card" }, Padding = new Thickness(14), Child = grid };
    }

    private static (string, string) Status(JsonElement p) => Mi.Str(p, "status") switch
    {
        "autoDisabled" => ("已自动禁用:" + Mi.Str(p, "autoDisabled"), "Danger"),
        "pendingRestart" => (B(p, "uninstall") ? "重启后卸载" : "待重启", "Warn"),
        "update" => ("可更新到 " + Mi.Str(p, "update"), "Accent"),
        _ => ("", ""),
    };

    private static string SourceName(string src) => src switch
    {
        "local" => "本地文件",
        "dev" => "开发目录",
        "" => "",
        _ => src.StartsWith("http") ? new Uri(src).Host : src,
    };

    private async Task InstallFromFile()
    {
        if (TopLevel.GetTopLevel(this) is not { } top) return;
        var files = await top.StorageProvider.OpenFilePickerAsync(new FilePickerOpenOptions
        {
            Title = "选择插件包", AllowMultiple = false,
            FileTypeFilter = [new FilePickerFileType("LinPlayer 插件") { Patterns = ["*.lpplugin"] }],
        });
        if (files.Count == 0 || files[0].TryGetLocalPath() is not { } path) return;
        try
        {
            var ins = await _core.PluginInspect(new { path });
            if (!await ConfirmInstall(this, ins)) return;
            var m = await _core.PluginInstallFile(new { path });
            Toast.Show($"已安装 {Mi.Str(m, "name")} {Mi.Str(m, "version")},重启后生效");
            Reload();
        }
        catch (Exception e) { Toast.Error("安装失败:" + LibraryPage.Advice(e)); }
    }

    /// <summary>安装确认(SPEC 14.5):贡献点清单 + 非官方来源 + 扩展组件 + 局域网 + 大小。</summary>
    internal static async Task<bool> ConfirmInstall(Visual anchor, JsonElement ins)
    {
        if (Mi.Str(ins, "incompatible") is { Length: > 0 } inc) { await Dialogs.Tell(anchor, "装不了", inc); return false; }
        var body = new StackPanel { Spacing = 6, MaxWidth = 420 };
        body.Children.Add(new TextBlock { Text = $"{Mi.Str(ins, "name")} {Mi.Str(ins, "version")}", FontWeight = FontWeight.SemiBold });
        if (Mi.Str(ins, "installed") is { Length: > 0 } had)
            body.Children.Add(Note(B(ins, "otherSource") ? $"已从别的来源装过 {had},会被替换" : $"覆盖已装的 {had}"));
        if (B(ins, "unofficial")) body.Children.Add(Note("非官方来源:内容由第三方提供,官方不对其负责。", "Warn"));
        var contrib = Mi.Arr(ins, "contributes").Select(x => x.GetString()).ToArray();
        if (contrib.Length > 0) body.Children.Add(Note("它会:" + string.Join("、", contrib)));
        var comps = Mi.Arr(ins, "components").Select(x => x.GetString()).ToArray();
        if (comps.Length > 0) body.Children.Add(Note("需要扩展组件:" + string.Join("、", comps)));
        if (B(ins, "lan")) body.Children.Add(Note("会访问你的局域网设备", "Warn"));
        if (Mi.Num(ins, "size") is > 0 and var sz) body.Children.Add(Note($"大小 {sz / 1024:0} KB"));
        return await Dialogs.Show(anchor, "安装插件", body, "安装", "取消");
    }

    private async Task DevLoad()
    {
        if (TopLevel.GetTopLevel(this) is not { } top) return;
        var dirs = await top.StorageProvider.OpenFolderPickerAsync(new FolderPickerOpenOptions { Title = "选择插件目录(含 manifest.json)" });
        if (dirs.Count == 0 || dirs[0].TryGetLocalPath() is not { } dir) return;
        try
        {
            var m = await _core.PluginDevLoad(new { dir });
            Toast.Show($"已加载开发版 {Mi.Str(m, "name")},改了文件会自动重载");
            Reload();
        }
        catch (Exception e) { Toast.Error("加载失败:" + LibraryPage.Advice(e)); }
    }

    private async Task UpdateAll(string[] ids)
    {
        Toast.Show("正在更新…");
        var r = await _core.PluginUpdateAll(new { ids });
        var failed = r.TryGetProperty("failed", out var f) && f.ValueKind == JsonValueKind.Object
            ? f.EnumerateObject().Select(x => $"{x.Name}:{x.Value.GetString()}").ToArray() : [];
        Reload();
        var msg = failed.Length == 0 ? "全部更新完了。" : "这些没更新成功:\n" + string.Join("\n", failed);
        if (await Dialogs.Show(this, "更新插件", new TextBlock { Text = msg + "\n\n重启后生效,现在重启吗?", TextWrapping = TextWrapping.Wrap, MaxWidth = 420 },
                "立即重启", "稍后"))
            await Restart();
    }

    // ---------------------------------------------------------------- 市场

    private async Task<Control> Market()
    {
        await Disclaimer("market", "插件由第三方开发维护,官方不对插件内容负责。");
        var m = await _core.PluginMarket(new { });
        var box = new StackPanel { Spacing = 10 };
        var search = new TextBox { Watermark = "搜索名称、描述、作者", Text = _query, MinWidth = 260 };
        var cat = new ComboBox { MinWidth = 120, ItemsSource = new[] { "全部分类", "数据源", "主题", "播放器", "弹幕字幕", "工具" }, SelectedIndex = 0 };
        var sort = new ComboBox { MinWidth = 120, ItemsSource = new[] { "下载量", "最近更新", "star", "新上架" }, SelectedIndex = 0 };
        var official = new CheckBox { Content = "只看官方" };
        var paid = new CheckBox { Content = "含付费功能的也显示", IsChecked = true };
        var refresh = Btn("刷新", async () => { await _core.PluginMarket(new { refresh = true }); Reload(); });
        box.Children.Add(new WrapPanel { ItemSpacing = 10, LineSpacing = 10, Children = { search, cat, sort, official, paid, refresh } });
        foreach (var kv in Obj(m, "stale")) box.Children.Add(Note($"{kv.Name} 拉取失败,用的是 {kv.Value.GetInt32()} 天前的缓存", "Warn"));
        foreach (var kv in Obj(m, "errors")) box.Children.Add(Note($"{kv.Name}:{kv.Value.GetString()}", "Danger"));
        var rows = new StackPanel { Spacing = 10 };
        box.Children.Add(rows);
        var entries = Mi.Arr(m, "entries");

        void Fill()
        {
            _query = search.Text?.Trim() ?? "";
            var q = _query.ToLowerInvariant();
            IEnumerable<JsonElement> e = entries.Where(x =>
                (q.Length == 0 || $"{Mi.Str(x, "name")} {Mi.Str(x, "description")} {Mi.Str(x, "author")}".ToLowerInvariant().Contains(q))
                && (cat.SelectedIndex <= 0 || Category(x) == (string)cat.SelectedItem!)
                && (official.IsChecked != true || B(x, "officialMark"))
                && (paid.IsChecked == true || !B(x, "paid")));
            e = sort.SelectedIndex switch
            {
                1 => e.OrderByDescending(x => Latest(x, "released")),
                2 => e.OrderByDescending(x => Mi.Num(x, "stars")),
                3 => e.OrderByDescending(x => Mi.Str(x, "addedAt")),
                _ => e.OrderByDescending(x => Mi.Num(x, "downloads")),
            };
            rows.Children.Clear();
            foreach (var x in e) rows.Children.Add(MarketRow(x));
            if (rows.Children.Count > 0) return;
            if (q.Length == 0) { rows.Children.Add(Dim("市场里还没有插件")); return; }
            rows.Children.Add(new StackPanel
            {
                Spacing = 10,
                Children = { Dim($"没找到「{_query}」"), Btn("清除搜索", () => { search.Text = ""; return Task.CompletedTask; }) },
            });
        }
        search.TextChanged += (_, _) => Fill();
        foreach (var c in new Avalonia.Controls.Primitives.SelectingItemsControl[] { cat, sort }) c.SelectionChanged += (_, _) => Fill();
        foreach (var c in new[] { official, paid }) c.IsCheckedChanged += (_, _) => Fill();
        Fill();
        return box;
    }

    // ponytail: 分类按贡献点粗分五类;弹幕字幕靠 providers 细分要读 index.json 的 providers 细项,等有这样的插件再分
    private static string Category(JsonElement x)
    {
        var c = Mi.Arr(x, "contributes").Select(v => v.GetString() ?? "").ToHashSet();
        if (c.Contains("dataSource")) return "数据源";
        if (c.Contains("theme") || c.Contains("wallpaper")) return "主题";
        if (c.Overlaps(["osd", "shaders", "playerOverlays", "playerPanels", "nextUp"])) return "播放器";
        if (c.Contains("providers")) return "弹幕字幕";
        return "工具";
    }

    private static string Latest(JsonElement x, string k) =>
        x.TryGetProperty("best", out var b) && b.ValueKind == JsonValueKind.Object ? Mi.Str(b, k) : "";

    private Control MarketRow(JsonElement x)
    {
        var title = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 6 };
        title.Children.Add(new TextBlock { Text = Mi.Str(x, "name"), FontWeight = FontWeight.SemiBold });
        if (Latest(x, "version") is { Length: > 0 } v) title.Children.Add(Tag(v));
        if (B(x, "officialMark")) title.Children.Add(Tag("官方", "Accent"));
        if (B(x, "paid")) title.Children.Add(Tag("含付费功能", "Warn"));
        var meta = string.Join(" · ", new[]
        {
            Mi.Str(x, "author"), Mi.Str(x, "repoName"),
            Mi.Num(x, "downloads") is > 0 and var d ? $"{d:0} 次下载" : "",
            Mi.Num(x, "stars") is > 0 and var s ? $"★{s:0}" : "",
        }.Where(t => t.Length > 0));
        var act = MarketAction(x);
        var grid = new Grid { ColumnDefinitions = new ColumnDefinitions("*,Auto"), ColumnSpacing = 14 };
        grid.Children.Add(new StackPanel { Spacing = 2, Children = { title, Dim(meta), Dim(Mi.Str(x, "description")) } });
        Grid.SetColumn(act, 1);
        act.VerticalAlignment = VerticalAlignment.Center;
        grid.Children.Add(act);
        var card = new Border { Classes = { "card", "tap" }, Padding = new Thickness(14), Child = grid };
        card.PointerReleased += (_, e) => { if (e.Source is not Button) _ = MarketDetail(x); };
        return card;
    }

    private Button MarketAction(JsonElement x)
    {
        var installed = Mi.Str(x, "installed");
        var best = Latest(x, "version");
        if (B(x, "needsUpgrade")) return new Button { Classes = { "ghost" }, Content = "需要升级 LinPlayer", IsEnabled = false };
        if (installed.Length > 0 && installed == best) return new Button { Classes = { "ghost" }, Content = "已安装", IsEnabled = false };
        return Btn(installed.Length > 0 ? "更新" : "安装", () => InstallFromRepo(x, best), primary: installed.Length == 0);
    }

    private async Task InstallFromRepo(JsonElement x, string version)
    {
        var id = Mi.Str(x, "id");
        var body = new StackPanel { Spacing = 6, MaxWidth = 420 };
        body.Children.Add(new TextBlock { Text = $"{Mi.Str(x, "name")} {version}", FontWeight = FontWeight.SemiBold });
        if (!B(x, "officialMark")) body.Children.Add(Note("非官方来源:内容由第三方提供,官方不对其负责。", "Warn"));
        if (B(x, "otherSource")) body.Children.Add(Note("已从别的来源装过,会被替换"));
        var contrib = Mi.Arr(x, "contributes").Select(v => v.GetString()).ToArray();
        if (contrib.Length > 0) body.Children.Add(Note("贡献点:" + string.Join("、", contrib)));
        if (x.TryGetProperty("best", out var b) && b.ValueKind == JsonValueKind.Object && Mi.Str(b, "changelog") is { Length: > 0 } log)
            body.Children.Add(Note("更新内容:" + log));
        if (B(x, "lan")) body.Children.Add(Note("会访问你的局域网设备", "Warn"));
        if (!await Dialogs.Show(this, Mi.Str(x, "installed").Length > 0 ? "更新插件" : "安装插件", body, "确定", "取消")) return;
        try
        {
            await _core.PluginInstallFromRepo(new { repo = Mi.Str(x, "repo"), id, version });
            Toast.Show("装好了,重启后生效");
            Reload();
        }
        catch (Exception e) { Toast.Error("安装失败:" + LibraryPage.Advice(e)); }
    }

    private async Task MarketDetail(JsonElement x)
    {
        var body = new StackPanel { Spacing = 6, MaxWidth = 520 };
        body.Children.Add(Note(Mi.Str(x, "description")));
        var contrib = Mi.Arr(x, "contributes").Select(v => v.GetString()).ToArray();
        if (contrib.Length > 0) body.Children.Add(Note("贡献点:" + string.Join("、", contrib)));
        if (x.TryGetProperty("best", out var b) && b.ValueKind == JsonValueKind.Object)
            body.Children.Add(Note($"最低 LinPlayer {Mi.Str(b, "minAppVersion")} · 包 {Mi.Num(b, "size") / 1024:0} KB"));
        foreach (var s in Mi.Arr(x, "sources")) body.Children.Add(Note($"来源 {Mi.Str(s, "repoName")}:最新 {Mi.Str(s, "latest")}"));
        body.Children.Add(new TextBlock { Text = "历史版本", Classes = { "h2" }, Margin = new Thickness(0, 10, 0, 0) });
        foreach (var v in Mi.Arr(x, "versions").Take(10))
            body.Children.Add(Note($"{Mi.Str(v, "version")}  {Mi.Str(v, "released")}  {Mi.Str(v, "changelog")}"));
        if (Mi.Str(x, "repository") is { Length: > 0 } repo) body.Children.Add(Note("源码:" + repo));
        await Dialogs.Show(this, Mi.Str(x, "name"), new ScrollViewer { MaxHeight = 480, Content = body }, "关闭", null);
    }

    // ---------------------------------------------------------------- 接管位

    private async Task<Control> Takeovers()
    {
        var slots = await _core.PluginTakeovers(new { });
        var box = new StackPanel { Spacing = 10 };
        if (slots.GetArrayLength() == 0) { box.Children.Add(Dim("没有插件声明接管位置,全部是官方的。")); return box; }
        foreach (var s in slots.EnumerateArray())
        {
            var slot = Mi.Str(s, "slot");
            var ids = new List<string> { "" };
            var names = new List<string> { "官方" };
            foreach (var c in Mi.Arr(s, "candidates")) { ids.Add(Mi.Str(c, "plugin_id")); names.Add(Mi.Str(c, "name")); }
            var cb = new ComboBox { MinWidth = 200, ItemsSource = names, SelectedIndex = Math.Max(0, ids.IndexOf(Mi.Str(s, "current"))) };
            cb.SelectionChanged += async (_, _) =>
            {
                await _core.PluginSetTakeover(new { slot, plugin_id = ids[Math.Max(0, cb.SelectedIndex)] });
                Toast.Show("重启后生效");
            };
            box.Children.Add(Field(SlotName(slot), cb));
        }
        return box;
    }

    private static string SlotName(string slot) => slot.StartsWith("page:") ? slot[5..] switch
    {
        "home" => "首页", "detail" => "详情页", "ranking" => "排行榜页", "calendar" => "追剧日历", var p => "页面 " + p,
    } : slot;

    // ---------------------------------------------------------------- 仓库

    private async Task<Control> Repos()
    {
        var r = await _core.PluginRepos(new { });
        var box = new StackPanel { Spacing = 10 };
        foreach (var repo in Mi.Arr(r, "repos"))
        {
            var url = Mi.Str(repo, "url");
            var row = new Grid { ColumnDefinitions = new ColumnDefinitions("*,Auto"), ColumnSpacing = 10 };
            row.Children.Add(new StackPanel { Spacing = 2, Children = { new TextBlock { Text = Mi.Str(repo, "name") is { Length: > 0 } n ? n : url }, Dim(url) } });
            if (!B(repo, "official"))
            {
                var del = Btn("删除", async () => { await _core.PluginRemoveRepo(new { url }); Reload(); });
                Grid.SetColumn(del, 1);
                row.Children.Add(del);
            }
            box.Children.Add(new Border { Classes = { "card" }, Padding = new Thickness(14), Child = row });
        }
        var input = new TextBox { Watermark = "仓库地址(index.json 所在目录或文件)", MinWidth = 360 };
        box.Children.Add(new StackPanel
        {
            Orientation = Orientation.Horizontal, Spacing = 10,
            Children =
            {
                input,
                Btn("添加", async () =>
                {
                    var url = input.Text?.Trim() ?? "";
                    if (url.Length == 0) return;
                    if (!await Dialogs.Confirm(this, "添加第三方仓库", "这个仓库不是官方维护的,里面的插件由第三方开发,官方不对其内容负责。", "添加", danger: false)) return;
                    try { await _core.PluginAddRepo(new { url }); Reload(); }
                    catch (Exception e) { Toast.Error("添加失败:" + LibraryPage.Advice(e)); }
                }, primary: true),
            },
        });
        var prefix = new TextBox { Text = Mi.Str(r, "github_prefix"), Watermark = "GitHub 加速前缀(可留空)", MinWidth = 360 };
        prefix.LostFocus += async (_, _) => await _core.PluginSetGithubPrefix(new { prefix = prefix.Text?.Trim() ?? "" });
        var auto = new CheckBox { Content = "自动更新插件", IsChecked = B(r, "auto_update") };
        auto.IsCheckedChanged += async (_, _) => await _core.PluginSetAutoUpdate(new { on = auto.IsChecked == true });
        box.Children.Add(new TextBlock { Text = "下载", Classes = { "h2" }, Margin = new Thickness(0, 14, 0, 0) });
        box.Children.Add(prefix);
        box.Children.Add(auto);
        return box;
    }

    // ---------------------------------------------------------------- 公用

    /// <summary>各免责提示只弹一次(SPEC 14.6),记在数据目录的标记文件里。</summary>
    internal async Task Disclaimer(string key, string text)
    {
        var mark = Path.Combine(Program.DataDir, "notice-" + key);
        if (File.Exists(mark)) return;
        await Dialogs.Tell(this, "提示", text);
        File.WriteAllText(mark, "");
    }

    internal static async Task AskRestart(CoreClient core)
    {
        if (Program.MainWindowRef is not { } w) return;
        try { if ((await core.PluginPendingRestart(new { })).ValueKind != JsonValueKind.True) return; }
        catch (CoreException) { return; } // 核心层已经在关:这时也没法重启
        if (await Dialogs.Show(w, "插件有改动", new TextBlock { Text = "重启后生效。", MaxWidth = 380 }, "立即重启", "稍后"))
            await Restart();
    }

    internal static Task Restart()
    {
        Process.Start(new ProcessStartInfo(Environment.ProcessPath!) { UseShellExecute = false });
        if (Application.Current?.ApplicationLifetime is IClassicDesktopStyleApplicationLifetime d) d.Shutdown();
        return Task.CompletedTask;
    }

    internal static bool B(JsonElement e, string k) =>
        e.ValueKind == JsonValueKind.Object && e.TryGetProperty(k, out var v) && v.ValueKind == JsonValueKind.True;

    internal static IEnumerable<JsonProperty> Obj(JsonElement e, string k) =>
        e.ValueKind == JsonValueKind.Object && e.TryGetProperty(k, out var v) && v.ValueKind == JsonValueKind.Object ? v.EnumerateObject() : [];

    internal static Button Btn(string text, Func<Task> click, bool primary = false)
    {
        var b = new Button { Classes = { primary ? "primary" : "ghost" }, Content = text, MinHeight = 32 };
        b.Click += async (_, _) =>
        {
            b.IsEnabled = false;
            try { await click(); }
            catch (Exception e) { Toast.Error(LibraryPage.Advice(e)); }
            finally { b.IsEnabled = true; }
        };
        return b;
    }

    internal static Control Tag(string text, string tone = "Ink3") => new Border
    {
        Padding = new Thickness(6, 2), CornerRadius = new CornerRadius(6), VerticalAlignment = VerticalAlignment.Center,
        BorderBrush = Tok.Of(tone), BorderThickness = new Thickness(1),
        Child = new TextBlock { Text = text, FontSize = 11, Foreground = Tok.Of(tone) },
    };

    internal static TextBlock Note(string text, string tone = "Ink2") =>
        new() { Text = text, TextWrapping = TextWrapping.Wrap, Foreground = Tok.Of(tone) };

    internal static Control Field(string label, Control input) => new StackPanel
    {
        Orientation = Orientation.Horizontal, Spacing = 10,
        Children = { new TextBlock { Text = label, Width = 120, TextAlignment = TextAlignment.Right, VerticalAlignment = VerticalAlignment.Center }, input },
    };

    private static Control Banner(string text, string? action = null, Func<Task>? click = null)
    {
        var row = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 10 };
        row.Children.Add(new TextBlock { Text = text, TextWrapping = TextWrapping.Wrap, VerticalAlignment = VerticalAlignment.Center, Foreground = Tok.Of("Warn") });
        if (action is not null && click is not null) row.Children.Add(Btn(action, click));
        return new Border { Classes = { "card" }, Padding = new Thickness(14, 10), BorderBrush = Tok.Of("Warn"), BorderThickness = new Thickness(1), Child = row };
    }
}

/// <summary>已装插件的详情(SPEC 14.4 末段):设置、贡献点、占用、启动耗时、版本操作、卸载、错误详情。</summary>
public sealed class PluginDetailPage : PageBase
{
    private readonly CoreClient _core;
    private readonly string _id;
    private readonly StackPanel _root = new() { Spacing = 14, MaxWidth = 760, HorizontalAlignment = HorizontalAlignment.Left };

    public PluginDetailPage(CoreClient core, string id)
    {
        _core = core;
        _id = id;
        Content = Scrolled(_root);
        _ = Load();
    }

    private async Task Load()
    {
        _root.Children.Clear();
        _root.Children.Add(Skeleton.Detail());
        JsonElement d;
        try { d = await _core.PluginDetail(new { id = _id }); }
        catch (Exception e) { _root.Children.Clear(); _root.Children.Add(Dim(LibraryPage.Advice(e))); return; }
        _root.Children.Clear();
        var info = d.TryGetProperty("info", out var i) ? i : default;
        var name = Mi.Str(info, "name") is { Length: > 0 } n ? n : _id;
        _root.Children.Add(H1(name));
        _root.Children.Add(Dim($"{Mi.Str(info, "version")} · {Mi.Str(info, "author")} · {_id}"));
        if (Mi.Num(info, "loadMs") is > 0 and var ms)
            _root.Children.Add(PluginPage.Note($"启动耗时 {ms:0} ms", ms > 500 ? "Warn" : "Ink2"));

        var settings = Mi.Arr(d, "settings");
        if (settings.Count > 0)
        {
            var vals = d.TryGetProperty("values", out var v) ? v : default;
            var form = new StackPanel { Spacing = 10 };
            foreach (var s in settings) if (SettingRow(s, vals) is { } row) form.Children.Add(row);
            _root.Children.Add(Card("设置", form));
        }

        var contrib = Mi.Arr(d, "contributes").Select(x => x.GetString()).ToArray();
        if (contrib.Length > 0) _root.Children.Add(Card("它做了什么", PluginPage.Note(string.Join("、", contrib))));

        var usage = d.TryGetProperty("usage", out var u) ? u : default;
        var total = Mi.Num(usage, "kv") + Mi.Num(usage, "data");
        _root.Children.Add(Card("占用", new StackPanel
        {
            Spacing = 10,
            Children =
            {
                PluginPage.Note($"数据 {Size(total)} · 缓存 {Size(Mi.Num(usage, "cache"))}"),
                new WrapPanel
                {
                    ItemSpacing = 10, LineSpacing = 10,
                    Children =
                    {
                        PluginPage.Btn("清缓存", async () => { await _core.PluginClearData(new { id = _id, cache_only = true }); await Load(); }),
                        PluginPage.Btn("清数据", async () =>
                        {
                            if (!await Dialogs.Confirm(this, "清数据", "插件保存的设置、登录状态都会清掉。")) return;
                            await _core.PluginClearData(new { id = _id, cache_only = false });
                            await Load();
                        }),
                    },
                },
            },
        }));

        var ops = new WrapPanel { ItemSpacing = 10, LineSpacing = 10 };
        if (Mi.Str(info, "prev").Length > 0)
            ops.Children.Add(PluginPage.Btn("回退到 " + Mi.Str(info, "prev"), async () => { await _core.PluginRollback(new { id = _id }); Toast.Show("重启后生效"); await Load(); }));
        if (Mi.Str(info, "update") is { Length: > 0 } up)
            ops.Children.Add(PluginPage.Btn("跳过 " + up, async () => { await _core.PluginSkipVersion(new { id = _id, version = up }); await Load(); }));
        var locked = PluginPage.B(info, "locked");
        ops.Children.Add(PluginPage.Btn(locked ? "解锁自动更新" : "锁定当前版本", async () => { await _core.PluginSetLocked(new { id = _id, locked = !locked }); await Load(); }));
        ops.Children.Add(PluginPage.Btn("复制错误详情", CopyErrors));
        if (PluginPage.B(info, "uninstall"))
            ops.Children.Add(PluginPage.Btn("撤销卸载", async () => { await _core.PluginCancelUninstall(new { id = _id }); await Load(); }));
        else
            ops.Children.Add(PluginPage.Btn("卸载", Uninstall));
        _root.Children.Add(Card("版本与管理", ops));
    }

    private Control? SettingRow(JsonElement s, JsonElement vals)
    {
        var key = Mi.Str(s, "key");
        var title = Mi.Str(s, "title") is { Length: > 0 } t ? t : key;
        var cur = vals.ValueKind == JsonValueKind.Object && vals.TryGetProperty(key, out var v) ? v
            : s.TryGetProperty("default", out var df) ? df : default;
        async Task Save(object? value)
        {
            try { await _core.PluginSetSetting(new { id = _id, key, value }); }
            catch (Exception e) { Toast.Error("没存上:" + LibraryPage.Advice(e)); }
        }
        Control? input = null;
        switch (Mi.Str(s, "type"))
        {
            case "toggle":
                var cb = new CheckBox { Content = title, IsChecked = cur.ValueKind == JsonValueKind.True };
                cb.IsCheckedChanged += (_, _) => _ = Save(cb.IsChecked == true);
                return Described(cb, s);
            case "text" or "password":
                var tb = new TextBox
                {
                    Text = cur.ValueKind == JsonValueKind.String ? cur.GetString() : "", MinWidth = 320,
                    PasswordChar = Mi.Str(s, "type") == "password" ? '•' : '\0',
                    AcceptsReturn = PluginPage.B(s, "multiline"), MinHeight = PluginPage.B(s, "multiline") ? 90 : 0,
                    TextWrapping = PluginPage.B(s, "multiline") ? TextWrapping.Wrap : TextWrapping.NoWrap,
                };
                tb.LostFocus += (_, _) => _ = Save(tb.Text ?? "");
                input = tb;
                break;
            case "number" or "slider":
                var nu = new NumericUpDown
                {
                    Value = cur.ValueKind == JsonValueKind.Number ? cur.GetDecimal() : 0, MinWidth = 160,
                    Minimum = s.TryGetProperty("min", out var mn) ? mn.GetDecimal() : decimal.MinValue,
                    Maximum = s.TryGetProperty("max", out var mx) ? mx.GetDecimal() : decimal.MaxValue,
                    Increment = s.TryGetProperty("step", out var sp) ? sp.GetDecimal() : 1,
                };
                nu.ValueChanged += (_, _) => _ = Save(nu.Value);
                input = nu;
                break;
            case "select":
                var opts = Mi.Arr(s, "options");
                var sel = new ComboBox
                {
                    MinWidth = 200, ItemsSource = opts.Select(o => Mi.Str(o, "label")).ToList(),
                    SelectedIndex = opts.FindIndex(o => cur.ValueKind == JsonValueKind.String && Mi.Str(o, "value") == cur.GetString()),
                };
                sel.SelectionChanged += (_, _) => { if (sel.SelectedIndex >= 0) _ = Save(Mi.Str(opts[sel.SelectedIndex], "value")); };
                input = sel;
                break;
            case "multiselect":
                var chosen = cur.ValueKind == JsonValueKind.Array ? cur.EnumerateArray().Select(x => x.GetString() ?? "").ToHashSet() : [];
                var wrap = new WrapPanel { ItemSpacing = 10, LineSpacing = 6 };
                foreach (var o in Mi.Arr(s, "options"))
                {
                    var val = Mi.Str(o, "value");
                    var c = new CheckBox { Content = Mi.Str(o, "label"), IsChecked = chosen.Contains(val) };
                    c.IsCheckedChanged += (_, _) =>
                    {
                        if (c.IsChecked == true) chosen.Add(val); else chosen.Remove(val);
                        _ = Save(chosen.ToArray());
                    };
                    wrap.Children.Add(c);
                }
                input = wrap;
                break;
            case "button":
                var action = Mi.Str(s, "action");
                return Described(PluginPage.Btn(title, async () =>
                {
                    await _core.SourceRunCommand(new { plugin_id = _id, command = action });
                    Toast.Show("完成");
                }), s);
            case "group":
                return new TextBlock { Text = title, Classes = { "h2" }, Margin = new Thickness(0, 10, 0, 0) };
        }
        return input is null ? null : Described(PluginPage.Field(title, input), s);
    }

    private static Control Described(Control c, JsonElement s) =>
        Mi.Str(s, "description") is { Length: > 0 } d
            ? new StackPanel { Spacing = 2, Children = { c, new TextBlock { Text = d, FontSize = 12, Foreground = Tok.Of("Ink3"), TextWrapping = TextWrapping.Wrap } } }
            : c;

    private async Task Uninstall()
    {
        var keep = new CheckBox { Content = "同时删除它的数据" };
        if (!await Dialogs.Show(this, "卸载插件", new StackPanel { Spacing = 10, Children = { new TextBlock { Text = "重启后卸载。" }, keep } }, "卸载", "取消", true)) return;
        await _core.PluginUninstall(new { id = _id, delete_data = keep.IsChecked == true });
        await Load();
    }

    private async Task CopyErrors()
    {
        var d = await _core.PluginErrorDetail(new { id = _id });
        if (TopLevel.GetTopLevel(this)?.Clipboard is { } cb)
        {
            await cb.SetTextAsync(JsonSerializer.Serialize(d, new JsonSerializerOptions { WriteIndented = true }));
            Toast.Show("错误详情已复制(只在本机内存里,不会自动上传)");
        }
    }

    private static string Size(double b) => b < 1024 * 1024 ? $"{b / 1024:0} KB" : $"{b / 1024 / 1024:0.0} MB";

    private static Control Card(string title, Control body) => new Border
    {
        Classes = { "card" }, Padding = new Thickness(18),
        Child = new StackPanel { Spacing = 10, Children = { H2(title), body } },
    };
}

/// <summary>
/// 扩展组件页(SPEC 18.5):宿主可下载的大件原生运行物。桌面目前只有补帧真有发行包,
/// 其余列出「谁需要」和实情,不摆下载键 —— 摆着点不动的按钮比没有更糟。
/// </summary>
public sealed class ExtensionsPage : PageBase
{
    private static readonly (string Id, string Name, string Use, string State)[] Others =
    [
        ("jar-runtime", "TVBox jar 运行时", "桌面跑 TVBox jar 源", "桌面版还在验证,暂不提供;jar 源在安卓端可用"),
        ("python", "Python 运行时", "TVBox Python 源", "还没有发行包"),
        ("whisper", "Whisper 转写", "本地语音转字幕", "还没有发行包"),
        ("ffmpeg", "FFmpeg 工具", "抽音频、抽帧", "还没有发行包"),
    ];

    public ExtensionsPage(CoreClient core)
    {
        var box = new StackPanel { Spacing = 14, MaxWidth = 760, HorizontalAlignment = HorizontalAlignment.Left };
        box.Children.Add(H1("扩展组件"));
        box.Children.Add(Dim("插件用到时才会提示下载,安装插件时不强制下。"));
        box.Children.Add(SettingsSections.Interp(core));
        var rest = new StackPanel { Spacing = 10 };
        box.Children.Add(rest);
        Content = Scrolled(box);
        _ = Task.Run(async () =>
        {
            var users = new Dictionary<string, List<string>>();
            try
            {
                foreach (var p in Mi.Arr(await core.PluginList(new { }), "plugins"))
                    foreach (var c in Mi.Arr(p, "components"))
                        (users.TryGetValue(c.GetString() ?? "", out var l) ? l : users[c.GetString() ?? ""] = []).Add(Mi.Str(p, "name"));
            }
            catch (CoreException e) { Log.W("ext", "读插件列表失败:" + e.Message); } // 只影响「谁在用」那一行
            Dispatcher.UIThread.Post(() =>
            {
                foreach (var (id, name, use, state) in Others)
                {
                    var s = new StackPanel { Spacing = 2, Children = { new TextBlock { Text = name, FontWeight = FontWeight.SemiBold }, Dim(use), Dim(state) } };
                    if (users.TryGetValue(id, out var who)) s.Children.Add(PluginPage.Note("需要它的插件:" + string.Join("、", who), "Warn"));
                    rest.Children.Add(new Border { Classes = { "card" }, Padding = new Thickness(14), Child = s });
                }
            });
        });
    }
}
