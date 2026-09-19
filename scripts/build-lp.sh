#!/usr/bin/env bash
# 构建插件开发者 CLI `lp`(SPEC 16.3)。版本号 = 应用版本号(D411),从仓库根 VERSION 注入。
#
#   bash scripts/build-lp.sh              # 本机平台 → build/lp/lp(.exe)
#   bash scripts/build-lp.sh all          # windows/linux/darwin × amd64/arm64,给 GitHub Releases 与 pnpm 包用
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/scripts/env.sh" >/dev/null
VER="$(tr -d '[:space:]' < "$ROOT/VERSION")"
OUT="$ROOT/build/lp"
mkdir -p "$OUT"

build() { # goos goarch
  local ext="" name
  [ "$1" = windows ] && ext=".exe"
  name="lp$ext"
  [ $# -eq 2 ] && [ "${ALL:-0}" = 1 ] && name="lp-$1-$2$ext"
  ( cd "$ROOT/core" && CGO_ENABLED=0 GOOS="$1" GOARCH="$2" \
      go build -trimpath -ldflags "-s -w -X main.version=$VER" -o "$OUT/$name" ./cmd/lp )
  echo "  ✓ $OUT/$name"
}

if [ "${1:-}" = all ]; then
  ALL=1
  for os in windows linux darwin; do for arch in amd64 arm64; do build "$os" "$arch"; done; done
else
  build "$(cd "$ROOT/core" && go env GOOS)" "$(cd "$ROOT/core" && go env GOARCH)"
fi
