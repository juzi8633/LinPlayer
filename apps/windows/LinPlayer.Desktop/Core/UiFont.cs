using System.Diagnostics.CodeAnalysis;
using System.Globalization;
using Avalonia;
using Avalonia.Controls.ApplicationLifetimes;
using Avalonia.Media;
using Avalonia.Media.Fonts;
using Avalonia.Platform;

namespace LinPlayer.Desktop.Core;

/// <summary>
/// 用户自己导入的界面字体(一个磁盘上的 .ttf / .otf)。
///
/// <para>☠ Avalonia 只从 <c>avares:</c> 里读字体,磁盘上的字体只能自己实现一个
/// <see cref="IFontCollection"/>;粗体斜体走 <see cref="FontSimulations"/> 模拟。
/// 实测依据与升 Avalonia 时的自检办法见 <c>docs/lessons/ui-desktop.md</c>。</para>
/// </summary>
public sealed class FileFontCollection : IFontCollection
{
    public static readonly Uri CollectionKey = new("fonts:linplayer/user");

    private readonly string _path;
    private readonly List<FontFamily> _families = [];
    private readonly Dictionary<FontSimulations, IGlyphTypeface> _faces = [];

    public FileFontCollection(string path) => _path = path;

    /// <summary>字体家族名。加载失败时是空串 —— 调用方据此决定要不要换。</summary>
    public string FamilyName { get; private set; } = "";

    public Uri Key => CollectionKey;
    public int Count => _families.Count;
    public FontFamily this[int index] => _families[index];
    public IEnumerator<FontFamily> GetEnumerator() => _families.GetEnumerator();
    System.Collections.IEnumerator System.Collections.IEnumerable.GetEnumerator() => GetEnumerator();
    public void Dispose() { }

    /// <summary>
    /// <c>IFontManagerImpl.TryCreateGlyphTypeface(Stream, FontSimulations, out IGlyphTypeface)</c>。
    /// internal,只能反射拿。拿不到 = 这个 Avalonia 版本上界面字体整个不可用,
    /// 那时要**看得见**:设置页回「这个文件读不了」,日志写明真实原因。
    /// </summary>
    private static readonly System.Reflection.MethodInfo? FromStream =
        typeof(IFontManagerImpl).GetMethod(
            "TryCreateGlyphTypeface",
            System.Reflection.BindingFlags.Public | System.Reflection.BindingFlags.NonPublic
            | System.Reflection.BindingFlags.Instance,
            null,
            [typeof(Stream), typeof(FontSimulations), typeof(IGlyphTypeface).MakeByRefType()],
            null);

    public void Initialize(IFontManagerImpl fontManager)
    {
        if (FromStream is null)
        {
            Log.W("字体", "Avalonia 换了内部接口(IFontManagerImpl.TryCreateGlyphTypeface 找不到),"
                        + "自定义界面字体在这个版本上用不了");
            return;
        }

        foreach (var sim in new[]
                 {
                     FontSimulations.None, FontSimulations.Bold,
                     FontSimulations.Oblique, FontSimulations.Bold | FontSimulations.Oblique,
                 })
        {
            try
            {
                // 每一档都要一条**自己的**流:TryCreateGlyphTypeface 会把流读到底
                using var s = File.OpenRead(_path);
                var call = new object?[] { s, sim, null };
                if (FromStream.Invoke(fontManager, call) is not true) continue;
                if (call[2] is not IGlyphTypeface face) continue;
                _faces[sim] = face;
                if (FamilyName.Length == 0)
                {
                    FamilyName = face.FamilyName;
                    _families.Add(new FontFamily(CollectionKey, FamilyName));
                }
            }
            catch
            {
                /* 文件被删了 / 换机恢复之后路径还在 / 根本不是字体。
                   这三种都只该回到默认字体,不该让程序起不来。 */
            }
        }
    }

    public bool TryGetGlyphTypeface(string familyName, FontStyle style, FontWeight weight,
        FontStretch stretch, [NotNullWhen(true)] out IGlyphTypeface? glyphTypeface)
    {
        var sim = FontSimulations.None;
        if (weight >= FontWeight.Bold) sim |= FontSimulations.Bold;
        if (style != FontStyle.Normal) sim |= FontSimulations.Oblique;
        if (!_faces.TryGetValue(sim, out glyphTypeface))
            _faces.TryGetValue(FontSimulations.None, out glyphTypeface);
        return glyphTypeface is not null;
    }

    public bool TryMatchCharacter(int codepoint, FontStyle style, FontWeight weight,
        FontStretch stretch, string? familyName, CultureInfo? culture, out Typeface typeface)
    {
        /* 这份集合里只有一个字体。它认不认这个字**交给上一层的系统回退** ——
           在这里硬报「认识」的话,字体缺的字会画成豆腐块,而不是回落到系统字体。
           一份只有拉丁字母的字体导进来之后,整个中文界面会全变方块。 */
        typeface = default;
        return false;
    }
}

/// <summary>界面字体的装卸。路径存在核心层偏好的 <c>ui_font</c> 里。</summary>
public static class UiFont
{
    /// <summary>当前生效的家族;没换过是 null。</summary>
    public static FontFamily? Current { get; private set; }

    /// <summary>
    /// 起手装一次。**必须在建窗口之前** —— 之后装的话已经建出来的控件不会补上,
    /// 表现是「设了要重启两次才生效」。
    ///
    /// <para>★ 最多等 1.5 秒:核心层要是卡住了,宁可用默认字体起来,也不能白屏。</para>
    /// </summary>
    public static void ApplyAtStartup(CoreClient? core)
    {
        if (core is null) return;
        try
        {
            var t = Task.Run(() => core.CallAsync("prefs.getPrefs", new { }));
            if (!t.Wait(1500)) return;
            var path = t.Result.TryGetProperty("ui_font", out var v) ? v.GetString() : null;
            Apply(path);
        }
        catch (Exception e) { Log.W("字体", $"界面字体没装上,用默认字体:{e.Message}"); }
    }

    /// <summary>换字体。空路径 = 回默认。返回是否真的换上了。</summary>
    public static bool Apply(string? path)
    {
        try { FontManager.Current.RemoveFontCollection(FileFontCollection.CollectionKey); }
        catch { /* 没装过就没得卸 */ }

        Current = null;
        if (!string.IsNullOrWhiteSpace(path) && File.Exists(path))
        {
            try
            {
                var c = new FileFontCollection(path);
                FontManager.Current.AddFontCollection(c);
                if (c.FamilyName.Length > 0) Current = new FontFamily(FileFontCollection.CollectionKey, c.FamilyName);
                else FontManager.Current.RemoveFontCollection(FileFontCollection.CollectionKey);
            }
            catch (Exception e) { Log.W("字体", $"这个字体读不了:{e.Message}"); }
        }

        // 已经开着的窗口当场换,不用重启
        if (Application.Current?.ApplicationLifetime is IClassicDesktopStyleApplicationLifetime d)
        {
            foreach (var w in d.Windows) w.FontFamily = Current ?? FontFamily.Default;
        }
        return Current is not null;
    }
}
