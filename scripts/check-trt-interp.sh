#!/usr/bin/env bash
# N 卡加速(TensorRT)补帧的**画面**自检。只看速度会假绿:RIFE v4.6 在 TRT 下补出满屏黑线,
# 那时量出来的「丢 0 帧」是真的,只是补的是垃圾(2026-09-18)。要本机有 N 卡 + 装好的运行时。
#
#   bash scripts/check-trt-interp.sh <运行时目录> <片子> [algo=rife] [起始帧=100]
#   LP_H=720 走 720 那档引擎(3/4 倍的实际路径);默认 1080
#
# 判据:下限 =「两原帧互比 PSNR − 3dB」(封顶 30,近乎静止的两帧互比 50+ dB,模型的色彩往返就够掉到 35)。
# RIFE 原帧原样放行、补的是正中间:离前后**都**要过线。黑线那版 9~16 dB,好的 22~47。
# DRBA 会重排时间轴,输出帧贴着某一侧:离**某一侧**过线即可。
set -euo pipefail
rt=$1 src=$2 algo=${3:-rife} start=${4:-100}
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
tmpw=$(cd "$tmp" && pwd -W 2>/dev/null || pwd) # 给 VapourSynth 里的 Python 用的 Windows 路径
here=$(cd "$(dirname "$0")/.." && pwd -W 2>/dev/null || pwd)

# 运行时里没有解码插件:ffmpeg 先解成裸 YUV420P8,脚本里按帧拷进 BlankClip
dim() { ffprobe -v error -select_streams v:0 -show_entries "stream=$1" -of default=nw=1:nk=1 "$src" | tr -dc 0-9; }
w=$(dim width)
h=$(dim height)
ffmpeg -v error -y -i "$src" -vf "select='gte(n,$start)'" -vsync 0 -frames:v 12 -pix_fmt yuv420p -f rawvideo "$tmp/in.yuv"

cat >"$tmp/t.vpy" <<EOF
import ctypes
import vapoursynth as vs
from vapoursynth import core
W, H, N = $w, $h, 12
raw = open(r"$tmpw/in.yuv", "rb").read()
fs = W * H * 3 // 2
def fill(n, f):
    f = f.copy()
    o = n * fs
    for p, (pw, ph) in enumerate([(W, H), (W // 2, H // 2), (W // 2, H // 2)]):
        dst, st = f.get_write_ptr(p).value, f.get_stride(p)
        for y in range(ph):
            ctypes.memmove(dst + y * st, raw[o + y * pw:o + (y + 1) * pw], pw)
        o += pw * ph
    return f
blank = core.std.BlankClip(width=W, height=H, format=vs.YUV420P8, length=N, fpsnum=24000, fpsden=1001)
g = {"video_in": core.std.ModifyFrame(blank, blank, fill), "container_fps": 24000 / 1001,
     "user_data": "algo=$algo;x=2;be=trt;h=${LP_H:-1080}"}
exec(open(r"$here/core/interp/files/interp.vpy", encoding="utf-8").read(), g)
EOF
"$rt/VSPipe.exe" -c y4m "$tmp/t.vpy" - 2>"$tmp/err" | ffmpeg -v error -y -f yuv4mpegpipe -i - -vsync 0 "$tmp/o_%02d.png" \
  || { tail -5 "$tmp/err"; exit 1; }

psnr() { ffmpeg -i "$1" -i "$2" -lavfi psnr -f null - 2>&1 | grep -o 'average:[0-9.inf]*' | cut -d: -f2 | sed 's/inf/99/'; }
fail=0
# 输出第 1、3、5… 帧(ffmpeg 从 01 编号)是原帧,偶数号是补出来的
for i in 2 6 10 14 18; do
  a=$(printf '%s/o_%02d.png' "$tmp" $((i - 1))) m=$(printf '%s/o_%02d.png' "$tmp" "$i") b=$(printf '%s/o_%02d.png' "$tmp" $((i + 1)))
  ab=$(psnr "$a" "$b") am=$(psnr "$a" "$m") mb=$(psnr "$m" "$b")
  ok=$(awk -v ab="$ab" -v am="$am" -v mb="$mb" -v a="$algo" 'BEGIN{
    x = (a == "rife") == (am < mb) ? am : mb; lim = (ab < 30 ? ab : 30) - 3; print (x >= lim) ? "ok" : "坏" }')
  printf '  第 %2d 帧:离前 %5.1f / 离后 %5.1f dB(两原帧互比 %5.1f)%s\n' "$i" "$am" "$mb" "$ab" "$ok"
  [ "$ok" = ok ] || fail=1
done
[ $fail = 0 ] && echo "通过" || { echo "失败:补出来的帧不像中间帧(黑线 / 花屏)"; exit 1; }
