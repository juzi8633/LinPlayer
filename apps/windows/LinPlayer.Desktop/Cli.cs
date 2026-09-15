using System.Text.Encodings.Web;
using System.Text.Json;
using LinPlayer.Core;
using LinPlayer.Desktop.Core;

namespace LinPlayer.Desktop;

/// <summary>
/// 命令行入口:不开窗口,直接调核心层命令,结果按 JSON 打到标准输出。
/// 给 Linux 上写脚本 / ssh 远程用。数据根和界面是同一个 userdata/,账号不用再登一遍。
/// </summary>
internal static class Cli
{
    private static readonly string[] Verbs = ["call", "commands", "version", "--version", "help", "--help", "-h"];

    public static bool Is(string[] args) => args.Length > 0 && Verbs.Contains(args[0]);

    public static int Run(string[] args, string lib, string dataDir, string version)
    {
        switch (args[0])
        {
            case "version" or "--version":
                Console.WriteLine(version);
                return 0;
            case "commands":
                foreach (var c in LinPlayerCommandNames.All) Console.WriteLine(c);
                return 0;
            case "call" when args.Length is 2 or 3:
                return Call(args[1], args.Length == 3 ? args[2] : "{}", lib, dataDir, version)
                    .GetAwaiter().GetResult();
            default:
                Console.WriteLine("""
                    LinPlayer 命令行(不开窗口,直接调核心层)

                      LinPlayer call <命令> [JSON 参数 | -]   结果以 JSON 打到标准输出;- 表示从标准输入读参数
                      LinPlayer commands                      列出全部命令
                      LinPlayer version                       打印版本

                    emby.* 命令自动带上当前登录的会话(server / token / user_id),JSON 里显式给了的优先。
                    例:LinPlayer call system.capabilities
                    退出码:0 成功,1 命令失败,2 用法错误,130 被中断
                    """);
                return args[0] == "call" ? 2 : 0;
        }
    }

    private static async Task<int> Call(string command, string json, string lib, string dataDir, string version)
    {
        if (!LinPlayerCommandNames.All.Contains(command))
        {
            Console.Error.WriteLine($"没有这条命令:{command}(LinPlayer commands 列出全部)");
            return 2;
        }
        if (json == "-") json = await Console.In.ReadToEndAsync();
        Dictionary<string, JsonElement> args;
        try { args = JsonSerializer.Deserialize<Dictionary<string, JsonElement>>(json) ?? new(); }
        catch (JsonException e)
        {
            Console.Error.WriteLine($"参数得是一个 JSON 对象:{e.Message}");
            return 2;
        }

        using var cts = new CancellationTokenSource();
        Console.CancelKeyPress += (_, e) => { e.Cancel = true; cts.Cancel(); };

        CoreClient core;
        try { core = new CoreClient(lib, dataDir, version); }
        catch (Exception e)
        {
            Console.Error.WriteLine($"核心层起不来:{e.Message}");
            return 1;
        }
        using (core)
        {
            try
            {
                if (command.StartsWith("emby.") && command != "emby.currentSession")
                    await MergeSession(core, args, cts.Token);
                var data = await core.CallAsync(command, args, cts.Token);
                Console.WriteLine(data.ValueKind == JsonValueKind.Undefined
                    ? "null"
                    : JsonSerializer.Serialize(data, new JsonSerializerOptions
                    {
                        WriteIndented = true,
                        Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
                    }));
                return 0;
            }
            catch (OperationCanceledException)
            {
                return 130;
            }
            catch (CoreException e)
            {
                Console.Error.WriteLine($"[{e.Code}] {e.Advice}");
                return 1;
            }
        }
    }

    /// <summary>界面上每条 Emby 命令都带着会话四件套(见 CardActions.Merge),命令行照做,省得手抄 token。</summary>
    private static async Task MergeSession(CoreClient core, Dictionary<string, JsonElement> args, CancellationToken ct)
    {
        Views.Sess? s;
        try { s = Views.Sess.From(await core.CallAsync("emby.currentSession", null, ct)); }
        catch (CoreException) { return; } // 当前账号不是 Emby:没有会话可带,原样发出去让核心层报缺什么
        if (s is null) return;
        foreach (var (k, v) in new[] { ("server", s.server), ("token", s.token), ("user_id", s.user_id), ("device_id", s.device_id) })
            args.TryAdd(k, JsonSerializer.SerializeToElement(v));
    }
}
