using System;
using System.Collections.Generic;
using Avalonia.Controls;

namespace LinPlayer.Desktop.Views;

/// <summary>
/// 按内容区宽度给一个缩放系数。**页面里那些写死的尺寸都要过它一遍。**
///
/// <para>☠ 放开 <c>MinWidth</c> 只是让窗口能拉小,里面的东西不跟着缩就等于没适配
/// (用户 2026-09-11:「不是说简单的允许自由缩放就行了,里面的样式也要能够自由缩放」)。
/// 写死的 158/256 卡宽、34px 标题、220×330 海报在 400px 宽的窗口上全都过大 ——
/// 一行只放得下一张卡、标题占掉三行、海报把简介挤没。</para>
/// </summary>
internal static class Responsive
{
    /// <summary>
    /// 基准内容宽:1280 窗口 − 侧栏 212 − 水槽 36 ≈ 1030。
    /// <para><b>只往小缩,不往大放。</b> 往大放会改掉用户已经认可的那一档版式,
    /// 而他报的是窗口变小之后的事。</para>
    /// </summary>
    public const double BaseWidth = 1030;

    /// <summary>
    /// 系数下限。再小下去字就读不了了,那不是适配是糊。
    /// <para><b>必须是 0.05 的整数倍</b> —— 量化和夹取是两步,不对齐的话夹出来的那一档
    /// 不在量化网格上(自检第一条就是这么红的:0.62 量化成 0.60,反而掉到下限以下)。</para>
    /// </summary>
    private const double Floor = 0.60;

    /// <summary>
    /// 内容宽 → 系数,**量化到 0.05 一档**。
    ///
    /// <para>量化不是偷懒:拖窗口时 SizeChanged 每帧都发,不量化的话每帧都是一个新系数,
    /// 凡是靠它重建控件的地方(横向轨道)就变成每帧重建一屏卡片。
    /// 量化之后整段拖动最多换 8 档。</para>
    /// </summary>
    public static double Of(double contentWidth)
    {
        if (contentWidth <= 1) return 1;          // 还没量到宽度:按基准画,量到了会再来一次
        // ☠ **先量化再夹**。反过来的话夹出来的下限会被量化推到下限以下
        var s = Math.Round(contentWidth / BaseWidth * 20) / 20;
        return Math.Clamp(s, Floor, 1.0);
    }

    /// <summary>按系数缩一个尺寸,<paramref name="min"/> 是绝对下限。</summary>
    public static double S(double contentWidth, double value, double min) =>
        Math.Max(min, value * Of(contentWidth));

    /// <summary>
    /// 一张卡**最少**多宽。
    /// <para>下限按「一行至少还能并两张」定:窄卡 104、宽卡 168。
    /// 不给下限的话窗口拉到 300 宽时一行只剩一张巨卡,那正是用户看到的样子。</para>
    /// </summary>
    public static double CardMin(double contentWidth, bool wide) =>
        S(contentWidth, wide ? 256 : 158, wide ? 168 : 104);

    /// <summary>
    /// 字号按系数缩,夹在 <paramref name="min"/> 和原值之间。
    /// <para>字号**不能和尺寸用同一个系数**:卡片缩到 0.62 还能看,12.5px 的字缩到
    /// 7.75px 就读不了了。所以字号走「缩一半幅度」—— 1 − (1−s)/2。</para>
    /// </summary>
    public static double Font(double contentWidth, double value, double min) =>
        Math.Max(min, value * HalfOf(contentWidth));

    /// <summary>尺寸系数的「一半幅度」版本。抽出来是为了让自检能单独钉住它。</summary>
    public static double HalfOf(double contentWidth) => 1 - (1 - Of(contentWidth)) / 2;

    /// <summary>
    /// 自检:缩放曲线的四条约定。返回失败条数。
    ///
    /// <para>这一层没有单测工程(整个壳都没有),所以做成和
    /// <c>LP_FONTPROBE</c> / <c>LP_SCROLLPROBE</c> 同一路的探针:
    /// <c>LP_SCALEPROBE=1 LinPlayer.exe</c> 打几行就退,不开窗口。
    /// 不可运行的约定等于没有约定。</para>
    /// </summary>
    public static int SelfCheck(Action<string> say)
    {
        var bad = 0;
        void Want(bool ok, string what)
        {
            say((ok ? "PROBE 缩放 ✓ " : "PROBE 缩放 ✗ ") + what);
            if (!ok) bad++;
        }

        // ① 基准宽及以上不动 —— 放大会改掉用户已经认可的那一档版式
        Want(Math.Abs(Of(BaseWidth) - 1) < 1e-9 && Math.Abs(Of(4000) - 1) < 1e-9,
            "基准宽以上系数恒为 1(不往大放)");
        // ② 窗口越窄系数越小,但有底
        Want(Of(600) < Of(800) && Of(800) < Of(1000) && Of(100) >= Floor,
            "越窄系数越小,且不低于下限");
        // ③ 量化:拖窗口的整条路上最多几档。不量化的话横向轨道每帧重建
        var steps = new HashSet<double>();
        for (var w = 200.0; w <= 1600; w += 1) steps.Add(Of(w));
        Want(steps.Count <= 10, $"档位数 {steps.Count} 条(不量化的话会是上千条)");
        /* ④ 卡宽两头都要钓:**下限不许跌穿**(跌穿了就是一排糊成一团的小砖),
           同时 300 宽的内容区还得并得下两张。
           ☆ 只钓后一半是**假绿**：把下限改成 0 照样能并两张 —— 实测过。 */
        Want(Math.Abs(CardMin(200, false) - 104) < 1e-9 && Math.Abs(CardMin(200, true) - 168) < 1e-9,
            $"卡宽守住可读下限(窄卡 {CardMin(200, false):0.#} / 宽卡 {CardMin(200, true):0.#})");
        var two = CardMin(300, false) * 2 + 14;
        Want(two <= 300, $"300 宽还能并两张窄卡(算出来 {two:0.#})");
        /* ⑤ 字号走「缩一半幅度」的曲线。
           ☆ 比的是 **HalfOf 本身**，不是 Font(…, min) 的结果 ——
             后者被 min 夹住之后，把公式换成和尺寸同一个系数照样绿。 */
        Want(HalfOf(300) > Of(300) + 0.05 && HalfOf(BaseWidth) == 1,
            $"字号缩得比尺寸温和(300 宽上 {HalfOf(300):0.00} vs {Of(300):0.00})");
        Want(Font(300, 34, 21) >= 21, "字号守住下限");
        return bad;
    }

    /// <summary>
    /// 盯住宿主的宽度,**只在档位变了的时候**回调一次,参数是当前宽度。
    /// <para>进了可视树就先回调一次 —— 控件构造时 <c>Bounds</c> 还是 0,
    /// 只靠 SizeChanged 的话首屏会按基准尺寸画出来再跳一次版。</para>
    /// </summary>
    public static void Watch(Control host, Action<double> apply)
    {
        var last = double.NaN;
        void Maybe(double w)
        {
            var s = Of(w);
            if (Math.Abs(s - last) < 1e-6) return;
            last = s;
            apply(w);
        }
        host.SizeChanged += (_, e) => Maybe(e.NewSize.Width);
        host.AttachedToVisualTree += (_, _) => Maybe(host.Bounds.Width);
    }
}
