# 由出包脚本 source:把崩溃上报用的 DSN 放进 $SENTRY_DSN,给 dotnet publish / gradle 读。
#
# DSN 不进仓库(全局红线:域名 + key 都不许出现在提交里),也不单独配一个 secret ——
# CI 里已有的 SENTRY_AUTH_TOKEN 能从 Sentry API 现查。没 token(本地 / fork)就不带 DSN,
# 程序里 DSN 为空 = SDK 整个不启用,开发机的崩溃不会灌进线上的 issue 列表。
#
# ★ 有 token 却查不到就**构建失败**:否则发行包静默丢掉崩溃上报,而 CI 全绿
#   (memory「CI 漏传编译期凭据」同一类事故)。
SENTRY_ORG="${SENTRY_ORG:-linplayer}"
# 历史原因叫 flutter(移动端先建的)。PC 与安卓都进这一个项目,靠 release 前缀区分
SENTRY_PROJECT="${SENTRY_PROJECT:-flutter}"
export SENTRY_ORG SENTRY_PROJECT

if [ -z "${SENTRY_DSN:-}" ] && [ -n "${SENTRY_AUTH_TOKEN:-}" ]; then
  # 不用 jq:Windows runner 上不保证有
  SENTRY_DSN="$(curl -fsS --retry 3 -H "Authorization: Bearer $SENTRY_AUTH_TOKEN" \
    "https://sentry.io/api/0/projects/$SENTRY_ORG/$SENTRY_PROJECT/keys/" \
    | grep -o '"public": *"[^"]*"' | head -1 | cut -d'"' -f4)" || SENTRY_DSN=""
  case "$SENTRY_DSN" in
    http*) ;;
    *) echo "有 SENTRY_AUTH_TOKEN 却查不到 $SENTRY_ORG/$SENTRY_PROJECT 的 DSN(token 缺 project:read?)" >&2
       exit 1 ;;
  esac
  [ -n "${GITHUB_ACTIONS:-}" ] && echo "::add-mask::$SENTRY_DSN"
fi
export SENTRY_DSN="${SENTRY_DSN:-}"
echo "崩溃上报: $([ -n "$SENTRY_DSN" ] && echo 已启用 || echo 未配置,本包不上报)"
