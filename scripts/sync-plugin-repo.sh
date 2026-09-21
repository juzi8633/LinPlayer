#!/usr/bin/env bash
#
# 把主仓库里该进官方插件仓库的东西同步进 plugin-repo/。
#
# ☠ 同步的方向是**单向**的:主仓库是正本,plugin-repo/ 是待发布的副本。
#   反过来改 plugin-repo/ 里的插件源码,下次同步会被盖掉。
#
# 官方仓库本身(D149:清空后整仓重建)是破坏性操作,**这个脚本不碰远端** ——
# 它只准备内容。推不推、什么时候推,由项目负责人决定。
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DST="$ROOT/plugin-repo"

echo "== 1/3 SDK 类型与 schema =="
# .d.ts 随应用发版同步进来(D410):它是**接口的唯一定义源**,
# 两处各留一份的话「文档写了、运行时没有」这种事就没人发现得了
cp "$ROOT/docs/plugin-system/api/plugin-sdk.d.ts" "$DST/packages/sdk/index.d.ts"
mkdir -p "$DST/packages/sdk/schema"
for f in manifest.schema.json index.schema.json theme-json.schema.json; do
  cp "$ROOT/docs/plugin-system/api/$f" "$DST/packages/sdk/schema/$f"
done
# 许可证正本在主仓库根:plugin-repo 那份也是同步来的,不另维护一份
cp "$ROOT/LICENSE" "$DST/LICENSE"

# ☠ 许可证正文要**进包**。npm 只会自动收包目录下的 LICENSE,而它在仓库根 ——
#   发出去的 tgz 里没有许可证正文,对 AGPL 这种要求随分发提供全文的许可证是硬伤。
#   这两份是生成物(plugin-repo/.gitignore 里忽略了),正本在仓库根。
for p in sdk cli; do
  cp "$DST/LICENSE" "$DST/packages/$p/LICENSE"
done

echo "== 2/3 官方插件源码 =="
# plugins/ 在 plugin-repo 里不留副本(见它的 README),推之前才同步过去
rm -rf "$DST/plugins"
mkdir -p "$DST/plugins"
for d in "$ROOT"/plugins/*/; do
  name="$(basename "$d")"
  mkdir -p "$DST/plugins/$name"
  # dist/ 是构建产物,不进仓库
  ( cd "$d" && tar --exclude=dist --exclude=node_modules -cf - . ) | ( cd "$DST/plugins/$name" && tar -xf - )
done
echo "  同步了 $(ls -1 "$DST/plugins" | wc -l) 个官方插件"

echo "== 3/3 示例 =="
# 每个扩展点一个几十行的最小示例(D151)。示例的正本也在主仓库。
if [ -d "$ROOT/docs/plugin-system/api/examples" ]; then
  rm -rf "$DST/examples"
  cp -r "$ROOT/docs/plugin-system/api/examples" "$DST/examples"
fi

echo
echo "同步完成。注意:"
echo "  · 这只是准备内容,**没有碰任何远端**;"
echo "  · 推之前跑一遍 bash scripts/check-secrets.sh;"
echo "  · 官方仓库要清空重建(D149),那一步要项目负责人点头。"
