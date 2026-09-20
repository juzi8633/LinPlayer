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
/// <para>未知组件画占位、未知属性直接忽略(D319):老宿主遇到新组件时,
/// 用户看到的是「这一块需要更新 LinPlayer」,而不是整页崩掉或者一片空白。</para>
/// </summary>
public sealed class PluginSurface : UserControl
{
    private static readonly Dictionary<string, PluginSurface> Live = [];

    private readonly CoreClient _core;
    private readonly string _plugin, _target, _kind;
    private readonly object? _props;
    /* ☠ 值是 object 不是 Control:文本节点必须是 Run。
        第一版把 #text 也做成 TextBlock,于是 <Text>你好</Text> 里那个文本节点
        找不到能塞它的容器(TextBlock 不是 Panel),被静默丢掉 ——
        截图上是「整页只剩一个按钮,别处一片空白」,而且不报错。 */
    private readonly Dictionary<int, object> _nodes = [];
    private readonly Border _host = new();
    private string _surface = "";
    /// <summary>挂载起点。首帧预算(D543 的 300ms)量的是这里到第一批 ops 画完。</summary>
    private readonly System.Diagnostics.Stopwatch _since = System.Diagnostics.Stopwatch.StartNew();
    private bool _firstFrameLogged;

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
        // 尺寸变了报一次视口(SPEC 7.7)。桌面没有刘海,insets 恒为 0
        SizeChanged += (_, e) => ReportViewport(e.NewSize);
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
            // 首帧跟着 mount 的返回值来:等事件的话会漏掉它(见 core/plugin/ui.go 那条)
            Dispatcher.UIThread.Post(() => { ApplyFrame(r); ReportViewport(Bounds.Size); });
        }
        catch (Exception e)
        {
            Dispatcher.UIThread.Post(() => ShowError(LibraryPage.Advice(e)));
        }
    }

    private string _lastViewport = "";

    /// <summary>断点阈值与官方页同一套(D217):算两遍迟早分叉。</summary>
    private static string BreakpointOf(double w) => w < 600 ? "compact" : w < 1000 ? "medium" : "expanded";

    private void ReportViewport(Size size)
    {
        if (_surface.Length == 0 || size.Width <= 0) return;
        var bp = BreakpointOf(size.Width);
        var key = $"{(int)size.Width}x{(int)size.Height}:{bp}";
        // 没变就不发:窗口拖动时 SizeChanged 一秒几十条
        if (key == _lastViewport) return;
        _lastViewport = key;
        _ = _core.PluginUiViewport(new
        {
            surface = _surface, width = size.Width, height = size.Height,
            breakpoint = bp, formFactor = "desktop",
            insets = new { top = 0, right = 0, bottom = 0, left = 0 },
        });
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
                    {
                        var root = RootPanel();
                        _nodes[0] = root;
                        _host.Child = root;
                        break;
                    }
                case "create":
                    {
                        var type = Mi.Str(op, "type");
                        _nodes[Id(op, "id")] = type == "#text"
                            ? new Avalonia.Controls.Documents.Run()
                            : Make(type);
                        break;
                    }
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
                case "canvas":
                    if (_nodes.TryGetValue(Id(op, "id"), out var cv) && cv is PluginCanvas pc
                        && op.TryGetProperty("cmds", out var cmds))
                        pc.SetCommands(cmds);
                    break;
            }
        }
        // 一帧里可能增删了窗口内的项,占位要跟着重算
        foreach (var n in _nodes.Values) if (n is ScrollViewer sv) VirtualInfo.Respace(sv);
        if (!_firstFrameLogged)
        {
            _firstFrameLogged = true;
            Log.I("插件UI", $"{_plugin}/{_target} 首帧 {_since.ElapsedMilliseconds} ms,{_nodes.Count} 个节点");
        }
    }

    private static int Id(JsonElement o, string k) =>
        o.TryGetProperty(k, out var v) && v.ValueKind == JsonValueKind.Number ? v.GetInt32() : -1;

    private static Panel RootPanel() => new FlexPanel();

    private void Insert(JsonElement op)
    {
        var parentId = Id(op, "parent");
        var id = Id(op, "id");
        if (!_nodes.TryGetValue(parentId, out var parent) || !_nodes.TryGetValue(id, out var child)) return;
        var before = op.TryGetProperty("before", out var b) && b.ValueKind == JsonValueKind.Number ? b.GetInt32() : -1;
        _nodes.TryGetValue(before, out var refNode);

        // 文本节点进父 TextBlock 的 Inlines:多段文本、顺序、更新全都由它管
        if (child is Avalonia.Controls.Documents.Run run)
        {
            var host = parent as TextBlock ?? TextHostOf(parent);
            if (host == null) return;
            host.Inlines ??= [];
            host.Inlines.Remove(run);
            var at = host.Inlines.Count;
            if (refNode is Avalonia.Controls.Documents.Run rr)
            {
                var k = host.Inlines.IndexOf(rr);
                if (k >= 0) at = k;
            }
            host.Inlines.Insert(at, run);
            return;
        }
        if (child is not Control cc || parent is not Control pc) return;
        // 已经在别处的节点先摘掉:同一个 id 再次 insert 是移动,不是新建
        Detach(cc);
        var kids = ChildrenOf(pc);
        if (kids == null) return;
        var at2 = kids.Count;
        if (refNode is Control refCtl)
        {
            var k2 = kids.IndexOf(refCtl);
            if (k2 >= 0) at2 = k2;
        }
        kids.Insert(at2, cc);
    }

    /// <summary>不是 TextBlock 的父节点收到文本时,给它补一个 TextBlock 装(Chip 那种 Border)。</summary>
    private static TextBlock? TextHostOf(object parent)
    {
        switch (parent)
        {
            case Border bo:
                if (bo.Child is TextBlock e1) return e1;
                var t1 = new TextBlock { TextWrapping = TextWrapping.Wrap };
                bo.Child = t1;
                return t1;
            case ContentControl co:
                if (co.Content is TextBlock e2) return e2;
                var t2 = new TextBlock { TextWrapping = TextWrapping.Wrap };
                co.Content = t2;
                return t2;
            case Panel pa:
                var t3 = new TextBlock { TextWrapping = TextWrapping.Wrap };
                pa.Children.Add(t3);
                return t3;
            default:
                return null;
        }
    }

    private void Remove(int id)
    {
        if (!_nodes.TryGetValue(id, out var n)) return;
        if (n is Avalonia.Controls.Documents.Run run)
        {
            (run.Parent as TextBlock)?.Inlines?.Remove(run);
            _nodes.Remove(id);
            return;
        }
        if (n is not Control c) return;
        Detach(c);
        // 子树的节点表一起丢,否则一页开开关关几次之后这张表只增不减
        DropSubtree(c);
    }

    private void DropSubtree(Control c)
    {
        foreach (var kv in new List<KeyValuePair<int, object>>(_nodes))
        {
            if (ReferenceEquals(kv.Value, c) || (kv.Value is Control kc && IsDescendant(kc, c)))
                _nodes.Remove(kv.Key);
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
        if (!_nodes.TryGetValue(id, out var n)) return;
        if (n is Avalonia.Controls.Documents.Run run) run.Text = value;
        else if (n is TextBlock tb) tb.Text = value;
    }

    // ---------------------------------------------------------------- 组件

    private Control Make(string type) => type switch
    {
        "#text" => new TextBlock { TextWrapping = TextWrapping.Wrap },
        "View" or "Column" => new FlexPanel(),
        "Row" => new FlexPanel { Horizontal = true },
        "Stack" => new Panel(),
        "ScrollView" => new ScrollViewer { HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled },
        "Text" => new TextBlock { TextWrapping = TextWrapping.Wrap, Foreground = Tok.Of("Ink") },
        "Image" => new Image { Stretch = Stretch.UniformToFill },
        "Canvas" => new PluginCanvas(),
        "Divider" => new Border { Height = 1, Background = Tok.Of("Line") },
        "Spinner" => new ProgressBar { IsIndeterminate = true, Height = 3 },
        "Skeleton" => Skeleton(),
        "Button" => new Button { Classes = { "ghost" } },
        "Pressable" => new Button { Classes = { "ghost" }, Padding = new Thickness(0), Background = Brushes.Transparent },
        "TextInput" => new TextBox(),
        // OnContent/OffContent 置空:Avalonia 默认会在开关边上写 "On"/"Off",
        // 那是它自己的英文文案,不是插件给的内容
        "Switch" => new ToggleSwitch { OnContent = null, OffContent = null },
        "Checkbox" => new CheckBox(),
        "Slider" => new Slider(),
        "Select" => new ComboBox(),
        "ProgressBar" => new ProgressBar { Height = 4 },
        "Chip" => new Border { Classes = { "chip" }, Padding = new Thickness(10, 6), CornerRadius = new CornerRadius(6), Background = Tok.Of("PanelAlt") },
        "ChipGroup" => new WrapPanel { ItemSpacing = 6, LineSpacing = 6 },
        "SettingsGroup" or "PosterRow" => new FlexPanel { Gap = 10 },
        "PosterGrid" or "EpisodeGrid" => new WrapPanel { ItemSpacing = 14, LineSpacing = 14 },
        /* 大列表(D134):壳只画可见范围,并把范围回报给 JS。
           桌面这一版用 ScrollViewer + FlexPanel:JS 那边已经只给一窗的节点,
           所以这里画的本来就只有几十个 —— 真正的虚拟化收益在 JS 那一侧。
           滚动时按像素估算可见范围回报;itemHeight 没给就按 56 估。 */
        "VirtualList" or "VirtualGrid" => new ScrollViewer
        {
            HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled,
            Content = new FlexPanel(),
        },
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
        if (!_nodes.TryGetValue(Id(op, "id"), out var pn) || pn is not Control c) return;
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
                else if (c is Border bd && TextHostOf(bd) is { } bt) bt.Text = unset ? "" : v.ToString();
                break;
            // Switch / Checkbox 在定义源里就叫 value(不是 checked)——
            // 名字对不上的表现是「开关画出来了但永远是关的」,不报错
            case "value" or "checked":
                if (c is ToggleButton tg) tg.IsChecked = !unset && v.ValueKind == JsonValueKind.True;
                else if (c is TextBox tb) tb.Text = unset ? "" : v.ToString();
                else if (c is Slider sl && !unset && v.ValueKind == JsonValueKind.Number) sl.Value = v.GetDouble();
                else if (c is ProgressBar pb && !unset && v.ValueKind == JsonValueKind.Number) pb.Value = v.GetDouble() * 100;
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
            case "itemCount" or "itemHeight" or "firstIndex":
                // 大列表的三件套记在控件上:滚动回调要算可见范围,占位要算上下留白
                if (c is ScrollViewer sv2 && !unset && v.ValueKind == JsonValueKind.Number)
                {
                    VirtualInfo.Set(sv2, name, v.GetDouble());
                    VirtualInfo.Respace(sv2);
                }
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
            case "onRange":
                if (c is ScrollViewer sv3)
                {
                    VirtualInfo.SetRangeFn(sv3, fn);
                    sv3.ScrollChanged -= OnVirtualScroll;
                    if (fn >= 0) sv3.ScrollChanged += OnVirtualScroll;
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

    /// <summary>滚动时把可见范围回报给 JS(带一屏余量,否则一滑就看见空白)。</summary>
    private void OnVirtualScroll(object? sender, ScrollChangedEventArgs e)
    {
        if (sender is not ScrollViewer sv) return;
        var (fn, count, itemH) = VirtualInfo.Get(sv);
        if (fn < 0 || count <= 0 || itemH <= 0) return;
        const int Slack = 12;
        var first = Math.Max(0, (int)(sv.Offset.Y / itemH) - Slack);
        var last = Math.Min(count, (int)((sv.Offset.Y + sv.Viewport.Height) / itemH) + Slack + 1);
        if (!VirtualInfo.RangeChanged(sv, first, last)) return;
        _ = _core.PluginUiEvent(new { surface = _surface, fn, args = new object[] { new { from = first, to = last } } });
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
        if (Num(s, "grow") is { } g) FlexPanel.SetGrow(c, g);

        c.Margin = Edge(s, "margin", c.Margin);
        SetPadding(c, Edge(s, "padding", default));
        if (Num(s, "gap") is { } gap && c is FlexPanel fp) { fp.Gap = gap; fp.InvalidateMeasure(); }
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
        if (c is FlexPanel fl)
        {
            if (s.TryGetProperty("direction", out var dir)) fl.Horizontal = dir.ToString() == "row";
            if (s.TryGetProperty("justify", out var ju)) fl.Justify = ju.ToString();
            if (s.TryGetProperty("align", out var al)) fl.AlignItems = al.ToString();
            fl.InvalidateMeasure();
        }
        else if (s.TryGetProperty("align", out var al2))
        {
            c.VerticalAlignment = al2.ToString() switch
            {
                "center" => VerticalAlignment.Center, "end" => VerticalAlignment.Bottom,
                "start" => VerticalAlignment.Top, _ => c.VerticalAlignment,
            };
        }
    }

    private static void SetPadding(Control c, Thickness t)
    {
        switch (c)
        {
            case FlexPanel fp: fp.Pad = t; fp.InvalidateMeasure(); break;
            case Border b: b.Padding = t; break;
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

/// <summary>
/// 插件页的整页容器(SPEC 7.2 的 page surface)。标题是官方的,内容整块交给插件。
/// </summary>
public sealed class PluginPageHost : PageBase
{
    public PluginPageHost(CoreClient core, string plugin, string pageId, string title)
    {
        Content = Scrolled(new StackPanel
        {
            Spacing = 14,
            Children =
            {
                H1(title),
                new PluginSurface(core, plugin, pageId, "page"),
            },
        });
    }
}


/// <summary>大列表在控件上记的那几件事(itemCount / itemHeight / 回调号 / 上次报过的范围)。</summary>
internal static class VirtualInfo
{
    private static readonly AttachedProperty<double> CountP =
        AvaloniaProperty.RegisterAttached<Control, Control, double>("VCount");
    private static readonly AttachedProperty<double> HeightP =
        AvaloniaProperty.RegisterAttached<Control, Control, double>("VHeight");
    private static readonly AttachedProperty<int> FnP =
        AvaloniaProperty.RegisterAttached<Control, Control, int>("VFn", -1);
    private static readonly AttachedProperty<double> FirstP =
        AvaloniaProperty.RegisterAttached<Control, Control, double>("VFirst");
    private static readonly AttachedProperty<string> LastP =
        AvaloniaProperty.RegisterAttached<Control, Control, string>("VLast", "");

    internal static void Set(Control c, string name, double v)
    {
        if (name == "itemCount") c.SetValue(CountP, v);
        else if (name == "itemHeight") c.SetValue(HeightP, v);
        else if (name == "firstIndex") c.SetValue(FirstP, v);
    }

    internal static void SetRangeFn(Control c, int fn) => c.SetValue(FnP, fn);

    /* 上下各垫一块空白:上面 firstIndex 项、下面剩下的那些。
       ☠ 不垫的话窗口一滑,壳画的那几十条永远贴在顶上,而滚动位置还停在别处 ——
       表现是「滑着滑着内容跳回去了」,而且不报错。滚动条长度也靠这两块撑出来。 */
    internal static void Respace(ScrollViewer sv)
    {
        if (sv.Content is not FlexPanel fp) return;
        var count = (int)sv.GetValue(CountP);
        if (count <= 0) return;
        var itemH = sv.GetValue(HeightP) is > 0 and var h ? h : 56;
        var first = (int)sv.GetValue(FirstP);
        var tail = Math.Max(0, count - first - fp.Children.Count);
        var want = new Thickness(0, first * itemH, 0, tail * itemH);
        if (fp.Pad != want) { fp.Pad = want; fp.InvalidateMeasure(); }
    }

    internal static (int Fn, int Count, double ItemH) Get(Control c) =>
        (c.GetValue(FnP), (int)c.GetValue(CountP), c.GetValue(HeightP) is > 0 and var h ? h : 56);

    /// <summary>范围没变就不发:滚动事件一秒几十条,每条都发等于自己做 DDoS。</summary>
    internal static bool RangeChanged(Control c, int from, int to)
    {
        var key = from + ":" + to;
        if (c.GetValue(LastP) == key) return false;
        c.SetValue(LastP, key);
        return true;
    }
}
