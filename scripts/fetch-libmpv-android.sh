#!/usr/bin/env bash
# 拉 Android 的 libmpv(每个 ABI 一包),落到 third_party/libmpv/android/<abi>/。
#
#   bash scripts/fetch-libmpv-android.sh [abi ...]     # 默认只有 arm64-v8a
#
# 产物**不进版本库**(.gitignore),和 Windows 侧 libmpv-2.dll 一个待遇:
# 大二进制由脚本现拉,CI 也跑这个脚本。
#
# 来源是本仓库 .github/workflows/libmpv-android.yml 自编的包(发在 Release `libmpv-android-<n>`)。
# 2026-09-17 以前用 media-kit 的成品:mpv 0.36 + `--disable-filters`,补帧滤镜没地方加,
# 副字幕延迟/位置也一直静默没生效。mpv 官方没有安卓 libmpv 成品,所以自己编。
#
# 包里是一组 .so:libmpv + ffmpeg 各库(官方 mpv-android 脚本把 ffmpeg 编成动态库)+ libc++_shared。
# 全部要进 APK —— 少一个就是装上去 dlopen 失败,而 APK 照样打得出来。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TAG="${LP_LIBMPV_TAG:-libmpv-android-3}"
BASE="https://github.com/zzzwannasleep/LinPlayer/releases/download/$TAG"
DEST="$ROOT/third_party/libmpv/android"
ABIS=("$@")
# ★ 默认只编 arm64-v8a【用户定 2026-09-06】。32 位留给 TV。
#   x86_64(模拟器)自编流程没出,要用得先在 workflow 里加这个 arch。
[ ${#ABIS[@]} -eq 0 ] && ABIS=(arm64-v8a)

# sha256 钉死在这里:换包 = 改 TAG + 这两行。只信 Release 里的 SHA256SUMS 等于没校验
sha_of() {
  case "$1" in
    arm64-v8a)   echo "4e9cfaf7642f8f77a94bd8fa2a0601ccc920e8be1ffac863e2213362b15b4339" ;;
    armeabi-v7a) echo "42e392838f992eb2b607cf93806446265795eff4f67134e4f4cc86e3abed42a9" ;;
    *) echo "" ;;
  esac
}

# ELF 机器类型:下错 ABI 是「装得上、一跑就 UnsatisfiedLinkError」,逐个校验。
elf_machine() {
  case "$1" in
    arm64-v8a)   echo "AArch64" ;;
    armeabi-v7a) echo "ARM" ;;
    *) echo "?" ;;
  esac
}

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

for abi in "${ABIS[@]}"; do
  want_sha="$(sha_of "$abi")"
  [ -n "$want_sha" ] || { echo "!! 自编 libmpv 没有 $abi(见 .github/workflows/libmpv-android.yml)"; exit 1; }
  mark="$DEST/$abi/.tag"
  if [ -f "$mark" ] && [ "$(cat "$mark")" = "$TAG" ] && [ "${LP_LIBMPV_FORCE:-0}" != "1" ]; then
    echo "已有 $abi($TAG),跳过。LP_LIBMPV_FORCE=1 可强拉"
    continue
  fi
  pkg="libmpv-android-$abi.tar.gz"
  echo "== 拉 $TAG/$pkg =="
  curl -fL --retry 3 -o "$tmp/$pkg" "$BASE/$pkg"
  got="$(sha256sum "$tmp/$pkg" | cut -d' ' -f1)"
  [ "$got" = "$want_sha" ] || { echo "!! $pkg sha256 对不上:$got(应为 $want_sha)"; exit 1; }

  # 整个目录换掉:上一版留下的旧 so(比如 media-kit 那颗单文件 libmpv)混进来,打包时会一起进 APK
  rm -rf "$DEST/$abi"
  mkdir -p "$DEST/$abi"
  tar -xzf "$tmp/$pkg" -C "$DEST/$abi"

  want="$(elf_machine "$abi")"
  for so in "$DEST/$abi"/*.so; do
    head -c 4 "$so" | od -An -tx1 | tr -d ' \n' | grep -qi '^7f454c46$' \
      || { echo "!! $so 不是 ELF"; exit 1; }
    if command -v readelf >/dev/null 2>&1; then
      readelf -h "$so" | grep -q "$want" || { echo "!! $so 的机器类型不是 $want"; exit 1; }
    fi
  done
  echo "$TAG" > "$mark"
  echo "   -> $DEST/$abi($(ls "$DEST/$abi"/*.so | wc -l) 个 so,$want)"
done

echo "完成。libmpv 不入版本库,构建前跑本脚本。"
