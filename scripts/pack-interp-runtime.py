#!/usr/bin/env python3
"""打补帧运行时包(Windows x64):从上游 mpv_PlayKit 懒人包里挑出补帧要的文件,打成可复现的 zip。

    python scripts/pack-interp-runtime.py [输出目录]      # 需要 7z 在 PATH 或装在默认位置

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


def main():
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
