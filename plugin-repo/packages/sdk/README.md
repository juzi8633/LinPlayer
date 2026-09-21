# @linplayer/plugin-sdk

LinPlayer 插件的类型定义与 JSON Schema。

```bash
pnpm add -D @linplayer/plugin-sdk
```

```ts
import { definePlugin, h, player, nav } from '@linplayer/plugin-sdk'

export default definePlugin({
  commands: {
    async hello() { nav.push('home') },
  },
})
```

**运行时由宿主注入**,这个包只提供类型:装了它不会把任何代码打进你的插件。

- 接口以 `index.d.ts` 为准(它随应用发版同步,D410)。
- `manifest.json` 的 schema 在 `@linplayer/plugin-sdk/manifest.schema.json`,
  编辑器里加一行 `"$schema"` 就有补全与校验。

## 许可证

AGPL-3.0-or-later,和 LinPlayer 主体一致。

**这不要求你的插件也用 AGPL。** 这个包只提供**类型**:`.d.ts` 在构建时被擦掉,
运行时的 SDK 由宿主注入 —— 它**不进你的插件产物**。你的插件用什么许可证由你定。
