using System;
using System.IO;
using System.Net.Http;
using System.Text.Json;
using System.Threading.Tasks;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Media.Imaging;
using Avalonia.Threading;
using LinPlayer.Core;
using LinPlayer.Desktop.Core;

namespace LinPlayer.Desktop.Views;

/// <summary>
/// 壁纸层(SPEC 11.5,D441~D443 D564)。垫在所有页面内容**最底下**,切换即时生效。
///
/// <para>整层 <c>IsHitTestVisible=false</c>:壁纸吃掉一次点击,用户看到的是
/// 「这个按钮点不动」,而没人会怀疑背景。</para>
/// </summary>
internal static class Wallpaper
{
    /// <summary>模糊最强时的半径(px)。0~1 的档位映射到 0~它。</summary>
    private const double MaxBlurRadius = 36;

    private static Panel? _host;
    private static CoreClient? _core;

    /// <summary>
    /// 订阅 <c>plugin.wallpaper</c>。**必须在建窗口之前** —— <c>load: startup</c> 的壁纸插件
    /// 在核心层起来那一刻就 set 完了,等窗口开了再订就已经错过:事件不重发,内容也不落盘。
    /// </summary>
    public static void Listen(CoreClient core)
    {
        _core = core;
        core.OnEvent += (name, data) =>
        {
            if (name != "plugin.wallpaper") return;
            var d = data.Clone();   // 事件线程上的那份随下一条事件失效
            /* ☠ 窗口还没有之前**一个字都不许碰 Dispatcher.UIThread**:那会在 Avalonia
               装好平台之前先把调度器建出来,之后 MainLoop 直接
               PlatformNotSupportedException —— 进程起不来,而且栈里看不出是这儿。 */
            if (_host is null) { _early = d; return; }
            Dispatcher.UIThread.Post(() => OnEvent(d));
        };
    }

    /// <summary>挂上主窗那一层。窗口开之前收到的那一张在这里补画。</summary>
    public static void Attach(Panel host, CoreClient core)
    {
        _host = host;
        _core = core;
        host.IsHitTestVisible = false;
        // 模糊会把画面糊到控件框外面去,不裁的话高 DPI 下整层的合成尺寸会算错
        host.ClipToBounds = true;
        Look.Load();
        if (_early is { } e) { _early = null; OnEvent(e); }
        else Apply();
    }

    /// <summary>窗口还没建好时收到的那一条。只留最后一条:壁纸是「这一刻长什么样」。</summary>
    private static JsonElement? _early;

    /// <summary>换一张(UI 线程)。</summary>
    private static void OnEvent(JsonElement data)
    {
        if (_core is null) return;
        if (_host is null) { _early = data; return; }
        var plugin = Str(data, "plugin");
        var kind = Str(data, "kind");
        Content = kind switch
        {
            "image" => Image(plugin, ImageRef(data)),
            "canvas" => new PluginSurface(_core, plugin, Str(data, "block"), "block"),
            "shader" => WallpaperShader.Of(AssetUrl(plugin, Str(data, "file"))),
            /* 视频壁纸桌面这一版不做(D564:一个进程只能有一条 GL 通道)。
               留纯色底 + 说清只有这一端不放 —— 静默放一张图会让用户以为包坏了。 */
            "video" => Solid("桌面这一版不放视频壁纸,只留底色"),
            _ => null,
        };
        if (Content is null) Log.W("壁纸", $"认不出来的壁纸类型:{kind}");
        else Log.I("壁纸", $"换上 {plugin} 的 {kind} 壁纸");
        Apply();
    }

    private static Control? Content;

    /// <summary>换内容。重建这一层会把 canvas 壁纸卸载再挂载,所以只在真换了的时候做。</summary>
    private static void Apply()
    {
        if (_host is null) return;
        _host.Children.Clear();
        if (Content is not null) _host.Children.Add(Content);
        Refresh();
    }

    /// <summary>换壁纸插件时先清掉上一张。不清的话新插件 set 之前看到的还是旧的那张。</summary>
    public static void Clear()
    {
        Content = null;
        Apply();
    }

    /// <summary>
    /// 只改观感(模糊 / 压暗),不碰内容。
    ///
    /// <para>压暗是把整层**调淡**,让它退回窗口底色,而不是另盖一层黑幕:
    /// 高 DPI 下只要同层里有一个带 Effect 的兄弟,另一个就会按 1.0 倍合成 ——
    /// 实测表现是黑幕只盖住左上角那一块(1280×800 那么大)。</para>
    /// </summary>
    public static void Refresh()
    {
        if (Content is null) return;
        Content.Effect = Look.Blur > 0 ? new BlurEffect { Radius = Look.Blur * MaxBlurRadius } : null;
        Content.Opacity = 1 - Look.Dim;
    }

    private static Control Solid(string why)
    {
        Log.I("壁纸", why);
        Dispatcher.UIThread.Post(() => Toast.Show(why));
        return new Border { Background = Tok.Of("PanelAlt") };
    }

    private static Control Image(string plugin, string src)
    {
        var img = new Avalonia.Controls.Image { Stretch = Stretch.UniformToFill };
        _ = Task.Run(async () =>
        {
            var bmp = src.Contains("://")
                ? await Images.LoadAsync(_core!, src, 1080)   // 外网图走官方取图链路(代理 / 缓存 / 脱敏)
                : await Local(AssetUrl(plugin, src));
            if (bmp is null) Log.W("壁纸", "这张壁纸取不到,保持原样");
            else Dispatcher.UIThread.Post(() => img.Source = bmp);
        });
        return img;
    }

    /// <summary>包内资源走本地数据通道(SPEC 4.1 的 <c>/p/</c>),不自己开文件。</summary>
    private static string AssetUrl(string plugin, string rel)
    {
        if (rel.Contains("://")) return rel;
        var b = _core?.LocalBaseUrl ?? "";
        if (b.Length == 0) return rel;
        return $"{b}/p/{_core!.LocalToken}/{plugin}/{rel.TrimStart('/')}";
    }

    private static readonly HttpClient Http = new() { Timeout = TimeSpan.FromSeconds(10) };

    private static async Task<Bitmap?> Local(string url)
    {
        try { return new Bitmap(new MemoryStream(await Http.GetByteArrayAsync(url))); }
        catch (Exception e) { Log.W("壁纸", $"包内壁纸读不到({url}):{e.Message}"); return null; }
    }

    /// <summary>image 字段既可能是一个字符串,也可能是 <c>ImageRef</c> 对象。</summary>
    private static string ImageRef(JsonElement d)
    {
        if (d.ValueKind != JsonValueKind.Object || !d.TryGetProperty("image", out var v)) return "";
        if (v.ValueKind == JsonValueKind.String) return v.GetString() ?? "";
        return v.ValueKind == JsonValueKind.Object && v.TryGetProperty("url", out var u)
               && u.ValueKind == JsonValueKind.String ? u.GetString() ?? "" : "";
    }

    private static string Str(JsonElement e, string k) =>
        e.ValueKind == JsonValueKind.Object && e.TryGetProperty(k, out var v) && v.ValueKind == JsonValueKind.String
            ? v.GetString() ?? "" : "";
}

/// <summary>
/// 壁纸的模糊度与压暗(0~1,官方设置项,对任何壁纸生效,SPEC 11.5)。
///
/// <para>落在自己的小文件里而不是核心层偏好:<c>prefs.setPrefs</c> 是白名单式的,
/// 认不得的键会被静默丢掉 —— 加字段就得改 core,而这两个数只有桌面壳用。</para>
/// </summary>
internal static class Look
{
    public static double Blur { get; private set; }
    public static double Dim { get; private set; }

    private static string File_ => System.IO.Path.Combine(Program.DataDir, "wallpaper-look.txt");

    public static void Load()
    {
        try
        {
            var p = System.IO.File.ReadAllText(File_).Split(',');
            Blur = Clamp(p[0]);
            Dim = p.Length > 1 ? Clamp(p[1]) : 0;
        }
        // 没设过就是没这个文件,两项都留 0(不模糊不压暗)—— 这是正常起步状态不是错误
        catch { return; }
    }

    public static void Set(double blur, double dim)
    {
        Blur = Math.Clamp(blur, 0, 1);
        Dim = Math.Clamp(dim, 0, 1);
        try { System.IO.File.WriteAllText(File_, $"{Blur:0.##},{Dim:0.##}"); }
        catch (Exception e) { Log.W("壁纸", "模糊 / 压暗没存下来:" + e.Message); }
        Wallpaper.Refresh();
    }

    private static double Clamp(string s) =>
        double.TryParse(s, System.Globalization.NumberStyles.Float,
            System.Globalization.CultureInfo.InvariantCulture, out var v) ? Math.Clamp(v, 0, 1) : 0;
}
