// 补帧的 OpenCL 计算核:光流 + 按 t 混合。**不依赖 mpv**,PC 上能单独编译跑基准(bench/)。
#pragma once

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
void lpi_set_radius(struct lpi_ctx *c, int radius);
int lpi_radius(struct lpi_ctx *c);
// 等队列清空。只给基准拆分计时用 —— flow 是异步排队的,不等的话时间全算到 warp 头上。
int lpi_finish(struct lpi_ctx *c);
