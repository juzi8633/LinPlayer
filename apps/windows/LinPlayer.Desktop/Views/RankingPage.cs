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
/// 排行榜(<c>UI_PC.md</c> §7.13)。动漫走弹弹Play,影视走 TMDB。
///
/// <para>2026-09-18 重做(用户:「重做 排行榜 和 追剧日历 样式」):两个下拉框换成两排 chip ——
/// 分类一共十来个,藏在下拉里就得点开才知道有什么;前三名单独一排大卡,其余海报墙。</para>
/// <para>这一页最要紧的仍然是错误怎么显示。核心层已经把「缺凭据 / 429 / 密钥无效 /
/// 分类下线」分别说清楚了,UI 原样透出来 —— 吞成「暂无数据」的话几种成因又变回一个样子。</para>
/// </summary>
public sealed class RankingPage : PageBase
{
    private readonly CoreClient _core;
    private readonly StackPanel _groups = new() { Orientation = Orientation.Horizontal, Spacing = 6 };
    private readonly WrapPanel _cats = new() { ItemSpacing = 6, LineSpacing = 6 };
    private readonly Button _refresh = new() { Classes = { "ghost" }, Content = "↻ 刷新" };
    // 前三名三等分一行:固定宽度的话窄窗口下第三张被挤出去
    private readonly Grid _podium = new() { ColumnDefinitions = new ColumnDefinitions("*,*,*") };
    private readonly WrapPanel _grid = new() { ItemSpacing = 18, LineSpacing = 26 };
    private readonly TextBlock _msg = Dim("");

    private sealed record Cat(string Id, string Group, string Label, string Source);

    private List<Cat> _all = [];
    private string _group = "";
    private string _curCat = "";

    public RankingPage(CoreClient core)
    {
        _core = core;
        // 手动刷新要**绕过 6 小时缓存**,否则点了没反应
        _refresh.Click += (_, _) => { if (_curCat.Length > 0) _ = Fetch(_curCat, force: true); };
        _refresh.IsVisible = false;

        var bar = new DockPanel { LastChildFill = false };
        DockPanel.SetDock(_refresh, Dock.Right);
        bar.Children.Add(_refresh);
        bar.Children.Add(_groups);

        Content = Scrolled(new StackPanel
        {
            Spacing = 18,
            Children = { H1("排行榜"), bar, _cats, _msg, _podium, _grid },
        });

        _ = Load();
    }

    private async Task Load()
    {
        _msg.Text = "加载中…";
        List<Cat> all;
        try
        {
            var arr = SelfCheckFake ? FakeCategories() : await _core.EmbyRankingCategories();
            all = arr.ValueKind == JsonValueKind.Array
                ? arr.EnumerateArray()
                    .Select(e => new Cat(Str(e, "id"), Str(e, "group"), Str(e, "label"), Str(e, "source")))
                    .ToList()
                : [];
        }
        catch (Exception e)
        {
            _msg.Text = "拿不到榜单分类:" + LibraryPage.Advice(e);
            return;
        }

        _all = all;
        if (_all.Count == 0)
        {
            // 空表不是「出错了」,是**这个构建没带凭据**。说清楚,别画空页面。
            _msg.Text = "这个版本没有带排行榜的凭据,所以排行榜不可用。\n" +
                        "官方发行包里是带的;自己从源码构建时,需要在构建环境里提供 " +
                        "DANDANPLAY_APP_ID / DANDANPLAY_APP_SECRET / TMDB_API_KEY。";
            return;
        }
        _msg.Text = "";
        _msg.IsVisible = false;
        _refresh.IsVisible = true;

        _groups.Children.Clear();
        // 只列**真的有分类**的组:亮一个点进去是空的页签,比不亮更让人困惑
        foreach (var (key, label) in new[] { ("anime", "动漫"), ("movie", "电影"), ("tv", "剧集") })
        {
            if (!_all.Any(c => c.Group == key)) continue;
            var b = new Button { Classes = { "chip" }, Content = label, Tag = key, FontSize = 14, Padding = new Thickness(18, 10) };
            b.Click += (_, _) => PickGroup(key);
            _groups.Children.Add(b);
        }
        if (_groups.Children.FirstOrDefault() is Button { Tag: string first }) PickGroup(first);
    }

    private void PickGroup(string g)
    {
        _group = g;
        foreach (var b in _groups.Children.OfType<Button>()) b.Classes.Set("on", (string?)b.Tag == g);
        _cats.Children.Clear();
        foreach (var c in _all.Where(x => x.Group == g))
        {
            var b = new Button { Classes = { "chip" }, Content = c.Label, Tag = c.Id };
            b.Click += (_, _) => PickCat(c.Id);
            _cats.Children.Add(b);
        }
        if (_cats.Children.FirstOrDefault() is Button { Tag: string id }) PickCat(id);
    }

    private void PickCat(string id)
    {
        foreach (var b in _cats.Children.OfType<Button>()) b.Classes.Set("on", (string?)b.Tag == id);
        _ = Fetch(id, force: false);
    }

    private async Task Fetch(string catId, bool force)
    {
        _curCat = catId;
        _podium.Children.Clear();
        _grid.Children.Clear();
        _msg.Text = "加载中…";
        _msg.IsVisible = true;

        JsonElement arr;
        try
        {
            arr = SelfCheckFake ? FakeItems(catId)
                : await _core.EmbyRankingFetch(new { category_id = catId, force_refresh = force });
        }
        catch (Exception e)
        {
            // 原样显示核心层给的那句话,见类注释
            _msg.Text = LibraryPage.Advice(e);
            return;
        }

        // 换分类比请求快得多:回来时用户可能已经点到别的榜了,别把旧结果画上去
        if (_curCat != catId) return;

        var list = arr.ValueKind == JsonValueKind.Array ? arr.EnumerateArray().ToList() : [];
        foreach (var (e, i) in list.Take(3).Select((e, i) => (e, i)))
        {
            var card = new PodiumCard(_core, e) { Margin = new Thickness(i == 0 ? 0 : 9, 0, i == 2 ? 0 : 9, 0) };
            Grid.SetColumn(card, i);
            _podium.Children.Add(card);
        }
        foreach (var e in list.Skip(3)) _grid.Children.Add(new RankCard(_core, e));
        _podium.IsVisible = list.Count > 0;
        _msg.Text = list.Count == 0 ? "这个榜当前是空的(上游没有返回条目)。" : "";
        _msg.IsVisible = _msg.Text != "";
    }

    /// <summary>自检用:落到指定分组(anime / movie / tv)。分类清单是异步来的,所以要等。</summary>
    internal void SelfCheckGroup(string group) => _ = SelectGroupWhenReady(group);

    private async Task SelectGroupWhenReady(string group)
    {
        for (var i = 0; i < 40 && _all.Count == 0; i++) await Task.Delay(100);
        if (_all.Any(c => c.Group == group)) PickGroup(group);
    }

    /// <summary>
    /// 自检:<c>LP_SELFCHECK_RANKFAKE=1</c> 用一份假榜单画这一页。本地构建不带弹弹 / TMDB 凭据,
    /// 真榜单拉不到 —— 不给假数据的话这一页的版式在本地永远看不到。
    /// </summary>
    private static bool SelfCheckFake => Environment.GetEnvironmentVariable("LP_SELFCHECK_RANKFAKE") == "1";

    private static JsonElement FakeCategories() => JsonDocument.Parse("""
        [{"id":"a1","group":"anime","label":"本季热门"},{"id":"a2","group":"anime","label":"周榜"},
         {"id":"a3","group":"anime","label":"历史高分"},{"id":"m1","group":"movie","label":"热映"},
         {"id":"t1","group":"tv","label":"热播"}]
        """).RootElement.Clone();

    private static JsonElement FakeItems(string cat)
    {
        var items = Enumerable.Range(1, 18).Select(i => new
        {
            rank = i, title = $"假番 {cat}-{i} 号 · 一个相当长的名字看换行", subtitle = $"{2026 - i % 3} · 12 集",
            rating = 9.3 - i * 0.12, image_url = "",
        });
        return JsonDocument.Parse(JsonSerializer.Serialize(items)).RootElement.Clone();
    }

    internal static string Str(JsonElement e, string k) =>
        e.ValueKind == JsonValueKind.Object && e.TryGetProperty(k, out var v) && v.ValueKind == JsonValueKind.String
            ? v.GetString() ?? "" : "";

    internal static int Rank(JsonElement e) =>
        e.TryGetProperty("rank", out var r) && r.ValueKind == JsonValueKind.Number ? r.GetInt32() : 0;

    internal static double Rating(JsonElement e) =>
        e.TryGetProperty("rating", out var r) && r.ValueKind == JsonValueKind.Number ? r.GetDouble() : 0;

    /// <summary>海报。没图时先摆标题,图到了淡入盖上去。</summary>
    internal static Border Art(CoreClient core, JsonElement e, double w, double h)
    {
        var title = Str(e, "title");
        var img = new Image { Stretch = Stretch.UniformToFill, Opacity = 0, Classes = { "art" } };
        var url = Str(e, "image_url");
        if (url.Length > 0) _ = LoadArt(core, url, img, (int)(h * 2));
        return new Border
        {
            Width = w, Height = h, CornerRadius = new CornerRadius(10), ClipToBounds = true,
            Background = Tok.Of("PanelAlt"),
            Child = new Panel
            {
                Children =
                {
                    // 占位:没有海报时也要看得出这是什么,而不是一块空砖
                    new TextBlock
                    {
                        Text = title, FontSize = 12, Margin = new Thickness(10), Foreground = Tok.Of("Ink3"),
                        TextWrapping = TextWrapping.Wrap, TextAlignment = TextAlignment.Center,
                        VerticalAlignment = VerticalAlignment.Center, HorizontalAlignment = HorizontalAlignment.Center,
                    },
                    img,
                },
            },
        };
    }

    private static async Task LoadArt(CoreClient core, string url, Image target, int maxH)
    {
        // 走本地图片通道(和封面同一条路)。图床在核心层的**静态白名单**里,
        // 不在账号表里 —— 见 core/net/localserve 的 AllowStatic。
        var bmp = await Images.LoadAsync(core, url, maxH);
        if (bmp is null) return;
        Dispatcher.UIThread.Post(() => { target.Source = bmp; target.Opacity = 1; });
    }

    /// <summary>
    /// 前三名的金银铜(草稿 10 页第 33 条)。三个都是金色的话「第一」和「第三」只能靠读数字分辨,
    /// 而名次存在的理由正是<b>不用读就看得出来</b>。第四名之后回到普通白。
    /// </summary>
    internal static IBrush Medal(int rank) => new SolidColorBrush(Color.Parse(rank switch
    {
        1 => "#e0a95b",   // 金
        2 => "#c9cfda",   // 银
        3 => "#c6803f",   // 铜
        _ => "#e8ebf1",
    }));
}

/// <summary>前三名那一排的大卡:海报 + 大号名次 + 标题 + 年份集数 + 评分。</summary>
public sealed class PodiumCard : Border
{
    public PodiumCard(CoreClient core, JsonElement e)
    {
        var rank = RankingPage.Rank(e);
        var rating = RankingPage.Rating(e);
        Padding = new Thickness(14);
        CornerRadius = new CornerRadius(10);
        Background = Tok.Of("Panel");
        BorderBrush = RankingPage.Medal(rank);
        BorderThickness = new Thickness(1);

        var text = new StackPanel
        {
            Spacing = 10, Margin = new Thickness(18, 0, 0, 0), VerticalAlignment = VerticalAlignment.Center,
            Children =
            {
                new TextBlock
                {
                    Text = rank.ToString(), FontSize = 44, FontWeight = FontWeight.Black,
                    Foreground = RankingPage.Medal(rank), LineHeight = 46,
                },
                new TextBlock
                {
                    Text = RankingPage.Str(e, "title"), FontSize = 16, FontWeight = FontWeight.SemiBold,
                    TextWrapping = TextWrapping.Wrap, MaxLines = 3, TextTrimming = TextTrimming.CharacterEllipsis,
                },
                new TextBlock { Text = RankingPage.Str(e, "subtitle"), Classes = { "dim" }, FontSize = 12.5 },
                new TextBlock
                {
                    Text = rating > 0 ? $"★ {rating:0.0}" : "", FontSize = 13, FontWeight = FontWeight.SemiBold,
                    Foreground = Tok.Of("Warn"), IsVisible = rating > 0,
                },
            },
        };
        var grid = new Grid { ColumnDefinitions = new ColumnDefinitions("Auto,*") };
        grid.Children.Add(RankingPage.Art(core, e, 128, 192));
        Grid.SetColumn(text, 1);
        grid.Children.Add(text);
        Child = grid;
    }
}

/// <summary>
/// 榜单卡。和媒体卡不同:它<b>不指向用户媒体库里的条目</b>,只是一张榜单海报。
///
/// <para>所以不复用 <see cref="Card"/> —— 那个带右键动作(标记已看 / 收藏 / 屏蔽),
/// 而榜单条目在用户的服务器上根本不存在,那三项点下去只会报错。</para>
/// </summary>
public sealed class RankCard : Border
{
    public RankCard(CoreClient core, JsonElement e)
    {
        const double w = 150;
        const double h = w * 3 / 2;
        var rank = RankingPage.Rank(e);
        var art = RankingPage.Art(core, e, w, h);
        // 名次压在海报左下,大号描边数字 —— 一排扫过去先看到的是名次
        ((Panel)art.Child!).Children.Add(new Border
        {
            HorizontalAlignment = HorizontalAlignment.Left, VerticalAlignment = VerticalAlignment.Bottom,
            Margin = new Thickness(6), Padding = new Thickness(10, 0), CornerRadius = new CornerRadius(6),
            Background = new SolidColorBrush(Color.Parse("#cc000000")), IsVisible = rank > 0,
            Child = new TextBlock
            {
                Text = rank.ToString(), FontSize = 20, FontWeight = FontWeight.Black, Foreground = RankingPage.Medal(rank),
            },
        });

        var rating = RankingPage.Rating(e);
        var meta = string.Join(" · ",
            new[] { RankingPage.Str(e, "subtitle"), rating > 0 ? "★ " + rating.ToString("0.0") : "" }.Where(s => s.Length > 0));

        Child = new StackPanel
        {
            Width = w, Spacing = 6,
            Children =
            {
                art,
                new TextBlock
                {
                    Text = RankingPage.Str(e, "title"), FontSize = 13, FontWeight = FontWeight.Medium, MaxLines = 2,
                    TextWrapping = TextWrapping.Wrap, TextTrimming = TextTrimming.CharacterEllipsis,
                },
                new TextBlock { Text = meta, FontSize = 11.5, Classes = { "dim" }, IsVisible = meta.Length > 0 },
            },
        };
    }

    /// <summary>自检用:某个名次该是什么颜色。<b>探针拿它对账</b> ——
    /// 抄一份色号过去测的是抄本,改了这儿而没改那儿照样绿。</summary>
    internal static string MedalHex(int rank) =>
        ((SolidColorBrush)RankingPage.Medal(rank)).Color.ToString();
}
