// 帧对判据的自检(切镜 / 按住的原画)。不依赖 mpv 也不依赖 OpenCL。
//
//   zig cc -O2 bench/lpi_pair_test.c lpinterp_ocl.c -o t && ./t
//
// 这段是纯逻辑,改阈值之前先看它红:把 LPI_DUP_BLOCK_TH 调到 0 或把 && 改成 ||,
// 下面必有用例失败。全绿才说明判据还按标定时的口径工作。
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "../lpinterp_ocl.h"

#define W 1920
#define H 1080

static unsigned char *a, *b;

static void fill(unsigned char *p, int v) { memset(p, v, (size_t)W * H); }

// 在 (x0,y0) 起的 w×h 矩形里加上 delta
static void patch(unsigned char *p, int x0, int y0, int w, int h, int delta) {
    for (int y = y0; y < y0 + h && y < H; y++)
        for (int x = x0; x < x0 + w && x < W; x++) {
            const int v = p[(size_t)y * W + x] + delta;
            p[(size_t)y * W + x] = (unsigned char)(v < 0 ? 0 : v > 255 ? 255 : v);
        }
}

static struct lpi_pair run(void) {
    struct lpi_pair st;
    lpi_pair_stats(a, W, b, W, W, H, &st);
    return st;
}

static int fails;
static void check(const char *what, bool got, bool want, struct lpi_pair st) {
    if (got != want) {
        printf("  ✗ %s:期望 %s,实际 %s (mean=%d max_block=%d hi=%d%%)\n",
               what, want ? "是" : "否", got ? "是" : "否", st.mean, st.max_block, st.hi_pct);
        fails++;
    } else {
        printf("  ✓ %s (mean=%d max_block=%d hi=%d%%)\n", what, st.mean, st.max_block, st.hi_pct);
    }
}

int main(void) {
    a = malloc((size_t)W * H);
    b = malloc((size_t)W * H);
    if (!a || !b)
        return 1;
    struct lpi_pair st;

    printf("按住的同一张原画:\n");
    fill(a, 120);
    fill(b, 120);
    st = run();
    check("两帧完全相同 → 判为按住", lpi_is_duplicate(&st), true, st);
    check("两帧完全相同 → 不是切镜", lpi_is_scene_cut(&st), false, st);

    // ★ 这条是 max_block 存在的理由:整帧平均差还是 0,但有一小块在动。
    //   只看整帧平均的话会被判成「没动」,那一小块的运动就永远补不出来。
    fill(b, 120);
    patch(b, 200, 200, 90, 90, 60);
    st = run();
    check("静止背景上一小块在动 → 不判为按住", lpi_is_duplicate(&st), false, st);

    printf("\n轻微变化(编码噪声量级):\n");
    fill(b, 120);
    patch(b, 0, 0, W, H, 1);
    st = run();
    check("整帧差 1 → 仍判为按住", lpi_is_duplicate(&st), true, st);

    fill(b, 120);
    patch(b, 0, 0, W, H, 6);
    st = run();
    check("整帧差 6 → 不判为按住", lpi_is_duplicate(&st), false, st);

    printf("\n切镜:\n");
    fill(b, 20);  // 整帧从 120 跳到 20
    st = run();
    check("整帧内容全变 → 判为切镜", lpi_is_scene_cut(&st), true, st);
    check("整帧内容全变 → 不判为按住", lpi_is_duplicate(&st), false, st);

    // ★ 这两条防的是 MVTools 默认 thSCD2=51% 的误杀:实测动画快速运动有 54%~59% 的块超标,
    //   只看块占比会把它当成切镜,补帧在最需要的地方被关掉。
    fill(b, 120);
    patch(b, 0, 0, W, H * 6 / 10, 40);
    st = run();
    check("六成画面在动(快速运动) → 不判为切镜", lpi_is_scene_cut(&st), false, st);

    // 同样六成的块,但变化幅度拉满 —— 整体差越过 50,只剩块占比这一道闸。
    // 阈值退回 51% 的话这条会红,而这正是动画上最容易误杀的形态。
    fill(b, 120);
    patch(b, 0, 0, W, H * 6 / 10, 135);
    st = run();
    check("六成画面剧变(仍非切镜) → 不判为切镜", lpi_is_scene_cut(&st), false, st);

    printf("\n边界:\n");
    lpi_pair_stats(a, W, b, W, 80, 40, &st);  // 比 16×9 块还小
    check("画面太小分不出块 → 不判为按住(照常补)", lpi_is_duplicate(&st), false, st);

    free(a);
    free(b);
    printf("\n%s\n", fails ? "有用例失败" : "全部通过");
    return fails ? 1 : 0;
}
