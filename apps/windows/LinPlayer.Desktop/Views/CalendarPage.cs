using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.Json;
using System.Threading.Tasks;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Threading;
using LinPlayer.Core;
using LinPlayer.Desktop.Core;

namespace LinPlayer.Desktop.Views;

/// <summary>
/// 追剧日历(<c>UI_PC.md</c> §7.12)。上面一条「周几」日期条,下面是选中那天的海报墙。
///
/// <para>2026-09-18 重做(用户:「重做 排行榜 和 追剧日历 样式」)。原来是七列 320 宽的看板
/// 横着滚:一屏只放得下三列,每列里一张小封面配一大块空白。现在一天占满整个宽度,
/// 周几靠上面那条切 —— 打开日历最常见的目的是看「今天更新了什么」。</para>
/// <para>仍然成立的规矩:今天居中(日期条从今天往前三天排起)【用户定】;标题不截成「…」;
/// 封面 <c>Uniform</c> 不裁(源站给的是 2:3 竖版);不上背景模糊;「今天是周几」按 JST。</para>
/// </summary>
public sealed class CalendarPage : PageBase
{
    private readonly CoreClient _core;
    private readonly StackPanel _days = new() { Orientation = Orientation.Horizontal, Spacing = 10 };
    private readonly WrapPanel _wall = new() { ItemSpacing = 18, LineSpacing = 26 };
    private readonly TextBlock _status = Dim("");
    private readonly List<Button> _sourceTabs = [];
    private readonly Button _onlyMine = new() { Classes = { "chip" }, Content = "只看我追的" };
    private string _source = "bangumi";
    private List<JsonElement> _items = [];
    // 放送条目下标 → 当前 Emby 服务器上的对应物(D366「已入库 / 可播」)
    private Dictionary<int, JsonElement> _lib = [];
    private int _picked;

    private static readonly string[] WeekNames = ["", "周一", "周二", "周三", "周四", "周五", "周六", "周日"];

    public CalendarPage(CoreClient core)
    {
        _core = core;
        _picked = TodayWeekdayJst();

        var sources = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 6 };
        // 默认 Bangumi:公开放送表免登录就能返回整张表
        foreach (var (k, label) in new[] { ("bangumi", "番剧 · Bangumi"), ("trakt", "剧集 · Trakt") })
        {
            var b = new Button { Classes = { "chip" }, Content = label };
            b.Classes.Set("on", k == _source);
            b.Click += (_, _) =>
            {
                if (_source == k) return;
                _source = k;
                foreach (var t in _sourceTabs) t.Classes.Set("on", t == b);
                _ = Load();
            };
            _sourceTabs.Add(b);
            sources.Children.Add(b);
        }
        _onlyMine.Click += (_, _) =>
        {
            _onlyMine.Classes.Set("on", !_onlyMine.Classes.Contains("on"));
            _ = Load();
        };

        var bar = new DockPanel { LastChildFill = false };
        DockPanel.SetDock(_onlyMine, Dock.Right);
        bar.Children.Add(_onlyMine);
        bar.Children.Add(sources);

        Content = Scrolled(new StackPanel
        {
            Spacing = 18,
            Children = { H1("追剧日历"), bar, _days, _status, _wall },
        });
        // 空的状态行不占位:Spacing 会把它算成一行,海报墙上方平白多一道空隙
        _status.PropertyChanged += (_, e) =>
        {
            if (e.Property == TextBlock.TextProperty) _status.IsVisible = !string.IsNullOrEmpty(_status.Text);
        };

        _ = Load();
    }

    private bool OnlyMine => _onlyMine.Classes.Contains("on");

    private async Task Load()
    {
        _wall.Children.Clear();
        _days.Children.Clear();
        _status.Text = "加载中…";
        var onlyMine = OnlyMine;

        JsonElement arr;
        try
        {
            arr = _source == "trakt"
                ? await _core.SyncTraktCalendar(new { only_mine = onlyMine })
                : await _core.SyncBangumiCalendar(new { only_mine = onlyMine });
        }
        catch (Exception e)
        {
            // 「还没连接」是**可操作**的失败:说清楚要去哪儿连,别只说一句失败
            _status.Text = LibraryPage.Advice(e);
            return;
        }

        var items = arr.ValueKind == JsonValueKind.Array ? arr.EnumerateArray().ToList() : [];
        Dispatcher.UIThread.Post(() => { _items = items; _lib = []; Render(); });
        _ = LoadLibrary(items);
    }

    /// <summary>放送表先画出来,再去当前 Emby 服务器对「入库没有」:对得慢不该拖住整页。</summary>
    private async Task LoadLibrary(List<JsonElement> items)
    {
        var q = items.Select((e, i) => new
        {
            key = i.ToString(), title = Str(e, "title"),
            tmdb_id = e.TryGetProperty("tmdb_id", out var t) && t.ValueKind == JsonValueKind.Number ? t.GetInt64() : (long?)null,
            season = e.TryGetProperty("season", out var se) && se.ValueKind == JsonValueKind.Number ? se.GetInt32() : (int?)null,
            episode = e.TryGetProperty("episode", out var ep) && ep.ValueKind == JsonValueKind.Number ? ep.GetInt32() : (int?)null,
        }).ToArray();
        JsonElement hits;
        try { hits = await _core.SyncCalendarLibrary(new { entries = q }); }
        catch (Exception e) { Log.W("calendar", "对媒体库失败(不影响放送表): " + e.Message); return; }
        var map = new Dictionary<int, JsonElement>();
        if (hits.ValueKind == JsonValueKind.Object)
            foreach (var p in hits.EnumerateObject())
                if (int.TryParse(p.Name, out var i)) map[i] = p.Value;
        Dispatcher.UIThread.Post(() =>
        {
            if (!ReferenceEquals(items, _items)) return; // 期间换了来源 / 过滤:结果作废
            _lib = map;
            Render();
        });
    }

    private void Render()
    {
        _days.Children.Clear();
        _wall.Children.Clear();
        if (_items.Count == 0)
        {
            _status.Text = OnlyMine
                ? "你追的番里,这一季没有正在放送的。"
                : "放送表是空的(上游没有返回条目)。";
            return;
        }
        _status.Text = "";

        var today = TodayWeekdayJst();
        // **今天居中**:从今天往前三天排起,七天里今天落在第四格
        for (var i = 0; i < 7; i++)
        {
            var wd = ((today - 4 + i + 7) % 7) + 1;
            _days.Children.Add(DayTab(wd, wd == today, _items.Count(e => Weekday(e) == wd)));
        }

        var ofDay = _items.Where(e => Weekday(e) == _picked).OrderBy(Time).ToList();
        if (ofDay.Count == 0) { _status.Text = "这天没有更新。"; return; }
        foreach (var e in ofDay) _wall.Children.Add(Poster(e, _items.IndexOf(e)));
    }

    /// <summary>日期条上的一格:周几 + 这天几部。今天那格写「今天」,选中那格上强调色。</summary>
    private Control DayTab(int weekday, bool isToday, int count)
    {
        var b = new Button
        {
            Classes = { "chip" }, Width = 96, Padding = new Thickness(0, 10),
            HorizontalContentAlignment = HorizontalAlignment.Center,
            Content = new StackPanel
            {
                Spacing = 2, HorizontalAlignment = HorizontalAlignment.Center,
                Children =
                {
                    new TextBlock
                    {
                        Text = isToday ? "今天" : WeekNames[weekday], FontSize = 15, FontWeight = FontWeight.SemiBold,
                        HorizontalAlignment = HorizontalAlignment.Center,
                    },
                    new TextBlock
                    {
                        Text = isToday ? $"{WeekNames[weekday]} · {count} 部" : $"{count} 部", FontSize = 11.5,
                        Opacity = 0.7, HorizontalAlignment = HorizontalAlignment.Center,
                    },
                },
            },
        };
        b.Classes.Set("on", weekday == _picked);
        b.Click += (_, _) => { _picked = weekday; Render(); };
        return b;
    }

    /// <summary>海报一张:2:3 封面(Uniform 不裁)+ 完整标题 + 评分 · 集数。更新时刻压在封面左下。</summary>
    private Control Poster(JsonElement e, int index)
    {
        const double w = 160, h = w * 3 / 2;
        var title = Str(e, "title");
        var img = new Image { Stretch = Stretch.Uniform, Opacity = 0, Classes = { "art" } };
        var layers = new Panel
        {
            Children =
            {
                new TextBlock
                {
                    Text = title, FontSize = 12, Margin = new Thickness(10), Foreground = Tok.Of("Ink3"),
                    TextWrapping = TextWrapping.Wrap, TextAlignment = TextAlignment.Center,
                    VerticalAlignment = VerticalAlignment.Center, HorizontalAlignment = HorizontalAlignment.Center,
                },
                img,
            },
        };
        if (Str(e, "image_url") is { Length: > 0 } url) _ = Fill(img, url);

        // 扫一排海报时最先要找的是「几点更新」,所以时刻压在封面上,不和评分挤在小字里
        if (Time(e) is { Length: > 0 } t)
        {
            layers.Children.Add(new Border
            {
                HorizontalAlignment = HorizontalAlignment.Left, VerticalAlignment = VerticalAlignment.Bottom,
                Margin = new Thickness(6), Padding = new Thickness(6, 2), CornerRadius = new CornerRadius(6),
                Background = new SolidColorBrush(Color.Parse("#cc000000")),
                Child = new TextBlock { Text = t, FontSize = 12, FontWeight = FontWeight.SemiBold, Foreground = Brushes.White },
            });
        }

        // 已在 Emby 媒体库里:这一集入库了标「可播」点了直接播,只有剧入库标「已入库」点了进详情(D366)
        Action? open = null;
        if (_lib.TryGetValue(index, out var hit) && Nav.Session is { } s)
        {
            var playable = hit.TryGetProperty("playable", out var pl) && pl.ValueKind == JsonValueKind.True;
            var itemId = Str(hit, "item_id");
            var seriesId = Str(hit, "series_id");
            layers.Children.Add(new Border
            {
                HorizontalAlignment = HorizontalAlignment.Left, VerticalAlignment = VerticalAlignment.Top,
                Margin = new Thickness(6), Padding = new Thickness(6, 2), CornerRadius = new CornerRadius(999),
                Background = playable ? Tok.Of("Accent") : new SolidColorBrush(Color.Parse("#cc000000")),
                Child = new TextBlock
                {
                    Text = playable ? "可播" : "已入库", FontSize = 11.5, FontWeight = FontWeight.SemiBold,
                    Foreground = playable ? Tok.Of("AccentInk") : Brushes.White,
                },
            });
            open = playable
                ? () => Nav.Push(new PlayerPage(_core, itemId, title, 0, serverId: s.server))
                : () => Nav.Push(new DetailPage(_core, s.server, seriesId), () => new DetailPage(_core, s.server, seriesId));
        }

        var bits = new List<string>();
        if (e.TryGetProperty("rating", out var r) && r.ValueKind == JsonValueKind.Number && r.GetDouble() > 0)
            bits.Add("★ " + r.GetDouble().ToString("0.0"));
        if (Str(e, "subtitle") is { Length: > 0 } sub) bits.Add(sub);

        var cover = new Border
        {
            Width = w, Height = h, CornerRadius = new CornerRadius(10), ClipToBounds = true,
            Background = Tok.Of("PanelAlt"), Child = layers,
        };
        if (open is not null)
        {
            cover.Cursor = new Cursor(StandardCursorType.Hand);
            cover.PointerPressed += (_, ev) => { if (ev.GetCurrentPoint(cover).Properties.IsLeftButtonPressed) open(); };
        }
        return new StackPanel
        {
            Width = w, Spacing = 6,
            Children =
            {
                cover,
                // 标题**不许截成「…」** —— 截了就是显示不全。完整换行。
                new TextBlock { Text = title, FontSize = 13, FontWeight = FontWeight.Medium, TextWrapping = TextWrapping.Wrap },
                new TextBlock
                {
                    Text = string.Join(" · ", bits), FontSize = 11.5, Classes = { "dim" },
                    TextWrapping = TextWrapping.Wrap, IsVisible = bits.Count > 0,
                },
            },
        };
    }

    private async Task Fill(Image target, string url)
    {
        var bmp = await Images.LoadAsync(_core, url, 240);
        if (bmp is null) return;
        Dispatcher.UIThread.Post(() => { target.Source = bmp; target.Opacity = 1; });
    }

    /// <summary>
    /// 一条的放送时刻,换算成**本地** HH:MM。取不到就空串(不编时间)。
    ///
    /// <para>两个来源两个字段:Trakt 给精确的 <c>air_date</c>,
    /// Bangumi 给每周固定的 <c>broadcast_at</c>。</para>
    /// </summary>
    private static string Time(JsonElement e)
    {
        var iso = Str(e, "air_date");
        if (iso.Length == 0) iso = Str(e, "broadcast_at");
        if (iso.Length == 0) return "";
        return DateTimeOffset.TryParse(iso, out var dt) ? dt.ToLocalTime().ToString("HH:mm") : "";
    }

    private static int Weekday(JsonElement e)
    {
        if (e.TryGetProperty("weekday", out var w) && w.ValueKind == JsonValueKind.Number)
            return w.GetInt32();
        // Trakt 那边没有 weekday,从 air_date 现算
        var iso = Str(e, "air_date");
        if (iso.Length > 0 && DateTimeOffset.TryParse(iso, out var dt))
            return ((int)dt.ToLocalTime().DayOfWeek + 6) % 7 + 1; // 周日=7
        return 0;
    }

    /// <summary>
    /// 今天是周几(1=周一…7=周日),**按上游时区 JST**。
    ///
    /// <para>按本地时区判的话,国内用户在每天 23:00~01:00 之间看到的「今天」是错的
    /// —— 番剧放送表是按日本时间排的(<c>SPEC.md</c> §14.4)。</para>
    /// </summary>
    private static int TodayWeekdayJst()
    {
        var jst = DateTimeOffset.UtcNow.ToOffset(TimeSpan.FromHours(9));
        return ((int)jst.DayOfWeek + 6) % 7 + 1;
    }

    private static string Str(JsonElement e, string k) =>
        e.ValueKind == JsonValueKind.Object && e.TryGetProperty(k, out var v) && v.ValueKind == JsonValueKind.String
            ? v.GetString() ?? "" : "";
}
