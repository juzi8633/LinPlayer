#!/usr/bin/env bash
# 核心层门禁。推之前跑这个。
#
#   bash scripts/check-core.sh
#
# 五关,任意一关红就退非零:
#   1. go vet + go test        —— 核心层单测
#   2. 出库                    —— c-shared 编得出来
#   3. FFI 契约                —— 生成的头文件与 SPEC §5.1 逐条一致
#   4. 契约测试(C# 宿主侧)   —— 35 条判据,覆盖 §5.0/§5.2/§5.3/§5.4/§5.7/§5.10/§5.11
#   5. 差分对账              —— Go 侧输出与黄金实现(Rust)逐字段一致
#   6. lp check              —— 真跑一次 CLI(不是只比 schema),外加一条必须红的坏包
#   7. SDK 注册骨架           —— .d.ts 重新生成一遍,产物必须和仓库里那份一样(D514)
#   8. UI 运行时              —— Preact + 最小 DOM 的打包产物同样要跟上源文件(D23)
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=/dev/null
source "$ROOT/scripts/env.sh"

fail=0
step() { echo; echo "===== $* ====="; }

# TVBox 全链路测试要用插件内置的 drpy 引擎:缺了就拉(配了上游地址时),拉不到直接算这一关红
bash "$ROOT/scripts/fetch-drpy.sh" || fail=$((fail + 1))
step "1. go vet + go test"
# ★ libmpv 要在 DLL 搜索路径上:core/player 是 cgo 包,它的测试二进制起不来就是
#   0xc0000135(找不到 DLL),而那个错误看起来像「测试失败」而不是「环境不对」。
#   2026-08-31 真绊过一次:之前一直是 go 的测试缓存在挡着,包一改动就现形。
( cd "$ROOT/core" && PATH="$ROOT/third_party/libmpv:$PATH" go vet ./...   && PATH="$ROOT/third_party/libmpv:$PATH" go test ./... ) || fail=$((fail + 1))

step "2. 出库"
bash "$ROOT/scripts/build-core.sh" >/dev/null || fail=$((fail + 1))

step "3. FFI 契约(头文件 vs SPEC §5.1)"
python "$ROOT/scripts/check-ffi-contract.py" || fail=$((fail + 1))

step "4. 契约测试(C# 宿主侧)"
CHECK="$ROOT/tools/corecheck/bin/Release/net10.0/corecheck.exe"
if [ ! -x "$CHECK" ]; then
  ( cd "$ROOT/tools/corecheck" && dotnet build -c Release --nologo >/dev/null )
fi
# LP_DEBUG_CMDS=1 才有 debug.* —— panic 边界那三条判据靠它们
LP_DEBUG_CMDS=1 "$CHECK" "$ROOT/build/core/lpcore.dll" | tail -20
rc=${PIPESTATUS[0]}
[ "$rc" = "0" ] || fail=$((fail + 1))

step "5. 差分对账(vs 黄金实现)"
( cd "$ROOT/core" && go run ./cmd/diffcheck ) || fail=$((fail + 1))

step "6. lp check(真跑一次 CLI)"
# ★ 只比 schema 的测试(TestSchemaCopiesInSync)看不见 CLI 本身 —— 它还要打包、解压、
#   起运行时、跑 activate、对贡献点。这一关跑的是**真的 lp check**,不是它的某个函数。
( cd "$ROOT/core" && go run ./cmd/lp check "$ROOT/plugins/tvbox" ) || fail=$((fail + 1))

# ☠ **反向用例不许省**:只跑「好包应该绿」的话,check 里任何一条判据被注释掉都照样全绿。
#   这里故意给一个 id 不合规的 manifest,lp check 必须非零退出。
BADDIR="$(mktemp -d)"
cat > "$BADDIR/manifest.json" <<'BAD'
{ "id": "NotAValid Id", "name": "坏包", "version": "1.0.0", "main": "index.js" }
BAD
if ( cd "$ROOT/core" && go run ./cmd/lp check "$BADDIR" >/dev/null 2>&1 ); then
  echo "  ✗ 坏 manifest 竟然通过了 lp check —— 这一关等于没有"
  fail=$((fail + 1))
else
  echo "  ✓ 坏 manifest 被挡下"
fi
rm -rf "$BADDIR"

step "7. SDK 注册骨架是否最新(D514)"
# 生成器用 TypeScript 编译器 API 读 api/plugin-sdk.d.ts,产物 core/plugin/rt/sdkspec_gen.go
# 进仓库;逐名比对由 rt/sdk_contract_test.go 在第 1 关做。这里只管**产物有没有跟上定义源** ——
# 改了 .d.ts 忘了重跑生成器的话,比对用的还是旧骨架,那一关就成了自欺。
if command -v pnpm >/dev/null 2>&1; then
  if [ ! -d "$ROOT/tools/sdkgen/node_modules" ]; then ( cd "$ROOT/tools/sdkgen" && pnpm install --silent ); fi
  # ☠ **不要用 `git diff` 判**:产物第一次加进来时是未跟踪文件,git diff 对它一声不吭,
  #   这一关就永远绿 —— 2026-09-20 写完当场踩到。生成到临时文件再 cmp,和 git 状态无关。
  GEN_TMP="$(mktemp)"
  # 直接 node 跑:pnpm 只负责装 typescript,多包一层 `pnpm gen --` 会把 `--` 也当成参数传进去
  if node "$ROOT/tools/sdkgen/gen.mjs" --out "$GEN_TMP" >/dev/null; then
    if cmp -s "$GEN_TMP" "$ROOT/core/plugin/rt/sdkspec_gen.go"; then
      echo "  ✓ 与 plugin-sdk.d.ts 一致"
    else
      echo "  ✗ sdkspec_gen.go 落后于 plugin-sdk.d.ts —— 跑 \`pnpm --dir tools/sdkgen gen\` 再提交"
      diff -u "$ROOT/core/plugin/rt/sdkspec_gen.go" "$GEN_TMP" | head -20
      fail=$((fail + 1))
    fi
  else
    echo "  ✗ 生成器跑不起来"
    fail=$((fail + 1))
  fi
  rm -f "$GEN_TMP"
else
  # ☠ 不许静默跳过:跳过的门禁和不存在的门禁是一回事,至少要让人看见它没跑
  echo "  ⚠ 没有 pnpm,这一关没跑(装:npm i -g pnpm)"
fi

step "8. UI 运行时产物是否最新(D23)"
# uiruntime.js 里有 Preact 本体,Go 侧 //go:embed 它 —— 进仓库是为了「编 Go 不需要 node」。
# 改了 tools/uibundle/src/*.js 却忘了重打包的话,跑的还是旧渲染器,
# 而 ui_test.go 测的也是旧的那份:两边一起过时,门禁全绿。
if command -v pnpm >/dev/null 2>&1; then
  if [ ! -d "$ROOT/tools/uibundle/node_modules" ]; then ( cd "$ROOT/tools/uibundle" && pnpm install --silent ); fi
  UI_TMP="$(mktemp)"
  if node "$ROOT/tools/uibundle/build.mjs" --out "$UI_TMP" >/dev/null; then
    if cmp -s "$UI_TMP" "$ROOT/core/plugin/rt/uiruntime.js"; then
      echo "  ✓ 与 tools/uibundle/src 一致"
    else
      echo "  ✗ uiruntime.js 落后于 tools/uibundle/src —— 跑 \`pnpm --dir tools/uibundle build\` 再提交"
      fail=$((fail + 1))
    fi
  else
    echo "  ✗ 打包器跑不起来"
    fail=$((fail + 1))
  fi
  rm -f "$UI_TMP"
else
  echo "  ⚠ 没有 pnpm,这一关没跑(装:npm i -g pnpm)"
fi

echo
if [ $fail -eq 0 ]; then echo "核心层门禁:全部通过。"; else echo "核心层门禁:$fail 关不通过。"; fi
exit $fail
