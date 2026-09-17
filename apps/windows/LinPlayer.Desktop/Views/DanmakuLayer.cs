using System;
using System.Collections.Generic;
using System.Globalization;
using System.Text.Json;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Media;
using Avalonia.Media.Immutable;
using Avalonia.Media.TextFormatting;
using Avalonia.Rendering.Composition;

namespace LinPlayer.Desktop.Views;

/// <summary>一条排好版的弹幕。字段名照 <c>player.danmakuLayout</c> 的单字母键。</summary>
public sealed class DmItem
{
    public double T;
    public int Mode;
    public int Lane;
    /// <summary>核心层估的文本宽,单位是 1920×1080 画布的像素。</summary>
    public double W;
    public uint Color;
    public string Text = "";

    /// <summary>
    /// 排好的字形,一段字体一份(中文和 emoji 常常落在不同的回退字体上)。
    /// <b>正文和描边共用</b> —— 画刷是 <c>DrawGlyphRun</c> 的参数,位置靠平移矩阵。
    /// </summary>
    internal IImmutableGlyphRunReference[]? Runs;
    /// <summary>缓存是按哪一档字号排的。字号变了(换窗口大小 / 改设置)就得重排。</summary>
    internal int Gen = -1;
}

/// <summary>一整份排版结果 + 已经把缩放和速度折算进去的几何量。</summary>
public sealed class DmLayout
{
    public double ResX = 1920, ResY = 1080;
    public double LaneHeight = 54, RollSeconds = 8, FixSeconds = 5, FontSize = 40;
    public double Opacity = 1;
    public bool Bold;
    /// <summary>按时刻升序 —— 每帧靠二分找起点,不遍历全表。</summary>
    public List<DmItem> Items = [];

    public static DmLayout? Parse(JsonElement r)
    {
        if (r.ValueKind != JsonValueKind.Object) return null;
        double N(string k, double def) =>
            r.TryGetProperty(k, out var v) && v.ValueKind == JsonValueKind.Number ? v.GetDouble() : def;
        var l = new DmLayout
        {
            ResX = N("res_x", 1920), ResY = N("res_y", 1080),
            LaneHeight = N("lane_height", 54), RollSeconds = N("roll_seconds", 8),
            FixSeconds = N("fix_seconds", 5), FontSize = N("font_size", 40),
            Opacity = N("opacity", 1),
            Bold = r.TryGetProperty("bold", out var b) && b.ValueKind == JsonValueKind.True,
        };
        if (!r.TryGetProperty("items", out var arr) || arr.ValueKind != JsonValueKind.Array) return l;
        foreach (var e in arr.EnumerateArray())
        {
            double G(string k) => e.TryGetProperty(k, out var v) && v.ValueKind == JsonValueKind.Number
                ? v.GetDouble() : 0;
            var text = e.TryGetProperty("u", out var u) ? u.GetString() ?? "" : "";
            if (text.Length == 0) continue;
            l.Items.Add(new DmItem
            {
                T = G("t"), Mode = (int)G("m"), Lane = (int)G("l"), W = G("w"),
                Color = (uint)G("c"), Text = text,
            });
        }
        return l;
    }
}

/// <summary>UI 线程发给合成器线程的两种消息。合成器侧的状态只由它们改。</summary>
internal sealed record DmLayoutMsg(DmLayout? Layout);
internal sealed record DmSyncMsg(double Position, bool Paused, double Speed);

/// <summary>
/// 弹幕的真正画法,跑在<b>合成器线程</b>上。
///
/// <para>搬到这里是因为默认合成模式下 UI 那条渲染 pass 实测只有 60Hz。
/// 光搬过来还不够匀:合成模式得是按 vblank 出帧的那种,见 <c>Program.CompositionModes</c>。</para>
/// </summary>
internal sealed class DanmakuVisualHandler : CompositionCustomVisualHandler
{
    internal static readonly Typeface Face = new(FontFamily.Default);
    private static readonly Typeface FaceBold =
        new(FontFamily.Default, FontStyle.Normal, FontWeight.Bold);

    private DmLayout? _layout;
    private double _clock;
    private bool _paused = true;
    private double _speed = 1;
    private TimeSpan _synced;
    private bool _looping;

    /// <summary>硬对表的门槛。超过这么多秒就是 seek / 换集,不是抖动。</summary>
    private const double SnapThreshold = 1.0;
    /// <summary>每拍追掉多少误差。0.15 → 约 1.5 秒内收敛,且单帧位移变化看不出来。</summary>
    private const double CatchUpRatio = 0.15;

    /// <summary>
    /// OnRender 被调到的次数。<b>探针拿它对账</b>(<c>LP_DMPROBE</c>):
    /// 画没画到(&gt;0)、节拍跟不跟得上刷新率 —— 两件事编译器都管不着。
    /// </summary>
    internal static long Rendered;
    /// <summary>探针开着时记每帧真正开画的时刻(毫秒),量帧间隔抖不抖。平时是 null。</summary>
    internal static List<double>? FrameTimes;

    private int _gen;
    private double _genFont = -1;
    private bool _genBold;
    private byte _genAlpha;
    private int _liveFrom;
    private readonly Dictionary<uint, IImmutableBrush> _brushes = [];
    private IImmutableBrush _shadow = new ImmutableSolidColorBrush(Colors.Black);

    /// <summary>当前这一刻的弹幕钟。两拍轮询之间靠<b>合成器自己的钟</b>往前推。</summary>
    private double NowClock() =>
        _paused ? _clock : _clock + (CompositionNow - _synced).TotalSeconds * _speed;

    public override void OnMessage(object message)
    {
        switch (message)
        {
            case DmLayoutMsg m:
                _layout = m.Layout;
                _liveFrom = 0;
                break;

            /* 对表。轮询每 250ms 来一拍,两拍之间自己按帧往前推。
               但**不能每拍硬拽回轮询值**:Position 是 mpv 的 time-pos,它只在
               视频帧边界更新(24fps 片源 = 41.7ms 一个台阶),再叠 FFI 往返抖动 ——
               硬拽等于每秒把整屏弹幕拉扯四下,往后拽那一下就是肉眼看到的顿。
               差得离谱(seek / 换集)才硬对,平时只追掉一小半。 */
            case DmSyncMsg m:
                var now = NowClock();          // 先按**旧的**暂停态算,否则暂停那一拍少推一截
                _paused = m.Paused;
                _speed = m.Speed <= 0 ? 1 : m.Speed;
                var drift = m.Position - now;
                _clock = Math.Abs(drift) > SnapThreshold ? m.Position : now + drift * CatchUpRatio;
                _synced = CompositionNow;
                break;
        }
        Kick();
    }

    /// <summary>该转就转起来。<b>只在有弹幕且没暂停时转</b> —— 一直转着的话没弹幕的片子也在烧电。</summary>
    private void Kick()
    {
        if (_looping || _paused || _layout is not { Items.Count: > 0 }) return;
        _looping = true;
        RegisterForNextAnimationFrameUpdate();
    }

    public override void OnAnimationFrameUpdate()
    {
        if (_paused || _layout is not { Items.Count: > 0 }) { _looping = false; return; }
        Invalidate();
        RegisterForNextAnimationFrameUpdate();
    }

    public override void OnRender(ImmediateDrawingContext ctx)
    {
        System.Threading.Interlocked.Increment(ref Rendered);   // 探针对账用,见 Rendered
        if (FrameTimes is { } ft) lock (ft) ft.Add(System.Diagnostics.Stopwatch.GetTimestamp() * 1000.0 / System.Diagnostics.Stopwatch.Frequency);
        var l = _layout;
        if (l is null || l.Items.Count == 0) return;
        var w = EffectiveSize.X;
        var h = EffectiveSize.Y;
        if (w <= 0 || h <= 0) return;

        var now = NowClock();
        // 按高度换算比例:行数和字号是相对画面高度定的,宽高各自缩会把字压扁
        var sy = h / l.ResY;
        var font = l.FontSize * sy;
        var laneH = l.LaneHeight * sy;
        var a = (byte)Math.Clamp(l.Opacity * 255, 0, 255);
        var face = l.Bold ? FaceBold : Face;
        if (Math.Abs(font - _genFont) > 0.5 || _genBold != l.Bold || _genAlpha != a)
        {
            _genFont = font; _genBold = l.Bold; _genAlpha = a; _gen++;
            _brushes.Clear();
            // 描边靠一层黑影垫底。四向描边要多画四遍,而弹幕本来就是薄薄一层
            _shadow = new ImmutableSolidColorBrush(Color.FromArgb((byte)(a * 3 / 4), 0, 0, 0));
        }
        var off = Math.Max(1.0, font * 0.05);

        var life = Math.Max(l.RollSeconds, l.FixSeconds);
        var from = FirstAtOrAfter(l.Items, now - life);
        // 已经滚出去的那些把字形丢掉 —— 不丢的话一部番看完攒着上万份排版结果
        for (var i = _liveFrom; i < from && i < l.Items.Count; i++)
        {
            l.Items[i].Runs = null;
            l.Items[i].Gen = -1;
        }
        _liveFrom = from;

        for (var i = from; i < l.Items.Count; i++)
        {
            var d = l.Items[i];
            if (d.T > now) break;
            var age = now - d.T;
            var span = d.Mode == 1 ? l.RollSeconds : l.FixSeconds;
            if (age > span) continue;
            var wPx = d.W * sy;
            double x, top;
            if (d.Mode == 1)
            {
                // 按实际像素插:横向铺满整块画面,不经过 1920 画布那一道
                x = RollX(w, wPx, age, l.RollSeconds);
                top = 4 * sy + d.Lane * laneH;
            }
            else
            {
                x = (w - wPx) / 2;
                top = d.Mode == 5
                    ? 4 * sy + d.Lane * laneH
                    : h - 4 * sy - (d.Lane + 1) * laneH;
            }
            if (x > w || x + wPx < 0) continue;

            if (d.Gen != _gen || d.Runs is null)
            {
                d.Runs = Shape(d.Text, face, font);
                d.Gen = _gen;
            }
            if (d.Runs.Length == 0) continue;   // 这条整形不出来就跳过,别让一条坏数据停掉整屏
            if (!_brushes.TryGetValue(d.Color, out var brush))
                _brushes[d.Color] = brush = new ImmutableSolidColorBrush(Color.FromArgb(
                    a, (byte)(d.Color >> 16), (byte)(d.Color >> 8), (byte)d.Color));

            /* 位置全靠平移矩阵,<b>字形固定在原点</b>。
               GlyphRun 的 BaselineOrigin 是构造时烤进去的,跟着每帧的 x 变就得每帧重建 ——
               那正是 2026-09-11 修掉的「每帧重排版」。推一个矩阵是零分配。 */
            using (ctx.PushPreTransform(Matrix.CreateTranslation(x + off, top + off)))
                foreach (var r in d.Runs) ctx.DrawGlyphRun(_shadow, r);
            using (ctx.PushPreTransform(Matrix.CreateTranslation(x, top)))
                foreach (var r in d.Runs) ctx.DrawGlyphRun(brush, r);
        }
    }

    /// <summary>
    /// 把一串字整形成可画的字形,原点在左上角。整形不了回空数组。
    ///
    /// <para>走 <c>TextLayout</c> 而不是直接 <c>TextShaper</c>:后者只认给它的那一个字体,
    /// 默认字体里没有的字(中文、emoji)全画成方块 —— 2026-09-17 用户报「一堆口口口」。
    /// 回退字体是 <c>TextLayout</c> 逐段挑的,这里只把它挑好的每一段照原样搬出来。</para>
    /// </summary>
    private static IImmutableGlyphRunReference[] Shape(string text, Typeface face, double font)
    {
        var refs = new List<IImmutableGlyphRunReference>();
        foreach (var run in ShapeRuns(text, face, font))
            if (run.TryCreateImmutableGlyphRunReference() is { } r) refs.Add(r);
        return [.. refs];
    }

    /// <summary>整形出来的字形段。单拎出来是给探针查「有没有落成 .notdef(方块)」。</summary>
    internal static List<GlyphRun> ShapeRuns(string text, Typeface face, double font)
    {
        var runs = new List<GlyphRun>();
        try
        {
            using var layout = new TextLayout(text, face, font, null);
            foreach (var line in layout.TextLines)
            {
                var x = 0.0;
                foreach (var tr in line.TextRuns)
                {
                    if (tr is not ShapedTextRun sr) continue;
                    var buf = sr.ShapedBuffer;
                    // 必须拷出来:ShapedBuffer 是池里借的,layout 一释放就还回去被别人写花
                    var infos = new GlyphInfo[buf.Length];
                    for (var i = 0; i < infos.Length; i++) infos[i] = buf[i];
                    runs.Add(new GlyphRun(buf.GlyphTypeface, buf.FontRenderingEmSize, buf.Text, infos,
                        new Point(x, line.Baseline), buf.BidiLevel));
                    x += sr.Size.Width;
                }
            }
        }
        catch
        {
            /* 整形失败只该少画这一条,不该把整屏弹幕停掉 ——
               这一层是盖在画面上的装饰,它挂了不能影响看片。 */
            runs.Clear();
        }
        return runs;
    }

    /// <summary>
    /// 一条滚动弹幕在 <paramref name="age"/> 秒时的左边缘。
    ///
    /// <para>走的距离是 <c>width + w</c> 不是 <c>width</c> —— 少算这一截的表现是
    /// 长弹幕还没走完就在左边被瞬间抹掉。</para>
    /// </summary>
    public static double RollX(double width, double w, double age, double roll) =>
        width - age / roll * (width + w);

    /// <summary>二分找第一条 <c>T &gt;= from</c> 的下标。列表按 T 升序。</summary>
    public static int FirstAtOrAfter(List<DmItem> items, double from)
    {
        int lo = 0, hi = items.Count;
        while (lo < hi)
        {
            var mid = (lo + hi) / 2;
            if (items[mid].T < from) lo = mid + 1; else hi = mid;
        }
        return lo;
    }
}

/// <summary>
/// 弹幕层。排版在核心层(<c>core/player/danmakustyle.go</c>),
/// 这一层只负责把合成器视觉挂上去、把状态转发过去。
///
/// <para>以前是交给 mpv 的 <c>osd-overlay</c> 画的:位置得从 <c>time-pos</c> 插,
/// 而它只在**视频帧边界**更新 —— 24fps 片源上弹幕跟着 24Hz 一顿一顿;
/// 每秒 120 条 overlay 命令还全压在 mpv 的核心线程上,而那条线程同时在解图形字幕。</para>
/// </summary>
public sealed class DanmakuLayer : Control
{
    private CompositionCustomVisual? _visual;
    private DmLayout? _layout;
    private DmSyncMsg _lastSync = new(0, true, 1);

    public DanmakuLayer()
    {
        IsHitTestVisible = false;
    }

    public DmLayout? Layout
    {
        get => _layout;
        set { _layout = value; Send(new DmLayoutMsg(value)); }
    }

    /// <summary>对表。由播放页的状态轮询每 250ms 调一次。</summary>
    public void Sync(double position, bool paused, double speed)
    {
        _lastSync = new DmSyncMsg(position, paused, speed);
        Send(_lastSync);
    }

    private void Send(object msg)
    {
        Ensure();
        _visual?.SendHandlerMessage(msg);
    }

    /// <summary>
    /// 把合成器视觉挂上来。
    ///
    /// <para><c>GetElementVisual</c> 在刚挂上树的那一刻可能还是 null(探针里泵了
    /// 500ms 才拿到),所以**每次要用的时候都试一遍**,不是只在
    /// <c>OnAttachedToVisualTree</c> 里试一次 —— 只试一次的表现是「偶尔整层不出来」。</para>
    /// </summary>
    private void Ensure()
    {
        if (_visual is not null) return;
        if (ElementComposition.GetElementVisual(this) is not { } host) return;
        _visual = host.Compositor.CreateCustomVisual(new DanmakuVisualHandler());
        _visual.Size = new Vector(Bounds.Width, Bounds.Height);
        ElementComposition.SetElementChildVisual(this, _visual);
        // 挂晚了的话前面那几条消息都丢了 —— 补发一次当前状态
        _visual.SendHandlerMessage(new DmLayoutMsg(_layout));
        _visual.SendHandlerMessage(_lastSync);
    }

    protected override void OnAttachedToVisualTree(VisualTreeAttachmentEventArgs e)
    {
        base.OnAttachedToVisualTree(e);
        Ensure();
    }

    protected override void OnDetachedFromVisualTree(VisualTreeAttachmentEventArgs e)
    {
        base.OnDetachedFromVisualTree(e);
        // 摘干净:留着的话它还挂在一棵已经卸载的树上,而下次进来会再造一个
        ElementComposition.SetElementChildVisual(this, null);
        _visual = null;
    }

    protected override Size ArrangeOverride(Size finalSize)
    {
        var s = base.ArrangeOverride(finalSize);
        Ensure();
        // 尺寸是合成器侧算位置的基准(EffectiveSize),不同步的话全屏切换后弹幕位置全错
        if (_visual is not null) _visual.Size = new Vector(s.Width, s.Height);
        return s;
    }
}
