#!/usr/bin/env bash
# 拉 TVBox 插件内置的 drpy 引擎与依赖库(D530 D531),按 sha256 钉版本。
#
#   bash scripts/fetch-drpy.sh           # 缺哪份拉哪份,已有的校验 sha256
#   bash scripts/fetch-drpy.sh --check   # 只校验不下载(门禁用)
#
# ★ 为什么文件本身不入库:上游 drpy2.js 的**可执行代码**里写着第三方 OCR 服务的域名与端口,
#   注释里还有私网 IP 和样例私钥 —— 原样提交就踩「任何 IP/域名/端口/密钥不进提交」的红线。
#   **地址不一样**:它是一个公开开源项目的 raw 地址,谁都查得到,拿去也只能下到同一份
#   开源代码。写死在这儿(D571),不走 Secret —— 藏一个人人可见的地址只会让 CI 少配一个
#   变量就静默失灵。
# ★ 版本就是 docs/research/plugins-v2/08-runtime-benchmark.md 实测的那一份(3.9.49beta40);
#   换版本要重跑 core/datasource 的 TVBox 全链路测试再改这里的 sha256。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT/plugins/tvbox/assets/drpy"

# 钉住提交:main 上的文件会变,不钉的话 sha256 明天就对不上了
BASE_DEFAULT="https://raw.githubusercontent.com/hjdhnx/dr_py/321a6426a598bd4b4526ee7b7b297841457873dc"

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

# 换镜像时可以用 LP_DRPY_BASE 顶掉(sha256 照验,顶错了下不出东西)
base="${LP_DRPY_BASE:-$BASE_DEFAULT}"

sha() { sha256sum "$1" | cut -d' ' -f1; }

# 路径里有中文(js/模板.js)。curl 原样发 UTF-8 字节时 raw.githubusercontent 回 404 ——
# 看起来像「上游没有这个文件」,而它就在那儿。非 ASCII 字节要自己转成 %XX。
urlenc() {
  local s="$1" out="" c
  local LC_ALL=C
  for (( i = 0; i < ${#s}; i++ )); do
    c="${s:i:1}"
    case "$c" in
      [a-zA-Z0-9.~_/-]) out+="$c" ;;
      *) out+="$(printf '%%%02X' "'$c")" ;;
    esac
  done
  printf '%s' "$out"
}

bad=0
for row in "${FILES[@]}"; do
  IFS='|' read -r dst src want <<<"$row"
  f="$DEST/$dst"
  if [ -f "$f" ] && [ "$(sha "$f")" = "$want" ]; then
    continue
  fi
  if [ $check_only = 1 ]; then
    echo "  ✗ $dst 缺失或版本不对(跑一次 bash scripts/fetch-drpy.sh)"
    bad=$((bad + 1))
    continue
  fi
  mkdir -p "$(dirname "$f")"
  # ☠ 落盘路径先用**纯 ASCII 的临时名**,再用 mv 改名。
  #   curl 是原生 Windows 程序,MSYS 把 UTF-8 路径交给它时按系统 ANSI 代码页转,
  #   带中文的那份会落在一个乱码名字上 —— 下载是成功的,而 sha256sum 找不到文件,
  #   报出来的是「上游换了版本?」。mv 是 MSYS 的,和 sha256sum 同一套编码。
  TMP="$DEST/.fetch.tmp"
  curl -fsSL "$base/$(urlenc "$src")" -o "$TMP"
  if [ "$(sha "$TMP")" != "$want" ]; then
    rm -f "$TMP"
    echo "  ✗ $dst 下载到的内容 sha256 对不上(上游换了版本?)"
    bad=$((bad + 1))
    continue
  fi
  mv "$TMP" "$f"
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
