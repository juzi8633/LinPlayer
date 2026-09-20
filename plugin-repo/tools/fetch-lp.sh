#!/usr/bin/env bash
#
# 取官方 `lp` 二进制(CI 校验上架 PR 用)。
#
# ☠ 取的是**主仓库 Release 里的官方构建**,不是 PR 带来的任何东西:
#   这个脚本跑在有写权限的工作流里,跑一行来自 PR 的代码就等于把权限交出去。
#
# 地址由 CI 变量给(仓库文件里不写域名,SPEC 15.6):
#   LP_RELEASE_BASE —— 指向主仓库 Releases 的下载前缀
set -euo pipefail
BASE="${LP_RELEASE_BASE:-}"
if [ -z "$BASE" ]; then
  echo "没有设置 LP_RELEASE_BASE(仓库 Variables 里配),没法取 lp。" >&2
  exit 1
fi
curl -fsSL "$BASE/lp-linux-x64" -o ./lp
chmod +x ./lp
./lp version
