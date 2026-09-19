#!/usr/bin/env bash
# issue #65 的门禁:进过 Go 的线程被 CoreCLR 的 GC 暂停信号打中会 SIGSEGV(见 docs/lessons/player-mpv.md)。
# 先证明探针有牙(关掉修复必须崩),再证明修复有效。
#
#   bash scripts/sigprobe-linux.sh <解压好的包目录>
set -uo pipefail
cd "${1:?用法:sigprobe-linux.sh <解压好的包目录>}"

run() { # $1=说明,其余=环境变量;回显退出码
  local rc=0
  env "${@:2}" timeout 60 ./LinPlayer || rc=$?
  echo "== $1:退出码 $rc"
  return $rc
}

FAIL=0
run "对照组(关掉修复,打信号)" LP_NO_ALTSTACK=1 LP_SIGPROBE=1; RC=$?
if [ "$RC" -lt 128 ] || [ "$RC" = 124 ]; then echo "✗ 关掉修复却没崩:探针测不出这个死法"; FAIL=1; fi
run "修复(只有 GC 压力)" LP_SIGPROBE=gc || FAIL=1
run "修复(打信号)" LP_SIGPROBE=1 || {
  FAIL=1
  echo "---- 修复后仍崩,取栈 ----"
  # 34=CoreCLR 暂停信号,SIGURG=Go 抢占,SIGPIPE 见 smoke-linux-gui.sh —— 都是正常信号,不能停在它们上面
  LP_SIGPROBE=1 timeout 120 gdb -batch \
    -ex 'handle SIG34 nostop noprint pass' -ex 'handle SIGURG nostop noprint pass' \
    -ex 'handle SIGPIPE nostop noprint pass' -ex run -ex 'bt 30' -ex 'x/3i $pc' \
    -ex 'p $_siginfo._sifields._sigfault.si_addr' -ex 'info registers rsp' --args ./LinPlayer 2>&1 | tail -n 60
}
exit $FAIL
