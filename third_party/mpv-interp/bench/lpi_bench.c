// 补帧计算核的基准 + 正确性检查。PC 上跑(OpenCL.dll),不依赖 mpv。
//
//   lpi_bench [宽] [高] [每帧位移x] [每帧位移y]
//
// 造一张平滑纹理按固定速度平移,真值中间帧 = 平移一半。看三个 PSNR:
// 光流补帧 vs 真值 必须明显高于 直接混合 vs 真值 —— 否则光流没起作用,补出来的只是重影。
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "../lpinterp_ocl.h"

#if defined(_WIN32)
#include <windows.h>
static double now(void) {
    LARGE_INTEGER f, c;
    QueryPerformanceFrequency(&f);
    QueryPerformanceCounter(&c);
    return (double)c.QuadPart / f.QuadPart;
}
#else
static double now(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec + ts.tv_nsec / 1e9;
}
#endif

// 值噪声(两个尺度叠加)。不用正弦叠加:周期纹理平移半个周期后和自己一模一样,
// 光流会「对上」错的那个周期,测出来的是纹理的问题而不是算法的问题。
static double hash2(int x, int y) {
    unsigned h = (unsigned)x * 374761393u + (unsigned)y * 668265263u;
    h = (h ^ (h >> 13)) * 1274126177u;
    return ((h ^ (h >> 16)) & 0xffff) / 65535.0;
}
static double vnoise(double x, double y, double cell) {
    x /= cell; y /= cell;
    int ix = (int)floor(x), iy = (int)floor(y);
    double fx = x - ix, fy = y - iy;
    fx = fx * fx * (3 - 2 * fx); fy = fy * fy * (3 - 2 * fy);
    double a = hash2(ix, iy), b = hash2(ix + 1, iy), c = hash2(ix, iy + 1), d = hash2(ix + 1, iy + 1);
    return (a + (b - a) * fx) + ((c + (d - c) * fx) - (a + (b - a) * fx)) * fy;
}
static unsigned char tex(double x, double y) {
    double v = 40 + 150 * vnoise(x, y, 23) + 60 * vnoise(x + 1000, y, 7);
    return (unsigned char)(v < 0 ? 0 : v > 255 ? 255 : v);
}

// 画面 = 纹理整体平移 (ox, oy);UV 用同一纹理的另一相位,保证色度也在动
static void render(unsigned char *y, unsigned char *uv, int w, int h, double ox, double oy) {
    for (int j = 0; j < h; j++)
        for (int i = 0; i < w; i++)
            y[j * w + i] = tex(i - ox, j - oy);
    for (int j = 0; j < h / 2; j++)
        for (int i = 0; i < w; i += 2) {
            uv[j * w + i] = tex(2 * i - ox + 77, 2 * j - oy);
            uv[j * w + i + 1] = tex(2 * i - ox, 2 * j - oy + 55);
        }
}

// 只比中间 80% —— 边缘处光流镜像取样本来就没法和真值对上,算进去会淹掉差别
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

int main(int argc, char **argv) {
    int w = argc > 1 ? atoi(argv[1]) : 1920, h = argc > 2 ? atoi(argv[2]) : 1080;
    double dx = argc > 3 ? atof(argv[3]) : 12, dy = argc > 4 ? atof(argv[4]) : 6;
    size_t ys = (size_t)w * h;
    unsigned char *py = malloc(ys), *puv = malloc(ys / 2), *ny = malloc(ys), *nuv = malloc(ys / 2);
    unsigned char *ty = malloc(ys), *tuv = malloc(ys / 2), *oy = malloc(ys), *ouv = malloc(ys / 2), *by = malloc(ys);

    char err[600];
    double t0 = now();
    struct lpi_ctx *c = lpi_create(w, w, h, err, sizeof(err));
    if (!c) {
        printf("创建失败:%s\n", err);
        return 1;
    }
    printf("设备:%s  初始化(含 kernel 编译)%.0f ms\n", lpi_device_name(c), (now() - t0) * 1000);

    for (int radius = LPI_RADIUS_MIN; radius <= LPI_RADIUS_MAX; radius += 11) {
        lpi_set_radius(c, radius);
        double flow_ms = 0, warp_ms = 0, up_ms = 0;
        const int rounds = 12;
        for (int r = 0; r < rounds; r++) {
            render(py, puv, w, h, dx * r, dy * r);
            render(ny, nuv, w, h, dx * (r + 1), dy * (r + 1));
            double u = now();
            lpi_push(c, py, puv);
            lpi_push(c, ny, nuv);
            double a = now();
            if (lpi_flow(c)) { printf("flow 失败\n"); return 1; }
            lpi_finish(c);
            { struct lpi_stats st; lpi_flow_stats(c, &st);
              fprintf(stderr, "  光流场 平均|v|=%.2f 最大=%d 遮挡掩膜 %.1f%%\n",
                      st.mean_abs, st.max_abs, st.occ_pct); }
            double b = now();
            if (lpi_warp(c, 0.5f, oy, ouv)) { printf("warp 失败\n"); return 1; }
            double d = now();
            if (r >= 2) { up_ms += (a - u) * 1000; flow_ms += (b - a) * 1000; warp_ms += (d - b) * 1000; }  // 前两轮算预热
        }
        printf("半径 %2d:上传两帧 %.1f ms | 光流 %.1f ms | 一张中间帧(含读回)%.1f ms\n", radius,
               up_ms / (rounds - 2), flow_ms / (rounds - 2), warp_ms / (rounds - 2));
        render(ty, tuv, w, h, dx * 11.5, dy * 11.5);
        for (size_t i = 0; i < ys; i++) by[i] = (unsigned char)((py[i] + ny[i] + 1) / 2);
        printf("         PSNR:光流补帧 %.2f dB | 直接混合 %.2f dB | 只重复上一帧 %.2f dB\n",
               psnr(oy, ty, w, h), psnr(by, ty, w, h), psnr(py, ty, w, h));
    }
    lpi_destroy(c);
    return 0;
}
