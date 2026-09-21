#!/usr/bin/env bash
#
# 把官方插件的 .lpplugin 发到官方插件仓库的 Releases。
# 索引里的下载地址指向**官方副本**(SPEC 15.2 第 4 条)—— 发完那些地址才不是 404。
#
# ☠ 全部插件共用**一个** Release(D572)。一个插件一条 Release 的话,九个插件就是
#   九条,往后每发一版再加一条 —— 而用户在 Release 页上找的是「应用的新版本」。
#   资产名带版本号,所以历史版本照样各有各的地址(D450)。
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
TAG="${PLUGIN_RELEASE_TAG:-plugins}"
PACK="$ROOT/build/lpplugin"
DRPY="$ROOT/plugins/tvbox/assets/drpy"
STAGE="$ROOT/build/release-assets"
GO=""
[ "${1:-}" = "--yes" ] && GO=1

[ -n "$SLUG" ] || { echo "没给 PLUGIN_REPO_SLUG(形如 owner/repo)。" >&2; exit 1; }
[ -d "$PACK" ] || { echo "还没打包。先跑:for d in plugins/*/; do (cd core && go run ./cmd/lp pack \"../\$d\" -o \"../build/lpplugin/\$(basename \$d).lpplugin\"); done" >&2; exit 1; }

rm -rf "$STAGE"
mkdir -p "$STAGE"

# 资产名要和 scripts/gen-registry.mjs 里的 assetUrl() **完全一致** ——
# 对不上的话索引里的地址指向一个不存在的资产,而用户看到的是「下载失败」。
fail=0
for f in "$PACK"/*.lpplugin; do
  name="$(basename "$f" .lpplugin)"
  ver="$(python -c "
import json,io,sys
print(json.load(io.open(r'$PYROOT/plugins/$name/manifest.json',encoding='utf-8'))['version'])")"
  asset="$name-$ver.lpplugin"

  want="$(python -c "
import json,io
idx=json.load(io.open(r'$PYROOT/plugin-repo/registry/index.json',encoding='utf-8'))
for p in idx['plugins']:
    for v in p['versions']:
        if v['url'].endswith('/$TAG/$asset'):
            print(v['url']); break")"
  if [ -z "$want" ]; then
    echo "  ✗ $name:索引里没有指向 $TAG/$asset 的地址 —— 标签或资产名对不上"
    fail=$((fail + 1))
    continue
  fi
  # ☠ `gh release upload 路径#名字` 里的 `#` 设的是**显示标签**,不是资产文件名 ——
  #   照那样传上去资产叫 devtools.lpplugin,而索引里的地址找的是 devtools-0.1.0.lpplugin,
  #   于是「发过了」而下载 404。资产名只能靠**文件本身的名字**定,所以先 cp 成目标名。
  cp "$f" "$STAGE/$asset"
  echo "  $asset($(stat -c%s "$f") 字节)"
done

# drpy 引擎同一条 Release 带走(D571 D572):它不入库(可执行代码里有第三方服务的
# 域名与端口、注释里有私网 IP 和样例私钥),构建期按 sha256 现拉。
# 放在这儿而不是上游,是为了不让构建依赖一个别人的仓库。
if [ -d "$DRPY" ]; then
  ( cd "$DRPY" && tar -czf "$STAGE/drpy-engine.tar.gz" drpy2.js lib )
  echo "  drpy-engine.tar.gz($(stat -c%s "$STAGE/drpy-engine.tar.gz") 字节)"
fi

[ "$fail" -eq 0 ] || { echo "有 $fail 个对不上,**没发**。"; exit 1; }

if [ -z "$GO" ]; then
  echo
  echo "演练结束,**什么都没发**。上面这些会作为资产进 Release \`$TAG\`。确认之后:"
  echo "  PLUGIN_REPO_SLUG=$SLUG bash scripts/release-plugins.sh --yes"
  exit 0
fi

echo
echo "== 发到 Release \`$TAG\` =="
if gh release view "$TAG" --repo "$SLUG" >/dev/null 2>&1; then
  gh release upload "$TAG" "$STAGE"/* --repo "$SLUG" --clobber
  echo "  ✓ 已存在,换了资产"
else
  gh release create "$TAG" "$STAGE"/* --repo "$SLUG" \
    --title "官方插件" \
    --notes "LinPlayer 官方插件的下载源。索引在 \`registry/index.json\`,应用里的「插件商店」读的就是它 —— 一般不用手动下。

资产名带版本号,老版本不会被新版顶掉。\`drpy-engine.tar.gz\` 是 TVBox 插件用的 JS 解析引擎,构建期按 sha256 取用。" >/dev/null
  echo "  ✓ 建好了"
fi
rm -rf "$STAGE"

# 发完照着索引里的地址真下一遍。脚本自己说「发成功了」不算数 ——
# 2026-09-21 就是这样:`gh release upload 路径#名字` 的 `#` 是标签不是文件名,
# 九个资产全传成了另一个名字,九条下载地址全 404,而脚本九行全是 ✓。
echo
echo "== 按索引里的地址回验 =="
bad=0
while read -r url want; do
  want="${want%$'\r'}"   # Windows 上 python 吐的是 \r\n,不剥的话数一样也比不相等
  got="$(curl -sL -o /dev/null -w '%{size_download}' "$url")"
  if [ "$got" = "$want" ]; then
    echo "  ✓ $(basename "$url")  $got 字节"
  else
    echo "  ✗ $(basename "$url")  下到 $got 字节,索引写 $want"
    bad=$((bad + 1))
  fi
done < <(python -c "
import json,io
idx=json.load(io.open(r'$PYROOT/plugin-repo/registry/index.json',encoding='utf-8'))
for p in idx['plugins']:
    for v in p['versions']:
        print(v['url'], v['size'])")
[ "$bad" -eq 0 ] || { echo "有 $bad 个地址下不到 / 长度对不上 —— 用户点下载会失败。"; exit 1; }
