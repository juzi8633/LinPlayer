#!/usr/bin/env bash
# 编真机自检用的 TVBox jar 源(core/internal/fakevod/spiderdemo/Demo.java)→ build/spider-demo.jar。
# javac 只对着宿主的 catvod 基类和 android.jar 编;d8 转成 dex 再打进 jar(TVBox jar 就是这个形状)。
#   bash scripts/build-spider-demo.sh
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-${LOCALAPPDATA:-}/Android/Sdk}}"
ANDROID_JAR="$(ls -d "$SDK"/platforms/android-*/android.jar | sort -V | tail -1)"
D8="$(ls -d "$SDK"/build-tools/*/d8* | sort -V | grep -E 'd8(\.bat)?$' | tail -1)"
OUT="$ROOT/build/spider-demo"
rm -rf "$OUT" && mkdir -p "$OUT/classes" "$OUT/dex"
javac -encoding UTF-8 --release 11 -cp "$ANDROID_JAR" -d "$OUT/classes" \
  "$ROOT/apps/android/app/src/main/java/com/github/catvod/crawler/Spider.java" \
  "$ROOT/core/internal/fakevod/spiderdemo/Demo.java" "$ROOT/core/internal/fakevod/spiderdemo/Proxy.java"
# 基类由宿主提供,jar 里只放 spider 自己
"$D8" --min-api 24 --lib "$ANDROID_JAR" --classpath "$OUT/classes" --output "$OUT/dex" \
  "$OUT/classes/com/github/catvod/spider/Demo.class" "$OUT/classes/com/github/catvod/spider/Proxy.class"
( cd "$OUT/dex" && jar cf "$ROOT/build/spider-demo.jar" classes.dex )
echo "build/spider-demo.jar $(stat -c %s "$ROOT/build/spider-demo.jar") 字节"
