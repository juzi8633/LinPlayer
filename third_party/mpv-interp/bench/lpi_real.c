// 真实片源上的补帧质量对照。合成纹理测不出果冻效应 —— 它没有遮挡、没有形变、没有细线条。
//
//   ffmpeg -ss 95 -i 片子.mkv -frames:v 3 -f rawvideo -pix_fmt nv12 op3.nv12
//   lpi_real op3.nv12 1920 1080
//
// 取第 0、2 帧算中间帧,和**真的第 1 帧**比:这才是补帧要还原的东西。
// 输出 out-interp/out-blend/out-truth/out-err.ppm,err 是补帧结果与真值的差(放大 4 倍)——
// 果冻和撕裂在 err 图上是成片的亮斑,直接混合的重影则是均匀的轮廓线。
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <time.h>
#include "../lpinterp_ocl.h"

#if defined(_WIN32)
#include <windows.h>
static double now(void){LARGE_INTEGER f,c;QueryPerformanceFrequency(&f);QueryPerformanceCounter(&c);return (double)c.QuadPart/f.QuadPart;}
#else
static double now(void){struct timespec t;clock_gettime(CLOCK_MONOTONIC,&t);return t.tv_sec+t.tv_nsec/1e9;}
#endif

static double psnr(const unsigned char *a, const unsigned char *b, int w, int h) {
    double se = 0;
    long n = 0;
    for (int j = h / 10; j < h * 9 / 10; j++)
        for (int i = w / 10; i < w * 9 / 10; i++, n++) {
            double d = (double)a[j * w + i] - b[j * w + i];
            se += d * d;
        }
    return se == 0 ? 99 : 10 * log10(255.0 * 255.0 / (se / n));
}

static int clamp8(double v) { return v < 0 ? 0 : v > 255 ? 255 : (int)v; }

// NV12 → PPM。BT.709 limited range,够看就行。
static void write_ppm(const char *path, const unsigned char *y, const unsigned char *uv, int w, int h) {
    FILE *f = fopen(path, "wb");
    if (!f) return;
    fprintf(f, "P6\n%d %d\n255\n", w, h);
    unsigned char *row = malloc((size_t)w * 3);
    for (int j = 0; j < h; j++) {
        for (int i = 0; i < w; i++) {
            double Y = (y[j * w + i] - 16) * 1.164;
            double U = uv[(j >> 1) * w + (i & ~1)] - 128.0;
            double V = uv[(j >> 1) * w + (i & ~1) + 1] - 128.0;
            row[i * 3 + 0] = clamp8(Y + 1.793 * V);
            row[i * 3 + 1] = clamp8(Y - 0.213 * U - 0.533 * V);
            row[i * 3 + 2] = clamp8(Y + 2.112 * U);
        }
        fwrite(row, 1, (size_t)w * 3, f);
    }
    free(row);
    fclose(f);
}

// 误差图:|补出来的 - 真值| × 4,灰度。
static void write_err(const char *path, const unsigned char *a, const unsigned char *b, int w, int h) {
    FILE *f = fopen(path, "wb");
    if (!f) return;
    fprintf(f, "P5\n%d %d\n255\n", w, h);
    unsigned char *row = malloc(w);
    for (int j = 0; j < h; j++) {
        for (int i = 0; i < w; i++)
            row[i] = (unsigned char)clamp8(abs((int)a[j * w + i] - (int)b[j * w + i]) * 4);
        fwrite(row, 1, w, f);
    }
    free(row);
    fclose(f);
}

int main(int argc, char **argv) {
    if (argc < 4) {
        fprintf(stderr, "用法: lpi_real <nv12 文件(>=3 帧)> <宽> <高> [t=0.5]\n");
        return 2;
    }
    const int w = atoi(argv[2]), h = atoi(argv[3]);
    const float t = argc > 4 ? (float)atof(argv[4]) : 0.5f;
    const size_t ys = (size_t)w * h, fs = ys * 3 / 2;

    unsigned char *buf = malloc(fs * 3);
    FILE *f = fopen(argv[1], "rb");
    if (!f || fread(buf, 1, fs * 3, f) != fs * 3) {
        fprintf(stderr, "读不到三帧 %dx%d NV12\n", w, h);
        return 1;
    }
    fclose(f);
    unsigned char *f0 = buf, *f1 = buf + fs, *f2 = buf + fs * 2;

    char err[640];
    struct lpi_ctx *c = lpi_create(w, w, h, err, sizeof(err));
    if (!c) {
        fprintf(stderr, "%s\n", err);
        return 1;
    }
    printf("设备: %s  %dx%d  t=%.2f\n", lpi_device_name(c), w, h, t);

    if (lpi_push(c, f0, f0 + ys) || lpi_push(c, f2, f2 + ys)) {
        fprintf(stderr, "上传画面失败\n");
        return 1;
    }
    lpi_finish(c);
    // 跑 5 遍取均值:单次测量被驱动的提交延迟淹没
    const double t0 = now();
    for (int i = 0; i < 5; i++) {
        if (lpi_flow(c)) {
            fprintf(stderr, "光流失败\n");
            return 1;
        }
        lpi_finish(c);
    }
    fprintf(stderr, "  光流耗时 %.1f ms\n", (now() - t0) * 200);
    struct lpi_stats st;
    lpi_flow_stats(c, &st);
    printf("光流场 %d 格: 平均|v|=%.2f  最大|v|=%d  遮挡掩膜 %.1f%%\n",
           st.cells, st.mean_abs, st.max_abs, st.occ_pct);

    // 切档自检:光流分辨率是运行时可调的,调档时**前一帧**的降采样版是按老档降的,
    // 必须一起重降。漏了的话流场会悄悄错(不报错、不崩),所以在这儿钉一道。
    // 来回切一趟再回到原档,结果必须和切之前逐字节一致。
    // ☠ 必须照搬滤镜的真实时序:**切完档只推一帧**。一次推两帧会把两帧都重降,
    //   刚好把 bug 盖住 —— 第一版自检就是这么假绿的。
    {
        const int base = lpi_shift_base(c);
        struct lpi_stats a = st, b, cc;
        // 切到粗档,只推一帧(此时 prev = f2,它的降采样版还是按细档降的)
        lpi_set_res_shift(c, base + LPI_SHIFT_EXTRA_MAX);
        int bad = lpi_push(c, f0, f0 + ys) || lpi_flow(c);
        lpi_finish(c);
        lpi_flow_stats(c, &b);
        // 同一对帧、同一档,但两帧都是在这一档降的 —— 这是正确答案
        bad |= lpi_push(c, f2, f2 + ys) || lpi_push(c, f0, f0 + ys) || lpi_flow(c);
        lpi_finish(c);
        lpi_flow_stats(c, &cc);
        // 切回细档,顺带验证来回一趟不掉东西,也把状态还原给后面的 warp
        lpi_set_res_shift(c, base);
        bad |= lpi_push(c, f0, f0 + ys) || lpi_push(c, f2, f2 + ys) || lpi_flow(c);
        lpi_finish(c);
        lpi_flow_stats(c, &st);
        if (bad) {
            fprintf(stderr, "切档自检:重跑失败\n");
            return 1;
        }
#define SAME(x, y) ((x).max_abs == (y).max_abs && fabs((x).mean_abs - (y).mean_abs) < 1e-9 && \
                    fabs((x).occ_pct - (y).occ_pct) < 1e-9)
        const bool ok = SAME(b, cc), back = SAME(st, a);
        fprintf(stderr, "  切档自检 %s(切档后前一帧%s重降;回到 %d 档%s)\n",
                ok && back ? "通过" : "★失败★", ok ? "已" : "★没★", base, back ? "复原" : "★没复原★");
        if (!(ok && back))
            return 1;
    }
    unsigned char *oy = malloc(ys), *ouv = malloc(ys / 2);
    if (lpi_warp(c, t, oy, ouv)) {
        fprintf(stderr, "生成中间帧失败\n");
        return 1;
    }

    // 对照组:直接按 t 混合前后两帧(不做运动补偿)
    unsigned char *by = malloc(ys), *buv = malloc(ys / 2);
    for (size_t i = 0; i < ys; i++)
        by[i] = (unsigned char)(f0[i] * (1 - t) + f2[i] * t + 0.5f);
    for (size_t i = 0; i < ys / 2; i++)
        buv[i] = (unsigned char)(f0[ys + i] * (1 - t) + f2[ys + i] * t + 0.5f);

    printf("补帧 vs 真值   PSNR %.2f dB\n", psnr(oy, f1, w, h));
    printf("混合 vs 真值   PSNR %.2f dB\n", psnr(by, f1, w, h));
    printf("前帧 vs 真值   PSNR %.2f dB  (什么都不做的下限)\n", psnr(f0, f1, w, h));

    write_ppm("out-interp.ppm", oy, ouv, w, h);
    write_ppm("out-blend.ppm", by, buv, w, h);
    write_ppm("out-truth.ppm", f1, f1 + ys, w, h);
    write_err("out-err.ppm", oy, f1, w, h);
    write_err("out-err-blend.ppm", by, f1, w, h);
    printf("已写出 out-interp / out-blend / out-truth / out-err / out-err-blend .ppm\n");
    lpi_destroy(c);
    return 0;
}
