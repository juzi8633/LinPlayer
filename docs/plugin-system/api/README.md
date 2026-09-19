# 插件 API 草稿

SPEC 定稿后的第一份接口定义(D329)。实现从这里开始;要改接口,先改这里和 SPEC,再改代码。

| 文件 | 是什么 | 谁用 |
|---|---|---|
| `plugin-sdk.d.ts` | 宿主 API 的**单一定义源**(D146 D514):命名空间、贡献点实现接口、统一数据结构、UI 组件与样式、JSX 工厂 | 插件作者(发布为 `@linplayer/plugin-sdk`);生成器读它产出 Go 注册骨架 |
| `manifest.schema.json` | `manifest.json` 的 JSON Schema(D264) | 编辑器补全、`lp check`、市场 CI |
| `index.schema.json` | 仓库 `index.json` 的 JSON Schema(D119) | 市场客户端、官方仓库 CI |
| `theme-json.schema.json` | 手机/TV 主题 JSON 的 JSON Schema(D215) | Compose 主题加载器、主题作者 |
| `ui-protocol.d.ts` | 核心层 ↔ 壳的 UI 渲染协议(内部契约,插件看不到) | 核心层 `core/plugin/ui`、两个壳的渲染器 |
| `examples/` | 两个用草稿 API 写的真实插件(数据源、直播页 + OSD + 后台任务) | 编译检查:证明 API 写得出插件 |

每个符号都带 `@see Dnnn`,指向 [`../DECISIONS.md`](../DECISIONS.md);逐条落点见 [`../COVERAGE.md`](../COVERAGE.md)。

## 自检命令

```bash
# 1. d.ts 本身与示例插件类型检查(pnpm,不用 npm)
cd docs/plugin-system/api/examples
corepack pnpm --package=typescript@5 dlx tsc -p tsconfig.json

# 2. manifest / index / 主题 schema:示例 manifest 必须通过,坏 manifest 必须被拒
#    (脚本在一个装了 ajv@8 与 ajv-formats@3 的临时目录里跑,见 SPEC 19.5「新增门禁」)

# 3. 决定落点核对:每条决定在 SPEC 有落点,引用的编号都存在
python docs/plugin-system/tools/coverage.py
```

2026-09-20 实跑结果:tsc 0 错误(注入一个未定义类型会报 TS2552,确认检查是真的);两份示例 manifest 通过 schema,一份故意写坏的 manifest(大写 id、非 semver、接管 `server.add`)被拒在 `/id` `/version` `/contributes/pageTakeovers/0/target`;index 与主题样例通过;coverage 549/549 有 SPEC 落点、退出码 0(注入不存在的编号时退出码 1)。
