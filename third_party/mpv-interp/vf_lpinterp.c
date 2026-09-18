/*
 * LinPlayer 补帧滤镜:OpenCL 光流,把帧率补到源的 multi 倍。
 *
 * 算法来自 HopperRender(https://github.com/HopperLogger/mpv-frame-interpolator,GPL-3.0),
 * 滤镜外壳重写:按倍数而不是按屏幕刷新率补(和 PC 端 DRBA/RIFE 的档位口径一致),
 * 时间戳按前后两帧真实间隔插值(上游输出比时间戳晚一个源帧),删掉了桌面状态面板。
 *
 * 本文件以 GPL-3.0-or-later 发布,只能进 GPL 构建的 libmpv。
 */

#include <math.h>
#include <stdio.h>
#include <string.h>

#include "common/common.h"
#include "common/msg.h"
#include "filters/f_autoconvert.h"
#include "filters/filter.h"
#include "filters/filter_internal.h"
#include "filters/user_filters.h"
#include "options/m_option.h"
#include "osdep/timer.h"
#include "video/img_format.h"
#include "video/mp_image.h"
#include "video/mp_image_pool.h"

#include "lpinterp_ocl.h"

#define MAX_QUEUE 4

struct lpinterp_opts {
    int multi;
};

struct priv {
    struct lpinterp_opts *opts;
    struct mp_autoconvert *conv;
    struct mp_image_pool *pool;
    struct lpi_ctx *ocl;
    int ocl_w, ocl_h, ocl_stride;
    struct mp_image *prev;
    struct mp_image *queue[MAX_QUEUE];
    int qn, qi;
    struct mp_frame pending;  // 冲刷完队列之后要写出去的信号帧(EOF 等)
    bool broken;              // 这台设备跑不了,之后原样放行
    int slow_runs;            // 连续超预算的源帧数
    uint8_t *tmp;             // 输出图 stride 和源不一致时的中转
    size_t tmp_size;
};

static void clear_queue(struct priv *p)
{
    for (int i = p->qi; i < p->qn; i++)
        talloc_free(p->queue[i]);
    p->qn = p->qi = 0;
}

static void f_reset(struct mp_filter *f)
{
    struct priv *p = f->priv;
    clear_queue(p);
    mp_image_unrefp(&p->prev);
    mp_frame_unref(&p->pending);
}

static void f_destroy(struct mp_filter *f)
{
    struct priv *p = f->priv;
    f_reset(f);
    lpi_destroy(p->ocl);
    p->ocl = NULL;
}

// 跑不了就整条放行,并用 MP_ERR 说一次 —— 核心层按这条撤档并把原因交给用户。
static void give_up(struct mp_filter *f, const char *why)
{
    struct priv *p = f->priv;
    if (!p->broken)
        MP_ERR(f, "lpinterp: %s\n", why);
    p->broken = true;
    lpi_destroy(p->ocl);
    p->ocl = NULL;
}

static bool ensure_ocl(struct mp_filter *f, struct mp_image *img)
{
    struct priv *p = f->priv;
    if (p->ocl && p->ocl_w == img->w && p->ocl_h == img->h && p->ocl_stride == img->stride[0])
        return true;
    lpi_destroy(p->ocl);
    mp_image_unrefp(&p->prev);  // 尺寸变了,上一帧不能再拿来算光流
    char err[640];
    p->ocl = lpi_create(img->stride[0], img->w, img->h, err, sizeof(err));
    if (!p->ocl) {
        give_up(f, err);
        return false;
    }
    p->ocl_w = img->w;
    p->ocl_h = img->h;
    p->ocl_stride = img->stride[0];
    MP_INFO(f, "lpinterp: %s,%dx%d,补到 %d 倍\n", lpi_device_name(p->ocl), img->w, img->h, p->opts->multi);
    return true;
}

// 源帧 stride 布局写进 out;out 的 stride 不同时经中转逐行拷。
static bool warp_into(struct priv *p, float t, struct mp_image *out)
{
    size_t ysz = (size_t)p->ocl_stride * p->ocl_h;
    if (out->stride[0] == p->ocl_stride && out->stride[1] == p->ocl_stride)
        return lpi_warp(p->ocl, t, out->planes[0], out->planes[1]) == 0;
    if (p->tmp_size < ysz * 3 / 2) {
        p->tmp = talloc_realloc_size(p, p->tmp, ysz * 3 / 2);
        p->tmp_size = ysz * 3 / 2;
    }
    if (lpi_warp(p->ocl, t, p->tmp, p->tmp + ysz))
        return false;
    for (int y = 0; y < p->ocl_h; y++)
        memcpy(out->planes[0] + y * out->stride[0], p->tmp + y * p->ocl_stride, p->ocl_w);
    for (int y = 0; y < p->ocl_h / 2; y++)
        memcpy(out->planes[1] + y * out->stride[1], p->tmp + ysz + y * p->ocl_stride, p->ocl_w);
    return true;
}

// 按上一个源帧的耗时调搜索半径;最小半径还连续超预算就放弃。
static void adapt(struct mp_filter *f, double spent, double budget)
{
    struct priv *p = f->priv;
    const int r = lpi_radius(p->ocl), w = lpi_min_win(p->ocl);
    if (spent > budget * 0.8) {
        // 降级顺序按实测的代价/质量曲线定:**先把窗口调粗,后砍搜索半径**。
        // R=8/W=16 只要 7.1ms,质量却全面压过 R=5/W=2 的 11.2ms(Intel UHD 1080p)——
        // 反过来先砍半径就是拿质量换了个更贵的档。
        if (w < LPI_MIN_WIN_WORST) {
            lpi_set_min_win(p->ocl, w * 2);
            p->slow_runs = 0;
        } else if (r > LPI_RADIUS_MIN) {
            lpi_set_radius(p->ocl, r - 1);
            p->slow_runs = 0;
        } else if (++p->slow_runs >= 24) {
            char why[160];
            snprintf(why, sizeof(why), "显卡算力不够(每个源帧要 %.0f ms,预算 %.0f ms)", spent * 1e3, budget * 1e3);
            give_up(f, why);
        }
    } else {
        p->slow_runs = 0;
        if (spent < budget * 0.5) {
            if (r < LPI_RADIUS_MAX)
                lpi_set_radius(p->ocl, r + 1);
            else if (w > LPI_MIN_WIN_BEST)
                lpi_set_min_win(p->ocl, w / 2);
        }
    }
}

// 收到新源帧 cur:把 prev 和 prev→cur 之间的中间帧排进队列,cur 成为新的 prev。
static void interpolate(struct mp_filter *f, struct mp_image *cur)
{
    struct priv *p = f->priv;
    int multi = p->opts->multi;
    struct mp_image *prev = p->prev;
    p->prev = cur;
    if (!prev)
        return;

    double fps = cur->nominal_fps > 0 ? cur->nominal_fps : 24000.0 / 1001;
    double dt = cur->pts - prev->pts;
    prev->nominal_fps = fps * multi;
    p->queue[p->qn++] = prev;
    // 跳变(seek 后第一对、可变帧率里的长间隔)不补:光流会把两个不相干的画面硬拉在一起
    if (!(dt > 0 && dt < 2.5 / fps) || p->broken)
        return;

    // 一次 CPU 扫描(9216 个取样点,微秒级)同时回答两个问题,两个都能省掉整个光流。
    struct lpi_pair st;
    lpi_pair_stats(prev->planes[0], prev->stride[0], cur->planes[0], cur->stride[0],
                   prev->w, prev->h, &st);

    // 切镜:上面那条 dt 判据只挡得住 seek 和可变帧率的长间隔,挡不住正常帧距的硬切。
    // 硬切的两帧内容毫不相干,光流会把它们拉成一团烂泥。
    if (lpi_is_scene_cut(&st))
        return;

    // 按住的同一张原画(动画的一拍二 / 一拍三)。中间帧就是 prev 本身 ——
    // 跑光流只会算出同一张图。这里只加引用计数,不拷像素。
    if (lpi_is_duplicate(&st)) {
        for (int k = 1; k < multi; k++) {
            struct mp_image *out = mp_image_new_ref(prev);
            if (!out)
                break;
            out->pts = prev->pts + dt * k / multi;
            out->nominal_fps = fps * multi;
            p->queue[p->qn++] = out;
        }
        return;
    }

    double start = mp_time_sec();
    if (lpi_flow(p->ocl)) {
        give_up(f, "OpenCL 光流计算失败");
        return;
    }
    for (int k = 1; k < multi; k++) {
        struct mp_image *out = mp_image_pool_get(p->pool, IMGFMT_NV12, prev->w, prev->h);
        if (!out)
            break;
        mp_image_copy_attributes(out, prev);
        out->pts = prev->pts + dt * k / multi;
        if (!warp_into(p, (float)k / multi, out)) {
            talloc_free(out);
            give_up(f, "OpenCL 生成中间帧失败");
            return;
        }
        p->queue[p->qn++] = out;
    }
    adapt(f, mp_time_sec() - start, dt);
}

static void f_process(struct mp_filter *f)
{
    struct priv *p = f->priv;

    if (mp_pin_can_transfer_data(p->conv->f->pins[0], f->ppins[0]))
        mp_pin_in_write(p->conv->f->pins[0], mp_pin_out_read(f->ppins[0]));

    // 先把排好的帧一张张交出去
    if (p->qi < p->qn || p->pending.type) {
        if (!mp_pin_in_needs_data(f->ppins[1]))
            return;
        if (p->qi < p->qn) {
            mp_pin_in_write(f->ppins[1], MAKE_FRAME(MP_FRAME_VIDEO, p->queue[p->qi++]));
            if (p->qi == p->qn)
                p->qn = p->qi = 0;
        } else {
            mp_pin_in_write(f->ppins[1], p->pending);
            p->pending = MP_NO_FRAME;
        }
        mp_filter_internal_mark_progress(f);
        return;
    }

    if (!mp_pin_can_transfer_data(f->ppins[1], p->conv->f->pins[1]))
        return;
    struct mp_frame frame = mp_pin_out_read(p->conv->f->pins[1]);

    if (mp_frame_is_signaling(frame)) {
        // EOF:手上压着的最后一个源帧先交出去
        if (p->prev)
            p->queue[p->qn++] = p->prev;
        p->prev = NULL;
        p->pending = frame;
        mp_filter_internal_mark_progress(f);
        return;
    }
    struct mp_image *img = frame.type == MP_FRAME_VIDEO ? frame.data : NULL;
    bool fits = img && !p->broken && img->imgfmt == IMGFMT_NV12 && !(img->h & 1) &&
                img->stride[1] == img->stride[0] && img->w <= 4096 && img->h <= 2176;
    if (fits && (!ensure_ocl(f, img) || lpi_push(p->ocl, img->planes[0], img->planes[1]))) {
        give_up(f, "OpenCL 上传画面失败");
        fits = false;
    }
    if (!fits) {
        // 原样放行。手上压着上一个源帧的话得先把它交出去,不然这一帧会插到它前面
        if (p->prev) {
            p->queue[p->qn++] = p->prev;
            p->prev = NULL;
            mp_pin_out_unread(p->conv->f->pins[1], frame);
            mp_filter_internal_mark_progress(f);
            return;
        }
        mp_pin_in_write(f->ppins[1], frame);
        return;
    }
    interpolate(f, img);
    mp_filter_internal_mark_progress(f);
}

static const struct mp_filter_info filter = {
    .name = "lpinterp",
    .process = f_process,
    .reset = f_reset,
    .destroy = f_destroy,
    .priv_size = sizeof(struct priv),
};

static struct mp_filter *f_create(struct mp_filter *parent, void *options)
{
    struct mp_filter *f = mp_filter_create(parent, &filter);
    if (!f) {
        talloc_free(options);
        return NULL;
    }
    mp_filter_add_pin(f, MP_PIN_IN, "in");
    mp_filter_add_pin(f, MP_PIN_OUT, "out");

    struct priv *p = f->priv;
    p->opts = talloc_steal(p, options);
    p->pool = mp_image_pool_new(p);
    p->conv = mp_autoconvert_create(f);
    MP_HANDLE_OOM(p->conv);
    // 只收 8 位 NV12:10 位片源会被降成 8 位再补(kernel 按字节算)
    mp_autoconvert_add_imgfmt(p->conv, IMGFMT_NV12, 0);
    return f;
}

#define OPT_BASE_STRUCT struct lpinterp_opts
static const m_option_t f_opts_list[] = {
    {"multi", OPT_INT(multi), M_RANGE(2, MAX_QUEUE)},
    {0}
};

static const struct lpinterp_opts f_opts_def = {
    .multi = 2,
};

const struct mp_user_filter_entry vf_lpinterp = {
    .desc = {
        .description = "LinPlayer OpenCL optical-flow frame interpolation",
        .name = "lpinterp",
        .priv_size = sizeof(OPT_BASE_STRUCT),
        .priv_defaults = &f_opts_def,
        .options = f_opts_list,
    },
    .create = f_create,
};
