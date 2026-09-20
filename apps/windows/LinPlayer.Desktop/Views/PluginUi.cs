using System;
using System.Collections.Generic;
using System.Text.Json;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.Primitives;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Threading;
using LinPlayer.Core;
using LinPlayer.Desktop.Core;

namespace LinPlayer.Desktop.Views;

/// <summary>
/// 插件 UI 渲染器(桌面端,SPEC 7.2~7.5)。核心层按帧发 ops,这里照着改原生控件树。
///
/// <para>☠ <b>渲染器不认识组件语义,只认 op</b>:create / props / insert / remove / text。
/// 想加一个组件就在 <see cref="Make"/> 里加一行;想加一个样式属性就在
/// <see cref="ApplyStyle"/> 里加一行。别在这里写业务判断 —— 业务在插件那边。</para>
///
/// <para>★ 未知组件画占位、未知属性直接忽略(D319):老宿主遇到新组件时,
/// 用户看到的是「这一块需要更新 LinPlayer」,而不是整页崩掉或者一片空白。</para>
/// </summary>
public sealed class PluginSurface : UserControl
{
    private static readonly Dictionary<string, PluginSurface> Live = [];

    private readonly CoreClient _core;
    private readonly string _plugin, _target, _kind;
    private readonly object? _props;
    private readonly Dictionary<int, Control> _nodes = [];
    private readonly Dictionary<int, string> _texts = [];
    private readonly Border _host = new();
    private string _surface = "";

    public PluginSurface(CoreClient core, string plugin, string target, string kind = "block", object? props = null)
    {
        _core = core;
        _plugin = plugin;
        _target = target;
        _kind = kind;
        _props = props;
        // 首帧之前是官方骨架屏(D271):留白比转圈更像「内容马上就来」
        _host.Child = Skeleton();
        Content = _host;
        AttachedToVisualTree += (_, _) => _ = Mount();
        DetachedFromVisualTree += (_, _) => Unmount();
    }

    /// <summary>AppJobs 把 plugin.ui / plugin.ui.surface 两个事件转进来。</summary>
    public static void OnUiEvent(string name, JsonElement data)
    {
        var sid = Mi.Str(data, "surface");
        if (sid.Length == 0 || !Live.TryGetValue(sid, out var s)) return;
        if (name == "plugin.ui") s.ApplyFrame(data);
        else s.ApplyState(data);
    }

    private async System.Threading.Tasks.Task Mount()
    {
        if (_surface.Length > 0) return;
        try
        {
            var r = await _core.PluginUiMount(new { plugin = _plugin, target = _target, kind = _kind, props = _props });
            _surface = Mi.Str(r, "surface");
            if (_surface.Length > 0) Live[_surface] = this;
        }
        catch (Exception e)
        {
            Dispatcher.UIThread.Post(() => ShowError(LibraryPage.Advice(e)));
        }
    }

    private void Unmount()
    {
        if (_surface.Length == 0) return;
        var sid = _surface;
        _surface = "";
        Live.Remove(sid);
        _ = _core.PluginUiUnmount(new { surface = sid });
    }

    // ---------------------------------------------------------------- 帧

    private void ApplyState(JsonElement data)
    {
        var state = Mi.Str(data, "state");
        if (state == "error")
        {
            var msg = data.TryGetProperty("error", out var e) ? Mi.Str(e, "message") : "插件出错了";
            ShowError(msg);
        }
    }

    private void ApplyFrame(JsonElement data)
    {
        if (!data.TryGetProperty("ops", out var ops) || ops.ValueKind != JsonValueKind.Array) return;
        foreach (var op in ops.EnumerateArray())
        {
            switch (Mi.Str(op, "op"))
            {
                case "root":
                    _nodes[0] = RootPanel();
                    _host.Child = _nodes[0];
                    break;
                case "create":
                    _nodes[Id(op, "id")] = Make(Mi.Str(op, "type"));
                    break;
                case "text":
                    SetText(Id(op, "id"), Mi.Str(op, "value"));
                    break;
                case "props":
                    ApplyProps(op);
                    break;
                case "insert":
                    Insert(op);
                    break;
                case "remove":
                    Remove(Id(op, "id"));
                    break;
                // canvas 在阶段 ② 的 Canvas 组件里接,这里先不认
            }
        }
    }

    private static int Id(JsonElement o, string k) =>
        o.TryGetProperty(k, out var v) && v.ValueKind == JsonValueKind.Number ? v.GetInt32() : -1;

    private static Panel RootPanel() => new StackPanel { Orientation = Orientation.Vertical };

    private void Insert(JsonElement op)
    {
        var parentId = Id(op, "parent");
        var id = Id(op, "id");
        if (!_nodes.TryGetValue(parentId, out var parent) || !_nodes.TryGetValue(id, out var child)) return;
        // 已经在别处的节点先摘掉:同一个 id 再次 insert 是**移动**,不是新建
        Detach(child);
        var before = op.TryGetProperty("before", out var b) && b.ValueKind == JsonValueKind.Number ? b.GetInt32() : -1;
        var kids = ChildrenOf(parent);
        if (kids == null) return;
        var at = kids.Count;
        if (before >= 0 && _nodes.TryGetValue(before, out var refCtl))
        {
            var i = kids.IndexOf(refCtl);
            if (i >= 0) at = i;
        }
        kids.Insert(at, child);
    }

    private void Remove(int id)
    {
        if (!_nodes.TryGetValue(id, out var c)) return;
        Detach(c);
        // 子树的节点表一起丢,否则一页开开关关几次之后这张表只增不减
        DropSubtree(c);
    }

    private void DropSubtree(Control c)
    {
        foreach (var kv in new List<KeyValuePair<int, Control>>(_nodes))
        {
            if (ReferenceEquals(kv.Value, c) || IsDescendant(kv.Value, c))
            {
                _nodes.Remove(kv.Key);
                _texts.Remove(kv.Key);
            }
        }
    }

    private static bool IsDescendant(Control c, Control root)
    {
        for (var p = c.Parent; p != null; p = p.Parent)
            if (ReferenceEquals(p, root)) return true;
        return false;
    }

    private static void Detach(Control c)
    {
        switch (c.Parent)
        {
            case Panel p: p.Children.Remove(c); break;
            case ContentControl cc when ReferenceEquals(cc.Content, c): cc.Content = null; break;
            case Decorator d when ReferenceEquals(d.Child, c): d.Child = null; break;
        }
    }

    /// <summary>能装子节点的容器:面板直接给集合;单子控件包一层面板,免得「第二个子节点静默消失」。</summary>
    private Avalonia.Controls.Controls? ChildrenOf(Control c) => c switch
    {
        Panel p => p.Children,
        ScrollViewer sv => (sv.Content as Panel ?? Wrap(sv)).Children,
        Border b => (b.Child as Panel ?? Wrap(b)).Children,
        Button btn => (btn.Content as Panel ?? Wrap(btn)).Children,
        _ => null,
    };

    private static Panel Wrap(ScrollViewer sv)
    {
        var p = new StackPanel();
        sv.Content = p;
        return p;
    }

    private static Panel Wrap(Border b)
    {
        var p = new StackPanel();
        b.Child = p;
        return p;
    }

    private static Panel Wrap(Button b)
    {
        var p = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 6 };
        b.Content = p;
        return p;
    }

    private void SetText(int id, string value)
    {
        _texts[id] = value;
        // 文本节点自己没有控件:把它记在父节点上,由父节点(Text / Button)显示
        if (!_nodes.TryGetValue(id, out var c)) return;
        if (c is TextBlock tb) tb.Text = value;
    }

    // ---------------------------------------------------------------- 组件

    private Control Make(string type) => type switch
    {
        "#text" => new TextBlock { TextWrapping = TextWrapping.Wrap },
        "View" or "Column" => new StackPanel { Orientation = Orientation.Vertical },
        "Row" => new StackPanel { Orientation = Orientation.Horizontal },
        "Stack" => new Panel(),
        "ScrollView" => new ScrollViewer { HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled },
        "Text" => new TextBlock { TextWrapping = TextWrapping.Wrap, Foreground = Tok.Of("Ink") },
        "Image" => new Image { Stretch = Stretch.UniformToFill },
        "Divider" => new Border { Height = 1, Background = Tok.Of("Line") },
        "Spinner" => new ProgressBar { IsIndeterminate = true, Height = 3 },
        "Skeleton" => Skeleton(),
        "Button" => new Button { Classes = { "ghost" } },
        "Pressable" => new Button { Classes = { "ghost" }, Padding = new Thickness(0), Background = Brushes.Transparent },
        "TextInput" => new TextBox(),
        "Switch" => new ToggleSwitch(),
        "Checkbox" => new CheckBox(),
        "Slider" => new Slider(),
        "Select" => new ComboBox(),
        "ProgressBar" => new ProgressBar { Height = 4 },
        "Chip" => new Border { Classes = { "chip" }, Padding = new Thickness(10, 6), CornerRadius = new CornerRadius(6), Background = Tok.Of("PanelAlt") },
        "ChipGroup" => new WrapPanel { ItemSpacing = 6, LineSpacing = 6 },
        "SettingsGroup" or "PosterRow" => new StackPanel { Orientation = Orientation.Vertical, Spacing = 10 },
        "PosterGrid" or "EpisodeGrid" => new WrapPanel { ItemSpacing = 14, LineSpacing = 14 },
        "Badge" => new Border { Padding = new Thickness(6, 2), CornerRadius = new CornerRadius(999), Background = Tok.Of("Accent") },
        // ★ 未知组件不是错误,是**版本差**(D319):画一块说明,别让整页空掉
        _ => Unknown(type),
    };

    private static Control Unknown(string type) => new Border
    {
        Padding = new Thickness(10, 6),
        CornerRadius = new CornerRadius(6),
        Background = Tok.Of("PanelAlt"),
        BorderBrush = Tok.Of("Line"),
        BorderThickness = new Thickness(1),
        Child = new TextBlock
        {
            Text = $"这一块需要更新 LinPlayer（{type}）",
            Foreground = Tok.Of("Ink2"),
            TextWrapping = TextWrapping.Wrap,
        },
    };

    private static Control Skeleton() => new Border
    {
        Height = 72,
        CornerRadius = new CornerRadius(10),
        Background = Tok.Of("PanelAlt"),
        Margin = new Thickness(0, 0, 0, 10),
    };

    private void ShowError(string message) => _host.Child = new Border
    {
        Padding = new Thickness(14, 10),
        CornerRadius = new CornerRadius(10),
        Background = Tok.Of("PanelAlt"),
        Child = new TextBlock { Text = "这一块出错了:" + message, Foreground = Tok.Of("Ink2"), TextWrapping = TextWrapping.Wrap },
    };

    // ---------------------------------------------------------------- 属性

    private void ApplyProps(JsonElement op)
    {
        if (!_nodes.TryGetValue(Id(op, "id"), out var c)) return;
        if (op.TryGetProperty("unset", out var un) && un.ValueKind == JsonValueKind.Array)
            foreach (var n in un.EnumerateArray())
                SetProp(c, n.GetString() ?? "", default, true);
        if (op.TryGetProperty("set", out var set) && set.ValueKind == JsonValueKind.Object)
            foreach (var p in set.EnumerateObject())
                SetProp(c, p.Name, p.Value, false);
    }

    private void SetProp(Control c, string name, JsonElement v, bool unset)
    {
        if (name == "style")
        {
            if (!unset && v.ValueKind == JsonValueKind.Object) ApplyStyle(c, v);
            return;
        }
        if (name.StartsWith("on", StringComparison.Ordinal))
        {
            Wire(c, name, unset ? default : v);
            return;
        }
        switch (name)
        {
            case "title" or "label":
                if (c is Button b) b.Content = unset ? null : v.ToString();
                else if (c is TextBlock t) t.Text = unset ? "" : v.ToString();
                else if (c is Border bd && bd.Child is TextBlock bt) bt.Text = unset ? "" : v.ToString();
                break;
            case "value":
                if (c is TextBox tb) tb.Text = unset ? "" : v.ToString();
                else if (c is Slider sl && !unset && v.ValueKind == JsonValueKind.Number) sl.Value = v.GetDouble();
                else if (c is ProgressBar pb && !unset && v.ValueKind == JsonValueKind.Number) pb.Value = v.GetDouble() * 100;
                break;
            case "checked" or "on":
                if (c is ToggleButton tg) tg.IsChecked = !unset && v.ValueKind == JsonValueKind.True;
                break;
            case "placeholder":
                if (c is TextBox tb2) tb2.Watermark = unset ? null : v.ToString();
                break;
            case "src":
                if (c is Image img && !unset) LoadImage(img, v.ToString());
                break;
            case "disabled":
                c.IsEnabled = unset || v.ValueKind != JsonValueKind.True;
                break;
            case "a11yLabel":
                Avalonia.Automation.AutomationProperties.SetName(c, unset ? "" : v.ToString());
                break;
            // 未知属性直接忽略(D319)。**不要**在这里记日志:一帧几百条属性,
            // 老宿主遇到新 SDK 时日志会被刷屏,真正的错误反而看不见。
        }
    }

    private void LoadImage(Image img, string url)
    {
        // 走官方那条取图链路(代理、缓存、脱敏都在里面),别自己 new HttpClient
        _ = System.Threading.Tasks.Task.Run(async () =>
        {
            var bmp = await Images.LoadAsync(_core, url, 720);
            if (bmp != null) Dispatcher.UIThread.Post(() => img.Source = bmp);
        });
    }

    /// <summary>函数属性是 {"$fn":n}:壳这边只记号,点了原样发回去。</summary>
    private void Wire(Control c, string name, JsonElement v)
    {
        var fn = v.ValueKind == JsonValueKind.Object && v.TryGetProperty("$fn", out var f) && f.ValueKind == JsonValueKind.Number
            ? f.GetInt32() : -1;
        switch (name)
        {
            case "onPress" or "onClick":
                if (c is Button btn)
                {
                    btn.Click -= OnClick;
                    btn.Tag = fn;
                    if (fn >= 0) btn.Click += OnClick;
                }
                break;
            case "onChangeText":
                if (c is TextBox tb)
                {
                    tb.Tag = fn;
                    tb.LostFocus -= OnTextCommit;
                    // 非受控(D135):原生自己维护文字,只在失焦/提交时通知 JS
                    if (fn >= 0) tb.LostFocus += OnTextCommit;
                }
                break;
            case "onToggle" or "onChange":
                if (c is ToggleButton tg)
                {
                    tg.Tag = fn;
                    tg.IsCheckedChanged -= OnToggle;
                    if (fn >= 0) tg.IsCheckedChanged += OnToggle;
                }
                break;
            // 其余事件随组件逐个接;没接上的**不许装作接上了** ——
            // 摆着不生效的控件比没有更糟
            default:
                break;
        }
    }

    private void OnClick(object? sender, Avalonia.Interactivity.RoutedEventArgs e)
    {
        if (sender is Control c && c.Tag is int fn && fn >= 0)
            _ = _core.PluginUiEvent(new { surface = _surface, fn, args = Array.Empty<object>() });
    }

    private void OnTextCommit(object? sender, Avalonia.Interactivity.RoutedEventArgs e)
    {
        if (sender is TextBox tb && tb.Tag is int fn && fn >= 0)
            _ = _core.PluginUiEvent(new { surface = _surface, fn, args = new object[] { tb.Text ?? "" } });
    }

    private void OnToggle(object? sender, Avalonia.Interactivity.RoutedEventArgs e)
    {
        if (sender is ToggleButton tg && tg.Tag is int fn && fn >= 0)
            _ = _core.PluginUiEvent(new { surface = _surface, fn, args = new object[] { tg.IsChecked == true } });
    }

    // ---------------------------------------------------------------- 样式(SPEC 7.5)

    private static double? Num(JsonElement o, string k) =>
        o.TryGetProperty(k, out var v) && v.ValueKind == JsonValueKind.Number ? v.GetDouble() : null;

    private static void ApplyStyle(Control c, JsonElement s)
    {
        if (Num(s, "width") is { } w) c.Width = w;
        if (Num(s, "height") is { } h) c.Height = h;
        if (Num(s, "minWidth") is { } mw) c.MinWidth = mw;
        if (Num(s, "minHeight") is { } mh) c.MinHeight = mh;
        if (Num(s, "maxWidth") is { } xw) c.MaxWidth = xw;
        if (Num(s, "maxHeight") is { } xh) c.MaxHeight = xh;
        if (Num(s, "opacity") is { } op) c.Opacity = op;
        if (Num(s, "grow") is { } g && g > 0 && c.Parent is StackPanel) c.HorizontalAlignment = HorizontalAlignment.Stretch;

        c.Margin = Edge(s, "margin", c.Margin);
        if (c is Decorator or TemplatedControl) SetPadding(c, Edge(s, "padding", default));
        if (Num(s, "gap") is { } gap && c is StackPanel sp) sp.Spacing = gap;
        if (Num(s, "radius") is { } r && c is Border bd) bd.CornerRadius = new CornerRadius(r);
        if (Brush(s, "background") is { } bg)
        {
            if (c is Border b2) b2.Background = bg;
            else if (c is Panel p) p.Background = bg;
            else if (c is TemplatedControl tc) tc.Background = bg;
        }
        if (Brush(s, "color") is { } fg && c is TextBlock tbk) tbk.Foreground = fg;
        if (Num(s, "fontSize") is { } fs && c is TextBlock tb2) tb2.FontSize = fs;
        if (Num(s, "maxLines") is { } ml && c is TextBlock tb3) tb3.MaxLines = (int)ml;
        if (s.TryGetProperty("fontWeight", out var fw) && c is TextBlock tb4)
            tb4.FontWeight = fw.ToString() is "bold" or "600" or "700" ? FontWeight.SemiBold : FontWeight.Normal;
        if (s.TryGetProperty("textAlign", out var ta) && c is TextBlock tb5)
            tb5.TextAlignment = ta.ToString() switch { "center" => TextAlignment.Center, "end" or "right" => TextAlignment.Right, _ => TextAlignment.Left };
        if (s.TryGetProperty("direction", out var dir) && c is StackPanel sp2)
            sp2.Orientation = dir.ToString() == "row" ? Orientation.Horizontal : Orientation.Vertical;
        if (s.TryGetProperty("justify", out var ju) && c is StackPanel sp3)
            sp3.HorizontalAlignment = ju.ToString() switch { "center" => HorizontalAlignment.Center, "end" => HorizontalAlignment.Right, _ => HorizontalAlignment.Stretch };
        if (s.TryGetProperty("align", out var al))
            c.VerticalAlignment = al.ToString() switch { "center" => VerticalAlignment.Center, "end" => VerticalAlignment.Bottom, "start" => VerticalAlignment.Top, _ => c.VerticalAlignment };
    }

    private static void SetPadding(Control c, Thickness t)
    {
        switch (c)
        {
            case Decorator d when d is Border b: b.Padding = t; break;
            case TemplatedControl tc: tc.Padding = t; break;
        }
    }

    /// <summary>padding / margin:给一个数是四边,给 *Top 这类就单独覆盖那一边。</summary>
    private static Thickness Edge(JsonElement s, string prefix, Thickness fallback)
    {
        var all = Num(s, prefix);
        if (all == null
            && Num(s, prefix + "Top") == null && Num(s, prefix + "Right") == null
            && Num(s, prefix + "Bottom") == null && Num(s, prefix + "Left") == null)
            return fallback;
        var b = all ?? 0;
        return new Thickness(
            Num(s, prefix + "Left") ?? b, Num(s, prefix + "Top") ?? b,
            Num(s, prefix + "Right") ?? b, Num(s, prefix + "Bottom") ?? b);
    }

    /// <summary>颜色:`token:名字` 走主题(D89),`#rrggbb` 直接解析。</summary>
    private static IBrush? Brush(JsonElement s, string k)
    {
        if (!s.TryGetProperty(k, out var v) || v.ValueKind != JsonValueKind.String) return null;
        var text = v.GetString() ?? "";
        if (text.StartsWith("token:", StringComparison.Ordinal)) return Tok.Of(text[6..]);
        return Color.TryParse(text, out var col) ? new SolidColorBrush(col) : null;
    }

}
