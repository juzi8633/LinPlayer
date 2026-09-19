#!/usr/bin/env bash
# issue #65 的门禁:Go 给 CoreCLR 的 GC 暂停信号打上 SA_ONSTACK 后,GC 一暂停线程就 SIGSEGV
# (见 docs/lessons/player-mpv.md)。先证明探针有牙(关掉修复必须崩),再证明修复有效。
#
#   bash scripts/sigprobe-linux.sh <解压好的包目录>
set -uo pipefail
cd "${1:?用法:sigprobe-linux.sh <解压好的包目录>}"

RC=0; LP_NO_SIGFIX=1 LP_SIGPROBE=1 timeout 60 ./LinPlayer || RC=$?
echo "== 对照组(关掉修复):退出码 $RC"
if [ "$RC" -lt 128 ] || [ "$RC" = 124 ]; then echo "✗ 关掉修复却没崩:探针测不出这个死法"; exit 1; fi

RC=0; LP_SIGPROBE=1 timeout 60 ./LinPlayer || RC=$?
echo "== 修复:退出码 $RC"
[ "$RC" = 0 ] && exit 0
echo "---- 修复后仍崩,取栈 ----"
# 34=CoreCLR 暂停信号,SIGURG=Go 抢占,SIGPIPE 见 smoke-linux-gui.sh —— 都是正常信号,不能停在它们上面
LP_SIGPROBE=1 timeout 120 gdb -batch   -ex 'handle SIG34 nostop noprint pass' -ex 'handle SIGURG nostop noprint pass'   -ex 'handle SIGPIPE nostop noprint pass' -ex run -ex 'bt 30' -ex 'x/3i $pc'   -ex 'p $_siginfo._sifields._sigfault.si_addr' -ex 'info registers rsp' --args ./LinPlayer 2>&1 | tail -n 60
exit 1
