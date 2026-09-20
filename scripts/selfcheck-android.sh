#!/usr/bin/env bash
# Android 真机 / 模拟器自检。照 scripts/selfcheck-win.sh 的形状。
#
#   bash scripts/selfcheck-android.sh [页面 ...]     # 不给就走全套
#
# 干什么:编核心 → 编 APK → 起假 Emby → 装 → 灌账号 → 逐页直达 → 截图 → 查 logcat。
#
# ☠ **「编译通过」不是交付。** 布局 / 可见性 / 时序这一类只有真渲染才现形:
#   渲染抛错在 Compose 里是**一片空白不报错**、卡片漏了就绪态是**封面隐身**、
#   命令全绿但白名单空是**一张封面都没有**。所以这个脚本的产物是**截图**,
#   不是一行「BUILD SUCCESSFUL」。
#
# ☠ **screencap 抓不到视频层时不要下「没画面」的结论。** 某些合成路径下
#   SurfaceView 的内容不进 framebuffer。判有没有画面要看 dumpsys SurfaceFlinger
#   的图层 + player.* 属性回读 —— 这是 Windows 侧「截图截不到视频层、
#   要用 EnumWindows 量窗口类」的同类问题。
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-${LOCALAPPDATA:-}/Android/Sdk}}"
ADB="$SDK/platform-tools/adb.exe"
[ -x "$ADB" ] || ADB="$SDK/platform-tools/adb"
PKG=xyz.linplayer.app.debug
ACT="$PKG/xyz.linplayer.app.MainActivity"
OUT="$ROOT/build/android-shots"
FAKE_PORT=18096
# 模拟器里访问宿主机是 10.0.2.2;真机要换成同网段地址 —— **别写进任何提交**
HOST="${LP_SELFCHECK_HOST:-10.0.2.2}"

mkdir -p "$OUT"
fail=0
step() { printf '\n\033[1m== %s ==\033[0m\n' "$1"; }
bad()  { fail=$((fail + 1)); echo "  ✗ $1"; }
ok()   { echo "  ✓ $1"; }

# ---- 1. 核心层 ----------------------------------------------------------
step "编核心层(两个 ABI)"
bash scripts/fetch-libmpv-android.sh >/dev/null || { bad "libmpv 拉不到"; exit 1; }
bash scripts/build-core-android.sh || { bad "核心层编不出来"; exit 1; }
ok "liblpcore.so + libmpv.so 就位"

# ---- 2. 设备(要先知道 ABI 才知道装哪个包)------------------------------------------------------------
step "找设备"
if ! "$ADB" devices | grep -qE "device$"; then
  echo "  没有设备。起模拟器:"
  echo "    \"$SDK/emulator/emulator\" -avd lp-phone -no-boot-anim -no-audio -gpu swiftshader_indirect &"
  echo "    \"$ADB\" wait-for-device"
  bad "没有可用设备"
  exit 1
fi
ok "$("$ADB" devices | grep -E 'device$' | head -1)"

# ---- 3. APK -------------------------------------------------------------
step "编 APK"
# ☠ **ABI 要按设备来**,不能用默认的 arm64:x86_64 模拟器上编出 arm64 包,
#   脚本报的是「APK 没出来」—— 那句话看起来像构建失败,其实是找错了文件名。
#   核心层那份 so 也要先按同一个 ABI 编:bash scripts/build-core-android.sh x86_64
ABI="$("$ADB" shell getprop ro.product.cpu.abi 2>/dev/null | tr -d "[:space:]")"
[ -n "$ABI" ] || ABI=arm64-v8a
( cd apps/android && ANDROID_HOME="$SDK" ./gradlew --no-daemon assembleDebug -q "-Plp.abis=$ABI" )   || { bad "assembleDebug 失败"; exit 1; }
APK="$ROOT/apps/android/app/build/outputs/apk/debug/app-$ABI-debug.apk"
[ -f "$APK" ] || APK="$ROOT/apps/android/app/build/outputs/apk/debug/app-debug.apk"
[ -f "$APK" ] || { bad "APK 没出来"; exit 1; }
ok "APK $(( $(stat -c %s "$APK") / 1024 / 1024 )) MB"

# ---- 4. 假 Emby ---------------------------------------------------------
# ☠ -clip-secs 必须给**真实时长**。不给的话它报写死的假片长,
#   一切按百分比算的功能(看完阈值 / 进度条 / 片头片尾跳过)全在对着一个假数验。
step "起假 Emby"
CLIP="${LP_SELFCHECK_CLIP:-$ROOT/build/clip.mp4}"
FAKE_PID=""
# ★ 两条都是踩出来的:
#   ① 超时给足 —— 这个假服务器起手那几次响应能到 3 秒以上,-m 2 会把「在跑」误判成「没起来」
#   ② 用 `-o /dev/null` 而不是 shell 的 `>/dev/null` —— gzip 响应下 shell 重定向
#      会让 curl 以 23(write error)退出,于是「在跑」照样被判成「没起来」
alive() { [ "$(curl -s -m 10 -o /dev/null -w '%{http_code}' "http://127.0.0.1:$FAKE_PORT/System/Info/Public")" = 200 ]; }
if alive; then
  ok "已经有一个在跑,复用"
else
  ( cd core && go build -o "$ROOT/build/fakeemby.exe" ./cmd/fakeemby ) || { bad "fakeemby 编不出来"; exit 1; }
  ARGS=(-addr "0.0.0.0:$FAKE_PORT" -gzip)
  if [ -f "$CLIP" ]; then
    SECS="$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$CLIP" 2>/dev/null | cut -d. -f1)"
    [ -n "$SECS" ] && ARGS+=(-clip "$CLIP" -clip-secs "$SECS")
  fi
  "$ROOT/build/fakeemby.exe" "${ARGS[@]}" > "$ROOT/build/fakeemby.log" 2>&1 &
  FAKE_PID=$!
  # ☠ 起手那几次响应能到几秒,sleep 2 之后只探一次会把「在跑」判成「没起来」——
  #   而后面每一页照样跑得通,于是 fail 里多出一条谁也不会去查的噪音。用 alive() 轮询。
  for _ in $(seq 1 15); do alive && break; sleep 1; done
  alive && ok "起在 $FAKE_PORT" || bad "假 Emby 没起来"
fi
trap '[ -n "$FAKE_PID" ] && kill "$FAKE_PID" 2>/dev/null' EXIT

# ---- 5. 装 + 灌账号 ------------------------------------------------------
step "装 APK 并登录"
"$ADB" install -r "$APK" >/dev/null 2>&1 || { bad "装不上"; exit 1; }
"$ADB" shell pm clear "$PKG" >/dev/null
"$ADB" logcat -c
"$ADB" shell "am start -n $ACT -e lp_login 'http://$HOST:$FAKE_PORT|demo|demo'" >/dev/null
sleep 10
ok "已登录"

# ---- 6. 逐页直达 + 截图 --------------------------------------------------
# 直达用 intent extra,**不用 input tap**:坐标随字号 / 数据变,
# 中间任何一步没点中后面全错位,而截图看起来还像是「那一页做坏了」。
PAGES=("$@")
if [ ${#PAGES[@]} -eq 0 ]; then
  PAGES=(home aggregate servers search favorites downloads
         settings settingsSub:about browse addServer)
fi

shoot() {
  local name="$1"
  "$ADB" exec-out screencap -p > "$OUT/$name.png"
  local sz; sz="$(stat -c %s "$OUT/$name.png" 2>/dev/null || echo 0)"
  # 截图小于 20KB 基本就是纯色一片 —— 那正是「渲染抛错」的样子
  if [ "$sz" -lt 20000 ]; then bad "$name 截图只有 $sz 字节(多半是一片纯色)"; else
    ok "$name → $OUT/$name.png($((sz / 1024)) KB)"; fi
}

step "逐页截图"
for p in "${PAGES[@]}"; do
  if [ "$p" = home ]; then
    "$ADB" shell "am start -n $ACT" >/dev/null
  else
    "$ADB" shell am force-stop "$PKG" >/dev/null
    # LP_DEVPLUGIN=<设备上的目录> 时先用开发版加载它(插件 UI 那几页要用)
    "$ADB" shell "am start -n $ACT -e lp_page '$p'${LP_DEVPLUGIN:+ -e lp_devplugin '$LP_DEVPLUGIN'}" >/dev/null
  fi
  sleep 6
  # ☠ 页面名里的 `/`(插件页是 `pluginpage:作者/名字/页`)不换掉的话,
  #   截图会写进一个不存在的子目录,结果是 0 字节文件 —— 看起来像「画了一片纯色」
  shoot "$(printf '%s' "$p" | tr ':/' '--')"
done

# ☠ 插件 UI 的日志要**在这里**抓走:后面的形变压力那一段会 `logcat -c`,
#   等到第 10 关再读,刚才这几页的打点已经被清掉了。
# 热路径:**不 force-stop**,在同一个进程里再进一次插件页。
# ☠ 上面那个循环每页都先 force-stop,量到的永远是冷路径(带装运行时 + 现编 TS)——
#   而 SPEC 7.12 的 300ms 写的是「插件已加载的前提下」。
if [ -n "${LP_DEVPLUGIN:-}" ]; then
  for p in "${PAGES[@]}"; do
    case "$p" in *pluginpage*)
      "$ADB" shell "am start -n $ACT -e lp_page '$p' -e lp_devplugin '$LP_DEVPLUGIN'" >/dev/null
      sleep 4 ;;
    esac
  done
fi

"$ADB" logcat -d 2>/dev/null > "$ROOT/build/android-plugin.log"

# ---- 7. 崩溃与异常 -------------------------------------------------------
step "查 logcat"
CRASH="$("$ADB" logcat -d -b crash 2>/dev/null | grep -c "FATAL EXCEPTION")"
[ "$CRASH" = 0 ] && ok "没有 FATAL" || bad "有 $CRASH 次 FATAL EXCEPTION"
"$ADB" logcat -d -s LinPlayer 2>/dev/null | grep -E "图片加载失败|图片地址为空" | head -5

# 核心层报的 error 一条都不该有(它是「图片全空」「起播失败」唯一的出口)
COREERR="$("$ADB" logcat -d -s LinPlayer 2>/dev/null | grep -c "核心层:error")"
[ "$COREERR" = 0 ] && ok "核心层没报 error" || bad "核心层报了 $COREERR 条 error"

# ---- 8. 视频层(截图看不到,要问 SurfaceFlinger)---------------------------
step "视频层可见性"
if "$ADB" shell dumpsys SurfaceFlinger --list 2>/dev/null | grep -q "$PKG"; then
  ok "SurfaceFlinger 里有本应用的图层"
else
  echo "  (当前不在播放页,跳过)"
fi

# ---- 9. 形变压力(U1.28)-------------------------------------------------
# ★ 转屏本身**不会**销毁 SurfaceView —— Activity 声明了 configChanges。
#   所以两件事都做:转 100 次(验形变)+ 前后台 40 次(验真正的解绑重绑)。
#   只做前者的话「解绑必须同步阻塞」那条根本没被跑到,测出来是假绿。
step "形变压力 U1.28"
"$ADB" shell settings put system accelerometer_rotation 0 >/dev/null
"$ADB" shell am force-stop "$PKG" >/dev/null
"$ADB" logcat -c
"$ADB" shell "am start -n $ACT -e lp_page 'player:ep-1:stress'" >/dev/null
sleep 6
for _ in $(seq 1 50); do
  "$ADB" shell settings put system user_rotation 1 >/dev/null
  "$ADB" shell settings put system user_rotation 0 >/dev/null
done
for _ in $(seq 1 40); do
  "$ADB" shell input keyevent KEYCODE_HOME >/dev/null
  "$ADB" shell "am start -n $ACT" >/dev/null
done
sleep 3
if [ -n "$("$ADB" shell pidof "$PKG" | tr -d '[:space:]')" ]; then
  ok "转屏 100 次 + 前后台 40 次之后进程还活着"
else
  bad "压力测试后进程没了"
fi
C2="$("$ADB" logcat -d -b crash 2>/dev/null | grep -c "FATAL EXCEPTION")"
[ "$C2" = 0 ] && ok "压力测试期间没有 FATAL" || bad "压力测试期间 $C2 次 FATAL"

# ---- 10. 插件 UI 的数字验收(D543)----------------------------------------
#
# ☠ 这一段**会改退出码**。只 echo 不判的检查躺在日志里没人看 ——
#   「有组件被降级成占位」上一轮就是这么在日志里躺了一整轮。
FIRSTFRAME_MS="${LP_FIRSTFRAME_MS:-300}"
JANK_PCT="${LP_TV_JANK_PCT:-5}"

step "插件 UI:未知组件与首帧(D543)"
LOG="$ROOT/build/android-plugin.log"
[ -f "$LOG" ] || "$ADB" logcat -d 2>/dev/null > "$LOG"
UNK="$(grep -c "未知组件" "$LOG" || true)"
if [ "${UNK:-0}" = 0 ]; then ok "没有组件被降级成占位"; else
  grep "未知组件" "$LOG" | tail -5 | sed 's/^/      /'
  bad "$UNK 次组件被降级成占位(D319 的占位是给「老宿主遇到新组件」的,不是给我们自己没做的)"
fi
# SPEC 7.12 的 300ms 写明「插件已加载的前提下」,所以只判**热路径**那一档;
# 冷的那一次还要装运行时 + 现编 TS,拿它签字等于把门槛放宽到冷路径,热路径就没门槛了。
COLD="$(grep -o "首帧上屏(冷) [0-9]* ms" "$LOG" | grep -o "[0-9]*" | sort -n | tail -1)"
WORST="$(grep -o "首帧上屏(热) [0-9]* ms" "$LOG" | grep -o "[0-9]*" | sort -n | tail -1)"
[ -n "$COLD" ] && echo "    (冷启动那一次 ${COLD} ms,不计入门槛)"
if [ -n "$WORST" ]; then
  if [ "$WORST" -le "$FIRSTFRAME_MS" ]; then ok "插件页首帧(热)最慢 ${WORST} ms(预算 ${FIRSTFRAME_MS} ms)"
  else bad "插件页首帧(热)最慢 ${WORST} ms,超过 ${FIRSTFRAME_MS} ms(SPEC 7.12 D543)"; fi
elif [ -n "${LP_DEVPLUGIN:-}" ]; then
  bad "加载了开发插件却没量到热路径首帧 —— 日志里一条「首帧上屏(热)」都没有,要么打点断了,要么每一页都是冷启动"
fi

# ---- 11. TV 1000 项 VirtualList 帧率(D543)--------------------------------
#
# 判据是 dumpsys gfxinfo 的 janky 比例,不是手敲一次 adb 看一眼的数:
# 手敲的数退化时不会有任何东西变红。≥50fps 等价于一帧 ≤20ms,而 gfxinfo 的
# janky 门槛是 16.7ms —— 比它严,所以「janky 比例低」足以推出 ≥50fps。
if [ -n "${LP_TVJANK:-}" ]; then
  step "TV 1000 项 VirtualList 帧率(D543)"
  "$ADB" shell am force-stop "$PKG" >/dev/null
  "$ADB" shell "am start -n $ACT -e lp_page 'tv:pluginpage:linplayer/devtools/list'${LP_DEVPLUGIN:+ -e lp_devplugin '$LP_DEVPLUGIN'}" >/dev/null
  sleep 8
  "$ADB" shell dumpsys gfxinfo "$PKG" reset >/dev/null 2>&1
  for _ in $(seq 1 "${LP_TVJANK_KEYS:-120}"); do
    "$ADB" shell input keyevent KEYCODE_DPAD_DOWN >/dev/null
  done
  sleep 2
  GFX="$ROOT/build/android-gfxinfo.txt"
  "$ADB" shell dumpsys gfxinfo "$PKG" 2>/dev/null > "$GFX"
  TOTAL="$(grep -o "Total frames rendered: [0-9]*" "$GFX" | grep -o "[0-9]*" | head -1)"
  JANKY="$(grep -o "Janky frames: [0-9]*" "$GFX" | grep -o "[0-9]*" | head -1)"
  if [ -z "$TOTAL" ] || [ "${TOTAL:-0}" -lt 100 ]; then
    # ☠ 帧数太少 = 列表根本没滚起来(整页没有焦点落点时就是这样),
    #   而那种情况下「一帧都没掉」也是真的 —— 不设下限这一关永远绿
    bad "只渲染了 ${TOTAL:-0} 帧,列表没真滚起来 —— 这个数字不算数"
  else
    PCT=$(( JANKY * 100 / TOTAL ))
    if [ "$PCT" -le "$JANK_PCT" ]; then ok "$TOTAL 帧,掉帧 $JANKY 帧($PCT%,门槛 $JANK_PCT%)"
    else bad "$TOTAL 帧里掉了 $JANKY 帧($PCT%,超过 $JANK_PCT%)—— 达不到 ≥50fps"; fi
  fi
fi


printf '\n'
[ "$fail" = 0 ] && { echo "全部通过。截图在 $OUT/"; exit 0; }
echo "$fail 项没过。截图在 $OUT/"
exit 1
