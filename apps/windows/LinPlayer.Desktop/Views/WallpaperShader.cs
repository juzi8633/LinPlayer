using System;
using System.Diagnostics;
using System.Net.Http;
using System.Runtime.InteropServices;
using Avalonia;
using Avalonia.Controls;
using Avalonia.OpenGL;
using Avalonia.OpenGL.Controls;
using Avalonia.Threading;
using LinPlayer.Desktop.Core;
using static Avalonia.OpenGL.GlConsts;

namespace LinPlayer.Desktop.Views;

/// <summary>
/// 着色器壁纸(SPEC 11.5,D564 的桌面降级路线)。包内一份 GLSL 片元着色器,铺满整层。
///
/// <para>约定:着色器只写 <c>void main()</c>,可用 <c>uniform float iTime</c> 与
/// <c>uniform vec2 iResolution</c>;版本头与 <c>precision</c> 由宿主按上下文补。</para>
///
/// <para>☠ 这里<b>不碰核心层的 GL 通道</b>(<c>lp_gl_*</c>)—— 那条是 mpv 独占的全局单例,
/// 借来画壁纸会把播放页弄黑(D561)。这一层是 Avalonia 自己的上下文。</para>
/// </summary>
internal sealed class WallpaperShader : OpenGlControlBase
{
    private static readonly HttpClient Http = new() { Timeout = TimeSpan.FromSeconds(10) };

    private readonly string _frag;
    private readonly Stopwatch _clock = Stopwatch.StartNew();
    private int _prog, _vbo, _uTime, _uRes;
    private Action<int, float, float>? _uniform2f;
    private bool _dead;

    private WallpaperShader(string frag) => _frag = frag;

    /// <summary>先给一块空层,GLSL 取回来再填进去 —— 取包内文件不该卡住 UI 线程。</summary>
    public static Control Of(string url)
    {
        var box = new Panel();
        _ = System.Threading.Tasks.Task.Run(async () =>
        {
            string src;
            try { src = await Http.GetStringAsync(url); }
            catch (Exception e) { Log.W("壁纸", $"着色器读不到({url}):{e.Message}"); return; }
            if (src.Length > 0)
                Dispatcher.UIThread.Post(() => box.Children.Add(new WallpaperShader(src)));
        });
        return box;
    }

    protected override void OnOpenGlInit(GlInterface gl)
    {
        // GLES 要 precision 限定符,桌面 GL 的 #version 110 里没有这个关键字 —— 补错一边就整块不亮
        var es = (gl.Version ?? "").Contains("OpenGL ES", StringComparison.OrdinalIgnoreCase);
        var head = es ? "precision mediump float;\n" : "";
        int vsh = gl.CreateShader(GL_VERTEX_SHADER), fsh = gl.CreateShader(GL_FRAGMENT_SHADER);
        var err = gl.CompileShaderAndGetError(vsh, "attribute vec2 p;void main(){gl_Position=vec4(p,0.0,1.0);}")
                  ?? gl.CompileShaderAndGetError(fsh, head + "uniform float iTime;uniform vec2 iResolution;\n" + _frag);
        if (!string.IsNullOrEmpty(err)) { Fail("着色器编译不过:" + err); return; }
        _prog = gl.CreateProgram();
        gl.AttachShader(_prog, vsh);
        gl.AttachShader(_prog, fsh);
        gl.BindAttribLocationString(_prog, 0, "p");
        if (gl.LinkProgramAndGetError(_prog) is { Length: > 0 } le) { Fail("着色器链接不过:" + le); return; }

        _uTime = gl.GetUniformLocationString(_prog, "iTime");
        _uRes = gl.GetUniformLocationString(_prog, "iResolution");
        // GlInterface 只到 Uniform1f,vec2 得自己绑一个
        var p = gl.GetProcAddress("glUniform2f");
        if (p != IntPtr.Zero)
            _uniform2f = Marshal.GetDelegateForFunctionPointer<Uniform2fFn>(p).Invoke;

        // 两个三角拼成的全屏矩形(GlConsts 里没有 TRIANGLE_STRIP,索性直接给六个点)
        float[] quad = [-1, -1, 1, -1, -1, 1, -1, 1, 1, -1, 1, 1];
        _vbo = gl.GenBuffer();
        gl.BindBuffer(GL_ARRAY_BUFFER, _vbo);
        unsafe
        {
            fixed (float* q = quad)
                gl.BufferData(GL_ARRAY_BUFFER, (IntPtr)(quad.Length * sizeof(float)), (IntPtr)q, GL_STATIC_DRAW);
        }
    }

    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    private delegate void Uniform2fFn(int location, float a, float b);

    protected override void OnOpenGlRender(GlInterface gl, int fb)
    {
        if (_dead || _prog == 0) return;
        var scale = (VisualRoot as TopLevel)?.RenderScaling ?? 1.0;
        int w = Math.Max(1, (int)(Bounds.Width * scale)), h = Math.Max(1, (int)(Bounds.Height * scale));
        gl.Viewport(0, 0, w, h);
        gl.UseProgram(_prog);
        if (_uTime >= 0) gl.Uniform1f(_uTime, (float)_clock.Elapsed.TotalSeconds);
        if (_uRes >= 0) _uniform2f?.Invoke(_uRes, w, h);
        gl.BindBuffer(GL_ARRAY_BUFFER, _vbo);
        gl.EnableVertexAttribArray(0);
        gl.VertexAttribPointer(0, 2, GL_FLOAT, 0, 2 * sizeof(float), IntPtr.Zero);
        gl.DrawArrays(GL_TRIANGLES, 0, new IntPtr(6));
        // 动态壁纸要一直动:不主动约下一帧,画完这张就停在那儿了
        Dispatcher.UIThread.Post(RequestNextFrameRendering, DispatcherPriority.Background);
    }

    protected override void OnOpenGlDeinit(GlInterface gl)
    {
        if (_prog != 0) gl.DeleteProgram(_prog);
        if (_vbo != 0) gl.DeleteBuffer(_vbo);
        _prog = _vbo = 0;
    }

    /// <summary>着色器坏了就整块不画,并说清原因 —— 默默黑一块谁也查不动。</summary>
    private void Fail(string why)
    {
        _dead = true;
        Log.W("壁纸", why);
        Dispatcher.UIThread.Post(() => Toast.Error("着色器壁纸没跑起来:" + why));
    }
}
