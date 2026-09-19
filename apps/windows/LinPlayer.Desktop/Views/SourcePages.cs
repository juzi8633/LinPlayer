using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.Primitives;
using Avalonia.Input;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Threading;
using Avalonia.VisualTree;
using LinPlayer.Core;
using LinPlayer.Desktop.Core;

namespace LinPlayer.Desktop.Views;

/// <summary>一次数据源播放:哪个源、哪部(详情快照)、哪条线路、哪一集。</summary>
public sealed record SourcePlay(string ServerId, JsonElement Item, string LineId, string EpisodeId, string Label);

/// <summary>统一结构(MediaItem / MediaDetail)的读取小工具。字段名见 plugin-sdk.d.ts。</summary>
internal static class Mi
{
    public static string Str(JsonElement e, string k) =>
        e.ValueKind == JsonValueKind.Object && e.TryGetProperty(k, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() ?? "" : "";

    public static double Num(JsonElement e, string k) =>
        e.ValueKind == JsonValueKind.Object && e.TryGetProperty(k, out var v) && v.ValueKind == JsonValueKind.Number ? v.GetDouble() : 0;

    public static List<JsonElement> ArrOf(JsonElement e) =>
        e.ValueKind == JsonValueKind.Array ? e.EnumerateArray().ToList() : [];
    public static List<JsonElement> Arr(JsonElement e, string k) =>
        e.ValueKind == JsonValueKind.Object && e.TryGetProperty(k, out var v) && v.ValueKind == JsonValueKind.Array ? v.EnumerateArray().ToList() : [];

    public static string Img(JsonElement e, string k) =>
        e.ValueKind == JsonValueKind.Object && e.TryGetProperty(k, out var v) && v.ValueKind == JsonValueKind.Object ? Str(v, "url") : "";

    public static string YearText(JsonElement e) => Num(e, "year") is var y && y > 0 ? ((int)y).ToString() : "";

    /// <summary>卡片宽高比:分类声明的海报比例(D336)。</summary>
    public static (double W, double H) Shape(string shape) => shape switch
    {
        "landscape" => (240, 135),
        "square" => (160, 160),
        _ => (150, 225),
    };

    /// <summary>错误对象里插件原 kind(needVerify / rateLimited …),给错误按钮用(D323)。</summary>
    public static (string Kind, int RetryAfter) KindOf(Exception e)
    {
        if (e is CoreException ce && ce.Detail is { Length: > 0 } d)
        {
            try
            {
                using var doc = JsonDocument.Parse(d);
                var r = doc.RootElement;
                return (Str(r, "kind"), (int)Num(r, "retryAfter"));
            }
            catch (JsonException) { /* detail 不是插件错误的 JSON(别的命令的原因文字):当作没有 kind */ }
        }
        return ("", 0);
    }
}

/// <summary>
/// 数据源条目卡:海报 + 角标(remarks,D330)+ 标题 + 年份。缺海报画统一灰色占位(D341)。
/// </summary>
public sealed class SourceCard : Button
{
    public SourceCard(CoreClient core, JsonElement item, Action<JsonElement> onOpen, string shape = "portrait", string badge = "", double progress = -1)
    {
        var (w, h) = Mi.Shape(shape);
        Classes.Add("media");
        Width = w;
        Padding = new Thickness(0);
        var title = Mi.Str(item, "title");
        var img = new Image { Stretch = Stretch.UniformToFill, Opacity = 0, Classes = { "art" } };
        var layers = new Panel { Children = { img } };
        var remarks = Mi.Str(item, "remarks");
        if (remarks.Length > 0)
            layers.Children.Add(new Border
            {
                HorizontalAlignment = HorizontalAlignment.Right, VerticalAlignment = VerticalAlignment.Top,
                Margin = new Thickness(6), Padding = new Thickness(6, 2), CornerRadius = new CornerRadius(6),
                Background = new SolidColorBrush(Color.Parse("#b0000000")),
                Child = new TextBlock { Text = remarks, FontSize = 11, Foreground = Brushes.White, MaxWidth = w - 26, TextTrimming = TextTrimming.CharacterEllipsis },
            });
        if (badge.Length > 0)
            layers.Children.Add(new Border
            {
                HorizontalAlignment = HorizontalAlignment.Left, VerticalAlignment = VerticalAlignment.Top,
                Margin = new Thickness(6), Padding = new Thickness(6, 2), CornerRadius = new CornerRadius(999),
                Background = new SolidColorBrush(Color.Parse("#b0000000")),
                Child = new TextBlock { Text = badge, FontSize = 11, Foreground = Brushes.White, MaxWidth = w - 26, TextTrimming = TextTrimming.CharacterEllipsis },
            });
        if (progress > 0)
            layers.Children.Add(new Border
            {
                VerticalAlignment = VerticalAlignment.Bottom, HorizontalAlignment = HorizontalAlignment.Left,
                Height = 3, Width = w * Math.Clamp(progress, 0, 1), Background = Tok.Of("Accent"),
            });
        var poster = Mi.Img(item, "poster");
        if (poster.Length > 0) _ = Fill(core, img, poster, (int)h);
        var year = Mi.YearText(item);
        Content = new StackPanel
        {
            Spacing = 6,
            Children =
            {
                new Border { Width = w, Height = h, CornerRadius = new CornerRadius(10), ClipToBounds = true, Background = Tok.Of("PanelAlt"), Child = layers },
                new TextBlock { Text = title, FontSize = 13, TextTrimming = TextTrimming.CharacterEllipsis, MaxLines = 2, TextWrapping = TextWrapping.Wrap },
                new TextBlock { Text = year, FontSize = 11.5, Classes = { "dim" }, IsVisible = year.Length > 0 },
            },
        };
        Click += (_, _) => onOpen(item);
    }

    private static async Task Fill(CoreClient core, Image target, string url, int h)
    {
        var bmp = await Images.LoadAsync(core, url, h);
        if (bmp is null) return; // 拉不到就留灰色占位
        Dispatcher.UIThread.Post(() => { target.Source = bmp; target.Opacity = 1; });
    }
}

/// <summary>数据源页面共用的跳转。</summary>
internal static class SourceNav
{
    public static Action<JsonElement> OpenDetail(CoreClient core, string serverId) => it =>
    {
        var sid = Mi.Str(it, "source") is { Length: > 0 } s ? s : serverId;
        var id = Mi.Str(it, "id");
        Nav.Push(new SourceDetailPage(core, sid, id), () => new SourceDetailPage(core, sid, id));
    };

    /// <summary>继续观看:进详情页并接着那一集的进度起播(详情页拿到完整线路表才知道下一集是哪集)。</summary>
    public static void Resume(CoreClient core, string server, JsonElement r, double pos)
    {
        var id = Mi.Str(r.GetProperty("item"), "id");
        var auto = new SourceDetailPage.AutoPlay(Mi.Str(r, "lineId"), Mi.Str(r, "episodeId"), pos);
        Nav.Push(new SourceDetailPage(core, server, id, auto), () => new SourceDetailPage(core, server, id));
    }

    /// <summary>全局观看历史里数据源那部分(D430 D431);来源已删的仍显示但点不开(D333)。</summary>
    public static async Task<Control?> HistorySection(CoreClient core)
    {
        var list = Mi.ArrOf(await core.SourceHistory(new { }));
        if (list.Count == 0) return null;
        var row = new WrapPanel { ItemSpacing = 18, LineSpacing = 18 };
        foreach (var c in list.Take(60))
        {
            var r = c.GetProperty("ref");
            var dur = Mi.Num(c, "duration_secs");
            var pos = Mi.Num(c, "position_secs");
            var srv = Mi.Str(c, "server_id");
            var removed = c.TryGetProperty("removed", out var rm) && rm.ValueKind == JsonValueKind.True;
            var badge = removed ? "来源已移除" : $"{Mi.Str(c, "server_name")} · {Mi.Str(r, "episodeName")}";
            row.Children.Add(new SourceCard(core, r.GetProperty("item"),
                _ => { if (removed) Toast.Show("这个来源已经移除了"); else Resume(core, srv, r, pos); },
                badge: badge, progress: dur > 0 ? pos / dur : -1));
        }
        return new StackPanel { Spacing = 14, Children = { new TextBlock { Text = "数据源", Classes = { "h2" } }, row } };
    }

    /// <summary>数据源收藏,按来源分组(D326 D333)。</summary>
    public static async Task<Control?> FavoritesSection(CoreClient core, string serverId = "")
    {
        var groups = Mi.ArrOf(await core.SourceFavorites(new { server_id = serverId }));
        if (groups.Count == 0) return null;
        var col = new StackPanel { Spacing = 26 };
        foreach (var g in groups)
        {
            var srv = Mi.Str(g, "server_id");
            var removed = g.TryGetProperty("removed", out var rm) && rm.ValueKind == JsonValueKind.True;
            var grid = new WrapPanel { ItemSpacing = 18, LineSpacing = 26 };
            foreach (var it in Mi.Arr(g, "items"))
                grid.Children.Add(new SourceCard(core, it, removed ? _ => Toast.Show("这个来源已经移除了") : OpenDetail(core, srv)));
            col.Children.Add(new StackPanel
            {
                Spacing = 14,
                Children = { new TextBlock { Text = Mi.Str(g, "server_name") + (removed ? "(已移除)" : ""), Classes = { "h2" } }, grid },
            });
        }
        return col;
    }

    /// <summary>错误文案 + 动作(D323):限流给倒计时、需要验证给[去验证]、站点挂了给[换源]。</summary>
    public static Control ErrorBlock(CoreClient core, string serverId, Exception e, Func<Task> retry, Action? switchSource = null)
    {
        var (kind, retryAfter) = Mi.KindOf(e);
        var panel = new StackPanel { Spacing = 10 };
        panel.Children.Add(new TextBlock { Text = LibraryPage.Advice(e), TextWrapping = TextWrapping.Wrap, Foreground = Tok.Of("Ink2") });
        var row = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 10 };
        panel.Children.Add(row);
        switch (kind)
        {
            case "rateLimited":
                {
                    var btn = new Button { Classes = { "ghost" }, IsEnabled = false };
                    row.Children.Add(btn);
                    var left = Math.Max(retryAfter, 5);
                    btn.Content = $"{left} 秒后重试";
                    var t = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1) };
                    t.Tick += (_, _) =>
                    {
                        if (--left > 0) { btn.Content = $"{left} 秒后重试"; return; }
                        t.Stop();
                        btn.Content = "重试";
                        btn.IsEnabled = true;
                    };
                    btn.Click += (_, _) => _ = retry();
                    t.Start();
                    break;
                }
            case "needVerify":
                {
                    var btn = new Button { Classes = { "primary" }, Content = "去验证" };
                    btn.Click += async (_, _) =>
                    {
                        var url = e is CoreException ce && ce.Detail is { } d && TryVerifyUrl(d) is { Length: > 0 } u ? u : "";
                        if (url.Length > 0 && await PluginShell.VerifyInWebView(url, serverId)) await retry();
                    };
                    row.Children.Add(btn);
                    break;
                }
            case "siteDown":
                if (switchSource is not null)
                {
                    var btn = new Button { Classes = { "primary" }, Content = "换源" };
                    btn.Click += (_, _) => switchSource();
                    row.Children.Add(btn);
                }
                break;
            default:
                {
                    var btn = new Button { Classes = { "ghost" }, Content = "重试" };
                    btn.Click += (_, _) => _ = retry();
                    row.Children.Add(btn);
                    break;
                }
        }
        return panel;
    }

    private static string TryVerifyUrl(string detail)
    {
        try
        {
            using var doc = JsonDocument.Parse(detail);
            return Mi.Str(doc.RootElement, "verifyUrl");
        }
        catch (JsonException) { return ""; } // 不是插件错误的 JSON:没有验证地址
    }
}

/// <summary>
/// 数据源首页(D167 D175 D344):顶部「继续观看」→ 推荐行 → 分类入口;顶上一条源内搜索(停顿 500ms 自动搜,D338)。
/// </summary>
public sealed class SourceHomePage : PageBase
{
    private readonly CoreClient _core;
    private readonly string _server;
    private readonly StackPanel _body = new() { Spacing = 26 };
    private readonly ContentControl _results = new() { IsVisible = false };
    private CancellationTokenSource? _searchCts;
    private JsonElement _caps;

    public SourceHomePage(CoreClient core, string serverId, string name)
    {
        _core = core;
        _server = serverId;
        var box = new TextBox { Classes = { "field" }, Watermark = $"在「{name}」里搜索", MinWidth = 320 };
        var debounce = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(500) };
        debounce.Tick += (_, _) => { debounce.Stop(); _ = Search(box.Text ?? ""); };
        box.TextChanged += (_, _) => { debounce.Stop(); _searchCts?.Cancel(); debounce.Start(); };
        var head = new DockPanel { Children = { H1(name) } };
        var searchRow = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 10, Children = { box } };
        DockPanel.SetDock(searchRow, Dock.Right);
        head.Children.Insert(0, searchRow);
        Content = Scrolled(new StackPanel { Spacing = 18, Children = { head, _results, _body } });
        _ = Load();
    }

    private async Task Load()
    {
        _body.Children.Clear();
        _body.Children.Add(Skeleton.Strip(false));
        try { _caps = await _core.SourceCaps(new { server_id = _server }); }
        catch (Exception e) { Show(SourceNav.ErrorBlock(_core, _server, e, Load)); return; }
        var hasHome = _caps.TryGetProperty("home", out var h) && h.ValueKind == JsonValueKind.True;
        JsonElement cont = default, home = default;
        Exception? homeErr = null;
        var tCont = _core.SourceContinueWatching(new { server_id = _server });
        if (hasHome)
        {
            try { home = await _core.SourceHome(new { server_id = _server }); }
            catch (Exception e) { homeErr = e; }
        }
        try { cont = await tCont; }
        catch (Exception e) { Log.W("source", "继续观看拉不到: " + e.Message); }
        Dispatcher.UIThread.Post(() =>
        {
            _body.Children.Clear();
            var conts = cont.ValueKind == JsonValueKind.Array ? cont.EnumerateArray().ToList() : [];
            if (conts.Count > 0)
            {
                var row = new WrapPanel { ItemSpacing = 18, LineSpacing = 18 };
                foreach (var c in conts.Take(12))
                {
                    var r = c.GetProperty("ref");
                    var it = r.GetProperty("item");
                    var dur = Mi.Num(c, "duration_secs");
                    var prog = dur > 0 ? Mi.Num(c, "position_secs") / dur : -1;
                    var pos = Mi.Num(c, "position_secs");
                    row.Children.Add(new SourceCard(_core, it, _ => Resume(r, pos), badge: Mi.Str(r, "episodeName"), progress: prog));
                }
                _body.Children.Add(new StackPanel { Spacing = 14, Children = { H2("继续观看"), row } });
            }
            if (homeErr is not null)
            {
                _body.Children.Add(SourceNav.ErrorBlock(_core, _server, homeErr, Load));
                return;
            }
            if (!hasHome)
            {
                if (_body.Children.Count == 0)
                    _body.Children.Add(Dim("这个源没有首页,在上面的搜索框里找想看的。"));
                return;
            }
            var cats = Mi.Arr(home, "categories");
            var rec = Mi.Arr(home, "recommended");
            if (cats.Count > 0)
            {
                var chips = new WrapPanel { ItemSpacing = 10, LineSpacing = 10 };
                foreach (var cat in cats)
                {
                    var c = cat;
                    var b = new Button { Classes = { "chip" }, Content = Mi.Str(c, "name") };
                    b.Click += (_, _) => Nav.Push(new SourceCategoryPage(_core, _server, c), () => new SourceCategoryPage(_core, _server, c));
                    chips.Children.Add(b);
                }
                _body.Children.Add(new StackPanel { Spacing = 14, Children = { H2("分类"), chips } });
            }
            if (rec.Count > 0)
            {
                var grid = new WrapPanel { ItemSpacing = 18, LineSpacing = 26 };
                foreach (var it in rec) grid.Children.Add(new SourceCard(_core, it, SourceNav.OpenDetail(_core, _server)));
                _body.Children.Add(new StackPanel { Spacing = 14, Children = { H2("推荐"), grid } });
            }
            else if (cats.Count > 0)
            {
                // 没有推荐行:直接铺第一个分类(D344)
                _body.Children.Add(new SourceCategoryGrid(_core, _server, cats[0]));
            }
            if (cats.Count == 0 && rec.Count == 0 && conts.Count == 0)
                _body.Children.Add(Dim("这个源的首页是空的,在上面的搜索框里找想看的。"));
        });
    }

    private void Show(Control c) => Dispatcher.UIThread.Post(() => { _body.Children.Clear(); _body.Children.Add(c); });

    private void Resume(JsonElement r, double pos) => SourceNav.Resume(_core, _server, r, pos);

    private async Task Search(string q)
    {
        q = q.Trim();
        if (q.Length == 0) { _results.IsVisible = false; _body.IsVisible = true; return; }
        _searchCts = new CancellationTokenSource();
        var ct = _searchCts.Token;
        _results.IsVisible = true;
        _body.IsVisible = false;
        _results.Content = Skeleton.Grid(false, 8);
        try
        {
            var r = await _core.SourceSearchItems(new { server_id = _server, keyword = q }, ct);
            if (ct.IsCancellationRequested) return;
            var items = Mi.Arr(r, "items");
            Dispatcher.UIThread.Post(() =>
            {
                if (items.Count == 0) { _results.Content = Dim($"没找到「{q}」。"); return; }
                var grid = new WrapPanel { ItemSpacing = 18, LineSpacing = 26 };
                foreach (var it in items) grid.Children.Add(new SourceCard(_core, it, SourceNav.OpenDetail(_core, _server)));
                _results.Content = grid;
            });
        }
        catch (OperationCanceledException) { /* 改了关键词:这次的结果作废(D166) */ }
        catch (Exception e) { Dispatcher.UIThread.Post(() => _results.Content = SourceNav.ErrorBlock(_core, _server, e, () => Search(q))); }
    }
}

/// <summary>分类页:筛选(单选/多选,D334)+ 滚到底加载下一页(D166)。</summary>
public sealed class SourceCategoryPage : PageBase
{
    public SourceCategoryPage(CoreClient core, string serverId, JsonElement category)
    {
        Content = Scrolled(new StackPanel { Spacing = 18, Children = { H1(Mi.Str(category, "name")), new SourceCategoryGrid(core, serverId, category) } });
    }
}

/// <summary>分类网格本体(首页没推荐行时直接铺在首页上)。</summary>
public sealed class SourceCategoryGrid : StackPanel
{
    // 筛选按「源 + 分类」记住(D335);进程内记,重启回默认
    private static readonly Dictionary<string, Dictionary<string, List<string>>> Remembered = [];
    private readonly CoreClient _core;
    private readonly string _server;
    private readonly JsonElement _cat;
    private readonly WrapPanel _grid = new() { ItemSpacing = 18, LineSpacing = 26 };
    private readonly TextBlock _status = new() { Classes = { "dim" } };
    private readonly Dictionary<string, List<string>> _filters;
    private string? _next = "";
    private bool _busy;
    private int _gen;

    public SourceCategoryGrid(CoreClient core, string serverId, JsonElement category)
    {
        _core = core;
        _server = serverId;
        _cat = category;
        Spacing = 14;
        var key = serverId + "#" + Mi.Str(category, "id");
        if (!Remembered.TryGetValue(key, out var f)) Remembered[key] = f = [];
        _filters = f;
        var dims = Mi.Arr(category, "filters");
        if (dims.Count > 0) Children.Add(FilterBar(dims));
        Children.Add(_grid);
        Children.Add(_status);
        // 滚到底加载下一页:挂在外层 ScrollViewer 上(页面用的是 PageBase.Scrolled)
        AttachedToVisualTree += (_, _) =>
        {
            if (this.FindAncestorOfType<ScrollViewer>() is { } sv)
                sv.ScrollChanged += (_, _) => { if (sv.Offset.Y + sv.Viewport.Height > sv.Extent.Height - 600) _ = More(); };
        };
        _ = More();
    }

    private Control FilterBar(List<JsonElement> dims)
    {
        var col = new StackPanel { Spacing = 10 };
        foreach (var d in dims)
        {
            var key = Mi.Str(d, "key");
            var multi = d.TryGetProperty("multi", out var m) && m.ValueKind == JsonValueKind.True;
            var row = new WrapPanel { ItemSpacing = 6, LineSpacing = 6 };
            row.Children.Add(new TextBlock { Text = Mi.Str(d, "name"), Classes = { "dim" }, Width = 64, VerticalAlignment = VerticalAlignment.Center });
            var buttons = new List<(Button B, string V)>();
            foreach (var o in Mi.Arr(d, "options"))
            {
                var v = Mi.Str(o, "value");
                var b = new Button { Classes = { "chip" }, Content = Mi.Str(o, "name") };
                buttons.Add((b, v));
                b.Click += (_, _) =>
                {
                    _filters.TryGetValue(key, out var cur);
                    cur ??= [];
                    if (v.Length == 0) cur.Clear();
                    else if (multi) { if (!cur.Remove(v)) cur.Add(v); }
                    else { cur.Clear(); cur.Add(v); }
                    _filters[key] = cur;
                    Sync();
                    Requery();
                };
                row.Children.Add(b);
            }
            void Sync()
            {
                _filters.TryGetValue(key, out var cur);
                foreach (var (b, v) in buttons)
                    b.Classes.Set("on", cur is { Count: > 0 } ? cur.Contains(v) : v.Length == 0);
            }
            Sync();
            col.Children.Add(row);
        }
        return col;
    }

    private void Requery()
    {
        _gen++;
        _busy = false; // 换筛选要松开在途的闸,否则这次换筛选会被吞掉
        _next = "";
        _grid.Children.Clear();
        _ = More();
    }

    private async Task More()
    {
        if (_busy || _next is null) return;
        _busy = true;
        var gen = _gen;
        _status.Text = "加载中…";
        try
        {
            var r = await _core.SourceCategory(new
            {
                server_id = _server, category_id = Mi.Str(_cat, "id"),
                filters = _filters.Where(p => p.Value.Count > 0).ToDictionary(p => p.Key, p => p.Value),
                cursor = _next is { Length: > 0 } c ? c : null,
            });
            if (gen != _gen) return;
            var items = Mi.Arr(r, "items");
            var next = Mi.Str(r, "next");
            _next = next.Length > 0 ? next : null; // 同步赋值:丢进 Post 会让下一轮读到旧值
            var shape = Mi.Str(_cat, "posterShape");
            Dispatcher.UIThread.Post(() =>
            {
                foreach (var it in items) _grid.Children.Add(new SourceCard(_core, it, SourceNav.OpenDetail(_core, _server), shape));
                _status.Text = _next is null ? (_grid.Children.Count == 0 ? "这个分类下没有内容。" : "") : "";
            });
        }
        catch (Exception e)
        {
            if (gen == _gen) Dispatcher.UIThread.Post(() => _status.Text = "加载失败:" + LibraryPage.Advice(e));
        }
        finally
        {
            if (gen == _gen) _busy = false;
        }
    }
}

/// <summary>添加插件数据源(D131 D45 D346):表单 → 插件给源草稿(或先给多仓让用户勾)→ 用户勾源 → 落账号表。</summary>
public static class PluginSources
{
    public static async Task<bool> Add(CoreClient core, Control anchor, string pluginId, string typeId, Dictionary<string, string> form)
    {
        var mark = System.IO.Path.Combine(Program.DataDir, "notice-subscription");
        if (!System.IO.File.Exists(mark))
        {
            await Dialogs.Tell(anchor, "提示", "订阅内容由你自行添加,与 LinPlayer 官方无关。");
            System.IO.File.WriteAllText(mark, "");
        }
        var r = await core.SourceCreateSources(new { plugin_id = pluginId, type_id = typeId, form });
        var repos = Mi.Arr(r, "repos");
        if (repos.Count > 0)
        {
            var picked = await Pick(anchor, "选择要订阅的仓库", repos.Select(x => (Mi.Str(x, "id"), Mi.Str(x, "name"), "")).ToList());
            if (picked.Count == 0) return false;
            r = await core.SourceCreateSources(new { plugin_id = pluginId, type_id = typeId, form, repos = picked });
        }
        var drafts = Mi.Arr(r, "sources");
        if (drafts.Count == 0) { await Dialogs.Tell(anchor, "没有可添加的源", "这份配置里没有读到任何源。"); return false; }
        var ids = drafts.Count == 1 && !PluginPage.B(drafts[0], "exists") ? [Mi.Str(drafts[0], "id")]
            : await Pick(anchor, "选择要添加的源", drafts.Select(d => (Mi.Str(d, "id"), Mi.Str(d, "name"),
                PluginPage.B(d, "exists") ? "已添加" : Mi.Str(d, "unavailableReason"))).ToList());
        if (ids.Count == 0) return false;
        var chosen = drafts.Where(d => ids.Contains(Mi.Str(d, "id"))).ToArray();
        var added = await core.SourceAddSources(new { plugin_id = pluginId, type_id = typeId, sources = chosen });
        Toast.Show($"已添加 {Mi.Arr(added, "added").Count} 个源");
        return true;
    }

    /// <summary>勾选框清单;第三项非空 = 不可选并写原因(已添加 / 本设备不可用)。</summary>
    private static async Task<List<string>> Pick(Control anchor, string title, List<(string Id, string Name, string Why)> items)
    {
        var boxes = new List<(CheckBox Box, string Id)>();
        var list = new StackPanel { Spacing = 6 };
        foreach (var (id, name, why) in items)
        {
            var cb = new CheckBox { Content = why.Length > 0 ? $"{name}({why})" : name, IsChecked = why.Length == 0, IsEnabled = why.Length == 0 };
            boxes.Add((cb, id));
            list.Children.Add(cb);
        }
        var all = new CheckBox { Content = "全选", IsChecked = true };
        all.IsCheckedChanged += (_, _) => { foreach (var (b, _) in boxes) if (b.IsEnabled) b.IsChecked = all.IsChecked; };
        var body = new StackPanel { Spacing = 10, Width = 420, Children = { all, new ScrollViewer { MaxHeight = 420, Content = list } } };
        if (!await Dialogs.Show(anchor, title, body, "添加", "取消")) return [];
        return boxes.Where(b => b.Box.IsChecked == true && b.Box.IsEnabled).Select(b => b.Id).ToList();
    }
}

/// <summary>当前是插件数据源时的「收藏」:全部数据源的收藏(Emby 收藏要先切到那台服务器)。</summary>
public sealed class SourceFavoritesPage : PageBase
{
    public SourceFavoritesPage(CoreClient core)
    {
        var body = new StackPanel { Spacing = 14, Children = { H1("收藏"), Skeleton.Grid(false, 8) } };
        Content = Scrolled(body);
        _ = Load();
        // 在 UI 线程上 await:区块里的控件是 await 之后才建的
        async Task Load()
        {
            Control c;
            try { c = await SourceNav.FavoritesSection(core) ?? Dim("还没有收藏。在详情页点「收藏」加进来。"); }
            catch (Exception e) { c = Dim(LibraryPage.Advice(e)); }
            body.Children.RemoveAt(1);
            body.Children.Add(c);
        }
    }
}
