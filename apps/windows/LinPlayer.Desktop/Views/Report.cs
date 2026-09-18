using System.Diagnostics;
using System.Runtime.InteropServices;
using System.IO;
using System.Text.Json;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Media;
using LinPlayer.Core;
using LinPlayer.Desktop.Core;

namespace LinPlayer.Desktop.Views;

/// <summary>
/// 崩溃报告 / 问题反馈:经核心层 <c>system.sendReport</c>(脱敏 + 自建代理)发到开发者的 Telegram。
///
/// <para>异常退出靠「运行中」标记判:启动时写 <c>logs/running.&lt;pid&gt;</c>,正常退出删掉。
/// 下次启动看到一个进程已经不在的标记 = 那次没走正常退出(崩了 / 被强杀 / 原生层崩)。
/// 按 pid 分开是因为没有单实例限制,开两个窗口时不能把对方当成崩了。</para>
/// </summary>
internal static class Report
{
    /// <summary>上次异常退出时的现场。null = 上次正常;空串 = 异常但没接到托管异常(强杀 / 原生层)。</summary>
    public static string? LastCrash { get; private set; }

    public static void Arm(string dataDir)
    {
        var dir = Path.Combine(dataDir, "logs");
        var me = Environment.ProcessId;
        try
        {
            Directory.CreateDirectory(dir);
            foreach (var f in Directory.GetFiles(dir, "running.*"))
            {
                if (!int.TryParse(Path.GetExtension(f).TrimStart('.'), out var pid) || Alive(pid)) continue;
                var crash = Path.Combine(dir, $"crash.{pid}.txt");
                var trail = Path.Combine(dir, $"trail.{pid}.txt");
                // ☠ 先落进 pending 再删现场:发出去之前又崩一次的话,读进内存的那份就没了 ——
                //   「一开就崩」的机器会一直循环,一条都发不出来(issue #65 差点就是这样)
                File.AppendAllText(_pending = Path.Combine(dir, "pending-crash.txt"),
                    (File.Exists(crash) ? File.ReadAllText(crash) : "(异常退出,没接到托管异常:可能被强杀或原生层崩溃)")
                    + (File.Exists(trail) ? "\n== 死前最后几步 ==\n" + File.ReadAllText(trail) : "") + "\n\n");
                File.Delete(f);
                File.Delete(crash);
                File.Delete(trail);
            }
            _pending = Path.Combine(dir, "pending-crash.txt");
            if (File.Exists(_pending)) LastCrash = File.ReadAllText(_pending);
            File.WriteAllText(Path.Combine(dir, $"running.{me}"), "");
            _trail = Path.Combine(dir, $"trail.{me}.txt");
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            return; // 日志目录不可写:这次不做崩溃检测,不能因为它起不来
        }
        AppDomain.CurrentDomain.UnhandledException += (_, e) =>
        {
            try { File.WriteAllText(Path.Combine(dir, $"crash.{me}.txt"), e.ExceptionObject.ToString()); }
            catch (IOException) { /* 进程已经在崩了,写不进就只剩「异常退出」这一条信息 */ }
        };
        // 正常关窗和 Environment.Exit(装更新)都走这里;崩溃和被强杀不走 —— 标记留下,下次就知道
        AppDomain.CurrentDomain.ProcessExit += (_, _) => Disarm(dir, me);
        // 关机 / 注销 / Ctrl+C / 关终端是「被请求退出」,不是崩。Linux 上不接的话,
        // 开着程序关机下次就报一条假崩溃(CI 冒烟被 timeout 掐掉时当场撞上)。不取消信号,照常退出
        foreach (var sig in new[] { PosixSignal.SIGTERM, PosixSignal.SIGINT, PosixSignal.SIGHUP })
            Signals.Add(PosixSignalRegistration.Create(sig, _ => Disarm(dir, me)));
    }

    // 注册对象被回收就等于注销,必须有人一直拿着
    private static readonly List<PosixSignalRegistration> Signals = [];

    private static void Disarm(string dir, int me)
    {
        try
        {
            File.Delete(Path.Combine(dir, $"running.{me}"));
            File.Delete(Path.Combine(dir, $"trail.{me}.txt"));
        }
        catch (IOException) { /* 删不掉下次会误报一次「异常退出」,只多一条报告 */ }
    }

    private static string _trail = "";
    private static readonly Queue<string> Steps = new();

    /// <summary>
    /// 记一步「现在走到哪了」,同步落盘。原生层崩溃(驱动 / libmpv)一个字不留,
    /// 只有这份轨迹说得出死在哪一步 —— issue #65 就是没有它,只能反复找用户要 gdb 栈。
    /// 只留最近 30 步,每次整份重写(几百字节)。
    /// </summary>
    public static void Trail(string step)
    {
        if (_trail.Length == 0) return;
        lock (Steps)
        {
            Steps.Enqueue($"{DateTime.Now:HH:mm:ss.fff} {step}");
            while (Steps.Count > 30) Steps.Dequeue();
            try { File.WriteAllLines(_trail, Steps); }
            catch (IOException) { /* 写不进只是少一条线索,不能为它打断正在做的事 */ }
        }
    }

    /// <summary>自检和 CI 冒烟设 <c>LP_NO_REPORT=1</c>:它们的「崩溃」是故意的,别发到开发者那儿。</summary>
    public static bool Off => Environment.GetEnvironmentVariable("LP_NO_REPORT") == "1";

    private static bool Alive(int pid)
    {
        try { return Process.GetProcessById(pid).ProcessName.StartsWith("LinPlayer", StringComparison.OrdinalIgnoreCase); }
        catch (ArgumentException) { return false; } // 该 pid 已经没有进程
    }

    /// <summary>
    /// 启动后调一次:上次异常退出就<b>自动</b>把现场发出去,不问。
    /// 用户说不清出了什么问题(用户 2026-09-18:「让用户描述没用」),问了也只多一个要点掉的框。
    /// </summary>
    public static async Task SendPendingCrash(CoreClient core)
    {
        if (LastCrash is not { } crash || Off) return;
        try
        {
            await core.SystemSendReport(new { kind = "crash", crash, log = ReadLog() });
            LastCrash = null;
            // 发出去才删:没发出去(断网 / 又崩)下次启动接着发
            try { File.Delete(_pending); } catch (IOException) { /* 删不掉下次重发一遍,多一条而已 */ }
            _sentEarly = true;
        }
        catch (Exception e)
        {
            Log.W("报告", "上次崩溃的报告没发出去: " + e.Message);
        }
    }

    /// <summary>
    /// 开窗<b>之前</b>先发一次,最多等 6 秒。只有上次崩过才等。
    /// 等到首屏之后再发的话,「一开就崩」的机器永远走不到那一步。
    /// </summary>
    public static void SendPendingCrashEarly(CoreClient core)
    {
        if (LastCrash is null || Off) return;
        try { SendPendingCrash(core).Wait(TimeSpan.FromSeconds(6)); }
        catch (AggregateException) { /* SendPendingCrash 自己吞了异常;这里只可能是超时之外的取消,开窗后再试 */ }
    }

    /// <summary>开窗后调:早发成功就补一句提示,没发成功再试一次。</summary>
    public static async Task AfterWindowOpened(CoreClient core)
    {
        if (!_sentEarly) await SendPendingCrash(core);
        if (_sentEarly) { Toast.Show("上次异常退出,已把报告发给开发者"); _sentEarly = false; }
    }

    private static string _pending = "";
    private static bool _sentEarly;

    /// <summary>出错横条上的「反馈」:点一下就把这次被兜住的异常发出去。</summary>
    public static Task FromError(Visual anchor, CoreClient core, Exception e) =>
        Send(anchor, core, new { kind = "crash", crash = e.ToString(), log = ReadLog() });

    /// <summary>设置页「发送日志给开发者」。</summary>
    public static Task Feedback(Visual anchor, CoreClient core) =>
        Send(anchor, core, new { kind = "feedback", log = ReadLog() });

    private static async Task Send(Visual anchor, CoreClient core, object args)
    {
        try
        {
            await core.SystemSendReport(args);
            Toast.Show("已发给开发者,谢谢");
        }
        catch (Exception e)
        {
            // CF 在部分地区连不上:给一条手动的退路。复制的也是脱敏后的那份
            if (!await Dialogs.Show(anchor, "没发出去", new TextBlock
                {
                    Text = LibraryPage.Advice(e) + "\n可以复制报告,自己发给开发者(GitHub issue 或群里)。",
                    TextWrapping = TextWrapping.Wrap, MaxWidth = 420,
                }, "复制报告", "关闭")) return;
            var j = JsonSerializer.SerializeToElement(args);
            var dry = new Dictionary<string, object?> { ["dry"] = true };
            foreach (var p in j.EnumerateObject()) dry[p.Name] = p.Value.GetString();
            var r = await core.SystemSendReport(dry);
            if (TopLevel.GetTopLevel(anchor)?.Clipboard is { } cb && r.ValueKind == JsonValueKind.String)
            {
                await cb.SetTextAsync(r.GetString());
                Toast.Show("报告已复制");
            }
        }
    }

    /// <summary>desktop.log 的尾部。核心层还会再截一次,这里只是别把几十 MB 读进内存。</summary>
    private static string ReadLog()
    {
        if (Log.FilePath is not { Length: > 0 } p || !File.Exists(p)) return "";
        try
        {
            // 日志正被本进程追加写着:必须允许共享写,否则当场 IOException
            using var fs = new FileStream(p, FileMode.Open, FileAccess.Read, FileShare.ReadWrite);
            fs.Seek(Math.Max(0, fs.Length - (512 << 10)), SeekOrigin.Begin);
            return new StreamReader(fs).ReadToEnd();
        }
        catch (IOException e) { return "(日志读不出来: " + e.Message + ")"; }
    }
}
