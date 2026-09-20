using System;
using System.Collections.Generic;
using System.Linq;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.Templates;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Media.Transformation;
using LinPlayer.Desktop.Core;

namespace LinPlayer.Desktop.Views;

/// <summary>
/// 播放页右侧贴边的选集栏(用户 2026-09-18:「贴右侧边出现选集列表 一个封面 封面旁边
/// 第一行是集数和标题 第二行稍小是参数」)。
///
/// <para>替掉的是一个贴着按钮弹出的纯文字列表:只写 S01E01,看不出哪集是哪集,
/// 悬停也没有反馈。章节和选集同在这一栏,上面两颗切换 —— 它们是「跳到别的集 /
/// 跳到本集某一段」同一件事的两个粒度。</para>
/// </summary>
internal sealed class EpisodeDrawer : Border
{
    public const double DrawerWidth = 420;
    private const double CoverW = 136, CoverH = 76;

    private readonly ContentControl _body = new();
    private readonly List<Button> _tabs = [];

    public EpisodeDrawer(CoreClient core, string server, IReadOnlyList<CardItem> episodes, string currentId,
        IReadOnlyList<(double At, string Label)> chapters, Action<CardItem> onPick, Action<double> onChapter,
        Action onClose, IReadOnlyList<PlayerSurfaceInfo>? panels = null)
    {
        Width = DrawerWidth;
        HorizontalAlignment = HorizontalAlignment.Right;
        // 叠在画面上的遮罩,不走主题 token:底下永远是片子,换浅色主题它也得是暗的
        Background = new SolidColorBrush(Color.Parse("#f80b0d12"));
        BorderBrush = new SolidColorBrush(Color.Parse("#1fffffff"));
        BorderThickness = new Thickness(1, 0, 0, 0);
        Padding = new Thickness(0, 14, 0, 0);

        var close = new Button
        {
            Classes = { "osd" }, Content = "\uE711", FontSize = 14, Width = 34, Height = 34,
            Focusable = false, HorizontalAlignment = HorizontalAlignment.Right,
        };
        ToolTip.SetTip(close, "收起(Esc)");
        close.Click += (_, _) => onClose();

        var tabs = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 6 };
        void Tab(object content, Func<Control> make)
        {
            var b = new Button { Classes = { "chip" }, Content = content, Focusable = false };
            b.Click += (_, _) =>
            {
                foreach (var t in _tabs) t.Classes.Set("on", t == b);
                _body.Content = make();
            };
            _tabs.Add(b);
            tabs.Children.Add(b);
        }
        if (episodes.Count > 0)
            Tab($"选集 · {episodes.Count}", () => EpisodeList(core, server, episodes, currentId, onPick));
        if (chapters.Count > 0)
            Tab($"章节 · {chapters.Count}", () => ChapterList(chapters, onChapter));
        foreach (var p in panels ?? [])
        {
            // 造一次留着:make 每次点标签都调,不存的话来回切两下就是两次挂载 + 两次插件重渲染
            Control? made = null;
            Tab(TabHead(p), () => made ??= new PluginSurface(core, p.Plugin, p.Target, "panel"));
        }

        var head = new Grid
        {
            ColumnDefinitions = new ColumnDefinitions("*,Auto"),
            Margin = new Thickness(18, 0, 10, 10),
            Children = { tabs, close },
        };
        Grid.SetColumn(close, 1);
        DockPanel.SetDock(head, Dock.Top);
        Child = new DockPanel { Children = { head, _body } };

        // 点一下第一颗而不是照抄它的内容:抄的那一版写死了「不是选集就是章节」,
        // 插件侧栏页成了唯一一颗时会去画一张空的章节表
        if (_tabs.Count > 0) _tabs[0].RaiseEvent(new Avalonia.Interactivity.RoutedEventArgs(Button.ClickEvent));
    }

    /// <summary>插件侧栏页的标签:manifest 给了 <c>icon</c> 就图标 + 名字(D300)。</summary>
    private static object TabHead(PlayerSurfaceInfo p)
    {
        var title = p.Title.Length > 0 ? p.Title : p.Plugin;
        if (PluginIcon.GlyphOf(p.Icon) is not { } cp) return title;
        return new StackPanel
        {
            Orientation = Orientation.Horizontal,
            Spacing = 6,
            Children =
            {
                new TextBlock { Text = cp, FontFamily = Glyph.Font, VerticalAlignment = VerticalAlignment.Center },
                new TextBlock { Text = title, VerticalAlignment = VerticalAlignment.Center },
            },
        };
    }

    /// <summary>
    /// 分集表。<b>虚拟化</b>:长篇番剧上千集,全造出来打开这一栏就要卡半秒。
    /// 打开时把正在放的那一集滚进视野 —— 第 500 集时停在第 1 集等于让人自己翻。
    /// </summary>
    private static Control EpisodeList(CoreClient core, string server, IReadOnlyList<CardItem> eps,
        string currentId, Action<CardItem> onPick)
    {
        var list = new ItemsControl
        {
            ItemsSource = eps,
            ItemsPanel = new FuncTemplate<Panel?>(() => new VirtualizingStackPanel()),
            ItemTemplate = new FuncDataTemplate<CardItem>((e, _) =>
                e is null ? new Panel() : Row(core, server, e, e.Id == currentId, onPick)),
        };
        var sv = new ScrollViewer
        {
            Content = list, HorizontalScrollBarVisibility = Avalonia.Controls.Primitives.ScrollBarVisibility.Disabled,
            Padding = new Thickness(10, 0, 10, 14),
        };
        var at = eps.ToList().FindIndex(e => e.Id == currentId);
        if (at > 0)
        {
            // 行高是定的,直接算偏移:等容器造出来再 BringIntoView 的话虚拟化下那一行根本还不存在
            sv.AttachedToVisualTree += (_, _) => Avalonia.Threading.Dispatcher.UIThread.Post(() =>
                sv.Offset = new Vector(0, Math.Max(0, at * RowHeight - CoverH)),
                Avalonia.Threading.DispatcherPriority.Loaded);
        }
        return sv;
    }

    /// <summary>一行的高度:封面 + 上下内边距 + 行间距。滚到当前集要用它算偏移。</summary>
    private const double RowHeight = CoverH + 12 + 6;

    private static Control Row(CoreClient core, string server, CardItem e, bool current, Action<CardItem> onPick)
    {
        var img = new Image { Classes = { "art" }, Stretch = Stretch.UniformToFill, Opacity = 0 };
        var skel = new Border { Background = new SolidColorBrush(Color.Parse("#1affffff")) };
        var ph = new TextBlock
        {
            Text = e.EpisodeNo > 0 ? e.EpisodeNo.ToString() : "·", IsVisible = false,
            FontSize = 22, FontWeight = FontWeight.SemiBold, Foreground = new SolidColorBrush(Color.Parse("#66ffffff")),
            HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center,
        };
        var cover = new Border
        {
            Classes = { "cover" }, Width = CoverW, Height = CoverH, CornerRadius = new CornerRadius(6),
            ClipToBounds = true, Child = new Panel { Children = { skel, ph, img } },
        };
        if (e.HasPrimary) Card.StartArt(core, server, e, img, (int)(CoverH * 2), skel, ph);
        else { skel.IsVisible = false; ph.IsVisible = true; }

        // 看到一半的集在封面底下压一条进度 —— 和卡片同一个口径
        if (e.Progress > 0 && !e.Played)
        {
            ((Panel)cover.Child).Children.Add(new Border
            {
                Height = 3, VerticalAlignment = VerticalAlignment.Bottom, HorizontalAlignment = HorizontalAlignment.Left,
                Width = CoverW * e.Progress, Background = Tok.Of("Accent"),
            });
        }

        var code = e.EpisodeNo > 0 ? $"第 {e.EpisodeNo} 集" : "";
        var title = new TextBlock
        {
            Text = code.Length > 0 && e.Name != code ? $"{code}  {e.Name}" : e.Name,
            FontSize = 14, FontWeight = current ? FontWeight.SemiBold : FontWeight.Medium,
            Foreground = current ? Tok.Of("Accent") : Brushes.White,
            TextTrimming = TextTrimming.CharacterEllipsis, MaxLines = 2, TextWrapping = TextWrapping.Wrap,
        };
        var spec = new TextBlock
        {
            Text = current ? "正在播放" + (e.SpecLabel.Length > 0 ? " · " + e.SpecLabel : "") : e.SpecLabel,
            FontSize = 12, Foreground = new SolidColorBrush(Color.Parse("#99ffffff")),
            TextTrimming = TextTrimming.CharacterEllipsis,
        };
        var text = new StackPanel
        {
            Spacing = 6, VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(14, 0, 0, 0),
            Children = { title, spec },
        };
        var grid = new Grid { ColumnDefinitions = new ColumnDefinitions("Auto,*"), Children = { cover, text } };
        Grid.SetColumn(text, 1);

        var b = new Button
        {
            Classes = { "eprow" }, Content = grid, Focusable = false,
            Margin = new Thickness(0, 0, 0, 6),
        };
        if (current) b.Classes.Add("on");
        b.Click += (_, _) => onPick(e);
        return b;
    }

    private static Control ChapterList(IReadOnlyList<(double At, string Label)> chapters, Action<double> onChapter)
    {
        var col = new StackPanel { Spacing = 2 };
        foreach (var (at, label) in chapters)
        {
            var b = new Button
            {
                Classes = { "eprow" }, Focusable = false,
                Content = new TextBlock { Text = label, FontSize = 13.5, Foreground = Brushes.White },
            };
            b.Click += (_, _) => onChapter(at);
            col.Children.Add(b);
        }
        return new ScrollViewer { Content = col, Padding = new Thickness(10, 0, 10, 14) };
    }

    /// <summary>滑进 / 滑出。位移只在 RenderTransform 上动,不进布局。</summary>
    public static void Slide(Control c, bool open)
    {
        c.RenderTransform = TransformOperations.Parse(open ? "translateX(0px)" : $"translateX({DrawerWidth}px)");
        c.Opacity = open ? 1 : 0;
        c.IsHitTestVisible = open;
    }
}
