using System;
using System.Collections.Generic;
using System.IO;
using System.Text.Json;
using System.Threading.Tasks;
using Avalonia;
using Avalonia.Markup.Xaml;
using Avalonia.Markup.Xaml.Styling;
using Avalonia.Styling;
using LinPlayer.Desktop.Core;

namespace LinPlayer.Desktop.Views;

/// <summary>
/// 插件主题(SPEC 11.3,D214 D372)。包里的 <c>.axaml</c> 运行时加载,叠在官方样式之后。
///
/// <para>☠ 加载失败必须<b>报回核心层再回退官方</b>:主题坏了的表现是整个界面画不出来,
/// 这时候用户连「换回官方主题」的按钮都点不到。报了之后下次启动核心层直接给官方。</para>
/// </summary>
internal static class PluginTheme
{
    /// <summary>失败原因。界面能画出来之后才有地方显示它 —— 见 <see cref="MainWindow"/> 的开窗提示。</summary>
    internal static string FailedName { get; private set; } = "";
    internal static string FailedDetail { get; private set; } = "";

    /// <summary>当前真正叠上去的主题 id。空 = 官方主题。</summary>
    internal static string ActiveId { get; private set; } = "";

    /// <summary>
    /// 启动时叠一次。**必须在建窗口之前** —— 窗口建完再加样式,已经建出来的控件
    /// 拿的还是官方那一份,表现是「换了主题要重启两次」。
    /// </summary>
    public static void ApplyAtStartup(CoreClient? core)
    {
        if (core is null || Application.Current is not { } app) return;
        JsonElement t;
        try
        {
            // 最多等 1.5 秒,和界面字体 / 窗口尺寸同一个口径:核心层卡住了宁可用官方主题开窗
            var call = Task.Run(() => core.CallAsync("plugin.activeTheme", new { platform = "desktop" }));
            if (!call.Wait(1500)) return;
            t = call.Result;
        }
        catch (Exception e) { Log.W("主题", "问不到当前主题,用官方的:" + e.Message); return; }
        if (t.ValueKind != JsonValueKind.Object) return;

        var id = Str(t, "plugin_id");
        var dir = Str(t, "dir");
        if (id.Length == 0 || dir.Length == 0) return;

        var added = new List<IStyle>();
        try
        {
            foreach (var rel in Files(t))
            {
                var path = Path.Combine(dir, rel.Replace('/', Path.DirectorySeparatorChar));
                var s = AvaloniaRuntimeXamlLoader.Parse<Styles>(File.ReadAllText(path));
                app.Styles.Add(s);
                added.Add(s);
            }
            ActiveId = id;
            Log.I("主题", $"已叠上插件主题 {id}({added.Count} 个样式文件)");
        }
        catch (Exception e)
        {
            // 半截样式比没有更糟:token 换了一半,界面是两套配色拼起来的
            foreach (var s in added) app.Styles.Remove(s);
            FailedName = Str(t, "name") is { Length: > 0 } n ? n : id;
            FailedDetail = e.Message;
            Log.W("主题", $"{id} 加载失败,已改用官方主题:{e}");
            // 报一次核心层就记住了,下次启动不再自动用它(D372)
            try { core.CallAsync("plugin.themeFailed", new { id, detail = e.ToString() }).Wait(1500); }
            catch (Exception e2) { Log.W("主题", "失败回报没送到:" + e2.Message); }
        }
    }

    private static List<string> Files(JsonElement t)
    {
        var out_ = new List<string>();
        if (t.TryGetProperty("axaml", out var a) && a.ValueKind == JsonValueKind.Array)
            foreach (var x in a.EnumerateArray())
                if (x.ValueKind == JsonValueKind.String && x.GetString() is { Length: > 0 } s) out_.Add(s);
        if (out_.Count == 0) throw new InvalidOperationException("这个主题没给桌面用的 .axaml 样式文件");
        return out_;
    }

    private static string Str(JsonElement e, string k) =>
        e.ValueKind == JsonValueKind.Object && e.TryGetProperty(k, out var v) && v.ValueKind == JsonValueKind.String
            ? v.GetString() ?? "" : "";
}
