# 由出包脚本 source:确认崩溃上报用的 $SENTRY_DSN(CI 的同名 secret),给 dotnet publish / gradle 读。
#
# DSN 不进仓库(全局红线:域名 + key 都不许出现在提交里)。没配(本地 / fork)= 程序里 DSN 为空,
# SDK 整个不启用,开发机的崩溃不会灌进线上的 issue 列表。
# 不需要 SENTRY_AUTH_TOKEN:PC 把 pdb 编进 dll、安卓关掉 R8 改名,堆栈在本机就可读,不用传符号。
export SENTRY_DSN="${SENTRY_DSN:-}"
if [ -n "$SENTRY_DSN" ]; then
  echo "崩溃上报: 已启用"
elif [ -n "${GITHUB_ACTIONS:-}" ]; then
  # 漏配没有任何运行时信号,只能在 Actions 页挂出来
  echo "::warning::没配 SENTRY_DSN secret,本包不带崩溃上报"
else
  echo "崩溃上报: 未配置,本包不上报"
fi
