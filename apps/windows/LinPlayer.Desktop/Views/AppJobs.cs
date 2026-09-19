using System;
using System.Linq;
using System.Text.Json;
using System.Threading.Tasks;
using Avalonia.Threading;
using LinPlayer.Core;
using LinPlayer.Desktop.Core;

namespace LinPlayer.Desktop.Views;

/// <summary>
/// 程序开着时的全局后台活:追剧日历开播提醒(D366)与插件宿主发来的事件。
/// <para>挂在启动后一次;事件在核心层事件线程上到,改界面一律 Post 回 UI 线程。</para>
/// </summary>
public static class AppJobs
{
    private static bool _started;

    public static void Start(CoreClient core)
    {
        if (_started) return;
        _started = true;
        core.OnEvent += (name, data) => Dispatcher.UIThread.Post(() => OnEvent(core, name, data));
        // 开播提醒:启动一分钟后问一次,之后每半小时。核心层记着提醒过哪些,重复问不会重复提醒
        var t = new DispatcherTimer { Interval = TimeSpan.FromMinutes(30) };
        t.Tick += (_, _) => _ = CheckDue(core);
        t.Start();
        DispatcherTimer.RunOnce(() => _ = CheckDue(core), TimeSpan.FromMinutes(1));
    }

    private static async Task CheckDue(CoreClient core)
    {
        JsonElement due;
        try { due = await core.SyncCalendarDue(); }
        catch (Exception e) { Log.W("calendar", "开播提醒查询失败: " + e.Message); return; }
        if (due.ValueKind != JsonValueKind.Array) return;
        foreach (var e in due.EnumerateArray().Take(3))
        {
            var title = e.TryGetProperty("title", out var t) ? t.GetString() : "";
            var sub = e.TryGetProperty("subtitle", out var s) && s.ValueKind == JsonValueKind.String ? " " + s.GetString() : "";
            Dispatcher.UIThread.Post(() => Toast.Show($"「{title}」{sub} 开播了"));
        }
    }

    private static void OnEvent(CoreClient core, string name, JsonElement data)
    {
        string S(string k) => data.ValueKind == JsonValueKind.Object && data.TryGetProperty(k, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() ?? "" : "";
        switch (name)
        {
            case "plugin.toast":
                Toast.Show(S("text"));
                break;
            case "plugin.autoDisabled":
                Toast.Error($"「{Name(S("name"), S("id"))}」连续出错,已自动禁用");
                break;
            case "plugin.memoryKilled":
                Toast.Error($"「{Name(S("name"), S("id"))}」插件内存占用过高,已暂停");
                break;
            case "plugin.safeMode":
                PluginShell.SafeModeNotice(S("suspect"));
                break;
            case "plugin.legacyRemoved":
                Toast.Show("插件系统已重做,旧插件已移除");
                break;
            case "plugin.shellRequest":
                PluginShell.Handle(core, data);
                break;
            case "plugin.devReloaded":
                Toast.Show($"开发版「{S("id")}」已重新加载");
                break;
            case "plugin.devError":
                Toast.Error($"开发版「{S("id")}」出错:{S("error")}");
                break;
        }
    }

    private static string Name(string name, string id) => name.Length > 0 ? name : id;
}
