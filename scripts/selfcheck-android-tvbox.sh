#!/usr/bin/env bash
# 安卓 TVBox 插件真机自检:首页 → 直链起播 → 网页嗅探起播 → jar 源(:spider 进程)起播 → jar 本地代理。
#
#   bash scripts/selfcheck-android-tvbox.sh
#
# 前提:设备 / 模拟器已连上(adb devices);模拟器访问宿主机用 10.0.2.2(LP_SELFCHECK_HOST 覆盖)。
# 数据全是假站编的;判据是截图 + 假站收到的请求(嗅探、jar、视频分片有没有真的被拉)。
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
source scripts/env.sh >/dev/null 2>&1 || true
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-${LOCALAPPDATA:-}/Android/Sdk}}"
ADB="$SDK/platform-tools/adb.exe"; [ -x "$ADB" ] || ADB="$SDK/platform-tools/adb"
PKG=xyz.linplayer.app.debug
ACT="$PKG/xyz.linplayer.app.MainActivity"
HOST="${LP_SELFCHECK_HOST:-10.0.2.2}"
VOD=18097
EMBY=18096
OUT="$ROOT/build/android-shots"
mkdir -p "$OUT"
fail=0
bad() { fail=$((fail + 1)); echo "  ✗ $1"; }
ok()  { echo "  ✓ $1"; }

"$ADB" devices | grep -qE "device$" || { echo "没有设备"; exit 1; }
ABI="$("$ADB" shell getprop ro.product.cpu.abilist | tr -d '[:space:]')"
# 包按设备 ABI 挑:x86_64 模拟器的 ARM 转译跑不了我们的 arm64 核心层(实测 SIGILL),要编 x86_64 那份
DEV_ABI="${ABI%%,*}"

echo "== 编核心层 / APK / demo jar / 假站 =="
# LP_SKIP_BUILD=1:编译已经分步做过了(内存紧时模拟器 + Gradle 一起跑会被系统回收)
if [ -z "${LP_SKIP_BUILD:-}" ]; then
  bash scripts/build-core-android.sh "$DEV_ABI" >/dev/null || { echo "核心层编不出来"; exit 1; }
  ( cd apps/android && ANDROID_HOME="$SDK" ./gradlew --no-daemon assembleDebug -Plp.abis="$DEV_ABI" -q ) || { echo "assembleDebug 失败"; exit 1; }
fi
APK="$(ls apps/android/app/build/outputs/apk/debug/*"$DEV_ABI"*-debug.apk 2>/dev/null | head -1)"
[ -f "$APK" ] || APK=apps/android/app/build/outputs/apk/debug/app-debug.apk
bash scripts/build-spider-demo.sh >/dev/null || { echo "demo jar 编不出来"; exit 1; }
( cd core && go build -o "$ROOT/build/fakevod.exe" ./cmd/fakevod && go build -o "$ROOT/build/fakeemby.exe" ./cmd/fakeemby ) || exit 1
[ -f build/fakehls/index.m3u8 ] || { mkdir -p build/fakehls && ffmpeg -v error -y -i build/clip.mp4 -t 40 -c:v libx264 -preset veryfast -c:a aac \
  -f hls -hls_time 4 -hls_list_size 0 -hls_segment_filename build/fakehls/seg%03d.ts build/fakehls/index.m3u8; }

powershell -NoProfile -Command "Get-Process fakevod,fakeemby -EA SilentlyContinue | Stop-Process -Force" >/dev/null 2>&1 || true
./build/fakevod.exe -addr "0.0.0.0:$VOD" -base "http://$HOST:$VOD" -media build/fakehls -jar build/spider-demo.jar > build/fakevod.log 2>&1 &
V=$!
./build/fakeemby.exe -addr "0.0.0.0:$EMBY" -gzip > build/fakeemby.log 2>&1 &
E=$!
trap 'kill $V $E 2>/dev/null' EXIT
sleep 2

echo "== 装包、登录假 Emby、推插件目录 =="
# Git Bash 会把 /sdcard/... 这种参数自动改写成 Windows 路径,adb 那边就找不到了。
# 只能在编译之后关:核心层的 -L /d/... 正靠这个改写
export MSYS_NO_PATHCONV=1
"$ADB" install -r "$APK" >/dev/null 2>&1 || { echo "装不上"; exit 1; }
"$ADB" shell pm clear "$PKG" >/dev/null
# 通知权限弹窗会压在播放页上(首次起播时申请),自检里先授权掉
"$ADB" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1
# 系统第一次进全屏会弹「Viewing full screen」提示,盖住截图
"$ADB" shell settings put secure immersive_mode_confirmations confirmed >/dev/null 2>&1
"$ADB" shell "am start -n $ACT -e lp_login 'http://$HOST:$EMBY|demo|demo'" >/dev/null
sleep 10
DIR="/sdcard/Android/data/$PKG/files/tvbox"
"$ADB" shell "rm -rf $DIR && mkdir -p $DIR" >/dev/null
# 宿主机上先打好 main.js 再推:目录里没有 src/ 时开发版加载直接读构建产物,不在设备上现场打包
( cd core && go build -o "$ROOT/build/lp.exe" ./cmd/lp ) && ./build/lp.exe build plugins/tvbox >/dev/null || { echo "插件打包失败"; exit 1; }
for f in manifest.json dist/main.js assets; do "$ADB" push "plugins/tvbox/$f" "$DIR/" >/dev/null || bad "推 $f 失败"; done

# 场景:名字、配置、跳到哪、等几秒、假站日志里必须出现的请求
run() {
  local name="$1" cfg="$2" then="$3" wait="$4" want="$5"
  local before; before="$(wc -l < build/fakevod.log)"
  "$ADB" logcat -c
  "$ADB" shell am force-stop "$PKG" >/dev/null
  "$ADB" shell "am start -n $ACT -e lp_page 'tvbox:$DIR|http://$HOST:$VOD/config/$cfg|$then'" >/dev/null
  sleep "$wait"
  "$ADB" exec-out screencap -p > "$OUT/tvbox-$name.png"
  local got; got="$(tail -n +"$((before + 1))" build/fakevod.log)"
  if [ -z "$want" ] || echo "$got" | grep -qE "$want"; then ok "$name:假站收到 ${want:-(不查)}"; else bad "$name:假站没收到 $want"; fi
  # 起播场景:m3u8 之后还得有分片请求 —— 播放器真的在拉流,不是只拿到了地址(模拟器截图常拍不到视频层)
  if [ "$name" != home ]; then
    if echo "$got" | grep -A50 -E "$want" | grep -q "/media/seg"; then ok "$name:播放器在拉分片"; else bad "$name:拿到地址但没拉分片"; fi
  fi
  "$ADB" logcat -d -s LinPlayer 2>/dev/null | grep -E "自检 tvbox" | sed 's/^.*\[自检/  [自检/' | head -3
  if [ "$name" != home ]; then
    if "$ADB" shell dumpsys SurfaceFlinger --list 2>/dev/null | grep -q "SurfaceView"; then ok "$name:有视频图层"; else echo "  没查到视频图层,看截图"; fi
  fi
}

echo "== 场景 =="
run home     plain.json  ""                              14 "/a/api.php/provide/vod/"
run direct   plain.json  "play:假站JSON:山河:星空线路"   22 "/media/a-102-0-0.m3u8"
run sniff    sniff.json  "play:假站JSON:山河:fakeflag"   30 "/media/sniff-102-2-0.m3u8"
run jar      plain.json  "play:jar源:jar:jar线路"        30 "/spider.jar"
grep -q "/media/jar-" build/fakevod.log && ok "jar:起播地址来自 spider 的 playerContent" || bad "jar:没有拉 jar 给的视频"
# 第 2 集走 TVBox 约定的本地代理 127.0.0.1:9978/proxy(D353):代理里的 jar 再去假站取 m3u8
run jarproxy plain.json  "play:jar源:jar:jar线路:2"      30 "/media/jar-proxy-"

C="$("$ADB" logcat -d -b crash 2>/dev/null | grep -c "FATAL EXCEPTION")"
[ "$C" = 0 ] && ok "没有 FATAL" || bad "$C 次 FATAL"
echo
[ "$fail" = 0 ] && echo "全部通过。截图在 $OUT/tvbox-*.png" || echo "$fail 项没过。截图在 $OUT/tvbox-*.png"
exit "$fail"
