# @linplayer/cli

LinPlayer 插件开发命令行。

```bash
pnpm add -D @linplayer/cli
pnpm lp new my-plugin      # 起一个骨架
pnpm lp dev                # 连上运行中的 LinPlayer,改完即时生效
pnpm lp check              # 本地跑一遍上架 CI 的同一套校验
pnpm lp pack               # 出 .lpplugin
pnpm lp submit             # 生成提交到官方索引用的 JSON 片段
```

`lp` 本体是随包装下来的原生二进制(逻辑在 LinPlayer 主仓库的 Go 代码里)。
拉不下来时这个包**不会让安装失败**,它只少一个工具 —— 自己从 Releases 下一个放进 PATH 也行。

许可证 AGPL-3.0-or-later,和 LinPlayer 主体一致。
