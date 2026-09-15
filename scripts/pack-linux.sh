#!/usr/bin/env bash
# 出 Linux 绿色包。界面和 Windows 是**同一份** C# 代码(apps/windows/LinPlayer.Desktop,Avalonia 自己跨平台)。
#
# 和 Windows 包的差别:
#   · libmpv 不进包 —— 运行时 dlopen 系统的(core/player/mpv_dlopen_linux.c,TODO L1)
#   · 包是 zip 不是 tar.gz,资产名带 linux(TODO L7;应用内更新按这个认包)
#
#   bash scripts/pack-linux.sh [输出目录]      # 只能在 Linux 上跑:核心层要本机 cgo 编
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${1:-$ROOT/build/pack-linux}"
APP="$ROOT/apps/windows/LinPlayer.Desktop"
STAGE="$OUT/LinPlayer"

if [ "$(uname -s)" != "Linux" ]; then
  echo "pack-linux.sh 要在 Linux 上跑(当前:$(uname -s))"
  exit 1
fi

source "$ROOT/scripts/env.sh"

export LP_VERSION="${LP_VERSION:-$(tr -d '[:space:]' < "$ROOT/VERSION")-dev}"
echo "版本: $LP_VERSION"

rm -rf "$STAGE"
mkdir -p "$STAGE"

echo "== 1/4 编核心层 =="
bash "$ROOT/scripts/build-core.sh" "$STAGE" >/dev/null
rm -f "$STAGE/liblpcore.h"

echo "== 2/4 发布外壳(self-contained)=="
dotnet publish "$APP" -c Release -r linux-x64 --self-contained true \
  -p:PublishSingleFile=false -p:DebugType=none \
  -o "$STAGE" --nologo -v q >/dev/null
# 丢了可执行位的表现是「解压了双击没反应」
chmod 0755 "$STAGE/LinPlayer"

echo "== 3/4 自检 =="
# L1:libmpv 链进 DT_NEEDED 的话,只有 .so.1 或只有 .so.2 的那一半发行版起都起不来
if readelf -d "$STAGE/liblpcore.so" | grep -q 'libmpv'; then
  echo "liblpcore.so 的 DT_NEEDED 里有 libmpv —— 该走 dlopen"
  exit 1
fi
if ! grep -q 'libmpv.so.2' "$STAGE/liblpcore.so"; then
  echo "liblpcore.so 里找不到 libmpv.so.2 —— dlopen 那份 C 文件没编进去"
  exit 1
fi
# L8:系统硬依赖打进日志,新增一个一眼可见
echo "liblpcore.so 硬依赖:"
readelf -d "$STAGE/liblpcore.so" | awk '/NEEDED/ {print "  " $NF}'
# 界面图标在 Linux 上走 LinIcons,漏编一个码位就是一个豆腐块,而编译照样绿
python3 "$ROOT/scripts/gen-icon-font.py" --check
echo "  图标码位全在 LinIcons 里 ✓"
# 命令行冒烟:壳真的加载得起核心层(不需要 libmpv,也不需要图形环境)
"$STAGE/LinPlayer" version
"$STAGE/LinPlayer" call system.capabilities >/dev/null
echo "  命令行调核心层 ✓"
# 冒烟会在包里建 userdata/ —— 带上去等于把本机数据发给用户
rm -rf "$STAGE/userdata"

echo "== 4/4 打包 =="
# ★ 名字是发布契约:publish.yml 按它捞资产,core/system/update.go 按 linux 关键词认包
ZIP="$OUT/LinPlayer-Linux-v$LP_VERSION.zip"
rm -f "$ZIP"
( cd "$OUT" && zip -qry "$ZIP" LinPlayer )

echo
echo "产物:$ZIP  ($(du -m "$ZIP" | cut -f1) MB)"
