// 补帧 OpenCL 计算核的主机侧。算法与参数来自 HopperRender(GPL-3.0),见 lpinterp.cl 开头。
#include "lpinterp_ocl.h"

#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "lpinterp_cl.h"
#include "lpinterp_kernels.h"  // 由 gen-kernels.py 从 lpinterp.cl 生成:LPI_KERNEL_SRC

#if defined(_WIN32)
#include <windows.h>
static void *lpi_dlopen(const char *n) { return LoadLibraryA(n); }
static void *lpi_dlsym(void *h, const char *s) { return (void *)GetProcAddress((HMODULE)h, s); }
static const char *const lpi_cl_names[] = {"OpenCL.dll"};
#else
#include <dlfcn.h>
static void *lpi_dlopen(const char *n) { return dlopen(n, RTLD_NOW | RTLD_LOCAL); }
static void *lpi_dlsym(void *h, const char *s) { return dlsym(h, s); }
// 安卓各家放法不一:高通 / 联发科 / 三星公开 libOpenCL.so;Pixel 是 libOpenCL-pixel.so;
// 部分 Mali 机器只在 libGLES_mali.so 里带 CL 符号。按名字试,命名空间不放行的会 dlopen 失败。
static const char *const lpi_cl_names[] = {
    "libOpenCL.so", "libOpenCL.so.1", "libOpenCL-pixel.so", "libGLES_mali.so", "libmali.so",
};
#endif

// 光流在低分辨率上算:高度不超过这个数(1080p → 270p)。
#define LPI_MAX_CALC_HEIGHT 270

struct lpi_ctx {
    struct lpi_cl cl;
    cl_context ctx;
    cl_command_queue q;
    cl_program prog;
    cl_kernel k_shrink, k_search, k_adjust, k_blur, k_occ, k_occ_post, k_warp;
    cl_mem frame[2];  // frame[cur] 是 next,另一个是 prev
    cl_mem small[2];  // frame[] 的降采样版(Y,U,V,0),搜索只读它
    int cur;
    int pushed;
    cl_mem out, offs, blurred, lowest, occ, occ2;
    int stride, width, height;
    int low_w, low_h, res_shift, radius;
    int shift_base;  // 由画面高度算出的最细那档,res_shift 不能比它更小
    int small_shift; // small[] 现在是按哪个 shift 降的;和 res_shift 不一致就要重降
    int use_occ;     // 遮挡掩膜。LPI_OCC=0 关掉做对照(bench 用)
    int min_win;     // 光流最细算到多大的窗口。和 radius 一起构成自适应的两级旋钮
    char device[128];
};

static int load_cl(struct lpi_cl *cl) {
    void *h = NULL;
    for (size_t i = 0; i < sizeof(lpi_cl_names) / sizeof(lpi_cl_names[0]) && !h; i++)
        h = lpi_dlopen(lpi_cl_names[i]);
    if (!h)
        return -1;
#define SYM(name) if (!(cl->name = lpi_dlsym(h, "cl" #name))) return -2
    SYM(GetPlatformIDs); SYM(GetDeviceIDs); SYM(GetDeviceInfo); SYM(CreateContext);
    SYM(CreateCommandQueue); SYM(CreateBuffer); SYM(CreateProgramWithSource); SYM(BuildProgram);
    SYM(GetProgramBuildInfo); SYM(CreateKernel); SYM(SetKernelArg); SYM(EnqueueNDRangeKernel);
    SYM(EnqueueWriteBuffer); SYM(EnqueueReadBuffer); SYM(EnqueueFillBuffer); SYM(Finish);
    SYM(ReleaseMemObject); SYM(ReleaseKernel); SYM(ReleaseProgram); SYM(ReleaseCommandQueue);
    SYM(ReleaseContext);
#undef SYM
    return 0;
}

// 选显存最大的 GPU。PC 上核显和独显并存时要的是独显;手机上只有一块。
static cl_device_id pick_gpu(struct lpi_cl *cl, char *name, size_t namelen) {
    cl_platform_id plats[8];
    cl_uint np = 0;
    if (cl->GetPlatformIDs(8, plats, &np) != CL_SUCCESS)
        return NULL;
    cl_device_id best = NULL;
    cl_ulong best_mem = 0;
    for (cl_uint i = 0; i < np && i < 8; i++) {
        cl_device_id devs[8];
        cl_uint nd = 0;
        if (cl->GetDeviceIDs(plats[i], CL_DEVICE_TYPE_GPU, 8, devs, &nd) != CL_SUCCESS)
            continue;
        for (cl_uint j = 0; j < nd && j < 8; j++) {
            cl_ulong mem = 0;
            cl->GetDeviceInfo(devs[j], CL_DEVICE_GLOBAL_MEM_SIZE, sizeof(mem), &mem, NULL);
            char nm[128] = {0};
            cl->GetDeviceInfo(devs[j], CL_DEVICE_NAME, sizeof(nm) - 1, nm, NULL);
            // LPI_DEVICE=名字片段:基准里强制挑某块卡(比如拿核显估手机)。播放器里不设
            const char *want = getenv("LPI_DEVICE");
            if (want && *want && !strstr(nm, want))
                continue;
            if (!best || mem > best_mem) {
                best = devs[j];
                best_mem = mem;
                cl->GetDeviceInfo(devs[j], CL_DEVICE_NAME, namelen, name, NULL);
                name[namelen - 1] = 0;
            }
        }
    }
    return best;
}

void lpi_destroy(struct lpi_ctx *c) {
    if (!c)
        return;
    struct lpi_cl *cl = &c->cl;
    if (c->q)
        cl->Finish(c->q);
    cl_mem mems[] = {c->frame[0], c->frame[1], c->small[0], c->small[1], c->out,
                     c->offs, c->blurred, c->lowest, c->occ, c->occ2};
    for (size_t i = 0; i < sizeof(mems) / sizeof(mems[0]); i++)
        if (mems[i])
            cl->ReleaseMemObject(mems[i]);
    cl_kernel ks[] = {c->k_shrink, c->k_search, c->k_adjust, c->k_blur, c->k_occ, c->k_occ_post, c->k_warp};
    for (size_t i = 0; i < sizeof(ks) / sizeof(ks[0]); i++)
        if (ks[i])
            cl->ReleaseKernel(ks[i]);
    if (c->prog)
        cl->ReleaseProgram(c->prog);
    if (c->q)
        cl->ReleaseCommandQueue(c->q);
    if (c->ctx)
        cl->ReleaseContext(c->ctx);
    free(c);
}

// 阈值全部按真实番剧实测标定(2396 个相邻帧对 + 240 帧全分辨率复核,
// 数字和标定过程见 docs/lessons/player-mpv.md「切镜检测与按住帧跳过」):
//   真实快速运动:整体差 24~35,超标块 36%~59%
//   真正的切镜:  整体差 76~165,超标块 84%~100%
// ★ 两个条件都要满足才算切镜 —— MVTools 默认的 thSCD2=51% 在动画上会把快速运动误杀。
#define LPI_SCD_BLOCK_TH 25
#define LPI_SCD_HI_PCT   70
#define LPI_SCD_MEAN_TH  50
// ★ 判「按住」必须同时看整体差**和**最大块差:只看整体差的话,
//   静止背景上一个小物体在动会被当成没动。实测放宽 max_block 到 6 只多跳过 0.8%,
//   却把最差那一对从 47.1dB 拉到 34.2dB —— 不值。
#define LPI_DUP_MEAN_TH  1
#define LPI_DUP_BLOCK_TH 4

#define LPI_SCD_BX 16
#define LPI_SCD_BY 9

void lpi_pair_stats(const unsigned char *a, int stride_a, const unsigned char *b, int stride_b,
                    int w, int h, struct lpi_pair *out) {
    const int bw = w / LPI_SCD_BX, bh = h / LPI_SCD_BY;
    out->mean = out->max_block = out->hi_pct = 0;
    if (bw < 8 || bh < 8) {
        out->mean = LPI_DUP_MEAN_TH + 1;  // 画面太小分不出块:当作有运动,照常补
        return;
    }
    long total = 0;
    int hi = 0;
    for (int by = 0; by < LPI_SCD_BY; by++) {
        for (int bx = 0; bx < LPI_SCD_BX; bx++) {
            int s = 0;
            for (int j = 0; j < 8; j++) {
                const int y = by * bh + j * bh / 8;
                const unsigned char *pa = a + (ptrdiff_t)y * stride_a;
                const unsigned char *pb = b + (ptrdiff_t)y * stride_b;
                for (int i = 0; i < 8; i++) {
                    const int x = bx * bw + i * bw / 8;
                    const int d = pa[x] - pb[x];
                    s += d < 0 ? -d : d;
                }
            }
            s /= 64;
            total += s;
            if (s > out->max_block)
                out->max_block = s;
            if (s > LPI_SCD_BLOCK_TH)
                hi++;
        }
    }
    out->mean = (int)(total / (LPI_SCD_BX * LPI_SCD_BY));
    out->hi_pct = hi * 100 / (LPI_SCD_BX * LPI_SCD_BY);
}

bool lpi_is_scene_cut(const struct lpi_pair *st) {
    return st->hi_pct >= LPI_SCD_HI_PCT && st->mean >= LPI_SCD_MEAN_TH;
}

bool lpi_is_duplicate(const struct lpi_pair *st) {
    return st->mean <= LPI_DUP_MEAN_TH && st->max_block <= LPI_DUP_BLOCK_TH;
}

const char *lpi_device_name(struct lpi_ctx *c) { return c->device; }
void lpi_set_radius(struct lpi_ctx *c, int r) {
    c->radius = r < LPI_RADIUS_MIN ? LPI_RADIUS_MIN : r > LPI_RADIUS_MAX ? LPI_RADIUS_MAX : r;
}
int lpi_radius(struct lpi_ctx *c) { return c->radius; }
void lpi_set_min_win(struct lpi_ctx *c, int w) {
    c->min_win = w < LPI_MIN_WIN_BEST ? LPI_MIN_WIN_BEST : w > LPI_MIN_WIN_WORST ? LPI_MIN_WIN_WORST : w;
}
int lpi_min_win(struct lpi_ctx *c) { return c->min_win; }
void lpi_set_res_shift(struct lpi_ctx *c, int s) {
    const int hi = c->shift_base + LPI_SHIFT_EXTRA_MAX;
    s = s < c->shift_base ? c->shift_base : s > hi ? hi : s;
    if (s == c->res_shift)
        return;
    c->res_shift = s;
    c->low_w = (c->stride + (1 << s) - 1) >> s;
    c->low_h = (c->height + (1 << s) - 1) >> s;
}
int lpi_res_shift(struct lpi_ctx *c) { return c->res_shift; }
int lpi_shift_base(struct lpi_ctx *c) { return c->shift_base; }
int lpi_finish(struct lpi_ctx *c) { return c->cl.Finish(c->q); }

#define FAIL(...) do { snprintf(err, errlen, __VA_ARGS__); lpi_destroy(c); return NULL; } while (0)

struct lpi_ctx *lpi_create(int stride, int width, int height, char *err, size_t errlen) {
    struct lpi_ctx *c = calloc(1, sizeof(*c));
    if (!c) {
        snprintf(err, errlen, "内存不足");
        return NULL;
    }
    struct lpi_cl *cl = &c->cl;
    int r = load_cl(cl);
    if (r == -1)
        FAIL("这台设备没有可用的 OpenCL(找不到 libOpenCL)");
    if (r == -2)
        FAIL("这台设备的 OpenCL 库缺少必要的函数");
    cl_device_id dev = pick_gpu(cl, c->device, sizeof(c->device));
    if (!dev)
        FAIL("这台设备的 OpenCL 里没有 GPU");

    c->stride = stride;
    c->width = width;
    c->height = height;
    c->radius = LPI_RADIUS_MIN;
    { const char *o = getenv("LPI_OCC"); c->use_occ = (o && o[0] == '0') ? 0 : 1; }
    { const char *v = getenv("LPI_RADIUS"); if (v) c->radius = atoi(v); }
    { const char *v = getenv("LPI_MINWIN"); c->min_win = v ? atoi(v) : LPI_MIN_WIN_DEF; }
    while ((height >> c->res_shift) > LPI_MAX_CALC_HEIGHT)
        c->res_shift++;
    c->shift_base = c->res_shift;
    c->small_shift = -1;
    c->low_w = (stride + (1 << c->res_shift) - 1) >> c->res_shift;
    c->low_h = (height + (1 << c->res_shift) - 1) >> c->res_shift;
    { const char *v = getenv("LPI_SHIFT"); if (v) lpi_set_res_shift(c, c->res_shift + atoi(v)); }

    cl_int e = 0;
    c->ctx = cl->CreateContext(NULL, 1, &dev, NULL, NULL, &e);
    if (e != CL_SUCCESS)
        FAIL("OpenCL 建上下文失败(%d)", e);
    // clCreateCommandQueue 在 2.0 起标为过时,但 1.2 的实现只认它;各家 3.0 实现都还留着
    c->q = cl->CreateCommandQueue(c->ctx, dev, 0, &e);
    if (e != CL_SUCCESS)
        FAIL("OpenCL 建命令队列失败(%d)", e);

    const char *src = LPI_KERNEL_SRC;
    c->prog = cl->CreateProgramWithSource(c->ctx, 1, &src, NULL, &e);
    if (e != CL_SUCCESS)
        FAIL("OpenCL 建程序失败(%d)", e);
    if ((e = cl->BuildProgram(c->prog, 1, &dev, "", NULL, NULL)) != CL_SUCCESS) {
        char log[512] = {0};
        cl->GetProgramBuildInfo(c->prog, dev, CL_PROGRAM_BUILD_LOG, sizeof(log) - 1, log, NULL);
        FAIL("OpenCL kernel 编译失败(%d):%s", e, log);
    }
#define KERNEL(field, name) \
    c->field = cl->CreateKernel(c->prog, name, &e); \
    if (e != CL_SUCCESS) FAIL("OpenCL kernel %s 创建失败(%d)", name, e)
    KERNEL(k_shrink, "lpi_shrink");
    KERNEL(k_search, "lpi_search");
    KERNEL(k_adjust, "lpi_adjust");
    KERNEL(k_blur, "lpi_blur");
    KERNEL(k_occ, "lpi_occ");
    KERNEL(k_occ_post, "lpi_occ_post");
    KERNEL(k_warp, "lpi_warp");
#undef KERNEL

    size_t frame_bytes = (size_t)stride * height * 3 / 2;
    // 按最细那档分配:分辨率档位调粗只会用到更少的格子,不必重新分配
    size_t low_px = (size_t)((stride + (1 << c->shift_base) - 1) >> c->shift_base) *
                    ((height + (1 << c->shift_base) - 1) >> c->shift_base);
#define BUF(field, flags, size) \
    c->field = cl->CreateBuffer(c->ctx, flags, size, NULL, &e); \
    if (e != CL_SUCCESS) FAIL("OpenCL 分配显存失败(%d)", e)
    BUF(frame[0], CL_MEM_READ_ONLY, frame_bytes);
    BUF(frame[1], CL_MEM_READ_ONLY, frame_bytes);
    BUF(small[0], CL_MEM_READ_WRITE, low_px * 4);
    BUF(small[1], CL_MEM_READ_WRITE, low_px * 4);
    BUF(out, CL_MEM_WRITE_ONLY, frame_bytes);
    BUF(offs, CL_MEM_READ_WRITE, 2 * low_px * sizeof(short));
    BUF(blurred, CL_MEM_READ_WRITE, 2 * low_px * sizeof(short));
    BUF(lowest, CL_MEM_READ_WRITE, low_px);
    BUF(occ, CL_MEM_READ_WRITE, low_px);
    BUF(occ2, CL_MEM_READ_WRITE, low_px);
#undef BUF
    return c;
}

#define ARG(k, i, v) e |= cl->SetKernelArg(k, i, sizeof(v), &(v))

int lpi_push(struct lpi_ctx *c, const uint8_t *y, const uint8_t *uv) {
    struct lpi_cl *cl = &c->cl;
    c->cur ^= 1;
    size_t ysz = (size_t)c->stride * c->height;
    // Y 不阻塞:队列有序,等 UV 那次回来就说明两次都完成了(核显上量不出差别,是给移动端省的)
    cl_int e = cl->EnqueueWriteBuffer(c->q, c->frame[c->cur], CL_FALSE, 0, ysz, y, 0, NULL, NULL);
    e |= cl->EnqueueWriteBuffer(c->q, c->frame[c->cur], CL_TRUE, ysz, ysz / 2, uv, 0, NULL, NULL);
    // 降采样一次,给搜索用。放在这里而不是 lpi_flow 里:按住的原画会跳过整个 lpi_flow,
    // 但每帧都要 push —— 放 flow 里就得对前后两帧各来一次,反而多。
    // 分辨率档位刚变过的话,前一帧的降采样版是按老档降的,对不上,一起重降
    const int both = c->small_shift != c->res_shift;
    c->small_shift = c->res_shift;
    cl_kernel k = c->k_shrink;
    size_t g[2] = {c->low_w, c->low_h};
    for (int i = both ? 0 : 1; i < 2; i++) {
        const int f = i ? c->cur : (c->cur ^ 1);
        ARG(k, 0, c->frame[f]); ARG(k, 1, c->small[f]); ARG(k, 2, c->height);
        ARG(k, 3, c->stride); ARG(k, 4, c->low_h); ARG(k, 5, c->low_w); ARG(k, 6, c->res_shift);
        e |= cl->EnqueueNDRangeKernel(c->q, k, 2, NULL, g, NULL, 0, NULL, NULL);
    }
    c->pushed++;
    return e;
}

int lpi_flow(struct lpi_ctx *c) {
    struct lpi_cl *cl = &c->cl;
    cl_int e = 0;
    const int zero = 0;
    cl_mem prev = c->frame[c->cur ^ 1], next = c->frame[c->cur];
    cl_mem sprev = c->small[c->cur ^ 1], snext = c->small[c->cur];
    size_t low_px = (size_t)c->low_w * c->low_h;
    e |= cl->EnqueueFillBuffer(c->q, c->offs, &zero, sizeof(short), 0, 2 * low_px * sizeof(short), 0, NULL, NULL);

    // 窗口从「不超过画面的最大 2 的幂」的一半开始,逐级减半;最细到 2×2,单像素那级交给模糊
    int max_dim = c->low_w > c->low_h ? c->low_w : c->low_h;
    int window = 1;
    while (window * 2 <= max_dim)
        window *= 2;
    size_t g2[2] = {c->low_w, c->low_h};
    // 画面太小时起始窗口可能已经比 min_win 还小 —— 那也要跑一轮,否则流场恒为零
    const int stop = window < c->min_win ? window : c->min_win;
    for (; window >= stop; window /= 2) {
        size_t gw[2] = {(c->low_w + window - 1) / window, (c->low_h + window - 1) / window};
        for (int step = 0; step < 2; step++) {
            cl_kernel k = c->k_search;
            ARG(k, 0, c->lowest); ARG(k, 1, sprev); ARG(k, 2, snext); ARG(k, 3, c->offs);
            ARG(k, 4, c->low_h); ARG(k, 5, c->low_w); ARG(k, 6, window); ARG(k, 7, c->radius);
            ARG(k, 8, c->res_shift); ARG(k, 9, step);
            e |= cl->EnqueueNDRangeKernel(c->q, k, 2, NULL, gw, NULL, 0, NULL, NULL);

            k = c->k_adjust;
            ARG(k, 0, c->offs); ARG(k, 1, c->lowest); ARG(k, 2, window); ARG(k, 3, c->radius);
            ARG(k, 4, c->low_h); ARG(k, 5, c->low_w); ARG(k, 6, step); ARG(k, 7, c->res_shift);
            e |= cl->EnqueueNDRangeKernel(c->q, k, 2, NULL, g2, NULL, 0, NULL, NULL);
        }
    }
    size_t gb[3] = {c->low_w, c->low_h, 2};
    cl_kernel k = c->k_blur;
    ARG(k, 0, c->offs); ARG(k, 1, c->blurred); ARG(k, 2, c->low_h); ARG(k, 3, c->low_w);
    e |= cl->EnqueueNDRangeKernel(c->q, k, 3, NULL, gb, NULL, 0, NULL, NULL);

    // 遮挡掩膜。两个 kernel 都只跑低分辨率网格(1080p 时 480×270),相对光流那几轮可以忽略。
    if (c->use_occ) {
        k = c->k_occ;
        ARG(k, 0, c->occ); ARG(k, 1, prev); ARG(k, 2, next); ARG(k, 3, c->blurred);
        ARG(k, 4, c->height); ARG(k, 5, c->stride); ARG(k, 6, c->low_h); ARG(k, 7, c->low_w);
        ARG(k, 8, c->res_shift);
        e |= cl->EnqueueNDRangeKernel(c->q, k, 2, NULL, g2, NULL, 0, NULL, NULL);
        k = c->k_occ_post;
        ARG(k, 0, c->occ); ARG(k, 1, c->occ2); ARG(k, 2, c->low_h); ARG(k, 3, c->low_w);
        e |= cl->EnqueueNDRangeKernel(c->q, k, 2, NULL, g2, NULL, 0, NULL, NULL);
    } else {
        const unsigned char z = 0;
        e |= cl->EnqueueFillBuffer(c->q, c->occ2, &z, 1, 0, (size_t)c->low_w * c->low_h, 0, NULL, NULL);
    }
    return e;
}

void lpi_flow_stats(struct lpi_ctx *c, struct lpi_stats *st) {
    const size_t low_px = (size_t)c->low_w * c->low_h;
    short *v = malloc(2 * low_px * sizeof(short));
    unsigned char *m = malloc(low_px);
    if (!v || !m) {
        free(v);
        free(m);
        return;
    }
    c->cl.EnqueueReadBuffer(c->q, c->blurred, CL_TRUE, 0, 2 * low_px * sizeof(short), v, 0, NULL, NULL);
    double sum = 0;
    int mx = 0;
    for (size_t i = 0; i < 2 * low_px; i++) {
        const int a = v[i] < 0 ? -v[i] : v[i];
        sum += a;
        if (a > mx)
            mx = a;
    }
    c->cl.EnqueueReadBuffer(c->q, c->occ2, CL_TRUE, 0, low_px, m, 0, NULL, NULL);
    long hi = 0;
    for (size_t i = 0; i < low_px; i++)
        if (m[i] > 128)
            hi++;
    st->cells = (int)low_px;
    st->mean_abs = sum / (2 * low_px);
    st->max_abs = mx;
    st->occ_pct = hi * 100.0 / low_px;
    free(v);
    free(m);
}

int lpi_warp(struct lpi_ctx *c, float t, uint8_t *y, uint8_t *uv) {
    struct lpi_cl *cl = &c->cl;
    cl_int e = 0;
    cl_mem prev = c->frame[c->cur ^ 1], next = c->frame[c->cur];
    cl_kernel k = c->k_warp;
    ARG(k, 0, prev); ARG(k, 1, next); ARG(k, 2, c->blurred); ARG(k, 3, c->occ2);
    ARG(k, 4, c->out); ARG(k, 5, t); ARG(k, 6, c->low_h); ARG(k, 7, c->low_w);
    ARG(k, 8, c->height); ARG(k, 9, c->stride); ARG(k, 10, c->width); ARG(k, 11, c->res_shift);
    for (int cz = 0; cz < 2; cz++) {
        e |= cl->SetKernelArg(k, 12, sizeof(int), &cz);
        size_t g[2] = {c->width, c->height >> cz};
        e |= cl->EnqueueNDRangeKernel(c->q, k, 2, NULL, g, NULL, 0, NULL, NULL);
    }
    size_t ysz = (size_t)c->stride * c->height;
    e |= cl->EnqueueReadBuffer(c->q, c->out, CL_TRUE, 0, ysz, y, 0, NULL, NULL);
    e |= cl->EnqueueReadBuffer(c->q, c->out, CL_TRUE, ysz, ysz / 2, uv, 0, NULL, NULL);
    return e;
}
