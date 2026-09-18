// LinPlayer 补帧 OpenCL kernel。算法来自 HopperRender(GPL-3.0,HopperLogger),重写成可移植版本。
//
// 和上游的区别(为什么重写,见 docs/lessons/player-mpv.md「补帧」):
// - 不用 __local 内存 + barrier 做归约,也不用 OpenCL 2.0 起废弃的 atomic_add ——
//   Mali、以及三星 Xclipse 那种 ANGLE-CL→Vulkan 的实现上这两样编不过或算不对。
// - 窗口代价不再逐像素落缓冲再求和(见 lpi_search),大窗口只取样。
// - 只保留混合输出,调试用的流向可视化删了。
// 全部只用 OpenCL C 1.2 的东西,工作组大小交给驱动选。

// 候选偏移:当前最优偏移 + 沿 x(step 偶数)或 y(step 奇数)试探的平方步长,**按窗口尺度缩放**。
//
// ★ 原来每一级窗口都用同一套候选(-4,-1,0,1,4),这不是分层搜索,是把同一个精搜跑八遍:
//   八轮累计最多只能挪 ±32 px,而 1080p 动画快动作的帧间位移常到 50~100 px —— 够不着。
//   以前只能靠把 radius 从 5 堆到 16 来换范围,代价直接 3.2 倍。
//   按窗口缩放之后,radius 还是 5,累计可达 ±256 px。粗层管远、细层管准,各司其职。
//
// 步长下限钉在一个低分辨率格(1<<resShift 个全分辨率像素):搜索是在降采样平面上做的,
// 比一格更细的位移那上面根本分辨不出来,试了也只是把同一对像素再比一遍。
// 钉住之后所有矢量恒为整格倍数,降采样平面上的寻址才是精确的、不是截断的。
int lvl_scale(int windowSize, int resShift) {
    return clamp(windowSize / 4, min(1 << resShift, 16), 16);
}

int trial_adjust(int layer, int radius) {
    int a = (layer % radius) - (radius / 2);
    return a * a * (a > 0 ? 1 : -1);
}

int trial_at(int layer, int radius, int windowSize, int resShift) {
    return trial_adjust(layer, radius) * lvl_scale(windowSize, resShift);
}

// 把 NV12 降采样成搜索用的 (Y,U,V) 平面,一个低分辨率格一个 uchar4。
//
// ★ 这是移动端能不能跑得动的关键。原来每个采样点直接从全分辨率平面上抓 3 个字节,
//   行距、列距都是 1<<resShift —— 一条 64 字节 cache line 只用得上 4 个字节,
//   而全分辨率的 Y 平面(1080p 有 2MB)放不进移动 GPU 的 L2。实测 Intel 核显对 RTX 5060
//   慢 11.4 倍,正好是两者的**带宽**比而不是算力比(25 倍),瓶颈在访存已经证实。
//   降采样平面 1080p 只有 480×270×4 = 0.5MB,整块常驻 L2,且每个采样点只读一次对齐的 4 字节。
// 顺带:盒式平均比原来的点取样抗噪,块匹配本来就该在带低通的图上做。
__kernel void lpi_shrink(__global const uchar *src, __global uchar4 *dst, const int dimY,
                         const int dimX, const int lowY, const int lowX, const int resShift) {
    const int cx = get_global_id(0), cy = get_global_id(1);
    if (cx >= lowX || cy >= lowY) return;
    const int n = 1 << resShift;
    const int sx = cx << resShift, sy = cy << resShift;
    const int plane = dimY * dimX;
    int y = 0, u = 0, v = 0;
    for (int dy = 0; dy < n; dy++) {
        const int py = min(sy + dy, dimY - 1);
        for (int dx = 0; dx < n; dx++)
            y += src[py * dimX + min(sx + dx, dimX - 1)];
    }
    // UV 交错存放:一行 UV 对应两行 Y,一对 UV 对应两列 Y
    for (int dy = 0; dy < n / 2; dy++) {
        const int py = min((sy >> 1) + dy, (dimY >> 1) - 1);
        for (int dx = 0; dx < n / 2; dx++) {
            const int px = min(sx + dx * 2, dimX - 2) & ~1;
            u += src[plane + py * dimX + px];
            v += src[plane + py * dimX + px + 1];
        }
    }
    const int nc = max(n / 2 * (n / 2), 1);
    dst[cy * lowX + cx] = (uchar4)((uchar)(y / (n * n)), (uchar)(u / nc), (uchar)(v / nc), 0);
}

int mirror_in(int pos, int dim) {
    if (pos >= dim) return dim - (pos - dim + 1);
    if (pos < 0) return -pos - 1;
    return pos;
}

// 下面五个是实测定下来的常量,不做成参数:扫过全程都在噪声里,留成旋钮只是噪音。
#define LPI_LAMBDA   2    // 代价里「偏离邻域预测矢量」的权重
#define LPI_ZBIAS    1    // 「偏离零」的权重,平坦区没得选时倾向不动
#define LPI_STILL_TH 16   // 每采样点 SAD 低于这个值就认为当前矢量已够好,跳过其余试探层
#define LPI_OCC_LO   75   // 补偿后残差压到「完全不补」的百分之几才算完全可信
#define LPI_OCC_TOL  8    // 流场自洽的基础容忍量(全分辨率像素)

// 4 个数的中值 = 排序后中间两个的平均。取中值不取平均:一个错配的邻居能把平均拽走。
int med4(int a, int b, int c, int d) {
    return (max(min(a, b), min(c, d)) + min(max(a, b), max(c, d))) / 2;
}

// 一个窗口一个工作项:对每个试探层累加窗口里采样点的代价,挑最小的那层。
//
// 为什么不是上游的「逐像素写代价缓冲 → 再逐窗口求和」:核显实测 1080p 光流 139ms,
// 大头是给每个像素、每层都派一个工作项再读一遍。大窗口只是在估整体运动,
// 均匀取 8×8 个采样点足够;窗口 ≤8 时退回逐像素。
//
// ★ 代价 = SAD + lambda·|v − 预测矢量| + zbias·|v|。
//   **偏向邻居而不是偏向零**是动画能不能补对的关键:角色身体是大片平坦色块,
//   那里任何矢量的 SAD 都差不多,全由惩罚项拍板。原来惩罚项只有 zbias·|v|(偏向零),
//   于是身体判成不动、只有轮廓在动 —— 撕裂和果冻就是这么来的。
//   改成偏向邻居后,平坦内部能从「边缘那圈 SAD 说了算的窗口」继承运动。
__kernel void lpi_search(__global uchar *lowest, __global const uchar4 *s1, __global const uchar4 *s2,
                         __global const short *offs, const int lowY, const int lowX,
                         const int windowSize, const int radius, const int resShift, const int step) {
    const int wx = get_global_id(0) * windowSize, wy = get_global_id(1) * windowSize;
    if (wx >= lowX || wy >= lowY) return;
    const int ex = min(wx + windowSize, lowX), ey = min(wy + windowSize, lowY);
    const int stride = max(windowSize / 8, 1);
    const int sh = 1 << resShift;
    const int comp = (step & 1) ? lowY * lowX : 0;  // 这一趟在调 x 还是 y

    // 窗口内 offs 是常数:每一轮的窗口都被上一轮更大的窗口整块覆盖,同一格里值一样。
    // 原来每个采样点、每个试探层都重读一次,纯属浪费。
    const int base = wy * lowX + wx;
    const int cur_x = offs[base], cur_y = offs[lowY * lowX + base];

    // 预测矢量:上下左右各隔**一个**窗口的邻居取中值。原来取的是隔两个窗口、
    // 而且只在 iteration>=3 之后才生效 —— 前几轮(窗口大、最该定调的时候)完全没有约束。
    const int d = windowSize;
    const int pred = med4(offs[comp + clamp(wy + d, 0, lowY - 1) * lowX + wx],
                          offs[comp + clamp(wy - d, 0, lowY - 1) * lowX + wx],
                          offs[comp + wy * lowX + clamp(wx + d, 0, lowX - 1)],
                          offs[comp + wy * lowX + clamp(wx - d, 0, lowX - 1)]);

    uint best = 0;
    uchar bestZ = 0;
    // 静止早退:先算「不动」那一层。当前矢量已经把这块解释得够好,就不必再试其它层。
    // 动画 69% 的相邻帧是按住的原画,这条把大半的搜索直接砍掉。
    const int z0 = radius / 2;  // trial_adjust 在 a==0 处为 0,即 layer == radius/2
    for (int pass = 0; pass < 2; pass++) {
    for (int z = (pass ? 0 : z0); z < radius; z++) {
        if (pass && z == z0) continue;  // 第一趟已经算过
        const int adj = trial_at(z, radius, windowSize, resShift);
        const int vtry = ((step & 1) ? cur_y : cur_x) + adj;  // 这一层试探出来的分量值
        // 窗口内 offs 恒定(见上),所以整个窗口共用一组偏移 —— 不必每个采样点重读一遍。
        // 矢量恒为整格倍数(lvl_scale 钉了下限),除法在这里是精确的。
        const int oxl = ((step & 1) ? cur_x : vtry) / sh;
        const int oyl = ((step & 1) ? vtry : cur_y) / sh;
        uint sum = 0;
        int n = 0;
        for (int cy = wy; cy < ey; cy += stride) {
            const int ny = clamp(mirror_in(cy + oyl, lowY), 0, lowY - 1);
            for (int cx = wx; cx < ex; cx += stride) {
                const int nx = clamp(mirror_in(cx + oxl, lowX), 0, lowX - 1);
                const uchar4 p = s1[ny * lowX + nx], q = s2[cy * lowX + cx];
                sum += (abs_diff(p.x, q.x) + abs_diff(p.y, q.y) + abs_diff(p.z, q.z)) << 2;
                n++;
            }
        }
        // 惩罚按采样点数放大,才和 SAD 在同一量纲上 —— 否则窗口越大惩罚越不起作用
        const uint sad = sum;
        sum += (uint)((LPI_LAMBDA * abs(vtry - pred) + LPI_ZBIAS * abs(vtry)) * n);
        if (pass == 0) {
            best = sum;
            bestZ = (uchar)z0;
            if (sad <= (uint)(LPI_STILL_TH * n)) {  // 够好了,剩下 radius-1 层不用试
                lowest[wy * lowX + wx] = bestZ;
                return;
            }
            break;
        }
        if (sum < best) {
            best = sum;
            bestZ = (uchar)z;
        }
    }
    }
    lowest[wy * lowX + wx] = bestZ;
}

__kernel void lpi_adjust(__global short *offs, __global const uchar *lowest, const int windowSize,
                         const int radius, const int lowY, const int lowX, const int step,
                         const int resShift) {
    const int cx = get_global_id(0), cy = get_global_id(1);
    if (cx >= lowX || cy >= lowY) return;
    const int wx = (cx / windowSize) * windowSize, wy = (cy / windowSize) * windowSize;
    offs[(step & 1) * lowY * lowX + cy * lowX + cx] +=
        (short)trial_at(lowest[wy * lowX + wx], radius, windowSize, resShift);
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

// 遮挡掩膜:这一格的运动矢量有多不可信。0 = 完全可信,255 = 完全不可信。
//
// 两个判据都是现成数据算出来的,不需要第二遍运动估计(MVTools 也是这么省的):
//   1. 匹配残差 —— 光流说 next[p] 的内容在 prev[p+o],那两处像素就该长得一样。差得多 = 配错了。
//      这个残差只和「对应关系」有关,和补到哪个时刻 t 无关,所以一帧只算一次。
//   2. 流场自洽 —— 沿矢量回溯一格再读一次矢量,两者差得多说明这里在收敛/发散,也就是遮挡与露出。
//
// ★ 半透明鬼影就出在这:那里光流其实是对的,但角色背后的像素在另一帧里**根本不存在**,
//   硬把两边混在一起就成了透明人。判出来之后 warp 那边会退回「最近一帧的原像素」。
__kernel void lpi_occ(__global uchar *occ, __global const uchar *prev, __global const uchar *next,
                      __global const short *offs, const int dimY, const int dimX, const int lowY,
                      const int lowX, const int resShift) {
    const int cx = get_global_id(0), cy = get_global_id(1);
    if (cx >= lowX || cy >= lowY) return;
    const int idx = cy * lowX + cx;
    const int ox = offs[idx], oy = offs[lowY * lowX + idx];
    const int sx = cx << resShift, sy = cy << resShift;

    // 3×3 取样,同时求两个残差:补偿后的、和完全不补的。单点比太抖,平坦色块上会满屏椒盐。
    // ☠ 不能用绝对阈值 —— 残差量级跟画面内容强相关(细草丛天生就比大色块残差高十倍),
    //   定死阈值在合成纹理上实测误报 38.6%。**拿两个残差相比**才是自归一化的:
    //   运动补偿的价值就在于把残差压下去,压不下去就说明这个矢量没用。
    int mc = 0, still = 0;
    for (int dy = -2; dy <= 2; dy += 2) {
        const int ny = clamp(sy + dy, 0, dimY - 1);
        const int py = clamp(ny + oy, 0, dimY - 1);
        for (int dx = -2; dx <= 2; dx += 2) {
            const int nx = clamp(sx + dx, 0, dimX - 1);
            const int px = clamp(nx + ox, 0, dimX - 1);
            const int n = next[ny * dimX + nx];
            mc += abs(prev[py * dimX + px] - n);
            still += abs(prev[ny * dimX + nx] - n);
        }
    }

    const int bx = clamp(cx - (ox >> resShift), 0, lowX - 1);
    const int by = clamp(cy - (oy >> resShift), 0, lowY - 1);
    const int inc = abs_diff((int)offs[by * lowX + bx], ox) +
                    abs_diff((int)offs[lowY * lowX + by * lowX + bx], oy);

    // 补偿后残差已经很小 → 无条件可信(静止画面、大片纯色,还有被硬字幕盖住的地方)
    int a = 0;
    if (mc > 54) {
        // 压到「不补」残差的 LPI_OCC_LO% 以下算完全可信,一点压不动算完全不可信
        const int lo = still * LPI_OCC_LO / 100;  // half 是 OpenCL 内建类型名,不能当变量
        const int span = still - lo > 1 ? still - lo : 1;
        a = clamp((mc - lo) * 255 / span, 0, 255);
    }
    // 流场自洽:回溯一格再读一次矢量。容忍量随位移增大而放宽 —— 大位移本来就估得粗,
    // 用固定容忍量的话真运动场景实测有 40~74% 的格子被判成遮挡,等于把补帧整个关掉了。
    const int mag = abs(ox) + abs(oy);
    const int tol = mag > LPI_OCC_TOL ? mag : LPI_OCC_TOL;
    const int b = clamp((inc - tol) * 255 / (tol * 2), 0, 255);
    occ[idx] = (uchar)max(a, b);
}

// 掩膜后处理:3×3 取最大值(膨胀)再和均值各取一半。
// 膨胀是必须的 —— 遮挡边界上掩膜总是比真实伪影区小一圈,不扩边的话鬼影会沿着边缘留一条。
__kernel void lpi_occ_post(__global const uchar *src, __global uchar *dst, const int lowY, const int lowX) {
    const int cx = get_global_id(0), cy = get_global_id(1);
    if (cx >= lowX || cy >= lowY) return;
    int mx = 0, sum = 0;
    for (int ky = -1; ky <= 1; ky++) {
        const int y = clamp(cy + ky, 0, lowY - 1);
        for (int kx = -1; kx <= 1; kx++) {
            const int v = src[y * lowX + clamp(cx + kx, 0, lowX - 1)];
            sum += v;
            mx = max(mx, v);
        }
    }
    dst[cy * lowX + cx] = (uchar)((mx + sum / 9) >> 1);
}

int mirror_warp(int pos, int dim) {
    int res = pos;
    if (pos >= dim - 1) res = pos - ((pos - (dim - 2)) * 2);
    else if (pos < 1) res = -pos + 1;
    return clamp(res, 1, dim - 2);
}

// 按光流把前后两帧各推到 t 处再按 t 混合;遮挡区按掩膜退回最近一帧的原像素。
// cz=0 画 Y 平面,cz=1 画 NV12 的 UV 平面。
__kernel void lpi_warp(__global const uchar *prev, __global const uchar *next, __global const short *offs,
                       __global const uchar *occ, __global uchar *out, const float t, const int lowY,
                       const int lowX, const int dimY, const int dimX, const int actualX,
                       const int resShift, const int cz) {
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
    const float mc = a * (1.0f - t) + b * t;
    // 退路:**不做**运动补偿的最近一帧原像素。那一块因此没补到帧(看起来会顿一下),
    // 但不会出现半透明的人 —— 宁可不流畅,不要鬼影(SVP 官方对这个取舍的说法是 "lose smoothness")。
    const __global uchar *near = t < 0.5f ? prev : next;
    const float fb = near[base + cy * dimX + cx];
    const float k = occ[ly * lowX + lx] * (1.0f / 255.0f);
    out[base + cy * dimX + cx] = (uchar)clamp(mc + (fb - mc) * k + 0.5f, 0.0f, 255.0f);
}
