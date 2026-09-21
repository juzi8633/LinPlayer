#!/usr/bin/env bash
# issue #65 的门禁:Go 给 CoreCLR 的 GC 暂停信号打上 SA_ONSTACK 后,GC 一暂停线程就 SIGSEGV
# (见 docs/lessons/player-mpv.md)。先证明探针有牙(关掉修复必须崩),再证明修复有效。
#
#   bash scripts/sigprobe-linux.sh <解压好的包目录>
set -uo pipefail
cd "${1:?用法:sigprobe-linux.sh <解压好的包目录>}"

# ☠ 对照组是**概率性**的:那个死法要 CoreCLR 的 GC 正好在某个线程踩在 Go 的信号栈上时
#   暂停它。实测跑 15 秒、671 轮 GC 也有整场躲过去的时候(2026-09-21 CI 红了一次)。
#   所以给它三次机会 —— 崩一次就证明探针有牙,三次都没崩才是真的测不出来。
#   **不许改成「没崩就算过」**:那样这一关就只是在跑程序,什么都没验。
RC=0
for i in 1 2 3; do
  RC=0; LP_NO_SIGFIX=1 LP_SIGPROBE=1 timeout 60 ./LinPlayer || RC=$?
  echo "== 对照组(关掉修复)第 $i 次:退出码 $RC"
  { [ "$RC" -ge 128 ] && [ "$RC" != 124 ]; } && break
done
if [ "$RC" -lt 128 ] || [ "$RC" = 124 ]; then echo "✗ 关掉修复连着三次都没崩:探针测不出这个死法"; exit 1; fi

RC=0; LP_SIGPROBE=1 timeout 60 ./LinPlayer || RC=$?
echo "== 修复:退出码 $RC"
[ "$RC" = 0 ] && exit 0
echo "---- 修复后仍崩,取栈 ----"
# 34=CoreCLR 暂停信号,SIGURG=Go 抢占,SIGPIPE 见 smoke-linux-gui.sh —— 都是正常信号,不能停在它们上面
LP_SIGPROBE=1 timeout 120 gdb -batch   -ex 'handle SIG34 nostop noprint pass' -ex 'handle SIGURG nostop noprint pass'   -ex 'handle SIGPIPE nostop noprint pass' -ex run -ex 'bt 30' -ex 'x/3i $pc'   -ex 'p $_siginfo._sifields._sigfault.si_addr' -ex 'info registers rsp' --args ./LinPlayer 2>&1 | tail -n 60
exit 1
