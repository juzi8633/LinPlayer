using System;
using System.Collections.Generic;
using System.Globalization;
using System.Text.Json;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Media;

namespace LinPlayer.Desktop.Views;

/// <summary>
/// 插件 Canvas(D19 D104 D105):把一帧的指令流照着画出来。
///
/// <para>☠ 只认**指令名**,不认语义:加一个方法就在 <see cref="Render"/> 的 switch 里加一行。
/// 认不出来的指令直接跳过 —— 老宿主遇到新 SDK 时少画一笔,而不是整块崩掉(D319 同一条口径)。</para>
/// </summary>
public sealed class PluginCanvas : Control
{
    private JsonElement _cmds;

    public void SetCommands(JsonElement cmds)
    {
        _cmds = cmds;
        InvalidateVisual();
    }

    private sealed class State
    {
        internal IBrush Fill = Brushes.White;
        internal IBrush Stroke = Brushes.White;
        internal double LineWidth = 1;
        internal double FontSize = 14;
        internal double Alpha = 1;
        internal string TextAlign = "left";
        internal State Clone() => (State)MemberwiseClone();
    }

    public override void Render(DrawingContext g)
    {
        if (_cmds.ValueKind != JsonValueKind.Array) return;
        var s = new State();
        var stack = new Stack<State>();
        var path = new List<Point>();
        var started = false;

        foreach (var c in _cmds.EnumerateArray())
        {
            if (c.ValueKind != JsonValueKind.Array || c.GetArrayLength() == 0) continue;
            var a = c.EnumerateArray().ToArrayLocal();
            var name = a[0].ValueKind == JsonValueKind.String ? a[0].GetString() : "";
            double N(int i) => i < a.Length && a[i].ValueKind == JsonValueKind.Number ? a[i].GetDouble() : 0;
            string S(int i) => i < a.Length && a[i].ValueKind == JsonValueKind.String ? a[i].GetString() ?? "" : "";

            switch (name)
            {
                case "set":
                    switch (S(1))
                    {
                        case "fillStyle": s.Fill = BrushOf(a, 2) ?? s.Fill; break;
                        case "strokeStyle": s.Stroke = BrushOf(a, 2) ?? s.Stroke; break;
                        case "lineWidth": s.LineWidth = N(2); break;
                        case "globalAlpha": s.Alpha = N(2); break;
                        case "textAlign": s.TextAlign = S(2); break;
                        // `16px sans-serif` 这种:取前面那个数当字号,字族交给系统
                        case "font": s.FontSize = ParseFontSize(S(2)) ?? s.FontSize; break;
                    }
                    break;
                case "fillRect":
                    g.FillRectangle(Alpha(s.Fill, s.Alpha), new Rect(N(1), N(2), N(3), N(4)));
                    break;
                case "strokeRect":
                    g.DrawRectangle(new Pen(Alpha(s.Stroke, s.Alpha), s.LineWidth), new Rect(N(1), N(2), N(3), N(4)));
                    break;
                case "fillText":
                    DrawText(g, S(1), N(2), N(3), s);
                    break;
                case "beginPath":
                    path.Clear();
                    started = false;
                    break;
                case "moveTo":
                    path.Clear();
                    path.Add(new Point(N(1), N(2)));
                    started = true;
                    break;
                case "lineTo":
                    if (!started) { path.Add(new Point(N(1), N(2))); started = true; }
                    else path.Add(new Point(N(1), N(2)));
                    break;
                case "stroke":
                    for (var i = 1; i < path.Count; i++)
                        g.DrawLine(new Pen(Alpha(s.Stroke, s.Alpha), s.LineWidth), path[i - 1], path[i]);
                    break;
                case "save":
                    stack.Push(s.Clone());
                    break;
                case "restore":
                    if (stack.Count > 0) s = stack.Pop();
                    break;
                // 认不出来的跳过:少画一笔比整块崩掉好(D319)
            }
        }
    }

    private void DrawText(DrawingContext g, string text, double x, double y, State s)
    {
        if (text.Length == 0) return;
        var ft = new FormattedText(text, CultureInfo.CurrentCulture, FlowDirection.LeftToRight,
            Typeface.Default, s.FontSize, Alpha(s.Fill, s.Alpha));
        var dx = s.TextAlign switch { "center" => -ft.Width / 2, "right" => -ft.Width, _ => 0 };
        // Canvas 的 fillText 以**基线**定位,Avalonia 以左上角 —— 差一个 Baseline
        g.DrawText(ft, new Point(x + dx, y - ft.Baseline));
    }

    private static double? ParseFontSize(string font)
    {
        var i = 0;
        while (i < font.Length && (char.IsDigit(font[i]) || font[i] == '.')) i++;
        return i > 0 && double.TryParse(font[..i], NumberStyles.Float, CultureInfo.InvariantCulture, out var v) ? v : null;
    }

    private static IBrush Alpha(IBrush b, double a) =>
        a >= 1 || b is not ISolidColorBrush sc ? b : new SolidColorBrush(sc.Color, a);

    private static IBrush? BrushOf(JsonElement[] a, int i)
    {
        if (i >= a.Length || a[i].ValueKind != JsonValueKind.String) return null;
        var text = a[i].GetString() ?? "";
        if (text.StartsWith("token:", StringComparison.Ordinal)) return Tok.Of(text[6..]);
        return Color.TryParse(text, out var col) ? new SolidColorBrush(col) : null;
    }
}

internal static class JsonArrayExt
{
    /// <summary>EnumerateArray 只能走一遍,指令要按下标取 —— 先摊成数组。</summary>
    internal static JsonElement[] ToArrayLocal(this JsonElement.ArrayEnumerator e)
    {
        var list = new List<JsonElement>();
        foreach (var x in e) list.Add(x);
        return list.ToArray();
    }
}
