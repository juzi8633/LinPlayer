using System;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Media;
using Avalonia.Threading;
using Avalonia.VisualTree;

namespace LinPlayer.Desktop.Views;

/// <summary>
/// 标题跑马灯。放得下就一动不动,放不下才滚。
///
/// <para>☠ 它解的是「长标题把旁边一排按钮挤出屏幕」(用户 2026-09-11)。
/// 原来是 <c>MaxWidth=620 + 省略号</c>:写死的上限在窄窗口上仍然吃掉整条,
/// 右边那几颗功能键被推到画面外,而长片名后半截<b>永远看不到</b>。</para>
///
/// <para>★ 速度写成 <b>像素/秒</b>,不写死总时长 —— 写死的话标题越长划得越快,
/// 而越长的标题恰恰越需要时间读。</para>
/// </summary>
public sealed class Marquee : Decorator
{
    /// <summary>30 px/s。和安卓端那条跑马灯同一个数,两端读起来是一个速度。</summary>
    private const double Speed = 30;
    /// <summary>两头的停顿(秒)。没有它的话字刚滚出来就走,开头那几个字读不到。</summary>
    private const double Hold = 2.0;

    private readonly TextBlock _text = new()
    {
        VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center,
        TextWrapping = TextWrapping.NoWrap,
    };
    private readonly TranslateTransform _shift = new();
    private readonly DispatcherTimer _timer;
    private double _overflow;
    private double _x;
    private double _hold = Hold;
    private DateTime _last = DateTime.UtcNow;

    public Marquee(string text, IBrush? foreground = null, double fontSize = 16,
        FontWeight weight = FontWeight.SemiBold)
    {
        ClipToBounds = true;
        _text.Text = text;
        _text.FontSize = fontSize;
        _text.FontWeight = weight;
        if (foreground is not null) _text.Foreground = foreground;
        _text.RenderTransform = _shift;
        Child = _text;
        ToolTip.SetTip(this, text);   // 滚动要等,鼠标停一下就能看全
        _timer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(16) };
        _timer.Tick += Step;
        AttachedToVisualTree += (_, _) => Pace();
        DetachedFromVisualTree += (_, _) => _timer.Stop();
    }

    public string Text
    {
        get => _text.Text ?? "";
        set
        {
            _text.Text = value;
            ToolTip.SetTip(this, value);
            _x = 0; _hold = Hold;
            _shift.X = 0;
            InvalidateMeasure();
        }
    }

    protected override Size MeasureOverride(Size availableSize)
    {
        // 横向不限:要先知道这行字**真正**有多长,才知道超出多少
        _text.Measure(new Size(double.PositiveInfinity, availableSize.Height));
        var want = _text.DesiredSize;
        var w = double.IsInfinity(availableSize.Width) ? want.Width
            : Math.Min(availableSize.Width, want.Width);
        return new Size(w, want.Height);
    }

    protected override Size ArrangeOverride(Size finalSize)
    {
        var want = _text.DesiredSize.Width;
        _text.Arrange(new Rect(0, 0, Math.Max(want, finalSize.Width), finalSize.Height));
        _overflow = Math.Max(0, want - finalSize.Width);
        Pace();
        return finalSize;
    }

    /// <summary>放得下就**彻底停下来**,不空转一个 60Hz 的定时器。</summary>
    private void Pace()
    {
        if (_overflow > 1 && this.GetVisualRoot() is not null)
        {
            _last = DateTime.UtcNow;
            _timer.Start();
        }
        else { _timer.Stop(); _x = 0; _atEnd = false; _shift.X = 0; }
    }

    private bool _atEnd;

    private void Step(object? sender, EventArgs e)
    {
        var now = DateTime.UtcNow;
        var dt = (now - _last).TotalSeconds;
        _last = now;
        if (_hold > 0)
        {
            _hold -= dt;
            if (_hold > 0) return;
            // 尾巴上那一停结束 = 回到开头,再停一次(开头同样要给时间读)
            if (_atEnd) { _atEnd = false; _x = 0; _shift.X = 0; _hold = Hold; }
            return;
        }
        _x -= Speed * dt;
        if (_x <= -_overflow)
        {
            // 滚到尾就停住;停够了直接跳回开头。**不往回滚** —— 来回走更难读
            _x = -_overflow;
            _atEnd = true;
            _hold = Hold;
        }
        _shift.X = _x;
    }
}
