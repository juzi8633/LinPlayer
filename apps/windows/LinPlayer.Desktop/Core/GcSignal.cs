using System.Runtime.InteropServices;

namespace LinPlayer.Desktop.Core;

/// <summary>
/// Linux:核心层(Go)加载后,把 CoreCLR 的 GC 暂停信号(SIGRTMIN)上的 SA_ONSTACK 摘掉。
///
/// <para>Go 以 c-shared 加载时会给进程里<b>已有的每个</b>信号处理函数都打上 SA_ONSTACK。
/// CoreCLR 的暂停处理函数本来跑在线程自己的栈上,被打上之后改跑在 CoreCLR 给每个线程的 16KB 备用栈上 ——
/// 它一开头探栈就冲出去,SIGSEGV。时间点跟 GC 走,所以是「随机崩」(issue #65)。
/// CoreCLR 只往正在跑托管代码的线程发这个信号,那时线程在自己的栈上,摘掉是安全的。
/// 细节与 CI 实测见 docs/lessons/player-mpv.md。</para>
/// </summary>
internal static partial class GcSignal
{
    private const int SA_ONSTACK = 0x08000000;

    // glibc x86_64 / aarch64 的 struct sigaction:handler、128 字节 mask、flags、restorer
    [StructLayout(LayoutKind.Sequential)]
    private unsafe struct SigAction
    {
        public nint Handler;
        public fixed byte Mask[128];
        public int Flags;
        public nint Restorer;
    }

    [LibraryImport("libc", EntryPoint = "sigaction")]
    private static unsafe partial int SigActionCall(int sig, SigAction* act, SigAction* old);

    [LibraryImport("libc")] private static partial int __libc_current_sigrtmin();

    /// <summary>必须在 Go 运行时初始化完之后调(第一次调导出函数返回之后) —— 早了会被 Go 再打回去。</summary>
    public static unsafe void Fix()
    {
        // LP_NO_SIGFIX=1 只给 LP_SIGPROBE 做反向对照
        if (!OperatingSystem.IsLinux() || Environment.GetEnvironmentVariable("LP_NO_SIGFIX") == "1") return;
        var sig = __libc_current_sigrtmin();
        SigAction a;
        if (SigActionCall(sig, null, &a) != 0 || (a.Flags & SA_ONSTACK) == 0) return;
        a.Flags &= ~SA_ONSTACK;
        if (SigActionCall(sig, &a, null) != 0)
            Log.W("启动", "摘 GC 暂停信号的 SA_ONSTACK 失败:Linux 上可能随机崩溃(issue #65)");
    }

    /// <summary>当前 SIGRTMIN 的 SA_ONSTACK 状态,给 SigProbe 打现场用。</summary>
    public static unsafe string Describe()
    {
        SigAction a;
        return SigActionCall(__libc_current_sigrtmin(), null, &a) != 0 ? "读不出"
            : (a.Flags & SA_ONSTACK) != 0 ? "SA_ONSTACK 在" : "SA_ONSTACK 已摘";
    }
}

/// <summary>
/// <c>LP_SIGPROBE=1 ./LinPlayer</c>:16 个线程池线程反复进 Go + 分配,逼 GC 一轮轮暂停线程,活过 15 秒算过。
/// 反向对照:加 <c>LP_NO_SIGFIX=1</c> 必须崩(issue #65 那个死法;CI 上不修一秒内就崩)。
/// </summary>
internal static class SigProbe
{
    public static bool Run(string dll, string version)
    {
        var core = new CoreClient(dll, Path.Combine(Path.GetTempPath(), "lp-sigprobe"), version);
        Console.WriteLine($"SIGPROBE 核心层加载后:{GcSignal.Describe()}");
        var until = DateTime.UtcNow.AddSeconds(15);
        var calls = 0L;
        var workers = Enumerable.Range(0, 16).Select(_ => Task.Run(() =>
        {
            while (DateTime.UtcNow < until)
            {
                core.CallAsync("system.dataPaths", null).Wait();
                GC.KeepAlive(new byte[16384]);
                Interlocked.Increment(ref calls);
            }
        })).ToArray();
        Task.WaitAll(workers);
        Console.WriteLine($"SIGPROBE ✓ 活过 15 秒:{calls} 次进 Go,GC {GC.CollectionCount(0)} 轮");
        return true;
    }
}
