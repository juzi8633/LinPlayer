#!/usr/bin/env bash
# Android 出包。照 scripts/pack-win.sh 的形状。
#
#   bash scripts/pack-android.sh [abi ...]      # 默认只有 arm64-v8a(x86_64 要显式传)
#   bash scripts/pack-android.sh tv             # TV 包 = armeabi-v7a【用户定 2026-09-14】
#
# ★ 32 位包一律叫 `app-tv-armeabi-v7a-release.apk`:应用内更新靠名字里的 `-tv-`
#   认 TV 包(core/system/update.go 的 assetKeywordSetsFor),改名前先改那儿。
#
# ☠ **「编译通过」不是交付。** 这个脚本的判据是「装得上的、已签名的 APK」,
#   而验签必须看**产物本身**:
#     · `META-INF/*.RSA|*.EC|*.DSA` —— v1 证书
#     · "APK Sig Block 42" 魔数 —— v2/v3 签名块
#   ★ `keystore.properties` **写了 ≠ 用了**:release 变体没挂 signingConfig 会
#     静默出 `-unsigned.apk`,它长得和正常包一模一样,直到用户去装才报
#     「安装包无效」。所以下面第 3 步是硬闸门,不是提示。
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-${LOCALAPPDATA:-}/Android/Sdk}}"
OUT="$ROOT/build/android"
ABIS=()
for a in "$@"; do [ "$a" = tv ] && ABIS+=(armeabi-v7a) || ABIS+=("$a"); done
# ★ 默认只编 arm64-v8a【用户定 2026-09-06】。x86_64 只有模拟器用得上,
#   32 位是 TV。要别的 ABI 就当参数传进来,映射表都还在。
[ ${#ABIS[@]} -eq 0 ] && ABIS=(arm64-v8a)
apk_name() { [ "$1" = armeabi-v7a ] && echo "app-tv-$1-release.apk" || echo "app-$1-release.apk"; }

fail=0
bad() { fail=$((fail + 1)); echo "  ✗ $1"; }
ok()  { echo "  ✓ $1"; }

echo "== 1. native =="
bash scripts/fetch-libmpv-android.sh "${ABIS[@]}" >/dev/null || { bad "libmpv 拉不到"; exit 1; }
bash scripts/build-core-android.sh "${ABIS[@]}" >/dev/null || { bad "核心层编不出来"; exit 1; }
ok "liblpcore.so + libmpv.so × ${#ABIS[@]}"

echo "== 2. assembleRelease =="
abis_csv="$(IFS=,; echo "${ABIS[*]}")"
( cd apps/android && ANDROID_HOME="$SDK" ./gradlew --no-daemon assembleRelease -Plp.abis="$abis_csv" -q ) \
  || { bad "assembleRelease 失败"; exit 1; }

mkdir -p "$OUT"
found=0
# ★ 只认这次要出的 ABI:release 目录里可能还躺着上一次别的 ABI 的包,
#   按通配全拷就会把旧包当新包发出去;也不清空 $OUT —— 手机包和 TV 包是分两次出的
for abi in "${ABIS[@]}"; do
  apk="apps/android/app/build/outputs/apk/release/app-$abi-release.apk"
  [ -f "$apk" ] || { bad "没出 $abi 的包:$apk"; continue; }
  found=1
  base="$(apk_name "$abi")"
  cp -f "$apk" "$OUT/$base"

  echo "== 3. 验签 $base =="
  # ☠ 名字里带 unsigned = 没挂 signingConfig。这是硬闸门
  case "$base" in
    *unsigned*) bad "$base 是未签名包 —— release 变体没挂 signingConfig" ; continue ;;
  esac

  if unzip -l "$OUT/$base" | grep -qE 'META-INF/.*\.(RSA|EC|DSA)$'; then
    ok "v1 证书在"
  else
    bad "$base 里没有 META-INF 证书"
  fi

  # v2/v3 的签名块在 ZIP 的 Central Directory 之前,魔数是 "APK Sig Block 42"
  if grep -aqs "APK Sig Block 42" "$OUT/$base"; then
    ok "APK Sig Block 42 在"
  else
    bad "$base 里没有 APK Sig Block 42(v2/v3 签名缺失)"
  fi

  # .so 必须已 strip:不 strip 的话包会从 21MB 涨到 105MB(栽过)
  sz=$(( $(stat -c %s "$OUT/$base") / 1024 / 1024 ))
  echo "  体积 ${sz} MB"
  [ "$sz" -le 60 ] || bad "$base ${sz}MB 超预算(先看 .so 有没有 strip)"
done
[ "$found" = 1 ] || bad "release 目录里一个 APK 都没有"

printf '\n'
[ "$fail" = 0 ] && { echo "出包完成:$OUT"; ls -la "$OUT"; exit 0; }
echo "$fail 项没过。"
exit 1
