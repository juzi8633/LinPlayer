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

if [ "$MODE" = "--staged" ]; then
  FILES=$(git diff --cached --name-only --diff-filter=ACM)
else
  FILES=$(git ls-files)
fi

# 二进制与产物不扫:它们里的「匹配」全是巧合,而且输出没法看
SKIP_EXT='\.(png|jpg|jpeg|gif|webp|ico|svg|ttf|otf|woff2?|zip|gz|xz|apk|aab|dll|so|dylib|exe|bin|jar|lock|sum|pdf|mp4|mkv|ts)$'

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
