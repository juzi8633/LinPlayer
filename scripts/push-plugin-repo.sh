#!/usr/bin/env bash
#
# 把 plugin-repo/ 的内容推到官方插件仓库(D149:清空后整仓重建)。
#
# ☠ **默认只演练**。真推要显式加 --yes。
#   这是不可逆的外发操作:远端现有的文件会被一次提交换掉。
#   负责人 2026-09-21 选的是「**保留历史**,一次普通提交换内容」——
#   不 force-push,旧东西在 git 历史里还找得回来。
#
#   bash scripts/push-plugin-repo.sh          # 演练:准备好树、列出会变什么,不推
#   bash scripts/push-plugin-repo.sh --yes    # 真推
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SLUG="${PLUGIN_REPO_SLUG:-}"
GO=""
[ "${1:-}" = "--yes" ] && GO=1

if [ -z "$SLUG" ]; then
  echo "没给 PLUGIN_REPO_SLUG(形如 owner/repo)。" >&2
  exit 1
fi

WORK="$ROOT/build/plugin-repo-push"
rm -rf "$WORK"

echo "== 1/5 同步内容 =="
bash "$ROOT/scripts/sync-plugin-repo.sh" >/dev/null
echo "  插件 $(ls -1 "$ROOT/plugin-repo/plugins" | wc -l) 个,索引 $(python -c "
import json,io;print(len(json.load(io.open(r'$ROOT/plugin-repo/registry/index.json',encoding='utf-8'))['plugins']))")
 条"

echo "== 2/5 取远端(保留历史)=="
gh repo clone "$SLUG" "$WORK" -- --quiet
cd "$WORK"

echo "== 3/5 换内容 =="
# 留下 .git 与两样**远端才有、本地不该维护**的东西:
#   · CNAME —— Pages 的自定义域名。设置里也有一份,但删掉文件时 GitHub 可能一并清掉设置,
#     而域名一断,已经发出去的链接全部 404。
#   · .gitattributes —— 换行/二进制规则,和内容无关。
KEEP_CNAME=""
[ -f CNAME ] && KEEP_CNAME="$(cat CNAME)"
KEEP_ATTR=""
[ -f .gitattributes ] && KEEP_ATTR="$(cat .gitattributes)"

git ls-files -z | xargs -0 git rm -q --cached --
find . -mindepth 1 -maxdepth 1 ! -name .git -exec rm -rf {} +

# ☠ 不能 `cp -r`:site/node_modules 有十万个文件、还带 pnpm 的符号链接,
#   拷过来既慢又会一路报 "cannot create symbolic link"。排除掉产物与依赖。
( cd "$ROOT/plugin-repo" && tar --exclude=node_modules --exclude=dist --exclude=.astro     --exclude='*.tgz' -cf - . ) | tar -xf -
# ☠ plugin-repo/.gitignore 是给**主仓库**用的(它忽略 plugins/,因为正本在主仓库)。
#   原样推上去的话官方插件源码一个都进不了远端 —— 而那正是这个仓库的主要内容。
cat > .gitignore <<'IGN'
node_modules/
dist/
.astro/
*.tgz
IGN
[ -n "$KEEP_CNAME" ] && printf '%s\n' "$KEEP_CNAME" > CNAME
[ -n "$KEEP_ATTR" ] && printf '%s\n' "$KEEP_ATTR" > .gitattributes

echo "== 4/5 差异 =="
git add -A
git status --short | head -30
echo "  ..."
echo "  新增 $(git diff --cached --name-only --diff-filter=A | wc -l) / 删除 $(git diff --cached --name-only --diff-filter=D | wc -l) / 改动 $(git diff --cached --name-only --diff-filter=M | wc -l)"

echo "== 5/5 提交并推 =="
if [ -z "$GO" ]; then
  echo "  演练结束,**没有推**。树在 $WORK,可以自己进去看。"
  echo "  确认没问题之后:PLUGIN_REPO_SLUG=$SLUG bash scripts/push-plugin-repo.sh --yes"
  exit 0
fi
# 用本机配好的身份提交,和主仓库的提交是同一个人
git commit -q -F - <<'MSG'
重建:2.0.0 插件系统

原来的内容是旧插件系统(com.linplayer.* 的 id 与旧 schema),和 2.0.0 的新格式
不兼容 —— 新系统从零重做,id 是「作者/名字」,索引是 index.schema.json 那一份。

这一版有:官方插件源码、市场索引、@linplayer/plugin-sdk 与 @linplayer/cli、
Astro 插件墙与开发者站、上架 PR 的校验与自动合并工作流、每个扩展点一个最小示例。

整仓 AGPL-3.0-or-later,和 LinPlayer 主体一致。
MSG
git push origin HEAD:main
echo
echo "推完了。接下来要人做的:"
echo "  · 官方插件的 .lpplugin 还没发 Release —— 索引里的下载地址现在指向 404。"
echo "    包已经打好在 build/lpplugin/,发法见 docs/plugin-system/RELEASE-RUNBOOK.md。"
echo "  · 仓库 Settings → Variables 配 SITE_URL 与 PUBLIC_REPO_URL,Pages 站才建得出绝对地址。"
