using System.IO;
using System.Reflection;
using System.Text.RegularExpressions;
using Sentry;
using Sentry.Extensibility;
using Sentry.Protocol.Envelopes;

namespace LinPlayer.Desktop;

/// <summary>
/// 崩溃上报(Sentry)。只做两件事:未处理异常 + 匿名活跃人数(Release Health)。
///
/// <para>隐私口径照搬 Rust 栈那版(<c>git show rust-final:apps/desktop/src/telemetry.rs</c>):
/// 不采 PII、不开性能追踪;出站前把主目录抹成 <c>~</c>(里面嵌着 Windows 用户名),
/// 把 URL 里的 api_key/token 抹掉(Emby 请求 URL 就带 token)。</para>
/// </summary>
internal static class Telemetry
{
    /// <summary>构建时注入的 DSN。本地构建为空 → 不启用,开发机的崩溃不灌进线上。</summary>
    private static string Dsn =>
        typeof(Telemetry).Assembly.GetCustomAttributes<AssemblyMetadataAttribute>()
            .FirstOrDefault(a => a.Key == "SentryDsn")?.Value ?? "";

    public static IDisposable? Init(string version)
    {
        if (Dsn.Length == 0 || Views.Report.Off) return null;
        return SentrySdk.Init(o => Configure(o, Dsn, version));
    }

    private static void Configure(SentryOptions o, string dsn, string version)
    {
        o.Dsn = dsn;
        // 前缀和安卓端区分:两端进同一个项目,活跃人数徽章要一起数
        o.Release = $"linplayer-pc@{version}";
        o.SendDefaultPii = false;
        o.TracesSampleRate = 0;
        o.AutoSessionTracking = true;
        o.IsGlobalModeEnabled = true;
        var home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        o.SetBeforeSend((ev, _) =>
        {
            if (ev.Message is { } m)
                ev.Message = new SentryMessage { Message = Scrub(m.Message, home), Formatted = Scrub(m.Formatted, home) };
            foreach (var ex in ev.SentryExceptions ?? []) ex.Value = Scrub(ex.Value, home);
            return ev;
        });
    }

    private static readonly Regex SecretQuery = new(
        @"(?i)\b(api_key|apikey|x-emby-token|token|access_token|pw|password|sign|authorization)=[^&\s""'<>]+");

    internal static string? Scrub(string? s, string home)
    {
        if (string.IsNullOrEmpty(s)) return s;
        // 太短的(比如根目录)不抹,免得把无关字符替没了
        if (home.Length >= 4) s = s.Replace(home, "~", StringComparison.OrdinalIgnoreCase);
        return SecretQuery.Replace(s, "$1=<redacted>");
    }

    /// <summary>
    /// 自检:走真的 SDK 管线发一条事件,截住出站的信封看里面还有没有主目录和 token。
    /// 只测 <see cref="Scrub"/> 证明不了它<b>被接上了</b> —— BeforeSend 漏挂时纯函数照样绿。
    /// </summary>
    public static bool Probe()
    {
        var sent = new List<string>();
        var home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        using (SentrySdk.Init(o =>
        {
            Configure(o, "https://k@o0.invalid/0", "0.0.0-probe");
            o.AutoSessionTracking = false;
            o.Transport = new Capture(sent);
        }))
        {
            // 真抛一次:要有堆栈帧,才验得到「行号在本机就解出来了」
            try { throw new IOException($@"打不开 {home}\userdata\x.db ?api_key=SECRET123&x=1"); }
            catch (IOException e) { SentrySdk.CaptureException(e); }
            SentrySdk.Flush();
        }
        // 信封是 JSON:反斜杠和 < 都被转义过,不先还原的话「不含主目录」这条永远成立
        var all = Regex.Unescape(string.Join("\n", sent));
        var ok = sent.Count > 0 && !all.Contains(home, StringComparison.OrdinalIgnoreCase)
                 && !all.Contains("SECRET123") && all.Contains("api_key=<redacted>")
                 && all.Contains("\"lineno\":");
        Console.WriteLine(ok ? "PROBE 崩溃上报 ✓ 出站事件带行号,主目录和 token 都抹掉了"
            : $"PROBE 崩溃上报 ✗ 发出 {sent.Count} 条,抹得不对:{all}");
        return ok;
    }

    private sealed class Capture(List<string> sink) : ITransport
    {
        public async Task SendEnvelopeAsync(Envelope envelope, CancellationToken ct = default)
        {
            using var ms = new MemoryStream();
            await envelope.SerializeAsync(ms, null, ct);
            sink.Add(System.Text.Encoding.UTF8.GetString(ms.ToArray()));
        }
    }
}
