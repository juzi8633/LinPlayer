#!/usr/bin/env python3
"""打补帧运行时包(Windows x64):从上游 mpv_PlayKit 懒人包里挑出补帧要的文件,打成可复现的 zip。

    python scripts/pack-interp-runtime.py [输出目录]      # 需要 7z 在 PATH 或装在默认位置
    python scripts/pack-interp-runtime.py --trt <vsNV 分卷所在目录> [输出目录]   # N 卡加速包

N 卡加速包(TensorRT)单独发 Release interp-trt-<版本>:一个通用包 + 每代显卡架构一个包,
用户只下自己那一代的(引擎构建资源一代 110~450MB,全带上就是 2GB)。
上游 vsNV 分卷 2.9GB,脚本不自己下(单线程要一个多小时):先多线程下到本地,脚本校验 sha256。

产物发到本仓库 Release 的 interp-runtime-<版本> 下,再把打印出的 sha256 和字节数
填回 core/interp/runtime.go。**换上游版本 = 改下面两个常量 + 重跑实测**(见 docs/lessons/player-mpv.md「补帧」)。

为什么是挑文件而不是整包转发:懒人包 132MB 里一大半是 mpv.exe、yt-dlp、pip、onnx 这些补帧用不上的东西。
文件表是**实测**出来的:少了 .pyd 那一批 VSScript 初始化直接失败(「last error unknown」)。
"""
import hashlib
import os
import shutil
import subprocess
import sys
import tempfile
import urllib.request
import zipfile

UPSTREAM_TAG = "20260510"
UPSTREAM_SHA256 = "f03e288d5c41b00604a64d30bb81e5f76e7f125c0447926ff39561b32d44fef4"
RUNTIME_VERSION = "1"
TRT_VERSION = "1"
# vsNV 分卷(同一个上游版本)
TRT_VOLUMES = {
    f"mpv-lazy-{UPSTREAM_TAG}-vsNV.7z.001": "f877973fae54d7cc05451d0369340f5a4ef6c82a2f633c66b786a337d3e6ddda",
    f"mpv-lazy-{UPSTREAM_TAG}-vsNV.7z.002": "1c7303d76f3767f16122c5ea16894add08b6c044ab15d2ccefba6155b8782007",
}
# 通用部分:实测少了 nvinfer_plugin 引擎建不出来(trtexec「Unable to open library」)。
# cuBLAS / cuDNN / cuFFT 用不着:k7sfunc 调 TRT 时 use_cublas / use_cudnn 都是 False。
TRT_COMMON = [
    "vs-plugins/vstrt.dll",
    "vs-plugins/vsmlrt-cuda/cudart64_13.dll",
    "vs-plugins/vsmlrt-cuda/nvinfer_10.dll",
    "vs-plugins/vsmlrt-cuda/nvinfer_plugin_10.dll",
    "vs-plugins/vsmlrt-cuda/nvonnxparser_10.dll",
    "vs-plugins/vsmlrt-cuda/trtexec.exe",
    # DRBA 走 TRT 时用 fp32 模型(DML 用的是 _fp16 那份)
    "vs-plugins/models/drba/distilDRBA_v2_lite_scale_ap.onnx",
]
# 显卡架构 → 引擎构建资源。和 core/interp 里按 compute capability 选包是同一张表
TRT_ARCHS = ["sm75", "sm80", "sm86", "sm89", "sm100", "sm120"]

# 相对懒人包根目录。目录整个拷(跳过 __pycache__)。
FILES = [
    "VSScript.dll", "portable.vs",
    "python3.dll", "python314.dll", "python314.zip", "python314._pth", "LICENSE.txt",
    "vcruntime140.dll", "vcruntime140_1.dll", "msvcp140.dll", "libffi-8.dll",
    "_asyncio.pyd", "_bz2.pyd", "_ctypes.pyd", "_decimal.pyd", "_elementtree.pyd", "_lzma.pyd",
    "_multiprocessing.pyd", "_overlapped.pyd", "_queue.pyd", "_socket.pyd", "_uuid.pyd",
    "_zoneinfo.pyd", "_zstd.pyd", "pyexpat.pyd", "select.pyd", "unicodedata.pyd",
    "vs-plugins/akarin.dll", "vs-plugins/vsort.dll", "vs-plugins/vsort",
    "vs-plugins/models/drba/distilDRBA_v2_lite_scale_ap_fp16.onnx",
    "vs-plugins/models/rife_v2/rife_v4.6.onnx",
    "Lib/site-packages/vapoursynth.dll", "Lib/site-packages/vapoursynth.pyd",
    "Lib/site-packages/vapoursynth-73.dist-info",
    "Lib/site-packages/k7sfunc", "Lib/site-packages/k7sfunc-1.8.1.dist-info",
]

NOTICE = """LinPlayer 补帧运行时(interp-runtime-{ver})

本包由 scripts/pack-interp-runtime.py 从 hooke007/mpv_PlayKit 发行版 {tag} 中挑选文件重新打包,
文件内容未做修改。各组件许可与源码:

- VapourSynth R73            LGPL-2.1          https://github.com/vapoursynth/vapoursynth
- Python 3.14                PSF License       https://www.python.org/  (见 LICENSE.txt)
- k7sfunc 1.8.1(含 vsmlrt.py) GPL-3.0-or-later  https://github.com/hooke007/k7sfunc  (源码即包内 .py)
- vs-mlrt / vsort             GPL-3.0           https://github.com/AmusementClub/vs-mlrt
- akarin                     LGPL-3.0          https://github.com/AkarinVS/vapoursynth-plugin
- ONNX Runtime               MIT               https://github.com/microsoft/onnxruntime
- DirectML                   MIT               https://github.com/microsoft/DirectML
- RIFE v4.6 模型             MIT               https://github.com/hzwer/Practical-RIFE
- DistilDRBA 模型            MIT               https://github.com/routineLife1/VS-DistilDRBA
- MSVC 运行库                Microsoft Visual C++ Redistributable 许可

上游打包:https://github.com/hooke007/mpv_PlayKit/releases/tag/{tag}
"""

# 固定时间戳:同样的输入打出逐字节相同的 zip,sha256 才能钉。
FIXED_TIME = (2026, 1, 1, 0, 0, 0)


def find_7z():
    for c in (shutil.which("7z"), r"C:\Program Files\7-Zip\7z.exe"):
        if c and os.path.exists(c):
            return c
    sys.exit("找不到 7z:懒人包是 7z 自解压 exe,需要 7-Zip 23.00 以上")


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def walk(root, rel):
    src = os.path.join(root, rel)
    if not os.path.exists(src):
        sys.exit(f"上游包里没有 {rel} —— 上游换了布局,文件表要重新实测")
    if os.path.isfile(src):
        yield rel
        return
    for d, dirs, files in os.walk(src):
        dirs[:] = sorted(x for x in dirs if x != "__pycache__")
        for f in sorted(files):
            yield os.path.relpath(os.path.join(d, f), root).replace("\\", "/")


TRT_NOTICE = """LinPlayer N 卡补帧加速包(interp-trt-{ver})

叠加在 interp-runtime 之上。由 scripts/pack-interp-runtime.py --trt 从 hooke007/mpv_PlayKit
发行版 {tag} 的 vsNV 包中挑选文件重新打包,文件内容未做修改。

- NVIDIA TensorRT 10 / CUDA 13 运行库   NVIDIA 软件许可协议  https://docs.nvidia.com/deeplearning/tensorrt/latest/reference/sla.html
- vs-mlrt / vstrt                      GPL-3.0             https://github.com/AmusementClub/vs-mlrt
- DistilDRBA 模型                      MIT                 https://github.com/routineLife1/VS-DistilDRBA
"""


def write_zip(zpath, root, names, notice):
    with zipfile.ZipFile(zpath, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for n in sorted(names):
            info = zipfile.ZipInfo(n, FIXED_TIME)
            info.compress_type = zipfile.ZIP_DEFLATED
            with open(os.path.join(root, n), "rb") as f:
                z.writestr(info, f.read())
        if notice:
            z.writestr(zipfile.ZipInfo("NOTICE-trt.txt", FIXED_TIME), notice.encode("utf-8"))
    print(f"{zpath}  字节 {os.path.getsize(zpath)}  sha256 {sha256(zpath)}")


def main_trt(vol_dir, out_dir):
    os.makedirs(out_dir, exist_ok=True)
    for name, want in TRT_VOLUMES.items():
        got = sha256(os.path.join(vol_dir, name))
        if got != want:
            sys.exit(f"{name} sha256 对不上:{got}")
    with tempfile.TemporaryDirectory() as tmp:
        first = os.path.join(vol_dir, sorted(TRT_VOLUMES)[0])
        subprocess.run([find_7z(), "x", first, f"-o{tmp}", "-y"], check=True, stdout=subprocess.DEVNULL)
        root = os.path.join(tmp, "mpv-lazy")
        common = [n for rel in TRT_COMMON for n in walk(root, rel)]
        notice = TRT_NOTICE.format(ver=TRT_VERSION, tag=UPSTREAM_TAG)
        write_zip(os.path.join(out_dir, f"linplayer-interp-trt-common-{TRT_VERSION}.zip"), root, common, notice)
        for arch in TRT_ARCHS:
            rel = f"vs-plugins/vsmlrt-cuda/nvinfer_builder_resource_{arch}_10.dll"
            write_zip(os.path.join(out_dir, f"linplayer-interp-trt-{arch}-{TRT_VERSION}.zip"), root,
                      list(walk(root, rel)), None)


def main():
    if len(sys.argv) > 1 and sys.argv[1] == "--trt":
        return main_trt(sys.argv[2], os.path.abspath(sys.argv[3] if len(sys.argv) > 3 else "build/interp-trt"))
    out_dir = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else "build/interp-runtime")
    os.makedirs(out_dir, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        exe = os.path.join(tmp, "lazy.exe")
        url = f"https://github.com/hooke007/mpv_PlayKit/releases/download/{UPSTREAM_TAG}/mpv-lazy-{UPSTREAM_TAG}.exe"
        print("下载", url)
        urllib.request.urlretrieve(url, exe)
        if sha256(exe) != UPSTREAM_SHA256:
            sys.exit(f"上游包 sha256 对不上:{sha256(exe)}")
        subprocess.run([find_7z(), "x", exe, f"-o{tmp}", "-y"], check=True, stdout=subprocess.DEVNULL)
        root = os.path.join(tmp, "mpv-lazy")
        names = sorted({n for rel in FILES for n in walk(root, rel)})
        zpath = os.path.join(out_dir, f"linplayer-interp-runtime-win-x64-{RUNTIME_VERSION}.zip")
        with zipfile.ZipFile(zpath, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
            for n in names:
                info = zipfile.ZipInfo(n, FIXED_TIME)
                info.compress_type = zipfile.ZIP_DEFLATED
                with open(os.path.join(root, n), "rb") as f:
                    z.writestr(info, f.read())
            z.writestr(zipfile.ZipInfo("NOTICE.txt", FIXED_TIME),
                       NOTICE.format(ver=RUNTIME_VERSION, tag=UPSTREAM_TAG).encode("utf-8"))
    print(f"{zpath}\n  文件 {len(names) + 1} 个\n  字节 {os.path.getsize(zpath)}\n  sha256 {sha256(zpath)}")


if __name__ == "__main__":
    main()
