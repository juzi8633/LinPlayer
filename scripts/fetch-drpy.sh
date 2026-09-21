#!/usr/bin/env bash
# 拉 TVBox 插件内置的 drpy 引擎与依赖库(D530 D531),按 sha256 钉版本。
#
#   bash scripts/fetch-drpy.sh           # 缺了就拉,已有的校验 sha256
#   bash scripts/fetch-drpy.sh --check   # 只校验不下载(门禁用)
#
# drpy 是解析 TVBox **JS 源(type 3)**的引擎 —— 那类源本身就是一段 JS 规则,
# 得有引擎跑它才出得来列表和播放地址。没有它 type 3 的源一个都打不开。
#
# ★ 为什么不入库:drpy2.js 的**可执行代码**里写着第三方 OCR 服务的域名与端口,
#   注释里还有私网 IP 和样例私钥 —— 原样提交就踩「任何 IP/域名/端口/密钥不进提交」的红线。
# ★ 为什么从**我们自己的**插件仓库拉,不从上游 hjdhnx/dr_py 拉(D572):
#   构建期依赖一个别人的仓库,人家删库改名我们就趴窝。这一份是那个上游某个提交的
#   副本,和官方插件包同一条 Release,由 scripts/release-plugins.sh 一起传上去。
# ★ 版本就是 docs/research/plugins-v2/08-runtime-benchmark.md 实测的那一份(3.9.49beta40);
#   换版本要重跑 core/datasource 的 TVBox 全链路测试再改这里的 sha256。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT/plugins/tvbox/assets/drpy"

REPO="${PLUGIN_REPO_SLUG:-zzzwannasleep/LinplayerPluginsRepository}"
TAG="${PLUGIN_RELEASE_TAG:-plugins}"
# 换镜像时可以用 LP_DRPY_URL 顶掉(sha256 照验,顶错了下不出东西)
URL="${LP_DRPY_URL:-https://github.com/$REPO/releases/download/$TAG/drpy-engine.tar.gz}"

# 包内路径 | sha256。**不钉整包的 sha**:tar 每打一次时间戳都不同,钉了下次发版自己就红。
# 真正的保证是解开之后逐份比对 —— 整包 sha 只说明「下对了文件」,
# 说不了「里面每一份都是当初实测的那一份」。
FILES=(
  "drpy2.js|c014ac79c5c2b275c2121c33d4155934df244b3b68ce720801e72f8e9b86a85d"
  "lib/cheerio.min.js|10cf39856c496ed2c681c00b6a245b2306a119041591c35d1e31b6bf0a9e6901"
  "lib/crypto-js.js|e888d4276fa167121c6fca26dbc9821a8a79b66ae9b192e5a33c6a5bb2056e53"
  "lib/gbk.js|cf46ccf34d32ce873f021fc5e94c43a73afcb7a551004be8d6a02e94986d0696"
  "lib/jsencrypt.js|37346f465953f9e032ffc4ab2aeedc809023fed97eda8d7ad7c0c05afa37c138"
  "lib/模板.js|1da00bdcb30ab1a7875a84ee6fc82256e705330b6cc9d84144f163df68b665cb"
)

check_only=0
[ "${1:-}" = --check ] && check_only=1

sha() { sha256sum "$1" | cut -d' ' -f1; }

verify() {
  local bad=0 row dst want
  for row in "${FILES[@]}"; do
    IFS='|' read -r dst want <<<"$row"
    if [ ! -f "$DEST/$dst" ] || [ "$(sha "$DEST/$dst")" != "$want" ]; then
      echo "  ✗ $dst 缺失或版本不对"
      bad=$((bad + 1))
    fi
  done
  return $bad
}

# 先静默探一次:缺了就去拉,拉之前把一串 ✗ 打出来只会让人以为出事了
if verify >/dev/null 2>&1; then
  :
elif [ $check_only = 1 ]; then
  verify
  echo "跑一次 bash scripts/fetch-drpy.sh 把它们拉下来。" >&2
  exit 1
else
  echo "  取 $TAG/drpy-engine.tar.gz"
  # ☠ 落盘路径全程**纯 ASCII**:curl 与 tar 是原生 Windows 程序,MSYS 把 UTF-8 路径
  #   交给它们时按系统 ANSI 代码页转,带中文的那份会落在乱码名字上 —— 下载明明成功,
  #   而 sha256sum 报 "No such file or directory"。解包目录也一样,所以解到 build/ 下
  #   再整目录搬过去。
  WORK="$ROOT/build/drpy"
  rm -rf "$WORK"
  mkdir -p "$WORK"
  curl -fsSL "$URL" -o "$WORK/engine.tar.gz"
  tar -xzf "$WORK/engine.tar.gz" -C "$WORK"
  rm -f "$WORK/engine.tar.gz"
  rm -rf "$DEST"
  mkdir -p "$(dirname "$DEST")"
  mv "$WORK" "$DEST"
  if ! verify; then
    echo "包里的文件和钉住的 sha256 对不上 —— 别用。" >&2
    exit 1
  fi
  echo "  ✓ 六份齐全,sha256 逐份对上"
fi

# 规则编辑器也要**自己带一份**(D17 SPEC 17.6:不去调 TVBox 插件)。
# 复制而不是让它引用 TVBox 的目录:打包时各包各自带,装了编辑器没装 TVBox 也能用。
MIRROR="$ROOT/plugins/rule-editor/assets/drpy"
rm -rf "$MIRROR"
mkdir -p "$(dirname "$MIRROR")"
cp -r "$DEST" "$MIRROR"
echo "  ✓ 同一份复制给 plugins/rule-editor"

echo "drpy 引擎与依赖库齐全。"
