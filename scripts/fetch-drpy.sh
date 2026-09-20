#!/usr/bin/env bash
# 拉 TVBox 插件内置的 drpy 引擎与依赖库(D530 D531),按 sha256 钉版本。
#
#   bash scripts/fetch-drpy.sh           # 缺哪份拉哪份,已有的校验 sha256
#   bash scripts/fetch-drpy.sh --check   # 只校验不下载(门禁用)
#
# ★ 为什么不入库:上游 drpy2.js 的**可执行代码**里写着第三方 OCR 服务的域名与端口,
#   注释里还有私网 IP 和样例私钥 —— 原样提交就踩「任何 IP/域名/端口/密钥不进提交」的红线。
#   上游地址同理不进仓库:放被忽略的 scripts/drpy-source.local(模板见 .example),或环境变量 LP_DRPY_BASE。
# ★ 版本就是 docs/research/plugins-v2/08-runtime-benchmark.md 实测的那一份(3.9.49beta40);
#   换版本要重跑 core/datasource 的 TVBox 全链路测试再改这里的 sha256。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT/plugins/tvbox/assets/drpy"

# 目标路径 | 上游相对路径 | sha256
FILES=(
  "drpy2.js|libs/drpy2.js|c014ac79c5c2b275c2121c33d4155934df244b3b68ce720801e72f8e9b86a85d"
  "lib/cheerio.min.js|libs/cheerio.min.js|10cf39856c496ed2c681c00b6a245b2306a119041591c35d1e31b6bf0a9e6901"
  "lib/crypto-js.js|libs/crypto-js.js|e888d4276fa167121c6fca26dbc9821a8a79b66ae9b192e5a33c6a5bb2056e53"
  "lib/gbk.js|libs/gbk.js|cf46ccf34d32ce873f021fc5e94c43a73afcb7a551004be8d6a02e94986d0696"
  "lib/jsencrypt.js|libs/jsencrypt.js|37346f465953f9e032ffc4ab2aeedc809023fed97eda8d7ad7c0c05afa37c138"
  "lib/模板.js|js/模板.js|1da00bdcb30ab1a7875a84ee6fc82256e705330b6cc9d84144f163df68b665cb"
)

check_only=0
[ "${1:-}" = --check ] && check_only=1

base="${LP_DRPY_BASE:-}"
if [ -z "$base" ] && [ -f "$ROOT/scripts/drpy-source.local" ]; then
  base="$(tr -d '[:space:]' < "$ROOT/scripts/drpy-source.local")"
fi

sha() { sha256sum "$1" | cut -d' ' -f1; }

bad=0
for row in "${FILES[@]}"; do
  IFS='|' read -r dst src want <<<"$row"
  f="$DEST/$dst"
  if [ -f "$f" ] && [ "$(sha "$f")" = "$want" ]; then
    continue
  fi
  if [ $check_only = 1 ] || [ -z "$base" ]; then
    echo "  ✗ $dst 缺失或版本不对(先跑 bash scripts/fetch-drpy.sh;上游地址配在 scripts/drpy-source.local)"
    bad=$((bad + 1))
    continue
  fi
  mkdir -p "$(dirname "$f")"
  curl -fsSL "$base/$src" -o "$f.tmp"
  if [ "$(sha "$f.tmp")" != "$want" ]; then
    rm -f "$f.tmp"
    echo "  ✗ $dst 下载到的内容 sha256 对不上(上游换了版本?)"
    bad=$((bad + 1))
    continue
  fi
  mv "$f.tmp" "$f"
  echo "  ✓ $dst"
done
[ $bad = 0 ] || exit 1

# 规则编辑器也要**自己带一份**(D17 SPEC 17.6:不去调 TVBox 插件)。
# 复制而不是让它引用 TVBox 的目录:打包时各包各自带,装了编辑器没装 TVBox 也能用。
MIRROR="$ROOT/plugins/rule-editor/assets/drpy"
rm -rf "$MIRROR"
mkdir -p "$(dirname "$MIRROR")"
cp -r "$DEST" "$MIRROR"
echo "  ✓ 同一份复制给 plugins/rule-editor"

echo "drpy 引擎与依赖库齐全。"
