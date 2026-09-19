using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text.Json;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Platform;
using Avalonia.Threading;
using LinPlayer.Desktop.Core;
using Microsoft.Web.WebView2.Core;

namespace LinPlayer.Desktop.Views;

/// <summary>
/// 插件用的 WebView(SPEC 5.8,D59 D62 D496 D497):隐藏嗅探、执行 JS 取值、可见验证页。
///
/// <para>隐藏和可见是<b>同一个窗口</b>:平时放在屏幕外,嗅探超时要用户动手时挪回屏幕中间 ——
/// 页面状态(已经点开的播放器、过了一半的验证)不会因为换窗口丢掉。</para>
/// </summary>
internal static class WebViewHost
{
    // 隐藏 WebView 全局最多 3 个,超出排队(排队时间计入调用超时,D497)
    private static readonly SemaphoreSlim Slots = new(3);
    private static Task<CoreWebView2Environment>? _env;

    public static bool Available
    {
        get
        {
            if (!OperatingSystem.IsWindows()) return false;
            try { return CoreWebView2Environment.GetAvailableBrowserVersionString() is { Length: > 0 }; }
            catch (WebView2RuntimeNotFoundException) { return false; } // 系统没装 WebView2 运行时:能力报「没有」,插件自己降级
        }
    }

    private static Task<CoreWebView2Environment> Env() => _env ??= CoreWebView2Environment.CreateAsync(
        null, Path.Combine(Program.DataDir, "webview"));

    public sealed record SniffOpts(string? UserAgent, Dictionary<string, string> Headers, string[] Match, string[] Block,
        string? Inject, TimeSpan VisibleAfter, bool AllowVisible, TimeSpan Timeout);

    /// <summary>嗅探:加载页面、拦截请求抓视频地址;超时仍没抓到且允许时,把窗口亮出来让用户点播放 / 过验证。</summary>
    public static async Task<(string Url, Dictionary<string, string> Headers)> Sniff(string url, SniffOpts o, CancellationToken ct)
    {
        await Slots.WaitAsync(ct);
        WebWindow? w = null;
        try
        {
            w = await Dispatcher.UIThread.InvokeAsync(() => new WebWindow("请完成验证或点一下播放", o.UserAgent));
            var wv = await w.Ready;
            var found = new TaskCompletionSource<(string, Dictionary<string, string>)>(TaskCreationOptions.RunContinuationsAsynchronously);
            var match = o.Match.Length > 0 ? o.Match.Select(p => new Regex(p, RegexOptions.IgnoreCase)).ToArray()
                : [new Regex(@"\.(m3u8|mp4|flv)(\?|$)|/m3u8\b", RegexOptions.IgnoreCase)];
            var block = o.Block.Select(p => new Regex(p, RegexOptions.IgnoreCase)).ToArray();
            await Dispatcher.UIThread.InvokeAsync(async () =>
            {
                wv.AddWebResourceRequestedFilter("*", CoreWebView2WebResourceContext.All);
                wv.WebResourceRequested += (_, e) =>
                {
                    var u = e.Request.Uri;
                    if (block.Any(r => r.IsMatch(u)))
                    {
                        e.Response = wv.Environment.CreateWebResourceResponse(null, 403, "Blocked", "");
                        return;
                    }
                    foreach (var (k, v) in o.Headers) e.Request.Headers.SetHeader(k, v);
                    if (match.Any(r => r.IsMatch(u)))
                    {
                        var h = new Dictionary<string, string>();
                        foreach (var kv in e.Request.Headers)
                            if (kv.Key is "Referer" or "User-Agent" or "Cookie" or "Origin") h[kv.Key] = kv.Value;
                        found.TrySetResult((u, h));
                    }
                };
                if (o.Inject is { Length: > 0 } js) await wv.AddScriptToExecuteOnDocumentCreatedAsync(js);
                wv.Navigate(url);
            });
            var visible = Task.Delay(o.VisibleAfter, ct).ContinueWith(_ =>
            {
                if (o.AllowVisible && !found.Task.IsCompleted) Dispatcher.UIThread.Post(() => w.ShowToUser());
            }, TaskScheduler.Default);
            var done = await Task.WhenAny(found.Task, Task.Delay(o.Timeout, ct));
            if (done != found.Task) throw new TimeoutException("网页嗅探超时,没抓到视频地址");
            return await found.Task;
        }
        finally
        {
            if (w is not null) await Dispatcher.UIThread.InvokeAsync(w.Close);
            Slots.Release();
        }
    }

    /// <summary>加载页面后执行一段 JS 取值(D62);waitFor 是等到出现的 CSS 选择器。</summary>
    public static async Task<string> Evaluate(string url, string script, string? ua, string? waitFor, TimeSpan timeout, CancellationToken ct)
    {
        await Slots.WaitAsync(ct);
        WebWindow? w = null;
        try
        {
            w = await Dispatcher.UIThread.InvokeAsync(() => new WebWindow("", ua));
            var wv = await w.Ready;
            var loaded = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
            await Dispatcher.UIThread.InvokeAsync(() =>
            {
                wv.NavigationCompleted += (_, _) => loaded.TrySetResult();
                wv.Navigate(url);
            });
            var deadline = DateTime.UtcNow + timeout;
            if (await Task.WhenAny(loaded.Task, Task.Delay(timeout, ct)) != loaded.Task) throw new TimeoutException("页面加载超时");
            if (waitFor is { Length: > 0 })
                while (true)
                {
                    var hit = await Dispatcher.UIThread.InvokeAsync(() => wv.ExecuteScriptAsync($"!!document.querySelector({JsonSerializer.Serialize(waitFor)})"));
                    if (hit == "true") break;
                    if (DateTime.UtcNow > deadline) throw new TimeoutException("等不到页面上的元素:" + waitFor);
                    await Task.Delay(300, ct);
                }
            return await Dispatcher.UIThread.InvokeAsync(() => wv.ExecuteScriptAsync(script));
        }
        finally
        {
            if (w is not null) await Dispatcher.UIThread.InvokeAsync(w.Close);
            Slots.Release();
        }
    }

    /// <summary>整页可见 WebView(过盾、登录):用户关窗或条件满足时返回 Cookie 与 localStorage。</summary>
    public static async Task<(Dictionary<string, string> Cookies, string LocalStorage, string FinalUrl)> Open(
        string url, string title, string? ua, string? untilUrl, string? untilCookie)
    {
        var w = await Dispatcher.UIThread.InvokeAsync(() => new WebWindow(title.Length > 0 ? title : "请完成验证后关闭窗口", ua));
        var wv = await w.Ready;
        var closed = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var until = untilUrl is { Length: > 0 } ? new Regex(untilUrl) : null;
        await Dispatcher.UIThread.InvokeAsync(() =>
        {
            w.Closed += (_, _) => closed.TrySetResult();
            wv.NavigationCompleted += async (_, _) =>
            {
                if (until?.IsMatch(wv.Source) == true) closed.TrySetResult();
                if (untilCookie is { Length: > 0 } && (await wv.CookieManager.GetCookiesAsync(wv.Source)).Any(c => c.Name == untilCookie))
                    closed.TrySetResult();
            };
            wv.Navigate(url);
            w.ShowToUser();
        });
        await closed.Task;
        return await Dispatcher.UIThread.InvokeAsync(async () =>
        {
            var finalUrl = wv.Source;
            var cookies = (await wv.CookieManager.GetCookiesAsync(finalUrl)).ToDictionary(c => c.Name, c => c.Value);
            string ls = "{}";
            try { ls = await wv.ExecuteScriptAsync("JSON.stringify(localStorage)"); }
            catch (Exception e) { Log.W("webview", "读 localStorage 失败(页面已关):" + e.Message); }
            if (w.IsVisible) w.Close();
            return (cookies, ls, finalUrl);
        });
    }

    /// <summary>承载 WebView2 的窗口。建出来时在屏幕外,要用户动手时再亮出来。</summary>
    private sealed class WebWindow : Window
    {
        private readonly TaskCompletionSource<CoreWebView2> _ready = new(TaskCreationOptions.RunContinuationsAsynchronously);
        private readonly string? _ua;
        public Task<CoreWebView2> Ready => _ready.Task;

        public WebWindow(string title, string? ua)
        {
            _ua = ua;
            Title = title;
            Width = 1100;
            Height = 720;
            ShowInTaskbar = false;
            WindowStartupLocation = WindowStartupLocation.Manual;
            Position = new PixelPoint(-32000, -32000);
            var host = new Host(this);
            Content = host;
            Show();
        }

        public void ShowToUser()
        {
            ShowInTaskbar = true;
            if (Program.MainWindowRef is { } main)
                Position = new PixelPoint(main.Position.X + (int)((main.Bounds.Width - Width) / 2), main.Position.Y + 60);
            else Position = new PixelPoint(120, 120);
            Activate();
        }

        private async Task Init(IntPtr hwnd, Host host)
        {
            try
            {
                var env = await Env();
                var ctl = await env.CreateCoreWebView2ControllerAsync(hwnd);
                if (_ua is { Length: > 0 }) ctl.CoreWebView2.Settings.UserAgent = _ua;
                ctl.CoreWebView2.Settings.AreDevToolsEnabled = false;
                host.Controller = ctl;
                host.Resize();
                Closed += (_, _) => ctl.Close();
                _ready.TrySetResult(ctl.CoreWebView2);
            }
            catch (Exception e) { _ready.TrySetException(e); }
        }

        private sealed class Host(WebWindow owner) : NativeControlHost
        {
            public CoreWebView2Controller? Controller;
            private IntPtr _hwnd;

            protected override IPlatformHandle CreateNativeControlCore(IPlatformHandle parent)
            {
                _hwnd = CreateWindowExW(0, "Static", "", WS_CHILD | WS_VISIBLE | WS_CLIPCHILDREN, 0, 0, 1, 1, parent.Handle, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero);
                _ = owner.Init(_hwnd, this);
                return new PlatformHandle(_hwnd, "HWND");
            }

            protected override void DestroyNativeControlCore(IPlatformHandle control) => DestroyWindow(control.Handle);

            protected override Size ArrangeOverride(Size finalSize)
            {
                var r = base.ArrangeOverride(finalSize);
                Resize();
                return r;
            }

            public void Resize()
            {
                if (Controller is null) return;
                var s = VisualRoot?.RenderScaling ?? 1;
                Controller.Bounds = new System.Drawing.Rectangle(0, 0, (int)(Bounds.Width * s), (int)(Bounds.Height * s));
            }
        }

        private const int WS_CHILD = 0x40000000, WS_VISIBLE = 0x10000000, WS_CLIPCHILDREN = 0x02000000;

        [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern IntPtr CreateWindowExW(int exStyle, string cls, string name, int style, int x, int y, int w, int h,
            IntPtr parent, IntPtr menu, IntPtr inst, IntPtr param);

        [DllImport("user32.dll")]
        private static extern bool DestroyWindow(IntPtr hwnd);
    }
}
