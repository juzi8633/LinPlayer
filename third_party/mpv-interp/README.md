# mpv-interp · 安卓 OpenCL 补帧滤镜

编进自编 libmpv 的视频滤镜 `vf=lpinterp=multi=N`,用 OpenCL 光流把帧率补到源的 N 倍(2~4)。

| 文件 | 做什么 |
|---|---|
| `vf_lpinterp.c` | mpv 滤镜外壳:排帧、时间戳、跑不动就放弃并报错 |
| `lpinterp_ocl.c/.h` | OpenCL 计算核,**不依赖 mpv**,PC 上可单独编译 |
| `lpinterp_cl.h` | OpenCL 函数表,运行时 dlopen(硬链的话没 OpenCL 的机器整颗 libmpv 起不来) |
| `lpinterp.cl` | kernel 源;`gen-kernels.py` 转成 C 字符串编进 .so |
| `apply-to-mpv.sh` | 装进一份 mpv 源码树(CI 用,见 `.github/workflows/libmpv-android.yml`) |
| `bench/lpi_bench.c` | 基准 + 正确性:平移纹理,光流补帧 PSNR 必须明显高于直接混合 |
| `bench/lpi_pair_test.c` | 帧对判据(切镜 / 按住的原画)的自检。纯逻辑,不依赖 mpv 和 OpenCL |
| `bench/lpi_real.c` | **真实片源**对照:取第 0、2 帧算中间帧去对真的第 1 帧。合成纹理没有遮挡/形变/细线条,测不出鬼影。顺带跑光流分辨率的切档自检 |

PC 上跑基准(zig 在 `.toolchain/`):

```bash
cd third_party/mpv-interp && python gen-kernels.py
../../.toolchain/zig/zig.exe cc -O2 -target x86_64-windows-gnu bench/lpi_bench.c lpinterp_ocl.c -o lpi_bench.exe
LPI_DEVICE=Intel ./lpi_bench.exe 1920 1080 12 6   # LPI_DEVICE=名字片段 强制挑卡
```

真实片源对照(数字和踩过的坑见 `docs/lessons/player-mpv.md`「安卓补帧第二轮」):

```bash
# ⚠ 取样点要挑「真运动」的三元组。动画 69% 的相邻帧是按住的原画,随便取时间点会量到一片打平
ffmpeg -ss 300 -i 番.mkv -vf "select='between(n,49,51)'" -fps_mode passthrough \
       -frames:v 3 -f rawvideo -pix_fmt nv12 tri.nv12
./lpi_real.exe tri.nv12 1920 1080          # 出 PSNR + 遮挡掩膜占比 + 五张对照图
LPI_OCC=0 ./lpi_real.exe tri.nv12 1920 1080   # 关掉遮挡掩膜,单看光流质量
LPI_RADIUS=16 LPI_MINWIN=8 ./lpi_real.exe …   # 手动指定档位(平时由滤镜按耗时自适应)
LPI_SHIFT=1 ./lpi_real.exe …                  # 光流再降一半分辨率(跑不动时的最后一档)
```

判据自检(改阈值前先跑):

```bash
../../.toolchain/zig/zig.exe cc -O2 bench/lpi_pair_test.c lpinterp_ocl.c -o t.exe && ./t.exe
```

**PSNR 不是好尺子** —— 它奖励糊,而直接混合是「糊」的极致形态。看 `遮挡掩膜 %`:流场越可信它越低。

改访存相关的东西时**拿核显量,别拿独显量**:两者差的是带宽不是算力,核显才代表手机。

## 许可

算法来自 [HopperRender](https://github.com/HopperLogger/mpv-frame-interpolator)(作者 HopperLogger,
其 Windows 版明确为 GPL-3.0)。本目录代码按 **GPL-3.0-or-later** 发布,只能编进 GPL 构建的 libmpv;
LinPlayer 本体是 AGPL-3.0,兼容。
