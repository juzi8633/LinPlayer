// 补帧的 OpenCL 计算核:光流 + 按 t 混合。**不依赖 mpv**,PC 上能单独编译跑基准(bench/)。
#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

struct lpi_ctx;

// 建上下文。stride = NV12 每行字节数(两个平面必须相同),width = 实际画面宽。
// 失败返回 NULL,原因写进 err(中文,给用户看)。
struct lpi_ctx *lpi_create(int stride, int width, int height, char *err, size_t errlen);
void lpi_destroy(struct lpi_ctx *c);
const char *lpi_device_name(struct lpi_ctx *c);

// 送入新的一帧(Y 平面 stride*height,UV 平面 stride*height/2)。之后它是「next」,原来的 next 变「prev」。
int lpi_push(struct lpi_ctx *c, const uint8_t *y, const uint8_t *uv);
// 算 prev → next 的光流。push 过至少两帧才有意义。
int lpi_flow(struct lpi_ctx *c);
// 生成 t∈(0,1) 处的中间帧,阻塞到拷回为止。
int lpi_warp(struct lpi_ctx *c, float t, uint8_t *y, uint8_t *uv);

// 搜索半径:越大越准越慢。滤镜按实测耗时在 [LPI_RADIUS_MIN, LPI_RADIUS_MAX] 里调。
#define LPI_RADIUS_MIN 5
#define LPI_RADIUS_MAX 16

// 光流最细算到多大的窗口(低分辨率格)。**这是比搜索半径更粗的那把旋钮**:
// 实测最后几级窗口(W=8→2)占 60% 算力却几乎不改善结果 —— 流场随后还要过 3×3 中值,
// 比格子更细的运动本来就被抹掉了。跑不动时先把它调粗,别先砍搜索半径
// (实测 R=8/W=16 用 7.1ms 就压过 R=5/W=2 的 11.2ms)。
#define LPI_MIN_WIN_BEST 8
#define LPI_MIN_WIN_DEF  16
#define LPI_MIN_WIN_WORST 32
void lpi_set_radius(struct lpi_ctx *c, int radius);
int lpi_radius(struct lpi_ctx *c);
// 一对相邻源帧的粗略统计。16×9 个块、每块固定取 8×8 个点 —— 和分辨率无关,
// 一共 9216 次取样,微秒级。放在这一层是因为它不依赖 mpv,可以单独测(bench/lpi_pair_test.c)。
struct lpi_pair {
    int mean;       // 整帧平均绝对差
    int max_block;  // 最大的块平均差
    int hi_pct;     // 块平均差超标的块占比
};
void lpi_pair_stats(const unsigned char *a, int stride_a, const unsigned char *b, int stride_b,
                    int w, int h, struct lpi_pair *out);

// 硬切:两帧内容毫不相干,光流会把它们拉成一团烂泥。不补,顺带省掉整个光流。
bool lpi_is_scene_cut(const struct lpi_pair *st);
// 按住的同一张原画(动画一拍二/一拍三)。中间帧就是前一帧本身,跑光流纯属浪费。
bool lpi_is_duplicate(const struct lpi_pair *st);

void lpi_set_min_win(struct lpi_ctx *c, int w);
int lpi_min_win(struct lpi_ctx *c);

// 一帧光流的统计量。bench 拿它量算法好坏 —— occ_pct(被判成遮挡的格子占比)
// 是比 PSNR 更好用的质量代理:流场越可信,它越低。
struct lpi_stats {
    int cells;        // 低分辨率网格的格子数
    double mean_abs;  // |ox|/|oy| 的平均(全分辨率像素)
    int max_abs;      // 最大位移
    double occ_pct;   // 遮挡掩膜 >128 的格子占比
};
void lpi_flow_stats(struct lpi_ctx *c, struct lpi_stats *st);
// 等队列清空。只给基准拆分计时用 —— flow 是异步排队的,不等的话时间全算到 warp 头上。
int lpi_finish(struct lpi_ctx *c);
