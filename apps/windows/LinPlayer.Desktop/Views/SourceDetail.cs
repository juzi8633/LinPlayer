using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.Json;
using System.Threading.Tasks;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Threading;
using LinPlayer.Core;
using LinPlayer.Desktop.Core;

namespace LinPlayer.Desktop.Views;

/// <summary>
/// 数据源详情页(SPEC 8.7「详情页」)。页面官方画,插件只供数据(D40)。
/// </summary>
public sealed class SourceDetailPage : PageBase
{
    /// <summary>进来就起播:继续观看(线路 + 集 + 进度)或换源跳过来(只知道第几集 + 进度)。</summary>
    public sealed record AutoPlay(string LineId, string EpisodeId, double Pos, int EpisodeIndex = 0);

    private const int EpPage = 50;
    private readonly CoreClient _core;
    private readonly string _server;
    private readonly string _itemId;
    private readonly AutoPlay? _auto;
    private readonly StackPanel _body = new() { Spacing = 18 };
    private JsonElement _d;
    private JsonElement _caps;
    private string _line = "";
    private int _page;
    private bool _desc;

    public SourceDetailPage(CoreClient core, string serverId, string itemId, AutoPlay? auto = null)
    {
        _core = core;
        _server = serverId;
        _itemId = itemId;
        _auto = auto;
        var back = new Button { Classes = { "ghost" }, Content = "← 返回", HorizontalAlignment = HorizontalAlignment.Left };
        back.Click += (_, _) => Nav.Back();
        _body.Children.Add(Skeleton.Detail());
        Content = Scrolled(new StackPanel { Spacing = 14, Children = { back, _body } });
        _ = Load();
    }

    private async Task Load()
    {
        try
        {
            var t = _core.SourceCaps(new { server_id = _server });
            _d = await _core.SourceDetail(new { server_id = _server, item_id = _itemId });
            _caps = await t;
        }
        catch (Exception e)
        {
            Dispatcher.UIThread.Post(() => { _body.Children.Clear(); _body.Children.Add(SourceNav.ErrorBlock(_core, _server, e, Load, () => OpenSwitch(0))); });
            return;
        }
        bool desc = false;
        try
        {
            var p = await _core.PrefsGetPrefs();
            desc = p.TryGetProperty("episode_desc", out var m) && m.ValueKind == JsonValueKind.Object
                && m.TryGetProperty(OrderKey, out var v) && v.ValueKind == JsonValueKind.True;
        }
        catch (Exception e) { Log.W("source", "读正序倒序偏好失败,按正序: " + e.Message); }
        Dispatcher.UIThread.Post(() =>
        {
            _desc = desc;
            var lines = Lines();
            var last = Mi.Str(_d, "lastLine");
            _line = lines.FirstOrDefault(l => Mi.Str(l, "name") == last) is { ValueKind: JsonValueKind.Object } hit ? Mi.Str(hit, "id")
                : lines.Count > 0 ? Mi.Str(lines[0], "id") : "";
            if (_auto is { LineId.Length: > 0 } a && lines.Any(l => Mi.Str(l, "id") == a.LineId)) _line = a.LineId;
            Render();
            if (_auto is { } ap) AutoStart(ap);
        });
    }

    private string OrderKey => _server + "#" + _itemId;

    private List<JsonElement> Lines()
    {
        var lines = Mi.Arr(_d, "lines");
        // 有季结构时先按季展开成线路(阶段 ① 的 TVBox 源都是线路结构,季是给别的插件的)
        foreach (var s in Mi.Arr(_d, "seasons"))
            foreach (var l in Mi.Arr(s, "lines")) lines.Add(l);
        return lines;
    }

    private List<JsonElement> Episodes(string lineId) =>
        Lines().FirstOrDefault(l => Mi.Str(l, "id") == lineId) is { ValueKind: JsonValueKind.Object } l ? Mi.Arr(l, "episodes") : [];

    private void Render()
    {
        _body.Children.Clear();
        _body.Children.Add(Header());
        var lines = Lines();
        if (lines.Count > 0) _body.Children.Add(LineTabs(lines));
        var eps = Episodes(_line);
        if (eps.Count > 1) _body.Children.Add(EpisodeGrid(eps)); // 只有一集时只有播放按钮(D463)
        var cast = (Mi.Arr(_d, "actors").Select(a => a.GetString() ?? "").Concat(Mi.Arr(_d, "directors").Select(a => a.GetString() ?? ""))).Where(s => s.Length > 0).Distinct().ToList();
        if (cast.Count > 0) _body.Children.Add(Cast(cast));
    }

    private Control Header()
    {
        var title = Mi.Str(_d, "title");
        var info = new StackPanel { Spacing = 10 };
        info.Children.Add(new TextBlock { Text = title, Classes = { "h1" }, TextWrapping = TextWrapping.Wrap });
        var meta = new List<string>();
        if (Mi.YearText(_d) is { Length: > 0 } y) meta.Add(y);
        meta.AddRange(Mi.Arr(_d, "countries").Select(x => x.GetString() ?? ""));
        meta.AddRange(Mi.Arr(_d, "genres").Select(x => x.GetString() ?? ""));
        if (Mi.Str(_d, "remarks") is { Length: > 0 } rm) meta.Add(rm);
        if (meta.Count > 0) info.Children.Add(new TextBlock { Text = string.Join(" · ", meta.Where(m => m.Length > 0)), Classes = { "dim" }, TextWrapping = TextWrapping.Wrap });
        var ratings = Mi.Arr(_d, "ratings").Select(r => $"{Mi.Str(r, "source")} {Mi.Num(r, "value"):0.0}").ToList();
        if (ratings.Count > 0) info.Children.Add(new TextBlock { Text = string.Join("   ", ratings), Foreground = Tok.Of("Warn") });
        if (Mi.Str(_d, "overview") is { Length: > 0 } ov)
            info.Children.Add(new TextBlock { Text = ov, TextWrapping = TextWrapping.Wrap, MaxLines = 6, TextTrimming = TextTrimming.CharacterEllipsis, Foreground = Tok.Of("Ink2"), MaxWidth = 820, HorizontalAlignment = HorizontalAlignment.Left });
        info.Children.Add(Actions());

        var backdrop = Mi.Img(_d, "backdrop");
        if (backdrop.Length > 0)
        {
            // 有横版背景图:和 Emby 详情同一种背景布局(D337)
            var img = new Image { Stretch = Stretch.UniformToFill, Opacity = 0, Classes = { "art" } };
            _ = Fill(img, backdrop, 360);
            return new Panel
            {
                Children =
                {
                    new Border { Height = 360, CornerRadius = new CornerRadius(10), ClipToBounds = true, Child = img },
                    new Border { Height = 360, CornerRadius = new CornerRadius(10), Background = new SolidColorBrush(Color.Parse("#99000000")) },
                    new Border { Padding = new Thickness(26), VerticalAlignment = VerticalAlignment.Bottom, Child = info },
                },
            };
        }
        var poster = new Image { Stretch = Stretch.UniformToFill, Opacity = 0, Classes = { "art" } };
        if (Mi.Img(_d, "poster") is { Length: > 0 } pu) _ = Fill(poster, pu, 300);
        var left = new Border { Width = 200, Height = 300, CornerRadius = new CornerRadius(10), ClipToBounds = true, Background = Tok.Of("PanelAlt"), Child = poster, VerticalAlignment = VerticalAlignment.Top };
        var grid = new Grid { ColumnDefinitions = new ColumnDefinitions("Auto,*") };
        info.Margin = new Thickness(26, 0, 0, 0);
        Grid.SetColumn(info, 1);
        grid.Children.Add(left);
        grid.Children.Add(info);
        return grid;
    }

    private Control Actions()
    {
        var row = new WrapPanel { ItemSpacing = 10, LineSpacing = 10 };
        var eps = Episodes(_line);
        var play = new Button { Classes = { "primary" }, Content = "▶ 播放", IsEnabled = eps.Count > 0 };
        play.Click += (_, _) => { if (eps.Count > 0) Play(_line, eps[0], 0); };
        row.Children.Add(play);
        var fav = new Button { Classes = { "ghost" }, Content = "☆ 收藏" };
        _ = SyncFav(fav);
        fav.Click += async (_, _) =>
        {
            var want = !fav.Classes.Contains("on");
            try
            {
                await _core.SourceSetFavorite(new { server_id = _server, item = _d, favorite = want });
                SetFav(fav, want);
                Toast.Show(want ? "已收藏" : "已取消收藏");
            }
            catch (Exception e) { Toast.Error("收藏失败:" + LibraryPage.Advice(e)); }
        };
        row.Children.Add(fav);
        var sw = new Button { Classes = { "ghost" }, Content = "⇄ 换源" };
        sw.Click += (_, _) => OpenSwitch(0);
        row.Children.Add(sw);
        return row;
    }

    private async Task SyncFav(Button b)
    {
        try
        {
            var r = await _core.SourceIsFavorite(new { server_id = _server, item_id = _itemId });
            Dispatcher.UIThread.Post(() => SetFav(b, r.ValueKind == JsonValueKind.True));
        }
        catch (Exception e) { Log.W("source", "查收藏状态失败: " + e.Message); }
    }

    private static void SetFav(Button b, bool on)
    {
        b.Classes.Set("on", on);
        b.Content = on ? "★ 已收藏" : "☆ 收藏";
    }

    private Control LineTabs(List<JsonElement> lines)
    {
        var row = new WrapPanel { ItemSpacing = 10, LineSpacing = 10 };
        foreach (var l in lines)
        {
            var id = Mi.Str(l, "id");
            var b = new Button { Classes = { "chip" }, Content = $"{Mi.Str(l, "name")} · {Mi.Arr(l, "episodes").Count}" };
            b.Classes.Set("on", id == _line);
            b.Click += (_, _) => { _line = id; _page = 0; Render(); };
            row.Children.Add(b);
        }
        return new StackPanel { Spacing = 10, Children = { H2("线路"), row } };
    }

    private Control EpisodeGrid(List<JsonElement> eps)
    {
        var col = new StackPanel { Spacing = 10 };
        var bar = new WrapPanel { ItemSpacing = 10, LineSpacing = 10 };
        bar.Children.Add(H2("选集"));
        var order = new Button { Classes = { "chip" }, Content = _desc ? "倒序" : "正序" };
        order.Click += (_, _) => { _desc = !_desc; _page = 0; _ = SaveOrder(); Render(); };
        bar.Children.Add(order);
        var list = _desc ? Enumerable.Reverse(eps).ToList() : eps;
        var pages = (list.Count + EpPage - 1) / EpPage;
        if (pages > 1)
            for (var p = 0; p < pages; p++)
            {
                var page = p;
                var lo = p * EpPage + 1;
                var hi = Math.Min((p + 1) * EpPage, list.Count);
                var b = new Button { Classes = { "chip" }, Content = $"{lo}-{hi}" };
                b.Classes.Set("on", p == _page);
                b.Click += (_, _) => { _page = page; Render(); };
                bar.Children.Add(b);
            }
        col.Children.Add(bar);
        var grid = new WrapPanel { ItemSpacing = 10, LineSpacing = 10 };
        foreach (var e in list.Skip(_page * EpPage).Take(EpPage))
        {
            var ep = e;
            var label = Mi.Str(ep, "label") is { Length: > 0 } lb ? lb : Mi.Str(ep, "name");
            var b = new Button { Classes = { "chip" }, Content = label, MinWidth = 96, HorizontalContentAlignment = HorizontalAlignment.Center };
            b.Click += (_, _) => Play(_line, ep, 0);
            grid.Children.Add(b);
        }
        col.Children.Add(grid);
        return col;
    }

    private async Task SaveOrder()
    {
        try
        {
            var p = await _core.PrefsGetPrefs();
            var map = new Dictionary<string, bool>();
            if (p.TryGetProperty("episode_desc", out var m) && m.ValueKind == JsonValueKind.Object)
                foreach (var kv in m.EnumerateObject()) map[kv.Name] = kv.Value.ValueKind == JsonValueKind.True;
            if (_desc) map[OrderKey] = true; else map.Remove(OrderKey);
            await _core.PrefsSetPrefs(new { episode_desc = map });
        }
        catch (Exception e) { Toast.Error("正序倒序没记住:" + LibraryPage.Advice(e)); }
    }

    private Control Cast(List<string> names)
    {
        var hasPerson = _caps.ValueKind == JsonValueKind.Object && _caps.TryGetProperty("person", out var p) && p.ValueKind == JsonValueKind.True;
        var hasSearch = _caps.ValueKind == JsonValueKind.Object && _caps.TryGetProperty("search", out var s) && s.ValueKind == JsonValueKind.True;
        var row = new WrapPanel();
        foreach (var n in names.Take(30))
        {
            var name = n;
            if (hasPerson || hasSearch)
                row.Children.Add(Chips.Clickable(name, () => Nav.Push(new SourceListPage(_core, _server, name, hasPerson ? "person" : "search"))));
            else row.Children.Add(Chips.Plain(name)); // 源不支持演员页也不支持搜索:纯文字(D340)
        }
        return new StackPanel { Spacing = 10, Children = { H2("演职人员"), row } };
    }

    private void Play(string lineId, JsonElement ep, double pos)
    {
        var epId = Mi.Str(ep, "id");
        var label = Mi.Str(ep, "label") is { Length: > 0 } lb ? lb : Mi.Str(ep, "name");
        var sp = new SourcePlay(_server, _d, lineId, epId, label);
        Nav.Push(new PlayerPage(_core, epId, $"{Mi.Str(_d, "title")} {label}".Trim(), pos, src: sp));
    }

    private void AutoStart(AutoPlay a)
    {
        var eps = Episodes(_line);
        var ep = eps.FirstOrDefault(e => Mi.Str(e, "id") == a.EpisodeId);
        if (ep.ValueKind != JsonValueKind.Object && a.EpisodeIndex > 0)
            ep = eps.FirstOrDefault(e => (int)Mi.Num(e, "index") == a.EpisodeIndex);
        if (ep.ValueKind != JsonValueKind.Object && eps.Count == 1) ep = eps[0];
        if (ep.ValueKind != JsonValueKind.Object)
        {
            Toast.Show(a.EpisodeIndex > 0 ? $"这个源没有第 {a.EpisodeIndex} 集,请手动选" : "上次看的那一集不在了,请手动选");
            return;
        }
        Play(_line, ep, a.Pos);
    }

    /// <summary>换源三层(D232~D236):从详情页进来时位置是 0,播放页进来时带进度。</summary>
    public void OpenSwitch(double pos, int episodeIndex = 0) =>
        SwitchSource.Show(this, _core, _server, _d, _line, episodeIndex, pos);

    private async Task Fill(Image target, string url, int h)
    {
        var bmp = await Images.LoadAsync(_core, url, h);
        if (bmp is null) return;
        Dispatcher.UIThread.Post(() => { target.Source = bmp; target.Opacity = 1; });
    }
}

/// <summary>演员页 / 按名字在源内搜(D168 D340)。</summary>
public sealed class SourceListPage : PageBase
{
    public SourceListPage(CoreClient core, string serverId, string name, string mode)
    {
        var grid = new WrapPanel { ItemSpacing = 18, LineSpacing = 26 };
        var status = Dim("加载中…");
        var back = new Button { Classes = { "ghost" }, Content = "← 返回", HorizontalAlignment = HorizontalAlignment.Left };
        back.Click += (_, _) => Nav.Back();
        Content = Scrolled(new StackPanel { Spacing = 14, Children = { back, H1(name), status, grid } });
        _ = Load();

        async Task Load()
        {
            try
            {
                var r = mode == "person"
                    ? await core.SourcePerson(new { server_id = serverId, name })
                    : await core.SourceSearchItems(new { server_id = serverId, keyword = name });
                var items = Mi.Arr(r, "items");
                Dispatcher.UIThread.Post(() =>
                {
                    status.Text = items.Count == 0 ? "没有找到相关内容。" : "";
                    foreach (var it in items) grid.Children.Add(new SourceCard(core, it, SourceNav.OpenDetail(core, serverId)));
                });
            }
            catch (Exception e) { Dispatcher.UIThread.Post(() => status.Text = LibraryPage.Advice(e)); }
        }
    }
}

/// <summary>
/// 换源(D232~D236 D259 D261 D525):三层 ① 本源其它线路 ② 允许聚合的数据源 ③ 允许聚合的 Emby,
/// 逐源出结果;层内按「同一部 / 可能是 / 不像(折叠)」,档内按接口耗时。跳过去带上进度。
/// </summary>
public static class SwitchSource
{
    private static readonly string[] LayerNames = ["", "本源其它线路", "其它数据源", "Emby 服务器"];

    /// <summary>Emby 的片换到数据源看(D525):跳转,不是把数据源塞进 Emby 详情当线路。按剧名 + 集序号找。</summary>
    public static async Task ShowForEmby(Visual anchor, CoreClient core, string itemId, double pos)
    {
        if (Nav.Session is not { } s) return;
        try
        {
            var d = await core.EmbyItemDetail(new { s.server, s.token, s.user_id, s.device_id, item_id = itemId, with_children = false });
            var title = Mi.Str(d, "series_name") is { Length: > 0 } sn ? sn : Mi.Str(d, "name");
            var item = JsonSerializer.SerializeToElement(new { id = itemId, title, year = Mi.Num(d, "year") });
            Show(anchor, core, s.server, item, "", (int)Mi.Num(d, "episode_no"), pos);
        }
        catch (Exception e) { Toast.Error("换源失败:" + LibraryPage.Advice(e)); }
    }

    public static void Show(Visual anchor, CoreClient core, string serverId, JsonElement item, string lineId, int episodeIndex, double pos)
    {
        var list = new StackPanel { Spacing = 14, Width = 560 };
        var status = new TextBlock { Text = "正在各个源里找…", Classes = { "dim" } };
        var cands = new List<JsonElement>();
        var scroll = new ScrollViewer { MaxHeight = 520, Content = list };
        var body = new StackPanel { Spacing = 10, Children = { status, scroll } };
        Window? dlg = null;

        void Redraw()
        {
            list.Children.Clear();
            foreach (var layer in new[] { 1, 2, 3 })
            {
                var ofLayer = cands.Where(c => (int)Mi.Num(c, "layer") == layer).OrderBy(c => Mi.Num(c, "tier")).ThenBy(c => Mi.Num(c, "ms")).ToList();
                if (ofLayer.Count == 0) continue;
                var sec = new StackPanel { Spacing = 6 };
                sec.Children.Add(new TextBlock { Text = LayerNames[layer], Classes = { "h2" } });
                var folded = ofLayer.Where(c => (int)Mi.Num(c, "tier") == 2).ToList();
                foreach (var c in ofLayer.Where(c => (int)Mi.Num(c, "tier") < 2)) sec.Children.Add(Row(c));
                if (folded.Count > 0)
                {
                    var more = new Expander { Header = $"不像的 {folded.Count} 个", Content = new StackPanel { Spacing = 6 } };
                    foreach (var c in folded) ((StackPanel)more.Content!).Children.Add(Row(c));
                    sec.Children.Add(more);
                }
                list.Children.Add(sec);
            }
        }

        Control Row(JsonElement c)
        {
            var layer = (int)Mi.Num(c, "layer");
            var tier = (int)Mi.Num(c, "tier");
            var tierText = tier == 0 ? "同一部" : tier == 1 ? "可能是" : "不像";
            string title, where;
            if (layer == 1) { title = Mi.Str(c, "line_name"); where = "同一部 · 另一条线路"; }
            else if (layer == 3) { var e = c.GetProperty("emby_item"); title = Mi.Str(e, "name"); where = $"{Mi.Str(c, "server_name")} · {tierText}"; }
            else { var it = c.GetProperty("item"); title = $"{Mi.Str(it, "title")} {Mi.YearText(it)}".Trim(); where = $"{Mi.Str(c, "server_name")} · {tierText} · {Mi.Num(c, "ms"):0}ms"; }
            var b = new Button
            {
                Classes = { "ghost" }, HorizontalAlignment = HorizontalAlignment.Stretch, HorizontalContentAlignment = HorizontalAlignment.Left,
                Content = new StackPanel { Spacing = 2, Children = { new TextBlock { Text = title, FontSize = 14 }, new TextBlock { Text = where, Classes = { "dim" }, FontSize = 12 } } },
            };
            b.Click += (_, _) => { dlg?.Close(); Pick(core, serverId, item, c, episodeIndex, pos); };
            return b;
        }

        _ = Task.Run(async () =>
        {
            try
            {
                var all = await core.CallStreamAsync("source.switchCandidates", new
                {
                    server_id = serverId, item, line_id = lineId, episode_index = episodeIndex,
                    title = Mi.Str(item, "title"), year = Mi.Num(item, "year"),
                }, part => Dispatcher.UIThread.Post(() =>
                {
                    if (part.ValueKind == JsonValueKind.Array) cands.AddRange(part.EnumerateArray());
                    Redraw();
                }));
                Dispatcher.UIThread.Post(() =>
                {
                    cands.Clear();
                    if (all.ValueKind == JsonValueKind.Array) cands.AddRange(all.EnumerateArray());
                    Redraw();
                    status.Text = cands.Count == 0 ? "别的源里都没找到这部片。可以在设置里给更多源打开「允许聚合」。" : $"找到 {cands.Count} 个候选";
                });
            }
            catch (Exception e) { Dispatcher.UIThread.Post(() => status.Text = "换源失败:" + LibraryPage.Advice(e)); }
        });
        dlg = new Window
        {
            Title = "换源", SizeToContent = SizeToContent.WidthAndHeight, CanResize = false,
            WindowStartupLocation = WindowStartupLocation.CenterOwner,
            Content = new Border { Padding = new Thickness(18), Child = body },
        };
        if (TopLevel.GetTopLevel(anchor) is Window owner) _ = dlg.ShowDialog(owner);
    }

    private static void Pick(CoreClient core, string serverId, JsonElement item, JsonElement c, int episodeIndex, double pos)
    {
        var layer = (int)Mi.Num(c, "layer");
        var target = Mi.Str(c, "server_id");
        if (layer == 1)
        {
            var sp = new SourcePlay(serverId, item, Mi.Str(c, "line_id"), Mi.Str(c, "episode_id"), "");
            Nav.Replace(new PlayerPage(core, sp.EpisodeId, Mi.Str(item, "title"), pos, src: sp));
            return;
        }
        if (layer == 3)
        {
            var e = c.GetProperty("emby_item");
            Nav.Push(new DetailPage(core, target, Mi.Str(e, "id")), () => new DetailPage(core, target, Mi.Str(e, "id")));
            return;
        }
        var it = c.GetProperty("item");
        var id = Mi.Str(it, "id");
        // 换源链路记下来:全局观看历史里同一部片按它合并,取进度最远的(D431)
        _ = core.SourceLinkSwitch(new { from_server = serverId, from_item = Mi.Str(item, "id"), to_server = target, to_item = id });
        Nav.Push(new SourceDetailPage(core, target, id, new SourceDetailPage.AutoPlay("", "", pos, episodeIndex)),
            () => new SourceDetailPage(core, target, id));
    }
}
