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

PC 上跑基准(zig 在 `.toolchain/`):

```bash
cd third_party/mpv-interp && python gen-kernels.py
../../.toolchain/zig/zig.exe cc -O2 -target x86_64-windows-gnu bench/lpi_bench.c lpinterp_ocl.c -o lpi_bench.exe
LPI_DEVICE=Intel ./lpi_bench.exe 1920 1080 12 6   # LPI_DEVICE=名字片段 强制挑卡
```

## 许可

算法来自 [HopperRender](https://github.com/HopperLogger/mpv-frame-interpolator)(作者 HopperLogger,
其 Windows 版明确为 GPL-3.0)。本目录代码按 **GPL-3.0-or-later** 发布,只能编进 GPL 构建的 libmpv;
LinPlayer 本体是 AGPL-3.0,兼容。
