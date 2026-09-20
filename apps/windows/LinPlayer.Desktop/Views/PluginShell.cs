using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using Avalonia.Threading;
using LinPlayer.Core;
using LinPlayer.Desktop.Core;

namespace LinPlayer.Desktop.Views;

/// <summary>
/// 插件宿主要壳执行的事(core/plugin/rt/shell.go):WebView 嗅探 / 取值 / 可见页;
/// 另外是安全模式提示、整页验证后把 Cookie 写回该源的罐子。
/// </summary>
public static class PluginShell
{
    private static readonly Dictionary<long, CancellationTokenSource> Running = [];

    /// <summary>启动时报壳能力。桌面暂不跑 jar / Python spider(D351 spike 之后再定)。</summary>
    public static void ReportCapabilities(CoreClient core) =>
        _ = core.PluginSetCapabilities(new { webview = WebViewHost.Available, spider_jar = false, spider_py = false });

    /// <summary>
    /// 报运行环境:主题明暗 + token 表(SPEC 20.4)+ 系统「减少动态效果」(D558 D556 D428)。
    ///
    /// <para>和能力分开报:能力一辈子不变,环境每次切深浅色都变 —— 切主题时再调一次本方法。
    /// token 值由**这一端**解出来(「Accent 在浅色下是哪个色号」只有壳知道),核心层只转发。</para>
    /// </summary>
    public static void ReportEnv(CoreClient core)
    {
        var app = Avalonia.Application.Current;
        var dark = app?.ActualThemeVariant != Avalonia.Styling.ThemeVariant.Light;
        var tokens = new Dictionary<string, object>();
        foreach (var (name, key) in ColorTokens)
            if (Tok.Of(key) is Avalonia.Media.ISolidColorBrush b) tokens[name] = Hex(b.Color);
        foreach (var (name, v) in NumberTokens) tokens[name] = v;
        _ = core.PluginSetEnv(new
        {
            reduced_motion = Motion.Reduced,
            theme_mode = dark ? "dark" : "light",
            theme_tokens = tokens,
        });
    }

    private static string Hex(Avalonia.Media.Color c) => $"#{c.R:x2}{c.G:x2}{c.B:x2}{c.A:x2}";

    private static readonly (string Name, string Key)[] ColorTokens =
    [
        ("color.bg", "Bg"), ("color.surface", "Panel"), ("color.surfaceAlt", "PanelAlt"),
        ("color.ink", "Ink"), ("color.ink2", "Ink2"), ("color.ink3", "Ink3"),
        ("color.line", "Line"), ("color.lineStrong", "LineStrong"),
        ("color.accent", "Accent"), ("color.accentInk", "AccentInk"), ("color.accentSoft", "AccentSoft"),
        ("color.ok", "Ok"), ("color.warn", "Warn"), ("color.danger", "Danger"),
    ];

    // 刻度是枚举不是区间(见 CLAUDE.md 的那张表):这里就是那把尺子对外的那一份
    private static readonly (string Name, double Value)[] NumberTokens =
    [
        ("radius.small", 6), ("radius.card", 10), ("radius.pill", 999),
        ("space.xs", 2), ("space.sm", 6), ("space.md", 10), ("space.lg", 14), ("space.xl", 18),
        ("font.size.body", 14), ("font.size.title", 18), ("font.size.h1", 26),
        ("motion.duration.fast", 120), ("motion.duration.normal", 220),
    ];

    public static void Handle(CoreClient core, JsonElement req)
    {
        var id = req.GetProperty("id").GetInt64();
        var op = Mi.Str(req, "op");
        var args = req.TryGetProperty("args", out var a) ? a : default;
        var cts = new CancellationTokenSource();
        lock (Running) Running[id] = cts;
        _ = Task.Run(async () =>
        {
            try
            {
                object? data = op switch
                {
                    "webview.sniff" => await Sniff(args, cts.Token),
                    "webview.evaluate" => await Evaluate(args, cts.Token),
                    "webview.open" => await Open(args),
                    _ => throw new NotSupportedException("桌面端不支持 " + op),
                };
                await core.PluginShellResult(new { id, ok = true, data });
            }
            catch (Exception e)
            {
                var kind = e is NotSupportedException ? "unsupported" : e is TimeoutException ? "timeout" : "internal";
                await core.PluginShellResult(new { id, ok = false, error = new { kind, message = e.Message } });
            }
            finally
            {
                lock (Running) Running.Remove(id);
            }
        });
    }

    private static Dictionary<string, string> StrMap(JsonElement o, string k)
    {
        var m = new Dictionary<string, string>();
        if (o.ValueKind == JsonValueKind.Object && o.TryGetProperty(k, out var v) && v.ValueKind == JsonValueKind.Object)
            foreach (var p in v.EnumerateObject()) if (p.Value.ValueKind == JsonValueKind.String) m[p.Name] = p.Value.GetString()!;
        return m;
    }

    private static string[] StrArr(JsonElement o, string k) =>
        o.ValueKind == JsonValueKind.Object && o.TryGetProperty(k, out var v) && v.ValueKind == JsonValueKind.Array
            ? v.EnumerateArray().Where(x => x.ValueKind == JsonValueKind.String).Select(x => x.GetString()!).ToArray() : [];

    private static async Task<object> Sniff(JsonElement args, CancellationToken ct)
    {
        var o = args.TryGetProperty("opts", out var ov) ? ov : default;
        var ms = (double v, double d) => TimeSpan.FromMilliseconds(v > 0 ? v : d);
        var block = StrArr(o.ValueKind == JsonValueKind.Object && o.TryGetProperty("block", out var b) ? b : default, "urlPatterns");
        var opts = new WebViewHost.SniffOpts(
            Mi.Str(o, "userAgent") is { Length: > 0 } ua ? ua : null, StrMap(o, "headers"), StrArr(o, "match"), block,
            Mi.Str(o, "injectScript"), ms(Mi.Num(o, "visibleAfter"), 15000),
            !(o.ValueKind == JsonValueKind.Object && o.TryGetProperty("allowVisible", out var av) && av.ValueKind == JsonValueKind.False),
            ms(Mi.Num(o, "timeout"), 60000));
        var (url, headers) = await WebViewHost.Sniff(Mi.Str(args, "url"), opts, ct);
        return new { url, headers };
    }

    private static async Task<object> Evaluate(JsonElement args, CancellationToken ct)
    {
        var o = args.TryGetProperty("opts", out var ov) ? ov : default;
        var t = Mi.Num(o, "timeout");
        var raw = await WebViewHost.Evaluate(Mi.Str(args, "url"), Mi.Str(args, "script"), Mi.Str(o, "userAgent"), Mi.Str(o, "waitFor"),
            TimeSpan.FromMilliseconds(t > 0 ? t : 30000), ct);
        return JsonDocument.Parse(raw).RootElement.Clone(); // ExecuteScriptAsync 回的本来就是 JSON
    }

    private static async Task<object> Open(JsonElement args)
    {
        var o = args.TryGetProperty("opts", out var ov) ? ov : default;
        var until = o.ValueKind == JsonValueKind.Object && o.TryGetProperty("until", out var u) ? u : default;
        var (cookies, ls, finalUrl) = await WebViewHost.Open(Mi.Str(args, "url"), Mi.Str(o, "title"), Mi.Str(o, "userAgent"),
            Mi.Str(until, "urlMatches"), Mi.Str(until, "cookieName"));
        // ExecuteScriptAsync 把 JSON.stringify 的结果再编码成一个 JSON 字符串字面量:先解出字符串,再解析
        var inner = JsonSerializer.Deserialize<string>(ls) ?? "{}";
        return new { cookies, localStorage = JsonDocument.Parse(inner).RootElement.Clone(), finalUrl };
    }

    /// <summary>[去验证](D323):整页 WebView 过盾,Cookie 进该源的罐子,回来后由调用方重试。</summary>
    public static async Task<bool> VerifyInWebView(string url, string serverId)
    {
        if (!WebViewHost.Available) { Toast.Error("本机没有可用的 WebView,没法打开验证页"); return false; }
        if (Program.Core is not { } core) return false;
        var (cookies, _, finalUrl) = await WebViewHost.Open(url, "完成验证后关闭这个窗口", null, null, null);
        var pluginId = serverId.StartsWith("plugin:") ? serverId["plugin:".Length..serverId.LastIndexOf('/')] : "";
        if (pluginId.Length == 0 || cookies.Count == 0) return cookies.Count > 0;
        try
        {
            await core.PluginSetCookies(new { plugin_id = pluginId, jar = serverId, url = finalUrl, cookies });
            return true;
        }
        catch (Exception e) { Toast.Error("验证结果没存上:" + LibraryPage.Advice(e)); return false; }
    }

    /// <summary>连崩进了安全模式(D376):弹一次,给[去插件页]。</summary>
    public static void SafeModeNotice(string suspect)
    {
        if (Program.MainWindowRef is not { } w || Program.Core is not { } core) return;
        var text = "上次启动连续崩溃,已关闭全部插件。" + (suspect.Length > 0 ? $"最可疑:{suspect}(崩溃前最后在跑的插件)。" : "");
        Dispatcher.UIThread.Post(async () =>
        {
            if (await Dialogs.Show(w, "安全模式", new Avalonia.Controls.TextBlock { Text = text, TextWrapping = Avalonia.Media.TextWrapping.Wrap, MaxWidth = 420 }, "去插件页", "知道了"))
                Nav.Push(new PluginPage(core));
        });
    }
}
