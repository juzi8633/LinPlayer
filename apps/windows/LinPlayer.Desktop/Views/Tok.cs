using Avalonia;
using Avalonia.Controls;
using Avalonia.Media;
using LinPlayer.Desktop.Core;

namespace LinPlayer.Desktop.Views;

/// <summary>
/// 按当前主题取一支画刷。
///
/// 写死色号在深色下勉强能看,浅色主题下就是错的 —— 而且不报错。
/// 唯一允许写死的是**带 alpha 的叠加色**:那底下是画面不是背景色。
///
/// 别拿它初始化 static readonly 字段:类型初始化可能早于 Application.Current。
/// </summary>
/// <summary>
/// 图标字体。界面里写的全是 Segoe MDL2 的码位,但那是 Windows 自带字体、不能随包带 ——
/// 别的平台换成按同一批码位编的 LinIcons(scripts/gen-icon-font.py),界面代码不用改。
/// 新加图标记得在那个脚本里补一行,漏了在 Linux 上就是豆腐块。
/// </summary>
public static class Glyph
{
    public static FontFamily Font { get; } = OperatingSystem.IsWindows()
        ? new FontFamily("Segoe MDL2 Assets")
        : new FontFamily("avares://LinPlayer/Assets/LinIcons.ttf#LinIcons");
}

public static class Tok
{
    public static IBrush Of(string key)
    {
        if (Application.Current is { } app
            && app.TryFindResource(key, app.ActualThemeVariant, out var v) && v is IBrush b)
            return b;
        /* 名字打错的表现是**那段文字整个看不见**,而且编译绿、运行不报错。
           2026-09-06 实测抓到:Tok.Of("Ink1") —— token 叫 Ink,没有 Ink1,
           于是快捷键设置页左边一列动作名全是透明的。留一条日志才查得动。 */
        Log.D("主题", $"没有这个 token: {key}(那一处会画成透明)");
        return Brushes.Transparent;
    }
}
