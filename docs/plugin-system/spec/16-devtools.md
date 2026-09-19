# 16. 开发者工具链

## 16.1 组成(D13)

TS 类型定义包 `@linplayer/plugin-sdk` + `lp` CLI + 应用内开发者模式 + 桌面调试面板插件 + 示例与文档。

## 16.2 SDK(D146 D147 D207 D264 D411 D514 D526)

- 发布到公共 JS 包仓库;生态**统一用 pnpm**(`lp create` 生成的项目、文档、示例全用 pnpm;文档与界面文案里不出现 npm 命令与「npm 库」说法)。
- 内容:`index.d.ts`(宿主 API 与组件类型)、`manifest.schema.json`(编辑器补全与校验)、JSX 运行时、Preact 渲染器、最小 DOM。
- **单一定义源**:宿主 API 手写在主仓库的 `.d.ts`,注释直接成文档;生成器用 TypeScript 编译器 API 读类型(pnpm 装,只在开发时跑;esbuild 会丢类型,不能用)产出 Go 注册骨架,门禁比对 `.d.ts` 与 Go 两边;随应用发版同步进插件仓库 `packages/sdk`。草稿在本仓库 `docs/plugin-system/api/plugin-sdk.d.ts`。
- **版本号 = 应用版本号**;不承诺兼容,API 改动直接改,写 SDK CHANGELOG。

## 16.3 `lp` CLI(D13 D42 D82 D145 D291 D293 D308 D453 D484)

Go 单文件,复用宿主同一份 esbuild + goja,作者不装 Node 也能用,打包结果与应用内编译一致。分发:pnpm 包 `@linplayer/cli`(按平台装二进制)+ GitHub Releases 单文件。

| 命令 | 作用 |
|---|---|
| `lp create <作者/名字>` | 生成**一个空白 TS 模板**(`manifest.json` + `src/main.tsx` + `package.json` + `tsconfig.json`);各扩展点写法看示例 |
| `lp build` | esbuild 打包成 ES2017 IIFE `main.js` + sourcemap;自动填 `minAppVersion` |
| `lp pack` | 出 `.lpplugin` |
| `lp publish` | 只打包(同 pack),不上传、不提 PR |
| `lp dev` | 监听源码,改了就重建;`--device` 连手机/TV 推送(需在设备上打开「接受推送」并输 6 位配对码);设备上的日志、报错栈、网络请求实时回传到终端(D367) |
| `lp check` | 本地跑一遍与官方 CI 相同的校验 |
| `lp submit` | 生成上架 PR 要改的 index 条目 JSON 片段,供复制 |

不做 `lp test`、`lp devices`、`lp logs`(D317 D484)。

## 16.4 开发者模式(D42 D80 D128 D291 D292 D369 D420 D451 D452)

- 打开方式:**关于页连点版本号 7 次**;打开后设置里出现「开发者」页。
- 「开发者」页:加载本地插件目录(桌面,改文件自动重载,应用内 esbuild 现编 TS)、接受推送(显示配对码)、手机/TV 主题预览、调试 API 开关状态。
- 开发版插件与正式版同 id 时开发版优先(4.9);热重载 = 整个插件重载;出错显示红色错误覆盖层。
- 调试 API(`debug` 命名空间)只在开发者模式存在,对所有插件开放。
- 开发者模式下额外的 warn:列表缺 key、未知组件/属性、patch 应用超 4ms、钩子接近 300ms。

## 16.5 调试面板插件(D80 D81 D367)

官方插件 `linplayer/devtools`,**只有桌面**。四块:
1. **日志**:按 debug / info / warn / error 筛选;报错栈经 sourcemap 映射回 TS 行号。
2. **网络**:URL / 头 / 状态码 / 耗时 / 响应体。
3. **UI 树**:任一 surface 的组件树与属性,选中高亮。
4. **性能与存储**:每次调用耗时、内存增量、KV 查看与编辑。

手机/TV 不做面板,调试走 `lp dev` 回传。

## 16.6 文档与示例(D150 D151 D399 D485 D486)

放在插件仓库 `docs/`,由 Pages 站渲染,中英双语,不上官网。**尽量详细**,至少:
- **快速开始**:10 分钟从 `lp create` 到手机上看到插件;
- **扩展点指南**:每个扩展点一页 —— 能做什么、manifest 写法、最小示例(每页一个示例插件,带「在 LinPlayer 中安装」按钮);
- **API 参考**:从 `.d.ts` 生成;
- **踩坑与限制**:goja 不支持的语法与 API、依赖 Node 内置模块或 DOM 的库跑不了、性能预算(1 秒 / 30 秒 / 300ms)、凭据页不可接管、局域网要声明、主题分端、Canvas 的帧率上限……
- 官方插件本身作为完整示例。
