using System;
using System.Linq;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Layout;

namespace LinPlayer.Desktop.Views;

/// <summary>
/// 插件 UI 的布局面板(SPEC 7.5 的 Flex 子集)。
///
/// <para>☠ StackPanel 表达不了这套东西:没有 justify、没有 grow,做出来是
/// 「标签和值挤在一起」而且不报错。自己写一个比「按 justify 换容器类型」省。</para>
///
/// <para>只做用得到的档:主轴 direction / justify / gap,交叉轴 align,子项 grow。
/// 加一档在这里加一行。</para>
/// </summary>
public sealed class FlexPanel : Panel
{
    public static readonly AttachedProperty<double> GrowProperty =
        AvaloniaProperty.RegisterAttached<FlexPanel, Control, double>("Grow");

    public static void SetGrow(Control c, double v) => c.SetValue(GrowProperty, v);
    public static double GetGrow(Control c) => c.GetValue(GrowProperty);

    /// <summary>row = 横向主轴。</summary>
    public bool Horizontal { get; set; }

    public double Gap { get; set; }

    /// <summary>start / center / end / between / around / evenly。</summary>
    public string Justify { get; set; } = "start";

    /// <summary>start / center / end / stretch。</summary>
    public string AlignItems { get; set; } = "stretch";

    /// <summary>Avalonia 的 Panel 没有 Padding,而 SPEC 7.5 里每个容器都能有 —— 自己算。</summary>
    public Thickness Pad { get; set; }

    private Control[] Kids => Children.Where(c => c.IsVisible).ToArray();

    protected override Size MeasureOverride(Size available)
    {
        var kids = Kids;
        if (kids.Length == 0) return default;
        var inner = available.Deflate(Pad);
        var inf = new Size(
            Horizontal ? double.PositiveInfinity : inner.Width,
            Horizontal ? inner.Height : double.PositiveInfinity);
        double main = 0, cross = 0;
        foreach (var c in kids)
        {
            c.Measure(inf);
            main += Horizontal ? c.DesiredSize.Width : c.DesiredSize.Height;
            cross = Math.Max(cross, Horizontal ? c.DesiredSize.Height : c.DesiredSize.Width);
        }
        main += Gap * (kids.Length - 1);
        var size = Horizontal ? new Size(main, cross) : new Size(cross, main);
        return size.Inflate(Pad);
    }

    protected override Size ArrangeOverride(Size final)
    {
        var kids = Kids;
        if (kids.Length == 0) return final;

        var box = final.Deflate(Pad);
        var total = Horizontal ? box.Width : box.Height;
        var used = kids.Sum(c => Horizontal ? c.DesiredSize.Width : c.DesiredSize.Height) + Gap * (kids.Length - 1);
        var growSum = kids.Sum(GetGrow);
        var slack = Math.Max(0, total - used);

        // grow 优先吃掉剩余空间;没人 grow 才轮到 justify 分配
        var extraFor = new double[kids.Length];
        if (growSum > 0)
        {
            for (var i = 0; i < kids.Length; i++) extraFor[i] = slack * GetGrow(kids[i]) / growSum;
            slack = 0;
        }

        var (lead, between) = Spread(Justify, slack, kids.Length, Gap);
        var pos = lead + (Horizontal ? Pad.Left : Pad.Top);
        var crossOff = Horizontal ? Pad.Top : Pad.Left;
        for (var i = 0; i < kids.Length; i++)
        {
            var c = kids[i];
            var mainSize = (Horizontal ? c.DesiredSize.Width : c.DesiredSize.Height) + extraFor[i];
            var crossAvail = Horizontal ? box.Height : box.Width;
            var crossSize = Horizontal ? c.DesiredSize.Height : c.DesiredSize.Width;
            var (crossPos, crossLen) = CrossFit(AlignItems, crossSize, crossAvail);
            c.Arrange(Horizontal
                ? new Rect(pos, crossOff + crossPos, mainSize, crossLen)
                : new Rect(crossOff + crossPos, pos, crossLen, mainSize));
            pos += mainSize + between;
        }
        return final;
    }

    /// <summary>主轴上把多余的空间分掉:返回(起始偏移, 每两项之间的间距)。</summary>
    private static (double Lead, double Between) Spread(string justify, double slack, int n, double gap)
    {
        if (slack <= 0 || n == 0) return (0, gap);
        return justify switch
        {
            "center" => (slack / 2, gap),
            "end" => (slack, gap),
            "between" => n > 1 ? (0, gap + slack / (n - 1)) : (0, gap),
            "around" => (slack / n / 2, gap + slack / n),
            "evenly" => (slack / (n + 1), gap + slack / (n + 1)),
            _ => (0, gap),
        };
    }

    private static (double Pos, double Len) CrossFit(string align, double desired, double available) => align switch
    {
        "center" => ((available - desired) / 2, desired),
        "end" => (available - desired, desired),
        "start" => (0, desired),
        _ => (0, available), // stretch
    };
}
