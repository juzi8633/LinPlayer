#!/usr/bin/env bash
#
# 把官方插件的 .lpplugin 发到官方插件仓库的 Releases。
# 索引里的下载地址指向**官方副本**(SPEC 15.2 第 4 条)—— 发完那些地址才不是 404。
#
# ☠ **默认只演练**。真发要显式加 --yes:发 Release 是不可撤回的外发操作。
#
#   PLUGIN_REPO_SLUG=owner/repo bash scripts/release-plugins.sh
#   PLUGIN_REPO_SLUG=owner/repo bash scripts/release-plugins.sh --yes
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# ☠ python 在 Windows 上解析不了 MSYS 的 /d/... 路径,报的是 FileNotFoundError,
#   看起来像文件真的不在。传给 python 的路径一律用转好的这一个。
PYROOT="$(cd "$ROOT" && pwd -W 2>/dev/null || echo "$ROOT")"
SLUG="${PLUGIN_REPO_SLUG:-}"
PACK="$ROOT/build/lpplugin"
GO=""
[ "${1:-}" = "--yes" ] && GO=1

[ -n "$SLUG" ] || { echo "没给 PLUGIN_REPO_SLUG(形如 owner/repo)。" >&2; exit 1; }
[ -d "$PACK" ] || { echo "还没打包。先跑:for d in plugins/*/; do (cd core && go run ./cmd/lp pack \"../\$d\" -o \"../build/lpplugin/\$(basename \$d).lpplugin\"); done" >&2; exit 1; }

# 标签与资产名要和 scripts/gen-registry.mjs 里的 assetUrl() **完全一致** ——
# 对不上的话索引里的地址指向一个不存在的资产,而用户看到的是「下载失败」。
fail=0
for f in "$PACK"/*.lpplugin; do
  name="$(basename "$f" .lpplugin)"
  ver="$(python -c "
import json,io,sys
print(json.load(io.open(r'$PYROOT/plugins/$name/manifest.json',encoding='utf-8'))['version'])")"
  tag="$name-v$ver"
  asset="$name-$ver.lpplugin"

  want="$(python -c "
import json,io
idx=json.load(io.open(r'$PYROOT/plugin-repo/registry/index.json',encoding='utf-8'))
for p in idx['plugins']:
    for v in p['versions']:
        if v['url'].endswith('$asset'):
            print(v['url']); break")"
  if [ -z "$want" ]; then
    echo "  ✗ $name:索引里没有指向 $asset 的地址 —— 标签或资产名对不上"
    fail=$((fail + 1))
    continue
  fi

  if [ -z "$GO" ]; then
    echo "  会发:$tag  ←  $asset($(stat -c%s "$f") 字节)"
    continue
  fi
  if gh release view "$tag" --repo "$SLUG" >/dev/null 2>&1; then
    echo "  $tag 已存在,只补资产"
    gh release upload "$tag" "$f#$asset" --repo "$SLUG" --clobber
  else
    cp "$f" "$PACK/$asset"
    gh release create "$tag" "$PACK/$asset" --repo "$SLUG" \
      --title "$name $ver" \
      --notes "LinPlayer 官方插件 \`$name\` $ver。索引:registry/index.json。" >/dev/null
    rm -f "$PACK/$asset"
    echo "  ✓ $tag"
  fi
done

[ "$fail" -eq 0 ] || { echo "有 $fail 个对不上,**没发**。"; exit 1; }
if [ -z "$GO" ]; then
  echo
  echo "演练结束,**什么都没发**。确认上面的标签与资产名之后:"
  echo "  PLUGIN_REPO_SLUG=$SLUG bash scripts/release-plugins.sh --yes"
fi
