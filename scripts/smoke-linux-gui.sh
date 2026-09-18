#!/usr/bin/env bash
# Linux 端开窗冒烟(TODO L11)。
#
# 为什么单独一条:pack-linux.sh 里那两条命令行冒烟只证明**壳加载得起核心层**,
# 够不到 Avalonia / X11 / Skia 那一整段 —— 用户 2026-09-18 报的
# 「Ubuntu 26.04 一打开就 SIGSEGV」(issue #65)死的正是命令行走不到的地方。
# 判据不是「打印了什么」,而是「到点还活着」:起不来的样子是秒退或秒崩,不是报错。
#
#   bash scripts/smoke-linux-gui.sh <解压好的包目录> [秒数]
set -euo pipefail
STAGE="${1:?用法:smoke-linux-gui.sh <解压好的包目录> [秒数]}"
SECS="${2:-20}"
LOG="$(mktemp)"

if ! command -v xvfb-run >/dev/null 2>&1; then
  echo "这台机器没有 xvfb —— 装 xvfb 再跑(CI 上必须有)"
  exit 1
fi

# 色深必须钉 24。xvfb 默认给 8 位 visual,Skia 在那上面的死法和真机对不上,
# 冒烟会变成「在测一个没人用的配置」。
X="-screen 0 1280x800x24"
# CI 出的包编进了上报地址:冒烟里被 timeout 掐掉、故意崩,都不能发到开发者的 TG / Sentry
export LP_NO_REPORT=1

RC=0
# LP_PERF=1 让 Perf.Log 往 stdout 打里程碑。光判「还活着」会假绿:
# 窗口起不来但进程挂在那儿不退,照样撑满 20 秒。
( cd "$STAGE" && LP_PERF=1 xvfb-run -a -s "$X" timeout "$SECS" ./LinPlayer >"$LOG" 2>&1 ) || RC=$?

# timeout 掐掉的退出码是 124 —— 它一直活着,这正是要的
if [ "$RC" = 124 ]; then
  if grep -q '框架初始化完成' "$LOG"; then
    echo "SMOKE 开窗 ✓ 撑满 ${SECS}s,框架初始化走完了"
    rm -f "$LOG"
    exit 0
  fi
  echo "SMOKE 开窗 ✗ 活着但没走到「框架初始化完成」—— 卡在起窗口那一步"
  tail -n 40 "$LOG" || true
  rm -f "$LOG"
  exit 1
fi

echo "SMOKE 开窗 ✗ 退出码 $RC(该是 124 = 活到被掐)"
echo "---- 它自己说了什么 ----"
tail -n 40 "$LOG" || true
# ≥128 是被信号杀的(139=SIGSEGV / 134=SIGABRT)。这种死法日志里一个字都不会留,
# 只有栈说得出死在哪一层 —— 再跑一次,这次挂在 gdb 底下。
if [ "$RC" -ge 128 ] && command -v gdb >/dev/null 2>&1; then
  echo "---- 信号杀的,取栈 ----"
  # SIGPIPE 必须放过:核心层(Go)往断开的连接写时会收到它,正常情况下被忽略;
  # 但 gdb 默认在它上面停,栈停在半路,真正的崩溃根本没走到(issue #65 用户贴的就是这个)
  ( cd "$STAGE" && xvfb-run -a -s "$X" \
      timeout 120 gdb -batch -ex 'handle SIGPIPE nostop noprint pass' -ex run -ex 'bt 40' \
      -ex 'info sharedlibrary' --args ./LinPlayer ) 2>&1 | tail -n 80 || true
fi
rm -f "$LOG"
exit 1
