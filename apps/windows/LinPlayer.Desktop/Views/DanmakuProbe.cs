using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using System.Threading;
using Avalonia.Controls;
using Avalonia.Media;
using Avalonia.Threading;

namespace LinPlayer.Desktop.Views;

/// <summary>
/// 弹幕层探针:把<b>真的</b> <see cref="DanmakuLayer"/> 开起来数帧。
///
/// <para>它拦的是一类编译器管不着的退化:弹幕画在<b>合成器线程</b>上,而 UI 那条
/// 渲染 pass 被 Avalonia 写死在 60Hz。谁哪天把它改回 <c>Control.Render</c>,
/// 60 帧就悄悄回来了 —— 编译绿、单测绿、只有真开一个窗口数帧才看得见。</para>
/// </summary>
internal static class DanmakuProbe
{
    [DllImport("user32.dll")] private static extern IntPtr GetDC(IntPtr hWnd);
    [DllImport("user32.dll")] private static extern int ReleaseDC(IntPtr hWnd, IntPtr hdc);
    [DllImport("gdi32.dll")] private static extern int GetDeviceCaps(IntPtr hdc, int index);

    /// <summary>主显示器刷新率(GetDeviceCaps 的 VREFRESH=116)。取不到按 60 算。</summary>
    internal static int RefreshHz()
    {
        var dc = GetDC(IntPtr.Zero);
        if (dc == IntPtr.Zero) return 60;
        try
        {
            var hz = GetDeviceCaps(dc, 116);
            return hz is > 1 and <= 480 ? hz : 60;   // 0/1 是「默认/未知」的约定值
        }
        finally { ReleaseDC(IntPtr.Zero, dc); }
    }

    /// <summary>三条各走一种 mode:1=滚动、4=底部固定、5=顶部固定。</summary>
    private static DmLayout Fake() => new()
    {
        ResX = 1920, ResY = 1080, LaneHeight = 54, RollSeconds = 8,
        FixSeconds = 5, FontSize = 40, Opacity = 1,
        Items = new List<DmItem>
        {
            new() { T = 0, Mode = 1, Lane = 0, W = 200, Color = 0xFFFFFF, Text = "滚动弹幕测试" },
            new() { T = 0.5, Mode = 4, Lane = 0, W = 160, Color = 0x66CCFF, Text = "底部固定" },
            new() { T = 1.0, Mode = 5, Lane = 1, W = 160, Color = 0xFFCC66, Text = "顶部固定" },
        },
    };

    /// <summary>开个真窗口跑 3 秒。<c>LP_DMPROBE=1 LinPlayer.exe</c></summary>
    internal static bool Run()
    {
        var dm = new DanmakuLayer();
        var w = new Window
        {
            Width = 800, Height = 450, ShowInTaskbar = false,
            SystemDecorations = SystemDecorations.None,
            Content = new Border { Background = Brushes.Black, Child = dm },
        };
        w.Show();

        // 合成器帧跑在渲染线程上,不走 dispatcher —— 泵消息只是让窗口活着
        static void Pump(int ms)
        {
            var t0 = DateTime.UtcNow;
            while ((DateTime.UtcNow - t0).TotalMilliseconds < ms)
            {
                Dispatcher.UIThread.RunJobs();
                Thread.Sleep(4);
            }
        }

        Pump(500);                    // 等窗口真上屏,GetElementVisual 才非 null
        dm.Layout = Fake();
        dm.Sync(0, false, 1);
        Pump(300);                    // 让第一帧排上,别把启动抖动算进去

        Interlocked.Exchange(ref DanmakuVisualHandler.Rendered, 0);
        var t1 = DateTime.UtcNow;
        // 照真实节奏每 250ms 对一次表(播放页的状态轮询就是这个频率)
        for (var i = 0; i < 12; i++)
        {
            Pump(250);
            dm.Sync((DateTime.UtcNow - t1).TotalSeconds, false, 1);
        }
        var secs = (DateTime.UtcNow - t1).TotalSeconds;
        var got = Interlocked.Read(ref DanmakuVisualHandler.Rendered);
        var fps = got / secs;
        var hz = RefreshHz();

        Console.WriteLine($"PROBE 弹幕 · 显示器 {hz}Hz · {secs:0.00}s 内 OnRender {got} 次 = {fps:0.0} FPS");
        /* 三种结论必须分开。**0 次是「循环压根没跑起来」,不是节拍慢** ——
           这两件事的现象一模一样,混成一句话就会据此判定整条路不可行(栽过一次)。 */
        var ok = fps > hz * 0.8;
        Console.WriteLine(got == 0
            ? "PROBE 弹幕 ✗ 一帧都没画 —— 循环没起来(暂停态初值 / visual 没挂上 / 消息没到)"
            : ok
                ? $"PROBE 弹幕 ✓ {fps:0.0} FPS 跟上了 {hz}Hz"
                : $"PROBE 弹幕 ✗ 只有 {fps:0.0} FPS(显示器 {hz}Hz)—— 多半是被改回了 UI 渲染 pass 的 60Hz");
        w.Close();
        return ok;
    }
}
