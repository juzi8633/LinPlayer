using System;
using System.Reflection;
using System.IO;
using Avalonia;
using LinPlayer.Desktop.Core;

namespace LinPlayer.Desktop;

internal static class Program
{
    /// <summary>进程级的核心层句柄。UI 的一切数据都从它来。</summary>
    public static CoreClient? Core { get; private set; }

    /// <summary>核心层起不来时的原因(启动页要如实显示,不能白屏)。</summary>
    public static string? CoreError { get; private set; }

    /// <summary>本程序版本。
    ///
    /// **不许写死字面量。** 唯一权威是仓库根的 `VERSION`(见 docs/VERSIONING.md):
    /// CI 用 `dotnet publish -p:Version=&lt;版本&gt;` 注进程序集,这里回读。
    /// 2026-09-04 之前这里硬编码 `"0.1.0-go"`,而线上已经发到 `v1.0.0-build684` ——
    /// 照那样发布,更新检查会判定「已是最新」并**静默**卡死所有老用户。
    /// </summary>
    public static string Version =>
        System.Reflection.Assembly.GetExecutingAssembly()
            .GetCustomAttribute<System.Reflection.AssemblyInformationalVersionAttribute>()
            ?.InformationalVersion
        ?? typeof(Program).Assembly.GetName().Version?.ToString()
        ?? "0.0.0-dev";

    [STAThread]
    public static void Main(string[] args)
    {
        /* 控制台按 UTF-8 输出。Windows 默认代码页是 GBK,日志里的中文会变成一串问号 ——
           而自检脚本正是靠 grep 中文关键字读这些日志的,乱码 = 整套日志形同不存在。 */
        try { Console.OutputEncoding = System.Text.Encoding.UTF8; } catch { /* 无控制台时会抛,忽略 */ }

        /* 界面字体自检:`LP_FONTPROBE=<字体文件> LinPlayer.exe` 打一行结果就退,不开窗口。
           ★ 它存在的理由和 core 那个 checkOptionNames 一样:界面字体走的是
             Avalonia 的 **internal** 接口(反射调),换版本时这一处会第一个坏,
             而坏了的样子是「设置里显示已换、界面一点没变」—— 光靠编译发现不了。
             升 Avalonia 之后跑一次这个。 */
        if (Environment.GetEnvironmentVariable("LP_FONTPROBE") is { } probeFont && probeFont.Length > 0)
        {
            AppBuilder.Configure<App>().UsePlatformDetect().SetupWithoutStarting();
            var ok = LinPlayer.Desktop.Core.UiFont.Apply(probeFont);
            Console.WriteLine($"PROBE 装上了={ok} 家族={LinPlayer.Desktop.Core.UiFont.Current?.Name ?? "(无)"}");
            return;
        }
        /* 响应式缩放自检:`LP_SCALEPROBE=1 LinPlayer.exe` 打几行就退。
           壳这一层没有单测工程,而缩放曲线是纯算术 —— 不给它一个能跑的门禁,
           改坏了只会在真机上表现成「窗口缩了里面没缩」,而编译全绿。 */
        if (Environment.GetEnvironmentVariable("LP_SCALEPROBE") is { Length: > 0 })
        {
            var bad = Views.Responsive.SelfCheck(Console.WriteLine);
            Console.WriteLine(bad == 0 ? "PROBE 缩放 全部通过" : $"PROBE 缩放 {bad} 条不过");
            Environment.ExitCode = bad == 0 ? 0 : 1;
            return;
        }
        /* 选集栏卡死自检:`LP_SCROLLPROBE=1 LinPlayer.exe` 打一行就退,不开窗口。
           判据是「目标去不了时,驱动器退不退得出来」—— 退不出来 = 之后每次点
           左右翻页按钮 Run() 都当场 return,按钮从此是死的(用户 2026-09-08 报的
           「有概率卡死」)。这类东西编译发现不了,只有把它逼进死角才看得见。 */
        if (Environment.GetEnvironmentVariable("LP_SCROLLPROBE") is { Length: > 0 })
        {
            AppBuilder.Configure<App>().UsePlatformDetect().SetupWithoutStarting();
            var sv = new Avalonia.Controls.ScrollViewer
            {
                Width = 200, Height = 100,
                Content = new Avalonia.Controls.Border { Width = 400, Height = 100 },
            };
            /* 量一遍,让 Extent/Viewport 有真值。
               ☠ **ApplyTemplate 不能省**:ScrollViewer 的 Extent 是它模板里那个
                 ScrollContentPresenter 报上来的,没套模板就永远是 0×0 ——
                 那样滚哪儿都一样,自检等于在一个滚不动的控件上跑,永远绿。 */
            sv.ApplyTemplate();
            sv.Measure(new Size(200, 100));
            sv.Arrange(new Rect(0, 0, 200, 100));
            sv.UpdateLayout();
            /* 没有可视根,Extent 会是 0 —— 这**正好**是我们要的形状:
               目标去不了、Offset 的 setter 把值压回原处。真机上造成同一个形状的是
               虚拟化轨道估变的 Extent。 */
            Console.WriteLine($"PROBE 滚动 · 量程 Extent={sv.Extent.Width:0.#}(0 = 目标去不了,正是要逼出来的死角)");
            // 目标 9999:远超 Extent-Viewport(=200),ScrollViewer 会把 Offset 压回 200
            var (stuck, frames) = Views.Smooth.SelfCheckStuck(sv, 9999);
            Console.WriteLine(stuck
                ? $"PROBE 滚动 ✗ 卡住了(跑满 {frames} 帧还没退出)—— 翻页按钮会变成死的"
                : $"PROBE 滚动 ✓ 第 {frames} 帧退出,偏移停在 {sv.Offset.X:0.#}");
            /* 第二道闸:窗口最小化时渲染循环停了,排进去的那一帧永远不会来。
               只判 Running 一个字段的话它永远停在 true,之后每次点按钮都当场 return。 */
            var fresh = Views.Smooth.StillAlive(true, DateTime.UtcNow);
            var stale = Views.Smooth.StillAlive(true, DateTime.UtcNow.AddSeconds(-5));
            Console.WriteLine(fresh && !stale
                ? "PROBE 滚动 ✓ 帧停了 5 秒的那一轮会被判死并重启"
                : $"PROBE 滚动 ✗ 停帧判定坏了(刚跑过={fresh} 停了5秒={stale})—— 最小化再还原后按钮会是死的");
            return;
        }

        Perf.Log("Main 入口");
        var exeDir = AppContext.BaseDirectory;
        /* 数据全在 exe 同级的 userdata/(绿色包单一数据根)。
           用户明确要求过「不喜欢到处拉屎」—— 不要往 AppData 里写。
           这里只把根传给核心层,**路径的唯一出口在 core/paths**,UI 侧不自己拼。 */
        var dataDir = Path.Combine(exeDir, "userdata");
        var dll = Path.Combine(exeDir, "lpcore.dll");
        // 元数据缓存和核心层共用一个数据根。它必须在任何页面构造之前就绪 ——
        // 晚一步的话首屏那几条读命令全部落空,而「首屏」正是它唯一要救的那一屏。
        MetaCache.Init(dataDir);
        // 日志要早于一切页面 —— 它是用来抓「只在用户那台机器上出现」的现象的
        Log.Init(dataDir);

        try
        {
            Core = new CoreClient(dll, dataDir, Version);
        }
        catch (Exception e)
        {
            // 不弹框、不静默退出:进主窗口显示原因。白屏是本项目最讨厌的失败形态。
            CoreError = e.Message;
        }

        Perf.Log("核心层就绪");
        BuildAvaloniaApp().StartWithClassicDesktopLifetime(args);

        /* 退出时调 lp_shutdown(Dispose 里)。它**阻塞到落盘完成**:停 mpv、
           关本地数据通道、停命令总线。

            实测说明:进度上报那条**不靠它** —— 关窗口时播放页的
             DetachedFromVisualTree 已经发过 player.stopPlayback,
             注入「不调 Dispose」跑一遍,/Sessions/Playing/Stopped 照样上报。
             所以这一句守的是**关停顺序与落盘**,不是上报;
             别拿上报当它的验收判据(我第一版就是这么错的)。 */
        Perf.Summary();
        Core?.Dispose();
    }

    public static AppBuilder BuildAvaloniaApp() =>
        AppBuilder.Configure<App>()
            .UsePlatformDetect()
            .WithInterFont()
            .LogToTrace();
}
