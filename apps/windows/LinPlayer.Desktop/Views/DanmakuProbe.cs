using System;
using System.Collections.Generic;
using System.Linq;
using System.Runtime.InteropServices;
using System.Threading;
using Avalonia.Controls;
using Avalonia.Media;
using Avalonia.Threading;

namespace LinPlayer.Desktop.Views;

/// <summary>
/// 弹幕层探针:把<b>真的</b> <see cref="DanmakuLayer"/> 开起来数帧。
///
/// <para>它拦三类编译器管不着的退化:<b>帧间隔不匀</b>(合成模式不按 vblank 出帧,
/// 平均帧率再高也一顿一顿 —— 只数总帧数的旧判据就是这么假绿的)、画回 UI 渲染 pass、
/// 字形落成方块。<c>LP_DMPROBE=&lt;视频文件&gt;</c> 再垫一层真 mpv 画面一起量。</para>
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
    /// <summary>
    /// 字形查一遍:中文 / 日文 / emoji 混排,不许有一个字落成 glyph 0(.notdef,屏幕上就是方块)。
    /// 只整形不画,所以不用等窗口。
    /// </summary>
    private static bool NoTofu()
    {
        const string text = "前方高能!!ここすき 草www 🎉 233";
        var runs = DanmakuVisualHandler.ShapeRuns(text, DanmakuVisualHandler.Face, 40);
        var glyphs = runs.Sum(r => r.GlyphInfos.Count);
        var tofu = runs.Sum(r => r.GlyphInfos.Count(g => g.GlyphIndex == 0));
        var faces = string.Join(" / ", runs.Select(r => r.GlyphTypeface.FamilyName).Distinct());
        // 字形数也要够:整形结果被池子回收时是「少了字」而不是「变方块」,只查 0 号会放过它
        var chars = new System.Globalization.StringInfo(text).LengthInTextElements;
        var ok = glyphs >= chars && tofu == 0;
        Console.WriteLine(ok
            ? $"PROBE 弹幕字形 ✓ {glyphs} 个字形没有方块(字体:{faces})"
            : $"PROBE 弹幕字形 ✗ {chars} 个字只整出 {glyphs} 个字形、其中 {tofu} 个是方块(字体:{faces})");
        return ok;
    }

    internal static bool Run(string? video = null, LinPlayer.Desktop.Core.CoreClient? core = null)
    {
        var glyphOk = NoTofu();
        var dm = new DanmakuLayer();
        var host = new Grid { Background = Brushes.Black };
        if (video is not null && core is not null)
        {
            // 真 mpv 画面垫在底下:弹幕和视频共用一个合成器,只数弹幕自己量不出互相拖累
            var view = new MpvGlView();
            view.OnReady = () => Dispatcher.UIThread.Post(() =>
                _ = core.CallAsync("player.mpvCommand", new { args = new[] { "loadfile", video } }));
            host.Children.Add(view);
        }
        host.Children.Add(dm);
        var w = new Window
        {
            Width = 1280, Height = 720, ShowInTaskbar = false,
            SystemDecorations = SystemDecorations.None,
            Content = host,
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

        Pump(video is null ? 500 : 3000);   // 等窗口真上屏;带视频时再等 mpv 起播
        dm.Layout = Fake();
        dm.Sync(0, false, 1);
        Pump(300);                    // 让第一帧排上,别把启动抖动算进去

        Interlocked.Exchange(ref DanmakuVisualHandler.Rendered, 0);
        var times = new List<double>();
        DanmakuVisualHandler.FrameTimes = times;
        var t1 = DateTime.UtcNow;
        // 照真实节奏每 250ms 对一次表(播放页的状态轮询就是这个频率)
        for (var i = 0; i < 12; i++)
        {
            Pump(250);
            dm.Sync((DateTime.UtcNow - t1).TotalSeconds, false, 1);
        }
        DanmakuVisualHandler.FrameTimes = null;
        var secs = (DateTime.UtcNow - t1).TotalSeconds;
        var got = Interlocked.Read(ref DanmakuVisualHandler.Rendered);
        var fps = got / secs;
        var hz = RefreshHz();
        double[] gaps;
        lock (times) gaps = times.Zip(times.Skip(1), (a, b) => b - a).ToArray();
        var period = 1000.0 / hz;
        var late = gaps.Count(g => g > period * 1.5);
        var worst = gaps.Length > 0 ? gaps.Max() : 0;
        if (Environment.GetEnvironmentVariable("LP_DMPROBE_HIST") is { Length: > 0 })
            foreach (var b in gaps.GroupBy(g => Math.Round(g)).OrderBy(b => b.Key))
                Console.WriteLine($"PROBE 间隔 {b.Key,4}ms × {b.Count()}");
        if (video is not null && core is not null)
        {
            Console.WriteLine($"PROBE 弹幕 · 底下的 mpv 画了 {MpvGlView.GlFrames} 帧(0 = 画面没起来,这一轮不算数)");
            // 视频自己稳不稳也得一起看:换合成模式救了弹幕、害了画面就不叫修好
            var st = core.CallAsync("player.status", new { });
            while (!st.IsCompleted) Pump(20);
            var j = st.Result;
            string F(string k) => j.TryGetProperty(k, out var e) ? e.ToString() : "-";
            Console.WriteLine($"PROBE 视频 · vo_delayed {F("vo_delayed")} · drops {F("drops")} · " +
                $"出帧间隔 {F("frame_gap_ms")}ms 抖 {F("frame_jitter_ms")}ms · avsync {F("avsync")}");
        }
        Console.WriteLine($"PROBE 弹幕 · 帧间隔 {gaps.Length} 个:超过 1.5 拍 {late} 个,最长 {worst:0.0}ms(一拍 {period:0.0}ms)");

        Console.WriteLine($"PROBE 弹幕 · 显示器 {hz}Hz · {secs:0.00}s 内 OnRender {got} 次 = {fps:0.0} FPS");
        /* 三种结论必须分开。**0 次是「循环压根没跑起来」,不是节拍慢** ——
           这两件事的现象一模一样,混成一句话就会据此判定整条路不可行(栽过一次)。 */
        // 掉拍超过 2% 肉眼就是「一顿一顿」:平均帧率高不代表匀
        var ok = fps > hz * 0.8 && late <= gaps.Length / 50;
        Console.WriteLine(got == 0
            ? "PROBE 弹幕 ✗ 一帧都没画 —— 循环没起来(暂停态初值 / visual 没挂上 / 消息没到)"
            : ok
                ? $"PROBE 弹幕 ✓ {fps:0.0} FPS 跟上了 {hz}Hz,掉拍 {late}/{gaps.Length}"
                : $"PROBE 弹幕 ✗ {fps:0.0} FPS(显示器 {hz}Hz)、掉拍 {late}/{gaps.Length} —— " +
                  "合成模式被换回了不按 vblank 出帧的那几种(见 Program.CompositionModes),或被改回 UI 渲染 pass");
        w.Close();
        return ok && glyphOk;
    }
}
