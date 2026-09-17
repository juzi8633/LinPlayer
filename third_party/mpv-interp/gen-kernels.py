#!/usr/bin/env python3
"""把 lpinterp.cl 转成 C 字符串头文件 lpinterp_kernels.h。

kernel 编进 .so 而不是运行时读盘:上游 HopperRender 从 $HOME/mpv-build/... 读 .cl,
安卓上根本没有那个路径。生成物不入库,apply-to-mpv.sh 和 bench 构建时现生成。
"""
import sys, os
here = os.path.dirname(os.path.abspath(__file__))
src = open(os.path.join(here, "lpinterp.cl"), encoding="utf-8").read()
out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(here, "lpinterp_kernels.h")
lines = ["// 生成物,勿手改:python gen-kernels.py", "#pragma once", "static const char LPI_KERNEL_SRC[] ="]
for ln in src.splitlines():
    # 注释里的中文对 OpenCL 编译器无用,有的实现遇到非 ASCII 会报错 —— 整行注释直接去掉
    if ln.strip().startswith("//"):
        continue
    ln = ln.split("//")[0].rstrip()
    lines.append('    "' + ln.replace("\\", "\\\\").replace('"', '\\"') + '\\n"')
lines[-1] += ";"
open(out, "w", encoding="utf-8").write("\n".join(lines) + "\n")
