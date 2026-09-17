#!/usr/bin/env bash
# 把补帧滤镜装进一份 mpv 源码树:拷源文件、生成 kernel 头、注册滤镜、加进 meson 源文件表。
#
#   bash third_party/mpv-interp/apply-to-mpv.sh <mpv 源码目录>
#
# 用脚本改而不是 .patch:上游 meson.build / user_filters.c 一动行号,补丁就打不上;
# 这里只认几个稳定的锚点行,锚点找不到就直接报错,不会静默跳过。
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MPV="$1"
[ -f "$MPV/meson.build" ] || { echo "!! $MPV 不是 mpv 源码目录"; exit 1; }

dst="$MPV/video/filter"
cp "$HERE"/vf_lpinterp.c "$HERE"/lpinterp_ocl.c "$HERE"/lpinterp_ocl.h "$HERE"/lpinterp_cl.h "$dst"/
"${PYTHON:-python3}" "$HERE/gen-kernels.py" "$dst/lpinterp_kernels.h"

anchor() {  # anchor 文件 锚点 要插入的行
  grep -qF -- "$2" "$1" || { echo "!! $1 里找不到锚点:$2"; exit 1; }
  grep -qF -- "$3" "$1" && return 0
  "${PYTHON:-python3}" - "$1" "$2" "$3" <<'PY'
import sys
p, a, ins = sys.argv[1:]
s = open(p, encoding="utf-8").read()
i = s.index(a) + len(a)
s = s[:i] + "\n" + ins + s[i:]
open(p, "w", encoding="utf-8").write(s)
PY
}
anchor "$MPV/meson.build" "    'video/filter/vf_format.c'," "    'video/filter/vf_lpinterp.c',
    'video/filter/lpinterp_ocl.c',"
anchor "$MPV/filters/user_filters.c" "    &vf_format," "    &vf_lpinterp,"
anchor "$MPV/filters/user_filters.h" "extern const struct mp_user_filter_entry vf_lavfi;" "extern const struct mp_user_filter_entry vf_lpinterp;"
echo "补帧滤镜已装进 $MPV"
