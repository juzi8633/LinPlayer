#!/usr/bin/env bash
#
# 发版前的一条命令:门禁 → 出三端包 → 生成更新说明 → 打 tag。
#
# ☠ **它不推**。推是外发操作,由人来做(见 docs/plugin-system/RELEASE-RUNBOOK.md)。
#   把 push 放进来的话,这个脚本就变成了「跑一下试试」会把版本发出去的东西。
#
#   bash scripts/release.sh            # 全跑一遍,最后停在「可以推了」
#   bash scripts/release.sh --no-pack  # 只跑门禁与说明(出包很慢,改文档时用)
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
source "$ROOT/scripts/env.sh" >/dev/null 2>&1 || true

VER="$(tr -d '[:space:]' < "$ROOT/VERSION")"
PACK=1
[ "${1:-}" = "--no-pack" ] && PACK=""

step() { echo; echo "===== $* ====="; }
fail=0
run() { "$@" || fail=$((fail + 1)); }

echo "准备发 v$VER"

step "1/6 红线"
run bash "$ROOT/scripts/check-secrets.sh"

step "2/6 核心层门禁"
run bash "$ROOT/scripts/check-core.sh"

step "3/6 绑定层门禁"
run bash "$ROOT/scripts/check-bindings.sh"

step "4/6 风格门禁"
run bash "$ROOT/scripts/check-style.sh"

if [ -n "$PACK" ]; then
  step "5/6 出包(Windows 先,安卓后)"
  # 三端都要出:报「编译通过」就收工是本仓最常犯的错(CLAUDE.md §7)
  run bash "$ROOT/scripts/pack-win.sh"
  run bash "$ROOT/scripts/pack-android.sh"
else
  step "5/6 出包(--no-pack,跳过)"
fi

step "6/6 更新说明"
run bash "$ROOT/scripts/release-notes.sh" "$ROOT/build/notes-$VER.md"

echo
if [ $fail -ne 0 ]; then
  echo "有 $fail 关没过 —— **不要推**。"
  exit $fail
fi

echo "全过。产物:"
ls -1 "$ROOT/build/pack"/*.zip "$ROOT/build/android"/*.apk 2>/dev/null | sed 's/^/  /' || true
echo "  $ROOT/build/notes-$VER.md"
echo
echo "接下来(人来做,见 docs/plugin-system/RELEASE-RUNBOOK.md):"
echo "  git tag v$VER"
echo "  git push origin main      # 触发自动预发布"
echo "  git push origin v$VER     # 发正式版"
echo
echo "☠ 这个脚本没有推任何东西。"
