using System.Runtime.InteropServices;

namespace LinPlayer.Desktop.Core;

/// <summary>
/// Linux:每个线程第一次进 Go 之前,先给它挂一块 1MB 的信号备用栈。
///
/// <para>不挂的话 Go 会在这个线程上装自己的 32KB 备用栈,而且之后一直留着;
/// .NET 做 GC 时往这个线程发的暂停信号就落在它上面,CoreCLR 的处理函数一开头就要探好几页栈 ——
/// 冲出 32KB,SIGSEGV。崩在哪、什么时候崩全看 GC,所以是「随机崩」(issue #65)。
/// Go 看到线程已经有备用栈就直接用,不再装自己的。详见 docs/lessons/player-mpv.md。</para>
/// </summary>
internal static partial class AltStack
{
    private const int Size = 1 << 20;
    private const int SS_DISABLE = 2;

    [StructLayout(LayoutKind.Sequential)]
    private struct StackT { public nint Sp; public int Flags; public nuint Size; }

    [LibraryImport("libc", EntryPoint = "sigaltstack", SetLastError = true)]
    private static unsafe partial int SigAltStack(StackT* ss, StackT* old);

    // 线程没了它就不可达,终结器把那 1MB 还回去。线程池会不停地退线程、起新线程,不还就是稳定泄漏
    private sealed unsafe class Block
    {
        public readonly void* P = NativeMemory.Alloc(Size);
        ~Block() => NativeMemory.Free(P);
    }

    [ThreadStatic] private static Block? _mine;
    [ThreadStatic] private static bool _checked;

    /// <summary>LP_NO_ALTSTACK=1 关掉:只给 LP_SIGPROBE 做反向对照用。</summary>
    private static readonly bool On = OperatingSystem.IsLinux()
        && Environment.GetEnvironmentVariable("LP_NO_ALTSTACK") != "1";

    public static unsafe void Ensure()
    {
        if (!On || _checked) return;
        _checked = true;
        StackT cur;
        if (SigAltStack(null, &cur) != 0 || (cur.Flags & SS_DISABLE) == 0) return; // 已经有了(CoreCLR 自己的),别动它
        var b = new Block();
        var ss = new StackT { Sp = (nint)b.P, Flags = 0, Size = Size };
        if (SigAltStack(&ss, null) == 0) _mine = b;
    }
}

/// <summary>
/// <c>LP_SIGPROBE=1 ./LinPlayer</c>:线程池线程反复进 Go,旁边一个线程往它们身上打 CoreCLR 的暂停信号(SIGRTMIN),
/// 再加 GC 压力,跑 15 秒还活着就过。反向对照:加 <c>LP_NO_ALTSTACK=1</c> 必须崩(issue #65 那个死法)。
/// </summary>
internal static partial class SigProbe
{
    [LibraryImport("libc")] private static partial int gettid();
    [LibraryImport("libc")] private static partial int getpid();
    [LibraryImport("libc")] private static partial int tgkill(int tgid, int tid, int sig);
    private const int SIGRTMIN = 34; // glibc 占掉 32/33,CoreCLR 的 INJECT_ACTIVATION_SIGNAL 就是它

    public static bool Run(string dll, string version)
    {
        var core = new CoreClient(dll, Path.Combine(Path.GetTempPath(), "lp-sigprobe"), version);
        var tids = new System.Collections.Concurrent.ConcurrentDictionary<int, byte>();
        var until = DateTime.UtcNow.AddSeconds(15);
        var calls = 0L;
        var workers = Enumerable.Range(0, 16).Select(_ => Task.Run(() =>
        {
            while (DateTime.UtcNow < until)
            {
                tids.TryAdd(gettid(), 0);
                core.CallAsync("system.dataPaths", null).Wait();
                GC.KeepAlive(new byte[16384]);
                Interlocked.Increment(ref calls);
            }
        })).ToArray();
        var spray = new Thread(() =>
        {
            var pid = getpid();
            while (DateTime.UtcNow < until)
            {
                foreach (var t in tids.Keys) tgkill(pid, t, SIGRTMIN);
                Thread.Sleep(0);
            }
        }) { IsBackground = true };
        spray.Start();
        Task.WaitAll(workers);
        Console.WriteLine($"SIGPROBE ✓ 活过 15 秒:{calls} 次进 Go,{tids.Count} 个线程被打信号");
        return true;
    }
}
