#!/usr/bin/env bash
#
# 把 @linplayer/plugin-sdk 与 @linplayer/cli 发到 pnpm(npm registry)。
#
# ☠ **默认只演练不发布**。真发要显式加 --yes:
#   发布是不可撤回的外发操作(npm 的 unpublish 有 72 小时窗口且会留痕),
#   而这个脚本很可能被人手滑跑到。
#
#   bash scripts/publish-sdk.sh          # 演练:打包、看内容、不发
#   bash scripts/publish-sdk.sh --yes    # 真发
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO="$ROOT/plugin-repo"
GO=""
[ "${1:-}" = "--yes" ] && GO=1

echo "== 1/4 同步接口定义源 =="
# .d.ts 是接口的唯一定义源,随应用发版同步(D410)。
# 不同步就发的话,发出去的类型是上一版的 —— 插件作者按它写,运行时报「没有这个方法」。
bash "$ROOT/scripts/sync-plugin-repo.sh" >/dev/null
echo "  index.d.ts $(wc -l < "$REPO/packages/sdk/index.d.ts") 行,schema $(ls "$REPO/packages/sdk/schema" | wc -l) 份"

echo "== 2/4 版本对齐 =="
# 包版本跟应用大版本走:2.0.0 的应用配 2.0.0 的 SDK。
APPVER="$(cd "$ROOT/core" && go run ./cmd/lp version 2>/dev/null | tr -d '\r' || true)"
echo "  应用版本:${APPVER:-(取不到,用包里写的)}"
for p in sdk cli; do
  # ☠ 不能把 MSYS 的 /d/... 路径丢给 node:它解析不了,报的还是 MODULE_NOT_FOUND,
  #   看起来像「包不存在」。进到目录里用相对路径。
  v="$(cd "$REPO/packages/$p" && node -p "require('./package.json').version")"
  echo "  @linplayer/$p:$v"
done

echo "== 3/4 打包看内容 =="
# `pnpm pack` 出 tgz 并列出会发出去的文件。**发之前一定要看这一眼** ——
# files 字段写漏的话会把整个目录发出去(含 .env、私钥、node_modules)。
for p in sdk cli; do
  ( cd "$REPO/packages/$p" && pnpm pack --pack-destination "$ROOT/build" >/dev/null )
  # tgz 的名字来自**包名**不是目录名(@linplayer/plugin-sdk → linplayer-plugin-sdk-x.y.z.tgz)。
  # 按目录名去找的话 sdk 那个永远找不到,而脚本会报「没打出包」。
  pkg="$(cd "$REPO/packages/$p" && node -p "require('./package.json').name.replace('@','').replace('/','-')")"
  tgz="$(ls -t "$ROOT/build/$pkg"-*.tgz 2>/dev/null | head -1 || true)"
  [ -n "$tgz" ] || { echo "  !! $p 没打出包"; exit 1; }
  echo "  --- @linplayer/$p 会发出去的文件:"
  tar -tzf "$tgz" | sed 's/^/      /'
done

echo "== 4/4 发布 =="
if [ -z "$GO" ]; then
  echo "  演练结束,**什么都没发**。确认上面的文件清单没问题之后:"
  echo "    bash scripts/publish-sdk.sh --yes"
  exit 0
fi
for p in sdk cli; do
  ( cd "$REPO/packages/$p" && pnpm publish --access public --no-git-checks )
done
echo "发布完成。"
