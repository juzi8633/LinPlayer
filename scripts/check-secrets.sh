#!/usr/bin/env bash
#
# 红线门禁:已跟踪文件里不许出现真实 IP / 域名 / 账号 / 密钥 / token。
#
# 红线原文(全局规则):
#   「任何 IP、域名、线路/中转地址、端口、账号、密码、密钥、token,
#     都不得出现在任何提交里。」
#
# ☠ 这道门禁**只管当前版本**。git 历史里已经有的东西它看不见,也修不掉 ——
#   历史要么改写要么删库重建,那是破坏性操作,由项目负责人决定。
#   现状与整改路径见 docs/lessons/red-line-audit.md。
#
# 豁免名单在 scripts/secrets-allow.txt:**每一行都要写理由**。
# 没理由的豁免等于把门禁关掉,而且下一个人看不出来是故意还是忘了。
#
#   bash scripts/check-secrets.sh                  # 扫已跟踪文件
#   bash scripts/check-secrets.sh --staged         # 只扫暂存区(pre-commit 用)
#   bash scripts/check-secrets.sh --write-baseline # 重写欠账清单(裁决之后才该跑)
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

ALLOW="$ROOT/scripts/secrets-allow.txt"
MODE="${1:-}"

# ☠ 要 `-c core.quotePath=false`:默认输出会把非 ASCII 文件名转义成
#   `"core/.../emby.counts.01-å¿....json"`,扫描器按字面路径找不到它们 ——
#   于是 18 个中文名文件**从来没被扫过**,而门禁一直报绿。
#   这是「读不到的文件要报出来」那条加上之后当场现形的。
if [ "$MODE" = "--staged" ]; then
  FILES=$(git -c core.quotePath=false diff --cached --name-only --diff-filter=ACM)
else
  FILES=$(git -c core.quotePath=false ls-files)
fi

# 跳过哪些后缀由 check-secrets.py 说了算 —— 这里原来还有一份,而且**含 `ts`**,
# 和 .py 里「不许把 ts 放进来」的注释正相反。两份规则迟早对不上,而且这一份
# 从来没被用过(死变量),留着只会让下一个人以为改它有用。
BASE="$ROOT/scripts/secrets-baseline.txt"
if [ "$MODE" = "--write-baseline" ]; then
  python "$ROOT/scripts/check-secrets.py" "$ALLOW" "$BASE" --write-baseline <<< "$FILES"
  exit $?
fi
python "$ROOT/scripts/check-secrets.py" "$ALLOW" "$BASE" <<< "$FILES"
rc=$?

if [ $rc -ne 0 ]; then
  echo
  echo "红线门禁:不通过。"
  echo "  · 真要保留的,加进 scripts/secrets-allow.txt 并**写清理由**;"
  echo "  · 属于自己基础设施的(服务器、线路、账号),一律抽进不进版本控制的配置文件。"
fi
exit $rc
