// LinPlayer 补帧 OpenCL kernel。算法来自 HopperRender(GPL-3.0,HopperLogger),重写成可移植版本。
//
// 和上游的区别(为什么重写,见 docs/lessons/player-mpv.md「补帧」):
// - 不用 __local 内存 + barrier 做归约,也不用 OpenCL 2.0 起废弃的 atomic_add ——
//   Mali、以及三星 Xclipse 那种 ANGLE-CL→Vulkan 的实现上这两样编不过或算不对。
// - 窗口代价不再逐像素落缓冲再求和(见 lpi_search),大窗口只取样。
// - 只保留混合输出,调试用的流向可视化删了。
// 全部只用 OpenCL C 1.2 的东西,工作组大小交给驱动选。

// 候选偏移:当前最优偏移 + 沿 x(step 偶数)或 y(step 奇数)试探的平方步长。
int trial_adjust(int layer, int radius) {
    int a = (layer % radius) - (radius / 2);
    return a * a * (a > 0 ? 1 : -1);
}

int mirror_in(int pos, int dim) {
    if (pos >= dim) return dim - (pos - dim + 1);
    if (pos < 0) return -pos - 1;
    return pos;
}

// 一个窗口一个工作项:对每个试探层累加窗口里采样点的代价,挑最小的那层。
//
// 为什么不是上游的「逐像素写代价缓冲 → 再逐窗口求和」:核显实测 1080p 光流 139ms,
// 大头是给每个像素、每层都派一个工作项再读一遍。大窗口只是在估整体运动,
// 均匀取 8×8 个采样点足够;窗口 ≤8 时退回逐像素。
__kernel void lpi_search(__global uchar *lowest, __global const uchar *f1, __global const uchar *f2,
                         __global const short *offs, const int dimY, const int dimX, const int lowY,
                         const int lowX, const int windowSize, const int radius, const int resShift,
                         const int iteration, const int step) {
    const int wx = get_global_id(0) * windowSize, wy = get_global_id(1) * windowSize;
    if (wx >= lowX || wy >= lowY) return;
    const int ex = min(wx + windowSize, lowX), ey = min(wy + windowSize, lowY);
    const int stride = max(windowSize / 8, 1);
    const int plane = dimY * dimX;
    float best = 0;
    uchar bestZ = 0;
    for (int z = 0; z < radius; z++) {
        const int adj = trial_adjust(z, radius);
        uint sum = 0;
        for (int cy = wy; cy < ey; cy += stride) {
            for (int cx = wx; cx < ex; cx += stride) {
                const int idx = cy * lowX + cx;
                int ox = offs[idx], oy = offs[lowY * lowX + idx];
                if (step & 1) oy += adj; else ox += adj;
                const int sx = min(cx << resShift, dimX - 1), sy = min(cy << resShift, dimY - 1);
                const int nx = clamp(mirror_in(sx + ox, dimX), 0, dimX - 1);
                const int ny = clamp(mirror_in(sy + oy, dimY), 0, dimY - 1);
                const int c1 = plane + (ny >> 1) * dimX + (nx & ~1);
                const int c2 = plane + (sy >> 1) * dimX + (sx & ~1);
                uint cost = (abs_diff(f1[ny * dimX + nx], f2[sy * dimX + sx]) + abs_diff(f1[c1], f2[c2]) +
                             abs_diff(f1[c1 + 1], f2[c2 + 1])) << 2;
                cost += (step & 1) ? abs(oy) : abs(ox);
                if (iteration >= 3) {
                    // 邻居一致性:和上下左右隔两个窗口处的偏移差得越多越贵,压住孤立的错配
                    const int d = 2 * windowSize;
                    const int n0 = clamp(cy + d, 0, lowY - 1) * lowX + cx;
                    const int n1 = cy * lowX + clamp(cx + d, 0, lowX - 1);
                    const int n2 = cy * lowX + clamp(cx - d, 0, lowX - 1);
                    const int n3 = clamp(cy - d, 0, lowY - 1) * lowX + cx;
                    const int o = (step & 1) ? lowY * lowX : 0;
                    const int v = (step & 1) ? oy : ox;
                    cost += (abs_diff((int)offs[o + n0], v) + abs_diff((int)offs[o + n1], v) +
                             abs_diff((int)offs[o + n2], v) + abs_diff((int)offs[o + n3], v)) >> 1;
                }
                sum += cost;
            }
        }
        const float f = (float)sum;
        if (z == 0 || f < best) {
            best = f;
            bestZ = (uchar)z;
        }
    }
    lowest[wy * lowX + wx] = bestZ;
}

__kernel void lpi_adjust(__global short *offs, __global const uchar *lowest, const int windowSize,
                         const int radius, const int lowY, const int lowX, const int step) {
    const int cx = get_global_id(0), cy = get_global_id(1);
    if (cx >= lowX || cy >= lowY) return;
    const int wx = (cx / windowSize) * windowSize, wy = (cy / windowSize) * windowSize;
    offs[(step & 1) * lowY * lowX + cy * lowX + cx] += (short)trial_adjust(lowest[wy * lowX + wx], radius);
}

// 8×8 方框模糊,边界镜像。
__kernel void lpi_blur(__global const short *offs, __global short *out, const int lowY, const int lowX) {
    const int cx = get_global_id(0), cy = get_global_id(1), cz = get_global_id(2);
    if (cx >= lowX || cy >= lowY) return;
    int sum = 0;
    for (int ky = -4; ky < 4; ky++) {
        const int y = clamp(mirror_in(cy + ky, lowY), 0, lowY - 1);
        for (int kx = -4; kx < 4; kx++) {
            const int x = clamp(mirror_in(cx + kx, lowX), 0, lowX - 1);
            sum += offs[cz * lowY * lowX + y * lowX + x];
        }
    }
    out[cz * lowY * lowX + cy * lowX + cx] = (short)(sum / 64);
}

int mirror_warp(int pos, int dim) {
    int res = pos;
    if (pos >= dim - 1) res = pos - ((pos - (dim - 2)) * 2);
    else if (pos < 1) res = -pos + 1;
    return clamp(res, 1, dim - 2);
}

// 按光流把前后两帧各推到 t 处再按 t 混合。cz=0 画 Y 平面,cz=1 画 NV12 的 UV 平面。
__kernel void lpi_warp(__global const uchar *prev, __global const uchar *next, __global const short *offs,
                       __global uchar *out, const float t, const int lowY, const int lowX, const int dimY,
                       const int dimX, const int actualX, const int resShift, const int cz) {
    const int cx = get_global_id(0), cy = get_global_id(1);
    if (cy >= (dimY >> cz) || cx >= actualX) return;
    // UV 平面一行对应两行全分辨率像素;x 在交错存放下本来就是全分辨率坐标
    const int scx = cx >> resShift;
    const int scy = cz ? ((cy << 1) >> resShift) : (cy >> resShift);
    const int lx = clamp(scx, 0, lowX - 1), ly = clamp(scy, 0, lowY - 1);
    const int ox12 = offs[ly * lowX + lx], oy12 = offs[lowY * lowX + ly * lowX + lx];
    const int bx = clamp(lx - (ox12 >> resShift), 0, lowX - 1), by = clamp(ly - (oy12 >> resShift), 0, lowY - 1);
    const int ox21 = offs[by * lowX + bx], oy21 = offs[lowY * lowX + by * lowX + bx];
    const float yScale = cz ? 0.5f : 1.0f;
    const int planeY = cz ? (dimY >> 1) : dimY;
    const int x12 = mirror_warp(cx + (int)round(ox12 * t), actualX);
    const int y12 = mirror_warp(cy + (int)round(oy12 * t * yScale), planeY);
    const int x21 = mirror_warp(cx - (int)round(ox21 * (1.0f - t)), actualX);
    const int y21 = mirror_warp(cy - (int)round(oy21 * (1.0f - t) * yScale), planeY);
    const int base = cz * dimY * dimX;
    const int keep = cz ? (cx & 1) : 0;  // UV 交错存放:保持 U/V 通道不串
    const int mask = cz ? ~1 : ~0;
    const float a = prev[base + y12 * dimX + (x12 & mask) + keep];
    const float b = next[base + y21 * dimX + (x21 & mask) + keep];
    out[base + cy * dimX + cx] = (uchar)clamp(a * (1.0f - t) + b * t + 0.5f, 0.0f, 255.0f);
}
