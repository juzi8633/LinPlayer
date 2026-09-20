using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using Avalonia.Input;
using Avalonia.Threading;
using Avalonia.VisualTree;
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

    /// <summary>
    /// 启动时报壳能力。桌面暂不跑 jar / Python spider(D351 spike 之后再定)。
    ///
    /// <para><c>shell</c> 是 nav / ui 对话框那一组的闸门:报 true 之前
    /// 下面的 op 必须都接上了 —— 只报不接的话插件要等满 60 秒才拿到超时,
    /// 而那条错误看起来像是它自己的参数写错了。</para>
    /// </summary>
    public static void ReportCapabilities(CoreClient core) =>
        _ = core.PluginSetCapabilities(new { webview = WebViewHost.Available, spider_jar = false, spider_py = false, shell = true });

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
        foreach (var (name, key) in PluginTokens.Colors)
            if (Tok.Of(key) is Avalonia.Media.ISolidColorBrush b) tokens[name] = Hex(b.Color);
        foreach (var (name, v) in PluginTokens.Numbers) tokens[name] = v;
        _ = core.PluginSetEnv(new
        {
            reduced_motion = Motion.Reduced,
            theme_mode = dark ? "dark" : "light",
            theme_tokens = tokens,
        });
    }

    private static string Hex(Avalonia.Media.Color c) => $"#{c.R:x2}{c.G:x2}{c.B:x2}{c.A:x2}";

    // 表在渲染器那边(PluginTokens):解 `token:名字` 和报给核心层用的是**同一张**。
    // 两处各写一份的话,改一个名字就有一处会悄悄失效(D556)。


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
                    "nav.push" => await Navigate(args, false),
                    "nav.replace" => await Navigate(args, true),
                    "nav.back" => await Back(),
                    "nav.setPageOptions" => await PageOptions(args),
                    "nav.setBadge" => throw new NotSupportedException("桌面侧栏还没有插件入口,角标没地方挂"),
                    "ui.confirm" => await Confirm(args),
                    "ui.prompt" => await Prompt(args),
                    "ui.select" => await Select(args),
                    "ui.notify" => await Notify(args),
                    "player.openPanel" => await OpenPanel(args),
                    "player.setOsdVisible" => await SetOsd(args),
                    "system.openUrl" => Launch(Mi.Str(args, "url")),
                    "system.openApp" => OpenApp(args),
                    "system.clipboardRead" => await ClipboardRead(),
                    "system.clipboardWrite" => await ClipboardWrite(args),
                    "system.share" => await Share(args),
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

    // ---------------------------------------------------------------- nav / ui(SPEC 7.9 7.10)

    private static bool Bool(JsonElement e, string k) =>
        e.ValueKind == JsonValueKind.Object && e.TryGetProperty(k, out var v) && v.ValueKind == JsonValueKind.True;

    private static string Or(JsonElement e, string k, string fallback) =>
        Mi.Str(e, k) is { Length: > 0 } s ? s : fallback;

    /// <summary>对话框和导航都得在 UI 线程上跑,而 shell 请求是在 Task.Run 里回来的。</summary>
    private static Task<T> OnUi<T>(Func<Task<T>> f) => Dispatcher.UIThread.InvokeAsync(f);

    /// <summary>
    /// 跳一页。路由名不认识要<b>报回去</b> —— 静默不动的表现是「点了没反应」,
    /// 而插件作者在自己这边查不到任何线索。
    /// </summary>
    private static async Task<object?> Navigate(JsonElement args, bool replace)
    {
        var route = Mi.Str(args, "route");
        var p = args.TryGetProperty("params", out var pv) ? pv : default;
        var ok = await Dispatcher.UIThread.InvokeAsync(() =>
            Program.MainWindowRef is MainWindow w && w.RouteTo(route, Target(p), Mi.Str(p, "title"), replace));
        if (!ok) throw new ArgumentException($"跳不过去:不认识的路由「{route}」(官方路由名见 SPEC 20.3)");
        return null;
    }

    /// <summary>参数里哪一个是「要打开的那个东西」。SDK 没把键名定死,三种写法都收。</summary>
    private static string Target(JsonElement p) =>
        Mi.Str(p, "id") is { Length: > 0 } id ? id
        : Mi.Str(p, "itemId") is { Length: > 0 } iid ? iid
        : p.ValueKind == JsonValueKind.Object && p.TryGetProperty("item", out var it) ? Mi.Str(it, "id") : "";

    /// <summary>返回。播放页要走它自己的离场(D459),不然 mpv 不停,留个孤儿在出声。</summary>
    private static async Task<object?> Back()
    {
        await Dispatcher.UIThread.InvokeAsync(() =>
        {
            if (Nav.Current is PlayerPage p) p.RequestLeave();
            else Nav.Back();
        });
        return null;
    }

    /// <summary>页面选项(D219)。当前页不是插件页就没得设 —— 这组选项只属于插件自己的页。</summary>
    private static async Task<object?> PageOptions(JsonElement args)
    {
        var o = args.TryGetProperty("options", out var v) ? v : default;
        await Dispatcher.UIThread.InvokeAsync(() =>
            (Nav.Current as PluginPageHost)?.SetOptions(Mi.Str(o, "title"), Bool(o, "immersive"), Bool(o, "keepAwake")));
        return null;
    }

    /// <summary>确认框。复用全站那一份 <see cref="Dialogs"/>,不另画一套。</summary>
    private static Task<object?> Confirm(JsonElement args) => OnUi<object?>(async () =>
        Program.MainWindowRef is { } w
        && await Dialogs.Show(w, Mi.Str(args, "title"),
            new Avalonia.Controls.TextBlock
            {
                Text = Mi.Str(args, "message"), Classes = { "dim" }, MaxWidth = 380,
                TextWrapping = Avalonia.Media.TextWrapping.Wrap,
            },
            Or(args, "ok", "确定"), Or(args, "cancel", "取消"), Bool(args, "danger")));

    private static Task<object?> Prompt(JsonElement args) => OnUi<object?>(async () =>
    {
        if (Program.MainWindowRef is not { } w) return null;
        var box = new Avalonia.Controls.TextBox
        {
            Classes = { "field" }, Text = Mi.Str(args, "value"), Watermark = Mi.Str(args, "placeholder"),
        };
        if (Bool(args, "secret")) box.PasswordChar = '●';
        var body = Body(Mi.Str(args, "message"), box);
        return await Dialogs.Show(w, Mi.Str(args, "title"), body, "确定", "取消") ? box.Text ?? "" : null;
    });

    private static Task<object?> Select(JsonElement args) => OnUi<object?>(async () =>
    {
        if (Program.MainWindowRef is not { } w) return null;
        var opts = Mi.Arr(args, "options");
        var list = new Avalonia.Controls.ListBox
        {
            MaxHeight = 260, SelectedIndex = 0,
            ItemsSource = opts.Select(o => Or(o, "label", Mi.Str(o, "value"))).ToList(),
        };
        var ok = await Dialogs.Show(w, Mi.Str(args, "title"), Body(Mi.Str(args, "message"), list), "确定", "取消");
        return ok && list.SelectedIndex >= 0 && list.SelectedIndex < opts.Count
            ? Mi.Str(opts[list.SelectedIndex], "value") : null;
    });

    /// <summary>一句说明 + 一个控件。说明是空的就不画那行 —— 空 TextBlock 会撑出一段没来由的留白。</summary>
    private static Avalonia.Controls.Control Body(string message, Avalonia.Controls.Control input)
    {
        var panel = new Avalonia.Controls.StackPanel { Spacing = 10, MaxWidth = 380 };
        if (message.Length > 0)
            panel.Children.Add(new Avalonia.Controls.TextBlock
            {
                Text = message, Classes = { "dim" }, TextWrapping = Avalonia.Media.TextWrapping.Wrap,
            });
        panel.Children.Add(input);
        return panel;
    }

    /// <summary>
    /// 系统通知。桌面壳没有托盘,按 SPEC 7.10 降级成 Toast,并留一行日志。
    ///
    /// <para>日志是必须的:降级之后 <c>actions</c> 那几颗按钮和 <c>command</c> 都落不了地,
    /// 插件那边看到的却是「发成功了」—— 不记的话没人查得出用户为什么没点到那颗按钮。</para>
    /// </summary>
    private static Task<object?> Notify(JsonElement args)
    {
        var title = Mi.Str(args, "title");
        var body = Mi.Str(args, "body");
        var dropped = Mi.Arr(args, "actions").Count + (Mi.Str(args, "command").Length > 0 ? 1 : 0);
        Log.W("插件通知", $"{Mi.Str(args, "plugin")}:{title} / {body}(降级成 Toast,丢掉 {dropped} 个动作)");
        Toast.Show(body.Length > 0 ? $"{title} — {body}" : title);
        return Task.FromResult<object?>(null);
    }

    // ---------------------------------------------------------------- player(D67 D162)

    /// <summary>
    /// 打开官方子面板。不在播放页、或者这一端没有那块面板都要报回去 ——
    /// <c>openPanel</c> 在 SDK 那头是 void,这条错误是插件唯一能拿到的线索。
    /// </summary>
    private static Task<object?> OpenPanel(JsonElement args) => OnUi<object?>(() =>
    {
        if (Nav.Current is not PlayerPage p) throw new NotSupportedException("现在不在播放页,没有可打开的子面板");
        p.OpenPanel(Mi.Str(args, "panel"));
        return Task.FromResult<object?>(null);
    });

    private static Task<object?> SetOsd(JsonElement args) => OnUi<object?>(() =>
    {
        if (Nav.Current is not PlayerPage p) throw new NotSupportedException("现在不在播放页,OSD 无处可显隐");
        p.SetOsdVisible(Bool(args, "visible"));
        return Task.FromResult<object?>(null);
    });

    // ---------------------------------------------------------------- system(SPEC 13)

    /// <summary>
    /// 交给系统去开。<c>file:</c> / <c>content:</c> / <c>C:\…</c> 核心层已经拦了
    /// (core/plugin/rt/system.go 的 badAppTarget),这层不再拦第二遍,也不放宽。
    /// </summary>
    private static object? Launch(string target)
    {
        using var p = System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(target) { UseShellExecute = true });
        return null;
    }

    /// <summary>
    /// 调起别的 App。<c>{action,package,extras}</c> 是安卓 Intent,桌面没有对应物 ——
    /// 只认其中的 <c>data</c>(深链);连 data 都没有就得报 unsupported,
    /// 静默回 false 会让插件以为「用户机器上没装」,而真相是这个端根本解释不了这种目标。
    /// </summary>
    private static object OpenApp(JsonElement args)
    {
        var t = args.TryGetProperty("target", out var v) ? v : default;
        var deep = t.ValueKind == JsonValueKind.String ? t.GetString() ?? "" : Mi.Str(t, "data");
        if (deep.Length == 0)
            throw new NotSupportedException("桌面端没有 Android Intent,action/package/extras 这种目标开不了;要在桌面也能用,请在 target 里给一个深链地址(data)");
        try { Launch(deep); return true; }
        catch (Exception e) { Log.W("插件 openApp", $"{deep} 没开起来:{e.Message}"); return false; }
    }

    /// <summary>剪贴板要在 UI 线程上拿,而 shell 请求是在 Task.Run 里回来的。</summary>
    private static Avalonia.Input.Platform.IClipboard Clip() =>
        Avalonia.Controls.TopLevel.GetTopLevel(Program.MainWindowRef)?.Clipboard
        ?? throw new NotSupportedException("主窗口还没起来,这会儿拿不到剪贴板");

    private static Task<object?> ClipboardRead() => OnUi<object?>(async () =>
        await Avalonia.Input.Platform.ClipboardExtensions.TryGetTextAsync(Clip()) ?? "");

    private static Task<object?> ClipboardWrite(JsonElement args) => OnUi<object?>(async () =>
    {
        await Clip().SetTextAsync(Mi.Str(args, "text"));
        return null;
    });

    /// <summary>
    /// 分享。桌面没有系统分享面板,按 SPEC 7.10 降级:文字进剪贴板,图片走另存为。
    ///
    /// <para>日志是必须的:插件那头收到的是「分享成功」,不记的话
    /// 没人查得出用户为什么没在微信里看到那张图。</para>
    /// </summary>
    private static Task<object?> Share(JsonElement args) => OnUi<object?>(async () =>
    {
        var text = string.Join("\n", new[] { Mi.Str(args, "title"), Mi.Str(args, "text"), Mi.Str(args, "url") }
            .Where(s => s.Length > 0));
        var img = Mi.Str(args, "image");
        Log.W("插件分享", $"桌面没有系统分享面板,降级:文字{(text.Length > 0 ? "进剪贴板" : "为空")}、图片{(img.Length > 0 ? "另存为" : "为空")}");
        if (text.Length > 0) await Clip().SetTextAsync(text);
        if (img.Length == 0)
        {
            Toast.Show(text.Length > 0 ? "已复制到剪贴板(桌面没有系统分享)" : "没有可分享的内容");
            return null;
        }
        var saved = await SavePng(Convert.FromBase64String(img));
        Toast.Show(saved ? "图片已保存" + (text.Length > 0 ? ",文字已复制到剪贴板" : "") : "没有保存图片");
        return null;
    });

    private static async Task<bool> SavePng(byte[] png)
    {
        if (Avalonia.Controls.TopLevel.GetTopLevel(Program.MainWindowRef)?.StorageProvider is not { } sp) return false;
        var f = await sp.SaveFilePickerAsync(new Avalonia.Platform.Storage.FilePickerSaveOptions
        {
            Title = "保存分享图片",
            SuggestedFileName = "LinPlayer-" + DateTime.Now.ToString("yyyyMMdd-HHmmss") + ".png",
            FileTypeChoices = [new Avalonia.Platform.Storage.FilePickerFileType("PNG 图片") { Patterns = ["*.png"] }],
        });
        if (f is null) return false;
        await using var s = await f.OpenWriteAsync();
        await s.WriteAsync(png);
        return true;
    }

    // ---------------------------------------------------------------- 壳问核心层:按键与返回(D85 D563)

    /// <summary>
    /// 桌面按键 → SDK 的 <c>PlayerKey</c>。翻不出来的键不问插件:遥控器上没有的键,
    /// 插件那套 TV 操作本来就用不上,问一次只是白等一趟。
    /// </summary>
    private static string? PlayerKeyName(Key key) => key switch
    {
        Key.Up => "up", Key.Down => "down", Key.Left => "left", Key.Right => "right",
        Key.Enter => "ok", Key.Escape => "back", Key.Apps => "menu",
        Key.PageUp => "channelUp", Key.PageDown => "channelDown",
        Key.Space or Key.MediaPlayPause => "playPause", Key.MediaStop => "stop",
        >= Key.D0 and <= Key.D9 => ((char)('0' + key - Key.D0)).ToString(),
        >= Key.NumPad0 and <= Key.NumPad9 => ((char)('0' + key - Key.NumPad0)).ToString(),
        _ => null,
    };

    /// <summary>
    /// 播放页按下一个键(D563)。回 true = 这一下的派发归我了,调用方吃掉它;
    /// 插件说没接走时由 <see cref="PlayerPage.KeyFallback"/> 把默认处理原样补上。
    ///
    /// <para>只在这个插件<b>真有一块可见的面儿挂在播放页上</b>时才问 ——
    /// 不判这一条的话,后台插件能把用户的每一下按键都截走。</para>
    /// </summary>
    internal static bool PlayerKeyPressed(PlayerPage page, Key key, KeyModifiers mods)
    {
        // 组合键不是遥控器上的键:Ctrl+S 之类一律不问
        if (mods != KeyModifiers.None) return false;
        if (Program.Core is not { } core || PlayerKeyName(key) is not { } name) return false;
        if (page.GetVisualDescendants().OfType<PluginSurface>().FirstOrDefault(s => s.IsEffectivelyVisible) is not { } top)
            return false;
        _ = Ask(core.PluginPlayerKey(new { key = name, plugin = top.Plugin }), "consumed",
            () => page.KeyFallback(key, mods));
        return true;
    }

    /// <summary>
    /// 用户按了返回 / Esc(D85)。栈顶是插件页时先问它一句,回 handled 就不退了。
    /// 不是插件页就当场退 —— 绕一趟核心层等于给每一次返回加一次往返延迟。
    /// </summary>
    internal static void BackPressed()
    {
        if (Nav.Current is not PluginPageHost host || Program.Core is not { } core) { Nav.Back(); return; }
        _ = Ask(core.PluginBackRequest(new { plugin = host.Plugin }), "handled", Nav.Back);
    }

    /// <summary>
    /// 问一句,没人接走就把默认处理补上。
    ///
    /// <para>400ms 的上限比核心层那 200ms 松一档:核心层答不上来(卡住、没起来)时
    /// 返回键仍然要管用 —— 按不动比按错难受得多,那时用户只能强杀应用。</para>
    /// </summary>
    private static async Task Ask(Task<JsonElement> call, string field, Action fallback)
    {
        var took = false;
        try { took = Bool(await call.WaitAsync(TimeSpan.FromMilliseconds(400)), field); }
        catch (Exception e) { Log.W("插件按键", $"没问到({field}),按默认处理:{e.Message}"); }
        if (!took) await Dispatcher.UIThread.InvokeAsync(fallback);
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
