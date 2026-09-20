using System;
using System.Collections.Generic;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text.Json;
using Avalonia;
using Avalonia.Animation;
using Avalonia.Animation.Easings;
using Avalonia.Controls;
using Avalonia.Controls.Documents;
using Avalonia.Controls.Primitives;
using Avalonia.Input;
using Avalonia.Interactivity;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Media.Transformation;
using Avalonia.Platform;
using Avalonia.Styling;
using Avalonia.Threading;
using LinPlayer.Core;
using LinPlayer.Desktop.Core;

namespace LinPlayer.Desktop.Views;

/// <summary>
/// 插件 UI 里 props 是**结构化 JSON** 的那一族(DetailHeader / FilterPanel / Tabs …)。
///
/// <para>和原语不同:内容不是子节点发过来的,而是一个 JSON 数组,渲染器自己建。
/// 所以属性只往 <see cref="Props"/> 里攒,一条 props op 应用完再统一 <see cref="Refresh"/> ——
/// 一帧设三个属性就重画三次的话,一千项的筛选面板会在一帧里被拆建三遍。</para>
///
/// <para>子节点(DetailHeader.actions、SettingsRow.trailing)仍走 insert 进
/// <see cref="Slot"/>,由各自的版式决定摆在哪。</para>
/// </summary>
internal sealed class PluginPart : Border
{
    internal readonly string Type;
    internal readonly Dictionary<string, JsonElement> Props = [];
    /// <summary>insert 进来的子节点落脚处。每次重画要先从旧父节点摘下来再挂。</summary>
    internal readonly FlexPanel Slot = new() { Horizontal = true, Gap = 10 };
    /// <summary>这一族每个组件只有一个回调(onChange / onSelect / onPress / onItemPress)。</summary>
    internal int Fn = -1;

    private readonly CoreClient _core;
    private readonly Action<int, object[]> _emit;
    private readonly ContentControl _built = new();

    internal PluginPart(string type, CoreClient core, Action<int, object[]> emit)
    {
        Type = type;
        _core = core;
        _emit = emit;
        Child = _built;
    }

    private JsonElement P(string k) => Props.TryGetValue(k, out var v) ? v : default;
    private string S(string k) => P(k).ValueKind == JsonValueKind.String ? P(k).GetString() ?? "" : "";
    private List<JsonElement> A(string k) => Mi.ArrOf(P(k));

    internal void Refresh()
    {
        // Slot 要换父节点,先从上一版版式里摘掉,否则 Avalonia 当场抛「已有父节点」
        if (Slot.Parent is Panel op) op.Children.Remove(Slot);
        else if (Slot.Parent is ContentControl oc && ReferenceEquals(oc.Content, Slot)) oc.Content = null;
        else if (Slot.Parent is Decorator od && ReferenceEquals(od.Child, Slot)) od.Child = null;
        _built.Content = Type switch
        {
            "PosterCard" => PosterCard(),
            "PosterRow" or "PosterGrid" => Posters(Type == "PosterRow"),
            "EpisodeGrid" => EpisodeGrid(),
            "DetailHeader" => DetailHeader(),
            "EmptyState" => EmptyState(),
            "FilterPanel" => FilterPanel(),
            "LineTabs" => LineTabs(),
            "RatingList" => RatingList(),
            "ServerCard" => ServerCard(),
            "SettingsRow" => SettingsRow(),
            "Tabs" => Tabs(),
            _ => Slot,
        };
    }

    private void Fire(params object[] args) { if (Fn >= 0) _emit(Fn, args); }

    /// <summary>嵌在结构化 props 里的回调号(<c>{"$fn":31}</c>)。</summary>
    private static int FnOf(JsonElement o, string key) =>
        o.ValueKind == JsonValueKind.Object && o.TryGetProperty(key, out var f)
        && f.ValueKind == JsonValueKind.Object && f.TryGetProperty("$fn", out var n)
        && n.ValueKind == JsonValueKind.Number ? n.GetInt32() : -1;

    // ---------------------------------------------------------------- 海报族

    /// <summary>官方数据源卡同一份实现(D21):主题改它,插件里的海报跟着变。</summary>
    private Control PosterCard()
    {
        var item = P("item");
        if (item.ValueKind != JsonValueKind.Object) return Slot;
        var show = P("showRemarks").ValueKind != JsonValueKind.False;
        var prog = P("progress").ValueKind == JsonValueKind.Number ? P("progress").GetDouble() : -1;
        return new SourceCard(_core, item, _ => Fire(), S("shape") is { Length: > 0 } s ? s : "portrait",
            progress: prog, showRemarks: show);
    }

    private Control Posters(bool row)
    {
        var shape = S("shape") is { Length: > 0 } s ? s : "portrait";
        var items = A("items");
        var box = row
            ? (Panel)new FlexPanel { Horizontal = true, Gap = 14 }
            : new WrapPanel { ItemSpacing = 14, LineSpacing = 14 };
        foreach (var it in items)
        {
            var one = it;
            box.Children.Add(new SourceCard(_core, one, _ => Fire(one), shape));
        }
        box.Children.Add(Slot);
        if (!row || S("title").Length == 0) return box;
        return new StackPanel
        {
            Spacing = 10,
            Children = { new TextBlock { Text = S("title"), Classes = { "h2" } }, Scroll(box) },
        };
    }

    private static Control Scroll(Control c) => new ScrollViewer
    {
        HorizontalScrollBarVisibility = ScrollBarVisibility.Auto,
        VerticalScrollBarVisibility = ScrollBarVisibility.Disabled,
        Content = c,
    };

    private Control EpisodeGrid()
    {
        var wrap = new WrapPanel { ItemSpacing = 10, LineSpacing = 10 };
        var eps = A("episodes");
        if (P("reversed").ValueKind == JsonValueKind.True) eps.Reverse();
        var cur = S("current");
        foreach (var e in eps)
        {
            var one = e;
            var b = new Button { Classes = { "chip" }, Content = Mi.Str(one, "name") };
            b.Classes.Set("on", Mi.Str(one, "id") == cur);
            b.Click += (_, _) => Fire(one);
            wrap.Children.Add(b);
        }
        wrap.Children.Add(Slot);
        return wrap;
    }

    // ---------------------------------------------------------------- 详情 / 信息

    /// <summary>详情头:海报 + 标题 + 元信息 + 评分,actions 摆在下面(和官方数据源详情同一版式)。</summary>
    private Control DetailHeader()
    {
        var it = P("item");
        var info = new StackPanel { Spacing = 10 };
        info.Children.Add(new TextBlock { Text = Mi.Str(it, "title"), Classes = { "h1" }, TextWrapping = TextWrapping.Wrap });
        var meta = new List<string>();
        if (Mi.YearText(it) is { Length: > 0 } y) meta.Add(y);
        meta.AddRange(Mi.Arr(it, "countries").Select(x => x.GetString() ?? ""));
        meta.AddRange(Mi.Arr(it, "genres").Select(x => x.GetString() ?? ""));
        if (Mi.Str(it, "remarks") is { Length: > 0 } rm) meta.Add(rm);
        meta = meta.Where(m => m.Length > 0).ToList();
        if (meta.Count > 0)
            info.Children.Add(new TextBlock { Text = string.Join(" · ", meta), Classes = { "dim" }, TextWrapping = TextWrapping.Wrap });
        if (Mi.Arr(it, "ratings") is { Count: > 0 } rs) info.Children.Add(Ratings(rs));
        if (Mi.Str(it, "overview") is { Length: > 0 } ov)
            info.Children.Add(new TextBlock
            {
                Text = ov, TextWrapping = TextWrapping.Wrap, MaxLines = 6,
                TextTrimming = TextTrimming.CharacterEllipsis, Foreground = Tok.Of("Ink2"),
                MaxWidth = 820, HorizontalAlignment = HorizontalAlignment.Left,
            });
        Slot.Horizontal = true;
        info.Children.Add(Slot);

        var poster = new Image { Stretch = Stretch.UniformToFill, Opacity = 0, Classes = { "art" } };
        if (Mi.Img(it, "poster") is { Length: > 0 } pu) LoadInto(poster, pu, 300);
        var left = new Border
        {
            Width = 200, Height = 300, CornerRadius = new CornerRadius(10), ClipToBounds = true,
            Background = Tok.Of("PanelAlt"), Child = poster, VerticalAlignment = VerticalAlignment.Top,
        };
        var grid = new Grid { ColumnDefinitions = new ColumnDefinitions("Auto,*") };
        info.Margin = new Thickness(26, 0, 0, 0);
        Grid.SetColumn(info, 1);
        grid.Children.Add(left);
        grid.Children.Add(info);
        return grid;
    }

    private Control RatingList() => Ratings(A("ratings"));

    /// <summary>各家评分并排(D435)。有 url 的能点开,没有的画成静态片 —— 外观要分得出来。</summary>
    private Control Ratings(List<JsonElement> rs)
    {
        var row = new WrapPanel { ItemSpacing = 10, LineSpacing = 10 };
        foreach (var r in rs)
        {
            var max = Mi.Num(r, "max");
            var text = $"{Mi.Str(r, "source")} {Mi.Num(r, "value"):0.0}" + (max > 0 ? $"/{max:0}" : "");
            row.Children.Add(Mi.Str(r, "url") is { Length: > 0 } u
                ? Chips.Clickable(text, () => OpenUrl(u))
                : Chips.Plain(text));
        }
        return row;
    }

    private Control EmptyState()
    {
        var col = new StackPanel { Spacing = 14, HorizontalAlignment = HorizontalAlignment.Center };
        col.Children.Add(new TextBlock
        {
            Text = S("text"), Foreground = Tok.Of("Ink2"),
            TextWrapping = TextWrapping.Wrap, TextAlignment = TextAlignment.Center,
        });
        var act = P("action");
        if (act.ValueKind == JsonValueKind.Object)
        {
            var b = new Button
            {
                Classes = { "primary" }, Content = Mi.Str(act, "title"),
                HorizontalAlignment = HorizontalAlignment.Center,
            };
            // 回调藏在 action 对象里(不是顶层 on* 属性),Wire 看不到它,自己抽
            var fn = FnOf(act, "onPress");
            b.Click += (_, _) => { if (fn >= 0) _emit(fn, []); };
            col.Children.Add(b);
        }
        col.Children.Add(Slot);
        return new Border { Padding = new Thickness(26), Child = col };
    }

    private Control ServerCard()
    {
        var s = P("server");
        var col = new StackPanel { Spacing = 6 };
        col.Children.Add(new TextBlock { Text = Mi.Str(s, "name"), Classes = { "h2" } });
        // 只有 id / name / type 三个字段,地址与用户名 SDK 根本不给(D287)
        col.Children.Add(new TextBlock { Text = Mi.Str(s, "type") == "emby" ? "Emby 服务器" : "数据源", Classes = { "dim" } });
        col.Children.Add(Slot);
        var card = new Border { Classes = { "card", "tap" }, Padding = new Thickness(14), Child = col };
        var b = new Button { Classes = { "ghost" }, Padding = new Thickness(0), Background = Brushes.Transparent, Content = card };
        b.Click += (_, _) => Fire();
        return b;
    }

    // ---------------------------------------------------------------- 选择器族

    /// <summary>设置行:标题 + 说明在左,trailing(开关 / 值)靠右。</summary>
    private Control SettingsRow()
    {
        var left = new StackPanel { Spacing = 2, VerticalAlignment = VerticalAlignment.Center };
        left.Children.Add(new TextBlock { Text = S("title"), Foreground = Tok.Of("Ink") });
        if (S("description") is { Length: > 0 } d)
            left.Children.Add(new TextBlock { Text = d, Classes = { "dim" }, TextWrapping = TextWrapping.Wrap });
        Slot.Horizontal = true;
        var grid = new Grid { ColumnDefinitions = new ColumnDefinitions("*,Auto") };
        Grid.SetColumn(Slot, 1);
        Slot.VerticalAlignment = VerticalAlignment.Center;
        grid.Children.Add(left);
        grid.Children.Add(Slot);
        var b = new Button
        {
            Classes = { "ghost" }, HorizontalAlignment = HorizontalAlignment.Stretch,
            Padding = new Thickness(14, 10), Content = grid,
        };
        b.Click += (_, _) => Fire();
        return b;
    }

    private Control Tabs() => Strip(A("tabs"), S("current"), t => Mi.Str(t, "id"),
        t => Mi.Str(t, "title"), id => Fire(id));

    private Control LineTabs() => Strip(A("lines"), S("current"), l => Mi.Str(l, "id"),
        l => $"{Mi.Str(l, "name")} · {Mi.Arr(l, "episodes").Count}", id => Fire(id));

    /// <summary>一排单选片,选中的挂 <c>on</c>(和官方线路条同一套外观)。</summary>
    private Control Strip(List<JsonElement> items, string current,
        Func<JsonElement, string> idOf, Func<JsonElement, string> labelOf, Action<string> pick)
    {
        var row = new WrapPanel { ItemSpacing = 10, LineSpacing = 10 };
        foreach (var it in items)
        {
            var id = idOf(it);
            var b = new Button { Classes = { "chip" }, Content = labelOf(it) };
            b.Classes.Set("on", id == current);
            b.Click += (_, _) => pick(id);
            row.Children.Add(b);
        }
        row.Children.Add(Slot);
        return row;
    }

    /// <summary>
    /// 筛选面板:一个维度一行片。选中项合成整张 value 表发回去 ——
    /// 只发变的那一维的话,JS 那边拿不到「另外两维现在是什么」。
    /// </summary>
    private Control FilterPanel()
    {
        var value = P("value");
        var col = new StackPanel { Spacing = 10 };
        foreach (var dim in A("dimensions"))
        {
            var key = Mi.Str(dim, "key");
            var multi = dim.TryGetProperty("multi", out var m) && m.ValueKind == JsonValueKind.True;
            var picked = Mi.Arr(value, key).Select(x => x.GetString() ?? "").ToList();
            var row = new WrapPanel { ItemSpacing = 6, LineSpacing = 6 };
            row.Children.Add(new TextBlock
            {
                Text = Mi.Str(dim, "name"), Classes = { "dim" },
                VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(0, 0, 10, 0),
            });
            foreach (var opt in Mi.Arr(dim, "options"))
            {
                var v = Mi.Str(opt, "value");
                var b = new Button { Classes = { "chip" }, Content = Mi.Str(opt, "name") };
                b.Classes.Set("on", picked.Contains(v));
                b.Click += (_, _) => Pick(key, v, multi);
                row.Children.Add(b);
            }
            col.Children.Add(row);
        }
        col.Children.Add(Slot);
        return col;
    }

    private void Pick(string key, string v, bool multi)
    {
        var next = new Dictionary<string, string[]>();
        foreach (var dim in A("dimensions"))
        {
            var k = Mi.Str(dim, "key");
            next[k] = Mi.Arr(P("value"), k).Select(x => x.GetString() ?? "").ToArray();
        }
        var cur = next.TryGetValue(key, out var a) ? a.ToList() : [];
        if (!multi) cur = cur.Contains(v) ? [] : [v];
        else if (!cur.Remove(v)) cur.Add(v);
        next[key] = [.. cur];
        Fire(next);
    }

    // ---------------------------------------------------------------- 小工具

    private void LoadInto(Image target, string url, int maxH)
    {
        _ = System.Threading.Tasks.Task.Run(async () =>
        {
            var bmp = await Images.LoadAsync(_core, url, maxH);
            if (bmp is null) return; // 拉不到就留灰底:占位本身就是「这里没有图」的答案
            Avalonia.Threading.Dispatcher.UIThread.Post(() => { target.Source = bmp; target.Opacity = 1; });
        });
    }

    internal static void OpenUrl(string url)
    {
        try { System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(url) { UseShellExecute = true }); }
        catch (Exception e) { Toast.Error("打不开这个链接:" + e.Message); }
    }
}

/// <summary>
/// 图标(SPEC 7.4,D211 D549)。<c>name</c> 是官方稳定名,<c>src</c> 是包内图走取图链路。
///
/// <para>名字表和 <c>scripts/gen-icon-font.py</c> 的码位表是同一批:那边加一个图标,
/// 这里补一行才认得。认不得的名字画成空框而不是留白 —— 留白和「图标画上了但是透明」
/// 在截图上一模一样。</para>
/// </summary>
internal sealed class PluginIcon : Panel
{
    private static readonly Dictionary<string, string> Names = new()
    {
        ["edit"] = "", ["add"] = "", ["dismiss"] = "", ["settings"] = "",
        ["apps"] = "", ["search"] = "", ["camera"] = "",
        ["skip_forward_10"] = "", ["skip_back_10"] = "", ["refresh"] = "",
        ["lock_closed"] = "", ["star"] = "", ["star_filled"] = "",
        ["checkbox_unchecked"] = "", ["checkmark"] = "",
        ["full_screen_minimize"] = "", ["full_screen_maximize"] = "",
        ["speaker_mute"] = "", ["speaker"] = "", ["lock_open"] = "",
        ["play"] = "", ["pause"] = "", ["chevron_left"] = "", ["chevron_right"] = "",
        ["globe"] = "", ["calendar"] = "", ["video"] = "", ["home"] = "",
        ["history"] = "", ["eye"] = "", ["next"] = "", ["download"] = "",
        ["grid"] = "", ["filmstrip"] = "", ["folder"] = "", ["sort"] = "",
        ["library"] = "", ["list"] = "", ["image"] = "", ["info"] = "",
        ["server"] = "", ["pulse"] = "",
    };

    private readonly TextBlock _glyph = new() { FontFamily = Glyph.Font, FontSize = 16, Foreground = Tok.Of("Ink") };
    private readonly Image _img = new() { Stretch = Stretch.Uniform, IsVisible = false };

    internal PluginIcon() { Children.Add(_glyph); Children.Add(_img); }

    /// <summary>稳定名 → 码位。Button.icon 也查这一张,免得两处表慢慢走散。</summary>
    internal static string? GlyphOf(string name) => Names.TryGetValue(name, out var cp) ? cp : null;

    internal void SetName(string name)
    {
        if (Names.TryGetValue(name, out var cp))
        {
            // 上一个名字认不得时把字体换成了默认字体,这里要换回来,否则码位会画成方框
            _glyph.FontFamily = Glyph.Font;
            _glyph.Foreground = Tok.Of("Ink");
            _glyph.Text = cp;
            return;
        }
        _glyph.FontFamily = FontFamily.Default;
        _glyph.Text = "▢";
        _glyph.Foreground = Tok.Of("Ink3");
        Log.W("插件UI", $"没有这个图标名 {name} —— 画成了空框");
    }

    internal void SetSrc(CoreClient core, string src)
    {
        _img.IsVisible = true;
        _glyph.IsVisible = false;
        _ = System.Threading.Tasks.Task.Run(async () =>
        {
            var bmp = await Images.LoadAsync(core, src, 64);
            if (bmp is null) return; // 取不到就留空框:上面那条 warn 已经说清是哪一个
            Avalonia.Threading.Dispatcher.UIThread.Post(() => _img.Source = bmp);
        });
    }

    internal void SetSize(double s) { _glyph.FontSize = s; _img.Width = s; _img.Height = s; }

    internal void SetColor(IBrush b) { _glyph.Foreground = b; }
}

/// <summary>
/// Markdown 的小子集(SPEC 7.4):标题 #~###、无序列表、粗体、行内代码、链接、段落。
///
/// <para>只做这几样,不引渲染库:插件说明和更新日志用的就是这几样,
/// 表格和图片进来会原样当正文显示 —— 那比整块空白好。</para>
/// </summary>
internal static class PluginMarkdown
{
    internal static Control Render(string source)
    {
        var col = new StackPanel { Spacing = 6 };
        foreach (var raw in source.Replace("\r\n", "\n").Split('\n'))
        {
            var line = raw.TrimEnd();
            if (line.Length == 0) continue;
            var (text, size, weight, indent) = Head(line);
            var tb = new TextBlock
            {
                TextWrapping = TextWrapping.Wrap, FontSize = size, FontWeight = weight,
                Foreground = Tok.Of("Ink"), Margin = new Thickness(indent, 0, 0, 0),
            };
            tb.Inlines = [];
            foreach (var run in Spans(text)) tb.Inlines.Add(run);
            col.Children.Add(tb);
        }
        return col;
    }

    private static (string Text, double Size, FontWeight Weight, double Indent) Head(string line)
    {
        if (line.StartsWith("### ", StringComparison.Ordinal)) return (line[4..], 15, FontWeight.SemiBold, 0);
        if (line.StartsWith("## ", StringComparison.Ordinal)) return (line[3..], 17, FontWeight.SemiBold, 0);
        if (line.StartsWith("# ", StringComparison.Ordinal)) return (line[2..], 20, FontWeight.SemiBold, 0);
        var t = line.TrimStart();
        if (t.StartsWith("- ", StringComparison.Ordinal) || t.StartsWith("* ", StringComparison.Ordinal))
            return ("• " + t[2..], 13.5, FontWeight.Normal, 14);
        return (line, 13.5, FontWeight.Normal, 0);
    }

    /// <summary>行内标记:<c>**粗**</c> <c>`码`</c> <c>[文字](地址)</c>。一遍扫过去,不回溯。</summary>
    private static IEnumerable<Inline> Spans(string s)
    {
        var buf = new System.Text.StringBuilder();
        for (var i = 0; i < s.Length; i++)
        {
            if (s[i] == '*' && i + 1 < s.Length && s[i + 1] == '*' && End(s, i + 2, "**") is var b && b > 0)
            {
                foreach (var r in Flush(buf)) yield return r;
                yield return new Run(s[(i + 2)..b]) { FontWeight = FontWeight.SemiBold };
                i = b + 1;
                continue;
            }
            if (s[i] == '`' && End(s, i + 1, "`") is var c && c > 0)
            {
                foreach (var r in Flush(buf)) yield return r;
                yield return new Run(s[(i + 1)..c]) { FontFamily = FontFamily.Parse("Consolas,monospace"), Foreground = Tok.Of("Accent") };
                i = c;
                continue;
            }
            if (s[i] == '[' && End(s, i + 1, "](") is var t && t > 0 && End(s, t + 2, ")") is var u && u > 0)
            {
                foreach (var r in Flush(buf)) yield return r;
                var url = s[(t + 2)..u];
                var link = new Run(s[(i + 1)..t]) { Foreground = Tok.Of("Accent") };
                yield return link;
                // Run 点不了,链接地址跟在后面括号里写出来 —— 藏起来等于这条链接不存在
                yield return new Run($" ({url})") { Foreground = Tok.Of("Ink3"), FontSize = 12 };
                i = u;
                continue;
            }
            buf.Append(s[i]);
        }
        foreach (var r in Flush(buf)) yield return r;
    }

    private static int End(string s, int from, string mark)
    {
        var k = s.IndexOf(mark, from, StringComparison.Ordinal);
        return k > from ? k : -1;
    }

    private static IEnumerable<Inline> Flush(System.Text.StringBuilder b)
    {
        if (b.Length == 0) yield break;
        yield return new Run(b.ToString());
        b.Clear();
    }
}

/// <summary>
/// 内嵌网页(SPEC 7.4.1,D269)。机器上没有 WebView2 运行时就画「不可用」那一块,
/// 而不是画一个永远白着的框 —— 后者和「网页正在加载」分不出来。
/// </summary>
internal sealed class PluginWebView : Border
{
    private readonly NativeHost _host = new();
    private string _src = "", _ua = "", _inject = "";
    private int _msgFn = -1;
    private Action<int, object[]>? _emit;

    internal bool Ok { get; }

    internal PluginWebView()
    {
        Ok = WebViewHost.Available;
        MinHeight = 200;
        if (Ok) { Child = _host; return; }
        Background = Tok.Of("PanelAlt");
        CornerRadius = new CornerRadius(10);
        Padding = new Thickness(14, 10);
        Child = new TextBlock
        {
            Text = "这台机器上网页组件不可用(缺 WebView2 运行时)",
            Foreground = Tok.Of("Ink2"), TextWrapping = TextWrapping.Wrap,
        };
    }

    internal void SetEmit(Action<int, object[]> emit) => _emit = emit;
    internal void SetMessageFn(int fn) { _msgFn = fn; Push(); }
    internal void SetSrc(string src) { _src = src; Push(); }
    internal void SetInject(string js) { _inject = js; Push(); }

    internal void SetOptions(JsonElement o)
    {
        _ua = Mi.Str(o, "userAgent");
        if (Mi.Str(o, "injectScript") is { Length: > 0 } js) _inject = js;
        Push();
    }

    private bool _queued;

    /// <summary>
    /// 一帧只应用一次:src 在 .d.ts 里排在 options 前面,逐条应用的话
    /// UA 会赶在导航之后才设上 —— 表现是「第一次打开没带 UA,刷新一下就好了」。
    /// </summary>
    private void Push()
    {
        if (!Ok || _queued) return;
        _queued = true;
        Avalonia.Threading.Dispatcher.UIThread.Post(() =>
        {
            _queued = false;
            if (_src.Length == 0) return;
            _ = _host.Apply(_src, _ua, _inject, data =>
            {
                if (_msgFn >= 0) _emit?.Invoke(_msgFn, [data]);
            });
        });
    }

    /// <summary>WebView2 要一个真 HWND 当父窗口,Avalonia 的控件不是窗口,所以自己造一个子窗口。</summary>
    private sealed class NativeHost : NativeControlHost
    {
        private Microsoft.Web.WebView2.Core.CoreWebView2Controller? _ctl;
        private IntPtr _hwnd;
        private string _loaded = "";

        private (string Src, string Ua, string Inject, Action<string> OnMessage)? _want;

        internal System.Threading.Tasks.Task Apply(string src, string ua, string inject, Action<string> onMessage)
        {
            _want = (src, ua, inject, onMessage);
            return Start();
        }

        /// <summary>属性可能比可视树先到:先记下来,HWND 建出来的那一刻再补跑一次。</summary>
        private async System.Threading.Tasks.Task Start()
        {
            if (_hwnd == IntPtr.Zero || _want is not { } w) return;
            var (src, ua, inject, onMessage) = w;
            if (_ctl is null)
            {
                var env = await WebViewHost.Env();
                _ctl = await env.CreateCoreWebView2ControllerAsync(_hwnd);
                _ctl.CoreWebView2.Settings.AreDevToolsEnabled = false;
                _ctl.CoreWebView2.WebMessageReceived += (_, e) => onMessage(e.WebMessageAsJson);
                Resize();
            }
            if (ua.Length > 0) _ctl.CoreWebView2.Settings.UserAgent = ua;
            if (inject.Length > 0) await _ctl.CoreWebView2.AddScriptToExecuteOnDocumentCreatedAsync(inject);
            if (src == _loaded) return;
            _loaded = src;
            _ctl.CoreWebView2.Navigate(src);
        }

        protected override IPlatformHandle CreateNativeControlCore(IPlatformHandle parent)
        {
            _hwnd = CreateWindowExW(0, "Static", "", 0x40000000 | 0x10000000 | 0x02000000,
                0, 0, 1, 1, parent.Handle, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero);
            _ = Start();
            return new PlatformHandle(_hwnd, "HWND");
        }

        protected override void DestroyNativeControlCore(IPlatformHandle control)
        {
            _ctl?.Close();
            _ctl = null;
            DestroyWindow(control.Handle);
        }

        protected override Size ArrangeOverride(Size finalSize)
        {
            var r = base.ArrangeOverride(finalSize);
            Resize();
            return r;
        }

        private void Resize()
        {
            if (_ctl is null) return;
            var s = VisualRoot?.RenderScaling ?? 1;
            _ctl.Bounds = new System.Drawing.Rectangle(0, 0, (int)(Bounds.Width * s), (int)(Bounds.Height * s));
        }

        [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern IntPtr CreateWindowExW(int exStyle, string cls, string name, int style,
            int x, int y, int w, int h, IntPtr parent, IntPtr menu, IntPtr inst, IntPtr param);

        [DllImport("user32.dll")]
        private static extern bool DestroyWindow(IntPtr hwnd);
    }
}

/// <summary>
/// 内嵌播放器(SPEC 7.4.1,D268)。
///
/// <para>桌面的视频是一个独立子窗口叠在主窗口上,要让它跟着这块区域走,得先把
/// 「窗口跟随 + 裁切 + 层级」那条 spike 做完(D268 明写「待 spike」)。没做完之前
/// 这里画的是一块说明,<b>不是</b> D319 的未知组件占位 —— 两者在截图上长得一样,
/// 但一个是「这一端没做」,另一个是「这一端太老」,自检脚本靠分支认领区分。</para>
/// </summary>
internal static class PluginPlayer
{
    internal static Control Slot() => new Border
    {
        MinHeight = 180,
        CornerRadius = new CornerRadius(10),
        Background = Tok.Of("PanelAlt"),
        BorderBrush = Tok.Of("Line"),
        BorderThickness = new Thickness(1),
        Padding = new Thickness(14, 10),
        Child = new TextBlock
        {
            Text = "内嵌播放器在桌面端还没接上(视频是独立子窗口,区域跟随待做)",
            Foreground = Tok.Of("Ink2"), TextWrapping = TextWrapping.Wrap,
            VerticalAlignment = VerticalAlignment.Center, TextAlignment = TextAlignment.Center,
        },
    };
}

/// <summary>
/// 动效(SPEC 7.6,D88 D428)。属性动画由原生端跑,不回 JS。
///
/// <para>系统开了「减少动态效果」就直接跳终态:Windows 那个开关
/// (设置 → 辅助功能 → 视觉效果 → 动画效果)对应 SPI_GETCLIENTAREAANIMATION,
/// 读不到按「不减少」算 —— 猜成「减少」会把所有人的动效都砍掉。</para>
/// </summary>
internal static class Motion
{
    private const uint SpiGetClientAreaAnimation = 0x1042;

    /// <summary>正在跑的那段 animation 的原文,用来认出「还是上一段」。</summary>
    private static readonly AttachedProperty<string> RunningP =
        AvaloniaProperty.RegisterAttached<Control, Control, string>("AnimSig", "");

    private static bool? _reduced;

    internal static bool Reduced
    {
        get
        {
            if (_reduced is { } v) return v;
            var on = 1;
            try { if (!SystemParametersInfoW(SpiGetClientAreaAnimation, 0, ref on, 0)) on = 1; }
            catch (Exception e) { Log.D("插件UI", "读不到系统动效开关,按不减少算:" + e.Message); on = 1; }
            _reduced = on == 0;
            return _reduced.Value;
        }
    }

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool SystemParametersInfoW(uint action, uint param, ref int pv, uint winIni);

    private static Easing Ease(string name) => name switch
    {
        "decelerate" => new CubicEaseOut(),
        "accelerate" => new CubicEaseIn(),
        "linear" => new LinearEasing(),
        _ => new CubicEaseInOut(),   // standard
    };

    /// <summary>
    /// <c>transition: {props, duration, easing, delay}</c>。
    /// props 里的名字映射到 Avalonia 属性;变换类(translate / scale / rotate)
    /// 在 Avalonia 里是同一个 RenderTransform,所以合成一条过渡。
    /// </summary>
    internal static void Transition(Control c, JsonElement t)
    {
        var ms = t.TryGetProperty("duration", out var d) && d.ValueKind == JsonValueKind.Number ? d.GetDouble() : 200;
        if (Reduced) ms = 0;
        var delay = t.TryGetProperty("delay", out var dl) && dl.ValueKind == JsonValueKind.Number ? dl.GetDouble() : 0;
        if (Reduced) delay = 0;
        var easing = Ease(t.TryGetProperty("easing", out var e) && e.ValueKind == JsonValueKind.String ? e.GetString() ?? "" : "");
        var dur = TimeSpan.FromMilliseconds(ms);
        var lag = TimeSpan.FromMilliseconds(delay);
        var list = new Transitions();
        var transformed = false;
        foreach (var p in Mi.ArrOf(t.TryGetProperty("props", out var ps) ? ps : default))
        {
            switch (p.GetString())
            {
                case "opacity": list.Add(new DoubleTransition { Property = Visual.OpacityProperty, Duration = dur, Delay = lag, Easing = easing }); break;
                case "width": list.Add(new DoubleTransition { Property = Layoutable.WidthProperty, Duration = dur, Delay = lag, Easing = easing }); break;
                case "height": list.Add(new DoubleTransition { Property = Layoutable.HeightProperty, Duration = dur, Delay = lag, Easing = easing }); break;
                case "translateX" or "translateY" or "scale" or "rotate":
                    if (transformed) break;
                    transformed = true;
                    list.Add(new TransformOperationsTransition { Property = Visual.RenderTransformProperty, Duration = dur, Delay = lag, Easing = easing });
                    break;
            }
        }
        c.Transitions = list.Count > 0 ? list : null;
    }

    /// <summary>变换四件套合成一句 TransformOperations —— 分开设会互相覆盖,只剩最后一个生效。</summary>
    internal static void Transform(Control c, JsonElement s)
    {
        double? tx = N(s, "translateX"), ty = N(s, "translateY"), sc = N(s, "scale"), ro = N(s, "rotate");
        if (tx is null && ty is null && sc is null && ro is null) return;
        c.RenderTransform = TransformOperations.Parse(
            $"translate({tx ?? 0}px,{ty ?? 0}px) scale({sc ?? 1}) rotate({ro ?? 0}deg)");
    }

    private static double? N(JsonElement o, string k) =>
        o.ValueKind == JsonValueKind.Object && o.TryGetProperty(k, out var v) && v.ValueKind == JsonValueKind.Number
            ? v.GetDouble() : null;

    /// <summary><c>animation: {keyframes, duration, iterations, easing}</c>;减少动效时直接摆成最后一帧。</summary>
    internal static void Animate(Control c, JsonElement a)
    {
        var frames = Mi.ArrOf(a.TryGetProperty("keyframes", out var kf) ? kf : default);
        if (frames.Count == 0) return;
        // 同一段动画不重复起跑:插件每次重渲染都会把整个 style 再发一遍,
        // 不比一下的话一个 1.2 秒的入场动画会被每一帧掐回开头,看着像卡住
        var sig = a.GetRawText();
        if (c.GetValue(RunningP) == sig) return;
        c.SetValue(RunningP, sig);
        if (Reduced) { ApplyFrame(c, frames[^1]); return; }

        var ms = N(a, "duration") ?? 300;
        var anim = new Avalonia.Animation.Animation
        {
            Duration = TimeSpan.FromMilliseconds(ms),
            Easing = Ease(a.TryGetProperty("easing", out var e) && e.ValueKind == JsonValueKind.String ? e.GetString() ?? "" : ""),
            IterationCount = a.TryGetProperty("iterations", out var it) && it.ValueKind == JsonValueKind.String
                ? IterationCount.Infinite
                : new IterationCount((ulong)Math.Max(1, N(a, "iterations") ?? 1)),
        };
        for (var i = 0; i < frames.Count; i++)
        {
            var k = new KeyFrame { Cue = new Cue(frames.Count == 1 ? 1 : (double)i / (frames.Count - 1)) };
            foreach (var st in Setters(frames[i])) k.Setters.Add(st);
            if (k.Setters.Count > 0) anim.Children.Add(k);
        }
        if (anim.Children.Count == 0) return;
        _ = anim.RunAsync(c);
    }

    private static IEnumerable<Avalonia.Styling.Setter> Setters(JsonElement f)
    {
        if (N(f, "opacity") is { } o) yield return new Avalonia.Styling.Setter(Visual.OpacityProperty, o);
        if (N(f, "width") is { } w) yield return new Avalonia.Styling.Setter(Layoutable.WidthProperty, w);
        if (N(f, "height") is { } h) yield return new Avalonia.Styling.Setter(Layoutable.HeightProperty, h);
        if (N(f, "translateX") is not null || N(f, "translateY") is not null
            || N(f, "scale") is not null || N(f, "rotate") is not null)
            yield return new Avalonia.Styling.Setter(Visual.RenderTransformProperty, TransformOperations.Parse(
                $"translate({N(f, "translateX") ?? 0}px,{N(f, "translateY") ?? 0}px) scale({N(f, "scale") ?? 1}) rotate({N(f, "rotate") ?? 0}deg)"));
    }

    private static void ApplyFrame(Control c, JsonElement f)
    {
        if (N(f, "opacity") is { } o) c.Opacity = o;
        if (N(f, "width") is { } w) c.Width = w;
        if (N(f, "height") is { } h) c.Height = h;
        Transform(c, f);
    }
}

/// <summary>焦点与无障碍(SPEC 7.8,D31 D424 D529)。方向键交给 Avalonia 的 XYFocus。</summary>
internal static class PluginFocus
{
    internal static void Apply(Control c, string name, JsonElement v, bool unset)
    {
        switch (name)
        {
            case "focusable":
                c.Focusable = !unset && v.ValueKind != JsonValueKind.False;
                break;
            case "autoFocus":
                // 这一帧控件还没进可视树,Focus() 会静默落空 —— 挂到进树那一刻
                if (!unset && v.ValueKind == JsonValueKind.True)
                    c.AttachedToVisualTree += FocusOnAttach;
                else c.AttachedToVisualTree -= FocusOnAttach;
                break;
            case "focusGroup":
                // 组内方向键循环:Avalonia 的 TabNavigation 就是这件事
                Avalonia.Input.KeyboardNavigation.SetTabNavigation(c,
                    unset ? Avalonia.Input.KeyboardNavigationMode.Continue : Avalonia.Input.KeyboardNavigationMode.Cycle);
                break;
            case "nextFocusUp": Dir(c, Avalonia.Input.XYFocus.UpNavigationStrategyProperty, unset); break;
            case "nextFocusDown": Dir(c, Avalonia.Input.XYFocus.DownNavigationStrategyProperty, unset); break;
            case "nextFocusLeft": Dir(c, Avalonia.Input.XYFocus.LeftNavigationStrategyProperty, unset); break;
            case "nextFocusRight": Dir(c, Avalonia.Input.XYFocus.RightNavigationStrategyProperty, unset); break;
        }
    }

    /// <summary>
    /// nextFocus* 指的是组件 key,而壳这边只有节点号,拿不到 key 对应的控件。
    /// 能落地的那一半是策略:声明了方向就让 XYFocus 按投影找,而不是按 Tab 顺序找。
    /// </summary>
    private static void Dir(Control c, AvaloniaProperty p, bool unset) =>
        c.SetValue(p, unset ? Avalonia.Input.XYFocusNavigationStrategy.Auto : Avalonia.Input.XYFocusNavigationStrategy.Projection);

    private static void FocusOnAttach(object? sender, VisualTreeAttachmentEventArgs e)
    {
        if (sender is Control c) Avalonia.Threading.Dispatcher.UIThread.Post(() => c.Focus());
    }
}

/// <summary>
/// 下拉框的选项(SPEC 7.4 的 Select)。<c>options</c> 是 <c>{value,label}</c> 数组,
/// 界面显示 label、回调发 value —— 不留一份映射的话回调只能把 label 发回去,
/// 而插件那边等的是 value。
/// </summary>
internal static class SelectOptions
{
    internal sealed record Item(string Value, string Label)
    {
        public override string ToString() => Label;
    }

    /// <summary>想选中的 value。props 里 value 排在 options 前面,先到的那个得等后到的。</summary>
    private static readonly AttachedProperty<string> WantP =
        AvaloniaProperty.RegisterAttached<ComboBox, ComboBox, string>("SelWant", "");

    internal static void Fill(ComboBox cb, JsonElement options)
    {
        var list = Mi.ArrOf(options).Select(o => new Item(Mi.Str(o, "value"), Mi.Str(o, "label"))).ToList();
        cb.ItemsSource = list;
        Sync(cb);
    }

    internal static void Select(ComboBox cb, string value)
    {
        cb.SetValue(WantP, value);
        Sync(cb);
    }

    private static void Sync(ComboBox cb)
    {
        if (cb.GetValue(WantP) is not { Length: > 0 } want) return;
        if (cb.ItemsSource is IEnumerable<Item> items)
            cb.SelectedItem = items.FirstOrDefault(i => i.Value == want);
    }
}

/// <summary>
/// PressProps 里 onPress 之外的三个(SPEC 7.4):长按、得焦、失焦。
///
/// <para>接在 <c>Control</c> 上而不是逐个组件接 —— Button / Chip / PosterCard /
/// ServerCard / SettingsRow / Pressable 全是控件,逐个接必然漏,而漏掉的那一个
/// 表现是「这一处长按没反应」,截图上看不出来。</para>
///
/// <para>长按自己计时:Avalonia 没有 Holding 手势,<c>Gestures.RightTapped</c> 是右键
/// 不是长按 —— 拿它顶包的话触屏上按住永远不触发。</para>
/// </summary>
internal static class PluginPress
{
    private static readonly TimeSpan Hold = TimeSpan.FromMilliseconds(500);
    /// <summary>手指按住时会微动,不给容差基本长按不出来。</summary>
    private const double Slop = 12;

    private static readonly AttachedProperty<int> LongFnP =
        AvaloniaProperty.RegisterAttached<Control, Control, int>("PressLongFn", -1);
    private static readonly AttachedProperty<int> FocusFnP =
        AvaloniaProperty.RegisterAttached<Control, Control, int>("PressFocusFn", -1);
    private static readonly AttachedProperty<int> BlurFnP =
        AvaloniaProperty.RegisterAttached<Control, Control, int>("PressBlurFn", -1);
    private static readonly AttachedProperty<Action<int, object[]>?> EmitP =
        AvaloniaProperty.RegisterAttached<Control, Control, Action<int, object[]>?>("PressEmit");
    private static readonly AttachedProperty<DispatcherTimer?> TimerP =
        AvaloniaProperty.RegisterAttached<Control, Control, DispatcherTimer?>("PressTimer");
    private static readonly AttachedProperty<Point> DownAtP =
        AvaloniaProperty.RegisterAttached<Control, Control, Point>("PressDownAt");
    private static readonly AttachedProperty<bool> FiredP =
        AvaloniaProperty.RegisterAttached<Control, Control, bool>("PressFired");

    internal static void Wire(Control c, string name, int fn, Action<int, object[]> emit)
    {
        c.SetValue(EmitP, emit);
        switch (name)
        {
            case "onLongPress":
                c.SetValue(LongFnP, fn);
                Pointer(c, false);
                if (fn >= 0) Pointer(c, true);
                break;
            case "onFocus":
                c.SetValue(FocusFnP, fn);
                c.GotFocus -= OnGotFocus;
                if (fn >= 0) c.GotFocus += OnGotFocus;
                break;
            case "onBlur":
                c.SetValue(BlurFnP, fn);
                c.LostFocus -= OnLostFocus;
                if (fn >= 0) c.LostFocus += OnLostFocus;
                break;
        }
    }

    /// <summary>
    /// 长按刚发过没有。<c>Button</c> 松手时照样会 Click,不吃掉这一次的话
    /// 插件看到的是「按一下同时来了 onLongPress 和 onPress」。
    /// </summary>
    internal static bool TookLongPress(Control c)
    {
        if (!c.GetValue(FiredP)) return false;
        c.SetValue(FiredP, false);
        return true;
    }

    /* handledEventsToo 必须开:Button 的类处理器在 OnPointerPressed 里就把事件
       标成已处理了,默认的 `+=` 挂上去一次都不会被调到。 */
    private static void Pointer(Control c, bool on)
    {
        const RoutingStrategies Bubble = RoutingStrategies.Bubble;
        if (on)
        {
            c.AddHandler(InputElement.PointerPressedEvent, OnDown, Bubble, true);
            c.AddHandler(InputElement.PointerMovedEvent, OnMove, Bubble, true);
            c.AddHandler(InputElement.PointerReleasedEvent, OnUp, Bubble, true);
            c.AddHandler(InputElement.PointerCaptureLostEvent, OnUp, Bubble, true);
            return;
        }
        c.RemoveHandler(InputElement.PointerPressedEvent, OnDown);
        c.RemoveHandler(InputElement.PointerMovedEvent, OnMove);
        c.RemoveHandler(InputElement.PointerReleasedEvent, OnUp);
        c.RemoveHandler(InputElement.PointerCaptureLostEvent, OnUp);
    }

    private static void OnDown(object? sender, PointerPressedEventArgs e)
    {
        if (sender is not Control c) return;
        Stop(c);
        c.SetValue(FiredP, false);
        c.SetValue(DownAtP, e.GetPosition(c));
        var t = new DispatcherTimer { Interval = Hold };
        t.Tick += (_, _) => { Stop(c); c.SetValue(FiredP, true); Fire(c, LongFnP); };
        c.SetValue(TimerP, t);
        t.Start();
    }

    private static void OnMove(object? sender, PointerEventArgs e)
    {
        if (sender is not Control c || c.GetValue(TimerP) is null) return;
        var d = e.GetPosition(c) - c.GetValue(DownAtP);
        if (Math.Abs(d.X) > Slop || Math.Abs(d.Y) > Slop) Stop(c);
    }

    private static void OnUp(object? sender, RoutedEventArgs e)
    {
        if (sender is Control c) Stop(c);
    }

    private static void Stop(Control c)
    {
        if (c.GetValue(TimerP) is not { } t) return;
        t.Stop();
        c.SetValue(TimerP, null);
    }

    private static void OnGotFocus(object? sender, GotFocusEventArgs e)
    {
        if (sender is Control c) Fire(c, FocusFnP);
    }

    private static void OnLostFocus(object? sender, RoutedEventArgs e)
    {
        if (sender is Control c) Fire(c, BlurFnP);
    }

    private static void Fire(Control c, AttachedProperty<int> p)
    {
        var fn = c.GetValue(p);
        if (fn >= 0) c.GetValue(EmitP)?.Invoke(fn, Array.Empty<object>());
    }
}

/// <summary>
/// Button 的 variant / icon 和 Badge 的 tone(SPEC 7.4)。
///
/// <para>三档 variant 全部映射到 <c>Theme/Controls.axaml</c> 里现成的 class:
/// <c>secondary</c> 给 <c>ghost</c>(那一条的标题写的就是「次按钮」),
/// <c>ghost</c> 给 <c>navbtn</c> —— 仓库里唯一无边框、只在悬停时出底色的按钮。</para>
/// </summary>
internal static class PluginButton
{
    private static readonly AttachedProperty<string> IconP =
        AvaloniaProperty.RegisterAttached<Button, Button, string>("BtnIcon", "");
    private static readonly AttachedProperty<string> TextP =
        AvaloniaProperty.RegisterAttached<Button, Button, string>("BtnText", "");

    internal static void SetText(Button b, string text) { b.SetValue(TextP, text); Rebuild(b); }

    internal static void SetIcon(Button b, string name) { b.SetValue(IconP, name); Rebuild(b); }

    internal static void SetVariant(Button b, string variant)
    {
        foreach (var cls in new[] { "primary", "ghost", "navbtn", "danger" }) b.Classes.Remove(cls);
        foreach (var cls in variant switch
        {
            "primary" => new[] { "primary" },
            "ghost" => ["navbtn"],
            "danger" => ["ghost", "danger"],
            _ => ["ghost"],
        }) b.Classes.Add(cls);
    }

    private static readonly AttachedProperty<string> ToneP =
        AvaloniaProperty.RegisterAttached<Border, Border, string>("BadgeTone", "");

    /// <summary>默认那一档也得画出来:tone 不给时 .d.ts 的口径是 neutral,不是「保持建出来的样子」。</summary>
    internal static Border Badge()
    {
        var b = new Border { Padding = new Thickness(6, 2), CornerRadius = new CornerRadius(999) };
        SetTone(b, "neutral");
        return b;
    }

    /// <summary>四档底色。tone 不接的表现是「警告和普通角标一个颜色」,不报错。</summary>
    internal static void SetTone(Border b, string tone)
    {
        b.SetValue(ToneP, tone);
        b.Background = Tok.Of(tone switch
        {
            "accent" => "Accent", "ok" => "Ok", "warn" => "Warn", _ => "PanelAlt",
        });
        ApplyToneInk(b);
    }

    /// <summary>字色跟着底色走。没有 tone 的普通 Border 不碰 —— 它的字色归样式表管。</summary>
    internal static void ApplyToneInk(Border b)
    {
        if (b.GetValue(ToneP) is not { Length: > 0 } tone || b.Child is not TextBlock t) return;
        t.Foreground = Tok.Of(tone == "neutral" ? "Ink2" : "AccentInk");
    }

    /* 图标和标题都设过才知道要摆成哪一版,所以两条路都重画一次整块内容。
       码位查 PluginIcon 那张表 —— 另抄一份的话新加的图标名总有一边认不得。 */
    private static void Rebuild(Button b)
    {
        var icon = b.GetValue(IconP);
        var text = b.GetValue(TextP);
        if (icon.Length == 0) { b.Content = text; return; }
        var glyph = new TextBlock { FontFamily = Glyph.Font, VerticalAlignment = VerticalAlignment.Center };
        if (PluginIcon.GlyphOf(icon) is { } cp) glyph.Text = cp;
        else
        {
            // 和 Icon 组件同一条口径:认不得画空框,留白和「画上了但是透明」在截图上一样
            glyph.FontFamily = FontFamily.Default;
            glyph.Text = "▢";
            Log.W("插件UI", $"没有这个图标名 {icon} —— 按钮上画成了空框");
        }
        b.Content = new StackPanel
        {
            Orientation = Orientation.Horizontal,
            Spacing = 6,
            Children = { glyph, new TextBlock { Text = text, VerticalAlignment = VerticalAlignment.Center } },
        };
    }
}

